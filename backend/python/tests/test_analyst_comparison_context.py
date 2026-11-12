import json
import pytest
from smartbi.services.analysis_evidence import build
from smartbi.services.analysis_distillation import common_quotes
from smartbi.services import local_analyst as analyst
from tests.test_smartbi_local_analyst import response_for


def project(i,code,material,labor,*,quote=50,name='PRIVATE-INPUT',input_code='M2'):
    return {'projectId':i,'projectCode':'PRIVATE-'+str(i),'productCode':code,'product':'PRIVATE-'+code,'unit':'包',
            'finishedGood':100,'unitIdentifiedDirectCost':(material+labor)/100,'materialPerFinished':material/100,
            'laborPerFinished':labor/100,'materialCost':material,'laborCost':labor,'unitCostAvailable':True,
            'components':[{'materialCode':input_code,'material':name,'unit':'KG','netConsumption':material/quote,
                           'recognizedCost':material,'unitPrice':quote}]}


def example():
    rows=[project(1,'P',6000,500,name='PRIVATE-STAGE-A'),project(2,'P',5000,0,name='PRIVATE-STAGE-B'),
          project(3,'N',1900,100),project(4,'N',1900.1,100)]
    report={'scope':'PRIVATE-FACTORY','summary':{'projects':5,'costEligible':4},'items':[]}
    context={'eligibleCostReview':{'projects':rows,'rankings':[]},
             'cost':{'projects':rows+[dict(rows[0],projectId=5,productCode='B',unitCostAvailable=False,finishedGood=None)]},
             'production':{},'inventory':{'movementOrders':1,'records':[]}}
    return report,context


def test_high_difference_quote_check_and_stable_group_are_all_required():
    report,context=example();pack=build(report,context)
    required=[f for f in pack['facts'] if f['id'] in pack['requiredFactRefs']['product_cost']]
    assert sum(f['kind']=='same_product_bridge' for f in required)==2
    assert any(f['kind']=='common_input_price_check' for f in required)
    bridges=[f for f in required if f['kind']=='same_product_bridge']
    assert len({f['groupId'] for f in bridges})==2
    assert 'PRIVATE-' not in json.dumps(pack['facts'])
    assert 'PRIVATE-' not in json.dumps(analyst.payload_for(pack,[],'qwen3.8:27b'))


def test_missing_normal_reference_is_rejected_not_silently_accepted():
    report,context=example();pack=build(report,context);value=response_for(pack,[])
    # Use semantically valid boilerplate, then remove just the required context.
    analyst.validate(value,pack,[])
    section=next(s for s in value['sections'] if s['sectionId']=='product_cost')
    section['evidenceRefs']=[r for r in section['evidenceRefs'] if r!=pack['requiredFactRefs']['product_cost'][-1]]
    with pytest.raises(ValueError,match='MISSING_COMPARISON_CONTEXT'):analyst.validate(value,pack,[])


def test_unknown_good_quantities_do_not_become_zero_quantity_coverage():
    report,context=example();pack=build(report,context)
    coverage=[f for f in pack['facts'] if f['kind']=='product_coverage']
    missing=next(f for f in coverage if f['statistics']['eligibleCount']==0)
    assert 'eligibleQuantityRatio' not in missing['statistics']
    assert missing['id'] in pack['requiredFactRefs']['coverage']


def test_same_code_quotes_are_not_purchase_inflation_or_verified_identity():
    high=project(1,'P',6000,500,name='PRIVATE-STAGE-A')
    low=project(2,'P',5000,0,name='PRIVATE-STAGE-B')
    stats,_=common_quotes(high,low)
    assert stats['sameBookQuoteCount']==1 and stats['changedBookQuoteCount']==0
    assert stats['inputNameConflictCount']==1
