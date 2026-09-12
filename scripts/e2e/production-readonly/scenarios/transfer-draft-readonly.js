'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertPreserved(before, after) {
  for (const key of Object.keys(before)) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) throw new Error(`Transfer draft lost field: ${key}`);
  }
}
async function inspect(page, _body, ctx) {
  const screenshots = [];
  await step(ctx, () => page.getByRole('button', { name: '手动新建调拨单', exact: true }).click());
  const form = page.locator('.create-workspace');
  await form.waitFor({ state: 'visible' });
  const remark = page.getByPlaceholder('如: 领用 / 研发样品 / 余料退总仓 等');
  const item = label => form.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: label }) });
  async function choose(select, text) {
    const control = select.getByRole('combobox');
    const id = await control.getAttribute('aria-controls');
    await step(ctx, () => select.click());
    const options = page.locator(`[id="${id}"] .el-select-dropdown__item:not(.is-disabled)`);
    const option = text ? options.filter({ hasText: text }).first() : options.first();
    await option.waitFor({ state: 'visible', timeout: 10000 });
    await step(ctx, () => option.click());
  }
  await choose(item('调拨类型').locator('.el-select'), '同一工厂仓库调拨');
  await choose(item('调出仓库').locator('.el-select'));
  await choose(item('调入仓库').locator('.el-select'));
  for (const [label, value] of [['调拨日期', '2030-01-15'], ['预计到货', '2030-01-16']]) {
    const input = item(label).locator('input');
    await step(ctx, () => input.fill(value));
    await input.press('Tab'); await page.keyboard.press('Escape');
  }
  await step(ctx, () => remark.fill('只读验收本地草稿，未提交'));
  await step(ctx, () => page.getByRole('button', { name: '添加物料', exact: true }).click());
  await choose(form.locator('.el-table__body .el-select').nth(0), '原料');
  await choose(form.locator('.el-table__body .el-select').nth(1));
  const quantity = form.getByPlaceholder('数量', { exact: true });
  await step(ctx, () => quantity.fill('1')); await remark.click();
  const snapshot = async () => ({
    remark: await remark.inputValue(),
    date: await item('调拨日期').locator('input').inputValue(),
    arrival: await item('预计到货').locator('input').inputValue(),
    source: await item('调出仓库').locator('.el-select__selection').innerText(),
    target: await item('调入仓库').locator('.el-select__selection').innerText(),
    quantity: await quantity.inputValue(),
    selectedMaterial: await form.locator('.el-table__body .el-select').nth(1).innerText(),
  });
  const before = await snapshot();
  if (!before.source || !before.target || !before.selectedMaterial || Number(before.quantity) !== 1) throw new Error('Transfer draft baseline incomplete');
  const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
  async function activate(target) {
    const tabs = page.locator('.workspace-tab');
    for (let i = 0; i < await tabs.count(); i++) if (await tabs.nth(i).getAttribute('data-workspace-key') === target) { await step(ctx, () => tabs.nth(i).click()); return; }
    throw new Error('Expected transfer workspace tab missing');
  }
  screenshots.push(await ctx.screenshot('transfer-draft-before'));
  for (let round = 0; round < 3; round++) {
    await activate('/transfer/list');
    await page.waitForURL(url => url.pathname === '/transfer/list');
    await activate(key);
    await remark.waitFor({ state: 'visible' });
    assertPreserved(before, await snapshot());
  }
  screenshots.push(await ctx.screenshot('transfer-draft-three-returns'));
  // Closing only the locally edited tab discards our unsaved input.
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await page.waitForURL(url => url.pathname === '/transfer/list');
  assertSafe(ctx);
  return { retainedFields: Object.keys(before), roundTrips: 3, localDraftClosed: true, screenshots,
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'transfer-draft-readonly', defaultEnabled: false, assertPreserved, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'transfer-draft-readonly', path: '/transfer/list', screenshot: true, inspect }) };
