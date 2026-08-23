/**
 * 「一键返回原页面 + 回到原来的上下滑动位置」
 *
 * ## 客户原话 (2026-07-21 复测)
 * 「通过单据追踪进入到其他业务模块后, 最好可以一键返回原来的打开单据追踪功能的页面、
 *   定位上下滑动的位置。实际使用中很可能需要『快速查找—返回—查找』直到目的达成,
 *   如果无法顺利衔接会降低效率。」
 *
 * ## 现状 (2026-08-19 核实)
 * - 「一键返回」这半**机制早就有**: `ReturnBanner.vue` 读 URL 上的 `_returnTo`,
 *   挂在 `AppLayout.vue` 全局。缺的只是**跳转方没把 `_returnTo` 带上**。
 * - 「定位滑动位置」这半**全仓都没有**: 路由没有 `scrollBehavior`, 唯一一处
 *   滚动恢复是 `views/sales/customers/list.vue` 自己用 `?scrollY=` 做的,
 *   而且它读的是 `window.scrollY` —— **在这个应用里是错的轴**。
 *
 * ## ⚠️ 滚的不是 window, 是 `.app-content`
 * `AppLayout.vue` 的 `<main class="app-content">` 才是滚动容器 (`overflow-y: auto`),
 * `window.scrollY` 在绝大多数页面上恒为 0。抄 customers/list.vue 那份会静默失效。
 *
 * ## ⚠️ 与「标签缓存跟随标签生命周期」(44654b3488) 的兼容
 * `workspaceRouteKey` 只认 `path` 和 `_task`, **其余 query 一律不进标签键** ——
 * 所以这里加的 `_returnScroll` 不会把标签劈成两个, 也不会让 keep-alive 换键重建。
 * 两种回来的情形都要能work:
 *   - 标签还开着 → keep-alive 复用实例, DOM 还在, 设一次 scrollTop 就到位;
 *   - 标签被关过 → 换了缓存键, 组件重新 mount 并重新取数, **内容要等一会儿才撑开**,
 *     所以 {@link restoreAppScroll} 是有界重试的, 不是设一次就算完。
 */

/** ReturnBanner 认的那个 query key —— 与 `components/layout/ReturnBanner.vue` 对齐。 */
export const RETURN_QUERY_KEY = '_returnTo';
/** 回到原页面时要恢复的滚动位置。 */
export const RETURN_SCROLL_QUERY_KEY = '_returnScroll';
/** 真正的滚动容器 —— ⛔ 不是 window。 */
export const APP_SCROLL_SELECTOR = '.app-content';

type QueryLike = Record<string, unknown>;

function splitPathAndSearch(fullPath: string): [string, string] {
  const at = fullPath.indexOf('?');
  if (at < 0) return [fullPath, ''];
  return [fullPath.slice(0, at), fullPath.slice(at + 1)];
}

/**
 * 把「从哪儿走的 + 当时滚到哪儿」拼成 `_returnTo` 的值。
 *
 * 幂等: 原路径上已有的 `_returnScroll` 会先删掉再写, 免得来回几次叠成
 * `?_returnScroll=10&_returnScroll=200`(URLSearchParams 允许重复键)。
 */
export function buildReturnTo(originFullPath: string, scrollTop = 0): string {
  const [path, search] = splitPathAndSearch(originFullPath);
  const params = new URLSearchParams(search);
  params.delete(RETURN_SCROLL_QUERY_KEY);
  const rounded = Math.round(scrollTop);
  if (rounded > 0) params.set(RETURN_SCROLL_QUERY_KEY, String(rounded));
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

/** 跳转时要并进目标 query 的那一片 —— 调用方 `{ ...target.query, ...returnContextQuery(...) }`。 */
export function returnContextQuery(originFullPath: string, scrollTop = 0): Record<string, string> {
  return { [RETURN_QUERY_KEY]: buildReturnTo(originFullPath, scrollTop) };
}

/**
 * 从 route.query 里读出要恢复的滚动位置。
 *
 * ⛔ 读不出来一律返回 0(= 不恢复), 但**不要**把「负数 / NaN / 数组」也翻译成 0 之外的东西 ——
 * 这里的 0 是「没有要求恢复」这个合法状态, 不是一个被兜底掉的真实读数。
 */
export function readReturnScroll(query: QueryLike | null | undefined): number {
  const raw = query?.[RETURN_SCROLL_QUERY_KEY];
  const text = Array.isArray(raw) ? raw[raw.length - 1] : raw;
  if (typeof text !== 'string' || !/^\d+$/.test(text)) return 0;
  const value = Number.parseInt(text, 10);
  return Number.isFinite(value) && value > 0 ? value : 0;
}

/** 当前滚到哪儿了。找不到容器返回 0 —— 那种情况下本来也没什么可恢复的。 */
export function currentAppScrollTop(doc: Document | null | undefined = typeof document === 'undefined' ? null : document): number {
  const el = doc?.querySelector(APP_SCROLL_SELECTOR);
  return el instanceof HTMLElement ? el.scrollTop : 0;
}

export interface RestoreAppScrollOptions {
  doc?: Document | null;
  /** 最多重试几帧 —— 标签被关过时组件要重新取数, 内容不是立刻就有的。 */
  maxAttempts?: number;
  /** 注入以便单测不依赖真 rAF。 */
  waitFrame?: () => Promise<void>;
}

const defaultWaitFrame = (): Promise<void> =>
  new Promise((r) => {
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => r());
    else setTimeout(r, 16);
  });

/**
 * 把 `.app-content` 滚回 `top`, 返回**是否真的到位**。
 *
 * 返回值是判据本身: 只报「我设过了」没有意义 —— 内容还没撑开时 `scrollTop = 500`
 * 会被浏览器夹成 0, 那是一次**看起来成功的失败**。
 */
export async function restoreAppScroll(top: number, options: RestoreAppScrollOptions = {}): Promise<boolean> {
  if (top <= 0) return false;
  const doc = options.doc ?? (typeof document === 'undefined' ? null : document);
  if (!doc) return false;
  const maxAttempts = options.maxAttempts ?? 20;
  const waitFrame = options.waitFrame ?? defaultWaitFrame;
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const el = doc.querySelector(APP_SCROLL_SELECTOR);
    if (el instanceof HTMLElement) {
      el.scrollTop = top;
      if (Math.abs(el.scrollTop - top) <= 1) return true;
    }
    await waitFrame();
  }
  return false;
}

/**
 * 恢复过就把这个参数摘掉 —— 否则用户自己往上滚之后一刷新又被拽回去。
 *
 * 保留入参的类型: 调用方传的是 vue-router 的 `LocationQuery`, 而 `router.replace`
 * 要的是 `LocationQueryRaw`。返回 `Record<string, unknown>` 会当场类型不兼容
 * (实测 `vue-tsc -b` 报 TS2322 —— 这条也顺便证明了那道闸确实在扫我新写的文件)。
 */
export function queryWithoutReturnScroll<T extends QueryLike>(query: T): T {
  const { [RETURN_SCROLL_QUERY_KEY]: _dropped, ...rest } = query;
  void _dropped;
  return rest as T;
}
