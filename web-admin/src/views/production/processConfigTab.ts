/**
 * 工序 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把「工序管理」+「产品-工序配置」合并到一个入口 (/production/process-hub —— ⛔ 不叫
 * /production/process, 同 productionAnalysisTab.ts 头部说明的「不许撞禁用名单」)。
 * ?tab= query 同步，与 inventoryQueryTab.ts 同模式 —— 纯函数，方便单测。
 *
 * ⚠️ 两个原页面都在 /system/* 下 (views/system/work-processes/index.vue,
 * views/system/product-processes/index.vue) —— 只搬菜单入口/路由挂载点，
 * ⛔ 不碰 WorkProcess 实体/Service/DTO (与在飞的 FEATURE-F006-R2-WORKPROCESS-DEDUP-001
 * 不冲突，那条任务锁的是后端代码，不是菜单)。
 */
export type ProcessConfigTab = 'work-processes' | 'product-processes';

export const PROCESS_CONFIG_TABS: ProcessConfigTab[] = ['work-processes', 'product-processes'];

/** 默认 tab = 工序管理 (原 /system/work-processes，合并前最常用)。 */
export const DEFAULT_PROCESS_CONFIG_TAB: ProcessConfigTab = 'work-processes';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 work-processes。
 */
export function resolveProcessConfigTab(query: Record<string, unknown> | null | undefined): ProcessConfigTab {
  const t = query?.tab;
  return PROCESS_CONFIG_TABS.includes(t as ProcessConfigTab)
    ? (t as ProcessConfigTab)
    : DEFAULT_PROCESS_CONFIG_TAB;
}
