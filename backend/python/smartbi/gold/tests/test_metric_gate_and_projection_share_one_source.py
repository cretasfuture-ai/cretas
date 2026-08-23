# -*- coding: utf-8 -*-
"""契约闸与「指标没给数」的投影，必须共用**同一份** token —— ⛔ 两份一定会漂。

## 为什么这个文件存在（⛔ 而不是去收紧那道闸）

`answer_contract._request_coverage_present` 的判据是
`_contains_any(answer_text, tokens)` —— **「提到了这个词」**。
实测它被一句**建议**（「往下可以拆开看…毛利分别是多少」）满足了。

⛔ **本轮裁定：不收紧那道闸。** 理由是可检验的：

    `request_coverage` 是**契约元素** ⇒ 闸红 ⇒ `contract.missing` 非空
    ⇒ 老板拿到的是**兜底拒答**，而不是「营收表 + 一句说明」。
    ▎收紧它 = 把「答案 + 说明」换成「拒答」= **退步**。

而「他要的指标没给出数」这件事，已经由 `_metric_gap_note` **对全部有 token 的
指标**做了投影（同一批 token、结构判据），老板都会被告知。

⇒ 剩下的唯一风险是**两者漂开**：闸认的 token 与投影认的 token 不是一份，
   于是出现「闸放行、投影也不说」的第三种缝。本文件钉住这一点。

## 断言各守什么

    承重1  两者读的是**同一个** `_REQUEST_TEXT_TOKENS` 对象（⛔ 不是拷贝）
    承重2  两者遍历的是**同一个**字段 `spec.requested_metrics`
    阴性   ⛔ 不断言两者判据相同 —— 它们**本来就不同**（闸宽、投影严），
           那正是本轮的裁定；断言相同会把裁定反过来
"""
from __future__ import annotations

import ast
import inspect
import textwrap

from smartbi.gold.restaurant import answer_contract as contract
from smartbi.gold.restaurant import restaurant_intent_service as svc


def _iterated_attr(fn, attr: str) -> bool:
    """函数体里有没有读到 `<something>.<attr>` —— **两种写法都算**。

    ⚠️ 第一版只认 `ast.Attribute`（`spec.requested_metrics`），当场红在
    `_metric_gap_note` 上，而它用的是 `getattr(spec, "requested_metrics", ())`
    —— **字符串形式**，AST 上是 `Call` 的常量实参，不是 `Attribute`。
    ▎**AST 判据也会量错对象**：它比字符串精确，但「精确地量了错的那个结构」
    ▎和量错字符串一样不成立。
    ⛔ 修法是改判据，⛔ 不是把生产代码改成属性访问去迁就判据。
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(fn)))
    for n in ast.walk(tree):
        if isinstance(n, ast.Attribute) and n.attr == attr:
            return True
        if (isinstance(n, ast.Call)
                and getattr(n.func, "id", None) == "getattr"
                and len(n.args) >= 2
                and isinstance(n.args[1], ast.Constant)
                and n.args[1].value == attr):
            return True
    return False


def test_both_read_the_same_token_table_object():
    """承重1：**同一个对象**，⛔ 不是「内容碰巧一样的两份」。

    ⚠️ 判据用 `is` —— 内容相等挡不住「有人复制了一份然后改了其中一份」。
    """
    src = textwrap.dedent(inspect.getsource(svc._metric_gap_note))
    tree = ast.parse(src)
    imported = {
        a.name
        for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)
        for a in n.names
    }
    assert "_REQUEST_TEXT_TOKENS" in imported, (
        "投影没有从 answer_contract 取那份 token —— 两份词表一定会漂")
    # 真的是同一个对象（import 进来的就是它）
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS
    assert _REQUEST_TEXT_TOKENS is contract._REQUEST_TEXT_TOKENS


def test_both_iterate_requested_metrics():
    """承重2：遍历的是同一个字段。

    闸读 `spec.requested_metrics`，投影也读它 ——
    其中一边改成读别的（比如 `spec.metrics`），就会出现
    「闸检查了 A、投影检查了 B」的缝，而那不报错。
    """
    assert _iterated_attr(contract._request_coverage_present, "requested_metrics")
    assert _iterated_attr(svc._metric_gap_note, "requested_metrics")


def test_we_deliberately_did_not_tighten_the_gate():
    """🔴 阴性：⛔ **不**断言两者判据相同。

    本轮的裁定就是「闸保持宽、投影负责说清」——
    闸用 `_contains_any`（提到就算），投影用结构判据（表头 / 近旁有数）。
    ▎断言它们相同，等于把这条裁定反过来，而且会让下一个人去收紧闸
    ▎—— 那会把「答案 + 说明」变成「拒答」。

    这条断言守的是**裁定本身**：闸仍然是那个宽判据。
    """
    src = inspect.getsource(contract._request_coverage_present)
    assert "_contains_any" in src, (
        "闸被收紧了 —— 如果那是有意的，请连同这条断言一起改，"
        "并说明为什么「拒答」比「答案 + 说明」好")
