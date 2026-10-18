"""Write-acceptance identity and source gates execute before any business I/O."""
import importlib.util
import json
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location('p1_quality_trace_acceptance', ROOT / 'p1_quality_trace.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


@pytest.mark.parametrize('source', [
    {'factoryId': 'VISION-ENTRY-B', 'entities': {}},
    {'factoryId': 'VISION-ENTRY-A', 'entities': {'verified_batch_a': {'id': 7}, 'verified_batch_b': {'id': 999}}},
])
def test_wrong_source_stops_before_login_or_business_writes(tmp_path, monkeypatch, source):
    state = tmp_path / 'state.json'
    state.write_text(json.dumps(source), encoding='utf-8')
    monkeypatch.setattr(sys, 'argv', ['qc', 'prepare', '--credentials', str(tmp_path / 'missing.json'),
        '--p0-state', str(state), '--out', str(tmp_path / 'out'), '--queue-db', str(tmp_path / 'queue.sqlite3'),
        '--allow-test-writes', 'NON_PRODUCTION_ONLY'])
    called = []
    monkeypatch.setattr(MODULE, 'BusinessFlow', lambda *args: called.append(args))
    with pytest.raises(ValueError):
        MODULE.main()
    assert called == []
    assert not (tmp_path / 'out').exists()


def test_explicit_write_acknowledgement_is_required(tmp_path, monkeypatch):
    monkeypatch.setattr(sys, 'argv', ['qc', 'prepare', '--credentials', 'missing.json',
        '--p0-state', 'missing.json', '--out', str(tmp_path), '--queue-db', 'missing.sqlite3'])
    with pytest.raises(SystemExit) as stopped:
        MODULE.main()
    assert stopped.value.code == 2
