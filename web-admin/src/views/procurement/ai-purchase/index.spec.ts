import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const previewMock = vi.fn();
const statusMock = vi.fn();
const revokeMock = vi.fn();
const pushMock = vi.fn();
const getMock = vi.fn();

vi.mock('@/api/controlledPurchase', () => ({
  previewControlledPurchase: (...args: unknown[]) => previewMock(...args),
  getControlledPurchaseStatus: (...args: unknown[]) => statusMock(...args),
  revokeControlledPurchase: (...args: unknown[]) => revokeMock(...args),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => getMock(...args),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F001' }) }));
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => ({ push: pushMock }),
}));
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), error: vi.fn() } }));
vi.mock('@/views/smart-bi/components/chat/AiOperationCard.vue', () => ({
  default: { name: 'AiOperationCard', props: ['response', 'factoryId'], template: '<div data-testid="operation-card" />' },
}));

import AiPurchasePage from './index.vue';
import { menuConfig } from '@/components/layout/menuConfig';
import router from '@/router';

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-input': { template: '<input />' },
  'el-date-picker': { template: '<input />' },
  'el-button': { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-alert': { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
};

function response(grantId = 'preview-7') {
  return {
    status: 'PREVIEW', intentCode: 'PURCHASE_ORDER_CREATE', message: 'preview', formattedText: '',
    resultData: { grantId },
    confirmableAction: { confirmToken: 'proof', commandDigest: 'd'.repeat(64), expiresAt: new Date(Date.now() + 300000).toISOString(), previewData: { grantId } },
  };
}

describe('AI controlled purchase page', () => {
  beforeEach(() => {
    localStorage.clear(); previewMock.mockReset(); statusMock.mockReset(); revokeMock.mockReset(); pushMock.mockReset(); getMock.mockReset();
    getMock.mockImplementation((url: string) => url.includes('suppliers')
      ? Promise.resolve({ success: true, data: [{ id: 's1', name: '供应商甲' }] })
      : Promise.resolve({ success: true, data: [{ id: 'm1', name: '五花肉' }] }));
    statusMock.mockResolvedValue({ grantId: 'preview-7', state: 'PENDING', version: 3 });
    revokeMock.mockResolvedValue({ grantId: 'preview-7', state: 'REVOKED', version: 4 });
  });

  it('submits canonical flat preview and freezes the form after a server grant', async () => {
    previewMock.mockResolvedValue(response());
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const vm = wrapper.vm as any;
    Object.assign(vm.form, { supplierId: 's1', materialTypeId: 'm1', quantity: '2', unitPrice: '10', amountCap: '22.60' });
    await vm.submitPreview();
    await flushPromises();
    expect(previewMock).toHaveBeenCalledWith('F001', expect.objectContaining({ supplierId: 's1', materialTypeId: 'm1', unit: 'kg', currency: 'CNY', taxRate: '13' }));
    expect(localStorage.getItem('cretas:controlled-purchase:F001')).toContain('preview-7');
    expect(wrapper.text()).toContain('AI 受控采购');
    expect((wrapper.findAll('button').find((b) => b.text().includes('生成预览'))?.element as HTMLButtonElement).disabled).toBe(true);
  });

  it('recovery checks persisted status and links a durable draft without creating a new preview', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001', JSON.stringify({ grantId: 'preview-9' }));
    statusMock.mockResolvedValue({ grantId: 'preview-9', state: 'DRAFT', purchaseOrderId: 'PO-9', version: 2 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(statusMock).toHaveBeenCalledWith('F001', 'preview-9');
    expect(pushMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('采购草稿已创建');
    expect(previewMock).not.toHaveBeenCalled();
  });

  it('recovery UNKNOWN blocks a new preview and cancel revokes server state first', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001', JSON.stringify({ grantId: 'preview-10' }));
    statusMock.mockResolvedValue({ grantId: 'preview-10', state: 'UNKNOWN', version: 5 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('结果待核对');
    expect(previewMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('刷新授权状态');
  });

  it('failed references keep a saved recovery blocked and never allow a new preview', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001', JSON.stringify({ grantId: 'preview-11' }));
    statusMock.mockRejectedValue(new Error('状态服务不可用'));
    getMock.mockRejectedValue(new Error('供应商服务不可用'));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('供应商服务不可用');
    expect(previewMock).not.toHaveBeenCalled();
    expect((wrapper.vm as any).recoveryReady).toBe(true);
  });

  it('does not allow preview while recovery is still pending, and restored PENDING can be revoked', async () => {
    let resolveStatus!: (value: unknown) => void;
    statusMock.mockReturnValue(new Promise((resolve) => { resolveStatus = resolve; }));
    localStorage.setItem('cretas:controlled-purchase:F001', JSON.stringify({ grantId: 'preview-12' }));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await Promise.resolve();
    const previewButton = wrapper.findAll('button').find((button) => button.text().includes('生成预览'));
    expect((previewButton?.element as HTMLButtonElement).disabled).toBe(true);
    resolveStatus({ grantId: 'preview-12', state: 'PENDING', version: 7 });
    await flushPromises();
    expect(wrapper.text()).toContain('撤销当前授权');
    await wrapper.findAll('button').find((button) => button.text().includes('撤销当前授权'))!.trigger('click');
    expect(revokeMock).toHaveBeenCalledWith('F001', 'preview-12', 7, expect.any(String));
  });

  it('exposes the procurement scoped route and menu child without analytics permission', () => {
    const route = router.getRoutes().find((item) => item.path === '/procurement/ai-purchase');
    const procurement = menuConfig.find((item) => item.path === '/procurement');
    expect(route?.meta.module).toBe('procurement');
    expect(procurement?.children?.some((item) => item.path === '/procurement/ai-purchase')).toBe(true);
  });
});
