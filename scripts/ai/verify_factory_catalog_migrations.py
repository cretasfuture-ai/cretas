"""Execute catalog SQL on an explicitly supplied disposable PostgreSQL database.

Uses an isolated schema and rolls back all fixtures and migration effects.
Never accepts a non-loopback host or a non-test database.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import uuid
import re
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import psycopg2
from psycopg2 import sql
from psycopg2.extensions import parse_dsn

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend/java/cretas-api/src/main/resources/db/flyway"


def verify(dsn: str) -> None:
    config = parse_dsn(dsn)
    if config.get("host") not in {"127.0.0.1", "localhost"} or "test" not in config.get("dbname", ""):
        raise ValueError("Only explicit loopback test databases are allowed")
    with psycopg2.connect(dsn) as connection:
        try:
            with connection.cursor() as cur:
                schema = "factory_catalog_verify_" + uuid.uuid4().hex
                cur.execute(sql.SQL("CREATE SCHEMA {}; SET LOCAL search_path TO {}")
                            .format(sql.Identifier(schema), sql.Identifier(schema)))
                cur.execute("""
CREATE TABLE ai_intent_configs (
 id text PRIMARY KEY, factory_id text, intent_code text, business_type text,
 tool_name text, description text, required_roles jsonb, metadata jsonb, keywords jsonb,
 previous_snapshot jsonb, is_active boolean, deleted_at timestamp, updated_at timestamp,
 requires_approval boolean, required_permission text);
CREATE TABLE ai_intent_phrase_registry (
 id serial PRIMARY KEY, factory_id text, business_type text, normalized_text text,
 intent_code text, status text, updated_at timestamp,
 UNIQUE(factory_id, business_type, normalized_text));
CREATE TABLE smart_bi_skill (name text PRIMARY KEY, tools jsonb, config jsonb,
 enabled boolean, updated_at timestamp);
CREATE TABLE material_batches (id text PRIMARY KEY, factory_id text,
 source_doc_type text, source_event_key text, deleted_at timestamp);
INSERT INTO ai_intent_configs VALUES
 ('retired', 'A', 'RESTAURANT_DAILY_REVENUE', 'RESTAURANT', 'restaurant_x', 'old',
 '["admin"]', '{}', '[]', NULL, true, NULL, NULL, true, 'sensitive:write'),
 ('retain', 'A', 'FOOD_SAFETY_RECALL', 'COMMON', 'old_write', 'old',
 '["quality_admin"]', '{"tenant_setting":true}', '[]', NULL, false, NULL, NULL, true, 'quality:read'),
 ('alias', NULL, 'EFFICIENCY_COMPARE_ALIAS', 'COMMON', 'alias_tool', 'alias',
 '["weak"]', '{}', '["同比"]', NULL, true, NULL, NULL, false, NULL),
 ('target', NULL, 'EFFICIENCY_COMPARISON', 'COMMON', 'efficiency_comparison', 'target',
 '["strong"]', '{}', '["效率"]', NULL, true, NULL, NULL, true, 'strong:read'),
 ('custom', 'A', 'MY_FACTORY_CUSTOM', 'FACTORY', 'custom', 'custom',
 '["own"]', '{}', '[]', NULL, true, NULL, NULL, false, NULL),
 ('governance', NULL, 'GOVERNANCE_SKILL_COMPOSE', 'RESTAURANT', 'skill_compose', 'old',
 '["factory_super_admin"]', '{}', '[]', NULL, true, NULL, NULL, true, NULL);
INSERT INTO ai_intent_phrase_registry(factory_id,business_type,normalized_text,intent_code,status) VALUES
 ('A','RESTAURANT','重复短语','CAMERA_STREAMS','APPROVED'),
 ('A','FACTORY','重复短语','OTHER_CANONICAL','APPROVED'),
 ('A','RESTAURANT','独立短语','CAMERA_STREAMS','PENDING'),
 ('B','RESTAURANT','重复短语','CAMERA_STREAMS','APPROVED'),
 ('A','RESTAURANT','旧餐饮','RESTAURANT_OLD_NOT_IN_SNAPSHOT','APPROVED'),
 ('A','RESTAURANT','旧别名','EFFICIENCY_COMPARE_ALIAS','APPROVED');
INSERT INTO smart_bi_skill VALUES
 ('restaurant-old','["restaurant_x"]','{}',true,NULL),
 ('food-safety-recall','["recall_batch_freeze"]','{"steps":[{"tool":"recall_batch_freeze"}],"tenant_setting":true}',false,NULL),
 ('custom-shared','["material_batch_query"]','{}',true,NULL);
ALTER TABLE ai_intent_configs ADD COLUMN sensitivity_level text;
""")
                retirement = (MIGRATIONS / "V20261104_51__factory_intent_catalog_retirement.sql").read_text(encoding="utf-8")
                cur.execute(retirement)
                cur.execute("SELECT is_active, deleted_at IS NOT NULL, previous_snapshot->>'tool_name' FROM ai_intent_configs WHERE id='retired'")
                assert cur.fetchone() == (False, True, "restaurant_x")
                cur.execute("SELECT business_type,tool_name,is_active,requires_approval,required_permission,required_roles,metadata->'tenant_setting' FROM ai_intent_configs WHERE id='retain'")
                assert cur.fetchone() == ("COMMON", None, False, True, "quality:read", ["quality_admin"], True)
                cur.execute("SELECT required_roles,requires_approval,keywords FROM ai_intent_configs WHERE id='target'")
                roles, approval, keywords = cur.fetchone()
                assert roles == ["strong"] and approval and set(keywords) == {"同比", "效率"}
                cur.execute("SELECT required_roles FROM ai_intent_configs WHERE id='governance'")
                assert cur.fetchone()[0] == ["super_admin", "platform_admin", "developer"]
                cur.execute("SELECT tool_name,is_active,previous_snapshot FROM ai_intent_configs WHERE id='custom'")
                assert cur.fetchone() == ("custom", True, None)
                cur.execute("SELECT business_type,status FROM ai_intent_phrase_registry WHERE factory_id='A' AND intent_code='CAMERA_STREAMS' ORDER BY normalized_text")
                assert set(cur.fetchall()) == {("RESTAURANT", "REJECTED"), ("FACTORY", "PENDING")}
                cur.execute("SELECT status FROM ai_intent_phrase_registry WHERE intent_code LIKE 'RESTAURANT%'")
                assert cur.fetchone()[0] == "REJECTED"
                cur.execute("SELECT business_type,status FROM ai_intent_phrase_registry WHERE factory_id='B'")
                assert cur.fetchone() == ("FACTORY", "APPROVED")
                cur.execute("SELECT intent_code,business_type,status FROM ai_intent_phrase_registry WHERE normalized_text='旧别名'")
                assert cur.fetchone() == ("EFFICIENCY_COMPARISON", "COMMON", "APPROVED")
                cur.execute("SELECT enabled,tools,config FROM smart_bi_skill WHERE name='food-safety-recall'")
                enabled, tools, config = cur.fetchone()
                assert not enabled and "recall_batch_freeze" not in tools and config == {"tenant_setting": True}
                # Re-execution preserves original snapshots and does not re-enable rows.
                cur.execute("DROP TABLE factory_catalog_patch")
                cur.execute(retirement)
                cur.execute("SELECT previous_snapshot->>'tool_name',is_active FROM ai_intent_configs WHERE id='retired'")
                assert cur.fetchone() == ("restaurant_x", False)
                # Legacy Flyway schemas did not declare these two Entity columns.
                # Exercise the actual migration against that shape, not just a fixture with them.
                cur.execute("ALTER TABLE ai_intent_configs DROP COLUMN metadata; ALTER TABLE ai_intent_configs DROP COLUMN previous_snapshot; ALTER TABLE ai_intent_configs ALTER COLUMN keywords TYPE text USING keywords::text; ALTER TABLE ai_intent_configs ALTER COLUMN required_roles TYPE text USING required_roles::text; DROP TABLE factory_catalog_patch")
                cur.execute(retirement)
                cur.execute("SELECT pg_typeof(metadata)::text, pg_typeof(previous_snapshot)::text FROM ai_intent_configs LIMIT 1")
                assert cur.fetchone() == ("jsonb", "jsonb")
                # Reuse the existing, live-row-only source-event index. A redundant
                # gift-specific lifetime index would break soft-delete/re-receive.
                existing = (MIGRATIONS / "V20261101_38__soft_delete_batch3a_partial_unique_indexes.sql").read_text(encoding="utf-8")
                definition = re.search(r"CREATE UNIQUE INDEX uq_material_batch_source_event\s+ON public\.material_batches \(factory_id, source_doc_type, source_event_key\)\s+WHERE source_event_key IS NOT NULL AND deleted_at IS NULL;", existing)
                assert definition, "Existing live-row source-event index contract changed"
                cur.execute(definition.group().replace("public.material_batches", "material_batches"))
                cur.execute("INSERT INTO material_batches(id,factory_id,source_doc_type,source_event_key) VALUES ('a','A','FREE_GIFT','DOC#line:1'),('b','B','FREE_GIFT','DOC#line:1'),('c','A','FREE_GIFT','DOC#line:2'),('d','A','PURCHASE','DOC#line:1'),('e','A','PURCHASE','DOC#line:2'),('f','A','FREE_GIFT',NULL)")
                cur.execute("SAVEPOINT duplicate_gift")
                try:
                    cur.execute("INSERT INTO material_batches(id,factory_id,source_doc_type,source_event_key) VALUES ('duplicate','A','FREE_GIFT','DOC#line:1')")
                    raise AssertionError("Duplicate gift source line was accepted")
                except psycopg2.errors.UniqueViolation:
                    cur.execute("ROLLBACK TO SAVEPOINT duplicate_gift")
                cur.execute("SELECT count(*) FROM material_batches")
                assert cur.fetchone()[0] == 6
                cur.execute("UPDATE material_batches SET deleted_at=CURRENT_TIMESTAMP WHERE id='a'")
                cur.execute("INSERT INTO material_batches(id,factory_id,source_doc_type,source_event_key) VALUES ('rereceive','A','FREE_GIFT','DOC#line:1')")
                cur.execute("SELECT count(*) FROM material_batches WHERE factory_id='A' AND source_doc_type='FREE_GIFT' AND source_event_key='DOC#line:1' AND deleted_at IS NULL")
                assert cur.fetchone()[0] == 1
                print("PASS: PostgreSQL catalog migration, permission preservation, phrase collisions, rerun audit, gift uniqueness")
        finally:
            connection.rollback()


def verify_gift_concurrency(dsn: str) -> None:
    """Real PostgreSQL two-transaction proof for the per-material lock/rebuild protocol."""
    schema = "gift_concurrency_verify_" + uuid.uuid4().hex
    ready, blocked = Event(), Event()
    with psycopg2.connect(dsn) as admin:
        with admin.cursor() as cur:
            cur.execute(sql.SQL("CREATE SCHEMA {}; SET LOCAL search_path TO {}")
                        .format(sql.Identifier(schema), sql.Identifier(schema)))
            cur.execute("CREATE TABLE raw_material_types(id text PRIMARY KEY, factory_id text, moving_avg_price numeric)")
            cur.execute("CREATE TABLE material_batches(id text PRIMARY KEY, factory_id text, material_type_id text, receipt_quantity numeric, unit_price numeric)")
            cur.execute("INSERT INTO raw_material_types VALUES ('MAT','A',10)")
            cur.execute("INSERT INTO material_batches VALUES ('purchase','A','MAT',10,10)")
    def open_transaction():
        connection = psycopg2.connect(dsn)
        with connection.cursor() as cur:
            cur.execute(sql.SQL("SET LOCAL search_path TO {}").format(sql.Identifier(schema)))
        return connection
    def rebuild(cur, key):
        cur.execute("INSERT INTO material_batches VALUES (%s,'A','MAT',10,0)", (key,))
        cur.execute("UPDATE raw_material_types SET moving_avg_price=(SELECT round(sum(receipt_quantity*unit_price)/sum(receipt_quantity),4) FROM material_batches WHERE factory_id='A' AND material_type_id='MAT') WHERE id='MAT' AND factory_id='A'")
    def first():
        with open_transaction() as connection:
            with connection.cursor() as cur:
                cur.execute("SELECT id FROM raw_material_types WHERE factory_id='A' AND id IN ('MAT') ORDER BY id FOR UPDATE")
                ready.set()
                if not blocked.wait(10):
                    raise AssertionError("Concurrent transaction did not prove the material lock")
                rebuild(cur, "gift-1")
    def second():
        if not ready.wait(10):
            raise AssertionError("First transaction did not acquire material lock")
        connection = open_transaction()
        try:
            with connection.cursor() as cur:
                cur.execute("SET LOCAL lock_timeout = '100ms'")
                try:
                    cur.execute("SELECT id FROM raw_material_types WHERE factory_id='A' AND id IN ('MAT') ORDER BY id FOR UPDATE")
                    raise AssertionError("Concurrent receipt bypassed the held material lock")
                except psycopg2.errors.LockNotAvailable:
                    connection.rollback()
                    blocked.set()
        finally:
            connection.close()
        with open_transaction() as connection:
            with connection.cursor() as cur:
                cur.execute("SET LOCAL lock_timeout = '10s'")
                cur.execute("SELECT id FROM raw_material_types WHERE factory_id='A' AND id IN ('MAT') ORDER BY id FOR UPDATE")
                rebuild(cur, "gift-2")
    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            a, b = executor.submit(first), executor.submit(second)
            a.result(timeout=20); b.result(timeout=20)
        with open_transaction() as connection:
            with connection.cursor() as cur:
                cur.execute("SELECT moving_avg_price FROM raw_material_types WHERE id='MAT'")
                assert str(cur.fetchone()[0]) == "3.3333"
                cur.execute("SELECT count(*) FROM material_batches")
                assert cur.fetchone()[0] == 3
        print("PASS: PostgreSQL concurrent gift lines serialize and WAC includes both zero-cost layers")
    finally:
        with psycopg2.connect(dsn) as admin:
            with admin.cursor() as cur:
                cur.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dsn", required=True)
    args = parser.parse_args()
    verify(args.dsn)
    verify_gift_concurrency(args.dsn)
