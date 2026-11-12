"""Redis account-wide lock, renewable lease, token cache and rolling quotas."""
import hashlib
import json
import threading
import time
import uuid
from contextlib import contextmanager

from .contracts import BlacklakeError, ENDPOINTS

# All quota decisions use the Redis server clock and are atomic across workers.
LIMIT = """
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + tonumber(t[2]) / 1000
local spans = {1000, 3600000, 3600000}
local caps = {20, 10000, 75}
for i=1,3 do
  if i < 3 or ARGV[2] == '1' then
    redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now-spans[i])
    if redis.call('ZCARD', KEYS[i]) >= caps[i] then return 0 end
  end
end
if ARGV[3] == '1' and redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
for i=1,3 do
  if i < 3 or ARGV[2] == '1' then
    redis.call('ZADD', KEYS[i], now, ARGV[1])
    redis.call('PEXPIRE', KEYS[i], spans[i])
  end
end
if ARGV[3] == '1' then redis.call('SET', KEYS[4], '1', 'PX', 10500) end
return 1
"""


class RedisCoordination:
    def __init__(self, redis, account_key, *, allow_insecure_synthetic_test=False):
        options = redis.connection_pool.connection_kwargs
        if allow_insecure_synthetic_test:
            if not account_key.startswith("blacklake-test:"):
                raise BlacklakeError("SYNTHETIC_CACHE_NAMESPACE_REQUIRED")
        else:
            if not options.get("password"):
                raise BlacklakeError("REDIS_CREDENTIALS_REQUIRED")
            if options.get("host") not in ("127.0.0.1", "localhost", "::1"):
                from redis.connection import SSLConnection
                if not issubclass(redis.connection_pool.connection_class, SSLConnection):
                    raise BlacklakeError("REMOTE_REDIS_TLS_REQUIRED")
        self.redis, self.key = redis, account_key
        self.lock = None
        self.lost = threading.Event()

    @contextmanager
    def lease(self):
        self.lock = self.redis.lock(self.key + ":lock", timeout=60, thread_local=False)
        if not self.lock.acquire(blocking=False):
            raise BlacklakeError("CONNECTION_BUSY")
        stopped = threading.Event()
        self.lost.clear()

        def renew():
            while not stopped.wait(10):
                try:
                    self.lock.extend(60, replace_ttl=True)
                except Exception:
                    self.lost.set()
                    return

        worker = threading.Thread(target=renew, daemon=True)
        worker.start()
        try:
            yield
            self.check_lease()
        finally:
            stopped.set()
            worker.join(timeout=11)
            try:
                self.lock.release()
            except Exception:
                self.lost.set()
            self.lock = None

    def check_lease(self):
        if self.lock is None or self.lost.is_set() or not self.lock.owned():
            raise BlacklakeError("CONNECTION_LEASE_LOST")

    def acquire(self, path, body, login=False):
        digest = hashlib.sha256((path + json.dumps(body, sort_keys=True)).encode()).hexdigest()
        # A ten-second duplicate guard is deliberately applied to retries too.
        duplicate = path in (ENDPOINTS["project"], ENDPOINTS["task"])
        deadline = time.monotonic() + 45
        while True:
            self.check_lease()
            admitted = self.redis.eval(LIMIT, 4, self.key + ":qps", self.key + ":hour",
                                       self.key + ":login", self.key + ":request:" + digest,
                                       uuid.uuid4().hex, int(login), int(duplicate))
            if admitted:
                return
            if time.monotonic() >= deadline:
                raise BlacklakeError("RATE_LIMIT_DEFERRED")
            time.sleep(0.1)

    def get_token(self):
        token = self.redis.get(self.key + ":token")
        return token.decode() if isinstance(token, bytes) else token

    def get_factory(self):
        factory = self.redis.get(self.key + ":factory")
        return factory.decode() if isinstance(factory, bytes) else factory

    def set_token(self, token, factory=None):
        # Local retention bound; does not claim an upstream expiration policy.
        with self.redis.pipeline() as pipeline:
            pipeline.set(self.key + ":token", token, ex=86400)
            if factory:
                pipeline.set(self.key + ":factory", factory, ex=86400)
            else:
                pipeline.delete(self.key + ":factory")
            pipeline.execute()

    def clear_token(self):
        self.redis.delete(self.key + ":token", self.key + ":factory")
