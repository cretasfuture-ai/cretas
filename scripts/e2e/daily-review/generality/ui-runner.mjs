// Local-only fallback when Playwright MCP's file roots exclude this isolated worktree.
import fs from 'node:fs/promises';
const { chromium } = await import(new URL('../../../../web-admin/node_modules/playwright/index.mjs', import.meta.url));
const browser = await chromium.launch({ headless: false });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  await page.goto('http://127.0.0.1:31772/login');
  const source = await fs.readFile(new URL('./ui.js', import.meta.url), 'utf8');
  const run = (0, eval)('(' + source + ')');
  console.log(JSON.stringify(await run(page)));
  if (process.argv.includes('--keep')) {
    await page.unroute('**/api/**');
    page.removeAllListeners('pageerror'); page.removeAllListeners('response');
    console.log('LOCAL_EXPERIENCE_BROWSER_READY');
    await new Promise(resolve => browser.on('disconnected', resolve));
  }
} finally { if (browser.isConnected()) await browser.close(); }
