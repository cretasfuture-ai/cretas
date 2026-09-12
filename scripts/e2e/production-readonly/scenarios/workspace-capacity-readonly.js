'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertDraft(value, expected) {
  if (value !== expected) throw new Error('Open workspace draft lost its independent date');
}
async function inspect(page, _body, ctx) {
  const date = page.getByRole('textbox', { name: '交货日期', exact: true });
  const copies = [];
  for (let n = 1; n <= 11; n++) {
    if (n > 1) {
      const previous = page.url();
      await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
      await page.waitForURL(url => url.href !== previous, { timeout: 15000 });
    }
    const expected = `2030-01-${String(n).padStart(2, '0')}`;
    await date.waitFor({ state: 'visible', timeout: 15000 });
    await step(ctx, () => date.fill(expected.replaceAll('-', '')));
    await page.keyboard.press('Tab'); await page.keyboard.press('Escape');
    assertDraft(await date.inputValue(), expected);
    const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    copies.push({ key, expected });
  }
  if (await page.locator('.workspace-tab').count() < 11) throw new Error('Workspace silently removed an open draft');
  for (const { key, expected } of copies) {
    const tab = page.locator('.workspace-tab').filter({ has: page.locator('.workspace-tab-title') });
    const index = await tab.evaluateAll((elements, wanted) => elements.findIndex(e => e.getAttribute('data-workspace-key') === wanted), key);
    if (index < 0) throw new Error('Expected draft tab disappeared');
    await step(ctx, () => tab.nth(index).click());
    await page.waitForFunction(wanted => document.querySelector('.workspace-tab-container.active .workspace-tab')?.getAttribute('data-workspace-key') === wanted, key);
    assertDraft(await date.inputValue(), expected);
  }
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.locator('.workspace-tab-container.active').scrollIntoViewIfNeeded();
  const screenshot = await ctx.screenshot('workspace-eleven-drafts');
  // Discard only the local UI copies; never save or submit any business data.
  for (let n = 11; n > 1; n--) {
    await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
    const expectedKey = copies[n - 2].key;
    await page.waitForURL(url => `${url.pathname}${url.searchParams.has('_task') ? '?_task=' + url.searchParams.get('_task') : ''}` === expectedKey);
  }
  assertDraft(await date.inputValue(), copies[0].expected);
  assertSafe(ctx);
  return { independentlyRetainedDrafts: 11, closedCopies: 10, originalRetainedAfterCleanup: true, screenshots: [screenshot], assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-capacity-readonly', defaultEnabled: false, inspect, assertDraft,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-capacity-readonly', path: '/sales/orders/new', screenshot: true, inspect }) };
