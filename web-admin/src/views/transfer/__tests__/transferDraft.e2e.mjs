import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:5187';
assert.equal(new URL(baseUrl).hostname, '127.0.0.1', 'fixture runner is local only');
const output = resolve('test-results/b07-transfer-draft'); await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
await context.addInitScript(() => localStorage.setItem('cretas_user', JSON.stringify({
  id: 1, username: 'b07-fixture', userType: 'factory', factoryUser: { factoryId: 'LOCAL_A', role: 'factory_super_admin', factoryType: 'FACTORY' },
})));
const requests = [], errors = [], results = []; let failCreate = true;
await context.route('**/*', async route => {
  const request = route.request(), url = new URL(request.url());
  if (url.origin !== new URL(baseUrl).origin) return route.abort();
  if (!url.pathname.startsWith('/api/')) return route.continue();
  requests.push({ path: url.pathname, query: url.search, method: request.method(), body: request.postDataJSON() });
  let data = [], success = true, message = 'fixture';
  if (url.pathname.includes('/api/admin/role-permissions')) data = ['inventory','warehouse'].map(moduleCode => ({ roleCode: 'factory_super_admin', moduleCode, permissionLevel: 'rw' }));
  if (url.pathname.includes('role-module-override')) data = {};
  if (url.pathname.endsWith('/factories/network')) data = [{ factoryId: url.pathname.split('/')[3], factoryName: '本地工厂' }, { factoryId: 'LOCAL_OTHER', factoryName: '另一工厂' }];
  if (/\/(factory|inventory)\/warehouses$/.test(url.pathname)) data = [{ id: 'S', name: '原料仓' }, { id: 'T', name: '生产仓' }];
  if (url.pathname.endsWith('/inventory/by-warehouse')) data = { materials: [{ materialTypeId: 'M', materialName: 'B07原料', materialMainCategory: '原料', materialCategory: '原料', unit: 'kg', currentQuantity: 100, status: 'AVAILABLE', ownership: 'COMPANY_OWNED' }], products: [] };
  if (url.pathname.includes('/material-packaging/')) data = { packagingSpecs: [] };
  if (url.pathname.endsWith('/transfers')) {
    data = { content: [], totalElements: 0 };
    if (request.method() === 'POST') { success = !failCreate; data = success ? { id: 'B07_CREATED' } : null; message = success ? 'created' : 'B07 fixture 保存失败'; }
  }
  if (url.pathname.endsWith('/transfers/B07_CREATED')) data = { id: 'B07_CREATED', status: 'DRAFT', items: [], sourceFactoryId: 'LOCAL_A', targetFactoryId: 'LOCAL_A' };
  await route.fulfill({ json: { success, data, message } });
});
const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
const formItem = label => page.locator('.create-workspace .el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: label }) });
const choose = async (locator, text) => {
  const listId = await locator.getByRole('combobox').getAttribute('aria-controls');
  await locator.click();
  await page.locator(`[id="${listId}"] .el-select-dropdown__item`).filter({ hasText: text }).first().click();
};
const draft = () => page.locator('.create-workspace');
const remark = () => page.getByPlaceholder('如: 领用 / 研发样品 / 余料退总仓 等');
const snapshot = async () => ({
  remark: await remark().inputValue(),
  date: await formItem('调拨日期').locator('input').inputValue(),
  arrival: await formItem('预计到货').locator('input').inputValue(),
  source: (await formItem('调出仓库').locator('.el-select__selection').innerText()).trim(),
  target: (await formItem('调入仓库').locator('.el-select__selection').innerText()).trim(),
  quantity: await page.getByPlaceholder('数量', { exact: true }).inputValue(),
  rowText: await draft().locator('.el-table__body').innerText(),
});
const clickTab = async index => { await page.locator('.workspace-tab').nth(index).click(); await page.waitForTimeout(100); };
const readDraftRequests = () => requests.filter(r => /factories\/network|factory\/warehouses|inventory\/warehouses|inventory\/by-warehouse|material-packaging/.test(r.path)).length;
try {
  await page.goto(`${baseUrl}/transfer/list`);
  await page.getByRole('button', { name: '手动新建调拨单', exact: true }).click();
  await remark().waitFor();
  const firstUrl = page.url(); assert.ok(new URL(firstUrl).searchParams.get('_task'));
  await choose(formItem('调拨类型').locator('.el-select'), '同一工厂仓库调拨');
  await choose(formItem('调出仓库').locator('.el-select'), '原料仓');
  await choose(formItem('调入仓库').locator('.el-select'), '生产仓');
  for (const [label, value] of [['调拨日期','2026-09-11'],['预计到货','2026-09-12']]) { const input = formItem(label).locator('input'); await input.fill(value); await input.press('Tab'); }
  await remark().fill('B07保留全部字段');
  await page.getByRole('button', { name: '添加物料', exact: true }).click();
  await choose(draft().locator('.el-table__body .el-select').nth(0), '原料');
  await choose(draft().locator('.el-table__body .el-select').nth(1), 'B07原料');
  await page.getByPlaceholder('数量', { exact: true }).fill('3'); await remark().click();
  const before = await snapshot(); assert.equal(before.quantity, '3.000');
  assert.equal(before.source, '原料仓'); assert.equal(before.target, '生产仓');
  await page.screenshot({ path: resolve(output, '01-before.png'), fullPage: true });
  const requestCount = readDraftRequests();
  for (let i=0;i<3;i++) { await clickTab(0); await clickTab(1); assert.deepEqual(await snapshot(), before); }
  assert.equal(readDraftRequests(), requestCount);
  results.push({ scenario: 'three actual tab returns retain all fields', before, after: await snapshot(), draftRequestsBefore: requestCount, draftRequestsAfter: readDraftRequests() });
  await page.screenshot({ path: resolve(output, '02-three-tab-returns.png'), fullPage: true });
  await page.getByRole('button', { name: '创建调拨草稿', exact: true }).click();
  await page.getByText('B07 fixture 保存失败', { exact: false }).first().waitFor();
  assert.deepEqual(await snapshot(), before); results.push({ scenario: 'failed submit retains all fields', pass: true });
  await page.screenshot({ path: resolve(output, '03-failed-save.png'), fullPage: true });
  await clickTab(0); await page.getByRole('button', { name: '手动新建调拨单', exact: true }).click(); await remark().waitFor();
  const secondUrl = page.url(); assert.notEqual(secondUrl, firstUrl); assert.equal(await remark().inputValue(), '');
  await remark().fill('B07独立第二草稿'); await clickTab(1); assert.deepEqual(await snapshot(), before);
  await clickTab(2); assert.equal(await remark().inputValue(), 'B07独立第二草稿');
  results.push({ scenario: 'explicit new and distinct _task isolation', firstUrl, secondUrl, pass: true });
  await clickTab(1);
  await choose(formItem('调拨类型').locator('.el-select'), '分部之间调拨');
  assert.equal(await formItem('调入仓库').locator('.el-select__placeholder').innerText(), '请选择调入仓库');
  await choose(formItem('调入仓库').locator('.el-select'), '生产仓');
  results.push({ scenario: 'transfer type resets dependent warehouse', pass: true });
  failCreate = false;
  await page.getByRole('button', { name: '创建调拨草稿', exact: true }).click();
  await page.waitForURL('**/transfer/B07_CREATED');
  await clickTab(1); await remark().waitFor(); assert.equal(await remark().inputValue(), '');
  assert.equal(await draft().locator('.el-table__body tr').count(), 0);
  assert.equal(requests.filter(r => r.method === 'POST' && r.path.endsWith('/transfers')).length, 2);
  results.push({ scenario: 'success clears its draft and two intentional attempts send exactly two writes', pass: true });
  await remark().fill('切厂前当前草稿');
  await page.evaluate(async () => {
    const { useAuthStore } = await import('/src/store/modules/auth.ts');
    useAuthStore().user.factoryUser.factoryId = 'LOCAL_B';
  });
  await page.waitForTimeout(200); assert.equal(await remark().inputValue(), '');
  await page.evaluate(path => document.querySelector('#app').__vue_app__.config.globalProperties.$router.push(path), new URL(secondUrl).pathname + new URL(secondUrl).search);
  await remark().waitFor(); assert.equal(await remark().inputValue(), '');
  results.push({ scenario: 'tenant switch clears active draft; reopening previous task URL contains no old tenant input', pass: true });
  await page.screenshot({ path: resolve(output, '04-tenant-reset.png'), fullPage: true });
  assert.deepEqual(errors, []);
  await writeFile(resolve(output, 'result.json'), JSON.stringify({ pass: true, realBusinessWrites: 0, results, errors, requests }, null, 2));
} catch(error) {
  await page.screenshot({ path: resolve(output, 'failure.png'), fullPage: true });
  await writeFile(resolve(output, 'failure.json'), JSON.stringify({ url: page.url(), error: String(error), results, errors, requests, body: await page.locator('body').innerText() }, null, 2));
  throw error;
} finally { await browser.close(); }
