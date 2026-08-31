"""OMNIX-Podstash — local podcast library (search, stash, no metadata proxy)."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
import sys
import time
import uuid
import webbrowser
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional
from urllib.error import URLError
from urllib.request import Request, urlopen

import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, PlainTextResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import catalog
import persist
from core import (
    DownloadItem,
    DownloadJob,
    Episode,
    mark_episodes_local,
    resolve_source,
    run_download_job,
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


@asynccontextmanager
async def lifespan(_app: FastAPI):
    persist.load()
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


class ResolveBody(BaseModel):
    source: str = Field(..., description="Apple ID / show URL / RSS URL")
    country: str = "CN"


class DownloadBody(BaseModel):
    show_name: str
    out_dir: Optional[str] = None
    concurrency: Optional[int] = None
    episodes: list[dict[str, Any]]


class SettingsBody(BaseModel):
    out_dir: Optional[str] = None
    concurrency: Optional[int] = None
    auto_scan: Optional[bool] = None
    auto_scan_days: Optional[int] = None
    auto_scan_limit: Optional[int] = None


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


class OpmlImportBody(BaseModel):
    xml: str


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


@app.post("/api/auto-scan")
async def api_auto_scan_now() -> dict[str, Any]:
    if _scan_lock.locked():
        raise HTTPException(409, "已有扫描任务在进行")
    result = await run_auto_scan(reason="manual")
    return result


@app.get("/api/auto-scan")
async def api_auto_scan_status() -> dict[str, Any]:
    s = persist.public_settings()
    return {
        "running": _scan_lock.locked(),
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

    out_path = _out_dir()
    local_rows, local_done = mark_episodes_local(out_path, show.name, episodes)
    ep_payload = []
    for e, local in zip(episodes, local_rows):
        row = e.to_dict()
        row.update(local)
        ep_payload.append(row)

    show_d = show.to_dict()
    show_d["subscribed"] = persist.is_subscribed(show_d)
    return {
        "show": show_d,
        "episodes": ep_payload,
        "via": via,
        "local_downloaded": local_done,
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
    local_rows, local_done = mark_episodes_local(out_dir, body.show_name, episodes)
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
    )
    async with _jobs_lock:
        _jobs[job_id] = job

    concurrency = body.concurrency or persist.get()["concurrency"]

    async def _run() -> None:
        await run_download_job(job, concurrency=concurrency)

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

    async def _run() -> None:
        await run_download_job(
            job,
            concurrency=concurrency,
            only_statuses={"pending"},
            max_attempts=4,
        )

    asyncio.create_task(_run())
    return {
        "job_id": job_id,
        "retrying": len(failed),
        "job": job.to_dict(),
    }


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


app.mount("/static", StaticFiles(directory=str(STATIC)), name="static")


def _show_source(show: dict[str, Any]) -> str:
    return str(show.get("feed_url") or show.get("id") or "").strip()


async def run_auto_scan(*, reason: str = "schedule") -> dict[str, Any]:
    """Fetch each subscribed show, skip files already on disk, download the rest."""
    if _scan_lock.locked():
        return {"ok": False, "message": "已有扫描任务在进行"}
    async with _scan_lock:
        shows = persist.subscribed()
        if not shows:
            msg = "没有关注的节目。先关注或导入 OPML。"
            persist.save({"last_auto_scan": int(time.time()), "last_auto_scan_message": msg})
            return {"ok": False, "message": msg, "queued": 0}
        st = persist.get()
        out_path = Path(st["out_dir"]).expanduser()
        limit = int(st.get("auto_scan_limit") or 0)
        concurrency = int(st.get("concurrency") or 8)
        queued = 0
        skipped_existing = 0
        failures: list[str] = []
        for show in shows:
            src = _show_source(show)
            if not src:
                failures.append(f"{show.get('name') or '?'}: 缺少 RSS")
                continue
            try:
                resolved, episodes = await resolve_source(src)
            except Exception as e:
                failures.append(f"{show.get('name') or src}: {e}")
                _log.warning("auto-scan resolve failed %s: %s", src, e)
                continue
            name = resolved.name or str(show.get("name") or "Podcast")
            rows, local_done = mark_episodes_local(out_path, name, episodes)
            skipped_existing += local_done
            pending = [
                ep
                for ep, row in zip(episodes, rows)
                if not row.get("downloaded") and ep.audio_url
            ]
            if limit > 0:
                pending = pending[:limit]
            newest = episodes[0].guid if episodes else ""
            persist.upsert_show(
                {
                    **show,
                    "id": resolved.id or show.get("id") or "",
                    "name": name,
                    "author": resolved.author or show.get("author") or "",
                    "artwork": resolved.artwork or show.get("artwork") or "",
                    "feed_url": resolved.feed_url or show.get("feed_url") or src,
                    "episode_count": len(episodes),
                    "last_seen_guid": newest,
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
            )
            async with _jobs_lock:
                _jobs[job_id] = job
            await run_download_job(job, concurrency=concurrency)
            queued += sum(1 for i in job.items if i.status in ("done", "skipped", "error"))
        parts = [f"扫描{len(shows)}档"]
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
                last = int(st.get("last_auto_scan") or 0)
                days = max(1, int(st.get("auto_scan_days") or 7))
                if time.time() - last >= days * 86400:
                    await run_auto_scan(reason="schedule")
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
