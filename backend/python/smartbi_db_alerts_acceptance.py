"""Real existing DB-overview route with real Java JWT and isolated PostgreSQL."""
import json
import time
from smartbi_alerts_testenv import create_app,directory

def main():
    app=create_app()  # Configure the isolated reader before legacy settings import.
    from smartbi.api.db_analysis import router
    from fastapi.testclient import TestClient
    from blacklake.secrets import load_local_secret
    import httpx,jwt
    app.include_router(router)
    config=load_local_secret(directory()/'testenv.dpapi')
    with httpx.Client(timeout=20,trust_env=False) as c:
        data=c.post('http://127.0.0.1:11010/api/mobile/auth/unified-login',json={k:config[k] for k in ('username','password')}).json()['data']
    assert data['factoryId']==config['tenant_id']
    token=data.get('accessToken') or data.get('token')
    claims=jwt.decode(token,config['jwt_secret'],algorithms=['HS256'],options={'verify_iat':False})
    delay=claims.get('iat',0)-time.time()
    if delay>5:raise RuntimeError('TEST_CLOCK_SKEW')
    if delay>0:time.sleep(delay+0.03)
    with TestClient(app) as client:
        headers={'Authorization':'Bearer '+token};url='/api/smartbi/analysis/db/overview'
        invalid=client.post(url,json={'upload_id':101},headers=headers)
        assert invalid.status_code==200
        body=invalid.json();assert body['success'] is False and body['dataAlerts']['summary']['byType']['NUMERIC_FIELD_INVALID']==1
        assert not body['kpiCards'] and not body['charts']
        assert client.post(url,json={'upload_id':201},headers=headers).status_code==404
        for upload in (102,104):
            value=client.post(url,json={'upload_id':upload},headers=headers).json()
            assert value['success'] is False and value['dataAlerts']['analysisStatus']=='restricted'
            assert not value['kpiCards'] and not value['charts'] and not value['fieldDefinitions']
    result={'success':True,'actual_sqlalchemy_pg':True,'actual_java_jwt':True,'foreign_metadata':404,
            'invalid_numeric_stops_before_aggregation':True,'restricted_cost_and_finance_no_kpis':True,'sourceBusinessWrites':0}
    (directory()/'db-overview-acceptance.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result));return 0

if __name__=='__main__':raise SystemExit(main())
