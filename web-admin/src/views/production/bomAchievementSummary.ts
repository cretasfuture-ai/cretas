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
