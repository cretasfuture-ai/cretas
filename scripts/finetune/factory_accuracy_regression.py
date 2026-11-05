"""Protected old diagnostics, only after primary blind results; never model selection."""
import argparse
import asyncio
import json
from pathlib import Path
from factory_experiment import read,write,save,sha,OLD
from factory_accuracy_train import verify,predict,scores,BASELINE,PREVIOUS
from factory_accuracy_eval import CandidatePool,decision,mini_many


async def regression(root):
    if not (root/'blind-summary.json').exists():raise ValueError('Primary blind result must be frozen before legacy diagnostics')
    manifest=verify(root);selection=json.loads((root/'selection.json').read_text(encoding='utf-8'));policy=json.loads((root/'routing-policy.json').read_text(encoding='utf-8'))
    selected=Path(selection['model_path'])
    if sha(selected/'model.safetensors')!=policy['model_sha256']:raise ValueError('Model changed')
    mapping=json.loads((root/'model-mapping.json').read_text(encoding='utf-8'))
    pool=CandidatePool(json.loads((root/'definitions.json').read_text(encoding='utf-8')),mapping,manifest['aliases'])
    summaries={}
    for name,file in [('legacy62',PREVIOUS/'test.jsonl'),('review35',PREVIOUS/'reviewed-35.jsonl')]:
        rows=[dict(r,record_id=f'{name}:{i}') for i,r in enumerate(read(file))]
        arms={}
        for arm,path in [('old259',OLD/'baseline-model'),('previous266',BASELINE),('new266',selected)]:
            pred,_=predict(path,rows);write(root/(name+'-'+arm+'.jsonl'),pred);arms[arm]=pred
        pred=arms['new266']
        uncertain=[r for r in pred if decision(r,mapping,policy)[1]=='abstain']
        cloud=await mini_many(root,name,uncertain,pool)
        if policy.get('critic_enabled'):
            from factory_accuracy_critic import review_disagreements
            cloud=await review_disagreements(root,name,uncertain,cloud,pool)
        paired=[]
        for row in pred:
            local,_=decision(row,mapping,policy)
            actual,route=decision(row,mapping,policy,cloud.get(row['record_id']))
            paired.append(dict(row,local_pipeline=local,prediction=actual,route=route,training_eligible=False))
        write(root/(name+'-hybrid.jsonl'),paired)
        summary={arm:scores(predictions) for arm,predictions in arms.items()}
        summary.update(local_pipeline=scores([dict(r,prediction=r['local_pipeline']) for r in paired]),hybrid=scores(paired),mini_calls=len(uncertain),source_hash=sha(file),used_for_model_selection=False)
        if name=='legacy62':
            summary['novel61']={arm:scores([r for i,r in enumerate(predictions) if i!=49]) for arm,predictions in arms.items()}
            summary['novel61']['hybrid']=scores([r for i,r in enumerate(paired) if i!=49])
        summaries[name]=summary
    save(root/'legacy-summary.json',summaries)
    print('LEGACY_DIAGNOSTIC',json.dumps(summaries,ensure_ascii=False),flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--evidence',type=Path,required=True);args=parser.parse_args();asyncio.run(regression(args.evidence))
