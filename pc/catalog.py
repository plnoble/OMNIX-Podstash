"""Local podcast catalog — no third-party metadata proxy.

Search: Apple iTunes Search API
Trending: 中文播客榜 xyzrank + Apple Top Podcasts RSS-JSON
Episodes: the show's public RSS (parsed locally)
"""

from __future__ import annotations

import re
import time
from typing import Any

from core import Episode, Show, _get_json, resolve_source, search_shows

XYZRANK_PODCASTS = "https://xyzrank.com/api/podcasts"
APPLE_TOP = "https://itunes.apple.com/{country}/rss/toppodcasts/limit={limit}/json"

_TREND_TTL = 30 * 60
_trend_cache: dict[str, tuple[float, tuple[str, list[Show]]]] = {}


def _apple_id_from_url(url: str) -> str:
    m = re.search(r"id(\d+)", url or "")
    return m.group(1) if m else ""


def _link_map(links: Any) -> dict[str, str]:
    out: dict[str, str] = {}
    if not isinstance(links, list):
        return out
    for item in links:
        if not isinstance(item, dict):
            continue
        name = str(item.get("name") or "").strip().lower()
        url = str(item.get("url") or "").strip()
        if name and url:
            out[name] = url
    return out


def _show_from_xyzrank(item: dict[str, Any]) -> Show | None:
    links = _link_map(item.get("links"))
    apple_url = links.get("apple", "")
    apple_id = _apple_id_from_url(apple_url)
    rss = links.get("rss", "").strip()
    xyz = (links.get("xyz") or links.get("website") or "").strip()
    name = str(item.get("name") or "").strip()
    if not name:
        return None
    count = item.get("trackCount") or 0
    try:
        count = int(count)
    except (TypeError, ValueError):
        count = 0
    rank = item.get("rank") or 0
    try:
        rank = int(rank)
    except (TypeError, ValueError):
        rank = 0
    return Show(
        id=apple_id or str(item.get("id") or ""),
        name=name,
        author=str(item.get("authorsText") or ""),
        artwork=str(item.get("logoURL") or ""),
        feed_url=rss or xyz,
        episode_count=count,
        country="CN",
        rank=rank,
    )


def _show_from_apple_entry(entry: dict[str, Any], rank: int, country: str) -> Show | None:
    attrs = (entry.get("id") or {}).get("attributes") or {}
    apple_id = str(attrs.get("im:id") or "")
    name = str((entry.get("im:name") or {}).get("label") or "").strip()
    if not name and not apple_id:
        return None
    images = entry.get("im:image") or []
    artwork = ""
    if isinstance(images, list) and images:
        last = images[-1]
        artwork = last.get("label") if isinstance(last, dict) else ""
    elif isinstance(images, dict):
        artwork = str(images.get("label") or "")
    page = str((entry.get("id") or {}).get("label") or "")
    if not apple_id:
        apple_id = _apple_id_from_url(page)
    return Show(
        id=apple_id,
        name=name or "Untitled",
        author=str((entry.get("im:artist") or {}).get("label") or ""),
        artwork=str(artwork or ""),
        feed_url="",
        episode_count=0,
        country=country.upper(),
        rank=rank,
    )


async def search(query: str, limit: int = 20, country: str = "CN") -> list[Show]:
    """Search Apple's podcast directory. Tries the requested store, then US."""
    q = (query or "").strip()
    if not q:
        return []
    country = (country or "CN").upper()
    shows = await search_shows(q, country=country, limit=limit)
    if shows or country == "US":
        return shows
    extra = await search_shows(q, country="US", limit=limit)
    return extra


async def fetch_xyzrank(limit: int = 40) -> list[Show]:
    data = await _get_json(XYZRANK_PODCASTS, params={"limit": max(1, min(int(limit), 100)), "offset": 0})
    raw = data.get("items") if isinstance(data, dict) else None
    if not isinstance(raw, list):
        nested = data.get("data") if isinstance(data, dict) else None
        if isinstance(nested, dict):
            raw = nested.get("podcasts") or nested.get("items")
        elif isinstance(nested, list):
            raw = nested
    if not isinstance(raw, list):
        return []
    shows: list[Show] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        show = _show_from_xyzrank(item)
        if show and (show.feed_url or show.id):
            shows.append(show)
    return shows


async def fetch_apple_charts(country: str = "US", limit: int = 40) -> list[Show]:
    cc = (country or "US").lower()
    lim = max(1, min(int(limit), 200))
    url = APPLE_TOP.format(country=cc, limit=lim)
    data = await _get_json(url)
    entries = ((data.get("feed") or {}).get("entry")) if isinstance(data, dict) else None
    if entries is None:
        return []
    if isinstance(entries, dict):
        entries = [entries]
    shows: list[Show] = []
    for i, entry in enumerate(entries or []):
        if not isinstance(entry, dict):
            continue
        show = _show_from_apple_entry(entry, i + 1, cc)
        if show:
            shows.append(show)
    return shows


async def _trending_uncached(source: str) -> tuple[str, list[Show]]:
    src = (source or "cn").lower()
    if src in ("apple", "intl", "international", "us"):
        return "apple", await fetch_apple_charts("US", 40)

    try:
        shows = await fetch_xyzrank(40)
        if shows:
            return "xyzrank", shows
    except Exception:
        shows = []

    # xyzrank down or empty → Apple China chart so the 中文 tab still works
    fallback = await fetch_apple_charts("CN", 40)
    return "apple-cn", fallback


async def trending(source: str = "cn") -> tuple[str, list[Show]]:
    """
    source:
      - cn / xyzrank → 中文播客榜
      - apple / intl → Apple Top (US)
    """
    src = (source or "cn").lower()
    key = "apple" if src in ("apple", "intl", "international", "us") else "cn"
    now = time.monotonic()
    hit = _trend_cache.get(key)
    if hit and now - hit[0] < _TREND_TTL:
        return hit[1]
    try:
        result = await _trending_uncached(key)
        _trend_cache[key] = (now, result)
        return result
    except Exception:
        if hit:
            return hit[1]
        raise


async def list_episodes(src: str, country: str = "CN") -> tuple[Show, list[Episode]]:
    """Load a show and every episode in its public RSS."""
    return await resolve_source(src, country=country)
