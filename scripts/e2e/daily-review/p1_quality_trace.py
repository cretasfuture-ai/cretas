"""Local synthetic QC acceptance, reusing the normal business transport and API."""
from __future__ import annotations

import argparse
from datetime import date
from decimal import Decimal
import json
import sqlite3
from pathlib import Path

import psycopg

from p0_business_flow import BusinessFlow

LATE_NOTE = 'P1本地合成体验：出货后补录抽检，仅验证记录接线，不作为出货前放行证明'
FAIL_NOTE = 'P1本地合成体验：不合格库存隔离与出货拦截验证'


def database_snapshot(queue_path):
    with sqlite3.connect(queue_path.resolve().as_uri() + '?mode=ro', uri=True) as queue:
        queue.row_factory = sqlite3.Row
        jobs = [dict(row) for row in queue.execute('select job_id,factory_id,stage,status,attempts from vision_jobs order by job_id')]
    with psycopg.connect(host='127.0.0.1', port=55447, dbname='vision_entry_probe_20261015',
                         user='assembly_executor', options='-c default_transaction_read_only=on',
                         row_factory=psycopg.rows.dict_row) as db:
        return {
            'bVersions': db.execute('select * from indicator_versions where factory_id=%s order by id', ('VISION-ENTRY-B',)).fetchall(),
            'production': db.execute('select id,actual_quantity,good_quantity,defect_quantity,yield_rate from production_batches where factory_id=%s and id in (7,8) order by id', ('VISION-ENTRY-A',)).fetchall(),
            'finished': db.execute('select id,status,produced_quantity,shipped_quantity,reserved_quantity from finished_goods_batches where factory_id=%s and production_plan_id in (select production_plan_id from production_batches where factory_id=%s and id in (7,8)) and deleted_at is null order by id', ('VISION-ENTRY-A', 'VISION-ENTRY-A')).fetchall(),
            'jobs': jobs,
            'inspections': db.execute('select id,production_batch_id,inspector_id,result,sample_size,pass_count,fail_count,pass_rate,created_at from quality_inspections where factory_id=%s and production_batch_id in (7,8) and deleted_at is null order by id', ('VISION-ENTRY-A',)).fetchall(),
        }


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('step', choices=['prepare', 'verify'])
    parser.add_argument('--credentials', required=True, type=Path)
    parser.add_argument('--p0-state', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--queue-db', required=True, type=Path)
    parser.add_argument('--allow-test-writes', required=True, choices=['NON_PRODUCTION_ONLY'])
    args = parser.parse_args()
    source = json.loads(args.p0_state.read_text(encoding='utf-8'))
    if source.get('factoryId') != 'VISION-ENTRY-A':
        raise ValueError('Synthetic source factory mismatch')
    entities = source['entities']
    if [entities['verified_batch_' + k]['id'] for k in ['a', 'b']] != [7, 8]:
        raise ValueError('Only the established local synthetic sample 7/8 is allowed')
    flow = BusinessFlow('http://127.0.0.1:10060', args.credentials, args.out)
    baseline_path = args.out / 'qc-baseline.json'
    if not baseline_path.exists():
        save(baseline_path, database_snapshot(args.queue_db))

    if args.step == 'prepare':
        # Allocate a tiny synthetic outgoing shipment while A is still saleable.
        sku = entities['sku_a']
        order = flow.create('qc_order', '/sales/orders', {
            'customerId': 'REVIEW-TRACE-A-CUSTOMER', 'orderDate': date.today().isoformat(),
            'requiredDeliveryDate': date.today().isoformat(), 'processingMode': 'STANDARD_SALE',
            'materialSupplyMode': 'FACTORY_SUPPLIED', 'remark': FAIL_NOTE,
            'items': [{'productTypeId': sku['id'], 'quantity': 1, 'unit': sku['unit'],
                       'unitPrice': 30, 'taxRate': 0, 'sourceWarehouseCode': 'WH-FG'}]})
        confirmed = flow.create('qc_order_confirmed', '/sales/orders/' + order['id'] + '/confirm', {})
        if confirmed['status'] != 'FINANCE_APPROVED':
            raise RuntimeError('Existing order review prerequisite is not complete')
        order = flow.call('GET', '/sales/orders/' + order['id'])
        master = flow.create('qc_delivery_master', '/sales/deliveries', {
            'idempotencyKey': 'p1-qc-delivery-master', 'salesOrderId': order['id'],
            'customerId': 'REVIEW-TRACE-A-CUSTOMER', 'deliveryDate': date.today().isoformat(),
            'deliveryAddress': '仅本地合成体验，禁止真实发货', 'remark': FAIL_NOTE,
            'items': [{'salesOrderItemId': line['id'], 'productTypeId': sku['id'],
                       'deliveredQuantity': 1, 'unit': sku['unit'], 'unitPrice': 30,
                       'sourceWarehouseCode': 'WH-FG'} for line in order['items']]})
        master = flow.call('GET', '/sales/deliveries/' + master['id'])
        child = flow.create('qc_shipment', '/sales/deliveries/' + master['id'] + '/shipments', {
            'idempotencyKey': 'p1-qc-child', 'plannedShipmentDate': date.today().isoformat(),
            'deliveryMode': 'FULL', 'deliveryMethod': 'SELF_PICKUP', 'remark': FAIL_NOTE,
            'items': [{'parentDeliveryItemId': line['id'], 'quantity': 1} for line in master['items']]})
        child = flow.call('GET', '/sales/deliveries/' + child['id'])
        for line in child['items']:
            flow.create('qc_allocation_' + str(line['id']), '/sales-deliveries/items/' + str(line['id']) + '/batch-allocations', {
                'allocations': [{'finishedGoodsBatchId': entities['verified_finished_receipt_a']['finishedGoodsBatchId'], 'allocatedQty': 1}]})
        # Use the ordinary endpoint: it updates the batch and quarantines remaining stock.
        flow.create('qc_fail', '/processing/quality/inspections?batchId=7', {
            'sampleSize': 2, 'passCount': 0, 'failCount': 2, 'result': 'FAIL', 'notes': FAIL_NOTE})
        before = database_snapshot(args.queue_db)
        response = flow.http.post(flow.base + '/api/mobile/' + flow.factory + '/warehouse/deliveries/' + child['id'] + '/confirm',
                                  json={'actualQuantities': {str(line['id']): 1 for line in child['items']}}, timeout=60)
        body = response.json()
        after = database_snapshot(args.queue_db)
        result = {'httpStatus': response.status_code, 'body': body, 'before': before, 'after': after,
                  'delivery': flow.call('GET', '/sales/deliveries/' + child['id'])}
        save(args.out / 'failed-shipment-control.json', result)
        if response.status_code != 409 or body.get('success') or body.get('errorCode', body.get('code')) != 'FINISHED_GOODS_BATCH_NOT_SALEABLE':
            raise AssertionError('Expected actual saleability rejection; see failed-shipment-control.json')
        if before['finished'] != after['finished'] or result['delivery']['status'] == 'SHIPPED':
            raise AssertionError('Blocked shipment changed physical stock or delivery status')
        print('PASS: existing FAIL isolation and shipment rejection; stock unchanged')
        print('Next: use the actual UI to record BOX batch 8: sample5/pass5/fail0/PASS; note=' + LATE_NOTE)
        return

    baseline = json.loads(baseline_path.read_text(encoding='utf-8'))
    current = database_snapshot(args.queue_db)
    checks = []

    def check(name, condition, facts=None):
        checks.append({'name': name, 'pass': bool(condition), 'facts': facts})

    check('b_metric_history_unchanged', baseline['bVersions'] == json.loads(json.dumps(current['bVersions'], default=str)))
    check('production_counts_and_yield_unchanged_by_sampling', baseline['production'] == json.loads(json.dumps(current['production'], default=str)))
    check('training_jobs_untouched', baseline['jobs'] == json.loads(json.dumps(current['jobs'], default=str)))
    trace = flow.call('GET', '/traceability/full/' + entities['verified_batch_b']['batchNumber'])
    qi = [row for row in trace['qualityInspections'] if row['remarks'] == LATE_NOTE]
    check('one_actual_ui_supplementary_inspection', len(qi) == 1, qi)
    if qi:
        check('sample_counts_and_percentage', [qi[0][key] for key in ['sampleSize', 'passCount', 'failCount', 'passRate']] == [5, 5, 0, 100])
        check('authenticated_inspector_identifiable', qi[0]['inspectorId'] == flow.actor_id, {'inspectorId': qi[0]['inspectorId'], 'inspectorName': qi[0]['inspectorName']})
        persisted = [row for row in current['inspections'] if row['id'] == qi[0]['inspectionId']]
        check('actual_ui_record_persisted_in_business_database', len(persisted) == 1 and persisted[0]['production_batch_id'] == 8 and persisted[0]['inspector_id'] == flow.actor_id, persisted)
    check('trace_production_counts_22_20_2', [trace['production'][key] for key in ['quantity', 'goodQuantity', 'defectQuantity']] == [22, 20, 2])
    check('trace_quality_status_passed', trace['production']['qualityStatus'] == 'PASSED')
    check('real_outbound_still_10_boxes', len(trace['shipments']) == 1 and trace['shipments'][0]['quantity'] == 10 and trace['shipments'][0]['unit'] == '盒')
    bad = flow.call('GET', '/traceability/full/' + entities['verified_batch_a']['batchNumber'])
    check('failed_quality_visible_in_trace', bad['production']['qualityStatus'] == 'FAILED' and any(row['remarks'] == FAIL_NOTE and row['result'] == 'FAIL' for row in bad['qualityInspections']))
    check('failed_remaining_inventory_quarantined', any(row['id'] == entities['verified_finished_receipt_a']['finishedGoodsBatchId'] and row['status'] == 'DEFECTIVE' and Decimal(row['shipped_quantity']) == 4 for row in current['finished']))
    today = date.today().isoformat()
    for code, expected in [('FACTORY_LU_YIELD_RATE', '90'), ('FACTORY_GOOD_OUTPUT_RATE', '90'), ('FACTORY_LU_UNIT_COST', '13.9256'), ('FACTORY_ACTUAL_TOTAL_COST', '501.32')]:
        result = flow.call('POST', f'/indicators/{code}/recompute?periodStart={today}&periodEnd={today}')
        check(code + '_unchanged', result['status'] == 'AVAILABLE' and Decimal(str(result['value'])) == Decimal(expected), result)
    # Same tenant boundary, explicit real B actor and a successful own-factory read.
    accounts = json.loads(args.credentials.read_text(encoding='utf-8'))
    import requests
    data = requests.post(flow.base + '/api/mobile/auth/unified-login', json={'username': 'daily_factory_b', 'password': accounts['password'], 'clientType': 'web'}, timeout=30).json()['data']
    check('real_b_actor', data['factoryId'] == 'VISION-ENTRY-B')
    headers = {'Authorization': 'Bearer ' + (data.get('token') or data['accessToken'])}
    own = requests.get(flow.base + '/api/mobile/VISION-ENTRY-B/processing/quality/inspections', headers=headers, timeout=30)
    cross = requests.get(flow.base + '/api/mobile/VISION-ENTRY-A/processing/batches/8', headers=headers, timeout=30)
    check('b_own_read_succeeds', own.status_code == 200 and own.json().get('success'))
    check('cross_factory_batch_read_denied', cross.status_code == 403)
    save(args.out / 'quality-acceptance.json', {'checks': checks, 'trace': trace, 'failedTrace': bad, 'database': current})
    for item in checks:
        print(('PASS ' if item['pass'] else 'FAIL ') + item['name'])
    if any(not item['pass'] for item in checks):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
