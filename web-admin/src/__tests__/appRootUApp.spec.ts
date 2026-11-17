/**
 * 根组件必须包 <UApp>: UTooltip 底层是 reka-ui 的 TooltipRoot, 缺 TooltipProvider 时在 setup 里抛错,
 * 整页 (甚至整个页签) 渲染失败。
 * vitest 没装 Nuxt UI 插件 (#imports 别名不可用), 不能直接挂真实 UApp/UTooltip, 所以:
 *  1. 用真实的 reka TooltipRoot 证明「未包 provider 会抛」(仪器能红);
 *  2. 用真实 App.vue, 全局组件 UApp 换成与 Nuxt UI App.vue 同构的 TooltipProvider 包装,
 *     证明 App.vue 里页面的 tooltip 不再抛 (App.vue 不包 UApp 则这条红);
 *  3. 钉住 node_modules 里真实 UApp 确实提供 TooltipProvider (漂了就红)。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui';
import App from '../App.vue';

const Page = defineComponent({
  name: 'PageWithTooltip',
  setup: () => () => h(TooltipRoot, null, { default: () => h(TooltipTrigger, { id: 'tt-trigger' }, { default: () => '悬停' }) }),
});

const UAppLike = defineComponent({
  name: 'UApp',
  setup: (_p, { slots }) => () => h(TooltipProvider, null, { default: () => slots.default?.() }),
});

describe('根组件 UApp', () => {
  it('对照: 没有 provider 时 tooltip 抛 TooltipProvider 缺失', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(() => mount(Page)).toThrow(/TooltipProvider/i);
  });

  it('真实 App.vue 下的页面里 tooltip 正常渲染', () => {
    const w = mount(App, { global: { components: { UApp: UAppLike }, stubs: { 'router-view': Page } } });
    expect(w.find('#tt-trigger').exists()).toBe(true);
  });

  it('Nuxt UI 的 UApp 确实提供 TooltipProvider', () => {
    const src = readFileSync(resolve(__dirname, '../../node_modules/@nuxt/ui/dist/runtime/components/App.vue'), 'utf-8');
    expect(src).toContain('<TooltipProvider');
  });
});
