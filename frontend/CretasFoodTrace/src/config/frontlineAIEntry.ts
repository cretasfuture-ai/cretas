/**
 * 一线角色 AI 助手入口 —— 熔断开关（默认开）
 *
 * 2026-10-05 owner 裁定：operator / warehouse_worker / quality_inspector **默认就有** AI 助手入口；
 * 开关是用来**关掉**它的（熔断），不是用来打开它的。设计与 UX Flow Analysis 见
 * `docs/specs/rn-frontline-ai-entry-2026-10-05.md`。
 *
 * 两层，任一层关掉入口就消失（首页按钮不渲染、导航器不注册 `AIChat` 路由）：
 *
 *   ① 全局（OTA）：把 `FRONTLINE_AI_ENTRY_GLOBAL_ENABLED` 改成 false → 合 main → 推 OTA。所有工厂一起关。
 *   ② 按工厂（远程，无需发版/OTA）：既有 feature-config 通道。web-admin「功能配置 → 智能BI → 禁用的屏幕」
 *      勾选 `FrontlineAIChat`，或整个 smartbi 模块关掉。App 下次加载功能配置时生效。
 *      ⚠️ 功能配置加载失败时 isScreenEnabled 返回 true（既有向后兼容行为）⇒ 这一层 fail-open；
 *      要「确定关掉」用第①层。入口只是 UI，服务端逐意图鉴权（IntentPermissionGate）不受影响。
 *
 * ⛔ 这里不做任何「这个角色能不能执行某意图」的判断 —— 那是服务端的事，客户端判了也拦不住人。
 */

/** 第①层：全局熔断。OTA 可改。默认开（owner 裁定）。 */
export const FRONTLINE_AI_ENTRY_GLOBAL_ENABLED = true;

/** 第②层的屏幕 ID：必须与 factoryFeatureStore 的 SCREEN_TO_MODULE 键、web-admin 功能配置页的 id 完全一致。 */
export const FRONTLINE_AI_SCREEN_ID = 'FrontlineAIChat';

/**
 * 有入口的角色。owner 只点名了这三个。
 * ⚠️ `yield_operator` 也落到 OperatorNavigator（同一个首页），但**故意不加** —— 列为 owner 决策项。
 */
export const FRONTLINE_AI_ROLES: readonly string[] = ['operator', 'warehouse_worker', 'quality_inspector'];

/**
 * 纯函数判定，便于测试与变异。
 * @param role 当前用户角色码（与 AppNavigator 分派用的同一个 getUserRole）
 * @param isScreenEnabled 第②层：factoryFeatureStore.isScreenEnabled
 * @param globalEnabled 第①层：默认取常量；测试注入以构造「熔断关」
 */
export function isFrontlineAIEntryEnabled(
  role: string | null | undefined,
  isScreenEnabled: (screenId: string) => boolean,
  globalEnabled: boolean = FRONTLINE_AI_ENTRY_GLOBAL_ENABLED,
): boolean {
  if (!role || !FRONTLINE_AI_ROLES.includes(role)) return false;
  if (!globalEnabled) return false;
  return isScreenEnabled(FRONTLINE_AI_SCREEN_ID);
}
