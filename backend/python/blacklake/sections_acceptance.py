"""Actual six-shan-men snapshot, official test authority, 27B and section QA.

No Blacklake network mutation or source API login is involved in this replay.
"""
import json
import os
from pathlib import Path
import time
import sys

# Standalone CLI follows the main application's legacy SmartBI bootstrap.
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'smartbi'))

import httpx
import jwt

from .secrets import load_local_secret, save_local_secret


def main():
    directory=(Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake').resolve()
    config=load_local_secret(directory/'smartbi-test.dpapi')
    output=directory/'outputs/sections-27b-20261103';output.mkdir(parents=True,exist_ok=True)
    java='http://127.0.0.1:11210';base='http://127.0.0.1:8093/api/smartbi/blacklake'
    result={'success':False,'sourceBusinessWrites':0,'sharedJavaModified':False,'productionDeployed':False}
    from .snapshot_proof import snapshot_proof,artifact_proof
    result['snapshotBinding']=snapshot_proof(config)
    result['artifactBinding']=artifact_proof()
    with httpx.Client(timeout=170,trust_env=False) as client:
        login=client.post(java+'/api/mobile/auth/unified-login',json={k:config[k] for k in ('username','password')})
        value=login.json();assert login.status_code==200 and value.get('success') is True,'TEST_LOGIN_FAILED'
        assert value['data']['factoryId']==config['tenant_id']
        token=value['data'].get('accessToken') or value['data'].get('token')
        claims=jwt.decode(token,config['jwt_secret'],algorithms=['HS256'],options={'verify_iat':False})
        delay=claims.get('iat',0)-time.time()
        if delay>5:raise RuntimeError('TEST_CLOCK_SKEW')
        if delay>0:time.sleep(delay+0.03)
        headers={'Authorization':'Bearer '+token}
        endpoint=java+'/api/mobile/'+config['tenant_id']+'/smart-bi/cost-product-access'
        auth=client.post(endpoint,headers=headers,json={'productIds':[]})
        assert auth.status_code==200 and auth.json().get('success') is True,'REAL_COST_AUTHORITY_FAILED'
        authority=auth.json()['data']
        assert authority['factoryId']==config['tenant_id'] and authority['userId']==claims['userId'] and authority['unscopedCostAllowed'] is True
        assert client.post(endpoint,json={'productIds':[]}).status_code in (401,403)
        denied=client.post(java+'/api/mobile/F006/smart-bi/cost-product-access',headers=headers,json={'productIds':[]})
        assert denied.status_code==403 or (denied.status_code==200 and denied.json().get('success') is False)
        response=client.get(base+'/analysis',headers=headers);assert response.status_code==200
        analysis=response.json()['data'];review=analysis['eligibleCostReview']
        assert analysis['production']['projectCount']==109 and review['eligibleProjects']==16
        assert analysis['runId']==result['snapshotBinding']['runId']
        assert analysis['costAlerts']['summary']['costBlocked']==93
        assert len(analysis['sectionInsights'])==7
        assert sum(p['count'] for p in analysis['catalog'])==1879
        residual=[]
        for p in review['projects']:
            delta=sum(c['costPerFinished'] for c in p['components'])+p['laborPerFinished']-p['unitIdentifiedDirectCost']
            assert abs(delta)<1e-8;residual.append(abs(delta))
        assert analysis['cost']['fullManufacturingCost'] is None and analysis['cost']['grossProfit'] is None
        ai=client.post(base+'/alerts/local-review',headers=headers);assert ai.status_code==200
        report=ai.json()['data'];assert report['ai']['status']=='complete' and report['ai']['model']=='qwen3.8:27b'
        assert report['ai']['sourceRecordsSent']==0 and report['ai']['externalProviderCalls']==0
        refreshed=client.get(base+'/analysis',headers=headers).json()['data']
        assert refreshed['costAlerts']['ai']['model']=='qwen3.8:27b'
        qa=[]
        for section in refreshed['sectionInsights']:
            body={'section_id':section['sectionId'],'run_id':refreshed['runId'],'question':'这个数字怎么算的？'}
            response=client.post(base+'/sections/followup',headers=headers,json=body)
            assert response.status_code==200
            reply=response.json()['data']
            assert reply['sectionId']==section['sectionId'] and reply['runId']==refreshed['runId']
            assert '计算口径' in reply['answer']
            from .section_insights import section_alerts
            from smartbi.services.alert_local_ai import representative_groups
            expected={a['id'] for a in representative_groups({'items':section_alerts(refreshed,section['sectionId'])}).values()}
            seen={r['alertId'] for r in reply['ai'].get('reviews',[])}
            assert seen<=expected
            assert reply['ai']['sectionRepresentativeFacts']==len(expected)
            assert reply['ai']['reviewedSectionFacts']==len(seen)
            if not expected:assert reply['ai']['status']=='not_applicable' and '27B 补充核对建议' not in reply['answer']
            elif seen==expected:assert reply['ai']['status']=='complete' and reply['ai']['model']=='qwen3.8:27b'
            else:assert reply['ai']['status']=='partial'
            qa.append(reply)
        assert client.post(base+'/sections/followup',headers=headers,json={**body,'run_id':'stale'}).status_code==409
        save_local_secret(output/'analysis.dpapi',refreshed)
        save_local_secret(output/'section-conversations.dpapi',qa)
        save_local_secret(directory/'smartbi-test-login.dpapi',value)
        text=['# 六膳门：数据板块分析与跟进','',f'数据采集时点：{analysis["snapshotAt"]}；本次为该归档的重放核验，并非新采集。',
              '可识别直接成本，不含完整制造费用和毛利；币种待核。','']
        for section in refreshed['sectionInsights']:
            text+=['## '+section['title'],section['summary'],'']
            for title,key in [('观察','observations'),('计算口径','calculation'),('待核假设','hypotheses'),('限制','limitations'),('下一步','nextSteps')]:
                text += ['### '+title]+['- '+s for s in section[key]]+['']
        (output/'六膳门-板块分析与异常清单.md').write_text('\n'.join(text),encoding='utf-8')
        result.update(success=True,authorityStatus=auth.status_code,realAuthority=True,eligible=16,blocked=93,
                      sections=7,sectionFollowups=len(qa),model=report['ai']['model'],aiSourceRecordsSent=0,
                      maxUnitCostResidual=max(residual),snapshotAt=analysis['snapshotAt'],replayedSnapshot=True,
                      cached27bAttached=True,crossFactoryDenied=True,
                      sectionAiStates={q['sectionId']:{k:q['ai'][k] for k in ('status','sectionRepresentativeFacts','reviewedSectionFacts')} for q in qa})
    (output/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
