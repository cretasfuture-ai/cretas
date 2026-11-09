/**
 * 成本差异报表的显示文字 —— 页面与 Excel 导出共用这一份 (同一个数在两处必须是同一句话)。
 *
 * 后端给 null 的金额一律写「无法比较」(⛔ 不在前端补 0); 回料差 null 要看回料状态说清是
 * 「无回料」「内部转移」还是「无法比较」, 三者不是一回事。
 */
import type {
  CostVarianceReport,
  PlanCostVariance,
  PlanCostVarianceLine,
  ReworkSource,
  ReworkStatus,
} from '@/api/costVariance';

export const INCOMPARABLE = '无法比较';

export function money(v: number | null | undefined): string {
  if (v == null) return INCOMPARABLE;
  return `¥${Number(v).toFixed(2)}`;
}

export function signedMoney(v: number | null | undefined): string {
  if (v == null) return INCOMPARABLE;
  const n = Number(v);
  return `${n > 0 ? '+' : n < 0 ? '−' : ''}¥${Math.abs(n).toFixed(2)}`;
}

export function rate(v: number | null | undefined): string {
  return v == null ? INCOMPARABLE : `${Number(v) > 0 ? '+' : ''}${Number(v).toFixed(2)}%`;
}

export function qty(v: number | null | undefined, unit?: string | null): string {
  return v == null ? '—' : `${Number(Number(v).toFixed(4))}${unit ? ` ${unit}` : ''}`;
}

/** 回料差: 有数写数; null 时按状态说清是没有回料、内部转移还是算不出。 */
export function reworkVarianceText(v: number | null | undefined, status?: ReworkStatus | null): string {
  if (v != null) return signedMoney(v);
  switch (status) {
    case 'NONE': return '无回料';
    case 'INTERNAL_ONLY': return '内部转移 (不另计)';
    case 'INCOMPARABLE': return INCOMPARABLE;
    default: return '无可比回料';
  }
}

export const STATUS_LABEL: Record<PlanCostVarianceLine['status'], string> = {
  COMPARABLE: '可比',
  PARTIAL: '部分可比',
  INCOMPARABLE: INCOMPARABLE,
  INTERNAL: '内部转移',
};

export const LINE_TYPE_LABEL: Record<PlanCostVarianceLine['lineType'], string> = {
  MATERIAL: '配方物料',
  SUBSTITUTE: '替代料',
  UNPLANNED: '计划外',
  LABOR: '人工',
  REWORK: '回料',
};

export const CATEGORY_LABEL: Record<string, string> = {
  RAW: '主料', AUXILIARY: '辅料', PACKAGING: '包材', SEASONING: '调料',
};

export function snapshotPriceText(line: PlanCostVarianceLine, priceMasked: boolean): string {
  if (line.lineType === 'REWORK') return line.standardQuantity == null ? '—' : '配方不含回料';
  return line.snapshotUnitPrice == null
    ? (priceMasked ? '—' : '无单价')
    : `¥${Number(line.snapshotUnitPrice).toFixed(4)}/${line.priceUnit || ''}`;
}

export function actualPriceText(line: PlanCostVarianceLine): string {
  if (line.actualUnitPrice == null) return '—';
  const unit = line.lineType === 'REWORK' && line.priceUnit ? `/${line.priceUnit}` : '';
  return `¥${Number(line.actualUnitPrice).toFixed(4)}${unit}`;
}

/** 「用量差 / 替代料差 / 人工差 / 回料差」那一列。 */
export function lineVarianceText(line: PlanCostVarianceLine): string {
  switch (line.lineType) {
    case 'LABOR': return signedMoney(line.laborVariance);
    case 'SUBSTITUTE': return `替代 ${signedMoney(line.substituteVariance)}`;
    case 'UNPLANNED': return INCOMPARABLE;
    case 'REWORK':
      return line.status === 'INTERNAL' ? '内部转移' : `回料 ${signedMoney(line.reworkVariance)}`;
    default: return signedMoney(line.usageVariance);
  }
}

/** 回料来源: 「计划号 批次号 数量」, 来自追溯边。 */
export function reworkSourcesText(sources: ReworkSource[] | undefined | null): string {
  if (!sources || sources.length === 0) return '';
  return sources
    .map((s) => `${s.producerPlanNumber || s.producerPlanId || '未知计划'} ${s.producerBatchNumber || ''} ${qty(s.quantity, s.unit)}`.trim())
    .join('；');
}

function lineName(line: PlanCostVarianceLine): string {
  return line.materialName || line.materialTypeId || '—';
}

function lineTypeText(line: PlanCostVarianceLine): string {
  const type = LINE_TYPE_LABEL[line.lineType] ?? line.lineType;
  const category = line.materialCategory ? ` · ${CATEGORY_LABEL[line.materialCategory] || line.materialCategory}` : '';
  return `${type}${category}`;
}

export type SheetRow = (string | number)[];

/** 期间汇总 + 各计划摘要 (导出用, 与页面同一套文字)。 */
export function buildPeriodRows(report: CostVarianceReport, range: [string, string]): SheetRow[] {
  const rows: SheetRow[] = [
    ['成本差异报表', `${range[0]} 至 ${range[1]}`],
    ['口径', report.standardCostBasis || '标准成本 = 计划锁定配方的标准用量 × 开计划时物料单价快照'],
  ];
  if (report.comparabilityReason) rows.push(['说明', report.comparabilityReason]);
  if (report.processSecretNote) rows.push(['说明', report.processSecretNote]);
  rows.push(
    [],
    ['标准成本（可比部分）', money(report.totalBomCost)],
    ['差异', signedMoney(report.totalVariance), rate(report.totalVarianceRate)],
    ['价格差', signedMoney(report.priceVariance)],
    ['用量差', signedMoney(report.usageVariance)],
    ['替代料差', signedMoney(report.substituteVariance)],
    ['人工差', signedMoney(report.laborVariance)],
    ['回料差', reworkVarianceText(report.reworkVariance)],
    ['期间实际总成本（全部批次）', money(report.totalActualCost)],
    [],
    ['计划', '产品', '标准成本', '实际成本', '差异', '差异率', '价格差', '用量差', '人工差', '回料差', '回投回料', '回料说明', '说明'],
  );
  for (const p of report.planVariances ?? []) {
    rows.push([
      p.planNumber || p.planId,
      p.productName || p.productTypeId || '—',
      money(p.standardCost),
      money(p.actualCost),
      signedMoney(p.totalVariance),
      rate(p.varianceRate),
      signedMoney(p.priceVariance),
      signedMoney(p.usageVariance),
      signedMoney(p.laborVariance),
      reworkVarianceText(p.reworkVariance, p.reworkStatus),
      p.reworkQuantityText || '—',
      p.recoveredMaterialNote || '',
      p.comparabilityReason || '全部可比',
    ]);
  }
  return rows;
}

/** 单计划逐项明细 (导出用, 与明细抽屉同一套文字)。 */
export function buildPlanRows(detail: PlanCostVariance): SheetRow[] {
  const rows: SheetRow[] = [
    ['成本差异明细', detail.planNumber || detail.planId],
    ['产品', detail.productName || detail.productTypeId || '—'],
    ['产量', `${qty(detail.outputQuantity, detail.outputUnit)}${detail.outputKg != null ? `（${qty(detail.outputKg, 'kg')}）` : ''}`],
    ['单价快照时间', detail.snapshotCapturedAt ? detail.snapshotCapturedAt.replace('T', ' ').slice(0, 19) : '无快照'],
  ];
  if (detail.comparabilityReason) rows.push(['说明', detail.comparabilityReason]);
  if (detail.priceMasked) rows.push(['说明', '你没有采购价查看权限，单价与逐项金额已隐藏']);
  rows.push(
    [],
    ['标准成本', money(detail.standardCost)],
    ['实际成本', money(detail.actualCost)],
    ['差异', signedMoney(detail.totalVariance), rate(detail.varianceRate)],
    ['价格差', signedMoney(detail.priceVariance)],
    ['用量差', signedMoney(detail.usageVariance)],
    ['替代料差', signedMoney(detail.substituteVariance)],
    ['人工差', signedMoney(detail.laborVariance)],
    ['回料差', reworkVarianceText(detail.reworkVariance, detail.reworkStatus)],
    ['回料说明', detail.recoveredMaterialNote || ''],
    [],
    ['项目', '类型', '开计划时单价', '标准用量', '实际用量', '实际单价', '标准金额', '实际金额', '价格差',
      '用量差 / 替代料差 / 人工差 / 回料差', '状态', '原因', '回料来源'],
  );
  for (const line of detail.lines) {
    rows.push([
      lineName(line),
      lineTypeText(line),
      snapshotPriceText(line, detail.priceMasked),
      qty(line.standardQuantity, line.lineType === 'LABOR' ? 'kg 成品' : line.priceUnit),
      qty(line.actualQuantity, line.priceUnit),
      actualPriceText(line),
      line.standardAmount == null ? '—' : money(line.standardAmount),
      line.actualAmount == null ? '—' : money(line.actualAmount),
      line.lineType === 'LABOR' || line.lineType === 'REWORK' ? '—' : signedMoney(line.priceVariance),
      lineVarianceText(line),
      STATUS_LABEL[line.status] ?? line.status,
      (line.reasons ?? []).join('；'),
      reworkSourcesText(line.reworkSources),
    ]);
  }
  return rows;
}
