import { describe, expect, it } from 'vitest';
import { factoryMenuForType, groupedMenuConfig, menuTrail, wholesalerMenuConfig, type MenuItem } from '../menuConfig';
import { getFactoryMenuProfile, getFactoryModuleMenuOptions, isFactoryMenuHidden } from '../factoryMenuVisibility';
import { configurableMenuConfig } from '../menuConfig';

const leaves = (items: readonly MenuItem[]): MenuItem[] => items.flatMap((item) => item.children ? leaves(item.children) : [item]);

describe('explicit wholesale navigation profile', () => {
  it('opts in only the configured factory and leaves the shared factory tree unchanged', () => {
    const before = JSON.stringify(groupedMenuConfig);
    const profile = getFactoryMenuProfile([{ moduleId: 'dashboard', config: { businessNavigationProfile: 'wholesale' } }]);
    expect(factoryMenuForType('FACTORY', profile)).toBe(wholesalerMenuConfig);
    for (const data of [null, [], [{ moduleId: 'sales', config: { businessNavigationProfile: 'wholesale' } }],
      [{ moduleId: 'dashboard', config: { businessNavigationProfile: true } }]]) {
      expect(factoryMenuForType('FACTORY', getFactoryMenuProfile(data))).toBe(groupedMenuConfig);
    }
    expect(JSON.stringify(groupedMenuConfig)).toBe(before);
    expect(menuTrail(groupedMenuConfig, '/sales/customers')?.[0].title).toBe('主数据');
    expect(factoryMenuForType('LOGISTICS', profile)).toBe(factoryMenuForType('LOGISTICS'));
  });

  it('places each existing party directory once in its business module, preserving every guard', () => {
    for (const [path, parent] of [['/procurement/suppliers', '采购管理'], ['/sales/customers', '销售管理']]) {
      const original = leaves(groupedMenuConfig).find((item) => item.path === path)!;
      const moved = leaves(wholesalerMenuConfig).filter((item) => item.path === path);
      expect(moved).toHaveLength(1);
      expect(moved[0]).toEqual(original);
      expect(menuTrail(wholesalerMenuConfig, path)?.map((item) => item.title)).toEqual([parent, original.title]);
    }
    expect(leaves(wholesalerMenuConfig).map((item) => item.path).sort()).toEqual(leaves(groupedMenuConfig).map((item) => item.path).sort());
    expect(menuTrail(wholesalerMenuConfig, '/system/products')?.map((item) => item.title)).toEqual(['基础资料', 'SKU 管理']);
  });

  it('lets platform configure factory-only weighing without hiding the SKU leaf or its parent', () => {
    const options = getFactoryModuleMenuOptions(configurableMenuConfig, 'production');
    expect(options.map((option) => option.path)).toContain('/production/weigh');
    const hidden = options.map((option) => option.path).filter((path) => path !== '/system/products');
    expect(isFactoryMenuHidden('/production/weigh', hidden)).toBe(true);
    expect(isFactoryMenuHidden('/system/products', hidden)).toBe(false);
    expect(isFactoryMenuHidden('/production', hidden)).toBe(false);
  });
});
