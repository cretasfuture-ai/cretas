import json
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from blacklake import authority_guard, live_api, live_monitor as live


@pytest.fixture
def cfg(tmp_path):
    v={'schema':live.SCHEMA,'tenant_id':'BLACKLAKE_POC_TEST','source_factory_id':'factory','baselineRunId':'baseline',
       'db':str(tmp_path/'monitor.sqlite3'),'intervalSeconds':60,'days':7}
    live.initialize(v)
    return v


def configured(cfg):
    return {'tenant_id':cfg['tenant_id'],'source_factory_id':'factory','run_id':'baseline'}


def set_nightly(cfg,state):
    c=live.connect(cfg['db'])
    with c:c.execute('UPDATE monitor SET nightly=?',(state if isinstance(state,str) or state is None else json.dumps(state),))
    c.close()


# 1. exceptions are no longer swallowed

async def test_nightly_failure_is_written_to_stderr_with_code_and_type_but_keeps_error_record(cfg,monkeypatch,capsys):
    from blacklake import period_runtime
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(live_api,'analysis_tick',AsyncMock())
    monkeypatch.setattr(period_runtime,'tick',AsyncMock(side_effect=RuntimeError('PERIOD_COST_AUTHORITY_UNAVAILABLE')))
    monkeypatch.setattr(live_api.live,'now',lambda:'2026-11-05T15:56:00+00:00')
    await live_api.analysis_step(configured(cfg))
    err=capsys.readouterr().err
    assert 'code=PERIOD_COST_AUTHORITY_UNAVAILABLE' in err and 'type=RuntimeError' in err and 'nightly_tick' in err
    st=live.status(cfg)['nightly']
    assert st['lastAttemptAt'] and st['error'] and not st.get('lastSuccessAt')


async def test_failure_log_never_contains_free_text_message(cfg,monkeypatch,capsys):
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setattr(live_api,'analysis_tick',AsyncMock(side_effect=ValueError('password=hunter2 SELECT * FROM t')))
    monkeypatch.setattr(live_api,'nightly_tick',AsyncMock())
    await live_api.analysis_step(configured(cfg))
    err=capsys.readouterr().err
    assert 'code=UNCODED' in err and 'type=ValueError' in err and 'hunter2' not in err and 'SELECT' not in err


async def test_bookkeeping_failure_is_reported_too(cfg,monkeypatch,capsys):
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic')
    monkeypatch.setattr(live,'config',MagicMock(side_effect=OSError('x')))
    monkeypatch.setattr(live_api,'analysis_tick',AsyncMock(side_effect=ValueError('boom')))
    monkeypatch.setattr(live_api,'nightly_tick',AsyncMock())
    await live_api.analysis_step(configured(cfg))
    assert capsys.readouterr().err.count('failed code=')==2


# 3. three-state alarm

NOW='2026-11-19T12:00:00+00:00'


@pytest.mark.parametrize('raw,state,status',[
    ('{"lastSuccessAt":"2026-11-19T00:00:00+00:00"}',0,'ok'),
    ('{"lastSuccessAt":"2026-11-17T23:59:59+00:00"}',1,'stale'),
    ('{"lastSuccessAt":"2026-11-08T15:59:59Z"}',1,'stale'),
    (None,2,'unreadable'),('',2,'unreadable'),('{}',2,'unreadable'),('{bad',2,'unreadable'),
    ('{"lastSuccessAt":"not-a-time"}',2,'unreadable'),('[]',2,'unreadable')])
def test_nightly_health_three_states(raw,state,status):
    h=live.nightly_health(raw,NOW)
    assert (h['state'],h['status'])==(state,status)


def test_stale_boundary_is_36_hours():
    assert live.nightly_health('{"lastSuccessAt":"2026-11-18T00:00:00+00:00"}',NOW)['state']==0
    assert live.nightly_health('{"lastSuccessAt":"2026-11-17T23:59:00+00:00"}',NOW)['state']==1


def test_status_endpoint_exposes_health_and_fresh_campaign_is_not_reported_healthy(cfg):
    assert live.status(cfg)['nightlyHealth']['state']==2
    set_nightly(cfg,{'lastSuccessAt':live.now()})
    assert live.status(cfg)['nightlyHealth']['state']==0


def test_cli_exit_codes_and_forced_unreadable(cfg,tmp_path,monkeypatch,capsys):
    conf=tmp_path/'live.json'
    conf.write_text(json.dumps({**cfg,**{k:str(tmp_path/(k+'.json')) for k in ('observation','bom','supplement')}}),encoding='utf8')
    def run():
        monkeypatch.setattr(sys,'argv',['live_monitor','--config',str(conf),'--nightly-health'])
        rc=live.main();return rc,json.loads(capsys.readouterr().out)
    set_nightly(cfg,{'lastSuccessAt':live.now()});assert run()[0]==0
    set_nightly(cfg,{'lastSuccessAt':'2026-11-08T15:59:59Z'});assert run()[0]==1
    set_nightly(cfg,{'lastAttemptAt':live.now()});assert run()[0]==2
    # constructed rc=2: database cannot be opened. Must not be created as a side effect.
    db=tmp_path/'monitor.sqlite3';db.unlink()
    rc,out=run()
    assert rc==2 and out['status']=='unreadable' and 'NIGHTLY_DB_UNREADABLE' in out['reason'] and not db.exists()


async def test_stale_alert_goes_to_stderr_once_per_day(cfg,monkeypatch,capsys):
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setitem(live_api._heal_state,'last',object());monkeypatch.setitem(live_api._heal_state,'alerted',None)
    ensure=MagicMock()
    at=live.instant('2026-11-19T04:00:00Z')
    for _ in range(2):await live_api.maintenance_step(configured(cfg),at,ensure,lambda _:live.nightly_health('{"lastSuccessAt":"2026-11-08T15:59:59Z"}',NOW))
    assert capsys.readouterr().err.count('ALERT nightly health state=1')==1
    ensure.assert_not_called()


# 2. self-heal sequence

class Runner:
    def __init__(self,cluster_up=False):
        self.calls=[];self.cluster_up=cluster_up
    def __call__(self,cmd,**kw):
        self.calls.append(cmd)
        rc=0 if (cmd[-1]!='status' or self.cluster_up) else 3
        return SimpleNamespace(returncode=rc)


def test_listening_port_does_not_touch_wsl():
    run=Runner()
    assert authority_guard.ensure_authority(probe=lambda:True,run=run,log=lambda m:None)=='listening'
    assert run.calls==[]


def test_missing_port_starts_cluster_then_authority_in_order_and_waits():
    run=Runner();ticks=iter([False,False,True])
    r=authority_guard.ensure_authority(probe=lambda:next(ticks),run=run,sleep=lambda s:None,log=lambda m:None)
    assert r=='started'
    cmds=[c[4:] for c in run.calls]
    assert cmds[0]==['pg_ctlcluster','17','testenv','status'] and cmds[1]==['pg_ctlcluster','17','testenv','start']
    assert cmds[2][:2]==['python3','-I'] and cmds[2][2].startswith('/mnt/') and cmds[2][2].endswith('/test_authority_wsl.py') and cmds[2][3]=='--start'
    assert all(c[:4]==['wsl','-u','root','--'] for c in run.calls) and len(run.calls)==3


def test_running_cluster_is_not_restarted():
    run=Runner(cluster_up=True);ticks=iter([False,True])
    assert authority_guard.ensure_authority(probe=lambda:next(ticks),run=run,sleep=lambda s:None,log=lambda m:None)=='started'
    assert not any(c[-1]=='start' and 'pg_ctlcluster' in c for c in run.calls)


def test_port_never_appears_is_failed_after_deadline_and_never_raises():
    t=[0.0]
    def clock():return t[0]
    def sleep(s):t[0]+=s
    r=authority_guard.ensure_authority(probe=lambda:False,run=Runner(),sleep=sleep,clock=clock,wait_seconds=120,log=lambda m:None)
    assert r=='failed' and 120<=t[0]<=130
    assert authority_guard.ensure_authority(probe=lambda:False,run=MagicMock(side_effect=OSError('no wsl')),log=lambda m:None)=='failed'


def test_wsl_path_translation():
    assert authority_guard.wsl_path('C:\\Users\\a b\\x.py')=='/mnt/c/Users/a b/x.py' if sys.platform=='win32' else True


async def test_maintenance_heals_only_at_startup_and_in_lead_window_with_rate_limit(cfg,monkeypatch):
    monkeypatch.setenv('BLACKLAKE_LIVE_CONFIG','synthetic');monkeypatch.setattr(live,'config',lambda _:cfg)
    monkeypatch.setitem(live_api._heal_state,'last',None);monkeypatch.setitem(live_api._heal_state,'alerted',None)
    ok=lambda _:{'state':0}
    ensure=MagicMock(return_value='listening')
    day=lambda hh,mm:live.instant('2026-11-19T%02d:%02d:00Z'%(hh-8,mm))  # Beijing = UTC+8
    await live_api.maintenance_step(configured(cfg),day(10,0),ensure,ok);assert ensure.call_count==1   # startup
    await live_api.maintenance_step(configured(cfg),day(10,5),ensure,ok);assert ensure.call_count==1   # idle hours
    await live_api.maintenance_step(configured(cfg),day(23,30),ensure,ok);assert ensure.call_count==2  # lead window
    await live_api.maintenance_step(configured(cfg),day(23,35),ensure,ok);assert ensure.call_count==2  # rate limited
    await live_api.maintenance_step(configured(cfg),day(23,41),ensure,ok);assert ensure.call_count==3
    await live_api.maintenance_step(configured(cfg),day(23,56),ensure,ok);assert ensure.call_count==3  # window itself
