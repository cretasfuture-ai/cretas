/**
 * 供应商三个子组件的弹层外观：UModal / USlideover 的 `ui` 覆盖，对齐组件库
 * （Cretas-组件库-NuxtUI-20261114 的 .u-modal / .u-slideover，本机 Playwright 量过 computed 值）：
 *   标题 16px / 700；弹窗头 14px 18px、体 16px 18px、脚 12px 18px；抽屉头 14px 16px、体 16px。
 * Nuxt UI 默认是 16px 24px / 24px，标题继承到 21px，和组件库差一档。
 *
 * ⚠️ Tailwind 只认源码里出现的完整类名字符串，所以宽度由调用处传字面量（modalUi('max-w-[520px]')），
 *    ⛔ 不要在这里拼 `max-w-[${n}px]`。
 * ⚠️ 默认类带 `sm:` 变体（sm:px-6），tailwind-merge 把 `px-*` 与 `sm:px-*` 当两条，所以两条都要覆盖。
 */
const TITLE = 'text-[16px] font-bold';

export function modalUi(widthClass: string) {
  return {
    title: TITLE,
    header: 'min-h-0 px-[18px] py-[14px] sm:px-[18px] sm:py-[14px]',
    body: 'px-[18px] py-[16px] sm:px-[18px] sm:py-[16px]',
    footer: 'justify-end px-[18px] py-[12px] sm:px-[18px] sm:py-[12px]',
    content: widthClass,
    description: 'sr-only',
  };
}

/** 抽屉：描述槽放的是编号 + 状态徽标（可见），所以不 sr-only。 */
export const slideUi = {
  title: TITLE,
  header: 'min-h-0 px-[16px] py-[14px] sm:px-[16px] sm:py-[14px]',
  body: 'px-[16px] py-[16px] sm:px-[16px] sm:py-[16px]',
  content: 'supplier-slide',
};

/** 提示条：组件库 .u-alert = 12px 字、12px 16px 内边距（Nuxt UI 默认 14px / 16px）。 */
export const alertUi = { root: 'px-4 py-3', title: 'text-xs font-normal', description: 'text-xs' };
