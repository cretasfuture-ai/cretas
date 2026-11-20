#!/usr/bin/env python3
"""Organizer-operated, read-only checks for the staged Aurora chart tunnel on 47."""

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import stat
import subprocess


HOST = "127.0.0.1"
PORT = 11440
MODEL = "qwen3:14b"


def token_from_file(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("token_file_must_be_private")
    token = path.read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", token):
        raise ValueError("invalid_token_file")
    return token


def check_listener():
    result = subprocess.check_output(["ss", "-Hltn", "sport", "=", f":{PORT}"], text=True, timeout=4)
    addresses = [line.split()[3] for line in result.splitlines()]
    if addresses != [f"{HOST}:{PORT}"]:
        raise ValueError("chart_listener_not_exclusively_loopback")


def request(path, *, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token is not None else {}
    connection = http.client.HTTPConnection(HOST, PORT, timeout=3)
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        return response.status, json.loads(response.read(65536))
    finally:
        connection.close()


def check_protocol(token):
    if request("/health") != (200, {"status": "ready"}):
        raise ValueError("chart_health_failed")
    if request("/v1/models")[0] != 401:
        raise ValueError("chart_missing_authentication")
    status, models = request("/v1/models", token=token)
    if status != 200 or [row["id"] for row in models.get("data", [])] != [MODEL]:
        raise ValueError("chart_model_contract_failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path, required=True)
    args = parser.parse_args()
    check_listener()
    token = token_from_file(args.token_file)
    check_protocol(token)
    print(json.dumps({"listener": f"{HOST}:{PORT}", "protocol": "pass"}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, IndexError, KeyError, TypeError, http.client.HTTPException,
            subprocess.SubprocessError) as exc:
        message = exc.args[0] if type(exc) is ValueError else type(exc).__name__
        raise SystemExit(f"chart check failed: {message}") from None
