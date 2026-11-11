"""Offline five-dimension scoring for the multi-turn read-only evaluation.

Pure functions over (locked corpus, complete-read-score.json). No model call, no
network, no database. The online probe and ``score_complete_read.py`` stay the
single source of the per-turn facts (goal/params/real-result checks against the SQL
oracle); this module only splits the one "whole turn / whole dialogue succeeded"
verdict into independent dimensions so a failure can be attributed.

Dimensions per turn (each True / False / None; None = cannot be evaluated from
the recorded evidence and is excluded from that dimension's evaluable count):

  goal         the selected intent / tool is the expected one. Executed turns use
               the executed intent; clarification turns use the recognised intent
               (``recognition.selectedIntent``), so choosing the right target and
               then asking a question is still "goal correct".
  action       dialogue action (new task / change parameter / paginate / clarify).
               The system does not record an action. Gold side is derived from the
               corpus transition (or an explicit ``action`` field); observed side is
               derived from the executed result versus the previous executed result.
               It is None when the previous turn did not execute (no baseline).
  clarify      asked-for-clarification matches the gold expectation. Reported in two
               directions: ``false_clarify`` (should answer, asked) and
               ``missed_clarify`` (should ask, answered). The locked development
               corpus has no gold clarification turn, so missed_clarify has
               denominator 0 and every clarification there is a false clarify.
  params       page/size/status of the executed read are the expected ones.
               None on clarification turns: no resolved parameters were recorded.
  real_result  returned rows equal the independent SQL oracle. None on clarification
               turns (no data returned).
  success      the existing whole-turn verdict (all of the above plus drift/auth/http).
"""
import argparse
import collections
import json
import pathlib

CLARIFY_STATUSES = frozenset({'NEED_CLARIFICATION', 'NEED_MORE_INFO'})
EXECUTED_STATUS = 'SUCCESS'
CHANNELS = ('normal', 'sse')
DIMENSIONS = ('goal', 'action', 'clarify', 'params', 'real_result')

NEW_TASK, PAGINATE, CHANGE_PARAM, CLARIFY, OTHER = 'NEW_TASK', 'PAGINATE', 'CHANGE_PARAM', 'CLARIFY', 'OTHER'

# Intents that are interchangeable targets for the same gold intent.
EQUIVALENT_INTENTS = {'ORDER_LIST': {'ORDER_STATUS'}}


def load_json(path):
    return json.loads(pathlib.Path(path).read_text('utf-8-sig'))


def allowed_intents(gold_turn):
    intent = gold_turn['intent']
    return {intent} | EQUIVALENT_INTENTS.get(intent, set())


def _turn_signature(turn):
    """Identity of the task a gold turn belongs to (intent + basis)."""
    return (turn['intent'], turn.get('basis'))


def gold_action(turns, index):
    """Expected dialogue action of turns[index] (0-based).

    An explicit ``action`` on the gold turn wins. Otherwise: first turn or a change of
    intent/basis = NEW_TASK; same task with page+1 and everything else equal = PAGINATE;
    same task with status or size changed = CHANGE_PARAM; anything else = OTHER.
    """
    turn = turns[index]
    if 'action' in turn:
        return turn['action']
    if index == 0:
        return NEW_TASK
    previous = turns[index - 1]
    if _turn_signature(previous) != _turn_signature(turn):
        return NEW_TASK
    p, q = previous['parameters'], turn['parameters']
    if q.get('page') == p.get('page', 0) + 1 and q.get('status') == p.get('status') and q.get('size') == p.get('size'):
        return PAGINATE
    if q.get('status') != p.get('status') or q.get('size') != p.get('size'):
        return CHANGE_PARAM
    return OTHER


def expects_clarification(turn):
    return bool(turn.get('expect_clarify', False))


def is_executed(row):
    return row.get('status') == EXECUTED_STATUS


def is_clarified(row):
    return row.get('status') in CLARIFY_STATUSES


def selected_intent(row):
    """Executed intent when the read ran, else the intent recognition chose."""
    if is_executed(row):
        return row.get('intent')
    return (row.get('recognition') or {}).get('selectedIntent') or row.get('intent')


def observed_action(row, previous_row):
    """Observed dialogue action, or None when it cannot be derived."""
    if is_clarified(row):
        return CLARIFY
    if not is_executed(row):
        return None
    if previous_row is None:
        return NEW_TASK
    if not is_executed(previous_row):
        return None
    if row.get('intent') != previous_row.get('intent'):
        return NEW_TASK
    now, before = row['observed'], previous_row['observed']
    if now.get('page') == (before.get('page') or 0) + 1 and now.get('status') == before.get('status') and now.get('size') == before.get('size'):
        return PAGINATE
    if now.get('status') != before.get('status') or now.get('size') != before.get('size'):
        return CHANGE_PARAM
    return OTHER


def score_turn(gold_turns, index, row, previous_row):
    """Five-dimension verdict for one (turn, channel) row of complete-read-score.json."""
    gold = gold_turns[index]
    executed, clarified = is_executed(row), is_clarified(row)
    carrier_status = not executed and not clarified      # FAILED / DENIED / missing: not a decision
    expected_clarify = expects_clarification(gold)
    want_action = gold_action(gold_turns, index)
    got_action = observed_action(row, previous_row)

    goal = selected_intent(row) in allowed_intents(gold) if (executed or clarified) else False
    if expected_clarify and clarified:
        goal = None                                      # no target to judge when clarification is the right answer
    action = None if got_action is None else (got_action == want_action)
    if expected_clarify:
        want_action = CLARIFY
        action = None if got_action is None else got_action == CLARIFY
    clarify = None if carrier_status else (clarified == expected_clarify)
    params = bool(row.get('parameters_correct')) if executed else None
    real_result = bool(row.get('real_result_correct')) if executed else None
    return dict(
        case_id=row['case_id'], channel=row['channel'], turn=row['turn'],
        goal=goal, action=action, clarify=clarify, params=params, real_result=real_result,
        success=bool(row.get('task_success')),
        false_clarify=None if carrier_status else (clarified and not expected_clarify),
        missed_clarify=None if carrier_status else (expected_clarify and not clarified),
        expected_clarify=expected_clarify, gold_action=want_action, observed_action=got_action,
        selected_intent=selected_intent(row), expected_intent=gold['intent'],
        status=row.get('status'), category=row.get('category'),
    )


def score_run(corpus, score):
    """Per-turn dimension rows for every (channel, dialogue, turn) in a score file."""
    index = {(r['channel'], r['case_id'], r['turn']): r for r in score['turns']}
    rows = []
    for channel in CHANNELS:
        for plan in corpus['dialogues']:
            for i, _ in enumerate(plan['turns']):
                key = (channel, plan['id'], i + 1)
                if key not in index:
                    raise KeyError('score file has no row for %s' % (key,))
                previous = index.get((channel, plan['id'], i))
                rows.append(score_turn(plan['turns'], i, index[key], previous))
    return rows


def summarize(rows, corpus):
    """Per-channel counts: dimension pass / evaluable / total, clarify directions, sessions."""
    out = {}
    for channel in CHANNELS:
        mine = [r for r in rows if r['channel'] == channel]
        dims = {}
        for d in DIMENSIONS:
            values = [r[d] for r in mine]
            evaluable = [v for v in values if v is not None]
            dims[d] = dict(passed=sum(1 for v in evaluable if v), evaluable=len(evaluable), total=len(values),
                           unevaluable=len(values) - len(evaluable))
        decided = [r for r in mine if r['clarify'] is not None]
        should_not = [r for r in decided if not r['expected_clarify']]
        should = [r for r in decided if r['expected_clarify']]
        sessions = collections.defaultdict(list)
        for r in mine:
            sessions[r['case_id']].append(r['success'])
        out[channel] = dict(
            turns_total=len(mine),
            turns_success=sum(1 for r in mine if r['success']),
            sessions_total=len(corpus['dialogues']),
            sessions_success=sum(1 for v in sessions.values() if all(v)),
            dimensions=dims,
            clarify_directions=dict(
                false_clarify=dict(count=sum(1 for r in should_not if r['false_clarify']), denominator=len(should_not)),
                missed_clarify=dict(count=sum(1 for r in should if r['missed_clarify']), denominator=len(should)),
                false_clarify_by_status=dict(collections.Counter(r['status'] for r in mine if r['false_clarify'])),
            ),
        )
    return out


def main():
    p = argparse.ArgumentParser(description='Recompute five-dimension scores offline from an existing score file.')
    p.add_argument('--corpus', type=pathlib.Path, required=True)
    p.add_argument('--score', type=pathlib.Path, required=True, help='complete-read-score.json')
    p.add_argument('--output', type=pathlib.Path)
    a = p.parse_args()
    corpus, score = load_json(a.corpus), load_json(a.score)
    rows = score_run(corpus, score)
    result = dict(summary=summarize(rows, corpus), turns=rows)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if a.output:
        a.output.write_text(text, encoding='utf-8')
    print(json.dumps(result['summary'], ensure_ascii=False))


if __name__ == '__main__':
    main()
