"""工艺保密产品名不许经由 Python 学习 / 检索出现在别的工厂 (Steve 2026-10-29, 复审 H2 / H3)。

假连接池只记录 SQL 与参数; 授权状态、保密产品名用注入的假查询函数。每组都有阴性对照。
"""
from __future__ import annotations

import pytest

from ai.learning import expression_learner as el
from ai.learning import keyword_learner as kl
from ai.learning.learning_rules import factory_learnable, is_global_factory
from ai.rag import retrieval

SECRET = "秘制酱牛肉"


class _Conn:
    def __init__(self, rows):
        self.rows = rows
        self.executed = []

    async def fetch(self, sql, *args):
        return self.rows

    async def execute(self, sql, *args):
        self.executed.append((sql, args))


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *exc):
        return False


class _Pool:
    def __init__(self, rows):
        self.conn = _Conn(rows)

    def acquire(self):
        return _Acquire(self.conn)


def _consent(scopes):
    async def lookup(factory_id):
        value = scopes.get(factory_id, "none")
        if isinstance(value, Exception):
            raise value
        return value
    return lookup


def _names(by_factory):
    async def lookup(factory_id):
        return by_factory.get(factory_id, frozenset())
    return lookup


NAMES = _names({"FA": frozenset({SECRET})})
CONSENT = _consent({"F_GONE": "withdrawn", "F_ERR": RuntimeError("db down")})


# ------------------------------------------------------------------ rules

@pytest.mark.parametrize("factory", [None, "", "  ", "*"])
def test_global_factory_values(factory):
    assert is_global_factory(factory)
    assert not is_global_factory("FA")


async def test_factory_learnable_rules():
    async def ok(f, t):
        return await factory_learnable(f, t, consent_scope=CONSENT, protected_names=NAMES)

    assert await ok("FA", "今天产量多少")                 # 阴性对照
    assert not await ok("FA", SECRET + "今天产量多少")      # 本厂保密产品
    assert await ok("FB", SECRET + "今天产量多少") is True   # 名单按厂: B 厂没有这个保密产品
    assert not await ok(None, "今天产量多少")               # 全局
    assert not await ok("F_GONE", "今天产量多少")           # 撤回
    assert not await ok("F_ERR", "今天产量多少")            # 查不到授权 → 不学
    assert not await ok("FA", "这个产品配方里盐放多少")      # 配方参数


# ------------------------------------------------------------------ H2 ExpressionLearner

@pytest.fixture
def fake_embedding(monkeypatch):
    async def embed(text):
        return [1.0, 0.0]
    monkeypatch.setattr(el, "get_embedding", embed)
    monkeypatch.setattr(el, "vec_to_pgvector_text", lambda v: "[1,0]")


async def test_expression_learner_writes_only_own_factory_learnable_rows(fake_embedding):
    pool = _Pool([
        {"query": "今天产量多少", "intent_code": "PRODUCTION_QUERY", "factory_id": "FA"},       # 写
        {"query": SECRET + "今天产量", "intent_code": "PRODUCTION_QUERY", "factory_id": "FA"},  # 保密产品
        {"query": "今天产量多少", "intent_code": "PRODUCTION_QUERY", "factory_id": None},       # 全局
        {"query": "库存多少", "intent_code": "INVENTORY_QUERY", "factory_id": "F_GONE"},         # 撤回
    ])
    learner = el.ExpressionLearner(pool, consent_scope=CONSENT, protected_names=NAMES)

    assert await learner.run_once() == 1
    assert len(pool.conn.executed) == 1
    sql, args = pool.conn.executed[0]
    assert args[1] == "今天产量多少" and args[4] == "FA"
    assert all(SECRET not in str(a) for _, a in pool.conn.executed)
    assert all(a[4] is not None for _, a in pool.conn.executed)


# ------------------------------------------------------------------ H2 KeywordLearner

async def test_keyword_learner_groups_by_factory_and_updates_only_own_rows():
    pool = _Pool([
        {"query": "出货情况", "intent_code": "SHIPMENT_QUERY", "factory_id": "FA", "confidence": 0.95},
        {"query": "退货情况", "intent_code": "SHIPMENT_QUERY", "factory_id": "FB", "confidence": 0.95},
        {"query": SECRET + "出货", "intent_code": "SHIPMENT_QUERY", "factory_id": "FA", "confidence": 0.95},
        {"query": "发货进度", "intent_code": "SHIPMENT_QUERY", "factory_id": None, "confidence": 0.95},
    ])
    learner = kl.KeywordLearner(pool, existing_keywords={}, consent_scope=CONSENT, protected_names=NAMES)

    learned = await learner.run_once()

    assert set(learned) == {("FA", "SHIPMENT_QUERY"), ("FB", "SHIPMENT_QUERY")}
    assert "退货" not in "".join(learned[("FA", "SHIPMENT_QUERY")])     # B 厂的词不进 A 厂
    assert "出货" not in "".join(learned[("FB", "SHIPMENT_QUERY")])
    updates = {args[2]: args[0] for _, args in pool.conn.executed}
    assert set(updates) == {"FA", "FB"}                                   # 没有 NULL (平台级) 行
    assert all(SECRET[:2] not in kws for kws in updates.values())
    assert "factory_id = $3" in kl.UPDATE_KEYWORDS_SQL
    assert "IS NOT DISTINCT FROM" not in kl.UPDATE_KEYWORDS_SQL


# ------------------------------------------------------------------ H3 RAG

def test_rag_sql_serves_only_manual_global_rows():
    sql = " ".join(retrieval.RAG_SQL.split())
    assert "(factory_id = $2 OR (factory_id IS NULL AND source_type = 'MANUAL'))" in sql
    assert "OR factory_id IS NULL)" not in sql


# ------------------------------------------------------------------ L4 大小写

def test_ascii_names_match_case_insensitively():
    from smartbi.services.process_secret_names import mentions_any

    assert mentions_any({"SecretX"}, "secretx 今天产量")
    assert mentions_any({"secretx"}, "SECRETX 库存")      # 两边都要折叠, 只折叠名字测不出来
    assert mentions_any({SECRET}, SECRET + "库存")
    assert not mentions_any({"SecretX"}, "SecretY 今天产量")
