import pandas as pd
import pytest

from .engine import Dataset
from .uploaded_joint import parse_selection, prepare_uploaded_join


def _datasets():
    production = Dataset(pd.DataFrame([{
        '来源主键': 'b1', '产品ID': 'p1', '单位': 'kg', '日期': '2026-01-01',
        '产量': 10, '良品数': 9, '总成本': 10,
    }]), 'upload-1')
    inspection = Dataset(pd.DataFrame([
        {'检验ID': 'q1', '生产批次ID': 'b1', '产品ID': 'p1', '日期': '2026-01-02', '单位': '样本', '检验数': 6, '合格数': 5, '不良数': 1},
        {'检验ID': 'q2', '生产批次ID': 'b1', '产品ID': 'p1', '日期': '2026-01-03', '单位': '样本', '检验数': 4, '合格数': 4, '不良数': 0},
    ]), 'upload-2')
    cost = Dataset(pd.DataFrame([{
        '生产批次ID': 'b1', '产品ID': 'p1', '单位': 'kg',
        '币种': 'CNY',
        '材料成本': 2, '人工成本': 3, '设备成本': 1, '其他成本': 4, '总成本': 10,
    }]), 'upload-3')
    return production, inspection, cost


def test_selection_requires_explicit_distinct_roles():
    assert parse_selection({'uploadId': 1, 'inspectionUploadId': 2, 'costUploadId': 3}).as_dict()['roles']['cost'] == 3
    with pytest.raises(ValueError):
        parse_selection({'uploadId': 1, 'inspectionUploadId': 2})
    with pytest.raises(ValueError):
        parse_selection({'uploadId': 1, 'inspectionUploadId': 1, 'costUploadId': 3})


def test_quality_many_rows_aggregate_before_join_without_cost_duplication():
    frame, gaps = prepare_uploaded_join(*_datasets())
    assert frame is not None and not gaps
    row = frame.iloc[0]
    assert row['检验数'] == 10 and row['合格数'] == 9 and row['总成本'] == 10
    assert frame.attrs['joint_production_scope'][0]['来源主键'] == 'b1'


def test_incomplete_cost_is_gap_not_zero():
    production, inspection, cost = _datasets()
    cost.frame.loc[0, '其他成本'] = 0
    cost.frame.loc[0, '总成本'] = 9
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and any('成本' in gap['missing'][0] for gap in gaps)


def test_api_accepts_snake_case_and_rejects_business_or_bool_joint_ids():
    from smartbi.api.financial_dashboard import FactoryRecommendationRequest
    request = FactoryRecommendationRequest(source='upload', upload_id=1,
                                           inspection_upload_id=2, cost_upload_id=3)
    assert request.inspection_upload_id == 2 and request.cost_upload_id == 3
    with pytest.raises(Exception):
        FactoryRecommendationRequest(source='business', upload_id=1,
                                     inspection_upload_id=2, cost_upload_id=3)
    with pytest.raises(Exception):
        FactoryRecommendationRequest(source='upload', upload_id=True,
                                     inspection_upload_id=2, cost_upload_id=3)


def test_duplicate_inspection_id_and_invalid_cost_are_gaps_not_500():
    production, inspection, cost = _datasets()
    inspection.frame.loc[1, '检验ID'] = inspection.frame.loc[0, '检验ID'] = 'duplicate'
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and gaps


@pytest.mark.parametrize('currency', [None, 'USD'])
def test_missing_or_foreign_cost_currency_is_gap(currency):
    production, inspection, cost = _datasets()
    cost.frame.loc[0, '币种'] = currency
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and gaps


def test_invalid_production_row_is_reported_and_cross_batch_duplicate_qi_rejects_all():
    production, inspection, cost = _datasets()
    production.frame = pd.concat([production.frame, pd.DataFrame([{
        '来源主键': 'b2', '产品ID': 'p1', '单位': 'kg', '产量': 10, '良品数': 9,
    }])], ignore_index=True)
    inspection.frame = pd.concat([inspection.frame, pd.DataFrame([{
        '检验ID': 'q-cross', '生产批次ID': 'b2', '产品ID': 'p1', '检验数': 1, '合格数': 1, '不良数': 0,
    }, {
        '检验ID': 'q-cross', '生产批次ID': 'b1', '产品ID': 'p1', '检验数': 1, '合格数': 1, '不良数': 0,
    }])], ignore_index=True)
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and any('无效' in gap['missing'][0] for gap in gaps)


def test_known_batch_conflict_in_related_table_rejects_whole_batch():
    production, inspection, cost = _datasets()
    inspection.frame = pd.concat([inspection.frame, pd.DataFrame([{
        '检验ID': 'q-bad', '生产批次ID': 'b1', '产品ID': 'wrong', '检验数': 1, '合格数': 1, '不良数': 0,
    }])], ignore_index=True)
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and any('冲突' in gap['missing'][0] for gap in gaps)
    production, inspection, cost = _datasets()
    cost.frame = pd.concat([cost.frame, pd.DataFrame([{
        '生产批次ID': 'b1', '产品ID': 'p1', '单位': '盒', '币种': 'CNY',
        '材料成本': 2, '人工成本': 3, '设备成本': 1, '其他成本': 4, '总成本': 10,
    }])], ignore_index=True)
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and any('冲突' in gap['missing'][0] for gap in gaps)
    production, inspection, cost = _datasets()
    cost.frame.loc[0, '材料成本'] = None
    frame, gaps = prepare_uploaded_join(production, inspection, cost)
    assert frame is None and gaps
