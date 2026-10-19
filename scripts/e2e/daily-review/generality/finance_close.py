"""P37-only financial acceptance, using existing business APIs and read-only SQL."""
from pathlib import Path
import sys
import json
import hashlib
import xml.etree.ElementTree as ET
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import acceptance as a

s = a.support
OUT = a.OUT / 'finance-acceptance-20261019'
OUT.mkdir(exist_ok=True)
PERIOD = '?periodStart=2026-10-01&periodEnd=2026-10-31'
a.WRITE_PREFIX = 'finance-close-'


def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    return value


def read(path):
    return s.call(s.admin, 'GET', path)


def init():
    matrix = OUT / 'scenarios.json'
    if not matrix.exists():
        save('scenarios', [{'id': code, 'role': '新厂管理员及本厂财务复核人', 'flow': flow,
            'status': '未测', 'actions': actions, 'readback': checks, 'negativeControl': negative}
            for code, flow, actions, checks, negative in [
                ('F1', '收入审批与过账', '核对来源审批、正常审核及过账', '凭证分录、审批人与应收', '未审批/重复过账不得重复记账'),
                ('F2', '出货成本结转', '复用月结成本结转及凭证', '2盒×8元=16元及成本分录', '重复结转不得重复记账'),
                ('F3', '完整月利润表与经营分析', '按2026年10月查询并核对', '销售收入、销售成本、毛利、费用、净利润、生产指标', '其他工厂及旧版本业务不变')]])
    baseline = OUT / 'baseline.json'
    if baseline.exists():
        return print('Existing baseline retained; do not replay setup')
    snap = s.snapshot()
    save('baseline', snap)
    save('source-before', s.snapshot(s.SOURCE))
    with s.dbconnect() as d:
        pending = d.execute("select row_to_json(v) from vouchers v where factory_id=%s and status='DRAFT' and deleted_at is null and voucher_date between '2026-10-01' and '2026-10-31' order by voucher_number", (a.FACTORY,)).fetchall()
        orders = d.execute('select row_to_json(o) from sales_orders o where factory_id=%s and deleted_at is null order by created_at', (a.FACTORY,)).fetchall()
    result = save('initial-readback', {'identity': {k:s.identity.get(k) for k in ('userId','username','factoryId','role')},
        'period': read('/finance/accounting-periods/2026/10'),
        'income': read('/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10'),
        'margin': read('/finance/report/shipment-sku-margin' + PERIOD),
        'production': read('/production/actual-metrics/by-product' + PERIOD),
        'pending': [x[0] for x in pending], 'orders': [x[0] for x in orders]})
    print(json.dumps({'status': 'INIT', 'identity': result['identity'],
        'period': result['period']['status'], 'pending': [{k:v.get(k) for k in ('id','voucher_number','source_business_type','source_business_id','total_debit')} for v in result['pending']],
        'income': result['income'], 'margin': {k:result['margin'].get(k) for k in ('totalRevenue','totalCost','grossProfit','reconciliationStatus')}}, ensure_ascii=False))


def inspect():
    with s.dbconnect() as d:
        gl = d.execute("""select v.source_business_type,e.subject_code,sum(e.debit-e.credit),count(*)
            from vouchers v join voucher_entries e on e.voucher_id=v.id where v.factory_id=%s
            and v.deleted_at is null and e.deleted_at is null and v.status in ('POSTED','REVERSED')
            and v.voucher_date between '2026-10-01' and '2026-10-31' and e.subject_code like '6%%'
            group by v.source_business_type,e.subject_code order by 1,2""", (a.FACTORY,)).fetchall()
        returns = d.execute("""select r.id,r.return_number,r.total_amount,r.status,v.status,v.voucher_date,
            v.total_debit from return_orders r left join vouchers v on v.factory_id=r.factory_id
            and v.source_business_type='RETURN_ORDER' and v.source_business_id=r.id and v.deleted_at is null
            where r.factory_id=%s and r.deleted_at is null and r.status='COMPLETED'
            order by r.return_number""", (a.FACTORY,)).fetchall()
        orders = d.execute("""select o.id,o.order_number,o.status,o.total_amount,v.id,v.voucher_number,v.status,v.total_debit,
            (select sum(r.total_amount) from sales_delivery_records r where r.factory_id=o.factory_id
            and r.sales_order_id=o.id and r.status in ('SHIPPED','DELIVERED') and coalesce(r.record_role,'LEGACY')<>'MASTER'
            and r.deleted_at is null) from sales_orders o left join vouchers v on v.factory_id=o.factory_id
            and v.source_business_id=o.id and v.source_business_type='SALES_ORDER' and v.deleted_at is null
            where o.factory_id=%s and o.deleted_at is null order by o.order_number""",(a.FACTORY,)).fetchall()
        negative_ar = d.execute('select row_to_json(t) from ar_ap_transactions t where factory_id=%s and sales_order_id=%s and deleted_at is null', (a.FACTORY,'32d72d68-3417-4068-800d-d3e322e09984')).fetchall()
        missing = d.execute('select row_to_json(t) from vouchers t where factory_id=%s and source_business_id=%s', (a.FACTORY,'b9745fbd-33ad-4de7-90f3-1d5e9d4f44c3')).fetchall()
    save('source-inspection', {'gl': gl, 'returns': returns, 'orders': orders, 'negativeAr': negative_ar, 'missingVoucher': missing})
    print(json.dumps({'gl':gl,'returns':returns,'orders':orders,'negativeAr':negative_ar,'missingVoucher':missing},ensure_ascii=False,default=str))


def prepare_posting():
    original = read('/finance/vouchers/91d68825-23d5-4ef0-9ab2-6aa9ed989277')
    assert original['status'] == 'POSTED' and Decimal(str(original['totalDebit'])) == 30
    order = read('/sales/orders/ce991bda-a7c4-4dda-803c-e212a549b2fd')
    assert order['status'] == 'COMPLETED'
    positive = read('/sales/orders/b9745fbd-33ad-4de7-90f3-1d5e9d4f44c3')
    assert positive['status'] == 'COMPLETED' and Decimal(str(positive['totalAmount'])) == 15
    generated = a.write_once('recover-positive-unit-voucher', '/finance/vouchers/generate', {
        'businessType':'SALES_ORDER','businessId':positive['id']})
    negative = read('/sales/orders/32d72d68-3417-4068-800d-d3e322e09984')
    assert negative['status'] == 'FINANCE_APPROVED' and all(Decimal(str(x['deliveredQuantity'])) == 0 for x in negative['items'])
    with s.dbconnect() as d:
        assert d.execute('select count(*) from ar_ap_transactions where factory_id=%s and sales_order_id=%s and deleted_at is null', (a.FACTORY, negative['id'])).fetchone()[0] == 0
    save('posting-sources', {'newRevenue': original, 'newOrder':order, 'positiveOrder':positive,
        'recoveredVoucher':generated, 'unshippedNegativeOrder':negative,
        'negativeDecision':'无实际出货、无应收、无扣库的单位阴性样本；正常作废草稿，不确认为营业收入'})
    print(json.dumps({'newRevenueStatus':original['status'],'positiveVoucher':{k:generated.get(k) for k in ('id','voucherNumber','status')},'negativeHasNoShipmentOrAr':True}, ensure_ascii=False))


def post_readback():
    sources = json.loads((OUT / 'posting-sources.json').read_text(encoding='utf-8'))
    ids = ['91d68825-23d5-4ef0-9ab2-6aa9ed989277','11d24fe2-78f3-4b90-80fe-29c9b8d454af',sources['recoveredVoucher']['id']]
    vouchers = [read('/finance/vouchers/' + vid) for vid in ids]
    assert all(v['status'] == 'POSTED' and v['approvedBy'] == s.identity['userId'] for v in vouchers)
    void = read('/finance/vouchers/7a5ed84c-3078-4165-b4f6-50598d050150')
    assert void['status'] == 'VOID' and '未出货' in void['description']
    before = s.snapshot()
    rejected = s.send(s.admin, 'POST', '/finance/vouchers/' + ids[0] + '/post', {})
    assert rejected['status'] == 409 and before == s.snapshot(), rejected
    save('duplicate-post-rejected', rejected)
    customer = sources['newOrder']['customerId']
    fin = {'income': read('/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10'),
        'statement': read('/finance/statement?counterpartyType=CUSTOMER&counterpartyId=' + customer + '&startDate=2026-10-01&endDate=2026-10-31'),
        'cash': read('/finance/report/cash-flow?startYear=2026&startMonth=10&endYear=2026&endMonth=10')}
    with s.dbconnect() as d:
        # The copy contains legitimate November reversal history. October reports
        # must be checked against October closing balances, not all-time GL.
        cash = d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code in ('1001','1002','1012') and v.voucher_date<='2026-10-31'",(a.FACTORY,)).fetchone()[0]
        ar_month = d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code='1122' and e.auxiliary_entity_id=%s and v.voucher_date<='2026-10-31'",(a.FACTORY,customer)).fetchone()[0]
        ar_current = d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code='1122' and e.auxiliary_entity_id=%s",(a.FACTORY,customer)).fetchone()[0]
        balance = d.execute('select current_balance from customers where factory_id=%s and id=%s',(a.FACTORY,customer)).fetchone()[0]
    assert Decimal(str(fin['cash']['endingCash'])) == cash
    assert Decimal(str(fin['statement']['closingBalance'])) == ar_month
    assert balance == ar_current
    fin.update(octoberCash=cash,octoberAr=ar_month,currentCustomerBalance=balance,currentBookAr=ar_current)
    assert Decimal(str(fin['income']['totalRevenue'])) == 765 and fin['income']['pendingDraftVoucherCount'] == 0
    save('posting-readback', {'vouchers': vouchers, 'void': void, 'finance': fin, 'duplicatePostRollback': True})
    matrix = json.loads((OUT / 'scenarios.json').read_text())
    matrix[0].update(status='通过', evidence=['posting-readback.json','duplicate-post-rejected.json','revenue-posted.png','negative-void.png'])
    save('scenarios',matrix)
    print(json.dumps({'status':'PASS','scenario':'F1','posted':3,'voidNegative':1,'monthlyNetRevenue':765,'subledgerGlConsistent':True}))


def close():
    assert (OUT / 'posting-readback.json').exists()
    private = json.loads((a.OUT.parent / 'factory-data-p30/finance-credentials.private.json').read_text())
    finance, actor = s.login(private['username'],private['password'])
    assert actor['userId'] == 12 and actor['role'] == 'finance_manager'
    assert actor['userId'] != s.identity['userId'] and actor['factoryId'] == a.FACTORY
    p = read('/finance/accounting-periods/2026/10')
    key = 'finance-accept-20261019-confirm-close'
    if key not in s.steps:
        assert p['status'] == 'PENDING_CLOSE' and not p.get('approvalWorkflowInstanceId'), 'Use the configured OA workflow if present'
    confirmed = s.step(key,finance,'/finance/accounting-periods/confirm-close',{'year':2026,'month':10})
    assert confirmed['closedBy'] == actor['userId']
    locked = s.step('finance-accept-20261019-final-carryover',finance,'/finance/accounting-periods/force-lock-close',{'year':2026,'month':10})
    assert locked['closingPostedAt'] and locked['adjustWindowState'] == 'LOCKED'
    with s.dbconnect() as d:
        gross = d.execute("select sum(e.quantity_used*b.unit_cost) from batch_lineage_edges e join finished_goods_batches b on b.id=e.source_id and b.factory_id=e.factory_id join sales_delivery_records r on r.id=e.target_id and r.factory_id=e.factory_id where e.factory_id=%s and e.edge_type='FINISHED_TO_SHIPMENT' and e.deleted_at is null and r.deleted_at is null and r.status in ('SHIPPED','DELIVERED') and r.delivery_date between '2026-10-01' and '2026-10-31'", (a.FACTORY,)).fetchone()[0]
        returns = d.execute("select coalesce(sum(i.quantity*b.unit_cost),0) from return_order_items i join return_orders r on r.id=i.return_order_id join finished_goods_batches b on b.return_order_item_id=i.id and b.factory_id=r.factory_id where r.factory_id=%s and r.status='COMPLETED' and r.return_type='SALES_RETURN' and r.with_goods=true and r.return_date between '2026-10-01' and '2026-10-31' and i.deleted_at is null and r.deleted_at is null and b.deleted_at is null", (a.FACTORY,)).fetchone()[0]
        expense = d.execute("select sum(actual_loss) from disposal_records where factory_id=%s and approval_status='APPROVED' and deleted_at is null and disposal_date between '2026-10-01' and '2026-10-31 23:59:59'", (a.FACTORY,)).fetchone()[0]
        vouchers = d.execute("select row_to_json(v) from vouchers v where factory_id=%s and source_business_type in ('COST_CARRYOVER','PL_CLOSING') and status='POSTED' and deleted_at is null and voucher_date='2026-10-31' order by voucher_number", (a.FACTORY,)).fetchall()
    cost = (gross-returns).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)
    income = read('/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
    assert Decimal(str(income['totalRevenue'])) == 765
    assert Decimal(str(income['totalCost'])) == cost
    assert Decimal(str(income['totalExpense'])) == expense
    assert Decimal(str(income['netProfit'])) == 765-cost-expense
    assert len(vouchers) == 2 and income['pendingDraftVoucherCount'] == 0 and income['costDataAvailable']
    frozen = json.loads(locked['incomeStatementSnapshot'])
    for field in ('totalRevenue','totalCost','totalExpense','grossProfit','netProfit'):
        assert Decimal(str(income[field])) == Decimal(str(frozen[field]))
    before = s.snapshot()
    again = s.call(finance,'POST','/finance/accounting-periods/force-lock-close',{'year':2026,'month':10})
    after = s.snapshot()
    save('repeat-carryover',{'before':before,'after':after,'firstPostedAt':locked['closingPostedAt'],'readPostedAt':again['closingPostedAt']})
    assert before == after
    assert datetime.fromisoformat(again['closingPostedAt']) == datetime.fromisoformat(locked['closingPostedAt'])
    margin = read('/finance/report/shipment-sku-margin'+PERIOD)
    save('before-fix-full-month-margin',margin)
    save('closing-readback', {'reviewer': {k:actor.get(k) for k in ('userId','username','factoryId','role')},
        'period':locked,'income':income,'vouchers':[x[0] for x in vouchers],
        'unroundedGrossCost':gross,'unroundedReturnedCost':returns,'expectedNetCost':cost,
        'expense':expense,'repeatCarryoverNoMutation':True,'sourceDatabase':s.SOURCE})
    matrix = json.loads((OUT / 'scenarios.json').read_text())
    matrix[1].update(status='通过',evidence=['closing-readback.json'])
    matrix[2].update(status='失败',defect='FIN-MARGIN-RETURN-BASIS',evidence=['before-fix-full-month-margin.json'])
    save('scenarios',matrix)
    print(json.dumps({'status':'PASS','scenario':'F2','closedBy':actor['userId'],'cost':str(cost),
        'income':income,'vouchers':[{k:x[0].get(k) for k in ('id','voucher_number','total_debit','source_business_type')} for x in vouchers],
        'preFixReconciliation':margin['reconciliationStatus']},ensure_ascii=False))


def prepare_scope_schema():
    # Complete the historical clone with the repository's existing V64 DDL.
    # No permission rows, business rows or Flyway history are rewritten.
    before = s.snapshot()
    path = s.ROOT / 'backend/java/cretas-api/src/main/resources/db/flyway/V20261103_64__factory_role_data_scopes.sql'
    with s.dbconnect(readonly=False) as d:
        history = d.execute('select row_to_json(h) from flyway_schema_history h order by installed_rank').fetchall()
        exists = d.execute('select to_regclass(%s)',('factory_role_data_scopes',)).fetchone()[0]
        if exists is None:
            d.execute(path.read_text(encoding='utf-8'))
        assert d.execute('select count(*) from factory_role_data_scopes').fetchone()[0] == 0
        assert history == d.execute('select row_to_json(h) from flyway_schema_history h order by installed_rank').fetchall()
    assert s.snapshot() == before
    save('existing-v64-schema', {'existingMigration':str(path.relative_to(s.ROOT)), 'appliedNow':exists is None,
        'database':s.DATABASE,'businessRowsUnchanged':True,'permissionOverridesWritten':0,'flywayHistoryModified':False,
        'fullFlywayUpgradeClaimed':False})
    print('Existing V64 schema ready; no business or permission rows changed')


def verify():
    closed = json.loads((OUT / 'closing-readback.json').read_text())
    income = read('/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
    margin = read('/finance/report/shipment-sku-margin'+PERIOD)
    save('after-fix-full-month-margin',margin)
    assert margin['reconciliationStatus'] == 'MATCH', margin
    expected = {'totalRevenue':840,'totalCost':'441.62','grossProfit':'398.38',
        'reconciliationReturnedRevenue':75,'reconciliationReturnedCost':'41.08',
        'reconciliationNetRevenue':765,'reconciliationNetCost':'400.53','reconciliationNetGrossProfit':'364.47'}
    for key, value in expected.items():
        assert Decimal(str(margin[key])) == Decimal(str(value)), (key,margin[key],value)
    for key in ('totalRevenue','totalCost','grossProfit','totalExpense','netProfit'):
        assert Decimal(str(income[key])) == Decimal(str(closed['income'][key]))
        assert Decimal(str(income[key])) == Decimal(str(margin['financialStatement'][key]))
    source = next(src for row in margin['rows'] for src in row['sources'] if src['shipmentLineId'] == 49)
    assert Decimal(str(source['revenue'])) == 30 and Decimal(str(source['cost'])) == 16
    assert len(source['batches']) == 1 and Decimal(str(source['batches'][0]['quantity'])) == 2 and Decimal(str(source['batches'][0]['unitCost'])) == 8
    partial = read('/finance/report/shipment-sku-margin?periodStart=2026-10-19&periodEnd=2026-10-19')
    assert partial['reconciliationStatus'] == 'PARTIAL_PERIOD'
    assert all(partial.get(k) is None for k in expected if k.startswith('reconciliation'))
    before = json.loads((OUT / 'baseline.json').read_text())
    after = s.snapshot()
    # Closing/posting cannot change production, stock or sales/return facts.
    unchanged = ['production_plans','production_batches','production_settlements','production_settlement_output_lines',
        'finished_goods_batches','material_batches','sales_orders','sales_order_items','sales_delivery_records',
        'sales_delivery_items','sales_delivery_item_batch_allocations','return_orders','return_order_items','disposal_records']
    permitted_flags = {'b9745fbd-33ad-4de7-90f3-1d5e9d4f44c3','ce991bda-a7c4-4dda-803c-e212a549b2fd','32d72d68-3417-4068-800d-d3e322e09984'}
    for table in unchanged:
        if table == 'sales_orders':
            def neutral(rows):
                return [{k:v for k,v in row.items() if not(row['id'] in permitted_flags and k in ('vflag','updated_at'))} for row in rows]
            assert neutral(before[table]) == neutral(after[table]), table
        else:
            assert before[table] == after[table], table
    assert s.snapshot(s.SOURCE) == json.loads((OUT / 'source-before.json').read_text()), 'Original P34 data changed'
    production = read('/production/actual-metrics/by-product'+PERIOD)
    baseline = json.loads((OUT / 'initial-readback.json').read_text())['production']
    assert production == baseline, 'Financial closing changed production metrics'
    partial_costs = [p for p in production['products'] if p['productName'].startswith('P37 generality')]
    assert len(partial_costs) == 2 and all(p['totalCost'] is None and p['unitCost'] is None for p in partial_costs)
    denied = s.admin.get(f'http://127.0.0.1:{s.PORT}/api/mobile/F006/finance/report/shipment-sku-margin'+PERIOD,timeout=30)
    assert denied.status_code == 403
    save('final-business-readback',{'status':'PASS','income':income,'margin':margin,'newBoxedSource':source,
        'partialPeriod':partial,'production':production,'unchangedBusinessTables':unchanged,
        'historicalPartialProductionCostsRemainUnknown':True,'crossTenantStatus':denied.status_code,
        'originalSourceUnchanged':True,'businessSqlWrites':0,'productionWrites':0})
    matrix = json.loads((OUT / 'scenarios.json').read_text())
    matrix[2].update(status='通过',closedDefect='FIN-MARGIN-RETURN-BASIS',evidence=['before-fix-full-month-margin.json','after-fix-full-month-margin.json','final-business-readback.json'])
    matrix[2].pop('defect',None)
    save('scenarios',matrix)
    print('PASS 3/3: income765 cost400.53 expense4.71 net359.76; gross840/441.62 with returns75/41.08 reconciled at raw precision; historical facts unchanged')


def receipt():
    scenarios = json.loads((OUT / 'scenarios.json').read_text())
    assert len(scenarios) == 3 and all(row['status'] == '通过' for row in scenarios)
    names = ['ShipmentSkuMarginServiceTest','SalesReturnCostServiceTest','CostCarryoverServiceTest',
        'IncomeStatementServiceTest','ProfitLossClosingServiceTest','MonthCloseServiceTest',
        'AccountingPeriodServiceTest','ClosingReadinessServiceTest']
    reports = []
    for name in names:
        files = list((a.OUT / 'build/surefire-reports').glob('TEST-*.' + name + '.xml'))
        assert len(files) == 1
        stats = ET.parse(files[0]).getroot().attrib
        assert all(int(stats.get(key,0)) == 0 for key in ('failures','errors','skipped'))
        reports.append({'class':name,'tests':int(stats['tests']),'report':str(files[0])})
    assert sum(row['tests'] for row in reports) == 106
    web = json.loads((OUT / 'web-tests-final.json').read_text())
    assert web['success'] and web['numPassedTests'] == 36 and web['numFailedTests'] == 0
    assert (OUT / 'web-types-final.log').read_text().strip() == ''
    assert 'built in' in (OUT / 'web-build-final.log').read_text(encoding='utf-8',errors='replace')
    freeze = json.loads((a.OUT / 'product-final-freeze.json').read_text())
    assert all(hashlib.sha256((s.ROOT / path).read_bytes()).hexdigest() == digest for path,digest in freeze.items())
    screenshots = ['revenue-posted.png','negative-void.png','recovered-revenue-posted.png',
        'full-month-margin-final.png','full-month-income-final.png']
    assert all((OUT / file).stat().st_size > 1000 for file in screenshots)
    return save('final-receipt', {'status':'PASS','businessScenarios':scenarios,'java':reports,
        'javaDistinctTests':106,'webInitialFourFilesTests':64,'webFinalAffectedTests':36,
        'typeCheck':True,'build':True,'sourceFreeze':freeze,'screenshots':screenshots,
        'evidence':'final-business-readback.json','localOnly':True,'sqlBusinessDml':0,
        'existingV64DdlOnly':True,'fullFlywayAcceptance':False,'ciRun':False,
        'pushed':False,'merged':False,'deployed':False,'userSignoff':'PENDING'})


if __name__ == '__main__':
    assert sys.argv[1] == 'NON_PRODUCTION_ONLY'
    {'init': init, 'inspect': inspect, 'prepare-posting':prepare_posting, 'post-readback':post_readback,
     'close':close,'prepare-scope-schema':prepare_scope_schema,'verify':verify,'receipt':receipt}[sys.argv[2]]()
