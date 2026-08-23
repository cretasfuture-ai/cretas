# -*- coding: utf-8 -*-
"""一个词表的结果，⛔ 不许**单独**否决 LLM 已经填好的槽位。

## 判据（一句话）

> 一个词表的结果，能不能【单独】否决 LLM 已经填好的槽位？
> **能 = 决策；不能 = 辅助。**

正确长相仓里现成（`restaurant_intent.py` 的 `llm_asked_store_grain`）：

    # ⛔ LLM 推断出的 `store_scope == "all"` 不算 —— 用一个猜出来的东西
    #    去否决另一个猜出来的东西, 两边都没有依据。

那一段里 `_asks_store_breakdown`（词表）**在**，但放行/否决的那一票是
`_resolver_serves_dimensions`（能力表）投的 —— 词表说了不算。

## ⛔ 这道闸不消灭词表

核心分界是**猜 vs 查**，⛔ 不是「词表 vs LLM」：词表和 LLM 都在猜，
目录 / 能力表 / 指标登记表在查。所以词表当**辅助**一律放行 ——
补一个 LLM 没填的槽、给 LLM 提候选、事后加注，都不在靶子里。
只拦一种用法：**词表单独把一个 LLM 已经填好的槽位收窄或清空**。

## 棘轮

**`MAX_LEXICAL_VETOES = 3`**（2026-08-20，从建闸当天的 4 下调，零余量）。

建闸当天（2026-08-19）实测正好 4，逐条是：

    ① restaurant_intent.py  dimension_list.remove("ingredient")
       6 个中文词(食材/原料/配料/采购/领料/库存)单独剥掉 LLM 给的 ingredient 维度
    ② restaurant_intent.py  dimensions = tuple(v for v in dimensions …)
       `_detect_comparison` 命中就剥掉 LLM 给的 time 维度
    ③ restaurant_intent.py  `_is_explicit_store_directory_query`
       一条纯正则同时清掉 requested_metrics / wants_margin / asks_profitability
       —— **3 处写、同一个决策 ⇒ 算 1**
    ④ restaurant_intent_service.py  changes["wants_margin"] = False
       「问句里没有毛利二字」就撤掉 planner 填的 wants_margin

2026-08-20 修掉一个（设计卡在 `docs/decisions/2026-08-20-*`）：

    ① → 6 个词照旧，**再加一票能力表**(`_resolver_serves_dimensions`) ⇒ ✅ 有旁证

⚠️ ② **量过、试过、撤了，仍在册**：收窄它（词表挪到解锁侧）会让路由落到
   `TREND_ANALYSIS`，而它按月分桶又剔残月 ⇒ prod A/B 实测三条问句 9/9 全部变差
   （丢掉了唯一那个同期对比数字，而按天/按周**两侧都没给出**）。
   ⛔ 不因为「试过了」就把它从靶子里拿掉。逐字读数与解冻条件见
   `docs/decisions/2026-08-20-比较句吃掉时间粒度-量过撤了.md`。

⚠️ ③ **也已收窄**（只在模型自述 `clarification_needed=True` 时才接管），
   按本闸自己的定义（「词表单独把 LLM **已经填好**的槽位收窄」）它已出靶，
   **但普查照旧点名** —— `clarification_needed` 是模型的自述，
   既不是词表也不是 `EVIDENCE_SOURCES` 里的「查」。
   ⛔ **不把它塞进 `EVIDENCE_SOURCES` 消音**（那是本闸最大的可被规避点）。
   ⇒ 计数偏保守，记在这里。

⚠️ 棘轮量的是**决策点**`(module, 函数, guard 原文)`去重后的数，
   ⛔ 不是「有多少行收窄写」（当天 7 处写 → 4 个决策点）。同一个判断拆成
   三处赋值不该虚高成 3 —— 本仓记过这条。

⚠️ 为什么是棘轮不是硬红：这 4 个都是**存量**，一上来硬红就是一道**当天被关掉**
   的闸（形态 E）。棘轮只保证**不再长**，⛔ 不代表这 4 个都合理 ——
   ③ 的 `_is_explicit_store_directory_query` 自己的 docstring 就写着它是
   「a post-LLM contract compiler guard」，即**有意**覆盖 LLM。

## 🔴 它看不见什么（代理判据，逐条标出来）

判据是**结构**的（AST），而「决策 / 辅助」是**语义**的。中间这三层都是代理：

| 代理 | 用什么当代理 | 看不见 |
|---|---|---|
| 「LLM 已经填好的槽位」 | `LLM_SLOTS` 名字 + `llm_*` 形参传染出来的局部名 | 槽位**运行时**空不空。所以判据收成「只认**收窄**写」（`.remove` / 清空 / 就地过滤）—— 收窄一个空槽位是 no-op，误报无害 |
| 「词表的结果」 | 中文字面量匹配 / 中文正则 / 已登记词表名 / 纯词法函数(不动点) | 词表被藏在一个**还调了别的东西**的函数里 ⇒ 那个函数不算词法判据 ⇒ **漏报** |
| 「单独」 | guard 里有没有 `EVIDENCE_SOURCES` 登记的名字 | 「查」发生在**别的函数体内**、guard 只看到函数名 ⇒ **误报**（出路：把那个函数登记进 `EVIDENCE_SOURCES`，登记是留痕不是豁免） |

已知的三个**漏报**面，写下来免得下一个人以为闸覆盖到了：

1. **累加器写法**。`for dim in spec.dimensions: … kept.append(dim)` 然后
   `_replace(spec, dimensions=tuple(kept))` —— 槽位名一次都没被直接写，闸看不见。
   仓里现成的一例是 `_drop_unanswerable_mislabeled_dimensions`（它**合规**：
   否决那一票是 `_RESOLVER_DIMENSIONS` 投的），所以当前漏的这一例不是缺陷 ——
   但换个人写一个**不合规**的累加器版本，闸同样一个字不说。
2. **换 resolver 而不动槽位**。词表命中就选另一条执行链，槽位原封不动 ——
   净效果同样是「词表说了算」，而它不是一次「收窄写」。
3. **跨函数 guard**。展开只在函数内做（单赋值局部名，深度 4）。

⇒ 所以本闸是**窄而可信**的那一头（形态 E）：宁可漏，不要天天误报被关掉。

## 载体

判据与两张登记表都在 `smartbi/scripts/audit_restaurant_wordlists.py` ——
**与词表棘轮同一个文件、同一份 `REGISTRY`**，⛔ 不另起第二份登记（形态 D）。
命令行：`python -m smartbi.scripts.audit_restaurant_wordlists --vetoes`。
"""
from __future__ import annotations

import pathlib

import pytest

from smartbi.scripts.audit_restaurant_wordlists import (
    EVIDENCE_SOURCES,
    LLM_SLOTS,
    MAX_LEXICAL_VETOES,
    ROOT,
    _cjk_dict_names,
    census,
    lexical_predicates,
    lexical_veto_census,
    lexical_veto_points,
)

#: ⛔ 零余量。既有的 `test_the_limit_is_not_slack` 用容差 3 守 88 那个数；
#: 这里存量只有 4，容差 3 等于允许**几乎翻倍**地悄悄新增 ⇒ 收成 0。
_SLACK_TOLERANCE = 0


@pytest.fixture(scope="module")
def vetoes():
    return lexical_veto_census()


# ══ 一 · 棘轮（承重）═════════════════════════════════════════════════════
class TestTheRatchetOnlyGoesDown:

    def test_no_new_lexical_veto_of_an_llm_slot(self, vetoes):
        """🔴 承重：新增一处「词表单独否决 LLM 槽位」必须先给它一个「查」。"""
        bad = [v for v in vetoes if not v.evidence]
        points = lexical_veto_points(bad)
        assert len(points) <= MAX_LEXICAL_VETOES, (
            f"新增了「词表单独否决 LLM 槽位」的决策点"
            f"（{len(points)} > {MAX_LEXICAL_VETOES}）：\n  "
            + "\n  ".join(
                f"{v.module}:{v.lineno} {v.function} [{v.kind}] {v.target}"
                f"\n      词表={list(v.wordlists)[:2]}  guard={v.guard[:110]}"
                for v in bad)
            + "\n\n⛔ 用一个猜出来的东西去否决另一个猜出来的东西，两边都没有依据。"
              "\n   出路：把否决那一票交给「查」（目录 / 能力表 / 指标登记表），"
              "\n         词表退回辅助位；或把那个「查」登记进 EVIDENCE_SOURCES。"
        )

    def test_the_limit_is_not_slack(self, vetoes):
        """⛔ 上限不许比实际值高 —— 留出的余量就是悄悄新增的空间。"""
        points = lexical_veto_points([v for v in vetoes if not v.evidence])
        assert MAX_LEXICAL_VETOES - len(points) <= _SLACK_TOLERANCE, (
            f"上限 {MAX_LEXICAL_VETOES} 比实际 {len(points)} 宽出 "
            f"{MAX_LEXICAL_VETOES - len(points)} —— 把它调到实际值"
        )


# ══ 二 · 阳性对照：判据必须真的看得见东西 ═══════════════════════════════
class TestTheCensusActuallySeesThings:
    """⛔ 少了这些，一个永远返回空的 `lexical_veto_census()` 会让棘轮**永远绿**，
    而它一处都没看过（本仓记过「跑批天天绿但一个样本没看」）。"""

    def test_it_finds_the_known_decision_points(self, vetoes):
        """📏 建闸当天逐条读过的那些，按 (模块, 函数) 锚定（⛔ 不锚行号）。

        ⚠️ 2026-08-20 从 4 个改成 2 个：`_build_spec` 里那三处**已经修好**
           （① 加了一票能力表、② 词表挪到解锁侧），普查把它们判成 ✅ 有旁证。
           ⛔ 这不是「仪器坏了」，所以它不该再出现在这张**违例**清单里 ——
              把它留着就是让一条断言守一个**已经不存在**的缺陷（形态 C‴）。
           ⇒ 它换成了下面 `test_it_still_sees_build_spec_as_compliant`：
              同一棵真实树上，`_build_spec` 仍然**被看见**，只是判成了合规。
              这样「仪器在真实树上活着」这个性质一格都没丢。
        """
        bad = {(v.module, v.function) for v in vetoes if not v.evidence}
        for known in (
            ("restaurant_intent.py", "_semantic_spec_from_t3"),
            ("restaurant_intent_service.py", "_drop_planner_invented_metrics"),
        ):
            assert known in bad, f"当天实测的违例点 {known} 不见了 —— 仪器可能坏了"

    def test_it_still_sees_build_spec_as_compliant(self, vetoes):
        """🔴 上一条腾出来的那一格：`_build_spec` 必须**仍然被看见**。

        ⛔ 少了它，一个「`_build_spec` 整个扫不到」的仪器故障，会和
           「那三处修好了」长得一模一样 —— 而前者会让棘轮从此对那个函数失明。
        """
        ok = {(v.module, v.function) for v in vetoes if v.evidence}
        assert ("restaurant_intent.py", "_build_spec") in ok, (
            "`_build_spec` 里一处收窄都没看见 —— 仪器对那个函数失明了，"
            "⛔ 不是那三处修好了")
        n = len([v for v in vetoes
                 if (v.module, v.function) == ("restaurant_intent.py", "_build_spec")])
        assert n == 3, f"`_build_spec` 里的收窄写从 3 处变成了 {n} 处 —— 先确认这是有意的"

    def test_it_also_finds_the_compliant_shape(self, vetoes):
        """🔴 阴性方向的阳性对照：**合规**的那一个必须被看见并判成合规。

        它就是本判据抄的那句注释所在的那段（`llm_asked_store_grain`）：
        词表 `_asks_store_breakdown` 在 guard 里，而投票的是能力表。
        ⛔ 少了这条，一个「见词表就报」的判据也能让上面全绿。
        """
        ok = [v for v in vetoes if v.evidence]
        assert ok, "一处合规的否决都没找到 —— 证据源那一半判据可能从没生效"
        assert any("_resolver_serves_dimensions" in v.evidence for v in ok), (
            f"能力表那一票没被认成证据：{[v.evidence for v in ok]}")

    def test_the_wordlist_side_is_wired_to_the_same_registry(self):
        """形态 D：判「什么是词表」用的是**同一个** `census()`，⛔ 不是第二份。"""
        named, _inline = census()
        assert "_DISH_GENERIC_TOKENS" in named
        assert len(named) > 50, f"词表普查只看到 {len(named)} 个 —— 仪器坏了"


# ══ 三 · 两张登记表不许漂 ═══════════════════════════════════════════════
class TestTheRegistriesMeanSomething:

    def test_the_llm_slot_list_still_matches_build_spec(self):
        """`LLM_SLOTS` 的来源是 `_build_spec` 的 `llm_*` 形参 —— 别漂。

        ⚠️ 有三个是**改过名**的（`llm_dish`→`dish_slot` …），写下来；
           新增一个 `llm_*` 形参而不登记 ⇒ 那个槽位从此不在靶子里。
        """
        import ast
        import inspect

        from smartbi.gold.restaurant import restaurant_intent as ri

        tree = ast.parse(pathlib.Path(inspect.getfile(ri)).read_text("utf-8"))
        fn = next(n for n in ast.walk(tree)
                  if isinstance(n, ast.FunctionDef) and n.name == "_build_spec")
        params = {a.arg for a in fn.args.args + fn.args.kwonlyargs
                  if a.arg.startswith("llm_")}
        renamed = {"llm_dish": "dish_slot", "llm_store": "store_slot",
                   "llm_stores": "store_slots"}
        #: ⛔ 不是槽位，是「这次要不要听 LLM 的」这个开关本身。
        not_a_slot = {"llm_semantics_authoritative"}
        missing = {
            p for p in params - not_a_slot
            if renamed.get(p, p[len("llm_"):]) not in LLM_SLOTS
        }
        assert not missing, (
            f"`_build_spec` 新增了 {sorted(missing)} 却没登记进 LLM_SLOTS —— "
            "那个槽位从此不在靶子里，词表可以随便否决它")

    def test_the_evidence_registry_is_not_a_wordlist(self):
        """🔴 堵住最明显的绕过：把一个词表函数改名塞进 `EVIDENCE_SOURCES`。

        登记进「查」的东西，⛔ 不许同时被判定为**纯词法判据** ——
        那样等于拿猜当依据，正是本判据要拦的那件事。
        """
        import ast

        trees, dict_names = [], set()
        for path in sorted(ROOT.rglob("*.py")):
            try:
                tree = ast.parse(path.read_text(encoding="utf-8"))
            except SyntaxError:
                continue
            trees.append(tree)
            dict_names |= _cjk_dict_names(tree)
        named, _ = census()
        lex = lexical_predicates(trees, set(named), dict_names)
        assert len(lex) > 10, f"词法判据只认出 {len(lex)} 个 —— 仪器坏了，本条无意义"
        overlap = sorted(EVIDENCE_SOURCES & lex)
        assert not overlap, (
            f"{overlap} 既被登记成「查」，又被判定为纯词法判据 —— "
            "拿猜当依据，本判据就空转了")

    def test_the_evidence_registry_is_not_empty(self):
        """它空了 ⇒ 每一处否决都成了违例 ⇒ 闸会被当成误报关掉（形态 E）。"""
        assert len(EVIDENCE_SOURCES) >= 8, (
            f"证据源只剩 {len(EVIDENCE_SOURCES)} 个，登记表被改动过")


# ══ 四 · 对照实验（合成小树）═════════════════════════════════════════════
#
# 🔴 阳性 / 阴性都在这里做：把闸指到一棵**合成**的树上，逐个形状问它一次。
#    ⛔ 不用它替代真实树上的读数 —— 上面那三条阳性对照守的是真实树。

_POSITIVE = '''# -*- coding: utf-8 -*-
"""阳性: 词表单独否决 LLM 已填的维度槽位。"""
_FAKE_CHANNEL_TOKENS = ("外卖", "堂食", "自提", "团购")


def veto_by_wordlist_alone(query, dimensions):
    if any(tok in query for tok in _FAKE_CHANNEL_TOKENS):
        dimensions = ()
    return dimensions
'''

_ASSIST = '''# -*- coding: utf-8 -*-
"""阴性: 词表**补**一个 LLM 没填的槽位 —— 辅助, 放行。"""
_FAKE_CHANNEL_TOKENS = ("外卖", "堂食", "自提", "团购")


def fill_when_the_llm_left_it_empty(query, dish_slot):
    if not dish_slot and any(tok in query for tok in _FAKE_CHANNEL_TOKENS):
        dish_slot = "外卖套餐"
    return dish_slot
'''

_CORROBORATED = '''# -*- coding: utf-8 -*-
"""阴性: 词表在, 但投票的是能力表 —— 合规。"""
_FAKE_CHANNEL_TOKENS = ("外卖", "堂食", "自提", "团购")


def veto_with_evidence(query, dimensions, code):
    if (any(tok in query for tok in _FAKE_CHANNEL_TOKENS)
            and not _resolver_serves_dimensions(code, ("store",))):
        dimensions = ()
    return dimensions
'''

_NO_WORDLIST = '''# -*- coding: utf-8 -*-
"""阴性: 否决在, 但依据是能力表, 一个中文词都没有 —— 不该被点名。"""


def veto_by_capability_only(dimensions, supported):
    if "store" not in supported:
        dimensions = ()
    return dimensions
'''


def _scan(tmp_path, name, src):
    (tmp_path / f"{name}.py").write_text(src, encoding="utf-8", newline="")
    return lexical_veto_census(root=tmp_path)


def test_positive_control_a_lexical_veto_is_flagged(tmp_path):
    """🔴 阳性：闸必须对一个**真的**违例报红。⛔ 没红过的闸不算闸。"""
    rows = _scan(tmp_path, "m", _POSITIVE)
    bad = [v for v in rows if not v.evidence]
    assert len(bad) == 1, f"合成的违例没被点名: {rows}"
    assert bad[0].function == "veto_by_wordlist_alone"
    assert bad[0].kind == "N2" and bad[0].target == "dimensions = ()"


def test_negative_control_filling_an_empty_slot_is_allowed(tmp_path):
    """🔴 阴性：**补**一个 LLM 没填的槽是辅助，⛔ 不许报红。

    这一格是本闸最容易做坏的那格 —— 一个「见词表改槽位就报」的版本会在
    这里误报，而误报正是让人把闸关掉的那个东西（形态 E）。
    """
    assert _scan(tmp_path, "m", _ASSIST) == ()


def test_negative_control_evidence_in_the_guard_is_allowed(tmp_path):
    """🔴 阴性：词表 + 能力表 ⇒ 词表说了不算 ⇒ 合规（正是仓里那句注释的形状）。"""
    rows = _scan(tmp_path, "m", _CORROBORATED)
    assert len(rows) == 1, rows
    assert rows[0].evidence == ("_resolver_serves_dimensions",)
    assert not [v for v in rows if not v.evidence]


def test_negative_control_a_veto_without_any_wordlist_is_not_flagged(tmp_path):
    """🔴 阴性：判据 ⛔ 没变成「凡是收窄 LLM 槽位就报」。"""
    assert _scan(tmp_path, "m", _NO_WORDLIST) == ()


def test_the_synthetic_controls_are_not_all_the_same_shape(tmp_path):
    """⚠️ 前提：四个合成样本**真的不同** —— 否则「四条全绿」只是同一个样本跑四遍。"""
    srcs = {_POSITIVE, _ASSIST, _CORROBORATED, _NO_WORDLIST}
    assert len(srcs) == 4
