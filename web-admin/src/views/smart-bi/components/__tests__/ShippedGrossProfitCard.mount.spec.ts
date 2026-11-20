/**
 * PROTO-GP-SHIPPED — 系统毛利卡 (收入 = 发货收入, Steve 2026-11-18)。
 *
 * 契约: 卡片只转述后端 /finance/report/shipped-gross-profit 的结果:
 *  1. OK → 发货收入 / 销售成本 / 毛利 / 毛利率照数显示;
 *  2. NO_SHIPMENT → 三个数都是「—」并写「缺少发货确认数据」, ⛔ 不显示 0, 不显示订单金额;
 *  3. COST_INCOMPLETE → 收入照数, 成本/毛利「—」+ 原因;
 *  4. 金额被权限脱敏 (status OK 而金额 null) → 「—」+「无权查看金额」, 不读成 0;
 *  5. 接口 403 → 说明需要财务查看权限, 没有任何金额。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ApiError } from '@/types/api';
import type { ShippedGrossProfit } from '@/api/financeReport';

const mockGet = vi.fn();
vi.mock('@/api/financeReport', () => ({
  getShippedGrossProfit: (...args: unknown[]) => mockGet(...args),
}));

import ShippedGrossProfitCard from '../ShippedGrossProfitCard.vue';

const BASIS = {
  revenueBasis: '发货收入：期间内已确认发货（已发货 / 已签收）的发运单实发数量 × 发货不含税单价',
  costBasis: '销售成本：这些发运单实际出库的成品批次单位成本 × 出库数量',
};

function payload(p: Partial<ShippedGrossProfit>): ShippedGrossProfit {
  return {
    periodStart: '2026-11-12', periodEnd: '2026-11-18', status: 'OK', basis: 'GROSS', shipmentLineCount: 0,
    revenue: null, cost: null, grossProfit: null, marginRate: null, unavailableReason: null,
    ...BASIS, ...p,
  };
}

async function render() {
  const w = mount(ShippedGrossProfitCard, {
    props: { factoryId: 'LSMDEMO', periodStart: '2026-11-12', periodEnd: '2026-11-18' },
    global: {
      stubs: { 'el-card': { template: '<div><slot /></div>' } },
      directives: { loading: () => undefined },
    },
  });
  await flushPromises();
  return w;
}

const text = (w: Awaited<ReturnType<typeof render>>, id: string) => w.find(`[data-testid="${id}"]`).text();

describe('ShippedGrossProfitCard', () => {
  beforeEach(() => mockGet.mockReset());

  it('OK: 照数显示发货收入、成本、毛利与毛利率 (LSMDEMO 11-12~11-18 prod 只读读数)', async () => {
    mockGet.mockResolvedValue({ success: true, data: payload({
      status: 'OK', shipmentLineCount: 3, revenue: 14310, cost: 7453.18, grossProfit: 6856.82, marginRate: 47.92,
    }) });
    const w = await render();
    expect(mockGet).toHaveBeenCalledWith('LSMDEMO', '2026-11-12', '2026-11-18');
    expect(text(w, 'shipped-gp-revenue')).toContain('14,310.00');
    expect(text(w, 'shipped-gp-cost')).toContain('7,453.18');
    expect(text(w, 'shipped-gp-profit')).toContain('6,856.82');
    expect(text(w, 'shipped-gp-profit-note')).toBe('毛利率 47.92%');
    expect(text(w, 'shipped-gp-revenue-label')).toBe('发货收入（折扣前，未扣退货）');
    expect(text(w, 'shipped-gp-basis')).toContain('发货不含税单价');
  });

  it('NET: 显示净额口径并在折扣资料缺失时提示原因', async () => {
    mockGet.mockResolvedValue({ success: true, data: payload({
      basis: 'NET', status: 'ADJUSTMENT_INCOMPLETE', shipmentLineCount: 2,
      revenueBasis: '本期已确认发货的不含税金额扣除订单折扣分摊及本期已完成销售退货',
      unavailableReason: '发运单缺少有效的本厂订单折扣资料，请核对关联订单与折扣',
    }) });
    const w = await render();
    expect(text(w, 'shipped-gp-revenue-label')).toBe('发货收入（扣折扣与退货后净额）');
    expect(text(w, 'shipped-gp-revenue')).toBe('—');
    expect(text(w, 'shipped-gp-profit')).toBe('—');
    expect(text(w, 'shipped-gp-revenue-note')).toContain('缺少有效的本厂订单折扣资料');
  });

  it('NO_SHIPMENT: 三个数都是「—」并说明缺少发货确认数据, 不出现 0', async () => {
    mockGet.mockResolvedValue({ success: true, data: payload({
      status: 'NO_SHIPMENT', unavailableReason: '缺少发货确认数据：所选期间没有已确认发货（已发货 / 已签收）的发运单，毛利无法计算；未发货的订单金额不计入收入',
    }) });
    const w = await render();
    for (const id of ['shipped-gp-revenue', 'shipped-gp-cost', 'shipped-gp-profit']) {
      expect(text(w, id)).toBe('—');
    }
    expect(text(w, 'shipped-gp-revenue-note')).toContain('缺少发货确认数据');
    expect(text(w, 'shipped-gp-profit-note')).toContain('缺少发货确认数据');
    expect(w.text()).not.toMatch(/0\.00/);
  });

  it('COST_INCOMPLETE: 收入照数, 成本与毛利「—」并带原因', async () => {
    mockGet.mockResolvedValue({ success: true, data: payload({
      status: 'COST_INCOMPLETE', shipmentLineCount: 49, revenue: 450932.4,
      unavailableReason: '发货收入已算出，但出库批次成本不完整，毛利无法计算：出货批次扣库记录或单位不完整',
    }) });
    const w = await render();
    expect(text(w, 'shipped-gp-revenue')).toContain('450,932.40');
    expect(text(w, 'shipped-gp-revenue-note')).toBe('49 行已确认发货明细');
    expect(text(w, 'shipped-gp-cost')).toBe('—');
    expect(text(w, 'shipped-gp-profit')).toBe('—');
    expect(text(w, 'shipped-gp-profit-note')).toContain('出库批次成本不完整');
  });

  it('金额被权限脱敏: 「—」+「无权查看金额」, 不读成 0', async () => {
    mockGet.mockResolvedValue({ success: true, data: payload({ status: 'OK', shipmentLineCount: 3 }) });
    const w = await render();
    expect(text(w, 'shipped-gp-revenue')).toBe('—');
    expect(text(w, 'shipped-gp-profit')).toBe('—');
    expect(text(w, 'shipped-gp-revenue-note')).toContain('无权查看金额');
    expect(text(w, 'shipped-gp-profit-note')).toContain('无权查看金额');
    expect(w.text()).not.toMatch(/毛利率 0/);
  });

  it('接口 403: 说明需要财务查看权限, 不显示任何金额', async () => {
    mockGet.mockRejectedValueOnce(new ApiError('权限不足', 'FORBIDDEN', 403));
    const w = await render();
    expect(text(w, 'shipped-gp-error')).toContain('需要财务查看权限');
    expect(w.find('[data-testid="shipped-gp-revenue"]').exists()).toBe(false);
    expect(w.text()).not.toMatch(/\d+\.\d{2}/);
  });
});
