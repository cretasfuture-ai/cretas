"""Fail-closed semantic equivalence, protected text, and API receipt contracts."""
import asyncio
from difflib import SequenceMatcher
import json
from pathlib import Path
import random
import sys
from types import SimpleNamespace
import pytest

SCRIPTS=Path(__file__).resolve().parents[3]/'scripts/finetune'
sys.path.insert(0,str(SCRIPTS))
from factory_accuracy_support import OverlapIndex,semantics
from flywheel_reviewed import normalized


def test_character_bag_index_preserves_full_near_duplicate_guard():
    rng=random.Random(41);alphabet='原料批次查询采购出货温度员工考勤统计ab１２'
    held=[{'text':''.join(rng.choices(alphabet,k=rng.randrange(8,35)))} for _ in range(80)]
    index=OverlapIndex(held)
    queries=[r['text'] for r in held]+[r['text'][:-1]+'新' for r in held]+[' ＡＢＣ １２３ ','abc123','全新客户汇总报告']
    for text in queries:
        expected=any(SequenceMatcher(None,normalized(text),normalized(r['text'])).ratio()>=.85 for r in held)
        assert index.overlaps({'text':text})==expected
    assert OverlapIndex([{'text':'abc123','group_id':'same'}]).overlaps({'text':'不同表达','group_id':'same'})


@pytest.mark.parametrize('tools,valid',[(['read'],True),(['read','write'],False),(['other'],False)])
def test_semantic_equivalence_requires_exact_single_tool(tmp_path,tools,valid):
    catalog={'canonical_intents':[{'intent_code':'P','route_type':'TOOL','tool_name':'read'},{'intent_code':'A','route_type':'SKILL','skill_tools':tools}]}
    policy={'equivalent_routes':[{'preferred':'P','aliases':['A'],'tool':'read'}],'label_notes':{}}
    path=tmp_path/'policy.json';path.write_text(json.dumps(policy),encoding='utf-8')
    if valid:assert semantics(catalog,path)[1]=={'A':'P'}
    else:
        with pytest.raises(ValueError):semantics(catalog,path)


def fake_client(tmp_path,monkeypatch,timeout=False):
    import factory_accuracy_api as api
    secrets=tmp_path/'.codex/secrets';secrets.mkdir(parents=True)
    (secrets/'cretas-openai-eval.env').write_text('OPENAI_API_KEY=dummy-accuracy-fixture',encoding='utf-8')
    monkeypatch.setattr(Path,'home',classmethod(lambda cls:tmp_path))
    requests=[]
    class Client:
        def __init__(self,**_):pass
        async def aclose(self):pass
        async def post(self,url,json):
            requests.append(json)
            if timeout:raise api.httpx.ReadTimeout('fixture')
            body={'usage':{'input_tokens':10,'output_tokens':2,'total_tokens':12},'output':[{'content':[{'type':'output_text','text':'{"texts":["合成需求"]}'}]}]}
            return SimpleNamespace(status_code=200,headers={},json=lambda:body)
    monkeypatch.setattr(api.httpx,'AsyncClient',Client)
    root=tmp_path/'experiment';root.mkdir()
    return api,root,requests


def test_api_resume_binds_payload_and_never_reissues_completed_request(tmp_path,monkeypatch):
    api,root,requests=fake_client(tmp_path,monkeypatch)
    async def scenario():
        client=api.SyntheticClient(root)
        result=await client.ask('fixed',api.MINI,'classify','synthetic',{'type':'object'},20)
        assert result['texts']==['合成需求']
        assert await client.ask('fixed',api.MINI,'classify','synthetic',{'type':'object'},20)==result
        with pytest.raises(ValueError,match='changed'):
            await client.ask('fixed',api.MINI,'changed','synthetic',{'type':'object'},20)
        assert client.used[api.MINI]==12
        assert len(requests)==1
        assert all(r['state']=='completed' for r in client.ledger)
        await client.close()
    asyncio.run(scenario())


def test_api_unknown_timeout_preserves_pending_and_forbids_resume(tmp_path,monkeypatch):
    api,root,requests=fake_client(tmp_path,monkeypatch,timeout=True)
    async def scenario():
        client=api.SyntheticClient(root)
        with pytest.raises(RuntimeError):await client.ask('unknown',api.MINI,'classify','synthetic',{'type':'object'},20)
        assert client.ledger[0]['state']=='pending'
        assert len(requests)==1
        await client.close()
        with pytest.raises(ValueError,match='Unresolved'):api.SyntheticClient(root)
    asyncio.run(scenario())


def test_api_daily_budget_blocks_before_network(tmp_path,monkeypatch):
    api,root,requests=fake_client(tmp_path,monkeypatch)
    async def scenario():
        client=api.SyntheticClient(root);client.used[api.MINI]=api.CAPS[api.MINI]-1
        with pytest.raises(ValueError,match='ceiling'):await client.ask('excess',api.MINI,'classify','synthetic',{'type':'object'},20)
        assert not requests and not client.ledger
        await client.close()
    asyncio.run(scenario())


@pytest.mark.parametrize('text,ambiguous',[('库存还有多少',True),('上次那张排行呢',True),('今天异常情况怎么样',True),('上班打卡',False),('确认这张采购单收货',False),('这批原料来自哪个供应商',False),('本周产品销售排名',False),('库存周转率是多少',False)])
def test_clarification_rules_check_objects_not_sentence_length(text,ambiguous):
    from factory_accuracy_eval import needs_clarification
    assert needs_clarification(text)==ambiguous


def test_calibration_and_training_cannot_resume_after_blind_open(tmp_path):
    from factory_accuracy_train import train
    from factory_accuracy_eval import calibrate
    (tmp_path/'blind-opened.json').write_text('{}',encoding='utf-8')
    with pytest.raises(ValueError,match='test already opened'):train(tmp_path)
    with pytest.raises(ValueError,match='locked'):asyncio.run(calibrate(tmp_path))


def test_changed_routing_code_blocks_blind_inference_before_model_load(tmp_path,monkeypatch):
    import factory_accuracy_eval as evaluation
    (tmp_path/'selection.json').write_text(json.dumps({'model_path':str(tmp_path/'unused-model')}),encoding='utf-8')
    (tmp_path/'routing-policy.json').write_text(json.dumps({'routing_code_sha256':'changed'}),encoding='utf-8')
    monkeypatch.setattr(evaluation,'verify',lambda _: {})
    monkeypatch.setattr(evaluation,'predict',lambda *_:pytest.fail('Must block before model access'))
    with pytest.raises(ValueError,match='implementation changed'):asyncio.run(evaluation.blind(tmp_path))


def test_mini_payload_does_not_depend_on_expected_label(tmp_path,monkeypatch):
    import factory_accuracy_eval as evaluation
    content=[]
    class Client:
        def __init__(self,*_,**__):pass
        async def close(self):pass
        async def ask(self,id,model,instructions,payload,schema,max_output):
            content.append(payload);return {'label':'A','confidence':.9}
    monkeypatch.setattr(evaluation,'SyntheticClient',Client)
    pool=SimpleNamespace(choices=lambda text,logits:[{'value':'A','description':'fixture'},{'value':'__CLARIFY__','description':'fixture'}])
    for gold in ['SECRET_EXPECTED_A','SECRET_EXPECTED_B']:
        row={'record_id':'case-1','label':gold,'text':'synthetic user request','logits':[2.,1.]}
        asyncio.run(evaluation.mini_many(tmp_path,'fixture',[row],pool))
    assert content[0]==content[1]
    assert 'SECRET_EXPECTED' not in content[0]
    assert set(json.loads(content[0]))=={'text','candidates'}


def test_inventory_stops_at_inline_description_and_preserves_quoted_braces(tmp_path,monkeypatch):
    import factory_accuracy_support as support
    directory=tmp_path/'backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/demo'
    directory.mkdir(parents=True)
    (directory/'DemoTool.java').write_text('public String getToolName(){return "read";}\npublic String getDescription(){return "查询 {完整} 单据";}\npublic String unrelated(){return "do not include schema";}\npublic AccessMode getAccessMode(){return AccessMode.READ;}',encoding='utf-8')
    monkeypatch.setattr(support,'WORK',tmp_path)
    rows=support.source_inventory({'canonical_intents':[{'intent_code':'P','tool_name':'read'}]})
    assert rows[0]['tool_contract']['description']=='查询 {完整} 单据'
    assert rows[0]['tool_contract']['declared_access_mode']=='READ'


def test_lexical_ambiguity_signal_routes_to_mini_instead_of_forcing_refusal():
    from factory_accuracy_eval import decision
    row={'text':'库存还有多少','logits':[10.,0.]}
    mapping={'A':0,'B':1};policy={'temperature':1.,'confidence':.8,'margin':.1,'mini_confidence':.65}
    assert decision(row,mapping,policy)==('__CLARIFY__','abstain')
    assert decision(row,mapping,policy,{'prediction':'B','confidence':.95})==('B','mini')


def test_selective_review_has_no_gold_and_is_optional_at_runtime(tmp_path,monkeypatch):
    import factory_accuracy_critic as critic
    from factory_accuracy_eval import decision
    payloads=[]
    class Client:
        def __init__(self,*_,**__):pass
        async def close(self):pass
        async def ask(self,id,model,instructions,payload,schema,max_output,effort):
            assert effort=='medium';payloads.append(payload)
            return {'label':'A','confidence':.95}
    monkeypatch.setattr(critic,'SyntheticClient',Client)
    rows=[{'record_id':'fixture','text':'synthetic text','label':'SECRET_GOLD','prediction':'A','logits':[1.,0.]}]
    cloud={'fixture':{'prediction':'B','confidence':.9,'candidates':['A','B','__CLARIFY__']}}
    pool=SimpleNamespace(defs={k:{'name':k,'meaning':'source contract'} for k in ['A','B']})
    output=asyncio.run(critic.review_disagreements(tmp_path,'fixture',rows,cloud,pool))
    assert 'SECRET_GOLD' not in payloads[0]
    policy={'temperature':1.,'confidence':.99,'margin':.1,'mini_confidence':.8,'critic_confidence':.8}
    assert decision(rows[0],{'A':0,'B':1},dict(policy,critic_enabled=False),output['fixture'])==('B','mini')
    assert decision(rows[0],{'A':0,'B':1},dict(policy,critic_enabled=True),output['fixture'])==('A','mini-review')
