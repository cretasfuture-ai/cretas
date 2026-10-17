import { describe, expect, it } from 'vitest';
import { defineComponent } from 'vue';
import { mount } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canOrderFromQuote } from '../quoteOrderContract';

const source=readFileSync(resolve(dirname(fileURLToPath(import.meta.url)),'../list.vue'),'utf8');
const button=source.match(/<el-button v-if="canWrite && canOrderFromQuote[\s\S]*?按报价下单<\/el-button>/)?.[0];
if(!button)throw new Error('Missing real quote-to-order action');
describe('approved quote to sales order',()=>{
  it.each(['PENDING_QUOTE','PENDING_APPROVAL','REJECTED','EXPIRED'])('blocks %s',status=>{
    expect(canOrderFromQuote({status},'2026-10-17')).toBe(false);
  });
  it('accepts no expiry and the final valid day but refuses an expired approval',()=>{
    expect(canOrderFromQuote({status:'APPROVED'},'2026-10-17')).toBe(true);
    expect(canOrderFromQuote({status:'APPROVED',validUntil:'2026-10-17'},'2026-10-17')).toBe(true);
    expect(canOrderFromQuote({status:'APPROVED',validUntil:'2026-10-16'},'2026-10-17')).toBe(false);
  });
  it.each([true,false])('real button respects sales write permission %s',canWrite=>{
    const calls: unknown[]=[];
    const w=mount(defineComponent({template:button,setup:()=>({canWrite,row:{id:'Q-62',status:'APPROVED'},canOrderFromQuote,toDateString:()=> '2026-10-17',router:{push:(r:unknown)=>calls.push(r)}})}),{global:{stubs:{ElButton:{template:'<button><slot /></button>'}}}});
    expect(w.find('button').exists()).toBe(canWrite);
    if(canWrite){w.find('button').trigger('click');expect(calls).toEqual([{path:'/sales/orders/new',query:{quoteId:'Q-62'}}]);}
  });
});
