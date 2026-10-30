'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const { ROUTES } = require('../config/routes');

function check(condition, message) {
  if (!condition) throw new Error(`Sidebar motion assertion: ${message}`);
}

async function frames(page) {
  return page.evaluate(async () => {
    const samples = [];
    const start = performance.now();
    const iconBefore = document.querySelector('.sidebar-menu > .el-sub-menu > .el-sub-menu__title .el-icon').getBoundingClientRect();
    const iconStartX = iconBefore.x;
    const iconStartWidth = iconBefore.width;
    document.querySelector('.collapse-btn').click();
    do {
      await new Promise(requestAnimationFrame);
      const rail = document.querySelector('.app-sidebar');
      const title = document.querySelector('.sidebar-menu > .el-sub-menu > .el-sub-menu__title .menu-title-copy');
      const leaf = document.querySelector('.sidebar-menu > .el-menu-item .menu-title-copy');
      const icon = document.querySelector('.sidebar-menu > .el-sub-menu > .el-sub-menu__title .el-icon');
      const logo = document.querySelector('.logo-copy');
      samples.push({ timeMs: performance.now() - start, width: rail.getBoundingClientRect().width,
        mainX: document.querySelector('.app-main').getBoundingClientRect().x,
        opacity: title ? Number(getComputedStyle(title).opacity) : null,
        leafOpacity: leaf ? Number(getComputedStyle(leaf).opacity) : null,
        logoOpacity: logo ? Number(getComputedStyle(logo).opacity) : null,
        iconStartX, iconStartWidth,
        iconX: icon?.getBoundingClientRect().x, iconWidth: icon?.getBoundingClientRect().width });
    } while (performance.now() - start < 420);
    return samples;
  });
}

async function inspectMotion(page, _body, ctx) {
  const viewport = page.viewportSize();
  const originallyReduced = await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches);
  const screenshots = [];
  let originallyCollapsed;
  const button = page.locator('.collapse-btn');
  const collapsed = () => page.locator('.app-sidebar').evaluate(e => e.classList.contains('is-collapsed'));
  async function expand() {
    if (await collapsed()) await button.click();
    await page.waitForTimeout(450);
  }
  try {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await button.waitFor({ state: 'visible' });
    originallyCollapsed = await collapsed();
    await expand();
    await page.locator('.sidebar-menu > .el-sub-menu .menu-title-copy').first().waitFor();
    screenshots.push(await ctx.screenshot('sidebar-motion-expanded'));
    const closing = await frames(page);
    const intermediate = value => typeof value === 'number' && value > 0 && value < 1;
    check(closing.some(s => intermediate(s.opacity)), 'title must fade out through intermediate opacity');
    check(closing.some(s => intermediate(s.leafOpacity)), 'leaf title must fade before moving into tooltip');
    check(closing.some(s => intermediate(s.logoOpacity)), 'brand text must fade out');
    check(closing.some(s => s.width > 65 && s.width < 219), 'sidebar width must transition');
    check(closing.every(s => Math.abs(s.mainX - s.width) < 2), 'content offset must track sidebar width');
    check(closing.every(s => Math.abs(s.iconX - s.iconStartX) < 2), 'icon horizontal alignment must remain stable');
    check(closing.every(s => Math.abs(s.iconWidth - s.iconStartWidth) < 1), 'icon must not shrink');
    check(Math.abs(closing.at(-1).width - 64) < 1 && closing.at(-1).opacity === 0, 'collapsed final state must be 64px with hidden text');
    screenshots.push(await ctx.screenshot('sidebar-motion-collapsed'));

    const opening = await frames(page);
    check(opening.some(s => intermediate(s.opacity)), 'title must fade in through intermediate opacity');
    check(Math.abs(opening.at(-1).width - 220) < 1 && opening.at(-1).opacity === 1, 'expanded final state must restore text');
    await button.click();
    await page.waitForTimeout(45);
    await button.click();
    await page.waitForTimeout(450);
    check(!await collapsed() && !await page.locator('.sidebar-menu.el-menu--collapse').count(), 'rapid reversal must cancel the pending collapse');

    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const reduced = await frames(page);
    check(reduced.every(s => s.opacity === 0 && Math.abs(s.width - 64) < 1), 'reduced-motion must suppress animation and hiding delay');
    await expand();
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.setViewportSize({ width: 390, height: 900 });
    await button.click();
    await page.waitForTimeout(450);
    const phone = await page.locator('.sidebar-menu > .el-sub-menu .menu-title-copy').first().evaluate(e => ({ opacity: getComputedStyle(e).opacity, visibility: getComputedStyle(e).visibility, width: e.getBoundingClientRect().width }));
    check(phone.opacity === '1' && phone.visibility === 'visible' && phone.width > 20, 'mobile drawer must retain readable text');
    screenshots.push(await ctx.screenshot('sidebar-motion-mobile'));
    check(ctx.mutationGuard.snapshot().actualBusinessWrites === 0 && ctx.mutationGuard.snapshot().blockedMutationAttempts.length === 0, 'read-only mutation guard must remain clean');
    return { closing, opening, reduced, phone, rapidReversalPassed: true, screenshots,
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  } catch (error) {
    if (!String(error.message).startsWith('Sidebar motion assertion:')) throw error;
    return { error: error.message, screenshots, assessment: { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' } };
  } finally {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    if (originallyCollapsed !== undefined && await collapsed() !== originallyCollapsed) await button.click();
    if (viewport) await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: originallyReduced ? 'reduce' : 'no-preference' });
  }
}

module.exports = { id: 'sidebar-motion-readonly', defaultEnabled: false, inspectMotion,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'sidebar-motion-readonly', path: ROUTES.dashboard,
    landmarks: ['工作台'], inspect: inspectMotion }) };
