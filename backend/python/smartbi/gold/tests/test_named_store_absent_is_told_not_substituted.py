# -*- coding: utf-8 -*-
"""点名一家租户没有的门店 ⇒ 说清楚，⛔ 不拿全店合计顶替。

## prod 实测（MOCK_REST，`_store_guard_trace` 6 轮逐轮一致）

问「北京三里屯店卖得怎么样」（这家店不存在），老板拿到：

    最近30天（2026-07-21 至 2026-08-19）覆盖 30 天、10 家门店，共 58,472 单。
    总营收 ¥21,042,226.51 … 表现最强的是模拟·徐汇美罗城店 …

▎**一个字没提那家店不存在。** 一个看着像答案、实际答的是别的事的数字。

## 断在哪（trace 事实，⛔ 不是读码）

    进 guard 时  store_scope='all'  store_slot='北京三里屯店'  defaulted=True
    ⇒ L5565 `or spec.store_scope` 早退 ⇒ L5689 那道核对**整支跳过**

上游 `_semantic_spec_from_t3` 的两个块用 `not store_names` 当
「老板没说门店」，而 `_validated_llm_store_names` 对**三种**模型输出都返回空
（它自己的 docstring 写着）—— 于是「他没说门店」和「他说了一家我们没有的店」
被折叠成同一件事，而这两件事该走相反的路。

## 断言各守什么（⛔ 不是同义的）

    承重1  点名名单外的门店 ⇒ 反问，且 `missing_slot="store"`
    承重2  反问正文里**出现那家店的名字** + 报出在营门店
    承重3  正文**以那个常量结尾** ⇒ 边界转换点仍映射得到 {"store"}
           （这一条是命：会话表只有两列，没有 missing_slot，
             延续轮认槽位靠对常量做字符串比较）
    阴性1  点名一家**真实存在**的门店 ⇒ 逐字维持原行为
    阴性2  **没点名门店** ⇒ 上游仍然补默认 all —— ⛔ 不许改回必答题
           （2026-08-07 实测：15 个问句里反问占 80%，烧掉 34,113 token 零答案）
    阴性3  门店名单不可用 ⇒ 逐字退回改动前（fail-open 纪律）
    接线   AST 数上游两个块真的引用了新判据

设计卡 `docs/decisions/2026-08-19-点名一家不存在的门店-静默顶替-设计卡.md`。
"""
from __future__ import annotations

import ast
import inspect
import textwrap
from dataclasses import replace

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant.restaurant_intent import (
    STORE_SCOPE_CLARIFICATION_QUESTION,
    _named_store_in_question,
    store_not_found_clarification,
)

_STOREMISS_STORES = (
    "模拟·徐汇美罗城店", "模拟·静安嘉里中心店", "模拟·杨浦五角场店", "模拟·宝山大场社区店",
)
_STOREMISS_FAKE = "北京三里屯店"
_STOREMISS_REAL = "模拟·徐汇美罗城店"
#: 没点名任何门店、但**按门店拆**的问句 —— 阴性2 的载体。
_STOREMISS_NO_STORE_Q = "哪家店最差"


# ══ 一 · 纯判据：「老板点名了一家门店吗」 ═══════════════════════════════
class TestTheNamedStorePredicate:
    """⛔ 本判据**不问**「它在不在名单里」—— 那是 guard 的活（唯一权威）。
    上游只需要停止抢先，⛔ 不做第二份核对（形态 D）。"""

    def test_llm_named_a_store(self):
        assert _named_store_in_question(
            {"store": _STOREMISS_FAKE}, f"{_STOREMISS_FAKE}卖得怎么样"
        ) == _STOREMISS_FAKE

    def test_llm_named_a_real_store_counts_too(self):
        """真店也算「点名了」—— 判据是「说没说」，⛔ 不是「有没有这家店」。"""
        assert _named_store_in_question(
            {"store": _STOREMISS_REAL}, f"{_STOREMISS_REAL}卖得怎么样"
        ) == _STOREMISS_REAL

    def test_a_hallucinated_name_not_in_the_question_does_not_count(self):
        """⛔ 模型编一个问句里没有的名字不算「老板点名了」——
        与 `_validated_llm_store_names` 用的是**同一个** `_verbatim_entity`。"""
        assert _named_store_in_question(
            {"store": "模拟·浦东金桥社区店"}, "哪家店最差") is None

    def test_no_store_named_at_all(self):
        """🔴 阴性2 的判据侧：这一条一旦返回非 None，
        「门店范围不再是必答题」那条裁定就被我改回必答题了。"""
        assert _named_store_in_question({}, _STOREMISS_NO_STORE_Q) is None
        assert _named_store_in_question({"store": None}, "这个月生意怎么样") is None
        assert _named_store_in_question({}, "全部门店最近30天营收多少") is None

    def test_the_boss_own_wording_counts_when_the_model_gave_null(self):
        """🔴 2026-08-19 晚补：模型给 `store=null` 时，认老板自己的措辞。

        prod 实测（`_store_absent_verify` 第二轮）模型 **3/3 给 null** ——
        只信模型提名的话，这一刀在那些轮次上够不着。
        """
        assert _named_store_in_question(
            {"store": None, "stores": []}, f"{_STOREMISS_FAKE}卖得怎么样"
        ) == _STOREMISS_FAKE

    def test_a_description_is_not_a_name_even_from_his_wording(self):
        """🔴 而「拖后腿的那家店」这类**描述**仍然不算点名。

        ⛔ 第一版直接退回 `extract_store_mention`，全量回归当场打红
        `test_模型没说缺门店时同样把按门店拆读成全部门店`。
        ⇒ 加了结构判据（⛔ 不是往词表里加「拖后腿」—— 那永远补不完，
          而且词表棘轮零余量）。
        """
        for q in ("拖后腿的那家店是哪家", "亏钱的那家店是哪个",
                  "最差的那家店", "生意最差的这家店怎么回事"):
            assert _named_store_in_question({"store": None, "stores": []}, q) is None, (
                f"「{q}」被当成点名了 —— 按门店拆会退回必答题")

    def test_a_superlative_reference_is_not_a_named_store(self):
        """🔴 这一条是**全量回归当场打红**换来的，⛔ 不是我想出来的。

        第一版判据退回 `extract_store_mention(query)`，而

            extract_store_mention("拖后腿的那家店是哪家") == "拖后腿的那家店"

        于是「按门店拆」的问句被当成「他点名了一家店」，
        `test_模型没说缺门店时同样把按门店拆读成全部门店` 当场红 ——
        那条断言守的是**需求**（⛔ 不是历史），所以改的是判据这一头。
        """
        for q in ("拖后腿的那家店是哪家", "最差的那家店", "哪家店成本最高",
                  "这家店怎么样"):
            assert _named_store_in_question({"store": None, "stores": []}, q) is None, (
                f"「{q}」里没有店名，判据却说他点名了 —— 按门店拆会退回必答题")


# ══ 二 · 纯判据：那句反问长什么样 ═════════════════════════════════════
class TestTheClarificationText:

    def test_it_names_the_store_the_boss_typed(self):
        text = store_not_found_clarification(_STOREMISS_FAKE, _STOREMISS_STORES)
        assert _STOREMISS_FAKE in text, f"没把老板打的那个名字说出来：{text!r}"

    def test_it_lists_real_stores_so_he_can_correct_himself(self):
        text = store_not_found_clarification(_STOREMISS_FAKE, _STOREMISS_STORES)
        shown = [n for n in _STOREMISS_STORES if n in text]
        assert len(shown) >= 2, f"只报出 {len(shown)} 家在营门店，他没法自己纠正：{text!r}"

    def test_it_offers_the_new_store_way_out(self):
        """⛔ 不把一个对的输入当成错的：可能是刚开的店还没进档案。"""
        text = store_not_found_clarification(_STOREMISS_FAKE, _STOREMISS_STORES)
        assert "刚开" in text or "新店" in text, (
            f"没给「是不是刚开的店」这条出路：{text!r}")

    def test_it_says_it_will_not_substitute(self):
        """老板要能看出「我不会拿全店合计糊弄你」—— 那正是改动前发生的事。"""
        text = store_not_found_clarification(_STOREMISS_FAKE, _STOREMISS_STORES)
        assert "顶替" in text or "合计" in text, f"没说清不会顶替：{text!r}"

    # ── 承重3：边界转换点 ─────────────────────────────────────────
    def test_it_ends_with_the_constant_so_the_slot_still_maps_back(self):
        """🔴 这一条是半 B 的命。

        `restaurant_pending_clarifications` 只有 `original_query` /
        `clarification_question` 两列 —— **没有 missing_slot**。延续轮认槽位
        靠 `_slots_of_clarification` 比常量。问句一旦变成纯动态文案：
        4 个按钮认不出来，老板的回答被拼成
        「北京三里屯店卖得怎么样 全部门店」重走一轮 ⇒ 又检测到假店 ⇒ **循环**。
        """
        text = store_not_found_clarification(_STOREMISS_FAKE, _STOREMISS_STORES)
        assert text.endswith(STORE_SCOPE_CLARIFICATION_QUESTION), (
            "常量没留在结尾 —— 延续轮会认不出这是门店那一问")
        assert ri._slots_of_clarification(text) == frozenset({"store"}), (
            "边界转换点没认出来 ⇒ 按钮会变成死的")
        assert ri._slot_of_clarification(text) == "store", (
            "单数版没跟着放宽 —— 同一条判据两份，它一定会漂（形态 D）")

    def test_the_plain_constant_still_maps_back(self):
        """阴性：放宽边界转换点**不许**改变原来那条路。"""
        assert ri._slots_of_clarification(
            STORE_SCOPE_CLARIFICATION_QUESTION) == frozenset({"store"})
        assert ri._slot_of_clarification(
            STORE_SCOPE_CLARIFICATION_QUESTION) == "store"

    def test_an_unrelated_question_still_maps_to_nothing(self):
        """阴性：⛔ 放宽不能宽到把别的问句也认成门店问。"""
        assert ri._slots_of_clarification("你想看哪个时间范围？") == frozenset()
        assert ri._slots_of_clarification(
            ri.COMBINED_SCOPE_TIME_CLARIFICATION_QUESTION
        ) == frozenset({"time", "store"}), "合成问句被放宽改坏了"

    def test_the_constant_in_the_middle_is_not_a_store_question(self):
        """🔴 这一条专门守「`endswith` 别滑成 `in`」。

        没有它，把判据写成 `常量 in question` 也全绿 ——
        而那就是本轮已经栽过五次的子串判据：任何**引用**到这句话的文本
        （日志、模板、把两问拼在一起的合成句）都会被认成门店那一问。
        """
        assert ri._slots_of_clarification(
            STORE_SCOPE_CLARIFICATION_QUESTION + "\n\n另外，你想看哪个时间范围？"
        ) == frozenset(), "常量出现在**中间**也被认成门店问 —— 判据从「结尾」滑成了「包含」"

    # ── 阴性3：fail-open ──────────────────────────────────────────
    def test_fail_open_when_the_store_list_is_unavailable(self):
        """名单不可用 ⇒ **逐字**退回改动前那句。
        ⛔ 名单都没读到就敢说「名单里没有这家店」，那是拿缺席当证据。"""
        assert store_not_found_clarification(_STOREMISS_FAKE, ()) == (
            STORE_SCOPE_CLARIFICATION_QUESTION)
        assert store_not_found_clarification(_STOREMISS_FAKE, None) == (
            STORE_SCOPE_CLARIFICATION_QUESTION)


# ══ 三 · 真入口：guard ════════════════════════════════════════════════
def _spec_named_store(store, *, scope=None, defaulted=False):
    """LLM 已经定好 intent、老板点名了一家门店的那个形状。

    ⚠️ `store_options` 直接给上 ⇒ guard 里 `spec.store_options or await
       _load_store_options(...)` 短路，**不碰 IO**（同 dish 那一刀的桩）。
    """
    return ri._build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        f"{store}卖得怎么样",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
        llm_semantics_authoritative=True,
        llm_store=None,
        llm_dimensions=("store",),
        llm_store_scope=scope,
        store_scope_defaulted=defaulted,
        clarification_needed=False,
        store_options=_STOREMISS_STORES,
    )


@pytest.mark.asyncio
async def test_guard_tells_which_store_is_missing(anyio_backend=None):
    """承重1+2：真入口上，点名名单外的门店 ⇒ 反问且说清是哪家。"""
    spec = _spec_named_store(_STOREMISS_FAKE)
    assert spec.store_slot == _STOREMISS_FAKE, (
        f"前提：门店槽位没进去（{spec.store_slot!r}），后面量的是别的事")
    out = await ri._apply_store_scope_guard(None, "MOCK_REST", spec, None)
    assert out.clarification_needed is True, "还在直接作答 —— 静默顶替没修掉"
    assert out.missing_slot == "store"
    assert _STOREMISS_FAKE in (out.clarification_question or ""), (
        f"反问里没说是哪家店：{out.clarification_question!r}")
    assert out.store_scope is None and out.store_slots == ()


@pytest.mark.asyncio
async def test_the_missing_store_is_told_before_asking_for_a_time_range(
        anyio_backend=None):
    """🔴 承重（第二刀）：门店核对必须排在 `store+time` 合成反问**之前**。

    prod 实测（`_store_absent_verify`，3 轮走出三条路）：模型多报一个「缺时间」，
    同一句「北京三里屯店卖得怎么样」就落到合成反问上，老板拿到通用的
    「哪一组门店、哪个时间范围？」——**一个字没提那家店不存在**。

    ▎与 dish 侧修之前完全同形。判据同一条：**问之前先看这个问题问了有没有用**
    ▎—— 门店不存在时，他把时间答出来产品照样给不出。
    """
    # ⚠️ 这里**直接构造 guard 的入参**，⛔ 不经 `_build_spec` —— 两次尝试都被
    #    前提断言当场拦下，留痕（形态 B‴：桩必须是真实上游产得出的形状）：
    #      ① `llm_store=` 传名字 ⇒ `_build_spec` 把 `store_scope` 定成 'single'
    #         ⇒ guard 开头 `or spec.store_scope` **早退**，量不到顺序；
    #         而 prod 的形状是 `store_slot` 有值、`store_scope=None`
    #         （假店过不了 `_validated_llm_store_names`，名字来自 deterministic）。
    #      ② `llm_store=None` ⇒ contract-repair 开火并**清掉 clarify**。
    #    guard 的输入契约就是一个 spec，而 prod r2 在 guard 入口正是这个状态：
    #    store_slot 有值 / scope None / 挂着一句时间反问。
    spec = ri._seal_query_plan(replace(
        _spec_named_store(_STOREMISS_FAKE),
        clarification_needed=True,
        clarification_question=ri.TIME_CLARIFICATION_QUESTION,
    ))
    assert "time" in ri._slots_of_clarification(spec.clarification_question), (
        "前提：这句反问必须被判成缺时间，否则本条量的是另一件事")
    assert spec.store_slot == _STOREMISS_FAKE, "前提：门店槽位没进去"

    out = await ri._apply_store_scope_guard(None, "MOCK_REST", spec, None)
    assert out.missing_slot == "store", (
        f"落到了 {out.missing_slot!r} 那一支 —— 门店核对被时间反问挡在后面了")
    assert _STOREMISS_FAKE in (out.clarification_question or ""), (
        f"老板拿到的是通用反问，没说那家店不存在：{out.clarification_question!r}")


@pytest.mark.asyncio
async def test_guard_leaves_a_real_store_alone(anyio_backend=None):
    """阴性1：真店 ⇒ ⛔ 不误伤。"""
    spec = _spec_named_store(_STOREMISS_REAL, scope="single")
    out = await ri._apply_store_scope_guard(None, "MOCK_REST", spec, None)
    assert out.clarification_needed is False, (
        f"把一家真实存在的门店也拦了：{out.clarification_question!r}")


# ══ 四 · 上游不再抢先 ═════════════════════════════════════════════════
def _t3(parsed, query):
    return ri._semantic_spec_from_t3(
        parsed, query, available_stores=_STOREMISS_STORES)


def _parsed(**over):
    """模型输出的那个形状。

    ⚠️ `time_range` 必须是 **None 或 dict** —— 第一版我写了字符串 "最近30天"，
       整份契约走 fail-closed（`planner_authority='llm_contract_incomplete'`，
       `intent=''`），于是两条上游断言红在**桩**上而不是红在被守的行为上。
       `None` 是真实上游产得出的形状（Q08 实测：五道菜的 `time_range` 全是 null）。
       ⇒ 下面 `test_precondition_*` 钉住这一点，⛔ 不让它再静默发生。
    """
    base = {
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "time_range": None, "wants_margin": True, "asks_profitability": False,
        "requested_metrics": [], "analysis_action": "compare",
        "dimensions": ["store"], "dish": None, "store": None, "stores": [],
        "store_scope": None, "confidence": 0.95,
        "clarification_needed": True, "missing_fields": ["store_scope"],
        "clarification_question": "你想看哪家店？", "clarification_options": [],
    }
    base.update(over)
    return base


def test_precondition_the_stub_passes_the_llm_contract():
    """前提：桩必须过契约闸，否则下面三条量的是 fail-closed，不是被守的行为。"""
    spec = _t3(_parsed(), _STOREMISS_NO_STORE_Q)
    assert spec.planner_authority != "llm_contract_incomplete", (
        "桩没过契约闸 —— 后面的断言无论红不红都没有意义")
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"


def test_upstream_stops_preempting_when_an_unknown_store_was_named():
    """承重：老板点名了门店 ⇒ 上游**不再**把 scope 定死，让 guard 有机会跑。"""
    spec = _t3(_parsed(store=_STOREMISS_FAKE), f"{_STOREMISS_FAKE}卖得怎么样")
    assert spec.store_scope != "all", (
        "上游又把它定成全部门店了 —— guard 的核对会被 `or spec.store_scope` 跳过")
    assert spec.store_scope_defaulted is False


def test_upstream_still_defaults_when_no_store_was_named():
    """🔴 阴性2（本文件最重要的一条）：没点名门店 ⇒ 仍然补默认。

    ⛔ 这一条红了就说明我把「门店范围不再是必答题」改回了必答题 ——
    2026-08-07 拿实测做过取舍（15 个问句里反问占 80%，烧掉 34,113 token 零答案），
    owner 2026-08-11 拍板「总部走乙」。
    """
    spec = _t3(_parsed(), _STOREMISS_NO_STORE_Q)
    assert spec.store_scope == "all", (
        f"没点名门店的问句也不补默认了 —— 反问回归必答题（scope={spec.store_scope!r}）")
    assert spec.store_scope_defaulted is True


def test_upstream_leaves_a_real_named_store_as_single():
    """阴性1（上游侧）：点名真店 ⇒ 走 single 那条正路。"""
    spec = _t3(_parsed(store=_STOREMISS_REAL), f"{_STOREMISS_REAL}卖得怎么样")
    assert spec.store_scope == "single"
    assert spec.store_slots == (_STOREMISS_REAL,)


# ══ 五 · 接线（AST）══════════════════════════════════════════════════
def test_both_upstream_blocks_consult_the_new_criterion():
    """⚠️ 挡得住「删掉接线」，⛔ 挡不住 `if False` —— 行为那半在上面和 prod 真跑。

    硬约束 8：`not store_names` 当「他没说门店」用的有 4 处，
    改 2 处、**登记不改 2 处**（L6662 归一触发条件 / L6994 简称歧义触发条件，
    那两处的 `not store_names` 是「上面几条都没留下名字」，与本判据无关）。
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(ri._semantic_spec_from_t3)))
    used = [n for n in ast.walk(tree)
            if isinstance(n, ast.Name) and n.id == "named_store_mentioned"]
    assert len(used) >= 3, (
        f"新判据在上游只出现 {len(used)} 次（1 处定义 + 2 处引用 = 至少 3）"
        " —— 有一个块没接上")


def test_the_guard_branch_uses_the_shared_wording():
    """阴性4：措辞只有一份（形态 D）—— guard 必须调那个函数，
    ⛔ 不在分支里手写第二份文案。"""
    src = inspect.getsource(ri._apply_store_scope_guard)
    tree = ast.parse(src.replace("\nasync def", "async def"))
    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
             and getattr(n.func, "id", None) == "store_not_found_clarification"]
    assert len(calls) == 1, f"guard 里调用 store_not_found_clarification {len(calls)} 次"
