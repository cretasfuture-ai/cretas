import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
const authFixture = vi.hoisted(() => ({ current: undefined as { factoryId: string; user: { id: number } } | undefined }));

const previewMock = vi.fn();
const statusMock = vi.fn();
const revokeMock = vi.fn();
const timerStatusMock = vi.fn();
const confirmMock = vi.fn();
const grantStatusMock = vi.fn();
const boxMock = vi.fn(async () => 'confirm');
const pushMock = vi.fn();
const getMock = vi.fn();

vi.mock('@/api/controlledPurchase', () => ({
  previewControlledPurchase: (...args: unknown[]) => previewMock(...args),
  getControlledPurchaseStatus: (...args: unknown[]) => statusMock(...args),
  revokeControlledPurchase: (...args: unknown[]) => revokeMock(...args),
  getPurchaseTimer: (...args: unknown[]) => timerStatusMock(...args),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => getMock(...args),
}));
vi.mock('@/api/smartbi/intent-chat', () => ({
  confirmIntentAction: (...args: unknown[]) => confirmMock(...args),
  getPurchaseTaskGrantStatus: (...args: unknown[]) => grantStatusMock(...args),
}));
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue');
  const state = reactive({ factoryId: 'F001', user: { id: 1553 } });
  authFixture.current = state;
  return { useAuthStore: () => state };
});
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-router')>()),
  useRouter: () => ({ push: pushMock }),
}));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return {
    ...actual,
    ElMessage: { ...actual.ElMessage, warning: vi.fn(), error: vi.fn() },
    ElMessageBox: { ...actual.ElMessageBox, confirm: (...args: unknown[]) => boxMock(...args) },
  };
});

import AiPurchasePage from './index.vue';
import { menuConfig } from '@/components/layout/menuConfig';
import router from '@/router';

const stubs = {
  'el-card': { template: '<div><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-date-picker': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-button': { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-alert': { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-tag': { template: '<span><slot /></span>' },
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
    Object.assign(authFixture.current!, { factoryId: 'F001', user: { id: 1553 } });
    localStorage.clear(); previewMock.mockReset(); statusMock.mockReset(); revokeMock.mockReset(); confirmMock.mockReset(); grantStatusMock.mockReset(); boxMock.mockClear(); pushMock.mockReset(); getMock.mockReset();
    getMock.mockImplementation((url: string) => url.includes('suppliers')
      ? Promise.resolve({ success: true, data: [{ id: 's1', name: '供应商甲' }] })
      : Promise.resolve({ success: true, data: [{ id: 'm1', name: '五花肉' }] }));
    statusMock.mockResolvedValue({ grantId: 'preview-7', state: 'PENDING', version: 3 });
    revokeMock.mockResolvedValue({ grantId: 'preview-7', state: 'REVOKED', version: 4 });
    timerStatusMock.mockReset().mockResolvedValue(null);
  });

  it.each([
    { path: 'card query', statusSource: 'card' as const },
    { path: 'parent refresh', statusSource: 'parent' as const },
  ])('real parent and operation card expose one revoke after uncertain confirmation then authoritative PENDING query via $path', async ({ statusSource }) => {
    previewMock.mockResolvedValue(response('preview-21'));
    confirmMock.mockRejectedValue(new Error('transport failure'));
    if (statusSource === 'card') grantStatusMock.mockResolvedValue({ grantId: 'preview-21', state: 'PENDING', version: 11 });
    else statusMock.mockResolvedValue({ grantId: 'preview-21', state: 'PENDING', version: 11 });
    revokeMock.mockResolvedValue({ grantId: 'preview-21', state: 'REVOKED', version: 12 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const selects = wrapper.findAll('select');
    await selects[0].setValue('s1');
    await selects[1].setValue('m1');
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('2');
    await inputs[1].setValue('10');
    await inputs[2].setValue('22.60');
    await wrapper.findAll('button').find((button) => button.text().includes('生成预览'))!.trigger('click');
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('确认执行'))!.trigger('click');
    await flushPromises();
    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(previewMock).toHaveBeenCalledTimes(1);
    const recoveryButton = wrapper.findAll('button').find((button) => button.text().includes(statusSource === 'card' ? '查询处理结果' : '刷新授权状态'));
    expect(recoveryButton).toBeDefined();
    await recoveryButton!.trigger('click');
    await flushPromises();

    if (statusSource === 'card') expect(grantStatusMock).toHaveBeenCalledWith('F001', 'preview-21');
    else expect(statusMock).toHaveBeenCalledWith('F001', 'preview-21');
    const revokeButton = wrapper.findAll('button').find((button) => button.text().includes('撤销当前授权'));
    expect(revokeButton).toBeDefined();
    if (revokeButton) await revokeButton.trigger('click');
    await flushPromises();
    expect(revokeMock).toHaveBeenCalledTimes(1);
    expect(revokeMock).toHaveBeenCalledWith('F001', 'preview-21', 11, expect.any(String));
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toBeNull();
    const previewButton = wrapper.findAll('button').find((button) => button.text().includes('生成预览'));
    expect(previewButton).toBeDefined();
    expect((previewButton!.element as HTMLButtonElement).disabled).toBe(false);
    expect(previewMock).toHaveBeenCalledTimes(1);
    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(revokeMock).toHaveBeenCalledTimes(1);
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
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toContain('preview-7');
    expect(wrapper.text()).toContain('AI 受控采购');
    expect((wrapper.findAll('button').find((b) => b.text().includes('生成预览'))?.element as HTMLButtonElement).disabled).toBe(true);
  });

  it('recovery checks persisted status and links a durable draft without creating a new preview', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-9' }));
    statusMock.mockResolvedValue({ grantId: 'preview-9', state: 'DRAFT', purchaseOrderId: 'PO-9', version: 2 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(statusMock).toHaveBeenCalledWith('F001', 'preview-9');
    expect(pushMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('采购草稿已创建');
    expect(previewMock).not.toHaveBeenCalled();
  });

  it('recovery UNKNOWN blocks a new preview and cancel revokes server state first', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-10' }));
    statusMock.mockResolvedValue({ grantId: 'preview-10', state: 'UNKNOWN', version: 5 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('结果待核对');
    expect(previewMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('刷新授权状态');
  });

  it('refreshes UNKNOWN through the actual status button and keeps the grant locked until PENDING is acknowledged', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-13' }));
    statusMock
      .mockResolvedValueOnce({ grantId: 'preview-13', state: 'UNKNOWN', version: 8 })
      .mockResolvedValueOnce({ grantId: 'preview-13', state: 'PENDING', version: 9 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const refresh = wrapper.findAll('button').find((button) => button.text().includes('刷新授权状态'));
    expect(refresh).toBeDefined();
    await refresh!.trigger('click');
    await flushPromises();
    expect(statusMock).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('撤销当前授权');
    expect(wrapper.text()).not.toContain('刷新授权状态');
  });

  it('failed references keep a saved recovery blocked and never allow a new preview', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-11' }));
    statusMock.mockRejectedValue(new Error('状态服务不可用'));
    getMock.mockRejectedValue(new Error('供应商服务不可用'));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('状态服务不可用');
    expect(previewMock).not.toHaveBeenCalled();
    expect((wrapper.vm as any).recoveryReady).toBe(true);
  });

  it('does not allow preview while recovery is still pending, and restored PENDING can be revoked', async () => {
    let resolveStatus!: (value: unknown) => void;
    statusMock.mockReturnValue(new Promise((resolve) => { resolveStatus = resolve; }));
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-12' }));
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

  it.each(['REVOKED', 'EXPIRED'])('clears %s recovery before allowing a fresh preview', async (state) => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-14' }));
    statusMock.mockResolvedValue({ grantId: 'preview-14', state, version: 10 });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toBeNull();
    expect((wrapper.vm as any).grantId).toBe('');
    expect((wrapper.vm as any).recoveryVersion).toBeNull();
    expect((wrapper.vm as any).previewResponse).toBeNull();
    expect((wrapper.vm as any).blockedReason).toBe('');
    const previewButton = wrapper.findAll('button').find((button) => button.text().includes('生成预览'));
    expect((previewButton?.element as HTMLButtonElement).disabled).toBe(false);
  });

  it('rejects a non-canonical preview grant when result and preview references do not match', async () => {
    previewMock.mockResolvedValue({
      ...response('preview-15'),
      resultData: { grantId: 'preview-15' },
      confirmableAction: { ...response('preview-15').confirmableAction, previewData: { grantId: 'preview-16' } },
    });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const vm = wrapper.vm as any;
    Object.assign(vm.form, { supplierId: 's1', materialTypeId: 'm1', quantity: '2', unitPrice: '10', amountCap: '22.60' });
    await vm.submitPreview();
    expect(vm.previewResponse).toBeNull();
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toBeNull();
  });

  it('locks a matching-grant FAILED confirmation into status-only recovery', async () => {
    previewMock.mockResolvedValue(response('preview-18'));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const vm = wrapper.vm as any;
    Object.assign(vm.form, { supplierId: 's1', materialTypeId: 'm1', quantity: '2', unitPrice: '10', amountCap: '22.60' });
    await vm.submitPreview();
    vm.handleConfirmed({ status: 'FAILED', resultData: { grantId: 'preview-18' } });
    expect(vm.recoveryState).toBe('UNKNOWN');
    expect(vm.blockedReason).toContain('刷新授权状态');
    expect(previewMock).toHaveBeenCalledTimes(1);
  });

  it('ignores a late recovery response after unmount and does not recreate scoped state', async () => {
    let resolveStatus!: (value: unknown) => void;
    statusMock.mockReturnValue(new Promise((resolve) => { resolveStatus = resolve; }));
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-17' }));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await Promise.resolve();
    wrapper.unmount();
    resolveStatus({ grantId: 'preview-17', state: 'DRAFT', purchaseOrderId: 'PO-17', version: 11 });
    await flushPromises();
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toContain('preview-17');
    expect(pushMock).not.toHaveBeenCalled();
  });

  it('requires an explicit new-task action after a durable FAILED status readback', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-19' }));
    statusMock.mockResolvedValue({ grantId: 'preview-19', state: 'FAILED' });
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    expect(previewMock).not.toHaveBeenCalled();
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toContain('preview-19');
    await wrapper.findAll('button').find((b) => b.text() === '已核对失败，开始新采购')!.trigger('click');
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toBeNull();
    expect(previewMock).not.toHaveBeenCalled();
  });

  it('discards a rejected revoke after a user/factory switch and resets old form data', async () => {
    localStorage.setItem('cretas:controlled-purchase:F001:1553', JSON.stringify({ grantId: 'preview-20' }));
    let rejectRevoke!: (error: Error) => void;
    revokeMock.mockReturnValue(new Promise((_, reject) => { rejectRevoke = reject; }));
    const wrapper = mount(AiPurchasePage, { global: { stubs } });
    await flushPromises();
    const vm = wrapper.vm as any;
    vm.form.remark = 'old private remark';
    await wrapper.findAll('button').find((b) => b.text() === '撤销当前授权')!.trigger('click');
    Object.assign(authFixture.current!, { factoryId: 'F002', user: { id: 1554 } });
    await flushPromises();
    rejectRevoke(new Error('old tenant revoke error'));
    await flushPromises();
    expect(wrapper.text()).not.toContain('old tenant revoke error');
    expect(vm.form.remark).toBe('');
    expect(vm.grantId).toBe('');
    expect(vm.recoveryLoading).toBe(false);
    expect(vm.cancelInFlight).toBe(false);
    expect(localStorage.getItem('cretas:controlled-purchase:F001:1553')).toContain('preview-20');
  });

  it('exposes the procurement scoped route and menu child without analytics permission', () => {
    const route = router.getRoutes().find((item) => item.path === '/procurement/ai-purchase');
    const procurement = menuConfig.find((item) => item.path === '/procurement');
    expect(route?.meta.module).toBe('procurement');
    expect(procurement?.children?.some((item) => item.path === '/procurement/ai-purchase')).toBe(true);
  });
});
