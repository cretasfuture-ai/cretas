'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const id = 'proposal-comparison-readonly';
const route = '/scheduling/proposal-comparison';
const basis = 'QA-FOOD-PROPOSAL-UI-20261106';

function requireCondition(condition, message) {
  if (!condition) throw new Error(message);
}

function assertCalculation(data, kind) {
  requireCondition(data && data.comparisonBasisId === basis, 'Comparison basis changed');
  requireCondition(data.factsVerified === false && data.businessExecutionPerformed === false
    && data.resourcesReserved === false, 'Calculation made an unsupported business claim');
  requireCondition(Array.isArray(data.candidates) && data.candidates.length === 3
    && ['A', 'B', 'C'].every(candidateId => data.candidates.filter(item => item.id === candidateId).length === 1),
  'Comparison candidate identity mismatch');
  if (kind === 'positive') {
    requireCondition(data.status === 'SUPPLIED_FACTS_COMPARABLE'
      && data.nonDominatedCandidateIds.length === 2
      && ['A', 'B'].every(candidateId => data.nonDominatedCandidateIds.includes(candidateId)),
    'Expected two alternatives with different cost/time/resource tradeoffs');
    const dominated = data.candidates.find(item => item.id === 'C');
    requireCondition(dominated.dominatedByCandidateIds.length === 2
      && ['A', 'B'].every(candidateId => dominated.dominatedByCandidateIds.includes(candidateId)),
    'Expected C to be dominated by both A and B');
  } else {
    requireCondition(data.status === 'NOT_COMPARABLE' && data.nonDominatedCandidateIds.length === 0,
      'Missing/mixed facts must not form a frontier');
    const reason = kind === 'missing-cost' ? 'MISSING_COST' : 'CURRENCY_MISMATCH';
    requireCondition(data.candidates.find(item => item.id === 'A').reasons.includes(reason),
      'Missing/mixed fact reason is absent');
  }
  return true;
}

async function scopedRead(page, suffix) {
  return page.evaluate(async path => {
    const token = localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('Fresh authenticated token missing');
    const response = await fetch(path, {headers: {Authorization: `Bearer ${token}`}});
    const body = await response.json();
    if (!response.ok || body.success !== true) throw new Error('Scoped acceptance read failed');
    return body.data;
  }, suffix);
}

async function inspect(page, _body, ctx) {
  requireCondition(ctx.expectedFactoryId === 'F006' && ctx.loginEvidence.factoryId === 'F006',
    'Synthetic UI acceptance requires the coordinated F006 factory');
  const me = await scopedRead(page, '/api/mobile/auth/me');
  requireCondition(me?.factoryUser?.factoryId === 'F006'
    && me.factoryUser.id === 1309
    && ['factory_super_admin', 'permission_admin'].includes(me.factoryUser.roleCode),
  'Coordinated actor identity changed');
  requireCondition(await scopedRead(page, '/api/mobile/F006/demo-sandbox/active-session') === null,
    'Shared sandbox is active; do not run comparison');
  await page.getByRole('heading', {name: '多方案比较', exact: true}).waitFor({state: 'visible'});
  const form = page.locator('.comparison-form');
  const common = form.locator('.form-card').first();
  const fields = { '比较口径': basis, '币种': 'CNY', '成本单位': '每批',
    '窗口开始': '2026-11-07T08:00', '窗口结束': '2026-11-10T18:00',
    '可选交付截止': '2026-11-10T18:00', '可用资源容量': '20' };
  for (const [label, value] of Object.entries(fields)) await common.getByLabel(label, {exact: true}).fill(value);
  await common.getByLabel('资源预算单位', {exact: true}).selectOption('PERSON_HOUR');
  await page.getByRole('button', {name: '增加候选', exact: true}).click();
  const candidates = [
    {id: 'A', cost: '100', demand: '8', completion: '2026-11-08T12:00'},
    {id: 'B', cost: '80', demand: '10', completion: '2026-11-09T12:00'},
    {id: 'C', cost: '120', demand: '12', completion: '2026-11-10T12:00'},
  ];
  for (const [index, candidate] of candidates.entries()) {
    const row = form.locator('.candidate-card').nth(index);
    const values = {'编号': candidate.id, '比较口径': basis, '币种': 'CNY', '成本单位': '每批',
      '成本': candidate.cost, '资源需求': candidate.demand, '完成时间': candidate.completion};
    for (const [label, value] of Object.entries(values)) await row.getByLabel(label, {exact: true}).fill(value);
    await row.getByLabel('资源单位', {exact: true}).selectOption('PERSON_HOUR');
  }
  const results = [];
  const screenshots = [];
  for (const kind of ['positive', 'missing-cost', 'mixed-currency']) {
    const first = form.locator('.candidate-card').first();
    if (kind === 'missing-cost') await first.getByLabel('成本', {exact: true}).fill('');
    if (kind === 'mixed-currency') {
      await first.getByLabel('成本', {exact: true}).fill('100');
      await first.getByLabel('币种', {exact: true}).fill('USD');
    }
    const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/mobile/F006/proposal-comparisons/evaluate', {timeout: 30000});
    await page.getByRole('button', {name: '查看比较结果', exact: true}).click();
    const response = await responsePromise;
    const responseBody = await response.json();
    requireCondition(response.status() === 200 && responseBody.success === true, 'Calculation did not complete successfully');
    assertCalculation(responseBody.data, kind);
    const card = page.locator('.result-card');
    await card.waitFor({state: 'visible'});
    const text = await card.innerText();
    requireCondition(text.includes('输入事实未核实') && text.includes('本次未执行方案')
      && text.includes('本次未预留资源'), 'Visible supplied-facts declarations missing');
    const expectedText = kind === 'positive' ? '被以下候选支配' : kind === 'missing-cost' ? '缺少成本' : '币种不同';
    requireCondition(text.includes(expectedText), 'Visible result does not explain the calculation');
    await card.scrollIntoViewIfNeeded();
    screenshots.push(await ctx.screenshot(id, kind));
    results.push({kind, status: responseBody.data.status, nonDominatedCandidateIds: responseBody.data.nonDominatedCandidateIds,
      factsVerified: false, businessExecutionPerformed: false, resourcesReserved: false});
  }
  return {factoryId: 'F006', actorId: 1309, suppliedSyntheticFacts: true, calculations: results,
    screenshots, assessment: {result: 'PASS', rootCauseClass: 'none'}};
}

async function inspectDenied(page, _body, ctx) {
  requireCondition(ctx.loginEvidence.factoryId === 'F006'
    && !['factory_super_admin', 'permission_admin', 'platform_admin'].includes(ctx.loginEvidence.role),
  'Denied scenario requires a real non-comparison factory role');
  await page.waitForURL(url => url.pathname === '/403', {timeout: 15000});
  requireCondition(await page.locator('.comparison-form').count() === 0, 'Denied role sees comparison input');
  requireCondition(ctx.mutationGuard.snapshot().readonlyPostRequests
    .filter(request => request.classificationId === 'proposal-comparison-evaluate').length === 0,
  'Denied route issued a calculation');
  return {role: ctx.loginEvidence.role, finalPath: '/403', calculationRequests: 0,
    assessment: {result: 'PASS', rootCauseClass: 'none'}};
}

module.exports = {id, defaultEnabled: false, assertCalculation, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, {id, path: route, screenshot: false, inspect}),
  denied: {id: 'proposal-comparison-denied-readonly', defaultEnabled: false,
    run: ctx => runReadOnlyPageScenario(ctx, {id: 'proposal-comparison-denied-readonly',
      path: route, screenshot: true, inspect: inspectDenied})},
};
