"""Transport fidelity checks; fake outputs establish no model accuracy."""
import asyncio
import importlib.util
import pathlib
import types

from ai.matcher.decision import DecisionMatcher, SPECIAL_OPTIONS, build_choice_messages, NONE_KEY, CLARIFY_KEY

spec=importlib.util.spec_from_file_location('read_decision_wire',pathlib.Path(__file__).resolve().parents[3]/'tests/semantic-read/openai_decision_transport.py')
wire=importlib.util.module_from_spec(spec);spec.loader.exec_module(wire)
ROWS=[dict(intent_code=str(i),intent_name=f'功能{i}',description='实际查询') for i in range(25)]


def test_native_choice_uses_exact_core_prompt_order_limits_and_special_exits():
    query='继续上一任务';history=[dict(role='user',content='历史请求')]
    plan=wire.choice_plan(query,'operator',ROWS,history,True,20)
    by={r['intent_code']:r for r in ROWS+SPECIAL_OPTIONS}
    codes=[{'__NONE__':'NONE','__CLARIFY__':'CLARIFY'}.get(c,c) for c in plan['mapping'].values()]
    assert len(codes)==20 and codes[-2:]==['NONE','CLARIFY']
    assert set(codes[:-2])=={str(i) for i in range(18)}
    messages=build_choice_messages(query,'operator',[by[c] for c in codes],history)
    assert plan['payload']['input']==messages[1]['content']
    assert plan['payload']['questions'][0]['instructions']==messages[0]['content']
    assert plan==wire.choice_plan(query,'operator',ROWS,history,True,20)
    assert '不沿用旧取消或旧业务目标' not in str(plan['payload'])


def test_raw_native_probabilities_and_none_clarify_are_preserved_with_one_call():
    calls=[]
    def ask(payload,endpoint,slot):
        calls.append((payload,endpoint,slot))
        return dict(answers=[dict(type='choice',name='intent',probabilities=[
            dict(value='A',probability=.55),dict(value='B',probability=.3),dict(value='C',probability=.15)])])
    transport=types.SimpleNamespace(LiveDecision=DecisionMatcher,ask=ask,log=lambda *args:None)
    dec=wire.decision_type(transport)(timeout_s=60)
    out=asyncio.run(dec.choose('查询','operator',ROWS[:1],special_options=True))
    assert out=={'0':.55,NONE_KEY:.3,CLARIFY_KEY:.15} and len(calls)==1
    assert calls[0][1:]==('decisions','choice')
    assert asyncio.run(dec.choose('查询','operator',ROWS[:1]))=={'0':1.}
    assert asyncio.run(dec.choose('查询','operator',[])) is None
    assert len(calls)==1
