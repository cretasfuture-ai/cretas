# -*- coding: utf-8 -*-
"""resolver → metric 的能力声明有**两份**，钉住它们不漂；并钉住那个取数口的陷阱。

## 为什么是这道闸，而不是第三张表

`docs/decisions/2026-08-18-有几家店在亏钱-两个根因拆开.md` 把「造一张
resolver → metric 能力表」写成了动手的解冻条件，理由是：

    📏 但仓里**没有** resolver → metric 的能力表（只有 `_RESOLVER_DIMENSIONS` 维度表）

📏 2026-08-18 实测（跑出来的，⛔ 不是读注释）：**这句话是错的，仓里有两份**。

    _DEFAULT_METRICS_BY_CODE   restaurant_intent.py:2136   19 个 resolver 全覆盖
    MARGIN_CAPABLE_INTENTS     answer_contract.py:369      毛利族 3 个

第一份**早在设计卡之前 14 小时**就已经被 contract-repair 自己当判据用了
（`restaurant_intent.py:2678`，2026-08-18 06:27 #2806；设计卡是当日 20:52）。
`restaurant_intent.py:2660` 就写着：

    判据读**既有**的 `_default_metrics_for_code`, ⛔ 不新造第二张
    「哪个 resolver 出哪些指标」的表(形态 D)。

⇒ 再造一张就是**亲手制造形态 D**。既然抽不成一份（两份服务于不同的消费点：
   一份喂 contract-repair 的指标兼容判据，一份喂契约的必答元素），
   按形态 D′ 该做的就是这道闸：**钉住两份一致，⛔ 不是各自都对**。

## 🔴 本文件抓到的那个陷阱（写这道闸时被自己的阴性对照抓的）

`_default_metrics_for_code(code, wants_margin)` **不是**「这个 resolver 能算什么」，
它是「这次要拉哪些指标」。`wants_margin=True` 时它给**任何** code 追加 `gross_margin`：

    _default_metrics_for_code("RESTAURANT_OPS_OUT_OF_DOMAIN", True) -> ('gross_margin',)

⇒ 谁要是照解冻条件 1 的字面意思、用这个函数去实现
  「LLM 选的 resolver 已能满足全部指标 ⇒ 不 repair」，
  在**毛利问句上判据恒真**，改动会静默地什么都不做，而单测照样绿。
  ▎我自己第一版就是这么写的，是下面那条阴性对照当场把它打红的。

⇒ 能力判据一律读**原表** `_DEFAULT_METRICS_BY_CODE`，⛔ 不经过那个 accessor。

## ⚠️ 一致 ≠ 相等 —— 反方向是**故意**不成立的

`SALES_SUMMARY` 默认只声明 `revenue/orders/avg_ticket`，但它的实现在
`spec.wants_margin` 时**也会**出「已覆盖毛利」/「已覆盖毛利率」
（`restaurant_ops_router.py:8668`/`:8674`）⇒ 它在 MARGIN_CAPABLE 里而不在
默认表的毛利族里，**这是对的，不是漏登**。所以闸只钉**单向**，
反方向显式登记在 `DELIBERATE_MARGIN_CAPABLE_ONLY` 里并写明理由 ——
⛔ 登记是留痕，不是豁免。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.answer_contract import MARGIN_CAPABLE_INTENTS
from smartbi.gold.restaurant.restaurant_intent import (
    _DEFAULT_METRICS_BY_CODE,
    _canonical_metrics,
    _default_metrics_for_code,
)

#: 「毛利」这件事在登记表里的两个 key。⛔ 不写死中文，也不 grep 源码文本。
MARGIN_METRIC_KEYS = frozenset({"gross_margin", "gross_profit"})

#: 在 `MARGIN_CAPABLE_INTENTS` 里、但**默认**不出毛利的 resolver。
#: 每一条都要写明理由 —— 这是登记，不是豁免。
DELIBERATE_MARGIN_CAPABLE_ONLY = {
    "RESTAURANT_OPS_SALES_SUMMARY":
        "默认只出 revenue/orders/avg_ticket; 但 spec.wants_margin 为真时"
        "同一个 resolver 会追加「已覆盖毛利」「已覆盖毛利率」"
        "(restaurant_ops_router.py:8668/8674) ⇒ 它**能**产出毛利, 只是不默认。",
}


def _declared_metrics(code):
    """这个 resolver **登记**了哪些指标。⛔ 读原表，不过 accessor（见模块顶部陷阱）。"""
    return set(_canonical_metrics(_DEFAULT_METRICS_BY_CODE.get(code, ())))


def _declares_margin(code) -> bool:
    return bool(_declared_metrics(code) & MARGIN_METRIC_KEYS)


def _resolver_declares_all(code, requested_metrics) -> bool:
    """解冻条件 1 那条判据：「LLM 选的 resolver 已能满足 planner 推出的全部指标」。

    ⛔ 空的 requested_metrics 返回 False —— 「没点名指标」不等于「都能满足」，
       兜底成 True 会把判据变成恒真式（形态 A¹⁰：把「我不知道」翻译成一个读数）。
    """
    needed = set(_canonical_metrics(tuple(requested_metrics)))
    return bool(needed) and needed.issubset(_declared_metrics(code))


def test_声明了毛利指标的_resolver_必须登记在_margin_capable_里():
    """两份表的**单**向一致性。漏一边就是形态 D 的现场。"""
    declares = {c for c in _DEFAULT_METRICS_BY_CODE if _declares_margin(c)}
    missing = sorted(declares - set(MARGIN_CAPABLE_INTENTS))
    assert not missing, (
        "这些 resolver 在 _DEFAULT_METRICS_BY_CODE 里声明了毛利指标, "
        "却不在 MARGIN_CAPABLE_INTENTS 里: %s —— 两份 resolver→metric 声明已经漂了。"
        "净效果是契约不会要求它给出毛利结论, 而 contract-repair 却认为它能算。" % missing
    )


def test_反方向的每一条都必须显式登记并写明理由():
    """⛔ 不许靠「反方向本来就不用一致」一句话糊过去。"""
    declares = {c for c in _DEFAULT_METRICS_BY_CODE if _declares_margin(c)}
    unregistered = sorted(
        (set(MARGIN_CAPABLE_INTENTS) - declares)
        - set(DELIBERATE_MARGIN_CAPABLE_ONLY))
    assert not unregistered, (
        "这些 resolver 在 MARGIN_CAPABLE_INTENTS 里但默认不出毛利, "
        "且**没有登记理由**: %s。要么补进 DELIBERATE_MARGIN_CAPABLE_ONLY 并写清"
        "它凭什么能算毛利, 要么把它从 MARGIN_CAPABLE_INTENTS 拿掉。" % unregistered
    )
    stale = sorted(set(DELIBERATE_MARGIN_CAPABLE_ONLY) - set(MARGIN_CAPABLE_INTENTS))
    assert not stale, (
        "登记表里这几条已经不在 MARGIN_CAPABLE_INTENTS 里了: %s —— 删掉登记。" % stale)
    for code, reason in DELIBERATE_MARGIN_CAPABLE_ONLY.items():
        assert len(reason) >= 20, "%s 的登记理由太短, 说不清它凭什么能算毛利" % code


# ── 🔴 陷阱闸：⛔ 不许拿 `_default_metrics_for_code` 当能力判据 ────────────────

@pytest.mark.parametrize("code", [
    "RESTAURANT_OPS_OUT_OF_DOMAIN",
    "RESTAURANT_OPS_WASTAGE_TOP",
    "RESTAURANT_OPS_INVENTORY_WARNING",
])
def test_accessor_在wants_margin下对任何resolver都追加毛利(code):
    """把陷阱**钉成读数**，⛔ 不写成注释（注释拦不住，本仓记过前科）。

    这条断言红了 = `_default_metrics_for_code` 的语义变了（它不再无条件追加）。
    那是好事，但要**同时**回来放宽本文件顶部那段说明和下面那条阴性对照。
    """
    assert "gross_margin" in _default_metrics_for_code(code, True), (
        "语义变了: %s 在 wants_margin=True 下不再被追加 gross_margin。"
        "回来重读本文件顶部的陷阱说明。" % code)
    assert "gross_margin" not in _declared_metrics(code), (
        "%s 现在**登记**了毛利 —— 那它就该进 MARGIN_CAPABLE_INTENTS, "
        "而不是继续留在这条陷阱对照里。" % code)


# ── 解冻条件 1 的判据：现有的表已经能回答它, ⛔ 不需要第三张表 ────────────────
#
# ⚠️ 这里**只验判据本身**, ⛔ 不改 restaurant_intent.py 的 repair 逻辑
#    （另一条线在改那个文件）。

def test_解冻条件1的判据在亏损句上给出正确裁定():
    """📏 spec 格取自 prod 实测（MOCK_REST，3 轮零抖动，设计卡 §一）。

    「有几家店是亏钱的」: LLM 判 STORE_MARGIN, planner 推 GROSS_MARGIN,
    requested_metrics=('gross_margin',) —— STORE_MARGIN 登记了它 ⇒ 不该 repair。
    """
    assert _resolver_declares_all(
        "RESTAURANT_OPS_STORE_MARGIN", ("gross_margin",)), (
        "STORE_MARGIN 明明登记了 gross_margin, 判据却说它满足不了 —— "
        "那么 contract-repair 会继续用只服务全店合计的 GROSS_MARGIN 覆盖它。")


@pytest.mark.parametrize("code", [
    "RESTAURANT_OPS_WASTAGE_TOP",
    "RESTAURANT_OPS_INVENTORY_WARNING",
    "RESTAURANT_OPS_SUPPLIER_PRICE",
    "RESTAURANT_OPS_STOCK_SHORTAGE",
])
def test_判据不是恒真式_算不出毛利的resolver必须返回False(code):
    """阴性对照。⛔ 少了它，上面那条断言无法与「判据恒为 True」区分。

    🔑 就是这条对照把第一版实现（走 `_default_metrics_for_code(code, True)`）
       当场打红的 —— 那一版对这四个 resolver 全部返回 True。
    """
    assert not _resolver_declares_all(code, ("gross_margin",)), (
        "%s 并不登记毛利, 判据却说它满足得了 —— 判据恒真, 守不住任何东西。" % code)


def test_判据在指标不兼容时要求repair():
    """📏 同一批 prod 实测里的第二条（「哪家店营收最高 → 那最低的呢」，2/3 轮）:

    LLM 判 STORE_MARGIN, planner 推 SALES_SUMMARY, requested=('revenue',)。
    STORE_MARGIN **登记不出** revenue ⇒ 这次该 repair（与上一条方向相反）。
    ▎同一条判据要能给出两个相反的答案, 否则它只是在复述其中一边。
    """
    assert not _resolver_declares_all("RESTAURANT_OPS_STORE_MARGIN", ("revenue",))
    assert _resolver_declares_all("RESTAURANT_OPS_SALES_SUMMARY", ("revenue",))


def test_没点名指标时判据返回False而不是兜底成True():
    """⛔ `requested_metrics` 为空 ⇒ 不许当成「都满足」。

    兜底成 True 会让「老板没点名指标」这一整类恒走「不 repair」，
    而那正是形态 A¹⁰：把「我不知道」翻译成一个确定的读数。
    """
    assert not _resolver_declares_all("RESTAURANT_OPS_STORE_MARGIN", ())
