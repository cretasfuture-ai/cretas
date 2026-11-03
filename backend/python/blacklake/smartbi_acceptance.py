"""Live local test API acceptance. Credentials and tokens stay in memory/DPAPI."""
import json
import os
import time
from pathlib import Path

import httpx
import jwt
import psycopg2

from .secrets import load_local_secret, save_local_secret


def main():
    directory=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    settings=load_local_secret(directory/'smartbi-test.dpapi')
    with httpx.Client(timeout=60) as client:
        response=client.post('http://127.0.0.1:11010/api/mobile/auth/unified-login',
                             json={'username':settings['username'],'password':settings['password']})
        result=response.json()
        save_local_secret(directory/'smartbi-test-login.dpapi',result)
        if response.status_code!=200 or result.get('success') is not True:
            raise RuntimeError('TEST_JAVA_LOGIN_FAILED')
        login=result['data']
        token=login.get('accessToken') or login.get('token')
        if login.get('factoryId')!=settings['tenant_id'] or not token:
            raise RuntimeError('TEST_LOGIN_FACTORY_MISMATCH')
        url='http://127.0.0.1:8093/api/smartbi/blacklake/analysis'
        response=client.get(url,headers={'Authorization':'Bearer '+token})
        if response.status_code!=200:
            raise RuntimeError('SMARTBI_AUTHENTICATED_API_FAILED')
        analysis=response.json()['data']
        assert sum(r['count'] for r in analysis['catalog'])==1879
        assert analysis['production']['projectCount']==109
        assert analysis['cost']['fullManufacturingCost'] is None
        save_local_secret(directory/'smartbi-analysis.dpapi',analysis)
        assert client.get(url).status_code==401
        for factory,role in [('F006','factory_super_admin'),(settings['tenant_id'],'viewer')]:
            denied=jwt.encode({'factoryId':factory,'userId':1,'role':role,'exp':int(time.time())+120},settings['jwt_secret'],algorithm='HS256')
            assert client.get(url,headers={'Authorization':'Bearer '+denied}).status_code==403
        assert client.post(url,headers={'Authorization':'Bearer '+token}).status_code==405
    connection=psycopg2.connect(settings['dsn'])
    try:
        with connection:
            with connection.cursor() as cur:
                for table in ('record','dataset'):
                    cur.execute('SELECT count(*) FROM blacklake_bi.'+table)
                    assert cur.fetchone()[0]==0
                    cur.execute("SELECT has_table_privilege(current_user,%s,'INSERT') OR has_table_privilege(current_user,%s,'UPDATE') OR has_table_privilege(current_user,%s,'DELETE') OR has_table_privilege(current_user,%s,'TRUNCATE')", ('blacklake_bi.'+table,)*4)
                    assert cur.fetchone()[0] is False
                cur.execute("SELECT set_config('blacklake.tenant_id','foreign',true),set_config('blacklake.source_factory_id',%s,true)",(settings['source_factory_id'],))
                cur.execute('SELECT count(*) FROM blacklake_bi.record')
                assert cur.fetchone()[0]==0
                cur.execute("SELECT set_config('blacklake.tenant_id',%s,true),set_config('blacklake.source_factory_id','foreign',true)",(settings['tenant_id'],))
                cur.execute('SELECT count(*) FROM blacklake_bi.record')
                assert cur.fetchone()[0]==0
    finally:
        connection.close()
    report={'success':True,'records':1879,'api_authorized':True,'unauthenticated_denied':True,
            'cross_factory_denied':True,'viewer_cost_denied':True,'mutation_method_denied':True,
            'database_unscoped_and_foreign_rows':0,'reader_write_privileges':False,
            'findings':analysis['findings'],'unit_cost_available':analysis['cost']['unitCostAvailableProjects']}
    save_local_secret(directory/'smartbi-acceptance.dpapi',report)
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    main()
