"""Deterministic, connector-independent cost alerts. AI can explain these facts only.

Input is a normalized cost-project view, scoped by its authenticated caller.
Missing values never become zeros; business signals are review candidates, not causes.
"""
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
import hashlib
import json

SCHEMA='smartbi.cost-alerts/1'
RULE_VERSION='2026-11-03.1'

# code: family, title, impacted fields, owner, next action
CATALOG={
 'NOT_FINISHED':('readiness','工单尚未完工',['status'],'生产','确认生产进度；完工后再核算最终单位成本'),
 'FINISHED_GOOD_INVALID':('data_quality','最终良品量缺失或非正数',['finishedGood'],'生产/仓库','核对最终审批良品与成品入库，确认是否空单或取消'),
 'FINISHED_UNIT_MISSING':('data_quality','成品单位缺失',['unit'],'生产','确认成品基本单位；不能跨单位直接相加'),
 'MATERIAL_LINK_MISSING':('data_quality','未关联真实领退料',['materialLinesDetail'],'仓库','补核真实领料及退料单和工单关联；BOM不能代替实际领料'),
 'MATERIAL_VALUE_MISSING':('data_quality','材料金额字段缺失',['materialLinesDetail.recognizedCost'],'财务/仓库','核对历史出入库估值与源字段，不将缺失金额默认为零'),
 'MATERIAL_VALUE_ZERO':('data_quality','有耗料但材料金额全部为零',['materialLinesDetail.recognizedCost'],'财务/仓库','确认是否漏估值或确为零成本；提供当时估值依据'),
 'MATERIAL_VALUE_PARTIAL_ZERO':('data_quality','部分材料零价或缺价',['materialLinesDetail.unitPrice','materialLinesDetail.recognizedCost'],'财务/仓库','逐材料核对历史成本单价与金额；参考均价不得直接回填'),
 'MOVEMENT_INVALID':('data_quality','领退料数量、单位或成本异常',['materialLinesDetail'],'仓库/财务','核对数量单位、金额符号和退料归属，不猜测换算'),
 'LABOR_COVERAGE_MISSING':('data_quality','工序人工成本覆盖不完整',['taskCount','laborCostKnownTasks'],'生产/人事','核对工序任务及累计工资；工资估算不能替代已核实工资'),
 'LABOR_WITH_HOURS_ZERO':('data_quality','有工时但工资为零或缺失',['processes.hours','processes.laborCost'],'生产/人事','核对报工时间与计薪规则，不将工资估算重复加入'),
 'LABOR_ZERO_UNVERIFIED':('data_quality','累计工资为零，需确认计薪口径',['laborCost'],'生产/人事','确认本单是否不计薪、工资另记或漏录；未确认前不认定人工效率更高'),
 'MATERIAL_SHARE_LOW':('business_signal','材料占比偏低，核对原料链路完整性',['materialCost','identifiedDirectCost'],'生产/仓库','核对主原料、半成品及委外材料是否完整；比例本身不能证明漏料'),
 'LABOR_SHARE_HIGH':('business_signal','人工占比较高，核对归集口径',['laborCost','identifiedDirectCost'],'生产/财务','先确认材料和半成品成本完整，再核对计薪、工时和成品单位；不能直接认定人工效率低'),
 'INPUT_ONLY_CONSUMABLES':('data_quality','关联材料名称仅见包装耗材线索',['materialLinesDetail.material'],'仓库/生产','核对食材或半成品是否在其他工单/流程归集；名称关键词仅为线索，须人工确认'),
 'UNIT_COST_SPREAD':('business_signal','同产品单位直接成本差异较大',['productCode','unit','unitIdentifiedDirectCost'],'生产/财务','按材料耗用、折算估值和人工逐项核对；不能直接认定浪费或采购涨价'),
 'PRODUCT_NAME_CONFLICT':('data_quality','同产品代码出现不同名称或阶段',['productCode','product'],'生产/主数据','确认是否同规格同工艺阶段；代码相同也不保证业务可比'),
 'PEER_SAMPLE_SMALL':('readiness','同产品可核算样本不足',['unitCostAvailable'],'业务分析','积累更多同规格同工艺数据；当前不做统计异常判定'),
 'CURRENCY_UNVERIFIED':('data_quality','来源金额币种尚未核实',['currency'],'财务','确认币种及估值口径；当前金额不标为人民币，不计算毛利'),
 'UPSTREAM_COST_BLOCKED':('data_quality','上游成本校验未通过',['issues'],'生产/财务','查看源校验明细；异常未核实前不展示可靠单位成本'),
 'PRODUCT_IDENTITY_MISSING':('data_quality','产品代码或成品单位缺失',['productCode','unit'],'生产/主数据','补核稳定产品代码和单位，当前禁止工单间成本比较'),
}

def numeric(value):
    if value is None or isinstance(value,bool):return None
    try:
        n=Decimal(str(value));return n if n.is_finite() else None
    except (InvalidOperation,ValueError):return None

def _json(value):
    return json.loads(json.dumps(value,ensure_ascii=False,default=lambda x:float(x) if isinstance(x,Decimal) else str(x)))

def evaluate(projects,*,scope,run_id,spread_threshold='0.20',material_share_threshold='0.10'):
    if not scope or not run_id:raise ValueError('ALERT_SCOPE_AND_RUN_REQUIRED')
    ids=[str(p['projectId']) for p in projects]
    if len(ids)!=len(set(ids)):raise ValueError('ALERT_ENTITY_ID_AMBIGUOUS')
    threshold=numeric(spread_threshold);share_threshold=numeric(material_share_threshold)
    if threshold is None or threshold<=0 or share_threshold is None or not 0<share_threshold<1:
        raise ValueError('ALERT_THRESHOLD_INVALID')
    alerts=[]
    def emit(code,rows,*,blocking=False,evidence=None,message=None):
        family,title,fields,owner,action=CATALOG[code]
        refs=[{'projectId':p['projectId'],'projectCode':p['projectCode'],'productCode':p.get('productCode'),'unit':p.get('unit')} for p in rows]
        identity=[scope,run_id,code,sorted(str(p['projectId']) for p in rows)]
        alerts.append({'id':hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()[:24],
                       'type':code,'family':family,'severity':'blocking' if blocking else ('info' if family=='readiness' else 'warning'),
                       'blocksUnitCost':blocking,'title':title,'message':message or title+'；'+action+'。',
                       'entityRefs':refs,'fields':fields,'evidence':_json(evidence or {}),'owner':owner,'action':action,
                       'status':'needs_review','detectedBy':'rule','ruleVersion':RULE_VERSION,
                       'scope':scope,'runId':run_id,'metric':'identified_unit_direct_cost'})
    groups=defaultdict(list)
    eligible=[]
    for p in projects:
        start=len(alerts)
        done=str(p.get('status'))=='20';blocked=not p['unitCostAvailable']
        good=numeric(p.get('finishedGood'));lines=p.get('materialLinesDetail') or []
        groups[(p.get('productCode'),p.get('unit'))].append(p)
        if not p.get('productCode') or not p.get('unit'):
            emit('PRODUCT_IDENTITY_MISSING',[p],evidence={'productCode':p.get('productCode'),'unit':p.get('unit')})
        context={'projectCode':p['projectCode'],'finishedGood':good,'materialCost':p.get('materialCost'),'laborCost':p.get('laborCost')}
        if not done:emit('NOT_FINISHED',[p],blocking=blocked,evidence={'status':p.get('status')})
        if good is None or good<=0:emit('FINISHED_GOOD_INVALID',[p],blocking=blocked,evidence={'finishedGood':p.get('finishedGood')})
        if not p.get('unit'):emit('FINISHED_UNIT_MISSING',[p],blocking=blocked)
        if not lines:emit('MATERIAL_LINK_MISSING',[p],blocking=blocked,evidence={'linkedLines':0})
        else:
            refs=[{k:r.get(k) for k in ('orderCode','materialCode','material','unit','netConsumption','recognizedCost','unitPrice')} for r in lines]
            missing=[r for r in refs if numeric(r['recognizedCost']) is None]
            zeros=[r for r in refs if numeric(r['netConsumption']) not in (None,0) and
                   (numeric(r['recognizedCost']) in (None,0) or numeric(r['unitPrice']) in (None,0))]
            if missing:emit('MATERIAL_VALUE_MISSING',[p],blocking=blocked,evidence={'lines':missing})
            if all(numeric(r['recognizedCost'])==0 for r in refs) and any((numeric(r['netConsumption']) or 0)>0 for r in refs):
                emit('MATERIAL_VALUE_ZERO',[p],blocking=blocked,evidence={'lines':zeros})
            elif zeros:emit('MATERIAL_VALUE_PARTIAL_ZERO',[p],blocking=blocked,evidence={'lines':zeros})
            invalid=[r for r in refs if not r['unit'] or numeric(r['netConsumption']) is None
                     or (numeric(r['unitPrice']) is not None and numeric(r['unitPrice'])<0)
                     or (numeric(r['netConsumption']) is not None and numeric(r['recognizedCost']) is not None
                         and (numeric(r['netConsumption'])*numeric(r['recognizedCost'])<0
                              or numeric(r['netConsumption'])==0 and numeric(r['recognizedCost'])!=0))]
            if invalid:emit('MOVEMENT_INVALID',[p],blocking=blocked,evidence={'lines':invalid})
        if not p.get('taskCount') or p.get('laborCostKnownTasks',0)<p.get('taskCount',0):
            emit('LABOR_COVERAGE_MISSING',[p],blocking=blocked,evidence={'taskCount':p.get('taskCount'),'knownTasks':p.get('laborCostKnownTasks')})
        unpaid=[r for r in p.get('processes',[]) if (numeric(r.get('hours')) or 0)>0 and numeric(r.get('laborCost')) in (None,0)]
        if unpaid:emit('LABOR_WITH_HOURS_ZERO',[p],blocking=blocked,evidence={'processes':unpaid})
        if blocked and not any(a['blocksUnitCost'] for a in alerts[start:]):
            emit('UPSTREAM_COST_BLOCKED',[p],blocking=True,evidence={'issues':p.get('issues',[])})
        if p['unitCostAvailable']:
            eligible.append(p)
            if numeric(p.get('laborCost'))==0:emit('LABOR_ZERO_UNVERIFIED',[p],evidence=context)
            total=numeric(p.get('identifiedDirectCost'));material=numeric(p.get('materialCost'))
            if total is not None and total>0 and material is not None and material/total<share_threshold:
                emit('MATERIAL_SHARE_LOW',[p],evidence={**context,'materialShare':material/total,'threshold':share_threshold,'materials':refs},
                     message=f"{p['projectCode']} 材料占已识别直接成本 {material/total:.1%}，低于本次核对阈值 {share_threshold:.0%}；请核对主原料是否完整，当前不能判为低成本产品。")
            wage=numeric(p.get('laborCost'))
            if total is not None and total>0 and wage is not None and wage/total>Decimal('0.65'):
                emit('LABOR_SHARE_HIGH',[p],evidence={**context,'laborShare':wage/total,'threshold':Decimal('0.65')},
                     message=f"{p['projectCode']} 人工占已识别直接成本 {wage/total:.1%}，超过本次65%核对阈值；先确认材料归集完整，再核对计薪和单位，不能直接认定人工效率低。")
            words=('吸塑盒','真空袋','包装袋','标签','纸箱','封口膜','保鲜膜')
            if lines and all(any(w in str(r.get('material') or '') for w in words) for r in lines):
                emit('INPUT_ONLY_CONSUMABLES',[p],evidence={'materials':refs,'method':'材料名称关键词，仅为线索，非物料类别真值'},
                     message=f"{p['projectCode']} 当前关联材料仅见包装耗材名称线索；请核对原料/半成品是否另记，暂不能把当前可识别成本认定为完整成品成本。")
    for (code,unit),rows in groups.items():
        if not code or not unit:continue
        valid=[p for p in rows if p['unitCostAvailable']]
        if not valid:continue
        names=sorted({p['product'] for p in valid})
        if len(names)>1:
            emit('PRODUCT_NAME_CONFLICT',valid,evidence={'names':names,'productCode':code},message=f'产品代码 {code} 的可核算工单存在多个名称/阶段：'+ '、'.join(names)+'。先核实同规格同工艺，本次不生成成本差异告警。')
            continue
        if len(valid)<3:emit('PEER_SAMPLE_SMALL',valid,evidence={'eligibleProjects':len(valid),'minimumForSpreadReview':3})
        else:
            lo=min(valid,key=lambda p:numeric(p['unitIdentifiedDirectCost']));hi=max(valid,key=lambda p:numeric(p['unitIdentifiedDirectCost']))
            low=numeric(lo['unitIdentifiedDirectCost']);high=numeric(hi['unitIdentifiedDirectCost'])
            if low is not None and low>0 and (high-low)/low>=threshold:
                emit('UNIT_COST_SPREAD',valid,evidence={'baselineProject':lo['projectCode'],'comparisonProject':hi['projectCode'],'minimum':low,'maximum':high,'spread':(high-low)/low,'threshold':threshold,'sampleSize':len(valid),'comparabilityVerified':False},
                     message=f"{hi['product']} 同代码同单位工单最高 {high:.4f}/{unit}、最低 {low:.4f}/{unit}，差异 {(high-low)/low:.1%}，触发 {threshold:.0%} 复核阈值；仅为候选信号，非已证实经营异常。")
    emit('CURRENCY_UNVERIFIED',[],evidence={'currency':None})
    families=Counter(a['family'] for a in alerts);types=Counter(a['type'] for a in alerts)
    summary={'projects':len(projects),'costEligible':len(eligible),'costBlocked':len(projects)-len(eligible),'alerts':len(alerts),
             'byFamily':dict(families),'byType':dict(types),'uniqueFlaggedProjects':len({str(p['projectId']) for a in alerts for p in a['entityRefs']})}
    return {'schema':SCHEMA,'ruleVersion':RULE_VERSION,'scope':scope,'runId':run_id,'summary':summary,'items':alerts,
            'limitations':['同一工单可命中多个提醒，提醒条数不能当工单数','通过字段校验不证明实际原料与人工已完整归集','高成本与异常分开；不跨SKU或单位判断高低','半成品转入成本不再叠加上游生产成本，避免重复计费'],
            'ai':{'status':'not_run','message':'规则已独立完成识别；AI只对带证据的问题提供核对建议'}}
