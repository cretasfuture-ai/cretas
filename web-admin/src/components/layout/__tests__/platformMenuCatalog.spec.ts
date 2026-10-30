import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';
import { getFactoryModuleMenuOptions } from '../factoryMenuVisibility';

describe('platform menu catalog consumed by the backend', () => {
  it('accepts exactly the configurable leaves of the current real menu tree', () => {
    const modules = new Set<string>();
    const visit = (items: readonly MenuItem[]) => {
      for (const item of items) {
        modules.add(item.module);
        if (item.children) visit(item.children);
      }
    };
    visit(menuConfig);
    const expected = Object.fromEntries([...modules].sort().map((moduleId) => [
      moduleId, getFactoryModuleMenuOptions(menuConfig, moduleId).map((option) => option.path).sort(),
    ]));
    const catalog = JSON.parse(readFileSync(resolve(process.cwd(),
      '../backend/java/cretas-api/src/main/resources/platform/factory-menu-paths.json',
    ), 'utf8')) as Record<string, string[]>;
    expect(catalog).toEqual(expected);
    expect(catalog.warehouse).toContain('/warehouse/source-individuals');
    expect(catalog.warehouse).toContain('/warehouse/wastage-reports');
    expect(catalog.production).toContain('/production/bom/ecns');
    expect(catalog.sales).toContain('/sales/vehicles');
  });

  it('keeps the Java view policy closed around the supported lists', () => {
    const paths = JSON.parse(readFileSync(resolve(process.cwd(),
      '../backend/java/cretas-api/src/main/resources/platform/factory-view-read-paths.json'), 'utf8'));
    const allows = (path: string) => (paths as string[]).some(pattern => new RegExp(`^(?:${pattern})$`).test(path));
    for (const path of ['/reports/dashboard/overview', '/production-plans', '/material-batches',
      '/purchase/orders', '/sales/orders', '/label-qc/tasks']) expect(allows(path)).toBe(true);
    for (const path of ['/users/current', '/smart-bi/analysis/dynamic', '/scheduling/config',
      '/purchase/orders/by-status', '/sales/orders/by-status']) expect(allows(path)).toBe(false);
  });
});
