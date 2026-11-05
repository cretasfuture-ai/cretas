"""One-off OpenAI teacher/Decisions experiment. Never a production model route.

Only the versioned synthetic fixtures below may leave the machine. One USD
additional conservative budget shares the existing ten USD experiment ledger.
"""
import argparse
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING
import hashlib
import json
from pathlib import Path
import uuid

import httpx

from .teacher_eval import Ledger, TeacherError, LIMIT_MICRO_USD, dump, root

LAB = Path('D:/CretasSmartBILab/local-analyst-distillation')
# Upper input rates cover the published long-retention cache write rate; all
# input is charged at that upper rate even when the server reports cache hits.
UPPER_RATES = {'gpt-6-astra': (Decimal('25'), Decimal('50')),
               'gpt-6.1-sol': (Decimal('5'), Decimal('10')),
               'decisions/gpt-6-luna': (Decimal('0.25'), Decimal('0'))}
STANDARD_RATES = {'gpt-6-astra': (Decimal('10'), Decimal('50')),
                  'gpt-6.1-sol': (Decimal('2'), Decimal('10')),
                  'decisions/gpt-6-luna': (Decimal('0.10'), Decimal('0'))}
CASES = [
    {'id': 'mix', 'synthetic': True, 'question': '解释同产品成本差异并排序下一步核对。',
     'facts': {'sameFinishedCodeAndUnit': True, 'specificationVerified': False,
               'higherOrder': {'good': 100, 'labor': 500, 'inputs': [{'code': 'M2', 'name': '半成品切配', 'kg': 120, 'bookPrice': 50}]},
               'lowerOrder': {'good': 100, 'labor': 0, 'inputs': [{'code': 'M1', 'name': '缓化料', 'kg': 100, 'bookPrice': 30}, {'code': 'M2', 'name': '半成品缓化', 'kg': 40, 'bookPrice': 50}]}}},
    {'id': 'normal', 'synthetic': True, 'question': '是否存在可确认的异常？',
     'facts': {'sameCodeUnitSpecificationStage': True, 'allInputsAndPayrollVerified': True,
               'unitCosts': [20.01, 20, 19.99, 20], 'quantity': [100, 90, 110, 100],
               'reconciliationDifference': 0}},
    {'id': 'coverage', 'synthetic': True, 'question': '这些工单能代表全厂平均成本吗？',
     'facts': {'totalOrders': 60, 'eligibleOrders': 12, 'eligibleGoodKg': 1000, 'allGoodKg': 10000,
               'blockedConcentratedInLargeBatches': True, 'blockedCostUnknown': True}},
    {'id': 'inventory', 'synthetic': True, 'question': '能否认定期间库存已经对账？',
     'facts': {'periodMovementOrders': 41, 'balanceRows': 55, 'balanceIsLaterSnapshot': True,
               'openingAndClosingBalanceAvailable': False, 'movementsUseBusinessDate': True}},
    {'id': 'certainty', 'synthetic': True, 'question': '审查这段解释并给出安全改写。',
     'facts': {'zeroValueReasonsVerified': False},
     'draft': {'why': '材料成本严重低估，无法反映真实消耗。', 'limitation': '不能确认实际成本严重低估。'}},
    {'id': 'grain', 'synthetic': True, 'question': '能否认定漏报四次或十张工单有问题？',
     'facts': {'tasks': 37, 'outputs': 33, 'taskOutputRelation': 'many-to-many',
               'alerts': 10, 'unlinkedMaterialRows': 1, 'countingScopesDifferent': True}},
]
LINEAGE_CASES = [
    {'id':'line_vs_batch','synthetic':True,'question':'哪些关联已成立，还缺什么、找谁补、补后能判断什么？',
     'facts':{'issuePointsToWorkOrderMaterialRow':True,'parentIdCodeAndProductMatched':True,
              'materialFeedProcessMatchesTask':True,'upstreamReceiptOrBatchAllocation':None}},
    {'id':'ambiguous_producer','synthetic':True,'question':'能选哪张上游工单？列出补证据动作。',
     'facts':{'sameProductUnitProducers':2,'datesAndQuantitiesNear':True,'actualBatchReference':None,'allocationQuantity':None}},
    {'id':'carried_value','synthetic':True,'question':'原料成本和半成品领料金额如何避免重复？',
     'facts':{'upstreamMaterials':80,'upstreamLabor':20,'downstreamMaterialCarryingValue':100,
              'downstreamLabor':30,'carryingValueConfirmedIncludesUpstream':True,'otherManufacturingExpensesKnown':False}},
    {'id':'split_and_joint','synthetic':True,'question':'拆批、混批和一料多成品还缺哪些成本依据？',
     'facts':{'receiptQty':10,'claimedAllocations':[6,6],'jointProductAllocationRuleApproved':False,
              'outputsHaveDifferentUnits':True}},
    {'id':'order_action','synthetic':True,'question':'用缺什么、找谁、补齐能判断什么写逐单动作。',
     'facts':{'finishedGoodQuantity':None,'materialCostIsSourceZero':True,'laborZeroReasonUnknown':True,
              'sourceQueriesSuccessful':True}},
    {'id':'normal_and_coverage','synthetic':True,'question':'怎样解释稳定对照及可算子集，避免误导？',
     'facts':{'stableSameProductUnitCosts':[20,20.01,19.99,20],'actualBatchChainUnverified':True,
              'eligibleOrders':6,'allOrders':30,'eligibleGoodKg':480,'allGoodKg':4800,
              'missingConcentratedInLargeOrders':True}},
]
SYSTEM = '''你是一次性离线补课教师。只分析明确标为合成的案例，不调用工具。
把事实、假设、数据缺口分开。模型结论不覆盖程序计算，账面估值不是采购价，金额不默认人民币。
重点补齐：逐物料投入组合、零人工基准、正常对照、覆盖选择偏差、计数粒度、库存时点与自相矛盾。
每题最多三个要点，白话解释，不超过一百五十字；说明先查什么及哪些结论不能下。
最后提炼可迁移提示规则，不保留合成个例数字；不提供内部推理过程。'''
ITEM = {'type': 'object', 'additionalProperties': False,
        'properties': {'id': {'type': 'string'}, 'judgement': {'type': 'string', 'enum': ['normal', 'needs_review', 'insufficient']},
                       'explanation': {'type': 'string'}, 'checks': {'type': 'array', 'items': {'type': 'string'}},
                       'rule': {'type': 'string'}},
        'required': ['id', 'judgement', 'explanation', 'checks', 'rule']}
SCHEMA = {'type': 'object', 'additionalProperties': False,
          'properties': {'cases': {'type': 'array', 'items': ITEM}}, 'required': ['cases']}


def config():
    values = {}
    path = Path.home() / '.codex/secrets/cretas-openai-eval.env'
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            name, value = line.split('=', 1)
            values[name.strip()] = value.strip().strip('"').strip("'")
    if not values.get('OPENAI_API_KEY'):
        raise TeacherError('OPENAI_KEY_NOT_CONFIGURED')
    return values


class SupplementBudget(Ledger):
    def reserve_openai(self, job, model, payload):
        if model not in UPPER_RATES:
            raise TeacherError('UNPRICED_SUPPLEMENT_MODEL')
        maximum = payload.get('max_output_tokens', 0)
        if not 0 <= maximum <= 6000 or (model.startswith('decisions/') and maximum):
            raise TeacherError('SUPPLEMENT_OUTPUT_LIMIT')
        upper = len(json.dumps(payload, ensure_ascii=False).encode()) + 4096
        a, b = UPPER_RATES[model]
        reserve = int((upper*a + maximum*b).to_integral_value(rounding=ROUND_CEILING))
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM calls WHERE job=?', (job,)).fetchone():
                raise TeacherError('SUPPLEMENT_ALREADY_RESERVED')
            total = db.execute('SELECT COALESCE(SUM(COALESCE(actual,reserved)),0) FROM calls').fetchone()[0]
            own = db.execute("SELECT COALESCE(SUM(COALESCE(actual,reserved)),0) FROM calls WHERE model LIKE 'openai/%'").fetchone()[0]
            if total+reserve > LIMIT_MICRO_USD or own+reserve > 1_000_000:
                raise TeacherError('SUPPLEMENT_BUDGET_STOP_BEFORE_SEND')
            call = uuid.uuid4().hex
            db.execute('INSERT INTO calls VALUES (?,?,?,?,NULL,?,NULL,?)',
                       (call, job, 'openai/'+model, reserve, 'reserved', datetime.now(timezone.utc).isoformat()))
        return call

    def settle_openai(self, call, usage):
        if any(type(usage.get(k)) is not int or usage[k]<0 for k in ('input_tokens', 'output_tokens')):
            raise TeacherError('SUPPLEMENT_USAGE_UNKNOWN_RESERVATION_RETAINED')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            model, reserved, status = db.execute('SELECT model,reserved,status FROM calls WHERE id=?', (call,)).fetchone()
            if status != 'reserved':
                raise TeacherError('SUPPLEMENT_NOT_RESERVED')
            a, b = UPPER_RATES[model.removeprefix('openai/')]
            charged = int((usage['input_tokens']*a + usage['output_tokens']*b).to_integral_value(rounding=ROUND_CEILING))
            exceeded = charged > reserved
            db.execute('UPDATE calls SET actual=?,status=?,usage_json=? WHERE id=?',
                       (charged, 'bound_exceeded' if exceeded else 'complete', json.dumps(usage), call))
        if exceeded:
            raise TeacherError('SUPPLEMENT_BOUND_EXCEEDED_STOP')
        return charged


class Supplement:
    def __init__(self, directory=None, ledger=None):
        self.directory = directory or LAB/'openai-supplement-20261104'
        self.ledger = ledger or SupplementBudget(root()/'experiment-20261104/budget.sqlite3')
        c = config()
        headers = {'Authorization': 'Bearer '+c['OPENAI_API_KEY']}
        for key, header in [('OPENAI_PROJECT_ID', 'OpenAI-Project'), ('OPENAI_ORGANIZATION_ID', 'OpenAI-Organization')]:
            if c.get(key): headers[header] = c[key]
        self.client = httpx.Client(headers=headers, timeout=300, trust_env=False, follow_redirects=False)

    def call(self, job, model, payload, endpoint):
        if endpoint not in ('responses', 'decisions'):
            raise TeacherError('SUPPLEMENT_ENDPOINT_NOT_ALLOWED')
        if payload.get('model') != model.removeprefix('decisions/'):
            raise TeacherError('SUPPLEMENT_MODEL_BILLING_MISMATCH')
        if endpoint == 'responses':
            effort = payload.get('reasoning', {}).get('effort')
            if effort not in ('medium', 'high') or not any(payload==teacher_payload(model,effort,lesson) for lesson in ('general','lineage')):
                raise TeacherError('SUPPLEMENT_SYNTHETIC_EGRESS_ONLY')
        elif not any(payload == decision_payload(case_id) for case_id in [None]+[c['id'] for c in REVIEW_CASES]):
            raise TeacherError('SUPPLEMENT_SYNTHETIC_EGRESS_ONLY')
        digest = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        path = self.directory/(job+'-'+digest+'.json')
        if path.exists():
            saved = json.loads(path.read_text(encoding='utf8'))
            if saved.get('conservativeDebitedMicroUsd') is None:
                raise TeacherError('UNCERTAIN_PRIOR_CALL_REQUIRES_REVIEW')
            return saved
        if not self.ledger.can_resume():
            raise TeacherError('UNCERTAIN_PRIOR_CALL_REQUIRES_REVIEW')
        call = self.ledger.reserve_openai('supplement/'+job+':'+digest, model, payload)
        try:
            response = self.client.post('https://api.openai.com/v1/'+endpoint, json=payload)
        except httpx.HTTPError:
            raise TeacherError('SUPPLEMENT_NETWORK_UNKNOWN_NO_AUTOMATIC_RETRY') from None
        if response.status_code != 200:
            raise TeacherError('SUPPLEMENT_HTTP_'+str(response.status_code)+'_RESERVATION_RETAINED')
        body = response.json()
        result = {'model': model, 'job': job, 'requestHash': digest, 'body': body,
                  'conservativeDebitedMicroUsd': None, 'syntheticOnly': True}
        # Keep even incomplete/unknown-usage responses for local review. A
        # pending ledger reservation prevents this artifact becoming a retry.
        dump(path, result)
        charged = self.ledger.settle_openai(call, body.get('usage', {}))
        result['conservativeDebitedMicroUsd'] = charged
        dump(path, result)
        print(json.dumps({'job': job, 'status': body.get('status', 'returned'),
                          'conservativeTotalUsd': self.ledger.total()/1e6}), flush=True)
        return result


def teacher_payload(model, effort, lesson='general'):
    if lesson not in ('general','lineage'):raise TeacherError('UNKNOWN_SYNTHETIC_LESSON')
    return {'model': model, 'store': False, 'max_output_tokens': 6000,
            'reasoning': {'effort': effort},
            'text': {'verbosity': 'medium', 'format': {'type': 'json_schema', 'name': 'analyst_supplement', 'strict': True, 'schema': SCHEMA}},
            'instructions': SYSTEM+(' 每题写清已证实的关联、尚缺的具体单据/字段、责任角色和补齐后能解决的判断；计划BOM不是实际消耗，业务关联不是批次证明，入库成本已承接上游时不重复相加。' if lesson=='lineage' else ''),
            'input': json.dumps({'cases': CASES if lesson=='general' else LINEAGE_CASES}, ensure_ascii=False)}


REVIEW_CASES = [
    {'id': 'asserted', 'facts': '合成材料记录含零值，原因未核实。', 'answer': '材料成本严重低估，无法反映真实消耗。不能确认实际成本严重低估。'},
    {'id': 'cautious', 'facts': '合成材料记录含零值，原因未核实。', 'answer': '材料成本可能未记全，先查零值来源；不能确认实际成本严重低估。'},
    {'id': 'mixed_grain', 'facts': '合成任务37个、报工33条，关系为多对多。', 'answer': '确定漏报四次。'},
    {'id': 'grain_caution', 'facts': '合成任务37个、报工33条，关系为多对多。', 'answer': '两种数量不能直接相减认定漏报，需要逐任务核对。'},
    {'id': 'normal_forced', 'facts': '合成数据同品同单位同阶段，投入与工资核验完整，成本接近且无已知对账差异。', 'answer': '确定存在严重生产浪费。'},
    {'id': 'normal_ok', 'facts': '合成数据同品同单位同阶段，投入与工资核验完整，成本接近且无已知对账差异。', 'answer': '当前未发现可确认异常，继续按同口径观察。'},
    {'id': 'snapshot', 'facts': '合成余额为关账日之后的采集快照，没有期初、期末余额。', 'answer': '已经验证关账库存准确。'},
    {'id': 'hypothesis', 'facts': '合成工资记录为零，是否另记未核实。', 'answer': '工资可能另记或漏记，需要核对计薪依据。'},
]


def decision_payload(case_id=None):
    selected=REVIEW_CASES if case_id is None else [case for case in REVIEW_CASES if case['id']==case_id]
    if not selected:raise TeacherError('UNKNOWN_SYNTHETIC_REVIEW_CASE')
    return {'model': 'gpt-6-luna', 'input': json.dumps(selected, ensure_ascii=False),
            'questions': [{'type': 'predicate', 'name': case['id'],
                           'instructions': ('仅检查同id回答是否含证据不支持的确定性断言或前后矛盾；明确的假设、建议、索取证据和谨慎拒绝判断不算错误。高概率也不是事实保证。只检查id='+case['id']) if case_id is None else
                           '判断条件是否成立：本案例answer含有原始facts不支持的确定性事实、因果结论或前后矛盾。明确写为可能性、待核实、建议或拒绝下判断，条件不成立。只评估这一条answer，不将facts中的缺失本身判为回答错误。'}
                          for case in selected]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    if not args.run:
        print(json.dumps({'syntheticCases': len(CASES), 'additionalBudgetUsd': 1, 'noPaidCalls': True}));return
    experiment = Supplement()
    try:
        for model, effort in [('gpt-6-astra', 'medium'), ('gpt-6-astra', 'high'), ('gpt-6.1-sol', 'high')]:
            result = experiment.call(model+'-'+effort, model, teacher_payload(model, effort), 'responses')
            if result['body'].get('status') != 'completed':
                raise TeacherError('PARTIAL_TEACHER_RETAINED_FOR_LOCAL_SUMMARY')
        experiment.call('decision-shadow', 'decisions/gpt-6-luna', decision_payload(), 'decisions')
    finally:
        experiment.client.close()


if __name__ == '__main__':
    try:main()
    except TeacherError as exc:
        print(json.dumps({'status': 'stopped', 'reason': str(exc)}), flush=True)
        raise SystemExit(2) from None
