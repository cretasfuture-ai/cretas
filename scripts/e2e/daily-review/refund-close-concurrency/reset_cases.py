"""Explicit one-time transition from retained pre-fix evidence to post-fix business races."""
import sys
from support import *
assert sys.argv[1:]==['NON_PRODUCTION_ONLY'] and (OUT/'product-final-freeze.json').exists()
archive=OUT/'pre-fix-concurrency';assert not archive.exists(),'Transition already performed; do not repeat'
prepared=json.loads((OUT/'prepared.json').read_text());base='/return-orders/'+prepared['returnOrder']+'/refund'
finance_credentials=json.loads((OUT/'finance-credentials.private.json').read_text());finance,actor=login(finance_credentials['username'],finance_credentials['password'])
view=call(admin,'GET',base);assert view['request']['status']=='PAID'
period('reopen','postfix-reset-reopen')
rid=view['request']['id'];pending=step('postfix-reset-recovery',admin,base+'/reversal',{'requestId':rid,'paymentReference':'P30-POSTFIX-RESET-'+rid[:8],'reason':'P30正常收回原款，为修复后的并发复验准备'})
step('postfix-reset-approved',finance,base+'/reversal/approve',{'requestId':rid,'reversalTransactionId':pending['request']['reversal']['ledger']['id']})
archive.mkdir();save('pre-fix-state.json',state)
cases=['pay-close','pay-refund','reverse-posted-close','reverse-posted-refund','reverse-draft-close','reverse-draft-refund','finalize']
for case in cases:
 for suffix in ('.json','-before.json','-after.json','.log'):
  file=OUT/(case+suffix)
  if file.exists():file.rename(archive/file.name)
for name in ('final-readback.json','final-readback.log','commands.json','source-freeze.json'):
 file=OUT/name
 if file.exists():file.rename(archive/name)
for key in list(steps):
 if any(key.startswith(case+'-') for case in cases):del steps[key]
persist();print('Post-fix checkpoint prepared through normal reversal; all prior evidence retained')
