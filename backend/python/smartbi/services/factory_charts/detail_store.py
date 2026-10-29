"""PostgreSQL-backed cross-worker store for factory chart detail contexts.

This is deliberately a store, rather than a fallback cache: if SmartBI's
database is unavailable the caller receives a 503 and never advertises a
context which another uvicorn worker cannot read.
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Sequence

from smartbi import config
from . import details
from .newstock_buckets import CONTRACT_KEY, prepare_rows as prepare_stock_rows
from . import cost_authorization


CONTEXT_TTL_SECONDS = 300
# Do not hand an expiring token back to the UI as a reusable detail context.
# A fresh context is cheap and the database grants INSERT/DELETE only; renewal
# must therefore be represented by a new row, never an UPDATE.
CONTEXT_REUSE_MIN_TTL_SECONDS = 60
MAX_CONTEXTS = 256
MAX_TOTAL_BYTES = 32 * 1024 * 1024


def _owner(value: Any) -> tuple[str, Any, str]:
    parsed = details._Owner.from_value(value)
    return parsed.factory_id, parsed.user_id, parsed.role


def _json_load(value: Any) -> Any:
    return json.loads(value) if isinstance(value, str) else value


def _iso(value: Any) -> str:
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def _has_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, float) and math.isnan(value):
        return False
    return True


def _drop_empty_manufacturing_cost_columns(
    rows: Sequence[Mapping[str, Any]], columns: Sequence[str]
) -> tuple[tuple[dict[str, Any], ...], tuple[str, ...]]:
    """Remove cost fields which are empty in the rows being frozen.

    The check must run after candidate-unit filtering as well as on the raw
    dataset.  A mixed-unit dataset may have a real cost in one unit while the
    selected candidate's rows contain only null cost values.
    """
    empty = {
        column for column in columns
        if cost_authorization.is_manufacturing_cost_column(column)
        and not any(_has_value(row.get(column)) for row in rows)
    }
    if not empty:
        return tuple(dict(row) for row in rows), tuple(columns)
    return (
        tuple({key: value for key, value in row.items() if key not in empty} for row in rows),
        tuple(column for column in columns if column not in empty),
    )


async def _pool_or_error():
    try:
        pool = await config.get_pg_pool()
    except Exception as exc:
        raise details.DetailError(503, "详情共享存储暂不可用") from exc
    if pool is None:
        raise details.DetailError(503, "详情共享存储暂不可用")
    return pool


async def _bind(conn: Any, owner: tuple[str, Any, str]) -> None:
    factory, user, role = owner
    await conn.execute("SELECT set_config('app.factory_id',$1,true)", str(factory))
    await conn.execute("SELECT set_config('app.user_id',$1,true)", str(user))
    await conn.execute("SELECT set_config('app.actor_role',$1,true)", str(role))


def _prepared(candidates: Sequence[Mapping[str, Any]], datasets: Sequence[Any], request: Any, owner: tuple[str, Any, str]):
    by_source = {}
    for dataset in datasets:
        _, source, _ = details._frame_of(dataset)
        if source:
            by_source[source] = dataset
    prepared = {}
    for source, dataset in by_source.items():
        try:
            raw, exact, _ = details._frame_of(dataset)
            if exact != source:
                continue
            rows, columns = details._canonical_rows(raw, request)
            # A quantity-only/fully denied merge may retain the original cost
            # field names with every value null.  Do not turn that into a
            # legacy cost-sensitive snapshot; remove empty cost columns before
            # classification and persist only the non-price facts.
            rows, columns = _drop_empty_manufacturing_cost_columns(rows, columns)
            prices, dimensions = details._classify(columns)
            unit_col = next((c for c in columns if details._base_name(c) in details._UNIT_NAMES), None)
            units = {str(row.get(unit_col, "")).strip() for row in rows} if unit_col else set()
            prepared[source] = (rows, columns, prices, dimensions, unit_col, units)
        except details.DetailError:
            # One malformed source cannot suppress valid recommendations from
            # the remaining datasets in the same response.
            continue
    for candidate in candidates:
        item = copy.deepcopy(dict(candidate))
        # Incoming annotations can only describe an earlier owner or snapshot.
        # Recompute them from the trusted scope, even when quota admission fails.
        for field in ('detailContext', 'detailSnapshotKey', 'detailStatus'):
            item.pop(field, None)
        source = item.get("source")
        base = prepared.get(source)
        if not base:
            yield item, None
            continue
        rows, columns, prices, dimensions, unit_col, units = base
        if not rows:
            yield item, None
            continue
        stock_contract = item.get("engine", {}).get(CONTRACT_KEY) if isinstance(item.get("engine"), Mapping) else None
        if stock_contract is not None:
            expected_kind = {"inventory.stock.age:bar": "age", "inventory.stock.expiry:bar": "expiry"}.get(str(item.get("id", "")))
            candidate_unit = str(item.get("unit", "") or "").rsplit("/", 1)[-1].strip()
            if (not isinstance(stock_contract, Mapping) or expected_kind is None
                    or candidate_unit != "批" or stock_contract.get("bucketKind") != expected_kind):
                yield item, None
                continue
            prepared_stock, bucket_column = prepare_stock_rows(rows, stock_contract)
            if not prepared_stock or not bucket_column:
                yield item, None
                continue
            rows = prepared_stock
            if bucket_column not in columns:
                columns = tuple(columns) + (bucket_column,)
            dimensions = tuple(dict.fromkeys((*dimensions, bucket_column)))
            unit_col = None
            units = set()
        unit = str(item.get("unit", "") or "")
        if "/" in unit:
            unit = unit.rsplit("/", 1)[-1]
        unit = unit.strip()
        engine_meta = item.get("engine") if isinstance(item.get("engine"), Mapping) else {}
        trusted_quantity_rows = engine_meta.get("detailUnitPolicy") == "trusted_quantity_rows"
        if unit_col and not trusted_quantity_rows and not details._currency_quantity_independent(item, by_source[source]):
            quantity_unit = str(item.get("sourceQuantityUnit", item.get("quantityUnit", "")) or "").strip()
            engine_meta = item.get("engine") if isinstance(item.get("engine"), Mapping) else {}
            quantity_unit = quantity_unit or str(engine_meta.get("sourceQuantityUnit", "") or "").strip()
            if unit in units:
                rows = tuple(row for row in rows if str(row.get(unit_col, "")).strip() == unit)
            elif quantity_unit and quantity_unit in units:
                rows = tuple(row for row in rows if str(row.get(unit_col, "")).strip() == quantity_unit)
            elif len(units - {""}) == 1:
                only_unit = next(iter(units - {""}), "")
                rows = tuple(row for row in rows if str(row.get(unit_col, "")).strip() == only_unit)
            else:
                yield item, None
                continue
        rows, columns = _drop_empty_manufacturing_cost_columns(rows, columns)
        retained_columns = set(columns)
        prices = frozenset(column for column in prices if column in retained_columns)
        dimensions = tuple(column for column in dimensions if column in retained_columns)
        financial = details._is_financial(str(source), item)
        engine_meta = item.get("engine") if isinstance(item.get("engine"), Mapping) else {}
        point_dimension = engine_meta.get("pointDimension")
        if point_dimension is not None and point_dimension not in dimensions:
            yield item, None
            continue
        def scope_value(snake, camel=None):
            if isinstance(request, Mapping):
                return request.get(snake, request.get(camel) if camel else None)
            return getattr(request, snake, None)
        scope = {key: scope_value(key, alias) for key, alias in (
            ("source", None), ("upload_id", "uploadId"),
            ("inspection_upload_id", "inspectionUploadId"), ("cost_upload_id", "costUploadId"),
            ("year", None), ("period_type", "periodType"), ("start_month", "startMonth"),
            ("end_month", "endMonth"), ("analysis_subject", "analysisSubject"))}
        dimension_contract = {"fields": list(dimensions), "pointDimension": point_dimension}
        product_columns = [c for c in columns if str(c).strip() in cost_authorization.PRODUCT_ID_ALIASES]
        manufacturing_columns = [c for c in columns if cost_authorization.is_manufacturing_cost_column(c)]
        cost_rows = [row for row in rows if any(_has_value(row.get(column)) for column in manufacturing_columns)]
        cost_product_ids = sorted({value for row in cost_rows for value in cost_authorization.row_product_ids(row)[0]})
        conflict_rows = [row for row in cost_rows if cost_authorization.row_product_ids(row)[1]]
        manufacturing_cost = bool(cost_rows)
        if manufacturing_cost:
            dimension_contract["costGuard"] = {
                "productIds": cost_product_ids,
                "unscoped": (not bool(cost_product_ids)
                              or any(not cost_authorization.row_product_ids(row)[0] for row in cost_rows)),
            }
        payload = {"source": source, "period": details._period_label(request), "unit": unit,
                   "rows": rows, "columns": columns, "scope": scope, "dimensions": dimension_contract}
        size = len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        if size > details.MAX_FRAME_BYTES:
            yield item, None
            continue
        snapshot_key = hashlib.sha256(json.dumps(
            [owner, source, scope, details._period_label(request), unit, rows, columns, dimension_contract],
            ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":")).encode("utf-8")).hexdigest()
        yield item, {
            "source": str(source), "period": details._period_label(request), "unit": unit,
            "rows": rows, "columns": columns, "dimensions": dimensions,
            "dimension_contract": dimension_contract,
            "price_columns": list(prices), "financial": financial, "size": size,
            "snapshot_key": snapshot_key,
        }


async def _register_shared_impl(candidates: Sequence[Mapping[str, Any]], datasets: Sequence[Any], request: Any,
                                owner: Any, can_price: bool) -> list[dict[str, Any]]:
    """Persist contexts/snapshots so any worker can read them."""
    trusted = _owner(owner)
    pool = await _pool_or_error()
    output = []
    prepared = list(_prepared(candidates, datasets, request, trusted))
    async with pool.acquire() as conn:
        async with conn.transaction():
            await _bind(conn, trusted)
            await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended($1,0))", trusted[0])
            await conn.execute("DELETE FROM smart_bi_factory_chart_detail_contexts WHERE factory_id=$1 AND expires_at <= CURRENT_TIMESTAMP", trusted[0])
            await conn.execute(
                """DELETE FROM smart_bi_factory_chart_detail_snapshots s
                   WHERE s.factory_id=$1 AND NOT EXISTS
                     (SELECT 1 FROM smart_bi_factory_chart_detail_contexts c WHERE c.snapshot_id=s.snapshot_id)""",
                trusted[0])
            active = await conn.fetchval("SELECT count(*) FROM smart_bi_factory_chart_detail_contexts WHERE factory_id=$1 AND expires_at > CURRENT_TIMESTAMP", trusted[0])
            used = await conn.fetchval("SELECT COALESCE(sum(size_bytes),0) FROM smart_bi_factory_chart_detail_snapshots WHERE factory_id=$1", trusted[0])
            for item, snapshot in prepared:
                if snapshot is None:
                    item["detailStatus"] = {
                        "state": "unavailable",
                        "message": "当前图表明细不可用",
                    }
                    output.append(item)
                    continue
                # Opaque proof of the exact immutable source snapshot.  It is
                # intentionally separate from detailContext so that the
                # existing context shape remains wire-compatible.
                item["detailSnapshotKey"] = snapshot["snapshot_key"]
                # Shared snapshots are charged once by their immutable digest.
                existing_size = await conn.fetchval(
                    "SELECT size_bytes FROM smart_bi_factory_chart_detail_snapshots WHERE dedup_key=$1",
                    snapshot["snapshot_key"])
                added = 0 if existing_size is not None else snapshot["size"]
                # A context is safe to reuse only when its owner, candidate and
                # complete immutable snapshot all match.  Near-expiry rows are
                # deliberately excluded so the UI receives a useful window.
                reusable = await conn.fetchrow(
                    """SELECT c.context_id,c.expires_at
                       FROM smart_bi_factory_chart_detail_contexts c
                       JOIN smart_bi_factory_chart_detail_snapshots s ON s.snapshot_id=c.snapshot_id
                       WHERE c.factory_id=$1 AND c.user_id=$2 AND c.role=$3
                         AND c.candidate_id=$4 AND s.dedup_key=$5
                         AND c.expires_at > CURRENT_TIMESTAMP + ($6 * interval '1 second')
                       ORDER BY c.expires_at DESC LIMIT 1""",
                    trusted[0], str(trusted[1]), trusted[2], str(item.get("id", "")),
                    snapshot["snapshot_key"], CONTEXT_REUSE_MIN_TTL_SECONDS)
                if reusable is not None:
                    context_id = str(reusable["context_id"])
                    expires = reusable["expires_at"]
                    drilldown = details.point_drilldown_enabled(item)
                    item["detailContext"] = {"id": context_id, "expiresAt": _iso(expires),
                                              "drilldown": drilldown}
                    output.append(item)
                    continue
                if int(active or 0) >= MAX_CONTEXTS or int(used or 0) + added > MAX_TOTAL_BYTES:
                    item["detailStatus"] = {
                        "state": "capacity",
                        "message": "详情上下文容量已满，请稍后重试",
                    }
                    output.append(item)
                    continue
                row = await conn.fetchrow(
                    """INSERT INTO smart_bi_factory_chart_detail_snapshots
                    (factory_id,user_id,role,source,period_label,unit,rows,columns,dimensions,price_columns,financial,size_bytes,dedup_key)
                    VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb,$9::jsonb,$10::jsonb,$11,$12,$13)
                    ON CONFLICT (dedup_key) DO NOTHING RETURNING snapshot_id""",
                    trusted[0], str(trusted[1]), trusted[2], snapshot["source"], snapshot["period"], snapshot["unit"],
                    json.dumps(snapshot["rows"], ensure_ascii=False), json.dumps(snapshot["columns"], ensure_ascii=False),
                    json.dumps(snapshot["dimension_contract"], ensure_ascii=False), json.dumps(snapshot["price_columns"], ensure_ascii=False),
                    snapshot["financial"], snapshot["size"], snapshot["snapshot_key"])
                snapshot_id = row["snapshot_id"] if row else await conn.fetchval(
                    "SELECT snapshot_id FROM smart_bi_factory_chart_detail_snapshots WHERE dedup_key=$1", snapshot["snapshot_key"])
                context_id = secrets.token_urlsafe(24)
                expires = datetime.now(timezone.utc) + timedelta(seconds=CONTEXT_TTL_SECONDS)
                await conn.execute(
                    """INSERT INTO smart_bi_factory_chart_detail_contexts
                    (context_id,snapshot_id,factory_id,user_id,role,candidate_id,title,expires_at)
                    VALUES($1,$2,$3,$4,$5,$6,$7,$8)""",
                    context_id, snapshot_id, trusted[0], str(trusted[1]), trusted[2], str(item.get("id", "")),
                    str(item.get("title", item.get("metric", ""))), expires)
                active = int(active or 0) + 1
                used = int(used or 0) + added
                drilldown = details.point_drilldown_enabled(item)
                item["detailContext"] = {"id": context_id, "expiresAt": expires.isoformat(),
                                          "drilldown": drilldown}
                output.append(item)
    return output


def prepare_shared_metadata(candidates: Sequence[Mapping[str, Any]], datasets: Sequence[Any], request: Any,
                            owner: Any, can_price: bool) -> list[dict[str, Any]]:
    """Attach an opaque snapshot proof without allocating a DB context.

    Catalogue discovery uses this path so every candidate can be opened lazily
    later without consuming the per-factory context budget.  It deliberately
    shares the exact bounded/prepared-row path with registration; candidates
    that fail source, unit, or frame guards receive no fingerprint.
    """
    trusted = _owner(owner)
    output = []
    for item, snapshot in _prepared(candidates, datasets, request, trusted):
        if snapshot is None:
            item["detailStatus"] = {
                "state": "unavailable",
                "message": "点击查看时加载详情",
            }
        else:
            item["detailSnapshotKey"] = snapshot["snapshot_key"]
            item["detailStatus"] = {
                "state": "unavailable",
                "message": "点击查看时加载详情",
            }
        output.append(item)
    return output


async def register_shared(candidates: Sequence[Mapping[str, Any]], datasets: Sequence[Any], request: Any,
                          owner: Any, can_price: bool) -> list[dict[str, Any]]:
    try:
        return await _register_shared_impl(candidates, datasets, request, owner, can_price)
    except details.DetailError:
        raise
    except Exception as exc:
        raise details.DetailError(503, "详情共享存储暂不可用") from exc


async def _load_impl(context_id: Any, owner: Any, auth_request: Any = None):
    if not isinstance(context_id, str) or not context_id:
        raise details.DetailError(400, "详情上下文无效")
    trusted = _owner(owner)
    pool = await _pool_or_error()
    async with pool.acquire() as conn:
        async with conn.transaction():
            await _bind(conn, trusted)
            row = await conn.fetchrow(
                """SELECT c.context_id,c.user_id,c.role,c.candidate_id,c.title,c.expires_at,s.source,s.period_label,
                   s.columns,s.dimensions,s.price_columns,s.financial,s.size_bytes,s.unit
                   FROM smart_bi_factory_chart_detail_contexts c
                   JOIN smart_bi_factory_chart_detail_snapshots s ON s.snapshot_id=c.snapshot_id
                   WHERE c.context_id=$1 AND c.factory_id=$2""",
                context_id, trusted[0])
    if row is None:
        raise details.DetailError(410, "详情上下文已过期或不可用")
    if str(row["user_id"]) != str(trusted[1]) or str(row["role"]) != trusted[2]:
        raise details.DetailError(403, "无权访问该详情上下文")
    expires = row["expires_at"]
    if expires <= datetime.now(timezone.utc):
        raise details.DetailError(410, "详情上下文已过期或不可用")
    dimensions = _json_load(row["dimensions"])
    # Existing snapshots used a bare array. New envelopes preserve the exact
    # candidate point dimension without a schema or permission change.
    dimension_fields = dimensions.get("fields", []) if isinstance(dimensions, Mapping) else dimensions
    point_dimension = dimensions.get("pointDimension") if isinstance(dimensions, Mapping) else None
    cost_guard = dimensions.get("costGuard") if isinstance(dimensions, Mapping) else None
    if cost_guard is not None and not isinstance(cost_guard, Mapping):
        raise details.DetailError(503, "详情成本授权证明无效，请重新分析")
    if point_dimension is not None and point_dimension not in dimension_fields:
        raise details.DetailError(503, "详情快照维度无效，请重新分析")
    prices = frozenset(_json_load(row["price_columns"]))
    column_values = _json_load(row.get("columns")) if hasattr(row, "get") else None
    if not column_values:
        column_values = list(prices)
    manufacturing_price = any(cost_authorization.is_manufacturing_cost_column(c) for c in column_values)
    if manufacturing_price and cost_guard is None:
        raise details.DetailError(403, "旧详情快照缺少成本授权证明，请重新分析")
    if cost_guard is not None:
        if auth_request is None:
            raise details.DetailError(403, "当前请求缺少成本授权")
        guard_ids = cost_guard.get("productIds", [])
        if not isinstance(guard_ids, list) or any(not isinstance(v, str) for v in guard_ids):
            raise details.DetailError(503, "详情成本授权证明无效，请重新分析")
        if not isinstance(cost_guard.get("unscoped"), bool) or (not guard_ids and not cost_guard["unscoped"]):
            raise details.DetailError(503, "详情成本授权证明无效，请重新分析")
        try:
            authority = await cost_authorization.authorize_batches(auth_request, trusted[0], guard_ids,
                                                          unscoped=bool(cost_guard.get("unscoped")))
        except cost_authorization.CostAuthorizationError as exc:
            raise details.DetailError(403, "当前授权不能读取该制造成本详情") from exc
        if not set(guard_ids) <= authority.allowed_product_ids:
            raise details.DetailError(403, "当前授权不能读取该制造成本详情")
        if cost_guard.get("unscoped") is True and not authority.unscoped_cost_allowed:
            raise details.DetailError(403, "当前授权不能读取缺少产品标识的制造成本详情")
        async with pool.acquire() as conn:
            async with conn.transaction():
                await _bind(conn, trusted)
                values = await conn.fetchrow(
                    """SELECT s.rows,s.columns FROM smart_bi_factory_chart_detail_snapshots s
                       JOIN smart_bi_factory_chart_detail_contexts c ON c.snapshot_id=s.snapshot_id
                       WHERE c.context_id=$1 AND c.factory_id=$2""", context_id, trusted[0])
        if values is None:
            raise details.DetailError(410, "详情上下文已过期或不可用")
        rows_value, columns_value = values["rows"], values["columns"]
    else:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await _bind(conn, trusted)
                values = await conn.fetchrow(
                    """SELECT s.rows,s.columns FROM smart_bi_factory_chart_detail_snapshots s
                       JOIN smart_bi_factory_chart_detail_contexts c ON c.snapshot_id=s.snapshot_id
                       WHERE c.context_id=$1 AND c.factory_id=$2""", context_id, trusted[0])
        if values is None:
            raise details.DetailError(410, "详情上下文已过期或不可用")
        rows_value, columns_value = values["rows"], values["columns"]
    frame = details._Frame(
        context_id=context_id, owner=details._Owner.from_value(trusted),
        candidate_id=str(row["candidate_id"]), title=str(row["title"]), source=str(row["source"]),
        period=str(row["period_label"]), rows=tuple(_json_load(rows_value)),
        columns=tuple(_json_load(columns_value)), dimensions=tuple(dimension_fields),
        point_dimension=point_dimension, price_columns=prices,
        financial=bool(row["financial"]), unit_filter=str(row["unit"] or "") or None,
        storage_key=str(row["context_id"]), expires_at=expires.timestamp(), size_bytes=int(row["size_bytes"] or 0))
    if cost_guard is not None:
        frame._cost_guard = dict(cost_guard)
    return frame


async def _load(context_id: Any, owner: Any, auth_request: Any = None):
    try:
        return await _load_impl(context_id, owner, auth_request)
    except details.DetailError:
        raise
    except Exception as exc:
        raise details.DetailError(503, "详情共享存储暂不可用") from exc


async def requires_finance_shared(context_id: str, owner: Any, auth_request: Any = None) -> bool:
    if not isinstance(context_id, str) or not context_id:
        raise details.DetailError(400, "详情上下文无效")
    trusted = _owner(owner)
    pool = await _pool_or_error()
    async with pool.acquire() as conn:
        async with conn.transaction():
            await _bind(conn, trusted)
            row = await conn.fetchrow(
                """SELECT c.user_id,c.role,c.expires_at,s.financial,s.columns,s.dimensions,s.price_columns
                   FROM smart_bi_factory_chart_detail_contexts c
                   JOIN smart_bi_factory_chart_detail_snapshots s ON s.snapshot_id=c.snapshot_id
                   WHERE c.context_id=$1 AND c.factory_id=$2""", context_id, trusted[0])
    if row is None:
        raise details.DetailError(410, "详情上下文已过期或不可用")
    if str(row["user_id"]) != str(trusted[1]) or str(row["role"]) != trusted[2]:
        raise details.DetailError(403, "无权访问该详情上下文")
    if row["expires_at"] <= datetime.now(timezone.utc):
        raise details.DetailError(410, "详情上下文已过期或不可用")
    dimensions = _json_load(row["dimensions"])
    guard = dimensions.get("costGuard") if isinstance(dimensions, Mapping) else None
    prices = frozenset(_json_load(row["price_columns"]))
    column_values = _json_load(row.get("columns")) if hasattr(row, "get") else None
    if not column_values:
        column_values = list(prices)
    if any(cost_authorization.is_manufacturing_cost_column(c) for c in column_values) and guard is None:
        raise details.DetailError(403, "旧详情快照缺少成本授权证明，请重新分析")
    return bool(row["financial"])


async def page_shared(payload: Mapping[str, Any], owner: Any, can_price: bool, finance_allowed: bool, auth_request: Any = None) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise details.DetailError(400, "详情请求无效")
    frame = await _load(payload.get("context_id", payload.get("contextId")), owner, auth_request)
    return details._page_frame(payload, frame, can_price, finance_allowed)


__all__ = ["register_shared", "prepare_shared_metadata", "requires_finance_shared", "page_shared"]
