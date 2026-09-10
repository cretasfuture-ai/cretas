/**
 * 鼠标停在横向滚动条上时, 滚轮横向滚动 (app 级, 一处覆盖全仓)
 *
 * ## 客户原话 (2026-07-21 复测)
 * 「任务框很长时要先滑到页面底部才能找到横向滚动条, 想看上面的数据就得
 *   『下滑到底—横移—下滑到底—横移』不断重复, 显著降低效率, 还会截图截不全。
 *   如果鼠标停在横向滚动条上时滑动滚轮能横向滚动, 效率将大大提高。」
 *
 * 本文件只做客户点名的那一条 —— **指针停在横向滚动条上时, 滚轮横向滚动**。
 * ⛔ 不做「表格区域内任意位置滚轮横向滚动」: 那会抢掉整页的纵向滚动。
 *
 * ## 为什么 Element Plus 自带的那半不够
 * `el-table` 确实绑了 EP 的 `Mousewheel` 指令, 但**只绑在表头/表尾**, 而且判据是
 * `Math.abs(pixelX) >= Math.abs(pixelY)` —— 即**手势本身已经是横向的**才转发
 * (触控板横扫 / shift+滚轮)。普通鼠标滚轮只有 `pixelY`, 一律不管:
 *
 * ```js
 * // node_modules/element-plus/es/components/table/src/table/style-helper.mjs:73
 * const handleHeaderFooterMousewheel = (_event, data) => {
 *   const { pixelX, pixelY } = data;
 *   if (Math.abs(pixelX) >= Math.abs(pixelY)) {
 *     table.refs.bodyWrapper.scrollLeft += data.pixelX / 5;
 *   }
 * };
 * ```
 * 且它注册时是 `{ passive: true }`, 无法 `preventDefault()` —— 就算改判据,
 * 页面照样会跟着一起纵向滚。所以另起一个 `{ passive: false }` 的监听。
 *
 * ## 为什么放在 app 级而不是逐处改
 * 全仓 322 个 `.vue` 用 `<el-table>`, 其中 155 个带 fixed 列 —— 只要列宽超出视口
 * 就会出现横向滚动条。逐页加指令既漏又难维护。与同目录 `ariaDisabledSync.ts`
 * (436 处 el-input-number) 同一取舍。
 *
 * ## 两种载体, 判法不同
 * 1. **Element Plus 的假滚动条**（`el-table` / `el-scrollbar` 默认 `native=false`）:
 *    `.el-scrollbar__bar.is-horizontal` 是一个**真实 DOM 元素**, 命中它即可;
 *    真正滚动的是它的兄弟 `.el-scrollbar__wrap`。
 * 2. **原生滚动条**（`overflow-x: auto` 的普通 div, 例如 `.order-items-scroll`）:
 *    滚动条**不在 DOM 里**, 只能按几何判断 —— 指针的 y 落在
 *    「内容盒底边 ~ 内边距盒底边」这条带子里。带子高度 =
 *    `offsetHeight - clientHeight - 上下边框`。
 *
 * ⚠️ 判据 2 依赖「指针在原生滚动条上时 wheel 事件仍派发给该元素」。
 * 这一条**不是从文档推出来的, 是在真实 Chrome 里量过的** ——
 * 见 `__tests__/horizontalScrollbarWheel.spec.ts` 顶部记录的实测结果。
 */
import type { App } from 'vue';

/** EP 假滚动条里那条横向的 */
export const EP_HORIZONTAL_BAR_SELECTOR = '.el-scrollbar__bar.is-horizontal';
/** EP 假滚动条真正在滚的那个容器 */
export const EP_WRAP_SELECTOR = '.el-scrollbar__wrap';

/** `deltaMode` 为行/页时的换算基准 —— 与主流实现取值一致, 只用于兜底。 */
const PIXELS_PER_LINE = 16;
const PIXELS_PER_PAGE = 400;

function toPixels(value: number, deltaMode: number): number {
  if (deltaMode === 1) return value * PIXELS_PER_LINE;
  if (deltaMode === 2) return value * PIXELS_PER_PAGE;
  return value;
}

/**
 * 这一次滚轮应该把 scrollLeft 推多少。
 *
 * 横向分量占优（触控板横扫 / shift+滚轮）就用横向分量, 否则**把纵向分量当横向用**
 * —— 后者正是客户要的那件事: 普通鼠标只有纵向分量。
 */
export function horizontalWheelDelta(event: Pick<WheelEvent, 'deltaX' | 'deltaY' | 'deltaMode'>): number {
  const x = toPixels(event.deltaX, event.deltaMode);
  const y = toPixels(event.deltaY, event.deltaMode);
  return Math.abs(x) >= Math.abs(y) ? x : y;
}

/** 能不能横向滚 —— 内容比可视区宽才算。 */
export function canScrollHorizontally(el: HTMLElement): boolean {
  return el.scrollWidth > el.clientWidth;
}

/**
 * 指针是否落在 `el` 的**原生**横向滚动条带子里。
 *
 * ⚠️ 用 `offsetHeight - clientHeight` 求带子高度时**必须减掉上下边框** ——
 * 那个差值里同时含边框。不减的话, 一个有 1px 上下边框、根本没有横向滚动条的
 * 元素会算出 2px 的假带子, 于是贴着底边的普通滚轮会被误判成「在滚动条上」。
 */
export function isPointerOverNativeHorizontalScrollbar(el: HTMLElement, clientY: number): boolean {
  const view = el.ownerDocument?.defaultView;
  if (!view) return false;
  const style = view.getComputedStyle(el);
  const borderTop = Number.parseFloat(style.borderTopWidth) || 0;
  const borderBottom = Number.parseFloat(style.borderBottomWidth) || 0;
  const band = el.offsetHeight - el.clientHeight - borderTop - borderBottom;
  if (band <= 0) return false;
  const rect = el.getBoundingClientRect();
  const bandBottom = rect.bottom - borderBottom;
  return clientY >= bandBottom - band && clientY <= bandBottom;
}

/**
 * 指针停在谁的横向滚动条上 —— 返回那个真正要改 `scrollLeft` 的元素。
 * 找不到返回 `null`（绝大多数滚轮都走这一支, 必须极快返回）。
 */
export function resolveHorizontalScrollbarTarget(event: WheelEvent): HTMLElement | null {
  const target = event.target;
  if (!(target instanceof Element)) return null;

  // (1) EP 假滚动条: 命中的是一个真实元素, 直接找它的 wrap
  const bar = target.closest(EP_HORIZONTAL_BAR_SELECTOR);
  if (bar) {
    const wrap = bar.parentElement?.querySelector(`:scope > ${EP_WRAP_SELECTOR}`);
    if (wrap instanceof HTMLElement && canScrollHorizontally(wrap)) return wrap;
    // 命中了假滚动条但它滚不动 —— 不要再往上找, 否则会把外层容器滚起来
    return null;
  }

  // (2) 原生滚动条: 沿祖先链找第一个「指针落在它的滚动条带子里」的可横滚元素
  for (let node: Element | null = target; node; node = node.parentElement) {
    if (!(node instanceof HTMLElement)) continue;
    if (!canScrollHorizontally(node)) continue;
    if (isPointerOverNativeHorizontalScrollbar(node, event.clientY)) return node;
  }
  return null;
}

/**
 * 处理一次滚轮。返回**是否真的横向滚动了** —— 单测据此判断, 不看副作用。
 *
 * 只有真的滚动了才 `preventDefault()`: 已经顶到左右两端时放行, 让页面照常纵向滚,
 * 否则指针停在滚动条上会把用户卡住。
 */
export function handleHorizontalScrollbarWheel(event: WheelEvent): boolean {
  // ctrl+滚轮是浏览器缩放, 不抢
  if (event.ctrlKey || event.defaultPrevented) return false;
  const scroller = resolveHorizontalScrollbarTarget(event);
  if (!scroller) return false;
  const delta = horizontalWheelDelta(event);
  if (!delta) return false;
  const before = scroller.scrollLeft;
  scroller.scrollLeft = before + delta;
  const moved = scroller.scrollLeft !== before;
  if (moved) event.preventDefault();
  return moved;
}

/**
 * 挂到某个事件源上, 返回卸载函数。
 *
 * `passive: false` 是必须的 —— Chrome 对 document 上的 wheel 默认 passive,
 * 那样 `preventDefault()` 会被忽略(且控制台报警), 表现为「横向滚了, 但页面也跟着纵向滚」。
 */
export function installHorizontalScrollbarWheel(source: EventTarget): () => void {
  const onWheel = (event: Event): void => {
    handleHorizontalScrollbarWheel(event as WheelEvent);
  };
  source.addEventListener('wheel', onWheel, { passive: false });
  return () => source.removeEventListener('wheel', onWheel);
}

/** A separate native scrollbar above the table, sharing EP's real scroll offset. */
export function installTableTopScrollbar(table: HTMLElement): () => void {
  const wrap = table.querySelector<HTMLElement>('.el-table__body-wrapper .el-scrollbar__wrap');
  if (!wrap || !table.parentElement) return () => {};
  const bar = document.createElement('div');
  bar.className = 'cretas-table-top-scrollbar';
  bar.setAttribute('role', 'region');
  bar.setAttribute('aria-label', '表格顶部横向滚动');
  bar.tabIndex = 0;
  const track = document.createElement('div');
  track.style.height = '1px';
  bar.append(track);
  table.before(bar);
  const update = (): void => {
    bar.style.width = `${table.clientWidth}px`;
    track.style.width = `${wrap.scrollWidth}px`;
    bar.hidden = wrap.scrollWidth <= wrap.clientWidth || table.clientWidth === 0;
    bar.scrollLeft = wrap.scrollLeft;
  };
  const fromTop = (): void => { if (wrap.scrollLeft !== bar.scrollLeft) wrap.scrollLeft = bar.scrollLeft; };
  const fromBody = (): void => { if (bar.scrollLeft !== wrap.scrollLeft) bar.scrollLeft = wrap.scrollLeft; };
  const wheel = (event: WheelEvent): void => {
    if (event.ctrlKey) return;
    const before = bar.scrollLeft;
    bar.scrollLeft += horizontalWheelDelta(event);
    if (bar.scrollLeft !== before) { fromTop(); event.preventDefault(); }
  };
  bar.addEventListener('scroll', fromTop);
  bar.addEventListener('wheel', wheel, { passive: false });
  wrap.addEventListener('scroll', fromBody);
  const observer = new ResizeObserver(update);
  observer.observe(table);
  observer.observe(wrap);
  const body = wrap.querySelector('.el-table__body');
  if (body) observer.observe(body);
  update();
  return () => {
    observer.disconnect();
    bar.removeEventListener('scroll', fromTop);
    bar.removeEventListener('wheel', wheel);
    wrap.removeEventListener('scroll', fromBody);
    bar.remove();
  };
}

export default {
  install(app: App): void {
    if (typeof document === 'undefined') return;
    installHorizontalScrollbarWheel(document);
    const cleanups = new WeakMap<object, () => void>();
    app.mixin({
      mounted() {
        if (this.$options.name === 'ElTable' && this.$el instanceof HTMLElement) {
          cleanups.set(this, installTableTopScrollbar(this.$el));
        }
      },
      activated() {
        if (!cleanups.has(this) && this.$options.name === 'ElTable' && this.$el instanceof HTMLElement) {
          cleanups.set(this, installTableTopScrollbar(this.$el));
        }
      },
      deactivated() { cleanups.get(this)?.(); cleanups.delete(this); },
      beforeUnmount() { cleanups.get(this)?.(); cleanups.delete(this); },
    });
  },
};
