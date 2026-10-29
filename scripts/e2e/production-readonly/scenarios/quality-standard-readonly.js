'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/quality/inspections?batchId=11003';
const STANDARD_PATH = '/api/mobile/F006/processing/batches/11003/quality-form';
const TEMPERATURE_ITEM_ID = 'a562ea27-8436-4944-8a66-c0afc80f103a';

async function inspectQualityStandard(page, ctx = {}) {
  const request = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === 'GET' && url.pathname === STANDARD_PATH;
  }, { timeout: 20000 });
  await page.getByRole('button', { name: '新建质检', exact: true }).click();
  const response = await request;
  const body = await response.json();
  const standard = body?.data || {};
  const dialog = page.getByRole('dialog', { name: '新建质检记录', exact: true });
  await dialog.waitFor({ state: 'visible' });
  await dialog.getByText('当前产品质检标准', { exact: true }).waitFor({ state: 'visible' });
  const visibleText = await dialog.innerText();
  const matchingItems = (standard.items || []).filter((value) => value.itemId === TEMPERATURE_ITEM_ID);
  const item = matchingItems.length === 1 ? matchingItems[0] : null;
  const checks = {
    standardHttp200: response.status() === 200 && body.success === true,
    exactBatch: String(standard.batchId) === '11003',
    requiredTemperature: Boolean(item && item.unit === '°C' && item.effectiveIsRequired === true
      && Number(item.effectiveMinValue) === 0 && Number(item.effectiveMaxValue) === 4),
    productStandardTitle: visibleText.includes('当前产品质检标准'),
    requiredLabelVisible: /温度\s*[（(]必检[）)]/u.test(visibleText),
    rangeVisible: /0\s*[~～]\s*4/u.test(visibleText) && visibleText.includes('°C'),
    noInternalRevision: Boolean(standard.standardRevision && !visibleText.includes(standard.standardRevision)),
  };
  const screenshot = ctx.screenshot ? await ctx.screenshot('quality-standard-11003-open') : null;
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  checks.cancelClosed = !await dialog.isVisible();
  const pass = Object.values(checks).every(Boolean);
  return { assessment: { result: pass ? 'PASS' : 'UNVERIFIED', rootCauseClass: pass ? 'none' : 'frontend', checks },
    checks, batchId: 11003, screenshot,
    observedTemperature: item ? { itemId: item.itemId, label: item.label, unit: item.unit,
      min: item.effectiveMinValue, max: item.effectiveMaxValue, required: item.effectiveIsRequired } : null,
    operation: 'Open existing batch standard and cancel; no submission' };
}

async function run(ctx) {
  if (ctx.expectedFactoryId !== 'F006') throw new Error('Quality standard acceptance is limited to F006');
  return runReadOnlyPageScenario(ctx, {
    id: 'quality-standard-readonly', path: PATH, landmarks: ['质检记录管理'],
    timeoutMs: 60000, inspect: (page, _body, inspectCtx) => inspectQualityStandard(page, inspectCtx),
  });
}

module.exports = { id: 'quality-standard-readonly', defaultEnabled: false, run, inspectQualityStandard };
