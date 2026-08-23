# -*- coding: utf-8 -*-
"""老板点名一家名单外的门店时，prompt 里两句话不能打架。

## 成因（写这个文件时才发现，⛔ 不是我一开始以为的那个）

prod 实测（`_store_absent_verify` 两轮共 6 次）问「北京三里屯店卖得怎么样」
（MOCK_REST 没这家店）：

    一轮模型 3/3 给 `store=null` ⇒ 上游把范围补成「全部门店」
      ⇒ guard 开头 `or spec.store_scope` 早退 ⇒ 那道核对整支跳过
      ⇒ 老板拿到全店合计 ¥21,042,226.51，**一个字没说那家店不存在**
    另一轮模型填了名字 ⇒ 同一句话**正确反问**了

我原以为「模型没被告知要回声」。**不是。** 静态规则块的 **2b** 早就写着：

    2b. dish/store: 如果问题点名了具体菜品或门店，原样摘抄那个名字
        (必须是问题原文里连续出现的子串…)；没点名就输出 null。
        泛指词 (这道菜/哪家店/门店) 不是名字，输出 null。

而门店名单那句（动态区）原文是「**只能从这里选择**，禁止编造」。
▎**两条互相矛盾** —— 照 2b 该填名字，照那句该留空。
▎⇒ 同一句话时好时坏，正好解释实测的翻面。

⇒ 修法是**消除矛盾、划清边界**，⛔ **不是再抄一份 2b**（形态 D）。

## 这个文件能测什么、⛔ 不能测什么

- ✅ 结构：边界说明在不在、⛔ 有没有长出第二份 2b、段落顺序（前缀缓存契约）
- ⛔ **不能**测「模型会不会照做」—— 只能 prod 真跑（`_store_absent_verify`）。
  ▎**本文件不是这一刀的验收，验收在 prod 那一侧。**
"""
from __future__ import annotations

from smartbi.gold.restaurant.restaurant_intent import _build_t3_prompt

_PROMPTSTORE_STORES = ("模拟·徐汇美罗城店", "模拟·静安嘉里中心店", "模拟·杨浦五角场店")
_PROMPTSTORE_Q = "北京三里屯店卖得怎么样"


def _prompt(stores=_PROMPTSTORE_STORES):
    # `hint` / `history` 是必填位参 —— 传空，本文件量的是门店那一段。
    return _build_t3_prompt(_PROMPTSTORE_Q, None, None, available_stores=stores)


def test_precondition_the_store_list_is_rendered():
    """前提：名单真的进了 prompt。不进去的话下面每条都在量空气。"""
    text = _prompt()
    for name in _PROMPTSTORE_STORES:
        assert name in text, f"门店名单没进 prompt：{name}"


def test_rule_2b_still_says_to_quote_the_name_verbatim():
    """前提二：2b 是这一刀依赖的那半 —— 它被删掉的话边界说明就悬空了。"""
    text = _prompt()
    assert "原样摘抄" in text
    assert "泛指词" in text, "2b 的泛指词阴性没了 —— 「哪家店最差」会被当成店名"


def test_the_store_list_no_longer_contradicts_rule_2b():
    """🔴 承重：门店名单那句不能再说「只能从这里选择」。

    那正是与 2b 打架的那句话。改动前它让模型面对名单外的门店名时两条规则冲突，
    实测表现为**同一句话时好时坏**。
    """
    text = _prompt()
    assert "只能从这里选择" not in text, (
        "那句和规则 2b 直接矛盾 —— 模型会在两者之间翻面")
    assert "stores 与 store_scope 只能落在这份名单里" in text, (
        "范围类字段的约束不能一起放掉：它们是喂给 SQL 的")
    assert "照规则 2b 填" in text and "留空，才是编造" in text, (
        "没把边界说清 —— 模型不知道名单外的门店该怎么办")


def test_it_does_not_grow_a_second_copy_of_rule_2b():
    """🔴 形态 D：同一条指令两份，它一定会漂。

    第一版我在门店那段里又抄了一遍「泛指词不是门店名」和示例，
    ⛔ 而 2b 里已经有了。两份措辞早晚不一致，而 prompt 漂了**不报错**，
    只会让模型的行为慢慢变得没人解释得了。
    """
    text = _prompt()
    assert text.count("泛指词") == 1, (
        f"「泛指词」出现 {text.count('泛指词')} 次 —— 长出了第二份 2b")
    assert text.count("原样摘抄") == 1, (
        f"「原样摘抄」出现 {text.count('原样摘抄')} 次 —— 同上")
    # ⚠️ 第一版这条红了一次，而**红得有道理但方向不对**：边界说明当时**引用**了
    #    2b 的措辞，被计数判据当成了「第二份」。
    #    ▎我数的是「这个词出现几次」，我想知道的是「有没有第二份**规则**」。
    #    ⇒ prompt 改成**指名引用**（「照规则 2b 填」），不复述它的措辞 ——
    #      对模型也更干净：一条规则一个出处。


def test_the_no_fabrication_rule_is_still_there():
    """阴性：划边界 ≠ 放开编造。

    ⚠️ 代码侧另有一道 `_verbatim_entity`（必须是问句的子串）——
    两层各守一半，prompt 这层也不能撤。
    """
    assert "禁止编造" in _prompt()


def test_nothing_is_rendered_without_a_store_list():
    """阴性：名单为空 ⇒ 整段不渲染，逐字维持改动前（fail-open）。"""
    text = _prompt(stores=())
    assert "当前账号真实可选门店" not in text
    assert "留空，才是编造" not in text


def test_the_store_line_stays_after_the_few_shot_block():
    """🔴 承重：段落顺序是 DashScope 隐式前缀缓存的**契约**。

    指令 + 意图目录 + 严格规则 + few-shot 是静态块，每次 T3 调用共享
    ~1.2k token 前缀，命中部分按 2 折计费。`store_line` 是**按租户变化**的，
    必须留在静态块**之后** —— 挪到前面会让每个租户各自成一份前缀，
    ▎**缓存命中归零，而这件事不报错、只在账单上出现。**
    """
    text = _prompt()
    few_shot_end = text.rfind("clarification_options")
    store_at = text.find("当前账号真实可选门店")
    assert few_shot_end != -1 and store_at != -1
    assert store_at > few_shot_end, (
        "门店段跑到 few-shot 前面了 —— 打破前缀缓存契约（不报错，只涨钱）")
