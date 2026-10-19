async (page) => {
  const fs = await import('node:fs/promises');
  const base = 'http://127.0.0.1:31772';
  const out = 'C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p37/joint-upgrade-20261019';
  if (!page.url().startsWith(base)) throw new Error('Only owned P37 UI is allowed');
  const credentials = JSON.parse(await fs.readFile('C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p14/factory-credentials.private.json', 'utf8'));
  await page.getByRole('textbox', { name: '请输入手机号或用户名' }).fill('food_3101_001_admin');
  await page.getByRole('textbox', { name: '请输入密码', exact: true }).fill(credentials.password);
  await page.getByRole('button', { name: '登 录' }).click();
  await page.waitForURL(url => url.pathname !== '/login', { timeout: 30000 });
  const errors = [], failures = [], writes = [], screenshots = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (response.url().startsWith(base + '/api/') && response.status() >= 400) failures.push({ path: new URL(response.url()).pathname, status: response.status() }); });
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== base) return route.abort();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && !url.pathname.endsWith('/activity-events')) {
      writes.push({ path: url.pathname, method: request.method() }); return route.abort();
    }
    return route.continue();
  });
  const rows = {};
  for (const period of ['2026-10-18', '2026-10-19']) {
    await page.goto(`${base}/analytics/indicators?periodStart=${period}&periodEnd=${period}`);
    const panel = page.getByTestId('product-production-results');
    await panel.getByRole('cell', { name: period === '2026-10-18' ? 'P37 generality FG A' : 'P0体验卤制品B（500克盒装）', exact: true }).waitFor({ timeout: 45000 });
    await panel.scrollIntoViewIfNeeded();
    const text = await panel.innerText();
    const tableText = await panel.locator('.el-table').innerText();
    const expected = period === '2026-10-18' ? ['33.33%', '66.67%', '待补数据'] : ['33.33%', '66.67%', '11.7', '19.3', '9.65', '88.89%', '100%', '27', '13.5'];
    if (expected.some(value => !tableText.includes(value)) || (period === '2026-10-19' && tableText.includes('待补数据'))) throw new Error('Incorrect product metric values: ' + tableText);
    if (!text.includes('贡献出成率')) throw new Error('Joint yield explanation is missing');
    rows[period] = text;
    const screenshot = `${out}/indicators-${period}.png`;
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' }); screenshots.push(screenshot);
  }
  const upgrade = JSON.parse(await fs.readFile(`${out}/formal-upgrade-business.json`, 'utf8'));
  for (const [key, caseData] of Object.entries({ old: upgrade.old, new: upgrade.new })) {
    const batch = caseData.settlement.outputLines[0].reportedBatchNumber;
    await page.goto(`${base}/production/traceability?batchNumber=${encodeURIComponent(batch)}`);
    await page.getByTestId('production-card').waitFor({ timeout: 45000 });
    if (!(await page.getByTestId('trace-code-batch').innerText()).includes(batch)) throw new Error('Wrong trace batch identity');
    const screenshot = `${out}/trace-${key}.png`;
    await page.screenshot({ path: screenshot, fullPage: true, animations: 'disabled' }); screenshots.push(screenshot);
  }
  await page.goto(`${base}/analytics/indicators?periodStart=2026-10-19&periodEnd=2026-10-19`);
  await page.getByTestId('product-production-results').getByText('贡献出成率', { exact: false }).waitFor();
  await page.getByTestId('product-production-results').scrollIntoViewIfNeeded();
  const record = { status: 'PASS', account: 'food_3101_001_admin', factory: 'FOOD_3101_001', rows, screenshots, errors, failures, blockedBusinessWrites: writes, url: page.url() };
  await fs.writeFile(`${out}/browser-acceptance.json`, JSON.stringify(record, null, 2));
  if (errors.length || failures.length || writes.length) throw new Error('P37 UI errors; inspect browser receipt');
  return { status: 'PASS', url: page.url(), screenshots };
}
