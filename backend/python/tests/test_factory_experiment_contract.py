"""Leakage and row-identity gates for the offline experiment."""
import importlib.util
from pathlib import Path
import sys
import pytest

SCRIPTS=Path(__file__).resolve().parents[3]/'scripts/finetune'
sys.path.insert(0,str(SCRIPTS))
from experiment_split_contract import validate_splits
from factory_experiment import transfer_head


def row(text,group):
    return dict(text=text,group_id=group,label='A')


def test_separate_families_pass():
    validate_splits([row('供应商合同查询','a')],[row('分析车间今日产量','b')],[row('调出某批货物的来历','c')])


@pytest.mark.parametrize('text,group',[('供应商合同查询','b'),('供应商合同查询一下','b'),('完全无关表达','a')])
def test_cross_split_leakage_rejected(text,group):
    with pytest.raises(ValueError):
        validate_splits([row('供应商合同查询','a')],[row(text,group)],[row('显示人员打卡','c')])


def test_transferred_classifier_matches_by_label_not_position():
    import torch
    from transformers import BertConfig,BertForSequenceClassification
    model=BertForSequenceClassification(BertConfig(vocab_size=8,hidden_size=8,num_hidden_layers=1,num_attention_heads=2,intermediate_size=16,num_labels=3))
    with torch.no_grad():
        model.classifier.weight.copy_(torch.arange(24).reshape(3,8))
        model.classifier.bias.copy_(torch.tensor([1.,2.,3.]))
    old=model.classifier.weight.detach().clone()
    transfer_head(model,{'REMOVED':0,'KEPT_B':1,'KEPT_A':2},['KEPT_A','NEW','KEPT_B','EXTRA'],torch)
    assert model.num_labels==4
    assert torch.equal(model.classifier.weight[0],old[2])
    assert torch.equal(model.classifier.weight[2],old[1])
    assert model.classifier.bias[0].item()==3
    assert model.config.label2id=={'KEPT_A':0,'NEW':1,'KEPT_B':2,'EXTRA':3}
    assert not torch.equal(model.classifier.weight[1],old[0])


def test_trainer_never_randomly_splits_explicit_test(tmp_path):
    import ast,json,random
    from types import SimpleNamespace
    function=next(n for n in ast.parse((SCRIPTS/'incremental_finetune.py').read_text(encoding='utf-8')).body if isinstance(n,ast.FunctionDef) and n.name=='run_finetuning')
    (tmp_path/'config.json').write_text(json.dumps({'id2label':{'0':'A'}}),encoding='utf-8')
    (tmp_path/'label_mapping.json').write_text(json.dumps({'label_to_id':{'A':0}}),encoding='utf-8')
    train=[row('独立采购进货样本XYZ','train')];val=[row('机器保养台账LMN','val')];test=[row('仓储温度异常QRS','test')]
    captured=[]
    class ReachedArguments(RuntimeError):pass
    def stop(**_):raise ReachedArguments()
    def forbidden(*_,**__):raise AssertionError('Must never randomly split explicit evaluation data')
    def dataset(texts,labels,*_):captured.append((texts,labels));return texts
    ns=dict(__file__=str(SCRIPTS/'incremental_finetune.py'),FinetuneConfig=SimpleNamespace,Path=Path,random=random,
        torch=SimpleNamespace(manual_seed=lambda _:None,cuda=SimpleNamespace(is_available=lambda:False)),logger=SimpleNamespace(info=lambda *_:None),
        load_data=lambda name:train if name=='train' else val,load_jsonl=lambda _:test,merge_and_sample_data=lambda new,old,ratio:new,
        AutoTokenizer=SimpleNamespace(from_pretrained=lambda *_:object()),
        AutoModelForSequenceClassification=SimpleNamespace(from_pretrained=lambda *_,**__:SimpleNamespace(config=SimpleNamespace())),
        IntentDataset=dataset,TrainingArguments=stop,train_test_split=forbidden)
    exec(compile(ast.Module(body=[function],type_ignores=[]),'trainer.explicit.validation','exec'),ns)
    config=SimpleNamespace(seed=42,new_data_path='train',old_data_path=None,replay_ratio=0,validation_data_path='validation',protected_test_path='test',model_path=str(tmp_path),label_mapping_path=None,freeze_layers=0,max_length=64,output_path=str(tmp_path/'out'),epochs=2,batch_size=4,learning_rate=5e-6,warmup_ratio=.1,weight_decay=.01,eval_steps=50,save_steps=100)
    with pytest.raises(ReachedArguments):ns['run_finetuning'](config)
    assert captured==[(['独立采购进货样本XYZ'],[0]),(['机器保养台账LMN'],[0])]


def test_shadow_resume_canary_is_not_charged_twice(tmp_path,monkeypatch):
    import asyncio,json
    import factory_cloud_shadow as shadow
    secrets=tmp_path/'.codex/secrets'
    secrets.mkdir(parents=True)
    (secrets/'cretas-openai-eval.env').write_text('OPENAI_API_KEY=dummy-shadow-fixture',encoding='utf-8')
    monkeypatch.setattr(Path,'home',classmethod(lambda cls:tmp_path))
    rows=[dict(id='test:'+str(i),split='test',text='synthetic '+str(i),choices=[dict(value='A',description='fixture')]) for i in range(2)]
    monkeypatch.setattr(shadow,'plan',lambda _:rows)
    output=tmp_path/'cloud-results.jsonl'
    output.write_text(json.dumps(dict(model=shadow.MODELS[0],id='test:0'))+'\n',encoding='utf-8')
    calls=[]
    class Client:
        def __init__(self,**_):pass
        async def __aenter__(self):return self
        async def __aexit__(self,*_):pass
        async def post(self,url,json):
            from types import SimpleNamespace
            calls.append((json['model'],json['input']))
            body=dict(usage=dict(input_tokens=10,output_tokens=2))
            if url.endswith('decisions'):body['answers']=[dict(choice='A',confidence=.9)]
            else:body['output']=[dict(content=[dict(type='output_text',text='{"label":"A","confidence":0.9}')])]
            return SimpleNamespace(status_code=200,headers={},json=lambda:body)
    monkeypatch.setattr(shadow.httpx,'AsyncClient',Client)
    asyncio.run(shadow.run(tmp_path))
    assert len(calls)==5
    assert len(set(calls))==5
    results=[json.loads(line) for line in output.read_text(encoding='utf-8').splitlines()]
    assert len(results)==6
    assert len({(r['model'],r['id']) for r in results})==6
