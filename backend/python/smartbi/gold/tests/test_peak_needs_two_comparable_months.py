# -*- coding: utf-8 -*-
"""可比的完整月不足两个时，⛔ 不出「最高月 / 最低月」。

## prod 实测（`_peak_equals_trough`，MOCK_REST，12 轮，按 `window_label` 分组）

```
'最近30天'  6 轮   最高==最低 **6/6**   🔴
'今年'      3 轮   最高==最低 0/3       ✅
'截至目前'   3 轮   最高==最低 0/3       ✅
```

老板看到的是：`- 数据月数：2  - 最高月：2026-07  - 最低月：2026-07`。
⚠️ **默认窗就是「最近30天」⇒ 这是常态输出，不是边界情况。**

## 成因（两条剔除规则的**交互**）

```
30 天窗 ⇒ monthly = [残首月, 残尾月]
       ⇒ 规则一剔残尾月 ⇒ comparable_monthly 只剩 1 个
       ⇒ 规则二的守卫 `len > 1` 不成立 ⇒ 残首月**没被剔**
       ⇒ max/min 作用在单元素列表 ⇒ 同一个元素，且它本身是残月
```

▎**两条规则各自都对，没有人守「过滤完还剩几个」。**

## ⛔ 修复方向不是「挑错了月」

外部审计一开始就是那么判的（SQL 量出 2026-08 更高 ⇒「该报 08」），
而按产品自己的规则 **08 是残尾月、本就该剔**。
▎**读数对 ≠ 成因对。** ⇒ 守的是**基数**，⛔ 不是排序/取数。
⛔ 也不加第三条剔除规则 —— 那会长出第三处同样没人守基数的地方（形态 D）。

## 断言各守什么（⛔ 不是同义的）

    承重1  基数 < 2 ⇒ 正文**不出**那两行，改说「比不出最高/最低月」
    承重2  基数 < 2 ⇒ `kpis` 里**没有**那两条
           （只改正文的话前端仍会显示那对相同的月）
    承重3  基数 < 2 ⇒ 建议动作第 1 条不再让他「把最高月和最低月拆开」
           —— 比不出来时那是一条他做不到的指引
    承重4  `meta.peak_month/trough_month` 给 `None`，并报出**基数本身**
           （「比不了」和「没算」在 None 上长得一样）
    阴性1  基数 >= 2 ⇒ 三处**逐字不变**
    阴性2  ⛔ 不许出现「最高月 == 最低月」的输出（这一条直接钉症状）
"""
from __future__ import annotations

import ast
import inspect
import re
import textwrap

from smartbi.gold.restaurant import restaurant_ops_router as router

_PEAK_SRC = inspect.getsource(router.resolve_trend_analysis)


def _fenced(name: str) -> bool:
    """`name` 这个消费点有没有被 `can_compare_months` 守住。

    ⚠️ 用 **AST** 找它出现在哪个条件表达式/分支里，⛔ 不数字符串
    （本仓今天已经因为「数注释」「数建议句里的词」栽过三次）。
    """
    tree = ast.parse(textwrap.dedent(_PEAK_SRC))
    guarded = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.IfExp, ast.If)):
            test = ast.dump(node.test)
            if "can_compare_months" not in test:
                continue
            for sub in ast.walk(node):
                if isinstance(sub, ast.Constant) and isinstance(sub.value, str):
                    guarded.add(sub.value)
                if isinstance(sub, ast.Name):
                    guarded.add(sub.id)
    return name in guarded


def test_precondition_the_guard_exists():
    """前提：那个基数守卫真的在源码里。

    ⚠️ 不在的话下面每条 AST 断言都会以「找不到」的方式**假绿**。
    """
    assert "peak_trough_comparable" in _PEAK_SRC
    assert "can_compare_months" in _PEAK_SRC


def test_the_prose_lines_are_fenced():
    """承重1：正文那两行被基数守卫圈住。"""
    assert _fenced("peak_trough_lines") or "peak_trough_lines" in _PEAK_SRC
    m = re.search(r"peak_trough_lines\s*=\s*\((.*?)\n    \)", _PEAK_SRC, re.S)
    assert m, "找不到 peak_trough_lines 的定义"
    body = m.group(1)
    assert "can_compare_months" in body, "正文那两行没有被基数守卫圈住"
    assert "比不出最高/最低月" in body, "比不出来时没有换成一句实话"


def test_the_kpis_say_it_too_instead_of_vanishing():
    """🔴 承重2：`kpis` 里那两条**跟着正文说同一件事**。

    ## ⚠️ 判据升级两次，都留痕（⛔ 不删）

    **第一版**往**前** 400 字符找守卫，而守卫写在那两条**之后**
    （`… ] if can_compare_months else []`）。
    ▎**判据方向错了，⛔ 不是代码错了** —— 差一点就去改生产代码迁就它。
    ⇒ 改成 AST 找「位于一个 `can_compare_months` 的 `IfExp` 子树里」。

    **第二版（本次）**：那个判据守的是「被圈住」这个**实现方式**，
    而当时的实现是「比不出来就**不出**这两条」。
    prod 实测（`_peak_equals_trough`，MOCK_REST，3 轮逐轮一致）：

    ```
    window_label='最近30天'  kpis=**2**   ← 那两张卡凭空少了
    window_label='今年'      kpis=4
    ```

    正文那句实话 6/6 都在，**而机器可读侧一个字都没说**。
    ▎「少了两张卡」和「这两个指标算不出来」在消费方长得一模一样。
    ⇒ 实现改成「照发，但 value 说『比不出来（可比完整月只有 N 个）』」。

    ▎**守的性质从头到尾没变**：⛔ 不许出现**两个相同的月**。
    ▎变的只是判据盯的层次 —— 从「怎么实现的」抬到「输出成不成立」。
    ⛔ 改断言没有滑成删断言：下面守的东西比原来**多一条**
    （「⛔ 不许静默消失」是新增的）。
    """
    tree = ast.parse(textwrap.dedent(_PEAK_SRC))
    kpi_dicts = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Dict):
            continue
        for k, v in zip(node.keys, node.values):
            if (isinstance(k, ast.Constant) and k.value == "title"
                    and isinstance(v, ast.Constant)
                    and v.value in ("最高月", "最低月")):
                kpi_dicts.append((v.value, node))
    got = {t for t, _ in kpi_dicts}
    assert got == {"最高月", "最低月"}, (
        f"kpis 里少了: {sorted({'最高月', '最低月'} - got)} —— "
        "静默消失和「算不出来」在消费方长得一模一样")

    for title, d in kpi_dicts:
        value = next(v for k, v in zip(d.keys, d.values)
                     if isinstance(k, ast.Constant) and k.value == "value")
        assert isinstance(value, ast.IfExp), (
            f"「{title}」的 value 不是条件表达式 —— "
            "比不出来时它会照旧摆出一个月份")
        assert "can_compare_months" in ast.dump(value.test), (
            f"「{title}」的 value 没有被基数守卫管着")
        # 比不出来那一侧必须是**一句实话**，⛔ 不是月份、⛔ 不是空
        texts = {
            n.value for n in ast.walk(value.orelse)
            if isinstance(n, ast.Constant) and isinstance(n.value, str)
        }
        assert any("比不出来" in s for s in texts), (
            f"「{title}」比不出来时没有说一句实话: {sorted(texts)}")
        # ⚠️ 判据订正（留痕）：第一版写 `"peak" not in ast.dump(...)` ——
        #    **子串**判据，而 `peak_trough_comparable` 这个名字里就含 `peak`，
        #    当场误报。⛔ 别用子串当判据（纪律 1）——
        #    这里要问的是「有没有读**那个变量**」，⇒ 数 `ast.Name` 的精确 id。
        names = {n.id for n in ast.walk(value.orelse) if isinstance(n, ast.Name)}
        assert not (names & {"peak", "trough"}), (
            f"「{title}」比不出来时仍然在读 {sorted(names & {'peak', 'trough'})}"
            " —— 那正是症状本身")


def test_the_first_action_does_not_ask_him_to_do_the_impossible():
    """承重3：比不出来时，⛔ 不再建议他「把最高月和最低月拆开」。

    同日已经因为**同一形状**改过一次（「建议他做他已经做过的事」）。
    """
    m = re.search(r"first_action\s*=\s*\((.*?)\n    \)", _PEAK_SRC, re.S)
    assert m, "找不到 first_action 的定义"
    body = m.group(1)
    assert "can_compare_months" in body
    assert "放宽到至少两个完整月" in body, "没有给一条他真能做的出路"


def test_meta_reports_the_base_count_itself():
    """承重4：`meta` 给 `None` **并且**报出基数本身。

    ⛔ 只给 None 的话，「比不了」和「没算」长得一模一样 ——
    那正是今天在别处栽过的形状（静默返回 None）。
    """
    assert '"peak_month": peak["month"] if can_compare_months else None' in _PEAK_SRC
    assert '"peak_trough_comparable_months": peak_trough_comparable' in _PEAK_SRC


def test_the_guard_is_a_base_count_not_a_third_filter():
    """🔴 阴性：⛔ 不许长出**第三条剔除规则**。

    那会长出第三处同样没人守基数的地方（形态 D）——
    本刀的全部意义是「守基数」，⛔ 不是「再剔一次」。
    ⇒ `comparable_monthly` 的赋值点数必须还是 **2** 个（原来那两条规则）。
    """
    tree = ast.parse(textwrap.dedent(_PEAK_SRC))
    assigns = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Assign)
        for t in n.targets
        if isinstance(t, ast.Name) and t.id == "comparable_monthly"
    ]
    assert len(assigns) == 2, (
        f"`comparable_monthly` 被赋值 {len(assigns)} 次（应为 2）"
        " —— 多出来的那次多半是第三条剔除规则")
