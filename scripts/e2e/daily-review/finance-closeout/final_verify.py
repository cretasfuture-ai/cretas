"""Final independent readback; refresh closing only through ordinary business APIs."""
import os,sys,json,hashlib,xml.etree.ElementTree as ET
from pathlib import Path
from decimal import Decimal,ROUND_HALF_UP
os.environ['DAILY_REVIEW_PROFILE']='P31'
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'refund-close-concurrency'))
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
assert not (OUT/'final-verify.json').exists(),'Completed evidence is immutable'
assert (OUT/'clock.txt').read_text().strip()=='2026-10-18'
for name in ['cost','rejection','closing','races','verify','cross-month']:
 assert json.loads((OUT/(name+'.json')).read_text())['verdict']=='PASS',name
f=json.loads((OUT/'facts.json').read_text())
save('pre-summary-refresh.json',call(admin,'GET','/finance/accounting-periods/2026/10'))
step('final-summary-reopen',admin,'/finance/accounting-periods/reopen',{'year':2026,'month':10,'reason':'P31正常重结账验收最终对账摘要与损益一致'})
step('final-summary-window',admin,'/finance/month-close/execute',{'year':2026,'month':10})
period('force-lock-close','final-summary-lock')
report=call(admin,'GET','/finance/report/income-statement?startYear=2026&startMonth=10&endYear=2026&endMonth=10')
period_row=call(admin,'GET','/finance/accounting-periods/2026/10')
frozen=json.loads(period_row['incomeStatementSnapshot'])
expected={'totalRevenue':'720','totalCost':'378.12','totalExpense':'4.71','grossProfit':'341.88','netProfit':'337.17'}
for field,amount in expected.items():
 assert Decimal(str(report[field]))==Decimal(str(frozen[field]))==Decimal(amount),(field,report,frozen)
assert str(report['netProfit']) in period_row['reconciliationSummary']
assert '无待审批调整' in period_row['reconciliationSummary']
assert '715.29' not in period_row['reconciliationSummary']
assert period_row['closingPostedAt'] and period_row['adjustWindowState']=='LOCKED'
statement=call(admin,'GET','/finance/statement?counterpartyType=CUSTOMER&counterpartyId='+f['customer']+'&startDate=2026-10-01&endDate=2026-11-30')
assert Decimal(str(statement['closingBalance']))==0
cash={}
with dbconnect() as d:
 balance=d.execute('select current_balance from customers where factory_id=%s and id=%s',(FACTORY,f['customer'])).fetchone()[0]
 assert balance==0
 pending=d.execute("select count(*) from ar_ap_transactions where factory_id=%s and approval_status='PENDING' and deleted_at is null",(FACTORY,)).fetchone()[0]
 assert pending==0
 for month,ending in [(10,15),(11,0)]:
  cash[str(month)]=call(admin,'GET',f'/finance/report/cash-flow?startYear=2026&startMonth={month}&endYear=2026&endMonth={month}')
  book=d.execute("select coalesce(sum(e.debit-e.credit),0) from voucher_entries e join vouchers v on v.id=e.voucher_id where v.factory_id=%s and v.status in ('POSTED','REVERSED') and v.deleted_at is null and e.deleted_at is null and e.subject_code in ('1001','1002','1012') and v.voucher_date<=%s",(FACTORY,'2026-10-31' if month==10 else '2026-11-30')).fetchone()[0]
  assert Decimal(str(cash[str(month)]['endingCash']))==book==ending
 final_cost=d.execute('select b.id,b.unit_cost,b.produced_quantity,b.shipped_quantity from finished_goods_batches b where b.factory_id=%s and b.id in (%s,%s) order by b.id',(FACTORY,f['sourceBatch'],f['returnedBatch'])).fetchall()
 assert len(final_cost)==2 and final_cost[0][1]==final_cost[1][1]==Decimal('9.4165')
 approved=d.execute('select approved_by,actual_loss,approval_status from disposal_records where factory_id=%s and id=5',(FACTORY,)).fetchone()
 assert approved==(12,Decimal('4.71'),'APPROVED'),approved
 refunds=d.execute("select id from payment_requests where factory_id=%s and return_order_id is not null and status='CANCELLED' and cancelled_at is not null",(FACTORY,)).fetchall()
 for (rid,) in refunds:
  movements=d.execute("select source_type,amount,approval_status from ar_ap_transactions where factory_id=%s and source_id=%s and source_type in ('RETURN_REFUND','RETURN_REFUND_REVERSAL') and deleted_at is null",(FACTORY,rid)).fetchall()
  assert len(movements)==2 and sum(r[1] for r in movements)==0 and all(r[2]=='APPROVED' for r in movements),(rid,movements)
# Authenticated own-factory user cannot approve a disposal through another factory path.
before=snapshot();denied=admin.put('http://127.0.0.1:10068/api/mobile/VISION-ENTRY-B/disposal-records/5/approve',json={},timeout=30)
assert denied.status_code==403 and before==snapshot()
assert snapshot(SOURCE)==json.loads((OUT/'source-before.json').read_text())
freeze=json.loads((OUT/'product-final-freeze.json').read_text())
for path,digest in freeze.items():assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==digest,path
reports=[]
for path in (OUT/'build/surefire-reports').glob('TEST-*.xml'):
 root=ET.parse(path).getroot()
 assert int(root.attrib.get('errors',0))==int(root.attrib.get('failures',0))==int(root.attrib.get('skipped',0))==0,path.name
 reports.append({'class':root.attrib['name'],'tests':int(root.attrib['tests']),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
save('final-verify.json',{'verdict':'PASS','factory':FACTORY,'income':report,'period':period_row,'cashFlow':cash,'statement':statement,'finalCosts':final_cost,'approvedDisposal':approved,'cancelledRefundPairs':len(refunds),'pendingAdjustments':pending,'sourceExactUnchanged':True,'crossTenantDisposal403Exact':True,'sqlBusinessDml':0,'clockRestored':'2026-10-18','reports':reports,'classCount':len(reports),'distinctTests':sum(r['tests'] for r in reports),'sourceFreeze':freeze})
print('P31 final readback PASS; income720 cost378.12 expense4.71 net337.17; original source exact unchanged')
