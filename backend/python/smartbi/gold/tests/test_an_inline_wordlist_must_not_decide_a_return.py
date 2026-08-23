# -*- coding: utf-8 -*-
"""内联**无名**中文词表，⛔ 不许再多一处直接决定 `return`。

## 判据（一句话）

> 一个连名字都没有的手写中文列表，正在直接决定这次调用 `return` 什么？
> **⛔ 不许再多。**

## 这道闸守什么

2026-08-17 的词表普查把读路径分成两半，只有前一半被闸管着::

    命名词表   150 个 / 1856 条   ← 第一道棘轮（`MAX_UNREGISTERED = 88`）管这半
    内联无名   258 个 / 1592 条   ← 🔴 一条都看不见

▎内联的那 1592 条**连名字都没有** —— 没有任何测试引用得到它们，
▎没有任何登记表登记得了它们。普查卡自己把这片标成
▎「本次普查最大的盲区」，至今没治。

⛔ 本闸**不给整片 1592 条建闸**。那片里绝大多数是产品自己的措辞池、SQL 片段、
日志解析，对它们报红就是一道**当天被加 skip 的闸**（形态 E：宁可窄而可信）。

只取**离决策最近**的那一档::

    if <内联中文字面量>: return / continue

📏 一次对抗审计按「离决策多近」分过三档，本闸只管第一档::

    DECIDES_RETURN   88 处 / 482 条   ← 本闸, **本仪器实测**
    IN_CONDITION     19 处 /  89 条   ⚠️ 转述自那次审计, 本仪器**没有重测**
    OTHER            68 处 / 417 条   ⚠️ 同上

⚠️ 那次审计给 DECIDES_RETURN 报的是 **87** 处 / 482 条 —— **条数逐字相同,
   处数差 1**。它的脚本不在本仓, 无法逐条对账 ⇒ ⛔ 不把差异归因成任何一方
   的缺陷, 只说明**口径要跟着数字一起报**。本闸的两个上限一律以
   `--inline` 当场跑出来的数为准, ⛔ 不引用转述值。

为什么是这一档：「最差」那个 bug 的长相**就是它** —— 一张手工列表直接决定
「这句话按不按菜品去查」，而手工列表永远补不全。

## 📏 当天实测（2026-08-20）与口径

⚠️ **报数带口径** —— 换一个门槛这三个数全变，光看数字没有可比性::

    口径: 门槛 >= 2 个词 / `if` body **第一层** return|continue /
          只数**未被赋名**的字面量 / 全读路径 `gold/restaurant/**/*.py`

    88 处 / 482 条 / （去重后 61 个决策点）

    命令: python -m smartbi.scripts.audit_restaurant_wordlists --inline

⚠️ 门槛是 **2**，比命名棘轮的 3 低一档。⛔ 这不是口径不一致，两道闸问的
   是两个问题：命名棘轮问「这张表值不值得登记一类」（3 个词以下不值得单独
   立类）；本闸问「这个 if 是不是拿手写词在决定 return」——**两个词照样决定**。
   📏 `if any(t in q for t in ("最好", "最差")): return` 正是那个 bug 的形状；
   门槛 3 会整个漏掉它，并且给出一条**廉价的规避路**：把长表拆成两两一组。

## ⚠️ 计数单位：**处** 和 **条**，两个都要

**处 = 一个字面量出现一次**，⛔ 不是「多少行代码」。

⛔ 没有按 `if` 去重成 61。理由：`if x in (A) or y in (B): return` 是**两张**
手写词表 OR 在一起，不是一个判断被拆成两处写。
（`MAX_LEXICAL_VETOES` 按 guard 去重是对的 —— 那里是**同一个**判断写了三个
槽位。两者形状不同，⛔ 别照搬。本仓「同一个问题拆成几处调用会虚高」那条
教训针对的是后者。）

🔴 **为什么两个数都要**——它们各自有一半是瞎的::

    只数「处」  ⇒ 把 30 条的表悄悄加到 45 条, 处数**不变**
    只数「条」  ⇒ 删 5 条老词 + 新开一处 5 条的表, 条数**不变**

而「一张补不全的表越补越长」正是「最差」那个 bug 的机制本身 ⇒ 条数必须也管。

## 余量（容差）与理由

    处   上限 88   容差 3    3/88 = 3.4%
    条   上限 482  容差 20   20/482 = 4.1%

⛔ 容差不是「允许新增」的额度，是**让日常增删不至于天天红**的宽度。
两个都按「小于本档最大的单张表」来定：当天最大的一处是 30 条
（`restaurant_ops_router.py:1683`）⇒ 容差 20 < 30，**余量装不下哪怕一张
新的大表**。处那边同理，3 处远小于任何一次有意义的新增批次。

⚠️ 既有那条命名棘轮容差也是 3；隔壁 `MAX_LEXICAL_VETOES` 用 0，因为它存量
只有 4，容差 3 等于允许翻倍。⇒ 容差按**存量量级**定，⛔ 不是抄一个数。

## 🔴 另外两条线合入之后，这道闸怎么跟着调

⚠️ 有两条并行线正在**删/改**内联词表（`_verbatim_entity` 的通名表 19 条、
`restaurant_ops_router.py:1683` 那 30 条都在他们手里）。

**棘轮本身不会红** —— 它是 `<=`，存量变少只会更绿。会红的是
`test_the_limit_is_not_slack`，而**那是设计**，不是误报::

    合入后跑 --inline 拿到新的实测值 (N 处 / M 条)
      → 把 MAX_INLINE_DECISION_SITES / _TERMS 直接改成 N / M
      → ⛔ 只许调小。调大 = 把别人删出来的余量变成自己悄悄新增的空间

失败信息里会直接印出该填的两个数，改常量是**一行**的机械动作。
⛔ 不要为了「合入当天不红」预先把上限留宽 —— 那正是这道闸要防的东西。

## 🔴 它看不见什么（代理判据，逐条标出来）

判据是**结构**的（AST），而「手写词表在替产品做判断」是**语义**的。中间三层都是代理：

| 代理 | 用什么当代理 | 看不见 |
|---|---|---|
| 「这是一张词表」 | 序列字面量里 ≥2 个、且 ≥80% 是中文串 | **f-string 拼出来的**、`import` 进来的、从库/配置读出来的 —— 一个字都看不见 |
| 「它在决定」 | `if` 的 body 第一层有 `return`/`continue` | `return "a" if q in (…) else "b"`（三元）、`while` 里的、`raise`、以及**赋个标志位再在别处 return** |
| 「决定了什么」 | ⛔ **不判** | 「决定一次 return」只是**结构**代理，⛔ 不等于「它真的改变了老板拿到什么」。一处 return 可能只是提前退出一个纯格式化函数 |

已知漏报面，写下来免得下一个人以为闸覆盖到了：

1. **`elif` 之外的分支形状**：`orelse` 里直接 return 的不算（只看 `body`）。
2. **跨函数**：词表在 A 函数里判、B 函数里 return —— 结构上不是一个 `if`。
3. **命名了但没登记**的：那是第一道棘轮的靶子，本闸**故意**不重复数（形态 D）。

⇒ 本闸是**窄而可信**的那一头：宁可漏，不要天天误报被关掉。

## 载体

判据与两个上限都在 `smartbi/scripts/audit_restaurant_wordlists.py` ——
**与另外两道闸同一个文件、同一个 `_cjk_seq`**，⛔ 不另起第二份判据（形态 D）。
"""
from __future__ import annotations

import pytest

from smartbi.scripts.audit_restaurant_wordlists import (
    INLINE_DECISION_MIN_TERMS,
    MAX_INLINE_DECISION_SITES,
    MAX_INLINE_DECISION_TERMS,
    inline_decision_census,
    inline_decision_points,
)

#: 📏 见 docstring「余量（容差）与理由」。容差 20 < 当天最大单表 30 条 ⇒
#: 余量装不下哪怕一张新的大表。
_SLACK_SITES = 3
_SLACK_TERMS = 20


@pytest.fixture(scope="module")
def rows():
    return inline_decision_census()


# ══ 一 · 棘轮（承重）═════════════════════════════════════════════════════
class TestTheRatchetOnlyGoesDown:

    def test_no_new_inline_wordlist_decides_a_return(self, rows):
        """🔴 承重：⛔ 不许再多一处无名词表直接决定 return。"""
        assert len(rows) <= MAX_INLINE_DECISION_SITES, (
            f"新增了「内联无名词表直接决定 return」的处"
            f"（{len(rows)} > {MAX_INLINE_DECISION_SITES}）：\n  "
            + "\n  ".join(
                f"{r.module}:{r.lineno} if@{r.if_lineno} {r.function} "
                f"{len(r.terms)} 条 {list(r.terms)[:6]}"
                for r in sorted(rows, key=lambda x: -len(x.terms))[:12])
            + "\n\n⛔ 这一档连名字都没有 ⇒ 无法被登记、被测试引用、被审计。"
              "\n   出路①：改成对目录 / 能力表 / 指标登记表**查** —— ⛔ 不是补词表"
              "\n   出路②：提成模块级常量并在 REGISTRY 登记，至少让它有名字"
        )

    def test_the_inline_wordlists_do_not_quietly_grow(self, rows):
        """🔴 承重的另一半：⛔ 处数不变、把表越补越长也不行。

        「一张补不全的表越补越长」正是「最差」那个 bug 的机制本身。
        """
        terms = sum(len(r.terms) for r in rows)
        assert terms <= MAX_INLINE_DECISION_TERMS, (
            f"内联决策词表变长了（{terms} > {MAX_INLINE_DECISION_TERMS} 条），"
            f"而处数是 {len(rows)}。\n"
            "⛔ 补词表补不完 —— 该问的是「这件事能不能改成查」。"
        )

    def test_the_limits_are_not_slack(self, rows):
        """⛔ 上限不许比实际值宽 —— 留出的余量就是悄悄新增的空间。

        ⚠️ 另外两条线删掉内联词表之后，这条会红 —— **那是设计**。
           照失败信息把两个常量调到实测值即可，⛔ 只许调小。
        """
        terms = sum(len(r.terms) for r in rows)
        assert MAX_INLINE_DECISION_SITES - len(rows) <= _SLACK_SITES, (
            f"处上限 {MAX_INLINE_DECISION_SITES} 比实际 {len(rows)} 宽出 "
            f"{MAX_INLINE_DECISION_SITES - len(rows)} —— "
            f"把 MAX_INLINE_DECISION_SITES 改成 {len(rows)}"
        )
        assert MAX_INLINE_DECISION_TERMS - terms <= _SLACK_TERMS, (
            f"条上限 {MAX_INLINE_DECISION_TERMS} 比实际 {terms} 宽出 "
            f"{MAX_INLINE_DECISION_TERMS - terms} —— "
            f"把 MAX_INLINE_DECISION_TERMS 改成 {terms}"
        )


# ══ 二 · 阳性对照：判据必须真的看得见东西 ═══════════════════════════════
class TestTheCensusActuallySeesThings:
    """⛔ 少了这些，一个永远返回空的 `inline_decision_census()` 会让棘轮
    **永远绿**，而它一处都没看过（本仓记过「跑批天天绿但一个样本没看」）。

    ⚠️ 8 号纪律：这些对照要问一句「仪器坏掉时它会不会也成立」——
       `census() == ()` 会让下面每一条**都失败**，所以它们不是恒真式。
    """

    def test_it_finds_the_known_big_ones(self, rows):
        """📏 建闸当天最大的几处，按 (模块, 函数) 锚定（⛔ 不锚行号）。"""
        seen = {(r.module, r.function) for r in rows}
        for known in (
            ("restaurant_ops_router.py", "_extract_dish_candidate_single"),
            ("restaurant_intent.py", "_verbatim_entity"),
            ("restaurant_intent.py", "_detect_analysis_action"),
        ):
            assert known in seen, f"当天实测的 {known} 不见了 —— 仪器可能坏了"

    def test_the_biggest_one_is_still_big(self, rows):
        """📏 当天最大的一处是 30 条。它塌成 3 条 ⇒ 门槛/判据被改坏了。"""
        assert rows, "普查一处都没找到 —— 仪器坏了"
        assert max(len(r.terms) for r in rows) >= 20, (
            f"最大的一处只有 {max(len(r.terms) for r in rows)} 条，"
            f"当天实测 30 —— 判据可能被收窄了"
        )

    def test_the_two_units_do_not_track_each_other(self, rows):
        """⚠️ 处和条**不是**同一个数的两种写法 —— 否则第二条棘轮是摆设。"""
        terms = sum(len(r.terms) for r in rows)
        assert terms > len(rows) * 2, (
            f"{len(rows)} 处只有 {terms} 条 —— 两个单位几乎重合，"
            f"说明门槛判据可能坏了"
        )

    def test_the_threshold_is_the_documented_one(self):
        """📏 口径钉死：门槛漂了，上面那两个上限就没有可比性（报数带口径）。"""
        assert INLINE_DECISION_MIN_TERMS == 2

    def test_dedup_to_decision_points_is_reported_not_ratcheted(self, rows):
        """⚠️ 去重数只用来报数。它 < 处数 ⇒ 确实有 `if` 里挂着多张表。"""
        points = inline_decision_points(rows)
        assert 0 < len(points) < len(rows), (
            f"去重后 {len(points)} 个决策点 vs {len(rows)} 处 —— "
            "两者相等说明没有一个 if 里挂多张表，与当天实测（61 vs 88）不符"
        )


# ══ 三 · 对照实验（合成小树）═════════════════════════════════════════════
#
# 🔴 阳性 / 阴性都在这里做：把闸指到一棵**合成**的树上，逐个形状问它一次。
#    ⛔ 不用它替代真实树上的读数 —— 上面那几条阳性对照守的是真实树。

#: 🔴 阳性：一处**真的**内联无名词表决定 return。
_POSITIVE = '''# -*- coding: utf-8 -*-
"""阳性: 内联无名词表直接决定 return。"""


def rank_direction(q):
    if any(tok in q for tok in ("最好", "最差", "最高")):
        return "rank"
    return None
'''

#: 🔴 阴性 1：纯英文字面量 —— ⛔ 不是中文词表。
_ENGLISH = '''# -*- coding: utf-8 -*-
"""阴性: 纯英文, 不该被算进来。"""


def pick(q):
    if any(tok in q for tok in ("best", "worst", "highest")):
        return "rank"
    return None
'''

#: 🔴 阴性 2：**注释**里的 —— AST 看不到注释。
#: ⚠️ 这一格是给 grep 式判据准备的：一个文本扫描器会在这里误报。
_COMMENT = '''# -*- coding: utf-8 -*-
"""阴性: 词表只出现在注释里。"""


def pick(q):
    # if any(tok in q for tok in ("最好", "最差", "最高")):
    #     return "rank"
    return None
'''

#: 🔴 阴性 3：**docstring** 里的。
#: ⚠️ 本仓因「grep 数进 docstring」栽过三次。走 AST 之后 docstring 是一个
#:    `Constant` 而不是 `Tuple`，⛔ 但这件事必须被钉住，不能靠「我觉得 AST 不会」。
_DOCSTRING = '''# -*- coding: utf-8 -*-
"""阴性: 词表只出现在 docstring 里。"""


def pick(q):
    """判据长这样::

        if any(tok in q for tok in ("最好", "最差", "最高")):
            return "rank"

    上面是**文档**, 不是代码。
    """
    return None
'''

#: 🔴 阴性 4：字面量在 `if` 里，但那一支**不 return** —— 不是决策档。
_NOT_DECIDING = '''# -*- coding: utf-8 -*-
"""阴性: 命中只是打个标记, 没有直接 return。"""


def pick(q, out):
    hit = False
    if any(tok in q for tok in ("最好", "最差", "最高")):
        hit = True
        out.append(q)
    return hit
'''

#: 🔴 阴性 5：字面量在 **body** 里 —— 那是产品自己的措辞, 不是判据。
_IN_BODY = '''# -*- coding: utf-8 -*-
"""阴性: 字面量用来拼产品自己的正文。"""


def pick(q):
    if q:
        return "、".join(("营收", "毛利", "客单价"))
    return None
'''

#: 🔴 阴性 6：**命名**的词表 —— 归第一道棘轮管, 本闸 ⛔ 不重复数（形态 D）。
_NAMED = '''# -*- coding: utf-8 -*-
"""阴性: 有名字的常量, 第一道棘轮的靶子。"""
_RANK_TOKENS = ("最好", "最差", "最高")


def pick(q):
    if any(tok in q for tok in _RANK_TOKENS):
        return "rank"
    return None
'''

#: 🔴 阴性 7：只有 1 个词 —— 低于门槛 2, ⛔ 不算一张表。
_SINGLE = '''# -*- coding: utf-8 -*-
"""阴性: 单个词, 低于门槛。"""


def pick(q):
    if any(tok in q for tok in ("最差",)):
        return "rank"
    return None
'''

_ALL_NEGATIVE = {
    "english": _ENGLISH, "comment": _COMMENT, "docstring": _DOCSTRING,
    "not_deciding": _NOT_DECIDING, "in_body": _IN_BODY,
    "named": _NAMED, "single": _SINGLE,
}


def _scan(tmp_path, name, src):
    #: ⚠️ `newline=''` —— ⛔ 不做行尾转换（本仓两个 PR 因 CRLF 污染被打回）。
    (tmp_path / f"{name}.py").write_text(src, encoding="utf-8", newline="")
    return inline_decision_census(root=tmp_path)


def test_positive_control_an_inline_decision_is_flagged(tmp_path):
    """🔴 阳性：闸必须对一个**真的**内联决策报红。⛔ 没红过的闸不算闸。"""
    rows = _scan(tmp_path, "m", _POSITIVE)
    assert len(rows) == 1, f"合成的内联决策没被点名: {rows}"
    assert rows[0].function == "rank_direction"
    assert rows[0].terms == ("最好", "最差", "最高")


@pytest.mark.parametrize("name", sorted(_ALL_NEGATIVE))
def test_negative_controls_are_not_counted(tmp_path, name):
    """🔴 阴性：七种**不该**算进来的形状，逐个问一次。

    ⚠️ 误报正是让人把闸关掉的那个东西（形态 E）。其中 `comment` / `docstring`
       两格是给**文本判据**准备的：grep 式扫描器会在那两格误报，AST 不会。
    """
    assert _scan(tmp_path, "m", _ALL_NEGATIVE[name]) == (), (
        f"阴性对照 {name} 被误算进内联决策档"
    )


def test_the_synthetic_controls_are_really_different(tmp_path):
    """⚠️ 前提：八个合成样本**真的不同** —— 否则「八条全绿」只是同一个样本跑八遍。

    🔴 而且它们必须**都被解析过** —— 一个语法错的样本会被 `census` 静默 skip
       （`except SyntaxError: continue`），于是「没被点名」和「根本没读」
       长得一模一样（「没发生」是最容易伪造的读数）。
    """
    import ast

    srcs = {_POSITIVE, *_ALL_NEGATIVE.values()}
    assert len(srcs) == 8, "合成样本有重复"
    for name, src in {"positive": _POSITIVE, **_ALL_NEGATIVE}.items():
        ast.parse(src)  # ⛔ 语法错会被 census 静默跳过 ⇒ 阴性对照变成恒真式


def test_positive_and_negative_in_one_tree(tmp_path):
    """🔴 最硬的一格：**同一棵树**里放阳性 + 全部阴性。

    ⚠️ 这是「必发生」的那个读数：阴性各自单独跑时返回 `()`，
       与「census 整个坏掉」无法区分。混在一起之后阳性那 1 处**必须**still在 ——
       它没在，就说明这一整批阴性绿得没有意义。
    """
    (tmp_path / "pos.py").write_text(_POSITIVE, encoding="utf-8", newline="")
    for name, src in _ALL_NEGATIVE.items():
        (tmp_path / f"neg_{name}.py").write_text(src, encoding="utf-8",
                                                 newline="")
    rows = inline_decision_census(root=tmp_path)
    assert len(rows) == 1, f"阳性/阴性混跑应恰好剩 1 处, 实得: {rows}"
    assert rows[0].module == "pos.py"


def test_the_gate_goes_red_when_the_stock_grows(tmp_path):
    """🔴 变异对照：注入一处 ⇒ 普查数 N → N+1 ⇒ 棘轮判据不再成立。

    ⚠️ 先证明**行为真的变了**（N → N+1），再看断言 —— ⛔ 只证明
       「字面量写进去了」不算（本仓 C″：变异不红有两种成因，
        分不清就会去改错那一头）。
    """
    before = _scan(tmp_path, "base", _ENGLISH)          # 阴性: 不贡献
    assert len(before) == 0

    after = _scan(tmp_path, "grow", _POSITIVE)
    #: ① 行为真的变了
    assert len(after) == len(before) + 1, (
        f"变异没生效: {len(before)} → {len(after)}")
    #: ② 变了之后, 棘轮那条判据在「上限=注入前」的口径下不再成立
    limit_at_before = len(before)
    assert not (len(after) <= limit_at_before), (
        "普查数涨了, 而棘轮判据仍然成立 —— 那条断言在守空气")
