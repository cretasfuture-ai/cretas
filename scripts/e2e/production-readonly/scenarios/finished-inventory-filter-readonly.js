'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');
async function inspect(page, _body, ctx) {
  await step(ctx, () => page.getByText('成品库存', { exact: true }).last().click());
  const table = page.getByTestId('finished-stock-summary');
  await table.waitFor({ state: 'visible', timeout: 15000 });
  await page.locator('.el-loading-mask').first().waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {});
  const rows = table.locator('.el-table__body tr');
  await rows.first().waitFor({ state: 'visible', timeout: 15000 });
  const count = await rows.count();
  const name = (await rows.first().locator('td').first().innerText()).trim();
  if (!name) throw new Error('No named finished product to verify filtering');
  const input = page.getByPlaceholder('成品名称 / 产品ID', { exact: true });
  await step(ctx, () => input.fill(name));
  const names = await rows.locator('td:first-child').allTextContents();
  if (!names.length || names.some(v => !v.toLowerCase().includes(name.toLowerCase()))) throw new Error('Finished product filter includes unrelated products');
  const screenshots = [await ctx.screenshot('finished-filter-matched')];
  await step(ctx, () => input.fill('NO_MATCH_VISUAL_ACCEPTANCE_7e9c11'));
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="finished-stock-summary"] .el-table__body tr').length === 0);
  await table.getByText('暂无符合条件的成品库存', { exact: true }).waitFor({ state: 'visible' });
  screenshots.push(await ctx.screenshot('finished-filter-empty'));
  await step(ctx, () => input.fill(''));
  await page.waitForFunction(n => document.querySelectorAll('[data-testid="finished-stock-summary"] .el-table__body tr').length === n, count);
  assertSafe(ctx);
  return { initialRows: count, filteredRows: names.length, noMatchEmpty: true, clearRestored: true, screenshots, limits: 'Read-only UI filtering; not an independent inventory/accounting/large-data performance audit', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'finished-inventory-filter-readonly', defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id: 'finished-inventory-filter-readonly', path: '/warehouse/inventory-query?tab=total', screenshot: true, inspect }) };
