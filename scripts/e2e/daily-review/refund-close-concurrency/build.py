"""Build and test the current refund/cost slice in a private output, never the running P29 classes."""
from pathlib import Path
import json,hashlib,os,sys,re,subprocess,shutil,psutil
import argparse
parser=argparse.ArgumentParser();parser.add_argument('ack',choices=['NON_PRODUCTION_ONLY']);parser.add_argument('--profile',choices=['P30','P31','P34','P37'],default='P30');parser.add_argument('--tests');options=parser.parse_args()
ROOT=Path(__file__).resolve().parents[4];OUT=Path.home()/('Documents/cretas-connect-20261015/factory-data-'+options.profile.lower());JAVA=ROOT/'backend/java/cretas-api';OUT.mkdir(exist_ok=True)
if (OUT/'runtime.json').exists():
 runtime=json.loads((OUT/'runtime.json').read_text())
 if psutil.pid_exists(runtime['javaPid']):
  process=psutil.Process(runtime['javaPid'])
  assert process.name().lower()=='java.exe' and '@'+runtime['javaArgFile'] in process.cmdline()
  assert str(OUT/'build/classes').replace('\\','/') not in Path(runtime['javaArgFile']).read_text().replace('\\\\','/'),'Do not overwrite the classes loaded by the active P30 service'
names=['CostCarryoverServiceTest','SalesReturnCostServiceTest','ReturnOrderRepositoryQueryValidationTest','ReturnOrderSalesReturnFinishedGoodsWarehouseContractTest','ReturnOrderVoucherTransactionContractTest','ReturnOrderVoucherListenerRejectTest','ReturnRefundReversalContractTest','ReturnRefundPaymentTransactionContractTest','IncomeStatementServiceTest','ProfitLossClosingServiceTest','MonthCloseServiceTest','AccountingPeriodServiceTest','ReturnedGoodsCostResolverContractTest','VoucherCostCorrectionRepositoryQueryValidationTest']
if options.profile in ('P31','P34'):names+=['ClosingReadinessServiceTest','ReturnedCostSynchronizationServiceTest','DisposalCostAccountingServiceTest','DisposalFinishedGoodsBalanceTest','DisposalRecordServiceCrossTenantTest','DisposalWorkflowIntegrationTest','DisposalRecordRepositoryQueryValidationTest','DisposalRecordLockRepositoryQueryValidationTest','ProductionPlanServiceImplActualCostsContractTest','ProductionCostConsistencyContractTest','ProductionPlanSettlementTest','ReturnOrderPurchaseReturnDefectiveTest']
if options.profile in ('P31','P34'):names+=['ArApAdjustmentClosingLockTest','ArApServiceImplCancelPendingAdjustmentTest']
if options.profile in ('P31','P34'):names+=['DisposalControllerApproveTest']
if options.profile=='P34':names+=['VoucherServiceImplTest','VoucherServiceManualTest']
if options.profile=='P37':
 names=['ReportedOutputCostAllocationServiceTest','ProductProcessWorkflowServiceImplTest','WorkflowAnchorPolicyTest','WorkflowRevisionSnapshotServiceTest','ModuleEnabledInterceptorUserAccessTest','ProductWorkflowProcessPathTest','ProductWorkflowUnifiedResolutionTest','FgQuantityUnitConverterTest','ShipmentCostRowsTest','FactoryIndicatorApplicabilityContractTest','FactoryReadinessContractTest','FactoryActualProductionMetricsContractTest','FactoryIndicatorProvisionerContractTest','MultiOutputAllocationBasisContractTest','ProcessSheetMultiOutputProjectionTest','BomRecipeDraftLifecycleJpaTest','BomWorkflowRevisionServiceTest','ShipmentCostRepositoryQueryValidationTest','IndicatorControllerTest','ProductionPlanSettlementTest','SalesServiceImplPackagingSnapshotTest','SalesDeliveryShipmentContractTest','UnitContractServiceTest']
if options.profile=='P37':
 names+=['ProcessSheetYieldCardTest','ProductionSummaryServiceTest','ProductionSummaryReuseIntegrationTest','BatchBomBindingContractTest','OrderCostBreakdownServiceTest','OrderCostBreakdownSfiFeedTest','ProductionSettlementCoverageControllerWiringContractTest','CustomerSuppliedSettlementCoverageGuardContractTest']
if options.profile=='P37':names+=['FormalFinishedOutputScopeContractTest','ProductionBatchFinishedOutputContractTest']
if options.tests:
 selected=options.tests.split(',');assert all(name in names for name in selected);names=selected
files=set(subprocess.check_output(['git','diff','--name-only'],cwd=ROOT,text=True).splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
input_freeze={f:hashlib.sha256((ROOT/f).read_bytes()).hexdigest() for f in files if f.endswith(('.java','.vue','.ts'))}
(OUT/'java-input-freeze.json').write_text(json.dumps(input_freeze,indent=2),encoding='utf-8')
if not (OUT/'build/classes').exists():
 source=JAVA/'target/classes' if options.profile=='P37' else OUT.parent/({'P30':'factory-data-p29','P31':'factory-data-p30','P34':'factory-data-p31'}[options.profile])/'build/classes'
 shutil.copytree(source,OUT/'build/classes')
pom=(JAVA/'pom.xml').read_text(encoding='utf-8').replace('<build>','<build>\n<directory>'+str(OUT/'build').replace(chr(92),'/')+'</directory>',1)
pom,n=re.subn(r'<testIncludes>.*?</testIncludes>','<testIncludes>'+''.join('<testInclude>**/'+name+'.java</testInclude>' for name in names)+'</testIncludes>',pom,flags=re.S);assert n==1
temp=JAVA/('pom-'+options.profile.lower()+'-local.xml');assert not temp.exists();temp.write_text(pom,encoding='utf-8')
env=dict(os.environ);env['JAVA_HOME']='C:/Program Files/Zulu/zulu-21';env['PATH']=env['JAVA_HOME']+'/bin;'+env['PATH']
cmd=['mvn.cmd','-f',temp.name,'test','-Dmaven.compiler.useIncrementalCompilation=false','-Dtest='+','.join(names)]
(OUT/'java-command.json').write_text(json.dumps({'cwd':str(JAVA),'command':cmd},indent=2),encoding='utf-8')
try:
 with (OUT/'java-final.log').open('w',encoding='utf-8') as log:r=subprocess.run(cmd,cwd=JAVA,env=env,stdout=log,stderr=subprocess.STDOUT,shell=True)
finally:temp.unlink()
print(options.profile+' Java exit='+str(r.returncode));print('\n'.join((OUT/'java-final.log').read_text(encoding='utf-8',errors='replace').splitlines()[-22:]))
if r.returncode==0:
 assert all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==digest for f,digest in input_freeze.items()),'Source changed during validation; do not freeze stale evidence'
 (OUT/'product-final-freeze.json').write_text(json.dumps(input_freeze,indent=2),encoding='utf-8')
sys.exit(r.returncode)
