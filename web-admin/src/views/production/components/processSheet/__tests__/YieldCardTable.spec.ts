import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus, { ElTooltip } from 'element-plus';
import YieldCardTable from '../YieldCardTable.vue';

const getInventoryYieldCard = vi.fn();

vi.mock('@/api/processSheet', () => ({
  getInventoryYieldCard: (...args: unknown[]) => getInventoryYieldCard(...args),
}));

describe('YieldCardTable', () => {
  beforeEach(() => {
    getInventoryYieldCard.mockReset();
  });

  it('renders upstream provenance values used to explain cumulative yield', async () => {
    getInventoryYieldCard.mockResolvedValue({
      data: [
        {
          batchNumber: 'CLK-W-BLANCH',
          processDate: '2026-07-01',
          produced: 604.5,
          used: 0,
          remaining: 604.5,
          status: 'ACTIVE',
          unit: 'kg',
          rowTotalCost: 15112.5,
          unitPrice: 25,
          inputQuantity: 765.19,
          sourceBatchNumber: 'CLK-W-ROLL',
          feedQuantity: 765.19,
          sourceProducedQuantity: 1571.19,
          sourceConsumedRatio: 48.7013,
          inheritedRawEquivalentQuantity: 701.2988,
          inheritedCost: 15303.8,
          addedCost: -191.3,
          stepYieldRate: 79,
          cumulativeYieldRate: 86.1972,
        },
        {
          batchNumber: 'CLK-W-MIX',
          produced: 80,
          used: 0,
          remaining: 80,
          status: 'ACTIVE',
          unit: 'kg',
          rowTotalCost: 2000,
          unitPrice: 25,
          inputQuantity: 90,
          sourceBatchNumber: 'CLK-W-A, CLK-W-B',
          feedQuantity: 90,
          sourceProducedQuantity: 135,
          sourceConsumedRatio: 66.6667,
          inheritedRawEquivalentQuantity: 100,
          inheritedCost: 1350,
          addedCost: 650,
          stepYieldRate: 88.8889,
          cumulativeYieldRate: 80,
        },
      ],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('2026-07-01');
    expect(text).toContain('CLK-W-ROLL');
    expect(text).toContain('765.19');
    expect(text).toContain('48.70%');
    expect(text).toContain('701.30');
    expect(text).toContain('86.20%');
    expect(text).toContain('CLK-W-A, CLK-W-B');
    expect(text).toContain('66.67%');
    expect(text).toContain('100.00');
    expect(text).toContain('80.00%');
  });

  it('explains missing yield and cost values instead of leaving only dashes', async () => {
    getInventoryYieldCard.mockResolvedValue({
      data: [
        {
          batchNumber: 'CLK-W-MISSING',
          processName: 'mix',
          produced: 25,
          used: 0,
          remaining: 25,
          status: 'ACTIVE',
          unit: 'kg',
          inputQuantity: 30,
          rowTotalCost: null,
          unitPrice: null,
          sourceBatchNumber: null,
          feedQuantity: null,
          sourceConsumedRatio: null,
          inheritedRawEquivalentQuantity: null,
          stepYieldRate: 83.3333,
          cumulativeYieldRate: null,
        },
      ],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('有 1 行累计出成率未显示');
    expect(text).toContain('检查来源批次、原料投入或 SKU 标准克重配置');
    expect(text).toContain('有 1 行成本未显示');
  });

  it('shows legacy gram snapshots as kg and exposes the horizontal-scroll hint', async () => {
    getInventoryYieldCard.mockResolvedValue({
      data: [{
        batchNumber: 'CLK-W-LEGACY-G', produced: 100_000, used: 50_000, remaining: 50_000,
        status: 'ACTIVE', unit: 'g', unitPrice: 0.1, rowTotalCost: 10_000,
        stepYieldRate: 100, cumulativeYieldRate: 100,
      }],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('100 kg');
    expect(text).toContain('50 kg');
    expect(text).not.toContain('100000 g');
    expect(text).toContain('表格可左右滑动查看完整字段');
    expect(text).toContain('点击表头可升/降序');

    const sortButtons = wrapper.findAll('button.yield-sort-trigger');
    expect(sortButtons.length).toBeGreaterThan(10);
    // 缺陷 F: 文案不再暗示"点这段文字能排序" —— 真正的排序入口是 el-table 自带的箭头图标。
    expect(sortButtons.some((button) => button.attributes('aria-label')?.includes('排序请点击右侧箭头图标'))).toBe(true);
    expect(sortButtons.every((button) => button.attributes('type') === 'button')).toBe(true);
  });

  it('缺陷 F: 点表头文字不排序，点表头右侧的箭头图标才排序', async () => {
    // 「产出」故意按降序喂 API (先高后低)；点文字不该动它，点排序箭头才该把它翻成升序。
    getInventoryYieldCard.mockResolvedValue({
      data: [
        { batchNumber: 'ZZZ-HIGH-BATCH', produced: 90, used: 0, remaining: 90, status: 'ACTIVE', unit: 'kg' },
        { batchNumber: 'ZZZ-LOW-BATCH', produced: 10, used: 0, remaining: 10, status: 'ACTIVE', unit: 'kg' },
      ],
    });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await flushPromises();
    await nextTick();

    const firstRowText = () => wrapper.find('.el-table__body tbody tr').text();
    expect(firstRowText()).toContain('ZZZ-HIGH-BATCH'); // 未排序前：API 顺序原样显示

    // 定位「产出」这一列的表头 —— 这段文字按钮与 el-table 自带的排序箭头同在一个 <th> 里。
    const producedHeader = wrapper.findAll('th').find((th) =>
      th.find('button.yield-sort-trigger').exists()
      && th.find('button.yield-sort-trigger').text() === '产出');
    expect(producedHeader, '「产出」列表头应该存在').toBeTruthy();

    // ① 点表头文字：不该触发排序。
    await producedHeader!.find('button.yield-sort-trigger').trigger('click');
    await nextTick();
    expect(firstRowText(), '点表头文字不该排序').toContain('ZZZ-HIGH-BATCH');

    // ② 点右侧真正的排序箭头（升序半只）：这才该触发排序。
    const ascendingCaret = producedHeader!.find('.caret-wrapper .sort-caret.ascending');
    expect(ascendingCaret.exists(), '应能找到「产出」列的升序排序箭头').toBe(true);
    await ascendingCaret.trigger('click');
    await nextTick();
    expect(firstRowText(), '点排序箭头应该把「产出」按升序排列').toContain('ZZZ-LOW-BATCH');
  });

  it('缺陷 D: 「分摊成本」「单价」表头有口径说明，讲清客供料成本为 0 是设计如此', async () => {
    getInventoryYieldCard.mockResolvedValue({ data: [] });
    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await flushPromises();
    await nextTick();

    const tooltipContents = wrapper.findAllComponents(ElTooltip)
      .map((tooltip) => tooltip.props('content'))
      .filter((content): content is string => typeof content === 'string');
    const costHelp = tooltipContents.find((c) => c.includes('本行归集到的实际成本 = 继承自上游批次的成本'));
    const priceHelp = tooltipContents.find((c) => c.includes('单价 = 分摊成本 ÷ 本行产出数量'));

    expect(costHelp, '「分摊成本」表头应该有口径说明 tooltip').toBeTruthy();
    expect(priceHelp, '「单价」表头应该有口径说明 tooltip').toBeTruthy();
    // 两条说明都要点名: 客供料的原料成本按口径为 0, 不是数据缺失。
    expect(costHelp).toContain('客供料');
    expect(costHelp).toContain('设计如此');
    expect(priceHelp).toContain('客供料');
  });

  it('表头是通栏的，不给每一格套自己的外壳', async () => {
    getInventoryYieldCard.mockResolvedValue({ data: [] });
    mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
    });
    await flushPromises();

    // 客户原话: 每个表头套了独立边框盒子。那个盒子感来自三层叠加 ——
    // 加深的表头底色、逐格右边框、以及每格一个带 ↕ 的按钮。三层都不要再回来。
    const source = readFileSync(resolve(__dirname, '../YieldCardTable.vue'), 'utf8');
    expect(source).not.toContain('--el-table-header-bg-color');
    expect(source).toContain('border-right: 0');
    expect(source).toContain('background: transparent');
    expect(source).not.toContain('yield-sort-trigger__hint');
    // 但按钮本身要留着 —— 去掉它就等于把触屏/键盘的排序入口一起去掉了
    expect(source).toContain(`class: 'yield-sort-trigger'`);
    expect(source).toContain('appearance: none');
    expect(source).toContain('box-shadow: none');
  });

  it('explains a genuinely empty plan-wide yield card', async () => {
    getInventoryYieldCard.mockResolvedValue({ data: [] });

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('\u6682\u65e0\u534a\u6210\u54c1\u5e93\u5b58');
    expect(text).toContain('\u4fdd\u5b58\u4efb\u4e00\u5de5\u5e8f\u6709\u6548\u4ea7\u51fa\u540e\u4f1a\u751f\u6210\u5168\u5de5\u5e8f\u51fa\u6210\u7387\u6c47\u603b');
  });

  it('shows a load failure message instead of silently rendering empty data', async () => {
    getInventoryYieldCard.mockRejectedValue(new Error('network down'));

    const wrapper = mount(YieldCardTable, {
      props: { factoryId: 'F006', planId: 'PLAN-001' },
      global: {
        plugins: [ElementPlus],
        stubs: {
          teleport: true,
          transition: false,
        },
      },
    });

    await flushPromises();
    await nextTick();

    const text = wrapper.text();
    expect(text).toContain('出成率数据加载失败');
    expect(text).toContain('请刷新重试');
  });
});
