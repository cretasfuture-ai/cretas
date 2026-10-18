async (page) => {
  const fs = await import('node:fs/promises');
  const path = await import('node:path');
  const out = 'C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p37';
  const mode = (await fs.readFile(path.join(out, 'ui-mode.txt'), 'utf8')).trim();
  if (!['platform', 'daily'].includes(mode)) throw new Error('P37 UI mode must be explicit');
  const credentialFile = mode === 'platform'
    ? 'C:/Users/Steve/Documents/cretas-connect-20261015/vision/platform-entry/credentials.private.json'
    : 'C:/Users/Steve/Documents/cretas-connect-20261015/factory-data-p14/factory-credentials.private.json';
  const credentials = JSON.parse(await fs.readFile(credentialFile, 'utf8'));
  const base = 'http://127.0.0.1:31772';
  if (!page.url().startsWith(base)) throw new Error('Only the owned P37 browser tab is allowed');
  if (new URL(page.url()).pathname === '/login') {
    await page.getByRole('textbox', { name: '请输入手机号或用户名' }).fill(mode === 'platform' ? credentials.username : 'food_3101_001_admin');
    await page.getByRole('textbox', { name: '请输入密码', exact: true }).fill(credentials.password);
    await page.getByRole('button', { name: '登 录' }).click();
    await page.waitForURL(url => url.pathname !== '/login', { timeout: 30000 });
  }
  const errors = [], failures = [], writes = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (response.url().startsWith(base + '/api/') && response.status() >= 400) failures.push({ url: new URL(response.url()).pathname, status: response.status() }); });
  await page.route('**/api/**', async route => {
    const request = route.request();
    if (new URL(request.url()).origin !== base) return route.abort();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      const pathname = new URL(request.url()).pathname;
      if (!pathname.startsWith('/api/mobile/auth/') && !pathname.endsWith('/activity-events')) {
        writes.push({ method: request.method(), path: pathname });
        return route.abort();
      }
    }
    return route.continue();
  });
  const target = mode === 'platform'
    ? '/factory-review?factoryId=FOOD_3101_001'
    : '/analytics/indicators?periodStart=2026-10-18&periodEnd=2026-10-18';
  await page.goto(base + target);
  if (mode === 'platform') {
    const panel = page.getByTestId('factory-readiness');
    await panel.getByText('当前候选：第2版').waitFor({ timeout: 30000 });
    await panel.getByText('已应用：第1版').waitFor();
    await panel.getByText('P0体验卤制品B（500克盒装）', { exact: true }).first().waitFor();
    await panel.scrollIntoViewIfNeeded();
  } else {
    await page.getByText('指标适用性', { exact: true }).waitFor({ timeout: 30000 });
    await page.locator('.indicator-applicability .product-note').first().waitFor();
  }
  await page.screenshot({ path: path.join(out, mode + '-readiness.png'), fullPage: true, animations: 'disabled' });
  const statusTags = await page.locator('.indicator-applicability .el-tag').evaluateAll(nodes => nodes.map(node => ({
    text: node.textContent, color: getComputedStyle(node).color, opacity: getComputedStyle(node).opacity,
    width: node.getBoundingClientRect().width,
  })));
  if (mode === 'daily' && (statusTags.length < 5 || statusTags.some(tag => !tag.text || tag.opacity !== '1' || tag.width <= 0))) {
    throw new Error('Indicator configuration statuses are not visibly rendered');
  }
  const record = { mode, url: page.url(), errors, failures, blockedBusinessWrites: writes,
    screenshot: path.join(out, mode + '-readiness.png'), productionTouched: false, statusTags };
  await fs.writeFile(path.join(out, mode + '-ui.json'), JSON.stringify(record, null, 2));
  if (errors.length || failures.length || writes.length) throw new Error('P37 UI has errors; inspect private receipt');
  return { status: 'PASS', mode, url: page.url(), screenshot: record.screenshot };
}
