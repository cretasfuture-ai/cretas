import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { reactive } from 'vue';

const api = vi.hoisted(() => ({ report: vi.fn(), plan: vi.fn(), message: vi.fn() }));
const auth = reactive({ factoryId: 'F006' });
vi.mock('@/api/costVariance', () => ({ getCostVarianceReport: api.report, getPlanCostVariance: api.plan }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('element-plus', async (original) => ({ ...(await original<typeof import('element-plus')>()), ElMessage: api.message }));
import Page from '../index.vue';

const planSummary = {
  planId: 'P1', planNumber: 'PLAN-QA-09', productTypeId: 'SKU', productName: '卤牛肉', planStatus: 'COMPLETED',
  comparable: true, comparabilityReason: '1 项无法比较, 未计入合计 (见各项原因)', snapshotCapturedAt: '2026-11-03T16:00:00',
  outputQuantity: 80, outputUnit: 'kg', outputKg: 80, standardCost: 1000, actualCost: 1200, totalVariance: 200,
  varianceRate: 20, priceVariance: 200, usageVariance: 0, substituteVariance: 0, laborVariance: 0,
  recoveredMaterialNote: '回料差异本期不单列', comparableItemCount: 1, incomparableItemCount: 1,
  customerSuppliedItemCount: 0, priceMasked: false, lines: [], allocationMethods: [],
};

beforeEach(() => {
  api.report.mockReset(); api.plan.mockReset(); api.message.mockReset();
  api.report.mockResolvedValue({ success: true, data: {
    comparable: true, comparabilityReason: null, standardCostBasis: '标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照',
    processSecretNote: null, startDate: '2026-10-04', endDate: '2026-11-03', totalBomCost: 1000, totalActualCost: 1364,
    totalVariance: 200, totalVarianceRate: 20, varianceStatus: 'CRITICAL', priceVariance: 200, usageVariance: 0,
    substituteVariance: null, laborVariance: 0, comparedPlanCount: 1, incomparablePlanCount: 0, batchCount: 2,
    planVariances: [planSummary],
  } });
  api.plan.mockResolvedValue({ success: true, data: { ...planSummary,
    lines: [
      { lineType: 'MATERIAL', materialTypeId: 'BEEF', materialName: '牛肉', materialCategory: 'RAW', parentMaterialTypeId: null,
        priceUnit: 'kg', snapshotUnitPrice: 10, standardQuantity: 100, standardAmount: 1000, actualQuantity: 100,
        actualUnitPrice: 12, actualAmount: 1200, priceVariance: 200, usageVariance: 0, substituteVariance: null,
        laborVariance: null, status: 'COMPARABLE', reasons: [] },
      { lineType: 'MATERIAL', materialTypeId: 'BOX', materialName: '纸箱', materialCategory: 'PACKAGING', parentMaterialTypeId: null,
        priceUnit: '个', snapshotUnitPrice: null, standardQuantity: 80, standardAmount: null, actualQuantity: 82,
        actualUnitPrice: 2, actualAmount: 164, priceVariance: null, usageVariance: null, substituteVariance: null,
        laborVariance: null, status: 'INCOMPARABLE', reasons: ['开计划时物料档案没有单价'] },
    ],
    allocationMethods: [{ processCode: 'SPLIT', processOrder: 2, basis: 'FIXED_RATIO', basisLabel: '固定比例 (工艺版本)',
      outputs: [
        { productTypeId: 'A', productName: '牛腱', batchNumber: 'B1', costAllocationRatio: 80, workflowFixedCostShare: 80, allocatedCost: 800 },
        { productTypeId: 'B', productName: '牛杂', batchNumber: 'B2', costAllocationRatio: 20, workflowFixedCostShare: 20, allocatedCost: 200 },
      ] }],
  } });
});

function mountPage() {
  return mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
}

describe('成本差异报表 (缺口 09/10)', () => {
  it('汇总把差异拆成价格差/用量差, 后端给 null 的分项写「无法比较」不写 0', async () => {
    const wrapper = mountPage(); await flushPromises();
    expect(api.report).toHaveBeenCalledWith('F006', expect.any(String), expect.any(String));
    const summary = wrapper.get('[data-testid="cost-variance-summary"]').text();
    expect(summary).toContain('+¥200.00');
    expect(wrapper.get('[data-testid="summary-price-variance"]').text()).toContain('+¥200.00');
    expect(summary).toContain('无法比较'); // 替代料差 null
    expect(summary).not.toContain('¥NaN');
    expect(wrapper.get('[data-testid="cost-variance-basis"]').text()).toContain('开计划时物料单价快照');
    wrapper.unmount();
  });

  it('明细: 快照缺价的那一项显示无法比较+原因; 分摊方法注明固定比例', async () => {
    const wrapper = mountPage(); await flushPromises();
    await wrapper.get('[data-testid="plan-detail-P1"]').trigger('click'); await flushPromises();
    expect(api.plan).toHaveBeenCalledWith('F006', 'P1');
    const detail = document.body.querySelector('[data-testid="plan-cost-variance-detail"]')?.textContent ?? '';
    expect(detail).toContain('纸箱');
    expect(detail).toContain('无单价');
    expect(detail).toContain('开计划时物料档案没有单价');
    expect(detail).toContain('无法比较');
    expect(detail).toContain('固定比例 (工艺版本)');
    expect(detail).toContain('牛腱：80.00%');
    expect(detail).toContain('¥800.00');
    wrapper.unmount();
  });

  it('读取失败: 原文 sticky 报错, 不留旧数', async () => {
    api.report.mockRejectedValueOnce(new Error('成本差异读取失败: 数据库不可用'));
    const wrapper = mountPage(); await flushPromises();
    expect(api.message).toHaveBeenCalledWith(expect.objectContaining({
      message: '成本差异读取失败: 数据库不可用', type: 'error', duration: 0, showClose: true }));
    expect(wrapper.find('[data-testid="cost-variance-summary"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
