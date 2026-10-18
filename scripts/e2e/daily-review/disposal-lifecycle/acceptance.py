"""P34: ordinary APIs, real PostgreSQL queues, no SQL business mutations."""
import os, sys, time, json
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP
from concurrent.futures import ThreadPoolExecutor

os.environ['DAILY_REVIEW_PROFILE'] = 'P34'
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'refund-close-concurrency'))
from support import *

assert len(sys.argv) == 3 and sys.argv[1] == 'NON_PRODUCTION_ONLY'
stage = sys.argv[2]
assert stage in ('init', 'guards', 'timeout', 'plus20', 'plus40', 'restore', 'verify')
assert not (OUT / (stage + '.json')).exists(), 'Completed stage: inspect receipt, never replay'
assert not any('javaagent' in a for a in args)
assert any(a.endswith('&options=-c%20lock_timeout%3D3s') for a in args)

def mutation(key, method, path, payload=None):
    if key not in steps:
        steps[key] = call(admin, method, path, payload or {})
        persist()
    return steps[key]

def negative(key, method, path, payload=None, status=409):
    if (OUT / (key + '.json')).exists():
        prior = json.loads((OUT / (key + '.json')).read_text(encoding='utf-8'))
        assert prior['response']['status'] == status and prior['before'] == prior['after']
        return prior['response']
    before = snapshot()
    response = send(admin, method, path, payload or {})
    after = snapshot()
    save(key + '.json', {'response': response, 'exactRollback': before == after,
                         'before': before, 'after': after})
    assert response['status'] == status and before == after, (key, response)
    return response

def opening(key):
    p = call(admin, 'GET', '/finance/accounting-periods/2026/10')
    if p['status'] == 'CLOSED':
        mutation(key, 'POST', '/finance/accounting-periods/reopen',
                 {'year': 2026, 'month': 10, 'reason': 'P34本地报废成本一致性验收'})
    else:
        assert p['status'] == 'OPEN', p

def active():
    with dbconnect() as d:
        rows = d.execute("select id from vouchers where factory_id=%s and deleted_at is null "
                         "and status='POSTED' and ((source_business_type='DISPOSAL_RECORD' and source_business_id='5') "
                         "or (source_business_type='DISPOSAL_RECORD_REVISION' and source_business_id like '5#r%%'))",
                         (FACTORY,)).fetchall()
    assert len(rows) == 1, rows
    return rows[0][0]

def endpoint(delta):
    return '/production-plans/' + f['plan'] + '/costs?otherCost=' + str(Decimal(f['otherCost']) + delta)

def waiters(count):
    end = time.monotonic() + 2
    while time.monotonic() < end:
        with dbconnect() as d:
            rows = d.execute("select pid,pg_blocking_pids(pid) from pg_stat_activity where "
                             "datname=current_database() and wait_event='advisory' "
                             "and query like '%pg_advisory_xact_lock%'").fetchall()
        if len(rows) == count:
            return rows
        time.sleep(.03)
    raise AssertionError('Missing real factory-lock waiters')

def queue(key, jobs):
    assert not (OUT / (key + '.json')).exists(), 'Inspect previous race instead of replaying'
    before = snapshot()
    save(key + '-before.json', before)
    with ThreadPoolExecutor(max_workers=2) as pool:
        with dbconnect(readonly=False) as blocker:
            blocker.execute('select pg_advisory_xact_lock(hashtextextended(%s,0))',
                            ('production-cost-finance:' + FACTORY,))
            first = pool.submit(send, admin, *jobs[0][1:])
            one = waiters(1)
            second = pool.submit(send, admin, *jobs[1][1:])
            both = waiters(2)
            assert not first.done() and not second.done()
            later = next(r for r in both if r[0] != one[0][0])
            assert one[0][0] in later[1], 'Second request did not queue behind first'
        results = {jobs[0][0]: first.result(), jobs[1][0]: second.result()}
    receipt = {'firstWaiter': one, 'bothWaiters': both, 'results': results,
               'before': before, 'after': snapshot()}
    save(key + '.json', receipt)
    return receipt

def check_history(previous, expected_loss, expected_unit_cost):
    history = call(admin, 'GET', '/disposal-records/5/vouchers')
    with dbconnect() as d:
        record = d.execute('select disposal_quantity,actual_loss,approved_by,approval_status,is_approved '
                           'from disposal_records where factory_id=%s and id=5', (FACTORY,)).fetchone()
        source = d.execute('select unit_cost from finished_goods_batches where factory_id=%s and id=%s',
                           (FACTORY, f['sourceBatch'])).fetchone()[0]
        children = d.execute("select b.id,b.unit_cost from finished_goods_batches b join return_order_items i "
                             "on i.id=b.return_order_item_id join return_orders r on r.id=i.return_order_id "
                             "where b.factory_id=%s and i.source_finished_goods_batch_id=%s and r.status='COMPLETED'",
                             (FACTORY, f['sourceBatch'])).fetchall()
        live = active()
        v = d.execute('select status,total_debit,total_credit from vouchers where id=%s', (live,)).fetchone()
        old = d.execute('select status,reversal_voucher_id from vouchers where id=%s', (previous,)).fetchone()
        red = d.execute('select status,original_voucher_id,source_business_type,source_business_id from vouchers '
                        'where id=%s', (old[1],)).fetchone()
        pair = d.execute('select subject_code,sum(debit-credit) from voucher_entries where voucher_id in (%s,%s) '
                         'and deleted_at is null group by subject_code', (previous, old[1])).fetchall()
        entries = d.execute('select subject_code,debit,credit from voucher_entries where voucher_id=%s '
                            'and deleted_at is null order by subject_code', (live,)).fetchall()
    assert record == (Decimal('.5'), expected_loss, 12, 'APPROVED', True), record
    assert source == expected_unit_cost, (source, expected_unit_cost)
    assert (record[0] * source).quantize(Decimal('.01'), rounding=ROUND_HALF_UP) == expected_loss
    assert len(children) >= 2 and all(x[1] == source for x in children), children
    assert v == ('POSTED', expected_loss, expected_loss), v
    assert old[0] == 'REVERSED' and red == ('POSTED', previous, 'VOUCHER_REVERSAL', previous), (old, red)
    assert len(pair) == 2 and all(x[1] == 0 for x in pair), pair
    assert entries == [('1405', Decimal(0), expected_loss), ('6711', expected_loss, Decimal(0))], entries
    return {'activeId': live, 'previousId': previous, 'redId': old[1], 'record': record,
            'sourceUnitCost': source, 'returnedBatches': children, 'entries': entries, 'history': history}

def close_and_check(key):
    p = call(admin, 'GET', '/finance/accounting-periods/2026/10')
    if p['status'] == 'OPEN':
        mutation(key + '-window', 'POST', '/finance/month-close/execute', {'year': 2026, 'month': 10})
    mutation(key + '-lock', 'POST', '/finance/accounting-periods/force-lock-close', {'year': 2026, 'month': 10})
    report = call(admin, 'GET', '/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
    with dbconnect() as d:
        shipped = d.execute("select coalesce(sum(e.quantity_used*b.unit_cost),0) from batch_lineage_edges e "
                            "join finished_goods_batches b on b.id=e.source_id and b.factory_id=e.factory_id "
                            "join sales_delivery_records r on r.id=e.target_id and r.factory_id=e.factory_id "
                            "where e.factory_id=%s and e.source_type='FINISHED_BATCH' and e.target_type='SHIPMENT_RECORD' "
                            "and e.edge_type='FINISHED_TO_SHIPMENT' and e.deleted_at is null and r.deleted_at is null "
                            "and r.status in ('SHIPPED','DELIVERED') and r.delivery_date between '2026-10-01' and '2026-10-31'",
                            (FACTORY,)).fetchone()[0]
        returned = d.execute("select coalesce(sum(i.quantity*b.unit_cost),0) from return_order_items i join return_orders r "
                             "on r.id=i.return_order_id join finished_goods_batches b on b.return_order_item_id=i.id "
                             "and b.factory_id=r.factory_id where r.factory_id=%s and r.status='COMPLETED' "
                             "and r.return_type='SALES_RETURN' and r.with_goods=true and r.return_date between '2026-10-01' "
                             "and '2026-10-31' and i.deleted_at is null and r.deleted_at is null and b.deleted_at is null",
                             (FACTORY,)).fetchone()[0]
        expense = d.execute("select sum(actual_loss) from disposal_records where factory_id=%s "
                            "and approval_status='APPROVED' and deleted_at is null", (FACTORY,)).fetchone()[0]
        book_expense = d.execute("select sum(e.debit-e.credit) from voucher_entries e join vouchers v on v.id=e.voucher_id "
                                 "where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null "
                                 "and v.voucher_type<>'PL_CLOSING' and e.deleted_at is null and e.subject_code='6711'", (FACTORY,)).fetchone()[0]
    cogs = (shipped - returned).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)
    assert Decimal(str(report['totalRevenue'])) == 720
    assert Decimal(str(report['totalCost'])) == cogs
    assert Decimal(str(report['totalExpense'])) == expense == book_expense
    assert Decimal(str(report['netProfit'])) == 720 - cogs - expense
    p = call(admin, 'GET', '/finance/accounting-periods/2026/10')
    frozen = json.loads(p['incomeStatementSnapshot'])
    assert p['status'] == 'CLOSED' and p['adjustWindowState'] == 'LOCKED' and p['closingPostedAt'] and p['reportReadyAt']
    for field in ('totalRevenue', 'totalCost', 'totalExpense', 'netProfit'):
        assert Decimal(str(frozen[field])) == Decimal(str(report[field])), (field, frozen, report)
    return {'income': report, 'period': p, 'shippedRaw': shipped, 'returnedRaw': returned,
            'expectedCogs': cogs, 'actualLoss': expense, 'bookExpense': book_expense}

if stage == 'init':
    save('source-before.json', snapshot(SOURCE))
    save('clone-before.json', snapshot())
    f = json.loads((OUT.parent / 'factory-data-p31/facts.json').read_text())
    with dbconnect() as d:
        f['otherCost'] = str(d.execute('select actual_other_cost from production_plans where id=%s', (f['plan'],)).fetchone()[0])
        f['unitCost'] = str(d.execute('select unit_cost from finished_goods_batches where id=%s', (f['sourceBatch'],)).fetchone()[0])
    f['activeId'] = active()
    f['metric'] = call(admin, 'GET', '/indicators/FACTORY_ACTUAL_TOTAL_COST/value?periodStart=2026-10-16&periodEnd=2026-10-16')
    save('facts.json', f)
    result = {'factory': FACTORY, 'admin': identity['userId'], 'activeId': f['activeId'], 'source': SOURCE}
else:
    f = json.loads((OUT / 'facts.json').read_text())
    if stage == 'guards':
        cases = []
        for label, vid in [('active', f['activeId']), ('original', '6295a4e9-44ca-4ada-93ed-928210b6b2ac'),
                           ('red', '5a819e48-b189-4856-b84b-498906c47fcd')]:
            cases.append(negative('locked-void-' + label, 'POST', '/finance/vouchers/' + vid + '/void', {'reason': '报废成本更正'}))
        cases.append(negative('locked-cost', 'PUT', endpoint(20)))
        cases.append(negative('approved-delete', 'DELETE', '/disposal-records/5', status=400))
        opening('guards-reopen')
        cases.append(negative('open-void-active', 'POST', '/finance/vouchers/' + active() + '/void', {'reason': '报废成本更正'}))
        # Real close-first queue: costs require OPEN; no cost side effects after closing wins.
        r = queue('close-first', [('close', 'POST', '/finance/month-close/execute', {'year': 2026, 'month': 10}),
                                  ('cost', 'PUT', endpoint(20), {})])
        assert r['results']['close']['status'] == 200 and r['results']['cost']['status'] == 409, r['results']
        closing_ids = {v['id'] for v in r['after']['vouchers'] if v['voucher_type'] in ('PL_CLOSING', 'COST_CARRYOVER')}
        def projection(s):
            return {t: ([v for v in rows if v['id'] not in closing_ids] if t == 'vouchers' else
                        [e for e in rows if e['voucher_id'] not in closing_ids] if t == 'voucher_entries' else rows)
                    for t, rows in s.items() if t != 'accounting_periods'}
        assert projection(r['before']) == projection(r['after']), 'Rejected cost changed business facts'
        opening('timeout-reopen')
        result = {'negativeCases': cases, 'closeFirstQueue': r['results'], 'rejectedCostNoSideEffects': True}
    elif stage == 'timeout':
        before = snapshot()
        save('timeout-before.json', before)
        start = time.monotonic()
        with ThreadPoolExecutor(max_workers=1) as pool:
            with dbconnect(readonly=False) as blocker:
                blocker_pid = blocker.execute('select pg_backend_pid()').fetchone()[0]
                blocker.execute('select id from vouchers where factory_id=%s and id=%s for update', (FACTORY, active()))
                future = pool.submit(send, admin, 'PUT', endpoint(20), {})
                end = time.monotonic() + 2.5
                waiting = []
                while time.monotonic() < end:
                    with dbconnect() as d:
                        waiting = d.execute('select pid,wait_event_type,query from pg_stat_activity where '
                                            'datname=current_database() and %s=any(pg_blocking_pids(pid))', (blocker_pid,)).fetchall()
                    if waiting:
                        break
                    time.sleep(.03)
                assert waiting, 'No actual voucher row-lock waiter'
                response = future.result(timeout=15)
        after = snapshot()
        save('timeout-detail.json', {'before': before, 'after': after, 'waiting': waiting, 'response': response})
        assert response['status'] >= 400 and not response['body']['success'] and before == after, response
        result = {'exactRollback26Tables': True, 'realWaiter': waiting, 'response': response,
                  'elapsedSeconds': time.monotonic() - start, 'testOnlyLockTimeout': '3s'}
    elif stage in ('plus20', 'plus40', 'restore'):
        delta = {'plus20': 20, 'plus40': 40, 'restore': 0}[stage]
        loss = {'plus20': Decimal('5.21'), 'plus40': Decimal('5.71'), 'restore': Decimal('4.71')}[stage]
        queue_key = stage + '-queue' if stage != 'restore' else 'cost-first-close'
        if (OUT / (queue_key + '.json')).exists():
            r = json.loads((OUT / (queue_key + '.json')).read_text(encoding='utf-8'))
            originals = [v for v in r['before']['vouchers'] if v['status'] == 'POSTED' and
                         ((v['source_business_type'] == 'DISPOSAL_RECORD' and v['source_business_id'] == '5') or
                          (v['source_business_type'] == 'DISPOSAL_RECORD_REVISION' and v['source_business_id'].startswith('5#r')))]
            assert len(originals) == 1
            previous = originals[0]['id']
        else:
            opening(stage + '-reopen')
            previous = active()
        if stage != 'restore' and not (OUT / (queue_key + '.json')).exists():
            jobs = [('cost', 'PUT', endpoint(delta), {}), ('void', 'POST', '/finance/vouchers/' + previous + '/void', {'reason': '报废成本更正'})]
            if stage == 'plus40':
                jobs.reverse()
            r = queue(stage + '-queue', jobs)
            assert r['results']['cost']['status'] == 200 and r['results']['void']['status'] == 409, r['results']
        elif not (OUT / (queue_key + '.json')).exists():
            r = queue('cost-first-close', [('cost', 'PUT', endpoint(delta), {}),
                                           ('close', 'POST', '/finance/month-close/execute', {'year': 2026, 'month': 10})])
            assert all(v['status'] == 200 for v in r['results'].values()), r['results']
        history = check_history(previous, loss, Decimal(f['unitCost']) + Decimal(delta) / 20)
        # Identical repeated cost after a new reopen must not create another accounting revision.
        if stage + '-repeat' not in steps:
            opening(stage + '-idempotent-reopen')
            before = snapshot()
            mutation(stage + '-repeat', 'PUT', endpoint(delta))
            after = snapshot()
            assert before['vouchers'] == after['vouchers'] and before['voucher_entries'] == after['voucher_entries']
            save(stage + '-idempotent.json', {'verdict': 'PASS', 'voucherIds': [v['id'] for v in after['vouchers']],
                                            'voucherEntryCount': len(after['voucher_entries'])})
        metric = call(admin, 'GET', '/indicators/FACTORY_ACTUAL_TOTAL_COST/value?periodStart=2026-10-16&periodEnd=2026-10-16')
        assert Decimal(str(metric['value'])) == Decimal(str(f['metric']['value'])) + delta, metric
        result = {'history': history, 'idempotentNoDuplicateVoucher': True, 'metric': metric,
                  'queue': r['results'], 'finance': close_and_check(stage)}
    else:
        negative('final-locked-cost', 'PUT', endpoint(20))
        negative('final-locked-void', 'POST', '/finance/vouchers/' + active() + '/void', {'reason': '报废成本更正'})
        before = snapshot()
        cross = admin.post('http://127.0.0.1:10070/api/mobile/VISION-ENTRY-B/finance/vouchers/' + active() + '/void',
                           json={'reason': 'P34跨厂反例'}, timeout=30)
        assert cross.status_code == 403 and before == snapshot(), cross.status_code
        with dbconnect() as d:
            balance = d.execute('select current_balance from customers where factory_id=%s and id=%s', (FACTORY, f['customer'])).fetchone()[0]
            cash = d.execute("select sum(e.debit-e.credit) from voucher_entries e join vouchers v on v.id=e.voucher_id "
                             "where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null "
                             "and e.deleted_at is null and e.subject_code in ('1001','1002','1012')", (FACTORY,)).fetchone()[0]
            pending = d.execute("select count(*) from vouchers where factory_id=%s and status='DRAFT' and deleted_at is null", (FACTORY,)).fetchone()[0]
        assert balance == cash == pending == 0, (balance, cash, pending)
        baseline = json.loads((OUT / 'clone-before.json').read_text(encoding='utf-8'))
        final = snapshot()
        for table in ('customers', 'payment_requests', 'payment_records', 'ar_ap_transactions',
                      'material_batches', 'material_batch_adjustments',
                      'sales_delivery_records', 'sales_delivery_items', 'sales_delivery_item_batch_allocations'):
            assert baseline[table] == final[table], 'Cost correction changed unrelated facts: ' + table
        old_logs = {row['id']: row for row in baseline['finished_goods_adjustment_log']}
        final_logs = {row['id']: row for row in final['finished_goods_adjustment_log']}
        assert all(final_logs.get(k) == v for k, v in old_logs.items()), 'Historical stock evidence changed'
        new_logs = [v for k, v in final_logs.items() if k not in old_logs]
        assert len(new_logs) >= 6
        for row in new_logs:
            assert row['reference_type'] == 'COST_CORRECTION' and row['factory_id'] == FACTORY and row['operator_id'] == 2
            assert Decimal(str(row['adjustment_quantity'])) == 0 and row['before_produced'] == row['after_produced']
            assert ('源批次=' + f['sourceBatch'] + ';') in row['reason']
        def physical(rows):
            return [{k: v for k, v in row.items() if 'quantity' in k or k in
                     ('id', 'status', 'approved_by', 'approved_at', 'is_approved', 'approval_status', 'actual_loss')}
                    for row in rows]
        assert physical(baseline['finished_goods_batches']) == physical(final['finished_goods_batches'])
        assert physical(baseline['disposal_records']) == physical(final['disposal_records'])
        cash_reports = []
        for month, expected in ((10, 15), (11, 0)):
            cash_report = call(admin, 'GET', '/finance/report/cash-flow?startYear=2026&startMonth=' + str(month)
                               + '&endYear=2026&endMonth=' + str(month))
            assert Decimal(str(cash_report['endingCash'])) == expected, cash_report
            cash_reports.append(cash_report)
        result = {'customerBalance': balance, 'netBookCash': cash, 'pendingVouchers': pending,
                  'physicalAndApprovalFactsRestored': True, 'unrelatedFinancialFactsUnchanged': True,
                  'newQuantityNeutralCostAuditLogs': new_logs,
                  'monthlyCashFlows': cash_reports, 'crossTenantStatus': cross.status_code, 'activeId': active(),
                  'history': call(admin, 'GET', '/disposal-records/5/vouchers')}

assert snapshot(SOURCE) == json.loads((OUT / 'source-before.json').read_text(encoding='utf-8')), 'Original P31 mutated'
save(stage + '.json', {'verdict': 'PASS', 'stage': stage, 'result': result,
                      'sourceExactUnchanged': True, 'sqlBusinessDml': 0})
print('P34 ' + stage + ' PASS')
