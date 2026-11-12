/**
 * 调度员「生产计划」列表底部汇总 —— 按【屏上这份列表】算 (2026-11-12 验收: 搜索/筛选后汇总仍是全部 156 条)。
 *
 * 为什么不再用后端 list-summary:
 *   这一屏的筛选全在前端做 (状态页签「待执行」= PLANNED + PENDING、去掉一天前的草稿、搜索还匹配计划名),
 *   后端汇总的 status 只能是单个值、搜索不看计划名、也不去旧草稿 ⇒ 怎么传参都和屏上对不齐。
 *   底部汇总要回答的是「我现在看到的这些计划合计多少」, 所以直接按屏上的列表算。
 *
 * 口径与后端 ListSummaryServiceImpl#computeProductionPlanSummary 一致: 共 N 条; 按标准单位分组
 * (盒/箱/公斤… 不跨单位相加), 每组 计划数量 / 完成数量 / 完成率 (一位小数); 多个单位时标签带单位。
 */
import type { SummaryStat } from '../../../types/listSummary';
import { canonicalUnit, displayUnit } from '../../../utils/packagingUnitConversion';

export interface SummarizablePlan {
  plannedQuantity?: number | null;
  actualQuantity?: number | null;
  plannedUnit?: string | null;
  productUnit?: string | null;
}

function num(v: number | null | undefined): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

/** 避免 0.1 + 0.2 这类浮点尾巴进到屏上。 */
function tidy(n: number): number {
  return Math.round(n * 1000) / 1000;
}

export function summarizePlans(plans: SummarizablePlan[]): SummaryStat[] {
  const groups = new Map<string, { label: string; planned: number; actual: number }>();
  for (const p of plans) {
    const raw = p.plannedUnit ?? p.productUnit ?? '';
    const key = canonicalUnit(raw) || 'unknown';
    const g = groups.get(key) ?? { label: displayUnit(raw) || '未登记单位', planned: 0, actual: 0 };
    g.planned += num(p.plannedQuantity);
    g.actual += num(p.actualQuantity);
    groups.set(key, g);
  }
  const stats: SummaryStat[] = [{ label: '共', value: plans.length, format: 'number', unit: '条' }];
  const multiple = groups.size > 1;
  [...groups.keys()].sort().forEach((key) => {
    const g = groups.get(key)!;
    const suffix = multiple ? `（${g.label}）` : '';
    const rate = g.planned > 0 ? Math.round((g.actual * 1000) / g.planned) / 10 : 0;
    stats.push({ label: `计划数量${suffix}`, value: tidy(g.planned), format: 'number', unit: g.label });
    stats.push({ label: `完成数量${suffix}`, value: tidy(g.actual), format: 'number', unit: g.label });
    stats.push({ label: `完成率${suffix}`, value: rate, format: 'percent', unit: '%' });
  });
  return stats;
}
