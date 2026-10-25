from .metrics import Series


def test_totals_are_not_unearned_success_and_domain_actions_are_specific():
    for key,domain,title,unit in [('cost.unit','成本','单位成本','元/kg'),
            ('quality.defect','质量','不良率','%'),('stock.expiry','库存','临期批次','批')]:
        findings=Series(key,title,domain,unit,[('甲',90),('乙',100)],'fixture').findings()
        assert findings['good']==[]
        assert any('190' in f for f in findings['attention'])
        assert '不据此推测原因' in findings['suggestions'][0]
        expected={'成本':'成本归集','质量':'检验分母','库存':'到期日'}[domain]
        assert expected in findings['suggestions'][0]


def test_only_proven_directional_time_improvement_is_good():
    improved=Series('quality.pass','检验合格率','质量','%',
                    [('2026-01-01',90),('2026-01-03',95)],'fixture','ratio',185,200).findings()
    assert improved['good']==['2026-01-03较2026-01-01上升 5个百分点；仅比较有记录的两期']
    worsening=Series('quality.defect','不良率','质量','%',
                    [('2026-01-01',1),('2026-01-03',10)],'fixture','ratio',11,200).findings()
    assert worsening['good']==[]
    assert any('上升 9个百分点' in text for text in worsening['attention'])
    cross_product=Series('quality.pass','检验合格率','质量','%',
                    [('产品A',90),('产品B',95)],'fixture','ratio',185,200).findings()
    assert cross_product['good']==[]


def test_cost_decline_uses_same_metric_points_without_causal_claims():
    findings=Series('cost.unit','单位成本','成本','元/kg',
                    [('2026-01',3),('2026-02',2)],'fixture','unit_cost',500,200).findings()
    assert findings['good']==['2026-02较2026-01下降 1元/kg；仅比较有记录的两期']
    assert '2.5元/kg' in findings['attention'][0]
