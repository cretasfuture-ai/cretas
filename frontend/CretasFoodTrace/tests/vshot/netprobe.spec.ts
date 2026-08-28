/**
 * 「App 到底打的是哪个地址」—— 登录失败时先跑这个，⛔ 不要猜。
 *
 * 2026-08-28 实测：走查登录一直「网络连接失败」，而 curl 打同一个接口 200、
 * 浏览器里 fetch 同一个接口也 200。看起来像网络问题，其实不是 ——
 * 这个探针一跑就看到 App 打的是 `http://10.0.2.2:10010`（Android 模拟器的兜底地址）。
 *
 * 成因：`babel.config.js` 用 `process.env.ENVFILE` 选 .env 文件，而 metro 的
 * **transform 缓存**是落盘的。之前有一次不带 ENVFILE 启动，缓存里就存了一份
 * `@env` 全空的转译结果；后面即使带上 ENVFILE 重启，不加 `--clear` 也照样命中旧缓存。
 *
 * ⇒ 起 metro 一律：`CI=1 ENVFILE=.env.production npx expo start --web --port 3021 --clear`
 * ⇒ 判据：bundle 里 `cretaceousfuture.com` 出现 **2** 次（1 次是常量注释，
 *    第 2 次才是 @env 真的注进去了）。只出现 1 次 = 又在跑兜底地址。
 */
import { test } from '@playwright/test';
import { waitForApp, login } from './vshot-lib';
const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
test('登录时 App 实际打的是哪个地址', async ({ page }) => {
  const reqs: string[] = [];
  const notes: string[] = [];
  page.on('request', (r) => { if (!r.url().startsWith(APP)) reqs.push(`${r.method()} ${r.url()}`); });
  page.on('response', (r) => { if (!r.url().startsWith(APP)) notes.push(`RESP ${r.status()} ${r.url()}`); });
  page.on('requestfailed', (r) => notes.push(`FAIL ${r.url()} -> ${r.failure()?.errorText}`));
  page.on('console', (m) => { if (m.type() === 'error') notes.push('ERR ' + m.text().slice(0, 220)); });
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'f006_admin', '123456').catch((e) => notes.push('LOGIN THREW ' + String(e).slice(0, 160)));
  console.log('=== cross-origin requests (' + reqs.length + ')');
  [...new Set(reqs)].slice(0, 15).forEach((r) => console.log('   ' + r));
  console.log('=== responses / failures / console errors (' + notes.length + ')');
  [...new Set(notes)].slice(0, 20).forEach((f) => console.log('   ' + f));
});
