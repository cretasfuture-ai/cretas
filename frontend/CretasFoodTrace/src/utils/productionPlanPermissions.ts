/** 生产计划属于 PC 端复杂规划；RN 对所有角色都只展示计划与现场进度。 */
export function canCreateProductionPlan(_roleCode: string, _isReadOnly: boolean): boolean {
  return false;
}
