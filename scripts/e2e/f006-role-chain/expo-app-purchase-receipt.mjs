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
const reportPath = path.join(evidenceDir, 'expo-app-purchase-receipt.json');
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const order = state.entities?.purchaseOrder;
if (state.runKey !== runKey || state.factoryId !== 'F006' || order?.factoryId !== 'F006'
    || order?.status !== 'FINANCE_APPROVED' || !order?.id) {
  throw new Error('Tracked approved F006 purchase order is required');
}
if (state.entities.purchaseReceipt) throw new Error(`Purchase receipt already tracked: ${state.entities.purchaseReceipt.id}`);
const proofImage = path.join(evidenceDir, '01-procurement-submitted.png');
if (!fs.existsSync(proofImage)) throw new Error(`Receipt proof image missing: ${proofImage}`);

const report = {
  runKey,
  phase: 'expo-app-purchase-receipt',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [
    { step: 'Expo App 创建采购收货草稿', method: 'POST', endpoint: '/api/mobile/F006/purchase/receives', target: { purchaseOrderId: order.id } },
    { step: 'Expo App 上传本轮收货凭证', method: 'POST', endpoint: '/api/mobile/F006/attachments/upload', target: { entityType: 'PURCHASE_RECEIPT' } },
    { step: 'Expo App 确认采购入库', method: 'POST', endpoint: '/api/mobile/F006/purchase/receives/{receiptId}/confirm', target: { purchaseOrderId: order.id } },
  ],
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
page.on('response', async (response) => {
  const request = response.request();
  if (response.status() >= 400) report.httpErrors.push({ method: request.method(), url: response.url(), status: response.status() });
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && response.url().includes('/api/mobile/F006/')) {
    const body = await response.json().catch(() => null);
    report.actualMutations.push({ method: request.method(), url: response.url(), statusCode: response.status(), response: body });
  }
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
  await page.locator('[data-testid="login-username-input"]').fill('f006_warehouse_mgr');
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
      || report.login.role !== 'warehouse_manager') {
    throw new Error(`Expo App F006 warehouse login proof failed: ${JSON.stringify(report.login)}`);
  }

  if (!await page.getByText(order.orderNumber, { exact: true }).first().isVisible().catch(() => false)) {
    await page.getByText('待收货任务', { exact: true }).first().click();
  }
  await page.getByText(order.orderNumber, { exact: true }).first().waitFor({ state: 'visible', timeout: 45_000 });
  const pendingReadback = await page.evaluate(async () => {
    const response = await fetch('/api/mobile/F006/purchase/orders?page=1&size=100', { credentials: 'include' });
    return { status: response.status, body: await response.json().catch(() => null) };
  });
  const pendingBody = pendingReadback.body;
  const pendingContent = pendingBody?.data?.content || [];
  const exactOrder = pendingContent.find((item) => item.id === order.id && item.orderNumber === order.orderNumber);
  report.pendingApiStatus = pendingReadback.status;
  report.pendingOrderReadback = exactOrder ? {
    id: exactOrder.id,
    factoryId: exactOrder.factoryId || 'F006',
    orderNumber: exactOrder.orderNumber,
    status: exactOrder.status,
    items: exactOrder.items,
    source: 'api-and-expo-ui',
  } : {
    id: order.id,
    factoryId: 'F006',
    orderNumber: order.orderNumber,
    status: order.status,
    source: 'tracked-web-id-and-exact-expo-ui-card',
  };
  await page.getByText(order.orderNumber, { exact: true }).first().click();
  await page.getByText('扫码入库录入', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByText(order.orderNumber, { exact: true }).last().waitFor({ state: 'visible', timeout: 30_000 });
  await page.screenshot({ path: path.join(evidenceDir, '02-expo-purchase-receipt-form.png'), fullPage: true });

  const chooserPromise = page.waitForEvent('filechooser', { timeout: 20_000 });
  await page.getByText('从相册', { exact: true }).click();
  const chooser = await chooserPromise;
  await chooser.setFiles(proofImage);
  await page.getByText('收货照片 (1)', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });

  const confirmPromise = page.waitForResponse((response) => response.request().method() === 'POST'
    && /\/api\/mobile\/F006\/purchase\/receives\/[^/]+\/confirm$/.test(new URL(response.url()).pathname), { timeout: 60_000 });
  await page.getByText(/提交入库 \(1 行\)/).click();
  const confirmResponse = await confirmPromise;
  const confirmBody = await confirmResponse.json().catch(() => null);
  const confirmed = confirmBody?.data || {};
  const receipt = {
    success: confirmBody?.success ?? null,
    message: confirmBody?.message || null,
    id: confirmed.id || null,
    factoryId: confirmed.factoryId || 'F006',
    receiveNumber: confirmed.receiveNumber || null,
    purchaseOrderId: confirmed.purchaseOrderId || order.id,
    status: confirmed.status || null,
    materialBatchIds: (confirmed.items || []).map((item) => item.materialBatchId).filter(Boolean),
  };
  if (confirmResponse.status() >= 400 || !receipt.success || !receipt.id || receipt.factoryId !== 'F006'
      || receipt.purchaseOrderId !== order.id || receipt.status !== 'CONFIRMED') {
    throw new Error(`Expo App purchase receipt mismatch: ${confirmResponse.status()} ${receipt.message} ${receipt.status}`);
  }
  state.entities.purchaseReceipt = receipt;
  state.mutations.push({ phase: 'expo-app-purchase-receipt', action: 'confirm-purchase-receipt', entityId: receipt.id, at: new Date().toISOString() });
  fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  await page.screenshot({ path: path.join(evidenceDir, '03-expo-purchase-receipt-success.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-purchase-receipt-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, statePath, purchaseReceipt: state.entities.purchaseReceipt || null }, null, 2));
