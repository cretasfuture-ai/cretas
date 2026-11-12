"""Auditable eligible-order review and private report; no source API or LLM required."""
from collections import defaultdict
from decimal import Decimal
from .analytics import number,serial

def review(analysis):
    rows=[p for p in analysis['cost']['projects'] if p['unitCostAvailable']]
    groups=defaultdict(list)
    out=[]
    for p in rows:
        q=number(p['finishedGood']);total=number(p['identifiedDirectCost'])
        components=[]
        for line in p['materialLinesDetail']:
            components.append({**line,'costPerFinished':number(line['recognizedCost'])/q,
                               'usagePerFinished':number(line['netConsumption'])/q})
        components.sort(key=lambda r:r['costPerFinished'],reverse=True)
        item={**p,'materialPerFinished':number(p['materialCost'])/q,'laborPerFinished':number(p['laborCost'])/q,
              'laborShare':number(p['laborCost'])/total if total>0 else None,
              'materialShare':number(p['materialCost'])/total if total>0 else None,'components':components,
              'coverageStatus':'字段通过，实际归集完整性仍待业务确认'}
        out.append(item)
        if p.get('productCode') and p.get('unit'):groups[(p['productCode'],p['unit'],p['product'])].append(p)
    rankings=[]
    for (code,unit,name),ps in groups.items():
        total=sum((number(p['identifiedDirectCost']) for p in ps),Decimal(0))
        good=sum((number(p['finishedGood']) for p in ps),Decimal(0))
        rankings.append({'productCode':code,'product':ps[0]['product'],'names':sorted({p['product'] for p in ps}),
                         'unit':unit,'projects':len(ps),'weightedUnitCost':total/good,
                         'minimum':min(number(p['unitIdentifiedDirectCost']) for p in ps),
                         'maximum':max(number(p['unitIdentifiedDirectCost']) for p in ps)})
    rankings.sort(key=lambda r:(r['unit'],r['productCode'],r['product']))
    # Source amounts can contain transfers between these very projects; do not total chain stages.
    return serial({'eligibleProjects':len(rows),'products':len({(k[0],k[1]) for k in groups}),'rankings':rankings,'projects':out,
                   'limitations':['仅对已关联的领退料与工序累计工资计算可识别直接成本，实际归集完整性未验收',
                                  '不把半成品上游制造成本再加到下游领料成本，避免重复计算',
                                  '缺少原料批次/半成品批次对应及期初在制品，不能证明逐批全链路成本',
                                  '同单位跨产品可查看金额水平，但规格、工艺不同不能直接认定异常；币种待核']})

def main():
    import os,json
    from pathlib import Path
    from .smartbi_store import read,settings
    from .secrets import save_local_secret
    directory=Path(os.environ['LOCALAPPDATA'])/'Cretas/Blacklake'
    analysis=read(settings(directory/'smartbi-test.dpapi'))
    result=analysis['eligibleCostReview'];alerts=analysis['costAlerts']
    out=(directory/'outputs/16-cost-review-20261103').resolve();out.mkdir(parents=True,exist_ok=True)
    save_local_secret(directory/'cost-review-analysis.dpapi',analysis)
    text=['# 六膳门｜16张工单原料到成品成本分析','',f"数据归档：{analysis['runId']}；时点：{analysis['snapshotAt']}。",
          '','金额为来源币种，币种尚未核实。以下仅为可识别材料与人工直接成本，不是完整制造成本或毛利。',
          '16张通过字段校验，不代表实际归集完整；工序报工不重复相加，半成品领料估值不再叠加上游工单成本。',
          '','## 产品成本水平（按单位分组）','','|产品|单位|可核算工单|加权单位成本|工单最低—最高|','|---|---|---:|---:|---:|']
    for p in result['rankings']:
        text.append(f"|{p['product']}（{p['productCode']}）|{p['unit']}|{p['projects']}|{p['weightedUnitCost']:.4f}|{p['minimum']:.4f}—{p['maximum']:.4f}|")
    text+=['','加权口径＝该产品通过校验子集的直接成本合计÷成品良品量合计。不同产品/规格不可直接据排名认定经营异常。',
           '','## 优先核对的异常信号','']
    selected=[a for a in alerts['items'] if a['family']=='business_signal' or a['type'] in ('INPUT_ONLY_CONSUMABLES','LABOR_ZERO_UNVERIFIED','PRODUCT_NAME_CONFLICT')]
    for a in selected:
        text+=[f"### {a['title']}",a['message'],f"工单：{'、'.join(p['projectCode'] for p in a['entityRefs'])}。",f"核对：{a['action']}；责任：{a['owner']}。",'']
    text+=['## 同产品差额分解','']
    for c in analysis['costIntelligence']['comparisons']:
        b=c.get('bridge')
        if b:
            text+=[f"- {c['product']}（{c['productCode']}，{c['unit']}）：{b['comparisonProject']} 对比 {b['baselineProject']}，单位直接成本差额 {b['delta']:.4f}，其中材料 {b['materialDelta']:.4f}、人工 {b['laborDelta']:.4f}。"]
            for m in b['materials']:
                text+=[f"  - {m['material']}：单位成品贡献差额 {m['delta']:.4f}；耗用折算分解 {m['usageEffect']}，价格折算分解 {m['priceEffect']}（未能分解时为None，不代表零）。"]
    text+=['','以上最高/最低来自同产品可核算子集，非时间趋势。材料替换和工艺阶段可能不同，代数分解不能证明浪费或涨价。',
           '','## 16张逐工单明细','','|工单|产品|成品良品|单位|单位材料|单位人工|单位直接成本|人工占比|',
           '|---|---|---:|---|---:|---:|---:|---:|']
    for p in result['projects']:
        share='待核' if p['laborShare'] is None else f"{p['laborShare']:.1%}"
        text.append(f"|{p['projectCode']}|{p['product']}|{p['finishedGood']:.4f}|{p['unit']}|{p['materialPerFinished']:.4f}|{p['laborPerFinished']:.4f}|{p['unitIdentifiedDirectCost']:.4f}|{share}|")
    text+=['','## 每张工单的原料/半成品贡献','']
    for p in result['projects']:
        text+=[f"### {p['projectCode']} · {p['product']}",f"工序累计人工 {p['laborCost']:.4f}；成品良品 {p['finishedGood']:.4f}{p['unit']}。",'']
        for line in p['components']:
            text.append(f"- {line['material']}（{line['materialCode']}），来源{line['orderCode']}：净耗用 {line['netConsumption']:.4f}{line['unit']}，材料净额 {line['recognizedCost']:.4f}；折合每{p['unit']}成品耗用 {line['usagePerFinished']:.6f}{line['unit']}、成本贡献 {line['costPerFinished']:.4f}。")
        text.append('')
    text+=['## 数据质量与本地AI','',f"规则识别 {alerts['summary']['alerts']} 条可重叠提醒，{alerts['summary']['costBlocked']}张暂不能核算。待补资料不被隐藏，作为结构化异常进入SmartBI。",alerts['ai']['message'],'']
    if alerts['ai']['status']=='complete':text.append(f"已运行 {alerts['ai']['host']} / {alerts['ai']['model']}，对每类代表问题选择核对动作与优先级；规则事实、金额和成本门禁不由AI改写。")
    text+=['','## 尚不能证明的全链路','',*['- '+s for s in result['limitations']],
           '- 需要实际原料批次—半成品入库批次—下游领料批次关联、工序规格、期初在制品及历史估值，才能形成逐批全链路成本闭环。']
    target=out/'六膳门-16张工单成本分析.md';target.write_text('\n'.join(text)+'\n',encoding='utf-8')
    (out/'cost-alerts.json').write_text(json.dumps(alerts,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'report':str(target),'projects':result['eligibleProjects'],'products':result['products'],'alerts':alerts['summary'],'local_ai':alerts['ai']['status']},ensure_ascii=False))

if __name__=='__main__':main()
