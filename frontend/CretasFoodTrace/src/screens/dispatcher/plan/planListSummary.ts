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

// ==================== 分页加载 (2026-11-12: 原来只取第一页 100 条) ====================

export const PLAN_PAGE_SIZE = 100;

export interface FilterablePlan {
  id: string;
  status?: string;
  createdAt?: string;
  planNumber?: string;
  productTypeName?: string;
  planName?: string;
}

const STATUS_GROUPS: Record<string, string[]> = {
  PLANNED: ['PLANNED', 'PENDING'],
  IN_PROGRESS: ['IN_PROGRESS'],
  COMPLETED: ['COMPLETED'],
  CANCELLED: ['CANCELLED'],
};

/**
 * 本屏的筛选 (原样搬自 PlanListScreen.loadPlans, 只是改成对「已加载的全部页」做):
 * 去掉一天前的草稿 → 页签 (待执行 = PLANNED+PENDING) → 搜索 (计划名 / 产品名 / 计划号) → 草稿置顶, 其余按创建时间倒序。
 */
export function filterPlans<T extends FilterablePlan>(items: T[], tab: string, search: string, nowMs: number): T[] {
  const oneDayAgo = new Date(nowMs - 24 * 60 * 60 * 1000).toISOString();
  let out = items.filter((i) => (i.status || '').toUpperCase() !== 'DRAFT' || (i.createdAt || '') >= oneDayAgo);
  const targets = tab !== 'all' ? STATUS_GROUPS[tab] : undefined;
  if (targets) out = out.filter((i) => targets.includes((i.status || '').toUpperCase()));
  const q = search.trim().toLowerCase();
  if (q) {
    out = out.filter((i) =>
      (i.planName || '').toLowerCase().includes(q)
      || (i.productTypeName || '').toLowerCase().includes(q)
      || (i.planNumber || '').toLowerCase().includes(q));
  }
  return [...out].sort((a, b) => {
    const ad = (a.status || '').toUpperCase() === 'DRAFT' ? 0 : 1;
    const bd = (b.status || '').toUpperCase() === 'DRAFT' ? 0 : 1;
    if (ad !== bd) return ad - bd;
    return (b.createdAt || '').localeCompare(a.createdAt || '');
  });
}

/** 第 1 页替换, 之后的页追加; 翻页期间有新计划插到最前面时, 同一条可能在两页都出现 → 按 id 去重。 */
export function mergePlanPage<T extends { id: string }>(existing: T[], incoming: T[], page: number): T[] {
  if (page <= 1) return incoming;
  const seen = new Set(existing.map((p) => p.id));
  return [...existing, ...incoming.filter((p) => !seen.has(p.id))];
}

/**
 * 还有没加载的计划吗。serverTotal 是后端全部计划数 (未筛选); 读不到 total 时按「这一页满了就可能还有」判断。
 */
export function hasMorePlans(loadedCount: number, serverTotal: number | null, lastPageSize: number): boolean {
  // 空页 = 到底了 (去重后数量可能永远差几条, 不能只看 total, 否则会一直翻空页)
  if (lastPageSize === 0) return false;
  if (serverTotal != null) return loadedCount < serverTotal;
  return lastPageSize >= PLAN_PAGE_SIZE;
}

/** 没加载完时, 汇总与计数要写明只算了已加载的 (null = 已全部加载, 不用提示)。 */
export function coverageNote(loadedCount: number, serverTotal: number | null, more: boolean): string | null {
  if (!more) return null;
  return serverTotal != null
    ? `已加载 ${loadedCount} / 共 ${serverTotal} 条计划，汇总只算已加载的`
    : `已加载 ${loadedCount} 条计划，后面还有，汇总只算已加载的`;
}
