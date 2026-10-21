"""财务看板口径回归测试 (2026-10-21, 三轮 Codex 审查复现)。

每个用例对应一条已复现的错算 / 错显, 断言读的是用户看得见的 KPI 与明细表,
而不是实现细节: 修复必须让页面上的数字变对, 不只是让某个内部字段变对。

Usage:
    cd backend/python
    python -m pytest smartbi/tests/test_financial_dashboard_correctness.py -v
"""
import io
import os
import re
import sys
from typing import Any, Dict, List, Optional

import pandas as pd
import pytest

_project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
if _project_root not in sys.path:
    sys.path.insert(0, _project_root)

from smartbi.services.financial_dashboard import FinancialDashboardService  # noqa: E402
from smartbi.services.financial.registry import registry  # noqa: E402

svc = FinancialDashboardService()


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def build(chart_type: str, rows: List[Dict[str, Any]], start: int = 1, end: int = 1,
          period_type: str = 'month_range', year: int = 2026) -> Dict[str, Any]:
    return svc.generate_chart(chart_type, pd.DataFrame(rows), year, period_type, start, end)


_SCALE = {'亿': 1e8, '万': 1e4}


def to_yuan(text: Any) -> Optional[float]:
    """把 KPI 显示值 ("1,100.00" / "10.00万" / "-") 还原成元; 无值返回 None。"""
    if text is None:
        return None
    s = str(text).strip()
    if s in ('', '-', '—'):
        return None
    m = re.fullmatch(r'([+-]?[\d,]+(?:\.\d+)?)\s*(亿|万)?', s)
    assert m, f'KPI 值不是数字: {text!r}'
    return float(m.group(1).replace(',', '')) * _SCALE.get(m.group(2) or '', 1)


def kpi(result: Dict[str, Any], label: str) -> Dict[str, Any]:
    for k in result.get('kpis') or []:
        if k.get('label') == label:
            return k
    raise AssertionError(f'没有 KPI {label!r}; 实际: {[k.get("label") for k in result.get("kpis") or []]}')


def table_row(result: Dict[str, Any], label: str) -> List[Any]:
    for row in (result.get('tableData') or {}).get('rows') or []:
        if isinstance(row, dict) and row.get('label') == label:
            return row['values']
    raise AssertionError(f'明细表没有 {label!r} 行')


# ---------------------------------------------------------------------------
# 第一轮
# ---------------------------------------------------------------------------
class TestChannelNeverFabricated:
    def test_no_channel_column_reports_missing_instead_of_inventing_channels(self):
        r = build('channel_analysis', [{'月份': '1月', '品类': '酱料', '实际': 100}])
        labels = {str(k.get('value')) for k in r.get('kpis') or []}
        assert not labels & {'直营', '经销', '电商'}, f'缺渠道列时编造了渠道: {r.get("kpis")}'
        assert r['success'] is False
        assert r['metadata']['dataQuality'] == 'insufficient'
        assert '渠道' in r['analysisContext']

    def test_real_channel_column_is_summed_exactly(self):
        r = build('channel_analysis', [
            {'月份': '1月', '渠道': '商超', '实际': 300},
            {'月份': '1月', '渠道': '电商', '实际': 100},
        ])
        assert r['success'] is True
        assert to_yuan(kpi(r, '总收入')['value']) == 400
        assert kpi(r, '最大渠道')['value'] == '商超'


class TestGrossMarginWeighting:
    def test_cumulative_margin_is_total_profit_over_total_revenue(self):
        # 1 月 收入 100 成本 50 (50%), 2 月 收入 900 成本 810 (10%)
        # 正确累计 = (1000-860)/1000 = 14%; 简单平均会给 30%
        r = build('gross_margin_trend', [
            {'月份': '1月', '项目': '营业收入', '实际': 100, '上年': 100},
            {'月份': '1月', '项目': '营业成本', '实际': 50, '上年': 60},
            {'月份': '2月', '项目': '营业收入', '实际': 900, '上年': 100},
            {'月份': '2月', '项目': '营业成本', '实际': 810, '上年': 60},
        ], 1, 2)
        assert float(kpi(r, '本年累计毛利率')['value']) == pytest.approx(14.0, abs=0.05)


class TestYearIsolation:
    def test_single_year_excludes_other_years(self):
        rows = [
            {'月份': '2025-01', '预算': 500, '实际': 400},
            {'月份': '2026-01', '预算': 200, '实际': 100},
        ]
        r = build('budget_achievement', rows, 1, 12, period_type='year', year=2026)
        assert to_yuan(kpi(r, '年度实际')['value']) == 100
        assert to_yuan(kpi(r, '年度目标')['value']) == 200

    def test_single_month_only_that_month(self):
        rows = [
            {'月份': '1月', '预算': 100, '实际': 90},
            {'月份': '3月', '预算': 300, '实际': 330},
        ]
        r = build('budget_achievement', rows, 3, 3, period_type='month')
        assert to_yuan(kpi(r, '年度实际')['value']) == 330


# ---------------------------------------------------------------------------
# 第二轮
# ---------------------------------------------------------------------------
PNL_REFERENCE = [
    ('营业收入', 8333), ('营业成本', 1471), ('销售费用', 540), ('管理费用', 329),
    ('研发费用', 999), ('财务费用', 604), ('其他收益', 65), ('营业外收入', 42),
    ('营业外支出', 86), ('所得税', 1103), ('净利润', 3308),
]


class TestPnlWaterfall:
    def test_net_profit_includes_other_gains_and_non_operating_items(self):
        r = build('pnl_waterfall', [{'月份': '1月', '项目': k, '实际': v} for k, v in PNL_REFERENCE])
        assert to_yuan(kpi(r, '净利润')['value']) == pytest.approx(3308)
        assert not r['metadata'].get('reconciliation'), r['metadata'].get('reconciliation')

    def test_reported_net_profit_mismatch_is_surfaced(self):
        items = [(k, 3300 if k == '净利润' else v) for k, v in PNL_REFERENCE]
        r = build('pnl_waterfall', [{'月份': '1月', '项目': k, '实际': v} for k, v in items])
        notes = r['metadata'].get('reconciliation') or []
        assert any('净利润' in n and '8' in n for n in notes), notes

    def test_subtotal_and_its_details_are_not_double_counted(self):
        r = build('pnl_waterfall', [{'月份': '1月', '项目': k, '实际': v} for k, v in [
            ('营业收入', 1000), ('主营业务收入', 800), ('其他业务收入', 200), ('营业成本', 400)]])
        assert to_yuan(kpi(r, '营业收入')['value']) == 1000

    def test_negative_finance_expense_reduces_expenses(self):
        # 财务费用为负 = 利息收入大于支出, 应增加利润
        r = build('pnl_waterfall', [{'月份': '1月', '项目': k, '实际': v} for k, v in [
            ('营业收入', 1000), ('营业成本', 400), ('管理费用', 100), ('财务费用', -20)]])
        assert to_yuan(kpi(r, '净利润')['value']) == pytest.approx(520)


class TestPerformanceChartsUseRevenueOnly:
    """业绩类图表 (预算完成 / 方差 / 目标达成): 损益表里只取收入, 不把成本费用一起加进「实际」。"""

    PNL_MONTHLY = [
        {'月份': '1月', '项目': '营业收入', '预算': 1000, '实际': 900, '上年': 800},
        {'月份': '1月', '项目': '营业成本', '预算': 600, '实际': 550, '上年': 500},
        {'月份': '1月', '项目': '销售费用', '预算': 100, '实际': 120, '上年': 90},
        {'月份': '3月', '项目': '营业收入', '预算': 1200, '实际': 1300, '上年': 1000},
        {'月份': '3月', '项目': '营业成本', '预算': 700, '实际': 760, '上年': 600},
    ]

    def test_budget_achievement_counts_revenue_only(self):
        r = build('budget_achievement', self.PNL_MONTHLY, 1, 3)
        assert to_yuan(kpi(r, '年度实际')['value']) == 2200
        assert to_yuan(kpi(r, '年度目标')['value']) == 2200
        assert float(kpi(r, '年度达成率')['value']) == pytest.approx(100.0)

    def test_budget_achievement_missing_month_is_empty(self):
        r = build('budget_achievement', self.PNL_MONTHLY, 1, 3)
        assert table_row(r, '实际')[1] is None
        assert table_row(r, '达成率(%)')[1] is None

    def test_variance_analysis_counts_revenue_only(self):
        r = build('variance_analysis', self.PNL_MONTHLY, 1, 3)
        assert to_yuan(kpi(r, '总实际')['value']) == 2200


class TestExpenseChart:
    def test_expense_total_excludes_revenue(self):
        r = build('expense_yoy_budget', [
            {'月份': '1月', '项目': '营业收入', '预算': 1000, '实际': 1000, '上年': 900},
            {'月份': '1月', '项目': '销售费用', '预算': 100, '实际': 100, '上年': 90},
        ])
        assert to_yuan(kpi(r, '本年费用合计')['value']) == 100

    def test_rate_axis_fits_large_overruns(self):
        r = build('expense_yoy_budget', [
            {'月份': '1月', '项目': '销售费用', '预算': 100, '实际': 277.73, '上年': 90},
        ])
        axes = r['echartsOption']['yAxis']
        rate_axis = next(a for a in axes if '%' in str(a.get('name', '')))
        assert rate_axis.get('max') is None or rate_axis['max'] >= 277.73


class TestMissingVersusZero:
    def test_missing_month_is_no_data_not_minus_100_percent(self):
        r = build('yoy_mom_comparison', [
            {'月份': '1月', '实际': 100, '上年': 0},
            {'月份': '3月', '实际': 200, '上年': 100},
        ], 1, 3)
        assert table_row(r, '本年')[1] is None, '没上传的 2 月不能显示成 0'
        assert table_row(r, '环比(%)') == [None, None, None]
        # 上年 1 月是真实的 0: 增长率无法计算, 不能显示 0%
        assert table_row(r, '同比(%)')[0] is None


class TestSuccessReflectsChartState:
    def test_caught_builder_error_is_not_counted_as_success(self):
        from smartbi.services.financial_data_normalizer import ColumnMapping
        # 有渠道列但缺「实际」列: 构建器内部报错并自己捕获, 返回 dataQuality=error
        r = registry._safe_build(registry.get('channel_analysis'), pd.DataFrame({'channel': ['商超']}),
                                 ColumnMapping(channel_col='渠道'), {'start_month': 1, 'end_month': 1}, 2026)
        assert r['metadata']['dataQuality'] == 'error'
        assert r['success'] is False

    def test_dashboard_success_count_excludes_insufficient_charts(self):
        r = svc.generate_dashboard(pd.DataFrame([{'月份': '1月', '品类': '酱料', '实际': 100}]),
                                   2026, 'month_range', 1, 1)
        ok = [c for c in r['charts'] if c.get('success')]
        assert all(c.get('metadata', {}).get('dataQuality') not in ('error', 'insufficient') for c in ok)
        assert r['successCount'] == len(ok)


# ---------------------------------------------------------------------------
# 第三轮
# ---------------------------------------------------------------------------
class TestInputParsing:
    def test_unit_in_column_header_is_applied(self):
        r = build('budget_achievement', [{'月份': '1月', '预算（万元）': 20, '实际（万元）': 10}])
        assert to_yuan(kpi(r, '年度实际')['value']) == 100000
        assert to_yuan(kpi(r, '年度目标')['value']) == 200000

    def test_iso_day_dates_map_to_their_month(self):
        r = build('budget_achievement', [{'日期': '2026-01-15', '预算': 200, '实际': 100}])
        assert to_yuan(kpi(r, '年度实际')['value']) == 100

    def test_percentage_strings_are_numbers(self):
        r = build('gross_margin_trend', [{'月份': '1月', '本年毛利率': '25%', '上年毛利率': '20%'}])
        assert r['metadata']['dataQuality'] != 'insufficient', r['analysisContext']
        assert float(kpi(r, '平均差异')['value']) == pytest.approx(5.0)


class TestProductRanking:
    ROWS = [
        {'月份': '1月', '品类': '酱料', '产品名称': '产品A', '实际': 100},
        {'月份': '1月', '品类': '酱料', '产品名称': '产品B', '实际': 200},
        {'月份': '1月', '品类': '退货品类', '产品名称': '产品C', '实际': -50},
    ]

    def test_ranks_individual_products_not_categories(self):
        r = build('product_ranking', self.ROWS)
        assert kpi(r, 'Top 1 产品')['value'] == '产品B'
        assert kpi(r, '产品总数')['value'] == '3'

    def test_negative_revenue_is_kept_and_net_total_is_used(self):
        r = build('product_ranking', self.ROWS)
        rows = r['tableData']['rows']
        names = [row['label'] if isinstance(row, dict) else row[1] for row in rows]
        assert '产品C' in names, '退货产品不能从排名里消失'
        assert r['metadata']['netRevenue'] == pytest.approx(250)


class TestNoSynthesizedCharts:
    """同因扫描: 缺数据时按预设比例拆分 / 随机波动「造」出来的图, 一律改为明确提示缺什么。"""

    SALES_ONLY = [
        {'月份': f'{m}月', '预算': 100 * m, '实际': 90 * m, '上年': 80 * m} for m in range(1, 7)
    ]
    SYNTH_PRONE = ('ar_aging', 'cashflow_trend', 'cash_flow_waterfall', 'hr_cost_analysis')

    def test_sales_sheet_does_not_produce_invented_cash_ar_or_hr_charts(self):
        r = svc.generate_dashboard(pd.DataFrame(self.SALES_ONLY), 2026, 'month_range', 1, 6)
        for c in r['charts']:
            if c['chartType'] in self.SYNTH_PRONE:
                assert c['success'] is False, f"{c['chartType']} 在没有对应数据时生成了图表: {c.get('kpis')}"
                assert c['metadata'].get('dataQuality') in ('insufficient', 'error')
                assert c.get('analysisContext'), '必须告诉用户缺什么'

    def test_same_input_gives_same_numbers(self):
        a = svc.generate_dashboard(pd.DataFrame(self.SALES_ONLY), 2026, 'month_range', 1, 6)
        b = svc.generate_dashboard(pd.DataFrame(self.SALES_ONLY), 2026, 'month_range', 1, 6)
        ka = {c['chartType']: c.get('kpis') for c in a['charts']}
        kb = {c['chartType']: c.get('kpis') for c in b['charts']}
        assert ka == kb, '同一份数据两次生成的数字不同 (含随机数)'

    def test_real_aging_columns_are_used(self):
        r = build('ar_aging', [
            {'客户': '甲', '实际': 600, '0-30天': 300, '31-60天': 200, '61-90天': 100},
        ])
        assert r['success'] is True
        assert r['metadata']['totalAR'] == pytest.approx(600)

    def test_real_cash_flow_items_are_used(self):
        r = build('cashflow_trend', [
            {'月份': '1月', '项目': '经营活动现金流量净额', '实际': 500},
            {'月份': '1月', '项目': '投资活动现金流量净额', '实际': -200},
            {'月份': '1月', '项目': '筹资活动现金流量净额', '实际': 50},
        ])
        assert r['success'] is True
        assert r['metadata']['dataQuality'] == 'good'
        headers = r['tableData']['headers']
        net_col = next(i for i, h in enumerate(headers) if str(h).startswith('净'))
        january = r['tableData']['rows'][0]
        assert to_yuan(january[net_col]) == pytest.approx(350)


# ---------------------------------------------------------------------------
# 导出
# ---------------------------------------------------------------------------
def _export_client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from smartbi.api import financial_dashboard as api

    app = FastAPI()
    app.include_router(api.router)
    return TestClient(app)


def _sheet_cells(content: bytes) -> List[List[Any]]:
    import openpyxl
    wb = openpyxl.load_workbook(io.BytesIO(content))
    cells = []
    for ws in wb:
        cells.extend([list(r) for r in ws.values])
    return cells


class TestExcelExport:
    def test_scaled_table_states_its_unit(self):
        chart = build('budget_achievement', [{'月份': '1月', '预算': 20000, '实际': 10000}])
        resp = _export_client().post('/export-excel', json={'charts': [chart]})
        assert resp.status_code == 200
        flat = ' '.join(str(c) for row in _sheet_cells(resp.content) for c in row if c is not None)
        assert '万元' in flat, '按万显示的明细必须写明单位'

    def test_product_ranking_table_exports(self):
        chart = build('product_ranking', [
            {'月份': '1月', '品类': '酱料', '产品名称': '产品A', '实际': 100},
            {'月份': '1月', '品类': '汤料', '产品名称': '产品B', '实际': 200},
        ])
        resp = _export_client().post('/export-excel', json={'charts': [chart]})
        assert resp.status_code == 200
        assert 'spreadsheetml' in resp.headers.get('content-type', '')
        flat = [c for row in _sheet_cells(resp.content) for c in row]
        assert '产品A' in flat

    def test_export_failure_is_not_http_200(self, monkeypatch):
        import openpyxl

        def boom(self, *a, **k):
            raise RuntimeError('disk full')

        monkeypatch.setattr(openpyxl.Workbook, 'save', boom)
        resp = _export_client().post('/export-excel', json={'charts': []})
        assert resp.status_code >= 500
        assert resp.json()['success'] is False


# ---------------------------------------------------------------------------
# 上传读取 + 租户
# ---------------------------------------------------------------------------
UPLOADED_ROWS = {
    # (upload_id, factory_id) -> rows as stored by the uploader (one row_data per Excel row)
    (5245, 'F006'): [
        {'月份': '1月', '预算': 200, '实际': 180, '上年': 150},
        {'月份': '2月', '预算': 220, '实际': 240, '上年': 160},
    ],
}


class _FakeConn:
    def __init__(self, calls):
        self.calls = calls

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        assert 'row_data' in sql and 'upload_id' in sql, sql
        upload_id, factory_id = args[0], args[1]
        return [{'row_data': r} for r in UPLOADED_ROWS.get((upload_id, factory_id), [])]


class _FakePool:
    def __init__(self):
        self.calls = []

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                return _FakeConn(pool.calls)

            async def __aexit__(self, *exc):
                return False
        return _Ctx()


def _tenant_client(monkeypatch, token_factory: Optional[str]):
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient
    from smartbi.api import financial_dashboard as api

    pool = _FakePool()

    async def fake_get_pool():
        return pool

    monkeypatch.setattr('smartbi.config.get_pg_pool', fake_get_pool)
    app = FastAPI()

    @app.middleware('http')
    async def inject_jwt_claims(request: Request, call_next):
        # 模拟 JWTAuthMiddleware: factory_id 来自登录身份
        request.state.factory_id = token_factory
        request.state.auth_method = 'jwt'
        return await call_next(request)

    app.include_router(api.router)
    return TestClient(app), pool


class TestUploadLoading:
    def test_uploaded_rows_are_read_by_upload_id_for_token_factory(self, monkeypatch):
        client, pool = _tenant_client(monkeypatch, 'F006')
        resp = client.post('/batch', json={'upload_id': 5245, 'year': 2026, 'period_type': 'month_range',
                                           'start_month': 1, 'end_month': 2})
        body = resp.json()
        assert resp.status_code == 200, body
        assert body['success'] is True
        budget = next(c for c in body['charts'] if c['chartType'] == 'budget_achievement')
        assert to_yuan(kpi(budget, '年度实际')['value']) == 420
        assert pool.calls and pool.calls[0][1][:2] == (5245, 'F006')

    def test_other_factory_cannot_read_the_upload(self, monkeypatch):
        client, _ = _tenant_client(monkeypatch, 'F001')
        resp = client.post('/batch', json={'upload_id': 5245})
        assert resp.status_code == 404
        assert resp.json()['success'] is False

    def test_body_factory_id_cannot_override_token(self, monkeypatch):
        client, pool = _tenant_client(monkeypatch, 'F001')
        resp = client.post('/batch', json={'upload_id': 5245, 'factory_id': 'F006'})
        assert resp.status_code == 403
        assert not pool.calls, '越权请求不应触发查询'
