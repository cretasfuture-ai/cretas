'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis?tab=query&mode=factory';
const ANALYSIS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/;
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const EXPORT_PATH = '/smartbi-api/api/smartbi/financial-dashboard/export-excel';
const QUESTION = '汇总当前库存、生产和销售表现';

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

function isExportResponse(response) {
  return response.request().method() === 'POST'
    && endpointPath(response.url()).replace(/\/$/, '') === EXPORT_PATH;
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

function summaryFromResponse(status, body, request) {
  const data = body?.data || {};
  const recommendations = Array.isArray(data.recommendations) ? data.recommendations : [];
  const facts = Array.isArray(data.facts) ? data.facts : [];
  const catalog = Array.isArray(data.catalog) ? data.catalog : [];
  const families = new Set(recommendations.map(familyId).filter(Boolean).map(String));
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
    limitationCount: Array.isArray(data.limitations) ? data.limitations.length : 0,
    familyCount: families.size,
    catalogCount: catalog.length,
    contextPresent: typeof data.contextId === 'string' && data.contextId.length > 0,
    followUpCount: Array.isArray(data.followUpQuestions) ? data.followUpQuestions.length : 0,
    requestContract: Object.keys(request).sort(),
    requestSource: request.source || null,
    requestPeriod: request.periodType
      ? JSON.stringify({ periodType: request.periodType, year: request.year, startMonth: request.startMonth, endMonth: request.endMonth })
      : null,
    requestContextPresent: typeof request.contextId === 'string' && request.contextId.length > 0,
  };
}

function assess({ initial, followup, ui, details, exportAttempt }) {
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
    followupContract: followupSummary.requestContract?.join(',') === 'contextId,question'
      && followupSummary.requestContextPresent === true,
    followupDifferentDimension: ui?.followupDifferentDimension === true,
    detailsAttempted: details?.attempted === true,
    detailsSucceeded: details?.success === true,
    exportAttempted: exportAttempt?.attempted === true,
    exportSucceeded: exportAttempt?.success === true,
  };
  const validChecks = Object.values(checks).every(Boolean);
  const result = unavailable ? 'UNVERIFIED' : validChecks ? 'PASS' : 'UNVERIFIED';
  return {
    result,
    rootCauseClass: result === 'PASS' ? 'none' : 'tool',
    checks,
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
      : validChecks ? 'Grounded chart/fact, follow-up, details, and export checks passed.' : 'One or more grounded UI checks were incomplete.',
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

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-analysis-readonly',
    path: PATH,
    landmarks: ['工厂 AI 问答分析', '当前授权图表目录'],
    timeoutMs: 60_000,
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

      let exportAttempt = { attempted: false, success: false };
      const exportButton = page.locator('.answer-tools:visible .el-button').first();
      if (await exportButton.count()) {
        exportAttempt.attempted = true;
        const downloadPromise = page.waitForEvent('download', { timeout: 30_000 }).catch(() => null);
        const exportResponsePromise = page.waitForResponse(isExportResponse, { timeout: 30_000 }).catch(() => null);
        await exportButton.click();
        const [download, exportResponse] = await Promise.all([downloadPromise, exportResponsePromise]);
        exportAttempt.success = Boolean(download && exportResponse && exportResponse.status() === 200);
      }

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
      const assessment = assess({
        initial,
        followup,
        ui: { catalogVisibleCount, visibleChartCount, renderedCanvasCount, drawingErrorCount, visibleFactCount, visibleLimitationCount, visibleSourceErrorCount, followupDifferentDimension },
        details,
        exportAttempt,
      });
      return {
        assessment,
        ...assessment,
        detailsAttempted: details.attempted,
        detailsSucceeded: details.success,
        exportAttempted: exportAttempt.attempted,
        exportSucceeded: exportAttempt.success,
        screenshots: [screenshotBeforeInteraction, ...(screenshotAfterDetails ? [screenshotAfterDetails] : [])],
      };
    },
  });
}

module.exports = {
  id: 'smartbi-factory-analysis-readonly',
  defaultEnabled: false,
  run,
  assess,
  summaryFromResponse,
  isAnalysisResponse,
};
