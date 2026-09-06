/**
 * 权限矩阵页的**模块轴**。
 *
 * <h3>为什么是「侧边栏那份」而不是别的六份</h3>
 * 全仓有 7 份互不一致的模块清单 (PR body 里有完整对照表)。owner 2026-09-06 裁定:
 * 轴取「web-admin 侧边栏**实际渲染用的**那一份」——因为这个页面回答的问题是
 * 「用户点得到的那些东西, 各角色分别是什么权限」, 而**用户点得到什么**正是
 * `menuConfig` 的 `module` 字段说了算的 (`AppSidebar.filterMenuItem` →
 * `permissionStore.canAccess(item.module)`)。
 *
 * ⛔ 这里**不写第 8 份清单**: 下面是从 `menuConfig` 现算的。加一个菜单项、改一个
 * `module:`, 这个轴自动跟着变; 契约测试 `moduleAxisMatchesSidebar.spec.ts` 钉住这件事。
 *
 * <h3>轴是全集的**投影**, 所以必须把投影掉的东西点名</h3>
 * 后端返回的 `modules` 是 L1 ∪ 兜底 ∪ L2 的全集, 比侧边栏轴宽 (例如 `operations`、
 * `work_report`、`report` 有 L1 行却没有任何菜单项)。⛔ 直接砍掉 = 形态 A 的经典
 * 马甲「截断把『没有』变成『我没看到』」⇒ 页面用 {@link offAxisModules} 单独列出它们,
 * 并把落在轴外的分歧条数一起报出来。
 */
import { menuConfig, financeManagerMenu, type MenuItem } from '@/components/layout/menuConfig';

function collectModules(items: MenuItem[], into: Set<string>): void {
  for (const item of items) {
    if (item.module) into.add(String(item.module));
    if (item.children?.length) collectModules(item.children, into);
  }
}

/**
 * 侧边栏真正渲染用的模块集合 (含财务主管专用菜单 —— 它也是侧边栏的一支)。
 * 排序按字典序, 让列顺序在两次渲染之间稳定。
 */
export function sidebarModuleAxis(): string[] {
  const set = new Set<string>();
  collectModules(menuConfig, set);
  collectModules(financeManagerMenu, set);
  return [...set].sort();
}

/** 后端返回的全集里, 落在侧边栏轴**之外**的模块 —— 页面必须点名, 不能静默丢掉。 */
export function offAxisModules(allModules: string[], axis: string[]): string[] {
  const onAxis = new Set(axis);
  return allModules.filter((m) => !onAxis.has(m)).sort();
}

/**
 * 反向: 侧边栏有这个模块, 而后端全集里**一行都没有**。
 *
 * 这是一个真实存在的形态 (2026-09-06 实测: `inventory` 在侧边栏上挂着「调拨单」,
 * 而前端硬编码兜底矩阵 `ModulePermissions` 里根本没有这个键) ⇒ DB 拿不到时
 * 那个菜单项对所有人消失。轴上留着这一列并显式标注, 比让它悄悄空着强。
 */
export function axisModulesMissingFromData(axis: string[], allModules: string[]): string[] {
  const known = new Set(allModules);
  return axis.filter((m) => !known.has(m));
}
