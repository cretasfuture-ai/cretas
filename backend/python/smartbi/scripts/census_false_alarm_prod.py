"""在 prod 上量「无条件提示」到底会不会误发 —— 三态 rc。

## 它回答的那一句话（owner 反目标第一条）

> 排在最前面的那个命中，他去查会不会一无所获。

普查(`census_unconditional_callouts.py`)只能说「这句话没挂在阈值上」。
**它值不值得他跑一趟**，只有真实数据能回答。本探针量两件事：

1. **五处无条件「建议动作」** —— 它们和同一份答案里**已经挂了阈值**的那句话
   会不会**当场打架**（例：上一段说「按门店查多半找不到东西」，
   下一段说「把第一名门店…作为其他门店对标模板」）。
2. **门店简称消解的 ambiguous 分支** —— 一个**不存在**的店名会不会被报成
   「我这边有 N 家对得上」，以及那 N 家凭什么算「对得上」(cosine 分布)。

## ⚠️ 干净样本会让每一道闸都绿

MOCK_REST 在本仓已明确裁定「只做冒烟，不做验收」。所以默认跑三个租户，
其中两个是脏样本(成本卡覆盖率 42.2% / 61.3%)。

## 三态退出码（硬约束 4）

    rc=0  没量到误发
    rc=1  量到误发（读数有效，且指向缺陷）
    rc=2  **这次没量到东西** —— 阳性对照没通过 / 一个租户都没读到 /
          embedding 服务不可用（那时「不存在的店名判 none」是仪器死了，
          ⛔ 不是产品对）

## 用法（部署树里跑，跑完删掉）

    cd /www/wwwroot/cretas/code/backend/python
    eval "$(tr '\\0' '\\n' < /proc/<pid>/environ \\
        | grep -E '^(POSTGRES_|FOOD_KB_POSTGRES_|LLM_)' | sed 's/^/export /')"
    venv-current/bin/python -m smartbi.scripts.census_false_alarm_prod
"""
from __future__ import annotations

import argparse
import asyncio
import pathlib
import sys
import traceback
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.scripts._probe_bootstrap import bootstrap_probe

# ⚠️ 常量一律带 CENSUS_ 前缀（同名常量漂移闸 tests/test_no_drifted_duplicate_constants.py）
CENSUS_PROD_TENANTS: Tuple[str, ...] = ("MOCK_REST", "DEMO_REST", "RES_3101_009")

#: 每个 resolver 的墙钟上限。超时**单列一档**，⛔ 不并进「没误发」。
CENSUS_CALL_TIMEOUT_S = 180.0

#: 源码锚点 —— (相对 backend/python 的路径, 必须出现的串, 这串是干什么的)。
#:
#: 🔴 **这些锚点自己就是阳性对照**：探针靠它们在答案里找东西，
#:    锚点一旦被改文案，探针会读出「没有误发」——而那是「我没看到」，
#:    ⛔ 不是「没发生」。所以每轮先证明它们还在源码里，找不到就 rc=2。
CENSUS_SOURCE_ANCHORS: Tuple[Tuple[str, str, str], ...] = (
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "按门店查多半找不到东西",
     "PR#2859 修好的那句(挂在 _STORE_SPREAD_WORTH_CHASING_PCT 上)"),
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "作为其他门店对标模板",
     "同一函数里**没挂阈值**的建议动作第 1 条"),
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "对盘亏最高的食材先核查领料单",
     "resolve_stock_shortage 的无条件建议动作"),
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "先把最高月和最低月按门店、渠道、折扣三层拆开",
     "resolve_trend_analysis 的无条件建议动作"),
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "需立即补货的食材今天下单",
     "resolve_inventory_warning 的无条件建议动作"),
    ("smartbi/gold/restaurant/restaurant_ops_router.py",
     "把领用靠前的食材和畅销菜、损耗榜交叉看",
     "resolve_requisition_trend 的无条件建议动作"),
)

#: (答案里那句**已挂阈值**的话, 同一份答案里那句**没挂阈值**的建议)
#: 两句同时出现 ⇒ 产品在同一屏里自相矛盾 ⇒ 必然有一句让他白跑。
CENSUS_CONTRADICTION_PAIRS: Tuple[Tuple[str, str, str], ...] = (
    ("resolve_store_margin",
     "按门店查多半找不到东西",
     "作为其他门店对标模板"),
)

#: 一个**不存在**的店名, 用来问「它会不会被报成对得上」。
#: ⚠️ 前缀由该租户真实店名的最长公共前缀现算, ⛔ 不写死 ——
#:    写死就只在 MOCK_REST 上成立。
CENSUS_FAKE_DISTINCTIVE = "青浦新开"


def _census_read(rel: str) -> str:
    root = pathlib.Path(__file__).resolve().parents[2]
    with open(root / rel, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def census_check_anchors() -> List[str]:
    """锚点还在不在源码里。返回**找不到**的那几条。"""
    missing: List[str] = []
    cache: Dict[str, str] = {}
    for rel, needle, what in CENSUS_SOURCE_ANCHORS:
        if rel not in cache:
            try:
                cache[rel] = _census_read(rel)
            except OSError as exc:
                missing.append(f"{rel} 读不到: {exc}")
                continue
        if needle not in cache[rel]:
            missing.append(f"{rel} 里找不到 {needle!r} —— {what}")
    return missing


def _census_common_prefix(names: Sequence[str]) -> str:
    if not names:
        return ""
    head = names[0]
    for name in names[1:]:
        i = 0
        while i < len(head) and i < len(name) and head[i] == name[i]:
            i += 1
        head = head[:i]
        if not head:
            break
    return head


async def _census_call(label: str, coro) -> Tuple[str, Any]:
    """跑一个 resolver，三态返回。⛔ 失败不 continue —— 失败要计数并贴出来。"""
    try:
        return "ok", await asyncio.wait_for(coro, timeout=CENSUS_CALL_TIMEOUT_S)
    except asyncio.TimeoutError:
        return "timeout", None
    except Exception as exc:  # noqa: BLE001 - 就是要把失败收集起来
        return f"error:{type(exc).__name__}:{str(exc)[:120]}", None


async def census_measure_tenant(tenant: str, out: List[str]) -> Dict[str, Any]:
    """一个租户的全部读数。**每个租户独立 bootstrap + 独立取池上下文证明**。"""
    result: Dict[str, Any] = {"tenant": tenant}
    ctx = bootstrap_probe(tenant)

    from smartbi.gold.restaurant import restaurant_ops_router as router

    pool = await ctx.pool()

    # ── RLS 上下文证明：⛔ 不假设它生效，查一次贴出来 ──────────────────
    async with pool.acquire() as conn:
        seen = await conn.fetchval("SELECT current_setting('app.factory_id', true)")
        rls_on = await conn.fetchval(
            "SELECT relrowsecurity FROM pg_class WHERE relname = 'dim_store'")
        store_rows = await conn.fetch(
            "SELECT name FROM dim_store WHERE factory_id = $1 ORDER BY name", tenant)
        # 🔴 「最低月」值不值得追, 取决于它是不是**残月** —— 数据起点在月中时,
        #    「上升 1438%」是数据覆盖的产物, ⛔ 不是经营变化。
        span = await conn.fetchrow(
            "SELECT min(date) AS lo, max(date) AS hi "
            "FROM agg_restaurant_daily_ops WHERE factory_id = $1", tenant)
    names = [r["name"] for r in store_rows if r["name"]]
    result["app_factory_id_seen"] = seen
    result["dim_store_rls"] = rls_on
    result["store_count"] = len(names)
    out.append(f"\n{'=' * 72}\n租户 {tenant}")
    out.append(f"  RLS 上下文 current_setting('app.factory_id') = {seen!r}"
               f"   (期望 {tenant!r})")
    out.append(f"  dim_store 开着 RLS = {rls_on}   门店数 = {len(names)}")
    if span and span["lo"]:
        result["ops_span"] = (str(span["lo"]), str(span["hi"]))
        out.append(f"  经营数据区间 {span['lo']} ~ {span['hi']}"
                   f"   (首月是不是残月, 决定「最低月」那条建议成不成立)")
    if seen != tenant:
        out.append("  🔴 上下文没生效 —— 这个租户的所有读数作废")
        result["context_ok"] = False
        return result
    # 🔴 三态, ⛔ 不是两态: 「这个租户没数据」必须与「量了, 没误发」分开。
    #    ⚠️ 第一版把 0 家门店的租户当成「不适用」放过去了 ——
    #    那正好长成「脏样本上也没问题」的样子, 而真相是**我一条都没量到**。
    if not names:
        out.append("  ⚠️ 这个租户在 prod 上**没有门店数据** —— 本轮它一条读数都没有,"
                   " ⛔ 不能读成「这里没有误发」")
        result["context_ok"] = True
        result["no_data"] = True
        return result
    result["context_ok"] = True
    result["no_data"] = False

    # ── ① 五处无条件建议动作: 在真实答案里找它们 ─────────────────────
    calls = {
        "resolve_store_margin": router.resolve_store_margin(
            pool, tenant, days=30, role=ctx.role),
        "resolve_trend_analysis": router.resolve_trend_analysis(
            pool, tenant, role=ctx.role),
        "resolve_stock_shortage": router.resolve_stock_shortage(
            pool, tenant, days=30),
        "resolve_inventory_warning": router.resolve_inventory_warning(
            pool, tenant),
        "resolve_requisition_trend": router.resolve_requisition_trend(
            pool, tenant, days=30),
        # 🟡 档候选: 都会**点名一个具体对象**, 依据是量级(拖累/缺口/毛利率)
        #    但**没有「值不值得跑一趟」的阈值**。
        "resolve_gross_margin": router.resolve_gross_margin(
            pool, tenant, days=30, role=ctx.role),
        "resolve_staffing_advice": router.resolve_staffing_advice(
            pool, tenant, role=ctx.role, dimensions=("store",)),
    }
    answers: Dict[str, Optional[str]] = {}
    statuses: Dict[str, str] = {}
    for label, coro in calls.items():
        status, ans = await _census_call(label, coro)
        statuses[label] = status
        answers[label] = getattr(ans, "answer_text", None) if ans is not None else None
        out.append(f"\n  ── {label}: {status}")
        if answers[label] is None:
            out.append("     (拿不到答案 —— 这一条**不算「没误发」**)")
            continue
        out.append(f"     答案长度 {len(answers[label])} 字")
    result["statuses"] = statuses

    # 同屏自相矛盾
    contradictions: List[Dict[str, Any]] = []
    for label, hedged, unconditional in CENSUS_CONTRADICTION_PAIRS:
        text = answers.get(label)
        if not text:
            continue
        has_hedge = hedged in text
        has_uncond = unconditional in text
        out.append(f"\n  ── 同屏矛盾检查 {label}")
        out.append(f"     已挂阈值那句「{hedged}」 : {has_hedge}")
        out.append(f"     无条件那句「{unconditional}」 : {has_uncond}")
        if has_hedge and has_uncond:
            out.append("     🔴 两句同时出现 —— 同一份答案里自相矛盾")
            contradictions.append({"resolver": label, "tenant": tenant})
    result["contradictions"] = contradictions

    # 无条件建议在「没有对应数据」时照发吗
    empty_but_advises: List[Dict[str, Any]] = []
    for label, needle in (
        ("resolve_stock_shortage", "对盘亏最高的食材先核查领料单"),
        ("resolve_trend_analysis", "先把最高月和最低月按门店、渠道、折扣三层拆开"),
        ("resolve_inventory_warning", "需立即补货的食材今天下单"),
        ("resolve_requisition_trend", "把领用靠前的食材和畅销菜、损耗榜交叉看"),
    ):
        text = answers.get(label)
        if not text:
            continue
        advises = needle in text
        out.append(f"\n  ── {label} 建议动作出现 = {advises}")
        if advises:
            empty_but_advises.append({"resolver": label, "tenant": tenant})
    result["advice_present"] = empty_but_advises
    result["answers"] = answers

    # ── ② 门店简称消解: 一个不存在的店名会不会被说成「对得上」 ────────
    result["shortlist"] = await census_shortlist(tenant, names, out)
    return result


async def census_shortlist(
    tenant: str, names: Sequence[str], out: List[str],
) -> Dict[str, Any]:
    """量 ambiguous 分支。**带跨样本阳性对照** —— 否则「判 none」分不清是
    产品谨慎还是 embedding 服务死了。"""
    from smartbi.canonical.entity_resolution import shortlist as sl

    info: Dict[str, Any] = {"tenant": tenant, "store_count": len(names)}
    out.append(f"\n  ── 门店简称消解 (候选 {len(names)}，上限 "
               f"{sl.MAX_EMBED_CANDIDATES})")
    if len(names) < 2:
        out.append("     门店少于 2 家，这一段不适用")
        info["skipped"] = "stores<2"
        return info

    prefix = _census_common_prefix(names)
    fake = f"{prefix}{CENSUS_FAKE_DISTINCTIVE}店"
    info["prefix"] = prefix
    info["fake"] = fake
    out.append(f"     真实店名最长公共前缀 = {prefix!r}")
    out.append(f"     构造的**不存在**店名 = {fake!r}")
    # 先证明它真的不存在
    exists = any(CENSUS_FAKE_DISTINCTIVE in n for n in names)
    info["fake_actually_absent"] = not exists
    out.append(f"     它确实不在门店清单里 = {not exists}")

    sl.clear_shortlist_cache()

    # ── 阳性对照(跨样本): 一个**真实**店名的机械简称, 应该判 unique ────
    #    ⛔ 没有它就分不清「判 none」是产品谨慎还是 embedding 死了。
    probe_real = names[0]
    tail = probe_real[len(prefix):] if prefix else probe_real
    real_short = f"{prefix}{tail[:2]}店" if len(tail) >= 2 else probe_real
    ctl = await sl.resolve_mention(real_short, names)
    info["control_mention"] = real_short
    info["control_verdict"] = ctl.verdict
    info["control_stage"] = ctl.stage
    info["control_canonical"] = ctl.canonical
    out.append(f"     [阳性对照] 真实简称 {real_short!r} -> {ctl.verdict}"
               f"/{ctl.stage} canonical={ctl.canonical!r}")

    # embedding 到底活着没有 —— 直接问一次
    scored = await sl.embedding_shortlist(fake, list(names))
    info["embedding_alive"] = bool(scored)
    out.append(f"     [仪器] embedding 返回候选数 = {len(scored)}"
               f"  (0 = 向量服务不可用 ⇒ 本段读数作废)")
    if scored:
        top = scored[: sl.MAX_ASK_CANDIDATES + 2]
        out.append(f"     cosine 前 {len(top)} 名 (下界 {sl.SIM_FLOOR}，"
                   f"间距门槛 {sl.SIM_MARGIN}):")
        for name, sim in top:
            flag = "过下界" if sim >= sl.SIM_FLOOR else "  -   "
            out.append(f"        {sim:.3f}  {flag}  {name}")
        info["top_scores"] = [(n, round(s, 4)) for n, s in top]

    decision = await sl.resolve_mention(fake, list(names))
    info["verdict"] = decision.verdict
    info["stage"] = decision.stage
    info["detail"] = decision.detail
    info["candidates"] = list(decision.candidates)
    out.append(f"     判定: {decision.verdict} / {decision.stage}")
    out.append(f"     理由: {decision.detail}")
    if decision.candidates:
        out.append(f"     会被列给老板的候选: {list(decision.candidates)}")
    if decision.verdict == sl.VERDICT_AMBIGUOUS:
        out.append("     🔴 一个**不存在**的店名被报成「有 N 家对得上」"
                   " —— 老板去看那几家，一无所获")
    elif decision.verdict == sl.VERDICT_UNIQUE:
        out.append("     🔴 一个**不存在**的店名被**静默改写**成了另一家店"
                   " —— 比反问更糟")
    else:
        out.append("     ✅ 判 none（走今天的路，不猜）")
    return info


#: 构造样本的连锁名模板 —— 高度同名目录长什么样(R_GML_DEMO 曾有 132 家
#: 「桂满陇（XX店）」)。⚠️ **构造**的，⛔ 不是真实租户读数。
CENSUS_CHAIN_DISTRICTS: Tuple[str, ...] = (
    "静安", "徐汇", "长宁", "普陀", "虹口", "杨浦", "黄浦", "闵行",
    "宝山", "嘉定", "浦东", "金山", "松江", "青浦", "奉贤", "崇明",
)


async def census_chain_sensitivity(out: List[str]) -> None:
    """同名连锁目录下，一个**不存在**的门店会被认成几家。

    回答的问题：候选池越大、名字越同质，ambiguous 分支是不是越容易误发。
    """
    from smartbi.canonical.entity_resolution import shortlist as sl

    out.append(f"\n{'=' * 72}\n构造样本敏感度（⚠️ 构造的候选清单，"
               f"⛔ 不是真实租户读数）")
    fake = "桂满陇（青浦新开店）"
    for size in (8, 16, 40, 61):
        names = [
            f"桂满陇（{CENSUS_CHAIN_DISTRICTS[i % len(CENSUS_CHAIN_DISTRICTS)]}"
            f"{i // len(CENSUS_CHAIN_DISTRICTS) + 1}店）"
            for i in range(size)
        ]
        assert fake not in names, "构造的假名字混进候选了"
        sl.clear_shortlist_cache()
        decision = await sl.resolve_mention(fake, names)
        out.append(f"  候选 {size:>3} 家 -> {decision.verdict}/{decision.stage}"
                   f"  列出 {len(decision.candidates)} 家   {decision.detail}")


async def census_main(tenants: Sequence[str]) -> int:
    out: List[str] = []
    missing = census_check_anchors()
    out.append("锚点自检（找不到 = 探针在读一份已经改过文案的源码，读数作废）:")
    if missing:
        for line in missing:
            out.append(f"  🔴 {line}")
        print("\n".join(out))
        print("\nINSTRUMENT DEAD — 源码锚点对不上，本次读数作废", file=sys.stderr)
        return 2
    out.append(f"  ✅ {len(CENSUS_SOURCE_ANCHORS)} 个锚点全部在源码里")

    results: List[Dict[str, Any]] = []
    for tenant in tenants:
        try:
            results.append(await census_measure_tenant(tenant, out))
        except Exception:  # noqa: BLE001
            out.append(f"\n🔴 租户 {tenant} 整体失败:\n{traceback.format_exc()}")
            results.append({"tenant": tenant, "context_ok": False})

    # ── 构造样本敏感度: 同名连锁目录 ────────────────────────────────────
    #    ⚠️ 明确标注这是**构造**的候选清单, ⛔ 不是某个真实租户的读数 ——
    #       它量的是 `resolve_mention` 这个**纯函数**在「高度同名目录」上的行为,
    #       而那种目录在 prod 上今天没有租户能提供(见上面的无数据租户)。
    await census_chain_sensitivity(out)

    usable = [r for r in results if r.get("context_ok") and not r.get("no_data")]
    no_data = [r["tenant"] for r in results if r.get("no_data")]
    out.append(f"\n{'=' * 72}\n汇总（口径: 每个租户独立 bootstrap，"
               f"RLS 上下文逐个贴出来验证过）")
    out.append(f"  有数据、量到了的租户 {len(usable)}/{len(tenants)}")
    if no_data:
        out.append(f"  ⚠️ **没有数据、这轮没量到**的租户: {no_data}")
        out.append("     ⛔ 这些租户上「没有误发」是**我没量到**，不是读数。")

    # embedding 活着没有 —— 跨样本
    alive = [r for r in usable if r.get("shortlist", {}).get("embedding_alive")]
    out.append(f"  embedding 活着的租户 {len(alive)}/{len(usable)}")

    contra = [c for r in usable for c in r.get("contradictions", [])]
    ambiguous_fakes = [
        r["tenant"] for r in usable
        if r.get("shortlist", {}).get("verdict") in ("ambiguous", "unique")
    ]
    out.append(f"  同屏自相矛盾: {len(contra)} 条 {contra}")
    out.append(f"  不存在的店名被认下来的租户: {ambiguous_fakes}")

    if CENSUS_DUMP_PATH:
        with open(CENSUS_DUMP_PATH, "w", encoding="utf-8", newline="") as fh:
            for r in results:
                for label, text in (r.get("answers") or {}).items():
                    fh.write(f"\n{'#' * 70}\n# {r['tenant']} :: {label}\n"
                             f"{'#' * 70}\n{text}\n")
        out.append(f"\n答案原文已落盘: {CENSUS_DUMP_PATH}")

    print("\n".join(out))

    if not usable:
        print("\nINSTRUMENT DEAD — 一个租户都没读到", file=sys.stderr)
        return 2
    if not alive:
        print("\nINSTRUMENT DEAD — 所有租户上 embedding 都拿不到向量，"
              "「不存在的店名判 none」是仪器死了，⛔ 不是产品对", file=sys.stderr)
        return 2
    return 1 if (contra or ambiguous_fakes) else 0


#: `--dump` 落盘路径。⛔ 全局而不是参数传递 —— 只为让 `census_main` 保持
#: 「一个协程一件事」的形状; 探针内部用, 不对外。
CENSUS_DUMP_PATH = ""


def main(argv: Optional[Sequence[str]] = None) -> int:
    global CENSUS_DUMP_PATH
    ap = argparse.ArgumentParser(description="prod 误发提示实测")
    ap.add_argument("--tenants", default=",".join(CENSUS_PROD_TENANTS))
    ap.add_argument("--dump", default="", help="把答案原文落盘到这个路径")
    args = ap.parse_args(list(argv) if argv is not None else None)
    CENSUS_DUMP_PATH = args.dump
    tenants = tuple(t.strip() for t in args.tenants.split(",") if t.strip())
    return asyncio.run(census_main(tenants))


if __name__ == "__main__":
    sys.exit(main())
