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

  describe('超时处置 (缺口 14)', () => {
    const openEvent = {
      id: 5, targetType: 'SEMI_FINISHED', targetId: '77', batchNumber: 'SEMI-9', processName: '卤制', limitMinutes: 120,
      startEvent: 'OUTPUT_POSTED', mixRule: 'EARLIEST', startAt: '2026-11-04T08:00:00', deadlineAt: '2026-11-04T10:00:00',
      blockedAt: '2026-11-04T10:30:00', expiredQuantity: 12.5, unit: 'kg', recheckValidMinutes: 30, status: 'OPEN',
      conclusion: null, decidedByName: null, decidedAt: null, validUntil: null,
    };
    const decided = {
      ...openEvent, id: 4, batchNumber: 'SEMI-8', status: 'RELEASED', conclusion: '复检合格',
      decidedByName: '王质检', decidedAt: '2026-11-04T09:00:00', validUntil: '2026-11-04T09:30:00',
    };

    beforeEach(() => {
      state.get.mockImplementation((url: string) => {
        if (url.endsWith('/events')) return Promise.resolve(ok([openEvent]));
        if (url.endsWith('/events/records')) return Promise.resolve(ok([decided, openEvent]));
        return Promise.resolve(ok(url.endsWith('/pending') ? pendingRows : []));
      });
      state.post.mockResolvedValue({ success: true, data: decided, message: '已复检放行' });
    });

    const conclusionBox = () => (document.querySelector('textarea[data-testid="timeout-conclusion"]')
      ?? document.querySelector('[data-testid="timeout-conclusion"] textarea')) as HTMLTextAreaElement;
    const timeoutSubmit = () => document.querySelector('[data-testid="timeout-decision-submit"]') as HTMLButtonElement;

    it('列出待处置批次和已处置记录; 待处置的不混进记录', async () => {
      const wrapper = mountPage();
      await flushPromises();
      const open = wrapper.get('[data-testid="timeout-events-table"]').text();
      expect(open).toContain('SEMI-9');
      expect(open).toContain('2 小时');
      expect(open).toContain('2026-11-04 08:00（上道产出入账）');
      expect(open).toContain('12.5');
      const records = wrapper.get('[data-testid="timeout-records-table"]').text();
      expect(records).toContain('SEMI-8');
      expect(records).toContain('王质检');
      expect(records).not.toContain('SEMI-9');
      expect(wrapper.text()).toContain('超时处置 (1)');
      wrapper.unmount();
    });

    it('标题带批次号; 没选结果或没填结论不能提交; 提交发到这条事件上', async () => {
      const wrapper = mountPage();
      await flushPromises();
      await wrapper.get('[data-testid="timeout-decide-5"]').trigger('click');
      await flushPromises();
      expect(document.body.textContent).toContain('超时处置 — 卤制 (SEMI-9)');
      expect(timeoutSubmit().disabled, '没选结果').toBe(true);
      expect(document.body.textContent).toContain('请选择处置结果');

      (document.querySelector('[data-testid="timeout-decision-released"] input') as HTMLInputElement).click();
      await flushPromises();
      expect(timeoutSubmit().disabled, '没填结论').toBe(true);
      expect(document.body.textContent).toContain('放行后 30 分钟 内可以领用');

      const box = conclusionBox();
      box.value = '中心温度复测合格';
      box.dispatchEvent(new Event('input'));
      await flushPromises();
      timeoutSubmit().click();
      await flushPromises();
      expect(state.post).toHaveBeenCalledWith('/F-QC/process-time-limits/events/5/decision', {
        decision: 'RELEASED', conclusion: '中心温度复测合格',
      });
      wrapper.unmount();
    });
  });

  it('D-S411-04 原料收货批次: 行里显示物料名 / 批次号 / 数量单位, 放行发出 RAW_MATERIAL_BATCH', async () => {
    const rawRow = {
      targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-raw-1', formLabel: '原料', batchNumber: 'MT-20261110-0001',
      productTypeId: null, productName: '冻猪蹄', quantity: 120, unit: 'kg', producedAt: '2026-11-10 08:00',
      openDeviationCount: 0, materialTypeId: 'RMT-PORK',
    };
    state.get.mockImplementation((url: string) =>
      Promise.resolve(ok(url.endsWith('/pending') ? [...pendingRows, rawRow] : [])));
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('待检 (3)');
    const rawTr = wrapper.findAll('[data-testid="qc-pending-table"] tbody tr')
      .find(tr => tr.text().includes('MT-20261110-0001'));
    expect(rawTr, '原料行要渲染出来').toBeTruthy();
    const rowText = rawTr!.text();
    expect(rowText).toContain('原料');
    expect(rowText).toContain('冻猪蹄');
    expect(rowText).toContain('120 kg');
    expect(rowText).toContain('原料不能领用投产');

    await openFor(wrapper, 'mb-raw-1');
    expect(document.body.textContent).toContain('放行后原料可以领用投产');
    const textarea = (document.querySelector('textarea[data-testid="qc-conclusion"]') ?? document.querySelector('[data-testid="qc-conclusion"] textarea')) as HTMLTextAreaElement;
    textarea.value = '感官检验合格';
    textarea.dispatchEvent(new Event('input'));
    await flushPromises();
    expect(submitButton().disabled).toBe(false);
    submitButton().click();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-QC/qc-release/decisions', {
      targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-raw-1', decision: 'RELEASED', conclusion: '感官检验合格',
    });
    wrapper.unmount();
  });

  it('放行记录里原料形态显示「原料」, 不是英文码', async () => {
    state.get.mockImplementation((url: string) => Promise.resolve(ok(url.endsWith('/records') ? [{
      id: 9, targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-raw-1', batchNumber: 'MT-REC-1', productTypeId: null,
      quantity: 120, unit: 'kg', decision: 'RELEASED', conclusion: '合格', releasedBy: 3, releasedByName: '王质检',
      releasedAt: '2026-11-10 09:00',
    }] : [])));
    const wrapper = mountPage();
    await flushPromises();
    const recordsText = wrapper.findAll('.el-table').map(t => t.text()).find(t => t.includes('MT-REC-1'));
    expect(recordsText).toBeTruthy();
    expect(recordsText).toContain('原料');
    expect(recordsText).not.toContain('RAW_MATERIAL_BATCH');
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
