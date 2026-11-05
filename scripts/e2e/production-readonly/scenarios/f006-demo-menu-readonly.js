'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

// Independent acceptance expectations: click visible leaves, never deep-link around missing menus.
const ENTRIES = [
  ['一键流程演示', '/demo-flow', '反向追溯'],
  ['SmartBI 经营驾驶舱', '/smart-bi/dashboard', '经营驾驶舱'],
  ['AI 问答与数据分析', '/smart-bi/analysis', '分析'],
  ['经营分析', '/smart-bi/analysis-hub', '往来余额'],
  ['生产成本分析', '/production-analytics/cost', '成本'],
  ['AI 工作台', '/dashboard/ai-value', 'AI'],
  ['产品反向溯源', '/production/traceability', '溯源'],
  ['视觉质检图片', '/quality/label-qc', '标签'],
  ['视觉质检样例库', '/quality/vision-training', '视觉'],
  ['采购订单', '/procurement/orders', '采购'],
  ['仓库收货与批次', '/warehouse/materials', '入库'],
  ['生产计划与报工', '/production/plans', '生产'],
  ['生产领料', '/production/material-flow', '领料'],
  ['成品库存', '/sales/finished-goods', '成品'],
  ['销售订单与出库', '/sales/orders', '销售'],
];

module.exports = {
  id: 'f006-demo-menu-readonly', path: '/dashboard', defaultEnabled: false,
  run: ctx => runReadOnlyPageScenario(ctx, {
    id: 'f006-demo-menu-readonly', path: '/dashboard', landmarks: [], timeoutMs: 30000,
    inspect: async page => {
      const checks = [];
      for (let index = 0; index < ENTRIES.length; index++) {
        const [title, path, landmark] = ENTRIES[index];
        const group = page.locator('.app-sidebar .el-sub-menu[aria-label="演示中心"]');
        await group.waitFor({ state: 'visible', timeout: 20000 });
        if (!(await group.getAttribute('class')).includes('is-opened')) {
          await group.locator(':scope > .el-sub-menu__title').click();
        }
        const leaf = group.getByRole('menuitem', { name: title, exact: true });
        await leaf.waitFor({ state: 'visible', timeout: 10000 });
        await leaf.click();
        await page.waitForURL(url => url.pathname === path, { timeout: 20000 });
        const main = page.locator('main.app-content');
        await main.waitFor({ state: 'visible' });
        await page.waitForFunction(({ landmark }) => {
          const main = document.querySelector('main.app-content');
          return main && main.textContent.includes(landmark);
        }, { landmark }, { timeout: 20000 }).catch(async () => {
          await ctx.screenshot(`f006-demo-menu-failure-${index + 1}`);
          throw new Error(`Menu ${title}: expected ${landmark}; main=${(await main.innerText()).slice(0, 180)}`);
        });
        await page.waitForLoadState('networkidle', { timeout: 3000 }).catch(() => {});
        const text = await main.innerText();
        checks.push({ title, path, rendered: text.includes(landmark),
          errorPage: /页面不存在|无权访问|没有权限|权限不足/.test(text) });
        await ctx.screenshot(`f006-demo-menu-${index + 1}`);
      }
      const passed = checks.length === ENTRIES.length && checks.every(item => item.rendered && !item.errorPage);
      return { visibleMenuCount: checks.length, entries: checks,
        assessment: { result: passed ? 'PASS' : 'CONFIRMED_DEFECT', rootCauseClass: passed ? 'none' : 'frontend' } };
    },
  }),
};
