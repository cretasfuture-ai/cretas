from copy import deepcopy
import pytest
from blacklake.labor_evidence import evidence,person_key,summary


def data():
    return {'task':[{'id':1,'projectId':10,'processId':20,'sumOutputSalary':100,'sumEffectiveTime':3600}],
        'output':[{'id':30,'projectId':10,'taskId':1,'processId':20,'estimatedSalary':100,'effectiveTime':3600,
            'planRestTime':0,'overtimeTime':0,'unplanShutdownTime':0,'approvalStatus':'10',
            'starttimeofReport':'2026-11-01 08:00:00','endtimeofReport':'2026-11-01 09:00:00',
            'productionWorkerKey':person_key(12,b'x'*32,('tenant','factory'))}]}


def test_scoped_person_keys_do_not_expose_source_ids_or_join_other_factories():
    assert person_key(12,b'x'*32,('T','A'))==person_key('12',b'x'*32,('T','A'))
    assert person_key(12,b'x'*32,('T','A'))!=person_key(12,b'x'*32,('T','B'))
    assert person_key(12,b'x'*32,('T','A'))!=person_key(12,b'y'*32,('T','A'))
    for value in (None,True,0,-1,{},'bad'):assert person_key(value,b'x'*32,('T','A')) is None


def test_registered_staff_and_elapsed_time_never_become_confirmed_roster_or_person_hours():
    result=evidence(data())['1']
    assert result['registeredCoverageComplete'] is True
    assert result['registeredReports']==1 and len(result['registeredWorkerKeys'])==1
    assert result['sourceEffectiveHours']==1
    assert result['rosterComplete'] is False and result['personHours'] is None


@pytest.mark.parametrize('change',[{'approvalStatus':'0'},{'estimatedSalary':99},{'productionWorkerKey':None},{'projectId':999},{'processId':999}])
def test_unapproved_incomplete_wages_or_wrong_child_association_block_registered_average(change):
    value=data();value['output'][0].update(change)
    result=evidence(value)['1']
    assert result['registeredCoverageComplete'] is False
    assert result['issues']


@pytest.mark.parametrize('change',[{'effectiveTime':60},{'planRestTime':10},{'endtimeofReport':None},{'starttimeofReport':'invalid'}])
def test_time_unit_and_interval_are_checked_instead_of_guessing_person_hours(change):
    value=data();value['output'][0].update(change)
    result=evidence(value)['1']
    assert result['sourceEffectiveHours'] is None and result['personHours'] is None


def test_shared_worker_overlap_remains_visible_without_double_claiming_attendance():
    value=data();other=deepcopy(value['task'][0]);other['id']=2;value['task'].append(other)
    report=deepcopy(value['output'][0]);report.update(id=31,taskId=2);value['output'].append(report)
    result=evidence(value)
    assert result['1']['overlapReports']==result['2']['overlapReports']==1
    assert result['1']['sourceEffectiveHours']==1 and result['1']['personHours'] is None


def test_zero_wages_without_reports_are_neutral_but_unknown_wages_do_not_become_zero():
    value=data();value['output']=[];value['task'][0].update(sumOutputSalary=0,sumEffectiveTime=0)
    assert evidence(value)['1']['registeredCoverageComplete'] is True
    value['task'][0]['sumOutputSalary']=None
    assert evidence(value)['1']['registeredCoverageComplete'] is False


def test_numeric_summary_deduplicates_privately_and_never_contains_person_ids():
    e=evidence(data())['1']
    orders=[{'laborCost':100,'processes':[{'process':'包装','laborCost':100,'laborEvidence':e}]}]*2
    result=summary(orders)
    assert result['participants']==1 and result['perRegisteredPerson']==200
    assert result['processes']==[{'process':'包装','participants':1}]
    assert 'worker:' not in str(result) and 'registeredWorkerKeys' not in str(result)
    assert result['actualRosterConfirmed'] is False and result['personHours'] is None


@pytest.mark.asyncio
async def test_summary_cost_authority_is_checked_before_loading_selected_snapshot(monkeypatch):
    from blacklake import smartbi_api as api
    from fastapi import HTTPException
    from unittest.mock import AsyncMock,Mock
    monkeypatch.setenv('BLACKLAKE_SMARTBI_CONFIG','synthetic')
    monkeypatch.setattr(api,'settings',lambda _: {'tenant_id':'T','source_factory_id':'F'})
    monkeypatch.setattr(api,'authorize',lambda *args:None)
    denied=AsyncMock(side_effect=HTTPException(403,'denied'))
    monkeypatch.setattr(api,'cost_authority',denied)
    reader=Mock(side_effect=AssertionError('must authorize first'));monkeypatch.setattr(api,'read',reader)
    with pytest.raises(HTTPException) as error:await api.labor_summary(object(),'1','live:'+'a'*32)
    assert error.value.status_code==403;reader.assert_not_called()
