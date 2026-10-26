'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis-hub?tab=factory';
const RECOMMENDATIONS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
const MAX_CARDS = 4;

function endpointPath(url) { try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; } }
function requestBody(response) { try { return response.request().postDataJSON() || {}; } catch { return {}; } }
function responseIsRecommendations(response) {
  return response.request().method() === 'POST' && endpointPath(response.url()).replace(/\/$/, '') === RECOMMENDATIONS_PATH;
}
async function responseFor(page, action) {
  const pending = page.waitForResponse(responseIsRecommendations, { timeout: 60_000 });
  await action();
  return pending;
}
function safeId(value) { return typeof value === 'string' || typeof value === 'number' ? String(value) : null; }
function candidates(data) { return Array.isArray(data?.recommendations) ? data.recommendations : []; }
async function readVisibleCards(page, candidateMap) {
  const cards = page.locator('[data-testid^="factory-chart-card-"]:visible');
  const values = [];
  for (let i = 0; i < await cards.count(); i += 1) {
    const id = (await cards.nth(i).getAttribute('data-testid')).replace('factory-chart-card-', '');
    const item = candidateMap.get(id);
    values.push({ id: safeId(id), familyId: safeId(item?.analysis?.familyId), domain: item?.domain || null });
  }
  return { count: values.length, values };
}
async function assess({ initial, product, baseline, initialVisible, categoryVisible, categoryDomain, familyRows, beforeGuide, categoryShot, productShot, questionnaireVisible, productVisible, productDomainRetained }) {
  const initialCandidates = candidates(initial?.body?.data);
  const initialFamilies = new Set(initialVisible.values.map(item => item.familyId).filter(Boolean));
  const productId = safeId(product?.request?.analysis_subject);
  const checks = {
    questionnaireVisible: questionnaireVisible === true,
    initialHttp200: initial?.status === 200, initialSuccess: initial?.body?.success === true,
    factoryF006: initial?.body?.data?.factoryId === 'F006', businessSource: initial?.body?.data?.source === 'business',
    guidedRequest: initial?.request?.guided === true, guidedScope: typeof initial?.body?.data?.analysisScopeLabel === 'string',
    visibleCardsBounded: initialVisible.count > 0 && initialVisible.count <= MAX_CARDS,
    visibleCardsAreCandidates: initialVisible.values.every(item => initialCandidates.some(candidate => String(candidate.id) === item.id)),
    visibleFamiliesDistinct: initialFamilies.size === initialVisible.count,
    categoryDomainSelected: typeof categoryDomain === 'string' && categoryDomain.length > 0 && categoryDomain !== 'all',
    familyRowsPresent: familyRows > 0,
    categoryCardsMatchDomain: categoryVisible.count > 0 && categoryVisible.values.every(item => item.domain === categoryDomain),
    categoryCardsBounded: categoryVisible.count <= MAX_CARDS,
    productConditional: !product || (product.status === 200 && product.body?.success === true && productId !== null && product.body?.data?.analysisSubject === productId),
    productQuestionRetained: !product || productDomainRetained === true,
    productCardsMatchQuestion: !product || (productVisible && productVisible.count <= MAX_CARDS && productVisible.values.every(item => item.domain === categoryDomain)),
    noSourceErrors: [initial, product, baseline].filter(Boolean).every(item => !Object.keys(item.body?.data?.sourceErrors || {}).length),
    baselineReturnedToFactory: !product || (baseline?.status === 200 && baseline.request?.analysis_subject == null && baseline.body?.data?.analysisSubject == null),
    screenshots: Boolean(beforeGuide && categoryShot && (!product || productShot)),
  };
  const result = Object.values(checks).every(Boolean) ? 'PASS' : 'CONFIRMED_DEFECT';
  return { result, rootCauseClass: result === 'PASS' ? 'none' : 'frontend', checks,
    source: initial?.body?.data?.source || null, period: initial?.body?.data?.period?.label || null,
    scope: initial?.body?.data?.analysisScopeLabel || null, domain: categoryDomain,
    visibleCandidateIds: initialVisible.values.map(item => item.id), visibleFamilies: initialVisible.values.map(item => item.familyId),
    categoryCandidateIds: categoryVisible.values.map(item => item.id), subjectCount: Array.isArray(initial?.body?.data?.subjects) ? initial.body.data.subjects.length : 0,
    productScope: product?.body?.data?.analysisScopeLabel || null };
}

const scenario = {
  id: 'smartbi-guided-readonly', path: PATH, defaultEnabled: false,
  // The questionnaire arrives with the recommendations, after the initial shell.
  // Its visibility remains a required assessment below, after the real wait.
  landmarks: ['工厂分析'], timeoutMs: 60_000,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'smartbi-guided-readonly', path: PATH, landmarks: scenario.landmarks, timeoutMs: scenario.timeoutMs,
    inspect: async (page, body, inspectCtx) => {
      await page.getByTestId('factory-chart-guide').waitFor({ timeout: 60_000 });
      const questionnaireVisible = await page.getByText('1. 想了解什么？', { exact: true }).isVisible();
      const beforeGuide = await inspectCtx.screenshot('smartbi-guided-before-guide');
      const initialResponse = await responseFor(page, () => page.getByTestId('factory-chart-refresh').click());
      const initial = { status: initialResponse.status(), body: await initialResponse.json().catch(() => ({})), request: requestBody(initialResponse) };
      const candidateMap = new Map(candidates(initial.body.data).map(item => [String(item.id), item]));
      await page.getByTestId('factory-chart-guide-generate').click();
      await page.locator('[data-testid^="factory-chart-card-"]:visible').first().waitFor();
      const initialVisible = await readVisibleCards(page, candidateMap);
      const domainGroup = page.getByTestId('factory-chart-guide-domain');
      const domainOption = domainGroup.locator('input[value="库存"], input[value="生产"], input[value="销售"], input[value="采购"]').first();
      if (!await domainOption.count()) throw new Error('factory-chart-guide-domain has no usable domain radio');
      const categoryDomain = await domainOption.getAttribute('value');
      await domainOption.locator('..').click();
      if (!await domainOption.isChecked()) throw new Error('business question did not become selected');
      const generate = page.getByTestId('factory-chart-guide-generate');
      if (await generate.count()) await generate.click();
      await page.waitForTimeout(100);
      const categoryVisible = await readVisibleCards(page, candidateMap);
      const categoryShot = await inspectCtx.screenshot('smartbi-guided-category');
      const browseRadio = page.getByTestId('factory-chart-mode').locator('input[value="browse"]');
      await browseRadio.locator('..').click();
      if (!await browseRadio.isChecked()) throw new Error('classified browse mode did not become selected');
      const familyRows = await page.getByTestId('factory-chart-family').count();
      const subject = page.getByTestId('factory-chart-guide-subject');
      let product = null; let baseline = null; let productShot = null; let productVisible = null; let productDomainRetained = false;
      const firstSubject = initial.body.data?.subjects?.[0];
      if (await subject.count() && firstSubject) {
        await subject.locator('input, button').first().click();
        {
          const selectedOption = page.getByRole('option', { name: firstSubject.label, exact: true });
          const productResponse = await responseFor(page, () => selectedOption.click());
          product = { status: productResponse.status(), body: await productResponse.json().catch(() => ({})), request: requestBody(productResponse) };
          await page.getByText(product.body.data.analysisScopeLabel, { exact: false }).first().waitFor();
          productDomainRetained = await domainGroup.locator(`input[value="${categoryDomain}"]`).isChecked();
          productVisible = await readVisibleCards(page, new Map(candidates(product.body.data).map(item => [String(item.id), item])));
          productShot = await inspectCtx.screenshot('smartbi-guided-product');
          await subject.locator('input, button').first().click();
          const clearOption = page.getByRole('option', { name: '全部产品 / 全厂范围', exact: true });
          if (await clearOption.count()) {
            const baselineResponse = await responseFor(page, () => clearOption.click());
            baseline = { status: baselineResponse.status(), body: await baselineResponse.json().catch(() => ({})), request: requestBody(baselineResponse) };
          }
        }
      }
      const assessment = await assess({ initial, product, baseline, initialVisible, categoryVisible, categoryDomain, familyRows, beforeGuide, categoryShot, productShot, questionnaireVisible, productVisible, productDomainRetained });
      return { assessment, ...assessment, screenshots: [beforeGuide, categoryShot, ...(productShot ? [productShot] : [])] };
    },
  }),
};

module.exports = scenario;
module.exports.assess = assess;
module.exports.responseIsRecommendations = responseIsRecommendations;
