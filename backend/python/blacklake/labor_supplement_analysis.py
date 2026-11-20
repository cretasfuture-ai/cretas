"""Supplement facts remain separate from source payroll and feed shared diagnosis."""
from collections import Counter
from .full_archive import digest


def apply_alerts(analysis):
    supplements=analysis.get('laborSupplements')
    if not supplements:return
    report=analysis['costAlerts'];projects={str(p['projectId']):p for p in analysis['cost']['projects']}
    report['items']=[a for a in report['items'] if not a['type'].startswith('LABOR_SUPPLEMENT_')]
    groups={}
    for task in supplements['tasks']:groups.setdefault(str(task['projectId']),[]).append(task)
    for pid,tasks in groups.items():
        pending=[t for t in tasks if t['status']!='ready' or not t.get('rosterComplete')];p=projects[pid]
        if not pending:continue
        stale=any(t['status']=='stale' for t in pending)
        code='LABOR_SUPPLEMENT_STALE' if stale else 'LABOR_SUPPLEMENT_MISSING'
        title='人工补充资料需重新核对' if stale else '实际人员与人时尚未补齐'
        action='核对本单每道工序实际人员、作业时段及休息停机；在人工补充入口填写或导入'
        report['items'].append({'id':digest([report['scope'],report['runId'],code,pid])[:24],
            'type':code,'family':'data_quality','severity':'warning','blocksUnitCost':False,'title':title,
            'message':f"{p['projectCode']}有{len(pending)}道工序{'源数据变化，原补充资料需复核' if stale else '尚缺实际人员或人时'}；找车间主管 / 班组长核对，补齐后才能确认人均及工序人时分摊。",
            'entityRefs':[{k:p.get(k) for k in ('projectId','projectCode','productCode','unit')}],
            'fields':['labor.actualRoster','labor.personHours'],
            'evidence':{'tasks':[{k:t.get(k) for k in ('taskId','process','status')} for t in pending]},
            'owner':'生产','action':action,'status':'needs_review','detectedBy':'rule','ruleVersion':report['ruleVersion'],
            'scope':report['scope'],'runId':report['runId'],'metric':'labor_person_hour_allocation'})
    items=report['items'];report['summary'].update(alerts=len(items),byFamily=dict(Counter(a['family'] for a in items)),byType=dict(Counter(a['type'] for a in items)),uniqueFlaggedProjects=len({str(p['projectId']) for a in items for p in a['entityRefs']}))


def explain(analysis):
    value=analysis.get('laborSupplements')
    if not value:return
    tasks=value['tasks'];ready=sum(t['status']=='ready' for t in tasks);summary=value['summary']
    section=next((s for s in analysis['sectionInsights'] if s['sectionId']=='labor'),None)
    if not section:return
    section['observations'].append(f"实际人工补充资料已完成 {ready}/{len(tasks)} 道工序；未补齐或源数据变化的工序不会按零人时处理。")
    if summary['complete']:
        section['observations'].append(f"补充确认后，实际参与人数 {summary['participants'] if summary['participants'] is not None else '待补'}；有效人时 {summary['personHours']:.2f}。")
        if summary['perPerson'] is not None:section['observations'].append(f"所选工单实际人均人工费 {summary['perPerson']:.2f}；同一人跨工序去重。")
        section['calculation'].append('先按每张工单自己的总人工费和各工序有效人时分摊，再汇总到产品；不将不同工单的工资混在一起重摊。')
    section['nextSteps'].append('车间主管 / 班组长在人工补充入口确认实际名单和时段；人员代码保持一致，Cretas 报工和批次签到可作核对依据。')
