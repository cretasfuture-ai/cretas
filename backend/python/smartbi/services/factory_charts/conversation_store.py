"""Durable, tenant-scoped conversation state for multi-worker SmartBI."""
from __future__ import annotations

import json
import secrets
import copy
import time
from datetime import datetime, timedelta, timezone
from typing import Any

from smartbi.services.factory_charts import detail_store, details

TABLE = "smart_bi_factory_analysis_conversations"
CONTEXT_TTL_SECONDS = 300
LEASE_SECONDS = 180
MAX_CONTEXTS = 128
MAX_HISTORY = 8


def _owner(value: Any) -> tuple[str, Any, str]:
    return detail_store._owner(value)


def _row_to_context(row: Any, conversation_type: Any) -> Any:
    return conversation_type(
        context_id=str(row["context_id"]), factory_id=str(row["factory_id"]),
        user_id=int(row["user_id"]), role=str(row["role"]), source=str(row["source"]),
        upload_id=row["upload_id"], scope=json.loads(row["scope"]) if isinstance(row["scope"], str) else dict(row["scope"] or {}),
        selected_ids=list(json.loads(row["selected_ids"]) if isinstance(row["selected_ids"], str) else (row["selected_ids"] or [])),
        history=list(json.loads(row["history"]) if isinstance(row["history"], str) else (row["history"] or [])),
        unresolved_product=bool(row["unresolved_product"]), unresolved_period=bool(row["unresolved_period"]),
        period_error=row["period_error"], expires_at=row["expires_at"].timestamp(), version=int(row["version"]),
        lease_token=str(row["lease_token"]),
        persisted_version=int(row["version"]),
    )


class ConversationStore:
    """DB implementation. A fake may be injected explicitly by tests only."""
    def __init__(self, pool_getter=None):
        # Resolve the shared pool getter at call time so test/runtime pool
        # swaps used by the existing detail-store harness remain effective.
        self._pool_getter = pool_getter or (lambda: detail_store.config.get_pg_pool())

    async def _pool(self):
        try:
            pool = await self._pool_getter()
        except Exception as exc:
            raise details.DetailError(503, "对话共享存储暂不可用") from exc
        if pool is None:
            raise details.DetailError(503, "对话共享存储暂不可用")
        return pool

    async def create(self, owner, source, upload_id, scope, conversation_type):
        trusted = _owner(owner); pool = await self._pool()
        now = datetime.now(timezone.utc); expires = now + timedelta(seconds=CONTEXT_TTL_SECONDS)
        context_id, lease = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
        async with pool.acquire() as conn:
            async with conn.transaction():
                await detail_store._bind(conn, trusted)
                # Serialize per-factory admission so concurrent workers cannot
                # both observe capacity below MAX_CONTEXTS and exceed it.
                await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", trusted[0])
                await conn.execute("DELETE FROM " + TABLE + " WHERE factory_id=$1 AND expires_at <= CURRENT_TIMESTAMP", trusted[0])
                active = await conn.fetchval("SELECT count(*) FROM " + TABLE + " WHERE factory_id=$1 AND expires_at > CURRENT_TIMESTAMP", trusted[0])
                if int(active or 0) >= MAX_CONTEXTS:
                    raise details.DetailError(503, "对话上下文容量已满，请稍后重试")
                await conn.execute("""INSERT INTO smart_bi_factory_analysis_conversations
                    (context_id,factory_id,user_id,role,source,upload_id,scope,selected_ids,history,
                     unresolved_product,unresolved_period,period_error,expires_at,version,lease_token,lease_expires_at)
                    VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,'[]'::jsonb,'[]'::jsonb,false,false,NULL,$8,0,$9,$10)""",
                    context_id, trusted[0], str(trusted[1]), trusted[2], source, upload_id,
                    json.dumps(dict(scope), ensure_ascii=False), expires, lease,
                    now + timedelta(seconds=LEASE_SECONDS))
        return conversation_type(context_id=context_id, factory_id=trusted[0], user_id=int(trusted[1]), role=trusted[2],
            source=source, upload_id=upload_id, scope=dict(scope), expires_at=expires.timestamp(), version=0,
            lease_token=lease)

    async def claim(self, context_id, owner, conversation_type):
        if not isinstance(context_id, str) or not context_id:
            raise details.DetailError(400, "对话上下文无效")
        trusted = _owner(owner); pool = await self._pool(); lease = secrets.token_urlsafe(24)
        async with pool.acquire() as conn:
            async with conn.transaction():
                await detail_store._bind(conn, trusted)
                # Classify identity before lease contention: an authorized
                # tenant with the wrong actor must receive 403 even if another
                # request currently holds the lease.
                owner_row = await conn.fetchrow("SELECT user_id,role FROM " + TABLE + " WHERE context_id=$1 AND factory_id=$2", context_id, trusted[0])
                if owner_row and (str(owner_row["user_id"]) != str(trusted[1]) or str(owner_row["role"]) != trusted[2]):
                    raise details.DetailError(403, "无权访问该对话上下文")
                row = await conn.fetchrow("""UPDATE smart_bi_factory_analysis_conversations
                    SET lease_token=$3, lease_expires_at=CURRENT_TIMESTAMP + ($4 * interval '1 second')
                    WHERE context_id=$1 AND factory_id=$2 AND user_id=$5 AND role=$6 AND expires_at > CURRENT_TIMESTAMP
                      AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
                    RETURNING *""", context_id, trusted[0], lease, LEASE_SECONDS, str(trusted[1]), trusted[2])
                if row is None:
                    busy = await conn.fetchval("SELECT 1 FROM " + TABLE + " WHERE context_id=$1 AND factory_id=$2 AND expires_at > CURRENT_TIMESTAMP AND lease_expires_at > CURRENT_TIMESTAMP", context_id, trusted[0])
                    if busy:
                        raise details.DetailError(409, "该对话正在处理中，请稍后重试")
                    raise details.DetailError(410, "对话上下文已过期或不可用")
        return _row_to_context(row, conversation_type)

    async def save(self, context):
        pool = await self._pool(); expires = datetime.now(timezone.utc) + timedelta(seconds=CONTEXT_TTL_SECONDS)
        async with pool.acquire() as conn:
            async with conn.transaction():
                await detail_store._bind(conn, (context.factory_id, context.user_id, context.role))
                row = await conn.fetchrow("""UPDATE smart_bi_factory_analysis_conversations
                    SET scope=$2::jsonb, selected_ids=$3::jsonb, history=$4::jsonb,
                        unresolved_product=$5, unresolved_period=$6, period_error=$7,
                        expires_at=$8, version=version+1, lease_expires_at=NULL
                    WHERE context_id=$1 AND factory_id=$11 AND user_id=$12 AND role=$13
                      AND lease_token=$9 AND version=$10 AND expires_at > CURRENT_TIMESTAMP
                    RETURNING version""", context.context_id, json.dumps(context.scope, ensure_ascii=False),
                    json.dumps(context.selected_ids, ensure_ascii=False), json.dumps(context.history, ensure_ascii=False),
                    context.unresolved_product, context.unresolved_period, context.period_error, expires,
                    context.lease_token, context.persisted_version,
                    context.factory_id, str(context.user_id), context.role)
                if row is None:
                    raise details.DetailError(409, "对话上下文已被其他请求更新，请重试")
                context.version = int(row["version"]); context.persisted_version = context.version; context.expires_at = expires.timestamp()

    async def release(self, context):
        pool = await self._pool()
        async with pool.acquire() as conn:
            async with conn.transaction():
                await detail_store._bind(conn, (context.factory_id, context.user_id, context.role))
                await conn.execute("UPDATE " + TABLE + " SET lease_expires_at=NULL WHERE context_id=$1 AND lease_token=$2 AND factory_id=$3 AND user_id=$4 AND role=$5", context.context_id, context.lease_token, context.factory_id, str(context.user_id), context.role)


_STORE = ConversationStore()


def get_store():
    return _STORE


def set_store_for_tests(store):
    global _STORE
    old = _STORE; _STORE = store
    return old


class MemoryConversationStore:
    """Explicit test double; never selected by production code."""
    def __init__(self):
        self.rows = {}

    async def create(self, owner, source, upload_id, scope, conversation_type):
        factory, user, role = _owner(owner); cid = secrets.token_urlsafe(24); now = time.time()
        row = conversation_type(context_id=cid, factory_id=factory, user_id=int(user), role=role,
            source=source, upload_id=upload_id, scope=dict(scope), expires_at=now + CONTEXT_TTL_SECONDS,
            version=0, lease_token=secrets.token_urlsafe(12))
        self.rows[cid] = row; return copy.deepcopy(row)

    async def claim(self, context_id, owner, conversation_type):
        row = self.rows.get(context_id)
        if row is None or row.expires_at <= time.time():
            raise details.DetailError(410, "对话上下文已过期或不可用")
        factory, user, role = _owner(owner)
        if (row.factory_id, row.user_id, row.role) != (factory, int(user), role):
            raise details.DetailError(403, "无权访问该对话上下文")
        return copy.deepcopy(row)

    async def save(self, context):
        row = self.rows.get(context.context_id)
        if row is None or row.version != context.persisted_version:
            raise details.DetailError(409, "对话上下文已被其他请求更新，请重试")
        context.version = context.persisted_version = row.version + 1
        context.expires_at = time.time() + CONTEXT_TTL_SECONDS
        self.rows[context.context_id] = copy.deepcopy(context)

    async def release(self, context):
        return None
