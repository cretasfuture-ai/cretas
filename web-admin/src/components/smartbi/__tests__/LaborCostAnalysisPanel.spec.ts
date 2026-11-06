// @vitest-environment jsdom
import { mount,flushPromises } from '@vue/test-utils';
import { expect,it,vi } from 'vitest';
vi.mock('@/api/smartbi/blacklake',()=>({getBlacklakeLaborSummary:vi.fn()}));
import {getBlacklakeLaborSummary} from '@/api/smartbi/blacklake';
import {computed,defineComponent,h,provide,inject,type Ref} from 'vue';
import Component from '../LaborCostAnalysisPanel.vue';
import type { LaborCostInput } from '@/utils/laborCostAnalysis';
const base=():LaborCostInput=>({total:100,output:10,unit:'包',rows:[{id:'a',process:'加工',amount:60,reportedHours:2,personHours:2},{id:'b',process:'包装',amount:40,reportedHours:1,personHours:3}]});
const table=defineComponent({props:['data'],setup(p,{slots}){provide('labor-rows',computed(()=>p.data));return()=>h('div',slots.default?.());}});
const column=defineComponent({props:['label'],setup(p,{slots}){const rows=inject<Ref<Record<string,unknown>[]>>('labor-rows');return()=>h('div',{'data-label':p.label},rows?.value.map(row=>slots.default?.({row})));}});
const render=(input:LaborCostInput)=>mount(Component,{props:{input,scopeLabel:'合成工单'},global:{stubs:{'el-table':table,'el-table-column':column}}});
it('shows missing staff as a specific request with an owner instead of zero people cost',()=>{
  const data=base();data.rows=data.rows.map(r=>({...r,personHours:null}));const page=render(data);
  expect(page.text()).toContain('实际参与人员名单');expect(page.text()).toContain('生产主管 / 考勤负责人');expect(page.text()).toContain('有效人时');expect(page.text()).toContain('待补资料');expect(page.find('select').exists()).toBe(false);
  expect(page.findAll('.labor-metrics small').map(item=>item.text())).toEqual(['缺实际参与人员名单','缺各工序的有效人时']);
});
it('explains the recovered registered basis without presenting it as actual headcount allocation',()=>{
  const input=base();input.rows=input.rows.map(row=>({...row,personHours:null,registeredWorkerKeys:['opaque'],registeredCoverageComplete:true,registeredReports:1,sourceEffectiveHours:1,zeroTimeReports:1,overlapReports:1}));
  const page=render(input);
  expect(page.text()).toContain('按登记人员测算人均：100.00');
  expect(page.text()).toContain('需确认实际班组名单');
  expect(page.text()).toContain('每段实际参与人数');
  expect(page.text()).toContain('班组长代报');
  expect(page.find('select').exists()).toBe(false);
});
it('uses a private server aggregation when the public response contains no person identifiers',async()=>{
  const input=base();input.blacklakeOrderIds=[1];input.sourceRunId='live:'+'a'.repeat(32);
  input.rows=input.rows.map(row=>({...row,personHours:null,registeredWorkerKeys:undefined,registeredCoverageComplete:true,registeredReports:1,sourceEffectiveHours:1}));
  vi.mocked(getBlacklakeLaborSummary).mockResolvedValue({total:100,participants:2,complete:true,perRegisteredPerson:50,processes:[{process:'加工',participants:2},{process:'包装',participants:1}],actualRosterConfirmed:false,personHours:null});
  const page=render(input);await flushPromises();
  expect(page.text()).toContain('按登记人员测算人均：50.00');
  expect(page.text()).toContain('2 名登记生产人员');
  expect(page.find('select').exists()).toBe(false);
  expect(getBlacklakeLaborSummary).toHaveBeenCalledWith([1],input.sourceRunId);page.unmount();
});
it('labels the person-hour allocation as a read-only comparison and resets it on scope change',async()=>{
  const page=render(base());expect(page.find('select').exists()).toBe(true);
  await page.get('select').setValue('person_hours');expect(page.text()).toContain('仅供比较，不改变工资或账目');
  expect(page.get('[data-label="测算分摊额"]').text()).toBe('40.0060.00');
  expect(page.get('[data-label="人工占比"]').text()).toBe('40.00%60.00%');
  expect(page.get('[data-label="每包人工"]').text()).toBe('4.006.00');
  await page.setProps({input:{...base(),rows:[]}});expect(page.find('select').exists()).toBe(false);expect(page.text()).not.toContain('仅供比较，不改变工资或账目');
});
