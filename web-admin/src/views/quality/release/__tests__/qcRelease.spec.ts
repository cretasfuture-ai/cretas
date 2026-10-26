import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 质检放行页 (缺口 11): 待检清单 → 放行 / 不合格, 结论必填; 有未处理 CCP 偏离的批次不能放行。
 * 断言打在真正发出去的请求体和按钮的可用性上 (不是文案存在与否)。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn() }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-QC' }) }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
}));
import Page from '../index.vue';

const ok = (data: unknown) => ({ success: true, data });
const pendingRows = [
  {
    targetType: 'FINISHED_GOODS', targetId: 'fg-1', formLabel: '成品', batchNumber: 'CLK-B-1',
    productTypeId: 'PT-1', productName: '卤猪蹄', quantity: 40, unit: 'kg', producedAt: '2026-10-26 10:00',
    openDeviationCount: 0,
  },
  {
    targetType: 'SEMI_FINISHED', targetId: '77', formLabel: '半成品', batchNumber: 'CLK-SEMI-1',
    productTypeId: 'PT-2', productName: '焯水猪蹄', quantity: 20, unit: 'kg', producedAt: '2026-10-26 09:00',
    openDeviationCount: 2,
  },
];

function mountPage() {
  return mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
}

function submitButton(): HTMLButtonElement {
  const btn = document.querySelector('[data-testid="qc-decision-submit"]') as HTMLButtonElement | null;
  expect(btn, '对话框里应有提交按钮').toBeTruthy();
  return btn!;
}

async function openFor(wrapper: VueWrapper, targetId: string) {
  await wrapper.find(`[data-testid="qc-decide-${targetId}"]`).trigger('click');
  await flushPromises();
}

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  state.get.mockImplementation((url: string) =>
    Promise.resolve(ok(url.endsWith('/pending') ? pendingRows : [])));
  state.post.mockResolvedValue(ok({ id: 1, decision: 'RELEASED' }));
});

describe('质检放行页', () => {
  it('待检清单按后端返回渲染, 并写明待检会挡住什么', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F-QC/qc-release/pending');
    const text = wrapper.text();
    expect(text).toContain('CLK-B-1');
    expect(text).toContain('不能分配、不能发货');
    expect(text).toContain('下道工序不能领用');
    expect(text).toContain('2 条未处理');
    wrapper.unmount();
  });

  it('结论为空不能提交; 填了结论 → 发出 RELEASED + 结论', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openFor(wrapper, 'fg-1');
    expect(submitButton().disabled, '结论为空时提交必须禁用').toBe(true);
    expect(document.body.textContent).toContain('请填写检验结论');

    const textarea = (document.querySelector('textarea[data-testid="qc-conclusion"]') ?? document.querySelector('[data-testid="qc-conclusion"] textarea')) as HTMLTextAreaElement;
    textarea.value = '感官检验合格';
    textarea.dispatchEvent(new Event('input'));
    await flushPromises();
    expect(submitButton().disabled).toBe(false);
    submitButton().click();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-QC/qc-release/decisions', {
      targetType: 'FINISHED_GOODS', targetId: 'fg-1', decision: 'RELEASED', conclusion: '感官检验合格',
    });
    wrapper.unmount();
  });

  it('有未处理 CCP 偏离: 默认选「不合格」, 「放行」被禁用, 提示去处理偏离', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openFor(wrapper, '77');
    const releasedRadio = document.querySelector('[data-testid="qc-decision-released"] input') as HTMLInputElement;
    expect(releasedRadio.disabled).toBe(true);
    expect(document.body.textContent).toContain('还有 2 条未处理的 CCP 偏离');
    const textarea = (document.querySelector('textarea[data-testid="qc-conclusion"]') ?? document.querySelector('[data-testid="qc-conclusion"] textarea')) as HTMLTextAreaElement;
    textarea.value = '中心温度不达标';
    textarea.dispatchEvent(new Event('input'));
    await flushPromises();
    submitButton().click();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-QC/qc-release/decisions',
      expect.objectContaining({ targetId: '77', decision: 'REJECTED' }));
    wrapper.unmount();
  });
});
