/**
 * Visual acceptance sweep v2.
 *
 * v1 was WRONG and its "280 clean" was largely vacuous: it used page.goto() per
 * route, which reloads and re-bootstraps the whole SPA. With a 2.2s settle most
 * pages were still on the "白垩纪 AI Agent / 正在加载应用…" splash — 289 shots
 * collapsed to 96 distinct images, one group of 22 identical. A page that never
 * rendered has no console errors and no failed API calls, so it scored "clean".
 *
 * v2:
 *   - navigates INSIDE the SPA (router.push), so the app boots exactly once
 *   - waits for the splash to be gone and for the view to settle
 *   - has a third state: UNMEASURED (still on splash / never settled).
 *     UNMEASURED is NOT clean. Folding "did not render" into "no problem" is the
 *     exact failure v1 made.
 *   - fingerprints rendered text per route and reports the distinct-image count,
 *     because that number is what exposed v1.
 */
const { chromium } = require('playwright');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const BASE = 'http://127.0.0.1:5173';
const OUT = process.env.OUT_DIR;
const USER = process.env.E2E_USER || 'f006_admin';
const PASS = '123456';
const SHOTS = path.join(OUT, 'v2-shots-' + USER);
fs.mkdirSync(SHOTS, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const SPLASH = '正在加载应用';

(async () => {
  const browser = await chromium.launch({ headless: false });
  const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 }, locale: 'zh-CN' });
  const page = await ctx.newPage();

  // ---- login (the only full page load in the whole run) ---------------------
  await page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 60000 });
  await page.fill('input[type="text"]', USER);
  await page.fill('input[type="password"]', PASS);
  await page.locator('button').first().click();
  await page.waitForFunction(() => !location.pathname.startsWith('/login'), null, { timeout: 60000 }).catch(() => {});
  await sleep(4000);
  if (page.url().includes('/login')) {
    console.log('FATAL::login failed for ' + USER);
    await page.screenshot({ path: path.join(SHOTS, 'LOGIN-FAILED.png') });
    await browser.close(); process.exit(1);
  }
  console.log('LOGIN_OK::' + page.url());

  const all = await page.evaluate(() => {
    const app = document.querySelector('#app').__vue_app__;
    return app.config.globalProperties.$router.getRoutes()
      .map(r => r.path).filter((v, i, a) => a.indexOf(v) === i).sort();
  });
  const paramd = all.filter(p => p.includes(':'));
  const visitable = all.filter(p => !p.includes(':'));
  console.log(`ROUTES::${all.length} VISITABLE::${visitable.length} PARAM_SKIPPED::${paramd.length}`);

  const results = [];
  let n = 0;
  for (const route of visitable) {
    n++;
    const errs = [], netFails = [], netAll = [];
    const onConsole = m => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); };
    const onResponse = r => {
      if (!r.url().includes('/api/')) return;
      netAll.push(r.status());
      if (r.status() >= 400) netFails.push(r.status() + ' ' + r.url().replace(BASE, '').slice(0, 130));
    };
    page.on('console', onConsole);
    page.on('response', onResponse);

    let landed = '', state = '', text = '', navErr = '';
    try {
      await page.evaluate(r => {
        const app = document.querySelector('#app').__vue_app__;
        return app.config.globalProperties.$router.push(r).catch(() => {});
      }, route);
      // settle: splash gone AND something rendered
      await page.waitForFunction(
        s => !(document.body.innerText || '').includes(s) && (document.body.innerText || '').length > 40,
        SPLASH, { timeout: 15000 }
      ).catch(() => {});
      await sleep(1600);
      landed = await page.evaluate(() => location.pathname + location.search);
      text = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').trim());
    } catch (e) { navErr = e.message.slice(0, 160); }

    page.off('console', onConsole);
    page.off('response', onResponse);

    const onSplash = text.includes(SPLASH) || text.length < 40;
    const p = landed.split('?')[0];
    // ORDER MATTERS. The 403 page's own text is ~34 chars ("抱歉，你没有权限…"),
    // so the length-based splash heuristic swallows it. v2 run #1 reported 62
    // UNMEASURED that were in fact correctly-rendered 403s. Terminal pages are
    // classified by WHERE WE LANDED first; the splash guess only runs after.
    if (navErr)              state = 'NAV_ERROR';
    else if (p === '/403')   state = 'FORBIDDEN';
    else if (p === '/404')   state = 'NOT_FOUND';
    else if (p === '/login') state = 'BOUNCED_LOGIN';
    else if (onSplash)       state = 'UNMEASURED';     // <- NOT clean
    else if (p !== route)  state = 'REDIRECT';
    else if (errs.length || netFails.length) state = 'ERRORS';
    else                   state = 'OK';

    const shot = String(n).padStart(3, '0') + '-' + route.replace(/[^a-zA-Z0-9]/g, '_').slice(0, 70) + '.png';
    try { await page.screenshot({ path: path.join(SHOTS, shot) }); } catch (e) {}

    results.push({ route, landed, state,
                   apiCalls: netAll.length, apiFailures: netFails.length, sampleApi: netFails[0] || '',
                   consoleErrors: errs.length, sampleError: errs[0] || '',
                   textLen: text.length, textHash: crypto.createHash('md5').update(text).digest('hex').slice(0, 10),
                   shot });
    if (n % 40 === 0) console.log(`  ...${n}/${visitable.length}`);
  }

  fs.writeFileSync(path.join(OUT, `v2-sweep-${USER}.json`),
    JSON.stringify({ user: USER, routes: all.length, visited: visitable.length,
                     parameterisedSkipped: paramd, results }, null, 1));

  const by = s => results.filter(r => r.state === s).length;
  const distinctText = new Set(results.map(r => r.textHash)).size;
  const zeroApi = results.filter(r => r.apiCalls === 0).length;

  console.log('SUMMARY::' + USER);
  console.log('  visited                : ' + results.length);
  console.log('  OK                     : ' + by('OK'));
  console.log('  UNMEASURED (splash)    : ' + by('UNMEASURED') + '   <- not counted as clean');
  console.log('  ERRORS                 : ' + by('ERRORS'));
  console.log('  FORBIDDEN /403         : ' + by('FORBIDDEN'));
  console.log('  NOT_FOUND /404         : ' + by('NOT_FOUND'));
  console.log('  BOUNCED_LOGIN          : ' + by('BOUNCED_LOGIN'));
  console.log('  REDIRECT               : ' + by('REDIRECT'));
  console.log('  NAV_ERROR              : ' + by('NAV_ERROR'));
  console.log('  PARAM ROUTES SKIPPED   : ' + paramd.length + ' (listed in json, not silently dropped)');
  console.log('INSTRUMENT_CHECKS::');
  console.log('  distinct rendered-text fingerprints : ' + distinctText + ' / ' + results.length +
              (distinctText < results.length * 0.5 ? '   <- SUSPICIOUS, pages may not be rendering' : ''));
  console.log('  routes that made ZERO api calls     : ' + zeroApi);

  await browser.close();
})().catch(e => { console.error('FATAL::' + e.message); process.exit(1); });
