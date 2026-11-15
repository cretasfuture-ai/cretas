/**
 * Nuxt UI 全局中文 locale (app 级, 一处覆盖所有 Nuxt UI 组件)
 *
 * ## 缺陷
 * 本仓没有用 `<UApp>` 包根组件, Nuxt UI 的 `useLocale()` 因此回落到内置英文:
 * `USelectMenu` 搜索框占位显示「Search…」、分页 aria-label 是 Next/Prev、
 * 空状态是「No data」、弹层关闭按钮 aria-label 是 Close (#1746 并排截图发现)。
 *
 * ## 修法
 * Nuxt UI 4 的 locale 通过注入键 `Symbol.for('nuxt-ui.locale-context')` 传递 (`<UApp :locale>` 也只是
 * provide 这个键)。在 app 级 provide 自带的 `zh_cn`, 所有组件 (含 teleport 出去的弹层) 一次变中文,
 * 不逐页写 placeholder 打补丁, 也不改根组件结构。
 *
 * ⛔ 不要在页面里再给 USelectMenu 写 search-input 占位去「修」英文 —— 那是补丁, 根因在这里。
 * 守卫: __tests__/nuxtUiLocale.spec.ts (移除本插件, 测试就红)。
 */
import type { App } from 'vue';
import { ref } from 'vue';
import { zh_cn } from './nuxtUiLocaleSource.mjs';

/** 与 @nuxt/ui 的 localeContextInjectionKey 同一个 Symbol.for 键 (runtime 内部导出, 不依赖深路径)。 */
export const NUXT_UI_LOCALE_KEY = Symbol.for('nuxt-ui.locale-context');

export default {
  install(app: App) {
    app.provide(NUXT_UI_LOCALE_KEY, ref(zh_cn));
  },
};
