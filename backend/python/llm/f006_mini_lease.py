"""Immediate host-wide lease for the single-host, multi-worker Mini pilot.

SQLite's OS locks work across workers and are released on process exit. This
private, empty coordination database never stores prompts or ERP data.
"""
from __future__ import annotations

import os
from pathlib import Path
import sqlite3
import stat
import tempfile


class MiniBusy(Exception):
    pass


class HostLease:
    def __init__(self, connection: sqlite3.Connection):
        self.connection = connection

    def close(self) -> None:
        self.connection.close()


def acquire_host_lease(directory: Path | None = None) -> HostLease:
    directory = directory or Path(tempfile.gettempdir()) / "cretas-f006-mini-pilot"
    directory.mkdir(mode=0o700, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode):
        raise OSError("pilot lock directory is not a directory")
    if os.name == "posix" and (info.st_uid != os.geteuid() or info.st_mode & 0o077):
        raise OSError("pilot lock directory is not private")
    path = directory / "inflight.sqlite"
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    try:
        file_info = os.fstat(descriptor)
        if not stat.S_ISREG(file_info.st_mode):
            raise OSError("pilot lease is not a regular file")
        if os.name == "posix" and (file_info.st_uid != os.geteuid() or file_info.st_mode & 0o077):
            raise OSError("pilot lease is not private")
    finally:
        os.close(descriptor)
    connection = sqlite3.connect(str(path), timeout=0, isolation_level=None)
    try:
        connection.execute("BEGIN IMMEDIATE")
        return HostLease(connection)
    except sqlite3.OperationalError as error:
        connection.close()
        if getattr(error, "sqlite_errorcode", None) in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED):
            raise MiniBusy() from None
        raise
    except BaseException:
        connection.close()
        raise
