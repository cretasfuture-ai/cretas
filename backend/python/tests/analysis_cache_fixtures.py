"""Isolated test backend for public SmartBI cache contracts."""
import asyncio
import copy
import os
from uuid import uuid4

import pytest
from redis.asyncio import Redis

from common import smartbi_analysis_cache as storage


class MemoryCache:
    def __init__(self):
        self.values = {}
        self.pending = {}
        self.revoked = set()
        self.redis = self

    async def aclose(self):
        pass

    async def zscore(self, _name, token):
        return 1 if token in self.revoked else None

    async def renew_until_released(self, entry):
        pass

    async def acquire(self, key):
        while key.field in self.pending:
            await self.pending[key.field].wait()
        if key.field in self.values:
            revision, value = self.values[key.field]
            return storage.AnalysisEntry(key, revision, copy.deepcopy(value))
        self.pending[key.field] = asyncio.Event()
        return storage.AnalysisEntry(key, uuid4().hex, leased=True)

    async def complete(self, entry, value):
        if not entry.leased:
            return False
        if value is not None:
            self.values[entry.key.field] = (entry.revision, copy.deepcopy(value))
            if len(self.values) > storage._MAX_ENTRIES:
                del self.values[next(iter(self.values))]
        self.pending.pop(entry.key.field).set()
        return value is not None

    async def invalidate(self, scope, token, input_hash=""):
        digest, revision = token.split(".")
        field = storage.AnalysisKey(scope, digest).field
        entry = self.values.get(field)
        if not entry or entry[0] != revision or (input_hash and entry[1].get("input_hash") != input_hash):
            return False
        del self.values[field]
        self.revoked.add(f"{field}:{revision}")
        return True

    async def invalidate_chart_hash(self, scope, input_hash):
        for field, (revision, value) in list(self.values.items()):
            if field.startswith(scope.prefix + ":") and value.get("input_hash") == input_hash:
                await self.invalidate(scope, f"{field.split(':')[1]}.{revision}", input_hash)


@pytest.fixture
async def analysis_cache(monkeypatch):
    """Exercise the production client when a private Redis URL is supplied."""
    url = os.getenv("SMARTBI_TEST_ANALYSIS_REDIS_URL")
    if not url:
        if os.getenv("SMARTBI_TEST_REQUIRE_ANALYSIS_REDIS") == "1":
            pytest.fail("Python Gate must supply SMARTBI_TEST_ANALYSIS_REDIS_URL to run production Lua")
        cache = MemoryCache()
        monkeypatch.setattr(storage, "get_cache", lambda: cache)
        yield cache
        return

    namespace = uuid4().hex
    keys = {name: f"{{cretas:smartbi:analysis:v1}}:pytest:{namespace}:{name}" for name in (
        "_VALUES", "_INDEX", "_SEQUENCE", "_REVOKED",
    )}
    for name, value in keys.items():
        monkeypatch.setattr(storage, name, value)
    monkeypatch.setenv("SMARTBI_ANALYSIS_REDIS_URL", url)
    redis = Redis.from_url(url)
    await redis.ping()
    try:
        yield redis
    finally:
        await redis.delete(*keys.values())
        await redis.aclose()
