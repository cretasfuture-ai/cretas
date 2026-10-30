'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis-hub?tab=factory';
const RECOMMENDATIONS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const EXPORTS = [
  { key: 'excel', label: 'Excel', button: '导出当前显示图表 Excel', path: '/smartbi-api/api/smartbi/financial-dashboard/export-excel', extension: 'xlsx' },
  { key: 'pdf', label: 'PDF', button: '导出当前显示图表 PDF', path: '/smartbi-api/api/smartbi/financial-dashboard/export-pdf', extension: 'pdf' },
  { key: 'ppt', label: 'PPT', button: '导出当前显示图表 PPT', path: '/smartbi-api/api/smartbi/financial-dashboard/export-ppt', extension: 'pptx' },
];
const MAX_OPTION_FAMILIES = 60;
const MAX_OPTION_CANDIDATES = 120;
const STEP_TIMEOUT_MS = 30_000;
const TOTAL_TIMEOUT_MS = 10 * 60_000;

function testId(page, id) { return page.locator(`[data-testid="${String(id).replace(/"/gu, '\\"')}"]`); }

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function normalizedPath(url) { return endpointPath(url).replace(/\/$/u, ''); }

function isRecommendationsResponse(response) {
  return response.request().method() === 'POST' && normalizedPath(response.url()) === RECOMMENDATIONS_PATH;
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST' && normalizedPath(response.url()) === DETAILS_PATH;
}

function isExportResponse(response, format) {
  const item = typeof format === 'string' ? EXPORTS.find((entry) => entry.key === format) : format;
  return response.request().method() === 'POST' && Boolean(item)
    && normalizedPath(response.url()) === item.path;
}

function requestBody(response) {
  try { return response.request().postDataJSON() || {}; } catch { return {}; }
}

async function responseBody(response) {
  return response ? response.json().catch(() => ({})) : {};
}

function familyOf(candidate) {
  return String(candidate?.analysis?.familyId || candidate?.familyId || candidate?.id || '').trim() || null;
}

function familyLabelOf(candidate) {
  return String(candidate?.analysis?.familyLabel || candidate?.title || familyOf(candidate) || '').trim() || null;
}

function isRestaurantOnly(candidate) {
  const domain = String(candidate?.domain || candidate?.analysis?.domain || '').toLowerCase();
  const family = String(candidate?.analysis?.familyId || candidate?.familyId || '').toLowerCase();
  const sourceKey = String(candidate?.sourceKey || candidate?.analysis?.sourceKey || '').toLowerCase();
  return domain === 'restaurant' || domain === '餐饮' || family.startsWith('restaurant.')
    || family.startsWith('餐饮.') || sourceKey === 'restaurant' || sourceKey.startsWith('restaurant.');
}

function validateCatalogFamilies(value) {
  if (value == null) return null;
  if (!Array.isArray(value)) throw new Error('factoryCatalogFamilies must be an array of family IDs');
  if (value.length > MAX_OPTION_FAMILIES) throw new Error(`factoryCatalogFamilies must contain at most ${MAX_OPTION_FAMILIES} IDs`);
  const result = value.map((entry) => {
    if (typeof entry !== 'string' || !entry.trim()) throw new Error('factoryCatalogFamilies contains an empty family ID');
    return entry.trim();
  });
  if (new Set(result).size !== result.length) throw new Error('factoryCatalogFamilies contains duplicate family IDs');
  return result;
}

function validateCatalogCandidateIds(value) {
  if (value == null) return null;
  if (!Array.isArray(value)) throw new Error('factoryCatalogCandidateIds must be an array of candidate IDs');
  if (value.length > MAX_OPTION_CANDIDATES) throw new Error(`factoryCatalogCandidateIds must contain at most ${MAX_OPTION_CANDIDATES} IDs`);
  const result = value.map((entry) => {
    if (typeof entry !== 'string' || !entry.trim()) throw new Error('factoryCatalogCandidateIds contains an empty candidate ID');
    return entry.trim();
  });
  if (new Set(result).size !== result.length) throw new Error('factoryCatalogCandidateIds contains duplicate candidate IDs');
  return result;
}

function groupCandidates(candidates) {
  const groups = new Map();
  for (const candidate of Array.isArray(candidates) ? candidates : []) {
    const id = familyOf(candidate);
    if (!id) continue;
    if (!groups.has(id)) groups.set(id, {
      familyId: id,
      familyLabel: familyLabelOf(candidate) || id,
      candidates: [],
    });
    groups.get(id).candidates.push(candidate);
  }
  return [...groups.values()];
}

function candidateIsQualified(candidate) {
  const lazyDetails = typeof candidate?.detailSnapshotKey === 'string'
    && candidate.detailSnapshotKey.trim().length > 0;
  return Boolean(candidate?.id && candidate?.source && candidate?.chart
    && typeof candidate.chart === 'object' && candidate?.analysis?.familyId
    && (candidate?.detailStatus?.state !== 'unavailable' || lazyDetails)
    && candidate?.detailStatus?.state !== 'capacity');
}

function verifiedWipVoucherCount(candidate, fact) {
  if (familyOf(candidate) !== 'wip-plan-flow.plan-flow'
    || !String(candidate?.source || '').startsWith('wip-plan-flow ·')
    || fact?.label !== '独立流转条数' || fact?.unit !== '条') return false;
  const headers = candidate?.tableData?.headers;
  const rows = candidate?.tableData?.rows;
  if (!Array.isArray(headers) || !Array.isArray(rows) || !rows.length) return false;
  const voucher = headers.indexOf('MC凭证');
  const quantity = headers.indexOf('数量');
  const rowUnit = headers.indexOf('单位');
  if ([voucher, quantity, rowUnit].some((index) => index < 0)) return false;
  const unit = String(candidate?.unit || '').trim();
  if (!rows.every((row) => Array.isArray(row) && /^MC:[1-9]\d*$/u.test(String(row[voucher]))
    && String(row[rowUnit]).trim() === unit && Number.isFinite(Number(row[quantity]))
    && Number(row[quantity]) > 0)) return false;
  return new Set(rows.map((row) => String(row[voucher]))).size === rows.length
    && Number.isSafeInteger(Number(fact.value)) && Number(fact.value) === rows.length;
}

function candidateUnitsAligned(candidate) {
  const unit = String(candidate?.unit || '').trim();
  if (!unit) return true; // Financial table candidates may legitimately be multi-unit.
  return (Array.isArray(candidate?.kpis) ? candidate.kpis : []).every((fact) => {
    const factUnit = String(fact?.unit || '').trim();
    return !factUnit || factUnit === unit
      || (factUnit === '%' && /率|占比|百分比/u.test(String(fact?.label || '')))
      || verifiedWipVoucherCount(candidate, fact);
  });
}

function purchasePriceEvidence(candidate) {
  if (familyOf(candidate) !== 'purchase.price') return null;
  const source = String(candidate?.source || '').trim();
  if (!/^purchase(?:\s*[·:|]|\s|$)/iu.test(source)) return null;
  const table = candidate?.tableData;
  const headers = Array.isArray(table) ? ['label', 'value'] : Array.isArray(table?.headers) ? table.headers.map(String) : [];
  const rows = Array.isArray(table) ? table : Array.isArray(table?.rows) ? table.rows : [];
  const materialIndex = headers.findIndex((header) => /原料|物料|label|名称/u.test(header));
  const unitIndex = headers.findIndex((header) => /单位/u.test(header));
  const priceIndex = headers.findIndex((header) => /采购单价|入库单价|加权.*价|均价|value|价格/u.test(header));
  const rowValue = (row, index, header) => Array.isArray(row) ? row[index] : row && typeof row === 'object' ? row[header] : undefined;
  const declaredUnit = String(candidate?.unit || '').trim();
  if (!/^元\s*\/\s*[^\s/]+$/u.test(declaredUnit)) return null;
  const numberValue = (raw) => !['number', 'string'].includes(typeof raw) || String(raw).trim() === '' ? NaN : Number(raw);
  const tablePoints = materialIndex >= 0 && unitIndex >= 0 && priceIndex >= 0
      ? rows.map((row) => ({ material: String(rowValue(row, materialIndex, headers[materialIndex]) ?? '').trim(), unit: String(rowValue(row, unitIndex, headers[unitIndex]) ?? '').trim(), value: numberValue(rowValue(row, priceIndex, headers[priceIndex])) }))
    : materialIndex >= 0 && priceIndex >= 0
      ? rows.map((row) => ({ material: String(rowValue(row, materialIndex, headers[materialIndex]) ?? '').trim(), unit: declaredUnit, value: numberValue(rowValue(row, priceIndex, headers[priceIndex])) }))
    : [];
  const option = candidate?.chart?.echartsOption || {};
  const labels = Array.isArray(option?.xAxis?.data) ? option.xAxis.data.map(String)
    : Array.isArray(option?.yAxis?.data) ? option.yAxis.data.map(String) : [];
  const series = Array.isArray(option?.series) ? option.series[0]?.data : [];
  const chartPoints = labels.map((material, index) => ({ material: material.trim(), unit: declaredUnit, value: numberValue(series?.[index]) }));
  if (!tablePoints.length || !chartPoints.length || tablePoints.length !== chartPoints.length) return null;
  if (tablePoints.some((point) => !point.material || !point.unit || !Number.isFinite(point.value) || point.value < 0)) return null;
  if (chartPoints.some((point) => !point.material || !point.unit || !Number.isFinite(point.value) || point.value < 0)) return null;
  const chartByMaterial = new Map(chartPoints.map((point) => [point.material, point]));
  if (new Set(tablePoints.map((point) => point.material)).size !== tablePoints.length || chartByMaterial.size !== chartPoints.length) return null;
  const same = tablePoints.every((point) => {
    const drawn = chartByMaterial.get(point.material);
    return drawn && point.unit === drawn.unit && Math.abs(point.value - drawn.value) < 1e-9;
  });
  return same ? { points: tablePoints.map((point) => ({ ...point })), source, nonadditive: true } : null;
}

function selectCatalogCandidates(data, requestedFamilies, requestedCandidateIds = null) {
  const candidates = Array.isArray(data?.recommendations) ? data.recommendations : [];
  const groups = groupCandidates(candidates);
  const available = new Set(groups.map((group) => group.familyId));
  const requested = requestedFamilies || groups.map((group) => group.familyId);
  const requestedUnknown = requested.filter((family) => !available.has(family));
  const availableCandidates = new Map(candidates.filter((candidate) => candidate?.id).map((candidate) => [String(candidate.id), candidate]));
  const requestedCandidateUnknown = (requestedCandidateIds || []).filter((id) => !availableCandidates.has(id));
  const excluded = [];
  const blocked = [];
  const explicitCandidates = requestedCandidateIds
    ? requestedCandidateIds.map((id) => availableCandidates.get(id)).filter(Boolean)
    : null;
  const eligibleGroups = groups.filter((group) => requested.includes(group.familyId)
    && (!requestedCandidateIds || requestedCandidateIds.some((id) => familyOf(availableCandidates.get(id)) === group.familyId))).flatMap((group) => {
    const sorted = explicitCandidates
      ? explicitCandidates.filter((candidate) => familyOf(candidate) === group.familyId)
      : [...group.candidates];
    const restaurantOnly = sorted.filter(isRestaurantOnly);
    if (restaurantOnly.length) excluded.push(...restaurantOnly.map((candidate) => ({
      familyId: group.familyId,
      familyLabel: group.familyLabel,
      candidateId: candidate.id,
      reason: 'restaurant-only',
    })));
    const qualified = sorted.filter((item) => !isRestaurantOnly(item) && candidateIsQualified(item));
    if (!qualified.length) {
      const blockedCandidate = sorted.find((item) => !isRestaurantOnly(item));
      blocked.push({
        familyId: group.familyId,
        familyLabel: group.familyLabel,
        candidateId: blockedCandidate?.id || null,
        reason: blockedCandidate?.detailStatus?.state || 'candidate-not-qualified',
      });
      return [];
    }
    const chosen = requestedCandidateIds ? qualified : qualified.slice(0, 1);
    return chosen.map((candidate) => ({ familyId: group.familyId, familyLabel: group.familyLabel, candidate }));
  });
  // Families are covered through individual previews by inspectCatalog. Keep the
  // explicit 60-family run bound, but never hide the remainder from the
  // assessment.
  const selected = eligibleGroups.slice(0, MAX_OPTION_FAMILIES);
  const deferred = eligibleGroups.slice(MAX_OPTION_FAMILIES).map((item) => ({
    familyId: item.familyId, familyLabel: item.familyLabel, candidateId: item.candidate.id, reason: 'family-limit-60',
  }));
  return { selected, deferred, blocked, excluded, requestedUnknown, requestedCandidateUnknown };
}

function detailPeriod(data) {
  return data?.period?.label || null;
}

function summarizeExportIds(body) {
  const ids = [];
  for (const chart of Array.isArray(body?.charts) ? body.charts : []) {
    const id = chart?.id || chart?.candidate_id;
    if (id) ids.push(String(id));
  }
  for (const id of Array.isArray(body?.selected_ids) ? body.selected_ids : []) if (id) ids.push(String(id));
  return [...new Set(ids)];
}

function compactDetail(response, body, candidate, expectedPeriod) {
  const data = body?.data;
  return {
    attempted: Boolean(response),
    httpStatus: response ? response.status() : null,
    success: Boolean(response && response.status() === 200 && body?.success === true),
    candidateId: data?.candidateId || null,
    factoryId: data?.factoryId || null,
    source: data?.source || null,
    period: data?.period || null,
    rowTotal: Number.isFinite(data?.total) ? data.total : null,
    rowsPresent: Array.isArray(data?.rows),
    scopeMatches: Boolean(data?.candidateId === candidate.id && data?.source === candidate.source
      && data?.period === expectedPeriod && Array.isArray(data?.rows)),
  };
}

function checkFailures(checks) {
  return Object.entries(checks).filter(([, value]) => value !== true).map(([key]) => key);
}

function assessCatalog({ families, requestedUnknown, deferred, excluded, eligibilityGaps = [], sourceErrors = [], remainingFamilyCount = 0, ui }) {
  const familyPass = families.length > 0 && families.every((item) => item.status === 'PASS');
  const result = familyPass && requestedUnknown.length === 0 && sourceErrors.length === 0
    && deferred.length === 0 && remainingFamilyCount === 0 ? 'PASS' : 'UNVERIFIED';
  const rootCauseClass = result === 'PASS' ? 'none'
    : ui?.missing ? 'frontend'
      : ui?.httpFailure ? 'backend'
        : ui?.selectionFailure ? 'tool'
        : 'data';
  return {
    result,
    rootCauseClass,
    fullPass: result === 'PASS',
    selectedFamilyCount: families.length,
    familyCount: families.length,
    coveredFamilyCount: families.filter((item) => item.status === 'PASS').length,
    failedFamilyCount: families.filter((item) => item.status !== 'PASS').length,
    remainingFamilyCount,
    requestedUnknown,
    deferredFamilies: deferred,
    excludedFamilies: excluded,
    eligibilityGaps,
    sourceErrors,
    checkFailures: [
      ...families.flatMap((item) => item.checkFailures.map((key) => `${item.familyId}.${key}`)),
      ...deferred.map((item) => `deferred.${item.familyId}`),
      ...sourceErrors.map((item) => `source.${item.source || 'unknown'}`),
    ],
  };
}

function remainingTimeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

async function waitResponse(page, predicate, deadline) {
  return page.waitForResponse(predicate, { timeout: remainingTimeout(deadline) });
}

async function captureExport(page, format, inspectCtx, deadline, batchNumber) {
  const result = { format: format.key, buttonFound: false, downloadStarted: false, httpStatus: null, filename: null, saved: false, candidateIds: [], success: false };
  const button = page.locator('button:visible').filter({ hasText: format.button }).first();
  result.buttonFound = await button.count() > 0;
  if (!result.buttonFound) return result;
  try {
    const responsePromise = waitResponse(page, (response) => isExportResponse(response, format), deadline).catch(() => null);
    const downloadPromise = page.waitForEvent('download', { timeout: remainingTimeout(deadline) }).catch(() => null);
    await button.click({ timeout: remainingTimeout(deadline) });
    const [response, download] = await Promise.all([responsePromise, downloadPromise]);
    result.httpStatus = response ? response.status() : null;
    result.candidateIds = summarizeExportIds(response ? requestBody(response) : {});
    result.downloadStarted = Boolean(download);
    result.suggestedFilename = download ? download.suggestedFilename() : null;
    result.filename = download ? `factory-catalog-batch-${batchNumber}-${format.key}.${format.extension}` : null;
    if (download && inspectCtx.evidenceDir && result.filename) {
      try {
        await download.saveAs(`${String(inspectCtx.evidenceDir).replace(/[\\/]+$/u, '')}/${result.filename}`);
        result.saved = true;
      } catch {}
    }
    result.success = result.downloadStarted && result.httpStatus === 200
      && (!inspectCtx.evidenceDir || result.saved);
  } catch {}
  return result;
}

async function chooseBrowseMode(page, deadline) {
  const mode = testId(page, 'factory-chart-mode');
  if (!await mode.count()) return false;
  const browse = mode.locator('button, label').filter({ hasText: '按分类浏览' }).first();
  if (!await browse.count()) return false;
  await browse.click({ timeout: remainingTimeout(deadline) });
  await testId(page, 'factory-chart-selection').waitFor({ state: 'visible', timeout: remainingTimeout(deadline) });
  return true;
}

async function selectFamilyPreview(page, item, allGroups, deadline) {
  const row = page.locator(`.family-row[data-family-id="${String(item.familyId).replace(/"/gu, '\\"')}"]`).first();
  if (!await row.count()) throw new Error(`Catalogue family row missing: ${item.familyId}`);
  const control = row.locator('.el-select, .el-select-v2').first();
  if (!await control.count()) throw new Error(`Catalogue family variant control missing: ${item.familyId}`);
  const candidateIndex = allGroups.find((group) => group.familyId === item.familyId)?.candidates
    .findIndex((candidate) => String(candidate?.id) === String(item.candidate.id));
  if (!Number.isInteger(candidateIndex) || candidateIndex < 0) throw new Error(`Catalogue candidate missing from family order: ${item.candidate.id}`);
  await control.click({ timeout: remainingTimeout(deadline) });
  const options = page.locator('.el-select-dropdown__item:visible, [role="option"]:visible');
  await options.first().waitFor({ state: 'visible', timeout: remainingTimeout(deadline) });
  if (await options.count() <= candidateIndex) throw new Error(`Catalogue variant options drifted for ${item.familyId}`);
  const option = options.nth(candidateIndex);
  const optionValue = await option.getAttribute('value') || await option.getAttribute('data-value');
  if (optionValue && optionValue !== String(item.candidate.id)) throw new Error(`Catalogue variant option ID drifted: ${optionValue}`);
  await option.click({ timeout: remainingTimeout(deadline) });
  return true;
}

function validateCatalogSubjectId(value) {
  if (value == null) return null;
  if (typeof value !== 'string' || !value.trim()) throw new Error('factoryCatalogSubjectId must be a non-empty subject ID');
  return value.trim();
}

async function selectCatalogSubject(page, subjectId, initialData, deadline) {
  const subjects = Array.isArray(initialData?.subjects) ? initialData.subjects : [];
  const subject = subjects.find((entry) => String(entry?.id || '') === subjectId);
  if (!subject || !String(subject.label || '').trim()) throw new Error(`Factory catalog subject is absent from initial response: ${subjectId}`);
  const control = testId(page, 'factory-chart-guide-subject');
  if (!await control.count()) throw new Error('Factory catalog subject control missing');
  await control.locator('input, button').first().click({ timeout: remainingTimeout(deadline) }).catch(() => control.click({ timeout: remainingTimeout(deadline) }));
  const option = page.locator('[role="option"]:visible, .el-select-dropdown__item:visible').filter({ hasText: String(subject.label) }).first();
  await option.waitFor({ state: 'visible', timeout: remainingTimeout(deadline) });
  const responsePromise = waitResponse(page, isRecommendationsResponse, deadline);
  await option.click({ timeout: remainingTimeout(deadline) });
  const response = await responsePromise;
  const body = await responseBody(response);
  const request = requestBody(response);
  if (request.analysis_subject !== subjectId || body?.data?.analysisSubject !== subjectId) {
    throw new Error(`Factory catalog subject scope drifted: ${subjectId}`);
  }
  return response;
}

async function captureCardScreenshot(card, inspectCtx, candidateId, batchNumber) {
  if (!inspectCtx.evidenceDir || !card) return null;
  const safeId = String(candidateId).replace(/[^A-Za-z0-9._-]+/gu, '-').slice(0, 90) || 'candidate';
  const target = `${String(inspectCtx.evidenceDir).replace(/[\\/]+$/u, '')}/factory-catalog-batch-${batchNumber}-${safeId}-card.png`;
  try { await card.screenshot({ path: target }); return target; } catch { return null; }
}

async function inspectCatalog(page, _body, inspectCtx) {
  if (inspectCtx.expectedFactoryId !== 'F006') throw new Error('Factory catalog acceptance is limited to F006');
  const startedAt = Date.now();
  const deadline = startedAt + TOTAL_TIMEOUT_MS;
  const optionValue = inspectCtx.factoryCatalogFamilies || inspectCtx.options?.factoryCatalogFamilies;
  const candidateOptionValue = inspectCtx.factoryCatalogCandidateIds || inspectCtx.options?.factoryCatalogCandidateIds;
  const subjectId = validateCatalogSubjectId(inspectCtx.factoryCatalogSubjectId || inspectCtx.options?.factoryCatalogSubjectId);
  const requestedFamilies = validateCatalogFamilies(optionValue);
  const requestedCandidateIds = validateCatalogCandidateIds(candidateOptionValue);
  const ui = { browseVisible: false, missing: false, httpFailure: false };

  await testId(page, 'factory-chart-guide').waitFor({ state: 'visible', timeout: remainingTimeout(deadline) }).catch(() => {});
  const refresh = testId(page, 'factory-chart-refresh');
  let recommendationResponse = null;
  if (await refresh.count()) {
    const pending = waitResponse(page, isRecommendationsResponse, deadline).catch(() => null);
    await refresh.click({ timeout: remainingTimeout(deadline) }).catch(() => {});
    recommendationResponse = await pending;
  }
  if (!recommendationResponse) ui.httpFailure = true;
  let body = await responseBody(recommendationResponse);
  if (subjectId) {
    recommendationResponse = await selectCatalogSubject(page, subjectId, body?.data || {}, deadline);
    body = await responseBody(recommendationResponse);
  }
  const data = body?.data || {};
  const selected = selectCatalogCandidates(data, requestedFamilies, requestedCandidateIds);
  const allGroups = groupCandidates(data.recommendations);
  const browse = await chooseBrowseMode(page, deadline).catch(() => false);
  ui.browseVisible = browse;
  if (!browse) ui.missing = true;

  const families = selected.blocked.map((item) => ({
    familyId: item.familyId,
    family: item.familyLabel,
    candidateId: item.candidateId,
    unit: null,
    source: null,
    period: detailPeriod(data),
    facts: [],
    rowTotal: null,
    filenames: [],
    exportCoverage: [],
    http: { recommendation: recommendationResponse?.status() || null, detail: null, exports: {} },
    checks: { familySelected: false, cardRendered: false, chartRendered: false, factsPresent: false, detailHttp: false, detailScope: false, detailRows: false, exportAtLeastOne: false },
    checkFailures: ['candidateQualified'],
    status: 'UNVERIFIED',
    blockedReason: item.reason,
  }));
  const exports = [];
  const screenshots = [];
  const processedCandidateIds = new Set();
  const batches = [];
  for (const item of selected.selected) {
    batches.push([item]);
  }
  let batchNumber = 0;
  let selectionFailed = false;
  for (const batch of batches) {
    if (Date.now() >= deadline) break;
    batchNumber += 1;
    const batchFamilies = [];
    for (const item of batch) {
      if (Date.now() >= deadline) break;
      const candidate = item.candidate;
      const summary = {
        familyId: item.familyId,
        family: item.familyLabel,
        candidateId: candidate.id,
        unit: candidate.unit || null,
        source: candidate.source || null,
        period: detailPeriod(data),
        facts: Array.isArray(candidate.kpis) ? candidate.kpis.slice(0, 20).map((fact) => ({ label: fact?.label || null, value: fact?.value ?? null, unit: fact?.unit || null })) : [],
        rowTotal: null,
        filenames: [],
        exportCoverage: [],
        http: { recommendation: recommendationResponse?.status() || null, detail: null, exports: {} },
        checks: { familySelected: false, cardRendered: false, chartRendered: false, unitAligned: false, factsPresent: false, detailHttp: false, detailScope: false, detailRows: false, exportAtLeastOne: false },
        checkFailures: [],
        status: 'UNVERIFIED',
        batch: batchNumber,
      };
      summary.checks.unitAligned = candidateUnitsAligned(candidate);
      const purchasePrice = purchasePriceEvidence(candidate);
      summary.purchasePrice = purchasePrice;
      summary.checks.factsPresent = summary.facts.length > 0 || Boolean(purchasePrice);
      summary.checks.priceEvidence = familyOf(candidate) === 'purchase.price' ? Boolean(purchasePrice) : true;
      summary.checks.familySelected = await selectFamilyPreview(page, item, allGroups, deadline).catch((error) => {
        summary.selectionError = String(error?.message || error).slice(0, 500);
        return false;
      });
      if (!summary.checks.familySelected) {
        summary.checkFailures = ['familySelected'];
        families.push(summary);
        processedCandidateIds.add(summary.candidateId);
        ui.selectionFailure = true;
        selectionFailed = true;
        break;
      }
      summary.checks.variantSelected = summary.checks.familySelected;
      const card = testId(page, `factory-chart-card-${candidate.id}`).first();
      summary.checks.cardRendered = await card.waitFor({ state: 'visible', timeout: Math.min(5000, remainingTimeout(deadline)) }).then(() => true).catch(() => false);
      if (!summary.checks.cardRendered) {
        await testId(page, 'factory-chart-guide-generate').click({ timeout: remainingTimeout(deadline) }).catch(() => {});
        summary.checks.cardRendered = await card.isVisible().catch(() => false);
      }
      if (summary.checks.cardRendered) {
        // Chart mounting is viewport-driven; DOM visibility alone does not
        // mean the observer has initialized ECharts below the fold.
        await card.scrollIntoViewIfNeeded({ timeout: remainingTimeout(deadline) });
        summary.checks.chartRendered = await card.locator('.factory-chart-canvas canvas, .factory-chart-canvas svg')
          .first().waitFor({ state: 'visible', timeout: Math.min(5000, remainingTimeout(deadline)) })
          .then(() => true).catch(() => false);
      }
      summary.cardScreenshot = summary.checks.chartRendered
        ? await captureCardScreenshot(card, inspectCtx, candidate.id, batchNumber)
        : null;
      summary.checks.cardScreenshot = !inspectCtx.evidenceDir || Boolean(summary.cardScreenshot);

      if (summary.checks.cardRendered) {
    const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true }).first();
        if (await detailsButton.count()) {
          const recommendationRefresh = candidate.detailContext
            ? Promise.resolve(null)
            : waitResponse(page, isRecommendationsResponse, deadline).catch(() => null);
          const detailsResponsePromise = waitResponse(page, isDetailsResponse, deadline).catch(() => null);
          await detailsButton.click({ timeout: remainingTimeout(deadline) }).catch(() => {});
          const refreshed = await recommendationRefresh;
          const detailsResponse = await detailsResponsePromise;
          const detailBody = await responseBody(detailsResponse);
          const detail = compactDetail(detailsResponse, detailBody, candidate, detailPeriod(data));
          summary.http.detail = detail.httpStatus;
          summary.rowTotal = detail.rowTotal;
          summary.checks.detailHttp = detail.success;
          summary.checks.detailScope = detail.scopeMatches;
          summary.checks.detailRows = detail.rowsPresent && detail.rowTotal > 0;
          if (refreshed && refreshed.status() !== 200) ui.httpFailure = true;
        }
        if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
      }
      batchFamilies.push(summary);
    }
    if (selectionFailed) break;
    if (batchFamilies.length && Date.now() < deadline) {
      const result = await captureExport(page, EXPORTS[0], inspectCtx, deadline, batchNumber);
      exports.push({ batch: batchNumber, ...result });
      for (const family of batchFamilies) {
        if (result.success && result.candidateIds.includes(family.candidateId)) {
          family.exportCoverage.push(result.format);
          family.filenames.push(result.filename);
        }
        family.http.exports[result.format] = result.httpStatus;
      }
    }
    for (const family of batchFamilies) {
      family.checks.exportAtLeastOne = family.exportCoverage.length > 0;
      family.checkFailures = checkFailures(family.checks);
      family.status = family.checkFailures.length ? 'UNVERIFIED' : 'PASS';
      families.push(family);
      processedCandidateIds.add(family.candidateId);
    }
    if (batchFamilies.length && inspectCtx.screenshot) {
      screenshots.push(await inspectCtx.screenshot(`factory-catalog-batch-${batchNumber}`));
    }
  }
  const eligibilityGaps = Array.isArray(data.unavailable) ? data.unavailable.map((item) => ({
    id: item?.id || null,
    title: item?.title || null,
    missing: Array.isArray(item?.missing) ? item.missing.slice(0, 12) : [],
  })) : [];
  const sourceErrors = Array.isArray(data.sourceErrors) ? data.sourceErrors.map((item) => ({
    source: item?.source || null,
    message: item?.message || null,
  })) : [];
  const remainingFamilyCount = selected.selected.filter((item) => !processedCandidateIds.has(item.candidate.id)).length;
  const requestedUnknown = [...selected.requestedUnknown, ...selected.requestedCandidateUnknown.map((id) => `candidate:${id}`)];
  const assessment = assessCatalog({ families, requestedUnknown, deferred: selected.deferred, excluded: selected.excluded, eligibilityGaps, sourceErrors, remainingFamilyCount, ui });
  return {
    assessment,
    ...assessment,
    families,
    flatFamilyResults: families.map((item) => `${item.familyId}|${item.candidateId}|${item.status}|${(item.checkFailures || []).join(',')}`),
    selectionErrors: families.filter(item => item.selectionError).map(item => `${item.familyId}: ${item.selectionError}`),
    screenshots,
    exports,
    eligibilityGaps,
    sourceErrors,
    ui,
    durationMs: Date.now() - startedAt,
  };
}

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-catalog-matrix-readonly',
    path: PATH,
    // The browse control appears after asynchronous catalogue discovery;
    // inspectCatalog waits for it and proves selection/details/exports.
    landmarks: ['工厂分析'],
    timeoutMs: 60_000,
    appMountTimeoutMs: 90_000,
    inspect: inspectCatalog,
  });
}

module.exports = {
  id: 'smartbi-catalog-matrix-readonly',
  defaultEnabled: false,
  run,
  inspectCatalog,
  assessCatalog,
  candidateIsQualified,
  candidateUnitsAligned,
  purchasePriceEvidence,
  familyOf,
  groupCandidates,
  isRestaurantOnly,
  selectCatalogCandidates,
  summarizeExportIds,
  validateCatalogFamilies,
  validateCatalogCandidateIds,
  validateCatalogSubjectId,
  isRecommendationsResponse,
  isDetailsResponse,
  isExportResponse,
};
