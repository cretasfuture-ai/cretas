'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const ROUTE = '/production/material-flow?tab=requisitions';
const STATUS_LABELS = { CLOSED: '已关单' };

function asOption(value) {
  if (!value || typeof value !== 'object') return null;
  const id = String(value.id || '');
  const requisitionNo = String(value.requisitionNo || '');
  const expectedStatus = String(value.expectedStatus || '');
  const expectedItems = Array.isArray(value.expectedItems) ? value.expectedItems : [];
  if (!id || !requisitionNo || expectedStatus !== 'CLOSED' || expectedItems.length === 0) return null;
  if (expectedItems.some((item) => !item || !item.materialTypeId
    || numberValue(item.issuedQty) === null || numberValue(item.returnedQty) === null
    || numberValue(item.consumedQty) === null || numberValue(item.wastageQty) === null)) return null;
  if (new Set(expectedItems.map((item) => String(item.materialTypeId))).size !== expectedItems.length) return null;
  return { id, requisitionNo, expectedStatus, expectedItems };
}

function numberValue(value) {
  if (value === null || value === undefined || typeof value === 'boolean') return null;
  if (typeof value === 'string' && value.trim() === '') return null;
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberEquals(actual, expected) {
  const left = numberValue(actual);
  const right = numberValue(expected);
  return left !== null && right !== null && left === right;
}

function itemMatches(item, expected) {
  return String(item?.materialTypeId || '') === String(expected.materialTypeId)
    && quantityFieldsMatch(item, expected);
}

function quantityFieldsMatch(item, expected) {
  return numberEquals(item?.issuedQty, expected.issuedQty)
    && numberEquals(item?.returnedQty, expected.returnedQty)
    && numberEquals(item?.consumedQty, expected.consumedQty)
    && numberEquals(item?.wastageQty, expected.wastageQty);
}

async function inspectMaterialRequisitionClosed(page, rawOption, screenshot) {
  const option = asOption(rawOption);
  if (!option) {
    return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' }, reason: 'missing exact private requisition option' };
  }

  const rows = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: option.requisitionNo });
  if (await rows.count() !== 1) {
    return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' }, reason: 'exact requisition row was not visible' };
  }
  const row = rows;

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && (() => {
      try {
        const url = new URL(response.url());
        return url.pathname === `/api/mobile/F006/material-requisitions/${encodeURIComponent(option.id)}`;
      } catch { return false; }
    })(), { timeout: 10_000 });
  await row.getByRole('button', { name: '详情', exact: true }).click();
  const detailResponse = await responsePromise;
  const payload = await detailResponse.json().catch(() => null);
  const data = payload?.data;
  const responseBound = payload?.success === true
    && String(data?.id || '') === option.id
    && String(data?.factoryId || '') === 'F006';

  const dialog = page.locator('.el-dialog:visible').filter({ hasText: option.requisitionNo }).last();
  await dialog.waitFor({ state: 'visible', timeout: 8_000 }).catch(() => {});
  if (!await dialog.isVisible().catch(() => false)) {
    return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' }, reason: 'exact detail dialog did not become visible' };
  }
  const dialogText = await dialog.innerText().catch(() => '');
  const visibleStatus = dialogText.includes(STATUS_LABELS[option.expectedStatus]);
  const responseItems = Array.isArray(data?.items) ? data.items : [];
  const responseIds = responseItems.map((item) => String(item?.materialTypeId || ''));
  const responseIdsUnique = responseIds.every((id) => id && responseIds.filter((candidate) => candidate === id).length === 1);
  const itemChecks = option.expectedItems.map((expected) => {
    const item = responseItems.find((candidate) => String(candidate?.materialTypeId || '') === String(expected.materialTypeId));
    return { materialTypeId: expected.materialTypeId, response: itemMatches(item, expected) };
  });
  const headers = await dialog.locator('.detail-table thead th').allInnerTexts();
  const headerIndex = (label) => headers.findIndex((header) => header.trim() === label);
  const issuedIndex = headerIndex('发出');
  const consumedIndex = headerIndex('实用');
  const wastageIndex = headerIndex('损耗');
  const returnedIndex = headerIndex('退回');
  const headersValid = [issuedIndex, consumedIndex, wastageIndex, returnedIndex].every((index) => index >= 0);
  const detailRows = dialog.locator('.detail-table .el-table__body-wrapper tbody tr');
  const rowCount = await detailRows.count();
  const visibleItemChecks = await Promise.all(option.expectedItems.map(async (expected) => {
    const responseIndex = responseItems.findIndex((candidate) => String(candidate?.materialTypeId || '') === String(expected.materialTypeId));
    if (responseIndex < 0 || responseIndex >= rowCount) return { materialTypeId: expected.materialTypeId, visible: false };
    const item = responseItems[responseIndex];
    if (!headersValid) return { materialTypeId: expected.materialTypeId, visible: false };
    const cells = detailRows.nth(responseIndex).locator('td');
    const actual = {
      issuedQty: await cells.nth(issuedIndex).innerText().catch(() => ''),
      consumedQty: await cells.nth(consumedIndex).innerText().catch(() => ''),
      wastageQty: await cells.nth(wastageIndex).innerText().catch(() => ''),
      returnedQty: await cells.nth(returnedIndex).innerText().catch(() => ''),
    };
    return { materialTypeId: expected.materialTypeId, visible: quantityFieldsMatch(actual, expected) };
  }));
  const exact = responseBound
    && String(data.status || '') === option.expectedStatus
    && responseItems.length === option.expectedItems.length
    && responseIdsUnique
    && itemChecks.every((check) => check.response)
    && visibleStatus
    && rowCount === option.expectedItems.length
    && headersValid
    && visibleItemChecks.every((check) => check.visible);
  const shot = screenshot ? await screenshot('material-requisition-closed-detail') : null;
  return {
    assessment: { result: exact ? 'PASS' : 'UNVERIFIED', rootCauseClass: exact ? 'none' : 'tool' },
    target: { id: option.id, requisitionNo: option.requisitionNo, expectedStatus: option.expectedStatus },
    response: { httpStatus: detailResponse.status(), exactFactoryAndId: responseBound, businessStatus: data?.status || null, itemCount: responseItems.length, uniqueMaterialIds: responseIdsUnique },
    visible: { statusLabel: STATUS_LABELS[option.expectedStatus], statusMatched: visibleStatus, itemRowCount: rowCount, itemChecks: visibleItemChecks },
    responseItemChecks: itemChecks,
    screenshots: shot ? [shot] : [],
  };
}

module.exports = {
  id: 'material-requisition-closed-readonly',
  defaultEnabled: false,
  route: ROUTE,
  inspectMaterialRequisitionClosed,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'material-requisition-closed-readonly',
    path: ROUTE,
    landmarks: ['领料单'],
    timeoutMs: 60_000,
    inspect: (page) => inspectMaterialRequisitionClosed(page, ctx.materialRequisition, (name) => ctx.screenshot(name)),
  }),
};
