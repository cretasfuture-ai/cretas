# -*- coding: utf-8 -*-
"""food_kb feedback 端点的跨租户隔离 (P0, 2026-08-25)。

修复前这四个端点一条租户过滤都没有:
  * GET  /feedback/stats     recent_low_ratings 返回**所有**租户的用户原话 + AI 回答
  * GET  /feedback/export    一次导出全平台 1000 行
  * POST /feedback/log-query 只按调用方自传的 session_id 反查, 重放别家 session 就读回原话
  * POST /feedback           写入的行没有租户归属

这里的 FakeConn **不是**只记录 SQL 然后断言字符串 —— 它按发过来的 SQL 里
实际出现的谓词过滤一个双租户的内存表。所以:
  * SQL 里有 `factory_id = $n`  → 只看得到本租户的行
  * SQL 里没有那个谓词         → 两家的行都返回 (= 修复前的真实行为) → 断言变红

即「把缺陷放回去」这个变异, 在这套桩上是真的会改变行为的, 不是恒真式。
"""
from __future__ import annotations

import re
import statistics
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

import pytest
from fastapi import HTTPException, Request

from food_kb.api import feedback as fb

# ⚠️ log_query 的反查窗口是 `created_at >= now()-5min`, 所以 query_log 那两行的
# 时间戳必须相对**真实当前时间**。第一版写死成 2026-08-25 12:00, 于是窗口一律
# 落空、一行都取不到 —— 「读不到别家」变成恒真式。是新加的阳性对照把它抓出来的。
NOW = datetime.now(timezone.utc)

FACTORY_A = "F_TENANT_A"
FACTORY_B = "F_TENANT_B"

# 两家各一条低评分反馈 —— 断言看的就是「A 能不能读到 B 这一行的原话」。
FEEDBACK_ROWS: list[dict[str, Any]] = [
    {
        "id": 1, "factory_id": FACTORY_A, "query": "TENANT_A_SECRET_QUESTION",
        "answer": "TENANT_A_SECRET_ANSWER", "rating": 1, "feedback_type": "explicit",
        "feedback_detail": "{}", "session_id": "sess-a", "user_id": 11,
        "retrieved_doc_ids": None, "retrieved_doc_titles": None,
        "intent_code": None, "refined_from": None, "response_time_ms": 10,
        "created_at": NOW,
    },
    {
        "id": 2, "factory_id": FACTORY_B, "query": "TENANT_B_SECRET_QUESTION",
        "answer": "TENANT_B_SECRET_ANSWER", "rating": 1, "feedback_type": "explicit",
        "feedback_detail": "{}", "session_id": "sess-b", "user_id": 22,
        "retrieved_doc_ids": None, "retrieved_doc_titles": None,
        "intent_code": None, "refined_from": None, "response_time_ms": 20,
        "created_at": NOW,
    },
]

# ⚠️ A 那条必须与探针问句**毫不相似** —— 第一版写成 TENANT_A_LOGGED_QUESTION /
# TENANT_B_LOGGED_QUESTION, 两者 bigram 相似度 > 0.6, 于是去掉租户条件后循环在
# A 那行就 break 了, similar_recent_query 是 A 的问句, 断言照样绿。
# 变异对照当场把它抓出来 (M3 not red): 断言当时是靠「先撞上 A」而不是靠租户闸。
QUERY_LOG_ROWS: list[dict[str, Any]] = [
    {"id": 1, "factory_id": FACTORY_A, "query": "alpha ledger reconciliation memo",
     "session_id": "sess-shared", "created_at": NOW - timedelta(minutes=1)},
    {"id": 2, "factory_id": FACTORY_B, "query": "TENANT_B_LOGGED_QUESTION",
     "session_id": "sess-shared", "created_at": NOW - timedelta(minutes=1)},
]


# ------------------------------------------------------------------
# 迷你 SQL 谓词求值器 —— 只认这些 handler 真正发出来的那几种形状
# ------------------------------------------------------------------
_PARAM_PRED = re.compile(r"(\w+)\s*(>=|<=|=)\s*\$(\d+)")
_LITERAL_PRED = re.compile(r"(\w+)\s*(>=|<=|=)\s*(\d+)\b")


def _where_of(sql: str) -> str:
    m = re.search(r"\bWHERE\b(.*?)(\bGROUP BY\b|\bORDER BY\b|\bLIMIT\b|$)", sql, re.S | re.I)
    return m.group(1) if m else ""


def _matches(row: dict[str, Any], sql: str, args: tuple) -> bool:
    where = _where_of(sql)
    if not where.strip():
        return True
    for col, op, idx in _PARAM_PRED.findall(where):
        if col not in row:
            continue
        want = args[int(idx) - 1]
        got = row[col]
        if got is None:
            return False
        if op == "=" and got != want:
            return False
        if op == ">=" and not got >= want:
            return False
        if op == "<=" and not got <= want:
            return False
    for col, op, lit in _LITERAL_PRED.findall(where):
        if col not in row:
            continue
        got, want = row[col], int(lit)
        if got is None:
            return False
        if op == "=" and got != want:
            return False
        if op == ">=" and not got >= want:
            return False
        if op == "<=" and not got <= want:
            return False
    if re.search(r"rating\s+IS\s+NOT\s+NULL", where, re.I) and row.get("rating") is None:
        return False
    return True


class FakeConn:
    def __init__(self) -> None:
        self.feedback = [dict(r) for r in FEEDBACK_ROWS]
        self.query_log = [dict(r) for r in QUERY_LOG_ROWS]
        self.executed: list[tuple[str, tuple]] = []
        self.tenant_guc: Optional[str] = None

    def _table(self, sql: str) -> list[dict[str, Any]]:
        return self.query_log if "food_kb_query_log" in sql else self.feedback

    def _select(self, sql: str, args: tuple) -> list[dict[str, Any]]:
        return [r for r in self._table(sql) if _matches(r, sql, args)]

    async def execute(self, sql: str, *args: Any) -> str:
        self.executed.append((sql, args))
        if "set_config" in sql:
            self.tenant_guc = args[0]
            return "SELECT 1"
        if sql.strip().upper().startswith("INSERT"):
            cols = re.search(r"\(([^)]*)\)\s*VALUES", sql, re.S)
            names = [c.strip().rstrip("\n") for c in cols.group(1).replace("\n", " ").split(",")]
            names = [n.strip() for n in names if n.strip()]
            row = dict(zip(names, args))
            row["id"] = 900 + len(self._table(sql))
            self._table(sql).append(row)
        return "INSERT 0 1"

    async def fetch(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        self.executed.append((sql, args))
        rows = self._select(sql, args)
        gb = re.search(r"GROUP BY\s+(\w+)", sql, re.I)
        if gb:
            col = gb.group(1)
            counts: dict[Any, int] = {}
            for r in rows:
                counts[r.get(col)] = counts.get(r.get(col), 0) + 1
            return [{col: k, "cnt": v} for k, v in sorted(counts.items(), key=lambda kv: str(kv[0]))]
        return rows

    async def fetchrow(self, sql: str, *args: Any) -> Optional[dict[str, Any]]:
        self.executed.append((sql, args))
        rows = self._select(sql, args)
        if "COUNT(*)" in sql.upper():
            ratings = [r["rating"] for r in rows if r.get("rating") is not None]
            return {"total": len(rows),
                    "avg_rating": statistics.mean(ratings) if ratings else 0}
        if "RETURNING" in sql.upper():
            return await self._insert_returning(sql, args)
        return rows[0] if rows else None

    async def _insert_returning(self, sql: str, args: tuple) -> dict[str, Any]:
        await self.execute(sql, *args)
        return {"id": self._table(sql)[-1]["id"]}

    async def fetchval(self, sql: str, *args: Any) -> Any:
        row = await self.fetchrow(sql, *args)
        return None if row is None else next(iter(row.values()))


class _Acquire:
    def __init__(self, conn: FakeConn) -> None:
        self.conn = conn

    async def __aenter__(self) -> FakeConn:
        return self.conn

    async def __aexit__(self, *exc: Any) -> bool:
        return False


class FakePool:
    def __init__(self, conn: FakeConn) -> None:
        self.conn = conn

    def acquire(self) -> _Acquire:
        return _Acquire(self.conn)


@pytest.fixture
def conn(monkeypatch: pytest.MonkeyPatch) -> FakeConn:
    c = FakeConn()
    pool = FakePool(c)

    async def _fake_pool():
        return pool

    monkeypatch.setattr(fb, "_get_pool", _fake_pool)
    return c


def make_request(factory_id: Optional[str], user_id: Optional[int] = None) -> Request:
    state: dict[str, Any] = {}
    if factory_id is not None:
        state["factory_id"] = factory_id
    if user_id is not None:
        state["user_id"] = user_id
    return Request({
        "type": "http", "method": "GET", "path": "/api/food-kb/feedback",
        "headers": [], "query_string": b"", "state": state,
    })


# ==================================================================
# 阳性对照: 桩里确实有 B 的数据, 且 A 的数据读得到
# ==================================================================

@pytest.mark.asyncio
async def test_positive_control_tenant_a_sees_its_own_row(conn: FakeConn) -> None:
    """先证明仪器活着 —— A 读得到自己那一行, 否则下面「读不到 B」毫无意义。"""
    stats = await fb.feedback_stats(make_request(FACTORY_A))
    assert stats["success"] is True
    texts = [r["query"] for r in stats["recent_low_ratings"]]
    assert "TENANT_A_SECRET_QUESTION" in texts, "A 连自己的行都读不到, 这套桩没在量东西"
    assert stats["total"] == 1


@pytest.mark.asyncio
async def test_positive_control_tenant_b_row_exists_in_store(conn: FakeConn) -> None:
    """B 的行确实在同一张表里 —— 「A 读不到 B」不是因为 B 根本不存在。"""
    stats = await fb.feedback_stats(make_request(FACTORY_B))
    texts = [r["query"] for r in stats["recent_low_ratings"]]
    assert "TENANT_B_SECRET_QUESTION" in texts


# ==================================================================
# 跨租户断言
# ==================================================================

@pytest.mark.asyncio
async def test_stats_does_not_leak_other_tenant_raw_text(conn: FakeConn) -> None:
    stats = await fb.feedback_stats(make_request(FACTORY_A))
    blob = repr(stats)
    assert "TENANT_B_SECRET_QUESTION" not in blob, "stats 把别家租户的用户原话返回了"
    assert "TENANT_B_SECRET_ANSWER" not in blob, "stats 把别家租户的 AI 回答返回了"
    assert stats["total"] == 1, f"total 跨租户计数: {stats['total']}"
    assert sum(stats["by_type"].values()) == 1
    assert sum(stats["by_rating"].values()) == 1


@pytest.mark.asyncio
async def test_export_does_not_leak_other_tenant_rows(conn: FakeConn) -> None:
    # 直接调 handler 时要显式传参 —— 默认值是 fastapi 的 Query 对象, 不是值。
    rows = await fb.export_feedback(
        make_request(FACTORY_A), since=None, feedback_type=None,
        min_rating=1, max_rating=5,
    )
    assert [r["query"] for r in rows] == ["TENANT_A_SECRET_QUESTION"]
    assert all(r["factory_id"] == FACTORY_A for r in rows if "factory_id" in r)


@pytest.mark.asyncio
async def test_export_filters_cannot_reach_other_tenants(conn: FakeConn) -> None:
    """where_clause 是 f-string 拼的 —— 确认租户条件不会被别的过滤支挤掉。"""
    rows = await fb.export_feedback(
        make_request(FACTORY_A), since="2026-01-01", feedback_type="explicit",
        min_rating=1, max_rating=5,
    )
    assert [r["query"] for r in rows] == ["TENANT_A_SECRET_QUESTION"]


@pytest.mark.asyncio
async def test_log_query_replaying_foreign_session_id_reads_nothing(conn: FakeConn) -> None:
    """session_id 是调用方自传的 —— A 拿 B 用过的 session 不许读回 B 的问句。"""
    res = await fb.log_query(
        make_request(FACTORY_A),
        fb.LogQueryRequest(query="TENANT_B_LOGGED_QUESTION", session_id="sess-shared"),
    )
    assert res["success"] is True
    assert res["similar_recent_query"] is None, (
        f"重放别家 session_id 把别家的问句原文读回来了: {res['similar_recent_query']!r}"
    )
    assert res["is_requery"] is False
    assert "TENANT_B_LOGGED_QUESTION" not in repr(res)


@pytest.mark.asyncio
async def test_log_query_positive_control_same_tenant_still_detects_requery(
    conn: FakeConn,
) -> None:
    """阳性对照: 同租户的重复提问照样要被检出 —— 否则上面那条只是「什么都查不到」。"""
    res = await fb.log_query(
        make_request(FACTORY_A),
        fb.LogQueryRequest(query="alpha ledger reconciliation memo", session_id="sess-shared"),
    )
    assert res["is_requery"] is True
    assert res["similar_recent_query"] == "alpha ledger reconciliation memo"


# ==================================================================
# 写入侧: 租户归属只能来自 JWT
# ==================================================================

@pytest.mark.asyncio
async def test_submit_feedback_stamps_tenant_from_jwt(conn: FakeConn) -> None:
    res = await fb.submit_feedback(
        make_request(FACTORY_A, user_id=11),
        fb.FeedbackRequest(query="q", rating=5, user_id=999999),
    )
    assert res["success"] is True
    written = conn.feedback[-1]
    assert written["factory_id"] == FACTORY_A
    assert written["user_id"] == 11, "user_id 用了 body 里调用方自传的值"


@pytest.mark.asyncio
async def test_log_query_stamps_tenant_from_jwt(conn: FakeConn) -> None:
    await fb.log_query(
        make_request(FACTORY_A, user_id=11),
        fb.LogQueryRequest(query="brand new question", session_id="sess-a"),
    )
    assert conn.query_log[-1]["factory_id"] == FACTORY_A


# ==================================================================
# fail-closed: 没有租户上下文一律 401, ⛔ 不许放行成"查全表"
# ==================================================================

@pytest.mark.asyncio
@pytest.mark.parametrize("call", ["stats", "export", "submit", "log_query"])
async def test_missing_tenant_context_is_401(conn: FakeConn, call: str) -> None:
    req = make_request(None)
    with pytest.raises(HTTPException) as exc:
        if call == "stats":
            await fb.feedback_stats(req)
        elif call == "export":
            await fb.export_feedback(req, since=None, feedback_type=None,
                                     min_rating=1, max_rating=5)
        elif call == "submit":
            await fb.submit_feedback(req, fb.FeedbackRequest(query="q", rating=5))
        else:
            await fb.log_query(req, fb.LogQueryRequest(query="q"))
    assert exc.value.status_code == 401


@pytest.mark.asyncio
async def test_empty_string_factory_id_is_also_rejected(conn: FakeConn) -> None:
    """空串是 falsy —— 但要确认它走的是 401 而不是被当成一个合法租户。"""
    with pytest.raises(HTTPException) as exc:
        await fb.feedback_stats(make_request(""))
    assert exc.value.status_code == 401


# ==================================================================
# RLS GUC 接上了没有
# ==================================================================

@pytest.mark.asyncio
async def test_tenant_guc_is_set_session_scoped(conn: FakeConn) -> None:
    await fb.feedback_stats(make_request(FACTORY_A))
    guc_calls = [(s, a) for s, a in conn.executed if "set_config" in s]
    assert guc_calls, "没有给连接设 app.factory_id, 表上的 RLS 策略等于没接上"
    sql, args = guc_calls[0]
    assert args[0] == FACTORY_A
    assert "false" in sql, (
        "set_config 第三个参数必须是 false (session 级); true 是事务级, "
        "asyncpg 自动提交下一条语句就没了"
    )
