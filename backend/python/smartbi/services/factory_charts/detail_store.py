"""PostgreSQL-backed cross-worker store for factory chart detail contexts.

This is deliberately a store, rather than a fallback cache: if SmartBI's
database is unavailable the caller receives a 503 and never advertises a
context which another uvicorn worker cannot read.
"""
from __future__ import annotations

import copy
import hashlib
import json
import secrets
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Sequence

from smartbi import config
from . import details
from .newstock_buckets import CONTRACT_KEY, prepare_rows as prepare_stock_rows


CONTEXT_TTL_SECONDS = 300
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
        if unit_col:
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
        financial = details._is_financial(str(source), item)
        payload = {"source": source, "period": details._period_label(request), "unit": unit,
                   "rows": rows, "columns": columns}
        size = len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        if size > details.MAX_FRAME_BYTES:
            yield item, None
            continue
        snapshot_key = hashlib.sha256(json.dumps(
            [owner, source, details._period_label(request), unit, rows, columns],
            ensure_ascii=False, sort_keys=True, default=str, separators=(",", ":")).encode("utf-8")).hexdigest()
        yield item, {
            "source": str(source), "period": details._period_label(request), "unit": unit,
            "rows": rows, "columns": columns, "dimensions": dimensions,
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
                    output.append(item)
                    continue
                # Shared snapshots are charged once by their immutable digest.
                existing_size = await conn.fetchval(
                    "SELECT size_bytes FROM smart_bi_factory_chart_detail_snapshots WHERE dedup_key=$1",
                    snapshot["snapshot_key"])
                added = 0 if existing_size is not None else snapshot["size"]
                if int(active or 0) >= MAX_CONTEXTS or int(used or 0) + added > MAX_TOTAL_BYTES:
                    output.append(item)
                    continue
                row = await conn.fetchrow(
                    """INSERT INTO smart_bi_factory_chart_detail_snapshots
                    (factory_id,user_id,role,source,period_label,unit,rows,columns,dimensions,price_columns,financial,size_bytes,dedup_key)
                    VALUES($1,$2,$3,$4,$5,$6,$7::jsonb,$8::jsonb,$9::jsonb,$10::jsonb,$11,$12,$13)
                    ON CONFLICT (dedup_key) DO NOTHING RETURNING snapshot_id""",
                    trusted[0], str(trusted[1]), trusted[2], snapshot["source"], snapshot["period"], snapshot["unit"],
                    json.dumps(snapshot["rows"], ensure_ascii=False), json.dumps(snapshot["columns"], ensure_ascii=False),
                    json.dumps(snapshot["dimensions"], ensure_ascii=False), json.dumps(snapshot["price_columns"], ensure_ascii=False),
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
                chart_type = str(item.get("chart", {}).get("chartType", "")).lower()
                drilldown = bool(item.get("chart", {}).get("echartsOption")) and chart_type not in {
                    "gauge", "kpi", "kpi_scorecard", "multi_axis", "dual_axis",
                }
                item["detailContext"] = {"id": context_id, "expiresAt": expires.isoformat(),
                                          "drilldown": drilldown}
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


async def _load_impl(context_id: Any, owner: Any):
    if not isinstance(context_id, str) or not context_id:
        raise details.DetailError(400, "详情上下文无效")
    trusted = _owner(owner)
    pool = await _pool_or_error()
    async with pool.acquire() as conn:
        async with conn.transaction():
            await _bind(conn, trusted)
            row = await conn.fetchrow(
                """SELECT c.context_id,c.user_id,c.role,c.candidate_id,c.title,c.expires_at,s.source,s.period_label,s.rows,s.columns,
                   s.dimensions,s.price_columns,s.financial,s.size_bytes,s.unit
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
    frame = details._Frame(
        context_id, details._Owner.from_value(trusted), str(row["candidate_id"]), str(row["title"]),
        str(row["source"]), str(row["period_label"]), tuple(_json_load(row["rows"])),
        tuple(_json_load(row["columns"])), tuple(_json_load(row["dimensions"])),
        frozenset(_json_load(row["price_columns"])), bool(row["financial"]), str(row["unit"] or "") or None,
        str(row["context_id"]), expires.timestamp(), int(row["size_bytes"] or 0))
    return frame


async def _load(context_id: Any, owner: Any):
    try:
        return await _load_impl(context_id, owner)
    except details.DetailError:
        raise
    except Exception as exc:
        raise details.DetailError(503, "详情共享存储暂不可用") from exc


async def requires_finance_shared(context_id: str, owner: Any) -> bool:
    return (await _load(context_id, owner)).financial


async def page_shared(payload: Mapping[str, Any], owner: Any, can_price: bool, finance_allowed: bool) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise details.DetailError(400, "详情请求无效")
    frame = await _load(payload.get("context_id", payload.get("contextId")), owner)
    return details._page_frame(payload, frame, can_price, finance_allowed)


__all__ = ["register_shared", "requires_finance_shared", "page_shared"]
