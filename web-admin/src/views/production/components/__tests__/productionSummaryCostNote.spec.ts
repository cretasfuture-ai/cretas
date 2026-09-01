/**
 * 守: 总成本未知(null)时把 costNote 显示出来, ⛔ 不画成 ¥0, 也不只给一个「—」。
 * 2026-09-02 本机 UAT: 小吃吧线成品批次上游半成品单价未知, 后端现在返回 totalCost=null + costNote;
 * 这里要让人看得见「为什么没有数」。
 * 阴性对照: 总成本已知时不显示 costNote; 脱敏时显示「无采购价权限」而不是 costNote。
 */
import ElementPlus from 'element-plus';
import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

const getProductionSummary = vi.fn();
vi.mock('@/api/productionPlan', () => ({ getProductionSummary: (...a: unknown[]) => getProductionSummary(...a) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));

import ProductionSummaryDialog from '../ProductionSummaryDialog.vue';

const base = {
  planId: 'p1', planNumber: 'PLAN-1', productTypeId: 'x', productName: '小吃吧',
  totalRawInput: 3705, reusedFrontRawInput: 0, yieldRawDenominator: 3705,
  totalFinishedOutput: 530, totalFinishedWeight: 5088, remainingSemiFinished: 0,
  realYieldRate: 137.33, yieldNote: null, batches: [], reusedSemiLineages: null,
};

async function mountWith(summary: Record<string, unknown>) {
  getProductionSummary.mockResolvedValue({ success: true, data: { ...base, ...summary } });
  // ⚠️ 组件的 watch(modelValue) 不是 immediate: 直接以 true 挂载不会触发加载, 屏幕永远是「暂无汇总数据」
  //    (仪器第一版就是这么坏的: 三条全红, 包括阴性对照 —— 那是仪器坏, 不是产品坏)。先 false 再翻 true。
  const wrapper = mount(ProductionSummaryDialog, {
    props: { modelValue: false, factoryId: 'LOCALUAT', planId: 'p1' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises(); await nextTick();
  return wrapper;
}

describe('ProductionSummaryDialog 总成本未知时的说明', () => {
  beforeEach(() => { document.body.innerHTML = ''; getProductionSummary.mockReset(); });

  it('totalCost 为 null 且有 costNote → 显示原因, 且不显示 ¥0', async () => {
    const w = await mountWith({ totalCost: null, priceMasked: false,
      costNote: '成本不完整: 批次 CLK-B-2 成本未知（缺 SEMI_FINISHED_OR_FINISHED_FEED_PRICE）' });
    const note = document.body.querySelector('[data-testid="summary-cost-note"]');
    expect(note?.textContent).toContain('CLK-B-2');
    expect(document.body.textContent).not.toContain('¥ 0.00');
    w.unmount();
  });

  it('阴性对照: 总成本已知 → 即使后端带了 costNote 也不显示', async () => {
    const w = await mountWith({ totalCost: 74544.28, priceMasked: false, costNote: '不该出现' });
    expect(document.body.querySelector('[data-testid="summary-cost-note"]')).toBeNull();
    expect(document.body.textContent).toContain('74,544.28');
    w.unmount();
  });

  it('阴性对照: 脱敏 → 显示「无采购价权限」, 不显示 costNote', async () => {
    const w = await mountWith({ totalCost: null, priceMasked: true, costNote: '不该出现' });
    expect(document.body.textContent).toContain('无采购价权限');
    expect(document.body.querySelector('[data-testid="summary-cost-note"]')).toBeNull();
    w.unmount();
  });
});
