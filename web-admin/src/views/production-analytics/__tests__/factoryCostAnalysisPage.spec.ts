/**
 * 成本分析页源码守卫（LSM-COSTBI-B）：这几条都是「页面看着正常、口径不对」的缺陷，
 * 纯函数测试守不到页面有没有真的接上，所以直接读页面源码断言接线。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const page = readFileSync(resolve(__dirname, '../FactoryCostAnalysis.vue'), 'utf-8');

describe('FactoryCostAnalysis 页面接线', () => {
  it('明细表有设备、其他两列，材料+人工+设备+其他能加出总成本', () => {
    for (const label of ['材料成本(¥)', '人工成本(¥)', '设备成本(¥)', '其他成本(¥)', '总成本(¥)']) {
      expect(page).toContain(`label="${label}"`);
    }
    expect(page).toContain('row.totalEquipmentCost');
    expect(page).toContain('row.totalOtherCost');
  });

  it('产品对比画单位成本，不画总成本', () => {
    expect(page).toContain('(canShow ? productsByUnitCost(all) : all).slice(0, 10)');
    expect(page).toContain('canShow ? p.costPerUnit : p.avgYieldRate');
    expect(page).not.toContain('canShow ? p.totalCost : p.avgYieldRate');
  });

  it('没有写死的「基准 80%」', () => {
    expect(page).not.toMatch(/yAxis:\s*80/);
    expect(page).not.toContain('基准 80%');
    expect(page).not.toContain('markLine');
  });

  it('四张图都挂一图一分析，且只用现算结论（不走 Tier2 模板）', () => {
    // 句子的「禁止模板话、要求现算」由 costInsightCharts.spec.ts 按真实形状的数据断言；这里守接线。
    const providers = page.match(/<ChartInsightProvider[\s\S]*?\/>/g) ?? [];
    expect(providers.map(p => p.match(/:chart="(\w+)"/)?.[1]).sort()).toEqual(
      ['costPieInsight', 'costTrendInsight', 'productInsight', 'yieldInsight'],
    );
    for (const p of providers) {
      expect(p).toContain(':auto-tier2="false"');
      expect(p).toContain('depth="detailed"');
      expect(p).toContain(':perms="insightPerms"');
    }
    expect(page).toContain('canViewFinance: canViewPrice.value');
    expect(page).not.toContain('useChartInsight');
  });

  it('产品条形图可切换排列方式，明细表每一列都能点表头排序', () => {
    expect(page).toContain('<ChartSortControl v-if="hasProducts" v-model="productSortMode" />');
    expect(page).toContain('watch(productSortMode, () => renderProductBar())');
    const columns = page.match(/<el-table-column[^>]*>/g) ?? [];
    expect(columns.length).toBe(11);
    for (const c of columns) {
      expect(c).toContain('sortable');
      expect(c).toContain(':sort-method="tableSortBy(');
    }
  });

  it('两张成本图共用一套类别颜色', () => {
    expect(page).toContain('COST_CATEGORY_COLORS[e.category]');
    expect(page).toContain('COST_CATEGORY_COLORS.材料');
    expect(page).not.toMatch(/'#5470c6' \}/);
  });

  it('手机宽度：图例下置、明细表改卡片、平均出成率按有出成率的批次说明', () => {
    expect(page).toContain("orient: 'horizontal', left: 'center', bottom: 0");
    expect(page).toContain('v-if="isNarrow" class="fca-product-cards"');
    expect(page).toContain('yieldBasisText(s.yieldBatchCount, s.batchCount)');
  });
});
