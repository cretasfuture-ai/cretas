'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { splitUrl, resolveUrl } = require('../core/url-utils');

function routeIdentity(path, baseUrl, actualUrl) {
  const expected = splitUrl(resolveUrl(path, baseUrl));
  const actual = splitUrl(actualUrl);
  // Census routes have a fixed, canonical query string; reject extra/changed parameters.
  return { routeMatches: actual.origin === expected.origin && actual.pathname === expected.pathname && actual.search === expected.search,
    expectedTab: expected.search.startsWith('?tab=') ? expected.search.slice(5) : null };
}

// Existing router paths only. No create/edit/detail IDs or arbitrary URL input.
const VISUAL_PAGES = Object.freeze([
  ['dashboard', '/dashboard', '首页'],
  ['purchase-orders', '/procurement/orders', '采购'],
  ['suppliers', '/procurement/suppliers', '供应商'],
  ['sales-orders', '/sales/orders', '销售'],
  ['sales-shipments', '/sales/shipments', '出货'],
  ['warehouse-shipments', '/warehouse/shipments', '出货'],
  ['materials', '/warehouse/materials', '入库'],
  ['inventory-warehouse', '/warehouse/inventory-query?tab=by-warehouse', '库存'],
  ['inventory-total', '/warehouse/inventory-query?tab=total', '库存'],
  ['inventory-batch', '/warehouse/inventory-query?tab=batch', '库存'],
  ['inventory-semi', '/warehouse/inventory-query?tab=semi-finished', '库存'],
  ['stocktake', '/warehouse/stocktake-query?tab=main', '盘点'],
  ['stocktake-semi', '/warehouse/stocktake-query?tab=semi-finished', '盘点'],
  ['transfers', '/transfer/list', '调拨'],
  ['quality', '/quality/inspections', '质检'],
  ['quality-disposition', '/quality/disposition', '处置'],
  ['label-qc', '/quality/label-qc', '标签'],
  ['products', '/system/products', 'SKU'],
  ['workflow', '/system/product-processes', 'Workflow'],
  ['plans', '/production/plans', '生产'],
  ['plan-queue', '/production/pending-queue', '计划'],
  ['restock-board', '/production/restock-board', '备货'],
  ['cost-summary', '/production/bom-achievement', '成本'],
  ['bom', '/production/bom', 'BOM'],
  ['finance', '/finance/ar-ap', '应收'],
].map(([id, path, landmark]) => Object.freeze({ id, path, landmark })));

async function inspectVisualPage(page, _body, ctx, item) {
  const guard = ctx.mutationGuard.snapshot();
  if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) return { assessment: { result: 'TOOL_ERROR', rootCauseClass: 'tool' } };
  const { routeMatches, expectedTab } = routeIdentity(item.path, ctx.baseUrl, page.url());
  const identity = await page.evaluate(({ landmark, tab }) => {
    const visible = el => Boolean(el && el.getBoundingClientRect().width && el.getBoundingClientRect().height && getComputedStyle(el).visibility !== 'hidden');
    const main = ['main.app-content', 'main', '[role="main"]', '.el-main', '.app-main']
      .map(selector => [...document.querySelectorAll(selector)].find(visible)).find(Boolean);
    const content = main?.cloneNode(true);
    content?.querySelectorAll('aside, nav, header, .sidebar, .el-menu, .el-breadcrumb').forEach(el => el.remove());
    const text = content?.textContent || '';
    const selected = main?.querySelector('.hub-tabs [role="tab"][aria-selected="true"]');
    const panel = selected && document.getElementById(selected.getAttribute('aria-controls'));
    return {
      mainFound: Boolean(main), bodyLandmarkMatches: text.includes(landmark),
      errorPage: /404|页面不存在|页面未找到|Page Not Found/i.test(text),
      expectedTab: tab, activeTabId: selected?.id || '',
      activePanelId: panel?.id || '', activePanelVisible: visible(panel),
      tabMatches: !tab || Boolean(selected?.id === `tab-${tab}` && panel?.id === `pane-${tab}` && visible(panel)),
    };
  }, { landmark: item.landmark, tab: expectedTab });
  const metrics = await page.evaluate(() => ({
    viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
    outerHorizontalOverflow: document.documentElement.scrollWidth > innerWidth + 2,
    tableCount: document.querySelectorAll('.el-table, table').length,
    rowCount: document.querySelectorAll('.el-table__body tbody tr').length,
    emptyStates: document.querySelectorAll('.el-empty, .el-table__empty-block').length,
    loadingVisible: [...document.querySelectorAll('.el-loading-mask')].some(el => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none'),
    errorVisible: [...document.querySelectorAll('.el-message--error, .el-alert--error')].some(el => el.getBoundingClientRect().width > 0),
    columnLabels: [...document.querySelectorAll('.el-table__header th')].map(el => (el.textContent || '').trim()).slice(0, 40),
  }));
  // Tab changes focus only. No activation/Enter, mutations or editable values.
  await page.keyboard.press('Tab');
  const focus = await page.evaluate(() => {
    const el = document.activeElement;
    const rect = el?.getBoundingClientRect();
    const style = el ? getComputedStyle(el) : null;
    return { tag: el?.tagName || '', visible: Boolean(rect?.width && rect?.height), outlineStyle: style?.outlineStyle || '', outlineWidth: style?.outlineWidth || '', boxShadow: style?.boxShadow || '' };
  });
  return { ...metrics, focus, routeMatches, ...identity, operation: 'navigate, inspect, one Tab, screenshot; no button activated', assessment: !routeMatches || !identity.mainFound || !identity.bodyLandmarkMatches || identity.errorPage || !identity.tabMatches || metrics.loadingVisible ? { result: 'UNVERIFIED', rootCauseClass: 'tool' } : metrics.errorVisible ? { result: 'PARTIAL_DEFECT', rootCauseClass: 'frontend' } : undefined };
}

async function runVisualCensus(ctx, pages = VISUAL_PAGES) {
  const started = Date.now();
  const result = createScenarioResult('visual-page-census-readonly', ctx.page.url());
  for (const item of pages) {
    const guard = ctx.mutationGuard.snapshot();
    if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) break;
    const pageResult = await runReadOnlyPageScenario(ctx, { id: `visual-${item.id}`, path: item.path, landmarks: [item.landmark], screenshot: true, inspect: (page, body, context) => inspectVisualPage(page, body, context, item) });
    result.pageEvidence.push({ page: item.id, route: item.path, result: pageResult.result, ...Object.assign({}, ...pageResult.pageEvidence), screenshots: pageResult.screenshots });
    for (const key of ['apiEvidence', 'consoleErrors', 'consoleWarnings', 'httpErrors', 'blockedMutationAttempts', 'screenshots']) result[key].push(...pageResult[key]);
    result.actualBusinessWrites += pageResult.actualBusinessWrites;
    if (pageResult.blockedMutationAttempts.length || pageResult.actualBusinessWrites) break;
  }
  const complete = result.pageEvidence.length === pages.length && result.pageEvidence.every(item => item.result === 'PASS');
  result.result = result.blockedMutationAttempts.length || result.actualBusinessWrites ? 'TOOL_ERROR' : complete ? 'PASS' : 'UNVERIFIED';
  result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
  result.durationMs = Date.now() - started;
  return assertScenarioResult(result);
}

module.exports = { id: 'visual-page-census-readonly', defaultEnabled: false, VISUAL_PAGES, routeIdentity, inspectVisualPage, runVisualCensus, run: ctx => runVisualCensus(ctx) };
