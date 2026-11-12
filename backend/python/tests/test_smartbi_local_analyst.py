import asyncio
from copy import deepcopy
import json

import pytest

from smartbi.services import alert_local_ai as provider,local_analyst as analyst
from smartbi.services.analysis_evidence import build
from smartbi.services.dataset_alerts import analyze_dataset
from tests.test_smartbi_shared_alerts import client,token,body


def sample_report():
    return analyze_dataset([{'工单号':'PRIVATE-ORDER','产品编码':'PRIVATE-SKU','产品名称':'PRIVATE-PRODUCT',
                             '单位':'kg','成品良品量':10,'材料成本':None,'人工成本':0}],
                           factory_id='PRIVATE-FACTORY',source_type='api',source_id='synthetic',dataset_kind='production_cost')


def response_for(pack,facts):
    sections=[]
    for sid in pack['sectionIds']:
        group=[f for f in pack['facts'] if f['sectionId']==sid]
        required=pack['requiredConsiderations'][sid]
        refs=[]
        for code in required:
            fact=next(f for f in group if code in f['considerations'])
            if fact['id'] not in refs:refs.append(fact['id'])
        refs+= [ref for ref in pack.get('requiredFactRefs',{}).get(sid,[]) if ref not in refs]
        refs+= [f['id'] for f in group if f['id'] not in refs][:10-len(refs)]
        sections.append({'sectionId':sid,'evidenceRefs':refs,
            'whatItMeans':'材料和人工记录还要核对，成本差异只是线索，不能当成已确认的浪费。',
            'whyItMatters':'材料记录不完整会使成本看起来偏低，人工为零也可能漏记或另记。',
            'hypotheses':['可能只是包装工序，食材或半成品价值记在其他工单。','可能名称、规格或阶段不同，需要先核实。'],
            'nextStep':'先核对材料单据、人工记录和最终良品，再核对规格与加工阶段。','owner':'生产/财务',
            'cannotConclude':'样本不足、周期数据未齐或缺历史库存时，不能判断完整成本和趋势；无数据不代表没有生产。',
            'followupQuestion':'还缺哪些资料才能确认成本？','considerations':required})
        narrative=pack.get('narrativeRequirements',{}).get(sid,[])
        if 'normal_control' in narrative:sections[-1]['whatItMeans']+='另一组账面波动小，但记录完整性仍待核。'
        if 'order_and_quantity_coverage' in narrative:sections[-1]['whatItMeans']+='按工单与按产量覆盖不同，不能外推全厂。'
        if 'business_link_is_not_batch' in narrative:sections[-1]['whatItMeans']+='业务关联不是批次证明。'
    return {'reviews':[{'alertId':f['alertId'],'check':f['allowedChecks'][0],'priority':'high'} for f in facts],
            'sections':sections}


@pytest.fixture(autouse=True)
def isolated(monkeypatch,tmp_path):
    monkeypatch.setenv('SMARTBI_LOCAL_ANALYST_ENABLED','1')
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','ssh')
    monkeypatch.setenv('SMARTBI_ALERT_AI_STATE_FILE',str(tmp_path/'usage.sqlite3'))
    analyst._cache.clear()


def install_model(monkeypatch,mutate_first=None):
    calls=[]
    def infer(payload,config):
        prompt=json.loads(payload['messages'][1]['content']);calls.append(prompt)
        pack={'sectionIds':list(prompt['curriculum']),'facts':prompt['facts'],
              'requiredConsiderations':prompt['requiredConsiderations'],
              'narrativeRequirements':prompt.get('narrativeChecklist',{}),
              'requiredFactRefs':{p['sectionId']:p['mustUseEvidenceRefs'] for p in prompt['sectionPlan']}}
        value=response_for(pack,prompt['reviewFacts'])
        if mutate_first and len(calls)==1:mutate_first(value)
        return provider.InferenceResult(value,{'inputTokens':10,'outputTokens':3,'totalTokens':13,'durationSeconds':0.1,'source':'ollama_response','estimated':False})
    monkeypatch.setattr(provider,'infer',infer)
    return calls


def test_profile_teaches_statistics_interpretation_and_keeps_private_names_off_model():
    report=sample_report();pack=build(report)
    payload=analyst.payload_for(pack,provider.facts_for(report),'qwen3.8:27b')
    wire=json.dumps(payload,ensure_ascii=False)
    assert 'PRIVATE-' not in wire
    assert '怎么看' in wire and '为什么值得' in wire and '不能' in wire
    assert set(pack['sectionIds'])=={'coverage','material','labor','finished','product_cost','production','inventory'}
    assert all(f['id'] in pack['displayFacts'] for f in pack['facts'])


def test_failed_explanation_enters_local_candidate_pool_without_auto_approval(monkeypatch,tmp_path):
    from smartbi.services.analyst_flywheel import FlywheelStore
    path=tmp_path/'learning.db';monkeypatch.setenv('SMARTBI_ANALYST_FLYWHEEL_DB',str(path))
    def invalid(v):v['sections'][0]['whyItMatters']='材料成本严重低估，无法反映真实消耗。'
    calls=install_model(monkeypatch,invalid)
    report=sample_report();asyncio.run(analyst.enrich(report,'actor'))
    assert len(calls)==2 and report['ai']['status']=='complete'
    assert report['ai']['flywheel']['status']=='candidate_only'
    assert len(report['ai']['flywheel']['candidateIds'])==1
    assert FlywheelStore(path).export_confirmed(report['scope'],'train')==[]


def test_generic_nonproduction_table_is_not_forced_into_manufacturing_cost():
    report=analyze_dataset([{'客户':'合成客户','次数':1}],factory_id='A',source_type='excel',source_id='generic',dataset_kind='generic')
    pack=build(report)
    prompt=json.loads(analyst.payload_for(pack,provider.facts_for(report),'qwen3.8:27b')['messages'][1]['content'])
    assert pack['sectionIds']==['coverage'] and not pack['isCostDataset']
    assert 'MISSING_COST' not in pack['requiredConsiderations']['coverage']
    assert prompt['curriculum']['coverage']['indicator']=='扫描覆盖与数据质量'
    assert set(prompt['statisticDefinitions'])=={'alertCount'}
    assert set(prompt['secondPassReviewDirections'])=={'coverage'}
    assert '工资' not in str(prompt['secondPassReviewDirections'])


def test_distilled_methods_define_flags_ratios_and_inventory_scope():
    from smartbi.services.analysis_methods import STATISTIC_DEFINITIONS,REVIEW_DIRECTIONS
    report=sample_report();pack=build(report)
    prompt=json.loads(analyst.payload_for(pack,provider.facts_for(report),'qwen3.8:27b')['messages'][1]['content'])
    assert '已经是单位估值变化' in prompt['statisticDefinitions']['inputValuationChange']
    assert '布尔标记' in prompt['statisticDefinitions']['inputKindsChanged']
    assert '不是总金额排名' in prompt['statisticDefinitions']['sameUnitAmountRank']
    assert '业务日期' in prompt['statisticDefinitions']['movementOrders/balanceRows']
    assert set(prompt['secondPassReviewDirections'])==set(pack['sectionIds'])
    assert '六膳门' not in str(STATISTIC_DEFINITIONS)+str(REVIEW_DIRECTIONS)
    assert 'GD2026' not in str(STATISTIC_DEFINITIONS)+str(REVIEW_DIRECTIONS)


def test_negated_impact_is_not_confused_with_asserting_understated_cost():
    report=sample_report();pack=build(report);facts=provider.facts_for(report);value=response_for(pack,facts)
    value['sections'][0]['cannotConclude']='不能断言全厂成本严重低估，仍需核对数据覆盖。'
    analyst.validate(value,pack,facts)
    value['sections'][0]['whatItMeans']='当前数据说明全厂成本严重低估。'
    with pytest.raises(ValueError,match='UNSUPPORTED_COVERAGE_IMPACT'):analyst.validate(value,pack,facts)


@pytest.mark.parametrize('text',[
    '材料成本严重低估，导致单位成本失真，无法反映真实消耗。',
    '无法倒推历史库存，材料成本严重低估。',
    '不能确认真实损耗，但材料成本严重低估。',
    '材料成本缺失会导致产品成本被低估，影响定价策略。',
])
def test_unrelated_later_negation_does_not_hide_an_unsupported_assertion(text):
    assert analyst._unsupported_impact(text)
    report=sample_report();pack=build(report);facts=provider.facts_for(report);value=response_for(pack,facts)
    value['sections'][0]['whyItMatters']=text
    with pytest.raises(ValueError,match='UNSUPPORTED_COVERAGE_IMPACT'):analyst.validate(value,pack,facts)


@pytest.mark.parametrize('text',[
    '不能断定实际成本一定严重低估。',
    '如果材料漏记，单位成本可能严重低估。',
    '若主料未记全，账面单位成本将严重低估。',
    '材料成本可能未记全，不能确定是否低估。',
    '材料成本是否被低估？',
])
def test_explicit_uncertainty_and_conditionals_remain_valid(text):
    assert not analyst._unsupported_impact(text)


def test_cautious_negative_claims_are_not_misread_as_certain_causes():
    assert not analyst._asserted('原因就是','不能认定原因就是浪费。')
    assert analyst._asserted('原因就是','不能认定浪费，但原因就是人工错误。')
    assert not analyst._asserted('没有历史出入库记录','不能把没有期末余额理解为没有历史出入库记录。')


@pytest.mark.parametrize('mutation,code',[
    (lambda v:v['sections'][0].update(whatItMeans='成本是九十九元而利润为99元'),'UNBOUND_NUMBER'),
    (lambda v:v['sections'][0].update(whatItMeans='原因就是工人浪费'),'UNSUPPORTED_CERTAINTY'),
    (lambda v:v['sections'][0].update(owner='越权修改系统'),'OWNER'),
    (lambda v:v['sections'].pop(),'SECTION_COVERAGE'),
    (lambda v:v['sections'][0].update(evidenceRefs=['NONEXISTENT']),'EVIDENCE_SCOPE'),
    (lambda v:v['sections'][0].update(considerations=[]),'REQUIRED_RISKS'),
])
def test_unbound_numbers_causes_missing_dimensions_and_unknown_evidence_are_rejected(mutation,code):
    report=sample_report();pack=build(report);facts=provider.facts_for(report)
    value=response_for(pack,facts);mutation(value)
    with pytest.raises(ValueError,match=code):analyst.validate(value,pack,facts)


def test_cross_section_evidence_cannot_be_borrowed_for_a_conclusion():
    report=sample_report();pack=build(report);facts=provider.facts_for(report);value=response_for(pack,facts)
    value['sections'][0]['evidenceRefs']=[next(f['id'] for f in pack['facts'] if f['sectionId']=='inventory')]
    with pytest.raises(ValueError,match='EVIDENCE_SCOPE'):analyst.validate(value,pack,facts)


def test_reference_padding_is_normalized_without_guessing_missing_or_cross_scope_ids():
    report=sample_report();pack=build(report);facts=provider.facts_for(report);value=response_for(pack,facts)
    value['sections'][0]['evidenceRefs']=[ref.lower().replace('f00','f').replace('f0','f') for ref in value['sections'][0]['evidenceRefs']]
    _,sections=analyst.validate(value,pack,facts)
    assert sections[0]['evidenceRefs']==response_for(pack,facts)['sections'][0]['evidenceRefs']


def test_low_price_zero_payroll_and_product_stage_conflict_are_required_checks():
    report=sample_report()
    report['items']=[]
    low={'projectId':1,'projectCode':'PRIVATE-LOW','productCode':'P','product':'PRIVATE-FOOD','unit':'kg',
         'unitIdentifiedDirectCost':10,'materialPerFinished':10,'laborPerFinished':0,'laborCost':0}
    high={**low,'projectId':2,'projectCode':'PRIVATE-HIGH','unitIdentifiedDirectCost':20,'laborPerFinished':10,'laborCost':100}
    context={'eligibleCostReview':{'projects':[low,high],'rankings':[]},'cost':{},'production':{}}
    pack=build(report,context)
    assert 'ZERO_LABOR_BASELINE' in pack['requiredConsiderations']['product_cost']
    assert 'PRIVATE-' not in json.dumps(analyst.payload_for(pack,[],'qwen3.8:27b'))
    bridge=next(f for f in pack['facts'] if f['kind']=='same_product_bridge')
    assert '人工为零' in pack['displayFacts'][bridge['id']]['text']
    context['eligibleCostReview']['projects'].append({**low,'product':'PRIVATE-OTHER-STAGE'})
    assert not any(f['kind']=='same_product_bridge' for f in build(report,context)['facts'])


@pytest.mark.parametrize('historical_missing',[True,False])
def test_stock_snapshot_limit_cannot_be_turned_into_missing_movements(historical_missing):
    report=sample_report();context={'cost':{},'production':{},'inventory':{'movementOrders':7,'records':[{}]},
                                   'periodMetadata':{'scopeDescription':'合成范围','inventoryStatus':'historical_balance_unavailable' if historical_missing else 'snapshot_within_period'}}
    pack=build(report,context);facts=provider.facts_for(report);value=response_for(pack,facts)
    inventory=next(s for s in value['sections'] if s['sectionId']=='inventory')
    inventory['whatItMeans']='只有库存快照，没有历史出入库记录。'
    with pytest.raises(ValueError,match='INVENTORY_AVAILABILITY_CONTRADICTION'):analyst.validate(value,pack,facts)


def test_actual_model_output_is_selected_bound_to_display_and_cached_without_new_tokens(monkeypatch):
    calls=install_model(monkeypatch);report=sample_report()
    result=asyncio.run(provider.enrich(deepcopy(report),22))
    assert result['ai']['status']=='complete' and len(result['ai']['analysisSections'])==7
    assert all(s['statistics'] and s['whatItMeans'] and s['whyItMatters'] and s['nextStep'] for s in result['ai']['analysisSections'])
    assert result['ai']['usage']['totalTokens']==13
    again=asyncio.run(provider.enrich(deepcopy(report),22))
    assert len(calls)==1 and again['ai']['cacheHit'] and again['ai']['currentRequestUsage']['totalTokens']==0
    monkeypatch.setattr(analyst,'VERSION','synthetic-next-profile')
    asyncio.run(provider.enrich(deepcopy(report),22));assert len(calls)==2


def test_one_repair_is_validated_and_both_model_calls_are_accounted(monkeypatch):
    calls=install_model(monkeypatch,lambda v:v['sections'][0].update(whatItMeans='成本123元'))
    result=asyncio.run(provider.enrich(sample_report(),22))
    assert len(calls)==2 and result['ai']['status']=='complete' and result['ai']['repairAttempts']==1
    assert result['ai']['usage']['totalTokens']==26
    assert calls[1]['validationFeedback']=='ANALYST_UNBOUND_NUMBER_OR_ALIAS'


def test_durable_retry_retains_usage_without_double_counting_shared_call_ids():
    first={'requestId':'first','inputTokens':10,'outputTokens':3,'totalTokens':13,'durationSeconds':1}
    second={**first,'requestId':'second'}
    prior={'usageCalls':[first]};current={'usageCalls':[first,second]}
    analyst.retain_usage(current,prior)
    assert current['usage']['totalTokens']==26 and len(current['usageCalls'])==2


def test_negative_necessity_is_not_misclassified_as_a_confirmed_cause():
    assert not analyst._asserted(r'必然','成本高不必然耗量高，仍需核实投入组合。')
    assert not analyst._asserted(r'必然','成本差并非必然来自浪费。')
    assert analyst._asserted(r'必然','不是材料问题，必然是生产浪费。')


def test_excel_and_other_api_sources_use_the_same_profile_and_permission_gate(client,monkeypatch):
    monkeypatch.setenv('SMARTBI_ALERT_AI_TRANSPORT','ssh');calls=install_model(monkeypatch)
    for source in ('api','excel'):
        response=client.post('/api/smartbi/data-alerts/analyze',json=body(source_type=source,with_ai=True),headers=token())
        assert response.status_code==200
        ai=response.json()['data']['ai']
        assert ai['status']=='complete' and len(ai['analysisSections'])==7
    assert len(calls)==2

    assert client.post('/api/smartbi/data-alerts/analyze',json=body(with_ai=True),headers=token(role='viewer')).status_code==403
    assert len(calls)==2


def test_excel_weighted_statistics_are_calculated_and_hidden_from_non_price_roles():
    from smartbi.services.alert_datasets import for_role
    rows=[{'工单号':code,'产品编码':'PRIVATE-P','产品名称':'PRIVATE-FOOD','单位':'kg',
           '成品良品量':quantity,'材料成本':material,'人工成本':0}
          for code,quantity,material in [('ONE',1,10),('TWO',9,18)]]
    report=analyze_dataset(rows,factory_id='A',source_type='excel',source_id='synthetic',dataset_kind='production_cost')
    assert float(report['analysisMetrics']['rankings'][0]['weightedUnitCost'])==2.8
    assert 'analysisMetrics' not in for_role(report,'viewer')
    pack=build(report)
    assert any(f['kind']=='weighted_unit_cost' for f in pack['facts'])
    assert 'PRIVATE-' not in json.dumps(analyst.payload_for(pack,provider.facts_for(report),'qwen3.8:27b'))


def test_department_order_is_normalized_but_unknown_owner_is_rejected():
    report=sample_report();pack=build(report);facts=provider.facts_for(report)
    value=response_for(pack,facts);value['sections'][0]['owner']='仓库/生产'
    assert analyst.validate(value,pack,facts)[1][0]['owner']=='生产/仓库'
    value['sections'][0]['owner']='仓库/财务'
    assert analyst.validate(value,pack,facts)[1][0]['owner']=='财务/仓库'
    value['sections'][0]['owner']='外部供应商'
    with pytest.raises(ValueError,match='ANALYST_OWNER'):analyst.validate(value,pack,facts)


@pytest.mark.parametrize('text',['数据未覆盖完整周期，不能外推。','当前非完整周期，结论受限。','数据采集早于周期结束，不能预测后续。'])
def test_equivalent_explicit_partial_period_wording_is_recognized(text):
    import re
    assert all(re.search(pattern,text) for pattern in analyst.HAZARD_WORDS['PARTIAL_PERIOD'])
    assert not all(re.search(pattern,'本周期数据完整，已完成全部核算。') for pattern in analyst.HAZARD_WORDS['PARTIAL_PERIOD'])
