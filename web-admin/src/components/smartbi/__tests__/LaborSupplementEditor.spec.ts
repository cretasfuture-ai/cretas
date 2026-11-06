// @vitest-environment jsdom
import {flushPromises,mount} from '@vue/test-utils';
import {afterEach,expect,it,vi} from 'vitest';
import {defineComponent} from 'vue';
import Component from '../LaborSupplementEditor.vue';
import {getLaborSupplements,saveLaborSupplements,type LaborSupplementStatus} from '@/api/smartbi/laborSupplement';
vi.mock('@/api/smartbi/laborSupplement',()=>({getLaborSupplements:vi.fn(),saveLaborSupplements:vi.fn()}));
const value=(editable=true):LaborSupplementStatus=>({editable,revision:'0',sourceRunId:'live:'+'a'.repeat(32),tasks:[{projectId:1,projectCode:'GD1',taskId:11,product:'样品',process:'包装',laborCost:100,revision:0,status:'missing',participants:null,personHours:null}],summary:{complete:false,participants:null,personHours:null,perPerson:null,perPersonHour:null,total:null,processes:[],orders:[]}});
const slot=defineComponent({template:'<div><slot/><slot name="footer"/></div>'});
const button=defineComponent({props:['disabled','loading'],emits:['click'],template:'<button :disabled="disabled" @click="$emit(\'click\')"><slot/></button>'});
const table=defineComponent({props:['data'],template:'<div>{{JSON.stringify(data)}}</div>'});
const page=()=>mount(Component,{props:{orderIds:[1],sourceRunId:value().sourceRunId},global:{stubs:{'el-dialog':slot,'el-tabs':slot,'el-tab-pane':slot,'el-form':slot,'el-form-item':slot,'el-button':button,'el-table':table,'el-table-column':true,'el-select':true,'el-option':true,'el-input':true,'el-input-number':true,'el-checkbox':true}}});
afterEach(()=>vi.restoreAllMocks());
it('keeps public edits disabled while showing existing-system sources and gaps',async()=>{
  vi.mocked(getLaborSupplements).mockResolvedValue(value(false));const p=page();await flushPromises();expect(p.text()).toContain('公开页只展示汇总结果');expect(p.text()).toContain('Cretas 生产报工、结单');expect(p.text()).toContain('尚未绑定');expect(p.findAll('button').find(b=>b.text()==='保存并重算')?.attributes('disabled')).toBeDefined();expect(saveLaborSupplements).not.toHaveBeenCalled();p.unmount();
});
it('discards an old product response after switching to another product',async()=>{
  let resolveFirst!:(v:LaborSupplementStatus)=>void;vi.mocked(getLaborSupplements).mockImplementationOnce(()=>new Promise(resolve=>{resolveFirst=resolve;})).mockResolvedValue(value());const p=page();await p.setProps({orderIds:[2]});await flushPromises();resolveFirst({...value(),revision:'99'});await flushPromises();expect(p.emitted('updated')?.length).toBe(1);p.unmount();
});
