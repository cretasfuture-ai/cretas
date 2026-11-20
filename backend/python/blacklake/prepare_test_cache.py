"""Password-protected local PoC token cache, independent from synthetic test Redis."""
import json
import os
import secrets
import subprocess
import time
from pathlib import Path

import redis

from .contracts import BlacklakeError
from .secrets import save_local_secret, load_local_secret
from .readiness import container_port


def verify(saved):
    port = container_port(saved["container_id"], "6379/tcp", "redis:7-alpine")
    from urllib.parse import urlparse
    destination = urlparse(saved["url"])
    if destination.hostname != "127.0.0.1" or destination.port != port or not destination.password:
        raise BlacklakeError("LOCAL_TEST_CACHE_IDENTITY_MISMATCH")
    target = redis.Redis.from_url(saved["url"])
    try:
        target.ping()
        anonymous = redis.Redis(host="127.0.0.1", port=port)
        try:
            try:
                anonymous.ping()
            except redis.exceptions.AuthenticationError:
                return
            raise BlacklakeError("TEST_CACHE_DOES_NOT_REQUIRE_AUTH")
        finally:
            anonymous.close()
    finally:
        target.close()


def main():
    directory = Path(os.environ["LOCALAPPDATA"]) / "Cretas/Blacklake"
    evidence = directory / "test-cache.dpapi"
    if evidence.exists():
        saved = load_local_secret(evidence)
        verify(saved)
        saved["initialization_complete"] = True
        save_local_secret(evidence, saved)
        print(json.dumps({"success": True, "message": "existing protected test cache verified"}))
        return
    password = secrets.token_urlsafe(32)
    result = subprocess.run([
        "docker", "run", "-d", "--name", "cretas-blacklake-realdata-redis-20261103",
        "--memory", "128m", "-p", "127.0.0.1::6379",
        "redis:7-alpine", "sh", "-c",
        'while [ ! -s /data/blacklake.conf ]; do sleep 0.2; done; exec redis-server /data/blacklake.conf'],
        capture_output=True, text=True)
    if result.returncode:
        raise BlacklakeError("TEST_CACHE_CREATE_FAILED_NO_RESET_PERFORMED")
    container_id = result.stdout.strip()
    subprocess.run(["docker", "exec", "-i", container_id, "sh", "-c",
                    "umask 077; cat > /data/blacklake.conf.tmp; mv /data/blacklake.conf.tmp /data/blacklake.conf"],
                   input="requirepass " + password + "\nappendonly yes\n", text=True,
                   capture_output=True, check=True)
    mapping = subprocess.run(["docker", "port", container_id, "6379"],
                             capture_output=True, text=True, check=True).stdout.strip()
    if not mapping.startswith("127.0.0.1:"):
        raise BlacklakeError("TEST_CACHE_MUST_BE_LOOPBACK_ONLY")
    port = int(mapping.rsplit(":", 1)[1])
    url = f"redis://:{password}@127.0.0.1:{port}/0"
    saved = {"url": url, "container_id": container_id, "initialization_complete": False}
    save_local_secret(evidence, saved)
    target = redis.Redis.from_url(url)
    for _ in range(30):
        try:
            target.ping()
            break
        except redis.exceptions.ConnectionError:
            time.sleep(1)
    else:
        raise BlacklakeError("TEST_CACHE_START_TIMEOUT")
    target.close()
    verify(saved)
    saved["initialization_complete"] = True
    save_local_secret(evidence, saved)
    print(json.dumps({"success": True, "data": {"host": "127.0.0.1", "port": port},
                      "message": "protected local cache initialized; credentials encrypted"}))


if __name__ == "__main__":
    main()
