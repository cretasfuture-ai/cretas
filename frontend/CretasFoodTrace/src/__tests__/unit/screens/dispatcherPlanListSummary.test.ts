/**
 * 调度员「生产计划」列表底部汇总 (2026-11-12 验收: 搜索 / 筛选后底部仍是全部 156 条和全量合计)。
 * 现在按屏上这份 (已按页签和搜索过滤的) 列表算, 口径同后端 computeProductionPlanSummary。
 */
import fs from 'fs';
import path from 'path';
import {
  coverageNote, filterPlans, hasMorePlans, mergePlanPage, summarizePlans,
} from '../../../screens/dispatcher/plan/planListSummary';

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
    expect(src).toContain('filterPlans(loadedPlans, activeTab, search');
  });
});

describe('分页 (原来只取第一页 100 条)', () => {
  it('第 1 页替换, 之后追加, 按 id 去重', () => {
    const a = [{ id: '1' }, { id: '2' }];
    expect(mergePlanPage(a, [{ id: '9' }], 1)).toEqual([{ id: '9' }]);
    expect(mergePlanPage(a, [{ id: '2' }, { id: '3' }], 2)).toEqual([{ id: '1' }, { id: '2' }, { id: '3' }]);
  });
  it('还有没有: 看后端 total; 空页即到底; 没有 total 时满页才算可能还有', () => {
    expect(hasMorePlans(100, 150, 100)).toBe(true);
    expect(hasMorePlans(150, 150, 50)).toBe(false);
    expect(hasMorePlans(148, 150, 0)).toBe(false);
    expect(hasMorePlans(100, null, 100)).toBe(true);
    expect(hasMorePlans(40, null, 40)).toBe(false);
  });
  it('没加载完才写「已加载 N / 共 M」', () => {
    expect(coverageNote(100, 156, true)).toBe('已加载 100 / 共 156 条计划，汇总只算已加载的');
    expect(coverageNote(156, 156, false)).toBeNull();
  });
  it('筛选: 待执行 = PLANNED + PENDING; 一天前的草稿去掉; 搜索含计划名', () => {
    const now = Date.UTC(2026, 10, 12, 12);
    const rows = [
      { id: 'a', status: 'PLANNED', createdAt: '2026-11-12T01:00:00Z' },
      { id: 'b', status: 'PENDING', createdAt: '2026-11-12T02:00:00Z', planName: '猪蹄专线' },
      { id: 'c', status: 'IN_PROGRESS', createdAt: '2026-11-12T03:00:00Z' },
      { id: 'd', status: 'DRAFT', createdAt: '2026-11-10T00:00:00Z' },
    ];
    expect(filterPlans(rows, 'PLANNED', '', now).map((r) => r.id)).toEqual(['b', 'a']);
    expect(filterPlans(rows, 'all', '', now).map((r) => r.id)).not.toContain('d');
    expect(filterPlans(rows, 'all', '猪蹄', now).map((r) => r.id)).toEqual(['b']);
  });
});
