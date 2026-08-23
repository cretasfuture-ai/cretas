# -*- coding: utf-8 -*-
"""维度/口径不匹配的拒答, 必须说出**到底哪一样算不出来**。

## 缺陷长相 (2026-08-20 端到端复测, MOCK_REST, 5/5)

老板问「最近30天各门店的营收和**退菜率**分别是多少」, 拿到的**全部内容**(60 字):

    我准备算的东西跟你问的对不上，所以这次我没敢算。你是想看某道菜、某家门店，
    还是全店合计？说一个就行，我不会拿别的数据凑。

两半都没说:

1. 他**已经说了**「各门店」, 却被反问「你是想看某道菜、某家门店，还是全店合计」;
2. 真正算不出来的是**退菜率**, 而这三个字一次都没出现。

分离对照 3/3: 删掉「和退菜率」三个字 → 3/3 `answer`, 正常给出按门店的营收榜
⇒ 能算的那一半**确实算得出来, 只是没给**。

## 断在哪

形态 B(机制在, 没接上): 那个 `else` 兜底分支手里**已经有**
`spec.unsupported_requirements`, 一次都没读过 —— 而同一个模块上面那条规划层澄清
早就在用 `should_use_capability_refusal` / `render_capability_refusal`
把同一件事说清楚了。缺的不是能力, 是接线。

⛔ 不另写措辞 —— 复用**同一族**模板。

## 本轮**没做**的那一半 (显式登记)

▎理想是把能算的那一半(按门店的营收榜)也发出去。那要在这道**跑之前**的闸后面
▎补一条降级执行路径 —— 本轮没做。
解冻条件: 先有一条「按剩余可算指标重新组计划」的路径, 且它自己有阳性对照证明
不会把整份答案降级。这里只保证「缺什么」说得出来。
"""
from __future__ import annotations

import asyncio
import ast
import inspect

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.capability_answer import missing_capability_labels
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

_QUERY = "最近30天各门店的营收和退菜率分别是多少"
_PLAN = ("RESTAURANT_OPS_STORE_MARGIN",)


def _spec(*, unsupported=("return_rate",), dimensions=("store", "dish")):
    """复现实测到的那个形状。

    ⚠️ 用**真** `RestaurantQuerySpec`(frozen dataclass), ⛔ 不用 SimpleNamespace ——
       产品代码走 `dataclasses.replace`, 桩不是 dataclass 会 TypeError,
       那不是缺陷, 是我造了一个真实上游产不出的形状(形态 B‴)。
    """
    return RestaurantQuerySpec(
        # `intent ∉ plan` —— 实测那条路撞的正是这一支
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        domain="restaurant_ops",
        date_range=(None, None),
        window_label="最近30天",
        relative_window=True,
        metrics=("revenue",),
        wants_margin=False,
        asks_profitability=False,
        dimensions=tuple(dimensions),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
        planned_intents=_PLAN,
        plan_hash="probe-hash",
        plan_version="restaurant-query-plan-v2",
        planner_authority="llm",
        unsupported_requirements=tuple(unsupported),
        resolver_query_seed=_QUERY,
        store_scope="all",
    )


class _DeadPool:
    """查不动的连接池。

    ⚠️ 这**不是**为了偷懒 —— `tenant_capability` 明确写了「查不动就返回空,
       猜错比不说更糟」。让真函数走它自己的降级路, 比塞一个假返回值更接近生产,
       而且顺带证明: 即使「我这儿有的是」那一段拿不到, **缺什么**照样说得出来。
    """


def _run(coro):
    # ⛔ 不用 `asyncio.get_event_loop()` —— 单跑绿、进全量套件红:
    #    `RuntimeError: There is no current event loop in thread 'MainThread'`
    #    (同套件里别的用例把那个 loop 关掉了)。仓里 65 处用 `asyncio.run`,
    #    只有 4 处是旧写法 —— 跟多数派, 它自带 loop 且用完即弃。
    # ⚠️ 这条是被**全量回归**抓出来的: 三个新文件单跑 19/19 绿, 进套件 8 条红。
    #    「单跑绿」不是判据。
    return asyncio.run(coro)


@pytest.fixture
def entry(monkeypatch):
    """真产品入口 `tiered_answer`, 只桩掉外部 IO。

    🔴 判据必须跑在**产品真实入口**上 —— 测 helper 不是测接线。
    """
    async def _no_gap(*_a, **_kw):
        return None

    async def _no_catalogue(*_a, **_kw):
        return ()

    monkeypatch.setattr(svc, "_known_data_gap", _no_gap)
    monkeypatch.setattr(svc, "load_dish_catalogue", _no_catalogue)

    def _call(spec):
        return _run(svc.tiered_answer(
            _QUERY, _DeadPool(), "MOCK_REST", "restaurant_manager",
            precomputed_spec=spec, allow_decompose=False,
        ))
    return _call


# ── 前提: 这条路必须真的走得到那个分支 ────────────────────────────────
# ⛔ 前提没了, 下面每一条断言都没量到东西 (rc=2, 不是 rc=0)。

def test_precondition_this_input_really_hits_the_fallback_branch(entry):
    """前提三件: ① 撞维度闸 ② `_dimension_gap_advice` 确实是空 ③ 缺口标签非空。"""
    spec = _spec()
    assert svc._execution_mismatch(
        spec, _PLAN, dish_mention=None, store_mention=None, store_dish=None,
    ) == "我准备算的东西跟你问的对不上", "没撞到实测的那条拒答"
    assert svc._dimension_gap_advice(spec, _PLAN) == "", (
        "`_dimension_gap_advice` 不再返回空 —— 那就走不到兜底口了, "
        "本文件所有断言都没量到东西")
    assert missing_capability_labels(spec.unsupported_requirements) == [
        "退菜率（缺少退菜时间、菜品、数量、原因和责任门店）"
    ], "缺口标签变了 —— 下面的逐字断言要跟着重量, ⛔ 不是把断言删掉"


# ── 承重 ──────────────────────────────────────────────────────────────

def test_the_boss_is_told_which_thing_cannot_be_computed(entry):
    """承重: 老板看到的正文里必须出现**退菜率**, 以及缺的是什么。"""
    res = entry(_spec())
    text = res["answer_text"]
    assert res["kind"] == "clarification", f"形状变了: {res['kind']}"
    assert res["clarification_source"] == "dimension_mismatch", res
    assert "退菜率" in text, (
        f"真正算不出来的那一样一个字都没说, 老板无从知道该改哪个字。原文: {text!r}")
    assert "缺的是" in text, f"只说了算不出来, 没说缺什么。原文: {text!r}"


def test_it_no_longer_asks_a_question_he_already_answered(entry):
    """阴性对照: 他说了「各门店」, ⛔ 不许再反问他想看哪一层。"""
    text = entry(_spec())["answer_text"]
    assert "你是想看某道菜、某家门店，还是全店合计" not in text, (
        f"还在反问一个他已经答过的问题。原文: {text!r}")


def test_no_capability_gap_keeps_the_old_wording(entry):
    """阴性对照(承重): **没有**能力缺口时, 原来那句通用反问必须原样保留。

    🔴 这一条是「不许把拒答模板用到不该用的地方」——
       缺时间、缺门店、说不清要看什么都**不是**能力缺口,
       拿拒答模板去顶会把「再说清楚点」说成「我做不到」, 而后者是一句关门的话。
    ⛔ 它同时是这道改动的**边界**证明: 变异掉那个 `not gap_advice and ...` 条件
       会让这条红。
    """
    text = entry(_spec(unsupported=()))["answer_text"]
    assert "你是想看某道菜、某家门店，还是全店合计" in text, (
        f"没有能力缺口却换了措辞 —— 越界了。原文: {text!r}")
    assert "缺的是" not in text, f"没有缺口却说「缺的是」。原文: {text!r}"


def test_the_wording_is_not_written_a_second_time(entry):
    """形态 D: 措辞必须来自既有模板, ⛔ 不在这个分支里另写一份。

    🔴 用 **AST 数字符串常量**, ⛔ 不用 `in src` 数文本 —— 第一版就是文本匹配,
       当场被**我自己写在注释里引用模板的那一行**判成违例(形态 C⁸ 同形第四次)。
       注释根本不进 AST, 换成结构就没有这个假读数。
    """
    tree = ast.parse(inspect.getsource(svc.tiered_answer).lstrip())
    called = {
        n.func.id for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
    }
    assert "render_capability_refusal" in called, (
        "没有调用既有模板 —— 要么没接上, 要么在这里又手写了一份措辞")
    literals = [
        n.value for n in ast.walk(tree)
        if isinstance(n, ast.Constant) and isinstance(n.value, str)
    ]
    # 阳性对照: 先证明这道闸**看得见字符串**, 否则「没有违例」可能只是它扫了空集。
    assert any("我没敢算" in s for s in literals), (
        "AST 里一个面向老板的串都取不到 —— 仪器坏了, 下面那条断言无意义")
    offenders = [s for s in literals if "现在算不出来" in s or "缺的是" in s]
    assert not offenders, (
        f"模板的句子被抄成了调用点的字面量 —— 那就是形态 D 的第二份: {offenders}")


def test_capability_lookup_failure_still_names_the_missing_thing(entry):
    """`tenant_capability` 查不动时(本文件用的就是死池), 「缺什么」照样说得出来。

    ⚠️ 这条守的是**降级方向**: 拿不到「我这儿有的是」那一段, ⛔ 不许连
       「缺的是」一起消失 —— 那会退回成原来那句什么都没说的通用反问。
    """
    text = entry(_spec())["answer_text"]
    assert "退菜率" in text and "缺的是" in text, text
    # 死池 ⇒ `tenant_capability` 按它自己的纪律返回 () ⇒ 这一段整段省掉,
    # ⛔ 不写「暂时什么都算不了」之类的猜测。
    assert "我这儿有的是" not in text, (
        f"查不动却报出了「我这儿有的是」—— 那是一句猜出来的承诺。原文: {text!r}")
