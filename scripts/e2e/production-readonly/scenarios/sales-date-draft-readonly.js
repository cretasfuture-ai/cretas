'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe, verifyTarget } = require('./visual-unverified-followup-readonly');
const { pathnameOf } = require('../core/url-utils');
module.exports = { id: 'sales-date-draft-readonly', defaultEnabled: false, run: ctx => runReadOnlyPageScenario(ctx, {
  id: 'sales-date-draft-readonly', path: '/sales/orders/new', screenshot: false,
  inspect: async page => {
    await verifyTarget(page, ctx, '/sales/orders/new', '新建销售订单');
    const input = page.getByRole('textbox', { name: '交货日期', exact: true });
    const screenshots = [];
    await step(ctx, () => input.fill('20300115'));
    await step(ctx, () => input.press('Tab'));
    await page.waitForFunction(() => document.querySelector('input[aria-label="交货日期"]')?.value === '2030-01-15', null, { timeout: 5000 });
    const valid = await input.inputValue();
    screenshots.push(await ctx.screenshot('sales-date-valid'));
    await step(ctx, () => input.fill('20260229'));
    await step(ctx, () => input.press('Tab'));
    const error = page.getByRole('alert').filter({ hasText: '日期无效' });
    await error.waitFor({ state: 'visible', timeout: 5000 });
    const invalid = await input.getAttribute('aria-invalid') === 'true';
    screenshots.push(await ctx.screenshot('sales-date-invalid'));
    await step(ctx, () => page.locator('.order-delivery-date .calendar-picker').click({ timeout: 5000 }));
    const calendar = page.locator('.el-picker-panel:visible');
    await calendar.waitFor({ state: 'visible', timeout: 5000 });
    screenshots.push(await ctx.screenshot('sales-date-calendar'));
    await step(ctx, () => calendar.locator('td.available:not(.disabled):not(.prev-month):not(.next-month)').first().click({ timeout: 5000 }));
    await error.waitFor({ state: 'hidden', timeout: 5000 });
    const chosen = await input.inputValue();
    const calendarRecovered = /^\d{4}-\d{2}-\d{2}$/.test(chosen) && await input.getAttribute('aria-invalid') === 'false';
    screenshots.push(await ctx.screenshot('sales-date-calendar-selected'));
    // Clear our only draft field before client-side leave; never invoke save validation.
    await step(ctx, () => input.fill(''));
    await step(ctx, () => input.press('Tab'));
    // Tab can reopen the calendar. Dismiss its popup before the page action.
    await step(ctx, () => page.keyboard.press('Escape'));
    await calendar.waitFor({ state: 'hidden', timeout: 5000 });
    await step(ctx, () => page.getByRole('button', { name: '取消', exact: true }).first().click({ timeout: 5000 }));
    const leave = page.getByRole('button', { name: '离开', exact: true });
    // Element Plus mounts the confirmation asynchronously after the click.
    // A clean draft can navigate directly; wait for either actual outcome.
    try {
      await Promise.race([
        page.waitForURL('**/sales/orders', { timeout: 8000 }),
        leave.waitFor({ state: 'visible', timeout: 8000 }),
      ]);
      if (await leave.isVisible()) await step(ctx, () => leave.click({ timeout: 5000 }));
      await page.waitForURL('**/sales/orders', { timeout: 8000 });
    } catch {
      assertSafe(ctx);
      screenshots.push(await ctx.screenshot('sales-date-leave-unverified'));
      return { validDate: valid, invalidInputDisplayed: invalid, calendarRecovered,
        leftWithoutSave: false, finalPath: pathnameOf(page.url()),
        visibleDialogs: await page.getByRole('dialog').count(), screenshots,
        limitation: 'Client-side leave did not reach the expected list URL; no save submitted',
        assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
    }
    assertSafe(ctx);
    return { validDate: valid, invalidInputDisplayed: invalid, backendValidation: 'UNVERIFIED: no save submitted', calendarRecovered, draftCleared: true, leftWithoutSave: true, screenshots, assessment: { result: invalid && calendarRecovered ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: invalid && calendarRecovered ? 'none' : 'frontend' } };
  },
}) };
