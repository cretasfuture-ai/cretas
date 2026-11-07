'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { assertCoordinatedActor } = require('./proposal-comparison-readonly');

const id = 'plan-concurrent-readiness-readonly';
const readinessStatus = new Set(['VERIFIED', 'UNVERIFIED']);

function fail(message) {
  throw new Error(message);
}

function finiteNumber(value, label, max = Number.POSITIVE_INFINITY) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > max) {
    fail(`Invalid ${label}`);
  }
  return value;
}

function nullableNumber(value, label, max = Number.POSITIVE_INFINITY) {
  if (value === null) return null;
  return finiteNumber(value, label, max);
}

function exactString(value, label, maxLength = 191) {
  if (typeof value !== 'string' || value.trim() === '' || value.length > maxLength) fail(`Invalid ${label}`);
  return value;
}

function validatePlanReadinessOptions(value) {
  if (!value || value.syntheticQa !== true || !Array.isArray(value.plans)
    || value.plans.length === 0 || value.plans.length > 3) {
    return { valid: false, reason: 'syntheticQa and one to three fixed plans are required' };
  }
  const plans = [];
  const ids = new Set();
  const numbers = new Set();
  for (const raw of value.plans) {
    if (!raw || typeof raw !== 'object') return { valid: false, reason: 'plan option is not an object' };
    if (typeof raw.planId !== 'string' || typeof raw.planNumber !== 'string') {
      return { valid: false, reason: 'fixed plan identity is required' };
    }
    if (!raw.planId.trim() || !raw.planNumber.trim() || raw.planId.length > 191 || raw.planNumber.length > 191) {
      return { valid: false, reason: 'fixed plan identity is invalid' };
    }
    const planId = exactString(raw.planId, 'planId');
    const planNumber = exactString(raw.planNumber, 'planNumber');
    if (ids.has(planId) || numbers.has(planNumber)) return { valid: false, reason: 'plan options are not unique' };
    ids.add(planId);
    numbers.add(planNumber);
    if (raw.expectedMaterialStatus !== undefined && !readinessStatus.has(raw.expectedMaterialStatus)) {
      return { valid: false, reason: 'expectedMaterialStatus is invalid' };
    }
    const lowerPresent = raw.expectedLowerPercent !== undefined;
    const upperPresent = raw.expectedUpperPercent !== undefined;
    if (lowerPresent !== upperPresent) return { valid: false, reason: 'expected material bounds must be paired' };
    if (lowerPresent) {
      try {
        nullableNumber(raw.expectedLowerPercent, 'expectedLowerPercent', 100);
        nullableNumber(raw.expectedUpperPercent, 'expectedUpperPercent', 100);
      } catch {
        return { valid: false, reason: 'expected material bounds are invalid' };
      }
      if (raw.expectedLowerPercent !== null && raw.expectedUpperPercent !== null
        && raw.expectedLowerPercent > raw.expectedUpperPercent) {
        return { valid: false, reason: 'expected material bounds are reversed' };
      }
    }
    if (raw.expectedProgressPercent !== undefined) {
      try {
        nullableNumber(raw.expectedProgressPercent, 'expectedProgressPercent');
      } catch {
        return { valid: false, reason: 'expected progress percent is invalid' };
      }
    }
    plans.push({
      planId,
      planNumber,
      ...(raw.expectedMaterialStatus === undefined ? {} : { expectedMaterialStatus: raw.expectedMaterialStatus }),
      ...(lowerPresent ? { expectedLowerPercent: raw.expectedLowerPercent, expectedUpperPercent: raw.expectedUpperPercent } : {}),
      ...(raw.expectedProgressPercent === undefined ? {} : { expectedProgressPercent: raw.expectedProgressPercent }),
    });
  }
  return { valid: true, plans };
}

function assertReadinessPayload(payload, planId) {
  if (!payload || payload.success !== true || !payload.data || typeof payload.data !== 'object') {
    fail('concurrent-readiness response is not a successful envelope');
  }
  const data = payload.data;
  if (data.planId !== planId || data.advisoryOnly !== true) fail('readiness identity or advisory flag mismatch');
  if (typeof data.planNumber !== 'string' || typeof data.planStatus !== 'string') fail('readiness plan metadata is invalid');
  if (!data.material || !readinessStatus.has(data.material.status)
    || typeof data.material.reason !== 'string' || !Array.isArray(data.material.items)) {
    fail('readiness material section is invalid');
  }
  const materialLower = nullableNumber(data.material.lowerPercent, 'material.lowerPercent', 100);
  const materialUpper = nullableNumber(data.material.upperPercent, 'material.upperPercent', 100);
  if (data.material.status === 'VERIFIED') {
    if (data.material.reason !== 'OK' || materialLower === null || materialUpper === null || materialLower > materialUpper) {
      fail('verified material bounds are invalid');
    }
  } else if (materialLower !== null || materialUpper !== null) {
    fail('unverified material must keep bounds null');
  }
  const materialIds = new Set();
  for (const item of data.material.items) {
    if (!item || typeof item !== 'object' || materialIds.has(item.materialTypeId)) fail('readiness material identity is invalid');
    materialIds.add(item.materialTypeId);
    exactString(item.materialTypeId, 'materialTypeId');
    exactString(item.materialName, 'materialName', 512);
    exactString(item.unit, 'material.unit');
    for (const field of ['required', 'consumed', 'ownHeldReserved', 'otherUnoccupied', 'stockFree']) {
      nullableNumber(item[field], `material.${field}`);
    }
    nullableNumber(item.lowerPercent, 'material.item.lowerPercent', 100);
    nullableNumber(item.upperPercent, 'material.item.upperPercent', 100);
  }
  if (!data.progress || !readinessStatus.has(data.progress.status)
    || data.progress.basis !== 'FINISHED_GOODS_RECEIVED') {
    fail('readiness progress section is invalid');
  }
  const progressPercent = nullableNumber(data.progress.completionPercent, 'progress.completionPercent');
  if (data.progress.status === 'UNVERIFIED' && progressPercent !== null) fail('unverified progress must keep completion null');
  if (data.progress.status === 'VERIFIED' && progressPercent === null) fail('verified progress must include completion');
  return data;
}

function matchesExpectedReadiness(data, option) {
  if (data.planId !== option.planId || data.planNumber !== option.planNumber) return false;
  if (option.expectedMaterialStatus !== undefined && data.material.status !== option.expectedMaterialStatus) return false;
  if (option.expectedLowerPercent !== undefined
    && (data.material.lowerPercent !== option.expectedLowerPercent || data.material.upperPercent !== option.expectedUpperPercent)) return false;
  if (option.expectedProgressPercent !== undefined
    && data.progress.completionPercent !== option.expectedProgressPercent) return false;
  return true;
}

function readinessPath(factoryId, planId) {
  return `/api/mobile/${encodeURIComponent(factoryId)}/production-plans/${encodeURIComponent(planId)}/concurrent-readiness`;
}

function productionPlansPath(factoryId) {
  return `/api/mobile/${encodeURIComponent(factoryId)}/production-plans`;
}

async function inspectPlan(page, ctx, option, index) {
  // The real list defaults to UNFINISHED; retained cancelled QA plans require the explicit All filter.
  await page.locator('.search-bar .el-select').first().click();
  await page.getByRole('option', { name: '全部', exact: true }).click();
  const search = page.getByPlaceholder('搜索计划编号/产品名称');
  await search.waitFor({ state: 'visible', timeout: 10_000 });
  await search.fill(option.planNumber);
  const listResponsePromise = page.waitForResponse((response) => {
    if (response.request().method() !== 'GET') return false;
    try {
      const url = new URL(response.url());
      return url.pathname === productionPlansPath(ctx.expectedFactoryId)
        && url.searchParams.get('keyword') === option.planNumber;
    } catch {
      return false;
    }
  }, { timeout: 20_000 });
  await search.press('Enter');
  const listResponse = await listResponsePromise;
  if (!listResponse.ok()) return { ok: false, reason: `plan list returned HTTP ${listResponse.status()}` };

  const planButton = page.getByRole('button', { name: option.planNumber, exact: true });
  if (await planButton.count() !== 1) return { ok: false, reason: 'fixed QA plan was not uniquely visible in the filtered list' };
  await planButton.click();
  const dialog = page.getByRole('dialog', { name: '计划详情' });
  await dialog.waitFor({ state: 'visible', timeout: 10_000 });
  const readinessButton = dialog.getByTestId('plan-concurrent-readiness-refresh');
  await readinessButton.waitFor({ state: 'visible', timeout: 10_000 });
  const readinessResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && (() => {
      try { return new URL(response.url()).pathname === readinessPath(ctx.expectedFactoryId, option.planId); } catch { return false; }
    })(), { timeout: 20_000 });
  await readinessButton.click();
  const readinessResponse = await readinessResponsePromise;
  if (readinessResponse.status() !== 200) return { ok: false, reason: 'readiness GET did not return HTTP 200', httpStatus: readinessResponse.status() };
  const payload = await readinessResponse.json().catch(() => null);
  let data;
  try {
    data = assertReadinessPayload(payload, option.planId);
  } catch (error) {
    return { ok: false, reason: String(error?.message || error), httpStatus: readinessResponse.status() };
  }
  const card = dialog.getByTestId('plan-concurrent-readiness');
  await card.waitFor({ state: 'visible', timeout: 10_000 });
  await card.getByText('成品入库进度', { exact: true }).waitFor({ state: 'visible', timeout: 10_000 });
  const cardText = await card.innerText();
  const visibleContract = cardText.includes('只读核算，不改变排程、库存或生产状态')
    && cardText.includes('不等同于物料匹配或报工实时完工');
  const expected = matchesExpectedReadiness(data, option);
  const shot = await ctx.screenshot(`plan-concurrent-readiness-${index + 1}`);
  const close = dialog.locator('.el-dialog__headerbtn').first();
  if (await close.isVisible().catch(() => false)) await close.click();
  return {
    ok: expected && visibleContract,
    reason: expected ? (visibleContract ? null : 'readiness UI lost read-only/progress distinction') : 'readiness response differed from fixed QA expectation',
    httpStatus: readinessResponse.status(),
    responsePlanId: data.planId,
    planNumber: data.planNumber,
    advisoryOnly: data.advisoryOnly,
    materialStatus: data.material.status,
    lowerPercent: data.material.lowerPercent,
    upperPercent: data.material.upperPercent,
    progressStatus: data.progress.status,
    progressPercent: data.progress.completionPercent,
    visibleContract,
    screenshot: shot,
  };
}

async function inspect(page, _body, ctx) {
  const options = validatePlanReadinessOptions(ctx.planReadiness);
  if (!options.valid) {
    return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'config' }, reason: options.reason };
  }
  if (ctx.expectedFactoryId !== 'F006' || ctx.loginEvidence?.factoryId !== 'F006') {
    return { assessment: { result: 'UNVERIFIED', rootCauseClass: 'config' }, reason: 'F006 context required' };
  }
  const identity = await page.evaluate(async () => {
    const read = async path => {
      const response = await fetch(path, { credentials: 'same-origin' });
      const payload = await response.json();
      if (response.status !== 200 || payload.success !== true) throw new Error('Owned readiness context could not be verified');
      return payload.data;
    };
    return { me: await read('/api/mobile/auth/me'), sandbox: await read('/api/mobile/F006/demo-sandbox/active-session') };
  });
  assertCoordinatedActor(identity.me, ctx.expectedUsername);
  if (identity.sandbox !== null) fail('Active sandbox requires coordination');
  const cases = [];
  for (let index = 0; index < options.plans.length; index += 1) {
    cases.push(await inspectPlan(page, ctx, options.plans[index], index));
  }
  const complete = cases.length === options.plans.length && cases.every((item) => item.ok);
  return {
    planCount: options.plans.length,
    cases,
    screenshots: cases.map((item) => item.screenshot).filter(Boolean),
    assessment: complete
      ? { result: 'PASS', rootCauseClass: 'none' }
      : { result: 'UNVERIFIED', rootCauseClass: 'data' },
  };
}

module.exports = {
  id,
  defaultEnabled: false,
  validatePlanReadinessOptions,
  assertReadinessPayload,
  matchesExpectedReadiness,
  readinessPath,
  inspect,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id,
    path: ROUTES.productionPlans,
    landmarks: ['生产计划管理'],
    timeoutMs: 60_000,
    inspect: (page, body, scenarioContext) => inspect(page, body, scenarioContext),
  }),
};
