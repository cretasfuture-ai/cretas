"""Bounded, authenticated original-row details for factory chart candidates.

The chart engine deliberately returns aggregates.  This module keeps a short
lived in-memory copy of the exact source rows used for a recommendation so a
UI click can be audited without querying an arbitrary table or re-running a
broader period.  It is intentionally independent of FastAPI; the route owns
identity and translates :class:`DetailError` to HTTP responses.
"""
from __future__ import annotations

import copy
import hashlib
import json
import secrets
import time
from collections import OrderedDict
from dataclasses import dataclass
from datetime import date, datetime, timezone
from typing import Any, Mapping, Sequence

import pandas as pd
from .newstock_buckets import CONTRACT_KEY, prepare_rows as prepare_stock_rows


TTL_SECONDS = 300.0
MAX_FRAMES = 256
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_FRAME_BYTES = 8 * 1024 * 1024
MAX_PAGE_SIZE = 100
MAX_FILTERS = 8


class DetailError(ValueError):
    """A safe route-facing validation/authentication failure."""

    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


@dataclass(frozen=True)
class _Owner:
    factory_id: str
    user_id: Any
    role: str

    @classmethod
    def from_value(cls, value: Any) -> "_Owner":
        if isinstance(value, cls):
            result = value
        elif isinstance(value, Mapping):
            result = cls(str(value.get("factory_id", value.get("factoryId", ""))),
                         value.get("user_id", value.get("userId")),
                         str(value.get("role", "")))
        elif isinstance(value, (tuple, list)) and len(value) == 3:
            result = cls(str(value[0]), value[1], str(value[2]))
        else:
            raise DetailError(403, "详情上下文身份无效")
        if not result.factory_id or result.user_id in (None, "") or not result.role:
            raise DetailError(403, "详情上下文身份无效")
        return result


@dataclass
class _Frame:
    context_id: str
    owner: _Owner
    candidate_id: str
    title: str
    source: str
    period: Any
    rows: tuple[dict[str, Any], ...]
    columns: tuple[str, ...]
    dimensions: tuple[str, ...]
    point_dimension: str | None
    price_columns: frozenset[str]
    financial: bool
    unit_filter: str | None
    storage_key: str
    expires_at: float
    size_bytes: int


_FRAMES: "OrderedDict[str, _Frame]" = OrderedDict()
_STORAGES: dict[str, list[int]] = {}  # [charged_bytes, ref_count]
_TOTAL_BYTES = 0


_DATE_NAMES = {"日期", "月份", "生产日期", "入库日期", "销售日期", "会计期间", "月", "年月", "date", "month"}
_UNIT_NAMES = {"单位", "数量单位", "unit"}
_NON_MONEY_NAMES = {
    "日期", "月份", "生产日期", "入库日期", "销售日期", "会计期间", "月", "年月",
    "产品", "产品名称", "SKU", "品类", "工序", "工序名称", "产线", "原料", "原料名称",
    "物料", "供应商", "供应商名称", "单位", "数量单位", "批次", "批次号", "订单号", "单号",
    "账单号", "仓库", "库位", "部门", "渠道", "车间", "班组", "状态", "规格", "型号", "备注",
    "订单ID", "订单行ID", "报工ID", "设备ID", "部门ID", "工序序", "原料ID", "流转记录ID",
    "计划运行分钟", "实际运行分钟", "理想周期秒", "总数", "来源节点", "去向节点", "流量",
    "来源WIP号", "目标WIP号", "产出WIP号",
    "产量", "实际产量", "产出数量", "计划产量", "计划数量", "投入量", "投入数量", "良品数",
    "合格数", "不良数", "缺陷数", "不合格数", "检验数", "检验数量", "库存数量", "可用数量",
    "库龄天数", "距到期天数", "临期天数", "设备开机时长", "运行时长", "停机时长", "节拍", "标准节拍",
    "OEE", "稼动率", "性能率", "良率", "数量", "重量", "数量单位", "id", "ID", "source_id",
    # Trusted catalogue dimensions are exact source labels, never inferred from IDs.
    "客户来源", "物料（单位）", "产品（单位）",
}
_MONEY_NAMES = {
    "总成本", "材料成本", "原料成本", "人工成本", "设备成本", "其他成本", "销售金额", "销售额",
    "采购金额", "入库金额", "平均库存金额", "同期销货成本", "同期领料成本", "订单行小计",
    "SKU折后金额", "订单总额", "销售订单总额", "入库单价", "采购单价", "金额", "成本", "收入",
    "费用", "毛利", "净利", "预算", "应收金额", "实收金额", "欠款金额", "现金流",
}
_FINANCIAL_SOURCE_PREFIXES = ("cashflow", "receivables", "historical-inventory")


def _name(value: Any) -> str:
    text = str(value).strip()
    # Engine treats bracketed unit suffixes as semantic decoration.
    for left, right in (("（", "）"), ("(", ")")):
        if left in text and text.endswith(right):
            text = text[:text.find(left)].strip()
    return text


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        if isinstance(value, float) and (pd.isna(value) or pd.isnull(value)):
            return None
        return value
    if isinstance(value, (pd.Timestamp, datetime, date)):
        return value.isoformat()
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    return str(value)


def _frame_of(dataset: Any) -> tuple[pd.DataFrame, str, str]:
    if hasattr(dataset, "frame"):
        return dataset.frame.copy(deep=True), str(dataset.source), str(getattr(dataset, "sheet_name", ""))
    if isinstance(dataset, Mapping):
        frame = dataset.get("frame", dataset.get("data"))
        if isinstance(frame, pd.DataFrame):
            return frame.copy(deep=True), str(dataset.get("source", "")), str(dataset.get("sheet_name", ""))
        if isinstance(frame, Sequence) and not isinstance(frame, (str, bytes)):
            return pd.DataFrame(frame).copy(deep=True), str(dataset.get("source", "")), str(dataset.get("sheet_name", ""))
    raise DetailError(422, "详情数据集无可用来源明细")


def _request_value(request: Any, key: str, default: Any = None) -> Any:
    if isinstance(request, Mapping):
        return request.get(key, default)
    return getattr(request, key, default)


def _period_mask(frame: pd.DataFrame, request: Any) -> pd.Series:
    period_type = str(_request_value(request, "period_type", _request_value(request, "periodType", "all")) or "all").lower()
    if period_type in {"all", "", "none"}:
        return pd.Series(True, index=frame.index)
    date_col = next((c for c in frame.columns if _name(c) in _DATE_NAMES), None)
    if date_col is None:
        raise DetailError(422, "来源无日期列，不能安全冻结所选期间详情")
    values = frame[date_col]
    parsed = pd.to_datetime(values, errors="coerce")
    year = _request_value(request, "year")
    start = _request_value(request, "start_month", _request_value(request, "startMonth", 1))
    end = _request_value(request, "end_month", _request_value(request, "endMonth", 12))
    if year is not None:
        # Month-only strings are accepted only when the requested year is also
        # represented in the value; otherwise the source is period ambiguous.
        if parsed.isna().any() and values.astype(str).str.fullmatch(r"\d{1,2}月|\d{1,2}").any():
            raise DetailError(422, "来源月份缺少年份，不能安全限定详情期间")
        mask = parsed.dt.year.eq(int(year))
        if period_type in {"month", "month_range", "monthly", "quarter"}:
            mask &= parsed.dt.month.between(int(start), int(end))
        return mask.fillna(False)
    if period_type in {"month", "month_range", "monthly"}:
        return parsed.dt.month.between(int(start), int(end)).fillna(False)
    return parsed.notna()


def canonical_period_label(request: Any) -> str:
    """Return the one period label shared by cards and frozen detail rows."""
    explicit = _request_value(request, "period")
    if explicit is not None:
        return str(explicit)
    year = _request_value(request, "year")
    typ = _request_value(request, "period_type", _request_value(request, "periodType", "all"))
    if year is None or str(typ).lower() == "all":
        return "全部历史"
    start = _request_value(request, "start_month", _request_value(request, "startMonth", 1))
    end = _request_value(request, "end_month", _request_value(request, "endMonth", 12))
    start, end = int(start), int(end)
    return f"{int(year)}年{start}–{end}月"


def _period_label(request: Any) -> str:
    # Compatibility wrapper for the shared detail-store implementation.
    return canonical_period_label(request)


def _canonical_rows(frame: pd.DataFrame, request: Any) -> tuple[tuple[dict[str, Any], ...], tuple[str, ...]]:
    selected = frame.loc[_period_mask(frame, request)]
    columns = tuple(str(c) for c in selected.columns)
    rows = tuple({str(c): _json_value(row[c]) for c in selected.columns} for _, row in selected.iterrows())
    return rows, columns


def _base_name(column: str) -> str:
    return _name(column)


def _classify(columns: Sequence[str]) -> tuple[frozenset[str], tuple[str, ...]]:
    prices = set()
    dimensions = []
    for column in columns:
        base = _base_name(column)
        if base in _MONEY_NAMES or any(token in base for token in ("金额", "成本", "收入", "费用", "应收", "欠款")):
            prices.add(column)
        # Deliberately exact: a column such as 工资名称 is unknown and must
        # not become a non-price filter merely because it ends with 名称.
        if base in _NON_MONEY_NAMES or base in _DATE_NAMES or base in _UNIT_NAMES:
            dimensions.append(column)
    return frozenset(prices), tuple(dict.fromkeys(dimensions))


def point_drilldown_enabled(candidate: Mapping[str, Any]) -> bool:
    """An explicit opt-out may only remove point drilling, never authorize it."""
    metadata = candidate.get("engine")
    if isinstance(metadata, Mapping) and metadata.get("pointDrilldown") is False:
        return False
    return bool(candidate.get("chart", {}).get("echartsOption")) and str(
        candidate.get("chart", {}).get("chartType", "")).lower() not in {
            "gauge", "kpi", "kpi_scorecard", "multi_axis", "dual_axis"}


def _currency_quantity_independent(candidate: Mapping[str, Any], dataset: Any) -> bool:
    """Only reconciled CNY SKU revenue keeps rows in their original units.

    This changes row selection, never sums kg/boxes or authorizes money access.
    Generic money displays, prices and ratios retain the existing unit guard.
    """
    source = str(candidate.get('source', ''))
    dataset_source = str(dataset.get('source', '')) if isinstance(dataset, Mapping) else str(getattr(dataset, 'source', ''))
    currency = dataset.get('currency') if isinstance(dataset, Mapping) else getattr(dataset, 'currency', None)
    analysis = candidate.get('analysis') if isinstance(candidate.get('analysis'), Mapping) else {}
    engine = candidate.get('engine') if isinstance(candidate.get('engine'), Mapping) else {}
    return (source == dataset_source and source.startswith('sales-sku · ')
            and currency == 'CNY' and candidate.get('unit') == '元'
            and analysis.get('familyId') == 'sales-sku.sales.sku'
            and engine.get('detailUnitPolicy') == 'currency_rows')


def _is_financial(source: str, candidate: Mapping[str, Any]) -> bool:
    root = source.split(" · ", 1)[0].lower()
    return root.startswith(_FINANCIAL_SOURCE_PREFIXES)


def _purge_expired(now: float) -> None:
    global _TOTAL_BYTES
    expired = [key for key, value in _FRAMES.items() if value.expires_at <= now]
    for key in expired:
        frame = _FRAMES.pop(key)
        storage = _STORAGES.get(frame.storage_key)
        if storage:
            storage[1] -= 1
            if storage[1] <= 0:
                _TOTAL_BYTES -= storage[0]
                _STORAGES.pop(frame.storage_key, None)


def _put(frame: _Frame) -> None:
    global _TOTAL_BYTES
    _purge_expired(time.monotonic())
    if frame.size_bytes > MAX_FRAME_BYTES:
        raise DetailError(422, "来源详情过大，无法安全建立详情上下文")
    additional = 0 if frame.storage_key in _STORAGES else frame.size_bytes
    while _FRAMES and (len(_FRAMES) >= MAX_FRAMES or _TOTAL_BYTES + additional > MAX_TOTAL_BYTES):
        _, old = _FRAMES.popitem(last=False)
        storage = _STORAGES.get(old.storage_key)
        if storage:
            storage[1] -= 1
            if storage[1] <= 0:
                _TOTAL_BYTES -= storage[0]
                _STORAGES.pop(old.storage_key, None)
    if frame.storage_key in _STORAGES:
        _STORAGES[frame.storage_key][1] += 1
    else:
        _STORAGES[frame.storage_key] = [frame.size_bytes, 1]
        _TOTAL_BYTES += frame.size_bytes
    _FRAMES[frame.context_id] = frame


def _get(context_id: Any, owner: Any) -> _Frame:
    if not isinstance(context_id, str) or not context_id or len(context_id) > 128:
        raise DetailError(400, "详情上下文无效")
    frame = _FRAMES.get(context_id)
    if frame is None:
        raise DetailError(410, "详情上下文已过期或不可用")
    if frame.expires_at <= time.monotonic():
        global _TOTAL_BYTES
        expired = _FRAMES.pop(context_id)
        storage = _STORAGES.get(expired.storage_key)
        if storage:
            storage[1] -= 1
            if storage[1] <= 0:
                _TOTAL_BYTES -= storage[0]
                _STORAGES.pop(expired.storage_key, None)
        raise DetailError(410, "详情上下文已过期或不可用")
    if frame.owner != _Owner.from_value(owner):
        raise DetailError(403, "无权访问该详情上下文")
    _FRAMES.move_to_end(context_id)
    return frame


def _new_context() -> str:
    return secrets.token_urlsafe(24)


def register(candidates: Sequence[Mapping[str, Any]], datasets: Sequence[Any], request: Any,
             owner: Any, can_price: bool) -> list[dict[str, Any]]:
    """Attach immutable detail contexts to eligible candidates.

    Candidate objects are copied so AI ranking/cache inputs remain unchanged.
    A candidate only receives a context when its exact ``source`` identifies a
    loaded dataset and the requested period can be proven from source rows.
    """
    trusted_owner = _Owner.from_value(owner)
    by_source: dict[str, Any] = {}
    for dataset in datasets:
        _, source, _ = _frame_of(dataset)
        if source:
            by_source[source] = dataset
    output = []
    prepared: dict[str, tuple[pd.DataFrame, str, tuple[dict[str, Any], ...], tuple[str, ...], frozenset[str], tuple[str, ...], str]] = {}
    for source, dataset in by_source.items():
        try:
            raw, exact_source, _ = _frame_of(dataset)
            if exact_source != source:
                continue
            rows, columns = _canonical_rows(raw, request)
            prices, dimensions = _classify(columns)
            units = [str(row.get(next((c for c in columns if _base_name(c) in _UNIT_NAMES), ""), "")).strip()
                     for row in rows]
            prepared[source] = (raw, exact_source, rows, columns, prices, dimensions,
                                ",".join(sorted(set(u for u in units if u))))
        except DetailError:
            continue
    for candidate in candidates:
        item = copy.deepcopy(dict(candidate))
        source = item.get("source")
        dataset = by_source.get(source)
        if dataset is None or not isinstance(source, str) or not source:
            output.append(item)
            continue
        try:
            raw, exact_source, rows, columns, prices, dimensions, unit_fingerprint = prepared[source]
            if exact_source != source:
                output.append(item)
                continue
            if not rows:
                output.append(item)
                continue
            financial = _is_financial(source, item)
            engine_meta = item.get("engine") if isinstance(item.get("engine"), Mapping) else {}
            point_dimension = engine_meta.get("pointDimension")
            if point_dimension is not None:
                point_dimension = str(point_dimension)
                if point_dimension not in dimensions:
                    raise DetailError(422, "图表定位维度不属于来源明细")
            unit_column = next((c for c in columns if _base_name(c) in _UNIT_NAMES), None)
            stock_contract = item.get("engine", {}).get(CONTRACT_KEY) if isinstance(item.get("engine"), Mapping) else None
            if stock_contract is not None:
                expected_kind = {"inventory.stock.age:bar": "age", "inventory.stock.expiry:bar": "expiry"}.get(str(item.get("id", "")))
                candidate_unit = str(item.get("unit", "") or "").rsplit("/", 1)[-1].strip()
                if (not isinstance(stock_contract, Mapping) or expected_kind is None
                        or candidate_unit != "批" or stock_contract.get("bucketKind") != expected_kind):
                    output.append(item)
                    continue
                prepared_stock, bucket_column = prepare_stock_rows(rows, stock_contract)
                if not prepared_stock or not bucket_column:
                    output.append(item)
                    continue
                rows = prepared_stock
                if bucket_column not in columns:
                    columns = tuple(columns) + (bucket_column,)
                dimensions = tuple(dict.fromkeys((*dimensions, bucket_column)))
                unit_column = None
            candidate_unit = str(item.get("unit", "") or "")
            if "/" in candidate_unit:
                candidate_unit = candidate_unit.rsplit("/", 1)[-1]
            candidate_unit = candidate_unit.strip()
            distinct_units = {str(row.get(unit_column, "")).strip() for row in rows} if unit_column else set()
            trusted_quantity_rows = engine_meta.get("detailUnitPolicy") == "trusted_quantity_rows"
            if unit_column and not trusted_quantity_rows and not _currency_quantity_independent(item, dataset):
                quantity_unit = str(item.get("sourceQuantityUnit", item.get("quantityUnit", "")) or "").strip()
                engine_meta = item.get("engine") if isinstance(item.get("engine"), Mapping) else {}
                quantity_unit = quantity_unit or str(engine_meta.get("sourceQuantityUnit", "") or "").strip()
                if candidate_unit in distinct_units:
                    rows = tuple(row for row in rows if str(row.get(unit_column, "")).strip() == candidate_unit)
                elif quantity_unit and quantity_unit in distinct_units:
                    # Display units such as 元 or % are measures over a
                    # quantity source; their source unit must be explicit.
                    rows = tuple(row for row in rows if str(row.get(unit_column, "")).strip() == quantity_unit)
                elif len(distinct_units - {""}) == 1:
                    # A monetary/ratio candidate may safely infer the only
                    # source quantity unit. Never infer across multiple units.
                    only_unit = next(iter(distinct_units - {""}), "")
                    rows = tuple(row for row in rows if str(row.get(unit_column, "")).strip() == only_unit)
                else:
                    output.append(item)
                    continue
            payload = {"source": source, "period": _period_label(request), "unit": candidate_unit,
                       "rows": rows, "columns": columns}
            size = len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
            context_id = _new_context()
            expires_at = time.monotonic() + TTL_SECONDS
            # Same source/period/owner rows are shared. Unit-scoped contexts
            # use a distinct key because their filtered row set differs.
            content_digest = hashlib.sha256(json.dumps([rows, columns], ensure_ascii=False,
                                                        sort_keys=True, default=str,
                                                        separators=(",", ":")).encode("utf-8")).hexdigest()
            storage_key = json.dumps([trusted_owner.factory_id, trusted_owner.user_id, trusted_owner.role,
                                      source, _period_label(request), candidate_unit, unit_fingerprint,
                                      content_digest], ensure_ascii=False)
            frame = _Frame(context_id, trusted_owner, str(item.get("id", "")), str(item.get("title", item.get("metric", ""))),
                           source, _period_label(request), rows, columns, dimensions, point_dimension, prices, financial,
                           candidate_unit or None, storage_key, expires_at, size)
            _put(frame)
            drilldown = point_drilldown_enabled(item)
            item["detailContext"] = {"id": context_id, "expiresAt": datetime.now(timezone.utc).isoformat(),
                                      "drilldown": drilldown}
        except DetailError:
            # An oversized/ambiguous source must not advertise a fake details button.
            item.pop("detailContext", None)
        output.append(item)
    return output


def requires_finance(context_id: str, owner: Any) -> bool:
    return _get(context_id, owner).financial


def _point_rows(frame: _Frame, point: Mapping[str, Any]) -> set[int]:
    if not isinstance(point, Mapping):
        raise DetailError(422, "图表定位点无效")
    name = point.get("name")
    if name in (None, ""):
        raise DetailError(422, "图表定位点缺少唯一维度")
    name = str(name)
    if "dataIndex" in point:
        try:
            if int(point["dataIndex"]) < 0:
                raise ValueError
        except (TypeError, ValueError):
            raise DetailError(422, "图表定位索引无效") from None
    matches: list[set[int]] = []
    point_columns = (frame.point_dimension,) if frame.point_dimension else frame.dimensions
    for column in point_columns:
        def matches_value(value: Any) -> bool:
            text = str(value or "").strip()
            if _base_name(column) in _DATE_NAMES:
                # Chart axes commonly emit YYYY-MM while source rows carry a
                # day, or emit a month label while source carries a date.
                if text == name:
                    return True
                parsed = pd.to_datetime(text, errors="coerce")
                if pd.notna(parsed):
                    if len(name) == 7 and name[4] == "-":
                        return parsed.strftime("%Y-%m") == name
                    if name.endswith("月") and name[:-1].isdigit():
                        return str(parsed.month) == name[:-1]
                    if len(name) == 4 and name.isdigit():
                        return str(parsed.year) == name
            return text == name
        values = {idx for idx, row in enumerate(frame.rows) if matches_value(row.get(column, ""))}
        if values:
            matches.append(values)
    if not matches:
        raise DetailError(422, "图表定位点不属于来源明细")
    first = matches[0]
    if any(values != first for values in matches[1:]):
        raise DetailError(422, "图表定位点对应多个不一致来源范围")
    return first


def _available_filters(frame: _Frame) -> list[dict[str, str]]:
    result = []
    for column in frame.dimensions:
        values = [row.get(column) for row in frame.rows]
        if len({json.dumps(v, ensure_ascii=False, sort_keys=True) for v in values}) <= 1:
            continue
        kind = "date" if _base_name(column) in _DATE_NAMES else "dimension"
        result.append({"field": column, "label": column, "kind": kind})
    return result


def _page_frame(payload: Mapping[str, Any], frame: _Frame, can_price: bool, finance_allowed: bool) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise DetailError(400, "详情请求无效")
    if frame.financial and (not can_price or not finance_allowed):
        raise DetailError(403, "当前身份没有该财务详情查看权限")
    try:
        page_no = int(payload.get("page", 1))
        page_size = int(payload.get("page_size", payload.get("pageSize", 50)))
    except (TypeError, ValueError):
        raise DetailError(400, "分页参数无效") from None
    if page_no < 1 or page_size < 1 or page_size > MAX_PAGE_SIZE:
        raise DetailError(400, "分页参数超出范围")
    selected = set(range(len(frame.rows)))
    raw_filters = payload.get("filters") or []
    if not isinstance(raw_filters, list) or len(raw_filters) > MAX_FILTERS:
        raise DetailError(400, "筛选条件超出范围")
    allowed = {item["field"]: item for item in _available_filters(frame)}
    applied = []
    for item in raw_filters:
        if not isinstance(item, Mapping) or not isinstance(item.get("field"), str) or "value" not in item:
            raise DetailError(400, "筛选条件无效")
        field, value = item["field"], item["value"]
        if field not in allowed:
            raise DetailError(422, "筛选字段不属于来源维度")
        if isinstance(value, (dict, list)):
            raise DetailError(422, "筛选值无效")
        filtered = {idx for idx in selected if str(frame.rows[idx].get(field, "")) == str(value)}
        if not filtered and selected:
            raise DetailError(422, "筛选值不属于来源明细")
        selected = filtered
        applied.append({"field": field, "value": value})
    point = payload.get("point")
    if point is not None:
        selected &= _point_rows(frame, point)
        if not selected:
            raise DetailError(422, "图表定位点与筛选条件无交集")
    ordered = [frame.rows[idx] for idx in range(len(frame.rows)) if idx in selected]
    if not can_price:
        # Financial contexts fail closed above. For non-financial uploads only
        # explicit non-money semantics survive; unknown columns are removed.
        safe = [c for c in frame.columns if c not in frame.price_columns and (_base_name(c) in _NON_MONEY_NAMES or c in frame.dimensions)]
        # Trusted quantity families have a narrow, source-bound evidence
        # contract.  This preserves auditable voucher/batch facts without
        # broadening ordinary-upload redaction or allowing a candidate to
        # self-declare arbitrary safe columns.
        trusted_fields = None
        if (frame.source.startswith("purchase-batch-lineage ·")
                and frame.candidate_id.startswith("purchase-batch-lineage.purchase.inventory@")):
            trusted_fields = {
                "日期", "收货单ID", "收货行ID", "物料批次ID", "物料", "物料（单位）",
                "收货单位", "批次数量单位", "包装单位快照", "库存基础单位快照",
                "数量", "数量单位", "批次入库数量", "批次已用数量", "批次预留数量", "当前可用数量",
            }
        elif (frame.source.startswith("wip-plan-flow ·")
                and frame.candidate_id.startswith("wip-plan-flow.plan-flow:sankey@")):
            trusted_fields = {
                "过账日期", "来源批次", "目标批次", "数量", "单位", "MC凭证",
                "来源节点", "去向节点", "流量",
            }
        if trusted_fields is not None:
            safe = [c for c in frame.columns if c not in frame.price_columns and c in trusted_fields]
        ordered = [{k: row[k] for k in safe} for row in ordered]
        columns = tuple(safe)
    else:
        columns = frame.columns
    total = len(ordered)
    start = (page_no - 1) * page_size
    rows = [copy.deepcopy(row) for row in ordered[start:start + page_size]]
    return {"success": True, "data": {"factoryId": frame.owner.factory_id, "candidateId": frame.candidate_id,
        "title": frame.title, "source": frame.source, "period": copy.deepcopy(frame.period),
        "columns": list(columns), "rows": rows, "total": total, "page": page_no, "pageSize": page_size,
        "hasMore": start + len(rows) < total, "appliedFilters": applied,
        "availableFilters": _available_filters(frame), "snapshotAt": int(time.time())}}


def page(payload: Mapping[str, Any], owner: Any, can_price: bool, finance_allowed: bool) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise DetailError(400, "详情请求无效")
    frame = _get(payload.get("context_id", payload.get("contextId")), owner)
    return _page_frame(payload, frame, can_price, finance_allowed)


def clear_cache() -> None:
    global _TOTAL_BYTES
    _FRAMES.clear()
    _STORAGES.clear()
    _TOTAL_BYTES = 0


__all__ = ["DetailError", "MAX_FRAMES", "MAX_TOTAL_BYTES", "MAX_FRAME_BYTES", "register", "requires_finance", "page", "clear_cache"]
