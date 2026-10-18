import importlib.util
import json
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location('p2_quality_recall_acceptance', ROOT / 'p2_quality_recall.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


@pytest.mark.parametrize('source', [
    {'factoryId': 'VISION-ENTRY-B', 'entities': {}},
    {'factoryId': 'VISION-ENTRY-A', 'entities': {'verified_batch_a': {'id': 999}, 'verified_batch_b': {'id': 8}}},
])
def test_source_identity_is_checked_before_business_login(tmp_path, monkeypatch, source):
    state = tmp_path / 'state.json'
    state.write_text(json.dumps(source), encoding='utf-8')
    monkeypatch.setattr(sys, 'argv', ['recall', 'boxed', '--credentials', 'absent.json', '--p0-state', str(state),
        '--out', str(tmp_path / 'out'), '--queue-db', 'absent.sqlite3', '--allow-test-writes', 'NON_PRODUCTION_ONLY'])
    called = []
    monkeypatch.setattr(MODULE, 'BusinessFlow', lambda *args: called.append(args))
    with pytest.raises(ValueError):
        MODULE.main()
    assert called == []
    assert not (tmp_path / 'out').exists()


def test_negative_control_cannot_target_another_business_path():
    with pytest.raises(ValueError):
        MODULE.reject(None, 'POST', '/production-plans/delete', {})


STARTUP_LOG = '''2026-10-16 10:00:04.049 INFO DailyIndicatorRecomputeScheduler: Spring ready — triggering initial recompute
2026-10-16 10:00:04.343 DEBUG FACTORY_LU_DAILY_OUTPUT factory=VISION-ENTRY-B period=[2026-10-01,2026-10-16] → 40.000000
2026-10-16 10:00:04.365 INFO DailyIndicatorRecomputeScheduler [startup] done: 7/7 success
'''


def added_version():
    return {'id': 'new-version', 'factory_id': 'VISION-ENTRY-B', 'deleted_at': None,
        'compute_source': 'REAL_BUSINESS:FACTORY_LU_DAILY_OUTPUT', 'value': '40.0000',
        'computed_at': '2026-10-16 10:00:04.343808', 'period_start': '2026-10-01', 'period_end': '2026-10-16'}


def test_startup_addition_requires_matching_log_without_rewriting_baseline():
    previous = [{'id': 'old-version', 'value': '100.0000'}]
    proof = MODULE.b_version_proof(previous, [*previous, added_version()], STARTUP_LOG)
    assert not proof['originalFullEquality']
    assert proof['baselineRowsUnchanged']
    assert proof['startupAdditionsExplained']
    assert len(proof['addedRows']) == 1


@pytest.mark.parametrize('field,value', [
    ('factory_id', 'VISION-ENTRY-A'), ('value', '41.0000'),
    ('computed_at', '2026-10-16 11:00:04.343808'),
    ('compute_source', 'REAL_BUSINESS:FACTORY_LU_YIELD_RATE'),
    ('period_end', '2026-10-15'),
])
def test_unproven_added_version_is_rejected(field, value):
    row = {**added_version(), field: value}
    assert not MODULE.b_version_proof([], [row], STARTUP_LOG)['startupAdditionsExplained']


def test_missing_startup_log_cannot_explain_added_version():
    assert not MODULE.b_version_proof([], [added_version()], '')['startupAdditionsExplained']


def test_changed_baseline_row_is_never_excused_by_startup():
    before = [{'id': 'old-version', 'value': '100.0000'}]
    after = [{'id': 'old-version', 'value': '90.0000'}, added_version()]
    proof = MODULE.b_version_proof(before, after, STARTUP_LOG)
    assert proof['startupAdditionsExplained']
    assert not proof['baselineRowsUnchanged']
