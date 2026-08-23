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
const reportPath = path.join(evidenceDir, 'expo-app-warehouse-shipment.json');
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
if (state.runKey !== runKey || state.factoryId !== 'F006') throw new Error('State tenant/run key mismatch');
const shipment = state.entities.salesShipment;
const allocation = state.entities.salesDeliveryAllocation;
if (!shipment?.id || shipment.factoryId !== 'F006' || shipment.status !== 'PENDING_WAREHOUSE_CONFIRM'
    || !shipment.deliveryNumber || !allocation?.batchNumber || allocation.quantity !== 1) {
  throw new Error('Tracked allocated F006 child shipment is required');
}
if (state.entities.warehouseShipmentConfirmation) {
  throw new Error(`Warehouse shipment already tracked: ${state.entities.warehouseShipmentConfirmation.id}`);
}
const itemId = String(shipment.items?.[0]?.id || '');
if (!itemId) throw new Error('Tracked shipment item is required');

const report = {
  runKey,
  phase: 'expo-app-warehouse-shipment',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [{
    step: 'Expo App 仓储主管确认子发运单、按计划实发 1 盒并扣减已分配批次库存',
    method: 'POST',
    endpoint: `/api/mobile/F006/warehouse/deliveries/${shipment.id}/confirm`,
    target: { deliveryId: shipment.id, deliveryNumber: shipment.deliveryNumber, itemId, allocatedBatchNumber: allocation.batchNumber },
    payloadBoundary: { actualQuantities: { [itemId]: 1 } },
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

  const pendingResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/mobile/F006/warehouse/deliveries/pending', { timeout: 35_000 });
  await page.locator('[data-testid="wh-tab-outbound"]').click();
  const pendingResponse = await pendingResponsePromise;
  const pendingBody = await pendingResponse.json().catch(() => null);
  const pendingRows = pendingBody?.data?.content || [];
  const exactPending = pendingRows.find((row) => row.id === shipment.id && row.deliveryNumber === shipment.deliveryNumber);
  report.pendingShipmentReadback = exactPending || null;
  if (pendingResponse.status() >= 400 || !pendingBody?.success || !exactPending || exactPending.factoryId !== 'F006') {
    throw new Error(`Exact F006 child shipment is missing from Expo App pending list: ${shipment.deliveryNumber}`);
  }

  const detailResponsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === `/api/mobile/F006/sales/deliveries/${shipment.id}`, { timeout: 35_000 });
  await page.getByText(shipment.deliveryNumber, { exact: true }).click();
  const detailResponse = await detailResponsePromise;
  const detailBody = await detailResponse.json().catch(() => null);
  report.shipmentDetailReadback = detailBody?.data || null;
  if (detailResponse.status() >= 400 || !detailBody?.success || detailBody?.data?.id !== shipment.id) {
    throw new Error(`Expo App child shipment detail mismatch: ${shipment.deliveryNumber}`);
  }

  const actualQty = page.locator(`[data-testid="wh-actual-qty-${itemId}"]`);
  await actualQty.waitFor({ state: 'visible', timeout: 30_000 });
  if ((await actualQty.inputValue()) !== '1') await actualQty.fill('1');
  const pickBox = page.locator(`[data-testid="wh-pick-batches-${itemId}"]`);
  await pickBox.waitFor({ state: 'visible', timeout: 30_000 });
  report.pickInstruction = await pickBox.innerText();
  if (!report.pickInstruction.includes(allocation.batchNumber) || !report.pickInstruction.includes('1 盒')) {
    throw new Error(`Expo App pick instruction does not match allocated batch: ${report.pickInstruction}`);
  }
  await page.screenshot({ path: path.join(evidenceDir, '14-expo-warehouse-shipment-ready.png'), fullPage: true });

  const confirmResponsePromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/mobile/F006/warehouse/deliveries/${shipment.id}/confirm`, { timeout: 35_000 });
  const confirmButton = page.locator('[data-testid="wh-confirm-delivery-btn"]');
  if (await confirmButton.isDisabled()) throw new Error('Expo App warehouse shipment confirm remains disabled');
  await confirmButton.click();
  const confirmResponse = await confirmResponsePromise;
  const confirmBody = await confirmResponse.json().catch(() => null);
  const confirmed = confirmBody?.data || {};
  report.actualMutations.push({ method: 'POST', url: confirmResponse.url(), statusCode: confirmResponse.status(), response: confirmed });
  if (confirmResponse.status() >= 400 || !confirmBody?.success || confirmed.id !== shipment.id
      || confirmed.factoryId !== 'F006' || confirmed.status !== 'SHIPPED') {
    throw new Error(`Expo App warehouse shipment confirm mismatch: ${confirmResponse.status()} ${confirmBody?.message} ${confirmed.status}`);
  }
  state.entities.warehouseShipmentConfirmation = confirmed;
  state.entities.salesShipment = { ...shipment, ...confirmed };
  state.mutations.push({ phase: 'expo-app-warehouse-shipment', action: 'confirm-shipment-and-deduct-stock', entityId: shipment.id, at: new Date().toISOString() });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  await page.getByText('发货确认成功，成品库存已扣减', { exact: true }).waitFor({ state: 'visible', timeout: 15_000 });
  await page.screenshot({ path: path.join(evidenceDir, '15-expo-warehouse-shipment-confirmed.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-warehouse-shipment-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, statePath, warehouseShipmentConfirmation: state.entities.warehouseShipmentConfirmation || null }, null, 2));
