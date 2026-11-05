#!/usr/bin/env python3
"""Read-only F006 projection. Output is PRIVATE; never commit the exported data.

The source is fixed. Business rows use one read-only repeatable-read snapshot;
catalog and schema fingerprints are checked before/after. Missing/cross-tenant
dependencies fail closed. No restore operation exists here.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import uuid
import os
from pathlib import Path

SOURCE = 'root@47.100.235.168'
DATABASE = 'cretas_prod_db'
STATIC_PARENTS = {'unit_of_measurements', 'enum_dictionary', 'role_definitions', 'permission_registry', 'industry_template_packages', 'purchase_accounting_subjects', 'factory_templates'}
BASELINE_TABLES = {
    'factories','users','departments','role_definitions','permission_registry','enum_dictionary','unit_of_measurements',
    'factory_role_data_scopes','user_menu_permissions','user_module_access','factory_warehouses','factory_warehouse_default',
    'suppliers','customers','raw_material_types','product_types','supplier_materials','supplier_material_purchase_specs',
    'material_packaging_specs','material_packaging_hierarchy','product_packaging_specs','product_unit_conversions',
    'work_processes','product_work_processes','product_process_workflows','product_process_workflow_revisions','product_process_workflow_activations',
    'bom_recipes','bom_recipe_items','bom_versions','approval_workflows','approval_chain_configs','purchase_order_approval_rules',
    'factory_cost_settings','factory_settings','factory_configurations','factory_module_configs','factory_templates','factory_feature_config',
    'factory_default_values','factory_field_visibility','factory_validation_rules','industry_template_packages','purchase_accounting_subjects',
}
GLOBAL_SCOPES = {'enum_dictionary': ['__GLOBAL__']}
DENY_TABLE = re.compile(r'(?:session|token|secret|credential|grant|notification|conversation|tool_|idempotency|operation_log|audit|activity|client_error|telemetry|training|learning|task_queue|outbox|api_key|embedding|vector|attachment|cache|factory_ai_settings|label_qc|vision_|photo)', re.I)
SECRET_FIELD = re.compile(r'(?:password|passwd|token|secret|credential|authorization|bearer|signing.?key|client.?key|api.?key|access.?key|private.?key|salt)', re.I)
CONTACT_FIELD = re.compile(r'(?:address|phone|mobile|email|contact.?person|contact.?name|id.?card|bank.?account|tax.?number|tax.?id|license|legal.?representative|wechat|weixin|(^|_)qq($|_)|webhook|callback|endpoint|url$|uri$)', re.I)
BOUNDARIES = {'factory_id', 'source_factory_id', 'target_factory_id', 'actor_factory_id', 'tenant_id'}
PRIVATE_ROOT = Path('C:/Users/Steve/Documents/F006演示-20261105')
SOURCE_SCRUB_SQL = r"""
CREATE FUNCTION pg_temp.f006_export_scrub(v jsonb, globals text[]) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $scrub$
DECLARE result jsonb; text_value text;
BEGIN
 IF jsonb_typeof(v)='object' THEN
   IF EXISTS (SELECT 1 FROM jsonb_each(v) e WHERE replace(lower(e.key),'_','') IN
       ('factoryid','sourcefactoryid','targetfactoryid','actorfactoryid','tenantid')
       AND e.value <> 'null'::jsonb AND e.value #>> '{}' <> 'F006' AND NOT (e.value #>> '{}' = ANY(globals))) THEN
     RAISE EXCEPTION 'F006 export: cross-tenant JSON boundary';
   END IF;
   SELECT coalesce(jsonb_object_agg(e.key, CASE
      WHEN e.key ~* '(password|passwd|token|secret|credential|authorization|bearer|signing.?key|client.?key|api.?key|access.?key|private.?key|salt|webhook|callback|endpoint|url$|uri$|(^|_)(phone|mobile|email|contact_person|contact_name|id_card|bank_account)($|_(number|address)$))'
      THEN 'null'::jsonb ELSE pg_temp.f006_export_scrub(e.value,globals) END),'{}'::jsonb)
      INTO result FROM jsonb_each(v) e;
   RETURN result;
 ELSIF jsonb_typeof(v)='array' THEN
   SELECT coalesce(jsonb_agg(pg_temp.f006_export_scrub(e.value,globals) ORDER BY e.ordinality),'[]'::jsonb)
      INTO result FROM jsonb_array_elements(v) WITH ORDINALITY e(value,ordinality);
   RETURN result;
 ELSIF jsonb_typeof(v)='string' THEN
   text_value := v #>> '{}';
   IF left(ltrim(text_value),1) IN ('{','[') THEN
     BEGIN RETURN to_jsonb(pg_temp.f006_export_scrub(text_value::jsonb,globals)::text);
     EXCEPTION WHEN invalid_text_representation THEN RETURN v; END;
   END IF;
 END IF;
 RETURN v;
END $scrub$;
"""

class ExportError(RuntimeError): pass

def validate_schema_material(schema, tables):
    text = schema.decode('utf-8') if isinstance(schema,bytes) else schema
    if re.search(r'-----BEGIN .*PRIVATE KEY-----|\b(?:AKIA[0-9A-Z]{16}|LTAI[A-Za-z0-9]{12,}|sk-[A-Za-z0-9_-]{20,})\b|47\.100\.235\.168|139\.196\.165\.140|admin\.cretaceousfuture\.com',text):
        raise ExportError('schema contains restricted credential/production endpoint material')
    for table,columns in tables.items():
        for column in columns:
            default = str(column.get('column_default') or '')
            usage_state = column['column_name'] == 'token_usage_state' and re.fullmatch(r"'[A-Z_]{1,40}'(?:::[a-z ]+(?:\(\d+\))?)?",default)
            if SECRET_FIELD.search(column['column_name']) and default and not usage_state and not re.fullmatch(r"(?:NULL|false|true|0|''(?:::[a-z ]+)?)",default,re.I):
                raise ExportError('sensitive default requires explicit review: '+table+'.'+column['column_name'])
            for literal in re.findall(r"'((?:[^']|'')*)'",default):
                if len(literal)>=48 and re.fullmatch(r'[A-Za-z0-9_+/=-]+',literal) and re.search('[a-z]',literal) and re.search('[A-Z]',literal) and re.search('[0-9]',literal):
                    raise ExportError('high-entropy default requires explicit review: '+table+'.'+column['column_name'])

def schema_without_comments(schema):
    return re.sub(rb"(?ms)^COMMENT ON .*? IS '(?:[^']|'')*';\r?\n?",b'',schema)

def ident(value):
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*', value): raise ExportError('unsafe catalog identifier')
    return '"' + value + '"'

def literal(value):
    return "'" + json.dumps(value, ensure_ascii=False, separators=(',', ':')).replace("'", "''") + "'"

def scrub(value, key=''):
    if SECRET_FIELD.search(key): return 'SANDBOX_DISABLED' if 'password' in key.lower() or 'salt' in key.lower() else None
    if CONTACT_FIELD.search(key): return None
    if isinstance(value, dict): return {k: scrub(v, k) for k, v in value.items()}
    if isinstance(value, list): return [scrub(v) for v in value]
    return value

class Snapshot:
    def __enter__(self): return self
    def run(self, script, preamble=''):
        # PostgreSQL allows writes to pre-existing TEMP tables in a read-only transaction,
        # but not CREATE TEMP itself. These empty session-only tables disappear on disconnect.
        wrapped = preamble + '\nBEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + script + '\nROLLBACK;\n'
        if not preamble:
            proc = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', SOURCE,
                'sudo -u postgres psql -X -qAt -v ON_ERROR_STOP=1 ' + DATABASE],
                input=wrapped, capture_output=True, text=True, encoding='utf-8', timeout=60)
        else:
            # Windows OpenSSH may deadlock on large simultaneous stdin/stdout pipes.
            # This temporary file contains generated SELECT/TEMP-selection SQL, never row data.
            remote = '/tmp/cretas-f006-sandbox-export-' + uuid.uuid4().hex + '.sql'
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', suffix='.sql', delete=False) as script_file:
                script_file.write(wrapped); local = Path(script_file.name)
            try:
                copy = subprocess.run(['scp', '-q', '-o', 'BatchMode=yes', str(local), SOURCE + ':' + remote], capture_output=True, timeout=45)
                if copy.returncode: raise ExportError('temporary projection script transfer failed')
                mode = subprocess.run(['ssh', '-o', 'BatchMode=yes', SOURCE, 'chmod 644 -- ' + remote], capture_output=True, timeout=20)
                if mode.returncode: raise ExportError('projection script access failed')
                proc = subprocess.run(['ssh', '-o', 'BatchMode=yes', SOURCE,
                    'sudo -u postgres psql -X -qAt -v ON_ERROR_STOP=1 -f ' + remote + ' ' + DATABASE],
                    capture_output=True, text=True, encoding='utf-8', timeout=180)
            finally:
                local.unlink(missing_ok=True)
                subprocess.run(['ssh', '-o', 'BatchMode=yes', SOURCE, 'rm -f -- ' + remote], capture_output=True, timeout=20)
        if proc.returncode: raise ExportError('read-only projection query failed: ' + proc.stderr[:300])
        return [json.loads(line) for line in proc.stdout.splitlines() if line.strip()]
    def query(self, query): return self.run(query + ';')[0]
    def __exit__(self, *args): pass

def catalog(snapshot):
    cols = snapshot.query("""SELECT coalesce(json_agg(row_to_json(c)), '[]') FROM
      (SELECT table_name,column_name,is_nullable,is_generated,is_identity,identity_generation,column_default,data_type,character_maximum_length
       FROM information_schema.columns WHERE table_schema='public' AND table_name IN
         (SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE')
       ORDER BY table_name,ordinal_position) c""")
    fks = snapshot.query("""SELECT coalesce(json_agg(row_to_json(f)), '[]') FROM
      (SELECT c.conrelid::regclass::text AS child,c.confrelid::regclass::text AS parent,
        array_agg(a.attname ORDER BY k.ord) AS child_cols,array_agg(b.attname ORDER BY k.ord) AS parent_cols
       FROM pg_constraint c CROSS JOIN LATERAL unnest(c.conkey,c.confkey) WITH ORDINALITY k(ca,pa,ord)
       JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.ca
       JOIN pg_attribute b ON b.attrelid=c.confrelid AND b.attnum=k.pa
       WHERE c.contype='f' AND c.connamespace='public'::regnamespace GROUP BY c.oid,c.conrelid,c.confrelid) f""")
    tables = {}
    for col in cols: tables.setdefault(col['table_name'], []).append(col)
    return tables, fks

def metadata_fingerprint(tables, foreign):
    canonical = {'tables': tables, 'foreign': sorted(foreign, key=lambda f: json.dumps(f, sort_keys=True))}
    return hashlib.sha256(json.dumps(canonical, sort_keys=True).encode()).hexdigest()

def source_schema():
    proc = subprocess.run(['ssh', '-o', 'BatchMode=yes', SOURCE,
        'sudo -u postgres pg_dump --schema-only --schema=public --no-owner --no-acl --no-security-labels --no-publications --no-subscriptions ' + DATABASE], capture_output=True, timeout=90)
    if proc.returncode: raise ExportError('source schema export failed')
    if re.search(rb'(?im)^CREATE (?:SERVER|USER MAPPING|FOREIGN TABLE)|\bdblink\s*\(', proc.stdout):
        raise ExportError('external database schema requires explicit review')
    return proc.stdout

def comparable_schema(value):
    # pg_dump security backports generate a fresh psql restrict nonce on every invocation.
    # It is a client guard, not a schema change. Preserve it in the actual restore artifact.
    return re.sub(rb'(?m)^\\(?:un)?restrict\s+\S+\s*$', b'', value)

def project(snapshot):
    schema_before = source_schema()
    tables, foreign = catalog(snapshot)
    validate_schema_material(schema_before,tables)
    history_before = snapshot.query("SELECT coalesce(json_agg(to_jsonb(t)),'[]') FROM public.flyway_schema_history t") if 'flyway_schema_history' in tables else []
    fingerprint_before = metadata_fingerprint(tables, foreign)
    for cols in tables.values():
        unknown = {c['column_name'] for c in cols if re.search(r'factory.*id|tenant', c['column_name'], re.I)} - BOUNDARIES
        if unknown: raise ExportError('unreviewed tenant boundary column: ' + ','.join(sorted(unknown)))
    eligible = [t for t in tables if t in BASELINE_TABLES and not DENY_TABLE.search(t)]
    selections = {table: ident('f006_export_' + str(i)) for i, table in enumerate(eligible)}
    def scope(table, alias):
        names = {c['column_name'] for c in tables[table]}
        terms = []
        if table == 'factories': terms.append(alias + ".id='F006'")
        boundaries = sorted(names & BOUNDARIES)
        for col in boundaries:
            allowed = ['F006'] + (GLOBAL_SCOPES.get(table,[]) if col == 'factory_id' else [])
            literals = ','.join("'" + value + "'" for value in allowed)
            terms.append(f"({alias}.{ident(col)} IS NULL OR {alias}.{ident(col)}::text IN ({literals}))")
        if boundaries and table not in STATIC_PARENTS:
            terms.append('(' + ' OR '.join(f"{alias}.{ident(c)}::text='F006'" for c in boundaries) + ')')
        return ' AND '.join('(' + t + ')' for t in terms) or 'TRUE'
    def demo_parent(alias, field, parent='product_types'):
        return f"EXISTS(SELECT 1 FROM public.{ident(parent)} d WHERE d.id={alias}.{ident(field)} AND d.factory_id='F006' AND d.code LIKE 'DEMO-20261105-%')"
    def selected_scope(table, alias):
        predicate = scope(table,alias); names = {c['column_name'] for c in tables[table]}
        if table == 'users': predicate += f" AND {alias}.id::text='1309'"
        if table in {'user_menu_permissions','user_module_access'} and 'user_id' in names: predicate += f" AND {alias}.user_id::text='1309'"
        if table in {'suppliers','customers','raw_material_types','product_types'}:
            codes = [c for c in ('code','supplier_code','customer_code') if c in names]
            if not codes: raise ExportError('demo code field missing: ' + table)
            predicate += ' AND (' + ' OR '.join(f"{alias}.{ident(c)} LIKE 'DEMO-20261105-%'" for c in codes) + ')'
        if table in {'product_work_processes','product_packaging_specs','product_unit_conversions','product_process_workflows','bom_recipes'}:
            predicate += ' AND ' + demo_parent(alias,'product_type_id')
        if table in {'material_packaging_specs','material_packaging_hierarchy','supplier_material_purchase_specs'}:
            predicate += ' AND ' + demo_parent(alias,'material_type_id','raw_material_types')
        if table == 'supplier_materials':
            predicate += ' AND ' + demo_parent(alias,'material_type_id','raw_material_types') + ' AND ' + demo_parent(alias,'supplier_id','suppliers')
        if table == 'work_processes':
            predicate += f" AND EXISTS(SELECT 1 FROM public.product_work_processes w WHERE w.work_process_id={alias}.id AND w.factory_id='F006' AND {demo_parent('w','product_type_id')})"
        if table in {'bom_recipe_items','bom_versions'}:
            field = next((c for c in ('recipe_id','bom_recipe_id') if c in names),None)
            if field: predicate += f" AND EXISTS(SELECT 1 FROM public.bom_recipes b WHERE b.id={alias}.{ident(field)} AND b.factory_id='F006' AND {demo_parent('b','product_type_id')})"
            elif 'product_type_id' in names: predicate += ' AND ' + demo_parent(alias,'product_type_id')
            else: raise ExportError('BOM parent filter missing: ' + table)
        if table == 'product_process_workflow_revisions':
            predicate += f" AND EXISTS(SELECT 1 FROM public.product_process_workflows w WHERE w.id={alias}.workflow_id AND w.factory_id='F006' AND {demo_parent('w','product_type_id')})"
        if table == 'product_process_workflow_activations':
            predicate += f" AND EXISTS(SELECT 1 FROM public.product_process_workflows w WHERE w.id={alias}.active_workflow_id AND w.factory_id='F006' AND {demo_parent('w','product_type_id')})"
        if table == 'approval_workflows': predicate += f" AND {alias}.decision_type IN ('PURCHASE_ORDER_APPROVAL','SALES_ORDER_APPROVAL')"
        return predicate
    commands = []; temp_tables = []
    for table, selection in selections.items():
        names = {c['column_name'] for c in tables[table]}
        initial = selected_scope(table, 't') if table == 'factories' or names & BOUNDARIES else ('TRUE' if table in {'unit_of_measurements','permission_registry','role_definitions','enum_dictionary'} else 'FALSE')
        temp_tables.append(f'CREATE TEMP TABLE {selection}(rowtid tid PRIMARY KEY);')
        commands.append(f'INSERT INTO {selection} SELECT t.ctid FROM public.{ident(table)} t WHERE {initial};')
    closure = []
    for fk in foreign:
        child, parent = fk['child'], fk['parent']
        if child not in selections or parent not in selections: continue
        equality = ' AND '.join(f'c.{ident(c)}=p.{ident(p)}' for c, p in zip(fk['child_cols'], fk['parent_cols']))
        child_names = {c['column_name'] for c in tables[child]}
        parent_names = {c['column_name'] for c in tables[parent]}
        if not child_names & BOUNDARIES and child != 'factories' and child not in STATIC_PARENTS:
            closure.append(f'INSERT INTO {selections[child]} SELECT c.ctid FROM public.{ident(child)} c JOIN public.{ident(parent)} p ON {equality} JOIN {selections[parent]} s ON p.ctid=s.rowtid WHERE {selected_scope(child,"c")} ON CONFLICT DO NOTHING;')
        if parent == 'factories' or parent_names & BOUNDARIES or parent in STATIC_PARENTS:
            closure.append(f'INSERT INTO {selections[parent]} SELECT p.ctid FROM public.{ident(parent)} p JOIN public.{ident(child)} c ON {equality} JOIN {selections[child]} s ON c.ctid=s.rowtid WHERE {selected_scope(parent,"p")} ON CONFLICT DO NOTHING;')
    commands.append('DO $f006$ DECLARE changed integer; total integer; BEGIN FOR i IN 1..30 LOOP total:=0;\n' +
        '\n'.join(c + ' GET DIAGNOSTICS changed=ROW_COUNT; total:=total+changed;' for c in closure) +
        "\nEXIT WHEN total=0; END LOOP; IF total<>0 THEN RAISE EXCEPTION 'F006 projection closure did not converge'; END IF; END $f006$;")
    for table, selection in selections.items():
        drops = [c['column_name'] for c in tables[table] if SECRET_FIELD.search(c['column_name']) or c['data_type'] == 'bytea']
        globals_sql = 'ARRAY[' + ','.join("'" + s + "'" for s in GLOBAL_SCOPES.get(table,[])) + ']::text[]'
        expr = 'pg_temp.f006_export_scrub(to_jsonb(t)' + ''.join(" - '" + k + "'" for k in drops) + ',' + globals_sql + ')'
        commands.append(f"SELECT json_build_object('table','{table}','rows',coalesce(json_agg({expr}),'[]')) FROM public.{ident(table)} t JOIN {selection} s ON t.ctid=s.rowtid;")
    entries = snapshot.run('\n'.join(commands), '\n'.join(temp_tables) + '\n' + SOURCE_SCRUB_SQL)
    if any(entry.get('table') not in selections for entry in entries): raise ExportError('unexpected source table in master projection')
    result = {entry['table']: entry['rows'] for entry in entries if entry['rows']}
    tables_after, foreign_after = catalog(snapshot)
    if metadata_fingerprint(tables_after, foreign_after) != fingerprint_before or comparable_schema(source_schema()) != comparable_schema(schema_before):
        raise ExportError('source schema changed during projection; no artifact published')
    snapshot.verified_schema = schema_before
    snapshot.metadata_fingerprint = fingerprint_before
    if 'flyway_schema_history' in tables:
        history = snapshot.query('SELECT coalesce(json_agg(to_jsonb(t)),\'[]\') FROM public.flyway_schema_history t')
        canonical = lambda values: json.dumps(sorted(values, key=lambda r: json.dumps(r, sort_keys=True)), sort_keys=True)
        if canonical(history) != canonical(history_before): raise ExportError('Flyway history changed during projection')
        snapshot.flyway_fingerprint = hashlib.sha256(canonical(history).encode()).hexdigest()
    # Validate every dependency in the projection before permitting an output artifact.
    for fk in foreign:
        child, parent = fk['child'], fk['parent']
        targets = {tuple(r.get(c) for c in fk['parent_cols']) for r in result.get(parent, [])}
        nullable = {c['column_name']: c['is_nullable'] == 'YES' for c in tables[child]}
        for row in result.get(child, []):
            ref = tuple(row.get(c) for c in fk['child_cols'])
            if None in ref or ref in targets: continue
            if not all(nullable[c] for c in fk['child_cols']): raise ExportError('required dependency unavailable: ' + child + ' -> ' + parent)
            for col in fk['child_cols']: row[col] = None
    for table, rows in result.items():
        for row in rows:
            if any(row.get(key) not in (['F006',None] + (GLOBAL_SCOPES.get(table,[]) if key == 'factory_id' else [])) for key in BOUNDARIES): raise ExportError('cross-tenant row: ' + table)
            if table == 'factories' and row.get('id') != 'F006': raise ExportError('cross-tenant factory')
            cleaned = scrub(row); row.clear(); row.update(cleaned)
            for col in tables[table]:
                name = col['column_name']
                if col['data_type'] == 'bytea':
                    if col['is_nullable'] == 'NO': raise ExportError('required binary master field needs explicit seed: ' + table + '.' + name)
                    row[name] = None
                if SECRET_FIELD.search(name) and col['is_nullable'] == 'NO':
                    row[name] = False if col['data_type'] == 'boolean' else (0 if col['data_type'] in ('integer','bigint','smallint','numeric') else 'SANDBOX_DISABLED')
                if row.get(name) is None and CONTACT_FIELD.search(name) and col['is_nullable'] == 'NO':
                    # Deterministic per-row placeholders preserve uniqueness without retaining contacts.
                    digest = hashlib.sha256((table + ':' + name + ':' + str(row.get('id',json.dumps(row,sort_keys=True)))).encode()).hexdigest()
                    row[name] = ('demo+' + digest[:16] + '@example.invalid') if 'email' in name else ('19' + str(int(digest[:16],16) % 1000000000).zfill(9))
                    if col.get('character_maximum_length'): row[name] = row[name][:col['character_maximum_length']]
    return tables, foreign, result


def write_output(directory, tables, foreign, rows, snapshot):
    if not directory.is_relative_to(PRIVATE_ROOT.resolve()) or directory.exists(): raise ExportError('output must be new and under the private task root')
    staging = directory.with_name(directory.name + '.staging-' + uuid.uuid4().hex)
    staging.mkdir(mode=0o700)
    if os.name == 'nt':
        user = subprocess.run(['whoami'], capture_output=True, text=True, check=True).stdout.strip()
        subprocess.run(['icacls', str(staging), '/inheritance:r', '/grant:r', user + ':(OI)(CI)F', 'SYSTEM:(OI)(CI)F'], capture_output=True, check=True)
    validate_schema_material(snapshot.verified_schema,tables)
    (staging / 'schema.private.sql').write_bytes(schema_without_comments(snapshot.verified_schema))
    (staging / 'rows.private.json').write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
    (staging / 'catalog.private.json').write_text(json.dumps({'tables': tables, 'foreign': foreign}), encoding='utf-8')
    manifest = {'tenant': 'F006', 'kind':'RESTRICTED_DEMO_MASTER_PRESET', 'sourceMode': 'ROWS_READ_ONLY_REPEATABLE_READ_SCHEMA_FINGERPRINTED', 'counts': {t: len(r) for t, r in rows.items()},
        'globalScopes':GLOBAL_SCOPES,
        'excludedTables':{t:'not required for the demo master preset' for t in tables if t not in BASELINE_TABLES or DENY_TABLE.search(t)},
        'flywayFingerprint':getattr(snapshot,'flyway_fingerprint',None),
        'metadataFingerprint': snapshot.metadata_fingerprint,
        'schemaFingerprint': hashlib.sha256(snapshot.verified_schema).hexdigest(),
        'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in staging.iterdir() if p.is_file()}}
    (staging / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    os.replace(staging, directory)
    return manifest

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--output-dir', required=True); args = parser.parse_args()
    target = Path(args.output_dir).resolve()
    if not Path(args.output_dir).is_absolute() or any((p / '.git').exists() for p in [target, *target.parents]): raise ExportError('output must be a new PRIVATE directory outside a Git worktree')
    with Snapshot() as snapshot:
        tables, foreign, rows = project(snapshot)
        manifest = write_output(target, tables, foreign, rows, snapshot)
    print(json.dumps({'tenant': 'F006', 'tables': len(manifest['counts']), 'rows': sum(manifest['counts'].values())}))
