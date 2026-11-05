// @vitest-environment jsdom
import {computed,defineComponent,h,inject,provide,type Ref} from 'vue';
import {mount,flushPromises} from '@vue/test-utils';
import {expect,it} from 'vitest';
import Component from '../CostDataTable.vue';
const table=defineComponent({name:'ElTable',props:['data'],emits:['sort-change'],setup(p,{slots}){provide('table-rows',computed(()=>p.data));return()=>h('div',slots.default?.());}});
const column=defineComponent({setup(_,{slots}){const rows=inject<Ref<object[]>>('table-rows');return()=>h('div',[slots.header?.(),rows?.value.map(row=>slots.default?.({row}))]);}});
it('sorts and filters all rows before pagination and returns to page one after a new filter',async()=>{
  const rows=Array.from({length:1000},(_,i)=>({id:i,amount:i,name:`产品${i}`}));
  const page=mount(Component,{props:{rows,columns:[{key:'name',label:'产品'},{key:'amount',label:'金额',type:'number'}],paginate:true},global:{stubs:{ElTable:table,ElTableColumn:column,ElIcon:true,ElPopover:{template:'<div><slot name="reference"/><slot/></div>'},ElPagination:{name:'ElPagination',props:['total','currentPage','pageSize'],emits:['update:currentPage','update:pageSize'],template:'<div/>'}}}});
  await flushPromises();page.findComponent(table).vm.$emit('sort-change',{prop:'amount',order:'descending'});await flushPromises();
  expect(page.findComponent(table).props('data')[0].id).toBe(999);
  const pager=page.findComponent({name:'ElPagination'});pager.vm.$emit('update:currentPage',2);await flushPromises();
  expect(page.findComponent(table).props('data')[0].id).toBe(979);
  await page.get('[aria-label="最低金额"]').setValue('990');await flushPromises();
  expect(pager.props('currentPage')).toBe(1);expect(pager.props('total')).toBe(10);expect(page.findComponent(table).props('data')).toHaveLength(10);
  expect(page.text()).toContain('筛选后 10 / 共 1000 条');
  await page.findAll('button').find(button=>button.text()==='清空表头筛选')!.trigger('click');await flushPromises();
  expect(pager.props('total')).toBe(1000);expect(rows[0]!.id).toBe(0);page.unmount();
});
it('does not mount nested detail tables for Element Plus registration probe rows',()=>{
  let details=0;
  const probeColumn=defineComponent({props:['type'],setup(p,{slots}){const rows=inject<Ref<object[]>>('table-rows');return()=>h('div',[...(p.type==='expand'?[slots.default?.({row:{}})]:[]),rows?.value.map(row=>slots.default?.({row}))]);}});
  const page=mount(Component,{props:{rows:[{id:1,components:[{}]}],columns:[{key:'id',label:'编号'}]},slots:{expand:({row}:{row:{components?:object[]}})=>{if(!row.components)throw new Error('Probe row must not mount details');details++;return h('span','关联明细');}},global:{stubs:{ElTable:table,ElTableColumn:probeColumn,ElPopover:true}}});
  expect(details).toBe(1);expect(page.text()).toContain('关联明细');page.unmount();
});
