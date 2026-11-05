// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, type Ref } from 'vue';
import type { CompleteCostReport, CostProject, CostDataAlert } from '@/api/smartbi/blacklake';
vi.mock('@nuxt/ui/components/Card.vue',()=>({default:{template:'<div><slot name="header"/><slot/></div>'}}));
vi.mock('@nuxt/ui/components/Button.vue',()=>({default:{template:'<button><slot/></button>'}}));
vi.mock('@/components/smartbi/DynamicChartRenderer.vue',()=>({default:{template:'<div>成本图表</div>'}}));
const auth=vi.hoisted(()=>({store:{factoryId:'T',user:{id:1},currentRole:'factory_super_admin'}}));
vi.mock('@/store/modules/auth',async()=>{const{reactive}=await import('vue');auth.store=reactive(auth.store);return{useAuthStore:()=>auth.store};});
vi.mock('@/api/smartbi/blacklake',()=>({getBlacklakeCompleteCost:vi.fn(),followupBlacklakeCompleteCost:vi.fn()}));
import {getBlacklakeCompleteCost} from '@/api/smartbi/blacklake';
import Component from '../BlacklakeCompleteCostAnalysis.vue';
function data():CompleteCostReport{
  const project:CostProject={projectId:1,projectCode:'SYNTHETIC',product:'合成食品',productCode:'P',unit:'盒',status:'20',planned:10,actual:10,finishedGood:10,
    materialCost:20,laborCost:2.5,identifiedDirectCost:22.5,unitIdentifiedDirectCost:2.25,materialLines:1,materialCostKnownLines:1,sourceZeroCostLines:0,taskCount:1,laborCostKnownTasks:1,
    materialLinesDetail:[{material:'合成原料',materialCode:'M',unit:'kg',netConsumption:5,recognizedCost:20,unitPrice:4,orderCode:'OUT-SYN',date:'2026-11-04',bizType:'32'}],
    processes:[{process:'包装',status:'20',good:10,hours:1,laborCost:2.5}],issues:[],unitCostAvailable:true};
  return{schema:'blacklake-complete-cost/1',sourceRunId:'S',sourceCheckedAt:'2026-11-04T10:50:00Z',contract:{originalRemainingFacts:44,eligibleOrders:26,inventoryBoundary:'没有历史余额，不倒推。'},
    batchAnalysis:{status:'running',expectedFacts:145,analyzedFacts:0,remainingFacts:145,results:[],jobs:{queued:29},usage:null,cohorts:{original44:{expected:44,analyzed:0},eligible26:{expected:101,analyzed:0}}},
    analysis:{factoryName:'合成工厂',snapshotAt:'2026-11-04',runId:'S',coverageComplete:false,catalog:[],findings:[],limitations:[],
      costIntelligence:{engine:'synthetic',recommendations:[],comparisons:[],answer:'',limitations:[],worklist:[],verifiedMaterialReferences:{},gapEvidence:{}},
      costTraceability:{schema:'trace',checkedAt:'2026-11-04',summary:{workOrders:26,ordersWithDirectCost:26,materialOriginLinks:1,materialProcessLinks:0},workOrders:[]},
      production:{projectCount:26,taskCount:26,outputCount:26,completionRate:1,statuses:{'20':26},planActual:[],quality:[],processes:[]},
      inventory:{records:[],byWarehouseUnit:[],pairsNotReturned:null,movementOrders:1},
      cost:{projects:Array.from({length:26},(_,i)=>({...project,projectId:i+1,projectCode:'SYNTHETIC-'+(i+1)})),materials:[],linkedMaterialLines:1,unlinkedMaterialLines:0,excludedUnapprovedOrders:0,knownMaterialCostLines:1,sourceZeroCostLines:0,knownLaborTasks:26,unitCostAvailableProjects:26,fullManufacturingCost:null,grossProfit:null}}};
}
const table=defineComponent({props:['data'],setup(p,{slots}){provide('rows',computed(()=>p.data));return()=>h('div',slots.default?.());}});
const column=defineComponent({props:['prop'],setup(p,{slots}){const rows=inject<Ref<CostProject[]>>('rows');return()=>h('div',rows?.value.map(row=>slots.default?slots.default({row}):p.prop==='product'?row.product:p.prop==='projectCode'?row.projectCode:''));}});
function render(){return mount(Component,{global:{stubs:{'el-table':table,'el-table-column':column,'el-select':{name:'ElSelect',props:['modelValue'],emits:['update:modelValue'],template:'<div><slot/></div>'},'el-option':true,'el-pagination':{name:'ElPagination',props:['total','currentPage'],emits:['update:currentPage'],template:'<div/>'},'el-input':true,'el-dialog':true,'el-drawer':{template:'<aside><slot/></aside>'},'el-alert':{props:['title'],template:'<div role="alert">{{title}}</div>'}}}});}
beforeEach(()=>{auth.store.factoryId='T';vi.mocked(getBlacklakeCompleteCost).mockReset();});
afterEach(()=>{vi.useRealTimers();});
it('guides data checks first and opens the actual higher-cost order from a reconciled product gap',async()=>{
  const report=data();const first=report.analysis.cost.projects[0]!;
  const higher={...first,projectId:2,projectCode:'HIGHER-ORDER',finishedGood:100,materialCost:500,laborCost:100,unitIdentifiedDirectCost:6,identifiedDirectCost:600};
  report.analysis.cost.projects=[first,higher];
  const items:CostDataAlert[]=(['data_quality','business_signal'] as const).map(family=>({id:family,type:'synthetic',family,severity:'warning',blocksUnitCost:false,title:'合成核对项',message:'核对原单',owner:'财务/生产',action:'核对原单',fields:[],entityRefs:[{projectId:1,projectCode:first.projectCode,productCode:'P',unit:'盒'}],evidence:{},detectedBy:'test',ruleVersion:'test',scope:'test',runId:'S',status:'open'}));
  report.analysis.costAlerts={schema:'test',ruleVersion:'test',summary:{projects:2,costEligible:2,costBlocked:0,alerts:2,byFamily:{data_quality:1,business_signal:1},byType:{}},items,limitations:[],ai:{status:'not_run',message:'合成测试'}};
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(report);const page=render();await flushPromises();
  expect(page.get('.metrics').text()).toContain('先核对资料的产品1 项');
  expect(page.get('.metrics').text()).toContain('再核对成本差异的产品0 项');
  await page.findAll('button').find(b=>b.text()==='查看资料问题')!.trigger('click');
  await page.findAll('button').find(b=>b.text()==='查看产品分析')!.trigger('click');
  expect(page.get('[aria-label="同产品工单成本差额"]').text()).toContain('相差 3.75');
  expect(page.text()).toContain('先处理下方的资料问题');
  await page.findAll('button').find(b=>b.text()==='核对较高成本工单')!.trigger('click');
  expect(page.get('[aria-label="最新工单成本明细"] .analysis').text()).toContain('材料费 5.00、人工费 1.00，合计 6.00');
  page.unmount();
});
it('shows exact two-decimal material, labor and unit cost while interpretations are queued',async()=>{
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(data());const page=render();await flushPromises();
  expect(page.text()).toContain('0 / 44');expect(page.text()).toContain('2.25 / 盒');
  await page.findAll('button').find(b=>b.text()==='查看产品分析')!.trigger('click');
  await page.findAll('button').find(b=>b.text()==='查看成本与分析')!.trigger('click');
  expect(page.text()).toContain('材料净额20.00');expect(page.text()).toContain('工序人工2.50');
  expect(page.text()).toContain('解释仍在排队');expect(page.text()).not.toContain('本轮所有证据已逐条完成');page.unmount();
});
it('removes previously visible cost details when renewed authorization fails',async()=>{
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValueOnce(data()).mockRejectedValueOnce(new Error('成本授权未确认'));
  const page=render();await flushPromises();expect(page.text()).toContain('合成食品');
  await page.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();
  expect(page.text()).toContain('成本授权未确认');expect(page.text()).not.toContain('合成食品');page.unmount();
});
it('never attaches another factory response after identity changes',async()=>{
  let finish:(value:CompleteCostReport)=>void=()=>undefined;
  vi.mocked(getBlacklakeCompleteCost).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;})).mockRejectedValueOnce(new Error('无权限'));
  const page=render();auth.store.factoryId='OTHER';await flushPromises();finish(data());await flushPromises();
  expect(page.text()).not.toContain('合成食品');expect(page.text()).toContain('无权限');page.unmount();
});
it('continues checking progress after a failed batch is repaired in the queue',async()=>{
  vi.useFakeTimers();const failed=data();failed.batchAnalysis.status='failed';
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValueOnce(failed).mockResolvedValueOnce(data());
  const page=render();await flushPromises();expect(page.text()).toContain('部分分析暂不可用');
  await vi.advanceTimersByTimeAsync(15000);await flushPromises();
  expect(getBlacklakeCompleteCost).toHaveBeenCalledTimes(2);expect(page.text()).toContain('分析排队中');page.unmount();
});

it('paginates 1000 orders and resets the page when filters change',async()=>{
  const value=data();value.analysis.cost.projects=Array.from({length:1000},(_,i)=>({...value.analysis.cost.projects[0]!,projectId:i+1,projectCode:'SYN-'+i}));
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(value);const page=render();await flushPromises();
  const buttons=()=>page.findAll('button').filter(b=>b.text()==='查看成本与分析');
  expect(buttons()).toHaveLength(0);
  expect(page.findAll('button').filter(b=>b.text()==='查看产品分析')).toHaveLength(1);
  await page.findAll('button').find(b=>b.text()==='查看产品分析')!.trigger('click');
  expect(buttons()).toHaveLength(20);const pager=page.find('[data-testid="review-order-table"]').findComponent({name:'ElPagination'});
  expect(pager.props('total')).toBe(1000);pager.vm.$emit('update:currentPage',50);await flushPromises();expect(buttons()).toHaveLength(20);
  page.findAllComponents({name:'ElSelect'})[0]!.vm.$emit('update:modelValue','all');await flushPromises();expect(page.findAllComponents({name:'ElPagination'})[0]!.props('currentPage')).toBe(1);expect(buttons()).toHaveLength(0);page.unmount();
});

it('uses source completion dates for the latest seven data days and keeps per-order analysis attached',async()=>{
  const value=data();value.analysis.cost.projects=value.analysis.cost.projects.slice(0,2);
  value.analysis.cost.projectDates={'1':{completedAt:'2026-10-01T00:00:00Z',createdAt:null},'2':{completedAt:'2026-11-03T00:00:00Z',createdAt:null}};
  value.batchAnalysis.results=[{factId:'ORDER2',cohort:'eligible26',sectionId:'product_cost',kind:'work_order_detail',whatItMeans:'只属于第二张工单的解释',whyItMatters:'核对材料',nextStep:'找仓库确认',owner:'仓库',cannotConclude:'暂无批次证明',hypotheses:[],display:{text:'工单2',entityRefs:[{projectId:2}]}}];
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(value);const page=render();await flushPromises();
  expect(page.text()).toContain('10-28 至 11-03 · 1 张工单');expect(page.findAll('button').filter(b=>b.text()==='查看成本与分析')).toHaveLength(0);
  // Per-order answers never appear in the detached specialty list.
  expect(page.text()).not.toContain('只属于第二张工单的解释');
  await page.findAll('button').find(b=>b.text()==='查看产品分析')!.trigger('click');
  await page.findAll('button').find(b=>b.text()==='查看成本与分析')!.trigger('click');expect(page.text()).toContain('只属于第二张工单的解释');page.unmount();
});

it('drills from product to its own orders and returns without mixing other products',async()=>{
  const value=data();value.analysis.cost.projects=value.analysis.cost.projects.slice(0,3);
  value.analysis.cost.projects[2]={...value.analysis.cost.projects[2]!,product:'另一种半成品',productCode:'OTHER'};
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(value);const page=render();await flushPromises();
  expect(page.findAll('button').filter(b=>b.text()==='查看产品分析')).toHaveLength(2);
  expect(page.find('[data-testid="review-order-table"]').exists()).toBe(false);
  // Find the group by its displayed product label rather than relying on sort order.
  const productTable=page.find('[data-testid="product-cost-list"]');
  expect(productTable.text()).toContain('合成食品');
  await page.findAll('button').filter(b=>b.text()==='查看产品分析')[0]!.trigger('click');await flushPromises();
  const table=page.find('[data-testid="review-order-table"]');expect(table.exists()).toBe(true);
  const shownOrders=page.findAll('button').filter(b=>b.text()==='查看成本与分析').length;
  expect([1,2]).toContain(shownOrders);expect(table.text().includes('SYNTHETIC-3')&&table.text().includes('SYNTHETIC-1')).toBe(false);
  await page.findAll('button').find(b=>b.text()==='返回全部产品')!.trigger('click');await flushPromises();
  expect(page.find('[data-testid="review-order-table"]').exists()).toBe(false);expect(page.find('[data-testid="product-cost-list"]').exists()).toBe(true);page.unmount();
});

it('searching an order locates the product without dropping its other orders from totals',async()=>{
  const value=data();value.analysis.cost.projects=value.analysis.cost.projects.slice(0,2);
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(value);const page=render();await flushPromises();
  page.findComponent('el-input-stub').vm.$emit('update:modelValue','SYNTHETIC-1');await flushPromises();
  await page.findAll('button').find(b=>b.text()==='查看产品分析')!.trigger('click');await flushPromises();
  expect(page.findAll('button').filter(b=>b.text()==='查看成本与分析')).toHaveLength(2);
  expect(page.find('[data-testid="selected-product-analysis"]').text()).toContain('本次日期范围内 2 张工单');page.unmount();
});
