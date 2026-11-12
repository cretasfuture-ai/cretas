/**
 * 工序投入产出对比 ·「工序汇总」页的单位显示与平均口径 (2026-11-12 网页验收两处修复)。
 *
 * 1) 单位: 后端用 'unitless' 作「没有单位」的占位 (新建工序默认写它), 页面曾原样显示「0.2 unitless」。
 *    没有单位如实写「无单位」, ⛔ 不猜成 kg。
 * 2) 转化率: 投入、产出单位不同 (kg → 盒) 或一侧混了几种单位时不相除 (后端给 null + 原因);
 *    平均转化率 / 平均损耗率只用能比的工序算, 并写明参与了几道。
 */

import { displayProcessUnit } from '@/utils/processSheetUnits';

export type IncomparableReason = 'UNIT_MISMATCH' | 'MIXED_UNITS' | 'NO_UNIT' | 'NO_INPUT';

export interface ProcessYieldAgg {
  processName: string;
  inputQuantity: number | null;   // 投入单位混杂时 null (合计不成立)
  outputQuantity: number | null;
  conversionRate: number | null;  // 后端已 0-100; 不可比为 null
  wastageRate: number | null;
  unit?: string | null;           // 旧字段, 同 inputUnit
  inputUnit?: string | null;
  outputUnit?: string | null;
  unitComparable: boolean;
  incomparableReason?: IncomparableReason | null;
  batchCount: number;
}

/** 'unitless' / 空串 / null 都算「没有单位」, 返回 null; 否则返回给用户看的单位 (box → 盒 等走 displayProcessUnit)。 */
export function realUnit(unit: string | null | undefined): string | null {
  const t = (unit ?? '').trim();
  if (!t || t.toLowerCase() === 'unitless') return null;
  return displayProcessUnit(t) || null;
}

/** 数量 + 单位。没有单位写「无单位」; 数量为 null (一侧单位混杂) 写「多种单位，未合计」。 */
export function formatQuantity(value: number | null | undefined, unit: string | null | undefined): string {
  if (value == null) return '多种单位，未合计';
  const u = realUnit(unit);
  return u ? `${value} ${u}` : `${value}（无单位）`;
}

/** 物料平衡表等只缺数量时显示「—」的场景用这个: 数量 null → 「—」。 */
export function formatQuantityOrDash(value: number | null | undefined, unit: string | null | undefined): string {
  return value == null ? '—' : formatQuantity(value, unit);
}

/** 转化率为空时给用户看的原因 (表格里代替「—」)。 */
export function incomparableLabel(row: Pick<ProcessYieldAgg, 'conversionRate' | 'incomparableReason'>): string {
  if (row.conversionRate != null) return '';
  switch (row.incomparableReason) {
    case 'UNIT_MISMATCH': return '单位不同，无法比较';
    case 'MIXED_UNITS': return '单位不统一，无法比较';
    case 'NO_UNIT': return '未登记单位，无法比较';
    case 'NO_INPUT': return '无投入，无法计算';
    default: return '无法比较';
  }
}

export interface SummaryKpi {
  processCount: number;
  /** 参与平均的工序数 (转化率算得出来的)。 */
  comparableCount: number;
  /** comparableCount 为 0 时 null —— 不显示成 0%。 */
  avgConversion: number | null;
  avgWastage: number | null;
  lowEfficiencyCount: number;
}

export function summaryKpi(rows: ReadonlyArray<Pick<ProcessYieldAgg, 'conversionRate' | 'wastageRate'>>): SummaryKpi {
  const comparable = rows.filter((r) => r.conversionRate != null);
  const n = comparable.length;
  const round1 = (v: number) => Math.round(v * 10) / 10;
  const withWastage = comparable.filter((r) => r.wastageRate != null);
  return {
    processCount: rows.length,
    comparableCount: n,
    avgConversion: n ? round1(comparable.reduce((s, r) => s + (r.conversionRate as number), 0) / n) : null,
    avgWastage: withWastage.length
      ? round1(withWastage.reduce((s, r) => s + (r.wastageRate as number), 0) / withWastage.length)
      : null,
    lowEfficiencyCount: comparable.filter((r) => (r.conversionRate as number) < 80).length,
  };
}

/** KPI 卡下方的口径说明, 例:「按 3 道同单位工序平均（共 5 道，2 道单位不同未计入）」。 */
export function averageScopeNote(kpi: Pick<SummaryKpi, 'processCount' | 'comparableCount'>): string {
  if (kpi.processCount === 0) return '暂无工序';
  if (kpi.comparableCount === 0) return `${kpi.processCount} 道工序都无法比较，未计算平均`;
  const skipped = kpi.processCount - kpi.comparableCount;
  return skipped > 0
    ? `按 ${kpi.comparableCount} 道同单位工序平均（共 ${kpi.processCount} 道，${skipped} 道无法比较未计入）`
    : `按 ${kpi.comparableCount} 道同单位工序平均`;
}
