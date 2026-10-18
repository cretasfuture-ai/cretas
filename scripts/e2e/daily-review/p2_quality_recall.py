"""Reuse normal business APIs for the owned, synthetic quality/recall acceptance."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import time

import psycopg
import requests

from p0_business_flow import BusinessFlow
from p1_quality_trace import database_snapshot


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def b_version_proof(before, after, startup_log):
    """Preserve baseline rows; explain additions only from actual startup compute logs."""
    previous = {row['id']: row for row in before}
    current = {row['id']: row for row in after}
    additions = [row for row in after if row['id'] not in previous]
    text = re.sub(r'\x1b\[[0-9;]*m', '', startup_log)
    blocks = []
    opened = None
    for line in text.splitlines():
        if 'DailyIndicatorRecomputeScheduler: Spring ready' in line:
            opened = [line]
        elif opened is not None:
            opened.append(line)
            if 'DailyIndicatorRecomputeScheduler [startup] done:' in line:
                blocks.append(opened)
                opened = None

    def explained(row):
        if row.get('factory_id') != 'VISION-ENTRY-B' or row.get('deleted_at') is not None:
            return False
        source = row.get('compute_source', '')
        if not source.startswith('REAL_BUSINESS:'):
            return False
        try:
            computed = datetime.fromisoformat(row['computed_at'])
            value = Decimal(row['value'])
        except (KeyError, ValueError, TypeError):
            return False
        code = source.removeprefix('REAL_BUSINESS:')
        pattern = (re.escape(code) + r' factory=VISION-ENTRY-B period=\['
                   + re.escape(row['period_start']) + ',' + re.escape(row['period_end'])
                   + r'\] → ([+-]?[0-9]+(?:\.[0-9]+)?)\b')
        for block in blocks:
            start = datetime.fromisoformat(block[0][:23])
            end = datetime.fromisoformat(block[-1][:23])
            if start <= computed <= end and any(Decimal(match) == value for match in re.findall(pattern, '\n'.join(block))):
                return True
        return False

    return {'originalFullEquality': before == after,
            'baselineRowsUnchanged': len(previous) == len(before) and len(current) == len(after)
                and all(current.get(key) == row for key, row in previous.items()),
            'startupAdditionsExplained': all(explained(row) for row in additions),
            'addedRows': additions,
            'startupLogSha256': hashlib.sha256(startup_log.encode('utf-8')).hexdigest()}


def snapshot(queue):
    data = database_snapshot(queue)
    with psycopg.connect(host='127.0.0.1', port=55447, dbname='vision_entry_probe_20261015',
                         user='assembly_executor', options='-c default_transaction_read_only=on',
                         row_factory=psycopg.rows.dict_row) as db:
        data['returns'] = db.execute('select id,return_number,status,source_order_id,total_amount,with_goods from return_orders where factory_id=%s and deleted_at is null order by id', ('VISION-ENTRY-A',)).fetchall()
        data['recalls'] = db.execute('select id,event_code,status,completed_at from recall_events where factory_id=%s and deleted_at is null order by id', ('VISION-ENTRY-A',)).fetchall()
        data['returnedStock'] = db.execute("select id,batch_number,product_type_id,produced_quantity,shipped_quantity,reserved_quantity,unit,status from finished_goods_batches where factory_id=%s and batch_number like 'RTN-%%' and deleted_at is null order by id", ('VISION-ENTRY-A',)).fetchall()
        data['audit'] = db.execute("select id,entity_id,decision_made,approval_status,executor_id,approver_id from decision_audit_logs where factory_id=%s and entity_type='QualityInspection' and entity_id in (select id from quality_inspections where factory_id=%s and production_batch_id in(7,8)) order by id", ('VISION-ENTRY-A', 'VISION-ENTRY-A')).fetchall()
    return data


def reject(flow, method, path, payload):
    if not path.startswith(('/quality-recalls', '/canvas-food-safety/recalls/', '/return-orders/')) or '..' in path:
        raise ValueError('Only exact quality acceptance paths are allowed')
    before = flow.call('GET', '/quality-recalls?batchId=7')
    response = flow.http.request(method, flow.base + '/api/mobile/' + flow.factory + path, json=payload, timeout=60)
    result = {'path': path, 'httpStatus': response.status_code, 'body': response.json(), 'before': before,
              'after': flow.call('GET', '/quality-recalls?batchId=7')}
    if response.status_code < 400 or response.json().get('success'):
        raise AssertionError('Expected rejection: ' + json.dumps(result, ensure_ascii=False, default=str))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['baseline', 'open-controls', 'pending-controls', 'partial-controls', 'boxed', 'voucher-control', 'verify'])
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--p0-state', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--queue-db', required=True, type=Path)
    parser.add_argument('--allow-test-writes', choices=['NON_PRODUCTION_ONLY'], required=True)
    args = parser.parse_args()
    source = json.loads(args.p0_state.read_text(encoding='utf-8'))
    if source.get('factoryId') != 'VISION-ENTRY-A':
        raise ValueError('Wrong source factory')
    entities = source['entities']
    if [entities['verified_batch_' + key]['id'] for key in ['a', 'b']] != [7, 8]:
        raise ValueError('Only established synthetic sample batches 7/8 are allowed')
    flow = BusinessFlow('http://127.0.0.1:10060', args.credentials, args.out)
    baseline = args.out / 'baseline.json'
    if not baseline.exists():
        save(baseline, snapshot(args.queue_db))
    if args.step == 'baseline':
        print('Baseline saved once; existing P0/P1/vision evidence retained')
        return

    if args.step in ['open-controls', 'partial-controls']:
        recalls = flow.call('GET', '/quality-recalls?batchId=7')
        if len(recalls) != 1:
            raise AssertionError('Create one batch7 recall through the actual UI first')
        recall = flow.call('GET', '/quality-recalls/' + str(recalls[0]['id']))
        path = '/quality-recalls/' + str(recall['id'])
        shipment = recall['shipments'][0]
        controls = {'closeBeforeComplete': reject(flow, 'POST', path + '/close', {'note': 'P2合成演练：缺数不得结案'})}
        controls['overReturn'] = reject(flow, 'POST', path + '/returns', {'shipmentId': shipment['shipmentId'], 'quantity': 999, 'reason': 'P2合成演练：超量阴性对照'})
        controls['genericEditor'] = reject(flow, 'PUT', '/canvas-food-safety/recalls/' + str(recall['id']), {'status': 'COMPLETED', 'version': 0})
        same = flow.call('POST', '/quality-recalls', {'batchId': 7, 'reason': 'P2合成演练：重复发起应复用'})
        if same['id'] != recall['id']:
            raise AssertionError('Duplicate recall did not reuse the existing event')
        save(args.out / (args.step + '.json'), {'recall': recall, 'controls': controls, 'duplicateEventId': same['id']})
        print('PASS: incomplete close, over-return, generic-editor bypass rejected; duplicate event reused')
        return

    if args.step == 'pending-controls':
        recalls = flow.call('GET', '/quality-recalls?batchId=7')
        if len(recalls) != 1:
            raise AssertionError('Expected the actual UI-created batch7 recall')
        detail = flow.call('GET', '/quality-recalls/' + str(recalls[0]['id']))
        shipment = detail['shipments'][0]
        pending = [row for row in shipment['returnOrders'] if row['status'] not in ['COMPLETED', 'REJECTED']]
        if len(pending) != 1 or Decimal(str(pending[0]['quantity'])) != 2:
            raise AssertionError('Create the first actual 2kg UI return draft before this control')
        path = '/quality-recalls/' + str(detail['id']) + '/returns'
        payload = {'shipmentId': shipment['shipmentId'], 'quantity': 2,
                   'reason': 'P2合成演练：并发重复请求应复用原草稿'}
        headers = dict(flow.http.headers)

        def repeat_return(_):
            response = requests.post(flow.base + '/api/mobile/' + flow.factory + path,
                                     json=payload, headers=headers, timeout=60)
            body = response.json()
            if response.status_code != 200 or not body.get('success'):
                raise AssertionError('Concurrent duplicate failed: ' + json.dumps(body, ensure_ascii=False))
            return body['data']

        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(repeat_return, range(2)))
        after = flow.call('GET', '/quality-recalls/' + str(detail['id']))
        if any(row['id'] != pending[0]['id'] for row in results) or len(after['shipments'][0]['returnOrders']) != len(shipment['returnOrders']):
            raise AssertionError('Concurrent duplicates created extra returns')
        controls = {'differentPendingQuantity': reject(flow, 'POST', path, {**payload, 'quantity': 1}),
                    'excessPrecision': reject(flow, 'POST', path, {**payload, 'quantity': 0.00001})}
        save(args.out / 'pending-controls.json', {'before': detail, 'duplicates': results, 'after': after, 'controls': controls})
        print('PASS: concurrent duplicate returns reused one draft; other quantity/precision rejected')
        return

    if args.step == 'boxed':
        recall = flow.create('boxed_recall', '/quality-recalls', {'batchId': 8, 'reason': 'P2本地合成演练：客户反馈核查并收回，禁止真实通知或发运'})
        recall = flow.call('GET', '/quality-recalls/' + str(recall['id']))
        shipment = recall['shipments'][0]
        flow.create('boxed_customer_ack', '/quality-recalls/' + str(recall['id']) + '/customer-ack', {
            'shipmentId': shipment['shipmentId'], 'note': 'P2合成客户演练：已确认收回全部10盒；非真实客户联系'})
        order = flow.create('boxed_return', '/quality-recalls/' + str(recall['id']) + '/returns', {
            'shipmentId': shipment['shipmentId'], 'quantity': 10, 'reason': 'P2本地合成质量演练：收回10盒'})
        for action in ['submit', 'approve', 'finance-approve', 'complete']:
            flow.create('boxed_return_' + action, '/return-orders/' + order['id'] + '/' + action, {})
        duplicate = reject(flow, 'POST', '/return-orders/' + order['id'] + '/complete', {})
        save(args.out / 'duplicate-return-completion.json', duplicate)
        flow.create('boxed_closed', '/quality-recalls/' + str(recall['id']) + '/close', {'note': 'P2合成演练：实际10盒退货已隔离入库，回执已核对'})
        print('PASS: boxed recall uses existing return submit/business/finance/physical receipt; closed')
        return

    if args.step == 'voucher-control':
        physical = flow.call('GET', '/return-orders/' + flow.state['entities']['boxed_return']['id'])
        line = physical['items'][0]
        def read_voucher_state(order_id, expect_void=False):
            deadline = time.monotonic() + 8
            with psycopg.connect(host='127.0.0.1', port=55447, dbname='vision_entry_probe_20261015',
                                 user='assembly_executor', options='-c default_transaction_read_only=on',
                                 row_factory=psycopg.rows.dict_row) as db:
                while True:
                    result = db.execute('select id,status,vflag,version from return_orders where factory_id=%s and id=%s',
                                        (flow.factory, order_id)).fetchone()
                    vouchers = db.execute('select id,status from vouchers where factory_id=%s and source_business_type=%s and source_business_id=%s and deleted_at is null',
                                          (flow.factory, 'RETURN_ORDER', order_id)).fetchall()
                    if result['vflag'] == 'CREATED' and len(vouchers) == 1 and (not expect_void or vouchers[0]['status'] == 'VOID'):
                        return {'order': result, 'vouchers': vouchers}
                    if time.monotonic() >= deadline:
                        raise AssertionError('Voucher state did not complete: ' + str((result, vouchers)))
                    time.sleep(0.1)

        # Keep the failed first control as audit evidence, close it by normal
        # transitions, then use a genuinely new draft for the immediate-write proof.
        old = flow.state['entities'].get('voucher_race_draft')
        cleanup = None
        if old:
            previous = flow.call('GET', '/return-orders/' + old['id'])
            if previous['status'] == 'DRAFT':
                flow.create('voucher_race_submit', '/return-orders/' + old['id'] + '/submit', {})
                previous = flow.call('GET', '/return-orders/' + old['id'])
            if previous['status'] == 'SUBMITTED':
                flow.create('voucher_race_reject', '/return-orders/' + old['id'] + '/reject', {})
            elif previous['status'] != 'REJECTED':
                raise AssertionError('Unexpected old control state: ' + previous['status'])
            cleanup = read_voucher_state(old['id'], True)

        order = flow.create('voucher_inline_lock_draft', '/return-orders', {
            'returnType': 'SALES_RETURN', 'counterpartyId': physical['counterpartyId'],
            'sourceOrderId': physical['sourceOrderId'], 'returnDate': date.today().isoformat(),
            'withGoods': False, 'reason': 'P2本地并发验证：快速提交后驳回，不办理退款或库存',
            'items': [{'productTypeId': line['productTypeId'], 'itemName': line['itemName'],
                       'quantity': 1, 'unitPrice': line['unitPrice']}]})
        # No delay/retry around this fresh write; async voucher creation must coexist.
        flow.create('voucher_inline_lock_submit', '/return-orders/' + order['id'] + '/submit', {})
        submitted = read_voucher_state(order['id'])
        if submitted['order']['status'] != 'SUBMITTED':
            raise AssertionError('Voucher creation overwrote the submitted business status')
        flow.create('voucher_inline_lock_reject', '/return-orders/' + order['id'] + '/reject', {})
        rejected = read_voucher_state(order['id'], True)
        if rejected['order']['status'] != 'REJECTED':
            raise AssertionError('Control refund draft was not rejected')
        save(args.out / 'voucher-concurrency-control.json', {'submitted': submitted, 'rejected': rejected, 'previousFailedControlClosed': cleanup,
             'physicalInventoryWrites': 0, 'approvalOrSettlementPerformed': False})
        print('PASS: immediate submission and async voucher coexist; control draft rejected and voucher voided')
        return

    current = snapshot(args.queue_db)
    original = json.loads(baseline.read_text(encoding='utf-8'))
    checks = []
    evidence = {'checks': checks, 'database': current, 'api': {}}

    def check(name, condition, facts=None):
        checks.append({'name': name, 'pass': bool(condition), 'facts': facts})

    # The original full-equality failure is retained separately. A real startup
    # recompute appended one B version during the owned service restart; never
    # overwrite the baseline or silently disregard unexpected added rows.
    b_proof = b_version_proof(original['bVersions'], json.loads(json.dumps(current['bVersions'], default=str)),
        (args.out / 'java-voucher-race-before-fix.log').read_text(encoding='utf-8'))
    evidence['bIndicatorStartupAttribution'] = b_proof
    check('b_existing_versions_unchanged', b_proof['baselineRowsUnchanged'], b_proof)
    check('b_added_versions_proven_by_startup_log', b_proof['startupAdditionsExplained'], b_proof)
    check('production_counts_and_yield_unchanged', original['production'] == json.loads(json.dumps(current['production'], default=str)))
    check('original_inspection_samples_unchanged', original['inspections'] == json.loads(json.dumps(current['inspections'], default=str)))
    check('vision_queue_untouched', original['jobs'] == json.loads(json.dumps(current['jobs'], default=str)))
    for batch_id, key, quantity, unit in [(7, 'a', 4, 'kg'), (8, 'b', 10, '盒')]:
        recalls = flow.call('GET', '/quality-recalls?batchId=' + str(batch_id))
        check(key + '_one_event', len(recalls) == 1, recalls)
        if not recalls:
            continue
        detail = flow.call('GET', '/quality-recalls/' + str(recalls[0]['id']))
        evidence['api']['recall_' + key] = detail
        check(key + '_closed', detail['status'] == 'COMPLETED' and bool(detail['completedAt']), detail)
        shipments = detail['shipments']
        check(key + '_native_outbound_quantity', len(shipments) == 1 and Decimal(str(shipments[0]['quantity'])) == quantity and shipments[0]['unit'] == unit)
        if not shipments:
            continue
        shipment = shipments[0]
        check(key + '_actual_returned_all', Decimal(str(shipment['returnedQuantity'])) == quantity and Decimal(str(shipment['pendingReturnQuantity'])) == 0 and Decimal(str(shipment['remainingReturnQuantity'])) == 0)
        check(key + '_customer_evidence_recorded', shipment['acknowledged'])
        for summary in shipment['returnOrders']:
            order = flow.call('GET', '/return-orders/' + summary['id'])
            check(key + '_' + order['returnNumber'] + '_finance_and_receipt', order['status'] == 'COMPLETED' and order['withGoods'] and order['sourceOrderId'] == entities['verified_sales_order']['id'])
            rows = [row for row in current['returnedStock'] if row['batch_number'].startswith('RTN-' + order['returnNumber'] + '-')]
            check(key + '_' + order['returnNumber'] + '_isolated_native_stock', len(rows) == 1 and rows[0]['status'] == 'DEFECTIVE' and rows[0]['unit'] == unit and Decimal(rows[0]['produced_quantity']) == Decimal(str(summary['quantity'])), rows)
        closed = flow.call('POST', '/quality-recalls/' + str(detail['id']) + '/close', {'note': 'P2合成演练：重复结案应幂等'})
        check(key + '_repeat_close_same_completion', closed['id'] == detail['id'] and closed['completedAt'] == detail['completedAt'])
        evidence['api']['closed_editor_' + key] = reject(flow, 'PUT', '/canvas-food-safety/recalls/' + str(detail['id']), {'status': 'FROZEN', 'version': 0})
        trace = flow.call('GET', '/traceability/full/' + entities['verified_batch_' + key]['batchNumber'])
        check(key + '_original_shipment_history_retained', len(trace['shipments']) == 1 and Decimal(str(trace['shipments'][0]['quantity'])) == quantity and trace['shipments'][0]['unit'] == unit)
    today = date.today().isoformat()
    for code, expected in [('FACTORY_LU_YIELD_RATE', '90'), ('FACTORY_GOOD_OUTPUT_RATE', '90'), ('FACTORY_LU_UNIT_COST', '13.9256'), ('FACTORY_ACTUAL_TOTAL_COST', '501.32')]:
        value = flow.call('POST', f'/indicators/{code}/recompute?periodStart={today}&periodEnd={today}')
        check(code + '_not_rewritten_by_returns', value['status'] == 'AVAILABLE' and Decimal(str(value['value'])) == Decimal(expected), value)
    check('original_remaining_inventory_kept_isolated', len(current['finished']) == 2 and all(row['status'] == 'DEFECTIVE' for row in current['finished']), current['finished'])
    voucher_control = json.loads((args.out / 'voucher-concurrency-control.json').read_text(encoding='utf-8'))
    check('immediate_submit_survives_async_voucher', voucher_control['submitted']['order']['status'] == 'SUBMITTED' and voucher_control['submitted']['order']['vflag'] == 'CREATED', voucher_control['submitted'])
    check('control_refund_draft_rejected_and_voucher_voided', voucher_control['rejected']['order']['status'] == 'REJECTED' and len(voucher_control['rejected']['vouchers']) == 1 and voucher_control['rejected']['vouchers'][0]['status'] == 'VOID', voucher_control['rejected'])
    control_id = voucher_control['rejected']['order']['id']
    control_order = flow.call('GET', '/return-orders/' + control_id)
    check('control_refund_did_not_receive_goods', control_order['status'] == 'REJECTED' and control_order['withGoods'] is False and not any(row['batch_number'].startswith('RTN-' + control_order['returnNumber'] + '-') for row in current['returnedStock']))
    check('exception_review_has_actual_separate_approval', any(row['decision_made'] == 'HOLD' and row['approval_status'] == 'APPROVED' and row['executor_id'] == flow.actor_id and row['approver_id'] == 93003 and row['executor_id'] != row['approver_id'] for row in current['audit']), current['audit'])
    accounts = json.loads(args.credentials.read_text(encoding='utf-8'))
    actor = requests.post(flow.base + '/api/mobile/auth/unified-login', json={'username': 'daily_factory_b', 'password': accounts['password'], 'clientType': 'web'}, timeout=30).json()['data']
    check('real_b_actor', actor['factoryId'] == 'VISION-ENTRY-B')
    headers = {'Authorization': 'Bearer ' + (actor.get('token') or actor['accessToken'])}
    own = requests.get(flow.base + '/api/mobile/VISION-ENTRY-B/quality-recalls', headers=headers, timeout=30)
    cross = requests.post(flow.base + '/api/mobile/VISION-ENTRY-A/quality-recalls', headers=headers, json={'batchId': 7, 'reason': 'P2跨厂阴性对照'}, timeout=30)
    check('b_own_read_succeeds', own.status_code == 200 and own.json().get('success'))
    check('cross_factory_mutation_denied', cross.status_code == 403)
    foreign_event_id = str(evidence['api']['recall_a']['id'])
    foreign_path = requests.get(flow.base + '/api/mobile/VISION-ENTRY-A/quality-recalls/' + foreign_event_id, headers=headers, timeout=30)
    foreign_id = requests.get(flow.base + '/api/mobile/VISION-ENTRY-B/quality-recalls/' + foreign_event_id, headers=headers, timeout=30)
    check('cross_factory_recall_read_denied', foreign_path.status_code == 403 and foreign_id.status_code == 403)
    save(args.out / 'recall-acceptance.json', evidence)
    for item in checks:
        print(('PASS ' if item['pass'] else 'FAIL ') + item['name'])
    if any(not item['pass'] for item in checks):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
