# -*- coding: utf-8 -*-
"""整段落在未来的时间窗，不许被说成「系统这会儿有点忙」。

## 成因（prod 实测 2026-08-19，MOCK_REST，2 轮逐轮一致）

T6(e) 的截断**只问了 end，没问 start**：

    「2026年8月25号到8月31号的营收是多少」  anchor=2026-08-19
      -> start 2026-08-25 > end 2026-08-19        ← 颠倒区间
      -> gold.queries `ValueError: start … > end …`
      -> kind=unavailable
      -> 老板看到「系统这会儿有点忙，这次什么都没算…请稍后再问一次。」

▎**那是一句假话。系统没忙，是那段时间还没到。**

## ⛔ 为什么修法是两半，而且缺一半就更糟

只把标签路由到 `_FUTURE_WINDOW_LABEL`（第一半）之后，prod 实测：

    「2026年9月1号到9月10号的毛利率是多少」
      -> kind=answer「**菜品毛利分析（2026-07-20 至 2026-08-19）**…¥9,576,887.96」
    「2026年8月25日至2026年8月31日各门店营收排名」
      -> kind=answer「**全部门店2026-07-20 至 2026-08-19营收对比**」

▎把**吵闹的假故障**换成了**静默的历史数据** —— 按「⛔ 禁止降级处理」那更糟。
成因是 AST 数出来的：本文件里收 `date_range` 的函数 15 个，**看**
`_FUTURE_WINDOW_LABEL` 的只有 1 个。⇒ 必须同时有第二半（派发处的短路）。

## 本文件的四组断言

  ① 解析层：整段在未来 -> 未来时间；跨界仍然截断（T6(e) 逐字保留）
  ② 接线：那句话靠 `missing_reference` 才发得出去（形态 B「机制在、没接上」）
  ③ 派发闸：未来窗口短路；**有可用窗口时一步都不许短路**（阴性对照）
  ④ 文案：⛔ 不许出现「系统忙」这类甩锅措辞
"""
from __future__ import annotations

import ast
import datetime
import inspect
import io

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as ris
from smartbi.gold.restaurant import restaurant_ops_router as R
from smartbi.gold.restaurant.restaurant_ops_router import (
    OpsAnswer,
    _FUTURE_WINDOW_LABEL,
    _future_window_answer,
    _resolve_sales_date_range,
    resolve_by_code,
)

TODAY = datetime.date(2026, 8, 19)          # prod 复现当天


# ── ① 解析层 ─────────────────────────────────────────────────────────
@pytest.mark.parametrize("query,expected_requested", [
    ("2026年8月25号到8月31号的营收是多少", (datetime.date(2026, 8, 25),
                                            datetime.date(2026, 8, 31))),
    ("2026年9月1号到9月10号的毛利率是多少", (datetime.date(2026, 9, 1),
                                             datetime.date(2026, 9, 10))),
    ("2026年8月25日至2026年8月31日各门店营收排名", (datetime.date(2026, 8, 25),
                                                    datetime.date(2026, 8, 31))),
])
def test_whole_future_absolute_range_never_produces_an_inverted_window(
        query, expected_requested):
    """整段在未来 -> 未来时间通道。⛔ 绝不返回 start > end。"""
    (start, end), label = _resolve_sales_date_range(query, today=TODAY)
    assert (start, end) == (None, None), (
        f"{query!r} 仍然带回了一个窗口 {(start, end)} —— "
        f"整段在未来时任何窗口都是编出来的")
    assert label == _FUTURE_WINDOW_LABEL
    # 阳性对照: 那个区间**确实**整段在未来（否则这条用例测的不是它想测的东西）
    assert expected_requested[0] > TODAY


def test_cross_boundary_range_still_truncates_verbatim():
    """⛔ T6(e) 的正当用途逐字保留: start 在过去 -> 截 end 到今天。

    这条是**阴性对照**: 第一半的改动只许作用在「整段在未来」上，
    碰到这一条就说明它切得太宽了。
    """
    (start, end), label = _resolve_sales_date_range(
        "2026年8月10号到8月31号的营收是多少", today=TODAY)
    assert (start, end) == (datetime.date(2026, 8, 10), TODAY)
    assert label == "指定区间"


@pytest.mark.parametrize("query,expected", [
    ("2026年8月1号到8月15号的营收是多少",
     ((datetime.date(2026, 8, 1), datetime.date(2026, 8, 15)), "指定区间")),
    ("上周营收多少",
     ((datetime.date(2026, 8, 10), datetime.date(2026, 8, 16)), "上周")),
    ("最近30天营收多少",
     ((datetime.date(2026, 7, 21), TODAY), "最近30天")),
])
def test_ordinary_past_windows_are_untouched(query, expected):
    assert _resolve_sales_date_range(query, today=TODAY) == expected


# ── ② 接线：那句话凭什么发得出去 ──────────────────────────────────────
def _guard_decline_keys() -> set:
    """从 `restaurant_intent_service` 源码里**读**出 guard-decline 的 key 表。

    ⛔ 不在这里复制一份那个元组（形态 D：两份一定会漂，而漂的方向恰好是
       「测试以为接上了」）。

    ⚠️ 第一版这个抽取器**自己是错的**：它找 `ast.Compare` 右边的 Tuple，
       而源码写的是 `any(key in guard_meta for key in ("…", "…"))` ——
       那张表是 `ast.comprehension` 的 `iter`，不是 Compare 的右操作数。
       抽取器返回空集，而空集会让下面那条断言**恒真**（`keys & set(meta)`
       对空集永远是空）。⇒ 所以本函数配一条 `assert keys`：
       ▎**会读出「全无」的仪器必须能说清「是没有，还是我没看见」。**
    """
    src = io.open(inspect.getsourcefile(ris), "r",
                  encoding="utf-8", newline="").read()
    found = set()
    for node in ast.walk(ast.parse(src)):
        iters = []
        if isinstance(node, ast.comprehension):
            iters.append(node.iter)
        elif isinstance(node, ast.For):
            iters.append(node.iter)
        elif (isinstance(node, ast.Compare) and len(node.ops) == 1
                and isinstance(node.ops[0], ast.In)):
            iters.append(node.comparators[0])
        for it in iters:
            if not isinstance(it, (ast.Tuple, ast.List, ast.Set)):
                continue
            values = [e.value for e in it.elts
                      if isinstance(e, ast.Constant) and isinstance(e.value, str)]
            if "store_not_found" in values:      # 认出**那一张**表，⛔ 不是任意元组
                found.update(values)
    return found


def test_the_future_answer_uses_a_key_the_service_actually_honours():
    """形态 B：机制在、没接上。

    🔴 第一版**没带** `missing_reference`，于是这份 `charts=[]/kpis=[]` 的答案
       在 `answer_contract` 上判 missing，被换成
           「…我这次跑出来了，但里面有几句话没达到…所以我没发给你…」
       —— 又一句假话，而且暗示那段时间的数存在、只是被扣下了。
    """
    keys = _guard_decline_keys()
    assert keys, "没在服务层找到 guard-decline 的 key 表 —— 判据自己坏了"
    meta = _future_window_answer("RESTAURANT_OPS_SALES_SUMMARY").meta
    assert keys & set(meta), (
        f"未来窗口答案的 meta {sorted(meta)} 里没有任何一个服务层认的 key "
        f"{sorted(keys)} ⇒ 正文到不了用户手里，会被契约兜底文案顶替")


def test_the_future_answer_does_not_blame_the_system_or_the_data():
    """④ 文案：⛔ 不甩锅。守的是**性质**，不是某一句措辞。"""
    text = _future_window_answer(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "2026年8月25号到8月31号的营收是多少",
        today=TODAY).answer_text
    for lie in ("系统这会儿有点忙", "稍后再问", "请稍后", "数据已经同步",
                "账单已同步", "跑出来了"):
        assert lie not in text, f"未来窗口的答案里出现了甩锅措辞 {lie!r}"
    # 正向: 说清「没拿别的时间段顶替」—— 这正是这道闸拦掉的那件事
    assert "顶替" in text
    # 老板原话里的日期要回读，让他确认系统听懂了哪几天
    assert "2026-08-25" in text and "2026-08-31" in text


def test_the_future_answer_has_exactly_one_definition():
    """形态 D：同一句话不许有第二份。`resolve_sales_summary` 也得调它。"""
    src = io.open(inspect.getsourcefile(R), "r",
                  encoding="utf-8", newline="").read()
    calls = [n for n in ast.walk(ast.parse(src))
             if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
             and n.func.id == "_future_window_answer"]
    defs = [n for n in ast.walk(ast.parse(src))
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
            and n.name == "_future_window_answer"]
    assert len(defs) == 1, "措辞长出了第二份定义"
    assert len(calls) >= 2, (
        "只有一个调用点 —— `resolve_sales_summary` 那条既有分支应该也走这份定义")


# ── ③ 派发闸：短路 + 阴性对照 ────────────────────────────────────────
_SENTINEL = OpsAnswer(code="SENTINEL", title="哨兵", answer_text="真的派发了",
                      charts=[], kpis=[], meta={})


@pytest.fixture
def only_sentinel(monkeypatch):
    """把整张派发表换成哨兵 —— 拿到哨兵 = **闸没拦**，真的走到了 resolver。

    ⚠️ 哨兵签名必须声明 `date_range`，否则 `resolver_supports_explicit_window`
       (按签名判) 会说这个 code 不看窗口，闸从此恒不触发 ——
       那样两条用例会**一起**变绿，等于什么都没测。
    """
    async def _sentinel(smartbi_pool, factory_id, *, date_range=None, **kw):
        return _SENTINEL

    monkeypatch.setattr(
        R, "_RESOLVERS", {"RESTAURANT_OPS_SALES_SUMMARY": _sentinel})
    return _sentinel


@pytest.mark.asyncio
async def test_dispatch_short_circuits_a_future_window(only_sentinel):
    """闸触发: ⛔ 一次库都不查（pool 传 None，查库就会炸）。"""
    got = await resolve_by_code(
        "RESTAURANT_OPS_SALES_SUMMARY", None, "MOCK_REST",
        query="2026年8月25号到8月31号的营收是多少", role="restaurant_manager",
        today=TODAY)
    assert got is not _SENTINEL, "闸没拦住 —— 未来窗口被送进了 resolver"
    assert got.meta.get("future_request") is True


@pytest.mark.asyncio
async def test_dispatch_does_not_fire_when_a_usable_window_exists(only_sentinel):
    """阴性对照: 有可用窗口就**一步都不许短路**。

    ▎这条是「正常过去窗口逐字不变」的**结构性**保证:
      闸的第二个条件就是「手上没有可用窗口」，所以 C/D 组走不到它。
    """
    got = await resolve_by_code(
        "RESTAURANT_OPS_SALES_SUMMARY", None, "MOCK_REST",
        query="2026年8月1号到8月15号的营收是多少", role="restaurant_manager",
        date_range=(datetime.date(2026, 8, 1), datetime.date(2026, 8, 15)))
    assert got is _SENTINEL, "正常窗口被未来闸误伤了"


@pytest.mark.asyncio
async def test_dispatch_does_not_fire_on_a_relative_past_window(only_sentinel):
    """阴性对照之二: 相对过去窗口同样不许被拦。"""
    got = await resolve_by_code(
        "RESTAURANT_OPS_SALES_SUMMARY", None, "MOCK_REST",
        query="上周营收多少", role="restaurant_manager",
        date_range=(datetime.date(2026, 8, 10), datetime.date(2026, 8, 16)))
    assert got is _SENTINEL
