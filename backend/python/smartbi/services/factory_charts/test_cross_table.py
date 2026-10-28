from types import SimpleNamespace
import pandas as pd
import pytest
from . import cross_table as joined, engine, detail_store, sources, conversation
from .engine import Dataset
from .test_sources import fake_loader


def raw_datasets():
    production = pd.DataFrame([
        {'日期': '2026-01-01', '来源主键': '101', '批次': 'B1', '产品ID': 'P1', '产品': '同名', '单位': 'kg', '产量': 100, '良品数': 90, '不良数': 10, '总成本': 200},
        {'日期': '2026-01-01', '来源主键': '102', '批次': 'B2', '产品ID': 'P2', '产品': '同名', '单位': 'kg', '产量': 50, '良品数': 45, '不良数': 5, '总成本': 100},
    ])
    quality = pd.DataFrame([
        {'日期': '2026-02-01', '生产批次ID': '101', '产品ID': 'P1', '检验数': 40, '合格数': 35, '不良数': 5},
        {'日期': '2026-02-02', '生产批次ID': '101', '产品ID': 'P1', '检验数': 60, '合格数': 55, '不良数': 5},
        {'日期': '2026-02-02', '生产批次ID': '102', '产品ID': 'P2', '检验数': 50, '合格数': 45, '不良数': 5},
    ])
    return Dataset(production, 'production · fixture'), Dataset(quality, 'quality-join · fixture')


def request(product='P1', **changes):
    return sources_request(analysis_subject=engine._subject_id('id', product), **changes)


def sources_request(**changes):
    values = dict(guided=True, analysis_subject=None, period_type='month_range', year=2026,
                  start_month=1, end_month=1, include_optional=True, use_ai=False)
    values.update(changes)
    return SimpleNamespace(**values)


def prepared_dataset(production, quality):
    frame, gaps = joined.prepare_joined(production, quality)
    return Dataset(frame, joined.SOURCE_LABEL) if frame is not None else None, gaps


def scoped(dataset, req):
    return engine.scope_datasets([dataset], req, engine.analysis_subjects([dataset], True), True)[0]


def test_real_joined_candidate_and_details_reconcile_without_name_join_or_multiplication():
    production, quality = raw_datasets()
    dataset, gaps = prepared_dataset(production, quality)
    assert not gaps and len(dataset.frame) == 2
    p1 = dataset.frame.loc[dataset.frame['产品ID'] == 'P1'].iloc[0]
    assert (p1['产量'], p1['总成本'], p1['检验数'], p1['质检记录数']) == (100, 200, 100, 2)
    selected = scoped(dataset, request())
    candidates, gaps = joined.build(selected, request(), True)
    assert not gaps and len(candidates) == 1
    candidate = candidates[0]
    assert candidate['analysis']['familyId'] == joined.FAMILY
    assert candidate['tableData']['rows'] == [['2026-01-01', 100, 90, 90, 200]]
    assert [k['value'] for k in candidate['kpis']] == ['100', '90', '90', '200']
    assert len(candidate['chart']['echartsOption']['yAxis']) == 3
    context, = detail_store._prepared(candidates, selected, request(), ('F006', 1, 'factory_super_admin'))
    snapshot = context[1]
    assert snapshot['source'] == joined.SOURCE_LABEL
    assert len(snapshot['rows']) == 1
    assert snapshot['rows'][0]['产品ID'] == 'P1'
    assert snapshot['rows'][0]['合格数'] == 90
    assert snapshot['rows'][0]['质检记录数'] == 2
    p2, _ = joined.build(scoped(dataset, request('P2')), request('P2'), True)
    assert p2[0]['id'] != candidate['id']


def test_sample_counts_are_not_compared_to_kilograms_and_rates_use_separate_denominators():
    production, quality = raw_datasets()
    quality.frame.loc[quality.frame['产品ID'] == 'P1', ['检验数', '合格数', '不良数']] = [[160, 150, 10], [240, 230, 10]]
    dataset, gaps = prepared_dataset(production, quality)
    assert not gaps
    candidates, _ = joined.build(scoped(dataset, request()), request(), True)
    assert candidates[0]['tableData']['rows'][0] == ['2026-01-01', 100, 95, 90, 200]
    assert candidates[0]['sourceMetadata']['coverage'] == {'totalBatches': 1, 'coveredBatches': 1}


@pytest.mark.parametrize('kind', ['sample-null', 'passed-null', 'failed-null', 'negative-cost', 'missing-unit', 'missing-id', 'bad-date', 'duplicate'])
def test_invalid_record_invalidates_its_batch_without_null_sum_or_fabricated_zero(kind):
    production, quality = raw_datasets()
    if kind.endswith('-null'):
        column = {'sample-null': '检验数', 'passed-null': '合格数', 'failed-null': '不良数'}[kind]
        quality.frame.loc[0, column] = None
    elif kind == 'duplicate':
        production.frame = pd.concat([production.frame, production.frame.iloc[[0]]], ignore_index=True)
    else:
        column, value = {'negative-cost': ('总成本', -1), 'missing-unit': ('单位', None),
                         'missing-id': ('产品ID', None), 'bad-date': ('日期', 'invalid')}[kind]
        production.frame.loc[0, column] = value
    dataset, gaps = prepared_dataset(production, quality)
    assert gaps and dataset is not None
    assert list(dataset.frame['产品ID']) == ['P2']
    assert dataset.frame['产量'].sum() == 50


def test_missing_relationship_is_excluded_and_coverage_denominator_remains_visible():
    production, quality = raw_datasets()
    extra = production.frame.iloc[0].copy()
    extra['来源主键'], extra['批次'] = '103', 'B3'
    production.frame = pd.concat([production.frame, pd.DataFrame([extra])], ignore_index=True)
    dataset, gaps = prepared_dataset(production, quality)
    assert any('缺少同厂同批次同产品ID' in g['missing'][0] for g in gaps)
    candidates, _ = joined.build(scoped(dataset, request()), request(), True)
    assert candidates[0]['sourceMetadata']['coverage'] == {'totalBatches': 2, 'coveredBatches': 1}
    assert '1/2' in candidates[0]['findings']['attention'][0]
    limitations = conversation.limitations_for(candidates, [], '跨表关联生产质量成本')
    assert any('1/2' in item for item in limitations)
    assert any('分母不同' in item for item in limitations)
    assert any('观测次数' in item for item in limitations)


def test_mixed_units_stay_separate_and_unscoped_or_price_denied_cannot_construct_join():
    production, quality = raw_datasets()
    extra = production.frame.iloc[0].copy()
    extra['来源主键'], extra['批次'], extra['单位'] = '103', 'B3', '盒'
    production.frame = pd.concat([production.frame, pd.DataFrame([extra])], ignore_index=True)
    q = quality.frame.iloc[0].copy()
    q['生产批次ID'] = '103'
    quality.frame = pd.concat([quality.frame, pd.DataFrame([q])], ignore_index=True)
    dataset, _ = prepared_dataset(production, quality)
    candidates, _ = joined.build(scoped(dataset, request()), request(), True)
    assert {c['unit'] for c in candidates} == {'kg', '盒'}
    assert all(c['kpis'][0]['value'] == '100' for c in candidates)
    assert joined.build([dataset], sources_request(), True) == ([], [])
    candidates, gaps = joined.build([dataset], request(), False)
    assert not candidates and '权限' in gaps[0]['missing'][0]
    assert not joined.build([production, quality], request(), True)[0]


@pytest.mark.asyncio
async def test_loader_bounds_join_to_authorized_product_and_preserves_later_inspection_date(monkeypatch):
    production, quality = raw_datasets()
    calls = []
    rows = [('settled', 'production_settlement_output_lines', []),
            ('production', 'fact_production_batch', production.frame.to_dict('records')),
            ('join', 'ANY($2::text[])', quality.frame.to_dict('records')),
            ('quality', 'quality_inspections qi', []), ('sales', 'sales_orders so', []),
            ('purchase', 'purchase_receive_records pr', []), ('process', 'fact_production_report', [])]
    pool = await fake_loader(rows, calls)
    monkeypatch.setattr(sources.config, 'get_pg_pool', pool)
    monkeypatch.setattr(sources.config, 'get_cretas_pool', pool)
    datasets, errors, _ = await sources.load('F006', request(), True)
    assert not errors
    join_calls = [c for c in calls if c[0] == 'fetch' and c[1] == 'join']
    assert len(join_calls) == 1 and join_calls[0][3] == ('F006', ['101'])
    assert 'qi.factory_id=$1' in join_calls[0][2] and '$3' not in join_calls[0][2]
    dataset = next(d for d in datasets if d.source.startswith(joined.SOURCE_LABEL))
    assert '最后同步' in dataset.source
    assert set(dataset.frame['产品ID']) == {'P1'}
    assert dataset.frame.iloc[0]['日期'] == '2026-01-01'
    assert dataset.frame.iloc[0]['末次检验日期'] == '2026-02-02'
    charts, _ = engine.build(scoped(dataset, request()), request(), True)
    assert len(charts) == 1 and charts[0]['analysis']['familyId'] == joined.FAMILY
    assert 'qi.inspection_date >= $2' in sources.QUALITY_SQL
    calls.clear()
    await sources.load('F006', sources_request(), True)
    assert not any(c[0] == 'fetch' and c[1] == 'join' for c in calls)
    calls.clear()
    await sources.load('F006', request(), False)
    assert not any(c[0] == 'fetch' and c[1] == 'join' for c in calls)


def test_long_period_join_uses_the_existing_chart_point_safety_limit():
    production, quality = raw_datasets()
    frame, _ = joined.prepare_joined(production, quality)
    one = frame.iloc[0].to_dict()
    rows = [{**one, '日期': day.strftime('%Y-%m-%d'), '来源主键': str(i + 1000), '生产批次ID': str(i + 1000)}
            for i, day in enumerate(pd.date_range('2026-01-01', periods=101))]
    dataset = Dataset(pd.DataFrame(rows), joined.SOURCE_LABEL)
    charts, gaps = engine.build([dataset], request(period_type='all'), True)
    assert not charts
    assert any('100' in message for gap in gaps for message in gap['missing'])
