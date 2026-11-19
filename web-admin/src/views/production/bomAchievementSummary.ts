/**
 * 「BOM达成率分析」的批次汇总 / 展开明细。
 *
 * 工艺保密 (独立审查 V2.1 M4): 保密产品的批次里, 报工自动带出 / BOM 自动扣料的物料实际量
 * 由后端置 null 并带 `processSecret` 标记。⛔ 不能按 0 加进合计 ——
 * 那会把「看不到」显示成「少用了」, 达成率和偏差都是假的。含隐藏量的批次合计与达成率一律不给。
 *
 * 「已保密」只看标记 (工厂级保密配置 2026-11-03: 工艺保密可以按工厂关掉, 值为 null 不再等于被隐藏);
 * 没有标记的 null 是「没有数」, 显示「—」, 同样不按 0 加。
 */

import { isProcessSecretHidden } from '@/utils/processSecretField';

export interface SummaryItem {
  [key: string]: unknown;
}

export interface BatchSummary {
  plannedTotal: number;
  /** null: 含保密隐藏的物料, 合计算不出 */
  actualTotal: number | null;
  achievementRate: number | undefined;
  secretHidden: boolean;
}

export interface MaterialDetail {
  materialName: string;
  planned: number;
  /** null: 没有数 (secretHidden 时是被工艺保密隐藏) */
  actual: number | null;
  /** 后端带了工艺保密标记、隐藏了实际量 */
  secretHidden: boolean;
  variance: number | null;
  unit?: string;
}

function plannedOf(item: SummaryItem): number {
  return Number(item.plannedQty ?? item.plannedQuantity ?? 0);
}

/**
 * 后端按工艺保密隐藏了实际量: 值是 null **且**有标记 (processSecret.hiddenFields 里有它; 旧的整块标记没有
 * hiddenFields 时视为全部)。只有 null 没标记 = 没有数; 有标记但值还在 = 不是这个字段被隐藏。
 */
export function isSecretHidden(item: SummaryItem): boolean {
  return actualMissing(item)
    && (isProcessSecretHidden(item, 'actualQty') || isProcessSecretHidden(item, 'actualQuantity'));
}

/** 字段在、值为 null: 没有数 (缺字段的旧形状仍按 0 处理)。 */
function actualMissing(item: SummaryItem): boolean {
  if ('actualQty' in item) return item.actualQty === null;
  if ('actualQuantity' in item) return item.actualQuantity === null;
  return false;
}

function actualOf(item: SummaryItem): number | null {
  if (isSecretHidden(item) || actualMissing(item)) return null;
  return Number(item.actualQty ?? item.actualQuantity ?? 0);
}

export function summarizeBatch(items: SummaryItem[]): BatchSummary {
  const planned = items.reduce((s, r) => s + plannedOf(r), 0);
  const secretHidden = items.some(isSecretHidden);
  if (secretHidden || items.some((item) => actualOf(item) === null)) {
    return { plannedTotal: Math.round(planned * 100) / 100, actualTotal: null, achievementRate: undefined, secretHidden };
  }
  const actual = items.reduce((s, r) => s + (actualOf(r) ?? 0), 0);
  const computedRate = planned > 0 ? Math.round((actual / planned) * 1000) / 10 : null;
  return {
    plannedTotal: Math.round(planned * 100) / 100,
    actualTotal: Math.round(actual * 100) / 100,
    achievementRate: computedRate ?? undefined,
    secretHidden,
  };
}

export function toMaterialDetail(item: SummaryItem): MaterialDetail {
  const planned = plannedOf(item);
  const actual = actualOf(item);
  return {
    materialName: (item.materialName as string) || (item.materialTypeName as string) || '-',
    planned,
    actual,
    variance: actual === null ? null : Math.round((actual - planned) * 100) / 100,
    unit: item.unit as string | undefined,
    secretHidden: isSecretHidden(item),
  };
}

/** 一次最多读多少个批次来算 KPI; 超过时页面如实写「仅基于最近 N 个」, 不假装是全部。 */
export const KPI_BATCH_CAP = 1000;
const PAGE_SIZE = 100;

export interface AllPagesResult<T> {
  items: T[];
  /** 服务端说一共有多少条 (可能大于 items.length, 即被 cap 截断) */
  totalElements: number;
  truncated: boolean;
}

/**
 * 把分页接口读全 (B6: KPI 必须基于全部批次, 而不是当前页)。
 * ⛔ 读不全也不假装全: 达到 cap 仍有剩余 ⇒ truncated=true, 由调用方在口径里写出来。
 */
export async function fetchAllPages<T>(
  fetchPage: (page: number, size: number) => Promise<{ content: T[]; totalElements: number }>,
  cap: number = KPI_BATCH_CAP,
): Promise<AllPagesResult<T>> {
  const items: T[] = [];
  let totalElements = 0;
  for (let page = 1; items.length < cap; page++) {
    const res = await fetchPage(page, PAGE_SIZE);
    totalElements = res.totalElements;
    items.push(...res.content);
    if (res.content.length === 0 || items.length >= totalElements) break;
  }
  const capped = items.length > cap ? items.slice(0, cap) : items;
  return { items: capped, totalElements, truncated: capped.length < totalElements };
}

/** 限并发 map, 保持输入顺序。 */
export async function mapWithConcurrency<T, R>(
  items: T[], limit: number, fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]);
    }
  });
  await Promise.all(workers);
  return results;
}
