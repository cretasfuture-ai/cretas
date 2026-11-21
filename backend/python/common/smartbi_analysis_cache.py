"""Durable SmartBI model results scoped to the authenticated factory and permissions."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import unicodedata
from contextlib import suppress
from dataclasses import dataclass
from uuid import uuid4

from fastapi.encoders import jsonable_encoder
from redis.asyncio import Redis
from redis.exceptions import RedisError

logger = logging.getLogger(__name__)

# The hash and its eviction index share a Redis cluster hash tag. Only the
# generation lease expires. Successful analysis results have no time limit.
_VALUES = "{cretas:smartbi:analysis:v1}:values"
_INDEX = "{cretas:smartbi:analysis:v1}:insertion-order"
_SEQUENCE = "{cretas:smartbi:analysis:v1}:sequence"
_REVOKED = "{cretas:smartbi:analysis:v1}:revoked"
_MAX_ENTRIES = 2048
_MAX_REVOKED = 8192
_MAX_RESULT_BYTES = 128 * 1024
_LEASE_SECONDS = 180
_MAX_WAIT_SECONDS = 60
_TOKEN = re.compile(r"([a-f0-9]{64})\.([a-f0-9]{32})\Z")

_ACQUIRE = """
local entry = redis.call('HGET', KEYS[1], ARGV[1])
if entry then return {1, entry} end
if redis.call('SET', KEYS[2], ARGV[2], 'NX', 'EX', ARGV[3]) then
    return {2}
end
return {0}
"""

_COMPLETE = """
if redis.call('GET', KEYS[4]) ~= ARGV[2] then return 0 end
if ARGV[3] ~= '' then
    redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
    redis.call('ZADD', KEYS[2], redis.call('INCR', KEYS[3]), ARGV[1])
    local overflow = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[4])
    if overflow > 0 then
        local victims = redis.call('ZRANGE', KEYS[2], 0, overflow - 1)
        redis.call('HDEL', KEYS[1], unpack(victims))
        redis.call('ZREM', KEYS[2], unpack(victims))
    end
end
redis.call('DEL', KEYS[4])
return 1
"""

_INVALIDATE = """
local entry = redis.call('HGET', KEYS[1], ARGV[1])
if not entry then return 0 end
local record = cjson.decode(entry)
if record.revision ~= ARGV[2] then return 0 end
if ARGV[3] ~= '' and record.value.input_hash ~= ARGV[3] then return 0 end
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('ZADD', KEYS[3], redis.call('INCR', KEYS[4]), ARGV[1] .. ':' .. ARGV[2])
local overflow = redis.call('ZCARD', KEYS[3]) - tonumber(ARGV[4])
if overflow > 0 then redis.call('ZREMRANGEBYRANK', KEYS[3], 0, overflow - 1) end
return 1
"""

_RENEW = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
return redis.call('EXPIRE', KEYS[1], ARGV[2])
"""


def _json(value) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str)


def fingerprint(value) -> str:
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


def normalize_question(question: str) -> str:
    return unicodedata.normalize("NFC", question.strip())


@dataclass(frozen=True)
class AnalysisScope:
    factory: str
    permission: str

    @property
    def prefix(self) -> str:
        return fingerprint((self.factory, self.permission))


def request_scope(request, *, permission: str | None = None) -> AnalysisScope | None:
    """Use the identity already verified by JWTAuthMiddleware."""
    state = request.state
    factory = getattr(state, "factory_id", None)
    if not factory or getattr(state, "auth_method", None) not in {"jwt", "internal"}:
        return None
    role = str(getattr(state, "role", "") or "").strip().lower()
    tier = role if permission is None else permission
    return AnalysisScope(str(factory), tier) if tier else None


@dataclass(frozen=True)
class AnalysisKey:
    scope: AnalysisScope
    digest: str

    @property
    def field(self) -> str:
        return f"{self.scope.prefix}:{self.digest}"

    @property
    def lock(self) -> str:
        return f"{{cretas:smartbi:analysis:v1}}:lease:{self.field}"


def result_key(scope: AnalysisScope | None, surface: str, data, *,
               prompt_version: str, model_version: str, question: str = "") -> AnalysisKey | None:
    if scope is None:
        return None
    return AnalysisKey(scope, fingerprint((
        surface, prompt_version, model_version, normalize_question(question), data,
    )))


@dataclass
class AnalysisEntry:
    key: AnalysisKey | None
    revision: str = ""
    value: dict | None = None
    leased: bool = False

    @property
    def hit(self) -> bool:
        return self.value is not None

    @property
    def token(self) -> str | None:
        if self.key is not None and self.revision:
            return f"{self.key.digest}.{self.revision}"
        return None


class AnalysisCache:
    def __init__(self, redis: Redis):
        self.redis = redis

    async def acquire(self, key: AnalysisKey) -> AnalysisEntry:
        revision = uuid4().hex
        clock = asyncio.get_running_loop().time
        deadline = clock() + _MAX_WAIT_SECONDS
        try:
            while clock() < deadline:
                state, *data = await self.redis.eval(
                    _ACQUIRE, 2, _VALUES, key.lock, key.field, revision, _LEASE_SECONDS,
                )
                if state == 1:
                    record = json.loads(data[0])
                    return AnalysisEntry(key, record["revision"], record["value"])
                if state == 2:
                    return AnalysisEntry(key, revision, leased=True)
                await asyncio.sleep(0.05)
        except asyncio.CancelledError:
            # Redis may have applied SET NX even though the awaited reply was cancelled.
            with suppress(RedisError):
                await self.complete(AnalysisEntry(key, revision, leased=True), None)
            raise
        return AnalysisEntry(key)

    async def renew_until_released(self, entry: AnalysisEntry) -> None:
        while entry.leased:
            await asyncio.sleep(_LEASE_SECONDS / 3)
            try:
                renewed = await self.redis.eval(_RENEW, 1, entry.key.lock, entry.revision, _LEASE_SECONDS)
                if not renewed:
                    return
            except RedisError:
                logger.warning("SmartBI analysis cache lease renewal unavailable")

    async def complete(self, entry: AnalysisEntry, value: dict | None) -> bool:
        if not entry.leased:
            return False
        key = entry.key
        payload = ""
        if value is not None:
            try:
                payload = _json({"revision": entry.revision, "value": jsonable_encoder(value)})
            except (ValueError, TypeError):
                logger.warning("SmartBI analysis result cannot be serialized")
        if len(payload.encode("utf-8")) > _MAX_RESULT_BYTES:
            payload = ""
        accepted = await self.redis.eval(
            _COMPLETE, 4, _VALUES, _INDEX, _SEQUENCE, key.lock,
            key.field, entry.revision, payload, _MAX_ENTRIES,
        )
        return bool(accepted and payload)

    async def invalidate(self, scope: AnalysisScope, token: str, input_hash: str = "") -> bool:
        match = _TOKEN.fullmatch(token)
        if not match:
            return False
        digest, revision = match.groups()
        return bool(await self.redis.eval(
            _INVALIDATE, 4, _VALUES, _INDEX, _REVOKED, _SEQUENCE,
            AnalysisKey(scope, digest).field, revision, input_hash, _MAX_REVOKED,
        ))

    async def invalidate_chart_hash(self, scope: AnalysisScope, input_hash: str) -> bool:
        """Accept votes from chart clients shipped before cacheToken."""
        removed = False
        async for raw_field, raw_record in self.redis.hscan_iter(_VALUES, match=f"{scope.prefix}:*"):
            record = json.loads(raw_record)
            if record["value"].get("input_hash") == input_hash:
                digest = raw_field.decode().split(":", 1)[1]
                removed |= await self.invalidate(scope, f"{digest}.{record['revision']}", input_hash)
        return removed


def get_cache() -> AnalysisCache:
    # A request owns its Redis client, avoiding reuse across async event loops.
    url = os.getenv("SMARTBI_ANALYSIS_REDIS_URL", "redis://127.0.0.1:6379/0")
    return AnalysisCache(Redis.from_url(url, socket_connect_timeout=0.3, socket_timeout=1))


class AnalysisCacheUnavailable(RuntimeError):
    """Redis could not acknowledge an invalidation."""


async def is_revoked(scope: AnalysisScope | None, token: str) -> bool:
    """Look up an explicit vote. Missing/evicted values do not revoke a projection."""
    match = _TOKEN.fullmatch(token)
    if scope is None or not match:
        return False
    digest, revision = match.groups()
    cache = None
    try:
        cache = get_cache()
        field = AnalysisKey(scope, digest).field
        return await cache.redis.zscore(_REVOKED, f"{field}:{revision}") is not None
    except (RedisError, ValueError):
        logger.warning("SmartBI analysis cache revision unavailable")
        return False
    finally:
        if cache is not None:
            await cache.redis.aclose()


async def invalidate(scope: AnalysisScope, token: str | None, *, input_hash: str = "") -> bool:
    cache = None
    try:
        cache = get_cache()
        if token:
            return await cache.invalidate(scope, token, input_hash)
        return bool(input_hash) and await cache.invalidate_chart_hash(scope, input_hash)
    except (RedisError, ValueError):
        raise AnalysisCacheUnavailable from None
    finally:
        if cache is not None:
            await cache.redis.aclose()


class analysis_result:
    """Reserve a cache miss, releasing the lease on failures and cancellation."""

    def __init__(self, key: AnalysisKey | None):
        self.key = key
        self.cache: AnalysisCache | None = None
        self.entry = AnalysisEntry(None)
        self.value: dict | None = None
        self._renewal: asyncio.Task | None = None

    async def __aenter__(self):
        if self.key is not None:
            try:
                self.cache = get_cache()
                self.entry = await self.cache.acquire(self.key)
                if self.entry.leased:
                    self._renewal = asyncio.create_task(self.cache.renew_until_released(self.entry))
            except (RedisError, ValueError):
                logger.warning("SmartBI analysis cache read unavailable")
            except asyncio.CancelledError:
                if self.cache is not None:
                    await self.cache.redis.aclose()
                raise
        return self

    @property
    def response_fields(self) -> dict:
        token = self.entry.token if self.entry.hit or self.value is not None else None
        return {"cacheHit": self.entry.hit, **({"cacheToken": token} if token else {})}

    async def store(self, value: dict) -> None:
        if self.entry.leased:
            try:
                if await self.cache.complete(self.entry, value):
                    self.value = value
                self.entry.leased = False
            except RedisError:
                logger.warning("SmartBI analysis cache write unavailable")

    async def __aexit__(self, exc_type, exc, tb):
        if self._renewal is not None:
            self._renewal.cancel()
            with suppress(asyncio.CancelledError):
                await self._renewal
        if self.cache is not None:
            try:
                await self.cache.complete(self.entry, None)
            except RedisError:
                logger.warning("SmartBI analysis cache lease release unavailable")
            finally:
                await self.cache.redis.aclose()


async def cached_response(key, generate, *, cacheable):
    """Run an existing JSON analysis, retaining only completed model results."""
    async with analysis_result(key) as cached:
        if cached.entry.hit:
            return {**cached.entry.value, **cached.response_fields}
        value = await generate()
        if cacheable(value):
            await cached.store(value)
        return {**value, **cached.response_fields}
