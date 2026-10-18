"""Real locked-period HTTP rejection/rollback and controlled-reopen acceptance."""
from pathlib import Path
import argparse
import importlib.util
import json
import sys

parser = argparse.ArgumentParser()
parser.add_argument('ack', choices=['NON_PRODUCTION_ONLY'])
parser.add_argument('--case', choices=['posted', 'draft'], required=True)
parser.add_argument('--stage', choices=['blocked', 'complete'], required=True)
args = parser.parse_args()

# Reuse the owned loopback/DB guards, real logins, resumable BusinessFlow,
# business endpoints and independent PostgreSQL assertions from P10.
spec = importlib.util.spec_from_file_location('p11_existing_support', Path(__file__).with_name('acceptance.py'))
s = importlib.util.module_from_spec(spec)
original_argv = sys.argv
try:
    sys.argv = [str(spec.origin), 'NON_PRODUCTION_ONLY']
    spec.loader.exec_module(s)
finally:
    sys.argv = original_argv

OUT = s.OUT / 'locked-period'
OUT.mkdir(exist_ok=True)
TABLES = ['payment_requests', 'payment_records', 'ar_ap_transactions', 'vouchers',
          'voucher_entries', 'customers', 'return_orders', 'return_order_items',
          'sales_orders', 'sales_order_items', 'sales_delivery_records',
          'sales_delivery_items', 'sales_delivery_item_batch_allocations',
          'accounting_periods', 'finished_goods_batches', 'material_batches']


def save(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')


def collect(database):
    assert database in [s.runtime['database'], 'vision_entry_probe_20261015']
    result = {}
    with s.psycopg.connect(host='127.0.0.1', port=55447, dbname=database,
                         user=s.db_credentials['username'], password=s.db_credentials['password'],
                         options='-c default_transaction_read_only=on') as db:
        assert db.execute('select current_database()').fetchone()[0] == database
        for table in TABLES:
            if db.execute('select to_regclass(%s)', (table,)).fetchone()[0]:
                result[table] = {str(r[0]['id']): r[0] for r in
                                 db.execute('select row_to_json(t) from ' + table + ' t order by id')}
    return result


if not (OUT / 'baseline.json').exists():
    save('baseline.json', {'clone': collect(s.runtime['database']),
                          'source': collect('vision_entry_probe_20261015')})

key = 'p11-' + args.case
posted = args.case == 'posted'
paid_date, reversed_date, month = ('2026-10-31', '2026-11-01', 10) if posted else ('2026-11-30', '2026-12-01', 11)
period_path = '/finance/accounting-periods'
period_payload = {'year': 2026, 'month': month}
reason = f'仅本地锁账演练：{paid_date}退款、{reversed_date}撤销；不实际转账'


def step(name, session, actor, path, payload):
    return s.step(key + '-' + name, session, actor, path, payload)


def blocked_call(name, path, payload):
    if key + '-' + name in s.e:
        return
    before = collect(s.runtime['database'])
    response, body = s.send(s.finance, 'daily_finance_a', 'POST', path, payload)
    assert response.status_code == 400 and not body.get('success'), (response.status_code, body)
    assert f'2026-{month:02d}' in body.get('message', '') and '期间已结账' in body.get('message', ''), body
    after = collect(s.runtime['database'])
    save(key + '-' + name + '.json', {'status': 'PASS', 'response': body, 'httpStatus': response.status_code,
                                     'before': before, 'after': after,
                                     'allFinanceAndStockRowsExactUnchanged': before == after})
    assert before == after, 'Rejected transaction leaked financial or stock changes'
    s.e[key + '-' + name] = {'status': 'PASS', 'httpStatus': response.status_code}
    s.flow.persist()


def prepare():
    s.phase(paid_date)
    if key + '-initial' not in s.e:
        s.e[key + '-initial'] = s.snapshot(key + '-initial', 525, 15, 2026, month)
        s.flow.persist()
    step('refund', s.flow.http, 'daily_factory_a', s.base, {'amount': 15, 'remark': reason})
    step('submit', s.flow.http, 'daily_factory_a', s.base + '/submit', {})
    step('approved', s.finance, 'daily_finance_a', s.base + '/approve', {})
    paid = step('paid', s.finance, 'daily_finance_a', s.base + '/mark-paid', {
        'paymentMethod': 'BANK_TRANSFER', 'paymentReference': 'P11-' + args.case.upper() + '-REFUND-15', 'remark': reason})
    request_id = paid['request']['id']
    voucher_id = paid['request']['voucher']['id']
    if posted:
        step('posted', s.finance, 'daily_finance_a', '/finance/vouchers/' + voucher_id + '/post', {})
    original_path = s.OUT / (key + '-original-before.json')
    if not original_path.exists():
        s.save(original_path.name, s.original_rows(request_id))
    step('period-open', s.finance, 'daily_finance_a', period_path + '/open', period_payload)
    step('period-request-close', s.finance, 'daily_finance_a', period_path + '/request-close', period_payload)
    step('period-close', s.finance, 'daily_finance_a', period_path + '/confirm-close', period_payload)
    s.phase(reversed_date)
    period = s.flow.call('GET', period_path + f'/2026/{month}')
    assert period['status'] == 'CLOSED' and period['adjustWindowState'] == 'LOCKED', period
    assert period['closedBy'] == 1 and period['closedAt'].startswith(paid_date)
    save(key + '-locked-period.json', period)
    pending = step('pending', s.flow.http, 'daily_factory_a', s.base + '/reversal', {
        'requestId': request_id, 'paymentReference': 'P11-' + args.case.upper() + '-RECOVERY-15',
        'reason': reason + '，原款已收回，申请撤销'})
    decision = {'requestId': request_id, 'reversalTransactionId': pending['request']['reversal']['ledger']['id']}
    blocked_call('locked-approve-first', s.base + '/reversal/approve', decision)
    blocked_call('locked-approve-repeat', s.base + '/reversal/approve', decision)
    if not posted:
        blocked_call('locked-direct-post', '/finance/vouchers/' + voucher_id + '/post', {})
    view = s.flow.call('GET', '/return-refunds/' + request_id)
    request = view['request']
    assert request['status'] == 'PAID' and request['reversal']['status'] == 'PENDING'
    assert request['voucher']['status'] == ('POSTED' if posted else 'DRAFT')
    assert request['reversal']['voucher'] is None
    rows = s.dbread('select approval_status,approved_at,approved_by from ar_ap_transactions where factory_id=%s and id=%s',
                    (s.FACTORY, decision['reversalTransactionId']))
    assert rows == [('PENDING', None, None)], rows
    assert s.dbread("select count(*) from vouchers where factory_id=%s and source_business_type='VOUCHER_REVERSAL' and source_business_id=%s",
                    (s.FACTORY, voucher_id))[0][0] == 0
    original = json.loads(original_path.read_text(encoding='utf-8'))
    assert original == s.original_rows(request_id), 'Failed approval changed the original refund records'
    balance = s.snapshot(key + '-blocked-balance', 540, 0 if posted else 15, 2026, month)
    save(key + '-blocked-final.json', {'status': 'PASS', 'period': period, 'refund': view,
                                      'balance': balance, 'decision': decision, 'retries': 2,
                                      'sqlBusinessDml': 0, 'originalRowsExactUnchanged': True})
    return decision


def complete(decision):
    reopened = step('period-reopen', s.finance, 'daily_finance_a', period_path + '/reopen', {
        **period_payload, 'reason': reason + '；确认锁账拦截及事务回滚后，反结账重试同一撤销'})
    assert reopened['status'] == 'OPEN' and reopened['adjustWindowState'] == 'NOT_CLOSED'
    assert reopened['reopenedBy'] == 1 and reopened['reopenedAt'].startswith(reversed_date)
    step('reversed', s.finance, 'daily_finance_a', s.base + '/reversal/approve', decision)
    s.snapshot(key + '-recovered-balance', 525, 15, 2026, month)
    view = s.inspect_case(key, decision['requestId'], paid_date, reversed_date, posted)
    statement = s.flow.call('GET', f'/finance/statement?counterpartyType=CUSTOMER&counterpartyId={s.CUSTOMER}&startDate={reversed_date}&endDate={reversed_date}')
    assert statement['openingBalance'] == statement['closingBalance'] == 525
    assert statement['periodDebit'] == statement['periodCredit'] == 0
    after_month = 11 if posted else 12
    s.snapshot(key + '-next-month-balance', 525, 15, 2026, after_month)
    save(key + '-completed.json', {'status': 'PASS', 'refund': view, 'reopenedPeriod': reopened,
                                  'nextMonthStatement': statement, 'sameDecisionAfterReopen': decision,
                                  'finalReceivable': 525, 'finalPostedCash': 15})
    s.e[key + '-complete'] = {'status': 'PASS', 'requestId': decision['requestId']}
    s.flow.persist()


def protect_after():
    before = json.loads((OUT / 'baseline.json').read_text(encoding='utf-8'))
    assert before['source'] == collect('vision_entry_probe_20261015'), 'Original experience source changed'
    after = collect(s.runtime['database'])
    changes = []
    additions = {}
    for table, rows in before['clone'].items():
        for row_id, row in rows.items():
            actual = after[table].get(row_id)
            assert actual is not None, (table, row_id, 'deleted')
            fields = [name for name in row if row[name] != actual.get(name)]
            allowed = {'updated_at', 'version'} if table == 'customers' and row_id == s.CUSTOMER else set()
            assert set(fields) <= allowed, (table, row_id, fields)
            if fields:
                changes.append({'table': table, 'id': row_id, 'fields': fields})
        additions[table] = [row_id for row_id in after[table] if row_id not in rows]
    save('protected-readback.json', {'status': 'PASS', 'originalSourceExactUnchanged': True,
                                    'p10PriorRecordsPreserved': True, 'changes': changes,
                                    'newRowIds': additions, 'sqlBusinessDml': 0})


if key + '-complete' not in s.e:
    decision = prepare()
    if args.stage == 'complete':
        complete(decision)

if all('p11-' + case + '-complete' in s.e for case in ['posted', 'draft']):
    protect_after()
    save('business-final.json', {'status': 'PASS', 'cases': ['posted', 'draft'],
                                'periodLockedHttpRejection': True, 'exactFailedTransactionRollback': True,
                                'sameApprovalPassesAfterExplicitReopen': True, 'finalReceivable': 525,
                                'finalPostedCash': 15, 'productionWrites': 0, 'realPayments': 0})
print(json.dumps({'status': 'PASS', 'case': args.case, 'stage': args.stage, 'factory': s.FACTORY,
                  'complete': key + '-complete' in s.e}))
