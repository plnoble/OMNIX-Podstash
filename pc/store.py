"""SQLite-backed store: settings, shows, episodes, download jobs.

Replaces the flat ``state.json`` so the library scales beyond a few hundred
episodes and download queues survive container restarts. Only the standard
library is used (``sqlite3``).
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator, Optional

_SCHEMA = """
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shows (
  id             TEXT PRIMARY KEY,
  name           TEXT NOT NULL,
  author         TEXT NOT NULL DEFAULT '',
  artwork        TEXT NOT NULL DEFAULT '',
  feed_url       TEXT NOT NULL DEFAULT '',
  episode_count  INTEGER NOT NULL DEFAULT 0,
  subscribed     INTEGER NOT NULL DEFAULT 1,
  last_seen_guid TEXT NOT NULL DEFAULT '',
  scan_days      INTEGER,
  scan_limit     INTEGER,
  last_scan_ts   INTEGER NOT NULL DEFAULT 0,
  updated_at     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS episodes (
  guid        TEXT PRIMARY KEY,
  show_id     TEXT NOT NULL,
  title       TEXT NOT NULL DEFAULT '',
  audio_url   TEXT NOT NULL DEFAULT '',
  published   TEXT NOT NULL DEFAULT '',
  duration    TEXT NOT NULL DEFAULT '',
  size        INTEGER NOT NULL DEFAULT 0,
  description TEXT NOT NULL DEFAULT '',
  local_path  TEXT NOT NULL DEFAULT '',
  ignored     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_episodes_show ON episodes(show_id);

CREATE TABLE IF NOT EXISTS jobs (
  id         TEXT PRIMARY KEY,
  show_name  TEXT NOT NULL DEFAULT '',
  out_dir    TEXT NOT NULL DEFAULT '',
  status     TEXT NOT NULL DEFAULT 'queued',
  message    TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS job_items (
  job_id      TEXT NOT NULL,
  idx         INTEGER NOT NULL,
  title       TEXT NOT NULL DEFAULT '',
  audio_url   TEXT NOT NULL DEFAULT '',
  guid        TEXT NOT NULL DEFAULT '',
  size        INTEGER NOT NULL DEFAULT 0,
  path        TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT 'pending',
  bytes_done  INTEGER NOT NULL DEFAULT 0,
  bytes_total INTEGER NOT NULL DEFAULT 0,
  error       TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (job_id, idx)
);
"""

_lock = threading.RLock()
_db_path: Optional[Path] = None


def configure(path: Path) -> None:
    """Point the store at a SQLite file (created on first use)."""
    global _db_path
    _db_path = Path(path)


def path() -> Optional[Path]:
    return _db_path


@contextmanager
def _db() -> Iterator[sqlite3.Connection]:
    if _db_path is None:
        raise RuntimeError("store not configured")
    _db_path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(str(_db_path), timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA synchronous=NORMAL")
    try:
        yield db
        db.commit()
    finally:
        db.close()


def init_schema() -> None:
    with _lock:
        with _db() as db:
            db.executescript(_SCHEMA)
            # WAL is a persistent file property; set once, not per connection.
            db.execute("PRAGMA journal_mode=WAL")


# ---------------------------------------------------------------- settings

def get_settings() -> dict[str, Any]:
    with _lock:
        with _db() as db:
            rows = db.execute("SELECT key, value FROM settings").fetchall()
    out: dict[str, Any] = {}
    for r in rows:
        try:
            out[r["key"]] = json.loads(r["value"])
        except (TypeError, ValueError):
            out[r["key"]] = r["value"]
    return out


def set_settings(patch: dict[str, Any]) -> None:
    if not patch:
        return
    with _lock:
        with _db() as db:
            db.executemany(
                "INSERT INTO settings(key, value) VALUES(?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                [(k, json.dumps(v, ensure_ascii=False)) for k, v in patch.items()],
            )


def has_settings() -> bool:
    with _lock:
        with _db() as db:
            row = db.execute("SELECT COUNT(*) AS n FROM settings").fetchone()
    return bool(row and row["n"])


# ------------------------------------------------------------------- shows

def _show_row(r: sqlite3.Row) -> dict[str, Any]:
    return {
        "id": r["id"],
        "name": r["name"],
        "author": r["author"],
        "artwork": r["artwork"],
        "feed_url": r["feed_url"],
        "episode_count": int(r["episode_count"] or 0),
        "subscribed": bool(r["subscribed"]),
        "last_seen_guid": r["last_seen_guid"],
        "scan_days": r["scan_days"],
        "scan_limit": r["scan_limit"],
        "last_scan_ts": int(r["last_scan_ts"] or 0),
    }


def list_shows() -> list[dict[str, Any]]:
    with _lock:
        with _db() as db:
            rows = db.execute(
                "SELECT * FROM shows ORDER BY subscribed DESC, updated_at DESC, name"
            ).fetchall()
    return [_show_row(r) for r in rows]


def get_show(key: str) -> Optional[dict[str, Any]]:
    with _lock:
        with _db() as db:
            row = db.execute("SELECT * FROM shows WHERE id = ?", (key,)).fetchone()
    return _show_row(row) if row else None


def upsert_show_record(rec: dict[str, Any]) -> None:
    with _lock:
        with _db() as db:
            db.execute(
                """
                INSERT INTO shows(
                  id, name, author, artwork, feed_url, episode_count,
                  subscribed, last_seen_guid, scan_days, scan_limit,
                  last_scan_ts, updated_at
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  name=excluded.name,
                  author=excluded.author,
                  artwork=excluded.artwork,
                  feed_url=excluded.feed_url,
                  episode_count=excluded.episode_count,
                  subscribed=excluded.subscribed,
                  last_seen_guid=excluded.last_seen_guid,
                  scan_days=excluded.scan_days,
                  scan_limit=excluded.scan_limit,
                  last_scan_ts=excluded.last_scan_ts,
                  updated_at=excluded.updated_at
                """,
                (
                    rec.get("id") or "",
                    rec.get("name") or "Podcast",
                    rec.get("author") or "",
                    rec.get("artwork") or "",
                    rec.get("feed_url") or "",
                    int(rec.get("episode_count") or 0),
                    1 if rec.get("subscribed") else 0,
                    rec.get("last_seen_guid") or "",
                    rec.get("scan_days"),
                    rec.get("scan_limit"),
                    int(rec.get("last_scan_ts") or 0),
                    int(time.time()),
                ),
            )


def delete_show(key: str) -> None:
    with _lock:
        with _db() as db:
            db.execute("DELETE FROM shows WHERE id = ?", (key,))


def reparent_episodes(from_key: str, to_key: str) -> None:
    """Move episodes of a duplicate show onto the kept show (dedup by name)."""
    if from_key == to_key or not from_key or not to_key:
        return
    with _lock:
        with _db() as db:
            db.execute(
                "UPDATE episodes SET show_id = ? WHERE show_id = ?",
                (to_key, from_key),
            )


# --------------------------------------------------------------- episodes

def _episode_row(r: sqlite3.Row) -> dict[str, Any]:
    return {
        "guid": r["guid"],
        "show_id": r["show_id"],
        "title": r["title"],
        "audio_url": r["audio_url"],
        "published": r["published"],
        "duration": r["duration"],
        "size": int(r["size"] or 0),
        "description": r["description"],
        "local_path": r["local_path"],
        "ignored": bool(r["ignored"]),
    }


def upsert_episode(ep: dict[str, Any]) -> None:
    guid = ep.get("guid") or ""
    if not guid:
        return
    with _lock:
        with _db() as db:
            db.execute(
                """
                INSERT INTO episodes(
                  guid, show_id, title, audio_url, published, duration,
                  size, description, local_path, ignored
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guid) DO UPDATE SET
                  show_id=excluded.show_id,
                  title=excluded.title,
                  audio_url=excluded.audio_url,
                  published=excluded.published,
                  duration=excluded.duration,
                  size=excluded.size,
                  description=excluded.description,
                  local_path=excluded.local_path,
                  ignored=excluded.ignored
                """,
                (
                    guid,
                    ep.get("show_id") or "",
                    ep.get("title") or "",
                    ep.get("audio_url") or "",
                    ep.get("published") or "",
                    ep.get("duration") or "",
                    int(ep.get("size") or 0),
                    ep.get("description") or "",
                    ep.get("local_path") or "",
                    1 if ep.get("ignored") else 0,
                ),
            )


def save_episodes(show_id: str, rows: list[dict[str, Any]]) -> None:
    """Batch-upsert episodes in a single connection/transaction (fast for large feeds)."""
    if not rows:
        return
    with _lock:
        with _db() as db:
            db.executemany(
                """
                INSERT INTO episodes(
                  guid, show_id, title, audio_url, published, duration,
                  size, description, local_path, ignored
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guid) DO UPDATE SET
                  show_id=excluded.show_id,
                  title=excluded.title,
                  audio_url=excluded.audio_url,
                  published=excluded.published,
                  duration=excluded.duration,
                  size=excluded.size,
                  description=excluded.description,
                  local_path=excluded.local_path,
                  ignored=excluded.ignored
                """,
                [
                    (
                        (r.get("guid") or "").strip(),
                        r.get("show_id") or show_id or "",
                        r.get("title") or "",
                        r.get("audio_url") or "",
                        r.get("published") or "",
                        r.get("duration") or "",
                        int(r.get("size") or 0),
                        r.get("description") or "",
                        r.get("local_path") or "",
                        1 if r.get("ignored") else 0,
                    )
                    for r in rows
                    if (r.get("guid") or "").strip()
                ],
            )


def get_episode(guid: str) -> Optional[dict[str, Any]]:
    with _lock:
        with _db() as db:
            row = db.execute("SELECT * FROM episodes WHERE guid = ?", (guid,)).fetchone()
    return _episode_row(row) if row else None


def list_episodes(show_id: str) -> list[dict[str, Any]]:
    with _lock:
        with _db() as db:
            rows = db.execute(
                "SELECT * FROM episodes WHERE show_id = ? ORDER BY rowid", (show_id,)
            ).fetchall()
    return [_episode_row(r) for r in rows]


def set_episode_ignored(guid: str, ignored: bool) -> None:
    with _lock:
        with _db() as db:
            db.execute(
                "UPDATE episodes SET ignored = ? WHERE guid = ?",
                (1 if ignored else 0, guid),
            )


def search_episodes(q: str, limit: int = 30) -> list[dict[str, Any]]:
    needle = f"%{q.lower()}%"
    with _lock:
        with _db() as db:
            rows = db.execute(
                """
                SELECT e.*, s.name AS show_name
                FROM episodes e
                LEFT JOIN shows s ON s.id = e.show_id
                WHERE lower(e.title) LIKE ? OR lower(e.description) LIKE ?
                ORDER BY e.rowid DESC
                LIMIT ?
                """,
                (needle, needle, int(limit)),
            ).fetchall()
    out: list[dict[str, Any]] = []
    for r in rows:
        desc = r["description"] or ""
        out.append(
            {
                "guid": r["guid"],
                "show_id": r["show_id"],
                "show_name": r["show_name"] or "",
                "title": r["title"],
                "published": r["published"],
                "duration": r["duration"],
                "local_path": r["local_path"],
                "snippet": (desc[:240] + ("…" if len(desc) > 240 else "")) if desc else "",
            }
        )
    return out


# ------------------------------------------------------------------- jobs

def save_job(job: dict[str, Any], items: list[dict[str, Any]]) -> None:
    with _lock:
        with _db() as db:
            db.execute(
                """
                INSERT INTO jobs(id, show_name, out_dir, status, message, created_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                  show_name=excluded.show_name,
                  out_dir=excluded.out_dir,
                  status=excluded.status,
                  message=excluded.message
                """,
                (
                    job.get("id") or "",
                    job.get("show_name") or "",
                    job.get("out_dir") or "",
                    job.get("status") or "queued",
                    job.get("message") or "",
                    int(job.get("created_at") or time.time()),
                ),
            )
            db.execute("DELETE FROM job_items WHERE job_id = ?", (job.get("id") or "",))
            db.executemany(
                """
                INSERT INTO job_items(
                  job_id, idx, title, audio_url, guid, size,
                  path, status, bytes_done, bytes_total, error
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        job.get("id") or "",
                        int(it.get("index") or it.get("idx") or 0),
                        it.get("title") or "",
                        it.get("audio_url") or "",
                        it.get("guid") or "",
                        int(it.get("size") or 0),
                        it.get("path") or "",
                        it.get("status") or "pending",
                        int(it.get("bytes_done") or 0),
                        int(it.get("bytes_total") or 0),
                        it.get("error") or "",
                    )
                    for it in items
                ],
            )


def load_jobs() -> list[dict[str, Any]]:
    with _lock:
        with _db() as db:
            jobs = db.execute("SELECT * FROM jobs ORDER BY created_at DESC").fetchall()
            items = db.execute("SELECT * FROM job_items ORDER BY idx").fetchall()
    by_job: dict[str, list[dict[str, Any]]] = {}
    for it in items:
        by_job.setdefault(it["job_id"], []).append(
            {
                "index": int(it["idx"]),
                "title": it["title"],
                "audio_url": it["audio_url"],
                "guid": it["guid"],
                "size": int(it["size"] or 0),
                "path": it["path"],
                "status": it["status"],
                "bytes_done": int(it["bytes_done"]),
                "bytes_total": int(it["bytes_total"]),
                "error": it["error"],
            }
        )
    out: list[dict[str, Any]] = []
    for j in jobs:
        out.append(
            {
                "id": j["id"],
                "show_name": j["show_name"],
                "out_dir": j["out_dir"],
                "status": j["status"],
                "message": j["message"],
                "created_at": j["created_at"],
                "items": by_job.get(j["id"], []),
            }
        )
    return out


def delete_job(job_id: str) -> None:
    with _lock:
        with _db() as db:
            db.execute("DELETE FROM job_items WHERE job_id = ?", (job_id,))
            db.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
