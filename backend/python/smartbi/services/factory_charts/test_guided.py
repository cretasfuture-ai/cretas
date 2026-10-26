from types import SimpleNamespace

import pandas as pd
import pytest
from httpx import ASGITransport, AsyncClient

from smartbi.api import financial_dashboard as api
from smartbi.services.factory_charts.engine import (
    Dataset, analysis_subjects, build, scope_datasets,
)
from smartbi.services.factory_charts import detail_store
from smartbi.services.factory_charts import details
from smartbi.services.factory_charts.test_detail_api import app_for


def req(**values):
    defaults = dict(guided=True, analysis_subject=None, include_optional=True,
                    period_type='all', year=2026, start_month=1, end_month=12)
    defaults.update(values)
    return SimpleNamespace(**defaults)


def product_rows(products=100, batches=5):
    return [
        {'日期': f'2026-01-{b + 1:02d}', '产品': f'产品-{i}',
         '单位': 'kg', '产量': 10 + b, '批次': f'B-{i}-{b}',
         '良品数': 10 + b,
         '总成本': 20 + b, '材料成本': 10 + b, '人工成本': 5,
         '设备成本': 3, '其他成本': 2}
        for i in range(products) for b in range(batches)
    ]


def test_guided_catalog_is_opaque_and_does_not_construct_product_specials(monkeypatch):
    from smartbi.services.factory_charts import engine
    constructed = []
    original = engine.series_candidate
    def observe(series, *args, **kwargs):
        constructed.append(series.key)
        return original(series, *args, **kwargs)
    monkeypatch.setattr(engine, 'series_candidate', observe)
    dataset = Dataset(pd.DataFrame(product_rows()), 'production · trusted', currency='CNY')
    subjects = analysis_subjects([dataset], True)
    assert len(subjects) == 100
    assert subjects[0]['id'].startswith('product-')
    assert all('产品-' not in item['id'] for item in subjects)
    items, _ = build([dataset], req(), True)
    assert items and any('production.products' in item['id'] for item in items)
    assert not any(key.startswith(('cost.dual', 'cost.distribution', 'cost.scatter',
                                   'production.fused', 'production.anomaly')) for key in constructed)
    assert not any(any(prefix in item['id'] for prefix in
                       ('cost.dual', 'cost.distribution', 'cost.scatter',
                        'production.fused', 'production.anomaly')) for item in items)


def test_guided_subject_filters_exact_product_and_excludes_unscopable_sources():
    dataset = Dataset(pd.DataFrame(product_rows(2)), 'production · trusted', currency='CNY')
    oee = Dataset(pd.DataFrame([{'产品': '产品-0', '产量': 99}]), 'oee · all factory')
    cash = Dataset(pd.DataFrame([{'产品': '产品-0', '销售金额': 100}]), 'allfactorycash · all factory', financial=True)
    unscopable = Dataset(pd.DataFrame([{'日期': '2026-01-01', '销售金额': 99999}]), 'unidentified · 全厂收入')
    subjects = analysis_subjects([dataset, oee, cash, unscopable], True)
    selected = next(item for item in subjects if item['label'] == '产品-0')
    scoped, gap, label = scope_datasets([dataset, oee, cash, unscopable], req(analysis_subject=selected['id']), subjects, True)
    assert [d.source for d in scoped] == ['production · trusted']
    assert scoped[0].frame['产品'].tolist() == ['产品-0'] * 5
    assert '无法按产品限定' in gap['missing'][0]
    assert label == '已选择分析主题：产品-0'


def test_guided_unknown_subject_fails_closed_and_legacy_keeps_all_data():
    dataset = Dataset(pd.DataFrame(product_rows(2)), 'production · trusted', currency='CNY')
    subjects = analysis_subjects([dataset], True)
    scoped, gap, label = scope_datasets([dataset], req(analysis_subject='product-unknown'), subjects, True)
    assert scoped == [] and gap['id'] == 'analysis.subject'
    assert label == '所选产品当前无数据'
    legacy, legacy_gap = build([dataset], req(guided=False), True)
    assert legacy and any('cost.distribution' in item['id'] for item in legacy)


def test_guided_subject_missing_in_period_never_uses_factory_totals():
    dataset = Dataset(pd.DataFrame(product_rows(2)), 'upload-5259 · 本厂上传报表', currency='CNY')
    subjects = analysis_subjects([dataset], True)
    selected = subjects[0]
    request = req(analysis_subject=selected['id'], period_type='month_range', start_month=2, end_month=2)
    scoped, _, _ = scope_datasets([dataset], request, subjects, True)
    items, gaps = build(scoped, request, True)
    assert not items
    assert any('所选期间没有记录' in gap['missing'] for gap in gaps)
    assert request.analysis_subject == selected['id']


def test_subject_catalog_excludes_financial_data_without_permission():
    financial = Dataset(pd.DataFrame([{'产品': '受限产品', '销售金额': 10}]), 'finance · private', financial=True)
    assert analysis_subjects([financial], False) == []


def test_product_id_is_identity_and_same_name_skus_do_not_merge():
    source = Dataset(pd.DataFrame([
        {'产品': '同名产品', '产品ID': 'sku-1', '单位': 'kg', '产量': 1},
        {'产品': '同名产品', '产品ID': 'sku-2', '单位': 'kg', '产量': 2},
    ]), 'production · trusted')
    subjects = analysis_subjects([source], True)
    assert len(subjects) == 2 and len({item['id'] for item in subjects}) == 2
    scoped_ids = []
    for selected in subjects:
        scoped, _, _ = scope_datasets([source], req(analysis_subject=selected['id']), subjects, True)
        scoped_ids.append(scoped[0].frame['产品ID'].tolist())
    assert sorted(scoped_ids) == [['sku-1'], ['sku-2']]


def test_name_only_source_is_excluded_when_name_is_ambiguous():
    identified = Dataset(pd.DataFrame([{'产品': '同名产品', '产品ID': 'sku-1', '产量': 1}]), 'production · identified')
    identified_two = Dataset(pd.DataFrame([{'产品': '同名产品', '产品ID': 'sku-2', '产量': 2}]), 'sales · identified')
    name_only = Dataset(pd.DataFrame([{'产品': '同名产品', '产量': 99}]), 'upload · name-only')
    subjects = analysis_subjects([identified, identified_two, name_only], True)
    selected = subjects[0]
    scoped, _, _ = scope_datasets([identified, identified_two, name_only], req(analysis_subject=selected['id']), subjects, True)
    assert all(dataset.source != 'upload · name-only' for dataset in scoped)


def test_name_only_source_aliases_unique_canonical_id():
    identified = Dataset(pd.DataFrame([{'产品': '唯一产品', '产品ID': 'sku-1', '产量': 1}]), 'production · identified')
    name_only = Dataset(pd.DataFrame([{'产品': '唯一产品', '产量': 99}]), 'upload · name-only')
    subjects = analysis_subjects([identified, name_only], True)
    assert len(subjects) == 1
    scoped, _, _ = scope_datasets([identified, name_only], req(analysis_subject=subjects[0]['id']), subjects, True)
    assert {dataset.source for dataset in scoped} == {'production · identified', 'upload · name-only'}


def test_detail_registration_receives_filtered_dataset_only():
    dataset = Dataset(pd.DataFrame(product_rows(2)), 'production · trusted')
    subjects = analysis_subjects([dataset], True)
    selected = next(item for item in subjects if item['label'] == '产品-1')
    scoped, _, _ = scope_datasets([dataset], req(analysis_subject=selected['id']), subjects, True)
    candidates = [{'id': 'production.products:bar_horizontal', 'title': '产品产量',
                   'source': scoped[0].source, 'unit': 'kg'}]
    prepared = list(detail_store._prepared(candidates, scoped, req(), ('F006', 1, 'ADMIN')))
    assert prepared and prepared[0][1] is not None
    assert all(row.get('产品') == '产品-1' for row in prepared[0][1]['rows'])


@pytest.mark.asyncio
async def test_route_echoes_subject_and_detail_uses_filtered_rows(monkeypatch):
    original = pd.DataFrame([
        {'日期': '2026-01-01', '产品': '同名产品', '产品ID': 'sku-1', '单位': 'kg', '产量': 1},
        {'日期': '2026-01-02', '产品': '同名产品', '产品ID': 'sku-1', '单位': 'kg', '产量': 2},
        {'日期': '2026-01-01', '产品': '同名产品', '产品ID': 'sku-2', '单位': 'kg', '产量': 99},
        {'日期': '2026-01-02', '产品': '同名产品', '产品ID': 'sku-2', '单位': 'kg', '产量': 100},
    ])
    async def load(*args):
        return original.copy()
    async def register(*args):
        return details.register(*args)
    async def page(*args):
        return details.page(*args)
    async def requires(*args):
        return details.requires_finance(*args)
    monkeypatch.setattr(api, '_load_data', load)
    monkeypatch.setattr(detail_store, 'register_shared', register)
    monkeypatch.setattr(detail_store, 'page_shared', page)
    monkeypatch.setattr(detail_store, 'requires_finance_shared', requires)
    details.clear_cache()
    subject = analysis_subjects([Dataset(original, 'upload-42 · 本厂上传报表')], True)[0]
    async with AsyncClient(transport=ASGITransport(app=app_for()), base_url='http://test') as client:
        response = await client.post('/factory-recommendations', json={
            'source': 'upload', 'upload_id': 42, 'use_ai': False,
            'guided': True, 'analysis_subject': subject['id'],
        })
        assert response.status_code == 200
        data = response.json()['data']
        assert data['analysisSubject'] == subject['id']
        card = next(item for item in data['recommendations'] if 'production.output' in item['id'])
        drilled = await client.post('/factory-details', json={'context_id': card['detailContext']['id'], 'page_size': 20})
        assert drilled.status_code == 200
        assert {row['产品ID'] for row in drilled.json()['data']['rows']} == {'sku-1'}
    details.clear_cache()
