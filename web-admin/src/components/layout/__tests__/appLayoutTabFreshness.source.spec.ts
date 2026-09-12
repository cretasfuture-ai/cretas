import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 接线闸 —— 与隔壁 appLayoutCacheKey.source.spec.ts 同一个理由。
 *
 * `workspace.invalidateModules` 的单测全绿, 只证明这个函数自己对: 给它一堆
 * **带 module 的标签**, 它能挑对该失效的那些。它 ⛔ 不证明产品里的标签真的带着 module。
 *
 * 而 module 的唯一来源就是 AppLayout 这一处 —— 它把路由的 `meta.module` 传进 openRoute。
 * 这一行要是被删掉/写错, 每个标签的 module 都是空, invalidateModules 一个都匹配不到,
 * owner 报的 bug 原样复发, 而 store 的单测**照样全绿**。本仓称之为「机制在, 没接上」。
 *
 * ⚠️ 扫之前先剥注释: 本文件和 AppLayout 的注释里都会原样提到 `meta.module` / `openRoute`,
 *    不剥就会量到「我自己写的说明」而不是代码 (本仓记过这个坑)。
 */
const here = dirname(fileURLToPath(import.meta.url));
const appLayout = readFileSync(resolve(here, '../AppLayout.vue'), 'utf8');

/** 剥掉 HTML 注释 / 块注释 / 行注释 —— 闸量的是代码, ⛔ 不是文档。 */
const code = appLayout
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

/** 前提断言: 抓不到就抛。抓不到时后面每一条都会「因为没东西可查」而绿, 那是假绿。 */
function openRouteCall(): string {
  const matched = code.match(/workspace\.openRoute\(\{[\s\S]*?\}\)/);
  expect(matched, 'AppLayout 里找不到 workspace.openRoute({...}) —— 闸自己瞎了').not.toBeNull();
  return matched![0];
}

describe('AppLayout 把路由的业务域接到了标签台账上', () => {
  it('阳性对照: 读到的确实是 AppLayout, 且它调了 openRoute', () => {
    expect(code).toContain('workspace.openRoute');
    expect(code).toContain('<WorkspaceRouteCache');
  });

  it('openRoute 收到 route.meta.module —— 失效机制全靠它认标签', () => {
    const call = openRouteCall();
    expect(call).toContain('module:');
    expect(call).toMatch(/module:\s*String\(route\.meta\.module/);
  });

  it('module 与 title 取自同一份 route.meta, ⛔ 不是另手写一份路径→业务域的映射', () => {
    // 反例: 一旦有人在 AppLayout 里按路径前缀现场判业务域, 就出现了第二份真相 (形态 D)。
    expect(code).not.toMatch(/route\.path\.startsWith\(['"]\/(finance|warehouse|procurement)/);
    expect(openRouteCall()).toContain('route.meta.title');
  });

  it('store 暴露了 invalidateModules (键名写错时这条会红)', async () => {
    const mod = await import('@/store/modules/workspace');
    const { createPinia, setActivePinia } = await import('pinia');
    setActivePinia(createPinia());
    const store = mod.useWorkspaceStore();
    expect(typeof store.invalidateModules).toBe('function');
  });
});
