import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import DisposalVoucherLinks from './DisposalVoucherLinks.vue';

const api=vi.hoisted(()=>({get:vi.fn(),push:vi.fn()}));
vi.mock('@/api/request',()=>({get:api.get}));
vi.mock('vue-router',()=>({useRouter:()=>({push:api.push})}));
const original={id:'o',factoryId:'F',voucherNumber:'V-1',sourceBusinessType:'DISPOSAL_RECORD',sourceBusinessId:'5',status:'REVERSED',totalDebit:4.21};
const red={...original,id:'r',voucherNumber:'V-2',sourceBusinessType:'VOUCHER_REVERSAL',sourceBusinessId:'o',originalVoucherId:'o',status:'POSTED'};
const revision={...original,id:'n',voucherNumber:'V-3',sourceBusinessType:'DISPOSAL_RECORD_REVISION',sourceBusinessId:'5#r2',status:'POSTED',totalDebit:4.71};
const render=()=>mount(DisposalVoucherLinks,{props:{factoryId:'F',disposalId:'5'},global:{plugins:[ElementPlus]}});
beforeEach(()=>{vi.clearAllMocks();api.get.mockResolvedValue({success:true,data:[original,red,revision]});});

describe('disposal accounting links',()=>{
  it('displays original, red and revision amounts and opens the existing detail route',async()=>{
    const w=render();await flushPromises();expect(api.get).toHaveBeenCalledWith('/F/disposal-records/5/vouchers');
    for(const text of ['原始凭证','红字冲销','成本修订','4.21','4.71'])expect(w.text()).toContain(text);
    await w.findAll('button').find(b=>b.text()==='V-3')!.trigger('click');expect(api.push).toHaveBeenCalledWith('/finance/voucher/n');w.unmount();
  });
  it('rejects foreign or unrelated vouchers',async()=>{
    api.get.mockResolvedValueOnce({success:true,data:[{...revision,factoryId:'OTHER'}]});const w=render();await flushPromises();
    expect(w.text()).toContain('不一致');expect(w.text()).not.toContain('V-3');w.unmount();
  });
  it('rejects a red voucher from another disposal',async()=>{
    api.get.mockResolvedValueOnce({success:true,data:[original,{...red,originalVoucherId:'another'}]});const w=render();await flushPromises();
    expect(w.text()).toContain('不一致');expect(w.text()).not.toContain('V-2');w.unmount();
  });
  it('ignores responses after factory changes',async()=>{
    let resolve!:(value:unknown)=>void;api.get.mockReturnValueOnce(new Promise(r=>{resolve=r;}));const w=render();await flushPromises();
    api.get.mockResolvedValueOnce({success:true,data:[]});await w.setProps({factoryId:'OTHER'});await flushPromises();
    resolve({success:true,data:[original,red,revision]});await flushPromises();expect(w.text()).not.toContain('V-3');w.unmount();
  });
  it('shows failures and retries instead of treating them as an empty history',async()=>{
    api.get.mockRejectedValueOnce(new Error('读取失败'));const w=render();await flushPromises();expect(w.text()).toContain('读取失败');
    await w.find('button').trigger('click');await flushPromises();expect(w.text()).toContain('V-3');w.unmount();
  });
});
