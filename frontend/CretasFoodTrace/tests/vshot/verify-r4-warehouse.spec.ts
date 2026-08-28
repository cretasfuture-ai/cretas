/**
 * 第四轮 W1：仓储四屏的「编出来的数据」必须消失，并且换成**说清楚是没接入**的空态。
 *
 * ⚠️ 这一类不能只断言「假数据没了」——把屏改成白屏也能让那条断言变绿。
 * 每一屏都同时断言：① 具体的假串不在了；② 那句「尚未接入」在。
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_warehouse_mgr';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/vshots-r4';

/** [路由, 截图名, 该消失的假串, 该出现的 testID] */
const CASES: [string[], string, string[], string][] = [
  [['Main', 'WarehouseManagerMain', 'WHProfileTab', 'WHRecallManage'], 'recall',
   // 「进行中(1)」「已完成(3)」是跟着假记录一起编的**计数** —— 删了记录还留着计数,
   // 屏上就会出现「进行中(1)」配一句「尚未接入」。这两条是看截图才发现的。
   ['RC-20251226-001', '供应商质检问题', '3家客户', '进行中(1)', '已完成(3)'], 'wh-recall-not-wired'],
  [['Main', 'WarehouseManagerMain', 'WHProfileTab', 'WHAlertHandle'], 'alert-handle',
   ['舟山渔业合作社', 'MB-20251223-001', '带鱼'], 'wh-alert-handle-not-wired'],
  [['Main', 'WarehouseManagerMain', 'WHProfileTab', 'WHConversionAnalysis'], 'conversion',
   ['92.3', '94.5', '88.5', '12,000'], 'wh-conversion-not-wired'],
];

test('仓储三屏：假数据消失，且空态说清是「尚未接入」', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  for (const [route, name, forbidden, testId] of CASES) {
    // 两步走：懒加载的 tab 先挂载，内层 stack 才存在。
    await navigateTo(page, route.slice(0, 3));
    await page.waitForTimeout(2500);
    await navigateTo(page, route);
    await page.waitForTimeout(4000);

    const { visible } = await screenText(page);
    await page.screenshot({ path: `${OUT}/wh-${name}.png`, fullPage: true });
    console.log(`=== ${name} 屏上文字: ` + visible.slice(0, 260).replace(/\s+/g, ' '));

    // ① 阳性对照：确实到了这一屏（否则「假串不在」只是因为没打开它）
    expect(await page.getByTestId(testId).count(), `${name}: 没到这一屏或空态没出现`).toBeGreaterThan(0);
    // ② 假串消失
    for (const f of forbidden) {
      expect(visible, `${name}: 屏上还有编出来的 ${f}`).not.toContain(f);
    }
    // ③ 空态说的是「没接入」，⛔ 不是「没有数据」
    expect(visible, `${name}: 空态没说清是尚未接入`).toContain('尚未接入');
  }
});

test('库存预警：AI 那一页不再把三条假告警和真告警并排放', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  await navigateTo(page, ['Main', 'WarehouseManagerMain', 'WHProfileTab']);
  await page.waitForTimeout(2500);
  await navigateTo(page, ['Main', 'WarehouseManagerMain', 'WHProfileTab', 'WHAlertList']);
  await page.waitForTimeout(4000);

  const { visible } = await screenText(page);
  await page.screenshot({ path: `${OUT}/wh-alert-list.png`, fullPage: true });
  console.log('=== 预警列表: ' + visible.slice(0, 300).replace(/\s+/g, ' '));

  for (const f of ['过期风险预警 - 带鱼批次', '海洋水产', '鲜食超市', '28kg/天']) {
    expect(visible, `屏上还有编出来的 ${f}`).not.toContain(f);
  }
  // 默认落在真实告警那一页
  expect(visible).toContain('常规告警');
});
