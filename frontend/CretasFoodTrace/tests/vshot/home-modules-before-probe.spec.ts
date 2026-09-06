/**
 * BEFORE 探针 —— 只在**回退到修复前的 HomeScreen.tsx** 之后跑，用来把「缺陷长什么样」量下来。
 * ⛔ 不是回归测试，不进 CI；它断言的是**缺陷仍然存在**。
 *
 * ⚠️ 仪器设计（形态 A）：修复前的 HomeScreen 上**没有 testID**，
 *    所以「`home-module-*` 计数为 0」在 before/after 之间是**不可判定**的
 *    （分不清「没有卡」和「没有 testID」）。
 *    ⇒ 判据改成**屏上的文字**：
 *       「功能模块」这四个字在  ← 孤零零的标题（缺陷的可见症状）
 *       任何一个模块名都不在    ← 底下确实一张卡都没有
 *    再配一个「一定会发生」的读数：问候语必须在（否则是屏没渲染出来）。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, login, authSummary, navRefReady, currentPath, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out/home-modules-before';
const PASS = process.env.VSHOT_PASS ?? '123456';

const SECTION_TITLE = '功能模块';
const GREETING = '你好';
/** 修复前那 5 项 + 本次新增的销售/财务/采购各项，一个都不该出现。 */
const ANY_MODULE_NAME = [
  '生产模块', '物流模块', '溯源模块', '用户管理', '系统设置',
  '销售订单', '客户管理', '应收应付', '价格表', '退货单',
  '待办审批', '采购订单', '供应商管理',
];

const USERS = [
  { user: 'f006_sales_mgr', role: 'sales_manager' },
  { user: 'f006_finance_mgr', role: 'finance_manager' },
  { user: 'f006_procurement_mgr', role: 'procurement_manager' },
];

test.beforeAll(() => {
  fs.mkdirSync(OUT, { recursive: true });
});

for (const u of USERS) {
  test(`BEFORE — ${u.user} 首页只有孤零零的「功能模块」标题`, async ({ page }) => {
    await page.goto(APP, { waitUntil: 'domcontentloaded' });
    await waitForApp(page);
    await login(page, u.user, PASS);
    await navRefReady(page);
    await page.waitForTimeout(4000);

    const auth = await authSummary(page);
    expect(auth?.role).toBe(u.role);

    const path0 = await currentPath(page);
    const { visible } = await screenText(page);
    const testIdCount = await page.evaluate(
      () => document.querySelectorAll('[data-testid^="home-module-"]').length
    );
    const present = ANY_MODULE_NAME.filter((n) => visible.includes(n));

    console.log(
      `BEFORE ${u.user} path=${path0} 标题在=${visible.includes(SECTION_TITLE)} ` +
      `问候语在=${visible.includes(GREETING)} 模块名命中=${JSON.stringify(present)} ` +
      `testID计数=${testIdCount}`
    );
    await page.screenshot({ path: `${OUT}/${u.user}-home-before.png`, fullPage: true });

    // 「一定会发生」的读数 —— 屏真的渲染出来了
    expect(visible).toContain(GREETING);
    // 缺陷本体：标题在，卡一张没有
    expect(visible).toContain(SECTION_TITLE);
    expect(present).toEqual([]);
  });
}
