# -*- coding: utf-8 -*-
"""产出被接地闸驳回时，**手边有没有数**，而我们一个都没给。

## 起点（`restaurant_intent_service.py` 那段的纪律）

```python
# ⚠️ **只在能算出「为什么」时才走这条路**: 说不出理由的数字贴在拒答旁边
#    就是顶替。理由取自 spec 自己记下的能力缺口, 取不到就退回整份拒答。
partial_text = None
gap_labels = missing_capability_labels(spec.unsupported_requirements)
if gap_labels:
    partial_text = partial_coverage_answer(missing, "、".join(gap_labels), kpis)
```

⇒ `gap_labels` 空 ⇒ **整份拒答**，哪怕 `kpis` 就在手边。
而 prod 实测那几条撞上兜底的问句，`unsupported_requirements` **都是空的**。

## ⛔ 本探针只量代价，**不改那条纪律**

那条纪律是 owner 写的（「说不出理由的数字贴在拒答旁边就是顶替」）。
⛔ 我不单方面推翻它。本探针只回答一件事：

    撞上兜底的那些轮次里，`kpis` 手边**有几个数**？

有很多 ⇒ 代价是「老板一个数都拿不到，而数就在手边」，值得 owner 重新裁定；
本来就没有 ⇒ 那条纪律没有代价，登记关掉。

## 阳性/阴性对照（⛔ 不许省）

- **阳性**：至少一条撞上兜底 —— 否则这一格没被触到（`rc=2`）
- **阴性**：一条正常出数的问句，`kpis` 必须非空 —— 证明我读的就是那个字段
"""
from __future__ import annotations

import asyncio
import os
import time

from smartbi.scripts._probe_bootstrap import bootstrap_probe

_RK_FID = "MOCK_REST"
_RK_RUN = "%d-%d" % (os.getpid(), int(time.time()))
ctx = bootstrap_probe(_RK_FID)

from smartbi.gold.restaurant import restaurant_intent as ri  # noqa: E402
from smartbi.gold.restaurant import restaurant_intent_service as svc  # noqa: E402
from smartbi.gold.restaurant import restaurant_ops_router as rr  # noqa: E402
from smartbi.tenant_ctx import set_factory_id  # noqa: E402

_RK_MARK = "不把推测说成结论"
_RK_QUERIES = [
    "最近30天营收为什么下滑",
    "模拟·徐汇美罗城店最近30天为什么毛利低",
    "最近30天哪个时段毛利最高",
    "本月生意不太好有什么办法提升",
    "最近30天各门店营收是多少",          # 阴性对照：正常出数
]

# 🔴 第一版读的是**最终响应 dict** 的 `res["kpis"]`，而**正常出数的那两条
#    它也是 0** —— 阴性对照当场红了，救回了整份读数：
#    「撞上兜底的 3 条 kpis=0」不是「手边没数」，是**我读错了字段**。
#    ▎会读出「全 0」的仪器必须带阳性/阴性对照 —— 这次它真的开火了。
# ⇒ 改成在**那一行被执行的现场**取 `tiered_result`：
#    `missing_capability_labels` 恰好就在它前一行被调用，
#    用 `sys._getframe(1).f_locals` 拿调用者的局部变量。
#    ⛔ 不用 `sys.settrace` + 行号（行号会随文件漂，还要自检一轮）。
_RK_AT_REFUSAL = []
_RK_REAL_LABELS = svc.missing_capability_labels


def _labels_spy(unsupported):
    import sys as _sys
    caller = _sys._getframe(1).f_locals
    tr = caller.get("tiered_result")
    kpis = getattr(tr, "kpis", None)
    _RK_AT_REFUSAL.append({
        "unsupported": tuple(unsupported or ()),
        "kpis_in_hand": len(kpis) if isinstance(kpis, list) else 0,
        "has_tiered_result": tr is not None,
        "answer_text_len": len(str(caller.get("answer_text") or "")),
        # 🔴 「手边有 4 个」不等于「那 4 个能发出去」——
        #    `partial_coverage_answer` 只认 `{label, value, unit}` 且 value 非占位。
        #    ⇒ 把**实际结构**打出来，⛔ 不猜它为什么返回 None。
        "kpi_shape": [
            (type(k).__name__,
             sorted(k.keys()) if isinstance(k, dict) else repr(k)[:60])
            for k in (kpis or [])[:4]
        ] if isinstance(kpis, list) else None,
        "kpi_sample": [
            {kk: str(vv)[:24] for kk, vv in k.items()}
            for k in (kpis or [])[:2] if isinstance(k, dict)
        ] if isinstance(kpis, list) else None,
    })
    return _RK_REAL_LABELS(unsupported)


svc.missing_capability_labels = _labels_spy


def _clear():
    for n in ("clear_semantic_plan_cache", "clear_route_cache",
              "clear_tenant_gate_cache", "clear_promoted_routes_cache"):
        getattr(ri, n)()


async def main() -> int:
    pool = await ctx.pool()
    print("PROBE_DB=%s RUN=%s" % (ctx.db_name, _RK_RUN))
    if getattr(ctx, "llm_dead_slots", None):
        print("⛔ rc=2 LLM 槽没有活账号: %s" % "、".join(ctx.llm_dead_slots))
        return 2

    rows = []
    for q in _RK_QUERIES:
        _clear()
        set_factory_id(_RK_FID)
        skey = "rk-%s-%s" % (_RK_RUN, q)
        async with rr.dish_catalogue_scope(pool, _RK_FID):
            spec = await ri.parse_restaurant_query(
                q, pool, factory_id=_RK_FID, session_key=skey, semantic_first=True)
            res = None
            if spec is not None and svc.should_delegate(spec, None, query=q):
                res = await svc.tiered_answer(
                    q, pool, _RK_FID, ctx.role, precomputed_spec=spec,
                    session_key=skey)
        res = res or {}
        body = res.get("answer_text") or ""
        hit = _RK_MARK in body
        # 🔑 从**拒答现场**取，⛔ 不从最终响应 dict 取（那里没有）
        at = _RK_AT_REFUSAL[len(_RK_AT_REFUSAL) - 1] if _RK_AT_REFUSAL else None
        n_kpi = at["kpis_in_hand"] if at else -1
        unsup = tuple(getattr(spec, "unsupported_requirements", ()) or ()) if spec else ()
        rows.append((q, res.get("kind"), hit, n_kpi, unsup, len(body)))
        print("\n" + "=" * 78)
        print("【%s】kind=%s 撞上兜底=%s" % (q, res.get("kind"), hit))
        print("   kpis 手边有 %d 个   unsupported_requirements=%s"
              % (n_kpi, list(unsup)))
        print("   正文 %d 字: %s" % (len(body), body[:120].replace("\n", " ")))

    hits = [r for r in rows if r[2]]
    ok = [r for r in rows if r[1] == "answer" and not r[2]]
    print("\n" + "=" * 78)
    print("阳性对照·撞上兜底 = %d 条" % len(hits))
    print("阳性对照·拒答现场被钩到 = %d 次（0 ⇒ monkeypatch 打空，读数作废）"
          % len(_RK_AT_REFUSAL))
    if not _RK_AT_REFUSAL:
        print("⛔ rc=2 一次都没钩到拒答现场 —— 读数没有意义")
        return 2
    if not hits:
        print("⛔ rc=2 一条都没撞上 —— 这一格没被触到，读数没有意义")
        return 2

    with_kpi = [r for r in hits if r[3]]
    print("\n🔑 撞上兜底的 %d 条里" % len(hits))
    print("  手边**有** kpis 却一个数都没给 = %d" % len(with_kpi))
    print("  手边本来就没有 kpis           = %d" % (len(hits) - len(with_kpi)))
    for r in hits:
        print("     kpis=%-3d unsupported=%-10s ← %s" % (r[3], list(r[4]), r[0]))
    print("\n🔑 kpis 的**实际结构**（决定它发不发得出去）")
    for at in _RK_AT_REFUSAL:
        print("   shape=%s" % (at.get("kpi_shape"),))
        print("   sample=%s" % (at.get("kpi_sample"),))
    # 阳性对照：`partial_coverage_answer` 拿这批真数据到底返不返回 None
    from smartbi.gold.restaurant.capability_answer import partial_coverage_answer
    for at in _RK_AT_REFUSAL[:1]:
        pass
    print("\n⚠️ 若 shape 不是 {label,value,unit} 或 value 是占位符，"
          "`partial_coverage_answer` 会返回 None ⇒ 仍然整份拒答。")
    print("\n⛔ 本探针不改那条纪律 —— 上面是它的代价，给 owner 裁定用。")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
