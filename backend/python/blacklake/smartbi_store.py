"""Read-only SmartBI dataset reader, isolated from source credentials and source API."""
from collections import Counter, defaultdict
from pathlib import Path

import psycopg2
from psycopg2.extensions import parse_dsn

from .analytics import build
from .full_archive import digest
from .secrets import load_local_secret
from .bi_readiness import validate


def settings(path):
    value=load_local_secret(Path(path))
    dsn=parse_dsn(value['dsn'])
    if (dsn.get('host')!='127.0.0.1' or dsn.get('port')!='15432'
            or dsn.get('dbname')!='smartbi_testenv' or dsn.get('user')!='blacklake_bi_reader'
            or value.get('tenant_id')!='BLACKLAKE_POC_TEST'):
        raise RuntimeError('SMARTBI_ISOLATED_TEST_DESTINATION_REQUIRED')
    return value


def read(value):
    connection=psycopg2.connect(value['dsn'],connect_timeout=5)
    try:
        connection.set_session(readonly=True)
        with connection:
            with connection.cursor() as cur:
                validate(cur,parse_dsn(value['dsn'])['user'])
                cur.execute('SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user')
                if any(cur.fetchone()): raise RuntimeError('SMARTBI_READER_BYPASSES_RLS')
                cur.execute("SELECT set_config('blacklake.tenant_id',%s,true),set_config('blacklake.source_factory_id',%s,true)",
                            (value['tenant_id'],value['source_factory_id']))
                scope=(value['tenant_id'],value['source_factory_id'],value['run_id'])
                cur.execute('SELECT metadata FROM blacklake_bi.dataset WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s',scope)
                found=cur.fetchone()
                if not found: raise RuntimeError('SMARTBI_DATASET_NOT_FOUND')
                metadata=found[0]
                cur.execute('SELECT resource,content_hash,payload FROM blacklake_bi.record WHERE tenant_id=%s AND source_factory_id=%s AND run_id=%s ORDER BY resource,external_id',scope)
                records=cur.fetchall()
        counts=dict(Counter(r[0] for r in records))
        if counts!=metadata['source_totals'] or any(digest(p)!=h for _,h,p in records):
            raise RuntimeError('SMARTBI_DATASET_INTEGRITY_FAILED')
        data=defaultdict(list)
        for resource,_,payload in records: data[resource].append(payload)
        return build(data,metadata)
    finally:
        connection.close()
