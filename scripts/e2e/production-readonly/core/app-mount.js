'use strict';

/**
 * 等 web-admin 这个 SPA 真正挂载完 —— 唯一实现。
 *
 * ## 判据不是「等久一点」，是一个可判定的条件
 *
 * `web-admin/index.html` 里写着：
 *
 * ```css
 * #app:not(:empty) + #app-loader { display: none; }
 * ```
 *
 * 也就是 **闪屏不可见 ⟺ `#app` 有内容 ⟺ 应用挂载了**。所以等的是
 * `#app-loader` 变为 hidden，⛔ 不是拍一个超时数字。
 *
 * ## 🔴 为什么需要它（2026-08-27 生产实测）
 *
 * 少了这一步，`goto` 的 `domcontentloaded` 在**闪屏阶段**就满足了（闪屏是
 * `index.html` 的静态 DOM），随后取到的 `body.innerText` 只有
 * 「白垩纪 AI Agent / 正在加载应用…」二十来个字符：
 *
 * - 16 个场景里 **13 个**报 `UNVERIFIED / rootCause: tool`
 * - 唯一 PASS 的是 `tenant-isolation` —— 因为它**不导航**
 * - 修好之后同一条命令重跑：PASS 1 → 8，并且**多暴露出 1 个真缺陷**
 *
 * ⚠️ 同一个缺陷有**两处**：页面导航（`scenarios/_shared.js`）和**登录落地页**
 * （`core/ui-login.js`）。后者更阴 —— 它让旁边那条「跨租户残留」canary
 * 变成恒真式（闪屏里永远不会出现别的租户名）。
 * ⛔ 所以判据收敛到这一个函数，不要复制第三份。
 *
 * ## 返回值要被记录下来
 *
 * `appMounted` / `appMountMs` 必须进证据（**含 PASS 时**）。否则下一个人分不清
 * 「应用没挂载」和「挂载了但页面缺东西」—— 那两件事的下一步动作相反。
 */
const APP_LOADER_SELECTOR = '#app-loader';
const DEFAULT_APP_MOUNT_TIMEOUT_MS = 30_000;

/**
 * @returns {Promise<{ appMounted: boolean, appMountMs: number }>}
 *   `appMounted=false` 表示在超时内闪屏一直没消失。⛔ 不要把它折叠进
 *   「页面缺 landmark」——调用方要用不同的 rootCause 报出来。
 */
async function waitForAppMount(page, timeoutMs = DEFAULT_APP_MOUNT_TIMEOUT_MS) {
  const startedAt = Date.now();
  try {
    // ⚠️ 元素不存在时 Playwright 认为 hidden 已满足 —— 这是想要的：
    //    没有闪屏就没什么可等的（例如纯静态错误页）。页面本身对不对，
    //    仍然由调用方的 landmark 判据来管。
    await page.locator(APP_LOADER_SELECTOR).waitFor({ state: 'hidden', timeout: timeoutMs });
    return { appMounted: true, appMountMs: Date.now() - startedAt };
  } catch {
    return { appMounted: false, appMountMs: Date.now() - startedAt };
  }
}

module.exports = { APP_LOADER_SELECTOR, DEFAULT_APP_MOUNT_TIMEOUT_MS, waitForAppMount };
