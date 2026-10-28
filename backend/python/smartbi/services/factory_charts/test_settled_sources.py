from contextlib import asynccontextmanager
from datetime import date

import pytest

from smartbi.services.factory_charts import settled_sources


def row(**overrides):
    value = {
        '日期': date(2026, 10, 28), '来源主键': '1600', '计划ID': 'plan-1',
        '批次': '11003', '产品ID': 'sku-1', '产品': 'QA原料成品',
        '成品批次ID': 'fg-1', '成品Manifest': 'manifestFG-1', '成品计划ID': 'plan-1',
        '成品产品ID': 'sku-1', '单位': '盒', '成品单位': '盒',
        '产出报告数量': 10, '实收数量': 10, '产出行状态': 'RECEIVED',
        '良品数': 10, '不良数': 0, '产量': 10,
        '生产批次状态': 'IN_PROGRESS',
        '不良清点结论': 'NONE_CONFIRMED',
        '材料成本': 25, '人工成本': 26, '设备成本': 2, '其他成本': 1,
        '总成本': 54, '成本不完整': False,
    }
    value.update(overrides)
    return value


def test_qualifies_verified_good_plus_explicit_zero_defect_and_complete_cost():
    qualified, gaps = settled_sources.qualify_settled_rows([row()])
    assert len(qualified) == 1
    assert gaps == []


@pytest.mark.parametrize('overrides, phrase', [
    ({'不良清点结论': None, '不良数': None, '产量': 10}, '未补零'),
    ({'不良清点结论': 'NOT_COUNTED', '不良数': None, '产量': 10}, '明确已清点'),
    ({'不良清点结论': 'NONE_CONFIRMED', '不良数': 0, '产量': 9}, '守恒'),
    ({'总成本': None}, '成本分项或总成本不完整'),
    ({'总成本': 55}, '成本分项或总成本不完整'),
    ({'成本不完整': None}, '成本分项或总成本不完整'),
    ({'来源主键': None}, '稳定生产批次键'),
    ({'成品产品ID': 'sku-other'}, '成品计划或SKU'),
    ({'日期': None}, '实际生产日期'),
    ({'生产批次状态': 'CANCELLED'}, '允许的生产/完成状态'),
    ({'实收数量': 8}, '产出报告/实收数量'),
])
def test_qualification_rejects_missing_or_inconsistent_facts(overrides, phrase):
    qualified, gaps = settled_sources.qualify_settled_rows([row(**overrides)])
    assert qualified == []
    assert phrase in '；'.join(gaps[0]['missing'])


def test_duplicate_stable_key_quarantines_all_rows():
    qualified, gaps = settled_sources.qualify_settled_rows([row(), row(产品='另一个显示名')])
    assert qualified == []
    assert len(gaps) == 2
    assert all('整组拒绝' in '；'.join(gap['missing']) for gap in gaps)


def test_multiple_rows_for_one_plan_are_quarantined_as_a_whole_plan():
    qualified, gaps = settled_sources.qualify_settled_rows([
        row(**{'来源主键': '1600', '成品批次ID': 'fg-1'}),
        row(**{'来源主键': '1601', '成品批次ID': 'fg-2'}),
    ])
    assert qualified == []
    assert len(gaps) == 2
    assert all('整计划拒绝' in '；'.join(gap['missing']) for gap in gaps)


def test_no_price_role_does_not_qualify_or_read_cost_columns():
    qualified, gaps = settled_sources.qualify_settled_rows([row()], can_price=False)
    assert len(qualified) == 1
    assert qualified[0]['产量'] == 10
    assert qualified[0]['单位'] == '盒'
    assert all(column not in qualified[0] for column in ('材料成本', '人工成本', '设备成本', '其他成本', '总成本'))
    assert any('无成本权限' in item for item in gaps[0]['missing'])
    assert 'material_cost' not in settled_sources.SETTLED_SOURCE_NO_PRICE_SQL
    assert 'total_cost' not in settled_sources.SETTLED_SOURCE_NO_PRICE_SQL
    assert 'cost_incomplete' not in settled_sources.SETTLED_SOURCE_NO_PRICE_SQL


def test_sql_contract_is_tenant_scoped_and_uses_process_date_and_real_settlement_guards():
    sql = settled_sources.SETTLED_SOURCE_SQL
    assert 'pol.factory_id = $1' in sql
    assert 'pb.factory_id = pol.factory_id' in sql
    assert 'pp.factory_id = pol.factory_id' in sql
    assert 'fgb.factory_id = pol.factory_id' in sql
    assert 'fgb.production_plan_id = pol.production_plan_id' in sql
    assert 'fgb.product_type_id = pol.product_type_id' in sql
    assert 'COUNT(*) = 1' in sql
    assert 'COUNT(DISTINCT pol.product_type_id) = 1' in sql
    assert "pol.status IN ('REPORTED', 'RECEIVED')" in sql
    assert 'pol.received_quantity = pol.reported_quantity' in sql
    assert 'COUNT(DISTINCT pis.session_seq) = 1' in sql
    assert 'COUNT(DISTINCT manifest.batch_number) = 1' in sql
    assert 'ble.factory_id' in sql and "source_type = 'PRODUCTION_BATCH'" in sql
    assert "target_type = 'FINISHED_BATCH'" in sql
    assert "pp.source_type = 'SAFETY_STOCK'" in sql
    assert "pp.status = 'COMPLETED'" in sql
    assert "ps.posting_status = 'POSTED'" in sql
    assert "psr.submission_status = 'SUBMITTED'" in sql
    assert 'psr.interim_settled_at IS NOT NULL' in sql
    assert "ps.defect_count_outcome IN ('NONE_CONFIRMED', 'COUNTED')" in sql
    assert 'interim_scope.batch_number = fgb.batch_number' in sql
    assert "pb.status IN ('IN_PROGRESS', 'PRODUCING', 'COMPLETED')" in sql
    assert 'fgb.produced_quantity' not in sql
    assert 'fgb.shipped_quantity' not in sql
    assert 'fgb.reserved_quantity' not in sql
    assert "fgb.status = 'AVAILABLE'" not in sql
    assert 'fgb.production_date >= $2' in sql
    assert 'fgb.production_date <= $3' in sql
    assert 'UPDATE ' not in sql.upper()
    assert 'DELETE ' not in sql.upper()


def test_reported_output_snapshot_is_accepted_without_claiming_warehouse_receipt():
    qualified, gaps = settled_sources.qualify_settled_rows([row(**{'产出行状态': 'REPORTED'})])
    assert len(qualified) == 1
    assert gaps == []


def test_output_snapshot_survives_current_inventory_or_shipping_state_changes():
    baseline, baseline_gaps = settled_sources.qualify_settled_rows([row()])
    changed, changed_gaps = settled_sources.qualify_settled_rows([
        row(**{'当前库存状态': 'SCRAPPED', '当前可用数量': 0, '当前发货状态': 'DELIVERED'})
    ])
    assert baseline_gaps == changed_gaps == []
    assert baseline[0]['产出报告数量'] == changed[0]['产出报告数量'] == 10


def test_second_output_line_without_verified_lineage_rejects_entire_plan():
    qualified, gaps = settled_sources.qualify_settled_rows([
        row(),
        row(**{'来源主键': '1601', '成品批次ID': 'fg-2', '成品Manifest': None}),
    ])
    assert qualified == []
    assert len(gaps) == 2
    assert all('整计划拒绝' in '；'.join(gap['missing']) for gap in gaps)


@pytest.mark.asyncio
async def test_load_sets_factory_config_and_returns_gap_when_no_price(monkeypatch):
    calls = []

    class Conn:
        async def execute(self, sql, *params):
            calls.append(('execute', sql, params))

        @asynccontextmanager
        async def transaction(self):
            yield self

        async def fetch(self, sql, *params):
            calls.append(('fetch', sql, params))
            assert 'material_cost' not in sql
            return [row()]

    class Pool:
        @asynccontextmanager
        async def acquire(self):
            yield Conn()

    async def get_pool():
        return Pool()

    monkeypatch.setattr(settled_sources.config, 'get_cretas_pool', get_pool)
    request = type('Request', (), {'period_type': 'month_range', 'year': 2026,
                                   'start_month': 10, 'end_month': 10})()
    datasets, errors, gaps = await settled_sources.load('F006', request, False)
    assert len(datasets) == 1 and errors == []
    assert list(datasets[0].frame['产量']) == [10]
    assert any('无成本权限' in item for item in gaps[0]['missing'])
    assert calls[0][2] == ('F006',)
    assert calls[1][2] == ('F006', date(2026, 10, 1), date(2026, 10, 31))


@pytest.mark.asyncio
async def test_real_pg_interim_scope_rejects_malformed_sessions_without_cross_tenant_failure():
    import json
    import os
    from urllib.parse import urlparse
    dsn = os.environ.get('DETAIL_STORE_TEST_PG_URL')
    if not dsn:
        pytest.skip('set DETAIL_STORE_TEST_PG_URL to the owned disposable PostgreSQL')
    target = urlparse(dsn)
    assert target.hostname in ('127.0.0.1', 'localhost') and target.port == 55439
    assert target.path == '/cretas_sc28_disposable_test'
    asyncpg = pytest.importorskip('asyncpg')
    # Execute the actual source CTE, including its tenant predicate and safe JSON
    # expansion, against malformed same/other-tenant sessions in the real parser.
    fragment = settled_sources._SETTLED_CTES.split('), interim_scope AS (', 1)[1].rsplit('\n)', 1)[0]
    assert 'WHERE pis.factory_id = $1' in fragment
    sessions = [
        ('F006', 'valid', 1, {'finishedGoodsBatchNumbers': ['FG1'], 'finishedQuantity': 10}),
        ('F006', 'ambiguous', 1, {'finishedGoodsBatchNumbers': ['FG2'], 'finishedQuantity': 10}),
        ('F006', 'ambiguous', 2, {'finishedGoodsBatchNumbers': {'bad': 'object'}, 'finishedQuantity': 10}),
        ('F006', 'invalid', 1, {'finishedGoodsBatchNumbers': 'text', 'finishedQuantity': 10}),
        ('F007', 'other', 1, {'finishedGoodsBatchNumbers': {'bad': 'object'}}),
        ('F007', 'other', 2, {'finishedGoodsBatchNumbers': None}),
    ]
    sql = '''WITH production_interim_settlement AS (
        SELECT factory_id, production_plan_id, session_seq, summary, NULL::timestamp AS deleted_at
        FROM jsonb_to_recordset($2::jsonb) AS t(factory_id text, production_plan_id text, session_seq int, summary jsonb)
    ), interim_scope AS (''' + fragment + ') SELECT * FROM interim_scope'
    records = [dict(factory_id=f, production_plan_id=p, session_seq=s, summary=j) for f, p, s, j in sessions]
    connection = await asyncpg.connect(dsn)
    try:
        rows = await connection.fetch(sql, 'F006', json.dumps(records))
        assert len(rows) == 1 and rows[0]['factory_id'] == 'F006'
        assert rows[0]['production_plan_id'] == 'valid' and rows[0]['finished_quantity'] == 10
    finally:
        await connection.close()
