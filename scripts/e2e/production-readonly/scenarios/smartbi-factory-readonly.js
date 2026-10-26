'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const DETAIL_PAGE_SIZE = 50;
const MAX_CANVAS_CLICK_ATTEMPTS = 7;
const MAX_DETAIL_CANDIDATES = 6;
const DETAIL_ACCEPTANCE_DEADLINE_MS = 85_000;
const DETAIL_RESPONSE_TIMEOUT_MS = 8_000;

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST' && endpointPath(response.url()).replace(/\/$/, '') === DETAILS_PATH;
}

function requestJson(request) {
  try { return request.postDataJSON(); } catch { return null; }
}

function expectedDetailIdentity(expected) {
  return {
    factoryId: expected.factoryId,
    candidateId: expected.candidateId,
    source: expected.source,
    period: expected.period,
    contextIdPresent: typeof expected.contextId === 'string' && expected.contextId.length > 0,
  };
}

const DATE_DIMENSIONS = new Set(['日期', '月份', '生产日期', '入库日期', '销售日期', '会计期间', '月', '年月', 'date', 'month']);

function dimensionBaseName(field) {
  return String(field || '').replace(/[（(].*?[）)]$/, '').trim();
}

function pointValueMatches(value, name, field) {
  const text = String(value ?? '').trim();
  const wanted = String(name ?? '').trim();
  if (text === wanted) return true;
  if (!DATE_DIMENSIONS.has(dimensionBaseName(field))) return false;
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) return false;
  if (/^\d{4}-\d{2}$/.test(wanted)) {
    return `${parsed.getUTCFullYear()}-${String(parsed.getUTCMonth() + 1).padStart(2, '0')}` === wanted;
  }
  if (/^\d{1,2}月$/.test(wanted)) return String(parsed.getUTCMonth() + 1) === wanted.slice(0, -1);
  if (/^\d{4}$/.test(wanted)) return String(parsed.getUTCFullYear()) === wanted;
  return false;
}

function pointMatchingDimensions(data, point) {
  const allowed = Array.isArray(data?.availableFilters)
    ? data.availableFilters.map((item) => item?.field).filter((field) => typeof field === 'string' && field)
    : [];
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const matches = rows.map((row) => allowed.filter((field) => pointValueMatches(row?.[field], point.name, field)));
  const expectedDimension = point.dimension;
  const dimensionContract = expectedDimension
    ? allowed.includes(expectedDimension) && matches.every((fields) => fields.includes(expectedDimension))
    : true;
  return {
    allowedFields: allowed,
    expectedDimension: expectedDimension || null,
    rowMatches: matches,
    everyRowMatchesAllowedDimension: rows.length > 0 && matches.every((fields) => fields.length > 0),
    dimensionContract,
  };
}

function assessDetailsResponse({ status, request, body, expected, point = null }) {
  const payload = request || {};
  const data = body?.data;
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const expectedPeriod = expected.period == null ? null : String(expected.period);
  const checks = {
    http200: status === 200,
    success: body?.success === true,
    factory: data?.factoryId === expected.factoryId,
    candidate: data?.candidateId === expected.candidateId,
    source: data?.source === expected.source,
    period: expectedPeriod === null || String(data?.period) === expectedPeriod,
    context: payload.context_id === expected.contextId,
    page: payload.page === 1 && data?.page === 1,
    pageSize: payload.page_size === DETAIL_PAGE_SIZE && data?.pageSize === DETAIL_PAGE_SIZE,
    rows: rows.length <= DETAIL_PAGE_SIZE,
    total: Number.isInteger(data?.total) && data.total >= rows.length,
  };
  if (point) {
    checks.pointRequest = payload.point?.name === point.name
      && (point.dataIndex == null || payload.point?.dataIndex === point.dataIndex)
      && (!point.seriesName || payload.point?.seriesName === point.seriesName);
    const pointDimensions = pointMatchingDimensions(data, point);
    checks.pointRows = rows.length > 0 && Number.isInteger(data?.total) && data.total > 0
      && pointDimensions.everyRowMatchesAllowedDimension && pointDimensions.dimensionContract;
    checks.pointDimensionContract = pointDimensions.dimensionContract;
    checks.pointRowsMatchDimension = pointDimensions.everyRowMatchesAllowedDimension;
  } else {
    checks.noPointRequest = payload.point == null;
  }
  const passed = Object.values(checks).every(Boolean);
  return {
    result: passed ? 'PASS' : 'CONFIRMED_DEFECT',
    rootCauseClass: passed ? 'none' : 'backend',
    checks,
    identity: expectedDetailIdentity(expected),
    point: point || null,
    pointDimensions: point ? pointMatchingDimensions(data, point) : null,
    rowCount: rows.length,
    total: Number.isInteger(data?.total) ? data.total : null,
    columns: Array.isArray(data?.columns) ? data.columns : [],
    hasMore: data?.hasMore === true,
    request: {
      contextIdMatches: payload.context_id === expected.contextId,
      page: payload.page ?? null,
      pageSize: payload.page_size ?? null,
      pointPresent: payload.point != null,
      pointName: typeof payload.point?.name === 'string' ? payload.point.name : null,
    },
  };
}

function stableRowKey(row) {
  if (!row || typeof row !== 'object') return JSON.stringify(row);
  return JSON.stringify(Object.keys(row).sort().reduce((out, key) => {
    out[key] = row[key];
    return out;
  }, {}));
}

function duplicateCount(rows) {
  const counts = new Map();
  for (const row of rows || []) {
    const key = stableRowKey(row);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.values()].filter((count) => count > 1).reduce((sum, count) => sum + count - 1, 0);
}

function assessPageContinuity(first, second, expected = {}) {
  const firstData = first?.body?.data;
  const secondData = second?.body?.data;
  const firstRows = Array.isArray(firstData?.rows) ? firstData.rows : [];
  const secondRows = Array.isArray(secondData?.rows) ? secondData.rows : [];
  const total = firstData?.total;
  const expectedSecondCount = Number.isInteger(total) ? Math.min(DETAIL_PAGE_SIZE, Math.max(0, total - DETAIL_PAGE_SIZE)) : -1;
  const firstRequest = first?.request || {};
  const secondRequest = second?.request || {};
  const checks = {
    firstHttp200: first?.status === 200,
    secondHttp200: second?.status === 200,
    firstPage: firstData?.page === 1,
    secondPage: secondData?.page === 2,
    sameTotal: Number.isInteger(total) && total === secondData?.total,
    firstPageSize: firstData?.pageSize === DETAIL_PAGE_SIZE,
    secondPageSize: secondData?.pageSize === DETAIL_PAGE_SIZE,
    firstHasMore: firstData?.hasMore === true,
    secondCount: secondRows.length === expectedSecondCount,
    loadedCount: firstRows.length + secondRows.length === Math.min(Number.isInteger(total) ? total : 0, DETAIL_PAGE_SIZE * 2),
    firstRequestContext: !expected.contextId || firstRequest.context_id === expected.contextId,
    secondRequestContext: !expected.contextId || secondRequest.context_id === expected.contextId,
    firstRequestPage: firstRequest.page === 1,
    secondRequestPage: secondRequest.page === 2,
    firstRequestPageSize: firstRequest.page_size === DETAIL_PAGE_SIZE,
    secondRequestPageSize: secondRequest.page_size === DETAIL_PAGE_SIZE,
    secondFactory: !expected.factoryId || secondData?.factoryId === expected.factoryId,
    secondCandidate: !expected.candidateId || secondData?.candidateId === expected.candidateId,
    secondSource: !expected.source || secondData?.source === expected.source,
    secondPeriod: !expected.period || String(secondData?.period) === String(expected.period),
  };
  const allRows = [...firstRows, ...secondRows];
  const passed = Object.values(checks).every(Boolean);
  return {
    result: passed ? 'PASS' : 'CONFIRMED_DEFECT',
    rootCauseClass: passed ? 'none' : 'frontend',
    completeness: 'UNVERIFIED',
    completenessReason: 'Page responses prove the navigation contract only; an independent source snapshot or SQL comparison is required to prove no missing rows.',
    checks,
    total: Number.isInteger(total) ? total : null,
    firstRowCount: firstRows.length,
    secondRowCount: secondRows.length,
    loadedRowCount: allRows.length,
    duplicateRowCount: duplicateCount(allRows),
    note: 'Rows are counted in response order; duplicate business rows are not content-deduplicated. Completeness remains UNVERIFIED pending an independent source comparison.',
  };
}

function axisData(option, axisName) {
  const axis = option?.[axisName];
  const value = Array.isArray(axis) ? axis[0]?.data : axis?.data;
  return Array.isArray(value) ? value : [];
}

function seriesList(option) {
  return Array.isArray(option?.series) ? option.series : [];
}

function chartPointForCandidate(candidate) {
  if (candidate?.detailContext && candidate.detailContext.drilldown === false) return null;
  const option = candidate?.chart?.echartsOption;
  if (!option || typeof option !== 'object') return null;
  const series = seriesList(option);
  const type = String(candidate?.chart?.chartType || '').toLowerCase();
  const pie = series.find((item) => String(item?.type || '').toLowerCase() === 'pie')
    || ((type === 'pie' || type === 'donut' || type === 'nested_donut') ? series[0] : null);
  if (pie && Array.isArray(pie.data)) {
    const index = pie.data.findIndex((value) => value && typeof value === 'object' && String(value.name || '').trim());
    const dimension = pie.encode?.itemName || option.dataset?.dimensions?.[0] || null;
    if (index >= 0) return { name: String(pie.data[index].name).trim(), seriesName: pie.name, dataIndex: index, dimension, kind: 'pie' };
  }
  const bar = series.find((item) => String(item?.type || '').toLowerCase() === 'bar');
  if (bar) {
    const horizontal = type === 'bar_horizontal' || (axisData(option, 'xAxis').length === 0 && axisData(option, 'yAxis').length > 0);
    const categories = horizontal ? axisData(option, 'yAxis') : axisData(option, 'xAxis');
    const dataPoint = Array.isArray(bar.data) ? bar.data.find((value) => value && typeof value === 'object' && String(value.name || '').trim()) : null;
    const dataIndex = dataPoint ? bar.data.indexOf(dataPoint) : 0;
    const name = dataPoint?.name ?? categories[dataIndex];
    const axis = horizontal ? option.yAxis : option.xAxis;
    const dimension = (Array.isArray(axis) ? axis[0]?.name : axis?.name) || null;
    if (name != null && String(name).trim()) {
      return { name: String(name).trim(), seriesName: bar.name, dataIndex, dimension, kind: horizontal ? 'bar_horizontal' : 'bar' };
    }
  }
  return null;
}

function numericDataValue(candidate, point) {
  const series = seriesList(candidate?.chart?.echartsOption);
  const item = series.find((entry) => String(entry?.name || '') === String(point?.seriesName || '')) || series[0];
  const value = Array.isArray(item?.data) ? item.data[point?.dataIndex ?? 0] : null;
  const raw = value && typeof value === 'object' ? value.value : value;
  return Number.isFinite(Number(raw)) ? Math.max(0, Number(raw)) : 1;
}

function clickAttempts(box, candidate, point) {
  const width = Number(box?.width || 0);
  const height = Number(box?.height || 0);
  const left = Number(box?.x || 0);
  const top = Number(box?.y || 0);
  if (width <= 0 || height <= 0 || !point) return [];
  const option = candidate?.chart?.echartsOption || {};
  const attempts = [];
  if (point.kind === 'pie') {
    const series = seriesList(option).find((item) => String(item?.type || '').toLowerCase() === 'pie') || {};
    const values = Array.isArray(series.data) ? series.data.map((item) => Number(item?.value ?? item) || 0) : [];
    const total = values.reduce((sum, value) => sum + value, 0) || values.length || 1;
    const before = values.slice(0, point.dataIndex).reduce((sum, value) => sum + value, 0);
    const angle = -Math.PI / 2 + ((before + numericDataValue(candidate, point) / 2) / total) * Math.PI * 2;
    const radius = Math.min(width, height) * 0.28;
    for (const scale of [1, 0.75, 1.25, 0.5]) attempts.push({ x: left + width / 2 + Math.cos(angle) * radius * scale, y: top + height / 2 + Math.sin(angle) * radius * scale });
    for (const angleOffset of [Math.PI / 2, Math.PI, -Math.PI / 2]) attempts.push({ x: left + width / 2 + Math.cos(angle + angleOffset) * radius, y: top + height / 2 + Math.sin(angle + angleOffset) * radius });
  } else {
    const horizontal = point.kind === 'bar_horizontal';
    const categories = horizontal ? axisData(option, 'yAxis') : axisData(option, 'xAxis');
    const count = Math.max(1, categories.length);
    const index = Math.max(0, point.dataIndex || 0);
    const plotLeft = width * 0.08;
    const plotRight = width * 0.08;
    const plotTop = Math.min(50, height * 0.18);
    const plotBottom = Math.min(70, height * 0.22);
    if (horizontal) {
      const y = top + plotTop + (height - plotTop - plotBottom) * ((index + 0.5) / count);
      for (const factor of [0.35, 0.55, 0.75, 0.2]) attempts.push({ x: left + plotLeft + (width - plotLeft - plotRight) * factor, y });
    } else {
      const x = left + plotLeft + (width - plotLeft - plotRight) * ((index + 0.5) / count);
      for (const factor of [0.35, 0.55, 0.75, 0.2]) attempts.push({ x, y: top + plotTop + (height - plotTop - plotBottom) * factor });
    }
  }
  return attempts.slice(0, MAX_CANVAS_CLICK_ATTEMPTS);
}

function unverified(reason, extra = {}) {
  return { result: 'UNVERIFIED', rootCauseClass: 'data', reason, ...extra };
}

async function cardForCandidate(page, candidate) {
  const cards = page.locator('.recommendation-card');
  const wanted = `factory-chart-card-${candidate.id}`;
  for (let index = 0; index < await cards.count(); index += 1) {
    if (await cards.nth(index).getAttribute('data-testid') === wanted) return cards.nth(index);
  }
  return null;
}

async function closeDetailsDrawer(page) {
  const drawer = page.locator('.el-drawer:visible').first();
  if (!await drawer.count()) return;
  const closeButton = drawer.locator('button.el-drawer__close-btn').first();
  if (await closeButton.count()) await closeButton.click().catch(() => {});
  else await page.keyboard.press('Escape').catch(() => {});
  await drawer.waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {});
}

async function inspectDetailsTable(page, expectedRows, timeoutMs = 5_000) {
  const drawer = page.locator('.el-drawer:visible').first();
  const drawerVisible = await drawer.count() > 0 && await drawer.isVisible().catch(() => false);
  const table = drawer.locator('.el-table-v2').first();
  if (drawerVisible && await table.count()) await table.waitFor({ state: 'visible', timeout: Math.max(1_000, timeoutMs) }).catch(() => {});
  const tableVisible = drawerVisible && await table.count() > 0 && await table.isVisible().catch(() => false);
  if (!tableVisible) return { drawerVisible, tableVisible: false, renderedRowCount: 0, rowCountMatches: false };
  const rows = table.locator('.el-table-v2__row');
  const renderedRowCount = await rows.count();
  const ariaRowCount = await table.getAttribute('aria-rowcount').catch(() => null);
  const metrics = await table.evaluate((element) => {
    const body = element.querySelector('.el-table-v2__body') || element.querySelector('.el-table-v2__body-wrapper');
    const row = element.querySelector('.el-table-v2__row');
    return {
      scrollHeight: body?.scrollHeight || 0,
      rowHeight: row?.getBoundingClientRect().height || 44,
    };
  }).catch(() => ({ scrollHeight: 0, rowHeight: 44 }));
  const estimatedRowCount = metrics.scrollHeight > 0 && metrics.rowHeight > 0
    ? Math.round(metrics.scrollHeight / metrics.rowHeight) : null;
  const rowCountMatches = expectedRows > 0 && (
    renderedRowCount === expectedRows
    || ariaRowCount === String(expectedRows)
    || estimatedRowCount === expectedRows
  );
  return { drawerVisible, tableVisible, renderedRowCount, ariaRowCount, estimatedRowCount, rowCountMatches };
}

async function captureBasicDetails(page, card, candidate, expected, deadline = Date.now() + DETAIL_RESPONSE_TIMEOUT_MS) {
  const button = card?.locator('button.details-button').first();
  if (!button || !await button.count()) return unverified('mounted card has no 查看原始明细 button');
  const stepTimeout = () => Math.max(1, Math.min(DETAIL_RESPONSE_TIMEOUT_MS, deadline - Date.now()));
  if (deadline <= Date.now()) return unverified('detail acceptance deadline reached before opening the mounted details button', { candidateId: candidate.id });
  const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: stepTimeout() });
  try { await button.click({ timeout: stepTimeout() }); } catch { return unverified('查看原始明细 button could not be clicked', { candidateId: candidate.id }); }
  let response;
  try { response = await responsePromise; } catch { return unverified('查看原始明细 click produced no factory-details response within the bounded budget', { candidateId: candidate.id }); }
  const body = await response.json().catch(() => null);
  await page.locator('.el-drawer:visible').first().waitFor({ timeout: Math.max(1, Math.min(5_000, deadline - Date.now())) }).catch(() => {});
  const request = requestJson(response.request());
  const assessment = assessDetailsResponse({ status: response.status(), request, body, expected });
  const ui = await inspectDetailsTable(page, assessment.rowCount, Math.max(1, Math.min(5_000, deadline - Date.now())));
  assessment.ui = ui;
  assessment.checks.drawerVisible = ui.drawerVisible;
  assessment.checks.virtualTableVisible = ui.tableVisible;
  assessment.checks.virtualTableRowCount = ui.rowCountMatches;
  if (assessment.result === 'PASS' && (!ui.drawerVisible || !ui.tableVisible || !ui.rowCountMatches)) {
    assessment.result = 'UNVERIFIED';
    assessment.rootCauseClass = 'tool';
    assessment.reason = 'API details passed but drawer/virtual-table visibility or returned-row count could not be proven in the mounted UI';
  }
  // Keep the exact first response only in memory for the page-boundary check;
  // non-enumerable fields cannot enter the sanitized evidence JSON.
  Object.defineProperty(assessment, '_rawBody', { value: body, enumerable: false });
  Object.defineProperty(assessment, '_status', { value: response.status(), enumerable: false });
  assessment.candidateId = candidate.id;
  assessment.interaction = 'mounted-card-view-details';
  return assessment;
}

async function capturePointDetails(page, candidate, expected, deadline = Date.now() + 30_000) {
  const point = chartPointForCandidate(candidate);
  const card = await cardForCandidate(page, candidate);
  const canvas = card?.locator('.factory-chart-canvas canvas').first();
  if (!point) return unverified('no eligible visible pie/bar point in the recommendation echartsOption', { candidateId: candidate.id });
  if (!canvas || !await canvas.count()) return unverified('eligible pie/bar candidate has no mounted canvas', { candidateId: candidate.id, point });
  await canvas.waitFor({ state: 'visible', timeout: Math.max(1_000, Math.min(5_000, deadline - Date.now())) }).catch(() => {});
  await closeDetailsDrawer(page);
  await canvas.scrollIntoViewIfNeeded().catch(() => {});
  const box = await canvas.boundingBox();
  const availableAttempts = Math.floor(Math.max(0, deadline - Date.now()) / 3_500);
  const attempts = clickAttempts(box, candidate, point).slice(0, Math.min(MAX_CANVAS_CLICK_ATTEMPTS, availableAttempts));
  if (!attempts.length) return unverified('mounted canvas has no usable click coordinates', { candidateId: candidate.id, point });
  const attemptEvidence = [];
  for (const [index, coordinate] of attempts.entries()) {
    const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: Math.max(1_000, Math.min(3_500, deadline - Date.now())) }).catch(() => null);
    await page.mouse.click(coordinate.x, coordinate.y);
    const response = await responsePromise;
    if (!response) { attemptEvidence.push({ index, response: false }); continue; }
    const body = await response.json().catch(() => null);
    const request = requestJson(response.request());
    const assessment = assessDetailsResponse({ status: response.status(), request, body, expected, point });
    const ui = await inspectDetailsTable(page, assessment.rowCount, Math.max(1_000, Math.min(5_000, deadline - Date.now())));
    assessment.ui = ui;
    assessment.checks.drawerVisible = ui.drawerVisible;
    assessment.checks.virtualTableVisible = ui.tableVisible;
    assessment.checks.virtualTableRowCount = ui.rowCountMatches;
    if (assessment.result === 'PASS' && (!ui.drawerVisible || !ui.tableVisible || !ui.rowCountMatches)) {
      assessment.result = 'UNVERIFIED';
      assessment.rootCauseClass = 'tool';
      assessment.reason = 'Point-filtered API response passed but the mounted drawer/virtual-table row evidence was incomplete';
    }
    attemptEvidence.push({ index, response: true, status: response.status(), result: assessment.result, rowCount: assessment.rowCount, total: assessment.total });
    if (assessment.result === 'PASS') {
      await page.locator('.el-drawer:visible').first().waitFor({ timeout: 10_000 }).catch(() => {});
      return { ...assessment, candidateId: candidate.id, interaction: 'mounted-canvas-mouse-click', attempts: attemptEvidence };
    }
    await closeDetailsDrawer(page);
  }
  return unverified('bounded real canvas clicks did not produce a valid point-filtered details response', { candidateId: candidate.id, point, attempts: attemptEvidence });
}

module.exports = {
  id: 'smartbi-factory-readonly',
  defaultEnabled: false,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-readonly',
    path: '/smart-bi/analysis-hub?tab=factory',
    landmarks: ['工厂图表推荐', '数据源'],
    inspect: async (page, _body, context) => {
      if (context.expectedFactoryId !== 'F006') throw new Error('SmartBI acceptance is limited to F006');
      await page.getByTestId('factory-chart-ranking').waitFor({ timeout: 60_000 });
      const pending = page.waitForResponse((response) => endpointPath(response.url()).replace(/\/$/, '') === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations' && response.request().method() === 'POST', { timeout: 60_000 });
      await page.getByTestId('factory-chart-refresh').click();
      const recommendationResponse = await pending;
      const recommendationBody = await recommendationResponse.json();
      const data = recommendationBody.data;
      await page.getByTestId('factory-chart-ranking').waitFor({ timeout: 60_000 });
      await page.locator('.recommendation-card canvas').first().waitFor({ timeout: 30_000 }).catch(() => {});
      const cards = await page.locator('.recommendation-card').count();
      const drawingErrors = await page.locator('.recommendation-card [role="alert"]').count();
      const scope = recommendationResponse.status() === 200 && recommendationBody.success === true && data?.factoryId === 'F006' && data?.source === 'business';
      const recommendations = Array.isArray(data?.recommendations) ? data.recommendations : [];
      const factsMatch = await page.locator('.recommendation-card').evaluateAll((elements, items) => elements.every((el) => {
        const testId = el.getAttribute('data-testid') || '';
        const id = testId.slice('factory-chart-card-'.length);
        const item = items.find((candidate) => candidate.id === id);
        const text = (el.innerText || '').replace(/\s/g, '');
        return item && Array.isArray(item.kpis) && item.kpis.every((kpi) => kpi.value === null || text.includes(String(kpi.value).replace(/\s/g, '')));
      }), recommendations);
      const summary = {
        scope,
        cards,
        drawingErrors,
        factsMatch,
        candidateCount: recommendations.length,
        sourceErrorCount: Array.isArray(data?.sourceErrors) ? data.sourceErrors.length : 0,
        rankingMode: data?.ranking?.mode,
      };
      const mountedDetailCandidates = [];
      for (const candidate of recommendations.filter((item) => item?.detailContext?.id)) {
        const card = await cardForCandidate(page, candidate);
        const canvas = card?.locator('.factory-chart-canvas canvas').first();
        if (card && canvas && await canvas.count()) mountedDetailCandidates.push({ candidate, card });
      }
      let basic = unverified('no recommendation with detailContext is mounted with a real details button');
      let pagination = unverified('basic details interaction was not verified');
      let point = unverified('no mounted point candidate was available');
      let selectedCandidate = null;
      let selectedExpected = null;
      const candidateChecks = [];
      const detailDeadline = Date.now() + DETAIL_ACCEPTANCE_DEADLINE_MS;
      for (const candidateRef of mountedDetailCandidates.slice(0, MAX_DETAIL_CANDIDATES)) {
        if (Date.now() >= detailDeadline) break;
        await closeDetailsDrawer(page);
        const expected = {
          factoryId: 'F006', candidateId: candidateRef.candidate.id, source: candidateRef.candidate.source,
          period: data?.period?.label, contextId: candidateRef.candidate.detailContext.id,
        };
        const candidateBasic = await captureBasicDetails(page, candidateRef.card, candidateRef.candidate, expected, detailDeadline);
        candidateChecks.push({ candidateId: candidateRef.candidate.id, result: candidateBasic.result, total: candidateBasic.total ?? null, rowCount: candidateBasic.rowCount ?? null, ui: candidateBasic.ui || null });
        if (candidateBasic.result === 'PASS' && (!selectedCandidate || (candidateBasic.total || 0) > (basic.total || 0))) {
          selectedCandidate = candidateRef;
          selectedExpected = expected;
          basic = candidateBasic;
        }
        if (candidateBasic.result === 'PASS' && candidateBasic.total >= DETAIL_PAGE_SIZE + 1) break;
      }
      if (selectedCandidate && basic.result === 'PASS') {
        const total = basic.total;
        if (total >= DETAIL_PAGE_SIZE + 1) {
          const drawer = page.locator('.el-drawer:visible').first();
          const loadMore = drawer.getByRole('button', { name: '加载更多' }).first();
          if (await loadMore.count()) {
            const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: Math.max(1_000, Math.min(DETAIL_RESPONSE_TIMEOUT_MS, detailDeadline - Date.now())) });
            await loadMore.click();
            const response = await responsePromise;
            const body = await response.json();
            const request = requestJson(response.request());
            const pageTwo = { status: response.status(), request, body };
            // Keep raw page bodies in the Node page object for root's independent
            // SQL/source comparison; never put them in the sanitized report.
            page.__smartbiDetailAcceptance = {
              contextId: selectedExpected.contextId,
              candidateId: selectedExpected.candidateId,
              first: { status: basic._status || 200, body: basic._rawBody },
              second: { status: response.status(), body },
            };
            const firstPage = { status: basic._status || 200, request: { context_id: selectedExpected.contextId, page: 1, page_size: DETAIL_PAGE_SIZE }, body: basic._rawBody };
            pagination = assessPageContinuity(firstPage, pageTwo, selectedExpected);
            pagination.request = { contextIdMatches: request?.context_id === selectedExpected.contextId, page: request?.page ?? null, pageSize: request?.page_size ?? null };
            if (pagination.result === 'PASS') pagination.rootCauseClass = 'none';
          } else pagination = unverified('details response has >=51 rows but mounted drawer has no 加载更多 control', { total });
        } else pagination = unverified('details total is below the 51-row pagination qualification threshold', { total, requiredTotal: DETAIL_PAGE_SIZE + 1 });
      }
      const pointCandidate = mountedDetailCandidates.slice(0, MAX_DETAIL_CANDIDATES).find(({ candidate }) => chartPointForCandidate(candidate));
      if (pointCandidate && Date.now() < detailDeadline) {
        const pointExpected = {
          factoryId: 'F006', candidateId: pointCandidate.candidate.id, source: pointCandidate.candidate.source,
          period: data?.period?.label, contextId: pointCandidate.candidate.detailContext.id,
        };
        point = await capturePointDetails(page, pointCandidate.candidate, pointExpected, detailDeadline);
      } else if (!pointCandidate) point = unverified('no mounted candidate exposes a visible pie/bar point target');
      else point = unverified('detail acceptance deadline reached before bounded canvas point probing');
      const summaryPass = scope && cards > 0 && factsMatch && drawingErrors === 0 && summary.sourceErrorCount === 0;
      const deepPass = summaryPass && basic.result === 'PASS' && pagination.result === 'PASS' && pagination.completeness === 'PASS' && point.result === 'PASS';
      const assessment = deepPass
        ? { result: 'PASS', rootCauseClass: 'none' }
        : basic.result === 'CONFIRMED_DEFECT' || pagination.result === 'CONFIRMED_DEFECT' || point.result === 'CONFIRMED_DEFECT'
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'backend' }
          : { result: summaryPass ? 'UNVERIFIED' : 'CONFIRMED_DEFECT', rootCauseClass: summaryPass ? 'data' : 'frontend' };
      return {
        ...summary,
        details: { basic, pagination, point, candidateId: selectedCandidate?.candidate.id || null, candidateChecks },
        screenshots: [await context.screenshot('smartbi-factory-business'), await context.screenshot('smartbi-factory-details')],
        assessment,
      };
    },
  }),
};

module.exports.assessDetailsResponse = assessDetailsResponse;
module.exports.assessPageContinuity = assessPageContinuity;
module.exports.chartPointForCandidate = chartPointForCandidate;
