import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import ElementPlus, { ElSelect } from 'element-plus';
const api=vi.hoisted(()=>({get:vi.fn(),getPlatformPermissions:vi.fn(),getFactoryOverride:vi.fn(),updateFactoryOverride:vi.fn(),updatePlatformPermission:vi.fn()}));
vi.mock('@/api/request',()=>({get:api.get}));
vi.mock('@/api/permissionApi',()=>api);
import Matrix from '../ModulePermissionMatrix.vue';
const wrappers: VueWrapper[]=[];
beforeEach(()=>{
  vi.clearAllMocks();api.get.mockResolvedValue({success:true,data:[{name:'factory_super_admin'},{name:'sales_manager'},{name:'platform_admin'}]});
  api.getPlatformPermissions.mockResolvedValue([]);api.getFactoryOverride.mockResolvedValue({});api.updateFactoryOverride.mockResolvedValue(undefined);
});
afterEach(()=>{wrappers.splice(0).forEach(w=>w.unmount());document.body.innerHTML='';});
async function open(){const w=mount(Matrix,{props:{factoryId:'F1'},attachTo:document.body,global:{plugins:[ElementPlus]}});wrappers.push(w);await flushPromises();return w;}
describe('factory-local role permission configuration',()=>{
  it('shows authoritative factory roles with empty L1 and saves only an explicit local change',async()=>{
    const w=await open();const row=w.findAll('.el-table__row').find(row=>row.text().includes('sales_manager'))!;
    expect(row.exists()).toBe(true);expect(row.findAllComponents(ElSelect).every(select=>select.props('modelValue')==='-')).toBe(true);
    expect(api.updateFactoryOverride).not.toHaveBeenCalled();
    row.findAllComponents(ElSelect)[6].vm.$emit('change','rw');await flushPromises();
    await w.findAll('button').find(button=>button.text().startsWith('保存'))!.trigger('click');await flushPromises();
    expect(api.updateFactoryOverride).toHaveBeenCalledExactlyOnceWith('F1','sales_manager','hr','rw');
    expect(api.updatePlatformPermission).not.toHaveBeenCalled();
  });
  it('does not turn platform or unknown historical L2 roles into editable factory roles',async()=>{
    api.getFactoryOverride.mockResolvedValue({unknown_historical:{hr:'rw'},platform_admin:{hr:'rw'}});
    const w=await open();const text=w.find('.el-table__body-wrapper').text();
    expect(text).not.toContain('platform_admin');expect(text).not.toContain('unknown_historical');expect(text).toContain('sales_manager');
    expect(api.updateFactoryOverride).not.toHaveBeenCalled();expect(api.updatePlatformPermission).not.toHaveBeenCalled();
  });
  it('does not display editable guesses when the role catalogue cannot be read',async()=>{
    api.get.mockResolvedValue({success:false,message:'denied'});const w=await open();
    expect(w.findAll('.el-table__row')).toHaveLength(0);expect(api.updateFactoryOverride).not.toHaveBeenCalled();
  });
  it('ignores a delayed old factory response and binds saves to the displayed factory',async()=>{
    let finishOld!: (value: unknown)=>void;
    const old=new Promise(resolve=>{finishOld=resolve;});
    api.getFactoryOverride.mockImplementation((factory:string)=>factory==='F1'?old:Promise.resolve({sales_manager:{hr:'r'}}));
    const w=await open();await w.setProps({factoryId:'F2'});await flushPromises();
    finishOld({sales_manager:{hr:'rw'}});await flushPromises();
    const row=w.findAll('.el-table__row').find(row=>row.text().includes('sales_manager'))!;
    expect(row.findAllComponents(ElSelect)[6].props('modelValue')).toBe('r');
    row.findAllComponents(ElSelect)[6].vm.$emit('change','rw');await flushPromises();
    await w.findAll('button').find(button=>button.text().startsWith('保存'))!.trigger('click');await flushPromises();
    expect(api.updateFactoryOverride).toHaveBeenCalledExactlyOnceWith('F2','sales_manager','hr','rw');
    expect(api.updatePlatformPermission).not.toHaveBeenCalled();
  });
});
