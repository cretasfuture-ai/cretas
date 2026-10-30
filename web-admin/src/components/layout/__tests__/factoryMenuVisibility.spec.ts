import { describe, expect, it } from 'vitest';
import { computed, ref } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  hasFactoryAnalyticsSidebarOptIn, canShowOptedInFactoryAnalytics,
  getDisabledFactoryMenuPaths, isFactoryMenuHidden, getFactoryModuleMenuOptions,
} from '../factoryMenuVisibility';
import { menuConfig } from '../menuConfig';

describe('factory analytics sidebar opt-in', () => {
  it('requires both an enabled analytics feature and the explicit sidebar setting', () => {
    expect(hasFactoryAnalyticsSidebarOptIn([
      { moduleId: 'analytics', enabled: true, config: { showInSidebar: true } },
    ])).toBe(true);
    for (const features of [
      undefined, null, {}, [], [null],
      [{ moduleId: 'analytics', enabled: true }],
      [{ moduleId: 'analytics', enabled: false, config: { showInSidebar: true } }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false } }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: 'true' } }],
      [{ moduleId: 'smartbi', enabled: true, config: { showInSidebar: true } }],
    ]) expect(hasFactoryAnalyticsSidebarOptIn(features)).toBe(false);
  });

  it('only lifts factory-type hiding for the data-and-analysis group', () => {
    expect(canShowOptedInFactoryAnalytics('FACTORY', '/smart-bi', true)).toBe(true);
    expect(canShowOptedInFactoryAnalytics('FACTORY', '/smart-bi', false)).toBe(false);
    for (const type of ['RESTAURANT', 'LOGISTICS', '']) {
      expect(canShowOptedInFactoryAnalytics(type, '/smart-bi', true)).toBe(false);
    }
    for (const path of ['/finance', '/crm', '/ops', '/smart-bi/health-report']) {
      expect(canShowOptedInFactoryAnalytics('FACTORY', path, true)).toBe(false);
    }
  });

  it('updates after async configuration arrives while still requiring analytics access', () => {
    const features = ref<unknown>([]);
    const permission = ref(false);
    const visible = computed(() => permission.value && canShowOptedInFactoryAnalytics(
      'FACTORY', '/smart-bi', hasFactoryAnalyticsSidebarOptIn(features.value),
    ));
    expect(visible.value).toBe(false);
    features.value = [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: true } }];
    expect(visible.value).toBe(false);
    permission.value = true;
    expect(visible.value).toBe(true);
    features.value = [];
    expect(visible.value).toBe(false);
  });

  it('wires the real sidebar to the opt-in without skipping its module and role checks', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/components/layout/AppSidebar.vue'), 'utf8');
    expect(src).toContain('hasFactoryAnalyticsSidebarOptIn(featuresResult.value.data)');
    expect(src).toContain('if (token !== disabledFetchToken) return;');
    expect(src).toContain('factoryAnalyticsMenuEnabled.value = false;');
    expect(src).toContain('item.hideForFactoryTypes?.includes(factoryType) && !factoryAnalyticsVisible');
    expect(src).toContain('return item.roles.includes(currentRole) && canAccess;');
    expect(src).toContain('if (disabledSet.has(item.module))');
  });
});

describe('per-factory child menu visibility', () => {
  it('combines module-specific disabled menus without mutating another factory configuration', () => {
    const otherFactory = [{ moduleId: 'warehouse', config: {} }];
    const disabled = getDisabledFactoryMenuPaths([
      { moduleId: 'warehouse', config: { disabledMenuPaths: ['/warehouse/source-individuals', '/warehouse/source-individuals'] } },
      { moduleId: 'production', config: { disabledMenuPaths: ['/production/traceability'] } },
      null,
      { config: { disabledMenuPaths: [null, '/', '//outside', 'https://example.com', 42] } },
    ]);
    expect(disabled).toEqual(['/warehouse/source-individuals', '/production/traceability']);
    expect(getDisabledFactoryMenuPaths(otherFactory)).toEqual([]);
    expect(getDisabledFactoryMenuPaths(undefined)).toEqual([]);
    expect(otherFactory).toEqual([{ moduleId: 'warehouse', config: {} }]);
  });

  it('hides the chosen submenu and descendants while keeping sibling and parent menus', () => {
    const disabled = ['/production/bom/ecns', '/sales/vehicles'];
    for (const path of ['/production/bom/ecns', '/production/bom/ecns/123', '/sales/vehicles?tab=active']) {
      expect(isFactoryMenuHidden(path, disabled)).toBe(true);
    }
    for (const path of ['/production', '/production/bom', '/production/plans', '/sales/orders', '/sales/vehicles-summary']) {
      expect(isFactoryMenuHidden(path, disabled)).toBe(false);
    }
  });

  it('offers actual child menus from their owning modules instead of a separate manual catalogue', () => {
    const warehouse = getFactoryModuleMenuOptions(menuConfig, 'warehouse');
    const production = getFactoryModuleMenuOptions(menuConfig, 'production');
    const sales = getFactoryModuleMenuOptions(menuConfig, 'sales');
    expect(warehouse).toContainEqual({ path: '/warehouse/source-individuals', label: '源头个体档案' });
    expect(warehouse).toContainEqual({ path: '/warehouse/wastage-reports', label: '报损管理' });
    expect(production).toContainEqual({ path: '/production/bom/ecns', label: '工程变更通知' });
    expect(sales).toContainEqual(expect.objectContaining({ path: '/sales/vehicles' }));
    expect(sales.some(item => item.path === '/warehouse/source-individuals')).toBe(false);
  });
});
