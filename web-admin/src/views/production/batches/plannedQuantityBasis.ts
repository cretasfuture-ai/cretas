/**
 * 批次「计划数量」这一列到底是什么数 (PROTO-YDWM-B4B8 · B4/B5)。
 *
 * 味美 10 月导入的批次, 原表只排「日期-产线-产品-面仓-批号」, 没有计划数量; 导入时把产能分析表的
 * 理论产能 (theoryKg) 写进了 planned_quantity。页面照旧叫它「计划数量」, 客户会当成计划量。
 *
 * 判据只看批次自己带的证据: custom_fields.theoryKg 是数, 且与 plannedQuantity 相等
 * ⇒ 这个数就是理论产能。拿不出这个证据的批次 (正常建的计划/批次) 仍叫「计划数量」, 不改口径。
 * 无计划数量 (null / 0) 的批次, 列里留空, 不显示 0。
 */

export type PlannedQuantityBasis = 'PLAN' | 'THEORETICAL_CAPACITY';

interface BatchLike {
  plannedQuantity?: unknown;
  plannedUnit?: unknown;
  unit?: unknown;
  actualQuantity?: unknown;
  efficiency?: unknown;
  customFields?: unknown;
}

function num(value: unknown): number | null {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function plannedBasisOf(batch: BatchLike | null | undefined): PlannedQuantityBasis {
  if (!batch) return 'PLAN';
  const planned = num(batch.plannedQuantity);
  const fields = batch.customFields;
  if (planned == null || planned <= 0 || fields == null || typeof fields !== 'object') return 'PLAN';
  const theory = num((fields as Record<string, unknown>).theoryKg);
  if (theory != null && Math.abs(theory - planned) < 1e-6) return 'THEORETICAL_CAPACITY';
  return 'PLAN';
}

export function plannedLabel(basis: PlannedQuantityBasis): string {
  return basis === 'THEORETICAL_CAPACITY' ? '理论产能' : '计划数量';
}

/** 列表列头: 全是理论产能就叫「理论产能」; 混合时两个都写, 不让一个名字盖住两种数。 */
export function plannedColumnLabel(rows: BatchLike[]): string {
  const bases = new Set(rows.filter((r) => num(r.plannedQuantity) != null && num(r.plannedQuantity)! > 0)
    .map((r) => plannedBasisOf(r)));
  if (bases.size === 0) return '计划数量';
  if (bases.size === 2) return '计划数量 / 理论产能';
  return plannedLabel([...bases][0]);
}

/** 没有计划数量 (null / 0 / 非数) → null, 页面空着, 不把 0 当成计划量。 */
export function plannedQuantityOrNull(batch: BatchLike | null | undefined): number | null {
  const planned = num(batch?.plannedQuantity);
  return planned != null && planned > 0 ? planned : null;
}

/**
 * 「总产量完成比例」= 含不良总产量 (actualQuantity) ÷ 计划数量 (或理论产能), 百分数一位小数。
 *
 * B5: 页面说明一直写「含不良总产量 ÷ 计划数量」, 后端 ProductionBatch.calculateMetrics 也是
 * actual / planned; 但导入写进 efficiency 列的是良品 ÷ 计划 (6,592/8,680 = 75.9%, 含不良应为 77.1%)。
 * 计划量单位与批次单位相同时, 按说明的口径用页面上的两个数现算, 说明与数值就不会再分叉;
 * 单位不同 (后端可能已折算) 或缺任一数时, 沿用后端给的 efficiency, 不自己猜换算。
 */
export function completionRatioPercent(batch: BatchLike | null | undefined): number | null {
  if (!batch) return null;
  const planned = plannedQuantityOrNull(batch);
  const actual = num(batch.actualQuantity);
  const plannedUnit = batch.plannedUnit == null || batch.plannedUnit === '' ? null : String(batch.plannedUnit);
  const unit = batch.unit == null || batch.unit === '' ? null : String(batch.unit);
  const sameUnit = plannedUnit == null || unit == null || plannedUnit === unit;
  if (planned != null && actual != null && sameUnit) {
    return Math.round((actual / planned) * 1000) / 10;
  }
  return num(batch.efficiency);
}

export function completionRatioLabel(basis: PlannedQuantityBasis): string {
  return basis === 'THEORETICAL_CAPACITY' ? '总产量 ÷ 理论产能' : '总产量完成比例';
}

export function completionRatioHint(basis: PlannedQuantityBasis): string {
  return basis === 'THEORETICAL_CAPACITY'
    ? '含不良总产量 ÷ 理论产能（原始计划无数量，按产能估算）'
    : '含不良总产量 ÷ 计划数量';
}
