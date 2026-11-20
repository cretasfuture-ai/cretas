"""Pins the offline policy mirror to the real orchestrator, and the scorer's directions.

Thresholds are chosen with ``decision_policy.outcome`` (no model calls). If that mirror
drifts from ``Orchestrator._match_merged`` a chosen threshold would not be the one the
live code applies, so every combination below goes through both and must agree.
"""
import asyncio
import itertools
import pathlib
import sys

# Lives under backend/python/tests so the Python Gate selects it; the modules it pins are the
# evaluation tools in <repo>/tests/semantic-read.
SEMANTIC_READ = pathlib.Path(__file__).resolve().parents[3] / 'tests' / 'semantic-read'
sys.path.insert(0, str(SEMANTIC_READ))

from ai import registry  # noqa: E402
from ai.config import AIConfig  # noqa: E402
from ai.matcher.decision import CLARIFY_KEY, NONE_KEY, RequestPurpose, Understanding  # noqa: E402
from ai.orchestrator import Orchestrator  # noqa: E402

import decision_eval_score as score  # noqa: E402
import decision_policy as policy  # noqa: E402

ROWS = [{'id': c, 'intent_code': c, 'intent_name': c, 'description': c, 'keywords': None, 'is_active': True}
        for c in ('A_QUERY', 'B_QUERY', 'C_QUERY')]


class Empty:
    async def match(self, *a, **k):
        return []


class Fake:
    def __init__(self, u, probs):
        self.u, self.probs = u, probs

    async def understand(self, *a, **k):
        return self.u

    async def choose(self, *a, **k):
        return dict(self.probs)


def live_outcome(u, probs, ct, cb, monkeypatch):
    monkeypatch.setattr(registry, 'get_registry', lambda: registry.RegistrySnapshot(available=False))
    cfg = AIConfig(decision_business_types='FACTORY', decision_special_options=True, decision_merged=True,
                   decision_clarify_threshold=ct, decision_choice_below=cb)
    orch = Orchestrator(Empty(), Empty(), Empty(), decision_matcher=Fake(u, probs), candidate_retriever=Empty(), config=cfg)
    res = asyncio.run(orch.match('q', 'F006', 'FACTORY', 'u', 'r', ROWS, [], 0.7))
    t = res.timingMs or {}
    if res.requiresConfirmation:
        return 'CLARIFY'
    if res.bestMatch is not None:
        return 'EXECUTE:' + res.bestMatch.intentCode
    return 'NONE' if t.get('decisionNone') else 'FALLBACK'


def mirror_outcome(u, probs, ct, cb):
    d = dict(purpose=u.purpose.value, act=u.act, candidates=u.candidates, alternatives=u.alternatives,
             target=u.target, confidence=u.confidence, clarify_probability=u.clarify_probability)
    kind, intent, _ = policy.outcome(d, probs, ct, cb)
    return kind + (':' + intent if kind == 'EXECUTE' else '')


def test_policy_mirror_matches_orchestrator(monkeypatch):
    dists = [
        {'A_QUERY': 0.9, 'B_QUERY': 0.05, NONE_KEY: 0.03, CLARIFY_KEY: 0.02},
        {'A_QUERY': 0.45, 'B_QUERY': 0.35, NONE_KEY: 0.1, CLARIFY_KEY: 0.1},
        {'B_QUERY': 0.85, 'A_QUERY': 0.1, NONE_KEY: 0.03, CLARIFY_KEY: 0.02},
        {'A_QUERY': 0.1, NONE_KEY: 0.7, CLARIFY_KEY: 0.2},
        {'A_QUERY': 0.2, NONE_KEY: 0.2, CLARIFY_KEY: 0.6},
    ]
    seen = set()
    for purpose, act, target, conf, pc, alts, probs in itertools.product(
            (RequestPurpose.CURRENT, RequestPurpose.UNRESOLVED), ('NEW_TASK', 'CANCEL'), ('A_QUERY', None),
            (0.95, 0.6), (0.1, 0.9), ([], ['B_QUERY']), dists):
        u = Understanding(purpose=purpose, act=act, candidates=['A_QUERY', 'B_QUERY'], target=target, confidence=conf,
                          alternatives=alts, clarify_probability=pc, clarify_reason='', parameters=[])
        live = live_outcome(u, probs, 0.5, 0.8, monkeypatch)
        assert live == mirror_outcome(u, probs, 0.5, 0.8), (u, probs)
        seen.add(live.split(':')[0])
    assert seen >= {'EXECUTE', 'CLARIFY', 'NONE'}  # the grid really reaches every outcome


def _row(part, case, turn, outcome, intent=None, top=(), gold_intent='A_QUERY', gold_act=None, params=None, **kw):
    return dict(run='r1', part=part, case_id=case, turn=turn, outcome=outcome, intent=intent, top=list(top),
                gold_intent=gold_intent, gold_act=gold_act, gold_params=params or {}, calls=[], n_calls=1,
                wall_ms=1.0, **kw)


def test_scorer_keeps_clarify_directions_apart_and_counts_cancel():
    rows = [
        _row('C', 'x1', 1, 'CLARIFY', top=['A_QUERY'], gold_intent=None, gold_act='NEED_CLARIFY'),   # right ask
        _row('C', 'x2', 1, 'EXECUTE', intent='A_QUERY', gold_intent=None, gold_act='NEED_CLARIFY'),  # missed
        _row('C', 'x3', 1, 'CLARIFY', top=['A_QUERY'], gold_act='NEW_TASK'),                          # false ask
        _row('C', 'x4', 1, 'NONE', gold_intent=None, gold_act='CANCEL'),                              # right cancel
    ]
    s = score.summarize(score.score_rows(rows, {}))['C']
    assert s['missed_clarify'] == dict(ok=1, n=2, pct=50.0)
    assert s['false_clarify'] == dict(ok=1, n=2, pct=50.0)
    assert s['cancel_ok']['ok'] == 1 and s['goal'] == dict(ok=1, n=1, pct=100.0)  # top button on a clarify counts


def test_scorer_does_not_credit_context_continue_for_a_page_turn():
    rows = [
        _row('M', 'd', 1, 'EXECUTE', intent='A_QUERY', params={'page': 1, 'size': 3}),
        _row('M', 'd', 2, 'EXECUTE', intent='CONTEXT_CONTINUE', params={'page': 2, 'size': 3}),
        _row('M', 'd', 3, 'EXECUTE', intent='PAGINATION_PREVIOUS', params={'page': 1, 'size': 3}),
    ]
    got = score.score_rows(rows, {})
    assert [r['goal'] for r in got] == [True, False, False]
    rows[1]['intent'] = 'PAGINATION_NEXT'
    got = score.score_rows(rows, {})
    assert got[1]['goal'] is True and got[1]['gold_act'] == 'PAGE'


def test_param_reading_maps_status_words_and_chinese_numbers():
    vocab = {'FINANCE_APPROVED': ['财务已批准'], 'COMPLETED': ['已完成']}
    got = score.read_params([{'name': '状态', 'value': '财务已批准'}, {'name': '页码', 'value': '第二页'},
                             {'name': '每页条数', 'value': '7条'}], vocab)
    assert got == {'status': 'FINANCE_APPROVED', 'page': 2, 'size': 7}
