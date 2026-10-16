import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import ElementPlus from 'element-plus';
import InvoicesTab from '../InvoicesTab.vue';

vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success:true, data:{
  content:[{id:'I1',invoiceNumber:'INV-20261016-0001',amount:62,taxAmount:8.06,totalAmount:70.06,status:'ISSUED',invoiceType:'NORMAL',issuedAt:'2026-10-16T09:00:00'}],
  totalElements:1,totalPages:1,page:1,size:20,
} })) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F1') }) }));
const priceAccess=ref(true);
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: priceAccess }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('customer invoice field contract', () => {
  it.each([true,false])('renders persisted invoice fields with price access %s', async allowed => {
    priceAccess.value=allowed;
    const wrapper=mount(InvoicesTab,{props:{customerId:'C1'},global:{plugins:[ElementPlus]}});
    await flushPromises();
    const text=wrapper.text();
    expect(text).toContain('INV-20261016-0001');
    expect(text).toContain('2026/10/16');
    if(allowed) { expect(text).toContain('62.00'); expect(text).toContain('8.06'); expect(text).toContain('70.06'); }
    else { expect(text).toContain('****'); expect(text).not.toContain('62.00'); }
    wrapper.unmount();
  });
});
