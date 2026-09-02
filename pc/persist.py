"""Persistent settings, subscriptions, and OPML for the PC / Docker library.

Backed by SQLite (``store.py``); the legacy flat ``state.json`` is migrated
automatically on first load after upgrade.
"""

from __future__ import annotations

import json
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

import store
from core import Show, default_out_dir

_DEFAULTS: dict[str, Any] = {
    "out_dir": "",
    "concurrency": 32,
    "auto_scan": False,
    "auto_scan_days": 7,
    "auto_scan_limit": 30,
    "auto_scan_mode": "new",
    "last_auto_scan": 0,
    "last_auto_scan_message": "",
}

_cache: dict[str, Any] = {}
_loaded = False


def _blank_state() -> dict[str, Any]:
    out = dict(_DEFAULTS)
    out["out_dir"] = str(default_out_dir())
    return out


def config_dir() -> Path:
    env = (os.environ.get("PODSTASH_CONFIG") or "").strip()
    if env:
        p = Path(env).expanduser()
    elif Path("/.dockerenv").exists() or Path("/config").is_dir():
        p = Path("/config")
    else:
        p = Path.home() / ".omnix-podstash"
    p.mkdir(parents=True, exist_ok=True)
    return p


def state_path() -> Path:
    return config_dir() / "state.json"


def db_path() -> Path:
    return config_dir() / "podstash.db"


def show_key(show: dict[str, Any] | Show) -> str:
    if isinstance(show, Show):
        return (show.id or show.feed_url or show.name).strip()
    return str(show.get("id") or show.get("feed_url") or show.get("name") or "").strip()


def _name_key(name: str) -> str:
    """Collapse a show name for dedup (whitespace-insensitive, casefold)."""
    return re.sub(r"\s+", "", (name or "")).casefold().strip()


def _normalize_show(show: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": str(show.get("id") or ""),
        "name": str(show.get("name") or "Podcast"),
        "author": str(show.get("author") or ""),
        "artwork": str(show.get("artwork") or ""),
        "feed_url": str(show.get("feed_url") or ""),
        "episode_count": int(show.get("episode_count") or 0),
        "subscribed": bool(show.get("subscribed"))
        if show.get("subscribed") is not None
        else True,
        "last_seen_guid": str(show.get("last_seen_guid") or ""),
        "scan_days": show.get("scan_days") if show.get("scan_days") is not None else None,
        "scan_limit": show.get("scan_limit") if show.get("scan_limit") is not None else None,
        "last_scan_ts": int(show.get("last_scan_ts") or 0),
    }


def _coerce(key: str, value: Any) -> Any:
    d = _DEFAULTS.get(key)
    if isinstance(d, bool):
        return bool(value)
    if isinstance(d, int):
        try:
            return int(value)
        except (TypeError, ValueError):
            return d
    return str(value) if value is not None else ""


def _apply_env(data: dict[str, Any], *, first_boot: bool) -> None:
    out = (os.environ.get("PODSTASH_OUT_DIR") or "").strip()
    if out:
        data["out_dir"] = out
    elif Path("/podcasts").is_dir():
        data["out_dir"] = "/podcasts"
    conc = (os.environ.get("PODSTASH_CONCURRENCY") or "").strip()
    if conc.isdigit():
        data["concurrency"] = max(1, min(int(conc), 32))
    if not first_boot:
        return
    flag = (os.environ.get("PODSTASH_AUTO_SCAN") or "").strip().lower()
    if flag in {"1", "true", "yes", "on"}:
        data["auto_scan"] = True
    elif flag in {"0", "false", "no", "off"}:
        data["auto_scan"] = False
    days = (os.environ.get("PODSTASH_AUTO_SCAN_DAYS") or "").strip()
    if days.isdigit():
        data["auto_scan_days"] = max(1, min(int(days), 30))
    limit = (os.environ.get("PODSTASH_AUTO_SCAN_LIMIT") or "").strip()
    if limit.isdigit():
        data["auto_scan_limit"] = max(0, min(int(limit), 5000))


def _migrate_from_state_json() -> None:
    path = state_path()
    if not path.exists():
        return
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        raw = None
    if not isinstance(raw, dict):
        return
    patch: dict[str, Any] = {}
    for k in _DEFAULTS:
        if k in raw:
            patch[k] = raw[k]
    store.set_settings(patch)
    shows = raw.get("shows")
    if isinstance(shows, list):
        for s in shows:
            if isinstance(s, dict) and show_key(s):
                store.upsert_show_record(_normalize_show(s))
    try:
        path.replace(path.with_suffix(".json.bak"))
    except OSError:
        pass


def _dedupe_shows() -> None:
    """Merge shows that share the same name (e.g. Apple vs 小宇宙/喜马拉雅 entry)."""
    shows = store.list_shows()
    seen: dict[str, str] = {}
    for s in shows:
        nk = _name_key(s.get("name") or "")
        if not nk:
            continue
        key = show_key(s)
        keep = seen.get(nk)
        if keep is None:
            seen[nk] = key
            continue
        if keep == key:
            continue
        # Merge the duplicate into the kept show, then drop the duplicate row.
        try:
            store.reparent_episodes(key, keep)
            store.delete_show(key)
        except Exception:
            pass


def load() -> dict[str, Any]:
    global _cache, _loaded
    store.configure(db_path())
    store.init_schema()
    path = state_path()
    had_db_settings = store.has_settings()
    first_boot = (not had_db_settings) and (not path.exists())
    if not had_db_settings and path.exists():
        _migrate_from_state_json()
    _dedupe_shows()

    data = _blank_state()
    data.update(store.get_settings())
    _apply_env(data, first_boot=first_boot)
    try:
        Path(data["out_dir"]).expanduser().mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    _cache = data
    _loaded = True
    if first_boot:
        save()
    return get()


def get() -> dict[str, Any]:
    if not _loaded:
        load()
    out = dict(_cache)
    out["shows"] = store.list_shows()
    return json.loads(json.dumps(out))


def save(patch: dict[str, Any] | None = None) -> dict[str, Any]:
    global _cache
    if not _loaded:
        load()
    if patch:
        for k, v in patch.items():
            if k in _DEFAULTS:
                _cache[k] = _coerce(k, v)
    store.set_settings({k: _cache[k] for k in _DEFAULTS if k in _cache})
    return get()


def public_settings() -> dict[str, Any]:
    s = get()
    shows = subscribed()
    return {
        "out_dir": s["out_dir"],
        "concurrency": s["concurrency"],
        "default_out_dir": str(default_out_dir()),
        "auto_scan": bool(s.get("auto_scan")),
        "auto_scan_days": int(s.get("auto_scan_days") or 7),
        "auto_scan_limit": int(s.get("auto_scan_limit") or 0),
        "auto_scan_mode": str(s.get("auto_scan_mode") or "new"),
        "last_auto_scan": int(s.get("last_auto_scan") or 0),
        "last_auto_scan_message": str(s.get("last_auto_scan_message") or ""),
        "subscribed_count": len(shows),
        "config_dir": str(config_dir()),
        "library_label": (os.environ.get("PODSTASH_LIBRARY_LABEL") or "").strip(),
        "in_docker": Path("/.dockerenv").exists() or Path("/podcasts").is_dir(),
    }


def subscribed() -> list[dict[str, Any]]:
    return [s for s in store.list_shows() if s.get("subscribed")]


def upsert_show(show: dict[str, Any], *, subscribed: bool | None = None) -> dict[str, Any]:
    key = show_key(show)
    if not key:
        raise ValueError("节目缺少 ID / RSS")
    rec = _normalize_show(show)
    # 去重：同名节目已存在（例如 Apple 与小宇宙/喜马拉雅各加了一次）时合并到已有记录。
    nk = _name_key(rec["name"])
    if nk:
        for s in store.list_shows():
            if _name_key(s.get("name") or "") == nk and show_key(s) != key:
                key = show_key(s)
                rec["id"] = str(s.get("id") or rec["id"])
                rec["feed_url"] = str(s.get("feed_url") or rec["feed_url"])
                break
    old = store.get_show(key)
    if old:
        for k, v in rec.items():
            if k == "subscribed" or k == "episode_count" or v not in ("", None, 0):
                old[k] = v
        rec = old
    if subscribed is not None:
        rec["subscribed"] = bool(subscribed)
    else:
        rec["subscribed"] = bool(rec.get("subscribed", True))
    store.upsert_show_record(rec)
    return rec


def set_last_seen(show: dict[str, Any], guid: str) -> None:
    rec = dict(show)
    rec["last_seen_guid"] = guid
    rec["subscribed"] = True
    upsert_show(rec, subscribed=True)


def is_subscribed(show: dict[str, Any] | Show) -> bool:
    key = show_key(show)
    if not key:
        return False
    return any(show_key(s) == key for s in subscribed())


def save_episodes(show_id: str, episodes: list[Any]) -> None:
    """Persist per-episode metadata (incl. shownotes) for a show."""
    show_id = (show_id or "").strip()
    if not show_id:
        return
    rows = [
        {
            "guid": (getattr(ep, "guid", "") or "").strip(),
            "show_id": show_id,
            "title": getattr(ep, "title", "") or "",
            "audio_url": getattr(ep, "audio_url", "") or "",
            "published": getattr(ep, "published", "") or "",
            "duration": getattr(ep, "duration", "") or "",
            "size": int(getattr(ep, "size", 0) or 0),
            "description": getattr(ep, "description", "") or "",
            "local_path": "",
            "ignored": False,
        }
        for ep in episodes
        if (getattr(ep, "guid", "") or "").strip()
    ]
    store.save_episodes(show_id, rows)


def parse_opml(text: str) -> list[dict[str, Any]]:
    text = (text or "").strip()
    if not text:
        return []
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return []
    shows: list[dict[str, Any]] = []
    seen: set[str] = set()
    for el in root.iter():
        tag = el.tag.split("}")[-1].lower()
        if tag != "outline":
            continue
        attrib = {k.lower(): v for k, v in el.attrib.items()}
        feed = (attrib.get("xmlurl") or attrib.get("url") or "").strip()
        name = (attrib.get("text") or attrib.get("title") or "").strip()
        if not re.match(r"^https?://", feed, re.I):
            continue
        if feed in seen:
            continue
        seen.add(feed)
        shows.append(
            {
                "id": "",
                "name": name or feed,
                "author": "",
                "artwork": "",
                "feed_url": feed,
                "subscribed": True,
                "last_seen_guid": "",
            }
        )
    return shows


def write_opml(shows: list[dict[str, Any]]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<opml version="2.0">',
        "  <head>",
        "    <title>OMNIX-Podstash</title>",
        "  </head>",
        "  <body>",
    ]
    for s in shows:
        feed = str(s.get("feed_url") or "").strip()
        if not feed:
            continue
        name = (
            str(s.get("name") or feed)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace('"', "&quot;")
        )
        feed_esc = feed.replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;")
        lines.append(
            f'    <outline type="rss" text="{name}" title="{name}" xmlUrl="{feed_esc}"/>'
        )
    lines += ["  </body>", "</opml>", ""]
    return "\n".join(lines)
