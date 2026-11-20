"""Copy verified archive to the existing local SmartBI test database, no source I/O."""
import json
import os
import secrets
import subprocess
from pathlib import Path

import bcrypt
from psycopg2.extensions import make_dsn

from .full_archive import Archive, digest
from .full_verify import offline_context, validate_retained_run
from .secrets import load_local_secret, save_local_secret


def main():
    directory, store = offline_context()
    try:
        run = load_local_secret(directory/'full-run.dpapi')
        report = load_local_secret(directory/'full-report.dpapi')
        archive = Archive(None,store,{},run['run_id'])
        validate_retained_run(run,report,archive.audit())
        if report.get('integrity_passed') is not True:
            raise RuntimeError('VERIFIED_ARCHIVE_REQUIRED')
        with store.transaction() as cur:
            cur.execute("SELECT s.resource,s.external_id,s.content_hash,r.payload FROM blacklake.full_snapshot s JOIN blacklake.raw_record r ON r.tenant_id=s.tenant_id AND r.source_factory_id=s.source_factory_id AND r.resource='archive:'||s.resource AND r.external_id=s.external_id AND r.content_hash=s.content_hash WHERE s.run_id=%s", (run['run_id'],))
            rows = [{'resource':r,'external_id':i,'content_hash':h,'payload':p} for r,i,h,p in cur.fetchall()]
        if any(digest(r['payload']) != r['content_hash'] for r in rows):
            raise RuntimeError('SOURCE_ARCHIVE_HASH_MISMATCH')
        settings_path = directory/'smartbi-test.dpapi'
        if settings_path.exists():
            settings = load_local_secret(settings_path)
        else:
            # Capture into memory only. Never echo service environment or JWT keys.
            script = "import json;from pathlib import Path;v=dict(x.split('=',1) for x in Path('/opt/cretas-testenv/testenv.env').read_text().splitlines() if '=' in x and not x.startswith('#'));assert v['PYTHON_ENV']=='test';print(json.dumps({'jwt_secret':v['JWT_SECRET'].strip(chr(34))}))"
            result = subprocess.run(['wsl','-d','Ubuntu-22.04','--','/opt/cretas-testenv/venv/bin/python','-c',script],capture_output=True,text=True,check=True)
            settings = json.loads(result.stdout)
            settings.update(reader_password=secrets.token_urlsafe(32),username='blacklake_lsm_bi',password=secrets.token_urlsafe(24),
                            tenant_id=store.scope[0],source_factory_id=store.scope[1],run_id=run['run_id'])
            settings['dsn']=make_dsn(host='127.0.0.1',port=15432,dbname='smartbi_testenv',user='blacklake_bi_reader',password=settings['reader_password'])
            save_local_secret(settings_path,settings)
        if (settings['tenant_id'],settings['source_factory_id'],settings['run_id']) != (*store.scope,run['run_id']):
            raise RuntimeError('SMARTBI_TEST_BINDING_CHANGED')
        request = dict(tenant_id=store.scope[0],source_factory_id=store.scope[1],run_id=run['run_id'],database='smartbi_testenv',
                       reader_password=settings['reader_password'],username=settings['username'],
                       password_hash=bcrypt.hashpw(settings['password'].encode(),bcrypt.gensalt()).decode(),metadata=report,records=rows)
        path = Path(__file__).resolve()
        # This repository's approved Windows worktree is visible in WSL; no remote hosts.
        wsl_path='/mnt/'+path.drive[0].lower()+path.as_posix()[2:]
        admin_path=wsl_path.rsplit('/',1)[0]+'/smartbi_testenv_admin.py'
        result=subprocess.run(['wsl','-d','Ubuntu-22.04','--','/opt/cretas-testenv/venv/bin/python',admin_path],
                              input=json.dumps(request),capture_output=True,text=True,encoding='utf-8')
        if result.returncode:
            save_local_secret(directory/'smartbi-import-error.dpapi', {'stderr':result.stderr,'stdout':result.stdout})
            raise RuntimeError('SMARTBI_TEST_IMPORT_FAILED_PRIVATE_DETAILS_WITHHELD')
        outcome=json.loads(result.stdout)
        from .smartbi_store import read
        verified=read(settings)
        if sum(r['count'] for r in verified['catalog'])!=len(rows):
            raise RuntimeError('SMARTBI_READER_CONNECTION_OR_COUNTS_NOT_VERIFIED')
        outcome['reader_connection_verified']=True
        import httpx
        with httpx.Client(timeout=30) as client:
            login=client.post('http://127.0.0.1:11010/api/mobile/auth/unified-login',json={'username':settings['username'],'password':settings['password']})
            body=login.json()
            if login.status_code!=200 or body.get('success') is not True or body.get('data',{}).get('factoryId')!=settings['tenant_id']:
                raise RuntimeError('SMARTBI_PREPARED_USER_LOGIN_NOT_VERIFIED')
        outcome['java_login_verified']=True
        save_local_secret(directory/'smartbi-import.dpapi',outcome)
        print(json.dumps(outcome))
    finally:
        store.connection.close()


if __name__ == '__main__':
    main()
