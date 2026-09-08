"""Replay actual ETL and writer SQL against a disposable PostgreSQL TEMP table.

Set TEST_PSQL_COMMAND to a JSON command array; no persistent schema/data is changed.
"""
import json
import os
import subprocess
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from smartbi.gold.restaurant.restaurant_ops_etl import sync_dim_ingredient
from smartbi.ingestion.platforms.ops_writer import _INGREDIENT_UPSERT_SQL


def pool(conn):
    result = MagicMock()
    result.acquire.return_value.__aenter__ = AsyncMock(return_value=conn)
    result.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    conn.transaction = MagicMock()
    conn.transaction.return_value.__aenter__ = AsyncMock()
    conn.transaction.return_value.__aexit__ = AsyncMock(return_value=False)
    return result


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, list):
        return "ARRAY[" + ",".join(literal(v) for v in value) + "]"
    return "'" + str(value).replace("'", "''") + "'"


@pytest.mark.asyncio
async def test_placeholder_adoption_preserves_fact_identity_and_writer_replay():
    command = os.environ.get("TEST_PSQL_COMMAND")
    if not command:
        pytest.skip("TEST_PSQL_COMMAND is required for real PostgreSQL identity validation")
    src, dst = AsyncMock(), AsyncMock()
    src.fetch.return_value = [dict(id="mrmt_fixture", name="rice", category="grain", code="R",
                                  unit="kg", unit_price=2, moving_avg_price=None,
                                  shelf_life_days=30, storage_type="DRY", is_active=True)]
    dst.fetch.return_value = [{"ingredient_id": 42}]
    with patch("smartbi.gold.restaurant.restaurant_ops_etl._get_latest_real_purchase_prices", AsyncMock(return_value={})):
        assert await sync_dim_ingredient(pool(src), pool(dst), "MOCK_REST") == 1
    adoption = next(c.args for c in dst.execute.await_args_list if "UPDATE dim_ingredient" in c.args[0])
    upsert = dst.fetch.await_args.args
    sql = """BEGIN;
    CREATE TEMP TABLE dim_ingredient (
      ingredient_id bigserial PRIMARY KEY, factory_id text, source_pk text, name text,
      normalized_name text, category text, code text, unit text, unit_price numeric,
      cost_source text, shelf_life_days int, storage_type text, is_active boolean,
      updated_at timestamp DEFAULT now(),
      UNIQUE(factory_id,source_pk), UNIQUE(factory_id,normalized_name));
    INSERT INTO dim_ingredient(ingredient_id,factory_id,source_pk,name,normalized_name,unit)
      VALUES(42,'MOCK_REST','mock_keruyun:rice','rice','rice','kg'),
            (43,'CONTROL','mock_keruyun:rice','rice','rice','kg');
    CREATE TEMP TABLE fact_control(ingredient_id bigint REFERENCES dim_ingredient(ingredient_id));
    INSERT INTO fact_control VALUES(42);
    """
    for index, args in enumerate([adoption, upsert, adoption, upsert,
                                 (_INGREDIENT_UPSERT_SQL, "MOCK_REST", "mock_keruyun:rice", "rice", "rice", "grain", "kg")]):
        sql += f"PREPARE step{index} AS {args[0]};\nEXECUTE step{index}(" + ",".join(literal(a) for a in args[1:]) + ");\n"
    sql += """SELECT json_build_object('count',(SELECT count(*) FROM dim_ingredient),
      'id',(SELECT ingredient_id FROM dim_ingredient WHERE factory_id='MOCK_REST'),
      'pk',(SELECT source_pk FROM dim_ingredient WHERE factory_id='MOCK_REST'),
      'control',(SELECT source_pk FROM dim_ingredient WHERE factory_id='CONTROL'),
      'fact',(SELECT ingredient_id FROM fact_control)); ROLLBACK;"""
    result = subprocess.run(json.loads(command), input=sql, text=True, capture_output=True, check=True)
    record = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{"count"')))
    assert record == dict(count=2, id=42, pk="mrmt_fixture", control="mock_keruyun:rice", fact=42)


@pytest.mark.asyncio
async def test_duplicate_source_names_fail_before_target_writes():
    src, dst = AsyncMock(), AsyncMock()
    src.fetch.return_value = [dict(id="a", name="rice"), dict(id="b", name=" rice ")]
    with patch("smartbi.gold.restaurant.restaurant_ops_etl._get_latest_real_purchase_prices", AsyncMock(return_value={})):
        with pytest.raises(ValueError, match="ambiguous"):
            await sync_dim_ingredient(pool(src), pool(dst), "MOCK_REST")
    dst.execute.assert_not_awaited()
