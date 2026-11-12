from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import pytest

import smartbi.services.period_reports as period_reports
from smartbi.services.period_reports import LeaseError, ReportStore


SCOPE = {'tenant_id': 'tenant-a', 'source_factory_id': 'F001', 'connector': 'demo'}
OTHER_SCOPE = {'tenant_id': 'tenant-b', 'source_factory_id': 'F001', 'connector': 'demo'}
PERIOD = {
    'startDate': '2026-11-01', 'endDate': '2026-11-01', 'kind': 'day', 'basis': 'completed',
    'timezone': 'Asia/Shanghai', 'startAt': '2026-10-31T16:00:00Z', 'endAt': '2026-11-01T16:00:00Z',
}


def submit(store, scope=SCOPE, fingerprint='fp-1', force=False):
    return store.submit(scope, PERIOD, fingerprint, {'private': 'facts'}, force=force)


def test_new_definition_never_executes_unfinished_old_definition_and_keeps_old_ready(tmp_path,monkeypatch):
    store=ReportStore(tmp_path/'definitions.sqlite3')
    current=period_reports.DEFINITION_VERSION
    monkeypatch.setattr(period_reports,'DEFINITION_VERSION','old-definition')
    ready=submit(store)
    lease=store.claim(SCOPE,ready['id'])
    store.complete(SCOPE,ready['id'],lease['leaseToken'],{'summary':'historical result'})
    pending=submit(store,force=True)
    monkeypatch.setattr(period_reports,'DEFINITION_VERSION',current)
    assert store.claim(SCOPE) is None
    old=store.get(SCOPE,pending['id'])
    assert old['status']=='failed' and old['result'] is None
    assert 'SUPERSEDED' in old['error']
    assert store.get(SCOPE,ready['id'])['result']=={'summary':'historical result'}
    new=submit(store)
    assert new['definitionVersion']==current
    assert store.claim(SCOPE)['id']==new['id']


def test_ai_budget_defer_persists_rules_without_burning_attempts_or_mutating_ready(tmp_path,monkeypatch):
    now=datetime(2026,11,4,tzinfo=timezone.utc)
    monkeypatch.setattr(period_reports,'_utc_now',lambda:now)
    path=tmp_path/'ai-budget.sqlite3';store=ReportStore(path)
    job=submit(store);claimed=store.claim(SCOPE)
    result={'summary':'saved rule analysis','ai':{'status':'rate_limited'}}
    queued=store.defer(SCOPE,job['id'],claimed['leaseToken'],result,3600,True)
    assert queued['status']=='queued' and queued['result']==result and queued['nextAttemptAt']
    restarted=ReportStore(path)
    assert restarted.claim(SCOPE) is None
    now += timedelta(seconds=3601)
    resumed=restarted.claim(SCOPE)
    assert resumed['attempts']==1 and resumed['facts']=={'private':'facts'}
    ready=restarted.complete(SCOPE,job['id'],resumed['leaseToken'],{'ai':{'status':'complete'}})
    assert ready['status']=='ready'
    with pytest.raises(LeaseError):restarted.defer(SCOPE,job['id'],resumed['leaseToken'],result)


def test_submit_is_idempotent_and_force_preserves_history(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    first = submit(store)
    cached = submit(store)
    forced = submit(store, force=True)
    assert first['status'] == 'queued'
    assert first['definitionVersion']
    assert cached['id'] == first['id'] and cached['fromCache'] is True
    assert forced['id'] != first['id'] and forced['revision'] == 2
    assert [row['revision'] for row in store.list(SCOPE)] == [2, 1]
    assert store.get(OTHER_SCOPE, first['id']) is None


def test_revisions_increment_across_fingerprints_but_same_fingerprint_is_idempotent(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    first = submit(store, fingerprint='fp-a')
    second = submit(store, fingerprint='fp-b')
    cached = submit(store, fingerprint='fp-a')
    assert first['revision'] == 1
    assert second['revision'] == 2
    assert cached['id'] == first['id'] and cached['fromCache'] is True


def test_windows_returns_latest_revision_per_window_with_stable_scope_paging(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    first = submit(store, fingerprint='fp-a')
    submit(store, fingerprint='fp-b')
    second_period = {**PERIOD, 'startDate': '2026-11-02', 'endDate': '2026-11-02'}
    store.submit(SCOPE, second_period, 'fp-c', {'private': 'facts'})
    store.submit(OTHER_SCOPE, PERIOD, 'other', {'private': 'facts'})
    windows = store.windows(SCOPE)
    assert [(item['period']['startDate'], item['fingerprint'], item['revision']) for item in windows] == [
        ('2026-11-01', 'fp-b', 2), ('2026-11-02', 'fp-c', 1)
    ]
    assert [item['period']['startDate'] for item in store.windows(SCOPE, offset=1, limit=1)] == ['2026-11-02']
    assert store.windows(OTHER_SCOPE)[0]['fingerprint'] == 'other'
    assert first['revision'] == 1


def test_concurrent_claim_has_one_winner_and_expired_old_worker_cannot_overwrite(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    job = submit(store)
    with ThreadPoolExecutor(max_workers=2) as pool:
        claimed = list(pool.map(lambda _: store.claim(SCOPE, job['id'], lease_seconds=1), range(2)))
    winners = [item for item in claimed if item]
    assert len(winners) == 1
    first = winners[0]
    second = store.claim(SCOPE, job['id'], lease_seconds=1)
    assert second is None

    import time
    time.sleep(1.1)
    takeover = store.claim(SCOPE, job['id'], lease_seconds=600)
    assert takeover and takeover['leaseToken'] != first['leaseToken']
    with pytest.raises(LeaseError):
        store.complete(SCOPE, job['id'], first['leaseToken'], {'stale': True})
    ready = store.complete(SCOPE, job['id'], takeover['leaseToken'], {'ok': True})
    assert ready['status'] == 'ready' and ready['result'] == {'ok': True}
    with pytest.raises(LeaseError):
        store.fail(SCOPE, job['id'], takeover['leaseToken'], 'late')


def test_fail_requeues_until_three_attempts_and_sanitizes_error(tmp_path, monkeypatch):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    job = submit(store)
    clock = [datetime(2026, 11, 4, 15, 55, tzinfo=timezone.utc)]
    monkeypatch.setattr(period_reports, '_utc_now', lambda: clock[0])
    for attempt in range(2):
        claimed = store.claim(SCOPE, job['id'])
        assert claimed
        failed = store.fail(SCOPE, job['id'], claimed['leaseToken'], {'code': 'BAD_INPUT', 'message': 'secret token should not be repr()'})
        assert failed['status'] == 'queued'
        assert 'secret token' in (failed['error'] or '')
        assert failed['nextAttemptAt'] == f"2026-11-04T15:{56 if attempt == 0 else 58:02d}:00Z"
        assert store.claim(SCOPE, job['id']) is None
        clock[0] += timedelta(seconds=60 if attempt == 0 else 120)
    claimed = store.claim(SCOPE, job['id'])
    failed = store.fail(SCOPE, job['id'], claimed['leaseToken'], RuntimeError('password=do-not-repr'))
    assert failed['status'] == 'failed'
    assert 'password=' not in (failed['error'] or '')


def test_expired_third_attempt_is_finalized_before_claiming_other_work(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    job = submit(store)
    for _ in range(3):
        claimed = store.claim(SCOPE, job['id'], lease_seconds=1)
        assert claimed
        import time
        time.sleep(1.1)
    assert store.claim(SCOPE, job['id']) is None
    finalized = store.get(SCOPE, job['id'])
    assert finalized['status'] == 'failed'
    assert 'LEASE_EXPIRED_MAX_ATTEMPTS' in (finalized['error'] or '')
    with pytest.raises(LeaseError):
        store.complete(SCOPE, job['id'], claimed['leaseToken'], {'late': True})


def test_schedule_seen_is_scope_isolated(tmp_path):
    store = ReportStore(tmp_path / 'reports.sqlite3')
    job = submit(store)
    assert store.schedule_seen(SCOPE, 'day:2026-11-01:preview') is False
    assert store.mark_schedule(SCOPE, 'day:2026-11-01:preview', job['id']) is True
    assert store.schedule_seen(SCOPE, 'day:2026-11-01:preview') is True
    assert store.schedule_seen(OTHER_SCOPE, 'day:2026-11-01:preview') is False


def test_schedule_checkpoint_is_scope_isolated_monotonic_and_survives_restart(tmp_path):
    path = tmp_path / 'reports.sqlite3'
    store = ReportStore(path)
    first = datetime(2026, 11, 4, 15, 55, tzinfo=timezone.utc)
    earlier = datetime(2026, 11, 4, 15, 54, tzinfo=timezone.utc)
    later = datetime(2026, 11, 5, 15, 55, tzinfo=timezone.utc)
    assert store.schedule_checkpoint(SCOPE) is None
    assert store.schedule_checkpoint(SCOPE, first) == '2026-11-04T15:55:00Z'
    assert store.schedule_checkpoint(SCOPE, earlier) == '2026-11-04T15:55:00Z'
    assert store.schedule_checkpoint(OTHER_SCOPE) is None
    assert store.schedule_checkpoint(SCOPE, later) == '2026-11-05T15:55:00Z'
    restarted = ReportStore(path)
    assert restarted.schedule_checkpoint(SCOPE) == '2026-11-05T15:55:00Z'
    with pytest.raises(ValueError):
        restarted.schedule_checkpoint(SCOPE, datetime(2026, 11, 6, 0, 0))


def test_explicit_resume_only_releases_owned_queued_ai_backpressure(tmp_path):
    db=ReportStore(tmp_path/'resume.sqlite3')
    waiting=submit(db);lease=db.claim(SCOPE,waiting['id'])
    draft={'ai':{'status':'rate_limited'},'summary':'keep rules'}
    saved=db.defer(SCOPE,waiting['id'],lease['leaseToken'],draft,3600)
    other=submit(db,OTHER_SCOPE);other_lease=db.claim(OTHER_SCOPE,other['id'])
    db.defer(OTHER_SCOPE,other['id'],other_lease['leaseToken'],draft,3600)
    ready=submit(db,force=True);ready_lease=db.claim(SCOPE,ready['id'])
    original=db.complete(SCOPE,ready['id'],ready_lease['leaseToken'],draft)
    assert db.resume_ai_queue(SCOPE)==[waiting['id']]
    resumed=db.get(SCOPE,waiting['id'])
    assert resumed['nextAttemptAt'] is None and resumed['result']==saved['result']
    assert db.get(OTHER_SCOPE,other['id'])['nextAttemptAt']
    assert db.get(SCOPE,ready['id'])==original
