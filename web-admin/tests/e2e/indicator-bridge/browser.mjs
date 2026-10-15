import { chromium, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const out = process.env.CRETAS_INDICATOR_BRIDGE_EVIDENCE_DIR;
if (!out) throw Error('Explicit evidence directory required');
const base = 'http://127.0.0.1:31759';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage();
const errors = [], calls = [], responses = [];
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
// Prevent a local fixture from contacting any production service. Only the
// exact recomputation endpoints can write in this private database scenario.
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url());
  if (url.origin !== base) { errors.push('Unexpected origin: ' + url.origin); return route.abort(); }
  if (url.pathname.includes('/chart-insight')) {
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: false, data: null, message: 'Narrative excluded from local indicator acceptance' }) });
  }
  if (url.pathname.startsWith('/api/')) {
    calls.push({ method: request.method(), path: url.pathname });
    if (request.method() !== 'GET' && !(request.method() === 'POST' && /^\/api\/mobile\/IND-BRIDGE-[AB]\/indicators\/(RESTAURANT_DISH_MARGIN|MISMATCHED_KPI)\/recompute$/.test(url.pathname))) {
      errors.push('Unexpected private mutation: ' + request.method() + ' ' + url.pathname);
      return route.abort();
    }
  }
  return route.continue();
});
page.on('response', async response => {
  if (response.url().includes('/api/mobile/') && /\/(value|recompute)(\?|$)/.test(response.url())) {
    responses.push({ path: new URL(response.url()).pathname, status: response.status(), body: await response.json() });
  }
});
async function closeDrawer() {
  await page.getByRole('button', { name: 'Close this dialog' }).click();
  await expect(page.locator('.el-drawer')).toBeHidden();
  await page.locator('.el-message').evaluateAll(nodes => nodes.forEach(node => node.remove()));
}
try {
  await page.goto(base + '/tests/e2e/indicator-bridge/fixture.html');
  await page.locator('#open-zero').click();
  await expect(page.locator('.threshold-gauge .value-number')).toHaveText('0');
  await expect(page.getByTestId('indicator-evidence')).toContainText('数据分析服务');
  await expect(page.getByText('使用当前成本快照计算', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: '强制重算' }).click();
  await expect(page.locator('.el-message--success')).toContainText('重算完成: 0');
  await page.screenshot({ path: resolve(out, 'ui-real-zero.png'), fullPage: true });
  await closeDrawer();
  await page.locator('#open-empty').click();
  await expect(page.getByTestId('indicator-evidence')).toContainText('暂无业务数据');
  await page.getByRole('button', { name: '强制重算' }).click();
  await expect(page.locator('.el-message--warning')).toContainText('未生成新的计算快照');
  await expect(page.locator('.el-message--success')).toHaveCount(0);
  await expect(page.locator('.threshold-gauge .value-number')).toHaveText('—');
  await page.screenshot({ path: resolve(out, 'ui-no-data.png'), fullPage: true });
  await closeDrawer();
  await page.locator('#open-invalid').click();
  await expect(page.getByTestId('indicator-evidence')).toContainText('计算失败');
  await page.getByRole('button', { name: '强制重算' }).click();
  await expect(page.locator('.el-message--error')).toContainText('指标重算失败');
  await expect(page.locator('.el-message--success')).toHaveCount(0);
  await page.screenshot({ path: resolve(out, 'ui-failed.png'), fullPage: true });
  if (errors.length) throw Error(errors.join('; '));
  await writeFile(resolve(out, 'ui-result.json'), JSON.stringify({ status: 'pass',
    layer: 'actual drawer -> Java HTTP -> Python main.app -> private PostgreSQL',
    javaProductionAuth: 'excluded; local component role configured in fixture',
    productionWrites: 0, errors, calls, responses }, null, 2));
  await writeFile(resolve(out, 'ui-done.txt'), 'PASS');
} catch (error) {
  await page.screenshot({ path: resolve(out, 'ui-failure.png'), fullPage: true });
  await writeFile(resolve(out, 'ui-failure.json'), JSON.stringify({ error: String(error), errors, calls, responses }, null, 2));
  throw error;
} finally { await browser.close(); }
