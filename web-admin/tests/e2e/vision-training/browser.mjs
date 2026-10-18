import { chromium, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const out = process.env.CRETAS_VISION_UI_EVIDENCE_DIR;
if (!out) throw Error('Explicit evidence directory required');
const base = 'http://127.0.0.1:31760';
const dataVersion = process.env.CRETAS_VISION_UI_DATA_VERSION || 'local-ui-data-v1';
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
const page = await context.newPage();
const errors = [], calls = [], saved = [];
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url());
  if (url.origin !== base) { errors.push('Unexpected origin ' + url.origin); return route.abort(); }
  if (url.pathname.startsWith('/api/')) {
    calls.push({ method: request.method(), path: url.pathname });
    if (!/^\/api\/platform\/factories\/VISION-UI-[AB]\/vision-training\//.test(url.pathname)
        || (request.method() !== 'GET' && !(request.method() === 'POST' && /\/(preview|drafts)$/.test(url.pathname)))) {
      errors.push('Unexpected private operation ' + request.method() + ' ' + url.pathname); return route.abort();
    }
  }
  return route.continue();
});
page.on('pageerror', error => errors.push(error.message));
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
page.on('response', async response => {
  if (response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/drafts')) {
    const result = await response.json(); saved.push(result.data);
  }
});
async function choose(testId, label) {
  await page.getByTestId(testId).click();
  await page.locator('.el-select-dropdown:visible').getByText(label, { exact: true }).click();
}
async function fill(testId, value) {
  const field = page.getByTestId(testId);
  const input = await field.evaluate(node => node.tagName.toLowerCase() === 'input') ? field : field.locator('input');
  await input.fill(value);
}
try {
  await page.goto(base + '/tests/e2e/vision-training/fixture.html');
  await expect(page.getByTestId('vision-template')).toBeVisible();
  expect(calls.every(call => call.method === 'GET')).toBe(true);
  await choose('vision-template', '包装有无与缺件检测');
  await fill('vision-workstation', '一号包装线');
  await choose('vision-products', '产品A');
  await page.getByTestId('vision-workstation').click();
  await fill('vision-packaging', '盒装');
  await page.getByTestId('vision-packaging').locator('input').press('Enter');
  await fill('vision-data_version', dataVersion);
  await fill('vision-recipe_version', 'local-ui-recipe-v1');
  await fill('vision-human_standard_version', 'local-ui-human-v1');
  await fill('vision-independent_acceptance_version', 'local-ui-accept-v1');
  await page.getByRole('button', { name: '预览训练流程', exact: true }).click();
  await expect(page.getByTestId('vision-preview')).toBeVisible();
  expect(saved).toHaveLength(0);
  await page.getByRole('button', { name: '保存训练项目', exact: true }).click();
  await expect(page.getByTestId('vision-project')).toContainText('一号包装线');
  await expect(page.getByText('本厂独立验收待完成', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '提交训练候选', exact: true })).toBeDisabled();
  expect(saved).toHaveLength(1);
  expect(saved[0].spec.factory_id).toBe('VISION-UI-A');
  expect(saved[0].status).toBe('draft');
  await page.screenshot({ path: resolve(out, 'ui-saved-pending.png'), fullPage: true });
  await page.reload();
  await expect(page.locator('.vision-resume .el-select')).toBeVisible();
  await page.locator('.vision-resume .el-select').click();
  await page.locator('.el-select-dropdown:visible').getByText(`一号包装线 · 产品A · ${dataVersion}`, { exact: true }).click();
  await expect(page.getByTestId('vision-project')).toBeVisible();
  await page.locator('#factory-b').click();
  await expect(page.getByTestId('vision-training-setup')).toContainText('VISION-UI-B');
  await expect(page.getByTestId('vision-template')).toBeVisible();
  await expect(page.locator('.vision-resume .el-select')).toHaveCount(0);
  await expect(page.getByTestId('vision-project')).toHaveCount(0);
  await page.getByTestId('vision-template').click();
  await expect(page.locator('.el-select-dropdown:visible')).not.toContainText('六膳门');
  await page.getByTestId('vision-workstation').click();
  await page.screenshot({ path: resolve(out, 'ui-other-factory.png'), fullPage: true });
  expect(calls.some(call => call.method === 'POST' && call.path.endsWith('/jobs'))).toBe(false);
  expect(errors).toEqual([]);
  await writeFile(resolve(out, 'ui-result.json'), JSON.stringify({ status: 'pass',
    layer: 'actual Vue component -> private test proxy -> Python main.app -> durable files/SQLite',
    excluded: ['actual platform onboarding entry', 'Java proxy', 'production authentication', 'GPU training', 'model acceptance'],
    productionWrites: 0, trainingExecuted: false, errors, calls, saved }, null, 2));
} catch (error) {
  await page.screenshot({ path: resolve(out, 'ui-failure.png'), fullPage: true });
  await writeFile(resolve(out, 'ui-failure.json'), JSON.stringify({ error: String(error), errors, calls }, null, 2));
  throw error;
} finally { await browser.close(); }
