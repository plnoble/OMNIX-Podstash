"""OMNIX-Podstash — local podcast library (search, stash, no metadata proxy)."""

from __future__ import annotations

import asyncio
import base64
import io
import json
import logging
import os
import re
import secrets
import socket
import sqlite3
import sys
import time
import uuid
import webbrowser
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional
from urllib.error import URLError
from urllib.request import Request, urlopen

import httpx
import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, JSONResponse, PlainTextResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import catalog
import persist
import store
from core import (
    DownloadItem,
    DownloadJob,
    Episode,
    USER_AGENT,
    fetch_cover,
    find_existing_in_library,
    iter_audio_files,
    iter_show_folders,
    mark_episodes_local,
    normalize_match_key,
    resolve_source,
    run_download_job,
    score_title_against_filename,
    write_media_tags,
)

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
REPO_ROOT = ROOT.parent if (ROOT.parent / "VERSION").exists() else ROOT


def app_version() -> str:
    path = REPO_ROOT / "VERSION"
    if path.exists():
        return path.read_text(encoding="utf-8").strip() or "0.1.0"
    return "0.1.0"


_log = logging.getLogger("podstash")
_jobs: dict[str, DownloadJob] = {}
_jobs_lock = asyncio.Lock()
_scan_lock = asyncio.Lock()
_scan_task: asyncio.Task[None] | None = None
_scan_pending = False


def _persist_job(job: DownloadJob) -> None:
    try:
        store.save_job(
            {
                "id": job.id,
                "show_name": job.show_name,
                "out_dir": job.out_dir,
                "status": job.status,
                "message": job.message,
            },
            [i.to_dict() for i in job.items],
        )
    except Exception:
        _log.exception("persist job failed")


def _restore_jobs() -> None:
    try:
        out_dir = persist.get()["out_dir"]
        for raw in store.load_jobs():
            items = [
                DownloadItem(
                    episode=Episode(
                        index=int(it.get("index") or 0),
                        title=str(it.get("title") or "Untitled"),
                        audio_url=str(it.get("audio_url") or ""),
                        published="",
                        duration="",
                        guid=str(it.get("guid") or ""),
                        size=int(it.get("size") or 0),
                    ),
                    path=str(it.get("path") or ""),
                    status=str(it.get("status") or "pending"),
                    bytes_done=int(it.get("bytes_done") or 0),
                    bytes_total=int(it.get("bytes_total") or 0),
                    error=str(it.get("error") or ""),
                )
                for it in raw.get("items") or []
            ]
            job = DownloadJob(
                id=str(raw["id"]),
                show_name=str(raw.get("show_name") or "Podcast"),
                out_dir=str(raw.get("out_dir") or out_dir),
                items=items,
                status=str(raw.get("status") or "queued"),
                message=str(raw.get("message") or ""),
            )
            if job.status in ("queued", "running"):
                job.status = "cancelled"
                job.message = "容器重启，任务中断；失败项可重试"
                for it in job.items:
                    if it.status == "running":
                        it.status = "error"
                        it.error = "容器重启中断"
            _jobs[job.id] = job
    except Exception:
        _log.exception("restore jobs failed")


async def _run_job_with_retries(
    job: DownloadJob,
    concurrency: int,
    only_statuses: Optional[set[str]] = None,
) -> None:
    """Run a download job and automatically retry failed items (bounded)."""
    await run_download_job(
        job, concurrency=concurrency, only_statuses=only_statuses, max_attempts=3
    )
    _persist_job(job)
    if job.status == "cancelled":
        return
    for _round in range(2):
        failed = [i for i in job.items if i.status == "error"]
        if not failed:
            break
        for i in failed:
            i.status = "pending"
            i.error = ""
            i.bytes_done = 0
            i.path = ""
        await run_download_job(
            job, concurrency=concurrency, only_statuses={"pending"}, max_attempts=4
        )
        _persist_job(job)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    persist.load()
    _restore_jobs()
    global _scan_task
    _scan_task = asyncio.create_task(_auto_scan_loop())
    try:
        yield
    finally:
        if _scan_task:
            _scan_task.cancel()
            try:
                await _scan_task
            except (asyncio.CancelledError, Exception):
                pass


app = FastAPI(title="OMNIX-Podstash", version=app_version(), lifespan=lifespan)

APP_PASSWORD = (os.environ.get("PODSTASH_PASSWORD") or "").strip()


def _password_ok(authorization: Optional[str]) -> bool:
    if not APP_PASSWORD:
        return True
    if not authorization:
        return False
    try:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "basic":
            return False
        raw = base64.b64decode(token.strip()).decode("utf-8", "replace")
        _user, _sep, pw = raw.partition(":")
        return secrets.compare_digest(pw.encode("utf-8"), APP_PASSWORD.encode("utf-8"))
    except Exception:
        return False


@app.middleware("http")
async def auth_middleware(request, call_next):
    if request.url.path == "/api/health":
        return await call_next(request)
    if not _password_ok(request.headers.get("authorization")):
        return JSONResponse(
            {"detail": "需要登录"},
            status_code=401,
            headers={"WWW-Authenticate": 'Basic realm="OMNIX-Podstash"'},
        )
    return await call_next(request)


class ResolveBody(BaseModel):
    source: str = Field(..., description="Apple ID / show URL / RSS URL")
    country: str = "CN"
    scan: bool = False


class DownloadBody(BaseModel):
    show_name: str
    out_dir: Optional[str] = None
    concurrency: Optional[int] = None
    artwork: Optional[str] = None
    episodes: list[dict[str, Any]]


class SettingsBody(BaseModel):
    out_dir: Optional[str] = None
    concurrency: Optional[int] = None
    auto_scan: Optional[bool] = None
    auto_scan_days: Optional[int] = None
    auto_scan_limit: Optional[int] = None
    auto_scan_mode: Optional[str] = None


class LocalStatusBody(BaseModel):
    show_name: str
    out_dir: Optional[str] = None
    episodes: list[dict[str, Any]]


class SubscribeBody(BaseModel):
    id: str = ""
    name: str = ""
    author: str = ""
    artwork: str = ""
    feed_url: str = ""
    episode_count: int = 0
    subscribed: bool = True


class RetagBody(BaseModel):
    show_name: str = ""
    author: str = ""
    artwork: str = ""
    out_dir: Optional[str] = None
    episodes: list[dict[str, Any]] = Field(default_factory=list)


class OpmlImportBody(BaseModel):
    xml: str


class IgnoreBody(BaseModel):
    guid: str
    ignored: bool = True


class ShowSettingsBody(BaseModel):
    id: str = ""
    name: str = ""
    feed_url: str = ""
    scan_days: Optional[int] = None
    scan_limit: Optional[int] = None


@app.get("/api/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "name": "OMNIX-Podstash",
        "version": app_version(),
        "search": "itunes",
        "trending_cn": "xyzrank",
        "trending_intl": "apple",
        "episodes": "rss",
        "auto_scan": persist.get().get("auto_scan"),
    }


_UPDATE_REPO = "plnoble/OMNIX-Podstash"
_update_cache: dict[str, Any] = {"ts": 0.0, "data": None}


def _ver_tuple(v: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", str(v or ""))[:3]
    return tuple(int(n) for n in nums) if nums else (0,)


@app.get("/api/update")
async def api_update() -> dict[str, Any]:
    """Compare the running version with the latest GitHub Release (read-only, 1h cache)."""
    now = time.monotonic()
    if _update_cache["data"] and now - _update_cache["ts"] < 3600:
        return _update_cache["data"]
    current = app_version()
    result: dict[str, Any] = {
        "current": current,
        "latest": current,
        "has_update": False,
        "notes": "",
        "html_url": "",
    }
    try:
        async with httpx.AsyncClient(
            timeout=10.0,
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "OMNIX-Podstash",
            },
            follow_redirects=True,
        ) as client:
            r = await client.get(
                f"https://api.github.com/repos/{_UPDATE_REPO}/releases/latest"
            )
            r.raise_for_status()
            data = r.json()
        latest = str(data.get("tag_name") or "").lstrip("vV")
        if latest:
            result["latest"] = latest
            result["has_update"] = _ver_tuple(latest) > _ver_tuple(current)
            result["notes"] = str(data.get("body") or "")
            result["html_url"] = str(data.get("html_url") or "")
    except Exception:
        # offline / rate-limited: report no update, never fail the page
        pass
    _update_cache["ts"] = now
    _update_cache["data"] = result
    return result


def _out_dir() -> Path:
    return Path(persist.get()["out_dir"]).expanduser()


@app.get("/api/settings")
async def get_settings() -> dict[str, Any]:
    data = persist.public_settings()
    data["version"] = app_version()
    return data


@app.post("/api/settings")
async def set_settings(body: SettingsBody) -> dict[str, Any]:
    patch: dict[str, Any] = {}
    if body.out_dir is not None:
        p = Path(body.out_dir).expanduser()
        try:
            p.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            raise HTTPException(400, f"无法创建目录: {e}") from e
        patch["out_dir"] = str(p.resolve())
    if body.concurrency is not None:
        patch["concurrency"] = max(1, min(int(body.concurrency), 32))
    if body.auto_scan is not None:
        patch["auto_scan"] = bool(body.auto_scan)
    if body.auto_scan_days is not None:
        patch["auto_scan_days"] = max(1, min(int(body.auto_scan_days), 30))
    if body.auto_scan_limit is not None:
        patch["auto_scan_limit"] = max(0, min(int(body.auto_scan_limit), 5000))
    if body.auto_scan_mode is not None:
        mode = str(body.auto_scan_mode).strip().lower()
        patch["auto_scan_mode"] = mode if mode in {"new", "backfill"} else "new"
    if patch:
        persist.save(patch)
    return await get_settings()


@app.get("/api/library")
async def api_library() -> dict[str, Any]:
    return {"shows": persist.subscribed()}


@app.post("/api/subscribe")
async def api_subscribe(body: SubscribeBody) -> dict[str, Any]:
    rec = persist.upsert_show(
        {
            "id": body.id,
            "name": body.name,
            "author": body.author,
            "artwork": body.artwork,
            "feed_url": body.feed_url,
            "episode_count": body.episode_count,
            "subscribed": body.subscribed,
        },
        subscribed=body.subscribed,
    )
    return {"ok": True, "subscribed": body.subscribed, "shows": persist.subscribed(), "saved": rec}


@app.post("/api/show-settings")
async def api_show_settings(body: ShowSettingsBody) -> dict[str, Any]:
    """Per-show overrides for scan frequency / per-scan episode cap."""
    key = (body.id or body.feed_url or body.name or "").strip()
    if not key:
        raise HTTPException(400, "缺少节目标识")
    show = store.get_show(key)
    if not show:
        raise HTTPException(404, "节目不在库里（先关注）")
    if body.scan_days is not None:
        show["scan_days"] = max(1, min(int(body.scan_days), 30))
    if body.scan_limit is not None:
        show["scan_limit"] = max(0, min(int(body.scan_limit), 5000))
    store.upsert_show_record(show)
    return {"ok": True, "show": show}


@app.post("/api/episodes/ignore")
async def api_ignore_episode(body: IgnoreBody) -> dict[str, Any]:
    guid = (body.guid or "").strip()
    if not guid:
        raise HTTPException(400, "缺少 guid")
    store.set_episode_ignored(guid, bool(body.ignored))
    return {"ok": True, "guid": guid, "ignored": bool(body.ignored)}


@app.get("/api/episodes/search")
async def api_episode_search(
    q: str = Query(..., min_length=1),
    limit: int = Query(30, ge=1, le=100),
) -> dict[str, Any]:
    results = store.search_episodes((q or "").strip(), limit)
    return {"query": q, "results": results}


@app.post("/api/opml/import")
async def api_opml_import(body: OpmlImportBody) -> dict[str, Any]:
    shows = persist.parse_opml(body.xml)
    if not shows:
        raise HTTPException(400, "OPML 里没有有效的 feed")
    for s in shows:
        persist.upsert_show(s, subscribed=True)
    return {"ok": True, "imported": len(shows), "shows": persist.subscribed()}


@app.get("/api/opml/export")
async def api_opml_export() -> PlainTextResponse:
    xml = persist.write_opml(persist.subscribed())
    return PlainTextResponse(xml, media_type="text/xml")


@app.get("/api/backup")
async def api_backup() -> StreamingResponse:
    """Zip: a consistent snapshot of the SQLite db + subscriptions OPML + index files."""
    buf = io.BytesIO()
    snap_path = persist.db_path().with_name(f"{persist.db_path().name}.bak")
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        # Consistent SQLite snapshot via the backup API (safe under WAL).
        try:
            src = sqlite3.connect(str(persist.db_path()))
            dst = sqlite3.connect(str(snap_path))
            with dst:
                src.backup(dst)
            src.close()
            dst.close()
            zf.write(snap_path, "podstash.db")
            snap_path.unlink(missing_ok=True)
        except Exception:
            _log.exception("db snapshot for backup failed")
        zf.writestr("subscriptions.opml", persist.write_opml(persist.subscribed()))
        out_dir = Path(persist.get()["out_dir"]).expanduser()
        count = 0
        for p in out_dir.rglob(".podbatch-index.json"):
            if count >= 2000:
                break
            try:
                zf.write(p, "indexes/" + p.relative_to(out_dir).as_posix())
                count += 1
            except OSError:
                pass
    buf.seek(0)
    return StreamingResponse(
        buf,
        media_type="application/zip",
        headers={"Content-Disposition": "attachment; filename=omnix-podstash-backup.zip"},
    )


@app.post("/api/auto-scan")
async def api_auto_scan_now() -> dict[str, Any]:
    """Start a background scan; poll GET /api/auto-scan for progress."""
    global _scan_pending
    if _scan_lock.locked() or _scan_pending:
        s = persist.public_settings()
        return {
            "ok": True,
            "running": True,
            "started": False,
            "message": s.get("last_auto_scan_message") or "扫描进行中…",
        }
    persist.save({"last_auto_scan_message": "准备扫描关注的节目…"})
    _scan_pending = True
    asyncio.create_task(run_auto_scan(reason="manual"))
    return {
        "ok": True,
        "running": True,
        "started": True,
        "message": "已开始扫描，页面会显示进度",
    }


@app.get("/api/auto-scan")
async def api_auto_scan_status() -> dict[str, Any]:
    s = persist.public_settings()
    return {
        "running": _scan_lock.locked() or _scan_pending,
        "auto_scan": s["auto_scan"],
        "auto_scan_days": s["auto_scan_days"],
        "last_auto_scan": s["last_auto_scan"],
        "last_auto_scan_message": s["last_auto_scan_message"],
        "subscribed_count": s["subscribed_count"],
    }


@app.get("/api/trending")
async def api_trending(
    source: str = Query("cn", description="cn | apple"),
) -> dict[str, Any]:
    """Trending: 中文播客榜 xyzrank / International Apple Top."""
    try:
        label, shows = await catalog.trending(source)
    except Exception as e:
        raise HTTPException(502, f"热门榜加载失败: {e}") from e
    return {
        "source": label,
        "shows": [s.to_dict() for s in shows],
        "via": label,
    }


@app.get("/api/search")
async def api_search(
    q: str = Query(..., min_length=1),
    country: str = Query("CN"),
    limit: int = Query(20, ge=1, le=50),
) -> dict[str, Any]:
    """Search Apple's public iTunes podcast directory."""
    try:
        shows = await catalog.search(q, limit=limit, country=country)
        return {
            "shows": [s.to_dict() for s in shows],
            "via": "itunes",
        }
    except Exception as e:
        raise HTTPException(502, f"搜索失败: {e}") from e


@app.post("/api/resolve")
async def api_resolve(body: ResolveBody) -> dict[str, Any]:
    """Load show + every episode from the public RSS (Apple / 小宇宙 / 喜马拉雅 / 直链)."""
    src = (body.source or "").strip()
    if not src:
        raise HTTPException(400, "请输入节目 ID、链接或 RSS")

    lower = src.lower()
    if "youzhiyouxing.cn/materials/" in lower:
        via = "yzyx"
    elif "xiaoyuzhoufm.com" in lower:
        via = "xiaoyuzhou"
    elif "ximalaya.com" in lower:
        via = "ximalaya"
    elif src.isdigit() or "podcasts.apple.com" in lower:
        via = "itunes"
    else:
        via = "rss"

    try:
        show, episodes = await resolve_source(src, country=body.country)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:
        raise HTTPException(502, f"解析失败: {e}") from e

    if not show:
        raise HTTPException(502, "解析失败: unknown")

    sid = persist.canonical_key(show)
    if sid:
        persist.save_episodes(sid, episodes)

    ignored_guids: set[str] = set()
    if sid:
        try:
            ignored_guids = {e["guid"] for e in store.list_episodes(sid) if e.get("ignored")}
        except Exception:
            pass

    out_path = _out_dir()
    local_done = 0
    ep_payload = []
    if body.scan:
        local_rows, local_done = mark_episodes_local(out_path, show.name, episodes, lenient=True)
        for e, local in zip(episodes, local_rows):
            row = e.to_dict()
            row.update(local)
            row["ignored"] = (e.guid or "") in ignored_guids
            ep_payload.append(row)
    else:
        for e in episodes:
            row = e.to_dict()
            row["ignored"] = (e.guid or "") in ignored_guids
            ep_payload.append(row)

    show_d = show.to_dict()
    show_d["subscribed"] = persist.is_subscribed(show_d)
    return {
        "show": show_d,
        "episodes": ep_payload,
        "via": via,
        "local_downloaded": local_done,
        "scanned": bool(body.scan),
        "out_dir": str(out_path),
    }


@app.post("/api/local-status")
async def api_local_status(body: LocalStatusBody) -> dict[str, Any]:
    """Scan the library folder and mark episodes whose audio is already on disk."""
    out_dir = Path(body.out_dir or persist.get()["out_dir"]).expanduser()
    episodes = []
    for raw in body.episodes:
        episodes.append(
            Episode(
                index=int(raw.get("index") or 0),
                title=str(raw.get("title") or "Untitled"),
                audio_url=str(raw.get("audio_url") or raw.get("url") or ""),
                published=str(raw.get("published") or raw.get("date") or ""),
                duration=str(raw.get("duration") or ""),
                guid=str(raw.get("guid") or ""),
                size=int(raw.get("size") or 0),
            )
        )
    local_rows, local_done = mark_episodes_local(out_dir, body.show_name, episodes, lenient=True)
    rows = []
    for ep, local in zip(episodes, local_rows):
        row = {"index": ep.index}
        row.update(local)
        rows.append(row)
    return {
        "out_dir": str(out_dir),
        "local_downloaded": local_done,
        "episodes": rows,
    }


@app.get("/api/scan-debug")
async def api_scan_debug(source: str, out_dir: str = "") -> dict[str, Any]:
    """诊断「检测已有文件」为什么没识别到本地音频：列出候选目录、文件与每集最佳匹配分。"""
    src = (source or "").strip()
    if not src:
        raise HTTPException(400, "缺少 source（节目链接/ID）")
    try:
        show, episodes = await resolve_source(src)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:
        raise HTTPException(502, f"解析失败: {e}") from e

    out_path = Path(out_dir or persist.get()["out_dir"]).expanduser()
    folders = [str(f) for f in iter_show_folders(out_path, show.name)]
    files: list[dict[str, Any]] = []
    for fd in folders:
        for p in iter_audio_files(Path(fd), extra_depth=1):
            try:
                sz = p.stat().st_size
            except OSError:
                sz = 0
            files.append({"folder": fd, "name": p.name, "size": sz})

    eps_debug = []
    for ep in episodes[:40]:
        best_score, best_name = 0, ""
        for fd in folders:
            for p in iter_audio_files(Path(fd), extra_depth=1):
                sc = score_title_against_filename(ep.title, p.name, show.name)
                if sc > best_score:
                    best_score, best_name = sc, p.name
        eps_debug.append(
            {
                "title": ep.title,
                "normalized": normalize_match_key(ep.title),
                "best_score": best_score,
                "best_file": best_name,
            }
        )

    return {
        "show_name": show.name,
        "show_name_normalized": normalize_match_key(show.name),
        "out_dir": str(out_path),
        "folders": folders,
        "files": files[:300],
        "episodes": eps_debug,
    }


@app.post("/api/retag")
async def api_retag(body: RetagBody) -> dict[str, Any]:
    """给已有音频文件补写 ID3/MP4 标签（标题/作者/专辑/音轨/封面 + shownotes 说明）。"""
    out_dir = Path(body.out_dir or persist.get()["out_dir"]).expanduser()
    raw_list = body.episodes
    eps = []
    for raw in raw_list:
        eps.append(
            Episode(
                index=int(raw.get("index") or 0),
                title=str(raw.get("title") or "Untitled"),
                audio_url=str(raw.get("audio_url") or raw.get("url") or ""),
                published=str(raw.get("published") or raw.get("date") or ""),
                duration=str(raw.get("duration") or ""),
                guid=str(raw.get("guid") or ""),
                size=int(raw.get("size") or 0),
                description=str(raw.get("description") or ""),
            )
        )

    cover = None
    try:
        async with httpx.AsyncClient(
            headers={"User-Agent": USER_AGENT, "Accept": "*/*"},
            follow_redirects=True,
            timeout=httpx.Timeout(15.0, connect=10.0),
        ) as client:
            cover = await fetch_cover(client, body.artwork)
    except Exception:
        cover = None

    tagged = 0
    checked = 0
    for i, ep in enumerate(eps):
        lp = str(raw_list[i].get("local_path") or "").strip()
        path = Path(lp) if lp else None
        if path is None or not path.exists():
            path = find_existing_in_library(out_dir, body.show_name, ep)
        if not path or not path.exists():
            continue
        checked += 1
        write_media_tags(
            path,
            title=ep.title,
            artist=body.author or body.show_name or "Podcast",
            album=body.show_name or "Podcast",
            track=ep.index + 1,
            cover=cover,
        )
        tagged += 1

    return {"tagged": tagged, "checked": checked, "total": len(eps)}


@app.post("/api/download")
async def api_download(body: DownloadBody) -> dict[str, Any]:
    if not body.episodes:
        raise HTTPException(400, "请至少选择一集")

    out_dir = body.out_dir or persist.get()["out_dir"]
    out_path = Path(out_dir).expanduser()
    try:
        out_path.mkdir(parents=True, exist_ok=True)
    except OSError as e:
        raise HTTPException(400, f"无法创建输出目录: {e}") from e

    items: list[DownloadItem] = []
    for raw in body.episodes:
        ep = Episode(
            index=int(raw.get("index") or 0),
            title=str(raw.get("title") or "Untitled"),
            audio_url=str(raw.get("audio_url") or raw.get("url") or ""),
            published=str(raw.get("published") or raw.get("date") or ""),
            duration=str(raw.get("duration") or ""),
            guid=str(raw.get("guid") or ""),
            size=int(raw.get("size") or 0),
        )
        if not ep.audio_url:
            continue
        items.append(DownloadItem(episode=ep))

    if not items:
        raise HTTPException(400, "没有有效的音频地址")

    job_id = uuid.uuid4().hex[:12]
    job = DownloadJob(
        id=job_id,
        show_name=body.show_name or "Podcast",
        out_dir=str(out_path.resolve()),
        items=items,
        artwork=body.artwork or "",
    )
    async with _jobs_lock:
        _jobs[job_id] = job
    _persist_job(job)

    concurrency = body.concurrency or persist.get()["concurrency"]

    async def _run() -> None:
        await _run_job_with_retries(job, concurrency)

    asyncio.create_task(_run())
    return {"job_id": job_id, "job": job.to_dict()}


@app.get("/api/download/{job_id}")
async def api_job(job_id: str) -> dict[str, Any]:
    job = _jobs.get(job_id)
    if not job:
        raise HTTPException(404, "任务不存在")
    return job.to_dict()


@app.post("/api/download/{job_id}/cancel")
async def api_cancel(job_id: str) -> dict[str, Any]:
    job = _jobs.get(job_id)
    if not job:
        raise HTTPException(404, "任务不存在")
    if job.status in ("done", "error"):
        return job.to_dict()
    job.status = "cancelled"
    job.message = "已取消"
    _persist_job(job)
    return job.to_dict()


@app.post("/api/download/{job_id}/retry")
async def api_retry_failed(job_id: str) -> dict[str, Any]:
    """Re-download items that failed (keeps partial files and resumes)."""
    job = _jobs.get(job_id)
    if not job:
        raise HTTPException(404, "任务不存在")
    if job.status == "running":
        raise HTTPException(409, "任务仍在进行中，请稍后再试")

    failed = [i for i in job.items if i.status == "error"]
    if not failed:
        raise HTTPException(400, "没有失败的条目可重试")

    # reset failed items to pending for a clean retry pass
    for item in failed:
        item.status = "pending"
        item.error = ""
        item.bytes_done = 0
        # keep path empty; partial file on disk still used by Range resume
        item.path = ""

    concurrency = int(persist.get().get("concurrency") or 32)
    _persist_job(job)

    async def _run() -> None:
        await _run_job_with_retries(job, concurrency, only_statuses={"pending"})

    asyncio.create_task(_run())
    return {
        "job_id": job_id,
        "retrying": len(failed),
        "job": job.to_dict(),
    }


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(
        STATIC / "index.html",
        headers={"Cache-Control": "no-cache, no-store, must-revalidate"},
    )


app.mount("/static", StaticFiles(directory=str(STATIC)), name="static")


def _show_source(show: dict[str, Any]) -> str:
    return str(show.get("feed_url") or show.get("id") or "").strip()


def _show_scan_days(show: dict[str, Any], st: dict[str, Any]) -> int:
    d = show.get("scan_days")
    return max(1, int(d)) if d is not None else max(1, int(st.get("auto_scan_days") or 7))


def _show_scan_limit(show: dict[str, Any], st: dict[str, Any]) -> int:
    lim = show.get("scan_limit")
    return int(lim) if lim is not None else int(st.get("auto_scan_limit") or 0)


async def run_auto_scan(
    *,
    reason: str = "schedule",
    shows: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Fetch shows (all subscribed, or a due subset), skip existing, download the rest."""
    global _scan_pending
    if _scan_lock.locked():
        _scan_pending = False
        return {"ok": False, "message": "已有扫描任务在进行"}
    async with _scan_lock:
        _scan_pending = False
        target_shows = shows if shows is not None else persist.subscribed()
        if not target_shows:
            msg = "没有关注的节目。先关注或导入 OPML。"
            persist.save({"last_auto_scan": int(time.time()), "last_auto_scan_message": msg})
            return {"ok": False, "message": msg, "queued": 0}
        st = persist.get()
        out_path = Path(st["out_dir"]).expanduser()
        concurrency = int(st.get("concurrency") or 8)
        mode = str(st.get("auto_scan_mode") or "new")
        queued = 0
        skipped_existing = 0
        failures: list[str] = []
        total = len(target_shows)
        for i, show in enumerate(target_shows, start=1):
            label = str(show.get("name") or show.get("feed_url") or "?")
            persist.save({"last_auto_scan_message": f"正在扫描 {i}/{total}：{label}"})
            src = _show_source(show)
            if not src:
                failures.append(f"{label}: 缺少 RSS")
                continue
            try:
                resolved, episodes = await resolve_source(src)
            except Exception as e:
                failures.append(f"{show.get('name') or src}: {e}")
                _log.warning("auto-scan resolve failed %s: %s", src, e)
                continue
            name = resolved.name or str(show.get("name") or "Podcast")
            sid = resolved.id or resolved.feed_url or name
            persist.save_episodes(sid, episodes)
            rows, local_done = mark_episodes_local(out_path, name, episodes)
            skipped_existing += local_done

            newest = episodes[0].guid if episodes else ""
            prev_guid = str(show.get("last_seen_guid") or "")
            candidates = list(zip(episodes, rows))
            if mode == "new" and prev_guid:
                cut = next(
                    (
                        j
                        for j, (ep, _row) in enumerate(candidates)
                        if (ep.guid or "").strip() and ep.guid.strip() == prev_guid
                    ),
                    None,
                )
                if cut is not None:
                    candidates = candidates[:cut]

            ignored: set[str] = set()
            try:
                ignored = {e["guid"] for e in store.list_episodes(sid) if e.get("ignored")}
            except Exception:
                pass

            limit = _show_scan_limit(show, st)
            pending = [
                ep
                for ep, row in candidates
                if not row.get("downloaded")
                and ep.audio_url
                and (ep.guid or "") not in ignored
            ]
            if limit > 0:
                pending = pending[:limit]

            persist.upsert_show(
                {
                    **show,
                    "id": sid,
                    "name": name,
                    "author": resolved.author or show.get("author") or "",
                    "artwork": resolved.artwork or show.get("artwork") or "",
                    "feed_url": resolved.feed_url or show.get("feed_url") or src,
                    "episode_count": len(episodes),
                    "last_seen_guid": newest,
                    "last_scan_ts": int(time.time()),
                    "scan_days": show.get("scan_days"),
                    "scan_limit": show.get("scan_limit"),
                },
                subscribed=True,
            )
            if not pending:
                continue
            job_id = uuid.uuid4().hex[:12]
            job = DownloadJob(
                id=job_id,
                show_name=name,
                out_dir=str(out_path.resolve()),
                items=[DownloadItem(episode=ep) for ep in pending],
                artwork=resolved.artwork or show.get("artwork") or "",
            )
            async with _jobs_lock:
                _jobs[job_id] = job
            await _run_job_with_retries(job, concurrency)
            queued += sum(1 for i in job.items if i.status in ("done", "skipped", "error"))
        parts = [f"扫描{len(target_shows)}档"]
        if queued:
            parts.append(f"处理 {queued} 集")
        if skipped_existing:
            parts.append(f"本地已有 {skipped_existing}")
        if failures:
            parts.append(f"失败 {len(failures)}")
        msg = " · ".join(parts)
        if failures:
            msg += " — " + "; ".join(failures[:5])
        persist.save({"last_auto_scan": int(time.time()), "last_auto_scan_message": msg})
        _log.info("auto-scan (%s): %s", reason, msg)
        return {
            "ok": True,
            "message": msg,
            "queued": queued,
            "local_existing": skipped_existing,
            "failures": failures,
            "reason": reason,
        }


async def _auto_scan_loop() -> None:
    await asyncio.sleep(15)
    while True:
        try:
            st = persist.get()
            if st.get("auto_scan"):
                now = time.time()
                due = [
                    s
                    for s in persist.subscribed()
                    if now - int(s.get("last_scan_ts") or 0)
                    >= _show_scan_days(s, st) * 86400
                ]
                if due:
                    await run_auto_scan(reason="schedule", shows=due)
        except asyncio.CancelledError:
            raise
        except Exception:
            _log.exception("auto-scan loop")
        await asyncio.sleep(600)


def _configure_stdio() -> None:
    if sys.platform != "win32":
        return
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def _port_in_use(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.4)
        return s.connect_ex((host, port)) == 0


def _existing_podbatch(host: str, port: int) -> bool:
    try:
        req = Request(
            f"http://{host}:{port}/api/health",
            headers={"Accept": "application/json"},
            method="GET",
        )
        with urlopen(req, timeout=1.5) as resp:
            data = json.loads(resp.read().decode("utf-8", "replace"))
        return bool(data.get("status") == "ok")
    except (URLError, TimeoutError, OSError, json.JSONDecodeError, ValueError):
        return False


def main() -> None:
    import threading

    _configure_stdio()
    persist.load()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    host = (os.environ.get("PODSTASH_HOST") or "127.0.0.1").strip() or "127.0.0.1"
    port = int(os.environ.get("PODSTASH_PORT") or "8765")
    probe_host = "127.0.0.1" if host in {"0.0.0.0", "::"} else host
    public = f"http://{probe_host}:{port}"

    if _port_in_use(probe_host, port):
        if _existing_podbatch(probe_host, port):
            print(f"\n  OMNIX-Podstash {app_version()} 已在运行 → {public}")
            print("  已打开浏览器。这个窗口可以关掉（不要关原来那个黑窗口）。\n")
            if host in {"127.0.0.1", "localhost"}:
                try:
                    webbrowser.open(public)
                except Exception:
                    pass
            return
        print(f"\n  端口 {port} 已被其他程序占用，无法启动。")
        print("  请关掉占用该端口的程序后重试，或改 PODSTASH_PORT。\n")
        sys.exit(1)

    open_browser = host in {"127.0.0.1", "localhost"} and os.environ.get("PODSTASH_NO_BROWSER") != "1"
    if open_browser:
        def _open_later() -> None:
            time.sleep(0.8)
            try:
                webbrowser.open(public)
            except Exception:
                pass

        threading.Thread(target=_open_later, daemon=True).start()
    print(f"\n  OMNIX-Podstash {app_version()} 已启动 → {public}")
    if host in {"0.0.0.0", "::"}:
        print(f"  局域网访问 http://<本机IP>:{port}")
    print("  私人播客库 · 搜索 / 关注 / 定期扫描未下载 · 已有文件会跳过")
    print("  关闭本窗口即可退出\n")
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
