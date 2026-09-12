// Run against this worktree's Vite server. All API traffic is fulfilled locally.
import { chromium, expect } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const base = process.env.B02_TEST_URL || 'http://127.0.0.1:5194';
if (new URL(base).hostname !== '127.0.0.1') throw new Error('Local fixture only');
const out = resolve(process.env.B02_EVIDENCE || '.playwright-mcp/purchase-edit-feedback');
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ headless: true });
const results = [];
try {
  for (const mode of ['http500', 'businessFailure', 'notDraft', 'normal']) {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const responses = [], errors = [], mutations = [];
    await ctx.addInitScript(() => {
      localStorage.setItem('cretas_user', JSON.stringify({ id: 1, username: 'local-test', userType: 'factory', factoryUser: { factoryId: 'LOCAL', role: 'factory_super_admin', factoryType: 'FACTORY' } }));
      window.__messages = [];
      const record = (kind, node) => {
        if (node.nodeType !== 1) return;
        for (const el of [...(node.matches('.el-message') ? [node] : []), ...node.querySelectorAll('.el-message')]) window.__messages.push({ kind, time: performance.now(), text: el.textContent });
      };
      new MutationObserver(ms => { for (const m of ms) { m.addedNodes.forEach(n => record('add', n)); m.removedNodes.forEach(n => record('remove', n)); } }).observe(document, { childList: true, subtree: true });
    });
    await ctx.route('**/*', async route => {
      const req = route.request(), url = new URL(req.url()), p = url.pathname;
      if (url.origin !== base) return route.abort();
      if (!p.startsWith('/api/')) return route.continue();
      if (!['GET', 'HEAD'].includes(req.method()) && !p.includes('/list-summary/') && !p.endsWith('/attachments/batch-3chip-counts')) {
        mutations.push({ path: p, method: req.method() }); return route.abort();
      }
      let data = [];
      if (p.endsWith('/role-permissions')) data = ['procurement','sales','warehouse'].map(moduleCode => ({ roleCode: 'factory_super_admin', moduleCode, permissionLevel: 'rw' }));
      if (p.includes('role-module-override')) data = {};
      if (p.endsWith('/purchase/orders')) data = { content: [], totalElements: 0 };
      if (p.endsWith('/purchase/orders/PO1')) {
        if (mode === 'http500' || mode === 'businessFailure') return route.fulfill({ status: mode === 'http500' ? 500 : 200, json: { success: false, data: null, message: `采购编辑失败-${mode}` } });
        data = { id: 'PO1', orderNumber: 'PO-LOCAL-1', status: mode === 'notDraft' ? 'APPROVED' : 'DRAFT', version: 1, supplierId: '', items: [1,2].map(i => ({ materialTypeId: `M${i}`, quantity: i * 10, unit: 'kg', quantityUnit: 'kg', priceUnit: 'kg', unitPrice: 20, taxRate: 13 })) };
      }
      await route.fulfill({ json: { success: true, data } });
    });
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(String(e)));
    page.on('response', r => { if (r.url().includes('/api/')) responses.push({ url: r.url(), status: r.status() }); });
    let failure;
    try {
      await page.goto(`${base}/procurement/orders?edit=PO1`);
      if (mode === 'normal') {
        const dialog = page.locator('.el-dialog:visible'); await expect(dialog).toBeVisible();
        const rows = dialog.locator('.item-block'); await expect(rows).toHaveCount(2);
        const quantity = rows.first().getByRole('spinbutton').first(); await quantity.fill('12'); await quantity.press('Tab');
        await dialog.getByRole('button', { name: '+ 添加行', exact: true }).click(); await expect(rows).toHaveCount(3); await expect(quantity).toHaveValue('12.000');
      } else {
        await expect(page).toHaveURL(`${base}/procurement/orders`);
        const message = page.locator('.el-message').filter({ hasText: mode === 'notDraft' ? '仅草稿采购单允许编辑' : `采购编辑失败-${mode}` }).last();
        await expect(message).toBeVisible(); await page.waitForTimeout(4500); await expect(message).toBeVisible();
        await expect(page.locator('.el-message')).toHaveCount(1);
        await expect(page.locator('.el-dialog:visible')).toHaveCount(0);
        await page.screenshot({ path: resolve(out, `${mode}-sticky.png`) });
        await message.locator('.el-message__closeBtn').click(); await expect(message).toHaveCount(0);
      }
      expect(errors).toEqual([]); expect(mutations).toEqual([]);
    } catch (e) { failure = String(e); }
    await page.screenshot({ path: resolve(out, `${mode}-final.png`) });
    results.push({ mode, pass: !failure, failure, responses, errors, mutations, url: page.url(), messages: await page.evaluate(() => window.__messages) });
    await ctx.close();
  }
} finally { await browser.close(); writeFileSync(resolve(out, 'result.json'), JSON.stringify(results, null, 2)); }
if (results.some(r => !r.pass)) throw new Error('Purchase edit feedback regression failed; see result.json');
