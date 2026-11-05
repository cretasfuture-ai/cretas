"""Quota recovery keeps every row and never retries an unknown HTTP outcome."""
import asyncio
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'scripts/finetune'))
import factory_generalization_recovery as recovery


def test_unknown_quarantine_preserves_original_ledger_and_locks_local_predictions(tmp_path,monkeypatch):
    monkeypatch.setattr(recovery,'validate',lambda _: ({},{},{}))
    directory=tmp_path/'confirmation-shadow';directory.mkdir()
    ledger=[dict(id='unknown',model='fixture',state='pending',reserved_tokens=100)]
    original=json.dumps(ledger).encode()
    (directory/'api-ledger.json').write_bytes(original)
    (directory/'api-results.jsonl').write_text(json.dumps(dict(id='unknown',day='2026-11-05',error='ReadError'))+'\n',encoding='utf-8')
    local=tmp_path/'confirmation-local.jsonl';local.write_text('frozen-local-results',encoding='utf-8')
    result=recovery.freeze(tmp_path)
    assert result['unknown_never_replay']==['unknown']
    assert (tmp_path/'confirmation-ledger-before-resolution.json').read_bytes()==original
    assert json.loads((directory/'api-ledger.json').read_text(encoding='utf-8'))[0]['state']=='abandoned_unknown'
    assert recovery.freeze(tmp_path)==result
    local.write_text('changed',encoding='utf-8')
    with pytest.raises(ValueError,match='local results changed'):recovery.freeze(tmp_path)


@pytest.mark.parametrize('unknown',['positive','ambiguous'])
def test_recovery_never_calls_unknown_and_scores_it_wrong_without_dropping_rows(tmp_path,monkeypatch,unknown):
    policy=dict(temperature=1.,confidence=.95,margin=.05,mini_confidence=.8,critic_enabled=False,model_sha256='frozen-weights')
    selected=dict(validation=dict(accuracy=1.),training_validation_gap=0.)
    monkeypatch.setattr(recovery,'validate',lambda _: (dict(aliases={}),policy,selected))
    request='v2:confirmation:'+unknown
    monkeypatch.setattr(recovery,'freeze',lambda _:dict(unknown_never_replay=[request]))
    mapping={'A':0,'B':1}
    rows=[dict(record_id='positive',text='long synthetic request',label='A',style='long'),dict(record_id='short',text='short request',label='B',style='short'),dict(record_id='ambiguous',text='unclear business request',label='__CLARIFY__',style='ambiguous')]
    pred=[dict(r,prediction='B' if r['label']=='B' else 'A',logits=[0.,0.]) for r in rows]
    recovery.write(tmp_path/'test.jsonl',rows);recovery.write(tmp_path/'confirmation-local.jsonl',pred)
    recovery.save(tmp_path/'model-mapping.json',mapping);recovery.save(tmp_path/'definitions.json',[])
    pool=SimpleNamespace(choices=lambda *_:[dict(value=k) for k in ['A','B','__CLARIFY__']])
    monkeypatch.setattr(recovery,'CandidatePool',lambda *_:pool)
    called=[]
    async def cloud(root,phase,eligible,pool):
        called.extend(r['record_id'] for r in eligible)
        return {r['record_id']:dict(prediction=r['label'],confidence=1.,candidates=['A','B','__CLARIFY__']) for r in eligible}
    monkeypatch.setattr(recovery,'mini_many',cloud)
    monkeypatch.setattr(recovery,'predict',lambda *_:(pred,mapping))
    asyncio.run(recovery.recover(tmp_path))
    assert unknown not in called and len(called)==2
    summary=json.loads((tmp_path/'confirmation-summary.json').read_text(encoding='utf-8'))
    assert summary['n']==3 and summary['hybrid']['n']==3 and summary['hybrid']['correct']==2
    paired=recovery.read(tmp_path/'confirmation-paired.jsonl')
    assert next(r for r in paired if r['record_id']==unknown)['prediction']=='__API_ERROR__'
    assert summary['primary_confirmation_openings']==1
    assert summary['transport_failures_scored_incorrect']==[request]
