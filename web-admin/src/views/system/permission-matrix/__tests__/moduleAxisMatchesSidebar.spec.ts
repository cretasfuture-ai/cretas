/**
 * 契约: 权限矩阵页的模块轴 === 侧边栏**实际渲染用的**那份清单。
 *
 * owner 2026-09-06 裁定轴取侧边栏那一份。这条闸的作用是: 有人以后往
 * `menuConfig` 里加一个新 `module:`, 而权限矩阵页没跟上, 那一列就会静默缺席 ——
 * 而「某个模块没有列」与「所有角色在那个模块上都没权限」在页面上长得一模一样。
 *
 * ⚠️ 阴性对照在下面第 2 条: 轴不是「把 menuConfig 里的字符串抄下来」——
 * 抄一份就成了全仓第 8 份清单。判据写成「独立地从 menuConfig 遍历出来的集合
 * 与 sidebarModuleAxis() 相等」, 一个写死清单的实现在加菜单项时必红。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { menuConfig, financeManagerMenu, type MenuItem } from '@/components/layout/menuConfig';
import { sidebarModuleAxis, offAxisModules, axisModulesMissingFromData } from '../moduleAxis';

function walk(items: MenuItem[], into: Set<string>): void {
  for (const item of items) {
    if (item.module) into.add(String(item.module));
    if (item.children?.length) walk(item.children, into);
  }
}

describe('权限矩阵模块轴 = 侧边栏清单', () => {
  it('轴与 menuConfig(+财务主管菜单) 的 module 取值集合逐字相等', () => {
    const expected = new Set<string>();
    walk(menuConfig, expected);
    walk(financeManagerMenu, expected);

    // 前提断言: 遍历真的抓到了东西, 否则「两个空集相等」是恒真式
    expect(expected.size).toBeGreaterThan(10);
    expect(sidebarModuleAxis()).toEqual([...expected].sort());
  });

  it('轴里必须含 system —— 本页自己的门禁模块, 否则页面看不到自己那一列', () => {
    expect(sidebarModuleAxis()).toContain('system');
  });

  /**
   * 🔴 现状钉子 (2026-09-06 实测): 侧边栏用 `module: 'inventory'` 挂着调拨单,
   * 而前端硬编码兜底矩阵 `ModulePermissions` 里**没有 inventory 这个键**。
   * 这条断言把「轴上有 inventory」钉住 —— 页面正是靠它把这件事显示出来。
   */
  it('inventory 在轴上 (侧边栏有入口), 而 operations 不在 (有 L1 行却没有菜单项)', () => {
    const axis = sidebarModuleAxis();
    expect(axis).toContain('inventory');
    expect(axis).not.toContain('operations');
  });

  it('offAxisModules 把后端全集里轴外的模块点名, 不静默丢掉', () => {
    const axis = sidebarModuleAxis();
    const all = [...axis, 'operations', 'work_report', 'report'];
    expect(offAxisModules(all, axis)).toEqual(['operations', 'report', 'work_report']);
    // 阴性对照: 全集与轴相同 ⇒ 一个都不该点名
    expect(offAxisModules(axis, axis)).toEqual([]);
  });

  it('axisModulesMissingFromData 找出「侧边栏有入口而三层数据一行都没有」的模块', () => {
    expect(axisModulesMissingFromData(['a', 'b'], ['a'])).toEqual(['b']);
    // 阴性对照
    expect(axisModulesMissingFromData(['a', 'b'], ['a', 'b', 'c'])).toEqual([]);
  });

  it('⛔ 页面目录里不许出现写死的模块清单字面量 (那就是第 8 份)', () => {
    const src = readFileSync(
      resolve(__dirname, '../moduleAxis.ts'),
      'utf-8',
    );
    // 判据: 轴文件里不能出现「连续 3 个已知模块名的字符串字面量」这种手抄清单形状。
    const literals = src.match(/'(dashboard|production|warehouse|quality|procurement|sales|hr|equipment|finance|analytics|scheduling|restaurant|rd|inventory|report|work_report|operations)'/g) || [];
    expect(literals, `moduleAxis.ts 里出现了模块名字面量: ${literals.join(',')}`).toHaveLength(0);
  });
});
