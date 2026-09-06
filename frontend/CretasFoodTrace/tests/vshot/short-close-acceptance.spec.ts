/**
 * T-SHORT-SHIP 行为验收 —— 在 Mac mini 测试环境 (100.85.234.108:10010) 上真跑一遍
 * 「短交结案」这条链, ⛔ 不写 prod。
 *
 * 正向: f006_sales_mgr 打开 PARTIAL_DELIVERED 订单 → 看得到「短交结案」按钮 → 打开对话框
 *       (逐行 订购/已发/短交) → 选原因 + 勾确认 → 提交 → 状态变成「短交结案」。
 * 阴性: 同一张订单, 换一个没有 sales:read_write 的 F006 角色 → 按钮不存在。
 *
 * 跑法 (需要先 `ENVFILE=.env.test.local npx expo start --web --port 3021 --clear`):
 *   npx playwright test tests/vshot/short-close-acceptance.spec.ts -c tests/vshot/vshot.config.ts
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, login, authSummary, navRefReady, screenText, currentPath } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out/short-close';
const ORDER_ID = process.env.SHORT_CLOSE_ORDER_ID ?? '';
const NAV = '__CRETAS_NAV__';

/**
 * 跳到销售单详情。
 *
 * ⚠️ 不能直接 `navigate('SalesOrderDetail', {orderId})` —— 每个角色落在不同的
 * navigator 里 (sales_manager → SalesManagerNavigator 的 SMSalesTab;
 * factory_super_admin → BossNavigator), 顶层根本没有这个名字, 而 react-navigation
 * **不报错**: 上一次实测 navigate 返回 ok=true 而屏幕还停在首页, 读数长得像
 * 「按钮没渲染」。所以这里逐个 tab 试, 并用 currentPath 证明真的到了。
 */
async function gotoOrderDetail(page: any, orderId: string): Promise<string> {
  await navRefReady(page);
  // ⚠️ 必须等到根 state 真的长出 Main —— 上一次实测读到的是 ["Loading"], 于是一个 tab
  //    都没试到, 读数长成「按钮不在」。这是形态 A: 我量的不是我想知道的那个数。
  await page.waitForFunction(
    (nav: string) => {
      const ref = (globalThis as any)[nav];
      if (!ref?.isReady?.()) return false;
      const st = ref.getRootState();
      const main = (st.routes ?? []).find((r: any) => r.name === 'Main');
      return !!(main?.state?.routeNames?.length);
    },
    NAV,
    { timeout: 120_000 },
  );
  const tabs: string[] = await page.evaluate((nav: string) => {
    const ref = (globalThis as any)[nav];
    const st = ref.getRootState();
    const main = (st.routes ?? []).find((r: any) => r.name === 'Main');
    return main?.state?.routeNames ?? [];
  }, NAV);
  console.log('TABS ' + JSON.stringify(tabs));

  for (const tab of tabs) {
    const r = await page.evaluate(
      ({ nav, tab, orderId }: any) => {
        const ref = (globalThis as any)[nav];
        try {
          ref.navigate(tab as never, { screen: 'SalesOrderDetail', params: { orderId } } as never);
          return { ok: true };
        } catch (e: any) {
          return { ok: false, error: String(e?.message ?? e) };
        }
      },
      { nav: NAV, tab, orderId },
    );
    await page.waitForTimeout(2500);
    const path = await currentPath(page);
    console.log(`TRY tab=${tab} navigate=${JSON.stringify(r)} currentPath=${path}`);
    if (path.endsWith('SalesOrderDetail')) {
      await page.waitForTimeout(2500);
      return path;
    }
  }
  return await currentPath(page);
}

test.describe.configure({ mode: 'serial' });

test('正向: sales_manager 看得到「短交结案」并能走完', async ({ page }) => {
  test.skip(!ORDER_ID, 'SHORT_CLOSE_ORDER_ID 未设置');
  fs.mkdirSync(OUT, { recursive: true });
  const errs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message.slice(0, 300)));
  // 硬约束 9: 「订单变了」不能只看屏幕 —— 把那一次真实请求/响应打出来。
  page.on('request', (r) => { if (r.url().includes('close-short-shipment')) console.log('REQ ' + r.method() + ' ' + r.url() + ' body=' + (r.postData() ?? '')); });
  page.on('requestfailed', (r) => { if (r.url().includes('close-short-shipment')) console.log('REQFAILED ' + r.url() + ' ' + JSON.stringify(r.failure())); });
  const closeResponses: Array<{ status: number; body: string }> = [];
  page.on('response', async (r) => {
    if (!r.url().includes('close-short-shipment')) return;
    let body = '';
    try { body = (await r.text()).slice(0, 500); } catch { body = '<unreadable>'; }
    console.log('RESP ' + r.status() + ' ' + r.url() + ' ' + body);
    closeResponses.push({ status: r.status(), body });
  });
  // window.alert 会阻塞 JS 且 playwright 默认自动 dismiss —— 显式接住并打出来。
  page.on('dialog', async (d) => { console.log('BROWSER-DIALOG ' + d.type() + ': ' + d.message()); await d.dismiss().catch(() => {}); });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'f006_sales_mgr', '123456');
  console.log('AUTH ' + JSON.stringify(await authSummary(page)));

  const path = await gotoOrderDetail(page, ORDER_ID);
  await page.screenshot({ path: `${OUT}/01-detail-before.png`, fullPage: true });
  const before = await screenText(page);
  console.log('BEFORE path=' + path + ' visible: ' + JSON.stringify(before.visible.slice(0, 400)));
  // 阳性对照: 屏真的渲染出来了 —— 否则「按钮不在」只是没加载完。
  expect(path).toContain('SalesOrderDetail');

  const btn = page.getByTestId('sales-order-short-close-button');
  await expect(btn).toBeVisible({ timeout: 20_000 });

  await btn.click();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: `${OUT}/02-dialog.png`, fullPage: true });
  const dialogText = await screenText(page);
  console.log('DIALOG visible: ' + JSON.stringify(dialogText.visible.slice(0, 800)));
  // 弹窗必须把缺口摊开给用户看 —— 合计那一行是设计上必然出现的读数 (硬约束 9)。
  await expect(page.getByTestId('sales-order-short-close-total')).toBeVisible();
  await expect(page.getByTestId('sales-order-short-close-line-0')).toBeVisible();

  // 提交按钮在选原因/勾确认之前必须是禁用的 (阴性对照)。
  const confirm = page.getByTestId('sales-order-short-close-confirm');
  await expect(confirm).toBeDisabled();

  await page.getByTestId('sales-order-short-close-reason-CUSTOMER_WAIVED_REMAINDER').click();
  await page.getByTestId('sales-order-short-close-ack').click();
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${OUT}/03-dialog-ready.png`, fullPage: true });
  await expect(confirm).toBeEnabled();

  await confirm.click();
  await page.waitForTimeout(8000);
  await page.screenshot({ path: `${OUT}/04-after-submit.png`, fullPage: true });
  const after = await screenText(page);
  console.log('AFTER visible: ' + JSON.stringify(after.visible.slice(0, 600)));
  // ── 三态判读 (硬约束 4): 没量到 ≠ 没问题 ──────────────────────────────
  // 请求必须真的发出去过, 否则「状态没变」是无信息的读数。
  expect(closeResponses.length, '一次 close-short-shipment 请求都没发出去').toBeGreaterThan(0);
  const last = closeResponses[closeResponses.length - 1]!;
  if (last.status === 404) {
    // 「没量到」: 这个环境的后端版本早于该端点 (阳性对照: 同一 controller 的 /confirm
    // 在这里返回 409 而不是 404)。⛔ 不允许静默当成绿, 也不是 UI 缺陷。
    throw new Error(
      'NOT-MEASURED: close-short-shipment 在该环境返回 404 —— 测试环境后端版本早于该端点, '
      + '本次无法执行结案。UI 与请求体已验证 (见上面的 REQ 行)。');
  }
  expect(last.status, '后端拒绝了这次结案: ' + last.body).toBe(200);
  // 真正的验收: 屏上状态必须变成「短交结案」, ⛔ 不是「对话框关掉了」。
  expect(after.visible).toContain('短交结案');
  expect(after.visible).not.toContain('部分发货');

  console.log('CONSOLE ERRORS (' + errs.length + ')');
  errs.slice(0, 10).forEach((e) => console.log('  ' + e));
});

test('阴性: 没有 sales:read_write 的角色看不到这个按钮', async ({ page }) => {
  test.skip(!ORDER_ID, 'SHORT_CLOSE_ORDER_ID 未设置');
  const user = process.env.SHORT_CLOSE_NEG_USER ?? 'f006_proc_mgr';
  fs.mkdirSync(OUT, { recursive: true });
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, user, '123456');
  console.log('AUTH(neg) ' + JSON.stringify(await authSummary(page)));

  const path = await gotoOrderDetail(page, ORDER_ID);
  await page.screenshot({ path: `${OUT}/05-negative-${user}.png`, fullPage: true });
  const txt = await screenText(page);
  console.log('NEG path=' + path + ' visible: ' + JSON.stringify(txt.visible.slice(0, 400)));
  // 阳性对照: 这一屏**真的到达并渲染了**, 否则「按钮不在」只是屏根本走不到 (恒真的阴性对照)。
  expect(path).toContain('SalesOrderDetail');
  expect(txt.visible).toContain('销售单详情');
  await expect(page.getByTestId('sales-order-short-close-button')).toHaveCount(0);
});
