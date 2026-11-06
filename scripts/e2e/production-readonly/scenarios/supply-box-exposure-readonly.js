'use strict';

const {runReadOnlyPageScenario} = require('./_shared');
const {assertCoordinatedActor} = require('./proposal-comparison-readonly');
const id = 'supply-box-exposure-readonly';
const decimal = /^(?:0|[1-9]\d*)(?:\.\d+)?$/;
function requireCondition(value, message) { if (!value) throw new Error(message); }
function assertTotals(totals) {
  requireCondition(totals && typeof totals.complete === 'boolean'
    && Number.isSafeInteger(totals.knownSourceCount) && totals.knownSourceCount >= 0
    && Number.isSafeInteger(totals.unknownSourceCount) && totals.unknownSourceCount >= 0,
  'Box source counts are invalid');
  for (const key of ['totalBoxQuantity', 'knownBoxSubtotal']) requireCondition(totals[key] === null
    || (typeof totals[key] === 'string' && decimal.test(totals[key])), 'Box quantity must be an exact decimal string or null');
  requireCondition((totals.knownSourceCount === 0) === (totals.knownBoxSubtotal === null), 'Known subtotal was invented or omitted');
  requireCondition(totals.complete ? totals.unknownSourceCount === 0 && totals.totalBoxQuantity !== null
    && totals.totalBoxQuantity === totals.knownBoxSubtotal : totals.totalBoxQuantity === null,
  'Unknown box totals were promoted to complete');
}
function assertBoxExposure(exposure) {
  requireCondition(exposure && exposure.scope === 'DISCOVERED_LINEAGE'
    && exposure.inventoryBasis === 'PHYSICAL_ON_HAND_INCLUDING_RESERVED'
    && exposure.quantityMeaning === 'PACKAGING_SNAPSHOT_EQUIVALENT', 'Box exposure meaning changed');
  assertTotals(exposure.stock); assertTotals(exposure.shipped);
  for (const key of ['stockSources', 'shipmentSources', 'customers', 'gaps']) requireCondition(Array.isArray(exposure[key]), 'Box source evidence is absent');
  for (const source of [...exposure.stockSources, ...exposure.shipmentSources]) {
    requireCondition(typeof source.sourceId === 'string' && typeof source.finishedGoodsBatchId === 'string'
      && Array.isArray(source.gaps), 'Box source identity is absent');
    requireCondition(source.boxQuantity === null ? source.gaps.length > 0
      : typeof source.boxQuantity === 'string' && decimal.test(source.boxQuantity), 'Unknown source was zero-filled');
  }
  for (const [kind, sources] of [['stock',exposure.stockSources], ['shipped',exposure.shipmentSources]]) {
    const known=sources.filter(source=>source.boxQuantity !== null).length;
    requireCondition(new Set(sources.map(source=>source.sourceId)).size === sources.length
      && exposure[kind].knownSourceCount === known
      && exposure[kind].unknownSourceCount >= sources.length-known,
    'Box source identities or aggregate counts contradict the actual sources');
  }
  for (const customer of exposure.customers) assertTotals(customer.totals);
  return true;
}
async function read(page, path) {
  return page.evaluate(async url => {
    const token = localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('Fresh session missing');
    const response = await fetch(url, {headers:{Authorization:`Bearer ${token}`}});
    const body = await response.json();
    if (!response.ok || body.success !== true) throw new Error('Scoped acceptance context read failed');
    return body.data;
  }, path);
}
async function inspect(page, _body, ctx) {
  requireCondition(ctx.expectedFactoryId === 'F006', 'F006 context required');
  assertCoordinatedActor(await read(page, '/api/mobile/auth/me'), ctx.expectedUsername);
  requireCondition(await read(page, '/api/mobile/F006/demo-sandbox/active-session') === null, 'Active sandbox requires coordination');
  const target = ctx.supplyImpact;
  requireCondition(target && typeof target.materialTypeId === 'string'
    && /^[A-Za-z0-9_-]{1,128}$/.test(target.materialTypeId)
    && typeof target.materialName === 'string' && target.materialName.trim().length > 0,
  'Explicit existing material from scoped readonly preflight is required');
  await page.getByPlaceholder('搜索原料名称 / 料号', {exact:true}).fill(target.materialName);
  await page.getByRole('button', {name:'搜索', exact:true}).click();
  const row = page.locator('.el-table__body tbody tr').filter({hasText:target.materialName});
  await row.first().waitFor({state:'visible',timeout:30000});
  requireCondition(await row.count() === 1, 'Material selection must resolve to one actual visible row');
  await row.locator('[data-test="row-supply-impact"]').click();
  const dialog = page.locator('[data-test="supply-impact-dialog"]');
  await dialog.waitFor({state:'visible'});
  const awaited = page.waitForResponse(response => {
    const url = new URL(response.url());
    return response.request().method() === 'GET' && url.pathname === '/api/mobile/F006/supply-impact/quality'
      && url.searchParams.get('materialTypeId') === target.materialTypeId;
  }, {timeout:30000});
  await dialog.getByRole('radio', {name:'质量问题', exact:true}).check();
  const response = await awaited; const body = await response.json();
  requireCondition(response.ok() && body.success === true && body.data.subject.id === target.materialTypeId,
    'Quality response is not the selected actual material');
  assertBoxExposure(body.data.boxExposure);
  const exposure = body.data.boxExposure;
  const panel = dialog.getByTestId('impact-box-exposure'); await panel.waitFor({state:'visible'});
  requireCondition((await panel.innerText()).includes('在库实物含预留'), 'Physical inventory basis is not visible');
  for (const [kind, testId] of [['stock','box-stock-total'], ['shipped','box-shipped-total']]) {
    const text = (await panel.getByTestId(testId).innerText()).trim();
    requireCondition(text === (exposure[kind].complete ? exposure[kind].totalBoxQuantity : '待核对'), 'UI total differs from server uncertainty');
  }
  await panel.scrollIntoViewIfNeeded();
  return {factoryId:'F006',actorId:1309,materialTypeId:target.materialTypeId,
    sourceBatchCount:body.data.sourceBatches.length,finishedBatchCount:body.data.finishedBatches.length,
    stockSources:exposure.stockSources.length,shipmentSources:exposure.shipmentSources.length,
    stockComplete:exposure.stock.complete,shippedComplete:exposure.shipped.complete,
    knownStockSources:exposure.stock.knownSourceCount,knownShipmentSources:exposure.shipped.knownSourceCount,
    observedGapCodes:exposure.gaps.map(gap=>gap.code),sealedCartonsVerified:false,uncertaintyRendered:true,
    screenshots:[await ctx.screenshot(id,'actual-exposure')],
    assessment:{result:body.data.finishedBatches.length > 0 && exposure.stock.knownSourceCount+exposure.shipped.knownSourceCount > 0 ? 'PASS' : 'UNVERIFIED',
      rootCauseClass:body.data.finishedBatches.length > 0 && exposure.stock.knownSourceCount+exposure.shipped.knownSourceCount > 0 ? 'none' : 'data'}};
}
module.exports = {id,defaultEnabled:false,assertBoxExposure,
  run:ctx=>runReadOnlyPageScenario(ctx,{id,path:'/warehouse/material-types',landmarks:['原料'],screenshot:false,inspect})};
