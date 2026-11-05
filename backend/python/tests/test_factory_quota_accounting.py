"""Shared groups and UTC boundaries must not be mistaken for per-model limits."""
import asyncio
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import pytest

sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'scripts/finetune'))
from factory_quota_accounting import SMALL,LARGE,add_usage,legacy_rows,quota_group,utc_day


def test_utc_reset_is_beijing_eight_and_yesterday_is_not_charged_again():
    before=dt.datetime(2026,11,4,23,59,59,tzinfo=dt.timezone.utc)
    after=before+dt.timedelta(seconds=1)
    assert before.astimezone(dt.timezone(dt.timedelta(hours=8))).hour==7
    assert after.astimezone(dt.timezone(dt.timedelta(hours=8))).hour==8
    totals={SMALL:0,LARGE:0}
    for timestamp in [before.timestamp(),after.timestamp()]:
        add_usage(totals,dict(model=SMALL,response=dict(created_at=timestamp),usage=dict(input_tokens=11,output_tokens=2)),'2026-11-05')
    assert totals=={SMALL:13,LARGE:0}


def test_luna_and_gpt54_share_large_group_mini_has_an_independent_group():
    totals={SMALL:0,LARGE:0}
    for model,count in [(LARGE,30),('gpt-6-luna',70),(SMALL,200)]:
        add_usage(totals,dict(model=model,day='2026-11-05',usage=dict(input_tokens=count,output_tokens=1)),'2026-11-05')
    assert totals=={SMALL:201,LARGE:102}
    with pytest.raises(ValueError,match='unverified'):quota_group('gpt-6.1-terra')


def test_legacy_day_requires_bound_evidence_not_directory_name_or_current_day(tmp_path):
    path=tmp_path/'yesterday.jsonl'
    row=dict(model='gpt-6-luna',usage=dict(input_tokens=7))
    path.write_text(json.dumps(row)+'\n',encoding='utf-8')
    with pytest.raises(ValueError,match='UTC day unavailable'):legacy_rows(path)
    evidence=dict(source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),default_utc_day='2026-11-04',evidence_ref='archived-batch-clock-proof.json')
    path.with_suffix('.utc-days.json').write_text(json.dumps(evidence),encoding='utf-8')
    assert legacy_rows(path)[0]['day']=='2026-11-04'
    path.write_text(json.dumps(row)+'\n\n',encoding='utf-8')
    with pytest.raises(ValueError,match='no longer matches'):legacy_rows(path)


def test_conflicting_utc_day_evidence_fails_closed():
    with pytest.raises(ValueError,match='conflict'):
        utc_day(dict(day='2026-11-04',response_created_at=dt.datetime(2026,11,5,tzinfo=dt.timezone.utc).timestamp()))


@pytest.mark.parametrize('model,group',[('gpt-5.2-2025-12-11',LARGE),('gpt-4o',LARGE),('gpt-6.1-sol',LARGE),('gpt-4o-mini-2024-07-18',SMALL),('gpt-5.4-nano',SMALL)])
def test_model_aliases_and_snapshots_use_the_documented_shared_group(model,group):
    assert quota_group(model)==group


def test_closing_client_drains_inflight_responses_instead_of_making_them_unknown(tmp_path,monkeypatch):
    import factory_accuracy_api as api
    monkeypatch.setattr(Path,'home',classmethod(lambda cls:tmp_path))
    secret=tmp_path/'.codex/secrets';secret.mkdir(parents=True)
    (secret/'cretas-openai-eval.env').write_text('OPENAI_API_KEY=dummy-quota-fixture',encoding='utf-8')
    async def scenario():
        started=asyncio.Event();release=asyncio.Event();closed=[]
        class Client:
            def __init__(self,**_):pass
            async def post(self,*_,**__):
                started.set();await release.wait()
                body=dict(usage=dict(input_tokens=4,output_tokens=2),output=[dict(content=[dict(type='output_text',text='{"answer":1}')])])
                return SimpleNamespace(status_code=200,headers={},json=lambda:body)
            async def aclose(self):closed.append(True)
        monkeypatch.setattr(api.httpx,'AsyncClient',Client)
        root=tmp_path/'run';root.mkdir();client=api.SyntheticClient(root)
        pending=asyncio.create_task(client.ask('slow',SMALL,'classify','synthetic',{'type':'object'},20))
        await started.wait()
        closing=asyncio.create_task(client.close());await asyncio.sleep(0)
        assert not closed
        release.set();await closing
        assert (await pending)=={'answer':1}
        assert closed==[True] and client.ledger[0]['state']=='completed'
        assert client.done['slow']['status']==200 and not client.active
    asyncio.run(scenario())
