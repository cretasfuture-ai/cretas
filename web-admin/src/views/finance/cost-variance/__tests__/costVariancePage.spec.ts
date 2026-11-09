import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { reactive } from 'vue';

const api = vi.hoisted(() => ({ report: vi.fn(), plan: vi.fn(), message: vi.fn(), download: vi.fn() }));
const auth = reactive({ factoryId: 'F006' });
vi.mock('@/api/costVariance', () => ({ getCostVarianceReport: api.report, getPlanCostVariance: api.plan }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('element-plus', async (original) => ({ ...(await original<typeof import('element-plus')>()), ElMessage: api.message }));
vi.mock('../costVarianceExport', () => ({ downloadSheet: api.download }));
import Page from '../index.vue';

const planSummary = {
  planId: 'P1', planNumber: 'PLAN-QA-09', productTypeId: 'SKU', productName: '卤牛肉', planStatus: 'COMPLETED',
  comparable: true, comparabilityReason: '1 项无法比较, 未计入合计 (见各项原因)', snapshotCapturedAt: '2026-11-03T16:00:00',
  outputQuantity: 80, outputUnit: 'kg', outputKg: 80, standardCost: 1000, actualCost: 1200, totalVariance: 200,
  varianceRate: 20, priceVariance: 200, usageVariance: 0, substituteVariance: 0, laborVariance: 0,
  reworkVariance: 32.34, reworkStatus: 'COMPARABLE', reworkQuantityText: '2.5 kg',
  recoveredMaterialNote: '回投 2.5 kg 回料, 按回料池单位成本 (产出回料的工序成本 ÷ 回料量, 与成本核算同源)计价; 配方不含回料, 标准回料用量为 0, 回料差 +¥32.34 已计入合计',
  comparableItemCount: 1, incomparableItemCount: 1,
  customerSuppliedItemCount: 0, priceMasked: false, lines: [], allocationMethods: [],
};
const noReworkSummary = {
  ...planSummary, planId: 'P2', planNumber: 'PLAN-QA-NORW', reworkVariance: null, reworkStatus: 'NONE',
  reworkQuantityText: null, recoveredMaterialNote: '本计划没有回投回料',
};
const reworkLine = {
  lineType: 'REWORK', materialTypeId: null, materialName: '卤牛肉 (回料)', materialCategory: null, parentMaterialTypeId: null,
  priceUnit: 'kg', snapshotUnitPrice: null, standardQuantity: 0, standardAmount: 0, actualQuantity: 2.5,
  actualUnitPrice: 12.9375, actualAmount: 32.34, priceVariance: null, usageVariance: null, substituteVariance: null,
  laborVariance: null, reworkVariance: 32.34, reworkPoolNo: 'CLK-SEMI-f9ba5b6f-fe2c87f5',
  priceSource: '回料池单位成本 (产出回料的工序成本 ÷ 回料量, 与成本核算同源)',
  reworkSources: [{ producerBatchNumber: 'PB-A1', producerPlanId: 'PA', producerPlanNumber: 'PLAN-A', quantity: 2.5, unit: 'kg' }],
  status: 'COMPARABLE', reasons: [],
};
const reworkOutputLine = {
  lineType: 'REWORK_OUTPUT', materialTypeId: null, materialName: '卤牛肉 (回料产出转出)', materialCategory: null,
  parentMaterialTypeId: null, priceUnit: 'kg', snapshotUnitPrice: null, standardQuantity: 0, standardAmount: 0,
  actualQuantity: 3, actualUnitPrice: 12.9375, actualAmount: -38.81, priceVariance: null, usageVariance: null,
  substituteVariance: null, laborVariance: null, reworkVariance: null, reworkPoolNo: 'CLK-SEMI-P1',
  priceSource: '回料池单位成本 (产出回料的工序成本 ÷ 回料量, 与成本核算同源)', reworkSources: [],
  status: 'INCOMPARABLE', reasons: ['主料用量差算不出 (配方没有出成率 / 缺领用等), 回料顶替或带走的原料看不到, 单算回料会失真, 回料差无法比较'],
};

beforeEach(() => {
  api.report.mockReset(); api.plan.mockReset(); api.message.mockReset();
  api.report.mockResolvedValue({ success: true, data: {
    comparable: true, comparabilityReason: null, standardCostBasis: '标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照',
    processSecretNote: null, startDate: '2026-10-04', endDate: '2026-11-03', totalBomCost: 1000, totalActualCost: 1364,
    totalVariance: 200, totalVarianceRate: 20, varianceStatus: 'CRITICAL', priceVariance: 200, usageVariance: 0,
    substituteVariance: null, laborVariance: 0, reworkVariance: 32.34, reworkStatus: 'COMPARABLE',
    reworkSummary: '1 个计划有回料: 1 个回料差已计入合计', comparedPlanCount: 2, incomparablePlanCount: 0,
    batchCount: 2, planVariances: [planSummary, noReworkSummary],
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
      reworkLine,
      reworkOutputLine,
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

  it('回料差: 汇总卡/计划表单列; 有回料的计划写数和回投量, 没回料的写「无回料」不写 ¥0', async () => {
    const wrapper = mountPage(); await flushPromises();
    expect(wrapper.get('[data-testid="summary-rework-variance"]').text()).toContain('+¥32.34');
    expect(wrapper.get('[data-testid="summary-rework-variance"]').text()).toContain('1 个回料差已计入合计');
    const table = wrapper.get('[data-testid="cost-variance-plans"]').text();
    expect(table).toContain('回投 2.5 kg');
    const rows = Array.from(wrapper.get('[data-testid="cost-variance-plans"]').element.querySelectorAll('.el-table__body tr'));
    const noRework = rows.find((r) => r.textContent?.includes('PLAN-QA-NORW'));
    const reworkCell = noRework?.querySelectorAll('td')[7]?.textContent ?? '';
    expect(reworkCell).toContain('无回料');
    expect(reworkCell, '没有回料 ⛔ 不写「回料差 ¥0」').not.toContain('¥');
    wrapper.unmount();
  });

  it('明细: 回料行写回投量、单价来源、回料差和来源批次; 回料说明置顶', async () => {
    const wrapper = mountPage(); await flushPromises();
    await wrapper.get('[data-testid="plan-detail-P1"]').trigger('click'); await flushPromises();
    const detail = document.body.querySelector('[data-testid="plan-cost-variance-detail"]')?.textContent ?? '';
    expect(detail).toContain('卤牛肉 (回料)');
    expect(detail).toContain('配方不含回料');
    expect(detail).toContain('¥12.9375/kg');
    expect(detail).toContain('回料池单位成本');
    expect(detail).toContain('回料 +¥32.34');
    expect(document.body.querySelector('[data-testid="rework-sources"]')?.textContent).toContain('PLAN-A PB-A1 2.5 kg');
    expect(document.body.querySelector('[data-testid="plan-rework-note"]')?.textContent).toContain('回投 2.5 kg');
    expect(document.body.querySelector('[data-testid="detail-rework-variance"]')?.textContent).toContain('+¥32.34');
    // 产出转出行: 金额照列为负, 主料用量差算不出时回料差写「无法比较」不写 0
    expect(detail).toContain('卤牛肉 (回料产出转出)');
    expect(detail).toContain('回料转出');
    expect(detail).toContain('−¥38.81');
    expect(detail).not.toContain('¥-');
    expect(detail).toContain('转出 无法比较');
    expect(detail).toContain('主料用量差算不出');
    wrapper.unmount();
  });

  it('导出: 期间表与明细表的回料文字与页面同一句', async () => {
    const wrapper = mountPage(); await flushPromises();
    await wrapper.get('[data-testid="cost-variance-export"]').trigger('click');
    const [periodRows, periodSheet] = api.download.mock.calls[0];
    expect(periodSheet).toBe('成本差异');
    const flat = (rows: (string | number)[][]) => rows.map((r) => r.join('|')).join('\n');
    expect(flat(periodRows)).toContain('回料差|+¥32.34|1 个计划有回料: 1 个回料差已计入合计');
    expect(flat(periodRows)).toContain('PLAN-QA-09');
    expect(flat(periodRows)).toContain('|+¥32.34|回投 2.5 kg|');
    expect(flat(periodRows)).toContain('PLAN-QA-NORW');
    expect(flat(periodRows)).toContain('|无回料|—|本计划没有回投回料|');

    await wrapper.get('[data-testid="plan-detail-P1"]').trigger('click'); await flushPromises();
    (document.body.querySelector('[data-testid="plan-cost-variance-export"]') as HTMLElement).click();
    await flushPromises();
    const [planRows] = api.download.mock.calls[1];
    const reworkRow = planRows.find((r: (string | number)[]) => r[0] === '卤牛肉 (回料)');
    expect(reworkRow).toEqual(['卤牛肉 (回料)', '回料', '配方不含回料', '0 kg', '2.5 kg', '¥12.9375/kg', '¥0.00', '¥32.34', '—',
      '回料 +¥32.34', '可比', '', 'PLAN-A PB-A1 2.5 kg']);
    expect(flat(planRows)).toContain('回料差|+¥32.34');
    const outRow = planRows.find((r: (string | number)[]) => r[0] === '卤牛肉 (回料产出转出)');
    expect(outRow?.[1]).toBe('回料转出');
    expect(outRow?.[9]).toBe('转出 无法比较');
    expect(outRow?.[10]).toBe('无法比较');
    wrapper.unmount();
  });

  it('无采购价权限: 回料金额写「已隐藏」, 不写「无法比较」', async () => {
    api.plan.mockResolvedValueOnce({ success: true, data: { ...planSummary, priceMasked: true, reworkPriceMasked: true,
      reworkVariance: null, recoveredMaterialNote: '回投 2.5 kg 回料, …; 回料差 (金额已隐藏) 已计入合计',
      lines: [{ ...reworkLine, actualUnitPrice: null, actualAmount: null, reworkVariance: null }], allocationMethods: [] } });
    const wrapper = mountPage(); await flushPromises();
    await wrapper.get('[data-testid="plan-detail-P1"]').trigger('click'); await flushPromises();
    const detail = document.body.querySelector('[data-testid="plan-cost-variance-detail"]')?.textContent ?? '';
    expect(document.body.querySelector('[data-testid="detail-rework-variance"]')?.textContent).toContain('已隐藏');
    expect(detail).toContain('回料 已隐藏');
    expect(detail).not.toContain('回料 无法比较');
    expect(detail).not.toContain('12.9375');
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
