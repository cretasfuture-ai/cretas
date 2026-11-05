#!/usr/bin/env python3
"""Fixed, private F006 Docker slot. Never connects to a production database.

Provision registers physical identities. Every restore verifies those identities,
stops this slot's Java container, and restores the baseline in one transaction.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import time
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler

CONTAINERS = {'pg': 'cretas-f006-sandbox-pg', 'redis': 'cretas-f006-sandbox-redis', 'java': 'cretas-f006-sandbox-java'}
VOLUMES = {'pg': 'cretas-f006-sandbox-pgdata', 'redis': 'cretas-f006-sandbox-redisdata'}
NETWORK = 'cretas-f006-sandbox-net'
LABELS = {'cretas.sandbox': 'true', 'cretas.sandbox.scope': 'F006', 'cretas.sandbox.network': NETWORK}
IMAGES = {'pg': 'postgres:17', 'redis': 'redis:7-alpine', 'java': 'eclipse-temurin:21-jre'}
PORTS = {'pg': ('5432/tcp', None), 'redis': ('6379/tcp', '6387'), 'java': ('10040/tcp', '10040')}
PROD = {'cretas_prod_db', 'cretas_db', 'prod', 'production'}
REQUIRED_FILES = {'schema.private.sql', 'rows.private.json', 'catalog.private.json'}
GLOBAL_SCOPES = {'enum_dictionary':['__GLOBAL__']}
JDBC_BASE = 'jdbc:postgresql://postgres:5432/f006_demo_sandbox'
JDBC_URL = JDBC_BASE + '?currentSchema=public&stringtype=unspecified'

class OrchestratorError(RuntimeError): pass

def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value): raise OrchestratorError('invalid catalog identifier')
    return '"' + value + '"'

def sha_file(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def atomic_json(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.' + secrets.token_hex(8) + '.tmp')
    with tmp.open('w', encoding='utf-8') as file:
        json.dump(value, file, ensure_ascii=False, indent=2); file.flush(); os.fsync(file.fileno())
    os.replace(tmp, path)
    if os.name != 'nt':
        fd = os.open(path.parent, os.O_RDONLY)
        try: os.fsync(fd)
        finally: os.close(fd)

class Docker:
    def __init__(self, runner=subprocess.run): self.runner = runner
    def run(self, args, timeout=120, **kwargs):
        if not args or args[0] != 'docker' or args[1] in {'compose','down','prune'} or (args[1] == 'rm' and {'-v','--volumes'}.intersection(args)) or args[1:3] == ['volume','rm']:
            raise OrchestratorError('unsafe Docker command')
        as_text=not isinstance(kwargs.get('input'),bytes)
        if as_text: kwargs.setdefault('encoding','utf-8')
        return self.runner(args, shell=False, capture_output=True, text=as_text, timeout=timeout, **kwargs)

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args): return None

class SandboxOrchestrator:
    def __init__(self, cfg, docker=None):
        self.cfg = cfg; self._validate_cfg(); self.docker = docker or Docker()

    def _validate_cfg(self):
        if self.cfg.get('db_name') != 'f006_demo_sandbox': raise OrchestratorError('only the fixed sandbox database is allowed')
        if self.cfg.get('network') != NETWORK or self.cfg.get('containers') != CONTAINERS or self.cfg.get('volumes') != VOLUMES:
            raise OrchestratorError('fixed sandbox topology mismatch')
        for key in ('identity_path', 'baseline_dir', 'java_jar', 'java_properties', 'baseline_proof_path'):
            if not os.path.isabs(self.cfg.get(key, '')): raise OrchestratorError('absolute private path required: ' + key)
        if not re.fullmatch(r'[0-9a-f-]{36}', self.cfg.get('instance_id', '')): raise OrchestratorError('private instance UUID required')
        if self.cfg.get('pg_user') != 'cretas_sandbox' or len(self.cfg.get('pg_password', '')) < 32: raise OrchestratorError('private PostgreSQL credentials required')

    def _checked(self, args, **kwargs):
        result = self.docker.run(args, **kwargs)
        if result.returncode:
            error = result.stderr.decode('utf-8','replace') if isinstance(result.stderr,bytes) else str(result.stderr or '')
            # Keep a small diagnostic inside the private configuration directory, never argv/secrets.
            first = next((line for line in error.splitlines() if 'ERROR:' in line or 'FATAL:' in line), error.splitlines()[0] if error else '')
            atomic_json(Path(self.cfg['identity_path']).with_name('last-error.private.json'), {'operation':args[1], 'detail':first[:400]})
            raise OrchestratorError('sandbox Docker operation failed: ' + args[1] + '; see private diagnostic')
        return result

    def _json(self, args): return json.loads(self._checked(args).stdout)

    def _labels(self, role=None):
        labels = {**LABELS, 'cretas.sandbox.instance': self.cfg['instance_id']}
        if role: labels['cretas.sandbox.role'] = role
        return labels

    def _java_command(self):
        return ['java', '-Xmx2g', '-jar', '/opt/app/app.jar', '--spring.config.additional-location=file:/opt/app/application-sandbox.properties', '--spring.profiles.active=sandbox']

    def _validate_properties(self):
        values = {}
        for line in Path(self.cfg['java_properties']).read_text(encoding='utf-8').splitlines():
            if line.strip() and not line.lstrip().startswith('#'):
                key, separator, value = line.partition('=')
                if not separator: raise OrchestratorError('invalid sandbox property line')
                values[key.strip()] = value.strip()
        expected = {'server.port':'10040',
            'spring.datasource.username':'cretas_sandbox', 'spring.datasource.password':self.cfg['pg_password'],
            'spring.datasource.driver-class-name':'org.postgresql.Driver', 'spring.data.redis.host':'redis', 'spring.data.redis.port':'6379',
            'cretas.demo-sandbox.node.enabled':'true', 'cretas.demo.sandbox.enabled':'false', 'aliyun.oss.enabled':'false',
            'spring.flyway.enabled':'false', 'spring.jpa.hibernate.ddl-auto':'validate'}
        if any(values.get(k) != v for k,v in expected.items()): raise OrchestratorError('sandbox properties do not enforce the isolated topology')
        # The legacy URL is accepted only to verify/reset a stopped old slot.
        # prepare below requires the production-shaped PostgreSQL binding mode.
        if values.get('spring.datasource.url') not in {JDBC_BASE,JDBC_URL}: raise OrchestratorError('sandbox JDBC URL changed')
        actual = values.get('cretas.jwt.secret', '')
        fingerprint = values.get('cretas.demo.sandbox.production-jwt-sha256', '')
        if len(actual) < 64 or not re.fullmatch(r'[0-9a-f]{64}', fingerprint) or hashlib.sha256(actual.encode()).hexdigest() == fingerprint:
            raise OrchestratorError('sandbox JWT signing key is not independent')
        return values

    def _inspect(self, role):
        obj = self._json(['docker', 'inspect', '--format={{json .}}', CONTAINERS[role]])
        if any(obj.get('Config', {}).get('Labels', {}).get(k) != v for k, v in self._labels(role).items()):
            raise OrchestratorError('sandbox container labels do not match: ' + role)
        if obj['Config'].get('Image') != IMAGES[role]: raise OrchestratorError('sandbox image changed: ' + role)
        if set(obj.get('NetworkSettings', {}).get('Networks', {})) != {NETWORK}: raise OrchestratorError('sandbox container network changed')
        aliases = obj['NetworkSettings']['Networks'][NETWORK].get('Aliases', []) or []
        if {'pg':'postgres','redis':'redis','java':'sandbox-java'}[role] not in aliases: raise OrchestratorError('sandbox service alias missing')
        bindings = obj.get('HostConfig', {}).get('PortBindings', {}) or {}
        inside, outside = PORTS[role]
        expected_bindings = {inside:[{'HostIp':'127.0.0.1','HostPort':outside}]} if outside else {}
        if bindings != expected_bindings: raise OrchestratorError('sandbox port exposure changed')
        mounts = obj.get('Mounts', [])
        volumes = [m for m in mounts if m.get('Type') == 'volume']
        if role != 'java':
            volume_target = {'pg': '/var/lib/postgresql/data', 'redis': '/data'}[role]
            if len(volumes) != 1 or volumes[0].get('Name') != VOLUMES[role] or volumes[0].get('Destination') != volume_target:
                raise OrchestratorError('sandbox dedicated volume changed: ' + role)
        elif volumes or obj.get('HostConfig', {}).get('ReadonlyRootfs') is not True:
            raise OrchestratorError('sandbox Java must have no persistent writable filesystem')
        if role == 'java':
            if obj['Config'].get('Cmd') != self._java_command(): raise OrchestratorError('sandbox Java launch command changed')
            self._validate_properties()
            dangerous_env = re.compile(r'^(?:SPRING_|CRETAS_|JWT_|DASHSCOPE_|ALIYUN_|AWS_|OPENAI_|HTTP_PROXY|HTTPS_PROXY|ALL_PROXY|JAVA_TOOL_OPTIONS)', re.I)
            if any(dangerous_env.search(entry.split('=',1)[0]) for entry in obj['Config'].get('Env', [])): raise OrchestratorError('unapproved sandbox Java environment override')
            binds = {m.get('Destination'): m for m in mounts if m.get('Type') == 'bind'}
            expected = {'/opt/app/app.jar': self.cfg['java_jar'], '/opt/app/application-sandbox.properties': self.cfg['java_properties']}
            if set(binds) != set(expected): raise OrchestratorError('sandbox Java mounts changed')
            # Docker Desktop normalizes Windows paths; compare normalized suffixes there.
            for target, source in expected.items():
                mount = binds[target]
                if mount.get('RW') is not False: raise OrchestratorError('sandbox code/config bind must be read-only')
                actual = str(mount.get('Source', '')).replace('\\', '/').lower()
                normalized = str(Path(source).resolve()).replace('\\', '/').lower()
                suffix = normalized[2:] if re.match(r'^[a-z]:/', normalized) else normalized
                if not actual.endswith(suffix): raise OrchestratorError('sandbox Java bind source changed')
            if set(obj.get('HostConfig', {}).get('Tmpfs', {})) != {'/tmp','/opt/app/data'}: raise OrchestratorError('sandbox Java temporary data mounts changed')
            if any(m.get('Type') not in {'bind','tmpfs'} or (m.get('Type') == 'tmpfs' and m.get('Destination') not in {'/tmp','/opt/app/data'}) for m in mounts): raise OrchestratorError('unapproved sandbox Java mount')
        elif any(m.get('Type') not in {'volume'} for m in mounts): raise OrchestratorError('database/Redis host mounts are forbidden')
        return obj

    def _inspect_running(self, role): return bool(self._inspect(role).get('State', {}).get('Running'))

    def _network(self):
        net = self._json(['docker', 'network', 'inspect', '--format={{json .}}', NETWORK])
        if net.get('Internal') is not True or any(net.get('Labels', {}).get(k) != v for k, v in self._labels().items()):
            raise OrchestratorError('sandbox network is not registered/internal')
        expected = {self._inspect(role)['Id'] for role in CONTAINERS}
        if not set(net.get('Containers', {})).issubset(expected): raise OrchestratorError('unregistered container joined the sandbox network')
        return net

    def _pg(self, sql, timeout=180):
        result = self._checked(['docker', 'exec', '-i', CONTAINERS['pg'], 'psql', '-X', '-U', 'cretas_sandbox', '-d', 'f006_demo_sandbox', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=sql.encode(), timeout=timeout)
        return result.stdout.decode('utf-8')

    def _wait_pg(self):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            # The official image starts a temporary PostgreSQL during initdb.
            # It can answer pg_isready before the entrypoint stops it again.
            # Only PID 1 == postgres identifies the final exec'ed server.
            main = self.docker.run(['docker', 'exec', CONTAINERS['pg'], 'cat', '/proc/1/comm'])
            if main.returncode == 0 and main.stdout.strip() == 'postgres':
                ready = self.docker.run(['docker', 'exec', CONTAINERS['pg'], 'pg_isready', '-U', 'cretas_sandbox', '-d', 'f006_demo_sandbox'])
                if ready.returncode == 0: return
            time.sleep(.5)
        raise OrchestratorError('sandbox PostgreSQL did not become ready')

    def _physical_identity(self):
        network = self._network(); nodes = {role: self._inspect(role) for role in CONTAINERS}
        fields = self._pg("SELECT system_identifier, current_setting('data_directory'), current_database(), current_setting('port') FROM pg_control_system();").strip().split('|')
        if len(fields) != 4 or fields[2:] != ['f006_demo_sandbox', '5432'] or fields[1] != '/var/lib/postgresql/data': raise OrchestratorError('unexpected physical database identity')
        proof = {'instance': self.cfg['instance_id'], 'networkId': network['Id'], 'systemIdentifier': fields[0], 'dataDirectory': fields[1],
            'nodes': {role: {'id': obj['Id'], 'imageId': obj['Image'], 'tmpfs': obj.get('HostConfig',{}).get('Tmpfs',{}), 'mounts': sorted([[m.get('Type'), m.get('Name'), m.get('Source'), m.get('Destination'), m.get('RW')] for m in obj.get('Mounts', []) if m.get('Type') != 'tmpfs'], key=str)} for role, obj in nodes.items()},
            'jarSha256': sha_file(self.cfg['java_jar']), 'propertiesSha256': sha_file(self.cfg['java_properties'])}
        dependency_path = Path(self.cfg['identity_path']).with_name('vector-dependency.private.json')
        if dependency_path.exists():
            dependency = json.loads(dependency_path.read_text(encoding='utf-8'))
            from vector_dependency import verify_receipt
            verify_receipt(self,dependency)
            proof['vectorDependency'] = dependency
        return proof

    def _verify_identity(self):
        path = Path(self.cfg['identity_path'])
        if not path.is_file(): raise OrchestratorError('registered physical identity is missing')
        expected = json.loads(path.read_text(encoding='utf-8')); actual = self._physical_identity()
        if expected != actual: raise OrchestratorError('sandbox physical identity changed')
        return actual

    def _stop_java(self):
        state = self._inspect('java').get('State',{})
        if state.get('Status') not in {'created','running','exited'}: raise OrchestratorError('sandbox Java lifecycle state requires operator review')
        if state.get('Running'): self._checked(['docker', 'stop', '--time', '30', CONTAINERS['java']])
        if self._inspect_running('java'): raise OrchestratorError('sandbox Java is still running')
        # Docker creates these mount-point parents even on a read-only rootfs.
        # The mount definitions themselves are verified by _inspect above.
        scaffolding = {'C /opt', 'A /opt/app', 'A /opt/app/app.jar',
            'A /opt/app/application-sandbox.properties', 'A /opt/app/data'}
        changes = set(self._checked(['docker','diff',CONTAINERS['java']]).stdout.strip().splitlines()) - {''}
        if not changes.issubset(scaffolding): raise OrchestratorError('sandbox Java has unexpected persistent layer changes')

    def _start(self, role):
        self._inspect(role); self._checked(['docker', 'start', CONTAINERS[role]])

    def provision(self):
        if Path(self.cfg['identity_path']).exists(): self._verify_identity(); return
        if not Path(self.cfg['java_jar']).is_file() or not Path(self.cfg['java_properties']).is_file(): raise OrchestratorError('sandbox JAR/properties missing')
        self._validate_properties()
        # Refuse to adopt any pre-existing resource. Interrupted provisioning needs explicit local review.
        for name in CONTAINERS.values():
            if self.docker.run(['docker', 'inspect', name]).returncode == 0: raise OrchestratorError('unregistered sandbox container already exists')
        labels = [item for key, value in self._labels().items() for item in ('--label', key + '=' + value)]
        self._checked(['docker', 'network', 'create', '--internal', *labels, NETWORK])
        for volume in VOLUMES.values():
            if self.docker.run(['docker', 'volume', 'inspect', volume]).returncode == 0: raise OrchestratorError('unregistered sandbox volume already exists')
            self._checked(['docker', 'volume', 'create', *labels, volume])
        for role in CONTAINERS:
            inside, outside = PORTS[role]
            args = ['docker', 'create', '--name', CONTAINERS[role], '--network', NETWORK, '--network-alias', {'pg':'postgres','redis':'redis','java':'sandbox-java'}[role],
                *labels, '--label', 'cretas.sandbox.role=' + role, '--restart', 'no']
            if outside: args += ['-p','127.0.0.1:' + outside + ':' + inside.split('/')[0]]
            if role != 'java':
                target = {'pg':'/var/lib/postgresql/data','redis':'/data'}[role]
                args += ['--mount', 'type=volume,source=' + VOLUMES[role] + ',target=' + target]
            if role == 'pg':
                args += ['-e','POSTGRES_DB=f006_demo_sandbox','-e','POSTGRES_USER=cretas_sandbox','-e','POSTGRES_PASSWORD=' + self.cfg['pg_password'], IMAGES[role]]
            elif role == 'redis': args += [IMAGES[role], 'redis-server', '--save', '', '--appendonly', 'no']
            else:
                args += ['--mount','type=bind,source=' + self.cfg['java_jar'] + ',target=/opt/app/app.jar,readonly',
                    '--mount','type=bind,source=' + self.cfg['java_properties'] + ',target=/opt/app/application-sandbox.properties,readonly',
                    '--read-only', '--tmpfs','/tmp:rw,noexec,nosuid,size=256m', '--tmpfs','/opt/app/data:rw,noexec,nosuid,size=256m', '--memory','3g', IMAGES[role], *self._java_command()]
            self._checked(args)
        self._start('pg'); self._start('redis'); self._wait_pg()
        from vector_dependency import install
        install(self)
        atomic_json(self.cfg['identity_path'], self._physical_identity())

    def _validated_baseline(self):
        root = Path(self.cfg['baseline_dir']); meta = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
        if meta.get('tenant') != 'F006' or set(meta.get('sha256', {})) != REQUIRED_FILES: raise OrchestratorError('baseline manifest scope/files invalid')
        for name in REQUIRED_FILES:
            if sha_file(root / name) != meta['sha256'][name]: raise OrchestratorError('baseline file hash mismatch')
        rows = json.loads((root/'rows.private.json').read_text(encoding='utf-8')); catalog = json.loads((root/'catalog.private.json').read_text(encoding='utf-8'))
        if set(rows) != set(meta.get('counts', {})) or any(len(values) != meta['counts'][table] for table, values in rows.items()): raise OrchestratorError('baseline counts are incomplete')
        if not any(str(row.get('id')) == '1309' and row.get('factory_id') == 'F006' for row in rows.get('users', [])): raise OrchestratorError('baseline demo actor missing')
        for table in catalog['tables']:
            identifier(table)
            for column in catalog['tables'][table]: identifier(column['column_name'])
        schema = (root/'schema.private.sql').read_text(encoding='utf-8')
        from baseline_export import validate_schema_material, CONTACT_FIELD, SECRET_FIELD, BASELINE_TABLES, DENY_TABLE
        validate_schema_material(schema,catalog['tables'])
        for table, records in rows.items():
            if table not in BASELINE_TABLES or DENY_TABLE.search(table): raise OrchestratorError('baseline contains an unapproved master table')
            for row in records:
                if any(CONTACT_FIELD.search(k) and v not in (None,'',False,0) for k,v in row.items()): raise OrchestratorError('baseline contains an unreviewed contact/endpoint field')
                if any(SECRET_FIELD.search(k) and v not in (None,'',False,0,'SANDBOX_DISABLED') for k,v in row.items()): raise OrchestratorError('baseline contains a sensitive field')
        if len(rows.get('users',[])) != 1 or len(rows.get('factories',[])) != 1: raise OrchestratorError('baseline actor/factory must be unique')
        if re.search(r'(?im)^CREATE (SERVER|USER MAPPING|FOREIGN TABLE|DATABASE|ROLE)|^ALTER SYSTEM|^\\(?:connect|!|i\s)|\bdblink\s*\(', schema): raise OrchestratorError('baseline schema has external/unapproved statements')
        return meta, rows, catalog, schema

    def _restore_sql(self, meta, rows, catalog, schema, truncate_tables=None):
        parts = ['BEGIN;']
        if truncate_tables is None:
            parts += ['DROP SCHEMA public CASCADE;', 'CREATE SCHEMA public;']
            schema = re.sub(r'(?m)^CREATE SCHEMA "?public"?;\s*$', '', schema)
            parts += ['CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;',schema]
        parts.append('SET LOCAL session_replication_role=replica;')
        if truncate_tables is not None:
            if not truncate_tables: raise OrchestratorError('cannot reset an unverified/empty schema')
            parts.append('TRUNCATE TABLE ' + ','.join('public.'+identifier(t) for t in truncate_tables) + ' RESTART IDENTITY CASCADE;')
        tag = 'SBX' + secrets.token_hex(16)
        for table, records in rows.items():
            columns = [c['column_name'] for c in catalog['tables'][table] if c.get('is_generated','NEVER') == 'NEVER']
            names = ','.join(identifier(c) for c in columns); target = 'public.' + identifier(table)
            blob = json.dumps(records, ensure_ascii=False)
            if '$' + tag + '$' in blob: raise OrchestratorError('baseline literal delimiter collision')
            parts.append(f'INSERT INTO {target} ({names}) OVERRIDING SYSTEM VALUE SELECT {names} FROM json_populate_recordset(NULL::{target}, ${tag}${blob}${tag}$);')
            for column in catalog['tables'][table]:
                if 'nextval(' in str(column.get('column_default') or '') or column.get('is_identity') == 'YES':
                    name = column['column_name']
                    parts.append(f"SELECT setval(pg_get_serial_sequence('public.{identifier(table)}','{name}'),coalesce((SELECT max({identifier(name)}) FROM {target}),1),EXISTS(SELECT 1 FROM {target}));")
        parts.append('SET LOCAL session_replication_role=origin;')
        for fk in catalog['foreign']:
            child, parent = identifier(fk['child']), identifier(fk['parent'])
            comparison = ' AND '.join(f'c.{identifier(a)}=p.{identifier(b)}' for a,b in zip(fk['child_cols'], fk['parent_cols']))
            present = ' AND '.join('c.' + identifier(c) + ' IS NOT NULL' for c in fk['child_cols'])
            parts.append(f"DO $$ BEGIN IF EXISTS(SELECT 1 FROM public.{child} c WHERE {present} AND NOT EXISTS(SELECT 1 FROM public.{parent} p WHERE {comparison})) THEN RAISE EXCEPTION 'sandbox FK orphan'; END IF; END $$;")
        boundaries = {'factory_id','source_factory_id','target_factory_id','actor_factory_id','tenant_id'}
        for table, columns in catalog['tables'].items():
            conditions = []
            for column in columns:
                name = column['column_name']
                if name not in boundaries: continue
                allowed = ['F006'] + (GLOBAL_SCOPES.get(table,[]) if name == 'factory_id' else [])
                literals = ','.join("'" + s + "'" for s in allowed)
                conditions.append(f"({identifier(name)} IS NOT NULL AND {identifier(name)}::text NOT IN ({literals}))")
            if conditions: parts.append(f"DO $$ BEGIN IF EXISTS(SELECT 1 FROM public.{identifier(table)} WHERE {' OR '.join(conditions)}) THEN RAISE EXCEPTION 'sandbox tenant leak'; END IF; END $$;")
        for table, count in meta['counts'].items():
            parts.append(f"DO $$ BEGIN IF (SELECT count(*) FROM public.{identifier(table)})<>{int(count)} THEN RAISE EXCEPTION 'sandbox baseline count mismatch'; END IF; END $$;")
        parts += ["DO $$ BEGIN IF EXISTS(SELECT 1 FROM public.factories WHERE id<>'F006') THEN RAISE EXCEPTION 'sandbox factory leak'; END IF; END $$;", 'COMMIT;']
        return '\n'.join(parts)

    def _content_hashes(self, tables):
        queries = [f"SELECT json_build_object('table','{table}','hash',md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY md5(to_jsonb(t)::text)),''))) FROM public.{identifier(table)} t;" for table in sorted(tables)]
        return {row['table']:row['hash'] for row in map(json.loads, self._pg('\n'.join(queries)).splitlines())}

    def _schema_hash(self):
        raw = self._checked(['docker','exec',CONTAINERS['pg'],'pg_dump','-U','cretas_sandbox','--schema-only','--schema=public','--no-owner','--no-acl','f006_demo_sandbox'],input=b'').stdout
        comparable = re.sub(rb'(?m)^\\(?:un)?restrict\s+\S+\s*$', b'', raw)
        return hashlib.sha256(comparable).hexdigest()

    def _verify_database_shape(self):
        state=self._pg("SELECT (SELECT count(*) FROM pg_matviews WHERE schemaname='public'),(SELECT count(*) FROM pg_largeobject_metadata),(SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='S' AND n.nspname='public' AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.objid=c.oid AND d.deptype IN ('a','i')));").strip()
        if state != '0|0|0': raise OrchestratorError('unmanaged materialized views, large objects or sequences require operator review')

    def _restore(self):
        self._verify_identity()
        if self._inspect_running('java'): raise OrchestratorError('restore requires stopped sandbox Java')
        meta, rows, catalog, schema = self._validated_baseline()
        path = Path(self.cfg['baseline_proof_path'])
        previous = json.loads(path.read_text(encoding='utf-8')) if path.exists() else None
        truncate = None
        count = int(self._pg("SELECT count(*) FROM information_schema.tables WHERE table_schema='public';").strip())
        if count:
            if not previous or previous.get('baseline') != meta['sha256']: raise OrchestratorError('existing schema has no registered baseline proof')
            current_schema = self._schema_hash()
            if 'schemaHash' not in previous: raise OrchestratorError('schema proof is missing; explicit operator recovery required')
            if previous['schemaHash'] != current_schema: raise OrchestratorError('sandbox schema drift requires explicit operator recovery')
            truncate = json.loads(self._pg("SELECT coalesce(json_agg(c.relname ORDER BY c.relname),'[]') FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p') AND NOT EXISTS(SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid);").strip())
        self._pg(self._restore_sql(meta, rows, catalog, schema, truncate), timeout=300)
        self._verify_database_shape()
        proof = {'baseline': meta['sha256'], 'schemaHash':self._schema_hash(), 'tables': self._content_hashes(catalog['tables'])}
        if previous and previous != proof: raise OrchestratorError('restored baseline content changed')
        if not previous: atomic_json(path, proof)
        return proof

    def assert_clean(self):
        """A missing/empty manager manifest is safe only after a complete physical reset."""
        self._verify_identity()
        if self._inspect_running('java'): raise OrchestratorError('a clean slot requires stopped Java')
        self._verify_database_shape()
        meta, _, catalog, _ = self._validated_baseline()
        proof_path = Path(self.cfg['baseline_proof_path'])
        if not proof_path.is_file(): raise OrchestratorError('verified baseline proof is missing')
        expected = json.loads(proof_path.read_text(encoding='utf-8'))
        actual = {'baseline':meta['sha256'],'schemaHash':self._schema_hash(),'tables':self._content_hashes(catalog['tables'])}
        if expected != actual: raise OrchestratorError('sandbox slot is dirty or its baseline changed')
        info = self._checked(['docker','exec',CONTAINERS['redis'],'redis-cli','INFO','keyspace']).stdout
        if re.search(r'(?m)^db\d+:keys=[1-9]', info): raise OrchestratorError('sandbox Redis is not clean')
        return actual

    def _clear_redis(self):
        self._inspect('redis'); self._checked(['docker','exec',CONTAINERS['redis'],'redis-cli','FLUSHALL'])
        if self._checked(['docker','exec',CONTAINERS['redis'],'redis-cli','DBSIZE']).stdout.strip() != '0': raise OrchestratorError('sandbox Redis is not empty')

    def prepare(self):
        if self._validate_properties()['spring.datasource.url'] != JDBC_URL: raise OrchestratorError('sandbox PostgreSQL string binding must be configured before preparation')
        self._verify_identity(); self._stop_java(); self._restore(); self._clear_redis(); self._seed_approval_actor(); self._start('java'); self._health()
        return self._verify_identity()

    def issue_node_token(self):
        from node_credentials import issue_node_token
        return issue_node_token(self._validate_properties()['cretas.jwt.secret'])

    def _seed_approval_actor(self):
        """A non-login fixture satisfies the genuine independent approval route.

        It exists only during a prepared round and is removed by the normal
        baseline restore. No source-person record or production account is used.
        """
        node=self._inspect('pg')
        expected=json.loads(Path(self.cfg['identity_path']).read_text(encoding='utf-8'))
        if node['Id'] != expected['nodes']['pg']['id']: raise OrchestratorError('registered fixture database changed')
        self._pg("""BEGIN;
DO $$ BEGIN
 IF (SELECT count(*) FROM public.users) <> 1 OR
    (SELECT count(*) FROM public.users WHERE id=1309 AND factory_id='F006') <> 1
 THEN RAISE EXCEPTION 'approval fixture requires the single registered F006 actor'; END IF;
END $$;
INSERT INTO public.users (id,created_at,updated_at,factory_id,is_active,password_hash,role_code,username,full_name,level,platform_type,employee_code)
OVERRIDING SYSTEM VALUE VALUES (900006001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'F006',true,'SANDBOX_DISABLED','finance_manager','demo_f006_finance_reviewer','演示财务复核（隔离）',99,'','D006FIN');
COMMIT;""")

    def reset(self):
        self._verify_identity(); self._stop_java(); self._restore(); self._clear_redis()
        return self._verify_identity()

    def _health(self):
        deadline = time.monotonic() + 360
        while time.monotonic() < deadline:
            try:
                status, _, _ = self.request('/api/mobile/health',timeout=4)
                if status == 200: return
            except (OSError,OrchestratorError): pass
            if not self._inspect_running('java'): raise OrchestratorError('sandbox Java exited during startup; inspect private logs')
            time.sleep(1)
        raise OrchestratorError('sandbox Java startup timed out')

    def request(self, path, method='GET', body=None, headers=None, timeout=60):
        """Internal-network HTTP via exec; independent of loopback port mapping.

        curl reads its config (including the private node JWT) from stdin, never
        argv/files. Only UTF-8 business payloads are supported; local binary
        attachments are handled by the manager before reaching this transport.
        """
        node=self._inspect('java')
        expected=json.loads(Path(self.cfg['identity_path']).read_text(encoding='utf-8'))
        if node['Id'] != expected['nodes']['java']['id'] or not node['State']['Running']: raise OrchestratorError('registered Java node is not running')
        from urllib.parse import urlsplit
        target=urlsplit(path)
        if target.scheme or target.netloc or target.fragment or not target.path.startswith('/api/mobile/') or '%' in target.path or any(c in path for c in '\r\n\\') or any(p in {'','.','..'} for p in target.path[1:].split('/')) or method not in {'GET','HEAD','POST','PUT','PATCH','DELETE'}: raise OrchestratorError('internal HTTP request denied')
        def quote(value):
            if '\0' in value: raise OrchestratorError('binary payload requires the local attachment endpoint')
            return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\r','\\r').replace('\n','\\n').replace('\t','\\t')+'"'
        lines=['url = '+quote('http://127.0.0.1:10040'+path),'request = '+quote(method),'silent','show-error','include','proto = "http"','noproxy = "*"','max-time = '+str(timeout),'connect-timeout = 3','max-filesize = '+str(16*1024*1024),'header = "Expect:"']
        for key,value in (headers or {}).items():
            if key.lower() not in {'content-type','authorization','idempotency-key','accept'} or any(c in key+value for c in '\r\n'): raise OrchestratorError('internal HTTP header denied')
            lines.append('header = '+quote(key+': '+value))
        if method == 'HEAD': lines.append('head')
        if body is not None:
            try: text=body.decode('utf-8')
            except UnicodeDecodeError: raise OrchestratorError('business body must be UTF-8; binary files use the local attachment endpoint') from None
            lines.append('data-binary = '+quote(text))
        raw=self._checked(['docker','exec','-i',CONTAINERS['java'],'curl','-q','--config','-'],input=('\n'.join(lines)+'\n').encode(),timeout=timeout+15).stdout
        if len(raw)>16*1024*1024+65536: raise OrchestratorError('internal HTTP response exceeded the limit')
        head,separator,data=raw.partition(b'\r\n\r\n')
        if not separator: raise OrchestratorError('invalid internal HTTP response')
        try:
            status=int(head.splitlines()[0].split()[1])
            content_type=next((line.split(b':',1)[1].strip().decode('ascii') for line in head.splitlines()[1:] if line.lower().startswith(b'content-type:')),'application/json')
        except (ValueError,IndexError,UnicodeDecodeError): raise OrchestratorError('invalid internal HTTP headers') from None
        if 300<=status<400: raise OrchestratorError('internal HTTP redirects are forbidden')
        return status,{'Content-Type':content_type},data

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('action', choices=['provision','prepare','reset','stop']); parser.add_argument('--config', required=True)
    args = parser.parse_args(); cfg = json.loads(Path(args.config).read_text(encoding='utf-8')); slot = SandboxOrchestrator(cfg)
    if args.action == 'stop': slot._verify_identity(); slot._stop_java()
    else: getattr(slot, args.action)()
    print(json.dumps({'action': args.action, 'result':'OK'}))

if __name__ == '__main__': main()
