"""Podcast search, feed resolve, and download helpers."""

from __future__ import annotations

import asyncio
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Optional
from urllib.parse import urlparse

import feedparser
import httpx

ITUNES_SEARCH = "https://itunes.apple.com/search"
ITUNES_LOOKUP = "https://itunes.apple.com/lookup"
# Browser-like UA: some hosts (e.g. 小宇宙 feed.xyzfm.space) 403 identifiable bots.
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

# Windows-illegal filename chars + control chars
_ILLEGAL = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
_WS = re.compile(r"\s+")


def sanitize_filename(name: str, max_len: int = 120) -> str:
    name = (name or "untitled").strip()
    name = _ILLEGAL.sub("", name)
    name = _WS.sub(" ", name).strip(" .")
    # Windows reserved device names
    reserved = {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        *(f"COM{i}" for i in range(1, 10)),
        *(f"LPT{i}" for i in range(1, 10)),
    }
    if name.upper() in reserved:
        name = f"_{name}"
    if not name:
        name = "untitled"
    if len(name) > max_len:
        name = name[:max_len].rstrip(" .")
    return name


AUDIO_EXTS = (".mp3", ".m4a", ".mp4", ".aac", ".ogg", ".opus", ".wav", ".flac")
INDEX_NAME = ".podbatch-index.json"
# RSS often omits enclosure length. A real episode is far larger than an error page.
MIN_COMPLETE_BYTES = 32 * 1024


def guess_ext(url: str, content_type: str | None = None) -> str:
    path = urlparse(url).path.lower()
    for ext in AUDIO_EXTS:
        if path.endswith(ext):
            return ext
    if content_type:
        ct = content_type.lower()
        if "mpeg" in ct or "mp3" in ct:
            return ".mp3"
        if "mp4" in ct or "m4a" in ct or "aac" in ct:
            return ".m4a"
        if "ogg" in ct:
            return ".ogg"
        if "opus" in ct:
            return ".opus"
        if "wav" in ct:
            return ".wav"
        if "flac" in ct:
            return ".flac"
    return ".mp3"


def _index_path(folder: Path) -> Path:
    return folder / INDEX_NAME


def read_index(folder: Path) -> dict[str, Any]:
    path = _index_path(folder)
    if not path.exists():
        return {"version": 1, "episodes": {}}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"version": 1, "episodes": {}}
    if not isinstance(data, dict):
        return {"version": 1, "episodes": {}}
    eps = data.get("episodes")
    if not isinstance(eps, dict):
        data["episodes"] = {}
    return data


def write_index(folder: Path, data: dict[str, Any]) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    path = _index_path(folder)
    tmp = folder / f"{INDEX_NAME}.tmp"
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _index_keys(ep: Episode) -> list[str]:
    keys: list[str] = []
    for raw in (ep.guid, ep.audio_url, sanitize_filename(ep.title, max_len=160)):
        k = (raw or "").strip()
        if k and k not in keys:
            keys.append(k)
    return keys


def is_complete_file(path: Path, expected_size: int = 0) -> bool:
    """True if path looks like a finished episode, not a failed/partial stub."""
    try:
        st = path.stat().st_size
    except OSError:
        return False
    if st <= 0:
        return False
    if expected_size and expected_size > 0:
        return st >= int(expected_size * 0.98)
    return st >= MIN_COMPLETE_BYTES


def find_existing_file(folder: Path, ep: Episode) -> Optional[Path]:
    """Locate a previously saved file by index (guid/url) or same title + any audio ext."""
    if not folder.exists():
        return None
    index = read_index(folder)
    records = index.get("episodes") or {}
    for key in _index_keys(ep):
        rec = records.get(key)
        if not isinstance(rec, dict):
            continue
        name = str(rec.get("file") or "").strip()
        if not name:
            continue
        path = folder / Path(name).name
        if path.exists() and path.stat().st_size > 0:
            return path

    base = sanitize_filename(ep.title, max_len=160)
    if not base:
        return None
    found: list[Path] = []
    for ext in AUDIO_EXTS:
        path = folder / f"{base}{ext}"
        if path.exists() and path.stat().st_size > 0:
            found.append(path)
    if not found:
        return None
    found.sort(key=lambda p: p.stat().st_size, reverse=True)
    return found[0]


def expected_size_for(folder: Path, ep: Episode, dest: Optional[Path] = None) -> int:
    if ep.size and ep.size > 0:
        return int(ep.size)
    records = (read_index(folder).get("episodes") or {})
    for key in _index_keys(ep):
        rec = records.get(key)
        if isinstance(rec, dict):
            try:
                sz = int(rec.get("size") or 0)
            except (TypeError, ValueError):
                sz = 0
            if sz > 0:
                return sz
    return 0


def remember_local_file(folder: Path, ep: Episode, dest: Path) -> None:
    """Record guid/title → filename so later runs skip even if the URL/ext changes."""
    if not dest.exists():
        return
    try:
        size = dest.stat().st_size
    except OSError:
        return
    if size <= 0:
        return
    data = read_index(folder)
    rec = {
        "title": ep.title,
        "file": dest.name,
        "size": size,
        "complete": is_complete_file(dest, ep.size),
        "guid": ep.guid or "",
    }
    episodes = data.setdefault("episodes", {})
    for key in _index_keys(ep):
        episodes[key] = rec
    write_index(folder, data)


def episode_local_status(out_dir: Path, show_name: str, ep: Episode) -> dict[str, Any]:
    folder = Path(out_dir) / sanitize_filename(show_name)
    path = find_existing_file(folder, ep)
    if not path:
        return {
            "downloaded": False,
            "partial": False,
            "local_path": "",
            "local_size": 0,
        }
    size = path.stat().st_size
    complete = is_complete_file(path, expected_size_for(folder, ep, path))
    return {
        "downloaded": complete,
        "partial": (not complete) and size > 0,
        "local_path": str(path),
        "local_size": size,
    }


def parse_date(value: Any) -> str:
    if not value:
        return ""
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d")
    # feedparser time_struct
    try:
        if hasattr(value, "tm_year"):
            return f"{value.tm_year:04d}-{value.tm_mon:02d}-{value.tm_mday:02d}"
    except Exception:
        pass
    text = str(value)
    m = re.search(r"(\d{4})-(\d{2})-(\d{2})", text)
    if m:
        return m.group(0)
    m = re.search(r"(\d{1,2})\s+(\w{3})\s+(\d{4})", text)
    if m:
        try:
            dt = datetime.strptime(m.group(0), "%d %b %Y")
            return dt.strftime("%Y-%m-%d")
        except ValueError:
            pass
    return ""


@dataclass
class Show:
    id: str
    name: str
    author: str = ""
    artwork: str = ""
    feed_url: str = ""
    episode_count: int = 0
    country: str = ""
    rank: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Episode:
    index: int
    title: str
    audio_url: str
    published: str = ""
    duration: str = ""
    guid: str = ""
    size: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DownloadItem:
    episode: Episode
    path: str = ""
    status: str = "pending"  # pending|running|done|error|skipped
    bytes_done: int = 0
    bytes_total: int = 0
    error: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.episode.index,
            "title": self.episode.title,
            "path": self.path,
            "status": self.status,
            "bytes_done": self.bytes_done,
            "bytes_total": self.bytes_total,
            "error": self.error,
        }


@dataclass
class DownloadJob:
    id: str
    show_name: str
    out_dir: str
    items: list[DownloadItem] = field(default_factory=list)
    status: str = "queued"  # queued|running|done|error|cancelled
    message: str = ""

    def to_dict(self) -> dict[str, Any]:
        done = sum(1 for i in self.items if i.status in ("done", "skipped"))
        failed = sum(1 for i in self.items if i.status == "error")
        return {
            "id": self.id,
            "show_name": self.show_name,
            "out_dir": self.out_dir,
            "status": self.status,
            "message": self.message,
            "total": len(self.items),
            "done": done,
            "failed": failed,
            "items": [i.to_dict() for i in self.items],
        }


def _client(**kwargs: Any) -> httpx.AsyncClient:
    timeout = kwargs.pop("timeout", httpx.Timeout(60.0, connect=30.0))
    return httpx.AsyncClient(
        headers={"User-Agent": USER_AGENT},
        follow_redirects=True,
        timeout=timeout,
        **kwargs,
    )


async def _get_json(url: str, params: dict[str, Any] | None = None) -> Any:
    """GET JSON with a couple retries (proxy / network flakes)."""
    last_err: Exception | None = None
    for attempt in range(3):
        try:
            async with _client() as client:
                r = await client.get(url, params=params)
                r.raise_for_status()
                return r.json()
        except Exception as e:
            last_err = e
            await asyncio.sleep(0.4 * (attempt + 1))
    assert last_err is not None
    raise last_err


async def search_shows(query: str, country: str = "CN", limit: int = 20) -> list[Show]:
    q = (query or "").strip()
    if not q:
        return []
    params = {
        "term": q,
        "media": "podcast",
        "entity": "podcast",
        "limit": max(1, min(limit, 50)),
        "country": (country or "CN").upper(),
    }
    data = await _get_json(ITUNES_SEARCH, params=params)
    shows: list[Show] = []
    for item in data.get("results", []):
        feed = (item.get("feedUrl") or "").strip()
        if not feed:
            continue
        shows.append(
            Show(
                id=str(item.get("collectionId") or item.get("trackId") or ""),
                name=item.get("collectionName") or item.get("trackName") or "Untitled",
                author=item.get("artistName") or "",
                artwork=item.get("artworkUrl600")
                or item.get("artworkUrl100")
                or item.get("artworkUrl60")
                or "",
                feed_url=feed,
                episode_count=int(item.get("trackCount") or 0),
                country=item.get("country") or country,
            )
        )
    return shows


async def lookup_show(apple_id: str) -> Optional[Show]:
    apple_id = str(apple_id).strip()
    if not apple_id.isdigit():
        return None
    data = await _get_json(ITUNES_LOOKUP, params={"id": apple_id, "entity": "podcast"})
    for item in data.get("results", []):
        if item.get("kind") == "podcast" or item.get("wrapperType") == "track":
            feed = (item.get("feedUrl") or "").strip()
            if not feed:
                continue
            return Show(
                id=str(item.get("collectionId") or apple_id),
                name=item.get("collectionName") or item.get("trackName") or "Untitled",
                author=item.get("artistName") or "",
                artwork=item.get("artworkUrl600") or item.get("artworkUrl100") or "",
                feed_url=feed,
                episode_count=int(item.get("trackCount") or 0),
                country=item.get("country") or "",
            )
    return None


def _duration_str(entry: dict) -> str:
    for key in ("itunes_duration", "duration"):
        raw = entry.get(key)
        if raw is None:
            continue
        if isinstance(raw, (int, float)):
            sec = int(raw)
        else:
            text = str(raw).strip()
            if text.isdigit():
                sec = int(text)
            elif re.match(r"^\d+:\d{2}(:\d{2})?$", text):
                return text
            else:
                continue
        h, rem = divmod(sec, 3600)
        m, s = divmod(rem, 60)
        if h:
            return f"{h}:{m:02d}:{s:02d}"
        return f"{m}:{s:02d}"
    return ""


def parse_feed_bytes(content: bytes, source_url: str = "") -> tuple[str, list[Episode]]:
    """Parse RSS/Atom; return (show_title, episodes newest-first)."""
    parsed = feedparser.parse(content)
    show_title = (
        (parsed.feed.get("title") if parsed.feed else None)
        or ""
    ).strip() or "Podcast"

    episodes: list[Episode] = []
    for entry in parsed.entries or []:
        audio_url = ""
        size = 0
        for enc in entry.get("enclosures") or []:
            href = (enc.get("href") or enc.get("url") or "").strip()
            typ = (enc.get("type") or "").lower()
            if href and (
                typ.startswith("audio")
                or typ.startswith("video")
                or re.search(r"\.(mp3|m4a|aac|ogg|opus|wav|flac)(\?|$)", href, re.I)
            ):
                audio_url = href
                try:
                    size = int(enc.get("length") or 0)
                except (TypeError, ValueError):
                    size = 0
                break
        if not audio_url:
            # media:content / links
            for link in entry.get("links") or []:
                href = (link.get("href") or "").strip()
                rel = (link.get("rel") or "").lower()
                typ = (link.get("type") or "").lower()
                if href and (
                    rel == "enclosure"
                    or typ.startswith("audio")
                    or re.search(r"\.(mp3|m4a|aac)(\?|$)", href, re.I)
                ):
                    audio_url = href
                    break
        if not audio_url:
            continue

        published = ""
        if entry.get("published_parsed"):
            published = parse_date(entry.published_parsed)
        elif entry.get("updated_parsed"):
            published = parse_date(entry.updated_parsed)
        elif entry.get("published"):
            published = parse_date(entry.published)

        title = (entry.get("title") or "Untitled").strip()
        guid = str(entry.get("id") or entry.get("guid") or audio_url)

        episodes.append(
            Episode(
                index=0,
                title=title,
                audio_url=audio_url,
                published=published,
                duration=_duration_str(entry),
                guid=guid,
                size=size,
            )
        )

    # If feedparser failed hard, try ElementTree fallback for dirty RSS
    if not episodes and content:
        try:
            episodes = _et_fallback(content)
            if not show_title or show_title == "Podcast":
                root = ET.fromstring(content)
                ch = root.find("channel")
                if ch is not None:
                    t = ch.findtext("title")
                    if t:
                        show_title = t.strip()
        except Exception:
            pass

    # Newest first (feedparser usually already is)
    for i, ep in enumerate(episodes):
        ep.index = i
    return show_title, episodes


def _et_fallback(content: bytes) -> list[Episode]:
    # strip default namespaces for simpler paths
    text = content.decode("utf-8", errors="replace")
    text = re.sub(r'\sxmlns(:\w+)?="[^"]*"', "", text)
    root = ET.fromstring(text)
    channel = root.find("channel")
    if channel is None:
        return []
    eps: list[Episode] = []
    for item in channel.findall("item"):
        enc = item.find("enclosure")
        if enc is None:
            continue
        url = (enc.get("url") or "").strip()
        if not url:
            continue
        title = (item.findtext("title") or "Untitled").strip()
        pub = parse_date(item.findtext("pubDate") or "")
        guid = (item.findtext("guid") or url).strip()
        try:
            size = int(enc.get("length") or 0)
        except ValueError:
            size = 0
        eps.append(
            Episode(
                index=0,
                title=title,
                audio_url=url,
                published=pub,
                guid=guid,
                size=size,
            )
        )
    return eps


async def fetch_episodes(feed_url: str) -> tuple[str, list[Episode]]:
    feed_url = (feed_url or "").strip()
    if not feed_url:
        raise ValueError("缺少 feed URL")
    last_err: Exception | None = None
    content = b""
    for attempt in range(3):
        try:
            async with _client(timeout=httpx.Timeout(90.0, connect=30.0)) as client:
                r = await client.get(feed_url)
                r.raise_for_status()
                content = r.content
            break
        except Exception as e:
            last_err = e
            await asyncio.sleep(0.5 * (attempt + 1))
    else:
        assert last_err is not None
        raise last_err
    return parse_feed_bytes(content, feed_url)


async def resolve_youzhiyouxing_material(url: str) -> tuple[Show, list[Episode]]:
    """
    Parse https://youzhiyouxing.cn/materials/<id> pages.
    Audio is embedded as asset.youzhiyouxing.cn/audio/...mp3 (public CDN).
    """
    url = (url or "").strip()
    m = re.search(r"youzhiyouxing\.cn/materials/(\d+)", url, re.I)
    if not m:
        raise ValueError("不是有效的有知有行 materials 链接")
    mat_id = m.group(1)
    page_url = f"https://youzhiyouxing.cn/materials/{mat_id}"

    async with _client(timeout=httpx.Timeout(45.0, connect=20.0)) as client:
        r = await client.get(page_url)
        r.raise_for_status()
        html = r.text

    # title
    title = "有知有行 materials"
    tm = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', html, re.I)
    if not tm:
        tm = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
        if tm:
            title = re.sub(r"\s+", " ", tm.group(1)).strip()
    else:
        title = tm.group(1).strip()

    # column / show name from markdown export if available
    show_name = "有知有行"
    published = ""
    try:
        async with _client(timeout=httpx.Timeout(20.0, connect=15.0)) as client:
            md = await client.get(f"{page_url}?format=md")
            if md.status_code == 200 and "text/markdown" in (md.headers.get("content-type") or ""):
                head = md.text[:1500]
                cm = re.search(r'^column:\s*"([^"]+)"', head, re.M)
                if cm:
                    show_name = cm.group(1).strip() or show_name
                dm = re.search(r'^date:\s*"([^"]+)"', head, re.M)
                if dm:
                    published = parse_date(dm.group(1))
                tm2 = re.search(r'^title:\s*"([^"]+)"', head, re.M)
                if tm2:
                    title = tm2.group(1).strip() or title
    except Exception:
        pass

    # audio url on CDN
    audio_urls = re.findall(
        r"https://asset\.youzhiyouxing\.cn/audio/[^\s\"'<>]+\.(?:mp3|m4a)",
        html,
        re.I,
    )
    # de-dupe preserve order
    seen: set[str] = set()
    audio_urls = [u for u in audio_urls if not (u in seen or seen.add(u))]  # type: ignore[func-returns-value]
    if not audio_urls:
        # broader fallback
        audio_urls = re.findall(
            r"https://[^\s\"'<>]+\.(?:mp3|m4a)(?:\?[^\s\"'<>]*)?",
            html,
            re.I,
        )
        audio_urls = [u for u in audio_urls if "youzhiyouxing" in u.lower()]

    if not audio_urls:
        raise ValueError("该 materials 页面未找到可下载音频（可能需登录或无音频）")

    episodes = [
        Episode(
            index=i,
            title=title if i == 0 else f"{title} ({i + 1})",
            audio_url=u,
            published=published,
            guid=f"yzyx-material-{mat_id}-{i}",
        )
        for i, u in enumerate(audio_urls)
    ]
    show = Show(
        id=mat_id,
        name=show_name,
        author="有知有行",
        feed_url=page_url,
        episode_count=len(episodes),
    )
    return show, episodes


def _norm_title(name: str) -> str:
    return re.sub(r"\s+", "", name or "").casefold()


def _best_title_match(title: str, shows: list[Show]) -> Optional[Show]:
    if not shows:
        return None
    needle = _norm_title(title)
    if not needle:
        return shows[0]
    for s in shows:
        if _norm_title(s.name) == needle:
            return s
    for s in shows:
        hay = _norm_title(s.name)
        if needle in hay or hay in needle:
            return s
    return shows[0]


def _xiaoyuzhou_show_title(html: str) -> str:
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if m:
        try:
            data = json.loads(m.group(1))
            page = (data.get("props") or {}).get("pageProps") or {}
            pod = page.get("podcast")
            if not isinstance(pod, dict):
                ep = page.get("episode") if isinstance(page.get("episode"), dict) else {}
                pod = ep.get("podcast") if isinstance(ep, dict) else {}
            if isinstance(pod, dict):
                title = str(pod.get("title") or "").strip()
                if title:
                    return title
        except (json.JSONDecodeError, TypeError, AttributeError):
            pass
    tm = re.search(
        r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)',
        html,
        re.I,
    )
    if tm:
        return tm.group(1).strip()
    tm = re.search(r"<title[^>]*>(.*?)</title>", html, re.I | re.S)
    if tm:
        return re.sub(r"\s+", " ", tm.group(1)).strip()
    return ""


async def resolve_xiaoyuzhou(url: str, country: str = "CN") -> tuple[Show, list[Episode]]:
    """
    小宇宙页面不公开 RSS。读取节目标题后，走 Apple 目录拿 feedUrl，再解析全集。
    """
    url = (url or "").strip()
    if "xiaoyuzhoufm.com" not in url.lower():
        raise ValueError("不是有效的小宇宙链接")

    async with _client(timeout=httpx.Timeout(45.0, connect=20.0)) as client:
        r = await client.get(url)
        r.raise_for_status()
        html = r.text

    title = _xiaoyuzhou_show_title(html)
    if not title:
        raise ValueError("无法读取小宇宙节目标题")

    countries = [country or "CN", "US"]
    seen: set[str] = set()
    candidates: list[Show] = []
    for cc in countries:
        cc = cc.upper()
        if cc in seen:
            continue
        seen.add(cc)
        try:
            found = await search_shows(title, country=cc, limit=10)
        except Exception:
            found = []
        candidates.extend(found)

    match = _best_title_match(title, candidates)
    if not match or not (match.feed_url or match.id):
        raise ValueError(
            f"未在 Apple 目录中找到「{title}」的 RSS，请改用 Apple 节目链接或 RSS 地址"
        )

    if match.id and match.id.isdigit():
        show = await lookup_show(match.id)
        if show and show.feed_url:
            feed_title, eps = await fetch_episodes(show.feed_url)
            if feed_title and feed_title != "Podcast":
                show.name = feed_title
            show.episode_count = len(eps)
            return show, eps

    if not match.feed_url:
        raise ValueError(f"「{title}」没有可用的 RSS 地址")
    feed_title, eps = await fetch_episodes(match.feed_url)
    show = Show(
        id=match.id,
        name=feed_title or match.name or title,
        author=match.author,
        artwork=match.artwork,
        feed_url=match.feed_url,
        episode_count=len(eps),
        country=match.country or country,
    )
    return show, eps


async def resolve_ximalaya_album(url: str) -> tuple[Show, list[Episode]]:
    """喜马拉雅专辑公开 RSS：https://www.ximalaya.com/album/<id>.xml"""
    url = (url or "").strip()
    m = re.search(r"ximalaya\.com/album/(\d+)", url, re.I)
    if not m:
        raise ValueError("不是有效的喜马拉雅专辑链接")
    album_id = m.group(1)
    feed = f"https://www.ximalaya.com/album/{album_id}.xml"
    title, eps = await fetch_episodes(feed)
    show = Show(
        id=album_id,
        name=title,
        author="喜马拉雅",
        feed_url=feed,
        episode_count=len(eps),
    )
    return show, eps


async def resolve_source(src: str, country: str = "CN") -> tuple[Show, list[Episode]]:
    """Accept Apple ID, Apple / 小宇宙 / 喜马拉雅 / RSS / 有知有行 materials."""
    src = (src or "").strip()
    if not src:
        raise ValueError("请输入节目 ID、Apple / 小宇宙链接、RSS 或有知有行 materials 链接")

    if re.search(r"youzhiyouxing\.cn/materials/\d+", src, re.I):
        return await resolve_youzhiyouxing_material(src)

    if re.search(r"xiaoyuzhoufm\.com/(podcast|episode)/", src, re.I):
        return await resolve_xiaoyuzhou(src, country=country)

    if re.search(r"ximalaya\.com/album/\d+", src, re.I):
        return await resolve_ximalaya_album(src)

    # bare Apple ID
    if src.isdigit():
        show = await lookup_show(src)
        if not show:
            raise ValueError(f"找不到 Apple Podcast ID: {src}")
        title, eps = await fetch_episodes(show.feed_url)
        if title and title != "Podcast":
            show.name = title
        show.episode_count = len(eps)
        return show, eps

    # Apple show URL (ignore episode ?i= — still load the whole show)
    m = re.search(r"id(\d+)", src)
    if "podcasts.apple.com" in src and m:
        return await resolve_source(m.group(1), country)

    # assume RSS / Atom URL
    if src.startswith("http://") or src.startswith("https://"):
        title, eps = await fetch_episodes(src)
        show = Show(
            id="",
            name=title,
            feed_url=src,
            episode_count=len(eps),
        )
        return show, eps

    raise ValueError(
        "无法识别来源，请使用 Apple 节目链接/ID、小宇宙链接、RSS 或有知有行 materials 链接"
    )


ProgressCb = Callable[[DownloadItem], None]


def _is_retryable_error(exc: BaseException) -> bool:
    """Network / transient HTTP errors worth retrying."""
    if isinstance(
        exc,
        (
            httpx.TimeoutException,
            httpx.NetworkError,
            httpx.RemoteProtocolError,
            ConnectionError,
            TimeoutError,
            OSError,
        ),
    ):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        code = exc.response.status_code if exc.response is not None else 0
        return code in {408, 425, 429, 500, 502, 503, 504} or code >= 500
    text = str(exc).lower()
    for token in (
        "timeout",
        "timed out",
        "connection reset",
        "connection aborted",
        "temporarily",
        "broken pipe",
        "server disconnected",
        "remote protocol",
        "connecterror",
        "readerror",
    ):
        if token in text:
            return True
    return False


async def download_one(
    client: httpx.AsyncClient,
    item: DownloadItem,
    dest: Path,
    on_progress: Optional[ProgressCb] = None,
    *,
    max_attempts: int = 3,
) -> None:
    """Download one episode; resume partial files; retry transient failures."""
    ep = item.episode
    last_err: Exception | None = None

    for attempt in range(1, max(1, max_attempts) + 1):
        item.status = "running"
        item.error = ""
        if on_progress:
            on_progress(item)

        headers: dict[str, str] = {}
        existing = dest.stat().st_size if dest.exists() else 0
        # Tiny leftovers are almost always error pages, not audio — don't resume them.
        if existing > 0 and existing < MIN_COMPLETE_BYTES:
            if not (ep.size and existing >= int(ep.size * 0.98)):
                existing = 0
        if existing > 0:
            headers["Range"] = f"bytes={existing}-"

        try:
            async with client.stream("GET", ep.audio_url, headers=headers) as resp:
                # If server ignores Range and returns 200, only restart when the
                # local file is clearly incomplete. Never wipe a full episode.
                if resp.status_code == 200 and existing > 0:
                    cl0 = resp.headers.get("content-length")
                    remote_total = int(cl0) if cl0 and cl0.isdigit() else (ep.size or 0)
                    if is_complete_file(dest, remote_total):
                        item.path = str(dest)
                        item.status = "skipped"
                        item.bytes_done = existing
                        item.bytes_total = existing
                        if on_progress:
                            on_progress(item)
                        return
                    existing = 0
                if resp.status_code == 416 and existing > 0:
                    # already complete according to server
                    item.path = str(dest)
                    item.status = "done"
                    item.bytes_done = existing
                    item.bytes_total = existing
                    if on_progress:
                        on_progress(item)
                    return
                if resp.status_code not in (200, 206):
                    resp.raise_for_status()

                total = 0
                cl = resp.headers.get("content-length")
                if cl and cl.isdigit():
                    total = int(cl) + (existing if resp.status_code == 206 else 0)
                elif ep.size:
                    total = ep.size

                ctype = resp.headers.get("content-type")
                if dest.suffix.lower() not in {
                    ".mp3",
                    ".m4a",
                    ".mp4",
                    ".aac",
                    ".ogg",
                    ".opus",
                    ".wav",
                    ".flac",
                }:
                    dest = dest.with_suffix(guess_ext(ep.audio_url, ctype))

                item.bytes_total = total
                item.bytes_done = existing
                mode = "ab" if existing and resp.status_code == 206 else "wb"
                if mode == "wb" and dest.exists():
                    dest.unlink(missing_ok=True)

                with open(dest, mode) as f:
                    async for chunk in resp.aiter_bytes(64 * 1024):
                        if not chunk:
                            continue
                        f.write(chunk)
                        item.bytes_done += len(chunk)
                        if on_progress:
                            on_progress(item)

            # basic sanity: empty file is failure
            final_size = dest.stat().st_size if dest.exists() else 0
            if final_size <= 0:
                raise RuntimeError("下载结果为空文件")

            item.path = str(dest)
            item.status = "done"
            item.error = ""
            if on_progress:
                on_progress(item)
            return
        except Exception as e:
            last_err = e
            item.status = "error"
            item.error = str(e)
            if on_progress:
                on_progress(item)
            if attempt < max_attempts and _is_retryable_error(e):
                # keep partial file for resume; brief backoff
                await asyncio.sleep(min(8.0, 1.2 * attempt + 0.5))
                continue
            break

    item.status = "error"
    item.error = str(last_err) if last_err else "下载失败"
    if on_progress:
        on_progress(item)


def build_dest_path(out_dir: Path, show_name: str, ep: Episode) -> Path:
    """
    Save as: <out>/<show>/<title>.<ext>
    Example:  知行小酒馆/E01 访问95后KOL，聊聊她的省钱秘笈.m4a
    Reuses an existing file (any audio extension / index entry) so we never
    create a second copy of the same episode.
    """
    folder = out_dir / sanitize_filename(show_name)
    folder.mkdir(parents=True, exist_ok=True)
    existing = find_existing_file(folder, ep)
    if existing:
        return existing
    base = sanitize_filename(ep.title, max_len=160)
    if not base:
        base = sanitize_filename(ep.published or ep.guid or "episode", max_len=80)
    ext = guess_ext(ep.audio_url)
    return folder / f"{base}{ext}"


async def run_download_job(
    job: DownloadJob,
    concurrency: int = 32,
    on_update: Optional[Callable[[DownloadJob], None]] = None,
    *,
    only_statuses: Optional[set[str]] = None,
    max_attempts: int = 3,
) -> None:
    """
    Download job items.
    only_statuses: if set, only process items whose current status is in this set
                   (e.g. {"error", "pending"} for retry).
    """
    job.status = "running"
    job.message = "下载中…"
    if on_update:
        on_update(job)

    out = Path(job.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    sem = asyncio.Semaphore(max(1, min(concurrency, 32)))

    targets = [
        i
        for i in job.items
        if only_statuses is None or i.status in only_statuses
    ]

    def touch(_item: DownloadItem | None = None) -> None:
        if on_update:
            on_update(job)

    # Long read timeout: single episode can be 100MB+ on slow links
    async with httpx.AsyncClient(
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "*/*",
        },
        follow_redirects=True,
        timeout=httpx.Timeout(connect=30.0, read=300.0, write=60.0, pool=30.0),
        limits=httpx.Limits(max_connections=32, max_keepalive_connections=16),
    ) as client:

        async def worker(item: DownloadItem) -> None:
            async with sem:
                if job.status == "cancelled":
                    if item.status not in ("done", "skipped"):
                        item.status = "error"
                        item.error = "已取消"
                        touch(item)
                    return
                dest = build_dest_path(out, job.show_name, item.episode)
                folder = dest.parent
                expected = expected_size_for(folder, item.episode, dest)
                if dest.exists() and is_complete_file(dest, expected):
                    st = dest.stat().st_size
                    item.path = str(dest)
                    item.status = "skipped"
                    item.bytes_done = st
                    item.bytes_total = st
                    item.error = ""
                    remember_local_file(folder, item.episode, dest)
                    touch(item)
                    return
                # partial files are kept and resumed via Range in download_one
                item.error = ""
                await download_one(
                    client,
                    item,
                    dest,
                    on_progress=lambda _i: touch(_i),
                    max_attempts=max_attempts,
                )
                if item.status in ("done", "skipped") and dest.exists():
                    # dest suffix may have changed after Content-Type sniff
                    final = Path(item.path) if item.path else dest
                    remember_local_file(folder, item.episode, final)

        if targets:
            await asyncio.gather(*(worker(i) for i in targets))

    if job.status == "cancelled":
        job.message = "已取消"
        if on_update:
            on_update(job)
        return
    failed = sum(1 for i in job.items if i.status == "error")
    skipped = sum(1 for i in job.items if i.status == "skipped")
    downloaded = sum(1 for i in job.items if i.status == "done")
    job.status = "error" if failed and failed == len(job.items) else "done"
    parts: list[str] = []
    if downloaded:
        parts.append(f"新下 {downloaded}")
    if skipped:
        parts.append(f"已存在 {skipped}")
    if failed:
        parts.append(f"失败 {failed} — 可点「重试失败」")
    job.message = "完成，" + "，".join(parts) if parts else "全部完成"
    if on_update:
        on_update(job)


def default_out_dir() -> Path:
    # Prefer fixed library path on Windows; fall back if unavailable.
    preferred = Path(r"D:\Podcasts")
    try:
        preferred.mkdir(parents=True, exist_ok=True)
        return preferred
    except OSError:
        return Path.home() / "Downloads" / "Podcasts"
