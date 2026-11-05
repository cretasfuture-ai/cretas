from decimal import Decimal

from smartbi.services.analysis_distillation import EXAMPLES,peer_basis,second_pass_facts


def project(code,quantity,material_quantity,material_cost,*,material='RAW',name='合成食品',unit='包'):
    return {'projectId':len(code),'projectCode':code,'productCode':'P','product':name,'unit':unit,
            'finishedGood':quantity,'unitIdentifiedDirectCost':material_cost/quantity,
            'components':[{'materialCode':material,'material':'合成投入','unit':'KG',
                           'netConsumption':material_quantity,'recognizedCost':material_cost}]}


def test_second_pass_distinguishes_less_material_from_higher_carry_in_value():
    low=project('LOW',10,20,100,material='RAW')
    high=project('HIGH',10,18,180,material='HALF')
    result=peer_basis(high,low)
    assert result['inputQuantityChange']==-0.1
    assert result['inputValuationChange']==1
    assert result['kgMaterialCostPerFinishedChange']==0.8
    assert result['inputKindsChanged']==1
    fact=next(f for f in second_pass_facts([high,low]) if f['kind']=='material_quantity_valuation_split')
    assert 'MATERIAL_MIX' in fact['hazards'] and '不是采购价' in fact['text']


def test_recorded_equality_requires_same_units_and_does_not_claim_no_real_loss():
    rows=[project(str(i),10,10,50,unit='KG') for i in range(3)]
    fact=next(f for f in second_pass_facts(rows) if f['kind']=='recorded_quantity_equality')
    assert 'MEASUREMENT_BASIS' in fact['hazards'] and '不能据此断言' in fact['text']
    for row in rows:row['unit']='盒'
    assert not any(f['kind']=='recorded_quantity_equality' for f in second_pass_facts(rows))


def test_stage_conflicts_cannot_be_turned_into_comparable_quantity_effects():
    low=project('LOW',10,20,100);high=project('HIGH',10,18,180,name='另一阶段')
    assert peer_basis(high,low) is None
    assert not any(f['kind']=='material_quantity_valuation_split' for f in second_pass_facts([high,low]))


def test_distilled_examples_are_methods_not_factory_codes_or_fixed_cost_answers():
    wire=str(EXAMPLES)
    assert {'normal_control','coverage_selection','contradictory_impact'}<={e['case'] for e in EXAMPLES}
    assert all(e['firstLook'] and e['secondLook'] and e['indicators'] for e in EXAMPLES)
    assert 'GD2026' not in wire and '六膳门' not in wire
