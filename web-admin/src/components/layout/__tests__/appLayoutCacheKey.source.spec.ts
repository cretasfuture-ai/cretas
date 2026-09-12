import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 接线闸。
 *
 * workspace.cacheKeyFor 单测全绿, 只证明这个函数自己对 —— 不证明产品里有人调它。
 * 缓存键实际生效的入口是 AppLayout 传给 WorkspaceRouteCache 的 active-key/cache-keys。
 * 那一行要是绕开 cacheKeyFor 直接用 workspaceRouteKey, 关闭标签就又不作废缓存了,
 * 而 store 的单测<b>照样全绿</b> —— 本仓称之为「机制在, 没接上」。
 */
const here = dirname(fileURLToPath(import.meta.url));
const appLayout = readFileSync(resolve(here, '../AppLayout.vue'), 'utf8');

describe('AppLayout 真的把标签生命周期接到了 keep-alive 上', () => {
  it('阳性对照: AppLayout 使用真实标签缓存组件', () => {
    expect(appLayout).toContain('<WorkspaceRouteCache');
    expect(appLayout).toContain('workspaceRouteKey');
  });

  it('活标签与当前路由使用同一套 cacheKeyFor，并有工厂缓存边界', () => {
    const componentTag = appLayout.match(/<WorkspaceRouteCache[\s\S]*?\/>/);
    expect(componentTag, 'AppLayout 里找不到 WorkspaceRouteCache').not.toBeNull();
    const tag = componentTag![0];
    expect(tag).toContain(':key=');
    expect(tag).toContain('cacheKeyFor');
    expect(tag).toContain(':active-key=');
    expect(tag).toContain(':cache-keys="workspaceCacheKeys"');
    expect(tag).toContain('authStore.factoryId');
    expect(appLayout).toContain('workspace.tabs.map(tab => workspace.cacheKeyFor(tab.key))');
  });

  it('store 暴露了 cacheKeyFor (键名写错时这条会红)', async () => {
    const mod = await import('@/store/modules/workspace');
    const { createPinia, setActivePinia } = await import('pinia');
    setActivePinia(createPinia());
    const store = mod.useWorkspaceStore();
    expect(typeof store.cacheKeyFor).toBe('function');
  });
});
