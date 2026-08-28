/**
 * 走**一次真实的提交路径** —— 填必填项 → 提交 → 核对请求。
 *
 * <h3>为什么必须真填</h3>
 *
 * 空表单点提交时 `handleSubmit` 会在校验处 return（这是**正确行为**），
 * 于是「0 个非 GET」既可能是「校验拦住了」也可能是「什么都没接」——
 * 两者在读数上一模一样。⇒ 只有把必填项填上，才分得开。
 *
 * ⚠️ 而在 web 上校验提示走 `Alert.alert`，那是个空函数 ⇒ 屏上**毫无反馈**，
 * 更没法靠「屏上说了什么」来判。
 *
 * <h3>写入范围</h3>
 *
 * F006 是测试租户。这里只做**一次**最小写入：一行包材、数量 1。
 * 每一条非 GET 逐条打印（方法/路径/状态码/请求体），出了什么事都能对账。
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_warehouse_mgr';

test('收货提交路径：填数量 → 提交 → 核对请求', async ({ page }) => {
  const reqs: string[] = [];
  page.on('request', (r) => {
    if (r.method() !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      reqs.push(`→ ${r.method()} ${r.url().split('?')[0]}  body=${(r.postData() ?? '').slice(0, 300)}`);
    }
  });
  page.on('response', async (r) => {
    if (r.request().method() !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      let body = '';
      try { body = (await r.text()).slice(0, 220); } catch { /* ignore */ }
      reqs.push(`← ${r.status()} ${r.url().split('?')[0]}  ${body}`);
    }
  });
  const dialogs: string[] = [];
  page.on('dialog', async (d) => { dialogs.push(d.message()); await d.dismiss().catch(() => {}); });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  await navigateTo(page, ['Main', 'WarehouseManagerMain']);
  await page.waitForTimeout(1500);
  await navigateTo(page, ['Main', 'WarehouseManagerMain', 'WHInboundTab']);
  await page.waitForTimeout(1500);
  await navigateTo(page, ['Main', 'WarehouseManagerMain', 'WHInboundTab', 'WHPurchaseReceiveList']);
  await page.waitForTimeout(3500);

  await page.locator('[role="button"], button').filter({ hasText: '开始收货' }).first().click();
  await page.waitForTimeout(3500);
  const entered = await screenText(page);
  console.log('=== 进入表单: ' + entered.visible.slice(0, 160).replace(/\s+/g, ' '));
  expect(entered.visible, '没进到收货录入屏 —— 后面的读数无意义').toContain('收货明细');

  // 填数量：找第一个数字输入框
  const inputs = page.locator('input');
  const n = await inputs.count();
  console.log(`=== 页面上有 ${n} 个输入框`);
  let filled = false;
  for (let i = 0; i < n; i += 1) {
    const el = inputs.nth(i);
    const type = await el.getAttribute('inputmode');
    const ph = (await el.getAttribute('placeholder')) ?? '';
    if (type === 'numeric' || type === 'decimal' || /数量|收货/.test(ph)) {
      await el.fill('1');
      console.log(`=== 已在第 ${i} 个输入框(inputmode=${type} placeholder=${ph})填入 1`);
      filled = true;
      break;
    }
  }
  expect(filled, '没找到数量输入框 —— 这一轮读数无效').toBe(true);
  await page.waitForTimeout(800);

  const mark = reqs.length;
  await page.locator('[role="button"], button').filter({ hasText: '提交入库' }).first().click();
  await page.waitForTimeout(6000);

  const after = await screenText(page);
  console.log('=== 提交后屏上: ' + after.visible.slice(0, 220).replace(/\s+/g, ' '));
  dialogs.forEach((d) => console.log('=== 弹窗: ' + d.replace(/\s+/g, ' ')));
  const fired = reqs.slice(mark);
  console.log(`=== 提交产生的请求 ${fired.length} 条:`);
  fired.forEach((m) => console.log('   ' + m));
});
