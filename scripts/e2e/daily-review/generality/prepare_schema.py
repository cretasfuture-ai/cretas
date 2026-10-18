"""Apply existing forward migrations missing from the historical P37 fixture only."""
from pathlib import Path
import hashlib
import json
import sys
import psycopg

assert sys.argv[1:] == ['NON_PRODUCTION_ONLY']
root = Path(__file__).resolve().parents[4]
out = Path.home() / 'Documents/cretas-connect-20261015/factory-data-p37'
runtime = json.loads((out / 'runtime.json').read_text())
assert runtime['owner'] == 'P37-FACTORY-GENERALITY-20261018'
assert runtime['database'] == 'p37_factory_generality_20261018'
migration = root / 'backend/java/cretas-api/src/main/resources/db/flyway/V20261103_63__assembly_binding_unapplied_version.sql'
pricing = root / 'backend/java/cretas-api/src/main/resources/db/flyway/V20261103_65__sales_quote_gross_price_snapshot.sql'
with psycopg.connect(host='127.0.0.1', port=55447, dbname=runtime['database'], user='assembly_executor') as db:
    assert db.execute('select current_database()').fetchone()[0] == runtime['database']
    before = db.execute('select row_to_json(b) from factory_blueprint_bindings b order by id').fetchall()
    history = db.execute('select row_to_json(h) from flyway_schema_history h order by installed_rank').fetchall()
    db.execute(migration.read_text(encoding='utf-8'))
    assert before == db.execute('select row_to_json(b) from factory_blueprint_bindings b order by id').fetchall()
    assert history == db.execute('select row_to_json(h) from flyway_schema_history h order by installed_rank').fetchall()
    nullable = db.execute("select is_nullable from information_schema.columns where table_schema='public' and table_name='factory_blueprint_bindings' and column_name='applied_version'").fetchone()[0]
    assert nullable == 'YES'
    orders = db.execute("select to_jsonb(i) - 'quoted_gross_unit_price' from sales_order_items i order by id").fetchall()
    has_pricing = db.execute("select count(*) from information_schema.columns where table_schema='public' and table_name='sales_order_items' and column_name='quoted_gross_unit_price'").fetchone()[0]
    if not has_pricing:
        db.execute(pricing.read_text(encoding='utf-8'))
    assert orders == db.execute("select to_jsonb(i) - 'quoted_gross_unit_price' from sales_order_items i order by id").fetchall()
receipt = {'status': 'PASS', 'database': runtime['database'], 'productionTouched': False,
           'existingRowsUnchanged': True, 'migration': str(migration.relative_to(root)),
           'sha256': hashlib.sha256(migration.read_bytes()).hexdigest(),
           'flywayHistoryModified': False, 'fullFlywayUpgradeClaimed': False}
receipt['additionalMigration'] = {'file': str(pricing.relative_to(root)), 'sha256': hashlib.sha256(pricing.read_bytes()).hexdigest(), 'appliedNow': not bool(has_pricing)}
(out / 'fixture-schema.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt))
