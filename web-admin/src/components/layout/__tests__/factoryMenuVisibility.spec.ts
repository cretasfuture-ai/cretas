import { describe, expect, it } from 'vitest';
import { computed, ref } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  isFactoryAnalyticsSidebarTurnedOff, isFactoryAnalyticsGroupHidden,
  getDisabledFactoryMenuPaths, isFactoryMenuHidden, getFactoryModuleMenuOptions,
  getHiddenFactoryMenuModules,
} from '../factoryMenuVisibility';
import { menuConfig } from '../menuConfig';

describe('factory analytics sidebar: default visible, per-factory off (P2b / M3)', () => {
  it('only an explicit showInSidebar === false turns the group off', () => {
    for (const features of [
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false } }],
      [{ moduleId: 'analytics', enabled: false, config: { showInSidebar: false } }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false, menuVisible: false } }],
    ]) expect(isFactoryAnalyticsSidebarTurnedOff(features)).toBe(true);
    for (const features of [
      undefined, null, {}, [], [null],
      [{ moduleId: 'analytics', enabled: true }],
      [{ moduleId: 'analytics', enabled: true, config: {} }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: true } }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: 'false' } }],
      [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: 0 } }],
      [{ moduleId: 'production', enabled: true, config: { showInSidebar: false } }],
    ]) expect(isFactoryAnalyticsSidebarTurnedOff(features)).toBe(false);
  });

  it('reads the legacy SmartBI alias only when the canonical analytics row is absent', () => {
    const legacyOff = { moduleId: 'smartbi', enabled: true, config: { showInSidebar: false } };
    expect(isFactoryAnalyticsSidebarTurnedOff([legacyOff])).toBe(true);
    expect(isFactoryAnalyticsSidebarTurnedOff([
      legacyOff, { moduleId: 'analytics', enabled: true, config: { showInSidebar: true } },
    ])).toBe(false);
    expect(getHiddenFactoryMenuModules([
      { moduleId: 'restaurant_buy', config: { menuVisible: false } },
    ])).toContain('restaurantProcurement');
  });

  it('only hides the FACTORY data-and-analysis group, never anything else', () => {
    expect(isFactoryAnalyticsGroupHidden('FACTORY', '/smart-bi', true)).toBe(true);
    expect(isFactoryAnalyticsGroupHidden('FACTORY', '/smart-bi', false)).toBe(false);
    for (const type of ['RESTAURANT', 'LOGISTICS', 'CENTRAL_KITCHEN', '']) {
      expect(isFactoryAnalyticsGroupHidden(type, '/smart-bi', true)).toBe(false);
    }
    for (const path of ['/finance', '/crm', '/ops', '/smart-bi/dashboard', '/analytics/ai-reports']) {
      expect(isFactoryAnalyticsGroupHidden('FACTORY', path, true)).toBe(false);
    }
  });

  it('the /smart-bi group no longer carries a FACTORY type hide; its own leaf hides stay', () => {
    const group = menuConfig.find(item => item.path === '/smart-bi');
    expect(group).toBeDefined();
    expect(group?.hideForFactoryTypes ?? []).not.toContain('FACTORY');
    expect(group?.children?.find(c => c.path === '/smart-bi/revenue-report')?.hideForFactoryTypes).toEqual(['FACTORY']);
  });

  it('updates after async configuration arrives while still requiring analytics access', () => {
    const features = ref<unknown>([]);
    const permission = ref(false);
    const visible = computed(() => permission.value && !isFactoryAnalyticsGroupHidden(
      'FACTORY', '/smart-bi', isFactoryAnalyticsSidebarTurnedOff(features.value),
    ));
    expect(visible.value).toBe(false);
    permission.value = true;
    expect(visible.value).toBe(true);
    features.value = [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false } }];
    expect(visible.value).toBe(false);
    features.value = [];
    expect(visible.value).toBe(true);
  });

  it('wires the real sidebar to the per-factory off switch without skipping module and role checks', () => {
    const src = readFileSync(resolve(process.cwd(), 'src/components/layout/AppSidebar.vue'), 'utf8');
    expect(src).toContain('isFactoryAnalyticsSidebarTurnedOff(featuresPayload.data)');
    expect(src).toContain('if (!navigationVisibility.isCurrentLoad(token)) return;');
    expect(src).toContain('featuresPayload?.success === true && Array.isArray(featuresPayload.data)');
    expect(src).toContain('navigationVisibility.markErrorIfCurrent(token)');
    expect(src).toContain('factoryAnalyticsTurnedOff.value = false;');
    expect(src).toContain('getHiddenFactoryMenuModules(featuresPayload.data)');
    expect(src).toContain('hiddenFactoryMenuModules.value.includes(item.module)');
    expect(src).toContain('if (hiddenByFactory || hiddenByModule || factoryAnalyticsHidden) return false;');
    expect(src).toContain('if (item.hideForFactoryTypes?.includes(factoryType)) {');
    expect(src).toContain('return item.roles.includes(currentRole) && canAccess;');
    expect(src).toContain('if (disabledSet.has(item.module))');
  });
});

describe('per-factory child menu visibility', () => {
  it('reads platform-owned parent visibility without treating enabled as menu visibility', () => {
    expect(getHiddenFactoryMenuModules([
      { moduleId: 'production', enabled: true, config: { menuVisible: false } },
      { moduleId: 'warehouse', enabled: false, config: { menuVisible: true } },
      { moduleId: 'smartbi', enabled: true, config: { menuVisible: false } },
    ])).toEqual(['production', 'analytics']);
    expect(getHiddenFactoryMenuModules([{ moduleId: 'production', enabled: false, config: {} }])).toEqual([]);
  });

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

  it('supports child menus that share a route but select different tabs', () => {
    const disabled = getDisabledFactoryMenuPaths([{ config: {
      disabledMenuPaths: ['/warehouse/inventory-query?tab=raw'],
    } }]);
    expect(disabled).toEqual(['/warehouse/inventory-query?tab=raw']);
    expect(isFactoryMenuHidden('/warehouse/inventory-query?extra=1&tab=raw', disabled)).toBe(true);
    expect(isFactoryMenuHidden('/warehouse/inventory-query?tab=finished', disabled)).toBe(false);
    expect(isFactoryMenuHidden('/warehouse/inventory-query', disabled)).toBe(false);
  });
});
