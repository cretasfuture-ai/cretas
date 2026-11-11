"""Offline tests for multi-run aggregation. Run: python -m pytest tests/semantic-read -q"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import aggregate_runs as ar  # noqa: E402
import scoring_dimensions as sd  # noqa: E402

DIALOGUES = [dict(id='d1', turns=[
    dict(intent='ORDER_LIST', parameters=dict(page=1, size=3, status='COMPLETED'), input='a'),
    dict(intent='ORDER_LIST', parameters=dict(page=2, size=3, status='COMPLETED'), input='b'),
])]


def turn_row(channel, turn, ok=True):
    if ok:
        return dict(case_id='d1', channel=channel, turn=turn, status='SUCCESS', intent='ORDER_LIST',
                    recognition=dict(selectedIntent='ORDER_LIST'),
                    observed=dict(status='COMPLETED', page=turn, size=3), parameters_correct=True,
                    real_result_correct=True, task_success=True, category='PASS')
    return dict(case_id='d1', channel=channel, turn=turn, status='NEED_CLARIFICATION', intent='ORDER_LIST',
                recognition=dict(selectedIntent='ORDER_LIST'), observed=dict(status=None, page=None, size=None),
                parameters_correct=False, real_result_correct=False, task_success=False, category='CLARIFICATION')


def make_run(root, name, base_head, failing=(), hashes=None):
    d = root / name
    d.mkdir()
    (d / 'development-corpus-locked.json').write_text(json.dumps(dict(dialogues=DIALOGUES)), encoding='utf-8')
    turns = [turn_row(ch, t, ok=(ch, t) not in failing) for ch in sd.CHANNELS for t in (1, 2)]
    (d / 'complete-read-score.json').write_text(json.dumps(dict(turns=turns)), encoding='utf-8')
    (d / 'run-manifest.json').write_text(json.dumps(dict(base_head=base_head, source_hashes=hashes or {'A.java': 'h1'})), encoding='utf-8')
    return d


def test_stats_mean_min_max_and_inconsistency(tmp_path):
    r1 = ar.load_run(make_run(tmp_path, 'r1', 'aaaaaaaaaaaa'))
    r2 = ar.load_run(make_run(tmp_path, 'r2', 'aaaaaaaaaaaa', failing={('normal', 2)}))
    r3 = ar.load_run(make_run(tmp_path, 'r3', 'aaaaaaaaaaaa', failing={('normal', 2), ('sse', 1)}))
    result = ar.aggregate([r1, r2, r3])
    sessions = result['channels']['normal']['sessions_success']
    assert [p['passed'] for p in sessions['per_run']] == [1, 0, 0]
    assert sessions['passed']['min'] == 0 and sessions['passed']['max'] == 1
    assert abs(sessions['passed']['mean'] - round(1 / 3, 4)) < 1e-9
    fc = result['channels']['normal']['false_clarify']
    assert [p['count'] for p in fc['per_run']] == [0, 1, 1]
    assert result['channels']['normal']['false_clarify']['count']['max'] == 1
    keys = {(i['channel'], i['turn']) for i in result['inconsistent']}
    # sse turn 2 is listed too: when turn 1 did not execute, its dialogue action has no baseline (None) in r3 only
    assert keys == {('normal', 2), ('sse', 1), ('sse', 2)}
    assert result['mixed_versions'] is False


def test_identical_runs_have_no_inconsistent_turns(tmp_path):
    runs = [ar.load_run(make_run(tmp_path, n, 'bbbbbbbbbbbb', failing={('sse', 2)})) for n in ('x', 'y')]
    result = ar.aggregate(runs)
    assert result['inconsistent'] == []
    assert result['channels']['sse']['turns_success']['rate']['min'] == result['channels']['sse']['turns_success']['rate']['max']


def test_different_source_versions_are_flagged_not_pooled_silently(tmp_path):
    a = ar.load_run(make_run(tmp_path, 'a', 'aaaaaaaaaaaa'))
    b = ar.load_run(make_run(tmp_path, 'b', 'bbbbbbbbbbbb'))
    c = ar.load_run(make_run(tmp_path, 'c', 'bbbbbbbbbbbb', hashes={'A.java': 'changed'}))
    result = ar.aggregate([a, b, c])
    assert result['mixed_versions'] is True
    assert len(result['versions']) == 3                      # same base_head but different source hashes = different version
    assert 'WARNING' in ar.render_markdown(result)


def test_missing_manifest_is_unknown_version_not_a_guess(tmp_path):
    d = make_run(tmp_path, 'm', 'aaaaaaaaaaaa')
    (d / 'run-manifest.json').unlink()
    run = ar.load_run(d)
    assert run['identity']['base_head'] is None and run['version'].startswith('UNKNOWN')
