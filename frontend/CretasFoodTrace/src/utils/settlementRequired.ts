/**
 * 纪律排查 A7: 「完工必须先结单」是工厂开关, 默认开 (Steve 2026-11-10, 所有工厂)。
 * 手机上没有结单入口, 直接完工会收到 409 PRODUCTION_SETTLEMENT_REQUIRED ——
 * 这里把它翻成「去哪、怎么做」的提示, ⛔ 不把原始错误直接弹给用户。
 * 后端用 409 + errorCode; 这里按形状取, 不依赖 axios 类型 (同 reworkRatio.ts)。
 */
export const PRODUCTION_SETTLEMENT_REQUIRED = 'PRODUCTION_SETTLEMENT_REQUIRED';

/** 网页端「生产管理 → 生产计划」的路由 (web-admin router: /production/plans)。 */
export const WEB_SETTLEMENT_PATH = '/production/plans';

export interface SettlementRequiredNotice {
  title: string;
  message: string;
}

export function settlementRequiredNotice(error: unknown, planLabel?: string | null): SettlementRequiredNotice | null {
  const resp = (error as { response?: { status?: number; data?: { errorCode?: string } } } | null)?.response;
  if (!resp || resp.status !== 409 || resp.data?.errorCode !== PRODUCTION_SETTLEMENT_REQUIRED) return null;
  const plan = planLabel ? `计划 ${planLabel}` : '这个计划';
  return {
    title: '本厂要求先结单',
    message:
      `本厂设置为生产完成前必须先核对结单，手机上不能直接完工。\n\n`
      + `请到网页端打开「生产管理 → 生产计划」（网址路径 ${WEB_SETTLEMENT_PATH}），`
      + `找到${plan}，点「核对结单」录入实际产量、实际领用后完成。\n\n`
      + '如本厂允许直接完工，请管理员在网页端「系统设置 → 业务阈值配置 → 生产」把「完工必须先结单」设为 0。',
  };
}
