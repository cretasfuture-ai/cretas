'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');
async function inspect(page, _body, ctx) {
  await page.locator('.plan-number-link').first().waitFor({ state: 'visible', timeout: 15000 });
  // Workflow entry always POSTs create-batch, even when the batch already exists.
  // Only an explicitly disabled Workflow on a currently visible plan is safe here.
  const candidates = await page.evaluate(async factoryId => {
    const root = `/api/mobile/${encodeURIComponent(factoryId)}`;
    const read = async path => { const response = await fetch(path, { credentials: 'same-origin' }); if (!response.ok) throw new Error('Read-only preflight failed'); return response.json(); };
    const plans = await read(`${root}/production-plans?page=1&size=10&status=UNFINISHED`);
    if (!plans.success || !Array.isArray(plans.data?.content)) throw new Error('Plan response does not support safe entry selection');
    const visible = new Set(Array.from(document.querySelectorAll('.plan-number-link')).map(e => e.textContent.trim()));
    const safe = [];
    for (const plan of plans.data.content) {
      if (!plan.productTypeId || !visible.has(plan.planNumber)) continue;
      const activation = await read(`${root}/product-process-workflows/${encodeURIComponent(plan.productTypeId)}/activation`);
      if (activation.success && activation.data?.enabled === false) safe.push(plan.planNumber);
    }
    return safe;
  }, ctx.expectedFactoryId);
  assertSafe(ctx);
  let entry;
  for (const number of candidates) {
    const candidate = page.getByRole('row').filter({ hasText: number }).getByRole('button', { name: '继续录入', exact: true });
    if (await candidate.count() === 1 && await candidate.isVisible()) { entry = candidate; break; }
  }
  if (!entry) return { reason: 'No visible plan with explicitly disabled Workflow; entry may create a production batch and is excluded from read-only acceptance', assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
  await entry.waitFor({ state: 'visible', timeout: 15000 });
  await step(ctx, () => entry.click());
  const table = page.locator('.yield-card-table');
  const header = table.locator('th').first();
  await header.waitFor({ state: 'visible', timeout: 15000 });
  const style = await header.evaluate(e => ({
    label: e.textContent.trim(), width: e.getBoundingClientRect().width,
    border: getComputedStyle(e.querySelector('button')).borderWidth,
    background: getComputedStyle(e.querySelector('button')).backgroundColor,
  }));
  if (style.label !== '序号' || style.width < 80 || style.border !== '0px') throw new Error('Yield sequence header does not match the accepted style');
  const rows = table.locator('.el-table__body tr td:first-child');
  const values = async () => (await rows.allTextContents()).map(v => Number(v.trim()));
  await step(ctx, () => header.locator('.sort-caret.ascending').click());
  const ascending = await values();
  await step(ctx, () => header.locator('.sort-caret.descending').click());
  const descending = await values();
  const sortable = ascending.length > 1 && ascending.every(Number.isFinite);
  if (sortable && (ascending.some((v,i) => i>0 && v<ascending[i-1]) || descending.some((v,i) => i>0 && v>descending[i-1]))) throw new Error('Yield sequence sorting regressed');
  assertSafe(ctx);
  return { ...style, rowCount: ascending.length, sorting: sortable ? 'PASS' : 'UNVERIFIED: fewer than two numeric rows', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'yield-header-r3-readonly', defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id: 'yield-header-r3-readonly', path: '/production/plans', screenshot: true, inspect }) };
