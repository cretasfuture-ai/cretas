import pandas as pd
import pytest

from . import details, detail_store, engine, newstock_buckets
from .engine import Dataset


def _dataset(count=67):
    units = (['kg'] * 34) + (['个'] * 17) + (['袋'] * 16)
    ages = ([10] * 20) + ([45] * 20) + ([120] * 27)
    frame = pd.DataFrame([
        {'批次': f'b-{index:03d}', '日期': '2026-10-01', '单位': unit,
         '产品': '混单位原料', '库存数量': 1, '库龄天数': age}
        for index, (unit, age) in enumerate(zip(units[:count], ages[:count]), 1)
    ])
    return Dataset(frame, 'inventory · 正余额批次快照')


def _request():
    return type('Request', (), {'period_type': 'all', 'include_optional': False})()


def test_engine_stock_age_is_batch_count_and_emits_trusted_contract():
    candidates, gaps = engine.build([_dataset()], _request(), True)
    candidate = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    assert candidate['unit'] == '批'
    assert sum(point['value'] for point in candidate['tableData']) == 67
    contract = candidate['engine']['stockCountContract']
    assert contract['kind'] == 'batch_count'
    assert contract['bucketColumn'] == '库存库龄桶'
    assert {point['label'] for point in candidate['tableData']} == {'不超过30天', '31–90天', '91–180天', '超过180天'}


def test_in_memory_details_count_pages_and_filters_derived_bucket():
    candidates, _ = engine.build([_dataset()], _request(), True)
    owner = ('F006', 901, 'factory_super_admin')
    registered = details.register(candidates, [_dataset()], _request(), owner, True)
    candidate = next(item for item in registered if item['id'] == 'inventory.stock.age:bar')
    context_id = candidate['detailContext']['id']
    first = details.page({'context_id': context_id, 'page': 1, 'page_size': 50}, owner, True, True)
    second = details.page({'context_id': context_id, 'page': 2, 'page_size': 50}, owner, True, True)
    assert first['data']['total'] == 67 and len(first['data']['rows']) == 50
    assert second['data']['total'] == 67 and len(second['data']['rows']) == 17
    assert {row['单位'] for row in first['data']['rows']} | {row['单位'] for row in second['data']['rows']} == {'kg', '个', '袋'}
    filtered = details.page({'context_id': context_id, 'point': {'name': '不超过30天'}, 'page_size': 50}, owner, True, True)
    assert filtered['data']['total'] == 20
    assert all(row['库存库龄桶'] == '不超过30天' for row in filtered['data']['rows'])
    with pytest.raises(details.DetailError) as denied:
        details.page({'context_id': context_id, 'page': 1}, ('F999', 901, 'factory_super_admin'), True, True)
    assert denied.value.status_code == 403


def test_shared_prepared_stock_contract_does_not_infer_quantity_unit():
    dataset = _dataset()
    candidates, _ = engine.build([dataset], _request(), True)
    candidate = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    prepared = list(detail_store._prepared([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin')))
    item, snapshot = next(value for value in prepared if value[0]['id'] == 'inventory.stock.age:bar')
    assert snapshot is not None
    assert len(snapshot['rows']) == 67
    assert {row['单位'] for row in snapshot['rows']} == {'kg', '个', '袋'}
    assert {row['库存库龄桶'] for row in snapshot['rows']} == {'不超过30天', '31–90天', '91–180天'}


def test_invalid_stock_balance_or_bucket_never_enters_count_and_bad_metadata_does_not_broaden():
    rows, bucket = newstock_buckets.prepare_rows([
        {'库存数量': 2, '库龄天数': 10, '单位': 'kg'},
        {'库存数量': 0, '库龄天数': 10, '单位': 'kg'},
        {'库存数量': -1, '库龄天数': 10, '单位': 'kg'},
        {'库存数量': 1, '库龄天数': None, '单位': 'kg'},
    ], newstock_buckets.contract('age', '库存数量', '库龄天数', '库存库龄桶'))
    assert len(rows) == 1 and bucket == '库存库龄桶'
    dataset = _dataset()
    candidates, _ = engine.build([dataset], _request(), True)
    candidate = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    candidate['engine']['stockCountContract'] = {'version': 999, 'kind': 'batch_count'}
    registered = details.register([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin'), True)
    assert 'detailContext' not in next(item for item in registered if item['id'] == 'inventory.stock.age:bar')


def test_contract_rejects_derived_column_tampering_and_preserves_source_columns():
    source = [{'库存数量': 2, '库龄天数': 10, '单位': 'kg'}]
    for bad_column in ('单位', '库存数量', '客户端自定义桶'):
        with pytest.raises(ValueError):
            newstock_buckets.contract('age', '库存数量', '库龄天数', bad_column)
        metadata = {'version': 1, 'kind': 'batch_count', 'bucketKind': 'age',
                    'stockColumn': '库存数量', 'valueColumn': '库龄天数', 'bucketColumn': bad_column}
        rows, bucket = newstock_buckets.prepare_rows(source, metadata)
        assert rows == () and bucket is None
    metadata = newstock_buckets.contract('age', '库存数量', '库龄天数', '库存库龄桶')
    rows, bucket = newstock_buckets.prepare_rows(source, metadata)
    assert bucket == '库存库龄桶' and rows[0]['单位'] == 'kg' and rows[0]['库存数量'] == 2
    for stock_column, value_column in (('客户端库存', '库龄天数'), ('库存数量', '客户端天数'), ('库存数量', '距到期天数')):
        with pytest.raises(ValueError):
            newstock_buckets.contract('age', stock_column, value_column, '库存库龄桶')


def test_decimal_positive_balance_is_shared_and_invalid_positive_date_fails_closed():
    tiny = Dataset(pd.DataFrame([{
        '批次': 'tiny', '日期': '2026-10-01', '单位': 'kg', '产品': '微量',
        '库存数量': '1e-400', '库龄天数': 0,
    }]), 'inventory · 正余额批次快照')
    candidates, _ = engine.build([tiny], _request(), True)
    age = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    assert sum(point['value'] for point in age['tableData']) == 1
    prepared = list(detail_store._prepared([age], [tiny], _request(), ('F006', 901, 'factory_super_admin')))
    assert next(snapshot for item, snapshot in prepared if item['id'] == age['id'])['rows'][0]['库存数量'] == '1e-400'
    bad = Dataset(pd.DataFrame([{
        '批次': 'bad', '日期': '2026-10-01', '单位': 'kg', '产品': '坏日期',
        '库存数量': '1e-400', '库龄天数': 'NaT',
    }]), 'inventory · 正余额批次快照')
    bad_candidates, _ = engine.build([bad], _request(), True)
    assert not any(item['id'] == 'inventory.stock.age:bar' for item in bad_candidates)


def test_stock_marker_requires_exact_candidate_semantics_and_kind():
    dataset = _dataset(1)
    candidates, _ = engine.build([dataset], _request(), True)
    original = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    for candidate_id, unit, kind in (
        ('inventory.stock.other:bar', '批', 'age'),
        ('inventory.stock.age:bar', 'kg', 'age'),
        ('inventory.stock.age:bar', '批', 'expiry'),
    ):
        candidate = dict(original)
        candidate['id'], candidate['unit'] = candidate_id, unit
        candidate['engine'] = {'stockCountContract': dict(original['engine']['stockCountContract'], bucketKind=kind)}
        registered = details.register([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin'), True)
        assert 'detailContext' not in registered[0]

    for forged_marker in ('forged', ['stockCountContract']):
        candidate = dict(original, engine={newstock_buckets.CONTRACT_KEY: forged_marker})
        registered = details.register([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin'), True)
        assert 'detailContext' not in registered[0]
        prepared = list(detail_store._prepared([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin')))
        assert prepared[0][1] is None


def test_expiry_negative_days_are_valid_and_use_expiry_bucket():
    dataset = Dataset(pd.DataFrame([{
        '批次': 'expired', '日期': '2026-10-01', '单位': 'kg', '产品': '效期',
        '库存数量': 1, '距到期天数': -1,
    }]), 'inventory · 正余额批次快照')
    candidates, _ = engine.build([dataset], _request(), True)
    candidate = next(item for item in candidates if item['id'] == 'inventory.stock.expiry:bar')
    assert candidate['engine']['stockCountContract']['bucketColumn'] == '库存效期桶'
    assert candidate['tableData'][0] == {'label': '已到期', 'value': 1}


def test_parenthesized_stock_columns_keep_actual_names_through_details():
    dataset = Dataset(pd.DataFrame([{
        '批次': 'unit-label', '日期': '2026-10-01', '单位': 'kg', '产品': '括号列',
        '库存数量（kg）': 1, '库龄天数(天)': 10,
    }]), 'inventory · 正余额批次快照')
    candidates, _ = engine.build([dataset], _request(), True)
    candidate = next(item for item in candidates if item['id'] == 'inventory.stock.age:bar')
    metadata = candidate['engine']['stockCountContract']
    assert metadata['stockColumn'] == '库存数量（kg）'
    assert metadata['valueColumn'] == '库龄天数(天)'
    registered = details.register([candidate], [dataset], _request(), ('F006', 901, 'factory_super_admin'), True)
    context = next(item for item in registered if item['id'] == candidate['id'])['detailContext']['id']
    page = details.page({'context_id': context, 'page': 1}, ('F006', 901, 'factory_super_admin'), True, True)
    assert page['data']['total'] == 1
    assert page['data']['rows'][0]['库存数量（kg）'] == 1
    assert page['data']['rows'][0]['库龄天数(天)'] == 10
