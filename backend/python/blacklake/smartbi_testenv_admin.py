"""Explicit, local test-only import. Read input from private stdin, print counts only."""
import json
import sys
import os
import pwd
import hashlib
from pathlib import Path

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json, execute_values
from bi_readiness import validate

TENANT = 'BLACKLAKE_POC_TEST'


def main():
    request = json.load(sys.stdin)
    if request['tenant_id'] != TENANT or request['database'] != 'smartbi_testenv':
        raise RuntimeError('TEST_SCOPE_REQUIRED')
    migration = (Path(__file__).parent / 'migrations/003_smartbi_dataset.sql').read_text()
    if os.geteuid() == 0:
        local_postgres = pwd.getpwnam('postgres')
        os.setgroups([])
        os.setgid(local_postgres.pw_gid)
        os.setuid(local_postgres.pw_uid)
    connection = psycopg2.connect(dbname='smartbi_testenv', port=15432, user='postgres')
    try:
        with connection:
            with connection.cursor() as cur:
                cur.execute('SELECT current_database(),inet_server_addr()')
                if cur.fetchone() != ('smartbi_testenv', None):
                    raise RuntimeError('LOCAL_UNIX_TEST_DATABASE_REQUIRED')
                cur.execute("SELECT to_regnamespace('blacklake_bi')")
                if cur.fetchone()[0] is None:
                    cur.execute(migration)
                cur.execute("SELECT 1 FROM pg_roles WHERE rolname='blacklake_bi_reader'")
                if not cur.fetchone():
                    cur.execute(sql.SQL('CREATE ROLE blacklake_bi_reader LOGIN PASSWORD {} NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOINHERIT').format(sql.Literal(request['reader_password'])))
                cur.execute(sql.SQL('ALTER ROLE blacklake_bi_reader NOINHERIT PASSWORD {}').format(sql.Literal(request['reader_password'])))
                cur.execute('GRANT USAGE ON SCHEMA blacklake_bi TO blacklake_bi_reader')
                cur.execute('GRANT SELECT ON blacklake_bi.dataset,blacklake_bi.record TO blacklake_bi_reader')
                validate(cur)
                cur.execute('INSERT INTO blacklake_bi.dataset(tenant_id,source_factory_id,run_id,metadata) VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING',
                            (TENANT,request['source_factory_id'],request['run_id'],Json(request['metadata'])))
                execute_values(cur, 'INSERT INTO blacklake_bi.record(tenant_id,source_factory_id,run_id,resource,external_id,content_hash,payload) VALUES %s ON CONFLICT DO NOTHING',
                               [(TENANT,request['source_factory_id'],request['run_id'],r['resource'],r['external_id'],r['content_hash'],Json(r['payload'])) for r in request['records']])
                cur.execute('SELECT resource,count(*) FROM blacklake_bi.record WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s GROUP BY resource',
                            (TENANT,request['source_factory_id'],request['run_id']))
                counts = dict(cur.fetchall())
                if counts != request['metadata']['source_totals']:
                    raise RuntimeError('SMARTBI_IMPORT_COUNTS_MISMATCH')
                cur.execute('SELECT metadata FROM blacklake_bi.dataset WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s',
                            (TENANT,request['source_factory_id'],request['run_id']))
                if cur.fetchone()[0] != request['metadata']:
                    raise RuntimeError('SMARTBI_IMPORT_METADATA_MISMATCH')
                cur.execute('SELECT resource,external_id,content_hash,payload FROM blacklake_bi.record WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s',
                            (TENANT,request['source_factory_id'],request['run_id']))
                expected={(r['resource'],r['external_id']):(r['content_hash'],r['payload']) for r in request['records']}
                actual={}
                for resource,external_id,content_hash,payload in cur.fetchall():
                    encoded=json.dumps(payload,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()
                    if hashlib.sha256(encoded).hexdigest()!=content_hash:
                        raise RuntimeError('SMARTBI_TARGET_PAYLOAD_HASH_MISMATCH')
                    actual[(resource,external_id)]=(content_hash,payload)
                if actual!=expected:
                    raise RuntimeError('SMARTBI_SOURCE_TARGET_CONTENT_MISMATCH')
    finally:
        connection.close()
    connection = psycopg2.connect(dbname='cretas_testenv', port=15432, user='postgres')
    try:
        with connection:
            with connection.cursor() as cur:
                cur.execute('SELECT name,is_active,type,deleted_at,manually_verified FROM factories WHERE id=%s', (TENANT,))
                row = cur.fetchone()
                if row and row != ('六膳门 · 黑湖测试',True,'FACTORY',None,True):
                    raise RuntimeError('TEST_FACTORY_ID_ALREADY_OWNED')
                if not row:
                    cur.execute("INSERT INTO factories(id,name,created_at,updated_at,ai_weekly_quota,is_active,manually_verified,type) VALUES(%s,%s,now(),now(),100,true,true,'FACTORY')", (TENANT,'六膳门 · 黑湖测试'))
                cur.execute('SELECT factory_id,role_code FROM users WHERE username=%s', (request['username'],))
                row = cur.fetchone()
                if row and row != (TENANT,'factory_super_admin'):
                    raise RuntimeError('TEST_USER_ALREADY_OWNED')
                if not row:
                    cur.execute("INSERT INTO users(factory_id,username,full_name,password_hash,role_code,is_active,created_at,updated_at,level,platform_type) VALUES(%s,%s,%s,%s,'factory_super_admin',true,now(),now(),0,'web')", (TENANT,request['username'],'六膳门测试分析',request['password_hash']))
                else:
                    cur.execute("UPDATE users SET password_hash=%s,is_active=true,platform_type='web',updated_at=now() WHERE username=%s AND factory_id=%s AND role_code='factory_super_admin' AND deleted_at IS NULL",(request['password_hash'],request['username'],TENANT))
                    if cur.rowcount!=1: raise RuntimeError('TEST_USER_CANNOT_BE_REACTIVATED')
    finally:
        connection.close()
    print(json.dumps({'success':True,'counts':counts,'records':sum(counts.values()),'factory':TENANT}))


if __name__ == '__main__':
    main()
