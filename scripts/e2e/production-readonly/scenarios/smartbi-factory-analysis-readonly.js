'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis?tab=query&mode=factory';
const ANALYSIS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/;
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const QUESTION = '汇总当前库存、生产和销售表现';
const JOINT_QUESTION = '关联生产、检验和成本做多表分析';

const EXPORT_FORMATS = [
  { key: 'excel', label: 'Excel', path: '/smartbi-api/api/smartbi/financial-dashboard/export-excel', extension: 'xlsx' },
  { key: 'pdf', label: 'PDF', path: '/smartbi-api/api/smartbi/financial-dashboard/export-pdf', extension: 'pdf' },
  { key: 'ppt', label: 'PPT', path: '/smartbi-api/api/smartbi/financial-dashboard/export-ppt', extension: 'pptx' },
];

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function isAnalysisResponse(response) {
  return response.request().method() === 'POST' && ANALYSIS_PATH.test(endpointPath(response.url()));
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST'
    && endpointPath(response.url()).replace(/\/$/, '') === DETAILS_PATH;
}

function isExportResponse(response, format) {
  const expectedPath = typeof format === 'string'
    ? EXPORT_FORMATS.find((item) => item.key === format)?.path
    : format?.path;
  return response.request().method() === 'POST'
    && Boolean(expectedPath)
    && endpointPath(response.url()).replace(/\/$/, '') === expectedPath;
}

function requestBody(response) {
  try { return response.request().postDataJSON() || {}; } catch { return {}; }
}

async function responseBody(response) {
  return response.json().catch(() => ({}));
}

function periodSignature(period) {
  if (!period || typeof period !== 'object') return null;
  return JSON.stringify({
    type: period.periodType || period.period_type || period.type || null,
    year: period.year || null,
    startMonth: period.startMonth || period.start_month || null,
    endMonth: period.endMonth || period.end_month || null,
    label: period.label || null,
  });
}

function familyId(candidate) {
  return candidate?.analysis?.familyId || candidate?.familyId || candidate?.id || null;
}

function isFusedCandidate(candidate) {
  return familyId(candidate) === 'production.inspection_fused';
}

function selectAuthorizedSubject(subjects, requestedId) {
  if (!requestedId) return subjects[0];
  const subject = subjects.find((item) => String(item?.id) === String(requestedId));
  if (!subject) throw new Error('Requested joint-analysis subject is absent from the authorized response');
  return subject;
}

function hasThreeAxis(candidate) {
  const option = candidate?.chart?.echartsOption;
  return Array.isArray(option?.yAxis) && option.yAxis.length >= 3
    && Array.isArray(option?.series) && option.series.length >= 3;
}

function normalizedDomain(value) {
  return String(value || '').trim().toLocaleLowerCase();
}

function domainGroup(value) {
  const text = normalizedDomain(value);
  if (/(inventory|stock|库存)/u.test(text)) return 'inventory';
  if (/(production|manufactur|生产)/u.test(text)) return 'production';
  if (/(sales|order|shipment|销售)/u.test(text)) return 'sales';
  return null;
}

function qualitySemantic(value) {
  return /(quality|inspection|defect|良品|质量|检验|不良|production\.fused)/iu.test(String(value || ''));
}

function summaryFromResponse(status, body, request) {
  const data = body?.data || {};
  const recommendations = Array.isArray(data.recommendations) ? data.recommendations : [];
  const facts = Array.isArray(data.facts) ? data.facts : [];
  const limitations = Array.isArray(data.limitations) ? data.limitations : [];
  const catalog = Array.isArray(data.catalog) ? data.catalog : [];
  const families = new Set(recommendations.map(familyId).filter(Boolean).map(String));
  const fusedRecommendations = recommendations.filter(isFusedCandidate);
  const recommendationDomains = [...new Set(recommendations.flatMap((item) => [item?.domain, familyId(item)]).filter(Boolean).map(String))];
  const catalogDomains = [...new Set(catalog.flatMap((item) => [item?.domain, item?.familyId]).filter(Boolean).map(String))];
  const availableOverviewGroups = new Set(catalogDomains.map(domainGroup).filter(Boolean));
  const selectedOverviewGroups = new Set(recommendationDomains.map(domainGroup).filter(Boolean));
  const missingOverviewGroups = ['inventory', 'production', 'sales']
    .filter((group) => availableOverviewGroups.has(group) && !selectedOverviewGroups.has(group));
  return {
    http200: status === 200,
    envelopeSuccess: body?.success === true,
    status: typeof data.status === 'string' ? data.status : null,
    answered: data.status === 'answered',
    unavailable: data.status === 'unavailable',
    clarification: data.status === 'clarification',
    factoryScope: data.factoryId === 'F006',
    source: data.source || null,
    period: periodSignature(data.period),
    recommendationCount: recommendations.length,
    factCount: facts.length,
    sourceErrorCount: Array.isArray(data.sourceErrors) ? data.sourceErrors.length : 0,
    limitationCount: limitations.length,
    familyCount: families.size,
    familyIds: [...families],
    domains: recommendationDomains,
    catalogDomains,
    qualityGrounded: recommendationDomains.some(qualitySemantic),
    overviewScopeCoverage: missingOverviewGroups.length === 0 || limitations.length > 0,
    fusedCount: fusedRecommendations.length,
    fusedThreeAxis: fusedRecommendations.some(hasThreeAxis),
    fusedTableRowCount: fusedRecommendations.reduce((count, item) => count + (Array.isArray(item?.tableData?.rows) ? item.tableData.rows.length : 0), 0),
    catalogCount: catalog.length,
    contextPresent: typeof data.contextId === 'string' && data.contextId.length > 0,
    followUpCount: Array.isArray(data.followUpQuestions) ? data.followUpQuestions.length : 0,
    requestContract: Object.keys(request).sort(),
    requestSource: request.source || null,
    requestPeriod: request.periodType
      ? JSON.stringify({ periodType: request.periodType, year: request.year, startMonth: request.startMonth, endMonth: request.endMonth })
      : null,
    requestContextPresent: typeof request.contextId === 'string' && request.contextId.length > 0,
    requestSubjectPresent: typeof request.analysisSubject === 'string' && request.analysisSubject.trim().length > 0,
  };
}

function assessJoint({ initial, selected, ui, details, exportAttempt }) {
  const initialSummary = initial?.summary || {};
  const selectedSummary = selected?.summary || {};
  const hasFused = selectedSummary.fusedCount > 0;
  const expectedLimitation = !hasFused
    && selectedSummary.sourceErrorCount === 0
    && selectedSummary.limitationCount > 0
    && (selectedSummary.unavailable || selectedSummary.answered || selectedSummary.clarification);
  const checks = {
    initialClarification: initialSummary.clarification === true,
    selectedSubjectRetried: selected?.attempted === true,
    selectedSubjectProvided: selectedSummary.requestSubjectPresent === true,
    selectedFactoryF006: selectedSummary.factoryScope === true,
    selectedSourceBusiness: selectedSummary.source === 'business',
    selectedAnswered: selectedSummary.answered === true,
    fusedFamily: hasFused,
    fusedThreeAxis: selectedSummary.fusedThreeAxis === true,
    fusedTable: selectedSummary.fusedTableRowCount > 0,
    fusedFacts: selectedSummary.factCount > 0,
    detailAttempted: details?.attempted === true,
    detailSucceeded: details?.success === true,
    exportAttempted: exportAttempt?.attempted === true,
    exportSucceeded: exportAttempt?.success === true,
  };
  const positivePass = Object.values(checks).every(Boolean);
  return {
    result: positivePass ? 'PASS' : expectedLimitation ? 'NEGATIVE_PASS' : 'UNVERIFIED',
    positiveJoint: positivePass ? 'PASS' : 'UNVERIFIED_DATA_NOT_ELIGIBLE',
    negativePass: expectedLimitation,
    expectedLimitation,
    checks,
    status: selectedSummary.status || null,
    note: positivePass
      ? 'Real production.inspection_fused candidate passed family, three-axis, table, facts, details, and export checks.'
      : expectedLimitation
        ? 'Expected limitation recorded: no eligible complete joint dataset; positive joint acceptance remains UNVERIFIED_DATA_NOT_ELIGIBLE.'
        : 'Joint analysis did not provide either a grounded fused candidate or an evidenced data limitation.',
  };
}

function assess({ initial, followup, ui, details, exportAttempt, joint }) {
  const initialSummary = initial?.summary || {};
  const followupSummary = followup?.summary || {};
  const initialAnswered = initialSummary.answered === true;
  const unavailable = initialSummary.unavailable === true || followupSummary.unavailable === true;
  const checks = {
    initialHttp200: initialSummary.http200 === true,
    initialSuccess: initialSummary.envelopeSuccess === true,
    factoryF006: initialSummary.factoryScope === true && followupSummary.factoryScope === true,
    sourceBusiness: initialSummary.source === 'business' && followupSummary.source === 'business',
    samePeriod: Boolean(initialSummary.period) && initialSummary.period === followupSummary.period,
    answeredWithChartsAndFacts: initialAnswered && initialSummary.recommendationCount > 0 && initialSummary.factCount > 0,
    followupAnsweredWithChartsOrFacts: followupSummary.answered === true
      && (followupSummary.recommendationCount > 0 || followupSummary.factCount > 0),
    boundedFamilies: initialSummary.familyCount > 0 && initialSummary.familyCount <= 4
      && followupSummary.familyCount > 0 && followupSummary.familyCount <= 4,
    catalogRetained: initialSummary.catalogCount > 0 && ui?.catalogVisibleCount > 0,
    visibleChartsRendered: ui?.visibleChartCount > 0 && ui?.renderedCanvasCount > 0 && ui?.drawingErrorCount === 0,
    groundedFactsVisible: initialSummary.factCount === 0 || ui?.visibleFactCount > 0,
    limitationsVisible: initialSummary.limitationCount === 0 || ui?.visibleLimitationCount > 0,
    sourceErrorsVisible: initialSummary.sourceErrorCount === 0 || ui?.visibleSourceErrorCount > 0,
    noSourceErrors: initialSummary.sourceErrorCount === 0 && followupSummary.sourceErrorCount === 0,
    overviewScopeCoverage: initialSummary.overviewScopeCoverage === true,
    followupContract: followupSummary.requestContract?.join(',') === 'contextId,question'
      && followupSummary.requestContextPresent === true,
    followupDifferentDimension: ui?.followupDifferentDimension === true,
    followupQualityGrounded: followupSummary.qualityGrounded === true,
    detailsAttempted: details?.attempted === true,
    detailsSucceeded: details?.success === true,
    exportAttempted: exportAttempt?.attempted === true,
    exportSucceeded: exportAttempt?.success === true,
  };
  const validChecks = Object.values(checks).every(Boolean);
  const jointAssessment = joint || assessJoint({});
  const acceptedJoint = jointAssessment.positiveJoint === 'PASS' || jointAssessment.negativePass === true;
  const result = unavailable ? 'UNVERIFIED' : validChecks && acceptedJoint ? 'PASS' : 'UNVERIFIED';
  return {
    result,
    rootCauseClass: result === 'PASS' ? 'none' : 'tool',
    checks,
    fullPass: result === 'PASS' && validChecks && jointAssessment.positiveJoint === 'PASS',
    partialReadWarn: !checks.noSourceErrors,
    joint: jointAssessment,
    status: initialSummary.status,
    followupStatus: followupSummary.status,
    chartCount: ui?.visibleChartCount || 0,
    factCount: initialSummary.factCount || 0,
    familyCount: initialSummary.familyCount || 0,
    catalogCount: initialSummary.catalogCount || 0,
    unavailableIsUnverified: unavailable ? result === 'UNVERIFIED' : true,
    clarificationIsUnverified: initialSummary.clarification ? result === 'UNVERIFIED' : true,
    note: unavailable || initialSummary.clarification
      ? 'AI returned unavailable/clarification; this run is UNVERIFIED and does not claim AI acceptance.'
      : validChecks && acceptedJoint
        ? jointAssessment.negativePass
          ? 'Overview checks passed; joint analysis recorded an expected data limitation without claiming a positive AI result.'
          : 'Grounded overview/joint chart-fact, follow-up, details, and export checks passed.'
        : 'One or more grounded UI checks were incomplete.',
  };
}

async function screenshotRedacted(ctx, page, name) {
  const changed = await page.evaluate(() => {
    const selectors = [
      '[data-testid="factory-analysis-question"]',
      '[data-testid="factory-analysis-answer"]',
      '[data-testid="factory-analysis-facts"]',
      '.scope-hint',
      '.details-meta',
      '.point-context',
      '.details-table-wrap',
    ];
    const records = [];
    for (const selector of selectors) {
      [...document.querySelectorAll(selector)].forEach((element, index) => {
        records.push({ selector, index, html: element.innerHTML });
        element.textContent = '[脱敏证据]';
      });
    }
    return records;
  });
  try { return await ctx.screenshot(name); }
  finally {
    await page.evaluate((records) => {
      for (const record of records) {
        const element = document.querySelectorAll(record.selector)[record.index];
        if (element) element.innerHTML = record.html;
      }
    }, changed).catch(() => {});
  }
}

async function waitForResponse(page, predicate, action, timeout = 120_000) {
  const responsePromise = page.waitForResponse(predicate, { timeout });
  await action();
  return responsePromise;
}

function summarizeExportResults(results) {
  const formats = {};
  for (const result of results) formats[result.format] = {
    attempted: result.attempted === true,
    buttonFound: result.buttonFound === true,
    downloadStarted: result.downloadStarted === true,
    httpStatus: Number.isInteger(result.httpStatus) ? result.httpStatus : null,
    saved: result.saved === true,
    success: result.success === true,
    fileName: result.fileName || null,
  };
  const complete = EXPORT_FORMATS.every((format) => formats[format.key]);
  const attempted = complete && EXPORT_FORMATS.every((format) => formats[format.key].attempted);
  const success = attempted && EXPORT_FORMATS.every((format) => formats[format.key].success);
  return { attempted, success, formats };
}

async function downloadExportFormats(page, answer, evidenceDir, prefix, timeout = 30_000) {
  const results = [];
  const tools = answer.locator('.answer-tools:visible').first();
  for (const format of EXPORT_FORMATS) {
    const fileName = `${prefix}-${format.key}.${format.extension}`;
    const result = {
      format: format.key,
      attempted: true,
      buttonFound: false,
      downloadStarted: false,
      httpStatus: null,
      saved: false,
      success: false,
      fileName,
    };
    try {
      const button = tools.getByRole('button', { name: format.label, exact: true }).first();
      result.buttonFound = await button.count() > 0;
      if (!result.buttonFound) {
        results.push(result);
        continue;
      }
      const downloadPromise = page.waitForEvent('download', { timeout }).catch(() => null);
      const exportResponsePromise = page.waitForResponse(
        (response) => isExportResponse(response, format),
        { timeout },
      ).catch(() => null);
      await button.click();
      const [download, exportResponse] = await Promise.all([downloadPromise, exportResponsePromise]);
      result.downloadStarted = Boolean(download);
      result.httpStatus = exportResponse ? exportResponse.status() : null;
      if (download && exportResponse && exportResponse.status() === 200) {
        const target = `${String(evidenceDir || '').replace(/[\\/]+$/, '')}/${fileName}`;
        if (evidenceDir) {
          await download.saveAs(target);
          result.saved = true;
        }
      }
      result.success = result.downloadStarted && result.httpStatus === 200 && (Boolean(evidenceDir) ? result.saved : true);
    } catch {
      result.success = false;
    }
    results.push(result);
  }
  return summarizeExportResults(results);
}

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-analysis-readonly',
    path: PATH,
    landmarks: ['工厂 AI 问答分析', '当前授权图表目录'],
    timeoutMs: 60_000,
    appMountTimeoutMs: 90_000,
    inspect: async (page, _body, inspectCtx) => {
      if (inspectCtx.expectedFactoryId !== 'F006') throw new Error('Factory AI acceptance is limited to F006');
      await page.getByTestId('factory-analysis-chat').waitFor({ timeout: 60_000 });
      const initialResponse = await waitForResponse(
        page,
        isAnalysisResponse,
        async () => {
          await page.getByTestId('factory-analysis-input').fill(QUESTION);
          await page.getByTestId('factory-analysis-submit').click();
        },
      );
      const initialBody = await responseBody(initialResponse);
      const initial = {
        status: initialResponse.status(),
        summary: summaryFromResponse(initialResponse.status(), initialBody, requestBody(initialResponse)),
      };

      if (!initial.summary.answered) {
        const screenshot = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-unverified');
        const assessment = assess({ initial, followup: { summary: {} }, ui: {}, details: {}, exportAttempt: {} });
        return { assessment, ...assessment, screenshots: [screenshot] };
      }

      await page.getByTestId('factory-analysis-answer').waitFor({ timeout: 30_000 });
      const visibleCards = page.locator('[data-testid^="factory-chart-card-"]:visible');
      await visibleCards.first().waitFor({ timeout: 30_000 });
      const visibleChartCount = await visibleCards.count();
      const renderedCanvasCount = await visibleCards.locator('.factory-chart-canvas canvas').count();
      const drawingErrorCount = await visibleCards.locator('[role="alert"]').count();
      const visibleFactCount = await page.locator('[data-testid="factory-analysis-facts"] .fact:visible').count();
      const visibleLimitationCount = await page.locator('[data-testid="factory-analysis-limitations"]:visible').count();
      const visibleSourceErrorCount = await page.locator('[data-testid="factory-analysis-source-errors"]:visible').count();

      const catalogToggle = page.getByRole('button', { name: '展开目录', exact: true });
      if (await catalogToggle.count()) await catalogToggle.click();
      const catalogVisibleCount = await page.locator('.catalog-item:visible').count();
      const screenshotBeforeInteraction = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-answer');

      const firstDetailId = initialBody?.data?.recommendations?.find((item) => item?.detailContext?.id)?.id;
      let details = { attempted: false, success: false, drawerVisible: false };
      if (firstDetailId) {
        const card = page.getByTestId(`factory-chart-card-${firstDetailId}`);
        const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true });
        if (await detailsButton.count()) {
          details.attempted = true;
          const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
          await detailsButton.click();
          const detailResponse = await responsePromise;
          const detailBody = detailResponse ? await responseBody(detailResponse) : {};
          details.success = Boolean(detailResponse && detailResponse.status() === 200 && detailBody.success === true
            && detailBody.data?.factoryId === 'F006' && Array.isArray(detailBody.data?.rows));
          details.drawerVisible = await page.locator('.el-drawer:visible').count() > 0;
        }
      }
      const screenshotAfterDetails = details.drawerVisible
        ? await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-details')
        : null;
      if (details.drawerVisible) await page.keyboard.press('Escape').catch(() => {});

      const exportAttempt = await downloadExportFormats(
        page,
        page.locator('.turn.turn--assistant:visible').last(),
        inspectCtx.evidenceDir,
        'smartbi-factory-analysis-overview',
      );

      const followupButtons = page.locator('.followups:visible .el-button');
      const qualityFollowup = followupButtons.filter({ hasText: '质量' }).first();
      const followupButton = await qualityFollowup.count() ? qualityFollowup : followupButtons.first();
      const followupDifferentDimension = await followupButton.count()
        ? (await followupButton.innerText()).includes('质量')
        : false;
      let followup = { summary: {} };
      if (await followupButton.count()) {
        const followupResponse = await waitForResponse(page, isAnalysisResponse, () => followupButton.click());
        const followupBody = await responseBody(followupResponse);
        followup = {
          status: followupResponse.status(),
          summary: summaryFromResponse(followupResponse.status(), followupBody, requestBody(followupResponse)),
        };
      }

      // Start a fresh conversation for the explicit cross-table branch. The
      // backend must first ask for a real product subject; the selected
      // subject is taken from that response, never invented by the harness.
      let jointInitial = { summary: {} };
      let jointSelected = { attempted: false, summary: {} };
      let jointSelectedBody = {};
      let jointDetails = { attempted: false, success: false };
      let jointExport = { attempted: false, success: false, formats: {} };
      let jointScreenshot = null;
      let jointSubjectId = null;
      const newAnalysisButton = page.getByRole('button', { name: '新分析', exact: true });
      if (await newAnalysisButton.count()) {
        const jointInitialResponse = await waitForResponse(
          page,
          isAnalysisResponse,
          async () => {
            await newAnalysisButton.click();
            await page.getByTestId('factory-analysis-input').fill(JOINT_QUESTION);
            await page.getByTestId('factory-analysis-submit').click();
          },
        );
        const jointInitialBody = await responseBody(jointInitialResponse);
        jointInitial = {
          status: jointInitialResponse.status(),
          summary: summaryFromResponse(jointInitialResponse.status(), jointInitialBody, requestBody(jointInitialResponse)),
        };

        const subjects = Array.isArray(jointInitialBody?.data?.subjects) ? jointInitialBody.data.subjects : [];
        if (jointInitial.summary.clarification && subjects.length > 0) {
          const subject = selectAuthorizedSubject(subjects, inspectCtx.factoryAnalysisSubjectId);
          jointSubjectId = subject.id;
          if (typeof subject?.label !== 'string' || !subject.label.trim()) throw new Error('Factory AI clarification returned an unusable subject label');
          const subjectControl = page.getByTestId('factory-analysis-subject');
          await subjectControl.click();
          const subjectOption = page.locator('[role="option"], .el-select-dropdown__item')
            .filter({ hasText: subject.label }).first();
          await subjectOption.waitFor({ timeout: 15_000 });
          await subjectOption.click();
          const retryButton = page.getByRole('button', { name: '按当前产品重试', exact: true });
          await retryButton.waitFor({ timeout: 15_000 });
          const selectedResponse = await waitForResponse(page, isAnalysisResponse, () => retryButton.click());
          jointSelectedBody = await responseBody(selectedResponse);
          jointSelected = {
            attempted: true,
            status: selectedResponse.status(),
            summary: summaryFromResponse(selectedResponse.status(), jointSelectedBody, requestBody(selectedResponse)),
          };

          if (jointSelected.summary.fusedCount > 0) {
            const fusedId = jointSelectedBody?.data?.recommendations?.find(isFusedCandidate)?.id;
            const fusedCard = fusedId ? page.getByTestId(`factory-chart-card-${fusedId}`) : null;
            if (fusedCard) {
              await fusedCard.waitFor({ state: 'visible', timeout: 30_000 });
              jointScreenshot = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-joint');
              const detailsButton = fusedCard.getByRole('button', { name: '查看原始明细', exact: true });
              if (await detailsButton.count()) {
                jointDetails.attempted = true;
                const detailsPromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
                await detailsButton.click();
                const detailsResponse = await detailsPromise;
                const detailBody = detailsResponse ? await responseBody(detailsResponse) : {};
                jointDetails.success = Boolean(detailsResponse && detailsResponse.status() === 200
                  && detailBody.success === true && detailBody.data?.factoryId === 'F006'
                  && Array.isArray(detailBody.data?.rows));
                if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
              }
            }
            const jointAnswer = page.locator('.turn.turn--assistant:visible').last();
            jointExport = await downloadExportFormats(
              page,
              jointAnswer,
              inspectCtx.evidenceDir,
              'smartbi-factory-analysis-joint',
            );
          }
        }
      }
      const assessment = assess({
        initial,
        followup,
        ui: { catalogVisibleCount, visibleChartCount, renderedCanvasCount, drawingErrorCount, visibleFactCount, visibleLimitationCount, visibleSourceErrorCount, followupDifferentDimension },
        details,
        exportAttempt,
        joint: assessJoint({
          initial: jointInitial,
          selected: jointSelected,
          ui: {},
          details: jointDetails,
          exportAttempt: jointExport,
        }),
      });
      return {
        assessment,
        ...assessment,
        detailsAttempted: details.attempted,
        detailsSucceeded: details.success,
        exportAttempted: exportAttempt.attempted,
        exportSucceeded: exportAttempt.success,
        exportFormats: exportAttempt.formats,
        joint: assessment.joint,
        jointSubjectId,
        jointTables: (jointSelectedBody?.data?.recommendations || []).filter(isFusedCandidate)
          .map((item) => ({ unit: item.unit, headers: item.tableData?.headers, rows: item.tableData?.rows })),
        jointExportFormats: jointExport.formats,
        screenshots: [screenshotBeforeInteraction, ...(screenshotAfterDetails ? [screenshotAfterDetails] : []), ...(jointScreenshot ? [jointScreenshot] : [])],
      };
    },
  });
}

module.exports = {
  selectAuthorizedSubject,
  id: 'smartbi-factory-analysis-readonly',
  defaultEnabled: false,
  run,
  assess,
  assessJoint,
  summaryFromResponse,
  isAnalysisResponse,
  isExportResponse,
  summarizeExportResults,
  downloadExportFormats,
};
