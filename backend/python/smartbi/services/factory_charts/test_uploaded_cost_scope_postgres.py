"""Opt-in PostgreSQL proof for uploaded manufacturing-cost projection.

This test deliberately uses the real ``financial_dashboard._load_data`` SQL
against a disposable local PostgreSQL cluster.  Only the Java authority reply
is mocked; the upload tables, JSONB rows, tenant/status predicates, and SQL
projection are real.
"""

import json
import os
import uuid
from types import SimpleNamespace
from urllib.parse import urlparse

import pytest

from smartbi.api import financial_dashboard as dashboard
from smartbi.services.factory_charts import cost_authorization


def _dsn_or_skip():
    dsn = os.getenv("FACTORY_SELECTION_TEST_PG_URL")
    if not dsn:
        pytest.skip("set FACTORY_SELECTION_TEST_PG_URL for disposable PostgreSQL")
    parsed = urlparse(dsn)
    assert parsed.hostname in {"127.0.0.1", "localhost"}, "dedicated local test cluster only"
    assert parsed.port == 55439, "owned disposable cluster only"
    return dsn


def _request(role="factory_super_admin"):
    return SimpleNamespace(
        state=SimpleNamespace(factory_id="F006", user_id=42, role=role),
        headers={"authorization": "Bearer pg-fixture-token"},
    )


def _row(product=None, **values):
    row = {"产量": values.pop("产量", 1), "总成本": values.pop("总成本", 10)}
    if product is not None:
        row["productId"] = product
    row.update(values)
    return row


@pytest.mark.asyncio
async def test_real_pg_jsonb_upload_projection_enforces_product_cost_scope(monkeypatch):
    dsn = _dsn_or_skip()
    asyncpg = pytest.importorskip("asyncpg")
    admin = await asyncpg.connect(dsn)
    schema = "sc28_cost_scope_" + uuid.uuid4().hex
    pool = None
    try:
        await admin.execute(f"CREATE SCHEMA {schema}")
        await admin.execute(f"""
            CREATE TABLE {schema}.smart_bi_pg_excel_uploads (
                id BIGINT PRIMARY KEY,
                factory_id VARCHAR(64) NOT NULL,
                upload_status VARCHAR(32) NOT NULL
            );
            CREATE TABLE {schema}.smart_bi_dynamic_data (
                id BIGSERIAL PRIMARY KEY,
                upload_id BIGINT NOT NULL,
                factory_id VARCHAR(64) NOT NULL,
                row_data JSONB NOT NULL,
                sheet_name VARCHAR(255),
                row_index INTEGER NOT NULL
            );
        """)
        await admin.executemany(
            f"INSERT INTO {schema}.smart_bi_pg_excel_uploads(id, factory_id, upload_status) VALUES($1,$2,$3)",
            [(7001, "F006", "COMPLETED"), (7002, "F006", "PROCESSING")],
        )
        rows = [
            _row("PUBLIC", **{"总成本": 10, "产量": 1}),
            _row("SECRET", **{"总成本": 99, "产量": 2}),
            _row(None, **{"总成本": 30, "产量": 3}),
            _row("PUBLIC", **{"产品ID": "SECRET", "总成本": 88, "产量": 4}),
            {"产品编号": "产品（甲）", "产量": 5, "总成本": 40},
            {"sku编码": "SKU-甲", "产量": 6, "总成本": 50},
        ]
        await admin.executemany(
            f"INSERT INTO {schema}.smart_bi_dynamic_data(upload_id,factory_id,row_data,sheet_name,row_index) VALUES($1,$2,$3::jsonb,$4,$5)",
            [(7001, "F006", json.dumps(row, ensure_ascii=False), "成本", index)
             for index, row in enumerate(rows)]
            + [(7001, "F999", json.dumps(_row("PUBLIC", **{"总成本": 700, "产量": 8}), ensure_ascii=False), "成本", 99)]
            + [(7002, "F006", json.dumps(_row("PUBLIC", **{"总成本": 900}), ensure_ascii=False), "成本", 1)],
        )
        pool = await asyncpg.create_pool(
            dsn, min_size=1, max_size=1,
            server_settings={"search_path": f"{schema},public"},
        )
        monkeypatch.setattr("smartbi.config.get_pg_pool", lambda: pool)
        authority_calls = []
        allowed = {"PUBLIC", "产品（甲）", "SKU-甲"}
        unscoped_allowed = True

        async def java_authority(request, factory_id, product_ids, *, unscoped=False):
            authority_calls.append((factory_id, tuple(sorted(product_ids)), unscoped))
            return cost_authorization.CostAuthorization(
                factory_id, 42, frozenset(allowed), unscoped_allowed,
            )

        monkeypatch.setattr(cost_authorization, "authorize_batches", java_authority)
        frame = await dashboard._load_data(
            dashboard.FinancialDashboardRequest(upload_id=7001), "F006", _request(),
            require_cost_authorization=True,
        )
        assert len(authority_calls) == 1
        assert authority_calls[0][0] == "F006"
        assert authority_calls[0][2] is True
        assert list(frame["产量"]) == [1, 2, 3, 4, 5, 6]
        assert set(frame["productId"].dropna()) == {"PUBLIC", "SECRET"}
        assert frame.loc[frame["productId"] == "PUBLIC", "总成本"].iloc[0] == 10
        assert frame.loc[frame["productId"] == "SECRET", "总成本"].isna().all()
        assert frame.loc[frame["产量"] == 3, "总成本"].iloc[0] == 30
        assert frame.loc[frame["产量"] == 4, "总成本"].isna().all()
        assert set(frame.loc[frame["产量"].isin([5, 6]), "总成本"].tolist()) == {40, 50}

        # An authority response with unscoped=false must hide otherwise
        # unidentified cost rows while still retaining their non-price facts.
        unscoped_allowed = False
        restricted = await dashboard._load_data(
            dashboard.FinancialDashboardRequest(upload_id=7001), "F006", _request(),
            require_cost_authorization=True,
        )
        assert restricted.loc[restricted["产量"] == 3, "总成本"].isna().all()
        assert list(restricted["产量"]) == [1, 2, 3, 4, 5, 6]

        with pytest.raises(dashboard.DataSourceError) as incomplete:
            await dashboard._load_data(
                dashboard.FinancialDashboardRequest(upload_id=7002), "F006", _request(),
                require_cost_authorization=True,
            )
        assert incomplete.value.status_code == 404

        authority_calls.clear()
        quality = await dashboard._load_data(
            dashboard.FinancialDashboardRequest(upload_id=7001), "F006",
            _request("quality_inspector"), require_cost_authorization=True,
        )
        assert authority_calls == []
        assert list(quality["产量"]) == [1, 2, 3, 4, 5, 6]
        assert not any(cost_authorization.is_manufacturing_cost_column(column) for column in quality.columns)
    finally:
        if pool is not None:
            await pool.close()
        await admin.execute(f"DROP SCHEMA IF EXISTS {schema} CASCADE")
        await admin.close()


@pytest.mark.asyncio
async def test_real_pg_process_scope_uses_same_factory_batch_lineage_and_report_key():
    from datetime import date
    from . import sources
    asyncpg = pytest.importorskip('asyncpg')
    connection = await asyncpg.connect(_dsn_or_skip())
    schema = 'sc28_process_scope_' + uuid.uuid4().hex
    try:
        await connection.execute(f'CREATE SCHEMA {schema}')
        await connection.execute(f'SET search_path TO {schema},public')
        await connection.execute('''
            CREATE TABLE fact_production_batch(factory_id text,source_pk text,product_type_id text,
                                               UNIQUE(factory_id,source_pk));
            CREATE TABLE fact_production_report(factory_id text,source_pk text,batch_id text,
                report_date date,process_category text,material_cost numeric,labor_cost numeric,
                cost_source text,updated_at timestamp);
            INSERT INTO fact_production_batch VALUES
                ('F006','B1','PUBLIC'),('F006','B2','SECRET'),('F999','B1','SECRET'),('F999','B3','PUBLIC');
            INSERT INTO fact_production_report VALUES
                ('F006','R1','B1','2026-10-01','mix',10,5,'reported','2026-10-02'),
                ('F006','R2','B1','2026-10-02','mix',20,7,'reported','2026-10-03'),
                ('F006','R3','B2','2026-10-02','mix',99,99,'reported','2026-10-03'),
                ('F006','R4','B3','2026-10-02','mix',88,88,'reported','2026-10-03'),
                ('F999','R5','B1','2026-10-02','mix',77,77,'reported','2026-10-03');
        ''')
        params = ['F006', date(2026,10,1), date(2026,10,31)]
        ids = await connection.fetch(sources.PROCESS_ID_SQL, *params)
        assert [row['来源主键'] for row in ids] == ['R1','R2','R3','R4']
        assert [row['产品ID'] for row in ids] == ['PUBLIC','PUBLIC','SECRET',None]
        assert all('材料成本' not in row and '人工成本' not in row for row in ids)
        scoped = sources.PROCESS_SQL.replace('\nORDER BY report_date',
                    '\n AND product_type_id = ANY($4::text[])\nORDER BY report_date')
        prices = await connection.fetch(scoped, *params, ['PUBLIC'])
        assert [row['来源主键'] for row in prices] == ['R1','R2']
        assert [(row['材料成本'],row['人工成本']) for row in prices] == [(10,5),(20,7)]
    finally:
        await connection.execute(f'DROP SCHEMA {schema} CASCADE')
        await connection.close()
