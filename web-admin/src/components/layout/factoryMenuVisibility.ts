import type { MenuItem } from './menuConfig';

/**
 * P2b（迁移方案 M3，2026-11-14）：「数据与分析」组对工厂**默认显示**，按厂可关。
 * 关 = feature-config 里 analytics（无此行时读旧别名 smartbi）的 config.showInSidebar === false。
 * 平台「工厂菜单」页关掉 analytics 时后端同时写 showInSidebar=false 与 menuVisible=false。
 * 只认显式的 false：缺行、缺字段、非布尔值都按「没关」处理（默认显示）。
 */
export function isFactoryAnalyticsSidebarTurnedOff(features: unknown): boolean {
  if (!Array.isArray(features)) return false;
  const rows = features.filter((feature): feature is Record<string, unknown> => !!feature && typeof feature === 'object');
  const row = rows.find(feature => feature.moduleId === 'analytics')
    || rows.find(feature => feature.moduleId === 'smartbi');
  if (!row) return false;
  const config = row.config;
  return Boolean(config && typeof config === 'object'
    && (config as Record<string, unknown>).showInSidebar === false);
}

const ANALYTICS_CONTAINER_PATH = '#group/analytics';

/** 只作用于工厂（FACTORY）的 /smart-bi 组；只会多隐藏，从不放宽模块/角色判据。 */
export function isFactoryAnalyticsGroupHidden(
  factoryType: string,
  path: string,
  turnedOff: boolean,
): boolean {
  // P6：原型分组的展示容器 '#group/analytics' 也认同一个开关——搬进来的成本分析 / 物料均价（D1/D6）跟着整组一起关
  return factoryType === 'FACTORY' && (path === '/smart-bi' || path === ANALYTICS_CONTAINER_PATH) && turnedOff;
}

export function getDisabledFactoryMenuPaths(features: unknown): string[] {
  if (!Array.isArray(features)) return [];
  const paths = new Set<string>();
  for (const feature of features) {
    if (!feature || typeof feature !== 'object') continue;
    const config = (feature as Record<string, unknown>).config;
    if (!config || typeof config !== 'object') continue;
    const disabled = (config as Record<string, unknown>).disabledMenuPaths;
    if (!Array.isArray(disabled)) continue;
    for (const path of disabled) {
      if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) continue;
      try {
        const url = new URL(path, 'https://factory-menu.invalid');
        if (url.origin !== 'https://factory-menu.invalid' || url.pathname === '/') continue;
        paths.add(url.pathname.replace(/\/$/, '') + url.search);
      } catch { /* Ignore malformed configuration entries. */ }
    }
  }
  return [...paths];
}

/** Platform-owned parent visibility. `enabled` and permission gates remain separate. */
export function getHiddenFactoryMenuModules(features: unknown): string[] {
  if (!Array.isArray(features)) return [];
  const hidden = new Set<string>();
  for (const feature of features) {
    if (!feature || typeof feature !== 'object') continue;
    const row = feature as Record<string, unknown>;
    const moduleId = typeof row.moduleId === 'string' ? row.moduleId : '';
    const config = row.config;
    if (!moduleId || !config || typeof config !== 'object') continue;
    if ((config as Record<string, unknown>).menuVisible === false) {
      hidden.add(moduleId === 'smartbi' ? 'analytics'
        : moduleId === 'restaurant_buy' ? 'restaurantProcurement' : moduleId);
    }
  }
  return [...hidden];
}

export function isFactoryMenuHidden(path: string, disabledPaths: readonly string[]): boolean {
  const pathname = path.split(/[?#]/)[0];
  return disabledPaths.some(disabled => {
    const [disabledPath, query] = disabled.split('?');
    if (query) {
      if (pathname !== disabledPath) return false;
      const actual = new URLSearchParams(path.split('?')[1]?.split('#')[0] || '');
      return [...new URLSearchParams(query)].every(([key, value]) => actual.getAll(key).includes(value));
    }
    return pathname === disabledPath || pathname.startsWith(`${disabledPath}/`);
  });
}

/** Reuse the actual menu tree so configuration options track menu additions and labels. */
export function getFactoryModuleMenuOptions(menu: readonly MenuItem[], moduleId: string) {
  const options = new Map<string, { path: string; label: string }>();
  const visit = (items: readonly MenuItem[]) => {
    for (const item of items) {
      if (item.children?.length) visit(item.children);
      else if (item.module === moduleId) options.set(item.path, { path: item.path, label: item.title });
    }
  };
  visit(menu);
  return [...options.values()];
}
