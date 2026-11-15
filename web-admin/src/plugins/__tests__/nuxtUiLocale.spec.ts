import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createApp, defineComponent, h, inject } from 'vue';
import { describe, expect, it } from 'vitest';
import { en, zh_cn } from '../nuxtUiLocaleSource.mjs';
import nuxtUiLocale, { NUXT_UI_LOCALE_KEY } from '../nuxtUiLocale';

/**
 * Nuxt UI 全局 locale 守卫: 本仓不用 <UApp>, 不装这个 plugin 所有内置文案回落英文
 * (USelectMenu 搜索框占位 "Search…" 等)。
 *
 * Nuxt UI 的 useLocale() 就是 inject(Symbol.for('nuxt-ui.locale-context'), en) 再按路径取文案;
 * 这里在真实 app 里照同一口径读 (不直接 import composables: 它会拉 Nuxt 的 #imports 别名), 另有一条
 * 钉住 node_modules 里 useLocale 确实用这个键, 键漂了测试会红。
 */
type LocaleLike = { messages: Record<string, unknown> };

function readMessages(withPlugin: boolean) {
  let captured: { t: (path: string) => string } | undefined;
  const Probe = defineComponent({
    setup() {
      const loc = inject<{ value: LocaleLike } | LocaleLike>(NUXT_UI_LOCALE_KEY, en as unknown as LocaleLike);
      const messages = ('value' in (loc as object) ? (loc as { value: LocaleLike }).value : (loc as LocaleLike)).messages;
      captured = {
        t: (path: string) => path.split('.').reduce<unknown>((o, k) => (o as Record<string, unknown>)[k], messages) as string,
      };
      return () => h('div');
    },
  });
  const app = createApp(Probe);
  if (withPlugin) app.use(nuxtUiLocale);
  app.mount(document.createElement('div'));
  return captured!;
}

const KEYS = [
  'selectMenu.search', // USelectMenu 搜索框占位 (本次缺陷)
  'selectMenu.noMatch',
  'selectMenu.noData',
  'inputMenu.noMatch',
  'pagination.next',
  'pagination.prev',
  'table.noData',
  'modal.close',
  'slideover.close',
  'toast.close',
  'alert.close',
];

describe('Nuxt UI 中文 locale', () => {
  it('阴性对照: 不装 plugin 时回落英文 (证明仪器能看见缺陷)', () => {
    const locale = readMessages(false);
    expect(locale.t('selectMenu.search')).toBe(en.messages.selectMenu.search);
    expect(locale.t('selectMenu.search')).toMatch(/Search/);
  });

  it('装了 plugin: 内置文案全部是中文, 不含英文字母词', () => {
    const locale = readMessages(true);
    for (const key of KEYS) {
      const text = locale.t(key);
      expect(text, key).toMatch(/[一-龥]/);
      expect(text, key).not.toMatch(/[A-Za-z]{3,}/);
    }
    expect(locale.t('selectMenu.search')).toBe('搜索…');
  });

  it('Nuxt UI 的 useLocale 确实读这个注入键 (键漂了这里红)', () => {
    const src = readFileSync(
      resolve(process.cwd(), 'node_modules/@nuxt/ui/dist/runtime/composables/useLocale.js'),
      'utf8',
    );
    expect(src).toContain('Symbol.for("nuxt-ui.locale-context")');
  });

  it('注入的就是 zh_cn', () => {
    expect(zh_cn.code).toBe('zh-CN');
    expect(NUXT_UI_LOCALE_KEY).toBe(Symbol.for('nuxt-ui.locale-context'));
  });

  it('接线: main.ts 真的 app.use 了它 (只 import 不装等于没修)', () => {
    const main = readFileSync(resolve(process.cwd(), 'src/main.ts'), 'utf8');
    expect(main).toMatch(/import\(['"]\.\/plugins\/nuxtUiLocale['"]\)/);
    expect(main).toMatch(/app\.use\(\s*nuxtUiLocale\.default\s*\)/);
  });
});
