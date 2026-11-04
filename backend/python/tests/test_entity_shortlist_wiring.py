"""门店简称消解**接在生产入口上**没有 —— 断言跑 `parse_restaurant_query`。

🏠 「生产上谁保证它被调用」的答案: `parse_restaurant_query` 是餐饮问答唯一的
   规划入口（Web/Java 餐饮聊天只走 `semantic_first=True` 这一支），
   本文件每条断言都从它进去，⛔ 不直接调 `_resolve_store_mentions` /
   `_validated_llm_store_names`。

   ⚠️ 这一条是本仓踩出来的: 直接调被测函数的断言绕过了「谁调它」，
      变异下会全绿（形态 B / B′）。同一个错在隔壁分支犯过第二次。

📏 用的门店名是 2026-08-18 prod MOCK_REST 的**真名**（`dim_store` 全表 10 行，
   ANALYZE 后 reltuples=10）。简称由真名机械派生，⛔ 不是挑出来的。
"""
from __future__ import annotations

import json
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant.restaurant_intent import (
    clear_promoted_routes_cache,
    clear_route_cache,
    clear_semantic_plan_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)
from smartbi.canonical.entity_resolution.shortlist import clear_shortlist_cache

FACTORY = "MOCK_REST"

PROD_STORES = (
    "模拟·宝山大场社区店", "模拟·徐汇美罗城店", "模拟·打浦桥日月光店",
    "模拟·普陀真如社区店", "模拟·杨浦五角场店", "模拟·浦东金桥社区店",
    "模拟·长宁龙之梦店", "模拟·闵行莘庄社区店", "模拟·陆家嘴正大店",
    "模拟·静安嘉里中心店",
)


@pytest.fixture(autouse=True)
def _reset_caches():
    """跨样本读数前清缓存, 并**贴出清了哪几个**（硬约束 3）。

    ⛔ 用各模块自己的 helper, ⛔ 不拼属性名 —— 拼错的名字不会报错,
       只会让清理静默失效（形态 C⁵）。
    """
    for clear in (clear_route_cache, clear_tenant_gate_cache,
                  clear_semantic_plan_cache, clear_promoted_routes_cache,
                  clear_shortlist_cache):
        clear()
    yield
    for clear in (clear_route_cache, clear_tenant_gate_cache,
                  clear_semantic_plan_cache, clear_promoted_routes_cache,
                  clear_shortlist_cache):
        clear()


# ─── Fake asyncpg pool（形状抄自 test_restaurant_intent_flywheel_reconnect） ──

class _Row(dict):
    pass


class _FakeConn:
    def __init__(self, owner):
        self.owner = owner
        self.in_transaction = False

    def transaction(self):
        conn = self

        class _Ctx:
            async def __aenter__(self):
                conn.in_transaction = True
                return None

            async def __aexit__(self, *_exc):
                conn.in_transaction = False
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        if "set_config('app.factory_id'" in sql:
            assert self.in_transaction, "RLS GUC 必须是事务级"
            return "SELECT 1"
        if "restaurant_pending_clarifications" in sql:
            return "OK"
        return "OK"

    async def fetchrow(self, sql, *_args):
        if "agg_restaurant_daily_totals" in sql:
            return {"?column?": 1}
        if "restaurant_pending_clarifications" in sql:
            return None
        return None

    async def fetch(self, sql, *_args):
        if "ai_promoted_routes" in sql:
            return []
        if "FROM dim_store" in sql or "fact_pos_item" in sql:
            self.owner.store_reads += 1
            return [_Row(name=n) for n in self.owner.stores]
        return []


class _FakePool:
    def __init__(self, stores=PROD_STORES):
        self.stores = list(stores)
        self.store_reads = 0

    def acquire(self):
        conn = _FakeConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Ctx()


def _plan(store):
    """一份完整的单店营收计划。`store` 就是模型提名的那个名字。

    🔴 **`store` 的取值必须是真模型会产出的形状** —— 这一条是踩出来的:

       我第一版所有用例都喂**老板打的简称**(`store="宝山店"`), 单测全绿,
       而 prod 端到端 **3/3 全红**。2026-08-18 prod 打出的原始 plan 是:

           Q: 宝山店最近30天营收多少
           store = "模拟·宝山大场社区店"   ← 模型看得到门店清单, 自己归一好了

       ▎桩的自由度让我可以构造任何输入, **包括真实上游永远不会给出的输入**
       ▎(形态 B‴)。所以下面的用例按 prod 实测分成两族, 各自标明形状来源。
    """
    return {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"kind": "relative", "unit": "day", "count": 30},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": store,
        "stores": [],
        "store_scope": "single",
        "confidence": 0.95,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }


async def _ask(query, store_in_plan, pool=None):
    pool = pool or _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_plan(store_in_plan))
    ):
        return await parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True,
        )


# ══════════════════════════════════════════════════════════════════════
# 1. 唯一命中 → 归一成库里的全名
# ══════════════════════════════════════════════════════════════════════


















# ══════════════════════════════════════════════════════════════════════
# 2. 多条命中 → 确认式反问（⛔ 不静默挑一个、⛔ 不静默补「全部门店」）
# ══════════════════════════════════════════════════════════════════════










# ══════════════════════════════════════════════════════════════════════
# 3. 零 token 回放路径也要归一（⛔ 不能两条路行为不一致）
# ══════════════════════════════════════════════════════════════════════




# ══════════════════════════════════════════════════════════════════════
# 4. 接线本身（AST）: 三个 `_semantic_spec_from_t3` 出口一个都不许漏
# ══════════════════════════════════════════════════════════════════════


def test_every_semantic_spec_call_site_passes_the_resolution():
    """守: 「同一个东西只改一半调用点」是本仓反复踩的形状（硬约束 8）。

    ⛔ 用 AST 数**真正的调用节点**, ⛔ 不用文本 grep —— grep 会把 docstring
       里提到函数名的行也数上（形态 C⁸）。

    ⚠️ 这道闸挡得住「删掉某个出口的接线」, 挡不住「用 if False 绕过」。
       所以它是兜底, 不是免检 —— 行为断言在上面那几条。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(ri))
    call_sites = [
        node for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and isinstance(node.func, ast.Name)
        and node.func.id == "_semantic_spec_from_t3"
    ]
    assert len(call_sites) == 3, (
        f"`_semantic_spec_from_t3` 的调用点从 3 个变成了 {len(call_sites)} 个 —— "
        "新出口也要传 store_resolution, 或在这里显式登记为「故意不传」并写理由")
    for call in call_sites:
        assert any(kw.arg == "store_resolution" for kw in call.keywords), (
            f"第 {call.lineno} 行的 `_semantic_spec_from_t3` 没传 store_resolution")


def test_the_production_entry_actually_computes_the_resolution():
    """守: 生产入口里真的**调**了 `_resolve_store_mentions`。

    上面那条只证明「参数传下去了」, 不证明那个参数不是恒 None。

    ⚠️ 量的是 `_parse_restaurant_query_impl`, ⛔ 不是 `parse_restaurant_query` ——
       后者是个薄壳, 它函数体里只有一句委派。第一版量错了对象, 断言当场红,
       而红的原因不是缺陷（形态 A: 我量的这个数不是我想知道的那个数）。
       阳性对照就在下面那行 assert: 薄壳里确实只找得到委派。
    """
    import ast
    import inspect

    shell = {
        node.func.id for node in ast.walk(
            ast.parse(inspect.getsource(ri.parse_restaurant_query)))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert "_parse_restaurant_query_impl" in shell, (
        "薄壳的形状变了 —— 下面量的那个函数可能已经不是真入口了")

    tree = ast.parse(inspect.getsource(ri._parse_restaurant_query_impl))
    called = {
        node.func.id for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert "_resolve_store_mentions" in called, (
        "生产入口没有计算门店消解 —— store_resolution 恒为 None, "
        "整条接线是死代码")


def test_the_wiring_survives_a_json_roundtrip_of_the_plan():
    """守: 计划缓存存的是 JSON, 归一后的全名要能原样过一遍序列化。

    （非 ASCII + 中点在 JSON 里踩过坑, 这条是十秒钟的保险。）
    """
    name = "模拟·宝山大场社区店"
    assert json.loads(json.dumps({"store": name}, ensure_ascii=False))["store"] == name
