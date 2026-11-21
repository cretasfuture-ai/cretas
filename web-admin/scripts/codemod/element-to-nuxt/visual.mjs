import { chromium } from 'playwright';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { parseEnv } from 'node:util';

const [, , before, after, output, credentialFile] = process.argv;
if (!before || !after || !output || !credentialFile) {
  throw new Error('用法 node visual.mjs <基线 localhost> <试点 localhost> <截图目录> <本地 .env.test>');
}
const origins = [new URL(before), new URL(after)];
if (origins.some(url => url.hostname !== '127.0.0.1' || url.protocol !== 'http:' || url.pathname !== '/' || url.search || url.hash || url.username || url.password) || origins[0].origin === origins[1].origin) {
  throw new Error('需要两个不同的本地 Vite origin');
}
const target = 'LSMDEMO';
const env = parseEnv(await readFile(credentialFile, 'utf8'));
const username = env.TEST_F006_ADMIN_USER?.replace(/f006/i, 'lsmdemo');
const password = env.TEST_F006_ADMIN_PASS;
if (!username?.includes('lsmdemo') || !password) throw new Error('缺少演示厂登录配置');
const directory = resolve(output);
await mkdir(directory, { recursive: true });
const pilot = JSON.parse(await readFile(new URL('./pilot.json', import.meta.url), 'utf8'));
const viewport = { width: 1440, height: 1000 };
const tolerance = 24;
const threshold = 0.12;
const report = { viewport, tolerance, threshold, tenant: target, actualBusinessWrites: 0, blocked: [], logins: [], rows: [] };
const browser = await chromium.launch({ headless: false, args: ['--lang=zh-CN'] });
const context = await browser.newContext({ viewport, locale: 'zh-CN', deviceScaleFactor: 1, serviceWorkers: 'block' });
const pages = await Promise.all(origins.map(() => context.newPage()));
const loginOpen = new Set();
const approved = new Set();
const seen = [[], []];

async function guard(route) {
  const request = route.request();
  const url = new URL(request.url());
  const method = request.method();
  const local = origins.some(origin => origin.origin === url.origin);
  if (method === 'GET' && local) return route.continue();
  const auth = method === 'POST' && local && url.pathname === '/api/mobile/auth/unified-login' && loginOpen.has(url.origin);
  if (auth) {
    const payload = request.postDataJSON();
    if (payload?.username === username && payload?.password === password
      && (!payload.factoryId || payload.factoryId === target) && !approved.has(url.origin)) {
      approved.add(url.origin);
      return route.continue();
    }
  }
  report.blocked.push({ method, local, path: url.pathname });
  return route.abort('blockedbyclient');
}
await context.routeWebSocket('**/*', socket => {
  const url = new URL(socket.url());
  if (url.protocol === 'ws:' && origins.some(origin => origin.host === url.host) && url.pathname === '/') socket.connectToServer();
  else socket.close();
});
for (const [index, page] of pages.entries()) {
  await page.route('**/*', guard);
  page.on('pageerror', error => seen[index].push({ kind: 'js', reason: error.name }));
  page.on('response', response => {
    const url = new URL(response.url());
    if (/^\/(api|smartbi-api)\//.test(url.pathname)) seen[index].push({ kind: 'api', path: url.pathname, status: response.status(), method: response.request().method() });
  });
}

async function login(page, origin, lane) {
  loginOpen.add(origin.origin);
  try {
    await page.goto(new URL('/login', origin).href);
    await page.locator('.login-form input:not([type=password])').first().fill(username);
    await page.locator('.login-form input[type=password]').fill(password);
    const response = page.waitForResponse(reply => new URL(reply.url()).pathname === '/api/mobile/auth/unified-login' && reply.request().method() === 'POST');
    await page.locator('.login-button').click();
    const reply = await response;
    const { data, success } = await reply.json();
    const factory = data?.factoryId ?? data?.factoryUser?.factoryId ?? data?.user?.factoryId;
    const verified = reply.ok() && success === true && factory === target;
    report.logins.push({ lane, http: reply.status(), verified });
    if (!verified) throw new Error('演示账号登录响应未证明目标租户');
    await page.locator('main.app-content').waitFor({ timeout: 20000 });
  } finally {
    loginOpen.delete(origin.origin);
  }
}

async function screenshot(page, name) {
  return page.screenshot({ path: resolve(directory, `${name}.png`), animations: 'disabled', caret: 'hide',
    mask: [page.locator('input[type=password]')], timeout: 12000 });
}

async function compare(page, left, right) {
  return page.evaluate(async ({ a, b, tolerance }) => {
    const importImage = async base64 => createImageBitmap(await (await fetch(`data:image/png;base64,${base64}`)).blob());
    const images = await Promise.all([importImage(a), importImage(b)]);
    const [first, second] = images;
    if (first.width !== second.width || first.height !== second.height) throw new Error('截图尺寸不同');
    const canvas = new OffscreenCanvas(first.width, first.height);
    const ctx = canvas.getContext('2d');
    const data = images.map(image => {
      ctx.drawImage(image, 0, 0);
      return ctx.getImageData(0, 0, first.width, first.height).data;
    });
    let changed = 0;
    for (let pixel = 0; pixel < data[0].length; pixel += 4) {
      if (Math.max(...[0, 1, 2].map(channel => Math.abs(data[0][pixel + channel] - data[1][pixel + channel]))) > tolerance) changed++;
    }
    return { changed, pixels: first.width * first.height, ratio: changed / (first.width * first.height) };
  }, { a: left.toString('base64'), b: right.toString('base64'), tolerance });
}

async function capture(page, origin, row, lane, index) {
  const initial = seen[index].length;
  const name = row.route.split('/').filter(Boolean).join('-');
  const navigation = await page.goto(new URL(row.route, origin).href, { waitUntil: 'domcontentloaded', timeout: 45000 });
  const main = page.locator('main.app-content');
  await main.waitFor({ timeout: 20000 });
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.waitForLoadState('networkidle', { timeout: 9000 }).catch(() => {});
  const path = new URL(page.url()).pathname;
  const tableRows = await main.locator('tbody tr').count();
  const accessDenied = await main.getByText(/无权访问|权限不足|没有访问权限/).first().isVisible();
  const emptyHint = await main.getByText(/暂无数据|没有数据|无符合条件|暂无记录/).first().isVisible();
  const file = `${name}-${lane}`;
  const image = await screenshot(page, file);
  const details = { path, http: navigation?.status(), expectedRoute: path === row.route, tableRows, accessDenied, emptyHint, file: `${file}.png`, interaction: '未配置' };
  const interaction = row.interaction && main.getByRole('button', { name: row.interaction, exact: true });
  if (interaction && await interaction.first().isVisible() && await interaction.first().isEnabled()) {
    await interaction.first().click();
    const dialog = page.getByRole('dialog').filter({ visible: true }).last();
    if (await dialog.isVisible()) {
      const submit = dialog.getByRole('button', { name: /^(确定|创建|保存)$/ }).last();
      if (row.validateEmpty && await submit.isVisible() && await submit.isEnabled()) {
        // 这个交互专用于空白的创建表单，非 GET 请求继续由同一守卫拦截。
        await submit.click();
        const feedback = page.locator('.el-form-item__error, [data-slot=error], .el-message, .compat-message-toast').filter({ visible: true }).first();
        await feedback.waitFor({ timeout: 3500 }).catch(() => {});
        details.validationVisible = await feedback.isVisible();
      }
      await screenshot(page, `${file}-interaction`);
      details.interaction = `${file}-interaction.png`;
    } else details.interaction = '点击后没有可见弹窗';
  } else if (interaction) details.interaction = '控件不在当前角色视图中';
  details.requests = seen[index].slice(initial);
  return { details, image };
}

try {
  for (const [index, page] of pages.entries()) await login(page, origins[index], index ? 'after' : 'before');
  for (const row of pilot) {
    const result = { route: row.route };
    const captures = [];
    for (const [index, page] of pages.entries()) {
      const lane = index ? 'after' : 'before';
      try {
        const captureResult = await capture(page, origins[index], row, lane, index);
        result[lane] = captureResult.details;
        captures.push(captureResult.image);
      } catch (error) {
        result[lane] = { error: error.name, path: new URL(page.url()).pathname };
      }
    }
    if (captures.length === 2 && result.before.expectedRoute && result.after.expectedRoute && !result.before.accessDenied && !result.after.accessDenied) {
      result.pixels = await compare(pages[1], ...captures);
    }
    result.review = !result.pixels || result.pixels.ratio > threshold
      || [result.before, result.after].some(lane => lane.requests?.some(request => request.kind === 'js' || request.status >= 400));
    report.rows.push(result);
    await writeFile(resolve(directory, 'comparison.json'), JSON.stringify(report, null, 2) + '\n');
  }
} catch (error) {
  report.failure = error.name;
  process.exitCode = 2;
} finally {
  await browser.close();
  await writeFile(resolve(directory, 'comparison.json'), JSON.stringify(report, null, 2) + '\n');
}
if (report.blocked.some(record => record.method !== 'GET') || report.rows.some(row => !row.pixels)) process.exitCode ??= 1;
