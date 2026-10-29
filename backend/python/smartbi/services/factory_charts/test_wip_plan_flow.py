from decimal import Decimal
from datetime import date
import os
import uuid
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlparse

import pandas as pd

from . import detail_store, details, engine
from . import detail_store
from .wip_plan_flow import WIP_PLAN_FLOW_SQL, assemble_wip_plan_flows


def row(**overrides):
    base = {
        'MC凭证ID': 3986, '__factory_id': 'F006', '过账日期': '2026-10-29',
        '来源库存批次ID': '1409914', '目标生产批次ID': 11009, '数量': Decimal('1'),
        '来源类型': 'SEMI_FINISHED', '来源批次号': 'CLK-W-20261029-1151',
        '目标批次号': 'CLK-W-20261029-92940', '计划ID': 'e13710fc-4fd9-42c7-867d-6470b97ba6e0',
        '来源库存单位': 'kg', '来源批次单位': 'kg', '目标批次单位': 'kg',
        '来源工序序': 1, '目标工序序': 2, '来源批次状态': 'COMPLETED',
        '目标批次状态': 'COMPLETED', '来源库存入账量': Decimal('1'),
        '来源库存已用量': Decimal('1'), '已过账消耗合计': Decimal('1'),
        '期间已过账消耗合计': Decimal('1'),
        '已过账消耗行数': 1, '来源库存批次数': 1,
    }
    base.update(overrides)
    return base


def test_posted_mc_flow_is_quantity_conserving_and_namespaced():
    edges, gaps = assemble_wip_plan_flows([row()], 'F006')
    assert not gaps
    assert edges[0]['流量'] == Decimal('1')
    assert edges[0]['MC凭证'] == 'MC:3986'
    assert edges[0]['来源节点'] != edges[0]['去向节点']


def test_fail_closed_for_unsettled_wrong_unit_and_overconsumed_source():
    edges, gaps = assemble_wip_plan_flows([row(来源库存单位='box')], 'F006')
    assert not edges and '单位' in gaps[0]['missing'][0]
    edges, gaps = assemble_wip_plan_flows([row(来源库存已用量=Decimal('2'))], 'F006')
    assert not edges and '守恒' in gaps[0]['missing'][0]
    edges, gaps = assemble_wip_plan_flows([row(来源类型='SFI_OUT')], 'F006')
    assert not edges and 'SEMI_FINISHED' in gaps[0]['missing'][0]


def test_sql_is_date_scoped_to_posted_at_and_has_tenant_and_soft_delete_guards():
    sql = WIP_PLAN_FLOW_SQL.lower()
    assert "mc.interim_settled_at" in sql
    assert "mc.factory_id = $1" in sql
    assert "mc.deleted_at is null" in sql
    assert "source_doc_type = 'production_batch'" in sql
    assert "source_type = 'semi_finished'" in sql
    assert "srcpb.production_plan_id" in sql
    assert 'unit_price' not in sql and 'total_cost' not in sql


def test_engine_emits_unit_isolated_sankey_with_whole_chart_drilldown_disabled():
    dataset = engine.Dataset(pd.DataFrame([
        {'过账日期': '2026-10-29', '来源节点': 'S', '去向节点': 'T', '流量': Decimal('1'),
         '单位': 'kg', 'MC凭证': 'MC:3986'},
    ]), 'wip-plan-flow · 普通Web在制过账流')
    charts, gaps = engine.wip_plan_flow_candidates(dataset, type('R', (), {'include_optional': True})())
    assert not gaps
    assert charts[0]['chart']['chartType'] == 'sankey'
    assert charts[0]['engine']['pointDrilldown'] is False
    assert charts[0]['chart']['echartsOption']['series'][0]['links'][0]['value'] == 1.0
    assert charts[0]['tableData']['rows'][0][-1] == 'MC:3986'


def test_engine_candidate_details_preserve_original_mc_rows_without_point_drilldown():
    from . import details
    dataset = engine.Dataset(pd.DataFrame([row()]), 'wip-plan-flow · 普通Web在制过账流')
    edges, gaps = assemble_wip_plan_flows([row()], 'F006')
    assert not gaps
    source = engine.Dataset(pd.DataFrame(edges), dataset.source)
    candidates, build_gaps = engine.wip_plan_flow_candidates(source, type('R', (), {'include_optional': True})())
    assert not build_gaps
    details.clear_cache()
    request = type('Request', (), {'period_type': 'all', 'year': 2026, 'start_month': 1, 'end_month': 12})()
    owner = ('F006', 1, 'factory_super_admin')
    registered = details.register(candidates, [source], request, owner, True)
    assert registered[0]['detailContext']['drilldown'] is False
    payload = details.page({'context_id': registered[0]['detailContext']['id'], 'page': 1, 'page_size': 10}, owner, True, True)
    rows = payload['data']['rows']
    assert any(row.get('MC凭证') == 'MC:3986' for row in rows)


def test_engine_full_build_keeps_wip_plan_flow_as_business_source_without_finance_gap():
    dataset = engine.Dataset(pd.DataFrame([{
        '过账日期': '2026-10-29', '来源节点': 'S', '去向节点': 'T', '流量': Decimal('1'),
        '单位': 'kg', 'MC凭证': 'MC:3986',
    }]), 'wip-plan-flow · 普通Web在制过账流')
    request = type('Request', (), {'period_type': 'all', 'year': 2026, 'start_month': 1,
                                   'end_month': 12, 'include_optional': False,
                                   'guided': False, 'analysis_subject': None})()
    candidates, gaps = engine.build([dataset], request, False)
    assert not any(item['id'].startswith('wip-plan-flow.plan-flow') for item in candidates)
    request.include_optional = True
    candidates, gaps = engine.build([dataset], request, False)
    assert any(item['id'].startswith('wip-plan-flow.plan-flow') for item in candidates)
    assert not any('finance' in str(gap.get('id')) for gap in gaps)


def test_mixed_units_full_engine_local_and_shared_detail_are_isolated_without_prices():
    """The exact WIP family keeps MC/plan/batch evidence while scoping units."""
    import asyncio
    import asyncpg

    source_name = 'wip-plan-flow · 普通Web在制过账流'
    frame = pd.DataFrame([
        {**row(), '单位': 'kg', '流量': Decimal('1'), '来源节点': 'CLK-KG-S', '去向节点': 'CLK-KG-T',
         'MC凭证': 'MC:3986', '来源库存批次ID': '1409914', '目标生产批次ID': 11009,
         '计划ID': 'plan-kg', '来源批次号': 'CLK-KG-S', '目标批次号': 'CLK-KG-T'},
        {**row(MC凭证ID=4986), '单位': '箱', '流量': Decimal('2'), '来源节点': 'CLK-BOX-S', '去向节点': 'CLK-BOX-T',
         'MC凭证': 'MC:4986', '来源库存批次ID': '2409914', '目标生产批次ID': 21009,
         '计划ID': 'plan-box', '来源批次号': 'CLK-BOX-S', '目标批次号': 'CLK-BOX-T',
         '来源库存单位': '箱', '来源批次单位': '箱', '目标批次单位': '箱'},
    ])
    request = type('R', (), {'period_type': 'all', 'year': 2026, 'start_month': 1,
                             'end_month': 12, 'include_optional': True, 'guided': False,
                             'analysis_subject': None})()
    dataset = engine.Dataset(frame, source_name)
    candidates, gaps = engine.build([dataset], request, False)
    wip_candidates = [c for c in candidates if c.get('analysis', {}).get('familyId') == 'wip-plan-flow.plan-flow']
    assert not gaps and {c['unit'] for c in wip_candidates} == {'kg', '箱'}
    owner = ('F006', 9981, 'factory_super_admin')
    for candidate in details.register(wip_candidates, [dataset], request, owner, False):
        page = details.page({'context_id': candidate['detailContext']['id'], 'page': 1, 'page_size': 20},
                            owner, False, True)
        rows = page['data']['rows']
        assert {str(item.get('单位')) for item in rows} == {candidate['unit']}
        assert any(item.get('MC凭证') in {'MC:3986', 'MC:4986'} for item in rows)
        assert any(item.get('来源节点') and item.get('去向节点') for item in rows)
        assert not any(any(token in key for token in ('金额', '价格', '成本')) for item in rows for key in item)

    async def shared_check():
        dsn = _pg_dsn()
        schema = 'wip_units_' + uuid.uuid4().hex[:12]
        conn = await asyncpg.connect(dsn)
        try:
            await conn.execute(f'CREATE SCHEMA {schema}; SET search_path TO {schema},public')
            migration_path = Path(__file__).resolve().parents[3] / 'smartbi' / 'database' / 'migrations' / 'V20261101_18__factory_chart_detail_contexts.sql'
            await conn.execute(migration_path.read_text(encoding='utf-8'))
            await conn.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
            await conn.execute(f'GRANT USAGE ON SCHEMA {schema} TO smartbi_user')
            async def setup(pool_conn):
                await pool_conn.execute('SET ROLE smartbi_user')
                await pool_conn.execute(f'SET search_path TO {schema},public')
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=1,
                                              server_settings={'search_path': f'{schema},public'}, setup=setup)
            old_pool = detail_store.config.get_pg_pool
            detail_store.config.get_pg_pool = lambda: pool
            try:
                registered = await detail_store.register_shared(wip_candidates, [dataset], request, owner, False)
                assert len(registered) == 2
                for candidate in registered:
                    page = await detail_store.page_shared(
                        {'context_id': candidate['detailContext']['id'], 'page': 1, 'page_size': 20},
                        owner, False, True)
                    rows = page['data']['rows']
                    assert {str(item.get('单位')) for item in rows} == {candidate['unit']}
                    assert any(item.get('MC凭证') for item in rows)
                    assert any(item.get('来源节点') and item.get('去向节点') for item in rows)
                    assert not any(any(token in key for token in ('金额', '价格', '成本')) for item in rows for key in item)
            finally:
                detail_store.config.get_pg_pool = old_pool
                await pool.close()
        finally:
            await conn.execute(f'DROP SCHEMA IF EXISTS {schema} CASCADE')
            await conn.close()

    asyncio.run(shared_check())


def _pg_dsn():
    value = os.getenv('DETAIL_STORE_TEST_PG_URL') or os.getenv('COMMERCE_JOINT_TEST_PG_URL')
    if not value:
        import pytest
        pytest.skip('local disposable PostgreSQL DSN required')
    parsed = urlparse(value)
    assert parsed.hostname in {'127.0.0.1', 'localhost'} and parsed.port == 55439
    if parsed.port == 55439:
        assert parsed.path == '/cretas_sc28_disposable_test'
    return value


def test_real_pg_wip_plan_flow_matrix():
    """Execute the production SQL against an isolated local PostgreSQL schema."""
    import asyncio
    import asyncpg

    async def run():
        dsn = _pg_dsn()
        schema = 'wip_plan_' + uuid.uuid4().hex[:12]
        conn = await asyncpg.connect(dsn)
        try:
            await conn.execute(f'CREATE SCHEMA {schema}')
            await conn.execute(f'SET search_path TO {schema},public')
            await conn.execute('''
                CREATE TABLE production_plans (
                  id text PRIMARY KEY, factory_id text NOT NULL, status text, deleted_at timestamp
                );
                CREATE TABLE production_batches (
                  id bigint PRIMARY KEY, factory_id text NOT NULL, batch_number text,
                  production_plan_id text, unit text, status text, product_name text, deleted_at timestamptz
                );
                CREATE TABLE material_batches (
                  id text PRIMARY KEY, factory_id text NOT NULL, source_doc_type text,
                  source_doc_id text, receipt_quantity numeric, used_quantity numeric,
                  quantity_unit text, deleted_at timestamptz
                );
                CREATE TABLE material_consumptions (
                  id integer PRIMARY KEY, factory_id text NOT NULL, production_plan_id text,
                  production_batch_id bigint, batch_id text, quantity numeric,
                  source_type text, interim_settled_at timestamp, deleted_at timestamp
                );
                CREATE TABLE process_sheet_rows (
                  id bigint PRIMARY KEY, factory_id text NOT NULL, batch_id bigint,
                  process_order integer, submission_status text, interim_settled_at timestamp,
                  deleted_at timestamp
                );
            ''')
            await conn.execute('''
                INSERT INTO production_batches VALUES
                  (11008,'F006','CLK-W-20261029-1151','plan6','kg','COMPLETED','源',NULL),
                  (11009,'F006','CLK-W-20261029-92940','plan6','kg','COMPLETED','目标',NULL),
                  (21008,'F007','F7-S','plan7','kg','COMPLETED','F7源',NULL),
                  (21009,'F007','F7-T','plan7','kg','COMPLETED','F7目标',NULL);
                INSERT INTO production_plans VALUES
                  ('plan6','F006','COMPLETED',NULL), ('plan7','F007','IN_PROGRESS',NULL);
                INSERT INTO material_batches VALUES
                      ('mb6','F006','PRODUCTION_BATCH','11008',2,2,'kg',NULL),
                  ('mb7','F007','PRODUCTION_BATCH','21008',2,2,'kg',NULL),
                  ('mb_bad','F006','PRODUCTION_BATCH','99999',1,1,'kg',NULL),
                  ('mb_deleted','F006','PRODUCTION_BATCH','11008',1,1,'kg','2026-10-30');
                INSERT INTO process_sheet_rows VALUES
                  (1,'F006',11008,1,'SUBMITTED','2026-10-29',NULL),
                  (2,'F006',11009,2,'SUBMITTED','2026-10-29',NULL),
                  (3,'F007',21008,1,'SUBMITTED','2026-10-29',NULL),
                  (4,'F007',21009,2,'SUBMITTED','2026-10-29',NULL);
                INSERT INTO material_consumptions VALUES
                      (3986,'F006',NULL,11009,'mb6',1,'SEMI_FINISHED','2026-10-29',NULL),
                      (4991,'F006',NULL,11009,'mb6',1,'SEMI_FINISHED','2026-11-01',NULL),
                  (4986,'F007',NULL,21009,'mb7',2,'SEMI_FINISHED','2026-10-29',NULL),
                  (4987,'F006',NULL,11009,'mb6',1,'SEMI_FINISHED',NULL,NULL),
                  (4988,'F006',NULL,11009,'mb6',1,'SFI_OUT','2026-10-29',NULL),
                  (4989,'F006',NULL,11009,'mb6',1,'SEMI_FINISHED','2026-10-29','2026-10-30');
            ''')
            await conn.execute("SELECT set_config('app.factory_id',$1,true)", 'F006')
            rows = [dict(r) for r in await conn.fetch(WIP_PLAN_FLOW_SQL, 'F006', None, None)]
            assert [r['MC凭证ID'] for r in rows] == [3986, 4991]
            assert rows[0]['过账日期'].date().isoformat() == '2026-10-29'
            edges, gaps = assemble_wip_plan_flows(rows, 'F006')
            assert not gaps and {edge['MC凭证'] for edge in edges} == {'MC:3986', 'MC:4991'}

            async def query_ids(start=None, end=None):
                return [r['MC凭证ID'] for r in await conn.fetch(WIP_PLAN_FLOW_SQL, 'F006', start, end)]

            assert await query_ids(date(2026, 10, 1), date(2026, 10, 31)) == [3986]

            # Each mutation is reverted before the next one, so every case is
            # evaluated against the same one-row valid baseline.
            await conn.execute("UPDATE production_batches SET unit='box' WHERE id=11009")
            assert await query_ids() == []
            await conn.execute("UPDATE production_batches SET unit='kg' WHERE id=11009")
            await conn.execute("UPDATE material_batches SET source_doc_id='bad-key' WHERE id='mb6'")
            assert await query_ids() == []
            await conn.execute("UPDATE material_batches SET source_doc_id='11008' WHERE id='mb6'")
            await conn.execute("UPDATE production_batches SET deleted_at='2026-10-30' WHERE id=11009")
            assert await query_ids() == []
            await conn.execute("UPDATE production_batches SET deleted_at=NULL WHERE id=11009")
            await conn.execute("UPDATE process_sheet_rows SET deleted_at='2026-10-30' WHERE id=2")
            assert await query_ids() == []
            await conn.execute("UPDATE process_sheet_rows SET deleted_at=NULL WHERE id=2")
            await conn.execute("UPDATE material_batches SET deleted_at='2026-10-30' WHERE id='mb6'")
            assert await query_ids() == []
            await conn.execute("UPDATE material_batches SET deleted_at=NULL WHERE id='mb6'")
            await conn.execute("UPDATE process_sheet_rows SET process_order=0 WHERE id=2")
            assert await query_ids() == []
            await conn.execute("UPDATE process_sheet_rows SET process_order=2 WHERE id=2")
            await conn.execute("UPDATE production_plans SET status='CANCELLED' WHERE id='plan6'")
            assert await query_ids() == []
            await conn.execute("UPDATE production_plans SET status='COMPLETED' WHERE id='plan6'")
            await conn.execute("UPDATE production_plans SET factory_id='F007' WHERE id='plan6'")
            assert await query_ids() == []
            await conn.execute("UPDATE production_plans SET factory_id='F006' WHERE id='plan6'")
            await conn.execute("UPDATE production_batches SET factory_id='F007' WHERE id=11009")
            assert await query_ids() == []
            await conn.execute("UPDATE production_batches SET factory_id='F006' WHERE id=11009")
            await conn.execute("UPDATE production_batches SET status='CANCELLED' WHERE id=11009")
            assert await query_ids() == []
            await conn.execute("UPDATE production_batches SET status='COMPLETED' WHERE id=11009")
            await conn.execute("UPDATE material_consumptions SET quantity=2 WHERE id=3986")
            assert await query_ids() == []
            await conn.execute("UPDATE material_consumptions SET quantity=1 WHERE id=3986")
            await conn.execute("INSERT INTO material_consumptions VALUES (4990,'F006',NULL,11009,'mb6',1,'SEMI_FINISHED','2026-10-29',NULL)")
            assert await query_ids() == []
            await conn.execute("DELETE FROM material_consumptions WHERE id=4990")
            assert await query_ids(date(2026, 10, 30), date(2026, 10, 31)) == []

            # Exercise the actual shared PostgreSQL detail path, not only the
            # process-local details cache.  The migration is installed in this
            # same isolated schema and the app role reads the immutable rows.
            await conn.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='smartbi_user') THEN CREATE ROLE smartbi_user NOLOGIN; END IF; END $$")
            migration_path = Path(__file__).resolve().parents[3] / 'smartbi' / 'database' / 'migrations' / 'V20261101_18__factory_chart_detail_contexts.sql'
            migration = migration_path.read_text(encoding='utf-8')
            await conn.execute(migration)
            await conn.execute(f'GRANT USAGE ON SCHEMA {schema} TO smartbi_user')
            async def setup(pool_conn):
                await pool_conn.execute('SET ROLE smartbi_user')
                await pool_conn.execute(f'SET search_path TO {schema},public')
            pool = await asyncpg.create_pool(dsn, min_size=1, max_size=1,
                                              server_settings={'search_path': f'{schema},public'}, setup=setup)
            old_pool = detail_store.config.get_pg_pool
            detail_store.config.get_pg_pool = lambda: pool
            try:
                source = engine.Dataset(pd.DataFrame(edges), 'wip-plan-flow · 普通Web在制过账流')
                candidates, candidate_gaps = engine.wip_plan_flow_candidates(source, type('R', (), {'include_optional': True})())
                assert not candidate_gaps
                owner = ('F006', 9981, 'factory_super_admin')
                request = type('R', (), {'period_type': 'all', 'year': 2026,
                                         'start_month': 1, 'end_month': 12})()
                registered = await detail_store.register_shared(candidates, [source], request, owner, True)
                context_id = registered[0]['detailContext']['id']
                page = await detail_store.page_shared({'context_id': context_id, 'page': 1, 'page_size': 20},
                                                       owner, True, True)
                assert any(item.get('MC凭证') == 'MC:3986' for item in page['data']['rows'])
                assert registered[0]['detailContext']['drilldown'] is False
            finally:
                detail_store.config.get_pg_pool = old_pool
                await pool.close()
        finally:
            await conn.execute(f'DROP SCHEMA IF EXISTS {schema} CASCADE')
            await conn.close()

    asyncio.run(run())

