/**
 * 编码规则页的单据类型 (PROTO-QC-NUMBER follow-up M2)。
 *
 * 质检记录的编号 QI-YYYYMMDD-NNNN 由系统 (数据库触发器) 自动发号, 这里配规则不会生效 ——
 * 所以不出现在「新建规则」的可选类型里; 历史上已经建过的质检记录规则仍然显示, 但标明「由系统自动编号」。
 * 后端同一口径: EncodingRuleService.SYSTEM_NUMBERED_ENTITY_TYPES, 新建会被 400 拒绝。
 */
export const ENTITY_TYPE_LABELS: Record<string, string> = {
  SALES_ORDER: '销售订单',
  PURCHASE_ORDER: '采购订单',
  PRODUCTION_PLAN: '生产计划',
  PRODUCTION_BATCH: '生产批次',
  MATERIAL_BATCH: '原材料批次',
  SHIPMENT: '出货单',
  TRANSFER: '调拨单',
  QUALITY_INSPECTION: '质检记录',
};

export const SYSTEM_NUMBERED_ENTITY_TYPES: ReadonlySet<string> = new Set(['QUALITY_INSPECTION']);

export const SYSTEM_NUMBERED_NOTE = '由系统自动编号，此规则不生效';

export function isSystemNumbered(entityType: unknown): boolean {
  return typeof entityType === 'string' && SYSTEM_NUMBERED_ENTITY_TYPES.has(entityType.trim().toUpperCase());
}

/** 新建规则时可选的单据类型: 去掉由系统自动编号的类型。 */
export function selectableEntityTypes(): Array<{ value: string; label: string }> {
  return Object.entries(ENTITY_TYPE_LABELS)
    .filter(([code]) => !isSystemNumbered(code))
    .map(([value, label]) => ({ value, label }));
}
