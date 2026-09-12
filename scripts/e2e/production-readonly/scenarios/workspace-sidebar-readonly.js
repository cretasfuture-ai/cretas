'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

async function inspect(page, _body, ctx) {
  const search = page.getByPlaceholder('搜索 订单号/客户', { exact: true });
  const tab = title => page.locator('.workspace-tab').filter({ has: page.locator('.workspace-tab-title').filter({ hasText: new RegExp(`^${title}$`) }) });
  const activeKey = () => page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
  await search.waitFor({ state: 'visible', timeout: 15000 });
  const original = await activeKey();
  await step(ctx, () => search.fill('R14-original-unsent'));
  const menu = page.locator('.app-sidebar .el-menu-item').filter({ has: page.locator('.menu-title-copy').filter({ hasText: /^销售订单$/ }) });
  // Use the real sidebar selection event; do not synthesize a router push.
  async function openMenu(ordinal) {
    await step(ctx, () => menu.click());
    await page.waitForURL(url => url.searchParams.get('_taskLabel') === `销售订单 ${ordinal}`, { timeout: 10000 });
    await search.waitFor({ state: 'visible' });
    if (await search.inputValue() !== '') throw new Error('Sidebar new task inherited an old filter');
  }
  await openMenu(2);
  const second = await activeKey();
  await step(ctx, () => search.fill('R14-second-unsent'));
  await openMenu(3);
  const third = await activeKey();
  if (new Set([original, second, third]).size !== 3) throw new Error('Sidebar task identities collided');
  async function activate(title) {
    await step(ctx, () => tab(title).click());
    await page.waitForFunction(expected => document.querySelector('.workspace-tab-container.active .workspace-tab-title')?.textContent === expected, title);
  }
  await activate('销售订单 1');
  if (await activeKey() !== original || await search.inputValue() !== 'R14-original-unsent') throw new Error('Original list state lost');
  await activate('销售订单 2');
  if (await search.inputValue() !== 'R14-second-unsent') throw new Error('Second list state lost');
  const screenshots = [];
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    for (const n of [1, 2, 3]) {
      const number = tab(`销售订单 ${n}`).locator('.workspace-tab-number');
      if (!await number.isVisible()) throw new Error('Sidebar task ordinal is hidden');
    }
    screenshots.push(await ctx.screenshot(`workspace-sidebar-${width}`));
  }
  for (const n of [3, 2]) {
    await activate(`销售订单 ${n}`);
    await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  }
  await activate('销售订单');
  if (await search.inputValue() !== 'R14-original-unsent') throw new Error('Cleanup lost original list state');
  await step(ctx, () => search.fill(''));
  assertSafe(ctx);
  return { sidebarCreatesIndependentTasks: true, freshFilters: true, originalKeyUnchanged: true, originalFilterRetained: true, secondFilterRetained: true, cleanupRetainedOriginal: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-sidebar-readonly', defaultEnabled: false, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-sidebar-readonly', path: '/sales/orders', screenshot: true, inspect }) };
