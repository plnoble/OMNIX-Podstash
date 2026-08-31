"""OMNIX-Podstash — local podcast library (search, stash, no metadata proxy)."""

from __future__ import annotations

import asyncio
import json
import socket
import sys
import uuid
import webbrowser
from pathlib import Path
from typing import Any, Optional
from urllib.error import URLError
from urllib.request import Request, urlopen

import uvicorn
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import catalog
from core import (
    DownloadItem,
    DownloadJob,
    Episode,
    default_out_dir,
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


app = FastAPI(title="OMNIX-Podstash", version=app_version())

_jobs: dict[str, DownloadJob] = {}
_jobs_lock = asyncio.Lock()
_settings: dict[str, Any] = {
    "out_dir": str(default_out_dir()),
    "concurrency": 32,
}


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


class LocalStatusBody(BaseModel):
    show_name: str
    out_dir: Optional[str] = None
    episodes: list[dict[str, Any]]


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
    }


@app.get("/api/settings")
async def get_settings() -> dict[str, Any]:
    return {
        "out_dir": _settings["out_dir"],
        "concurrency": _settings["concurrency"],
        "default_out_dir": str(default_out_dir()),
        "version": app_version(),
    }


@app.post("/api/settings")
async def set_settings(body: SettingsBody) -> dict[str, Any]:
    if body.out_dir is not None:
        p = Path(body.out_dir).expanduser()
        try:
            p.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            raise HTTPException(400, f"无法创建目录: {e}") from e
        _settings["out_dir"] = str(p.resolve())
    if body.concurrency is not None:
        _settings["concurrency"] = max(1, min(int(body.concurrency), 32))
    return await get_settings()


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

    out_path = Path(_settings["out_dir"]).expanduser()
    local_rows, local_done = mark_episodes_local(out_path, show.name, episodes)
    ep_payload = []
    for e, local in zip(episodes, local_rows):
        row = e.to_dict()
        row.update(local)
        ep_payload.append(row)

    return {
        "show": show.to_dict(),
        "episodes": ep_payload,
        "via": via,
        "local_downloaded": local_done,
        "out_dir": str(out_path),
    }


@app.post("/api/local-status")
async def api_local_status(body: LocalStatusBody) -> dict[str, Any]:
    """Scan the library folder and mark episodes whose audio is already on disk."""
    out_dir = Path(body.out_dir or _settings["out_dir"]).expanduser()
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

    out_dir = body.out_dir or _settings["out_dir"]
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

    concurrency = body.concurrency or _settings["concurrency"]

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

    concurrency = int(_settings.get("concurrency") or 32)

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
    import time

    _configure_stdio()

    host = "127.0.0.1"
    port = 8765
    url = f"http://{host}:{port}"

    if _port_in_use(host, port):
        if _existing_podbatch(host, port):
            print(f"\n  OMNIX-Podstash {app_version()} 已在运行 → {url}")
            print("  已打开浏览器。这个窗口可以关掉（不要关原来那个黑窗口）。\n")
            try:
                webbrowser.open(url)
            except Exception:
                pass
            return
        print(f"\n  端口 {port} 已被其他程序占用，无法启动。")
        print("  请关掉占用该端口的程序后重试，或改 app.py 里的 port。\n")
        sys.exit(1)

    def _open_later() -> None:
        time.sleep(0.8)
        try:
            webbrowser.open(url)
        except Exception:
            pass

    threading.Thread(target=_open_later, daemon=True).start()
    print(f"\n  OMNIX-Podstash {app_version()} 已启动 → {url}")
    print("  私人播客库 · 搜索 / 热门 / 全集下载 · 已有文件会跳过")
    print("  关闭本窗口即可退出\n")
    uvicorn.run(app, host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()
