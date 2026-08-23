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
const reportPath = path.join(evidenceDir, 'expo-app-warehouse-receipt.json');
if (!fs.existsSync(statePath)) throw new Error(`State file missing: ${statePath}`);
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
if (state.runKey !== runKey || state.factoryId !== 'F006') throw new Error('State tenant/run key mismatch');
const productionPlan = state.entities.productionPlan;
const productionBatch = state.entities.productionBatch;
const settlement = state.entities.productionSettlement;
if (!productionPlan?.id || productionPlan.factoryId !== 'F006' || productionPlan.status !== 'COMPLETED'
    || !productionBatch?.id || productionBatch.factoryId !== 'F006'
    || settlement?.postingStatus !== 'PENDING_WAREHOUSE_RECEIPT') {
  throw new Error('Tracked completed F006 production receipt is required');
}
if (state.entities.productionWarehouseReceipt) {
  throw new Error(`Production warehouse receipt already tracked: ${state.entities.productionWarehouseReceipt.settlementId}`);
}

const report = {
  runKey,
  phase: 'expo-app-warehouse-receipt',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [{
    step: 'Expo App 仓储主管确认 Web 结单的成品实收',
    method: 'POST',
    endpoint: `/api/mobile/F006/warehouse/transit-ledgers/${productionPlan.id}/confirm`,
    target: { productionPlanId: productionPlan.id, productionBatchId: String(productionBatch.id), batchNumber: productionBatch.batchNumber },
    payloadBoundary: { exactFullReceipt: true, expectedQuantity: 1, expectedUnit: '盒', note: runKey },
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
  await page.goto(appUrl, { waitUntil: 'domcontentloaded', timeout: 90_000 });
  const landingButton = page.locator('[data-testid="landing-login-btn"]');
  if (await landingButton.isVisible().catch(() => false)) await landingButton.click();
  const usernameInput = page.locator('[data-testid="login-username-input"]');
  await usernameInput.waitFor({ state: 'visible', timeout: 90_000 });
  await usernameInput.fill('f006_warehouse_mgr');
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
      || report.login.role !== 'warehouse_manager') {
    throw new Error(`Expo App F006 warehouse login proof failed: ${JSON.stringify(report.login)}`);
  }

  const warehouseHome = page.locator('[data-testid="wh-home-screen"]');
  await warehouseHome.waitFor({ state: 'visible', timeout: 60_000 });
  await page.locator('[data-testid="wh-home-inbound"]').click();
  const entry = page.locator('[data-testid="wh-production-receipt-entry"]');
  await entry.waitFor({ state: 'visible', timeout: 60_000 });
  await page.screenshot({ path: path.join(evidenceDir, '11-expo-warehouse-inbound-home.png'), fullPage: true });
  const listResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/mobile/F006/warehouse/transit-ledgers'
    && new URL(response.url()).searchParams.get('status') === 'PENDING_CONFIRMATION', { timeout: 30_000 });
  await entry.click();
  const listResponse = await listResponsePromise;
  const listBody = await listResponse.json().catch(() => null);
  const exactItem = (Array.isArray(listBody?.data) ? listBody.data : []).find((item) => item.id === productionPlan.id
    && item.sourceNumber === productionPlan.planNumber);
  report.pendingReceiptReadback = exactItem || null;
  if (listResponse.status() >= 400 || !listBody?.success || !exactItem
      || exactItem.direction !== 'FINISHED_GOODS_RECEIPT') {
    throw new Error(`Exact F006 production receipt is missing from Expo App: ${productionPlan.planNumber}`);
  }

  const card = page.locator(`[data-testid="transit-card-${productionPlan.id}"]`);
  await card.waitFor({ state: 'visible', timeout: 30_000 });
  const outputLines = Array.isArray(exactItem.outputLines) ? exactItem.outputLines : [];
  if (outputLines.length > 0) {
    for (let index = 0; index < outputLines.length; index += 1) {
      await card.locator(`[data-testid="transit-received-${productionPlan.id}-${index}"]`)
        .fill(String(outputLines[index].reportedQuantity));
    }
  } else {
    await card.locator(`[data-testid="transit-received-${productionPlan.id}"]`)
      .fill(String(exactItem.reportedQuantity));
  }
  await card.locator(`[data-testid="transit-note-${productionPlan.id}"]`).fill(runKey);
  const confirmButton = card.locator(`[data-testid="transit-confirm-${productionPlan.id}"]`);
  await confirmButton.waitFor({ state: 'visible', timeout: 15_000 });
  if (await confirmButton.isDisabled()) throw new Error('Expo App production receipt confirm remains disabled after exact prefill');

  const confirmResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/warehouse/transit-ledgers/${productionPlan.id}/confirm`, { timeout: 35_000 });
  await confirmButton.click();
  const confirmResponse = await confirmResponsePromise;
  const confirmBody = await confirmResponse.json().catch(() => null);
  const data = confirmBody?.data || {};
  const receipt = {
    success: confirmBody?.success ?? null,
    message: data.message || confirmBody?.message || null,
    settlementId: data.settlementId || settlement.settlementId || null,
    productionPlanId: data.productionPlanId || productionPlan.id,
    planNumber: data.planNumber || productionPlan.planNumber,
    productionReportedQuantity: data.productionReportedQuantity ?? exactItem.reportedQuantity ?? null,
    warehouseReceivedQuantity: data.warehouseReceivedQuantity ?? null,
    quantityUnit: data.quantityUnit || exactItem.unit || null,
    postingStatus: data.postingStatus || null,
    finishedGoodsBatchId: data.finishedGoodsBatchId || null,
    outputLines: Array.isArray(data.outputLines) ? data.outputLines : [],
  };
  report.actualMutations.push({ method: 'POST', url: confirmResponse.url(), statusCode: confirmResponse.status(), response: receipt });
  if (confirmResponse.status() >= 400 || !receipt.success || receipt.productionPlanId !== productionPlan.id
      || !['POSTED', 'POSTED_WITH_TOLERANCE'].includes(receipt.postingStatus)) {
    throw new Error(`Expo App production receipt mismatch: ${confirmResponse.status()} ${receipt.message} ${receipt.postingStatus}`);
  }
  state.entities.productionWarehouseReceipt = receipt;
  state.entities.productionSettlement = { ...settlement, postingStatus: receipt.postingStatus };
  state.entities.productionBatch = { ...productionBatch, status: 'COMPLETED' };
  state.mutations.push({ phase: 'expo-app-warehouse-receipt', action: 'confirm-production-warehouse-receipt', entityId: receipt.finishedGoodsBatchId || receipt.settlementId, at: new Date().toISOString() });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  await page.screenshot({ path: path.join(evidenceDir, '12-expo-warehouse-production-receipt.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-warehouse-receipt-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, statePath, productionWarehouseReceipt: state.entities.productionWarehouseReceipt || null }, null, 2));
