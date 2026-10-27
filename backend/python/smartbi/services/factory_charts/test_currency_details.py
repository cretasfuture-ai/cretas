"""Reconciled money rows retain physical units without weakening unit/ACL guards."""
import copy
from types import SimpleNamespace

import pandas as pd
import pytest

from . import detail_store, details, engine
from .engine import Dataset

SOURCE = 'sales-sku · 销售订单行 · 逐单对账'
OWNER = ('F006', 12, 'factory_super_admin')
REQUEST = SimpleNamespace(guided=True, analysis_subject=None, include_optional=True,
                          period_type='all', year=2026, start_month=1, end_month=12)


def fixture():
    frame = pd.DataFrame([
        {'日期': '2026-10-01', '产品ID': 'sku-1', '产品': '同一SKU', '单位': 'kg',
         '数量': 2, '订单行ID': 'L1', '订单ID': 'O1', '订单行小计': 10, '订单总额': 10},
        {'日期': '2026-10-02', '产品ID': 'sku-1', '产品': '同一SKU', '单位': '盒',
         '数量': 20, '订单行ID': 'L2', '订单ID': 'O2', '订单行小计': 30, '订单总额': 30},
    ])
    dataset = Dataset(frame, SOURCE, currency='CNY')
    candidates, _ = engine.build([dataset], REQUEST, True)
    item = next(c for c in candidates if c['analysis']['familyId'] == 'sales-sku.sales.sku')
    return item, dataset


@pytest.fixture(autouse=True)
def clear_contexts():
    details.clear_cache()
    yield
    details.clear_cache()


def test_reconciled_sku_money_context_retains_each_original_unit_and_acl():
    item, dataset = fixture()
    registered = details.register([item], [dataset], REQUEST, OWNER, True)[0]
    context = registered['detailContext']['id']
    page = details.page({'context_id': context, 'page': 1, 'page_size': 10}, OWNER, True, True)['data']
    assert page['total'] == 2
    assert {(r['订单行ID'], r['单位'], r['数量']) for r in page['rows']} == {('L1', 'kg', 2), ('L2', '盒', 20)}
    assert sum(r['订单行小计'] for r in page['rows']) == 40
    with pytest.raises(details.DetailError) as error:
        details.page({'context_id': context}, ('OTHER', 12, OWNER[2]), True, True)
    assert error.value.status_code == 403
    masked = details.page({'context_id': context}, OWNER, False, True)['data']
    assert not {'订单行小计', '订单总额'} & set(masked['columns'])
    assert all(not {'订单行小计', '订单总额'} & set(row) for row in masked['rows'])


def test_shared_preparation_keeps_same_rows_and_owner_bound_digest():
    item, dataset = fixture()
    snapshot = list(detail_store._prepared([item], [dataset], REQUEST, OWNER))[0][1]
    assert snapshot is not None
    assert {r['单位'] for r in snapshot['rows']} == {'kg', '盒'}
    assert len(snapshot['rows']) == 2
    foreign = list(detail_store._prepared([item], [dataset], REQUEST, ('OTHER', 12, OWNER[2])))[0][1]
    assert foreign['snapshot_key'] != snapshot['snapshot_key']


@pytest.mark.parametrize('change', ['no_policy', 'wrong_family', 'ratio', 'usd', 'foreign_source'])
def test_policy_cannot_enable_generic_amounts_or_ratio_mixed_unit_rows(change):
    item, dataset = fixture()
    item = copy.deepcopy(item)
    if change == 'no_policy':
        item['engine'].pop('detailUnitPolicy')
    elif change == 'wrong_family':
        item['analysis']['familyId'] = 'cost.unit'
    elif change == 'ratio':
        item['unit'] = '%'
    elif change == 'usd':
        dataset.currency = 'USD'
    else:
        dataset.source = item['source'] = 'upload · sales-sku'
    assert 'detailContext' not in details.register([item], [dataset], REQUEST, OWNER, True)[0]
    assert list(detail_store._prepared([item], [dataset], REQUEST, OWNER))[0][1] is None


def test_explicit_quantity_unit_and_unit_price_keep_existing_exact_filter():
    item, dataset = fixture()
    item['analysis']['familyId'] = 'cost.unit'
    item['unit'] = '元/kg'
    snapshot = list(detail_store._prepared([item], [dataset], REQUEST, OWNER))[0][1]
    assert [r['单位'] for r in snapshot['rows']] == ['kg']
    item['unit'] = '元'
    item['sourceQuantityUnit'] = '盒'
    registered = details.register([item], [dataset], REQUEST, OWNER, True)[0]
    page = details.page({'context_id': registered['detailContext']['id']}, OWNER, True, True)['data']
    assert [r['单位'] for r in page['rows']] == ['盒']


def test_price_permission_still_controls_reconciled_money_candidate():
    _, dataset = fixture()
    candidates, _ = engine.build([dataset], REQUEST, False)
    assert not any(c['analysis']['familyId'] == 'sales-sku.sales.sku' for c in candidates)
