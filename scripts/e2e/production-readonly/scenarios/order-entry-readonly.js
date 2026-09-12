'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

async function settleVisual(locator) {
  await locator.evaluate(async element => {
    const animations = [...element.getAnimations({ subtree: true })];
    for (let parent = element.parentElement; parent; parent = parent.parentElement) animations.push(...parent.getAnimations());
    await Promise.all([...new Set(animations)]
      .filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime))
      .map(animation => animation.finished.catch(() => {})));
  });
}

async function purchaseInspect(page, _body, ctx) {
  const screenshots = [];
  const filter = page.locator('.search-bar .el-select').first();
  await step(ctx, () => filter.click());
  const responsePromise = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.endsWith('/purchase/orders/by-status')
    && new URL(r.url()).searchParams.get('status') === 'DRAFT', { timeout: 15000 }).catch(() => null);
  await step(ctx, () => page.getByRole('option', { name: '草稿', exact: true }).click());
  const response = await responsePromise;
  if (!response || response.status() >= 400) throw new Error('Draft list query was not successful');
  const payload = await response.json();
  const items = payload.data?.content;
  if (!payload.success || !Array.isArray(items)) throw new Error('Draft list response was not recognized');
  if (!items.length) return { reason: 'No existing draft purchase order available; no data created', assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
  const row = page.locator('.el-table__body tbody tr').filter({ has: page.locator('.el-tag', { hasText: /^草稿$/ }) }).first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  const dialog = page.getByRole('dialog', { name: '编辑草稿采购订单', exact: true });
  const rounds = [];
  for (let i = 0; i < 2; i++) {
    await step(ctx, () => row.getByRole('button', { name: /更多/ }).click());
    const edit = page.locator('.el-dropdown-menu:visible .el-dropdown-menu__item').filter({ has: page.locator('.row-action-menu__label', { hasText: /^编辑$/ }) });
    await step(ctx, () => edit.click());
    await page.waitForURL(url => Boolean(url.searchParams.get('edit')), { timeout: 10000 });
    await dialog.waitFor({ state: 'visible', timeout: 20000 });
    await settleVisual(dialog);
    const formCount = await dialog.locator('.el-form-item').count();
    if (formCount < 3) throw new Error('Purchase editor opened without its form');
    screenshots.push(await ctx.screenshot(`purchase-edit-open-${i + 1}`));
    await step(ctx, () => dialog.getByRole('button', { name: '取消', exact: true }).click());
    await dialog.waitFor({ state: 'hidden', timeout: 10000 });
    await page.waitForURL(url => !url.searchParams.has('edit'), { timeout: 10000 });
    rounds.push({ opened: true, formCount, closed: true, editQueryCleared: true });
  }
  assertSafe(ctx);
  return { rounds, screenshots, limits: 'Existing draft opened and cancelled without editing or saving; persistence not verified', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}

async function salesInspect(page, _body, ctx) {
  const screenshots = [];
  await step(ctx, () => page.getByRole('button', { name: '+ 添加产品', exact: true }).click());
  const picker = page.getByRole('dialog', { name: '选择销售产品', exact: true });
  await picker.waitFor({ state: 'visible' });
  const product = picker.locator('.product-picker-row:not(:disabled)').first();
  await product.waitFor({ state: 'visible', timeout: 15000 });
  await step(ctx, () => product.click());
  await step(ctx, () => picker.getByRole('button', { name: /完成选择/ }).click());
  const table = page.locator('.line-table');
  const header = table.locator('.el-table__header th').filter({ hasText: /^来源仓库$/ });
  await header.scrollIntoViewIfNeeded();
  const headers = await table.locator('.el-table__header th').allTextContents();
  const index = headers.findIndex(text => text.trim() === '来源仓库');
  if (index < 0) throw new Error('Sales source warehouse heading is missing');
  const select = table.locator('.el-table__body tbody tr').first().locator('td').nth(index).locator('.el-select');
  await select.scrollIntoViewIfNeeded();
  const control = select.getByRole('combobox');
  const listId = await control.getAttribute('aria-controls');
  await step(ctx, () => select.click());
  const options = page.locator(`[id="${listId}"] .el-select-dropdown__item:not(.is-disabled)`);
  await options.first().waitFor({ state: 'visible', timeout: 15000 });
  const optionCount = await options.count();
  const chosenLabel = (await options.first().innerText()).trim();
  await settleVisual(options.first());
  screenshots.push(await ctx.screenshot('sales-source-warehouse-options'));
  await step(ctx, () => options.first().click());
  await options.first().waitFor({ state: 'hidden', timeout: 5000 });
  if (!(await select.innerText()).includes(chosenLabel)) throw new Error('Warehouse selection label was not retained');
  screenshots.push(await ctx.screenshot('sales-source-warehouse-selected'));
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await table.waitFor({ state: 'hidden', timeout: 10000 });
  assertSafe(ctx);
  return { heading: '来源仓库', optionCount, selectedLabelRetained: true, localDraftClosed: true, screenshots,
    limits: 'Warehouse wording and local selection only; no order saved or stock moved', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}

function scenario(id, path, inspect) {
  return { id, defaultEnabled: false, inspect,
    run: ctx => runReadOnlyPageScenario(ctx, { id, path, inspect: page => inspect(page, '', ctx), screenshot: false }) };
}
module.exports = {
  purchase: scenario('purchase-edit-entry-readonly', '/procurement/orders', purchaseInspect),
  sales: scenario('sales-source-warehouse-readonly', '/sales/orders/new', salesInspect),
};
