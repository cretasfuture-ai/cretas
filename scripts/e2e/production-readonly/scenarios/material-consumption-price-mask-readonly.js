'use strict';

const { ROUTES } = require('../config/routes');
const { pathnameOf } = require('../core/url-utils');
const { runReadOnlyPageScenario } = require('./_shared');

const TARGET_BATCH_NUMBER = 'PB-PLAN-1787036063746-8719B078-74084';
const EXPECTED_ITEM_COUNT = 4;
const EXPECTED_ROLE = 'quality_inspector';
const ROOT_PRICE_KEYS = ['totalPlannedCost', 'totalActualCost', 'totalCost', 'subtotal'];
const ITEM_PRICE_KEYS = ['unitPrice', 'cost', 'totalCost', 'subtotal', 'lineAmount'];
const PRICE_KEY_PATTERN = /(price|cost|amount|subtotal)/i;

async function findTargetBatch(page, factoryId) {
  return page.evaluate(async ({ expectedFactoryId, batchNumber }) => {
    const pageSize = 100;
    for (let pageNumber = 1; pageNumber <= 50; pageNumber += 1) {
      const url = `/api/mobile/${encodeURIComponent(expectedFactoryId)}/processing/batches?page=${pageNumber}&size=${pageSize}`;
      const response = await fetch(url, { credentials: 'same-origin' });
      const payload = await response.json().catch(() => null);
      if (!response.ok || payload?.success === false) {
        return { ok: false, status: response.status, pageNumber, reason: 'batch-list-request-failed' };
      }
      const data = payload?.data || payload || {};
      const rows = Array.isArray(data.content) ? data.content : [];
      const match = rows.find((row) => String(row?.batchNumber || '') === batchNumber);
      if (match) {
        return {
          ok: true,
          status: response.status,
          pageNumber,
          id: String(match.id),
          batchNumber: String(match.batchNumber),
        };
      }
      const totalPages = Number(data.totalPages || 0);
      if ((totalPages > 0 && pageNumber >= totalPages) || rows.length < pageSize) break;
    }
    return { ok: false, status: 200, reason: 'target-batch-not-found' };
  }, { expectedFactoryId: factoryId, batchNumber: TARGET_BATCH_NUMBER });
}

async function inspectSummaryResponse(page, factoryId, batchId) {
  return page.evaluate(async ({ expectedFactoryId, productionBatchId, rootPriceKeys, itemPriceKeys }) => {
    const url = `/api/mobile/${encodeURIComponent(expectedFactoryId)}/processing/material-consumptions/batch/${encodeURIComponent(productionBatchId)}/summary`;
    const response = await fetch(url, { credentials: 'same-origin' });
    const payload = await response.json().catch(() => null);
    const data = payload?.data || {};
    const items = Array.isArray(data.items) ? data.items : [];
    const rootKeys = Object.keys(data);
    const itemKeys = [...new Set(items.flatMap((item) => Object.keys(item || {})))];
    const hasMaterialNames = items.every((item) => Boolean(item?.materialTypeName || item?.materialName));
    const hasPlannedAndActualQuantities = items.every((item) => item?.plannedQty != null && item?.actualQty != null);
    const forbiddenRootKeys = rootPriceKeys.filter((key) => rootKeys.includes(key));
    const forbiddenItemKeys = itemPriceKeys.filter((key) => itemKeys.includes(key));
    const suspiciousRootKeys = rootKeys.filter((key) => /(price|cost|amount|subtotal)/i.test(key));
    const suspiciousItemKeys = itemKeys.filter((key) => /(price|cost|amount|subtotal)/i.test(key));
    return {
      ok: response.ok && payload?.success !== false,
      status: response.status,
      itemCount: items.length,
      hasMaterialNames,
      hasPlannedAndActualQuantities,
      forbiddenRootKeyCount: forbiddenRootKeys.length,
      forbiddenItemKeyCount: forbiddenItemKeys.length,
      suspiciousRootKeyCount: suspiciousRootKeys.length,
      suspiciousItemKeyCount: suspiciousItemKeys.length,
      forbiddenRootKeys,
      forbiddenItemKeys,
      suspiciousRootKeys,
      suspiciousItemKeys,
      rootKeys,
      itemKeys,
    };
  }, {
    expectedFactoryId: factoryId,
    productionBatchId: batchId,
    rootPriceKeys: ROOT_PRICE_KEYS,
    itemPriceKeys: ITEM_PRICE_KEYS,
  });
}

const scenario = {
  id: 'material-consumption-price-mask-readonly',
  path: ROUTES.bomAchievement,
  targetBatchNumber: TARGET_BATCH_NUMBER,
  expectedItemCount: EXPECTED_ITEM_COUNT,
  expectedRole: EXPECTED_ROLE,
  rootPriceKeys: ROOT_PRICE_KEYS,
  itemPriceKeys: ITEM_PRICE_KEYS,
  priceKeyPattern: PRICE_KEY_PATTERN,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'material-consumption-price-mask-readonly',
    path: '/mobile-only?role=quality_inspector',
    landmarks: ['请使用移动端 App'],
    timeoutMs: 60_000,
    inspect: async (page) => {
      const target = await findTargetBatch(page, ctx.expectedFactoryId);
      if (!target.ok || !target.id) {
        return {
          targetBatchFound: false,
          targetLookup: target,
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const summary = await inspectSummaryResponse(page, ctx.expectedFactoryId, target.id);
      const screenshot = await ctx.screenshot('material-consumption-price-mask-quality-inspector');
      const authenticatedRole = String(ctx.loginEvidence?.role || '').toLowerCase();
      const pass = authenticatedRole === EXPECTED_ROLE
        && ctx.loginEvidence?.landingPath === '/mobile-only'
        && target.batchNumber === TARGET_BATCH_NUMBER
        && summary.ok
        && summary.itemCount === EXPECTED_ITEM_COUNT
        && summary.hasMaterialNames
        && summary.hasPlannedAndActualQuantities
        && summary.forbiddenRootKeyCount === 0
        && summary.forbiddenItemKeyCount === 0
        && summary.suspiciousRootKeyCount === 0
        && summary.suspiciousItemKeyCount === 0;

      return {
        targetBatchFound: true,
        authenticatedRole,
        targetBatchNumber: target.batchNumber,
        targetBatchPage: target.pageNumber,
        webBoundary: {
          landingPath: pathnameOf(page.url()),
          mobileOnlyExpected: true,
          appUiFollowupRequired: true,
        },
        summary,
        screenshots: [screenshot],
        assessment: pass
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'backend' },
      };
    },
  }),
};

module.exports = scenario;
