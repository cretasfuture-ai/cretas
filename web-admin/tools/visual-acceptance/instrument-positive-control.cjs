/**
 * Positive control for the sweep's instrument.
 * "0 API failures" is only meaningful if the listener sees API traffic at all,
 * and if a KNOWN failure would actually be recorded.
 */
const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:5173';
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await (await browser.newContext({ viewport: { width: 1600, height: 1000 } })).newPage();

  await page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 60000 });
  await page.fill('input[type="text"]', 'f006_admin');
  await page.fill('input[type="password"]', '123456');
  await page.locator('button').first().click();
  await page.waitForFunction(() => !location.pathname.startsWith('/login'), null, { timeout: 60000 }).catch(()=>{});
  await sleep(3000);

  for (const route of ['/dashboard', '/smart-bi/dashboard', '/smart-bi/analysis', '/production/batches', '/warehouse/materials']) {
    const seen = [];
    const onResp = r => { if (r.url().includes('/api/')) seen.push(r.status()); };
    page.on('response', onResp);
    await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(()=>{});
    await sleep(3500);
    page.off('response', onResp);
    const bad = seen.filter(s => s >= 400);
    console.log(`${route}  apiCalls=${seen.length}  statuses=${JSON.stringify([...new Set(seen)])}  failures=${bad.length}`);
  }

  // --- the actual positive control: force a request that MUST fail -----------
  const forced = [];
  const onResp = r => { if (r.url().includes('/api/')) forced.push(r.status()); };
  page.on('response', onResp);
  await page.evaluate(async () => {
    try { await fetch('/api/mobile/THIS_ENDPOINT_DOES_NOT_EXIST_' + Date.now()); } catch (e) {}
  });
  await sleep(2000);
  page.off('response', onResp);
  const caught = forced.filter(s => s >= 400);
  console.log(`FORCED_FAILURE:: observed=${forced.length} statuses=${JSON.stringify(forced)} recordedAsFailure=${caught.length}`);
  console.log(caught.length > 0
    ? 'INSTRUMENT_OK:: a known-bad request WAS recorded — the sweep\'s zero is a real zero'
    : 'INSTRUMENT_DEAD:: a known-bad request was NOT recorded — the sweep\'s zero is meaningless');

  await browser.close();
})().catch(e => { console.error('FATAL::' + e.message); process.exit(1); });
