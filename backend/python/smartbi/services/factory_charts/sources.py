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

PRODUCTION_SQL = """
SELECT stat_date AS "日期", batch_number AS "批次", COALESCE(product_name, product_type_id, '未关联产品') AS "产品",
       unit AS "单位", planned_qty AS "计划产量", actual_qty AS "产量", good_qty AS "良品数",
       defect_qty AS "不良数", material_cost AS "材料成本", labor_cost AS "人工成本",
       equipment_cost AS "设备成本", other_cost AS "其他成本", total_cost AS "总成本", updated_at AS "数据同步时间"
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
SELECT qi.inspection_date AS "日期", COALESCE(pt.name, '未关联产品') AS "产品", '样本' AS "单位",
       qi.sample_size AS "检验数", qi.pass_count AS "合格数", qi.fail_count AS "不良数"
FROM quality_inspections qi
LEFT JOIN production_batches pb ON pb.id=qi.production_batch_id AND pb.factory_id=qi.factory_id AND pb.deleted_at IS NULL
LEFT JOIN product_types pt ON pt.id=pb.product_type_id AND pt.factory_id=pb.factory_id AND pt.deleted_at IS NULL
WHERE qi.factory_id=$1 AND qi.deleted_at IS NULL
 AND ($2::date IS NULL OR qi.inspection_date >= $2) AND ($3::date IS NULL OR qi.inspection_date <= $3)
ORDER BY qi.inspection_date, qi.id LIMIT 10001
"""
PROCESS_SQL = """
SELECT report_date AS "日期", COALESCE(process_category, '未登记工序') AS "工序",
       material_cost AS "材料成本", labor_cost AS "人工成本", updated_at AS "数据同步时间"
FROM fact_production_report
WHERE factory_id=$1 AND cost_source='reported'
 AND ($2::date IS NULL OR report_date >= $2) AND ($3::date IS NULL OR report_date <= $3)
ORDER BY report_date, source_pk LIMIT 10001
"""


def dates(request):
    if request.period_type == 'all':
        return None, None
    return date(request.year, request.start_month, 1), date(request.year, request.end_month,
            calendar.monthrange(request.year, request.end_month)[1])


async def load(factory_id, request, can_price):
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

    async def read(name, title, get_pool, sql):
        try:
            pool = await get_pool()
            if pool is None:
                raise RuntimeError('pool unavailable')
            params = [factory_id] if name == 'inventory' else [factory_id, start, end]
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

    results = await asyncio.gather(*(read(*source) for source in sources))
    return [dataset for dataset, _ in results if dataset is not None], [error for _, error in results if error], gaps
