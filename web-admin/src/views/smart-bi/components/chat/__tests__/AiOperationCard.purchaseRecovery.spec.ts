import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const confirmMock = vi.fn();
const boxMock = vi.fn(async () => 'confirm');
vi.mock('@/api/smartbi/intent-chat', () => ({ confirmIntentAction: (...args: unknown[]) => confirmMock(...args) }));
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: (...args: unknown[]) => boxMock(...args) } };
});
import AiOperationCard from '../AiOperationCard.vue';

const stubs = {
  'el-button': { props: ['disabled', 'loading'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
  'el-tag': { template: '<span><slot /></span>' },
};
const purchaseResponse = (seconds: number) => ({
  status: 'PREVIEW', intentCode: 'PURCHASE_ORDER_CREATE', intentName: 'AI受控采购', message: '', formattedText: '',
  confirmableAction: { confirmToken: 'proof', commandDigest: 'd'.repeat(64), expiresAt: new Date(Date.now() + 300000).toISOString(), expiresInSeconds: seconds, previewData: { grantId: 'preview-1', amountCap: '22.60' } },
});

describe('AiOperationCard canonical purchase recovery', () => {
  beforeEach(() => { vi.useFakeTimers(); confirmMock.mockReset(); boxMock.mockClear(); });
  afterEach(() => { vi.useRealTimers(); });

  it('emits uncertainty on canonical timeout and never offers a retry', async () => {
    const wrapper = mount(AiOperationCard, { props: { response: purchaseResponse(1) as any, factoryId: 'F001' }, global: { stubs } });
    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.emitted('uncertain')).toBeTruthy();
    expect(wrapper.text()).toContain('结果待核对');
    expect(wrapper.text()).not.toContain('重新发起');
    expect(confirmMock).not.toHaveBeenCalled();
  });

  it('renders UNKNOWN confirmation as terminal uncertainty and forwards proof unchanged', async () => {
    confirmMock.mockResolvedValue({ status: 'OUTCOME_UNKNOWN', message: '待核对', resultData: { grantId: 'preview-1' } });
    const wrapper = mount(AiOperationCard, { props: { response: purchaseResponse(300) as any, factoryId: 'F001' }, global: { stubs } });
    await wrapper.findAll('button').find((button) => button.text().includes('确认执行'))!.trigger('click');
    await flushPromises();
    expect(confirmMock).toHaveBeenCalledWith('F001', 'proof', expect.objectContaining({ commandDigest: 'd'.repeat(64) }));
    expect(wrapper.emitted('uncertain')).toBeTruthy();
    expect(wrapper.text()).toContain('待核对');
  });

  it('accepts a canonical SUCCESS only when the returned grant reference matches the preview', async () => {
    confirmMock.mockResolvedValue({ status: 'SUCCESS', message: '采购草稿已创建', resultData: { grantId: 'preview-1' } });
    const wrapper = mount(AiOperationCard, { props: { response: purchaseResponse(300) as any, factoryId: 'F001' }, global: { stubs } });
    await wrapper.findAll('button').find((button) => button.text().includes('确认执行'))!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('采购草稿已创建');
    expect(wrapper.emitted('uncertain')).toBeFalsy();
  });

  it('keeps ordinary purchase-free card success behavior', async () => {
    confirmMock.mockResolvedValue({ status: 'SUCCESS', message: '普通操作完成' });
    const ordinary = { ...purchaseResponse(300), intentCode: 'MATERIAL_INBOUND', confirmableAction: { ...purchaseResponse(300).confirmableAction, previewData: { material: '五花肉' } } };
    const wrapper = mount(AiOperationCard, { props: { response: ordinary as any, factoryId: 'F001' }, global: { stubs } });
    await wrapper.findAll('button').find((button) => button.text().includes('确认执行'))!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('普通操作完成');
    expect(wrapper.emitted('uncertain')).toBeFalsy();
  });

  it('waits for server revoke acknowledgement before collapsing canonical cancellation', async () => {
    let resolveCancel!: (value: boolean) => void;
    const cancelHandler = vi.fn(() => new Promise<boolean>((resolve) => { resolveCancel = resolve; }));
    const wrapper = mount(AiOperationCard, {
      props: { response: purchaseResponse(300) as any, factoryId: 'F001', cancelHandler },
      global: { stubs },
    });
    await wrapper.findAll('button').find((button) => button.text() === '取消')!.trigger('click');
    await flushPromises();
    expect(cancelHandler).toHaveBeenCalledWith('preview-1');
    expect(wrapper.text()).not.toBe('已取消');
    resolveCancel(true);
    await flushPromises();
    expect(wrapper.text()).toBe('已取消');
  });
});
