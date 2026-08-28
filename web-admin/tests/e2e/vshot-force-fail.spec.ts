import { mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';

/**
 * 「让它红一次」——把接口打成 404，看失败态是否真的出现。
 *
 * ⚠️ 为什么需要这一步：修复上线后重新截图，「字段映射复核」屏显示的是
 * 绿色对勾「暂无待复核列映射」，而顶部**没有**红色横幅 —— 说明这次接口**通了**，
 * 走的是空态分支。而我改的是**失败**分支。
 *
 * ▎那条路径没被走到，就没有验证。「看起来正常」不等于「失败时也正常」。
 *
 * ⇒ 用 route 拦截把 `mapping-review/pending` 打成 404，再截图。
 *   期望：正文出现「加载待复核队列失败 / 无法判断是否有待复核的列映射」+ 重试按钮，
 *   ⛔ 不再出现绿色对勾「暂无待复核列映射」。
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.VSHOT_BASE || 'http://139.196.165.140:8086';
const USER = process.env.VSHOT_USER || 'f006_admin';
const PASS = process.env.VSHOT_PASS || '123456';
const OUT = process.env.VSHOT_OUT || resolve(HERE, '../../../../my-prototype-logistics/vshots4');

test('映射复核: 接口 404 时必须显示失败态, 不许显示绿色对勾', async ({ page }) => {
  test.setTimeout(5 * 60 * 1000);
  if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true });

  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input').first().fill(USER);
  await page.locator('input[type="password"]').first().fill(PASS);
  await page.getByRole('button', { name: /登\s*录|登录/ }).first().click();
  await page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 30_000 });

  // 🔴 把那两个接口打成 404 —— 复现走查当天实拍到的那个状态
  // 诊断: 把所有请求 URL 记下来 —— 拦不到时才知道它到底请求了什么
  const seen: string[] = [];
  page.on('request', r => { if (r.url().includes('mapping')) seen.push(r.url()); });

  let intercepted = 0;
  // ⚠️ 用正则而不是 glob: `**/x/**` 要求 x 后面还有段, 而
  //   `/smartbi-api/api/mobile/F006/smart-bi/mapping-review/pending?limit=200`
  //   的 `mapping-review` 后面是 `/pending?...`, glob 对 query 的处理也不确定。
  // 🔴 第一版写的是 `/mapping-review/` —— 它把**页面导航本身**也拦了,
  //   因为 `goto('.../smart-bi/mapping-review')` 这个 URL 也含这个词。
  //   结果浏览器直接渲染了我伪造的 JSON, 截图上根本不是应用页面。
  //   ▎**仪器把被测对象替换掉了。**
  //   ⇒ 只拦 API 前缀 `/smartbi-api/`, 并显式跳过 document 类型的请求。
  await page.route(/\/smartbi-api\/.*mapping-review/, route => {
    if (route.request().resourceType() === 'document') return route.continue();
    intercepted += 1;
    return route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, message: '请求的接口不存在 (forced by test)' }),
    });
  });

  await page.goto(`${BASE}/smart-bi/mapping-review`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6000);

  // 阳性对照：拦截真的发生了。0 次说明路由没匹配上，下面的断言无意义。
  expect(
    intercepted,
    ['一次都没拦到 ⇒ 这次根本没打成 404, 判据不可判定。',
     '实际请求过的 mapping 相关 URL:',
     ...(seen.length ? seen : ['(一个都没有 —— 页面可能没发这个请求)'])].join('\n  '),
  ).toBeGreaterThan(0);

  await page.screenshot({ path: resolve(OUT, '139-字段映射复核-FORCED-404.png'), fullPage: true });

  const body = await page.locator('body').innerText();
  expect(body, '接口 404 时必须明说加载失败').toContain('加载待复核队列失败');
  expect(body, '必须明说「无法判断」, ⛔ 不能只说失败').toContain('无法判断是否有待复核的列映射');
  expect(body, '⛔ 接口失败时不许出现「暂无待复核列映射」——那是把 404 说成「一切正常」')
    .not.toContain('暂无待复核列映射');
  console.log(`INTERCEPTED=${intercepted}`);
});
