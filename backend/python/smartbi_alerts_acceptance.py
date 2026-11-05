"""Actual local Java login + scoped PG reader + shared API + local model."""
import json
import os
from pathlib import Path
import sys
import time
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'smartbi'))

def main():
    import httpx
    import jwt
    from blacklake.secrets import load_local_secret
    from smartbi.services.dataset_alerts import analyze_dataset
    directory=(Path(os.environ['LOCALAPPDATA'])/'Cretas/SmartBIAlerts').resolve()
    config=load_local_secret(directory/'testenv.dpapi')
    with httpx.Client(timeout=170,trust_env=False) as client:
        login=client.post('http://127.0.0.1:11010/api/mobile/auth/unified-login',json={k:config[k] for k in ('username','password')}).json()
        assert login.get('success') and login['data']['factoryId']==config['tenant_id']
        token=login['data'].get('accessToken') or login['data'].get('token')
        claims=jwt.decode(token,config['jwt_secret'],algorithms=['HS256'],options={'verify_iat':False})
        delay=claims.get('iat',0)-time.time()
        if delay>5:raise RuntimeError('TEST_CLOCK_SKEW')
        if delay>0:time.sleep(delay+0.03)
        headers={'Authorization':'Bearer '+token};base='http://127.0.0.1:8094/api/smartbi/data-alerts'
        response=client.get(base+'/uploads/101',headers=headers)
        assert response.status_code==200
        excel=response.json()['data']
        assert {'NUMERIC_FIELD_MISSING','NUMERIC_FIELD_INVALID','DUPLICATE_ROWS_CANDIDATE'}<=set(excel['summary']['byType'])
        assert excel['capabilities']['cost']['status']=='not_applicable'
        assert all(ref.get('recordId') for item in excel['items'] for ref in item.get('sourceRefs',[]))
        assert client.get(base+'/uploads/101').status_code==401
        assert client.get(base+'/uploads/201',headers=headers).status_code==404
        assert client.get(base+'/uploads/999999',headers=headers).status_code==404
        assert client.get(base+'/uploads/101?factory_id=SYNTH_FOREIGN',headers=headers).status_code==403
        payload={'rows':config['finance_rows'],'columns':[{'originalName':'测量值','standardName':'测量值','fieldType':'number','isMeasure':True}],
                 'source_type':'api','source_id':'same-table-api','dataset_kind':'generic'}
        api=client.post(base+'/analyze',json=payload,headers=headers).json()['data']
        assert api['summary']==excel['summary']
        assert [i['type'] for i in api['items']]==[i['type'] for i in excel['items']]
        old=client.post('http://127.0.0.1:8094/api/analysis/sales/kpis',json={'data':config['finance_rows']},headers=headers)
        assert old.status_code==200 and old.json()['dataAlerts']['schema']=='smartbi.dataset-alerts/1'
        cost=client.get(base+'/uploads/102',headers=headers).json()['data']
        # The current local Java lacks cost-product-access; this MUST fail closed.
        assert cost['analysisStatus']=='restricted' and not cost['items'] and not cost['summary']['byType']
        finance=client.get(base+'/uploads/104',headers=headers).json()['data']
        assert finance['analysisStatus']=='restricted' and not finance['items']
        result=client.post(base+'/uploads/101/local-review',headers=headers)
        assert result.status_code==200
        reviewed=result.json()['data'];assert reviewed['ai']['status']=='complete'
        assert reviewed['ai']['model']=='qwen3.8:27b' and reviewed['ai']['sourceRecordsSent']==0
        assert reviewed['items']==excel['items']
    import psycopg2
    connection=psycopg2.connect(host=config['db_host'],port=config['db_port'],dbname=config['database'],user=config['db_user'],password=config['db_password'])
    with connection:
        with connection.cursor() as cur:
            cur.execute('SHOW default_transaction_read_only');assert cur.fetchone()[0]=='on'
            for table in ('smart_bi_pg_excel_uploads','smart_bi_dynamic_data','smart_bi_pg_field_definitions'):
                cur.execute("SELECT has_table_privilege(current_user,%s,'INSERT') OR has_table_privilege(current_user,%s,'UPDATE') OR has_table_privilege(current_user,%s,'DELETE')",(table,table,table))
                assert cur.fetchone()[0] is False
    connection.close()
    evidence={'success':True,'real_excel_to_pg':True,'authenticated_api':200,'unauthenticated':401,'foreign_or_missing_upload':404,'factory_override':403,
              'excel_api_rule_parity':True,'legacy_analysis_hook':True,'local_ai_model':reviewed['ai']['model'],'local_ai_status':'complete',
              'rules_unchanged_by_ai':True,'stored_cost_authority_unavailable_is_restricted':True,'reader_write_privileges':False,'sourceBusinessWrites':0,
              'stored_financial_scope_unverified_is_restricted':True,
              'production_deployed':False,'shared_java_upload_shell_verified':False}
    (directory/'acceptance.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
    print(json.dumps(evidence));return 0

if __name__=='__main__':raise SystemExit(main())
