'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const { findLegacyBatchIndex } = require('./smartbi-focused-acceptance-readonly');

const PATH = '/smart-bi/analysis?tab=query&mode=factory&source=upload&upload_id=5278&inspection_upload_id=5277&cost_upload_id=5279';
const ANALYSIS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/;
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const UPLOADS_PATH = /^\/api\/mobile\/F006\/smart-bi\/uploads\/?$/;
const RAW_UPLOAD_PATH = /^\/api\/mobile\/F006\/smart-bi\/uploads\/(\d+)\/data\/?$/;
const QUESTION = '关联生产、质检和成本一起看';
const DEFAULT_OPTIONS = Object.freeze({
  uploadId: 5278,
  inspectionUploadId: 5277,
  costUploadId: 5279,
  targetProductId: 'product-13dbfb8a219e694e',
  expectedSubjectCount: 100,
  expectedProductRows: 5,
  expectedRawRows: 500,
  pageSize: 50,
  expectedKpis: { production: 500, inspection: 96, quality: 95, cost: 6250 },
});

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function requestBody(response) {
  try { return response.request().postDataJSON() || {}; } catch { return {}; }
}

async function responseBody(response) {
  return response.json().catch(() => ({}));
}

function isAnalysisResponse(response) {
  return response.request().method() === 'POST' && ANALYSIS_PATH.test(endpointPath(response.url()));
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST'
    && endpointPath(response.url()).replace(/\/$/u, '') === DETAILS_PATH;
}

function isUploadsResponse(response) {
  return response.request().method() === 'GET' && UPLOADS_PATH.test(endpointPath(response.url()));
}

function isRawUploadResponse(response, uploadId, page, size) {
  if (response.request().method() !== 'GET') return false;
  const match = endpointPath(response.url()).match(RAW_UPLOAD_PATH);
  if (!match || Number(match[1]) !== Number(uploadId)) return false;
  const url = new URL(response.url());
  return Number(url.searchParams.get('page')) === page && Number(url.searchParams.get('size')) === size;
}

function parseNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : NaN;
  if (typeof value !== 'string') return NaN;
  return Number(value.replace(/[,%，％\s]/gu, ''));
}

function validateScaleOptions(value) {
  const supplied = value == null ? {} : value;
  if (!supplied || typeof supplied !== 'object' || Array.isArray(supplied)) {
    throw new Error('smartbiScale must be an object');
  }
  const options = { ...DEFAULT_OPTIONS, ...supplied, expectedKpis: { ...DEFAULT_OPTIONS.expectedKpis, ...(supplied.expectedKpis || {}) } };
  for (const key of ['uploadId', 'inspectionUploadId', 'costUploadId']) {
    if (!Number.isSafeInteger(options[key]) || options[key] <= 0) throw new Error(`smartbiScale.${key} must be a positive integer`);
  }
  if (options.uploadId !== 5278 || options.inspectionUploadId !== 5277 || options.costUploadId !== 5279) {
    throw new Error('smartbiScale requires the canonical upload trio 5278/5277/5279');
  }
  if (typeof options.targetProductId !== 'string' || !options.targetProductId.trim()) {
    throw new Error('smartbiScale.targetProductId must be a non-empty stable ID');
  }
  if (options.expectedSubjectCount !== 100 || options.expectedProductRows !== 5 || options.expectedRawRows !== 500 || options.pageSize !== 50) {
    throw new Error('smartbiScale uses the canonical 100-subject/5-row/500-row/page-50 contract');
  }
  return options;
}

function subjectId(subject) {
  return typeof subject?.id === 'string' && subject.id.trim() ? subject.id : null;
}

function subjectsFromBody(body) {
  const subjects = body?.data?.subjects;
  return Array.isArray(subjects) ? subjects : [];
}

function exactUploadScope(data, options) {
  return data?.factoryId === 'F006'
    && data?.source === 'upload'
    && data?.uploadId === options.uploadId
    && data?.jointUploads?.inspectionUploadId === options.inspectionUploadId
    && data?.jointUploads?.costUploadId === options.costUploadId;
}

function exactUploadRequest(request, options) {
  return request?.source === 'upload'
    && request?.uploadId === options.uploadId
    && request?.inspectionUploadId === options.inspectionUploadId
    && request?.costUploadId === options.costUploadId;
}

function candidateKpisMatch(candidate, expectedKpis) {
  const values = Array.isArray(candidate?.kpis) ? candidate.kpis.map((item) => parseNumber(item?.value)) : [];
  return [expectedKpis.production, expectedKpis.inspection, expectedKpis.quality, expectedKpis.cost]
    .every((expected, index) => values[index] === parseNumber(expected))
    && ['kg', '%', '%', '元'].every((unit, index) => candidate?.kpis?.[index]?.unit === unit);
}

function assessProductDetail(detail, expected, candidateId, detailContextId, periodLabel, candidate) {
  const data = detail?.body?.data || detail?.data || {};
  const rows = Array.isArray(data.rows) ? data.rows : [];
  const request = detail?.request || {};
  const checks = {
    attempted: detail?.attempted === true,
    http200: detail?.status === 200,
    success: detail?.body?.success === true,
    factory: data.factoryId === 'F006',
    candidate: data.candidateId === candidateId,
    source: data.source === 'upload',
    period: data.period === periodLabel,
    context: request.context_id === detailContextId,
    requestKeys: Object.keys(request).sort().join(',') === 'context_id,filters,page,page_size',
    pageSize: request.page_size === expected.pageSize && request.page === 1,
    productRows: data.total === expected.expectedProductRows && rows.length === expected.expectedProductRows,
    noPoint: !Object.prototype.hasOwnProperty.call(request, 'point') && !Object.prototype.hasOwnProperty.call(data, 'point'),
    candidateUnit: candidate?.unit === 'kg',
    candidateFamily: candidate?.analysis?.familyId === 'production.inspection_fused',
  };
  const paginationEvidence = data.total > expected.pageSize
    && request.page === 1
    && request.page_size === expected.pageSize
    && rows.length === expected.pageSize
    && data.hasMore === true;
  checks.productPagination = paginationEvidence;
  const coreChecksPass = Object.entries(checks)
    .filter(([key]) => key !== 'productPagination')
    .every(([, value]) => value === true);
  return {
    result: coreChecksPass ? 'PASS' : 'UNVERIFIED',
    checks,
    total: Number.isFinite(Number(data.total)) ? Number(data.total) : null,
    rowCount: rows.length,
    pagination: paginationEvidence ? 'PASS' : 'UNVERIFIED',
    note: paginationEvidence
      ? 'Observed page 1 with 50 rows and hasMore=true.'
      : 'Five product rows do not prove pagination; no pagination claim is made.',
  };
}

function tablePage(body) {
  const data = body?.data || {};
  return {
    rows: Array.isArray(data.data) ? data.data : Array.isArray(data.rows) ? data.rows : [],
    total: Number(data.total),
    page: data.page,
    totalPages: data.totalPages,
  };
}

function assessRawPagination(first, second, expected) {
  const firstPage = tablePage(first?.body || first);
  const secondPage = tablePage(second?.body || second);
  const firstRows = firstPage.rows;
  const secondRows = secondPage.rows;
  const firstRequest = first?.request || {};
  const secondRequest = second?.request || {};
  const checks = {
    firstHttp200: first?.status === 200,
    secondHttp200: second?.status === 200,
    firstSuccess: first?.body?.success === true,
    secondSuccess: second?.body?.success === true,
    total500: firstPage.total === expected.expectedRawRows && secondPage.total === expected.expectedRawRows,
    pageSize50: firstRequest.page_size === expected.pageSize && secondRequest.page_size === expected.pageSize,
    firstPage: firstRequest.page === 0 && firstRows.length === expected.pageSize,
    secondPage: secondRequest.page === 1 && secondRows.length === expected.pageSize,
    pageAdvances: secondRequest.page === 1 && firstRequest.page === 0,
    distinctRows: new Set([...firstRows, ...secondRows].map(row => JSON.stringify(row))).size === expected.pageSize * 2,
    mountedRows: first?.mountedRows === expected.pageSize && second?.mountedRows === expected.pageSize,
    visibleRowsMatchResponses: first?.uiMatchesRows === true && second?.uiMatchesRows === true,
    sourceSelected: first?.sourceSelected === true && second?.sourceSelected === true,
  };
  const pass = Object.values(checks).every(Boolean);
  return {
    result: pass ? 'PASS' : 'UNVERIFIED',
    checks,
    total: firstPage.total ?? null,
    loadedRowCount: firstRows.length + secondRows.length,
    pagination: pass ? 'PASS' : 'UNVERIFIED',
    note: pass
      ? 'Observed the original 100-SKU upload table at 50 rows per page; total=500.'
      : 'Original-upload pagination was not observed; it must not be inferred from five product rows.',
  };
}

function assessScale({ factoryId, options, initial, selections = [], detail, rawPagination }) {
  const subjects = subjectsFromBody(initial?.body);
  const initialData = initial?.body?.data || {};
  const initialRequest = initial?.request || {};
  const initialScope = exactUploadRequest(initialRequest, options) && exactUploadScope(initialData, options);
  const stableSubjectIds = selections.map((item) => item.selectedSubjectId).filter(Boolean);
  const selectionChecks = selections.map((selection) => {
    const request = selection.request || {};
    const data = selection.body?.data || {};
    const candidate = Array.isArray(data.recommendations) ? data.recommendations[0] : null;
    const subjectMatches = selection.requestedSubjectId === selection.selectedSubjectId
      && subjectId(subjects.find((item) => subjectId(item) === selection.selectedSubjectId)) === selection.selectedSubjectId;
    const answerMatches = data.analysisSubject === selection.selectedSubjectId
      && exactUploadScope(data, options)
      && exactUploadRequest(request, options)
      && request.analysisSubject === selection.selectedSubjectId;
    return {
      subjectMatches,
      answerMatches,
      oneCandidate: Array.isArray(data.recommendations) && data.recommendations.length === 1,
      expectedKpis: selection.selectedSubjectId === options.targetProductId ? candidateKpisMatch(candidate, options.expectedKpis) : true,
      expectedRows: Array.isArray(candidate?.tableData?.rows) && candidate.tableData.rows.length === options.expectedProductRows,
      answered: selection.status === 200 && selection.body?.success === true && data.status === 'answered',
      family: candidate?.analysis?.familyId === 'production.inspection_fused',
      unit: candidate?.unit === 'kg',
      chartsBounded: Number.isInteger(selection.visibleChartCount) && selection.visibleChartCount > 0 && selection.visibleChartCount < options.expectedSubjectCount,
      canvasesBounded: Number.isInteger(selection.renderedCanvasCount) && selection.renderedCanvasCount > 0 && selection.renderedCanvasCount < options.expectedSubjectCount,
    };
  });
  const checks = {
    expectedFactory: factoryId === 'F006',
    initialHttp200: initial?.status === 200,
    initialSuccess: initial?.body?.success === true,
    initialUploadRequest: initialScope,
    subjectCount: subjects.length === options.expectedSubjectCount,
    subjectsHaveStableIds: subjects.length === options.expectedSubjectCount
      && subjects.every((item) => Boolean(subjectId(item)))
      && new Set(subjects.map(subjectId)).size === options.expectedSubjectCount,
    atLeastTwoProductFilters: selections.filter((item) => item.request?.analysisSubject).length >= 2,
    firstMiddleLastSelected: firstMiddleLastIds(subjects).every((id) => stableSubjectIds.includes(id)),
    distinctSelectedSubjects: new Set(stableSubjectIds).size >= 3,
    answerCount: selectionChecks.length === selections.length && selectionChecks.length >= 2,
    answersMatchSelectedIds: selectionChecks.length > 0 && selectionChecks.every((item) => item.answerMatches && item.subjectMatches),
    oneCandidateEach: selectionChecks.length > 0 && selectionChecks.every((item) => item.oneCandidate),
    targetSelected: selections.some((item) => item.selectedSubjectId === options.targetProductId),
    targetKpis: selections.some((item) => item.selectedSubjectId === options.targetProductId)
      && selectionChecks.every((item, index) => selections[index]?.selectedSubjectId !== options.targetProductId || item.expectedKpis),
    fiveRowsEach: selectionChecks.length > 0 && selectionChecks.every((item) => item.expectedRows),
    answeredEach: selectionChecks.length > 0 && selectionChecks.every((item) => item.answered),
    familyEach: selectionChecks.length > 0 && selectionChecks.every((item) => item.family),
    unitEach: selectionChecks.length > 0 && selectionChecks.every((item) => item.unit),
    noHundredCharts: selectionChecks.length > 0 && selectionChecks.every((item) => item.chartsBounded && item.canvasesBounded),
    productDetail: detail?.result === 'PASS' && detail?.checks?.productRows === true,
    // Product detail is only five rows; the actual 500-row upload table is
    // the sole evidence allowed to satisfy the pagination requirement.
    pagination: rawPagination?.result === 'PASS',
  };
  const failReasons = Object.entries(checks).filter(([, value]) => value !== true).map(([key]) => key);
  const hardDefect = !checks.expectedFactory || !checks.initialHttp200 || !checks.initialSuccess || !checks.initialUploadRequest
    || (checks.subjectCount && selectionChecks.some((item) => !item.answerMatches || !item.subjectMatches || !item.chartsBounded || !item.canvasesBounded));
  const missingEvidence = !checks.subjectCount || !checks.productDetail || !checks.pagination;
  const result = hardDefect ? 'PARTIAL_DEFECT' : failReasons.length || missingEvidence ? 'UNVERIFIED' : 'PASS';
  return {
    result,
    fullPass: result === 'PASS',
    rootCauseClass: hardDefect ? 'frontend' : result === 'PASS' ? 'none' : 'data',
    checks,
    failReasons,
    subjectCount: subjects.length,
    selectedSubjectIds: stableSubjectIds,
    selectionChecks,
    detail: detail || null,
    rawPagination: rawPagination || null,
    note: result === 'PASS'
      ? '100 stable products, bounded rendering, exact subject-scoped answers, target KPI/rows and real pagination evidence passed.'
      : 'Scale acceptance is fail-closed; missing subjects, scope, selected IDs, rendering bounds, or pagination stay unverified/defective.',
  };
}

function firstMiddleLastIds(subjects) {
  return [subjects[0], subjects[Math.floor(subjects.length / 2)], subjects[subjects.length - 1]]
    .map(subjectId).filter(Boolean);
}

async function waitForResponse(page, predicate, action, timeout = 120_000) {
  const promise = page.waitForResponse(predicate, { timeout });
  await action();
  return promise;
}

async function selectSubject(page, subject) {
  const control = page.getByTestId('factory-analysis-subject');
  await control.click();
  const input = control.locator('input').first();
  if (await input.count()) await input.fill(subject.label);
  const option = page.locator('[role="option"], .el-select-dropdown__item').filter({ hasText: subject.label }).first();
  await option.waitFor({ state: 'visible', timeout: 15_000 });
  await option.click();
}

async function runRawUploadPagination(page, options) {
  const historyPromise = page.waitForResponse(isUploadsResponse, { timeout: 30_000 }).catch(() => null);
  await page.goto(`${page.url().split('/smart-bi/')[0]}/smart-bi/analysis?tab=analysis`, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  const historyResponse = await historyPromise;
  if (!historyResponse) return { result: 'UNVERIFIED', pagination: 'UNVERIFIED', note: 'Upload history response was not observed.' };
  const historyBody = await responseBody(historyResponse);
  const uploads = historyBody?.data?.content || historyBody?.data || [];
  const targetUpload = Array.isArray(uploads) ? uploads.find((item) => Number(item?.id) === options.uploadId) : null;
  if (!targetUpload) return { result: 'UNVERIFIED', pagination: 'UNVERIFIED', note: 'Canonical upload 5278 was not present in the live upload history.' };
  const switcher = page.locator('.header-actions .el-select').first();
  const batchIndex = findLegacyBatchIndex(uploads, { production: options.uploadId, quality: options.inspectionUploadId, cost: options.costUploadId });
  if (batchIndex == null) return { result: 'UNVERIFIED', pagination: 'UNVERIFIED', note: 'The exact three-upload batch was not present.' };
  await switcher.click();
  const batchOption = page.locator('.el-select-dropdown__item:visible').nth(batchIndex + 1);
  await batchOption.waitFor({ state: 'visible', timeout: 20_000 });
  if (!(await batchOption.innerText()).includes(targetUpload.fileName)) throw new Error('Original-upload batch label drifted');
  await batchOption.click();
  const sheetTab = page.locator('.sheet-tabs .el-tabs__item:visible').filter({ hasText: targetUpload.sheetName }).first();
  if (await sheetTab.count()) await sheetTab.click();
  const previewButton = page.getByRole('button', { name: '查看原始数据', exact: true }).first();
  await previewButton.waitFor({ state: 'visible', timeout: 60_000 });
  const firstPromise = page.waitForResponse((response) => isRawUploadResponse(response, options.uploadId, 0, options.pageSize), { timeout: 30_000 });
  await previewButton.click();
  const firstResponse = await firstPromise;
  const firstBody = await responseBody(firstResponse);
  const dialog = page.locator('.el-dialog:visible').first();
  await dialog.locator('.el-table__body-wrapper .el-table__row').first().waitFor({ state: 'visible', timeout: 15_000 });
  const firstMountedRows = await dialog.locator('.el-table__body-wrapper .el-table__row').count();
  const rowMatches = async body => {
    const record = tablePage(body).rows[0];
    if (!record) return false;
    const values = Object.values(record).filter(value => value != null && ['string', 'number', 'boolean'].includes(typeof value))
      .map(String).filter(Boolean);
    const text = await dialog.locator('.el-table__body-wrapper .el-table__row').first().innerText();
    return values.length > 0 && values.every(value => text.includes(value));
  };
  const firstUiMatchesRows = await rowMatches(firstBody);
  const next = dialog.locator('.el-pagination .btn-next').first();
  await next.waitFor({ state: 'visible', timeout: 15_000 });
  const secondPromise = page.waitForResponse((response) => isRawUploadResponse(response, options.uploadId, 1, options.pageSize), { timeout: 30_000 });
  await next.click();
  const secondResponse = await secondPromise;
  const secondBody = await responseBody(secondResponse);
  await dialog.locator('.el-pagination .number.is-active').filter({ hasText: /^2$/u }).waitFor({ state: 'visible', timeout: 15_000 });
  const secondMountedRows = await dialog.locator('.el-table__body-wrapper .el-table__row').count();
  const secondUiMatchesRows = await rowMatches(secondBody);
  const selectedLabel = `${await switcher.innerText()} ${await switcher.locator('input').inputValue().catch(() => '')}`;
  const sourceSelected = selectedLabel.includes(targetUpload.fileName);
  const rawRequest = response => {
    const url = new URL(response.url());
    return { page: Number(url.searchParams.get('page')), page_size: Number(url.searchParams.get('size')) };
  };
  return assessRawPagination(
    { status: firstResponse.status(), body: firstBody, request: rawRequest(firstResponse), mountedRows: firstMountedRows, sourceSelected, uiMatchesRows: firstUiMatchesRows },
    { status: secondResponse.status(), body: secondBody, request: rawRequest(secondResponse), mountedRows: secondMountedRows, sourceSelected, uiMatchesRows: secondUiMatchesRows },
    options,
  );
}

async function inspectScale(page, inspectCtx) {
  if (inspectCtx.expectedFactoryId !== 'F006') throw new Error('SmartBI scale acceptance is limited to F006');
  const rawOptions = inspectCtx.smartbiScale || inspectCtx.options?.smartbiScale;
  if (!rawOptions) throw new Error('smartbiScale option is required; do not run scale acceptance with implicit defaults');
  const options = validateScaleOptions(rawOptions);
  await page.getByTestId('factory-analysis-chat').waitFor({ timeout: 60_000 });
  const startedAt = Date.now();
  const initialResponse = await waitForResponse(page, isAnalysisResponse, async () => {
    await page.getByTestId('factory-analysis-input').fill(QUESTION);
    await page.getByTestId('factory-analysis-submit').click();
  });
  const initialBody = await responseBody(initialResponse);
  const initial = { status: initialResponse.status(), body: initialBody, request: requestBody(initialResponse) };
  const subjects = subjectsFromBody(initialBody);
  const ordered = [subjects[0], subjects[Math.floor(subjects.length / 2)], subjects[subjects.length - 1]]
    .filter(Boolean);
  const target = subjects.find((item) => subjectId(item) === options.targetProductId);
  if (target && !ordered.some((item) => subjectId(item) === options.targetProductId)) ordered.push(target);
  const selections = [];
  const screenshots = [];
  let detail = null;
  for (const [selectionIndex, subject] of ordered.entries()) {
    const selectedId = subjectId(subject);
    if (!selectedId) continue;
    await selectSubject(page, subject);
    const selectedResponse = selectionIndex === 0
      ? await waitForResponse(page, isAnalysisResponse, () => page.getByRole('button', { name: '按当前产品重试', exact: true }).last().click())
      : await waitForResponse(page, isAnalysisResponse, async () => {
        await page.getByTestId('factory-analysis-input').fill(QUESTION);
        await page.getByTestId('factory-analysis-submit').click();
      });
    const body = await responseBody(selectedResponse);
    const request = requestBody(selectedResponse);
    await page.getByTestId('factory-analysis-answer').last().waitFor({ timeout: 30_000 });
    const cards = page.locator('[data-testid^="factory-chart-card-"]:visible');
    await cards.first().waitFor({ timeout: 30_000 });
    const visibleChartCount = await cards.count();
    for (let index = 0; index < visibleChartCount; index += 1) {
      await cards.nth(index).scrollIntoViewIfNeeded({ timeout: 10_000 });
      await cards.nth(index).locator('canvas').first().waitFor({ state: 'visible', timeout: 10_000 });
    }
    const renderedCanvasCount = await cards.locator('canvas').count();
    const selection = { status: selectedResponse.status(), requestedSubjectId: selectedId, selectedSubjectId: body?.data?.analysisSubject || request.analysisSubject || null, body, request, visibleChartCount, renderedCanvasCount };
    const candidate = (body?.data?.recommendations || [])[0];
    if (selectedId === options.targetProductId && candidate?.id) {
      const card = page.getByTestId(`factory-chart-card-${candidate.id}`);
      const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true });
      if (await detailsButton.count()) {
        const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
        await detailsButton.click();
        const detailsResponse = await responsePromise;
        const detailsBody = detailsResponse ? await responseBody(detailsResponse) : {};
        detail = assessProductDetail({ attempted: true, status: detailsResponse?.status(), body: detailsBody, request: detailsResponse ? requestBody(detailsResponse) : {} }, options, candidate.id, candidate.detailContext?.id, body?.data?.period?.label, candidate);
        if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
      } else {
        detail = assessProductDetail({ attempted: false, status: null, body: { success: false, data: {} }, request: {} }, options, candidate.id, candidate.detailContext?.id, body?.data?.period?.label, candidate);
      }
    }
    selections.push(selection);
    if (selectedId === options.targetProductId && inspectCtx.screenshot) {
      screenshots.push(await inspectCtx.screenshot('scale-selected-product-answer'));
    }
  }
  const rawPagination = await runRawUploadPagination(page, options);
  if (inspectCtx.screenshot) screenshots.push(await inspectCtx.screenshot('scale-original-upload-page-two'));
  const assessment = assessScale({ factoryId: inspectCtx.expectedFactoryId, options, initial, selections, detail, rawPagination });
  return {
    assessment,
    ...assessment,
    screenshots,
    initial: { status: initial.status, request: initial.request, subjectCount: subjects.length, subjectIds: subjects.map(subjectId).filter(Boolean) },
    selections: selections.map((item) => ({
      requestedSubjectId: item.requestedSubjectId,
      selectedSubjectId: item.selectedSubjectId,
      request: item.request,
      response: { factoryId: item.body?.data?.factoryId, source: item.body?.data?.source, analysisSubject: item.body?.data?.analysisSubject, recommendationCount: item.body?.data?.recommendations?.length || 0 },
      visibleChartCount: item.visibleChartCount,
      renderedCanvasCount: item.renderedCanvasCount,
    })),
    rawPagination,
    durationMeasuredMs: Date.now() - startedAt,
  };
}

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-scale-readonly',
    path: PATH,
    landmarks: ['工厂 AI 问答分析', '当前授权图表目录'],
    timeoutMs: 60_000,
    appMountTimeoutMs: 90_000,
    inspect: (page, _body, inspectCtx) => inspectScale(page, inspectCtx),
  });
}

module.exports = {
  id: 'smartbi-scale-readonly',
  defaultEnabled: false,
  PATH,
  DEFAULT_OPTIONS,
  validateScaleOptions,
  assessProductDetail,
  assessRawPagination,
  assessScale,
  inspectScale,
  run,
  isAnalysisResponse,
  isDetailsResponse,
};
