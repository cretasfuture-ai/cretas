import { isAxiosError } from 'axios';

/**
 * 后端路由闸对「全工厂汇总 / 未接入行级范围的快捷查询」的拒绝码
 * (DataScopeRouteInterceptor.DATA_SCOPE_UNSUPPORTED)。
 * 这种拒绝重试永远不会好, 页面要说明原因和找谁调整, 不能提示「下拉重试」。
 */
export const DATA_SCOPE_UNSUPPORTED = 'DATA_SCOPE_UNSUPPORTED';

/** 库存页: 列表已按岗位数据范围过滤、全厂统计被拒时, 列表上方的说明。 */
export const INVENTORY_SCOPE_NOTICE =
  '你的岗位数据范围不是「全工厂」：下面只列出你（或你可查看的同事）入库的批次，全工厂统计不显示。'
  + '需要看全厂库存，请工厂总监在网页后台「角色权限 → 数据范围」调整本岗位。';

/** 是否是「数据范围不支持」这一类拒绝 (只看语义码, 不匹配中文原文)。 */
export function isDataScopeRefusal(error: unknown): boolean {
  if (!isAxiosError(error)) return false;
  const data = error.response?.data as { errorCode?: unknown } | undefined;
  return error.response?.status === 403 && data?.errorCode === DATA_SCOPE_UNSUPPORTED;
}

/** 成功响应里后端给的范围说明 (受限范围时才有)。 */
export function scopeHintOf(response: unknown): string | null {
  if (response == null || typeof response !== 'object') return null;
  const hint = (response as { actionHint?: unknown }).actionHint;
  return typeof hint === 'string' && hint.trim() !== '' ? hint : null;
}
