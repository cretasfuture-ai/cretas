"""Which failures of the no-generative arm need a generative model, and how often (line E).

Inputs: scored turns of an arm (``decision_eval_score.py --output`` ``*.turns.json``), the raw
turns (candidates, bound params), the rubric (declared parameters per intent), and the merged
reading of the same rows from the mini arm (which parameters each utterance actually carries).

Failure step per turn (first failing step wins):
  recall          gold intent not among the candidates (retrieval / local model, not generative)
  cancel          gold CANCEL not handled as cancel
  missed_clarify  should ask, did not          false_clarify   asked, should not
  choice          gold in candidates, decision model chose another intent
  params          right intent executed, program binders produced other page/size/status
Generative need per turn (independent of failure): the utterance carries a parameter typed
FREE_TEXT (remark / reason / description ...) for which no enum, number, time or entity resolver
exists. Typing is a keyword heuristic over parameter names/descriptions; the rule is printed.
"""
import argparse
import collections
import json
import pathlib
import re

FREE_TEXT = re.compile(r'remark|reason|note|comment|content|desc|feedback|message|text|title|summary|opinion|issue|problem|'
                       r'备注|原因|说明|描述|内容|意见|反馈|留言|问题|情况|要求|处理意见|理由')
TIME = re.compile(r'date|time|start|end|period|month|week|day|hour|日期|时间|月份|周期|时段|起止|小时|天数')
NUMBER = re.compile(r'quantity|qty|amount|count|weight|price|limit|size|page|percent|rate|temperature|threshold|'
                    r'数量|重量|金额|单价|价格|条数|页|比例|温度|上限|下限|阈值')
ENUM = re.compile(r'status|type|category|level|mode|metric|dimension|sort|orderby|group|scope|状态|类型|类别|级别|方式|口径|维度|排序|范围')
ENTITY = re.compile(r'id$|no$|code|name|batch|customer|supplier|product|material|equipment|line|workshop|user|operator|order|'
                    r'编号|单号|名称|批次|客户|供应商|产品|原料|物料|设备|产线|车间|人员|员工|订单|仓库')


def ptype(name, desc=''):
    text = (name or '') + ' ' + (desc or '')
    low = text.lower()
    for kind, rx in (('FREE_TEXT', FREE_TEXT), ('TIME', TIME), ('NUMBER', NUMBER), ('ENUM', ENUM), ('ENTITY', ENTITY)):
        if rx.search(low):
            return kind
    return 'OTHER'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--scored', required=True, help='*.turns.json from decision_eval_score.py')
    p.add_argument('--arm', required=True, help='version key inside the scored file')
    p.add_argument('--raw', required=True, help='turns(.params).jsonl of the same arm')
    p.add_argument('--merged-reading', required=True, help='mini merged arm turns.jsonl (understand.parameters)')
    p.add_argument('--rubric', required=True)
    p.add_argument('--output', type=pathlib.Path)
    a = p.parse_args()
    scored = json.loads(pathlib.Path(a.scored).read_text('utf-8'))[a.arm]
    raw = {(r['run'], r['case_id'], r['turn']): r for r in
           (json.loads(x) for x in pathlib.Path(a.raw).read_text('utf-8').splitlines() if x.strip())}
    reading = {}
    for x in pathlib.Path(a.merged_reading).read_text('utf-8').splitlines():
        r = json.loads(x)
        u = r.get('understand')
        if u and (r['case_id'], r['turn']) not in reading:
            reading[(r['case_id'], r['turn'])] = u.get('parameters') or []
    rubric = json.loads(pathlib.Path(a.rubric).read_text('utf-8'))
    declared_types = {c: {x['param']: ptype(x['param'], x.get('description'))
                          for k in ('must_ask', 'defaults', 'optional') for x in e.get(k, [])}
                      for c, e in rubric['intents'].items()}

    steps = collections.Counter()
    gen = collections.Counter()
    examples = collections.defaultdict(list)
    per_run_turns = collections.Counter()
    for s in scored:
        r = raw[(s['run'], s['case_id'], s['turn'])]
        per_run_turns[s['run']] += 1
        step = None
        if s['expected'] == 'EXECUTE' and r.get('candidates') is not None and s['gold_intent'] not in r['candidates']:
            step = 'recall'
        elif s['cancel_ok'] is False:
            step = 'cancel'
        elif s['missed_clarify']:
            step = 'missed_clarify'
        elif s['false_clarify']:
            step = 'false_clarify'
        elif s['expected'] == 'EXECUTE' and not s['exec_ok']:
            step = 'choice'
        elif s.get('params') is False:
            step = 'params'
        steps[(s['run'], step)] += 1
        if step and len(examples[step]) < 6:
            examples[step].append(dict(input=r['input'], gold=s['gold_intent'], got=s['selected'], outcome=s['outcome'],
                                       bound=(r.get('bound') or {}).get('params')))
        params = reading.get((s['case_id'], s['turn']), [])
        kinds = sorted({ptype(x.get('name'), '') for x in params})
        gold_free = [k for k, t in declared_types.get(s['gold_intent'] or '', {}).items() if t == 'FREE_TEXT']
        needs = 'FREE_TEXT' in kinds
        gen[(s['run'], 'turns')] += 1
        gen[(s['run'], 'needs_generative')] += needs
        gen[(s['run'], 'gold_intent_declares_free_text')] += bool(gold_free)
        if needs and len(examples['needs_generative']) < 12:
            examples['needs_generative'].append(dict(input=r['input'], gold=s['gold_intent'],
                                                     free_params=[x for x in params if ptype(x.get('name'), '') == 'FREE_TEXT']))
    runs = sorted(per_run_turns)
    summary = dict(
        rule='FREE_TEXT/TIME/NUMBER/ENUM/ENTITY keyword typing over parameter name (+ rubric description)',
        failure_steps={run: {str(k[1]): v for k, v in steps.items() if k[0] == run} for run in runs},
        generative_need={run: dict(turns=gen[(run, 'turns')], needs_generative=gen[(run, 'needs_generative')],
                                   gold_intent_declares_free_text=gen[(run, 'gold_intent_declares_free_text')])
                         for run in runs},
        examples=examples)
    text = json.dumps(summary, ensure_ascii=False, indent=1)
    if a.output:
        a.output.write_text(text, 'utf-8')
    print(text[:4000])


if __name__ == '__main__':
    main()
