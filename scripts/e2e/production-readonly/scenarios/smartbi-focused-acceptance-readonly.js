'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const FACTORY_QUERY_PATH = '/smart-bi/analysis?tab=query&mode=factory';
const LEGACY_PATH = '/smart-bi/analysis?tab=analysis';
const ANALYSIS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/;
const UPLOADS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/uploads\/?$/;
const DETAIL_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const COMMERCE_QUESTION = '联合看客户渠道销售、发货和库存';

function pathname(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}
function normalized(url) { return pathname(url).replace(/\/$/u, ''); }
function bodyOf(response) { return response.json().catch(() => ({})); }
function requestOf(response) { try { return response.request().postDataJSON() || {}; } catch { return {}; } }
function familyOf(item) { return String(item?.analysis?.familyId || item?.familyId || item?.id || '').trim(); }

function validateCommerceOptions(value) {
  if (value == null) return null;
  if (!value || typeof value !== 'object') throw new Error('smartbiCommerceJoint must be an object');
  const expectedFamilies = value.expectedFamilies;
  if (!Array.isArray(expectedFamilies) || expectedFamilies.length !== 2
    || expectedFamilies.some((item) => typeof item !== 'string' || !item.trim())
    || new Set(expectedFamilies).size !== 2) {
    throw new Error('smartbiCommerceJoint.expectedFamilies must contain two distinct family IDs');
  }
  return {
    expectedFamilies: expectedFamilies.map((item) => item.trim()),
    expectedKpisByUnit: value.expectedKpisByUnit && typeof value.expectedKpisByUnit === 'object'
      ? value.expectedKpisByUnit : {},
    expectedChannelAmount: value.expectedChannelAmount,
  };
}

function validateLegacyOptions(value) {
  if (value == null) return null;
  if (!value || typeof value !== 'object') throw new Error('smartbiLegacyKpi must be an object');
  const ids = value.uploadIds;
  if (!ids || typeof ids !== 'object') throw new Error('smartbiLegacyKpi.uploadIds is required');
  const values = Object.values(ids);
  if (!values.length || !Number.isSafeInteger(ids.cost) || ids.cost <= 0 || values.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    throw new Error('smartbiLegacyKpi.uploadIds must contain positive IDs');
  }
  const expected = value.expected || {};
  for (const key of ['totalCost', 'materialCost', 'laborCost', 'equipmentCost']) {
    if (!Number.isFinite(Number(expected[key]))) throw new Error(`smartbiLegacyKpi.expected.${key} is required`);
  }
  return { uploadIds: { ...ids }, expected: Object.fromEntries(Object.entries(expected).map(([k, v]) => [k, Number(v)])) };
}

function parseNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : NaN;
  if (typeof value !== 'string') return NaN;
  return Number(value.replace(/[,%，％\s]/gu, ''));
}

function collectCommerceKpis(candidates, facts) {
  const values = {};
  const put = (unit, key, value) => {
    const number = parseNumber(value);
    if (!unit || !Number.isFinite(number)) return;
    values[unit] ||= {};
    values[unit][key] = number;
  };
  for (const candidate of candidates) {
    const unit = String(candidate?.unit || '').trim();
    for (const kpi of Array.isArray(candidate?.kpis) ? candidate.kpis : []) {
      const label = String(kpi?.label || '').toLowerCase();
      const key = /订单|order/u.test(label) ? 'orders'
        : /发货|shipment/u.test(label) ? 'shipments'
          : /库存|stock/u.test(label) ? 'stock'
            : /渠道.*(金额|销售)|销售.*(金额|额)|channel.*(amount|sales)/u.test(label) ? 'channelAmount' : null;
      if (key) put(String(kpi?.unit || unit).trim(), key, kpi?.value);
    }
  }
  for (const fact of Array.isArray(facts) ? facts : []) {
    const label = String(fact?.label || '').toLowerCase();
    const key = /订单|order/u.test(label) ? 'orders'
      : /发货|shipment/u.test(label) ? 'shipments'
        : /库存|stock/u.test(label) ? 'stock'
          : /渠道.*(金额|销售)|销售.*(金额|额)|channel.*(amount|sales)/u.test(label) ? 'channelAmount' : null;
    if (key) put(String(fact?.unit || '').trim() || '金额', key, fact?.value);
  }
  return values;
}

function compareExpectedKpis(candidateValues, factValues, candidates, expectedByUnit, expectedChannelAmount) {
  const checks = {};
  const stock = candidates.find((item) => /stock/u.test(familyOf(item)));
  const selectedUnit = String(stock?.unit || '').trim();
  const expected = expectedByUnit?.[selectedUnit];
  if (!selectedUnit || !expected || typeof expected !== 'object') checks.expectedKpisProvided = false;
  for (const [key, value] of Object.entries(expected || {})) {
    const unit = selectedUnit;
    checks[`kpi:${unit}:${key}`] = Number.isFinite(Number(value))
      && candidateValues?.[unit]?.[key] === Number(value)
      && factValues?.[unit]?.[key] === Number(value);
  }
  for (const [unit, values] of Object.entries(candidateValues || {})) {
    for (const [key, value] of Object.entries(values || {})) {
      if (key === 'channelAmount' && value !== Number(expectedChannelAmount)) continue;
      if (key !== 'channelAmount' && unit !== selectedUnit) continue;
      checks[`factMatchesCandidate:${unit}:${key}`] = factValues?.[unit]?.[key] === value;
    }
  }
  const channelFacts = Object.values(factValues || {}).flatMap((item) => item.channelAmount == null ? [] : [item.channelAmount]);
  const channelCandidates = Object.values(candidateValues || {}).flatMap((item) => item.channelAmount == null ? [] : [item.channelAmount]);
  checks.channelAmount = Number.isFinite(Number(expectedChannelAmount))
    && channelFacts.includes(Number(expectedChannelAmount))
    && channelCandidates.includes(Number(expectedChannelAmount));
  return checks;
}

function assessCommerce({ body, request, ui, expected }) {
  const data = body?.data || {};
  const candidates = Array.isArray(data.recommendations) ? data.recommendations : [];
  const families = candidates.map(familyOf).filter(Boolean);
  const expectedFamilies = expected?.expectedFamilies || [];
  const candidateByFamily = new Map(candidates.map((item) => [familyOf(item), item]));
  const selected = expectedFamilies.map((family) => candidateByFamily.get(family)).filter(Boolean);
  // financial_dashboard._analysis_response deliberately does not expose the
  // provider/model.  Its route only emits answered after planner mode==ai and
  // fresh candidate/family revalidation; use that tested contract, not a
  // fabricated provider field.
  const plannerMode = data.status === 'answered' ? 'answered-contract' : '';
  const selectedIds = new Set(selected.map((item) => String(item.id || familyOf(item))));
  const facts = Array.isArray(data.facts) ? data.facts : [];
  const scopedFacts = facts.length > 0 && facts.every((fact) => {
    const candidate = selected.find((item) => String(item.id) === String(fact?.candidateId));
    return candidate && selectedIds.has(String(fact.candidateId))
      && String(fact.source) === String(candidate.source)
      && (candidate.kpis || []).some((kpi) => kpi.label === fact.label
        && parseNumber(kpi.value) === parseNumber(fact.value)
        && String(kpi.unit || candidate.unit || '') === String(fact.unit || ''));
  });
  const kpiValues = collectCommerceKpis(candidates, facts);
  const candidateKpiValues = collectCommerceKpis(candidates, []);
  const factKpiValues = collectCommerceKpis([], facts);
  const kpiChecks = compareExpectedKpis(candidateKpiValues, factKpiValues, candidates, expected?.expectedKpisByUnit, expected?.expectedChannelAmount);
  const checks = {
    httpSuccess: body?.success === true,
    answered: data.status === 'answered',
    factoryF006: data.factoryId === 'F006',
    requestQuestion: request?.question === COMMERCE_QUESTION,
    exactFamilies: families.length === 2 && new Set(families).size === 2 && expectedFamilies.every((family) => families.includes(family)),
    sourceBusiness: data.source === 'business' && request?.source === 'business',
    candidateSourcesTrusted: candidates.length === 2 && candidates.every((candidate) => String(candidate?.source || '').startsWith('commerce-joint')),
    channelCurrency: candidateByFamily.get('commerce-joint.customer.channel')?.unit === '元',
    plannerAiEvidence: plannerMode === 'ai' || plannerMode === 'answered-contract',
    factsScopedToSelection: scopedFacts,
    factsVisible: ui?.factsVisible === true,
    chartsRendered: ui?.visibleChartCount === 2 && ui?.renderedCanvasCount === 2,
    detailsAllCharts: ui?.detailsAllCharts === true && ui?.detailsPointFree === true,
    excelExported: ui?.excelExported === true,
    ...kpiChecks,
  };
  const pass = Object.values(checks).every(Boolean);
  return { result: pass ? 'PASS' : 'UNVERIFIED', rootCauseClass: pass ? 'none' : 'data', checks, families, plannerMode, kpiValues, candidateKpiValues, factKpiValues };
}

function assessLegacyKpi({ visibleText, expected }) {
  const text = String(visibleText || '');
  const read = (label) => {
    const match = text.match(new RegExp(`${label}\\s*\\n?\\s*([\\d,]+(?:\\.\\d+)?)`, 'u'));
    return match ? parseNumber(match[1]) : NaN;
  };
  const actual = {
    totalCost: read('总成本'), materialCost: read('材料成本'), laborCost: read('人工成本'), equipmentCost: read('设备成本'),
  };
  const checks = Object.keys(expected).reduce((all, key) => {
    all[key] = actual[key] === expected[key];
    return all;
  }, {});
  const pass = Object.values(checks).every(Boolean);
  return { result: pass ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: pass ? 'none' : 'frontend', checks, actual };
}

function findLegacyBatchIndex(uploads, uploadIds) {
  const latest = new Map();
  for (const item of Array.isArray(uploads) ? uploads : []) {
    const key = `${item?.fileName || ''}|${item?.sheetName || ''}`;
    if (!latest.has(key) || Number(item?.id) > Number(latest.get(key)?.id)) latest.set(key, item);
  }
  const sorted = [...latest.values()].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  const wanted = new Set(Object.values(uploadIds).map(String));
  const batches = [];
  let current = [];
  let fileName = '';
  let currentTime = 0;
  for (const item of sorted) {
    const time = new Date(item.createdAt).getTime();
    if (item.fileName !== fileName || currentTime - time > 5 * 60 * 1000) {
      if (current.length) batches.push(current);
      current = [item]; fileName = item.fileName; currentTime = time;
    } else current.push(item);
  }
  if (current.length) batches.push(current);
  const index = batches.findIndex((batch) => {
    const ids = new Set(batch.map((item) => String(item.id)));
    return [...wanted].every((id) => ids.has(id));
  });
  return index >= 0 ? index : null;
}

async function runCommerce(ctx) {
  const expected = validateCommerceOptions(ctx.smartbiCommerceJoint || ctx.options?.smartbiCommerceJoint);
  if (!expected) return { assessment: { result: 'NOT_RUN', rootCauseClass: 'none', checks: {} } };
  if (ctx.expectedFactoryId !== 'F006') throw new Error('Focused acceptance is limited to F006');
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-commerce-joint-readonly', path: FACTORY_QUERY_PATH,
    landmarks: ['智能数据分析'], timeoutMs: 60_000, appMountTimeoutMs: 90_000,
    inspect: async (page, _body, inspectCtx) => inspectCommercePage(page, inspectCtx, expected),
  });
}

async function inspectCommercePage(page, inspectCtx, expected) {
  const input = page.getByTestId('factory-analysis-input');
  await input.waitFor({ timeout: 60_000 });
  const responsePromise = page.waitForResponse(
    (item) => item.request().method() === 'POST' && ANALYSIS_PATH.test(pathname(item.url())),
    { timeout: 60_000 },
  );
  await input.fill(COMMERCE_QUESTION);
  await page.getByTestId('factory-analysis-submit').click();
  const response = await responsePromise;
  const body = await bodyOf(response);
  const request = requestOf(response);
  const cards = page.locator('[data-testid^="factory-chart-card-"]:visible');
  await cards.first().waitFor({ timeout: 30_000 }).catch(() => {});
  const visibleChartCount = await cards.count();
  for (let index = 0; index < visibleChartCount; index += 1) {
    await cards.nth(index).scrollIntoViewIfNeeded({ timeout: 10_000 }).catch(() => {});
    await cards.nth(index).locator('canvas').first().waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
  }
  const factHost = page.getByTestId('factory-analysis-facts').last();
  const factText = await factHost.innerText({ timeout: 5_000 }).catch(() => '');
  const factsVisible = await factHost.isVisible().catch(() => false)
    && (body.data?.facts || []).every((fact) => factText.includes(fact.label)
      && factText.includes(String(fact.unit))
      && new RegExp(`(^|[^0-9])${String(parseNumber(fact.value)).replace(/\./gu, '\\.')}([^0-9]|$)`, 'u').test(factText));
  const ui = { visibleChartCount, renderedCanvasCount: await cards.locator('canvas').count(), factsVisible,
    detailsAllCharts: false, detailsPointFree: true, detailsRequests: [], excelExported: false };
  const detailButtons = page.locator('[data-testid^="factory-chart-card-"]:visible button');
  const detailCount = await detailButtons.count();
  const detailNames = await detailButtons.allTextContents();
  ui.detailsAllCharts = detailCount === 2 && detailNames.every((name) => name.trim() === '查看原始明细');
  const period = body?.data?.period || {};
  for (let i = 0; i < detailCount; i += 1) {
    const detailPromise = page.waitForResponse(
      (item) => item.request().method() === 'POST' && normalized(item.url()) === DETAIL_PATH,
      { timeout: 30_000 },
    ).catch(() => null);
    await detailButtons.nth(i).click().catch(() => {});
    const detailResponse = await detailPromise;
    const detailBody = detailResponse ? await bodyOf(detailResponse) : {};
    const detailRequest = detailResponse ? requestOf(detailResponse) : {};
    const candidate = body?.data?.recommendations?.[i] || {};
    const candidateId = String(candidate.id || familyOf(candidate));
    const detailData = detailBody?.data || {};
    const requestKeys = Object.keys(detailRequest);
    const requestMatches = String(detailRequest.context_id || '') === String(candidate?.detailContext?.id || '')
      && detailRequest.page === 1 && detailRequest.page_size === 50
      && requestKeys.every((key) => ['context_id', 'page', 'page_size', 'filters'].includes(key))
      && !Object.prototype.hasOwnProperty.call(detailRequest, 'point')
      && !Object.prototype.hasOwnProperty.call(detailRequest, 'pointId')
      && detailResponse?.status() === 200 && detailBody?.success === true
      && detailData.factoryId === 'F006' && detailData.candidateId === candidateId
      && detailData.source === candidate?.source && detailData.period === period.label
      && Array.isArray(detailData.rows) && detailData.rows.length > 0
      && Number(detailData.total) > 0;
    ui.detailsRequests.push({ candidateId, request: detailRequest, requestMatches });
    ui.detailsPointFree = ui.detailsPointFree && requestMatches
      && !('point' in detailData) && !('pointId' in detailData);
    await page.keyboard.press('Escape').catch(() => {});
  }
  const answer = page.locator('.turn.turn--assistant:visible').last();
  const excel = answer.getByRole('button', { name: 'Excel', exact: true });
  if (await excel.count()) {
    const download = page.waitForEvent('download', { timeout: 30_000 }).catch(() => null);
    const exportResponse = page.waitForResponse((item) => item.request().method() === 'POST' && normalized(item.url()).endsWith('/export-excel'), { timeout: 30_000 }).catch(() => null);
    await excel.click();
    const [file, result] = await Promise.all([download, exportResponse]);
    if (file && result && result.status() === 200) {
      const target = `${String(inspectCtx.evidenceDir || '').replace(/[\\/]+$/, '')}/smartbi-commerce-joint-readonly.xlsx`;
      if (inspectCtx.evidenceDir) await file.saveAs(target);
      ui.excelExported = Boolean(inspectCtx.evidenceDir);
    }
  }
  const screenshot = inspectCtx.screenshot ? await inspectCtx.screenshot('smartbi-commerce-joint-readonly-answer') : null;
  const assessment = assessCommerce({ body, request, ui, expected });
  return { assessment, ...assessment, screenshot, detailsRequests: ui.detailsRequests, detailNames, ui,
    catalogFamilyIds: [...new Set((body.data?.catalog || []).map(familyOf).filter(Boolean))] };
}

async function runLegacy(ctx) {
  const expected = validateLegacyOptions(ctx.smartbiLegacyKpi || ctx.options?.smartbiLegacyKpi);
  if (!expected) return { assessment: { result: 'NOT_RUN', rootCauseClass: 'none', checks: {} } };
  if (ctx.expectedFactoryId !== 'F006') throw new Error('Focused acceptance is limited to F006');
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-legacy-kpi-readonly', path: LEGACY_PATH,
    landmarks: ['传 Excel 分析', '智能数据分析'], timeoutMs: 60_000, appMountTimeoutMs: 90_000,
    inspect: async (page, body, inspectCtx) => inspectLegacyPage(page, body, expected, inspectCtx),
  });
}

async function inspectLegacyPage(page, body, expected, inspectCtx = {}) {
  const historyPromise = page.waitForResponse((item) => item.request().method() === 'GET' && UPLOADS_PATH.test(pathname(item.url())), { timeout: 30_000 }).catch(() => null);
  const costCachePromise = page.waitForResponse((item) => item.request().method() === 'GET'
    && normalized(item.url()) === `/smartbi-api/api/smartbi/analysis-cache/${expected.uploadIds.cost}`, { timeout: 18_000 }).catch(() => null);
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 60_000 });
  const historyResponse = await historyPromise;
  if (!historyResponse) return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'data', checks: { sourceHistoryRead: false } }, sourceBatchFound: false };
  const historyBody = await bodyOf(historyResponse);
  const uploads = historyBody?.data?.content || historyBody?.data || [];
  const batchIndex = findLegacyBatchIndex(uploads, expected.uploadIds);
  if (batchIndex == null) return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'data', checks: { sourceBatchFound: false } }, sourceBatchFound: false };
  const expectedFileName = uploads.find((item) => Number(item.id) === expected.uploadIds.cost)?.fileName;
  const switcher = page.locator('.header-actions .el-select').first();
  await switcher.click();
  const option = page.locator('.el-select-dropdown__item:visible').nth(batchIndex + 1);
  await option.click();
  const costTab = page.locator('.sheet-tabs .el-tabs__item').filter({ hasText: '成本' }).first();
  const costTabFound = await costTab.waitFor({ state: 'visible', timeout: 20_000 }).then(() => true).catch(() => false);
  if (costTabFound) await costTab.click();
  const costResponse = await costCachePromise;
  const costBody = costResponse ? await bodyOf(costResponse) : {};
  const sourceDataVerified = Boolean(costResponse && costResponse.status() === 200 && costBody.success === true);
  const sourceSelected = `${await switcher.innerText()} ${await switcher.locator('input').inputValue().catch(() => '')}`;
  const selectedFileMatches = Boolean(expectedFileName && sourceSelected.includes(expectedFileName));
  const costTabSelected = costTabFound && (await costTab.getAttribute('aria-selected') === 'true'
    || /\bis-active\b/u.test(await costTab.getAttribute('class') || ''));
  let assessment;
  for (let attempt = 0; attempt < 30; attempt += 1) {
    body = await page.locator('body').innerText();
    assessment = assessLegacyKpi({ visibleText: body, expected: expected.expected });
    if (assessment.result === 'PASS') break;
    await page.waitForTimeout(500);
  }
  assessment.checks.sourceBatchFound = true;
  assessment.checks.costTabSelected = costTabSelected;
  assessment.checks.selectedFileMatches = selectedFileMatches;
  assessment.checks.sourceDataVerified = sourceDataVerified;
  assessment.result = assessment.result === 'PASS' && costTabSelected && selectedFileMatches && sourceDataVerified ? 'PASS' : 'PARTIAL_DEFECT';
  const screenshot = inspectCtx.screenshot ? await inspectCtx.screenshot('legacy-upload-cost-kpi') : null;
  return { assessment, ...assessment, sourceBatchFound: true, costTabSelected, sourceSelected,
    sourceDataRequestId: sourceDataVerified ? expected.uploadIds.cost : null, screenshot };
}

module.exports = {
  commerceJoint: { id: 'smartbi-commerce-joint-readonly', defaultEnabled: false, run: runCommerce },
  legacyKpi: { id: 'smartbi-legacy-kpi-readonly', defaultEnabled: false, run: runLegacy },
  validateCommerceOptions, validateLegacyOptions, assessCommerce, assessLegacyKpi, findLegacyBatchIndex, inspectCommercePage, inspectLegacyPage,
};
