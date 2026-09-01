#!/usr/bin/env python3
"""Container entrypoint: align volume ownership, then drop to a non-root uid.

Runs as root (started by Docker), fixes /podcasts and /config ownership to
PUID/PGID (default 1000:1000), then re-execs the app as that uid.
"""

from __future__ import annotations

import os
import sys


def _num(value: str | None, default: int) -> int:
    try:
        return int(value) if value else default
    except (TypeError, ValueError):
        return default


def _chown_tree(path: str, uid: int, gid: int) -> None:
    try:
        st = os.stat(path)
        if st.st_uid == uid and st.st_gid == gid:
            return
    except OSError:
        return
    for root, dirs, files in os.walk(path):
        for name in dirs + files:
            try:
                os.chown(os.path.join(root, name), uid, gid)
            except OSError:
                pass
        try:
            os.chown(root, uid, gid)
        except OSError:
            pass


def main() -> None:
    puid = _num(os.environ.get("PUID"), 1000)
    pgid = _num(os.environ.get("PGID"), 1000)

    for d in ("/podcasts", "/config"):
        _chown_tree(d, puid, pgid)

    if pgid:
        os.setgid(pgid)
    if puid:
        os.setuid(puid)

    os.environ.setdefault("PODSTASH_OUT_DIR", "/podcasts")
    os.environ.setdefault("PODSTASH_CONFIG", "/config")
    os.chdir("/app/pc")
    os.execv(sys.executable, [sys.executable, "-u", "app.py"])


if __name__ == "__main__":
    main()
