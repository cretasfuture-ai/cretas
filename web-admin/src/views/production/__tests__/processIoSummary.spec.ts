import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';
import {
  averageScopeNote,
  formatQuantity,
  formatQuantityOrDash,
  incomparableLabel,
  realUnit,
  summaryKpi,
  type ProcessYieldAgg,
} from '../processIoSummary';

/**
 * 工序投入产出对比 ·「工序汇总」(2026-11-12 网页验收 F006 两处问题):
 *  1) QA-S3-1104-腌制 显示「0.2 unitless」—— 'unitless' 是后端「没有单位」的占位;
 *  2) 定量包装 投入 kg/袋、产出 袋/盒 直接相除得 333%~500%, 平均转化率 141.2% 把它们混进来。
 */

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
const apiGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
}));

import ProcessIOComparison from '../ProcessIOComparison.vue';

function agg(p: Partial<ProcessYieldAgg> & { processName: string }): ProcessYieldAgg {
  return {
    inputQuantity: 100, outputQuantity: 90, conversionRate: 90, wastageRate: 10,
    unit: 'kg', inputUnit: 'kg', outputUnit: 'kg', unitComparable: true, incomparableReason: null,
    batchCount: 1, ...p,
  };
}

// 新后端对验收原形的返回 (YieldAnalysisServiceImpl 单测钉住同样的三种情形)
const ROWS: ProcessYieldAgg[] = [
  agg({ processName: '清洗', inputQuantity: 200, outputQuantity: 180, conversionRate: 90, wastageRate: 10 }),
  agg({ processName: '切丁', inputQuantity: 100, outputQuantity: 70, conversionRate: 70, wastageRate: 30 }),
  agg({ processName: 'QA-S3-1104-腌制', inputQuantity: 0.2, outputQuantity: 0.1, conversionRate: null,
    wastageRate: null, unit: null, inputUnit: null, outputUnit: null, unitComparable: false,
    incomparableReason: 'NO_UNIT' }),
  agg({ processName: '定量包装', inputQuantity: null, outputQuantity: null, conversionRate: null,
    wastageRate: null, unit: null, inputUnit: null, outputUnit: null, unitComparable: false,
    incomparableReason: 'MIXED_UNITS' }),
  agg({ processName: '装盒', inputQuantity: 998, outputQuantity: 3184, conversionRate: null, wastageRate: null,
    inputUnit: '袋', outputUnit: '盒', unit: '袋', unitComparable: false, incomparableReason: 'UNIT_MISMATCH' }),
];

describe('单位显示: unitless 不再原样露给用户, 也不猜成 kg', () => {
  it('unitless / 空 / null 都算没有单位', () => {
    expect(realUnit('unitless')).toBeNull();
    expect(realUnit(' UNITLESS ')).toBeNull();
    expect(realUnit('')).toBeNull();
    expect(realUnit(null)).toBeNull();
    expect(realUnit(' kg ')).toBe('kg');
  });
  it('没有单位写「无单位」', () => {
    expect(formatQuantity(0.2, 'unitless')).toBe('0.2（无单位）');
    expect(formatQuantity(0.2, null)).toBe('0.2（无单位）');
    expect(formatQuantity(0.2, 'unitless')).not.toContain('kg');
    expect(formatQuantity(12.5, 'kg')).toBe('12.5 kg');
  });
  it('一侧单位混杂 (数量 null) 不给假合计', () => {
    expect(formatQuantity(null, null)).toBe('多种单位，未合计');
    expect(formatQuantityOrDash(null, 'kg')).toBe('—');
    expect(formatQuantityOrDash(3, 'unitless')).toBe('3（无单位）');
  });
});

describe('转化率: 跨单位不相除, 平均只用同单位工序并写明口径', () => {
  it('同单位正常平均; 跨单位 / 无单位 / 混杂单位都不计入', () => {
    const kpi = summaryKpi(ROWS);
    expect(kpi.processCount).toBe(5);
    expect(kpi.comparableCount).toBe(2);
    expect(kpi.avgConversion).toBe(80);   // (90 + 70) / 2, 不是把 333%/500% 混进去的 141.2%
    expect(kpi.avgWastage).toBe(20);
    expect(kpi.lowEfficiencyCount).toBe(1);
    expect(averageScopeNote(kpi)).toBe('按 2 道同单位工序平均（共 5 道，3 道无法比较未计入）');
  });
  it('一道能比的都没有: 平均为空 (显示「—」), 不是 0%', () => {
    const kpi = summaryKpi(ROWS.slice(2));
    expect(kpi.avgConversion).toBeNull();
    expect(kpi.avgWastage).toBeNull();
    expect(averageScopeNote(kpi)).toBe('3 道工序都无法比较，未计算平均');
  });
  it('每种不可比原因有对应的中文说明', () => {
    expect(incomparableLabel(ROWS[4])).toBe('单位不同，无法比较');
    expect(incomparableLabel(ROWS[3])).toBe('单位不统一，无法比较');
    expect(incomparableLabel(ROWS[2])).toBe('未登记单位，无法比较');
    expect(incomparableLabel(ROWS[0])).toBe('');
  });
});

describe('ProcessIOComparison 工序汇总 —— 真实挂载', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiGet.mockImplementation((url: string) =>
      Promise.resolve({ success: true, data: url.includes('/production/yield/by-process') ? ROWS : [] }));
  });

  it('页面上: 无单位、跨单位、平均口径都按新规则显示', async () => {
    const wrapper = mount(ProcessIOComparison, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    const text = wrapper.text();
    expect(text).not.toContain('unitless');
    expect(text).toContain('0.2（无单位）');
    expect(text).toContain('0.1（无单位）');
    expect(text).toContain('998 袋');
    expect(text).toContain('3184 盒');
    expect(text).toContain('单位不同，无法比较');
    expect(text).toContain('多种单位，未合计');
    expect(text).not.toMatch(/31[89]\.\d%|333|500%/);
    expect(wrapper.find('[data-testid="kpi-avg-conversion"]').text()).toBe('80%');
    expect(wrapper.find('[data-testid="kpi-scope-note"]').text()).toContain('按 2 道同单位工序平均');
    wrapper.unmount();
  });

  it('旧后端只给 unit=unitless 时也显示「无单位」', async () => {
    apiGet.mockImplementation((url: string) => Promise.resolve({
      success: true,
      data: url.includes('/production/yield/by-process')
        ? [{ processName: '腌制', inputQuantity: 0.2, outputQuantity: 0.1, conversionRate: 50, wastageRate: 50,
            unit: 'unitless', unitComparable: true, batchCount: 1 }]
        : [],
    }));
    const wrapper = mount(ProcessIOComparison, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('unitless');
    expect(wrapper.text()).toContain('0.2（无单位）');
    wrapper.unmount();
  });
});
