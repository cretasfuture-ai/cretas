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
const reportPath = path.join(evidenceDir, 'expo-app-quality-readback.json');
const state = JSON.parse(fs.readFileSync(statePath, 'utf8'));
const batch = state.entities?.productionBatch;
const inspection = state.entities?.qualityInspection;
if (state.runKey !== runKey || state.factoryId !== 'F006' || batch?.factoryId !== 'F006'
    || inspection?.factoryId !== 'F006' || !inspection?.id) {
  throw new Error('Tracked F006 quality inspection is required');
}

const report = {
  runKey,
  phase: 'expo-app-quality-readback',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [],
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
  const landing = page.locator('[data-testid="landing-login-btn"]');
  if (await landing.isVisible().catch(() => false)) await landing.click();
  await page.locator('[data-testid="login-username-input"]').waitFor({ state: 'visible', timeout: 60_000 });
  await page.locator('[data-testid="login-username-input"]').fill('f006_quality_insp');
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
      || report.login.role !== 'quality_inspector') {
    throw new Error(`Expo App F006 login proof failed: ${JSON.stringify(report.login)}`);
  }
  await page.locator('[data-testid="qi-home-screen"]').waitFor({ state: 'visible', timeout: 45_000 });

  const recordsPromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/mobile/F006/processing/quality/inspections', { timeout: 30_000 });
  await page.locator('[data-testid="qi-tab-records"]').click();
  const recordsResponse = await recordsPromise;
  const recordsBody = await recordsResponse.json().catch(() => null);
  const content = recordsBody?.data?.content || recordsBody?.data || [];
  const exact = Array.isArray(content) ? content.find((item) => String(item.id) === String(inspection.id)) : null;
  report.readback = exact ? {
    id: String(exact.id),
    factoryId: exact.factoryId || 'F006',
    productionBatchId: String(exact.batchId || exact.productionBatchId || ''),
    batchNumber: exact.batchNumber || batch.batchNumber,
    result: exact.result,
    sampleSize: exact.sampleSize,
    passCount: exact.passCount,
    failCount: exact.failCount,
    notes: exact.notes,
  } : null;
  if (!exact || report.readback.factoryId !== 'F006'
      || report.readback.productionBatchId !== String(batch.id)
      || !['PASS', 'PASSED'].includes(report.readback.result)) {
    throw new Error(`Exact persisted inspection not found: ${inspection.id}`);
  }
  await page.getByText(`B-${batch.id}`, { exact: true }).first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.getByText(runKey, { exact: true }).first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.screenshot({ path: path.join(evidenceDir, '12b-expo-quality-record-readback.png'), fullPage: true });
  report.result = 'PASS';
} catch (error) {
  report.result = 'FAIL';
  report.error = error instanceof Error ? error.message : String(error);
  await page.screenshot({ path: path.join(evidenceDir, 'expo-app-quality-readback-failure.png'), fullPage: true }).catch(() => {});
  process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await context.close();
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, readback: report.readback || null }, null, 2));
