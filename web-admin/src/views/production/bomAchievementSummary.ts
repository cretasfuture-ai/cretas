/**
 * 「BOM达成率分析」的批次汇总 / 展开明细。
 *
 * 工艺保密 (独立审查 V2.1 M4): 保密产品的批次里, 报工自动带出 / BOM 自动扣料的物料实际量
 * 由后端置 null (actualQty 原本恒有值, null 只会是被隐藏)。⛔ 不能按 0 加进合计 ——
 * 那会把「看不到」显示成「少用了」, 达成率和偏差都是假的。含隐藏量的批次合计与达成率一律不给。
 */

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
  /** null: 工艺保密, 已隐藏 */
  actual: number | null;
  variance: number | null;
  unit?: string;
}

function plannedOf(item: SummaryItem): number {
  return Number(item.plannedQty ?? item.plannedQuantity ?? 0);
}

/** 字段在、值为 null = 后端按工艺保密隐藏了 (缺字段的旧形状仍按 0 处理)。 */
export function isSecretHidden(item: SummaryItem): boolean {
  if ('actualQty' in item) return item.actualQty === null;
  if ('actualQuantity' in item) return item.actualQuantity === null;
  return false;
}

function actualOf(item: SummaryItem): number | null {
  if (isSecretHidden(item)) return null;
  return Number(item.actualQty ?? item.actualQuantity ?? 0);
}

export function summarizeBatch(items: SummaryItem[]): BatchSummary {
  const planned = items.reduce((s, r) => s + plannedOf(r), 0);
  const secretHidden = items.some(isSecretHidden);
  if (secretHidden) {
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
  };
}
