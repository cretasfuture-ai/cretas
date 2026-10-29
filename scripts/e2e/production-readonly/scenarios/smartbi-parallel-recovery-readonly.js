'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { inspectCommercePage } = require('./smartbi-focused-acceptance-readonly');
const PATH = '/api/mobile/F006/smart-bi/factory-analysis';
const QUESTION = '联合看客户渠道销售、发货和库存';

async function inspect(page, _body, ctx) {
  if (ctx.expectedFactoryId !== 'F006' || !ctx.smartbiCommerceJoint) throw new Error('Recovery requires an explicit F006 commerce contract');
  const input = page.getByTestId('factory-analysis-input'); const submit = page.getByTestId('factory-analysis-submit');
  await input.waitFor({ state: 'visible', timeout: 30_000 });
  await ctx.network.settle();
  if (await page.locator('[data-testid^="factory-chart-card-"]:visible').count()) throw new Error('Recovery requires a fresh, empty conversation');
  let onFailed; let timer;
  const failed = new Promise((resolve, reject) => {
    onFailed = request => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === PATH) resolve(request);
    };
    page.on('requestfailed', onFailed);
    timer = setTimeout(() => reject(new Error('Expected offline analysis request was not observed')), 15_000);
  });
  let request;
  try {
    await input.fill(QUESTION);
    await ctx.context.setOffline(true);
    await submit.click();
    request = await failed;
  } finally {
    clearTimeout(timer); page.off('requestfailed', onFailed);
    await ctx.context.setOffline(false);
  }
  await page.locator('.el-alert--error, [role="alert"]').filter({ hasText: /失败|不可用|重试|网络|Network|Failed/iu }).first().waitFor({ state: 'visible', timeout: 10_000 });
  const noFabricatedCharts = await page.locator('[data-testid^="factory-chart-card-"]:visible').count() === 0;
  const noFabricatedFacts = (await page.getByTestId('factory-analysis-facts').allTextContents()).every(text => !text.trim());
  if (!noFabricatedCharts || !noFabricatedFacts) throw new Error('Offline request displayed unverified facts or charts');
  const expectedFailure = ctx.network.expectClientOfflineFailure(request);
  ctx.consoleRecorder.expectClientOfflineResourceError(request);
  const offlineShot = ctx.screenshot ? await ctx.screenshot('smartbi-client-offline-error') : null;
  const online = await inspectCommercePage(page, ctx, ctx.smartbiCommerceJoint);
  const expected = ctx.network.snapshot().expectedTransportFailures;
  const pass = online.assessment?.result === 'PASS' && expected.length === 1 && noFabricatedCharts && noFabricatedFacts;
  return { ...online, recovery: { result: pass ? 'PASS' : 'UNVERIFIED', offlineFailure: expectedFailure.error, expectedTransportFailures: expected.length,
    explicitErrorVisible: true, noFabricatedCharts, noFabricatedFacts, retriedOnline: online.assessment?.result === 'PASS' },
    screenshots: [offlineShot, online.screenshot].filter(Boolean),
    assessment: { result: pass ? 'PASS' : 'UNVERIFIED', rootCauseClass: pass ? 'none' : 'data' } };
}

module.exports = { id: 'smartbi-parallel-recovery-readonly', defaultEnabled: false,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'smartbi-parallel-recovery-readonly', path: '/smart-bi/analysis?tab=analysis',
    landmarks: ['智能数据分析'], timeoutMs: 60_000, appMountTimeoutMs: 90_000, inspect }) };
