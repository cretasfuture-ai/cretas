/**
 * 二次加工批次判定 (SP3: 首道从厂内半成品库存领料, 而不是领原料)。
 *
 * 后端只写 `planSourceType = 'SECONDARY'` (ProductionPlanServiceImpl#createSecondaryPlan),
 * ProcessingServiceImpl 原样回填到批次的 batchSourceType。App 这里曾只认 'SEMI_FINISHED',
 * 于是二次加工的半成品选择区在线上从不出现 (2026-11-04 真机 + 接口核对)。
 * 'SEMI_FINISHED' 保留兼容旧值。
 */
export function isSecondaryBatchSourceType(batchSourceType: string | null | undefined): boolean {
  return batchSourceType === 'SECONDARY' || batchSourceType === 'SEMI_FINISHED';
}
