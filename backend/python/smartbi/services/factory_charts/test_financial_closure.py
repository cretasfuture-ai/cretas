from types import SimpleNamespace
import pandas as pd
import pytest
from .engine import Dataset, build, factory_candidates
from .financial_addons import build as additions
from .selection_groups import default_selection
from smartbi.api.financial_dashboard import FactoryRecommendationRequest
from smartbi.services.financial.category_yoy import CategoryYoyComparisonBuilder


def request(**kw):
    return FactoryRecommendationRequest(use_ai=False,**kw)


@pytest.mark.parametrize('column,sheet', [('总成本','成本表（万元）'),('总成本（万元）','成本表（万元）')])
def test_factory_monetary_sheet_scale_shared_without_double_multiplication(column,sheet):
    frame=pd.DataFrame([{'日期':'2026-01-01','产品':'A','单位':'kg','产量':100,column:2,'销售金额':10,'客户':'甲'},
                        {'日期':'2026-02-01','产品':'B','单位':'kg','产量':300,column:3,'销售金额':50,'客户':'乙'}])
    charts,_=factory_candidates(Dataset(frame,'fixture',sheet_name=sheet),request(),True)
    cost=next(c for c in charts if c['id'].startswith('cost.unit'))
    assert cost['kpis'][0]['value']=='125' and cost['unit']=='元/kg'
    customer=next(c for c in charts if c['id'].startswith('sales.customer'))
    assert customer['kpis'][0]['value']=='600,000'
    assert {r['label']:r['value'] for r in customer['tableData']}=={'甲':100000,'乙':500000}
    assert customer['chart']['echartsOption']['series'][0]['data']==[500000,100000]


def test_financial_sheet_scale_budget_axis_table_and_weighted_margin_kpi():
    frame=pd.DataFrame([{'日期':'2026-01-20','现金流活动':'经营活动','现金流金额':100,'科目':'销售费用','实际':12,'预算':10,'收入':100,'成本':90,'品类':'甲'},
                        {'日期':'2026-03-01','现金流活动':'投资活动','现金流金额':-20,'科目':'销售费用','实际':8,'预算':10,'收入':900,'成本':450,'品类':'乙'}])
    charts,_=additions(Dataset(frame,'fixture',sheet_name='利润现金表（万元）'),request(period_type='month_range',start_month=1,end_month=3),True)
    cash=next(c for c in charts if c['id']=='finance.cash_flow_waterfall')
    assert cash['kpis'][-1]['value']=='800,000.00'
    budget=next(c for c in charts if c['id']=='finance.budget.费用')
    assert budget['chart']['echartsOption']['series'][0]['data']==[120000,None,80000]
    assert budget['tableData']['rows']==[['2026-01',120000,100000,120],['2026-02',None,None,None],['2026-03',80000,100000,80]]
    assert len(budget['chart']['echartsOption']['yAxis'])==2
    assert 'max' not in budget['chart']['echartsOption']['yAxis'][1]
    rate=next(c for c in charts if c['id']=='finance.margin.category.rate')
    assert rate['kpis'][0]['value']=='46.00'
    assert rate['findings']['attention'][0].startswith('加权毛利率 46.00%')
    assert rate['chart']['echartsOption']['xAxis']['type']=='value'


def test_cash_trend_canonical_months_and_missing_periods():
    frame=pd.DataFrame([{'日期':'2026/2','现金流活动':'operating','现金流金额':100,'币种':'CNY'},
                        {'日期':'2026-04-20','现金流活动':'投资活动','现金流金额':-40,'币种':'CNY'}])
    charts,_=additions(Dataset(frame,'fixture'),request(),True)
    trend=next(c for c in charts if c['id']=='finance.cashflow_trend')
    assert trend['chart']['echartsOption']['xAxis']['data']==['2026-02','2026-03','2026-04']
    assert trend['chart']['echartsOption']['series'][0]['data']==[100,None,None]
    assert trend['tableData']['rows'][1]==['2026-03',None,None,None]


@pytest.mark.parametrize('prior,expected',[(0,'flat'),(100,'down')])
def test_category_comparison_no_false_growth_and_both_period_legends(prior,expected):
    frame=pd.DataFrame([{'month':1,'category':'甲','actual':90,'last_year':prior},
                        {'month':1,'category':'乙','actual':80,'last_year':prior}])
    chart=CategoryYoyComparisonBuilder().build(frame,SimpleNamespace(),{'start_month':1,'end_month':2},2026)
    kpi=next(k for k in chart['kpis'] if k['label']=='同比表现最高品类')
    assert kpi['trend']==expected
    if prior==0: assert kpi['value']=='-'
    option=chart['echartsOption']
    assert {s['name'] for s in option['series']}==set(option['legend']['data'])


def test_default_selection_does_not_repeat_same_metric_view():
    ids=['upload.production.output@kg:line','upload.production.output@kg:area','upload.finance.budget_achievement',
         'upload.finance.variance_analysis','upload.cost.structure:donut','upload.cost.structure:pareto','upload.quality.pass:bar']
    assert default_selection([{'id':i} for i in ids])==[ids[0],ids[2],ids[4],ids[6]]


def test_historical_balances_do_not_sum_months():
    rows=[{'日期':'2026-01-31','原料':'A','单位':'kg','库存数量':100},
          {'日期':'2026-02-28','原料':'A','单位':'kg','库存数量':80}]
    charts,_=build([Dataset(pd.DataFrame(rows),'historical-inventory · 已关账')],request(),False)
    assert len(charts)==1 and charts[0]['kpis']==[{'label':'最后关账余额','value':'80','unit':'kg'}]
    assert not any('180' in text for text in charts[0]['findings']['attention'])
