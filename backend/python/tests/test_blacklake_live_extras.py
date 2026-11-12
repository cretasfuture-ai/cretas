"""Synthetic responses only; shapes follow the 2026-11-12 one-page read-only probes."""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from blacklake import live_extras as extras
from blacklake.contracts import BlacklakeError, ENDPOINTS
from blacklake.full_archive import Archive

CONTRACT = json.loads((Path(extras.__file__).parent / 'full_contract.json').read_text(encoding='utf8'))
NOW = datetime(2026, 11, 12, 4, 0, tzinfo=timezone.utc)
WRITE = {'create', 'add', 'update', 'modify', 'delete', 'remove', 'sync', 'approve', 'approvestatus', 'upload', 'edit',
         'upsert', 'enabled', 'disabled', 'batch', 'del', 'batchadd', 'batchcreate', 'updatestatus'}
PERSON = ('user/queryList2', 'permission', 'salary', 'performance', 'operationLog/detail')


class Settings:
    source_factory_id = 'factory'


class Client:
    def __init__(self, rows=None, fail=None):
        self.settings, self.factory_verified, self.calls = Settings(), True, []
        self.rows, self.fail = rows or {}, fail or {}

    def query(self, resource, body):
        assert resource in ENDPOINTS
        self.calls.append((resource, json.loads(json.dumps(body))))
        if resource in self.fail:
            raise BlacklakeError(self.fail[resource])
        rows = self.rows.get(resource, [])
        if callable(rows):
            rows = rows(body)
        if CONTRACT[resource]['items'] == 'data':
            return {'code': '01000000', 'data': rows, 'page': {'total': len(rows)}}
        return {'code': '01000000', 'data': {'data': rows, 'total': len(rows)}}


def run(client, prior=None, data=None, now=NOW, budget=extras.EXTRA_BUDGET):
    data = data if data is not None else {'product': [{'id': 1, 'productCode': 'P1'}]}
    projects = [{'id': 7, 'projectCode': 'WO-1'}]
    state, used = extras.collect_extras(Archive(client, None, CONTRACT, '00000000-0000-0000-0000-000000000000'),
                                        client, data, prior, projects, now=now, budget=budget)
    return data, state, used


def test_only_read_only_query_paths_and_no_person_endpoints_are_allowlisted():
    for path in ENDPOINTS.values():
        segments = [s.lower() for s in path.split('/') if s]
        assert not WRITE & set(segments), path
        assert segments[-1].startswith('query') or segments[-1] in ('list', 'expand'), path
        assert not any(w.lower() in path.lower() for w in PERSON), path
    for name, _, _ in extras.plans(NOW, [{'projectCode': 'WO-1'}]):
        assert name in ENDPOINTS and name in CONTRACT


def test_budget_is_never_exceeded_and_unfinished_resources_stay_due():
    client = Client()
    _, state, used = run(client, budget=10)
    assert used == len(client.calls) == 10
    # The next cycle continues with resources that were not reached; finished ones are not repeated.
    refreshed = set(state['at'])
    client2 = Client()
    _, state2, _ = run(client2, prior=state, now=NOW + timedelta(minutes=1), budget=10)
    assert not refreshed & {name for name, _ in client2.calls}
    assert set(state2['at']) > refreshed


def test_frequent_and_daily_intervals_and_rolling_cost_detail():
    summary = [{'projectCostId': 11, 'projectCode': 'WO-1', 'totalActualCost': 5, 'updatedAt': 'a'}]
    detail = lambda body: [{'id': body['costSourceType'] * 100, 'projectCostId': body['projectCostId'],
                            'costSourceType': body['costSourceType'], 'amount': 1}]
    client = Client({'project_cost': summary, 'project_cost_detail': detail})
    data, state, _ = run(client, budget=200)
    assert len(data['project_cost_detail']) == len(extras.COST_SOURCE_TYPES)
    assert [b['projectCodes'] for r, b in client.calls if r == 'project_cost'] == [['WO-1']]
    assert {b['orderType'] for r, b in client.calls if r == 'inspection_order'} == {1, 2, 3, 4, 5}
    assert all(b['page'] == {'pageNum': 1, 'pageSize': 100} for _, b in client.calls)
    # One minute later nothing is due; 31 minutes later only FREQUENT resources and no rolling work.
    quiet = Client({'project_cost': summary, 'project_cost_detail': detail})
    run(quiet, prior=state, data=data, now=NOW + timedelta(minutes=1), budget=200)
    assert quiet.calls == []
    later = Client({'project_cost': summary, 'project_cost_detail': detail})
    run(later, prior=state, data=data, now=NOW + timedelta(minutes=31), budget=200)
    called = {r for r, _ in later.calls}
    assert 'project_cost' in called and 'customer' not in called and 'project_cost_detail' not in called
    # A changed cost summary row re-queries that record's detail.
    changed = Client({'project_cost': [{**summary[0], 'totalActualCost': 6}], 'project_cost_detail': detail})
    run(changed, prior=state, data=data, now=NOW + timedelta(minutes=31), budget=200)
    assert sum(r == 'project_cost_detail' for r, _ in changed.calls) == len(extras.COST_SOURCE_TYPES)


def test_upstream_error_keeps_previous_rows_and_other_resources_continue():
    previous = {'sale_order': [{'id': 1}], 'product': [{'id': 1, 'productCode': 'P1'}]}
    client = Client({'customer': [{'id': 3, 'name': '客户甲'}]}, fail={'sale_order': 'UPSTREAM_BUSINESS_ERROR:1078'})
    data, state, _ = run(client, data=previous, budget=200)
    assert data['sale_order'] == [{'id': 1}] and state['errors'] == {'sale_order': 'UPSTREAM_BUSINESS_ERROR:1078'}
    assert data['customer'] == [{'id': 3, 'name': '客户甲'}]
    assert sum(r == 'sale_order' for r, _ in client.calls) == 1
    # Failed resources are retried after RETRY (not the full interval); healthy ones wait their interval.
    soon = Client()
    run(soon, prior=state, data=data, now=NOW + timedelta(seconds=extras.RETRY - 1), budget=200)
    assert 'sale_order' not in {r for r, _ in soon.calls}
    retry = Client()
    _, state2, _ = run(retry, prior=state, data=data, now=NOW + timedelta(seconds=extras.RETRY), budget=200)
    assert {r for r, _ in retry.calls} == {'sale_order'} and state2['errors'] == {}


def test_created_time_windows_stay_under_documented_upstream_limits():
    def parse(text):
        return datetime.strptime(text, '%Y-%m-%d %H:%M:%S')
    for name, _, requests in extras.plans(NOW, [{'projectCode': 'WO-1'}]):
        for body, _ in requests:
            for a, b in (('createdAtGte', 'createdAtLte'), ('createdAtStart', 'createdAtEnd')):
                if a in body:
                    limit = 365 if name == 'inspection_order' else 730
                    assert (parse(body[b]) - parse(body[a])).days < limit, name


@pytest.mark.parametrize('code', ['CONNECTION_LEASE_LOST', 'AUTH_REFRESH_EXHAUSTED', 'LIVE_REQUEST_BOUND_REACHED'])
def test_lease_auth_and_bound_errors_still_stop_the_cycle(code):
    client = Client(fail={'project_cost': code})
    with pytest.raises(BlacklakeError, match=code):
        run(client, budget=200)
    assert client.query.__func__ is Client.query  # Wrapper removed even on failure.


def test_person_fields_are_scrubbed_but_business_fields_kept():
    row = {'id': 1, 'customerName': '客户甲', 'customerCode': 'C1', 'contactName': '张三', 'contactNumber': '138',
           'contactAddress': '某路', 'phone': '139', 'creatorName': '李四', 'creator': {'password': 'x', 'phone': '1'},
           'updator': {'name': '王五'}, 'bizUserName': '赵六', 'operator': '孙七', 'approverName': '周八',
           'customFieldValues': [{'value': 'v'}], 'attachment': [{'url': 'u'}], 'salaryUnitPrice': 3,
           'details': [{'productCode': 'P1', 'qty': 2, 'operatorName': '吴九', 'amount': 5}],
           'realReceivableAmount': 10, 'orderNo': 'SO1', 'updatedAt': 't', 'status': 1}
    client = Client({'receivable': [row]})
    data, _, _ = run(client, budget=200)
    kept = data['receivable'][0]
    assert kept == {'id': 1, 'customerName': '客户甲', 'customerCode': 'C1', 'details': [{'productCode': 'P1', 'qty': 2, 'amount': 5}],
                    'realReceivableAmount': 10, 'orderNo': 'SO1', 'updatedAt': 't', 'status': 1}
    text = json.dumps(data, ensure_ascii=False)
    for secret in ('张三', '138', '李四', '王五', '赵六', '孙七', '周八', '吴九', 'password'):
        assert secret not in text


def test_child_scope_mismatch_is_rejected_per_resource():
    client = Client({'inspection_order': lambda body: [{'id': body['orderType'], 'orderType': 9}]})
    data, state, _ = run(client, budget=200)
    assert state['errors']['inspection_order'] == 'EXTRA_SCOPE_MISMATCH' and data['inspection_order'] == []


def test_rolling_price_lists_cover_every_product_within_budget():
    products = [{'id': i, 'productCode': f'P{i}'} for i in range(30)]
    prices = lambda body: [{'id': int(body['productCode'][1:]), 'productCode': body['productCode'], 'price': 1}]
    seen, state, data = set(), None, {'product': products}
    for minute in range(10):
        client = Client({'vendor_price': prices, 'customer_price': prices})
        data, state, used = run(client, prior=state, data=data, now=NOW + timedelta(minutes=minute), budget=50)
        assert used <= 50
        seen |= {b['productCode'] for r, b in client.calls if r == 'vendor_price'}
    assert seen == {p['productCode'] for p in products} and len(data['vendor_price']) == 30
