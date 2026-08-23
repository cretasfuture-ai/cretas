#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';

const appUrl = process.env.E2E_EXPO_URL || 'http://localhost:3010';
const password = process.env.E2E_PASSWORD;
const runKey = process.env.E2E_RUN_KEY || 'E2E-RBAC-20260823-APP-CENSUS';
if (!password) throw new Error('E2E_PASSWORD is required');
const evidenceDir = path.resolve(process.env.E2E_EVIDENCE_DIR || path.join('.playwright-mcp', 'f006-role-chain', runKey));
fs.mkdirSync(evidenceDir, { recursive: true });
const reportPath = path.join(evidenceDir, 'expo-app-role-census.json');

const roles = [
  { username: 'f006_procurement_mgr', role: 'procurement_manager', tabs: ['pm-tab-home', 'pm-tab-purchase', 'pm-tab-supplier', 'pm-tab-profile'] },
  { username: 'f006_warehouse_mgr', role: 'warehouse_manager', tabs: ['wh-tab-inbound', 'wh-tab-outbound', 'wh-tab-profile'] },
  { username: 'f006_sales_mgr', role: 'sales_manager', tabs: ['sm-tab-home', 'sm-tab-sales', 'sm-tab-customer', 'sm-tab-profile'] },
  { username: 'f006_production_mgr', role: 'production_manager', tabs: ['production-manager-tab-overview', 'production-manager-tab-profile'] },
  { username: 'f006_finance_mgr', role: 'finance_manager', tabs: ['finance-tab-home', 'finance-tab-oa-todo', 'finance-tab-profile'] },
  { username: 'f006_quality_insp', role: 'quality_inspector', tabs: ['qi-tab-home', 'qi-tab-inspect', 'qi-tab-records', 'qi-tab-profile'] },
  { username: 'f006_workshop', role: 'workshop_supervisor', tabs: ['tab-home', 'tab-batches', 'tab-workers', 'tab-profile'] },
];

const report = {
  runKey,
  phase: 'expo-app-role-census',
  factoryId: 'F006',
  startedAt: new Date().toISOString(),
  intendedMutations: [],
  actualMutations: [],
  roles: [],
  result: 'RUNNING',
};

const browser = await chromium.launch({ headless: true });
try {
  for (const roleConfig of roles) {
    const context = await browser.newContext({ viewport: { width: 430, height: 920 } });
    const page = await context.newPage();
    const roleResult = { username: roleConfig.username, expectedRole: roleConfig.role, login: null, tabs: [], consoleErrors: [], httpErrors: [] };
    page.on('console', (message) => {
      if (message.type() === 'error') roleResult.consoleErrors.push({ text: message.text(), url: message.location().url || null });
    });
    page.on('response', (response) => {
      if (response.status() >= 400) roleResult.httpErrors.push({ method: response.request().method(), url: response.url(), status: response.status() });
    });
    page.on('dialog', (dialog) => dialog.accept());
    try {
      await page.goto(appUrl, { waitUntil: 'domcontentloaded', timeout: 90_000 });
      const landing = page.locator('[data-testid="landing-login-btn"]');
      if (await landing.isVisible().catch(() => false)) await landing.click();
      await page.locator('[data-testid="login-username-input"]').waitFor({ state: 'visible', timeout: 90_000 });
      await page.locator('[data-testid="login-username-input"]').fill(roleConfig.username);
      await page.locator('[data-testid="login-password-input"]').fill(password);
      const loginPromise = page.waitForResponse((response) => response.request().method() === 'POST'
        && new URL(response.url()).pathname === '/api/mobile/auth/unified-login', { timeout: 30_000 });
      await page.locator('[data-testid="login-submit-btn"]').click();
      const loginResponse = await loginPromise;
      const loginBody = await loginResponse.json().catch(() => null);
      const loginData = loginBody?.data || {};
      roleResult.login = {
        statusCode: loginResponse.status(),
        success: loginBody?.success ?? null,
        username: loginData.username || loginData.user?.username || null,
        factoryId: loginData.factoryUser?.factoryId || loginData.factoryId || loginData.user?.factoryId || null,
        role: loginData.factoryUser?.role || loginData.role || loginData.user?.role || null,
      };
      if (loginResponse.status() >= 400 || !roleResult.login.success || roleResult.login.factoryId !== 'F006'
          || roleResult.login.role !== roleConfig.role) {
        throw new Error(`Login proof mismatch: ${JSON.stringify(roleResult.login)}`);
      }
      await page.locator(`[data-testid="${roleConfig.tabs[0]}"]`).waitFor({ state: 'attached', timeout: 60_000 });
      for (let index = 0; index < roleConfig.tabs.length; index += 1) {
        const tabId = roleConfig.tabs[index];
        const tab = page.locator(`[data-testid="${tabId}"]`);
        const visible = await tab.isVisible().catch(() => false);
        if (!visible) {
          roleResult.tabs.push({ tabId, result: 'FAIL', reason: 'required tab not visible' });
          continue;
        }
        const errorsBefore = roleResult.httpErrors.length;
        await tab.click();
        await page.waitForTimeout(1200);
        const newHttpErrors = roleResult.httpErrors.slice(errorsBefore).filter((item) => item.status === 403 || item.status >= 500);
        const text = (await page.locator('body').innerText()).replace(/\s+/g, ' ').trim().slice(0, 500);
        const screenshotName = `app-census-${roleConfig.role}-${String(index + 1).padStart(2, '0')}-${tabId}.png`;
        await page.screenshot({ path: path.join(evidenceDir, screenshotName), fullPage: true });
        roleResult.tabs.push({ tabId, result: newHttpErrors.length === 0 ? 'PASS' : 'FAIL', httpErrors: newHttpErrors, visibleText: text, screenshot: screenshotName });
      }
    } catch (error) {
      roleResult.error = error instanceof Error ? error.message : String(error);
    } finally {
      const blockingHttpErrors = roleResult.httpErrors.filter((item) => item.status === 403 || item.status >= 500);
      roleResult.result = !roleResult.error && roleResult.tabs.length === roleConfig.tabs.length
        && roleResult.tabs.every((item) => item.result === 'PASS')
        && roleResult.consoleErrors.length === 0 && blockingHttpErrors.length === 0 ? 'PASS' : 'FAIL';
      report.roles.push(roleResult);
      await context.close();
    }
  }
  report.result = report.roles.every((item) => item.result === 'PASS') ? 'PASS' : 'FAIL';
  if (report.result !== 'PASS') process.exitCode = 1;
} finally {
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  await browser.close();
}

console.log(JSON.stringify({ result: report.result, reportPath, roles: report.roles.map((item) => ({ role: item.expectedRole, result: item.result, tabs: item.tabs.length, error: item.error || null })) }, null, 2));
