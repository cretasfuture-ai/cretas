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
const reportPath = path.join(evidenceDir, 'expo-app-production-report.json');
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const plan = state.entities?.productionPlan;
const batch = state.entities?.productionBatch;
if (state.runKey !== runKey || state.factoryId !== 'F006' || plan?.factoryId !== 'F006'
    || batch?.factoryId !== 'F006' || plan?.status !== 'IN_PROGRESS' || batch?.status !== 'IN_PROGRESS') {
  throw new Error('Tracked in-progress F006 production plan/batch is required');
}
if (state.entities.appProductionInputReport) {
  throw new Error(`App production report already tracked: ${state.entities.appProductionInputReport.reportId}`);
}

const report = {
  runKey,
  phase: 'expo-app-production-report',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [{
    step: 'Expo App 车间主任提交首道实际投入',
    method: 'POST',
    endpoint: `/api/mobile/F006/production/batches/${batch.id}/reports`,
    target: { productionPlanId: plan.id, productionBatchId: String(batch.id), batchNumber: batch.batchNumber },
    payloadBoundary: { reportKind: 'INPUT', inputQuantity: 0.04, inputUnit: 'kg', oneMaterialBatch: true },
  }],
  actualMutations: [],
  consoleErrors: [],
  httpErrors: [],
  dialogs: [],
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
page.on('dialog', async (dialog) => {
  report.dialogs.push({ type: dialog.type(), message: dialog.message() });
  await dialog.accept();
});

try {
  await page.goto(appUrl, { waitUntil: 'domcontentloaded', timeout: 90_000 });
  const landing = page.locator('[data-testid="landing-login-btn"]');
  if (await landing.isVisible().catch(() => false)) await landing.click();
  await page.locator('[data-testid="login-username-input"]').waitFor({ state: 'visible', timeout: 90_000 });
  await page.locator('[data-testid="login-username-input"]').fill('f006_workshop');
  await page.locator('[data-testid="login-password-input"]').fill(password);
  const loginPromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/mobile/auth/unified-login', { timeout: 30_000 });
  await page.locator('[data-testid="login-submit-btn"]').click();
  const loginResponse = await loginPromise;
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
      || report.login.role !== 'workshop_supervisor') {
    throw new Error(`Expo App F006 workshop login proof failed: ${JSON.stringify(report.login)}`);
  }

  await page.locator('[data-testid="tab-batches"]').waitFor({ state: 'visible', timeout: 60_000 });
  await page.locator('[data-testid="tab-batches"]').click();
  const batchSelect = page.locator('[data-testid="yield-batch-select-btn"]');
  if (!await batchSelect.isVisible().catch(() => false)) {
    const taskEntry = page.locator('[data-testid="ws-process-task-btn"]');
    if (await taskEntry.isVisible().catch(() => false)) await taskEntry.click();
  }
  await batchSelect.waitFor({ state: 'attached', timeout: 30_000 });
  // React Navigation keeps the previous screen mounted on web; DOM click invokes the
  // active stack action even when the visual header is covered by the current card layer.
  await batchSelect.evaluate((element) => element.click());
  const exactBatch = page.getByText(batch.batchNumber, { exact: true });
  await exactBatch.waitFor({ state: 'visible', timeout: 45_000 });
  await page.screenshot({ path: path.join(evidenceDir, '07-expo-production-batch-select.png'), fullPage: true });
  await exactBatch.evaluate((element) => element.click());

  await page.locator('[data-testid="yield-phase-badge"]').waitFor({ state: 'visible', timeout: 60_000 });
  await page.getByText(batch.batchNumber, { exact: false }).last().waitFor({ state: 'visible', timeout: 30_000 });
  await page.screenshot({ path: path.join(evidenceDir, '08-expo-production-input-form.png'), fullPage: true });

  const pickerHeader = page.getByText(/领料批次 \(原料\)/).first();
  await pickerHeader.click();
  const firstBatchRow = page.getByRole('checkbox', { name: /选择批次/ }).first();
  await firstBatchRow.waitFor({ state: 'visible', timeout: 45_000 });
  const selectedBatchLabel = await firstBatchRow.getAttribute('aria-label');
  await firstBatchRow.click();

  await page.locator('[data-testid="yield-input-qty-tap-to-edit"]').click();
  await page.locator('[data-testid="yield-input-qty-modal-input"]').fill('0.04');
  await page.locator('[data-testid="yield-input-qty-modal-confirm"]').click();
  const confirmMaterial = page.getByLabel('确定选择领料批次');
  await confirmMaterial.waitFor({ state: 'visible', timeout: 15_000 });
  if (await confirmMaterial.isDisabled()) throw new Error('Material batch confirmation remained disabled after 0.04 kg input');
  await confirmMaterial.click();

  const submitPromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/production/batches/${batch.id}/reports`, { timeout: 60_000 });
  await page.locator('[data-testid="yield-submit-input-btn"]').click();
  const submitResponse = await submitPromise;
  const submitBody = await submitResponse.json().catch(() => null);
  const data = submitBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: submitResponse.url(), statusCode: submitResponse.status(), response: submitBody });
  const persisted = {
    success: submitBody?.success ?? null,
    message: submitBody?.message || null,
    reportId: data.reportId || null,
    productionPlanId: plan.id,
    productionBatchId: String(batch.id),
    batchNumber: batch.batchNumber,
    reportKind: 'INPUT',
    inputQuantity: 0.04,
    inputUnit: 'kg',
    selectedMaterialBatchLabel: selectedBatchLabel,
  };
  if (submitResponse.status() >= 400 || !persisted.success || !persisted.reportId) {
    throw new Error(`Expo App production input failed: ${submitResponse.status()} ${persisted.message}`);
  }
  state.entities.appProductionInputReport = persisted;
  state.mutations.push({ phase: 'expo-app-production-report', action: 'submit-production-input', entityId: persisted.reportId, at: new Date().toISOString() });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  await page.getByText('投入已提交', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.screenshot({ path: path.join(evidenceDir, '09-expo-production-input-success.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-production-report-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, statePath, appProductionInputReport: state.entities.appProductionInputReport || null }, null, 2));
