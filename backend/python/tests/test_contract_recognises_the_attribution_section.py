"""契约的 `analysis_action` 要认**产出侧那一段确定性归因小节**。

## 一 · 实测事实（prod / MOCK_REST / 10 条归因建议型问句 × 2 轮 / 16 次 validate）

⛔ 不是推断。语料是钩住 `answer_contract.validate` 逐条落盘的**真实产出**。

| `analysis_action` | validate 次数 | 被 `analysis_action` 拦下 |
|---|---|---|
| `optimize` | 7 | **0** |
| `diagnose` | 6 | **4** |
| `lookup`   | 3 | 0 |

**6 条 diagnose 里，命中现有白名单的是 0 条。** 过了的那 2 条走的是
`_explicit_analysis_gap`（也就是「我做不到」那条逃生口）。

⇒ **在 LLM 合成这条路上，`diagnose` 只有承认失败才过得去；真的做出了归因反而过不去。**

## 二 · 为什么会这样（两边都查过出处，⛔ 不从注释推）

* `optimize` 的白名单里有 `优化建议`，而
  `restaurant_intent_service.py:1743-1747` **无条件给每份答案前缀 `优化建议\\n`**
  （实测语料里 7/7 条 optimize 文本都以它开头）⇒ 这一支**由代码自己满足**。
* `diagnose` 的白名单（`原因拆解`/`计算构成`/`前提成立`…）全部是
  `restaurant_ops_router.py:2831-2907` 那个**单菜确定性 resolver** 的抬头字面量。
  LLM 合成路径**一个都不产出**。

▎判据认得**某一个 resolver** 的归因抬头，认不得**另一个产出点**的。
▎这不是「标准太高」，是**清单没列全**。

## 三 · 这次加的是什么（⛔ 不是放宽）

`synthesis_engine._append_investigation_directions` 逐字拼这一行：

    f"### 差距出在「{cause}」——接下来往哪儿查"

那个函数的 docstring 原话是「**确定性追加，⛔ 不经过 LLM**」，
并且四道门任一不过就一个字不说（没跑归因 / 没给出拖后腿门店 /
`primary_cause` 不在清单里 / 能查与查不了都是空）。

⇒ **它出现 ⇒ 产品真的算出了一个主因。** 认它，与认 `原因拆解` 是同一件事：
  **把另一个产出点也列进清单**，⛔ 不是降低标准。

📏 实测判别力（同一批 16 条语料）：diagnose 6/6 命中、lookup 0/3、optimize 1/7。
⚠️ 对照组：页脚「…也不参与原因判断」在 **12/16** 条里都有（含 lookup）——
   拿它当判据就是再造一个 `优化建议`。所以本文件专门钉了它**不算数**。

## 四 · 这个文件最重要的是哪几条

`test_the_2026_08_10_incident_shape_is_still_rejected_after_this_change`
与 `test_the_universal_footer_is_not_an_attribution_account`：
本次改动往 `_analysis_action_present` 里加的是一条 **OR 支路**，
而 OR 支路最容易把既有阴性对照变成恒真式。上面两条就是钉这个的。

设计卡：`docs/decisions/2026-08-18-契约analysis_action是最直接的拒答方-设计卡.md`
"""
import pytest

from smartbi.gold.restaurant.answer_contract import (
    _ANALYSIS_ATTRIBUTION_SECTION_MARKS,
    _analysis_action_present,
    _attribution_section_present,
    _explicit_analysis_gap,
)


class _AttribSpec:
    """只带这条判据要看的字段。"""

    def __init__(self, analysis_action):
        self.analysis_action = analysis_action


#: prod 实测原文的**逐字节选**（MOCK_REST，问句「为什么这周的订单量少了」，
#: 全文 1796 字）。⛔ 不手写桩 —— 桩的自由度能构造真实上游永不产生的形状。
_REAL_DIAGNOSED_ANSWER = (
    "优化建议\n"
    "核心结论：这周订单量（3,956单）比上周少了约0.3%，但10家门店的单量几乎一模一样"
    "（395-396单），所以不是某家店突然没人了。真正拖后腿的是模拟·普陀真如社区店，"
    "它营收比平均少4,733元，主要原因是客人花得少了（每单平均消费347元 vs 平均358元），"
    "而不是客人少了（只差1单）。\n"
    "\n"
    "### 差距出在「客单价」——接下来往哪儿查\n"
    "- 系统里有数、可以接着查（这几项目前都是全店合计的数）：**优惠与营销活动**"
    "（数据不全，只能看个大概）、**菜品销售结构**\n"
    "- 现在查不了（这些数据还没接进来）：**堂食/外卖/自提**、**午晚市与时段**\n"
    "- 上面这些都查过、还是解释不了，才轮到怀疑「这家店自己的运营」"
    "（服务、出品、人手）—— 现在还没到那一步。\n"
    "\n"
    "还没有数据的方面当前保持为空，不按 0 处理，也不参与原因判断。"
)


# ── 🔴 承重：产品真的做出了归因，就该合规 ────────────────────────────
def test_a_real_prod_diagnosis_passes_the_contract():
    """🔴 本文件的主张：prod 上这份 1796 字的归因答案**当前被整份拒掉**。

    它有主因（客单价）、有拖后腿门店、有支撑数字、有下一步排查顺序 ——
    也就是 goal 定义里「答得上」的样子。
    """
    assert _analysis_action_present(
        _AttribSpec("diagnose"), _REAL_DIAGNOSED_ANSWER) is True


def test_the_attribution_section_is_detected():
    assert _attribution_section_present(_REAL_DIAGNOSED_ANSWER) is True


# ── 🔴 阴性对照：加了 OR 支路之后，旧的那几条不许变成恒真式 ──────────
def test_the_2026_08_10_incident_shape_is_still_rejected_after_this_change():
    """🔴 **承重**：用一个数字回答「为什么」必须**仍然**不通过。

    2026-08-10 实测原形：用户问「本月营收比上月低是什么原因」，
    产品回「本月全部门店营收合计 ¥6,490,180.61。」

    ⚠️ 本次加的是一条 OR 支路 —— OR 支路最容易把既有阴性对照变成恒真式。
       这一条就是钉它没变。
    """
    text = "本月全部门店营收合计 ¥6,490,180.61。另有部分菜品缺成本卡。"
    assert _attribution_section_present(text) is False
    assert _explicit_analysis_gap(text) is False
    assert _analysis_action_present(_AttribSpec("diagnose"), text) is False


def test_the_universal_footer_is_not_an_attribution_account():
    """🔴 **承重**：那句页脚在 16 条语料里出现 12 次（含 3 条 lookup）。

    认它 = 再造一个 `优化建议`（一个由代码自己无条件写出来的 token）。
    ⇒ 本次判据**不许**被它满足。
    """
    text = (
        "本月全部门店营收合计 ¥6,490,180.61。\n"
        "还没有数据的方面当前保持为空，不按 0 处理，也不参与原因判断。"
    )
    assert _attribution_section_present(text) is False
    assert _analysis_action_present(_AttribSpec("diagnose"), text) is False


def test_one_marker_alone_is_not_enough():
    """阴性对照：只有半个标记不算 —— 判据要的是那一行的**结构**，不是一个词。"""
    assert _attribution_section_present("这个月的差距出在哪里我也说不好。") is False
    assert _attribution_section_present("接下来往哪儿查我还没想好。") is False


def test_the_two_markers_on_different_lines_do_not_count():
    """阴性对照：产出侧是**同一行**拼出来的。跨行凑齐 ⇒ 是巧合，不是那一段。

    ⛔ 少了这条，任何一篇同时提到这两个词的长文都能蒙混过关。
    """
    text = "差距出在哪里还不清楚。\n接下来往哪儿查也没有头绪。"
    assert _attribution_section_present(text) is False


def test_a_pure_number_answer_is_still_rejected():
    text = "本月全部门店营收合计 ¥6,490,180.61，环比下降 3.2%。"
    assert _analysis_action_present(_AttribSpec("diagnose"), text) is False


# ── 阳性对照：旧路径不许被这次改动弄坏 ──────────────────────────────
def test_the_existing_whitelists_still_work():
    """⛔ 少了它，这个文件只验了新支路，而**旧路径坏掉也看不出来**。"""
    assert _analysis_action_present(_AttribSpec("diagnose"), "原因拆解如下：…") is True
    assert _analysis_action_present(_AttribSpec("optimize"), "优化建议：…") is True


def test_non_analysis_actions_are_unaffected():
    assert _analysis_action_present(_AttribSpec(None), "本月营收 ¥100。") is True
    assert _analysis_action_present(_AttribSpec("lookup"), "本月营收 ¥100。") is True


# ── 🔴 接上没有：产出侧和消费侧不许漂 ───────────────────────────────
def test_the_producer_still_emits_what_the_contract_looks_for():
    """🔴 形态 D′：标记的**源**在 `synthesis_engine`，契约是**校验方**。

    ⛔ 不比对两处字面量（那只证明字符串一样）—— **真的调一次产出侧那个函数**，
       断言它的输出能被契约认出来。产出侧改了措辞，这一条当场红。

    ⚠️ 阳性对照在同一条里：`attribution` 为空时它一个字都不加
       （那是它 docstring 里写的第一道门），所以「输出等于输入」证明我构造的
       FactBook 真的走到了那段逻辑，而不是四道门里某一道把它挡在外面。
    """
    from smartbi.agent.factbook import FactBook
    from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine

    emit = ComprehensiveSynthesisEngine._append_investigation_directions

    # 阳性对照（先证明仪器活着）：没有归因时一个字都不加。
    assert emit("正文", FactBook()) == "正文"

    factbook = FactBook(
        attribution={
            "laggard": {"store_name": "模拟·普陀真如社区店"},
            "primary_cause": "客单价",
        },
        available_dimensions=[{"code": "dish_sales", "label": "菜品销售结构"}],
        missing_dimensions=[{"code": "channel", "label": "堂食/外卖/自提"}],
    )
    produced = emit("正文", factbook)
    assert produced != "正文", (
        "产出侧这一轮什么都没加 —— 那么下面那条断言无论过不过都没有意义")
    assert _attribution_section_present(produced) is True, (
        "产出侧的归因小节措辞变了，而契约还在找旧的 —— 两份漂开了。"
        f"实际产出:\n{produced}")


def test_the_marks_are_a_pair():
    """判据是**一对**标记同现，不是单个词。改成单个词这条会红。"""
    assert len(_ANALYSIS_ATTRIBUTION_SECTION_MARKS) == 2
