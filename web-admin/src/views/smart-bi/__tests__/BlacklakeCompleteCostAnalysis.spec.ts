// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, type Ref } from 'vue';
import type { CompleteCostReport, CostProject } from '@/api/smartbi/blacklake';
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
const column=defineComponent({setup(_,{slots}){const rows=inject<Ref<CostProject[]>>('rows');return()=>h('div',rows?.value.map(row=>slots.default?.({row})));}});
function render(){return mount(Component,{global:{stubs:{'el-table':table,'el-table-column':column,'el-select':{template:'<div><slot/></div>'},'el-option':true,'el-pagination':true,'el-input':true,'el-dialog':true,'el-alert':{props:['title'],template:'<div role="alert">{{title}}</div>'}}}});}
beforeEach(()=>{auth.store.factoryId='T';vi.mocked(getBlacklakeCompleteCost).mockReset();});
afterEach(()=>{vi.useRealTimers();});
it('shows exact two-decimal material, labor and unit cost while interpretations are queued',async()=>{
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValue(data());const page=render();await flushPromises();
  expect(page.text()).toContain('0 / 44');expect(page.text()).toContain('2.25 / 盒');
  await page.findAll('button').find(b=>b.text()==='查看原料与分析')!.trigger('click');
  expect(page.text()).toContain('材料净额 20.00');expect(page.text()).toContain('工序人工 2.50');
  expect(page.text()).toContain('解释仍在排队');expect(page.text()).not.toContain('本轮所有证据已逐条完成');page.unmount();
});
it('removes previously visible cost details when renewed authorization fails',async()=>{
  vi.mocked(getBlacklakeCompleteCost).mockResolvedValueOnce(data()).mockRejectedValueOnce(new Error('成本授权未确认'));
  const page=render();await flushPromises();expect(page.text()).toContain('合成食品');
  await page.findAll('button').find(b=>b.text()==='刷新进度')!.trigger('click');await flushPromises();
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
  const page=render();await flushPromises();expect(page.text()).toContain('部分批次尚未通过校验');
  await vi.advanceTimersByTimeAsync(15000);await flushPromises();
  expect(getBlacklakeCompleteCost).toHaveBeenCalledTimes(2);expect(page.text()).toContain('模型正在按顺序分析');page.unmount();
});
