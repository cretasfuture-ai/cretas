"""Gold layer HTTP read endpoints.

Week 4 Phase B v0 of Unified Data Layer v1 spec (§5).

v1.1 pilot: one endpoint (`/finance-summary`) that serves the 财务报表
page's data from `agg_daily`. More queries will be added as other
downstream modules cut over.

Auth / tenant scope
-------------------
Routes require the caller to already have `tenant_ctx.current_factory_id`
set — the auth middleware does this from the JWT. The `factory_id`
query param is a double-check (must match) but RLS is the enforcement
mechanism.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import date, datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel

from smartbi.config import get_pg_pool
from smartbi.gold import (
    channel_breakdown,
    daily_trend,
    data_range,
    discount_breakdown,
    finance_summary,
    kpi_summary,
    member_profile,
    member_rfm,
    order_type_mix,
    peak_hours,
    staff_ranking,
    top_products,
    trend_bundle,
    void_audit,
    void_rate,
    zone_efficiency,
)
from smartbi.gold.gold_read_cache import GoldReadCache, compute_cache_key
from smartbi.tenant_ctx import INTERNAL_SENTINEL, get_factory_id, set_factory_id
from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES, strip_price_for_role

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/gold", tags=["Gold Reads"])

# Module-level cache wrapper. The class is a thin stateless wrapper over the
# asyncpg pool; we lazily bind it to the (singleton) pool on first use so the
# pool isn't created at import time.
_gold_read_cache: Optional[GoldReadCache] = None


def _get_cache(pool) -> GoldReadCache:
    global _gold_read_cache
    if _gold_read_cache is None or _gold_read_cache._pool is not pool:
        _gold_read_cache = GoldReadCache(pool)
    return _gold_read_cache


# Gold-specific money keys not matched by the shared `_MONEY_PATTERN`. The
# shared regex deliberately excludes bare ``value`` / ``per`` tokens to avoid
# false-positives like ``store_count`` / ``items_per_bill``; in Gold response
# shapes ``avg_bill_value`` (元/单) and ``avg_per_capita`` (元/客) are
# unambiguously monetary so we strip them here as a thin local extension.
_GOLD_EXTRA_MONEY_KEYS: frozenset[str] = frozenset({
    "avg_bill_value",
    "avg_per_capita",
    # trend-bundle weekday/weekend average daily REVENUE (元) — camelCase keys
    # not matched by the shared _MONEY_PATTERN, so strip them here too.
    "weekdayAvg",
    "weekendAvg",
    # member-profile (会员储值+画像) 储值余额/充值本金/赠送金 — "balance" /
    # "principal" / "bonus" aren't matched by the shared _MONEY_PATTERN
    # (which targets price/amount/revenue/cost/... substrings), so strip
    # them here too. member_count / tier / birth_month are NOT money and
    # stay visible.
    "total_balance",
    "principal",
    "bonus",
    # member-rfm (CRM P0 会员 RFM) 累计消费金额 — "total_cum_spend" /
    # "avg_cum_spend" aren't matched by the shared _MONEY_PATTERN either
    # (no "amount"/"spend"+"ing" substring), so strip them here too.
    # avg_spend_interval is a day-count, NOT money, and stays visible.
    "total_cum_spend",
    "avg_cum_spend",
})


def _get_role(request: Request) -> Optional[str]:
    """Role string set by JWTAuthMiddleware (auth_middleware.py)."""
    return getattr(request.state, "role", None)


def _strip_extras(node: Any) -> None:
    """Depth-first null of gold-specific money keys not caught by the shared regex."""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in _GOLD_EXTRA_MONEY_KEYS and not isinstance(v, (dict, list)):
                node[k] = None
            else:
                _strip_extras(v)
    elif isinstance(node, list):
        for item in node:
            _strip_extras(item)


def _apply_rbac_strip(result: Any, role: Optional[str]) -> Any:
    """Mirror PR #435 pattern for the Gold layer.

    Whitelisted roles (PRICE_VIEW_ROLES) get the raw payload; everyone else
    gets monetary fields nulled in place. Returns the same ``result`` reference
    for caller convenience.
    """
    if role and role in PRICE_VIEW_ROLES:
        return result
    strip_price_for_role(result, role)
    _strip_extras(result)
    return result


def _parse_date(s: str, field: str) -> date:
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"{field} must be YYYY-MM-DD, got {s!r}",
        )


#: 演示账号 → 数据租户 的重映射。**2026-08-17 起为空。**
#:
#: 🔴 原本这里写着 `"DEMO_REST": "RES_3101_009"`，注释称后者是「the rich demo
#:    factory」。当天逐环实测下来，那个描述是错的：
#:
#:      RES_3101_009 的租户名是 **QHJ_PROD**，625,764 笔 POS、跨 2025-01-01 至
#:      2026-08-16，门店是「青花椒南方百联店 / 大丸百货店 / 徐汇光启城店」这类
#:      高度具体的真实门店 —— **是真客户的生产租户，不是 demo 工厂。**
#:
#:    而 `POST /api/mobile/auth/demo-login` 在 `JwtAuthInterceptor` 的免鉴权白名单
#:    里，prod 上 `cretas.demo.enabled` 与 `cretas.demo.rest.factory-id` 都没有覆盖
#:    (走 jar 默认 true / DEMO_REST)。⇒ 这条别名把**免密端点**接到了**真客户的
#:    营收、门店排行、毛利、客单价**上。
#:    ⚠️ Java 侧 `MobileAuthServiceImpl` 的注释写着「2026-08-05 停用、fallback 默认值
#:       同步清空」——**那句注释是过期的**，打包的 application.properties 仍是默认开启。
#:
#: ⇒ owner 裁定(2026-08-17)：**只用 MOCK_REST**。演示不再借道任何真客户租户。
#: ⛔ 将来要给演示账号配数据源，只能指向**生成数据**的租户(如 MOCK_REST)，
#:    绝不指向生产租户 —— 判据很简单：**那个租户的门店名是不是真实存在的店。**
DEMO_GOLD_TENANT_ALIASES: dict = {}


def _resolve_tenant(factory_id: Optional[str]) -> str:
    """Factor out the 'use JWT tenant unless caller explicitly matches' guard."""
    tenant = get_factory_id()
    if not tenant:
        raise HTTPException(status_code=401, detail="tenant context not set")
    fid = factory_id or tenant
    if fid != tenant:
        raise HTTPException(
            status_code=403,
            detail=f"factory_id query param {fid!r} doesn't match JWT tenant {tenant!r}",
        )
    resolved = DEMO_GOLD_TENANT_ALIASES.get(fid, fid)
    # 🔒 RLS chokepoint fix (2026-07-11): the alias remap rewrites the query's
    # WHERE factory_id = resolved, but the RLS GUC (app.factory_id) is applied by
    # the pool setup callback from the ContextVar, which still held the un-remapped
    # JWT tenant (DEMO_REST). RLS policy `factory_id = current_setting('app.factory_id')`
    # then filtered out every resolved-tenant row → zero results on kpi-summary /
    # finance-summary / trend-bundle / daily-trend / data-range / review-*.
    # store-kpi-dashboard escaped only because compute_store_kpi_dashboard re-set
    # the ContextVar itself. Re-point the RLS context to the resolved tenant HERE,
    # at the single chokepoint, so every downstream pool.acquire() sees the matching
    # GUC. Safe: the JWT-match guard above already ran; `resolved` is either the same
    # JWT tenant (non-demo → no-op) or the whitelisted DEMO_REST→RES_3101_009 demo
    # elevation. Per-task ContextVar; middleware resets on request unwind → no leak.
    if resolved != tenant:
        set_factory_id(resolved)
    return resolved


def _resolve_tiered_tenant(factory_id: Optional[str]) -> str:
    """Like ``_resolve_tenant`` (belt-and-suspenders JWT/internal-tenant
    match) but WITHOUT the ``DEMO_GOLD_TENANT_ALIASES`` DEMO_REST ->
    RES_3101_009 remap.

    The restaurant tiered-intent path (``parse_restaurant_query`` /
    ``resolve_by_code`` / the Answer Contract) already handles ``DEMO_REST``
    directly against its own seeded restaurant-ops Gold data (V20260706_01
    migration) -- matching chat.py's existing ``factory_id_hdr`` usage at its
    3 SSE call sites, which never aliases either. Aliasing here would look up
    the WRONG tenant's restaurant-ops rows (Phase 2 design section 4: "传原始
    factoryId（DEMO_REST 在 Python smartbi 有自己的 gold seed，与 chat.py 路径
    一致；不做 RES_3101_009 映射）").
    """
    tenant = get_factory_id()
    if not tenant:
        raise HTTPException(status_code=401, detail="tenant context not set")
    fid = factory_id or tenant
    if fid != tenant:
        raise HTTPException(
            status_code=403,
            detail=f"factory_id {fid!r} doesn't match tenant {tenant!r}",
        )
    return fid


def _parse_range(start_date: Optional[str], end_date: Optional[str]) -> tuple:
    """Parse an optional date range.

    WS1: dates are optional — a missing bound means "open" (= all history on
    that side). Only validate the ordering when BOTH bounds are present.
    """
    start = _parse_date(start_date, "start_date") if start_date else None
    end = _parse_date(end_date, "end_date") if end_date else None
    if start is not None and end is not None and start > end:
        raise HTTPException(status_code=400, detail="start_date > end_date")
    return start, end


@router.get("/finance-summary")
async def get_finance_summary(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None, description="belt-and-suspenders; defaults to JWT tenant"),
    top_n_stores: int = Query(10, ge=1, le=100),
):
    """Finance KPI summary from Gold agg_daily.

    Returns: total_revenue, bill_count, avg_bill_value, store_count,
    day_count, top_stores[{store_id, store_name, revenue, bill_count}]

    No cost / profit data yet (Silver doesn't capture costs — Week 5+).
    """
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    role = _get_role(request)
    # Role is in the cache key, so a cached payload is already RBAC-correct
    # for this caller — we cache the POST-strip result and never re-strip.
    cacheable = fid != INTERNAL_SENTINEL
    cache = _get_cache(pool)
    cache_key = compute_cache_key(
        "finance-summary",
        # start/end may be None (all-history). Pass the parsed date's ISO
        # string when present, else None — compute_cache_key collapses None
        # to "" giving a stable, distinct all-history key (no AttributeError
        # on None).
        start.isoformat() if start is not None else None,
        end.isoformat() if end is not None else None,
        role, {"top_n_stores": top_n_stores},
    )
    try:
        if cacheable:
            try:
                hit = await cache.get(fid, cache_key)
                if hit is not None:
                    return hit
            except Exception as ce:  # cache read is best-effort, never fatal
                logger.warning("finance-summary cache get failed: %s", ce)
        result = await finance_summary(
            pool, fid, (start, end), top_n_stores=top_n_stores,
        )
        stripped = _apply_rbac_strip(result, role)
        if cacheable:
            try:
                await cache.put(fid, cache_key, stripped)
            except Exception as ce:  # cache write is best-effort, never fatal
                logger.warning("finance-summary cache put failed: %s", ce)
        return stripped
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("finance-summary failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/daily-trend")
async def get_daily_trend(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
):
    """Daily revenue + bill-count trend for 分析概览 line chart."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await daily_trend(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("daily-trend failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/trend-bundle")
async def get_trend_bundle(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
):
    """趋势分析合一 — one bundle (dailyTrend + weekdayWeekend + monthlyTrend)
    so 趋势分析 page loads in a single round-trip.

    Revenue fields are monetary → RBAC money-strip applies for non price-view
    roles (mirrors /daily-trend). Dates optional (省略 → 全部历史)。
    """
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await trend_bundle(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("trend-bundle failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/top-products")
async def get_top_products(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(10, ge=1, le=100),
    order: str = Query("desc", description="Sort direction: 'desc' for top sellers, 'asc' for slow sellers"),
):
    """Top (or bottom) products by revenue. Uses agg_product which is monthly-grained —
    date range is normalized to the month(s) it touches.

    Pass order=asc to get slow-sellers (慢销菜品) ranked by lowest revenue first."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await top_products(pool, fid, (start, end), top_n=top_n, order=order)
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("top-products failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/channel-breakdown")
async def get_channel_breakdown(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(10, ge=1, le=100),
):
    """Revenue by payment channel. Returns empty channels list if
    fact_pos_payment has no rows for this tenant (EAV not yet wired)."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await channel_breakdown(pool, fid, (start, end), top_n=top_n)
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("channel-breakdown failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/discount-breakdown")
async def get_discount_breakdown(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(10, ge=1, le=100),
):
    """Voucher/coupon usage ranked by amount. Direct aggregate over
    fact_pos_discount (no materialized agg_discount table yet)."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await discount_breakdown(pool, fid, (start, end), top_n=top_n)
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("discount-breakdown failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/order-type-mix")
async def get_order_type_mix(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
):
    """堂食 vs 外卖 revenue split from agg_daily_order_type_meal.order_type.

    NOTE: this is NOT /channel-breakdown — that endpoint shows payment channel
    (微信/美团) which conflates payment method with service mode. This endpoint
    reads the order_type dimension (堂食/外卖) to answer delivery-mix questions."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await order_type_mix(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("order-type-mix failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/peak-hours")
async def get_peak_hours(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
):
    """按小时的营业强度分布 —— 回答"几点最忙"。

    Source: fact_pos_transaction.time (开单时刻)。

    NOTE: 该列并非所有租户/时段都有 (DEMO_REST 1-7 月为空, MOCK_REST 全期有)。
    缺失时返回 hours_available=false + unavailable_reason, **不返回 24 小时全 0**
    —— 把"没记录"渲染成"没生意"是两件不同的事。"""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await peak_hours(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("peak-hours failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/staff-ranking")
async def get_staff_ranking(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(5, ge=1, le=50),
):
    """POS operator ranking by net revenue handled.

    Returns staff list with honest caveat: fact_pos_transaction.staff_id
    records the POS operator (cashier / order-taker), NOT server/waiter
    performance. Results should not be used for server performance reviews."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await staff_ranking(pool, fid, (start, end), top_n=top_n)
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("staff-ranking failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/void-rate")
async def get_void_rate(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
    top_n: int = Query(10, ge=1, le=50),
):
    """撤单率 + 撤单稽核(操作人/原因 Top N) — order void rate + staff audit.

    New restaurant analytics dimension (greenfield). Combines void_rate()
    (void_count/bill_count) and void_audit() (staff+reason breakdown) into
    one response so the frontend card can render both the KPI and the audit
    table from a single request. No monetary fields — RBAC strip is a no-op
    here but applied for consistency with every other Gold read endpoint."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        rate_result = await void_rate(pool, fid, (start, end))
        audit_result = await void_audit(pool, fid, (start, end), top_n=top_n)
        combined = {**rate_result, **audit_result}
        return _apply_rbac_strip(combined, _get_role(request))
    except Exception as e:
        logger.exception("void-rate failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")

async def _query_analysis_results_batch(
    pool,
    factory_id: str,
    template_codes: list,
    upload_id: Optional[int],
) -> dict:
    """Core batch resolver used by the /analysis-results endpoint.

    Split out as a module-level helper so unit tests can exercise the
    query shape directly (FastAPI request/response handling is separate).

    Returns dict with:
      - items: list of template rows (one per code that resolved)
      - missing_codes: codes not found in the pinned upload_id (only
        populated when upload_id is given)
      - never_materialized_codes: codes with zero rows in the entire
        smart_bi_pg_analysis_results for this factory
    """
    if not template_codes:
        return {"items": [], "missing_codes": [], "never_materialized_codes": []}

    async with pool.acquire() as conn:
        if upload_id is not None:
            # Strict: only this upload. Codes not in upload → missing_codes.
            rows = await conn.fetch(
                """
                SELECT r.upload_id, r.template_code, r.domain, r.analysis_type,
                       r.analysis_result, r.chart_configs, r.kpi_values,
                       r.insights, r.created_at,
                       u.file_name  AS upload_label,
                       u.created_at AS upload_created_at
                  FROM smart_bi_pg_analysis_results r
                  JOIN smart_bi_pg_excel_uploads u ON u.id = r.upload_id
                 WHERE r.factory_id    = $1
                   AND r.upload_id     = $2
                   AND r.template_code = ANY($3)
                """,
                factory_id, upload_id, template_codes,
            )
            items = [_row_to_result(r) for r in rows]
            found = {i["template_code"] for i in items}
            # Among requested codes: found-in-upload vs not-found-in-upload vs never-seen.
            ever_seen_rows = await conn.fetch(
                """
                SELECT DISTINCT template_code
                  FROM smart_bi_pg_analysis_results
                 WHERE factory_id    = $1
                   AND template_code = ANY($2)
                """,
                factory_id, template_codes,
            )
            ever_seen = {r["template_code"] for r in ever_seen_rows}
            missing = sorted(ever_seen - found)
            never = sorted(set(template_codes) - ever_seen)
            return {
                "items": items,
                "missing_codes": missing,
                "never_materialized_codes": never,
            }

        # resolve_latest: DISTINCT ON per code, ordered by created_at DESC.
        rows = await conn.fetch(
            """
            SELECT DISTINCT ON (r.template_code)
                   r.upload_id, r.template_code, r.domain, r.analysis_type,
                   r.analysis_result, r.chart_configs, r.kpi_values,
                   r.insights, r.created_at,
                   u.file_name  AS upload_label,
                   u.created_at AS upload_created_at
              FROM smart_bi_pg_analysis_results r
              JOIN smart_bi_pg_excel_uploads u ON u.id = r.upload_id
             WHERE r.factory_id    = $1
               AND r.template_code = ANY($2)
             ORDER BY r.template_code, r.created_at DESC
            """,
            factory_id, template_codes,
        )
        items = [_row_to_result(r) for r in rows]
        found = {i["template_code"] for i in items}
        never = sorted(set(template_codes) - found)
        return {
            "items": items,
            "missing_codes": [],
            "never_materialized_codes": never,
        }


@router.get("/analysis-results")
async def get_analysis_results(
    request: Request,
    upload_id: Optional[int] = Query(None, description="Pin to one upload; omit to resolve latest per code"),
    template_code: Optional[str] = Query(None, description="Single template; alias for template_codes=<code>"),
    template_codes: Optional[str] = Query(None, description="CSV of template codes (1..20)"),
    factory_id: Optional[str] = Query(None, description="belt-and-suspenders; defaults to JWT tenant"),
):
    """Read materialized template results from smart_bi_pg_analysis_results.

    Query modes:
    - template_codes=<csv> : batch. Resolves per-code "latest upload where
      this code exists" unless upload_id is also given.
    - template_code=<single> : alias for template_codes=<single> — takes
      the same batch path.
    - upload_id=<id> without codes : legacy list-by-upload mode; returns
      all templates materialized for that upload.

    Response shapes:
    - template_codes or template_code given (batch path):
        { items: [...], missing_codes: [...], never_materialized_codes: [...] }
    - upload_id only (legacy by-upload list):
        { items: [...] }
    - Neither codes nor upload_id:
        HTTP 400
    """
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()

    # Normalize: template_code → template_codes
    codes_list: list = []
    if template_codes:
        codes_list = [c.strip() for c in template_codes.split(",") if c.strip()]
    elif template_code:
        codes_list = [template_code.strip()]

    if codes_list:
        if len(codes_list) > 20:
            raise HTTPException(
                status_code=400,
                detail=f"template_codes max 20 per call, got {len(codes_list)}",
            )
        try:
            result = await _query_analysis_results_batch(
                pool, fid, codes_list, upload_id,
            )
            return _apply_rbac_strip(result, _get_role(request))
        except HTTPException:
            raise
        except Exception as e:
            logger.exception("analysis-results batch failed: %s", e)
            raise HTTPException(status_code=500, detail=f"Analysis read failed: {e}")

    # Legacy: upload_id without codes → list all templates for the upload.
    if upload_id is None:
        raise HTTPException(
            status_code=400,
            detail="Provide template_codes= or upload_id=",
        )
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT DISTINCT ON (r.template_code)
                       r.upload_id, r.template_code, r.domain, r.analysis_type,
                       r.analysis_result, r.chart_configs, r.kpi_values,
                       r.insights, r.created_at,
                       u.file_name AS upload_label, u.created_at AS upload_created_at
                  FROM smart_bi_pg_analysis_results r
                  JOIN smart_bi_pg_excel_uploads u ON u.id = r.upload_id
                 WHERE r.factory_id = $1
                   AND r.upload_id  = $2
                   AND r.template_code IS NOT NULL
                 ORDER BY r.template_code, r.created_at DESC
                """,
                fid, upload_id,
            )
            return _apply_rbac_strip(
                {"items": [_row_to_result(r) for r in rows]},
                _get_role(request),
            )
    except Exception as e:
        logger.exception("analysis-results by-upload failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Analysis read failed: {e}")


def _row_to_result(row) -> dict:
    """Parse JSONB fields (asyncpg returns them as strings) + date isoformat."""
    import json as _json

    def _j(v):
        if v is None:
            return None
        if isinstance(v, str):
            try:
                return _json.loads(v)
            except ValueError:
                return v
        return v

    def _iso(dt):
        return dt.isoformat() if dt is not None else None

    return {
        "upload_id": int(row["upload_id"]),
        "template_code": row["template_code"],
        "domain": row["domain"],
        "analysis_type": row["analysis_type"],
        "analysis_result": _j(row["analysis_result"]),
        "chart_configs": _j(row["chart_configs"]),
        "kpi_values": _j(row["kpi_values"]),
        "insights": _j(row["insights"]),
        "created_at": _iso(row["created_at"]),
        # upload_label + upload_created_at may not exist for the old
        # single-template caller path. Use row.get()-style access via try.
        "upload_label": row["upload_label"] if "upload_label" in row.keys() else None,
        "upload_created_at": _iso(row["upload_created_at"]) if "upload_created_at" in row.keys() else None,
    }


@router.get("/kpi-summary")
async def get_kpi_summary(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None),
):
    """Compact KPI card data — feeds both 分析概览 + KPI看板 headers.
    One trip to agg_daily; top rankings live on separate routes."""
    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    pool = await get_pg_pool()
    try:
        result = await kpi_summary(pool, fid, (start, end))
        return _apply_rbac_strip(result, _get_role(request))
    except Exception as e:
        logger.exception("kpi-summary failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/data-range")
async def get_data_range(
    request: Request,
    factory_id: Optional[str] = Query(None),
):
    """Actual [min,max] date span of a factory's Gold data (agg_daily).

    Dashboard uses this to default a restaurant tenant's date picker to its
    real data window instead of the empty current month. No money fields →
    no RBAC strip needed."""
    fid = _resolve_tenant(factory_id)
    pool = await get_pg_pool()
    try:
        return await data_range(pool, fid)
    except Exception as e:
        logger.exception("data-range failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


@router.get("/store-kpi-dashboard")
async def get_store_kpi_dashboard(
    request: Request,
    start_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    end_date: Optional[str] = Query(None, description="YYYY-MM-DD inclusive; 省略=全部历史"),
    factory_id: Optional[str] = Query(None, description="belt-and-suspenders; defaults to JWT tenant"),
):
    """店长经营 6-KPI 看板 (single-store / factory-level aggregation).

    Returns {store_name, start_date, end_date, kpis:[6 cards], overall_health}.
    Each card: {key, label, unit, value, rawValue, status (GOOD/WARNING/
    CRITICAL/INSUFFICIENT/NO_TARGET), money, detail?}.

    RBAC: the compute function itself nulls money KPIs (日营收 / 客单价) for
    roles outside PRICE_VIEW_ROLES (NOT 0 — Rule 4 missing-vs-zero). Rates
    (毛利率 / 食材成本率 / 目标完成率) and counts (订单数) stay visible. So we
    do NOT re-apply the generic _apply_rbac_strip here (it would false-positive
    the rate cards whose labels contain 毛利 / 成本).
    """
    from smartbi.services.restaurant.sections.store_kpi_dashboard import (
        compute_store_kpi_dashboard,
    )

    fid = _resolve_tenant(factory_id)
    start, end = _parse_range(start_date, end_date)
    role = _get_role(request)
    pool = await get_pg_pool()
    try:
        data = await compute_store_kpi_dashboard(
            pool, fid, (start, end), role, store_name=None
        )
        if data is None:
            # Honest empty: no agg_daily sales rows for this factory.
            return {
                "store_name": None,
                "start_date": start.isoformat() if start else None,
                "end_date": end.isoformat() if end else None,
                "kpis": [],
                "overall_health": "GOOD",
                "empty": True,
                "message": "未检测到经营数据，请先上传 POS 销售/财务数据",
            }
        return data
    except Exception as e:
        logger.exception("store-kpi-dashboard failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Gold query failed: {e}")


# =============================================================================
# Phase 2 Java-entry delegate gate (2026-07-07)
