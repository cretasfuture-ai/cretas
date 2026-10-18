"""Complete actual HTTP chain in an isolated cloned runtime; no SQL business DML."""
from pathlib import Path
from datetime import date
from decimal import Decimal
import requests,psycopg,json,sys,time,os

OUT=Path(os.environ['P10_ACCEPTANCE_DIR']).resolve()
assert OUT==Path.home()/'Documents/cretas-connect-20261015/factory-data-p10','Private P10 owner directory required'
ROOT=Path(__file__).resolve().parents[4]
assert sys.argv[1:]==['NON_PRODUCTION_ONLY']
sys.path.insert(0,str(ROOT/'scripts/e2e/daily-review'))
from p0_business_flow import BusinessFlow
runtime=json.loads((OUT/'runtime.json').read_text());assert runtime['owner']=='P10-REFUND-HTTP-20261016' and runtime['database']=='p10_refund_http_20261016' and not runtime['productionTouched']
log=(OUT/'java.log').read_text(encoding='utf-8',errors='replace')
targets={line.split('class=',1)[1].split(' ',1)[0] for line in log.splitlines() if line.startswith('P10_CLOCK_TRANSFORM class=')}
assert len(targets)==7 and 'P10_CLOCK_TRANSFORM_ERROR' not in log,'Actual application clock instrumentation incomplete; do not execute business'
accounts=json.loads((OUT.parent/'daily-review/accounts.private.json').read_text(encoding='utf-8'))
db_credentials=json.loads((OUT/'database.private.json').read_text(encoding='utf-8'))
BASE='http://127.0.0.1:10062'
FACTORY='VISION-ENTRY-A'
RET='2b3e1b62-3836-4bee-a9e0-bace3edfbfd7'
CUSTOMER='REVIEW-TRACE-A-CUSTOMER'
events=json.loads((OUT/'http-events.json').read_text(encoding='utf-8')) if (OUT/'http-events.json').exists() else []

def login(name,factory=FACTORY):
    session=requests.Session()
    r=session.post(BASE+'/api/mobile/auth/unified-login',json={'username':name,'password':accounts['password'],'clientType':'web'},timeout=45)
    r.raise_for_status();body=r.json();assert body.get('success'),body
    actor=body['data'];assert actor['username']==name and actor['factoryId']==factory
    session.headers['Authorization']='Bearer '+(actor.get('token') or actor['accessToken'])
    return session,{k:actor.get(k) for k in ['userId','username','factoryId','role']}

def dbread(sql,args=()):
    with psycopg.connect(host='127.0.0.1',port=55447,dbname=runtime['database'],user=db_credentials['username'],password=db_credentials['password'],options='-c default_transaction_read_only=on') as db:
        assert db.execute('select current_database()').fetchone()[0]==runtime['database']
        return db.execute(sql,args).fetchall()

def save(name,obj):(OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
def phase(value):
    assert value in ['2026-10-31','2026-11-01','2026-11-30','2026-12-01']
    (OUT/'clock.txt').write_text(value+'\n',encoding='utf-8')
    save('phase.json',{'businessDate':value,'hostObservedAt':time.time(),'simulationOnly':True})

class CrossMonthFlow(BusinessFlow):
    def __init__(self):
        self.base=BASE;self.factory=FACTORY;self.out=OUT/'scenario';self.out.mkdir(exist_ok=True)
        self.state_path=self.out/'business-state.json'
        self.state=json.loads(self.state_path.read_text(encoding='utf-8')) if self.state_path.exists() else {'factoryId':FACTORY,'entities':{},'events':[]}
        assert self.state['factoryId']==FACTORY
        self.http,self.state['identity']=login('daily_factory_a')
        self.actor_id=self.state['identity']['userId'];assert self.actor_id==93001 and self.state['identity']['role']=='factory_super_admin'
        self.persist()
    def call(self,method,path,payload=None):
        return call(self.http,'daily_factory_a',method,path,payload)
flow=CrossMonthFlow();finance,finance_identity=login('daily_finance_a');assert finance_identity['userId']==1
factory_b,b_identity=login('daily_factory_b','VISION-ENTRY-B')
e=flow.state['entities'];base='/return-orders/'+RET+'/refund'

def send(session,actor,method,path,payload=None,factory=FACTORY):
    assert path.startswith('/') and '..' not in path and '://' not in path and factory in [FACTORY,'VISION-ENTRY-B']
    r=session.request(method,BASE+'/api/mobile/'+factory+path,json=payload,timeout=90)
    body=r.json()
    clock=r.headers.get('X-P10-Business-Date')
    if factory==FACTORY:assert clock==(OUT/'clock.txt').read_text().strip(),('Missing/mismatched test clock proof',r.status_code,clock)
    event={'actor':actor,'method':method,'path':path,'factory':factory,'status':r.status_code,'success':body.get('success'),'businessDate':clock,'hostObservedAt':time.time()}
    events.append(event);save('http-events.json',events)
    return r,body
def call(session,actor,method,path,payload=None):
    r,b=send(session,actor,method,path,payload);assert r.status_code==200 and b.get('success'),b
    return b['data']
def step(name,session,actor,path,payload):
    if name not in e:
        e[name]=call(session,actor,'POST',path,payload);flow.persist()
    return e[name]
def negative(name,session,actor,path,payload,status,factory=FACTORY):
    if name not in e:
        r,b=send(session,actor,'POST',path,payload,factory);assert r.status_code==status and not b.get('success'),(r.status_code,b)
        e[name]={'status':r.status_code,'body':b};flow.persist()

def original_rows(request_id):
    rows=dbread('select row_to_json(t) from payment_requests t where factory_id=%s and id=%s',(FACTORY,request_id));assert len(rows)==1
    request=rows[0][0]
    ledger=dbread("select row_to_json(t) from ar_ap_transactions t where factory_id=%s and source_type='RETURN_REFUND' and source_id=%s and deleted_at is null",(FACTORY,request_id));assert len(ledger)==1
    voucher=dbread("select row_to_json(t) from vouchers t where factory_id=%s and source_business_type='RETURN_REFUND' and source_business_id=%s and deleted_at is null",(FACTORY,request_id));assert len(voucher)==1
    entries=dbread('select row_to_json(t) from voucher_entries t where voucher_id=%s order by id',(voucher[0][0]['id'],))
    return {'request':request,'ledger':ledger[0][0],'voucher':voucher[0][0],'entries':[r[0] for r in entries]}

def original_integrity(key,request_id,posted):
    before=json.loads((OUT/(key+'-original-before.json')).read_text(encoding='utf-8'))
    after=original_rows(request_id);changes={}
    for kind in ['request','ledger','voucher']:
        assert set(before[kind])==set(after[kind])
        fields=[k for k in before[kind] if before[kind][k]!=after[kind][k]]
        allowed={'request':{'status','cancelled_at','updated_at','version'},'ledger':set(),'voucher':{'status','reversal_voucher_id','description','updated_at','version'} if posted else {'status','approved_at','approved_by','description','updated_at','version'}}[kind]
        assert set(fields)<=allowed,(kind,fields)
        changes[kind]=fields
    assert before['entries']==after['entries'],'Original voucher entries changed'
    assert after['request']['version']==before['request']['version']+1
    assert after['voucher']['description'].startswith((before['voucher']['description'] or '')+(' [红字冲销: ' if posted else ' [退款撤销作废: '))
    save(key+'-original-integrity.json',{'status':'PASS','allowedChanges':changes,'originalLedgerExactUnchanged':True,'originalEntriesExactUnchanged':True})

def snapshot(label,balance,cash,year,month):
    overview=flow.call('GET','/finance/overview')
    report=flow.call('GET',f'/finance/report/cash-flow?startYear={year}&startMonth={month}&endYear={year}&endMonth={month}')
    sql_balance=dbread('select current_balance from customers where factory_id=%s and id=%s and deleted_at is null',(FACTORY,CUSTOMER))[0][0]
    sql_ar=dbread("select coalesce(sum(case when transaction_type='AR_INVOICE' then amount when transaction_type in ('AR_ADJUSTMENT','AR_REFUND') and approval_status='APPROVED' then amount when transaction_type in ('AR_PAYMENT','AR_CREDIT_NOTE') then -abs(amount) else 0 end),0) from ar_ap_transactions where factory_id=%s and counterparty_type='CUSTOMER' and counterparty_id=%s and deleted_at is null",(FACTORY,CUSTOMER))[0][0]
    assert Decimal(str(overview['totalReceivable']))==sql_balance==sql_ar==Decimal(balance),(label,overview,sql_balance,sql_ar)
    assert Decimal(str(report['endingCash']))==Decimal(cash),(label,report)
    value={'status':'PASS','businessDate':(OUT/'clock.txt').read_text().strip(),'overview':overview,'cashFlow':report,'postgresCustomerBalance':str(sql_balance),'postgresSignedAr':str(sql_ar)}
    save(label+'.json',value);return value

def inspect_case(key,request_id,original_date,approval_date,posted):
    view=flow.call('GET','/return-refunds/'+request_id);r=view['request'];rev=r['reversal']
    assert r['status']=='CANCELLED' and rev['status']=='APPROVED'
    assert r['voucher']['status']==('REVERSED' if posted else 'VOID')
    assert (rev['voucher'] is not None)==posted
    assert r['ledger']['transactionDate']==rev['ledger']['transactionDate']==original_date
    assert rev['ledger']['approvedAt'].startswith(approval_date) and rev['requestedAt'].startswith(approval_date)
    assert rev['ledger']['operatedBy']==93001 and rev['ledger']['approvedBy']==1
    assert rev['ledger']['balanceBefore']==540 and rev['ledger']['balanceAfter']==525
    requests_row=dbread('select paid_at,cancelled_at,status from payment_requests where factory_id=%s and id=%s',(FACTORY,request_id))[0]
    assert str(requests_row[0]).startswith(original_date) and str(requests_row[1]).startswith(approval_date) and requests_row[2]=='CANCELLED'
    ids=[r['voucher']['id']]+([rev['voucher']['id']] if posted else [])
    vouchers=dbread('select id,voucher_number,voucher_date,status,approved_at,reversal_voucher_id,original_voucher_id from vouchers where factory_id=%s and id=any(%s)',(FACTORY,ids))
    assert all(str(row[2])==original_date for row in vouchers)
    ledger=dbread('select id,transaction_date,created_at,approved_at,amount,approval_status from ar_ap_transactions where factory_id=%s and id=any(%s)',(FACTORY,[r['ledger']['id'],rev['ledger']['id']]))
    assert len(ledger)==2 and all(str(row[1])==original_date for row in ledger)
    reversal_row=next(row for row in ledger if row[0]==rev['ledger']['id']);assert str(reversal_row[2]).startswith(approval_date) and str(reversal_row[3]).startswith(approval_date)
    if posted:
        assert next(v for v in vouchers if v[0]==r['voucher']['id'])[5]==rev['voucher']['id']
        assert next(v for v in vouchers if v[0]==rev['voucher']['id'])[6]==r['voucher']['id']
        assert str(next(v for v in vouchers if v[0]==rev['voucher']['id'])[4]).startswith(approval_date)
        net=dbread("select subject_code,sum(debit-credit) from voucher_entries where voucher_id=any(%s) and deleted_at is null group by subject_code",(ids,));assert net and all(row[1]==0 for row in net)
        for voucher_id,expected in [(r['voucher']['id'],{'1122':(15,0),'1002':(0,15)}),(rev['voucher']['id'],{'1122':(0,15),'1002':(15,0)})]:
            entries=dbread('select subject_code,debit,credit from voucher_entries where voucher_id=%s and deleted_at is null',(voucher_id,));assert len(entries)==2
            assert {row[0]:(row[1],row[2]) for row in entries}==expected
    else:
        assert dbread("select count(*) from vouchers where factory_id=%s and source_business_type='VOUCHER_REVERSAL' and source_business_id=%s and deleted_at is null",(FACTORY,r['voucher']['id']))[0][0]==0
    approved_before=dbread('select approved_at,approved_by from ar_ap_transactions where factory_id=%s and id=%s',(FACTORY,rev['ledger']['id']))[0]
    duplicate_before=original_rows(request_id)
    mirror_before=dbread("select row_to_json(t) from vouchers t where factory_id=%s and source_business_type='VOUCHER_REVERSAL' and source_business_id=%s order by id",(FACTORY,r['voucher']['id']))
    decision={'requestId':request_id,'reversalTransactionId':rev['ledger']['id']}
    call(finance,'daily_finance_a','POST',base+'/reversal/approve',decision)
    assert dbread('select approved_at,approved_by from ar_ap_transactions where factory_id=%s and id=%s',(FACTORY,rev['ledger']['id']))[0]==approved_before
    assert dbread("select count(*) from ar_ap_transactions where factory_id=%s and source_type='RETURN_REFUND_REVERSAL' and source_id=%s and approval_status='APPROVED' and deleted_at is null",(FACTORY,request_id))[0][0]==1
    assert dbread('select current_balance from customers where factory_id=%s and id=%s',(FACTORY,CUSTOMER))[0][0]==525
    assert original_rows(request_id)==duplicate_before
    assert dbread("select row_to_json(t) from vouchers t where factory_id=%s and source_business_type='VOUCHER_REVERSAL' and source_business_id=%s order by id",(FACTORY,r['voucher']['id']))==mirror_before
    original_integrity(key,request_id,posted)
    save(key+'-completed.json',{'status':'PASS','refund':view,'postgresRequest':requests_row,'postgresLedgers':ledger,'postgresVouchers':vouchers,'duplicateApprovalNoEffect':True})
    return view

def run_case(key,paid_date,reversed_date,posted,year,month):
    note=f'跨月模拟：{paid_date}退款、{reversed_date}撤销，仅本地演练，非实际转账'
    phase(paid_date)
    if key+'_before' not in e:e[key+'_before']=snapshot(key+'-01-before',525,15,year,month);flow.persist()
    flow.create(key+'_refund',base,{'amount':15,'remark':note})
    flow.create(key+'_submit',base+'/submit',{})
    step(key+'_approved',finance,'daily_finance_a',base+'/approve',{})
    paid=step(key+'_paid',finance,'daily_finance_a',base+'/mark-paid',{'paymentMethod':'BANK_TRANSFER','paymentReference':'P10-'+key.upper()+'-BANK-REFUND-15','remark':note})
    request_id=paid['request']['id'];voucher_id=paid['request']['voucher']['id']
    if key+'_registered' not in e:
        e[key+'_registered']=snapshot(key+'-02-registered-draft',540,15,year,month);flow.persist()
        assert paid['request']['ledger']['transactionDate']==paid_date and paid['request']['paidAt'].startswith(paid_date)
    if posted:step(key+'_posted',finance,'daily_finance_a','/finance/vouchers/'+voucher_id+'/post',{})
    if key+'_paid_snapshot' not in e:e[key+'_paid_snapshot']=snapshot(key+'-03-paid',540,0 if posted else 15,year,month);flow.persist()
    if not (OUT/(key+'-original-before.json')).exists():save(key+'-original-before.json',original_rows(request_id))
    phase(reversed_date)
    pending=flow.create(key+'_pending',base+'/reversal',{'requestId':request_id,'paymentReference':'P10-'+key.upper()+'-BANK-RECOVERY-15','reason':note+'，款项已收回，申请撤销'})
    decision={'requestId':request_id,'reversalTransactionId':pending['request']['reversal']['ledger']['id']}
    if key+'_pending_snapshot' not in e:e[key+'_pending_snapshot']=snapshot(key+'-04-pending',540,0 if posted else 15,year,month);flow.persist()
    negative(key+'_self_approval',flow.http,'daily_factory_a',base+'/reversal/approve',decision,409)
    negative(key+'_foreign_actor',factory_b,'daily_factory_b',base+'/reversal/approve',decision,403)
    negative(key+'_foreign_factory',factory_b,'daily_factory_b',base+'/reversal/approve',decision,403,'VISION-ENTRY-B')
    step(key+'_reversed',finance,'daily_finance_a',base+'/reversal/approve',decision)
    snapshot(key+'-05-final',525,15,year,month)
    view=inspect_case(key,request_id,paid_date,reversed_date,posted)
    for start,end in [(f'{year}-{month:02d}-01',paid_date),(reversed_date,reversed_date)]:
        statement=flow.call('GET',f'/finance/statement?counterpartyType=CUSTOMER&counterpartyId={CUSTOMER}&startDate={start}&endDate={end}')
        assert Decimal(str(statement['closingBalance']))==525
        if start==reversed_date:assert Decimal(str(statement['openingBalance']))==525 and Decimal(str(statement['periodDebit']))==Decimal(str(statement['periodCredit']))==0
        save(key+'-statement-'+start+'.json',statement)
    month_after=date.fromisoformat(reversed_date)
    report=flow.call('GET',f'/finance/report/cash-flow?startYear={month_after.year}&startMonth={month_after.month}&endYear={month_after.year}&endMonth={month_after.month}')
    assert Decimal(str(report['endingCash']))==15
    save(key+'-next-month-cash.json',report)
    return view

def main():
    results=[]
    results.append(run_case('posted','2026-10-31','2026-11-01',True,2026,10))
    results.append(run_case('draft','2026-11-30','2026-12-01',False,2026,11))
    save('business-final.json',{'status':'PASS','factory':FACTORY,'actors':[flow.state['identity'],finance_identity],'cases':results,'finalReceivable':525,'finalPostedCash':15,'normalHttpBusinessActionsOnly':True,'sqlBusinessDml':0,'productionWrites':0,'externalPayments':0,'fullSpringBootHttpAndPg':True})
    print(json.dumps({'status':'PASS','postedRequest':results[0]['request']['id'],'draftRequest':results[1]['request']['id'],'receivable':525,'postedCash':15,'crossMonthHttp':True}))

if __name__=='__main__':
    main()
