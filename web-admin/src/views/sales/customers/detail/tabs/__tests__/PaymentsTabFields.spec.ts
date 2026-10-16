import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import ElementPlus from 'element-plus';
import PaymentsTab from '../PaymentsTab.vue';

vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success:true, data:{
  content:[{id:'I1',paymentNumber:'PAY-20261016-0001',amount:62,status:'PENDING',paymentMethod:'BANK_TRANSFER',paymentDate:'2026-10-16',remark:'scope receipt'}],
  totalElements:1,totalPages:1,page:1,size:20,
} })) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F1') }) }));
const priceAccess=ref(true);
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: priceAccess }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('customer payment field contract', () => {
  it.each([true,false])('renders persisted payment fields with price access %s', async allowed => {
    priceAccess.value=allowed;
    const wrapper=mount(PaymentsTab,{props:{customerId:'C1'},global:{plugins:[ElementPlus]}});
    await flushPromises();
    const text=wrapper.text();
    expect(text).toContain('PAY-20261016-0001');
    expect(text).toContain('2026-10-16');
    expect(text).toContain('BANK_TRANSFER');
    expect(text).toContain('scope receipt');
    if(allowed) { expect(text).toContain('62.00'); }
    else { expect(text).toContain('****'); expect(text).not.toContain('62.00'); }
    wrapper.unmount();
  });
});
