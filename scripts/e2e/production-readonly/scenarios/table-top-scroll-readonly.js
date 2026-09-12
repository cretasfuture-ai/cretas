'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertPosition(m) {
  if (!m.external || m.overlap > 1 || m.widthDifference > 2) throw new Error('Top scrollbar intrudes into the table or has the wrong width');
  if (m.bodyRange <= 0 || !m.visible) throw new Error('UNVERIFIED: no visible overflowing table');
  if (Math.abs(m.topLeft - m.bodyLeft) > 2 || Math.abs(m.headerLeft - m.bodyLeft) > 2) throw new Error('Top/header/body scroll offsets disagree');
}

function scenario(id, path) {
  const inspect = async (page, _body, ctx) => {
    const table = page.locator('main.app-content .el-table').first();
    await table.locator('.el-table__body tbody tr').first().waitFor({ state: 'visible', timeout: 15000 });
    const bar = table.locator('xpath=preceding-sibling::*[1][contains(@class,"cretas-table-top-scrollbar")]');
    const measure = () => table.evaluate(el => {
      const top = el.previousElementSibling;
      const wrap = el.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
      const header = el.querySelector('.el-table__header-wrapper');
      const a = top.getBoundingClientRect(), b = el.getBoundingClientRect();
      return { external: top.classList.contains('cretas-table-top-scrollbar') && !el.contains(top),
        visible: !top.hidden && a.height > 0, overlap: a.bottom - b.top,
        widthDifference: Math.abs(a.width - b.width), topRange: top.scrollWidth - top.clientWidth,
        bodyRange: wrap.scrollWidth - wrap.clientWidth, topLeft: top.scrollLeft,
        bodyLeft: wrap.scrollLeft, headerLeft: header.scrollLeft, barHeight: a.height };
    });
    async function wheel(target, delta, horizontal = false) {
      if (!await target.isVisible()) {
        const diagnostic = await target.evaluate(el => ({ className: el.className, hidden: el.hidden,
          display: getComputedStyle(el).display, width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height,
          parentClass: el.parentElement?.className }));
        await ctx.screenshot(`${id}-hidden-scroll-target`);
        throw new Error(`UNVERIFIED: scroll target hidden ${JSON.stringify(diagnostic)}`);
      }
      await target.scrollIntoViewIfNeeded();
      await target.hover();
      await step(ctx, () => page.mouse.wheel(horizontal ? delta : 0, horizontal ? 0 : delta));
      await page.waitForTimeout(200);
    }
    const snapshots = [], screenshots = [], nonOverflowWidths = [], reverseGestures = [];
    for (const width of [1440, 1024]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(300);
      const initial = await measure();
      if (initial.bodyRange <= 0) {
        if (initial.visible) throw new Error('Scrollbar occupies space when the table does not overflow');
        nonOverflowWidths.push(width);
        screenshots.push(await ctx.screenshot(`${id}-${width}-no-overflow`));
        continue;
      }
      await bar.waitFor({ state: 'visible', timeout: 5000 });
      await wheel(bar, -100000);
      let m = await measure(); assertPosition(m);
      if (m.bodyLeft > 2) throw new Error('Top scrollbar did not reach the left edge');
      await wheel(bar, 120);
      m = await measure(); assertPosition(m);
      if (m.bodyLeft < 50) throw new Error('Top wheel did not move table content');
      const beforeReverse = m.bodyLeft;
      const bottom = table.locator(':scope > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__bar.is-horizontal');
      if (await bottom.isVisible()) {
        await wheel(bottom, -60);
        reverseGestures.push('bottom-scrollbar-wheel');
      } else {
        // Some tables hide EP's synthetic bottom bar. Exercise their real
        // scrolling content with a horizontal wheel gesture, never force-click hidden UI.
        await wheel(table.locator(':scope > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap'), -60, true);
        reverseGestures.push('body-horizontal-wheel');
      }
      m = await measure(); assertPosition(m);
      if (m.bodyLeft >= beforeReverse - 20) throw new Error('Body scrollbar did not drive the top scrollbar');
      await wheel(bar, 100000);
      m = await measure(); assertPosition(m);
      if (Math.abs(m.bodyRange - m.bodyLeft) > 2) throw new Error('Top scrollbar did not reach the right edge');
      snapshots.push({ width, ...m });
      screenshots.push(await ctx.screenshot(`${id}-${width}-right`));
    }
    if (!snapshots.length) throw new Error('UNVERIFIED: no overflowing viewport was exercised');
    await wheel(bar, -100000);
    const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    if (!key) throw new Error('Original tab identity is missing');
    await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
    await page.waitForFunction(original => document.querySelector('.workspace-tab-container.active .workspace-tab')?.getAttribute('data-workspace-key') !== original, key);
    const copyKey = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    await step(ctx, () => page.locator(`.workspace-tab[data-workspace-key=${JSON.stringify(key)}]`).click());
    await table.waitFor({ state: 'visible' });
    if (await bar.count() !== 1) throw new Error('Tab reactivation duplicated or removed the scrollbar');
    await wheel(bar, 120);
    const restored = await measure(); assertPosition(restored);
    if (restored.bodyLeft < 50) throw new Error('Reactivated scrollbar no longer works');
    await wheel(bar, -100000);
    await step(ctx, () => page.locator('.workspace-tab-container').filter({ has: page.locator(`[data-workspace-key=${JSON.stringify(copyKey)}]`) }).getByRole('button', { name: '关闭任务', exact: true }).click());
    screenshots.push(await ctx.screenshot(`${id}-restored`));
    assertSafe(ctx);
    return { viewportWidths: snapshots.map(m => m.width), nonOverflowWidths, reverseGestures, bodyRanges: snapshots.map(m => m.bodyRange),
      topRanges: snapshots.map(m => m.topRange), rightOffsets: snapshots.map(m => m.bodyLeft),
      headerOffsets: snapshots.map(m => m.headerLeft), topOffsets: snapshots.map(m => m.topLeft),
      barHeights: snapshots.map(m => m.barHeight), maximumOverlap: Math.max(...snapshots.map(m => m.overlap)),
      maximumWidthDifference: Math.max(...snapshots.map(m => m.widthDifference)),
      topWheelPassed: true, bodyToTopPassed: true, bothEndpointsPassed: true, reactivationPassed: true,
      screenshots, limits: 'Current populated tables only; native cursor/DPI, touch, all-page coverage and elapsed-time efficiency not measured',
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  };
  return { id, defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id, path, screenshot: true, inspect: page => inspect(page, '', ctx) }) };
}
module.exports = { assertPosition,
  sales: scenario('table-top-scroll-sales-readonly', '/sales/orders'),
  purchase: scenario('table-top-scroll-purchase-readonly', '/procurement/orders'),
  transfer: scenario('table-top-scroll-transfer-readonly', '/transfer/list') };
