"""Bounded synthetic 8B/27B comparison; no factory records or credentials sent."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'smartbi'))
from smartbi.services.dataset_alerts import analyze_dataset
from smartbi.services.alert_local_ai import facts_for,payload_for,infer,validate_response

def main():
    model_rows=[{'工单编码':f'SYNTHETIC-{i}','产品代码':'SYNTHETIC-SKU','产品名称':'合成产品','单位':'kg',
                 '成品良品量':10,'材料成本':v,'人工成本':10} for i,v in enumerate([100,110,200])]
    reports=[analyze_dataset(model_rows,factory_id='SYNTHETIC-BENCH',source_type='api',source_id='model-comparison')]
    reports.append(analyze_dataset([{'材料成本':None,'人工成本':0}],factory_id='SYNTHETIC-BENCH',source_type='api',source_id='missing-values'))
    reports.append(analyze_dataset([{'备注':'合成重复候选'},{'备注':'合成重复候选'}],factory_id='SYNTHETIC-BENCH',source_type='api',source_id='duplicate-rows'))
    chosen=[];seen=set()
    for f in [f for report in reports for f in facts_for(report)]:
        if f['type'] in ('NUMERIC_FIELD_MISSING','NUMERIC_FIELD_ZERO','DUPLICATE_ROWS_CANDIDATE','UNIT_COST_SPREAD_CANDIDATE') and f['type'] not in seen:
            chosen.append(f);seen.add(f['type'])
    assert len(chosen)==4
    pressure=subprocess.run(['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=6','canada-mini','memory_pressure -Q'],capture_output=True,text=True,timeout=15)
    free=re.search(r'free percentage:\s*(\d+)%',pressure.stdout)
    if pressure.returncode or not free or int(free.group(1))<45:raise RuntimeError('LOCAL_MODEL_MEMORY_WINDOW_UNAVAILABLE')
    outcome={'syntheticOnly':True,'sourceRecordsSent':0,'externalProviderCalls':0,'facts':len(chosen),'memoryFreePercentBefore':int(free.group(1)),'results':[]}
    for model in ('qwen3:8b','qwen3.8:27b'):
        started=time.monotonic()
        config={'transport':'ssh','host':'canada-mini','model':model}
        payload=payload_for(chosen,model);payload['options']['num_predict']=512
        try:
            raw=infer(payload,config);reviews=validate_response(raw,chosen)
            result={'model':model,'status':'passed','validReviews':len(reviews),'expectedFacts':len(chosen),
                    'completeCoverage':len(reviews)==len(chosen),'seconds':round(time.monotonic()-started,2),'reviews':reviews}
        except Exception as exc:
            result={'model':model,'status':'failed','reason':type(exc).__name__,'seconds':round(time.monotonic()-started,2)}
        outcome['results'].append(result)
    directory=(Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake/validation').resolve()
    directory.mkdir(parents=True,exist_ok=True)
    (directory/'shared-alert-model-benchmark.json').write_text(json.dumps(outcome,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(outcome,ensure_ascii=False))
    return 0 if all(r['status']=='passed' and r['completeCoverage'] for r in outcome['results']) else 1

if __name__=='__main__':raise SystemExit(main())
