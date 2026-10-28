"""Read-only, parameterized, tenant-scoped factory business sources.

Production quantities use Silver unit snapshots, not Gold's mixed-unit sums.
Inventory is a current available-balance snapshot, never a historical balance.
"""
import asyncio
import calendar
from datetime import date

import pandas as pd
import smartbi.config as config
from .engine import Dataset
from . import settled_sources
from . import cost_authorization
from .metrics import complete_manufacturing_cost

PRODUCTION_SQL = """
SELECT stat_date AS "日期", source_pk AS "来源主键", batch_number AS "批次", product_type_id AS "产品ID",
       COALESCE(product_name, product_type_id, '未关联产品') AS "产品",
       unit AS "单位", planned_qty AS "计划产量", actual_qty AS "产量", good_qty AS "良品数",
       defect_qty AS "不良数", material_cost AS "材料成本", labor_cost AS "人工成本",
       equipment_cost AS "设备成本", other_cost AS "其他成本", total_cost AS "总成本", updated_at AS "数据同步时间"
FROM fact_production_batch
WHERE factory_id = $1 AND status = 'COMPLETED'
  AND ($2::date IS NULL OR stat_date >= $2) AND ($3::date IS NULL OR stat_date <= $3)
ORDER BY stat_date, source_pk LIMIT 10001
"""
PRODUCTION_ID_SQL = """
SELECT stat_date AS "日期", source_pk AS "来源主键", batch_number AS "批次", product_type_id AS "产品ID",
       COALESCE(product_name, product_type_id, '未关联产品') AS "产品",
       unit AS "单位", planned_qty AS "计划产量", actual_qty AS "产量", good_qty AS "良品数",
       defect_qty AS "不良数", updated_at AS "数据同步时间"
FROM fact_production_batch
WHERE factory_id = $1 AND status = 'COMPLETED'
  AND ($2::date IS NULL OR stat_date >= $2) AND ($3::date IS NULL OR stat_date <= $3)
ORDER BY stat_date, source_pk LIMIT 10001
"""
SALES_SQL = """
SELECT so.order_date AS "日期", so.customer_id AS "客户ID", COALESCE(c.name, so.customer_id) AS "客户", so.total_amount AS "销售金额"
FROM sales_orders so
LEFT JOIN customers c ON c.id = so.customer_id AND c.factory_id = so.factory_id AND c.deleted_at IS NULL
WHERE so.factory_id = $1 AND so.deleted_at IS NULL
 AND so.status NOT IN ('CANCELLED', 'DRAFT', 'FINANCE_REJECTED')
 AND ($2::date IS NULL OR so.order_date >= $2) AND ($3::date IS NULL OR so.order_date < $3 + INTERVAL '1 day')
ORDER BY so.order_date, so.id LIMIT 10001
"""
PURCHASE_SQL = """
SELECT pr.receive_date AS "日期", COALESCE(pri.material_name, pri.material_type_id, '未关联物料') AS "原料",
       COALESCE(s.name, pr.supplier_id, '未登记供应商') AS "供应商", pri.unit AS "单位",
       pri.received_quantity AS "入库数量", pri.unit_price AS "入库单价", pri.price_unit AS "价格单位",
       CASE WHEN pri.price_unit = pri.unit THEN ROUND(pri.received_quantity * pri.unit_price, 2) ELSE NULL END AS "入库金额"
FROM purchase_receive_records pr
JOIN purchase_receive_items pri ON pri.receive_record_id=pr.id AND pri.deleted_at IS NULL
LEFT JOIN suppliers s ON s.id=pr.supplier_id AND s.factory_id=pr.factory_id AND s.deleted_at IS NULL
WHERE pr.factory_id=$1 AND pr.deleted_at IS NULL AND pr.status='CONFIRMED'
 AND ($2::date IS NULL OR pr.receive_date >= $2) AND ($3::date IS NULL OR pr.receive_date <= $3)
ORDER BY pr.receive_date, pr.id, pri.id LIMIT 10001
"""
INVENTORY_SQL = """
SELECT CURRENT_DATE AS "日期", COALESCE(rmt.name, mb.material_type_id, mb.product_type_id, '未关联物料') AS "原料",
       mb.quantity_unit AS "单位",
       mb.receipt_quantity - COALESCE(mb.used_quantity,0) - COALESCE(mb.reserved_quantity,0) AS "库存数量",
       CURRENT_DATE - mb.inbound_date::date AS "库龄天数", mb.expire_date::date - CURRENT_DATE AS "距到期天数"
FROM material_batches mb
LEFT JOIN raw_material_types rmt ON rmt.id=mb.material_type_id AND rmt.factory_id=mb.factory_id AND rmt.deleted_at IS NULL
WHERE mb.factory_id=$1 AND mb.deleted_at IS NULL AND mb.status='AVAILABLE'
ORDER BY mb.id LIMIT 10001
"""
QUALITY_SQL = """
SELECT qi.inspection_date AS "日期", qi.production_batch_id AS "生产批次ID", pb.product_type_id AS "产品ID",
       COALESCE(pt.name, '未关联产品') AS "产品", '样本' AS "单位",
       qi.sample_size AS "检验数", qi.pass_count AS "合格数", qi.fail_count AS "不良数"
FROM quality_inspections qi
LEFT JOIN production_batches pb ON pb.id=qi.production_batch_id AND pb.factory_id=qi.factory_id AND pb.deleted_at IS NULL
LEFT JOIN product_types pt ON pt.id=pb.product_type_id AND pt.factory_id=pb.factory_id AND pt.deleted_at IS NULL
WHERE qi.factory_id=$1 AND qi.deleted_at IS NULL
 AND ($2::date IS NULL OR qi.inspection_date >= $2) AND ($3::date IS NULL OR qi.inspection_date <= $3)
ORDER BY qi.inspection_date, qi.id LIMIT 10001
"""
QUALITY_JOIN_SQL = """
SELECT qi.inspection_date AS "日期", qi.production_batch_id AS "生产批次ID", pb.product_type_id AS "产品ID",
       qi.sample_size AS "检验数", qi.pass_count AS "合格数", qi.fail_count AS "不良数"
FROM quality_inspections qi
JOIN production_batches pb ON pb.id=qi.production_batch_id AND pb.factory_id=qi.factory_id AND pb.deleted_at IS NULL
WHERE qi.factory_id=$1 AND qi.deleted_at IS NULL
 AND qi.production_batch_id::text = ANY($2::text[])
ORDER BY qi.inspection_date, qi.id LIMIT 10001
"""
PROCESS_SQL = """
SELECT report_date AS "日期", r.source_pk AS "来源主键", b.product_type_id AS "产品ID", COALESCE(process_category, '未登记工序') AS "工序",
       r.material_cost AS "材料成本", r.labor_cost AS "人工成本", r.updated_at AS "数据同步时间"
FROM fact_production_report r
LEFT JOIN fact_production_batch b ON b.factory_id=r.factory_id AND b.source_pk=r.batch_id
WHERE r.factory_id=$1 AND cost_source='reported'
 AND ($2::date IS NULL OR report_date >= $2) AND ($3::date IS NULL OR report_date <= $3)
ORDER BY report_date, r.source_pk LIMIT 10001
"""
PROCESS_ID_SQL = """
SELECT report_date AS "日期", r.source_pk AS "来源主键", b.product_type_id AS "产品ID", COALESCE(process_category, '未登记工序') AS "工序",
       r.updated_at AS "数据同步时间"
FROM fact_production_report r
LEFT JOIN fact_production_batch b ON b.factory_id=r.factory_id AND b.source_pk=r.batch_id
WHERE r.factory_id=$1 AND cost_source='reported'
 AND ($2::date IS NULL OR report_date >= $2) AND ($3::date IS NULL OR report_date <= $3)
ORDER BY report_date, r.source_pk LIMIT 10001
"""


def dates(request):
    if request.period_type == 'all':
        return None, None
    return date(request.year, request.start_month, 1), date(request.year, request.end_month,
            calendar.monthrange(request.year, request.end_month)[1])


async def load(factory_id, request, can_price, auth_request=None):
    start, end = dates(request)
    sources = [
        ('production', '已完成生产批次 · 保留批次单位快照', config.get_pg_pool, PRODUCTION_SQL),
        ('sales', '订单已保存总额（含订单折扣）；无可对账的SKU折后金额', config.get_cretas_pool, SALES_SQL),
        ('purchase', '已确认收货事实 · 单位匹配的实收数量×单价（非扣退货净额）', config.get_cretas_pool, PURCHASE_SQL),
        ('quality', '真实质检样本 · 样本通过/不良数', config.get_cretas_pool, QUALITY_SQL),
        ('process', '已报告工序材料/人工成本 · 无数量单位快照', config.get_pg_pool, PROCESS_SQL),
    ]
    gaps = []
    if request.period_type == 'all':
        sources.append(('inventory', f'物料批次可用库存快照（不含独立成品库存） · 截至{date.today()}', config.get_cretas_pool, INVENTORY_SQL))
    else:
        gaps.append({'id': 'stock.snapshot', 'title': '历史库存', 'missing': ['所选期间库存余额快照；当前余额不能倒推历史库存']})

    async def read(name, title, get_pool, sql, override_params=None, product_ids=None):
        try:
            pool = await get_pool()
            if pool is None:
                raise RuntimeError('pool unavailable')
            params = override_params if override_params is not None else ([factory_id] if name == 'inventory' else [factory_id, start, end])
            async with pool.acquire() as conn:
                async with conn.transaction():
                    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
                    rows = await conn.fetch(sql, *params)
            if len(rows) > 10000:
                return None, {'source': name, 'message': '明细超过安全上限，请缩小期间后重试'}
            frame = pd.DataFrame([dict(r) for r in rows])
            if name in ('production', 'process'):
                synced = frame['数据同步时间'].dropna() if '数据同步时间' in frame else []
                title += ' · 此期间最后同步：' + (str(max(synced)) if len(synced) else '未提供')
                frame = frame.drop(columns=['数据同步时间'], errors='ignore')
            if not can_price:
                money = [c for c in frame if any(k in c for k in ('金额', '成本'))]
                frame = frame.drop(columns=money)
            return Dataset(frame, name + ' · ' + title, currency='CNY'), None
        except Exception:
            # Do not expose connection details/SQL/schema; do not pretend failures are empty data.
            return None, {'source': name, 'message': '来源读取失败，请重试或联系管理员核对数据服务'}

    def merge_scoped(base, scoped):
        if base is None or scoped is None or base.frame.empty or scoped.frame.empty:
            return base
        key = '来源主键' if '来源主键' in base.frame and '来源主键' in scoped.frame else '产品ID'
        if key not in base.frame or key not in scoped.frame:
            return base
        cost_cols = [c for c in scoped.frame.columns if any(k in c for k in ('金额', '成本'))]
        if not cost_cols:
            return base
        priced = scoped.frame.copy()
        if scoped.source.split(' · ', 1)[0] == 'production':
            invalid = ~priced.apply(complete_manufacturing_cost, axis=1)
            if invalid.any():
                priced.loc[invalid, cost_cols] = None
                gaps.append({'id': 'production.cost-reconciliation', 'source': 'production',
                             'missing': [f'{int(invalid.sum())}行成本分项缺失、非有限、负数或不守恒；成本剔除，数量保留，未补零']})
        values = priced[[key, *cost_cols]].drop_duplicates(key).set_index(key)
        result = base.frame.copy()
        for column in cost_cols:
            result[column] = result[key].map(values[column])
        base.frame = result
        return base

    # Read production first so the fixed cross-table query can be bounded by
    # already-authorized, tenant-scoped stable batch keys.
    production_source = sources[0]
    ids = None
    id_error = None
    def cost_gap(source, message):
        gaps.append({'id': source + '.cost-authorization', 'source': source,
                     'missing': [message]})

    if auth_request is not None and can_price:
        try:
            # Phase 1 contains identifiers and quantity facts only.
            ids, id_error = await read('production', production_source[1], production_source[2], PRODUCTION_ID_SQL)
            if ids is not None:
                ids.frame = ids.frame.drop(columns=[c for c in ids.frame if any(k in c for k in ('金额', '成本'))], errors='ignore')
            product_ids = sorted({str(v).strip() for v in (ids.frame.get('产品ID', []) if ids else []) if str(v).strip()})
            authority = await cost_authorization.authorize_batches(auth_request, factory_id, product_ids)
            if not authority.allowed_product_ids:
                cost_gap('production', '当前授权未覆盖生产成本产品，成本事实未读取；数量事实保留')
                production_result = (ids, id_error)
            else:
                scoped, scoped_error = await read('production', production_source[1], production_source[2], PRODUCTION_SQL.replace('\nORDER BY stat_date', '\n  AND product_type_id = ANY($4::text[])\nORDER BY stat_date'), [factory_id, start, end, sorted(authority.allowed_product_ids)])
                production_result = (merge_scoped(ids, scoped), scoped_error)
                if set(product_ids) - authority.allowed_product_ids:
                    cost_gap('production', '部分产品未获成本授权；其数量事实保留，成本未读取')
        except cost_authorization.CostAuthorizationError:
            cost_gap('production', '生产成本授权失败，成本事实未读取；数量事实保留')
            production_result = (ids, id_error)
    else:
        production_result = await read('production', production_source[1], production_source[2], PRODUCTION_ID_SQL)
        if can_price:
            cost_gap('production', '成本授权未执行，成本事实未读取；数量事实保留')
    async def read_process_authorized():
        source = sources[4]
        if auth_request is None or not can_price:
            result = await read('process', source[1], source[2], PROCESS_ID_SQL)
            if can_price:
                cost_gap('process', '成本授权未执行，成本事实未读取')
            return result
        ids = None
        id_error = None
        try:
            ids, id_error = await read('process', source[1], source[2], PROCESS_ID_SQL)
            if ids is not None:
                ids.frame = ids.frame.drop(columns=[c for c in ids.frame if any(k in c for k in ('金额', '成本'))], errors='ignore')
            product_ids = sorted({str(v).strip() for v in (ids.frame.get('产品ID', []) if ids else []) if str(v).strip()})
            authority = await cost_authorization.authorize_batches(auth_request, factory_id, product_ids)
            if not authority.allowed_product_ids:
                cost_gap('process', '当前授权未覆盖工序成本产品，成本事实未读取')
                return ids, id_error
            scoped, scoped_error = await read('process', source[1], source[2], PROCESS_SQL.replace('\nORDER BY report_date', '\n  AND product_type_id = ANY($4::text[])\nORDER BY report_date'),
                                              [factory_id, start, end, sorted(authority.allowed_product_ids)])
            if set(product_ids) - authority.allowed_product_ids:
                cost_gap('process', '部分产品未获工序成本授权；成本事实未读取')
            return merge_scoped(ids, scoped), scoped_error
        except cost_authorization.CostAuthorizationError:
            cost_gap('process', '工序成本授权失败，成本事实未读取；数量事实保留')
            return ids, id_error

    remaining = await asyncio.gather(*(read(*source) for source in sources[1:4]), read_process_authorized(),
                                      *(read(*source) for source in sources[5:]))
    results = [production_result, *remaining]
    datasets = [dataset for dataset, _ in results if dataset is not None]
    errors = [error for _, error in results if error]
    production = production_result[0]
    # SAFETY_STOCK/interim-settle facts use the real production/FG lineage and
    # may legitimately have an IN_PROGRESS production_batches row.  Load this
    # read-only source before the optional quality join so its stable batch keys
    # are included in the same bounded relationship query.
    if auth_request is None:
        settled_datasets, settled_errors, settled_gaps = await settled_sources.load(factory_id, request, can_price)
    else:
        settled_datasets, settled_errors, settled_gaps = await settled_sources.load(
            factory_id, request, can_price, auth_request=auth_request)
    # The legacy completed-batch fact remains authoritative for keys it owns.
    # Do not double-count a row that happens to be visible through both paths;
    # retain an explicit gap instead of selecting an arbitrary representation.
    completed_keys = set()
    if production is not None and '来源主键' in production.frame:
        completed_keys = {str(value).strip() for value in production.frame['来源主键'].dropna()
                          if str(value).strip()}
    for dataset in settled_datasets:
        if completed_keys and '来源主键' in dataset.frame:
            keys = dataset.frame['来源主键'].map(lambda value: str(value).strip()
                                                 if pd.notna(value) else '')
            overlap = set(keys) & completed_keys
            if overlap:
                settled_gaps.append({'id': 'settled.production.overlap',
                                     'source': 'settled-production',
                                     'missing': ['已完成生产事实已覆盖稳定批次键，settled副本未重复累计'],
                                     'keys': sorted(overlap)})
                dataset.frame = dataset.frame.loc[~keys.isin(overlap)].reset_index(drop=True)
    datasets.extend(settled_datasets)
    errors.extend(settled_errors)
    gaps.extend(settled_gaps)
    join_production = production
    if settled_datasets:
        from .engine import Dataset as _Dataset
        frames = [dataset.frame for dataset in [production, *settled_datasets]
                  if dataset is not None and not dataset.frame.empty]
        if frames:
            columns = list(dict.fromkeys(column for frame in frames for column in frame.columns))
            normalized = [frame.reindex(columns=columns) for frame in frames]
            join_production = _Dataset(pd.concat(normalized, ignore_index=True),
                                       production.source if production is not None
                                       else 'production · 已停产安全库存小结事实',
                                       currency='CNY')
    # A product scope is required before reading this optional relationship.
    # Do not create a chart or perform an extra join query per SKU on startup.
    if (can_price and getattr(request, 'guided', False) and getattr(request, 'analysis_subject', None)
            and join_production is not None and '来源主键' in join_production.frame
            and not join_production.frame.empty):
        from .engine import analysis_subjects, scope_datasets
        selected, _, _ = scope_datasets([join_production], request,
                                         analysis_subjects([join_production], can_price), can_price)
        production = selected[0] if selected else None
        keys = (list(dict.fromkeys(str(v).strip() for v in production.frame['来源主键'].dropna() if str(v).strip()))
                if production is not None else [])
        if keys:
            joined_result = await read('quality-join', '生产批次关联质检 · 按生产日期范围取批次，保留检验日期',
                                       config.get_cretas_pool, QUALITY_JOIN_SQL, [factory_id, keys])
            joined, joined_error = joined_result
            if joined is not None:
                from .cross_table import prepare_joined
                merged, merge_gaps = prepare_joined(production, joined)
                gaps.extend(merge_gaps)
                if merged is not None:
                    from .cross_table import SOURCE_LABEL
                    provenance = production.source.split(' · ', 1)[-1]
                    datasets.append(Dataset(merged, SOURCE_LABEL + ' · ' + provenance, currency='CNY'))
            elif joined_error:
                errors.append(joined_error)
    return datasets, errors, gaps
