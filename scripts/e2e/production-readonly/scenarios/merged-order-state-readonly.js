'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { assertSafe } = require('./visual-unverified-followup-readonly');

async function inspect(page, _body, ctx) {
  const listResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && /\/production-plans\?/.test(r.url()));
  await page.reload();
  const response = await listResponse;
  if (!response.ok()) throw new Error('Production plan list failed');
  const payload = await response.json();
  const plans = Array.isArray(payload.data) ? payload.data : payload.data?.content;
  if (payload.success !== true || !Array.isArray(plans)) throw new Error('Unexpected production plan list');
  const candidate = plans.find(p => p.status === 'IN_PROGRESS' && p.productTypeId && p.sourceOrderId
    && Array.isArray(p.sourceOrderIds) && p.sourceOrderIds.some(id => id !== p.sourceOrderId));
  if (!candidate) return { scannedPlanCount: plans.length, limits: 'Current list page only; no existing active merged plan found; no production data created', assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' } };
  const extraId = candidate.sourceOrderIds.find(id => id !== candidate.sourceOrderId);
  const orderResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && new URL(r.url()).pathname.endsWith('/sales/orders/' + encodeURIComponent(extraId)));
  const linkedResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && new URL(r.url()).pathname.endsWith('/production-plans/by-sales-order/' + encodeURIComponent(extraId)));
  await page.goto(ctx.baseUrl + '/sales/orders/' + encodeURIComponent(extraId));
  const [orderRes, linkedRes] = await Promise.all([orderResponse, linkedResponse]);
  if (!orderRes.ok() || !linkedRes.ok()) throw new Error('Merged order detail read failed');
  const orderPayload = await orderRes.json(), linkedPayload = await linkedRes.json();
  const items = orderPayload.data?.items;
  if (orderPayload.success !== true || linkedPayload.success !== true) throw new Error('Merged order read unsuccessful');
  if (!Array.isArray(linkedPayload.data) || !linkedPayload.data.some(p => p.id === candidate.id)) throw new Error('Merged plan absent from associated order');
  if (!Array.isArray(items) || !items.length || !items.every(i => i.productTypeId === candidate.productTypeId)) {
    return { limits: 'Existing merged order includes other products; full coverage not proven by this narrow scenario', assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' } };
  }
  const button = page.getByRole('button', { name: '生产中', exact: true });
  await button.waitFor({ state: 'visible', timeout: 20000 });
  if (!await button.isDisabled()) throw new Error('Merged order offers duplicate production');
  await button.scrollIntoViewIfNeeded();
  assertSafe(ctx);
  return { scannedPlanCount: plans.length, sourceOrderCount: candidate.sourceOrderIds.length,
    matchedItemCount: items.length, linkedPlanFound: true, productionDisabled: true,
    screenshots: [await ctx.screenshot('merged-order-production-state')],
    limits: 'Existing active merged order read only; no creation, database transaction or cancellation mutation',
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'merged-order-state-readonly', defaultEnabled: false, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'merged-order-state-readonly', path: '/production/plans', screenshot: true, inspect }) };
