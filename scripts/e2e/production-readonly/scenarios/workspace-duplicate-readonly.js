'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function verifyLabels(labels) {
  const expected = ['新建销售订单', '新建销售订单 2', '新建销售订单 3'];
  if (labels.length !== expected.length || expected.some(label => !labels.includes(label))) {
    throw new Error('Workspace copy labels are missing, repeated or compounded');
  }
}

async function inspect(page, _body, ctx) {
  const tabs = page.locator('.workspace-tab');
  const tabByTitle = title => tabs.filter({ has: page.locator('.workspace-tab-title').filter({ hasText: new RegExp(`^${title}$`) }) });
  const date = page.getByRole('textbox', { name: '交货日期', exact: true });
  const labels = () => page.locator('.workspace-tab-title').allTextContents();
  async function fillDate(raw, expected) {
    await date.waitFor({ state: 'visible', timeout: 15000 });
    await step(ctx, () => date.fill(raw));
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');
    if (await date.inputValue() !== expected) throw new Error('Draft date did not normalize');
  }
  async function copy(expected) {
    await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
    await page.waitForURL(url => url.searchParams.get('_taskLabel') === expected, { timeout: 10000 });
    await tabs.filter({ hasText: expected }).waitFor({ state: 'visible' });
  }
  async function activate(title) {
    await step(ctx, () => tabByTitle(title).click());
    await page.waitForFunction(expected => document.querySelector('.workspace-tab-container.active .workspace-tab-title')?.textContent === expected, title);
  }
  await fillDate('20300115', '2030-01-15');
  await copy('新建销售订单 2');
  await fillDate('20300216', '2030-02-16');
  await copy('新建销售订单 3');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  await activate('新建销售订单');
  if (await date.inputValue() !== '2030-01-15') throw new Error('Original draft lost its date');
  await activate('新建销售订单 2');
  if (await date.inputValue() !== '2030-02-16') throw new Error('Copied draft lost its independent date');
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await activate('新建销售订单');
  await copy('新建销售订单 2');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  if (await date.inputValue() === '2030-02-16') throw new Error('New copy reused the closed draft instance');
  const screenshots = [];
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    await tabByTitle('新建销售订单 2').scrollIntoViewIfNeeded();
    for (const ordinal of [2, 3]) {
      const tab = tabByTitle(`新建销售订单 ${ordinal}`);
      const number = tab.locator('.workspace-tab-number');
      if (!await number.isVisible()) throw new Error('Workspace ordinal is not visibly separate from truncated text');
      const bounds = await number.boundingBox();
      const button = await tab.boundingBox();
      if (!bounds || !button || bounds.width < 5 || bounds.x < button.x || bounds.x + bounds.width > button.x + button.width + 1) {
        throw new Error('Workspace ordinal is clipped outside its tab');
      }
    }
    await page.mouse.move(250, 170);
    await page.waitForTimeout(350);
    screenshots.push(await ctx.screenshot(`workspace-copy-${width}`));
  }
  assertSafe(ctx);
  return { uniqueLabels: true, ordinalsVisibleAtBothWidths: true, originalDraftPreserved: true, copiedDraftPreserved: true, closedCopyNotReused: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-duplicate-readonly', defaultEnabled: false, inspect, verifyLabels,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-duplicate-readonly', path: '/sales/orders/new', screenshot: true, inspect }) };
