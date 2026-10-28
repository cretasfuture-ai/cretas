import { isAnyProcessSecretHidden } from '@/utils/processSecretField';

export interface BatchCostEvidence {
  finishedBatchNumber?: string | null;
  hasData?: boolean;
  costComplete?: boolean;
  priceMasked?: boolean;
  calculationStatus?: string;
  rawMaterialCost?: number | null;
  laborCost?: number | null;
  seasoningCost?: number | null;
  packagingCost?: number | null;
  equipmentCost?: number | null;
  otherCost?: number | null;
  semiFeedCost?: number | null;
  totalCost?: number | null;
  perBoxCost?: number | null;
  knownCostSubtotal?: number | null;
  knownPerBoxCost?: number | null;
  byproductCredit?: number | null;
  sellablePerBoxCost?: number | null;
  costDenominatorUnit?: string | null;
  outputUnit?: string | null;
  missingCostItems?: string[] | null;
}
const amountKeys = ['rawMaterialCost', 'laborCost', 'seasoningCost', 'packagingCost', 'equipmentCost', 'otherCost',
  'semiFeedCost', 'totalCost', 'perBoxCost', 'knownCostSubtotal', 'knownPerBoxCost', 'byproductCredit', 'sellablePerBoxCost'] as const;
export function readBatchCostEvidence(value: unknown, batchNumber: string): BatchCostEvidence | null {
  if (value == null) return null;
  if (typeof value !== 'object' || Array.isArray(value)) throw new Error('成本返回结构无效');
  const row = value as Record<string, unknown>;
  if (typeof row.hasData !== 'boolean') throw new Error('成本数据状态不明确');
  if (row.hasData && row.finishedBatchNumber !== batchNumber) throw new Error('成本来源批次与当前批次不一致');
  if (amountKeys.some(key => row[key] != null && (typeof row[key] !== 'number' || !Number.isFinite(row[key])))) {
    throw new Error('成本金额格式无效');
  }
  if (row.missingCostItems != null && (!Array.isArray(row.missingCostItems) || row.missingCostItems.some(v => typeof v !== 'string'))) {
    throw new Error('待补成本资料格式无效');
  }
  return row as unknown as BatchCostEvidence;
}
const missingLabels: Record<string, string> = {
  EQUIPMENT_COST: '设备成本', OTHER_COST: '其他成本', LABOR_COST_UNKNOWN: '人工成本未知',
  LABOR_TIME_DETAIL_MISSING: '人工工时段（金额已计入）', LABOR_RATE_OR_TIME: '人工工时或费率',
  PINNED_BOM_SNAPSHOT_MISSING: '生产时的配方快照', SEMI_FINISHED_OR_FINISHED_FEED_PRICE: '半成品/成品投料单价',
  RAW_MATERIAL_PRICE: '原料单价', PACKAGING_PRICE: '包材单价', SEASONING_PRICE: '调料单价',
  NO_COST_COLLECTED: '尚未归集成本', BOM_VERSION_MIXED: '生产配方版本待核对',
  ESTIMATED_AUX_RATIO_MISSING: '辅料配比缺失', ESTIMATED_AUX_PRICE_MISSING: '辅料单价缺失',
};
export function missingBatchCostLabels(items?: string[] | null): string[] {
  return [...new Set((items || []).map(item => missingLabels[item.split(':')[0] || ''] || '其他成本资料待核对'))];
}
export function batchCostState(data: BatchCostEvidence | null, loading: boolean, error: string) {
  if (loading) return 'LOADING';
  if (error) return 'ERROR';
  if (data?.priceMasked) return 'MASKED';
  // 工艺保密: 保密产品的成本合计对无授权账号是 null, ⛔ 不能落进「待核对」让人去查报工
  if (data && isAnyProcessSecretHidden(data, ['totalCost', 'knownCostSubtotal'])) return 'SECRET';
  if (!data?.hasData || data.calculationStatus === 'NO_DATA') return 'NO_DATA';
  if (typeof data.priceMasked !== 'boolean') return 'UNVERIFIED';
  if (data.calculationStatus === 'PARTIAL' && data.costComplete === false) return 'PARTIAL';
  if (data.calculationStatus === 'COMPLETE' && data.costComplete === true && data.totalCost != null && data.totalCost >= 0) return 'COMPLETE';
  return 'UNVERIFIED';
}
