# -*- coding: utf-8 -*-
"""菜单目录在**执行期**接上没有 —— 缺口 #1 / #13。

## 这份文件守的是什么

`_catalogue_says_not_a_dish`（「菜单说了算」）2026-07 就写好了，也确实接在
`_extract_dish_candidate_single` 末尾。但 2026-08-18 prod 实测发现：

    `dish_catalogue_scope` **只包住 `parse_restaurant_query`**，
    执行期（`tiered_answer`）跑在作用域外 ——
    于是那道闸在**唯一一次结果能到达老板面前的调用**上恒 fail-open。

探针逐条打「每次调用时目录在不在」，长这样::

    '香辣蟹煲卖得怎么样'  目录=10   ->  None      ← 在 parse 里, 正确否决
    …(共 7 次)…
    '香辣蟹煲卖得怎么样'  目录=None ->  '香辣蟹煲'  ← 在执行里, 放行

⇒ 形态 B 的一个更彻底的版本：**机制在、也接上了、但接的那一份跑在
  一个它看不见东西的作用域里。**

## 判据纪律（每条断言都遵守）

* ⛔ **断言不许 grep 源码文本** —— 注释里引用 prod 原文会被数进去。
  要证明「某段代码在」一律走 AST（`_calls_in`）。
* ⛔ **AST 断言挡不住 `if False` 绕过** —— 所以「没接上会红」那一条用的是
  **真的把 `async with` 整段删掉**（`test_removing_the_scope_breaks_the_gate`），
  ⛔ 不是断言源码里有那个名字。
* 每条正向断言都配一条能让它红的对照。
"""
from __future__ import annotations

import ast
import inspect
import re
from typing import Optional

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as S
from smartbi.gold.restaurant import restaurant_ops_router as R


#: 一份像样的菜单。⛔ 不含「最差」「香辣蟹煲」—— 那正是被测的两种输入。
_MENU = frozenset({
    "米饭", "娃娃菜", "干锅花菜", "凉拌木耳", "水煮牛肉", "藤椒鸡", "鲈鱼",
    "罗氏虾", "酸梅汤", "红糖糍粑",
})


@pytest.fixture
def menu():
    token = R.set_dish_catalogue(_MENU)
    try:
        yield _MENU
    finally:
        R.reset_dish_catalogue(token)


@pytest.fixture
def no_menu():
    """目录不可用 —— fail-open 那一支。"""
    token = R.set_dish_catalogue(None)
    try:
        yield None
    finally:
        R.reset_dish_catalogue(token)


def _calls_in(func, name: str) -> int:
    """函数体里**真正的调用节点**数。

    ⛔ 不用 `"name" in source` —— docstring / 注释里提到函数名会被数进去，
       本仓已经为此造过一次假读数（「调用点变成 3 处」）。
    """
    tree = ast.parse(inspect.getsource(func))
    return sum(
        1
        for node in ast.walk(tree)
        if isinstance(node, ast.Call)
        and (
            getattr(node.func, "id", None) == name
            or getattr(node.func, "attr", None) == name
        )
    )


# ── ① 执行入口必须绑住目录 ──────────────────────────────────────────────

def test_tiered_answer_binds_the_catalogue_around_the_whole_turn():
    """执行入口把菜单目录绑住整轮，并且**一定复位**。

    ⚠️ 这一条只证明「结构在」。「它真的在守东西」由下面那条**把目录摘掉**的
       对照证明 —— AST 断言挡不住 `if False`。
    """
    assert _calls_in(S.tiered_answer, "load_dish_catalogue") == 1, (
        "执行入口没有加载菜单目录 —— 那道闸在执行期就是 no-op")
    assert _calls_in(S.tiered_answer, "set_dish_catalogue") == 1
    assert _calls_in(S.tiered_answer, "reset_dish_catalogue") == 1, (
        "没有复位 —— contextvar 是 task 级的，会把这个租户的菜单泄漏给下一段代码")
    # 复位必须在 finally 里: 本函数几十个 return 点，逐个复位必漏一个
    tree = ast.parse(inspect.getsource(S.tiered_answer))
    in_finally = any(
        isinstance(node, ast.Try)
        and any(
            isinstance(c, ast.Call)
            and getattr(c.func, "id", None) == "reset_dish_catalogue"
            for stmt in node.finalbody
            for c in ast.walk(stmt)
        )
        for node in ast.walk(tree)
    )
    assert in_finally, "复位不在 finally 里 —— 任何一条早退路径都会泄漏目录"


def test_the_entry_is_not_split_into_a_thin_shell():
    """⛔ 不许把 `tiered_answer` 拆成「薄壳 + 实现」。

    🔴 我第一版就是那么写的（为了少缩进 900 行）。仓里有 **6 处**
       `inspect.getsource(svc.tiered_answer)` 的闸，其中 **2 处是
       `assert X not in src`** —— 壳里当然没有 X，那两道闸会**静默变成恒真式**。
       另外 4 处会当场变红（那次实测新增 11 条失败）。

    ▎为了少缩进而亲手做出两道假绿的闸，是最不划算的一笔交易。

    这条断言就是那个机械触发点：⛔ 不靠「下次记得」。
    """
    src = inspect.getsource(S.tiered_answer)
    assert len(src.splitlines()) > 300, (
        "`tiered_answer` 变短了 —— 业务体大概被搬进了另一个函数。"
        "仓里 6 处 getsource 闸读的是这个函数，其中 2 处是 `not in src`，"
        "它们会静默变成恒真式。把实现搬回来，或者把那 6 处一起改掉再删这条断言。")
    # 阳性对照: 这个函数里确实有那 6 道闸各自要找的东西, 说明它们还看得见
    for marker in ("_drop_planner_invented_metrics", "_STORE_SCOPE_MISMATCH"):
        assert marker in src, (
            f"{marker} 不在 `tiered_answer` 里了 —— 读它的那道闸已经瞎了")


def test_removing_the_scope_breaks_the_gate(menu):
    """**变异：把作用域整段删掉**，抽取器当场放行一个菜单里没有的词。

    这一条是 `test_tiered_answer_binds...` 的对照：它证明那个作用域
    **真的在决定结果**，而不只是一段长得像的代码。

    ⛔ 变异不是改源码字符串，是**在真实的执行条件上**把目录摘掉
       （`set_dish_catalogue(None)` == 作用域没绑时的状态）。
    """
    # 有目录（= 入口绑上了）
    assert R._extract_dish_candidate_single("香辣蟹煲卖得怎么样") is None, (
        "目录在的时候没有否决一个菜单里查不到的词")
    # 变异：目录没绑（= 把 `async with dish_catalogue_scope(...)` 删掉之后的状态）
    token = R.set_dish_catalogue(None)
    try:
        # 🔴 先证明**变异真的到达了被测代码** —— 不然「不红」说明不了任何事
        assert R.current_dish_catalogue() is None, "变异没生效: 目录还在"
        leaked = R._extract_dish_candidate_single("香辣蟹煲卖得怎么样")
    finally:
        R.reset_dish_catalogue(token)
    assert leaked == "香辣蟹煲", (
        "变异之后抽取器**应该**放行那个假菜名（那正是 prod 上发生的事）；"
        "它没放行 ⇒ 这道闸守的不是目录，上面那条断言是空的")


def test_planning_no_longer_opens_a_second_scope():
    """规划那一步**不再**自己开一个作用域 —— 同一个东西不留两份（形态 D）。"""
    assert _calls_in(S.tiered_answer, "dish_catalogue_scope") == 0, (
        "函数体里还留着一个 `dish_catalogue_scope`：它只包住规划那一步，"
        "而目录已经由入口那一格绑住整轮了 —— 两份一定会漂")


# ── ② 计划槽位那条兜底路要过目录 ────────────────────────────────────────

def test_ranking_plan_drops_a_dish_name_the_menu_cannot_find(menu):
    """「那最差的呢」→ LLM 把「最差」填进 dish 槽位 → 摘掉 + 交出那个词。"""
    kept, rejected = R.dish_slot_after_catalogue_check(
        "最差", ranking_direction="worst")
    assert kept is None, "排行计划上那个菜单里查不到的词没有被摘掉"
    assert rejected == "最差", "⛔ 静默丢掉了 —— 那个词必须交出来给老板看"


def test_a_named_dish_keeps_its_slot_even_when_the_menu_cannot_find_it(menu):
    """**阴性对照**：点名一道菜单里没有的菜 ⇒ ⛔ 不许摘。

    摘掉 = 拿全菜榜顶替他点名的那道菜 = 替他改题。
    这一条是 `ranking_direction` 那个条件的守卫；把那个条件删掉它就红。
    """
    kept, rejected = R.dish_slot_after_catalogue_check(
        "香辣蟹煲", ranking_direction=None)
    assert kept == "香辣蟹煲", (
        "点名的菜被摘掉了 —— 他问的就是这道菜，摘掉之后没题了，"
        "而下游会拿全部菜品的榜单顶替")
    assert rejected is None


def test_a_real_dish_is_never_touched(menu):
    """**阳性对照**：菜单里真有的菜，任何计划形状下都不许动。"""
    for direction in (None, "worst", "best"):
        kept, rejected = R.dish_slot_after_catalogue_check(
            "米饭", ranking_direction=direction)
        assert kept == "米饭", f"真菜在 ranking_direction={direction!r} 上被摘掉了"
        assert rejected is None


def test_fail_open_when_the_catalogue_is_unavailable(no_menu):
    """目录不可用 ⇒ 行为与改动前**逐字相同**（既有纪律）。

    ⛔ 这条不能靠「目录为空时判据恒 False」自证 —— 那是恒真式。
       它配的对照是上面 `test_ranking_plan_drops_...`：同样的输入、
       同样的 `ranking_direction`，只有目录在不在不同，结论必须相反。
    """
    kept, rejected = R.dish_slot_after_catalogue_check(
        "最差", ranking_direction="worst")
    assert kept == "最差", "目录不可用时收紧了 —— fail-open 被破坏"
    assert rejected is None


def test_the_service_calls_the_check_exactly_once():
    """三个 `llm_dish` 来源汇流之后**只判一次**（形态 D：三份一定会漂）。"""
    assert _calls_in(S.tiered_answer,
                     "dish_slot_after_catalogue_check") == 1
    assert _calls_in(S.tiered_answer,
                     "dish_catalogue_miss_disclosure") == 1, (
        "摘掉了却没有把它说出来 ——「⛔ 绝不静默丢掉」")


def test_the_dropped_word_reaches_the_boss(menu):
    """披露那句话必须**指名道姓**，否则老板不知道我们摘掉了什么。"""
    text = R.dish_catalogue_miss_disclosure("最差")
    assert "最差" in text, "披露里没有那个被摘掉的词"
    assert "菜单里没查到" in text
    assert "新菜" in text, "没告诉他「如果是新菜该怎么办」"


# ── ③ 拒答要说清三件事，且不许说假话 ──────────────────────────────────

def test_a_dish_outside_the_menu_is_told_it_may_be_new(menu):
    """菜单里没有 ⇒ 缺什么 / 怎么拿到 / 他要干什么，三件都要有。"""
    text = R.dish_not_found_answer_text("香辣蟹煲")
    assert "香辣蟹煲" in text
    assert "菜单里没查到" in text, "没说清缺的是什么"
    assert "新菜" in text and "加进菜单" in text, "没说怎么拿到 / 他要干什么"
    # 在售菜单要真的列出来 —— ⛔ 不是让他自己猜
    assert any(name in text for name in _MENU), "没有把在售的菜列给他"
    # 阴性对照：⛔ 不许再说那句在这一支上为假的话
    assert "没有找到名为" not in text


def test_a_dish_inside_the_menu_is_never_told_it_does_not_exist(menu):
    """🔴 **菜在菜单里、只是这段没卖** ⇒ ⛔ 绝不许说「没有找到名为「X」的菜品」。

    那是一条**经不起他去查**的提示：他打开菜单一看明明有这道菜。
    一条误发的提示烧掉的是「这东西说的话能信」。

    ⚠️ 这个形状在 MOCK_REST 上**不可达**（10 道菜最近 30 天全部有销量，
       实测差集为空），所以只有这里的用例守着它 —— 见设计卡 §4 的登记。
    """
    text = R.dish_not_found_answer_text("水煮牛肉")
    assert "没有找到名为" not in text, (
        "菜单里明明有这道菜，却告诉老板「没有找到名为「水煮牛肉」的菜品」")
    assert "在菜单里" in text and "一份都没卖出去" in text, (
        "没有把真正的事实说出来：菜是有的，缺的是销量")


def test_the_legacy_wording_is_preserved_when_the_catalogue_is_down(no_menu):
    """目录不可用 ⇒ 分不开两种成因 ⇒ **逐字**退回改动前那句。

    这一条的对照就是上面两条：同样两个输入，目录在的时候措辞完全不同。
    """
    for candidate in ("香辣蟹煲", "水煮牛肉"):
        text = R.dish_not_found_answer_text(candidate)
        assert text == (
            f"**没有找到名为「{candidate}」的菜品**，"
            "不能给出该菜的销量或毛利，也不会用全部菜品的榜单替代。\n\n"
            "请核对菜名；可以先问「哪个菜卖得最好」查看在售菜品。"
        ), "目录不可用时的措辞变了 —— fail-open 要求逐字相同"


def test_dish_ambiguity_speaks_like_the_store_side():
    """多条命中的确认式反问照门店那半的形状（缺口 #13 菜品侧）。

    ⚠️ 这一支在 MOCK_REST 上**不可达**（10 道菜名两两互不为子串），
       只有这里的用例守着它 —— 见设计卡 §4 的登记。
    """
    from smartbi.gold.restaurant.restaurant_intent import store_ambiguity_question

    text = R.dish_ambiguity_question("藤椒", ("招牌藤椒味(单人份)", "招牌藤椒味(双人份)"))
    assert "你说的「藤椒」" in text
    assert "2 道对得上" in text
    assert "招牌藤椒味(单人份)、招牌藤椒味(双人份)" in text
    assert text.endswith("你要看哪一道？")
    # ⛔ 黑话对照：这几个词一个都不许出现
    for jargon in ("匹配到", "候选", "请指定", "再查询", "维度", "实体"):
        assert jargon not in text, f"黑话漏给店长了: {jargon}"
    # 与门店那半是同一个形状（⛔ 同一件事不许有两套说法）
    store_text = store_ambiguity_question("宝山", ("模拟·宝山大场社区店",))
    shape = re.compile(r"^你说的「.+?」，我这边有 \d+ (家|道)对得上：.+。你要看哪一(家|道)？$")
    assert shape.match(text), "菜品侧不是那个形状"
    assert shape.match(store_text), "门店侧不是那个形状 —— 形状本身漂了"


# ── ⛔ 未做的登记（读得到的备忘，不是断言） ─────────────────────────────

def test_registered_not_done_shorthand_normalisation_uses_pos_rows_only(menu):
    """**登记**：简称归一今天只对**窗口内有销量**的 POS 行做，不对菜单全量。

    实测（prod MOCK_REST）唯一匹配那一支**已经在工作**：
        木耳 → 抬头「凉拌木耳」/ 花菜 → 「干锅花菜」/ 牛肉 → 「水煮牛肉」
    所以这一轮**没有**改它 —— 在这个租户上零差异，⛔ 不为了让读数好看去动它。

    还缺的是：菜单里有、但窗口内零销量的菜，简称归一不到它。
    解冻条件写在设计卡 §6。这里只钉住「今天的判据确实只看 POS 行」，
    以免下次有人以为它已经查过菜单了。
    """
    rows = [
        {"dish_name": "凉拌木耳", "normalized_name": "凉拌木耳", "product_id": 1},
    ]
    assert R._match_dish_rows("木耳", rows), "唯一匹配归一坏了"
    # 菜单里有「水煮牛肉」，但它不在这批 POS 行里 ⇒ 归一不到 —— 这就是那个缺口
    assert "水煮牛肉" in menu
    assert R._match_dish_rows("牛肉", rows) == [], (
        "如果这里开始命中了，说明归一已经改成查菜单全量 —— "
        "那就去把设计卡 §6 那条登记划掉")


# ── 🏠 跑在产品真实入口上（判据 B #1）─────────────────────────────────────
#
# 上面那些用例测的是 helper 和结构。这两条测**接线** ——
# 「摘掉的那个词，有没有真的出现在老板看到的字里」。
# ⛔ 少了它们，`test_the_service_calls_the_check_exactly_once` 只是一条 AST 断言，
#    而 AST 断言证明不了那句话到得了 `answer_text`。

def _ranking_spec(**overrides):
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    defaults = dict(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        domain="restaurant",
        date_range=(None, None),
        window_label="最近30天",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=("dish",),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
        clarification_needed=False,
        clarification_question=None,
        requested_metrics=("sales_volume",),
        planned_intents=("RESTAURANT_OPS_GROSS_MARGIN",),
        plan_version="restaurant-query-plan-v2",
        planner_authority="llm",
        plan_hash="dishcat-e2e",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


async def _drive(monkeypatch, spec, answer_text="1. 娃娃菜 — 销量 42,484 份"):
    """走真实的 `tiered_answer`，只桩掉外部 IO（库读目录 / resolver / 埋点）。"""
    from unittest.mock import AsyncMock

    from smartbi.gold.restaurant.restaurant_ops_router import OpsAnswer

    async def _menu(*_a, **_kw):
        return _MENU

    monkeypatch.setattr(S, "load_dish_catalogue", _menu)
    monkeypatch.setattr(S, "parse_restaurant_query", AsyncMock(return_value=spec))
    monkeypatch.setattr(S, "log_intent_capture", AsyncMock(return_value=1))
    resolver = AsyncMock(return_value=OpsAnswer(
        code=spec.intent, title="菜品销量排行", answer_text=answer_text,
        charts=[], kpis=[], meta={"dish_ranking": "worst"},
    ))
    monkeypatch.setattr(S, "_resolve_tiered", resolver)
    result = await S.tiered_answer(
        "那最差的呢", object(), "MOCK_REST", "restaurant_manager")
    return result, resolver


@pytest.mark.asyncio
async def test_the_dropped_word_appears_in_what_the_boss_reads(monkeypatch):
    """LLM 把「最差」填进菜名槽位 + 计划是排行 ⇒ 摘掉，**并且把它说出来**。"""
    spec = _ranking_spec(dish_slot="最差", ranking_direction="worst")
    result, resolver = await _drive(monkeypatch, spec)

    assert result["kind"] == "answer", "摘掉假菜名之后应该答得出排行，而不是拒答"
    assert "菜单里没查到「最差」" in result["answer_text"], (
        "⛔ 静默丢掉了：老板拿到一张排行表，却不知道我们把他说的那个词摘掉了")
    assert "新菜" in result["answer_text"], "没告诉他「如果那其实是道新菜」怎么办"
    # 摘掉的槽位**真的没有**传给 resolver（否则它照样会按假菜名限域去拒答）
    assert resolver.await_args.kwargs.get("dish_mention") is None


@pytest.mark.asyncio
async def test_a_named_dish_still_reaches_the_resolver(monkeypatch):
    """**阴性对照**：点名一道菜（计划不是排行）⇒ 槽位照传，⛔ 不许摘、不许披露。

    ⛔ 摘掉它 = 拿全菜榜顶替他点名的那道菜。这一条守的就是那件事。
    """
    spec = _ranking_spec(dish_slot="香辣蟹煲", ranking_direction=None)
    result, resolver = await _drive(monkeypatch, spec)

    assert resolver.await_args.kwargs.get("dish_mention") == "香辣蟹煲", (
        "点名的菜没传给 resolver —— 下游会拿全部菜品的榜单顶替他的问题")
    assert "菜单里没查到" not in (result.get("answer_text") or ""), (
        "什么都没摘，却发了一句「菜单里没查到」—— 一条误发的提示")


@pytest.mark.asyncio
async def test_a_real_dish_reaches_the_resolver_untouched(monkeypatch):
    """**阳性对照**：菜单里真有的菜，排行计划下也照传（改动不许碰它）。"""
    spec = _ranking_spec(dish_slot="米饭", ranking_direction="worst")
    _result, resolver = await _drive(monkeypatch, spec)
    assert resolver.await_args.kwargs.get("dish_mention") == "米饭"
