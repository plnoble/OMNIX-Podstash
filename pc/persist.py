"""Persistent settings, subscriptions, and OPML for the PC / Docker library."""

from __future__ import annotations

import json
import os
import re
import threading
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

from core import Show, default_out_dir

_lock = threading.RLock()
_state: dict[str, Any] = {}
_path: Path | None = None


def _blank_state() -> dict[str, Any]:
    return {
        "out_dir": str(default_out_dir()),
        "concurrency": 32,
        "auto_scan": False,
        "auto_scan_days": 7,
        "auto_scan_limit": 30,
        "last_auto_scan": 0,
        "last_auto_scan_message": "",
        "shows": [],
    }


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


def show_key(show: dict[str, Any] | Show) -> str:
    if isinstance(show, Show):
        return (show.id or show.feed_url or show.name).strip()
    return str(show.get("id") or show.get("feed_url") or show.get("name") or "").strip()


def _apply_env(state: dict[str, Any], *, first_boot: bool) -> None:
    out = (os.environ.get("PODSTASH_OUT_DIR") or "").strip()
    if out:
        state["out_dir"] = out
    elif Path("/podcasts").is_dir():
        state["out_dir"] = "/podcasts"
    conc = (os.environ.get("PODSTASH_CONCURRENCY") or "").strip()
    if conc.isdigit():
        state["concurrency"] = max(1, min(int(conc), 32))
    if not first_boot:
        return
    flag = (os.environ.get("PODSTASH_AUTO_SCAN") or "").strip().lower()
    if flag in {"1", "true", "yes", "on"}:
        state["auto_scan"] = True
    elif flag in {"0", "false", "no", "off"}:
        state["auto_scan"] = False
    days = (os.environ.get("PODSTASH_AUTO_SCAN_DAYS") or "").strip()
    if days.isdigit():
        state["auto_scan_days"] = max(1, min(int(days), 30))
    limit = (os.environ.get("PODSTASH_AUTO_SCAN_LIMIT") or "").strip()
    if limit.isdigit():
        state["auto_scan_limit"] = max(0, min(int(limit), 5000))


def load() -> dict[str, Any]:
    global _state, _path
    path = state_path()
    _path = path
    first_boot = not path.exists()
    data = _blank_state()
    if path.exists():
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                data.update({k: raw[k] for k in data if k in raw})
                if isinstance(raw.get("shows"), list):
                    data["shows"] = raw["shows"]
        except (OSError, json.JSONDecodeError):
            pass
    _apply_env(data, first_boot=first_boot)
    try:
        Path(data["out_dir"]).expanduser().mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    with _lock:
        _state = data
    if first_boot:
        save()
    return get()


def get() -> dict[str, Any]:
    if _path is None:
        load()
    with _lock:
        if not _state:
            return _blank_state()
        return json.loads(json.dumps(_state))


def save(patch: dict[str, Any] | None = None) -> dict[str, Any]:
    with _lock:
        if not _state:
            _state.update(_blank_state())
        if patch:
            allowed = _blank_state()
            for k, v in patch.items():
                if k == "shows" or k in allowed:
                    _state[k] = v
        path = _path or state_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(_state, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)
        return json.loads(json.dumps(_state))


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
        "last_auto_scan": int(s.get("last_auto_scan") or 0),
        "last_auto_scan_message": str(s.get("last_auto_scan_message") or ""),
        "subscribed_count": len(shows),
        "config_dir": str(config_dir()),
        "library_label": (os.environ.get("PODSTASH_LIBRARY_LABEL") or "").strip(),
        "in_docker": Path("/.dockerenv").exists() or Path("/podcasts").is_dir(),
    }


def subscribed() -> list[dict[str, Any]]:
    s = get()
    out = []
    for raw in s.get("shows") or []:
        if isinstance(raw, dict) and raw.get("subscribed") and show_key(raw):
            out.append(raw)
    return out


def upsert_show(show: dict[str, Any], *, subscribed: bool | None = None) -> dict[str, Any]:
    key = show_key(show)
    if not key:
        raise ValueError("节目缺少 ID / RSS")
    rec = {
        "id": str(show.get("id") or ""),
        "name": str(show.get("name") or "Podcast"),
        "author": str(show.get("author") or ""),
        "artwork": str(show.get("artwork") or ""),
        "feed_url": str(show.get("feed_url") or ""),
        "episode_count": int(show.get("episode_count") or 0),
        "subscribed": bool(show.get("subscribed")),
        "last_seen_guid": str(show.get("last_seen_guid") or ""),
    }
    if subscribed is not None:
        rec["subscribed"] = subscribed
    with _lock:
        shows = list(_state.get("shows") or [])
        rest = [x for x in shows if isinstance(x, dict) and show_key(x) != key]
        old = next((x for x in shows if isinstance(x, dict) and show_key(x) == key), None)
        if old:
            rec = {**old, **rec}
            if subscribed is not None:
                rec["subscribed"] = subscribed
        _state["shows"] = rest + [rec]
    return save()


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
