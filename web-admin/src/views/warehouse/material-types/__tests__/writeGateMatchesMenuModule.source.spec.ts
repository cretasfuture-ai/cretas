import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';

/**
 * 缺陷 46 (2026-09-02, 刘山门实测): `/warehouse/material-types` 在 menuConfig.ts
 * 里挂的是 module: 'warehouse' —— 菜单对仓储角色放行, 用户点得进这个页面。
 * 但页面自己的写门禁 (`const canWrite = computed(() => permissionStore.canWrite(...))`)
 * 绑的是 'production' —— 仓储角色进得了页、新增/编辑/删除按钮却因 `v-if="canWrite"`
 * 全部隐藏, 只剩「供应商」一栏能操作。
 *
 * 这道闸守的是**这一个路由**「菜单宽 / 页面窄」不会再漂: 菜单条目的 module 与页面写门禁
 * 的 module 必须相同。菜单侧从真实 `menuConfig` 导入(不复刻), 页面侧从真实源码里抠出
 * `canWrite` 声明那一行的实参 —— 两边都读**当前代码**, 不是写死的期望值, 所以菜单或页面
 * 任一侧以后再漂移都会被抓到。
 */

const ROUTE_PATH = '/warehouse/material-types';

const PAGE_SOURCE = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'),
  'utf8',
);

function findMenuItem(items: MenuItem[], path: string): MenuItem | undefined {
  for (const item of items) {
    if (item.path === path) return item;
    if (item.children) {
      const found = findMenuItem(item.children, path);
      if (found) return found;
    }
  }
  return undefined;
}

/** 从源码里抠出 `canWrite` computed 声明的 canWrite(...) 实参 —— 页面唯一的写门禁开关。 */
function pageWriteGateModule(source: string): string {
  const match = /const canWrite = computed\(\(\) => permissionStore\.canWrite\('([a-z_]+)'\)\);/.exec(source);
  expect(match, '页面写门禁声明 (`const canWrite = computed(...)`) 找不到了 —— 本闸失去被测对象').not.toBeNull();
  return (match as RegExpExecArray)[1];
}

/** 纯函数抽出来单独测 —— 避免「两边其实读同一个来源」这种恒真式 (measurement-and-wiring B⁴)。 */
function gateMatchesMenuModule(pageModule: string, menuModule: string): boolean {
  return pageModule === menuModule;
}

describe('原料类型字典: 菜单 module 与页面写门禁 module 必须一致', () => {
  it('menuConfig 里存在这条路由', () => {
    const item = findMenuItem(menuConfig, ROUTE_PATH);
    expect(item, `menuConfig 里找不到 ${ROUTE_PATH} —— 本闸失去被测对象`).not.toBeUndefined();
  });

  it('页面 canWrite 门禁的 module 与菜单条目的 module 相同', () => {
    const menuItem = findMenuItem(menuConfig, ROUTE_PATH) as MenuItem;
    const menuModule = menuItem.module;
    const pageModule = pageWriteGateModule(PAGE_SOURCE);

    expect(gateMatchesMenuModule(pageModule, menuModule)).toBe(true);
    // 双向都断言一下, 报错信息里能直接看到两边分别是什么, 不用回去翻 gateMatchesMenuModule。
    expect(pageModule).toBe(menuModule);
  });

  it('⛔ 阴性对照: 两侧 module 不一致的 fixture 必须被判定为不一致', () => {
    // 缺陷 46 修复前的真实状态: 菜单 warehouse / 页面 production —— 这条 fixture
    // 复刻的正是那个坏状态, 证明 gateMatchesMenuModule 真的会在不一致时给 false,
    // 不是一条永远为真的比较。
    expect(gateMatchesMenuModule('production', 'warehouse')).toBe(false);
    expect(gateMatchesMenuModule('warehouse', 'warehouse')).toBe(true);
  });
});
