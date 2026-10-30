import type { MenuItem } from './menuConfig';

/** Factories retain the existing hidden analytics menu until explicitly opted in. */
export function hasFactoryAnalyticsSidebarOptIn(features: unknown): boolean {
  if (!Array.isArray(features)) return false;
  return features.some((feature: unknown) => {
    if (!feature || typeof feature !== 'object') return false;
    const row = feature as Record<string, unknown>;
    if (row.moduleId !== 'analytics' || row.enabled !== true) return false;
    const config = row.config;
    return Boolean(config && typeof config === 'object'
      && (config as Record<string, unknown>).showInSidebar === true);
  });
}

/** This only lifts the factory-type hiding of the analytics group, never access checks. */
export function canShowOptedInFactoryAnalytics(
  factoryType: string,
  path: string,
  optedIn: boolean,
): boolean {
  return factoryType === 'FACTORY' && path === '/smart-bi' && optedIn;
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
      if (typeof path === 'string' && /^\/(?!\/)[a-zA-Z0-9_/-]+$/.test(path) && path !== '/') {
        paths.add(path.replace(/\/$/, ''));
      }
    }
  }
  return [...paths];
}

export function isFactoryMenuHidden(path: string, disabledPaths: readonly string[]): boolean {
  const pathname = path.split(/[?#]/)[0];
  return disabledPaths.some(disabled => pathname === disabled || pathname.startsWith(`${disabled}/`));
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
