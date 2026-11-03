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
        response=client.post('http://127.0.0.1:11210/api/mobile/auth/unified-login',
                             json={'username':settings['username'],'password':settings['password']})
        result=response.json()
        save_local_secret(directory/'smartbi-test-login.dpapi',result)
        if response.status_code!=200 or result.get('success') is not True:
            raise RuntimeError('TEST_JAVA_LOGIN_FAILED')
        login=result['data']
        token=login.get('accessToken') or login.get('token')
        if login.get('factoryId')!=settings['tenant_id'] or not token:
            raise RuntimeError('TEST_LOGIN_FACTORY_MISMATCH')
        # Wait for the real signed token to activate; server iat/exp verification stays strict.
        claims=jwt.decode(token,settings['jwt_secret'],algorithms=['HS256'],options={'verify_iat':False})
        wait=float(claims.get('iat',0))-time.time()
        if wait>5:raise RuntimeError('TEST_CLOCK_SKEW_EXCEEDS_LIMIT')
        if wait>0:time.sleep(min(5,wait+0.025))
        url='http://127.0.0.1:8093/api/smartbi/blacklake/analysis'
        response=client.get(url,headers={'Authorization':'Bearer '+token})
        if response.status_code!=200:
            raise RuntimeError('SMARTBI_AUTHENTICATED_API_FAILED')
        analysis=response.json()['data']
        alerts=analysis['costAlerts'];review=analysis['eligibleCostReview']
        assert alerts['summary']['costEligible']==16 and alerts['summary']['costBlocked']==93
        assert review['eligibleProjects']==16 and len(review['projects'])==16
        assert alerts['ai']['status']=='complete'
        assert alerts['ai']['model']=='qwen3.8:27b' and alerts['ai']['host']=='canada-mini'
        assert {str(p['projectId']) for a in alerts['items'] if a['blocksUnitCost'] for p in a['entityRefs']}=={str(p['projectId']) for p in analysis['cost']['projects'] if not p['unitCostAvailable']}
        assert alerts['summary']['byType']['PRODUCT_NAME_CONFLICT']==1
        assert alerts['summary']['byType']['UNIT_COST_SPREAD']==1
        assert alerts['summary']['byType']['INPUT_ONLY_CONSUMABLES']==1
        for project in review['projects']:
            assert abs(sum(r['costPerFinished'] for r in project['components'])+project['laborPerFinished']-project['unitIdentifiedDirectCost'])<1e-8
        assert sum(r['count'] for r in analysis['catalog'])==1879
        assert analysis['production']['projectCount']==109
        assert analysis['cost']['fullManufacturingCost'] is None
        intelligence=analysis['costIntelligence']
        assert intelligence['engine'].startswith('Cretas SmartBI')
        assert len(intelligence['recommendations'])==8
        assert sum(c['bridge'] is not None for c in intelligence['comparisons'])==3
        assert intelligence['gapEvidence']['status']=='complete'
        assert intelligence['gapEvidence']['summary']['materialsQueryVerified']==225
        assert intelligence['gapEvidence']['summary']['zeroAmountsReconfirmed']==74
        assert sum(len(rows) for rows in intelligence['verifiedMaterialReferences'].values())==225
        for comparison in intelligence['comparisons']:
            if comparison['bridge']:assert abs(comparison['bridge']['reconciliationDifference'])<1e-8
        save_local_secret(directory/'smartbi-analysis.dpapi',analysis)
        assert client.get(url).status_code==401
        for factory,role in [('F006','factory_super_admin'),(settings['tenant_id'],'viewer')]:
            denied=jwt.encode({'factoryId':factory,'userId':1,'role':role,'exp':int(time.time())+120},settings['jwt_secret'],algorithm='HS256')
            assert client.get(url,headers={'Authorization':'Bearer '+denied}).status_code==403
        assert client.post(url,headers={'Authorization':'Bearer '+token}).status_code==405
        for timing in ({'iat':int(time.time())+60,'exp':int(time.time())+120},
                       {'iat':int(time.time())-120,'exp':int(time.time())-60}):
            invalid=jwt.encode(dict(factoryId=settings['tenant_id'],userId=1,role='factory_super_admin',**timing),settings['jwt_secret'],algorithm='HS256')
            assert client.get(url,headers={'Authorization':'Bearer '+invalid}).status_code==401
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
            'findings':analysis['findings'],'unit_cost_available':analysis['cost']['unitCostAvailableProjects'],
            'smartbi_candidates':8,'comparable_products':3,'verified_material_query_rows':225,
            'source_zero_rows_reconfirmed':74,'cost_bridge_reconciliation_passed':True,
            'future_issued_and_expired_tokens_denied':True}
    report.update(cost_alerts=alerts['summary'],local_ai_status=alerts['ai']['status'],local_ai_reviews=len(alerts['ai']['reviews']),
                  eligible_review_reconciled=16,blocked_project_alert_coverage=93)
    save_local_secret(directory/'smartbi-acceptance.dpapi',report)
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':
    main()
