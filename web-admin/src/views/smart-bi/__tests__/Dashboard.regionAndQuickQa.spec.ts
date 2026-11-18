/**
 * LSM-COSTBI-DASH: 经营驾驶舱「区域销售分布」占比 + 快捷问答食品工厂问法。
 * 症状: 指标全「暂无数据」, 区域却显示 华北 2,727.435% 等 >100% 的占比。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { mount } from '@vue/test-utils';
import RegionSalesList from '../components/RegionSalesList.vue';
import { buildRegionShares } from '../components/regionShare';
import { manufacturingQuickQuestions } from '../components/dashboardQuickQuestions';

const ElEmpty = { props: ['description'], template: '<div class="el-empty">{{ description }}</div>' };
const mountList = (items: { name: string; value: number }[], hasData: boolean) =>
  mount(RegionSalesList, { props: { items, hasData }, global: { stubs: { 'el-empty': ElEmpty } } });

// 与线上症状同量级的金额(旧逻辑/脏数据下会出现千分位三位小数的百分比)
const REAL = [
  { name: '华北', value: 2727435 },
  { name: '华南', value: 2612233 },
  { name: '华东', value: 2493932 },
];

describe('区域销售分布', () => {
  it('无数据: 不渲染区域行与数字, 显示说明缺什么的空状态', () => {
    const w = mountList(REAL, false); // 指标无数据(hasData=false)而 rankings 里有区域
    expect(w.findAll('.region-item')).toHaveLength(0);
    expect(w.text()).not.toMatch(/华北|华南|华东/);
    expect(w.text()).not.toContain('%');
    expect(w.find('.el-empty').text()).toContain('区域');
    expect(w.find('.el-empty').text()).toContain('销售额');
  });

  it('空列表也是空状态', () => {
    const w = mountList([], true);
    expect(w.findAll('.region-item')).toHaveLength(0);
    expect(w.find('.el-empty').exists()).toBe(true);
  });

  it('含负数的脏数据(会把合计压小、占比冲到几千%)按无数据处理', () => {
    const w = mountList([{ name: '华北', value: 2727.435 }, { name: '华南', value: -2700 }, { name: '华东', value: 100 }], true);
    expect(w.findAll('.region-item')).toHaveLength(0);
    expect(w.text()).not.toMatch(/\d{3,}\.\d+%/);
  });

  it('有数据: 每项 ≤100%, 合计约 100%, 无千分位三位小数百分比', () => {
    const rows = buildRegionShares(REAL);
    const sum = rows.reduce((a, r) => a + r.percentage, 0);
    expect(Math.abs(sum - 100)).toBeLessThanOrEqual(0.2);
    for (const r of rows) expect(r.percentage).toBeLessThanOrEqual(100);

    const w = mountList(REAL, true);
    const pcts = w.findAll('.percent').map((e) => e.text());
    expect(pcts).toHaveLength(3);
    for (const t of pcts) {
      expect(t).toMatch(/^\d{1,2}(\.\d)?%$/);
      expect(parseFloat(t)).toBeLessThanOrEqual(100);
    }
  });

  it('阴性对照: 正常数据照常显示区域名与占比(单区域=100%)', () => {
    const w = mountList(REAL, true);
    expect(w.text()).toContain('华北');
    expect(w.text()).toContain('华东');
    expect(w.find('.el-empty').exists()).toBe(false);
    expect(mountList([{ name: '华南', value: 50 }], true).find('.percent').text()).toBe('100.0%');
  });

  it('接线: Dashboard.vue 真正使用 RegionSalesList, 且不再自己内联算占比', () => {
    const src = readFileSync(resolve(__dirname, '../Dashboard.vue'), 'utf-8');
    expect(src).toMatch(/<RegionSalesList[^>]*:has-data="hasData"/);
    expect(src).not.toContain('item.percentage');
    expect(src).not.toMatch(/Math\.round\(\(item\.value \/ total\) \* 100\)/);
  });
});

describe('快捷问答', () => {
  const texts = manufacturingQuickQuestions.map((q) => q.text);
  it('是食品工厂问法, 不再有餐饮/部门业绩问法', () => {
    expect(texts).toEqual([
      '本月产量和出成率如何?',
      '哪个产品毛利最高?',
      '原料成本有没有上涨?',
      '哪个客户本月采购最多?',
    ]);
    for (const t of texts) expect(t).not.toMatch(/部门|业绩|销售额/);
  });
  it('接线: Dashboard 用的就是这份列表, 点击仍走 goToAIQuery', () => {
    const src = readFileSync(resolve(__dirname, '../Dashboard.vue'), 'utf-8');
    expect(src).toContain("import { manufacturingQuickQuestions } from './components/dashboardQuickQuestions'");
    expect(src).not.toContain('哪个部门业绩最好');
    expect(src).toContain('@click="goToAIQuery(q.text)"');
  });
});
