import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 接线闸。
 *
 * workspace.cacheKeyFor 单测全绿, 只证明这个函数自己对 —— 不证明产品里有人调它。
 * 缓存键实际生效的地方只有一处: AppLayout 里 <keep-alive> 内层 <component> 的 :key。
 * 那一行要是绕开 cacheKeyFor 直接用 workspaceRouteKey, 关闭标签就又不作废缓存了,
 * 而 store 的单测<b>照样全绿</b> —— 本仓称之为「机制在, 没接上」。
 */
const here = dirname(fileURLToPath(import.meta.url));
const appLayout = readFileSync(resolve(here, '../AppLayout.vue'), 'utf8');

describe('AppLayout 真的把标签生命周期接到了 keep-alive 上', () => {
  it('阳性对照: 读到的确实是 AppLayout 且里面有 keep-alive', () => {
    expect(appLayout).toContain('<keep-alive');
    expect(appLayout).toContain('workspaceRouteKey');
  });

  it('<component> 的 :key 经过 cacheKeyFor, 而不是裸 workspaceRouteKey', () => {
    // 取 <component ... /> 那一段(含换行)
    const componentTag = appLayout.match(/<component[\s\S]*?\/>/);
    expect(componentTag, 'AppLayout 里找不到 <component> —— 闸自己瞎了').not.toBeNull();
    const tag = componentTag![0];
    expect(tag).toContain(':key=');
    expect(tag).toContain('cacheKeyFor');
  });

  it('store 暴露了 cacheKeyFor (键名写错时这条会红)', async () => {
    const mod = await import('@/store/modules/workspace');
    const { createPinia, setActivePinia } = await import('pinia');
    setActivePinia(createPinia());
    const store = mod.useWorkspaceStore();
    expect(typeof store.cacheKeyFor).toBe('function');
  });
});
