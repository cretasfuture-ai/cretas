"""Private per-project gap checklist; never substitute reference prices for costs."""
import json
import os
from collections import Counter
from pathlib import Path
from .analytics import number
from .blocked_audit import CATEGORIES,category,validate_current
from .secrets import load_local_secret
from .sync import timestamp
from .contracts import BlacklakeError

def value(row,key):
    v=row.get(key)
    return '未提供' if v is None else str(v)

def linked(detail,project):
    return (project.get('id') not in (None,'') and project.get('projectCode') not in (None,'')
            and detail.get('associatedBizOrderId') not in (None,'')
            and detail.get('associatedBizOrderCode') not in (None,'')
            and str(detail.get('associatedBizOrderType'))=='10'
            and str(detail.get('associatedBizOrderId'))==str(project['id'])
            and detail.get('associatedBizOrderCode')==project['projectCode'])

def not_later(a,b):
    try:return timestamp(a,'Asia/Shanghai')<=timestamp(b,'Asia/Shanghai')
    except BlacklakeError:return False

def fields(row,keys,zero=False):
    numeric={'numberofGoodProducts','actualAmount','storageAmount','changeQty','costUnitPrice','changedCostAmountSigned','sumOutputSalary','outputDurationForSecond'}
    result=[]
    for k in keys:
        v=row.get(k)
        if zero:
            if number(v)==0:result.append(k)
        elif v is None or v=='':result.append(k)
        elif k in numeric and number(v) is None:result.append('格式非法:'+k)
    return '；'.join(result) or '无'

def report_tables(report):
    data=report['data'];validate_current(data)
    validate_current({'project':report['current_project_identities']})
    baseline={str(r['projectId']):r for r in report['baseline_analysis']['cost']['projects']}
    current={str(r['projectId']):r for r in report['current_analysis']['cost']['projects']}
    projects={str(p['id']):p for p in data['project']}
    products={str(p['id']):p for p in data['product']}
    actions={CATEGORIES[0]:('生产负责人','确认是否继续生产；完工后核对最终良品及领退料，不将未完工当作数据丢失'),
             CATEGORIES[1]:('生产/仓库负责人','核对工单numberofGoodProducts、末道工序审批良品及成品入库；确认取消或空工单，不能用计划量代替'),
             CATEGORIES[2]:('仓库/生产负责人','提供该工单真实领料、退料单及关联ID/编码；无领料需确认是否漏单或采用其他流程'),
             CATEGORIES[3]:('财务/仓库负责人','确认零金额是否合理；提供历史估值依据和领退料成本，核对costUnitPrice/changedCostAmountSigned'),
             CATEGORIES[4]:('财务/仓库负责人','补核清单所列零价/缺价材料历史成本；当前均价、采购价只能作线索，不直接回填')}
    detail=[];materials=[];quantity=[];labor=[];unlinked=[]
    stats=Counter()
    orders=data['stock_order']
    for order in orders:
        try:timestamp(order.get('bizAt'),'Asia/Shanghai')
        except BlacklakeError:stats['stock_orders_with_unknown_business_time']+=1
    for pid in report['blocked_ids']:
        old=baseline[pid];p=projects.get(pid);now=current.get(pid)
        if not p or not now:
            detail.append([old['projectCode'],old['product'],category(old),'本次未返回','待核','本次未返回',0,'待核',0,0,'待核','源端本次未返回','重新查证','集成负责人']);continue
        cat=category(now);owner,action=actions.get(cat,('集成/财务负责人','逐项核对异常字段'))
        detail.append([now['projectCode'],now['product'],category(old),cat,{'0':'未开始','10':'进行中','20':'已完工','30':'取消'}.get(now['status'],now['status']),
                       value(now,'finishedGood'),now['materialLines'],value(now,'materialCost'),now['taskCount'],now['laborCostKnownTasks'],now.get('unit') or '未提供',
                       '；'.join(now['issues']),action,owner])
        ts=[t for t in data['task'] if str(t.get('projectId'))==pid]
        outs=[o for o in data['output'] if str(o.get('projectId'))==pid]
        receipts=[(o,d) for o in orders if str(o.get('orderStatus'))=='1' and str(o.get('bizType'))=='13'
                  for d in o.get('orderDetails') or [] if linked(d,p)]
        if cat==CATEGORIES[1]:
            positive_tasks=sum((number(t.get('approvedFineAmount')) or 0)>0 for t in ts)
            positive_outputs=sum((number(o.get('numberofgoodProducts')) or 0)>0 for o in outs)
            stats['completed_no_good_positive_task_projects']+=bool(positive_tasks)
            stats['completed_no_good_positive_report_projects']+=bool(positive_outputs)
            stats['completed_no_good_receipt_projects']+=bool(receipts)
            quantity.append([p['projectCode'],p.get('productName'),value(p,'projectUnit'),value(p,'numberofGoodProducts'),value(p,'actualAmount'),
                             value(p,'storageAmount'),len(ts),positive_tasks,len(outs),positive_outputs,len(receipts),
                             '；'.join(o.get('orderNo','') for o,d in receipts) or '未检出',
                             '工序或入库数量仅作核对，不相加、不替代工单最终良品',
                             fields(p,['numberofGoodProducts','actualAmount','storageAmount','projectUnit']),
                             fields(p,['numberofGoodProducts','actualAmount','storageAmount'],True)])
        if any('人工' in issue or '工资' in issue for issue in now['issues']):
            if not ts:labor.append([p['projectCode'],'无任务','未提供','未提供','未提供',len(outs),'先核对工单是否实际生产；没有任务不能计零人工成本','task记录未返回（非工资字段为空）','不适用'])
            for t in ts:
                dur=number(t.get('outputDurationForSecond'));salary=number(t.get('sumOutputSalary'))
                if salary is None or (dur is not None and dur>0 and salary==0):
                    labor.append([p['projectCode'],t.get('processName'),value(t,'seq'),value(t,'outputDurationForSecond'),value(t,'sumOutputSalary'),
                                  sum(str(o.get('taskId'))==str(t['id']) for o in outs),'核对计薪规则和任务累计工资；estimatedSalary不叠加、不替代',
                                  fields(t,['sumOutputSalary','outputDurationForSecond']),fields(t,['sumOutputSalary','outputDurationForSecond'],True)])
        for o in orders:
            if str(o.get('orderStatus'))!='1' or str(o.get('bizType')) not in ('30','32'):continue
            for d in o.get('orderDetails') or []:
                if not linked(d,p):continue
                qty=number(d.get('changeQty'));cost=number(d.get('changedCostAmountSigned'));price=number(d.get('costUnitPrice'))
                if qty==0 or (cost is not None and cost!=0 and price is not None and price!=0):continue
                master=products.get(str(d.get('productId')), {})
                master_exact=master.get('productCode')==d.get('productCode') and master.get('unit')==d.get('unitName')
                refs=[]
                for f in ('costPrice','movingWeightedAverageCost','realCostUnitPrice','taxInclusiveUnitPrice'):
                    if (number(d.get(f)) or 0)>0:refs.append(f+'='+value(d,f))
                if master_exact:
                    for f in ('costPrice','movingWeightedAverageCost'):
                        if (number(master.get(f)) or 0)>0:refs.append('当前产品.'+f+'='+value(master,f))
                historical=[(oi,di) for oi in orders if str(oi.get('orderType'))=='10' and str(oi.get('orderStatus'))=='1'
                            and not_later(oi.get('bizAt'),o.get('bizAt'))
                            for di in oi.get('orderDetails') or [] if str(di.get('productId'))==str(d.get('productId'))
                            and di.get('productCode')==d.get('productCode') and di.get('unitName')==d.get('unitName')
                            and (number(di.get('costUnitPrice')) or 0)>0]
                stats['material_lines_with_reference_price']+=bool(refs)
                stats['material_lines_with_prior_inbound_price']+=bool(historical)
                materials.append([p['projectCode'],d.get('productCode'),d.get('productName'),d.get('unitName'),o.get('orderNo'),o.get('bizAt'),
                                  o.get('bizTypeDisplay'),value(d,'changeQty'),value(d,'costUnitPrice'),value(d,'changedCostAmountSigned'),
                                  '字段缺失' if cost is None or price is None else '源端显式零值',
                                  '；'.join(refs) or '未检出正数参考价',len(historical),'；'.join(oi.get('orderNo','') for oi,di in historical) or '未检出',
                                  '确认历史出库估值/退料成本；参考价不能直接认定为该笔实际成本',
                                  fields(d,['changeQty','unitName','costUnitPrice','changedCostAmountSigned']),
                                  fields(d,['changeQty','costUnitPrice','changedCostAmountSigned'],True)])
    allprojects=report['current_project_identities']
    for o in orders:
        if str(o.get('orderStatus'))!='1' or str(o.get('bizType')) not in ('30','32'):continue
        for d in o.get('orderDetails') or []:
            if any(linked(d,p) for p in allprojects):continue
            text=json.dumps([o.get('originIdList'),o.get('originCodeList'),o.get('remark'),o.get('customFieldValues'),d.get('originDetailDisplayInfo'),d.get('originDetailInfo'),d.get('customFieldValues')],ensure_ascii=False)
            candidates=[p['projectCode'] for p in allprojects if str(p['id']) in report['blocked_ids'] and p['projectCode'] in text]
            unlinked.append([o.get('orderNo'),o.get('bizTypeDisplay'),d.get('productCode'),d.get('productName'),value(d,'associatedBizOrderId'),
                             value(d,'associatedBizOrderCode'),value(d,'associatedBizOrderType'),'；'.join(candidates) or '未检出工单编码线索',
                             '需仓库确认真实归属；未按材料名或日期猜配工单',
                             fields(d,['associatedBizOrderId','associatedBizOrderCode','associatedBizOrderType']),
                             fields(d,['associatedBizOrderId','associatedBizOrderType'],True)])
    return {'detail':detail,'materials':materials,'quantity':quantity,'labor':labor,'unlinked':unlinked,'stats':dict(stats)}

def main():
    directory=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    report=load_local_secret(directory/'blocked93-probe.dpapi')
    tables=report_tables(report)
    out=(directory/'outputs/93-cost-gaps-20261103').resolve();out.mkdir(parents=True,exist_ok=True)
    from .full_archive import digest
    payload={'summary':report['summary'],'probe_id':report['probe_id'],'baseline_run_id':report['baseline_run_id'],
             'scope_digest':digest((report['tenant_id'],report['source_factory_id'])),
             'started_at':report['started_at'],'finished_at':report['finished_at'],**tables}
    (out/'checklist.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'rows':{k:len(v) for k,v in tables.items() if isinstance(v,list)},'stats':tables['stats'],'output':str(out)},ensure_ascii=False))

if __name__=='__main__':main()
