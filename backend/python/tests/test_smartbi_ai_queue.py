import json
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from types import SimpleNamespace

import pytest

from smartbi.services import alert_ai_coordination as queue, alert_local_ai as ai


@pytest.fixture(autouse=True)
def isolated(monkeypatch,tmp_path):
    monkeypatch.setenv('SMARTBI_ALERT_AI_STATE_FILE',str(tmp_path/'queue.sqlite3'))
    monkeypatch.setattr(queue,'POLL_SECONDS',0.01)


def test_fifo_runs_one_at_a_time_across_connections_and_keeps_usage():
    config={'transport':'ssh','host':'synthetic','model':'synthetic-model'}
    # Schema fsync is not part of the concurrency assertion, especially while
    # the acceptance build is using the same Windows disk.
    queue._connection().close()
    release=Event();started=Event();order=[]
    def infer(payload,config):
        order.append(payload['id'])
        if payload['id']==1:started.set();assert release.wait(30)
        return ai.InferenceResult({'reviews':[]},{'inputTokens':10,'outputTokens':3,'totalTokens':13,'durationSeconds':0.2})
    with ThreadPoolExecutor(max_workers=3) as pool:
        one=pool.submit(queue.run,infer,{'id':1},config,'T','U');assert started.wait(10)
        two=pool.submit(queue.run,infer,{'id':2},config,'T','U')
        deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            with queue._connection() as db:
                if db.execute('SELECT count(*) FROM inference_queue').fetchone()[0]==1:break
            time.sleep(0.01)
        else:raise AssertionError('SECOND_REQUEST_NOT_ENQUEUED')
        three=pool.submit(queue.run,infer,{'id':3},config,'FOREIGN','USER')
        assert not two.done() and not three.done()
        release.set()
        results=[job.result(timeout=15) for job in (one,two,three)]
    assert order==[1,2,3]
    assert len({r.usage['requestId'] for r in results})==3
    with queue._connection() as db:
        assert db.execute('SELECT count(*) FROM leases').fetchone()[0]==0
        assert db.execute('SELECT count(*) FROM inference_queue').fetchone()[0]==0
        assert db.execute('SELECT count(*),sum(total_tokens) FROM inference_usage').fetchone()==(3,39)


def test_wait_timeout_returns_to_durable_caller_without_starting_inference(monkeypatch):
    monkeypatch.setattr(queue,'WAIT_SECONDS',0.04)
    config={'transport':'ssh','host':'synthetic'}
    resource=queue._key(json.dumps({k:config.get(k) for k in ('transport','host','base')},sort_keys=True))
    with queue._connection() as db:db.execute('INSERT INTO leases VALUES (?,?,?)',(resource,'other-owner',time.time()+300))
    with pytest.raises(queue.ReviewBusy):queue.run(lambda *a:pytest.fail('must not run'),{},config,'T','U')
    with queue._connection() as db:
        assert db.execute('SELECT count(*) FROM inference_queue').fetchone()[0]==0
        assert db.execute('SELECT count(*) FROM inference_usage').fetchone()[0]==0
        assert db.execute('SELECT owner FROM leases').fetchone()[0]=='other-owner'


def test_failed_provider_keeps_unknown_usage_and_releases_for_next_request():
    def fail(*args):raise RuntimeError('synthetic transport failure')
    config={'transport':'ssh','host':'synthetic'}
    with pytest.raises(RuntimeError):queue.run(fail,{'private':'never persist this payload'},config,'tenant-secret','user-secret')
    queue.run(lambda *a:None,{},config,'T','U')
    with queue._connection() as db:
        row=db.execute("SELECT input_tokens,output_tokens,total_tokens FROM inference_usage WHERE outcome='failed'").fetchone()
        assert row==(None,None,None)
        assert 'never persist' not in '\n'.join(db.iterdump())
        assert 'tenant-secret' not in '\n'.join(db.iterdump())


def test_invalid_json_consumption_has_a_call_id_for_durable_retry_accounting():
    def fail(*args):raise ai.LocalAIOutputError({'inputTokens':10,'outputTokens':3,'totalTokens':13})
    with pytest.raises(ai.LocalAIOutputError) as error:queue.run(fail,{}, {'transport':'ssh','host':'synthetic'},'T','U')
    assert error.value.usage['requestId']
    with queue._connection() as db:
        assert db.execute('SELECT request_id,total_tokens FROM inference_usage').fetchone()==(error.value.usage['requestId'],13)


def test_ollama_actual_counts_are_distinct_from_model_content_and_missing_not_zero(monkeypatch):
    response={'model':'qwen3.8:27b','prompt_eval_count':123,'eval_count':45,'total_duration':2_000_000_000,
              'message':{'content':'{"reviews":[]}'}}
    monkeypatch.setattr(ai.subprocess,'run',lambda *a,**k:SimpleNamespace(returncode=0,stdout=json.dumps(response)))
    value=ai.infer({'model':'qwen3.8:27b'},{'transport':'ssh','host':'canada-mini'})
    assert dict(value)=={'reviews':[]}
    assert value.usage=={'inputTokens':123,'outputTokens':45,'totalTokens':168,'durationSeconds':2.0,'source':'ollama_response','estimated':False}
    assert ai.provider_usage({})['totalTokens'] is None
    assert ai.provider_usage({'prompt_eval_count':True,'eval_count':-1})['inputTokens'] is None
    response['message']['content']='invalid json'
    with pytest.raises(ai.LocalAIOutputError) as error:ai.infer({'model':'qwen3.8:27b'},{'transport':'ssh','host':'canada-mini'})
    assert error.value.usage['totalTokens']==168
