#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const appUrl = process.env.E2E_EXPO_URL || 'http://localhost:3010';
const password = process.env.E2E_PASSWORD;
const runKey = process.env.E2E_RUN_KEY;
if (!password || !runKey) throw new Error('E2E_PASSWORD and E2E_RUN_KEY are required');
if (!/^E2E-RBAC-20260823-[A-Z0-9-]+$/.test(runKey)) throw new Error(`Unsafe run key: ${runKey}`);

const evidenceDir = path.resolve(process.env.E2E_EVIDENCE_DIR || path.join('.playwright-mcp', 'f006-role-chain', runKey));
const statePath = path.join(evidenceDir, 'state.json');
const reportPath = path.join(evidenceDir, 'expo-app-quality.json');
if (!fs.existsSync(statePath)) throw new Error(`State file missing: ${statePath}`);
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
if (state.runKey !== runKey || state.factoryId !== 'F006') throw new Error('State tenant/run key mismatch');
const productionBatch = state.entities.productionBatch;
const productionPlan = state.entities.productionPlan;
if (!productionBatch?.id || productionBatch.factoryId !== 'F006' || productionPlan?.status !== 'COMPLETED') {
  throw new Error('Tracked completed F006 production plan/batch is required');
}
if (state.entities.qualityInspection) throw new Error(`Quality inspection already tracked: ${state.entities.qualityInspection.id}`);

const report = {
  runKey,
  phase: 'expo-app-quality',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [{
    step: 'Expo App 质检员对 Web 结单批次提交合格检验',
    method: 'POST',
    endpoint: '/api/mobile/F006/processing/quality/inspections',
    target: { productionBatchId: String(productionBatch.id), batchNumber: productionBatch.batchNumber },
    payloadBoundary: { defaultHaccpScore: 93, sampleSize: 10, result: 'PASS', notes: runKey },
  }],
  actualMutations: [],
  consoleErrors: [],
  httpErrors: [],
  result: 'RUNNING',
};

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 430, height: 920 } });
const page = await context.newPage();
page.on('console', (message) => {
  if (message.type() === 'error') report.consoleErrors.push({ text: message.text(), url: message.location().url || null });
});
page.on('response', (response) => {
  if (response.status() >= 400) report.httpErrors.push({ method: response.request().method(), url: response.url(), status: response.status() });
});

try {
  await page.goto(appUrl, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  const landingButton = page.locator('[data-testid="landing-login-btn"]');
  if (await landingButton.isVisible().catch(() => false)) await landingButton.click();
  const usernameInput = page.locator('[data-testid="login-username-input"]');
  await usernameInput.waitFor({ state: 'visible', timeout: 60_000 });
  await usernameInput.fill('f006_quality_insp');
  await page.locator('[data-testid="login-password-input"]').fill(password);
  const loginResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/auth/unified-login', { timeout: 30_000 });
  await page.locator('[data-testid="login-submit-btn"]').click();
  const loginResponse = await loginResponsePromise;
  const loginBody = await loginResponse.json().catch(() => null);
  const loginData = loginBody?.data || {};
  report.login = {
    statusCode: loginResponse.status(),
    success: loginBody?.success ?? null,
    username: loginData.username || loginData.user?.username || null,
    factoryId: loginData.factoryUser?.factoryId || loginData.factoryId || loginData.user?.factoryId || null,
    role: loginData.factoryUser?.role || loginData.role || loginData.user?.role || null,
  };
  if (loginResponse.status() >= 400 || !report.login.success || report.login.factoryId !== 'F006'
      || report.login.role !== 'quality_inspector') {
    throw new Error(`Expo App F006 login proof failed: ${JSON.stringify(report.login)}`);
  }
  await page.locator('[data-testid="qi-home-screen"]').waitFor({ state: 'visible', timeout: 45_000 });
  await page.screenshot({ path: path.join(evidenceDir, '11-expo-quality-home.png'), fullPage: true });

  const batchResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/mobile/F006/processing/batches'
    && new URL(response.url()).searchParams.get('status') === 'COMPLETED', { timeout: 30_000 });
  await page.locator('[data-testid="qi-tab-inspect"]').click();
  const batchResponse = await batchResponsePromise;
  const batchBody = await batchResponse.json().catch(() => null);
  const batchItems = batchBody?.data?.content || [];
  const exactBatch = batchItems.find((item) => String(item.id) === String(productionBatch.id)
    && item.batchNumber === productionBatch.batchNumber);
  report.pendingBatchReadback = exactBatch ? {
    id: String(exactBatch.id),
    factoryId: exactBatch.factoryId || 'F006',
    batchNumber: exactBatch.batchNumber,
    status: exactBatch.status,
    productName: exactBatch.productName,
    quantity: exactBatch.quantity,
    unit: exactBatch.unit,
  } : null;
  if (!exactBatch || report.pendingBatchReadback.factoryId !== 'F006' || exactBatch.status !== 'COMPLETED') {
    throw new Error(`Exact completed batch is missing from Expo App pending list: ${productionBatch.batchNumber}`);
  }
  const batchText = page.getByText(productionBatch.batchNumber, { exact: true }).first();
  await batchText.waitFor({ state: 'visible', timeout: 30_000 });
  await batchText.click();
  await page.getByText('质检表单', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByText(productionBatch.batchNumber, { exact: true }).last().waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByPlaceholder('输入备注信息...').fill(runKey);

  const inspectionResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/F006/processing/quality/inspections'
    && new URL(response.url()).searchParams.get('batchId') === String(productionBatch.id), { timeout: 35_000 });
  await page.getByText('提交检验结果', { exact: true }).click();
  const inspectionResponse = await inspectionResponsePromise;
  const inspectionBody = await inspectionResponse.json().catch(() => null);
  const envelope = inspectionBody?.data || {};
  const inspectionData = envelope.inspection || envelope;
  const inspection = {
    success: inspectionBody?.success ?? null,
    message: inspectionBody?.message || null,
    id: String(inspectionData.id || ''),
    factoryId: inspectionData.factoryId || 'F006',
    productionBatchId: String(inspectionData.batchId || inspectionData.productionBatchId || productionBatch.id),
    result: inspectionData.result || null,
    sampleSize: inspectionData.sampleSize ?? null,
    passCount: inspectionData.passCount ?? null,
    failCount: inspectionData.failCount ?? null,
    inspectorId: inspectionData.inspectorId ?? null,
    notes: inspectionData.notes || null,
    passRate: envelope.passRate ?? inspectionData.passRate ?? null,
  };
  report.actualMutations.push({ method: 'POST', url: inspectionResponse.url(), statusCode: inspectionResponse.status(), response: inspection });
  if (inspectionResponse.status() >= 400 || !inspection.success || !inspection.id || inspection.factoryId !== 'F006'
      || inspection.productionBatchId !== String(productionBatch.id) || !['PASS', 'PASSED'].includes(inspection.result)) {
    throw new Error(`Expo App quality inspection mismatch: ${inspectionResponse.status()} ${inspection.message} ${inspection.result}`);
  }
  state.entities.qualityInspection = inspection;
  state.mutations.push({ phase: 'expo-app-quality', action: 'create-passing-quality-inspection', entityId: inspection.id, at: new Date().toISOString() });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  await page.getByText('检验结果', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.screenshot({ path: path.join(evidenceDir, '12-expo-quality-inspection-passed.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-quality-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, statePath, qualityInspection: state.entities.qualityInspection || null }, null, 2));
