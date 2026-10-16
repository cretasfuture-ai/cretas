import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import ElementPlus from 'element-plus';
import QuotesTab from '../QuotesTab.vue';

vi.mock('@/api/request', () => ({ get: vi.fn(async () => ({ success:true, data:{
  content:[{id:'Q1',quoteNo:'QT-20261016-0001',customerId:'C1',productTypeId:'PRODUCT-1',unitPrice:62,unit:'个',status:'APPROVED'}],
  totalElements:1,totalPages:1,page:1,size:20,
} })) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F1') }) }));
const priceAccess=ref(true);
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: priceAccess }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('customer quote field contract', () => {
  it.each([true,false])('renders persisted quote fields with price access %s', async allowed => {
    priceAccess.value=allowed;
    const wrapper=mount(QuotesTab,{props:{customerId:'C1'},global:{plugins:[ElementPlus]}});
    await flushPromises();
    const text=wrapper.text();
    expect(text).toContain('QT-20261016-0001');
    expect(text).toContain('PRODUCT-1');
    expect(text).not.toContain('总额');
    if(allowed) expect(text).toContain('62.00/个');
    else { expect(text).toContain('****'); expect(text).not.toContain('62.00'); }
    wrapper.unmount();
  });
});
