"""候选补足检索 (例句 + 描述混合) 的闸。"""
import asyncio

import pytest

from ai.matcher import candidates as cand_mod
from ai.matcher.candidates import BlendRetriever, blend_scores


def test_blend_uses_examples_and_falls_back_to_description():
    desc = {"A": 0.5, "B": 0.6}
    ex = {"A": 0.9}                      # B 没有例句
    s = blend_scores(desc, ex, blend=0.35)
    assert s["A"] == pytest.approx(0.9 * 0.65 + 0.5 * 0.35)
    assert s["B"] == pytest.approx(0.6)  # 例句分取描述分 ⇒ 等于描述分
    assert s["A"] > s["B"]               # 例句命中把 A 抬到 B 前面


def test_only_visible_intents_are_scored():
    """例句表里有、但描述查询没返回 (不可见/已停用) 的意图不能混进候选。"""
    s = blend_scores({"A": 0.5}, {"A": 0.9, "GONE": 0.99}, blend=0.35)
    assert set(s) == {"A"}


class _Conn:
    def __init__(self, desc_rows, ex_rows=None, ex_error=None):
        self.desc_rows, self.ex_rows, self.ex_error = desc_rows, ex_rows or [], ex_error
        self.calls = []

    async def fetch(self, sql, *args):
        self.calls.append(sql)
        if "ai_intent_examples" in sql:
            if self.ex_error:
                raise self.ex_error
            return self.ex_rows
        return self.desc_rows


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                return pool.conn

            async def __aexit__(self, *a):
                return False
        return _Ctx()


def _row(code, sim):
    return {"intent_code": code, "intent_name": code.lower(), "intent_category": None, "description": None,
            "similarity": sim}


@pytest.fixture(autouse=True)
def _fake_embedding(monkeypatch):
    async def fake(q):
        return [0.0] * 768
    monkeypatch.setattr(cand_mod, "get_embedding_cached", fake)
    monkeypatch.setattr(cand_mod, "_missing_table_warned", False)


def test_retriever_ranks_by_blend_and_respects_top_k():
    conn = _Conn([_row("A", 0.50), _row("B", 0.60), _row("C", 0.10)],
                 ex_rows=[{"intent_code": "A", "similarity": 0.95}])
    out = asyncio.run(BlendRetriever(_Pool(conn), top_k=2, blend=0.35).match("q", "F006", "FACTORY"))
    assert [c.intentCode for c in out] == ["A", "B"]


def test_description_only_ordering_is_different():
    """对照: blend=1.0 (只看描述) 时 B 在 A 前 —— 证明上一条的顺序来自例句, 不是描述。"""
    conn = _Conn([_row("A", 0.50), _row("B", 0.60)], ex_rows=[{"intent_code": "A", "similarity": 0.95}])
    out = asyncio.run(BlendRetriever(_Pool(conn), top_k=2, blend=1.0).match("q", "F006", "FACTORY"))
    assert [c.intentCode for c in out] == ["B", "A"]


def test_missing_examples_table_degrades_to_description_only():
    conn = _Conn([_row("A", 0.50), _row("B", 0.60)],
                 ex_error=Exception('relation "ai_intent_examples" does not exist'))
    out = asyncio.run(BlendRetriever(_Pool(conn), top_k=2).match("q", "F006", "FACTORY"))
    assert [c.intentCode for c in out] == ["B", "A"]


def test_other_database_errors_are_not_swallowed():
    conn = _Conn([_row("A", 0.5)], ex_error=Exception("connection reset"))
    with pytest.raises(Exception, match="connection reset"):
        asyncio.run(BlendRetriever(_Pool(conn)).match("q", "F006", "FACTORY"))


def test_no_embedding_means_no_candidates(monkeypatch):
    async def none(q):
        return None
    monkeypatch.setattr(cand_mod, "get_embedding_cached", none)
    conn = _Conn([_row("A", 0.5)])
    assert asyncio.run(BlendRetriever(_Pool(conn)).match("q", "F006", "FACTORY")) == []
    assert conn.calls == []
