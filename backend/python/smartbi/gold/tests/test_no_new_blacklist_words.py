"""⛔ 黑名单不许再长。

🔴 Steve 2026-08-07 拍板：**确定性层只能「认得」，不能「猜」。**
黑名单是「对无限世界的枚举」——「这些词不是菜名」永远列不完。今天它已经 83 个词(34+10+9+30, 实测)，
而我自己这一轮就往里加过两次（「加权」、一批食材泛指词）。

⛔ 这道闸不追求把存量清零（食材那批还没有对应的目录可查，见下），它只保证
   **不再增长**：任何人想再加一个词，先得回答「目录为什么没拦住？」

判据来源就在代码里（`extract_dish_candidate` 最后一道守卫的注释）：
> 最后一道: 菜单说了算。**黑名单只能拦住有人想到过的词, 目录能拦住全部。**

## 存量为什么还留着

| 词表 | 能否用目录替代 | 状态 |
|---|---|---|
| `_DISH_GENERIC_TOKENS` / `_KITCHEN_OPS_NOUNS` | ✅ 菜品目录已覆盖（见 `test_catalogue_over_blacklist`） | 仅作目录不可用时的兜底 |
| `_INGREDIENT_GENERIC_TOKENS` | ❌ **食材没有对应目录** —— 菜品目录管不了食材 | 退役被阻塞 |

⇒ 想清空第二行，先得有一张食材目录。**那是数据/建模工作，不是改词表。**
"""
import re
from pathlib import Path

from smartbi.gold.restaurant import restaurant_intent as RI
from smartbi.gold.restaurant import restaurant_ops_router as RR

#: 2026-08-07 冻结的基线。**只允许降，不允许升。**
#: 降了就把这里改小（并在 commit 里说清楚是靠什么替代的）。
#:
#: ⚠️ 这里数的是**概念数**，⛔ 不是条目数 —— 见 `_size` 下面那段。基线值没动过。
FROZEN = {
    "_DISH_GENERIC_TOKENS": 34,
    "_KITCHEN_OPS_NOUNS": 10,
    "_INGREDIENT_GENERIC_TOKENS": 9,
}


def _concepts(values):
    """表里有几个**概念** —— 同一个词的繁体变体不另算一个。

    🔴 2026-08-20（形态 C‴：断言守的是需求，还是历史？）

    这道闸守的需求是「⛔ 不许再枚举更多**概念**」（「黑名单是对无限世界的枚举」）。
    而它原来的写法数的是 `len(表)` —— **条目数**。两者在只有简体时相等，
    所以一直没人发现口径是错的。

    今天把 `_DISH_GENERIC_TOKENS` 等表过了**已有的** `with_traditional()`
    （「整体」→ 同时收「整體」），条目 34→43、10→11，闸当场红。
    但一个繁体变体**不是一个新概念**，它是同一个概念的另一种字形：
    老板把系统语言设成繁中，问的是同一句话。按条目数算，等于宣布
    ▎「支持繁体输入」= 「往黑名单里加词」—— 那不是这道闸要拦的东西。

    ⛔ 而正确的修法**不是**把 34 改成 43（那是改断言去贴改动，等于把闸关掉）。
    ✅ 是把它守的东西从**字面**（有几行字符串）抬到**性质**（有几个概念）：

        概念 = 表里那些「不是本表内另一个词的繁体投影」的词。

    于是：
      · 加繁体变体   → 概念数不变 → 闸不动（本刀）
      · 加一个新词   → 概念数 +1  → 闸照红（见 `test_the_gate_still_reds_on_a_real_new_word`）
      · 想靠「伪装成变体」绕过 → 得同时写简体那一半，而简体那一半照样计数 ⇒ 绕不过
    """
    values = tuple(values)
    return [
        t for t in values
        if not any(s != t and s.translate(RR._SIMPLIFIED_TO_TRADITIONAL) == t
                   for s in values)
    ]


def _size(name):
    for mod in (RR, RI):
        v = getattr(mod, name, None)
        if v is not None:
            return len(_concepts(v))
    raise AssertionError(f"{name} 不见了或改名了 —— 这道闸已经量不到东西")


def test_wordlists_do_not_grow():
    grown = {
        name: (_size(name), frozen)
        for name, frozen in FROZEN.items()
        if _size(name) > frozen
    }
    assert not grown, (
        f"黑名单变长了: {grown}（当前 vs 冻结基线）。\n"
        "⛔ 加词之前先回答：**菜单/食材目录为什么没拦住它？**\n"
        "   如果目录能拦 → 去修目录的接线，不要加词。\n"
        "   如果那类实体压根没有目录 → 那是要建目录，也不是加词。\n"
        "   判据（代码原文）：黑名单只能拦住有人想到过的词，目录能拦住全部。"
    )


def test_the_gate_still_reds_on_a_real_new_word(monkeypatch):
    """🔴 变异对照：口径从「条目」换成「概念」之后，这道闸**还红不红**。

    ⛔ 没红过的闸不算闸 —— 而这次改的正是它的计数口径，
       所以必须当场证明「加一个真词」仍然过不去，⛔ 不能只证明「加繁体过得去」。

    两条一起看才有意义（阳性 + 阴性）：
      · 阴性：塞一个**真的新词** → `_size` 涨 → 断言必须红
      · 阳性：塞一个**已有词的繁体变体** → `_size` 不动 → 断言不该红
    """
    base = RR._DISH_GENERIC_TOKENS
    frozen = FROZEN["_DISH_GENERIC_TOKENS"]

    # ① 阴性对照：一个货真价实的新词，必须让 _size 超过基线
    monkeypatch.setattr(RR, "_DISH_GENERIC_TOKENS", tuple(base) + ("红烧狮子头",))
    assert _size("_DISH_GENERIC_TOKENS") > frozen, (
        "往表里塞了一个真词，概念数却没涨 —— 这道闸已经守不住任何东西了"
    )

    # ② 阳性对照：一个已有词的繁体投影，⛔ 不许被算成新概念
    #    ⚠️ 这个词必须**真的有**繁体投影，否则这条对照是空转。
    #       第一版我挑了「损耗」—— 损/耗 都不在映射表里，`variant == 原词`，
    #       当场被下面这句断言抓住（形态 C″：先证明变异真的生效了）。
    word, variant = "领料", "领料".translate(RR._SIMPLIFIED_TO_TRADITIONAL)
    assert variant != word, "映射表变了，这条对照已经空转"
    assert word not in base, "「领料」已经在表里了，这条对照测不到「新增」"
    monkeypatch.setattr(RR, "_DISH_GENERIC_TOKENS", tuple(base) + (word, variant))
    assert _size("_DISH_GENERIC_TOKENS") == frozen + 1, (
        "「领料」是新概念(+1)，它的繁体变体不是 —— 变体被多算了一个"
    )


def test_inline_blacklist_in_extractor_does_not_grow():
    """抽取器里还内联着一串词（不在任何常量里）—— 一并冻住，否则会从这里溜进来。"""
    src = Path(RR.__file__).read_text(encoding="utf-8")
    m = re.search(r"if any\(tok in candidate for tok in \(\n(.*?)\)\s*\+\s*_KITCHEN_OPS_NOUNS",
                  src, re.S)
    assert m, "内联黑名单的写法变了 —— 这道闸已经空转，先修正则"
    inline_words = re.findall(r'"([^"]+)"', m.group(1))

    # ⚠️ 30 是**实测**冻结值。我先前凭 `grep | wc -l` 估成 19 —— 统计口径错了
    #    (那条命令只数了某几行)。这道闸第一次跑就把我的错数抓了出来,
    #    判据: **冻结基线要用被测代码算出来, 不要用另一条命令估。**
    assert len(inline_words) <= 30, (
        f"抽取器内联黑名单从 30 涨到了 {len(inline_words)}: {inline_words}。"
        "同上：先问目录为什么没拦住。"
    )


def test_the_catalogue_veto_is_still_wired():
    """阴性对照：上面两条只是「别变大」，这条保证**目录那条路还在**。

    否则有人把目录否决删掉、黑名单不变，上面两条照样绿 —— 那才是最坏的情况：
    看着守住了词表，实际上把真正管用的那道守卫拆了。
    """
    src = Path(RR.__file__).read_text(encoding="utf-8")
    assert "_catalogue_says_not_a_dish(candidate)" in src, (
        "抽取器里的「菜单说了算」守卫不见了 —— 那黑名单就重新变成唯一防线了"
    )
