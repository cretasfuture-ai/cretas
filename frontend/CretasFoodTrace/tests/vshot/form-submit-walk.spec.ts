/**
 * 第五轮：**表单的提交路径**。前四轮都只走到「打开表单」为止。
 *
 * <h3>这一轮量什么</h3>
 *
 * 对每个表单屏：填最少的必填项 → 点提交 → 记录
 *   ① 有没有发出**非 GET** 请求（发了什么、回了什么状态码）
 *   ② 屏上出现的是成功还是失败文案
 *
 * ⇒ 关心的是这两者**对不对得上**。今晚已经抓到三处「弹成功但什么都没提交」
 *   （仓储预警处理 / 车间启动设备 / 车间开始生产），所以判据必须是
 *   **「有没有真的发请求」**，⛔ 不是「屏上说没说成功」。
 *
 * <h3>写入控制</h3>
 *
 * F006 是测试租户，测试数据出现在里面是它的用途。但仍然：
 *   · 每一条非 GET 都逐条打印（谁、什么方法、什么状态码），⛔ 不做汇总
 *   · ⛔ 不点任何删除 / 作废 / 审批通过
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_warehouse_mgr';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/vshots-r5';

/** [路由路径, 截图名] —— 都是「新建 / 录入」类表单。 */
// ⚠️ 第一版写的是 f006_admin + `Main/FAHomeTab/...` —— 两处都错：
//   ① f006_admin 走的是 BossNavigator，那棵树**按设计就没有表单**
//      （屏上原话：「查看与决策，不替岗位录入」）；
//   ② `PurchaseOrderCreate` 注册在 FAManagementStackNavigator / ProcurementManagerNavigator 里，
//      路径是 `Main/FactoryAdminMain/FAManagementTab/...`，与 FAHomeTab 无关。
// 而 `navigate()` 对这两种错误**都返回 ok=true，只是停在原地** ——
// 当时读数是「3 个表单都没发任何请求」，看起来像一个惊人的发现，其实是我没到那些屏。
// ⇒ 换成路径已经核实过的仓储树。
const FORMS: [string[], string][] = [
  [['Main', 'WarehouseManagerMain', 'WHInboundTab', 'WHReceiptCreate'], 'wh-receipt-create'],
  [['Main', 'WarehouseManagerMain', 'WHInboundTab', 'WHPutaway'], 'wh-putaway'],
  [['Main', 'WarehouseManagerMain', 'WHOutboundTab', 'WHDeliveryConfirm'], 'wh-delivery-confirm'],
];

test('表单提交：屏上说的成功 vs 真的发出去的请求', async ({ page }) => {
  const mutations: string[] = [];
  page.on('request', (r) => {
    if (r.method() !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      mutations.push(`${r.method()} ${r.url().split('?')[0]}`);
    }
  });
  page.on('response', (r) => {
    if (r.request().method() !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      mutations.push(`  -> ${r.status()} ${r.url().split('?')[0]}`);
    }
  });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  for (const [route, name] of FORMS) {
    const before = mutations.length;
    // ⚠️ 上一屏若崩过，AppErrorBoundary 会顶在那里，后面每一屏都读到同一张错误页
    //    （实测：第一屏崩了之后 #2 #3 的 navigate 全部 ok=false）。⇒ 先点「再试一次」恢复。
    const retry = page.getByTestId('app-error-boundary-retry');
    if ((await retry.count()) > 0) {
      await retry.click().catch(() => {});
      await page.waitForTimeout(2000);
    }

    // 逐级挂载：懒加载的 tab 不先挂上，内层 stack 根本不存在
    await navigateTo(page, route.slice(0, 2));
    await page.waitForTimeout(1500);
    await navigateTo(page, route.slice(0, 3));
    await page.waitForTimeout(2000);
    const nav = await navigateTo(page, route);
    await page.waitForTimeout(4000);

    const { visible } = await screenText(page);
    await page.screenshot({ path: `${OUT}/form-${name}.png`, fullPage: true });
    console.log(`\n=== ${name}  navigate.ok=${nav.ok}`);
    console.log('    屏上: ' + visible.slice(0, 200).replace(/\s+/g, ' '));

    // 找一个「提交/保存/确认」按钮并点它（⛔ 不点删除/作废/审批）
    const submit = page.getByText(/^(提交|保存|确认提交|创建)$/).first();
    const hasSubmit = (await submit.count()) > 0;
    console.log('    有提交按钮: ' + hasSubmit);
    if (hasSubmit) {
      await submit.click({ timeout: 8000 }).catch((e) => console.log('    点击失败: ' + String(e).slice(0, 80)));
      await page.waitForTimeout(4000);
      const after = await screenText(page);
      console.log('    点后屏上: ' + after.visible.slice(0, 200).replace(/\s+/g, ' '));
      await page.screenshot({ path: `${OUT}/form-${name}-after.png`, fullPage: true });
    }
    const fired = mutations.slice(before);
    console.log(`    这一屏发出的非 GET 请求 ${fired.length} 条:`);
    fired.forEach((m) => console.log('      ' + m));
  }

  console.log(`\n=== 全程非 GET 请求合计 ${mutations.length} 条`);
  // 这个 spec 是**取读数**用的, 不做断言 —— 断言要等看清读数之后再定。
  expect(true).toBe(true);
});
