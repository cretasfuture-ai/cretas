'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { routeIdentity } = require('./visual-page-census-readonly');
const { createScenarioResult } = require('../core/result-schema');

function assertSafe(ctx) {
  const state = ctx.mutationGuard.snapshot();
  if (state.actualBusinessWrites || state.blockedMutationAttempts.length) throw new Error('Read-only guard stopped follow-up');
}
async function step(ctx, action) { assertSafe(ctx); const value = await action(); assertSafe(ctx); return value; }
async function verifyTarget(page, ctx, path, heading, tab) {
  // Element Plus selects its pane before router.replace resolves. Keep the
  // exact route predicate, but wait for navigation instead of racing the UI.
  await page.waitForURL(url => routeIdentity(path, ctx.baseUrl, String(url)).routeMatches,
    { timeout: 8000 });
  const identity = routeIdentity(path, ctx.baseUrl, page.url());
  if (!identity.routeMatches) throw new Error('UNVERIFIED: final route does not match bounded target');
  const main = page.locator('main.app-content, main[role="main"], main').first();
  await main.waitFor({ state: 'visible', timeout: 8000 });
  if (heading) await main.getByText(heading, { exact: true }).first().waitFor({ state: 'visible', timeout: 8000 });
  if (tab) {
    const selected = main.locator(`.hub-tabs #tab-${tab}`);
    if (await selected.getAttribute('aria-selected') !== 'true' || !await main.locator(`#pane-${tab}`).isVisible()) throw new Error('UNVERIFIED: selected tab/panel mismatch');
  }
  assertSafe(ctx);
  return { actualPath: path, routeMatches: true, headingVisible: Boolean(heading), activeTab: tab || null };
}
const FOLLOWUP_PAGES = Object.freeze([
  { id: 'dashboard', path: '/dashboard', heading: '快捷操作' },
  { id: 'label-qc', path: '/quality/label-qc', heading: '包装标签拍检' },
  { id: 'pending-queue', path: '/production/kanban?tab=pending-queue', heading: '未完成计划队列', tab: 'pending-queue' },
  { id: 'restock-board', path: '/production/kanban?tab=restock-board', tab: 'restock-board' },
  { id: 'bom-achievement', path: '/production/analysis-query?tab=bom-achievement', heading: 'BOM达成率分析', tab: 'bom-achievement' },
]);
async function runFollowup(ctx, targets = FOLLOWUP_PAGES, id = 'visual-unverified-followup-readonly') {
  const result = createScenarioResult(id);
  const start = Date.now();
  for (const target of targets) {
    assertSafe(ctx);
    const pageResult = await runReadOnlyPageScenario(ctx, { id: `followup-${target.id}`, path: target.path, screenshot: true, inspect: async page => {
      try {
        const identity = await verifyTarget(page, ctx, target.path, target.heading, target.tab);
        // Query-only navigation controls. No row action, export, review or create control.
        let operation = 'bounded navigation and visible content';
        if (target.id === 'label-qc') {
          for (const [label, heading] of [['已完成记录', '已完成记录'], ['待人工审核', '审核队列']]) {
            const control = page.locator('.el-radio-button').filter({ hasText: label });
            await step(ctx, () => control.click({ timeout: 5000 }));
            await page.locator('.queue-toolbar h2').filter({ hasText: heading }).waitFor({ state: 'visible', timeout: 5000 });
            if (!await control.locator('input[type="radio"]').isChecked()) throw new Error('UNVERIFIED: queue radio did not become checked');
            assertSafe(ctx);
          }
          operation = 'completed queue then pending queue; checked radio and queue heading verified; no review action';
        } else if (target.tab) {
          const alternate = target.id === 'bom-achievement' ? 'process-io' : target.tab === 'pending-queue' ? 'restock-board' : 'pending-queue';
          await step(ctx, () => page.locator(`.hub-tabs #tab-${alternate}`).click({ timeout: 5000 }));
          await page.locator(`#pane-${alternate}`).waitFor({ state: 'visible', timeout: 8000 });
          await step(ctx, () => page.locator(`.hub-tabs #tab-${target.tab}`).click({ timeout: 5000 }));
          await verifyTarget(page, ctx, target.path, target.heading, target.tab);
          operation = `switch to ${alternate} and return; distinct selected pane verified`;
        }
        return { ...identity, operation, assessment: { result: 'PASS', rootCauseClass: 'none' } };
      } catch (error) { assertSafe(ctx); return { reason: String(error.message), assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } }; }
    } });
    result.pageEvidence.push({ page: target.id, result: pageResult.result, ...Object.assign({}, ...pageResult.pageEvidence) });
    for (const key of ['apiEvidence','consoleErrors','consoleWarnings','httpErrors','blockedMutationAttempts','screenshots']) result[key].push(...pageResult[key]);
    result.actualBusinessWrites += pageResult.actualBusinessWrites;
    if (pageResult.actualBusinessWrites || pageResult.blockedMutationAttempts.length) break;
  }
  result.result = result.actualBusinessWrites || result.blockedMutationAttempts.length ? 'TOOL_ERROR' : result.pageEvidence.length === targets.length && result.pageEvidence.every(p => p.result === 'PASS') ? 'PASS' : 'UNVERIFIED';
  result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool'; result.durationMs = Date.now()-start;
  return result;
}
module.exports = { id: 'visual-unverified-followup-readonly', defaultEnabled: false, assertSafe, step, verifyTarget, FOLLOWUP_PAGES, run: runFollowup };
