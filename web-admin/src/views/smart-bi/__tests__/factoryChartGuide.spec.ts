import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import ElementPlus from 'element-plus';
import { describe, it, expect } from 'vitest';
import type { FactoryRecommendation } from '@/api/smartbi/factory-charts';
import FactoryChartGuide from '../FactoryChartGuide.vue';
import { analysisFamilies, chartVariantLabel, recommendedAnalysisIds, replaceFamilySelection } from '../factoryChartGuide';

function item(id: string, family = 'stock', unit = 'kg', kind = 'bar', domain = '库存'): FactoryRecommendation {
  return { id, title: `${family} ${unit}`, metric: family, domain, priority: 'required', reason: '实测数据', source: 'business', unit,
    analysis: { familyId: family, familyLabel: family },
    chart: { chartType: kind, echartsOption: {} }, kpis: [{ label: '真实值', value: 67, unit }],
    findings: { good: [], attention: [], suggestions: [] }, tableData: [] };
}
const props = (items = [item('kg'), item('box', 'stock', 'box'), item('cost', 'cost', '元', 'line', '成本')]) => ({ items, selectedIds: ['kg'], subjects: Array.from({ length: 100 }, (_, i) => ({ id: `product-${i}`, label: `产品${i}` })), subject: null, loading: false });

describe('factory analysis families', () => {
  it('groups variants and units but retains distinct server facts and IDs', () => {
    const items = [item('kg'), item('box', 'stock', 'box'), item('area', 'stock', 'kg', 'area')];
    const before = JSON.stringify(items);
    expect(analysisFamilies(items)).toHaveLength(1);
    expect(analysisFamilies(items)[0].items).toHaveLength(3);
    expect(replaceFamilySelection(['kg', 'other'], analysisFamilies(items)[0], 'box')).toEqual(['other', 'box']);
    expect(JSON.stringify(items)).toBe(before);
  });

  it('labels ratio source units while leaving financial percentages unqualified', () => {
    expect(chartVariantLabel(item('ratio', 'ratio', '%', 'gauge'))).toBe('仪表 · %');
    expect(chartVariantLabel({ ...item('ratio-kg', 'ratio', '%', 'gauge'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: 'kg' } })).toBe('仪表 · % · 口径：kg');
    expect(chartVariantLabel({ ...item('ratio-box', 'ratio', '%', 'gauge'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: 'box' } })).toBe('仪表 · % · 口径：box');
    expect(chartVariantLabel({ ...item('ratio-盒', 'ratio', '%', 'gauge'), analysis: { familyId: 'ratio', familyLabel: '比率', sourceQuantityUnit: '盒' } })).toBe('仪表 · % · 口径：盒');
  });
  it('recommends at most four distinct families in server order within the question scope', () => {
    const items = Array.from({ length: 15 }, (_, i) => item(String(i), `metric${Math.floor(i / 2)}`));
    expect(recommendedAnalysisIds(items, 'all')).toEqual(['0', '2', '4', '6']);
    expect(recommendedAnalysisIds(items, '成本')).toEqual([]);
    expect(recommendedAnalysisIds(items, '库存', '库存:metric4')).toEqual(['8']);
  });
});

describe('mounted factory analysis guide', () => {
  it('starts with business questions and emits a small local recommendation set', async () => {
    const page = mount(FactoryChartGuide, { props: props(), global: { plugins: [ElementPlus] } });
    expect(page.text()).toContain('1. 想了解什么？');
    expect(page.findAll('[data-testid="factory-chart-family"]')).toHaveLength(0);
    expect(page.text()).not.toContain('产品99');
    await page.get('[data-testid="factory-chart-guide-generate"]').trigger('click');
    expect(page.emitted('analysis')?.[0]).toEqual([['kg', 'cost']]);
    expect(page.emitted('selection')).toBeUndefined();
    await page.get('[data-testid="factory-chart-guide-domain"] input[value="成本"]').setValue(true);
    await page.get('[data-testid="factory-chart-guide-generate"]').trigger('click');
    expect(page.emitted('analysis')?.at(-1)).toEqual([['cost']]);
    page.unmount();
  });
  it('shows one catalog row per family and supports unit/variant selection without adding product menus', async () => {
    const page = mount(FactoryChartGuide, { props: props(), global: { plugins: [ElementPlus] } });
    await page.get('[data-testid="factory-chart-mode"] input[value="browse"]').setValue(true);
    expect(page.findAll('[data-testid="factory-chart-family"]')).toHaveLength(2);
    const row = page.findAll('[data-testid="factory-chart-family"]')[0];
    row.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'box');
    await nextTick();
    expect(page.emitted('preview')?.at(-1)).toEqual([['box']]);
    await page.get('[aria-label="搜索分析主题"]').setValue('cost');
    expect(page.findAll('[data-testid="factory-chart-family"]')).toHaveLength(1);
    expect(page.find('[data-testid="factory-chart-family"]').text()).toContain('cost');
    page.unmount();
  });
  it('retains the selected opaque product and emits actual product changes', async () => {
    const page = mount(FactoryChartGuide, { props: { ...props(), subject: 'gone-product' }, global: { plugins: [ElementPlus] } });
    const select = page.findComponent({ name: 'ElSelectV2' });
    expect(select.props('placeholder')).toBe('全部产品 / 全厂范围');
    expect(select.props('modelValue')).toBe('gone-product');
    select.vm.$emit('update:modelValue', 'product-99');
    await nextTick();
    expect(page.emitted('subject')).toEqual([['product-99']]);
    page.unmount();
  });
});
