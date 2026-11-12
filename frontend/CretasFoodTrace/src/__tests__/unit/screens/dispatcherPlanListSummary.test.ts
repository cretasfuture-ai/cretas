/**
 * 调度员「生产计划」列表底部汇总 (2026-11-12 验收: 搜索 / 筛选后底部仍是全部 156 条和全量合计)。
 * 现在按屏上这份 (已按页签和搜索过滤的) 列表算, 口径同后端 computeProductionPlanSummary。
 */
import fs from 'fs';
import path from 'path';
import { summarizePlans } from '../../../screens/dispatcher/plan/planListSummary';

describe('summarizePlans', () => {
  it('单一单位: 共 N 条 + 计划 / 完成 / 完成率, 标签不带单位', () => {
    const stats = summarizePlans([
      { plannedQuantity: 100, actualQuantity: 40, plannedUnit: 'kg' },
      { plannedQuantity: 50, actualQuantity: 10, plannedUnit: '公斤' },
    ]);
    expect(stats).toEqual([
      { label: '共', value: 2, format: 'number', unit: '条' },
      { label: '计划数量', value: 150, format: 'number', unit: 'kg' },
      { label: '完成数量', value: 50, format: 'number', unit: 'kg' },
      { label: '完成率', value: 33.3, format: 'percent', unit: '%' },
    ]);
  });

  it('多个单位: 分组, 不把 kg 和盒加在一起, 标签带单位', () => {
    const stats = summarizePlans([
      { plannedQuantity: 10, actualQuantity: 5, plannedUnit: 'kg' },
      { plannedQuantity: 20, actualQuantity: 20, plannedUnit: '盒' },
    ]);
    const labels = stats.map((s) => s.label);
    expect(labels).toContain('计划数量（kg）');
    expect(labels).toContain('计划数量（盒）');
    expect(stats.find((s) => s.label === '计划数量（盒）')?.value).toBe(20);
    expect(stats.find((s) => s.label === '完成率（盒）')?.value).toBe(100);
  });

  it('筛选后为空: 共 0 条, 不出现假合计', () => {
    expect(summarizePlans([])).toEqual([{ label: '共', value: 0, format: 'number', unit: '条' }]);
  });

  it('计划量为 0 时完成率记 0, 不除零; 没有单位写「未登记单位」', () => {
    const stats = summarizePlans([{ plannedQuantity: 0, actualQuantity: 3, plannedUnit: null }]);
    expect(stats.find((s) => s.label === '完成率')?.value).toBe(0);
    expect(stats.find((s) => s.label === '计划数量')?.unit).toBe('未登记单位');
  });
});

describe('PlanListScreen 底部汇总跟着屏上的列表走', () => {
  const src = fs.readFileSync(path.resolve(__dirname, '../../../screens/dispatcher/plan/PlanListScreen.tsx'), 'utf8');
  it('汇总由筛选后的 plans 算出, 不再请求与页签 / 搜索对不齐的后端汇总', () => {
    expect(src).toContain('summarizePlans(plans)');
    expect(src).not.toContain("useListSummary('productionPlan'");
    // plans 就是经过页签 + 搜索过滤后放进 FlatList 的那份
    expect(src).toContain('data={plans}');
    expect(src).toMatch(/if \(search\.trim\(\)\) \{[\s\S]{0,200}items = items\.filter/);
  });
});
