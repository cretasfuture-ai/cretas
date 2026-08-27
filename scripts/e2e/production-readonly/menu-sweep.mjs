#!/usr/bin/env node
/**
 * 菜单巡检（L1 全局视觉验收） —— 把「用户点得到的每一屏」逐个打开、看一眼、留证据。
 *
 * ## 它回答什么
 *
 * 「所有 UI 走得通吗」——**逐屏**，不是抽样。判据对每一屏都是同一组：
 * 应用挂载了吗 / 正文有内容吗 / 有没有 console 错误 / 有没有 >=400 的请求 /
 * 有没有把用户踢回登录页。
 *
 * ## ⛔ 为什么不手点
 *
 * 2026-08-27 实测：一条生产链的两个步骤，手动驱动浏览器花了约 25 次交互。
 * 菜单有 100+ 个入口 ⇒ 手点一轮要几百次，而且**是一次性的**：今天点绿了，
 * 明天谁改了代码没人知道。巡检必须是**可重复**的，否则它不是验收，是表演。
 *
 * ## 零写入
 *
 * 复用 `core/mutation-guard.js`：非白名单的 POST/PUT/PATCH/DELETE 在**发出之前**被拦截。
 * ⚠️ 被拦截本身就是失败信号（说明有屏在**加载时就想写数据**），会逐条报出来。
 *
 * ## 覆盖面就是「用户走得到的」
 *
 * 路由来自 `web-admin/src/components/layout/menuConfig.ts` —— 也就是侧边栏。
 * ⚠️ 这**故意**不包含那 83 条孤儿路由（不在菜单、无跳转，用户走不到）。
 * 直接敲 URL 去测它们，恰好会**免疫「入口缺失」这一类缺陷** —— 本仓踩过：
 * 5 条孤儿路由的 E2E 是绿的，而没有任何用户能走到那些页面。
 *
 * ## ⚠️ 它量的是【生产】，而生产可能落后 main
 *
 * 2026-08-27 实测：生产 web-admin 资产 mtime 是 `08-27 16:42(+8)`，而修掉双 `/api/mobile`
 * 前缀的 #255 是 `08-28 03:29(+8)` ⇒ 巡检看到的那个 403 是**旧包**，代码早修好了。
 *
 * ⇒ **拿本工具的结果报缺陷之前，逐条对 `origin/main` 核一遍**，否则会报一个已经修好的东西。
 *
 * ## 用法
 *
 * ```bash
 * E2E_USERNAME=... E2E_PASSWORD=... \
 * node scripts/e2e/production-readonly/menu-sweep.mjs \
 *   --base-url https://admin.cretaceousfuture.com [--limit 20] [--headed]
 * ```
 */
import path from 'node:path';
import fs from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { establishCleanSession } = require('./core/clean-session.js');
const { performUiLogin } = require('./core/ui-login.js');
const { installMutationGuard } = require('./core/mutation-guard.js');
const { installNetworkRecorder } = require('./core/network-recorder.js');
const { installConsoleRecorder } = require('./core/console-recorder.js');
const { createScreenshotWriter } = require('./core/screenshot.js');
const { waitForAppMount } = require('./core/app-mount.js');
const { isSafeEvidenceDirectory } = require('./core/evidence-writer.js');

const MENU_CONFIG = 'web-admin/src/components/layout/menuConfig.ts';

function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--headed') options.headed = true;
    else if (a === '--base-url') options.baseUrl = argv[++i];
    else if (a === '--evidence-dir') options.evidenceDir = argv[++i];
    else if (a === '--limit') options.limit = Number(argv[++i]);
    else if (a === '--only') options.only = String(argv[++i] || '');
    else throw new Error(`Unknown argument: ${a}`);
  }
  return options;
}

/**
 * 从菜单配置里抽出 (path, title)。
 *
 * ⚠️ 用**文本抽取**而不是执行那个 TS 模块：它 import 了 Vue 侧的类型与角色常量，
 * 在 node 里跑不起来。抽取判据写死成配置里那一种写法，若将来格式变了，
 * 下面的「抽到 0 条就 exit 2」会当场喊出来，⛔ 不会静默跑一个空巡检。
 */
function readMenuRoutes(repoRoot) {
  const file = path.join(repoRoot, MENU_CONFIG);
  const source = fs.readFileSync(file, 'utf8');
  const seen = new Map();
  const re = /path:\s*'([^']+)'\s*,\s*title:\s*'([^']+)'/g;
  let m;
  while ((m = re.exec(source))) {
    const [, routePath, title] = m;
    if (!routePath.startsWith('/')) continue;
    if (!seen.has(routePath)) seen.set(routePath, title);
  }
  return [...seen.entries()].map(([routePath, title]) => ({ path: routePath, title }));
}

async function loadChromium() {
  for (const name of ['playwright', '@playwright/test']) {
    try {
      const mod = await import(name);
      if (mod.chromium) return mod.chromium;
    } catch {}
  }
  throw new Error('Project Playwright runtime is unavailable. Run npm ci at the repository root.');
}

function classify(entry) {
  if (entry.error) return { verdict: 'TOOL_ERROR', reason: entry.error.slice(0, 120) };
  if (entry.blockedMutations.length) {
    return { verdict: 'WRITE_ON_LOAD', reason: `加载时尝试写入 ${entry.blockedMutations.length} 次` };
  }
  if (entry.finalPath.startsWith('/login')) return { verdict: 'KICKED_TO_LOGIN', reason: '被踢回登录页' };
  if (!entry.appMounted) return { verdict: 'NOT_MOUNTED', reason: `${entry.appMountMs}ms 内应用没挂载` };
  // 🔴 403 必须与「空白」分开。2026-08-27 实测: 首轮把 46 屏报成 BLANK, 逐张看截图才发现
  //    它们是**规规矩矩的 403「访问被拒绝」页** —— f006_admin(factory_super_admin) 访问
  //    平台管理员 / 餐饮租户的路由, 本来就该拒。403 页正文只有 36 字符, 低于长度阈值,
  //    于是被我自己的分类器判成了「空白」。
  //    ⇒ 差一点把「RBAC 正常工作」报成「46 屏坏了」。它是**正向证据**, 不是缺陷。
  if (entry.forbidden) return { verdict: 'FORBIDDEN', reason: '403 访问被拒绝（本租户/角色越权，预期行为）' };
  // ⚠️ 同一个教训第二次: 404 页正文也只有 40 字符, 也会被长度阈值判成「空白」。
  //    「菜单里配了这条路由, 打开却是 404」是一类**独立**的问题(多为父级路由没有 index),
  //    与「页面渲染不出东西」下一步动作不同 ⇒ 单独成档。
  if (entry.notFound) return { verdict: 'NOT_FOUND', reason: '404 页面不存在（菜单配了这条路由，但它没有页面）' };
  if (entry.bodyTextLength <= 40) return { verdict: 'BLANK', reason: `正文只有 ${entry.bodyTextLength} 字符` };
  if (entry.httpErrors.length) return { verdict: 'HTTP_ERROR', reason: `${entry.httpErrors.length} 个 >=400 响应` };
  if (entry.consoleErrors.length) return { verdict: 'CONSOLE_ERROR', reason: `${entry.consoleErrors.length} 条 console 错误` };
  return { verdict: 'OK', reason: '' };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const baseUrl = args.baseUrl || process.env.E2E_ADMIN_URL;
  if (!baseUrl) throw new Error('--base-url or E2E_ADMIN_URL is required');
  const username = process.env.E2E_USERNAME;
  const password = process.env.E2E_PASSWORD;
  if (!username || !password) {
    throw new Error('E2E_USERNAME / E2E_PASSWORD are required; credentials must come from a gitignored environment source');
  }
  const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..', '..', '..');

  let routes = readMenuRoutes(repoRoot);
  // 🔴 抽到 0 条 = 仪器坏了, ⛔ 不许当成「没有屏要测」。
  if (!routes.length) {
    console.error(`[menu-sweep] 从 ${MENU_CONFIG} 一条路由都没抽到 —— 配置格式可能变了。本次读数作废。`);
    process.exitCode = 2;
    return;
  }
  if (args.only) routes = routes.filter((r) => r.path.includes(args.only) || r.title.includes(args.only));
  if (args.limit) routes = routes.slice(0, args.limit);
  // 🔴 过滤之后【也】要拦 0 —— 2026-08-27 实测本工具自己踩过:
  //    `--only /workdesk` 在 Git Bash 下被 MSYS 路径转换吃成了 Windows 路径, 匹配到 0 条,
  //    于是巡检【一个样本都没跑, 退出码却是 0】。那正是本仓反复记过的
  //    「没量到被折叠进没问题」。⛔ 上面那道 0 检查在过滤【之前】, 拦不住这一种。
  //    ⚠️ Git Bash 下传以 `/` 开头的过滤词要加 MSYS_NO_PATHCONV=1。
  if (!routes.length) {
    console.error(`[menu-sweep] 过滤之后一条路由都不剩（--only=${args.only ?? ''} --limit=${args.limit ?? ''}）—— 本次没有量到任何东西。`);
    process.exitCode = 2;
    return;
  }

  const runId = new Date().toISOString().replace(/[:.]/g, '-');
  const evidenceDir = path.resolve(args.evidenceDir || path.join('.playwright-mcp', 'menu-sweep', runId));
  if (!isSafeEvidenceDirectory(evidenceDir)) throw new Error(`Unsafe evidence directory: ${evidenceDir}`);
  fs.mkdirSync(evidenceDir, { recursive: true });

  const chromium = await loadChromium();
  const browser = await chromium.launch({ headless: !args.headed });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const scenarioRef = { value: 'menu-sweep' };
  const results = [];

  try {
    const mutationGuard = await installMutationGuard(context, { scenarioRef });
    const network = installNetworkRecorder(context, {});
    const consoleRecorder = installConsoleRecorder(context, {});
    const screenshot = createScreenshotWriter(page, evidenceDir, {});

    await establishCleanSession(page, baseUrl);
    const login = await performUiLogin(page, {
      baseUrl,
      username,
      password,
      expectedUsername: process.env.E2E_EXPECTED_USERNAME || 'f006_admin',
      expectedFactoryId: process.env.E2E_FACTORY_ID || 'F006',
    });
    console.error(`[menu-sweep] tenant=${login.factoryId} landing=${login.landingPath} mounted=${login.landingAppMounted} (${login.landingAppMountMs}ms) routes=${routes.length}`);

    for (const route of routes) {
      scenarioRef.value = route.path;
      const beforeNet = network.snapshot();
      const beforeConsole = consoleRecorder.snapshot();
      const beforeGuard = mutationGuard.snapshot();
      const entry = { path: route.path, title: route.title };
      const startedAt = Date.now();
      try {
        await page.goto(new URL(route.path, baseUrl).toString(), { waitUntil: 'domcontentloaded', timeout: 45_000 });
        const mount = await waitForAppMount(page);
        entry.appMounted = mount.appMounted;
        entry.appMountMs = mount.appMountMs;
        await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
        const body = await page.locator('body').innerText().catch(() => '');
        entry.bodyTextLength = body.trim().length;
        // ⚠️ 判 403 用页面自己的文案, ⛔ 不用正文长度 —— 长度只是它的一个副作用。
        entry.forbidden = /访问被拒绝|403/.test(body);
        entry.notFound = !entry.forbidden && /页面不存在|404/.test(body);
        entry.finalPath = new URL(page.url()).pathname;
        entry.screenshot = await screenshot(route.path);
      } catch (error) {
        entry.error = String(error?.message || error);
        entry.appMounted = false;
        entry.appMountMs = Date.now() - startedAt;
        entry.bodyTextLength = 0;
        entry.finalPath = entry.finalPath || route.path;
      }
      await network.flush();
      const afterNet = network.snapshot();
      const afterConsole = consoleRecorder.snapshot();
      const afterGuard = mutationGuard.snapshot();
      entry.httpErrors = afterNet.httpErrors.slice(beforeNet.httpErrors.length);
      entry.consoleErrors = afterConsole.consoleErrors.slice(beforeConsole.consoleErrors.length)
        .concat(afterConsole.pageErrors.slice(beforeConsole.pageErrors.length));
      entry.blockedMutations = afterGuard.blockedMutationAttempts.slice(beforeGuard.blockedMutationAttempts.length);
      entry.actualBusinessWrites = afterGuard.actualBusinessWrites - beforeGuard.actualBusinessWrites;
      entry.durationMs = Date.now() - startedAt;
      Object.assign(entry, classify(entry));
      results.push(entry);
      console.error(`[menu-sweep] ${entry.verdict.padEnd(15)} ${route.path}  ${route.title}  ${entry.reason}`);
    }

    const counts = results.reduce((acc, r) => { acc[r.verdict] = (acc[r.verdict] || 0) + 1; return acc; }, {});
    const totalWrites = results.reduce((acc, r) => acc + (r.actualBusinessWrites || 0), 0);
    const report = { runId, baseUrl, tenant: login.factoryId, routes: results.length, counts, actualBusinessWrites: totalWrites, evidenceDir, results };
    fs.writeFileSync(path.join(evidenceDir, 'menu-sweep.json'), JSON.stringify(report, null, 2), 'utf8');
    const md = [
      '# 菜单巡检（L1 全局视觉验收）',
      '',
      `- Run: ${runId}`,
      `- Base URL: ${baseUrl}`,
      `- 租户: ${login.factoryId}`,
      `- 屏数: ${results.length}`,
      `- 实际业务写入: ${totalWrites}`,
      '',
      '| 判定 | 条数 |',
      '|---|---:|',
      ...Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([k, v]) => `| ${k} | ${v} |`),
      '',
      '| 路由 | 标题 | 判定 | 说明 | 挂载(ms) | 正文 |',
      '|---|---|---|---|---:|---:|',
      ...results.map((r) => `| \`${r.path}\` | ${r.title} | ${r.verdict} | ${r.reason} | ${r.appMountMs ?? ''} | ${r.bodyTextLength ?? ''} |`),
    ].join('\n');
    fs.writeFileSync(path.join(evidenceDir, 'menu-sweep.md'), md, 'utf8');
    console.log(JSON.stringify({ runId, evidenceDir, counts, actualBusinessWrites: totalWrites }, null, 2));

    // ⚠️ 只要有一屏不是 OK 就非零退出 —— ⛔ 不把「有问题」和「没问题」折叠成同一个退出码。
    // ⚠️ FORBIDDEN 不算失败 —— 它是 RBAC 在正常工作的读数。
    if (totalWrites > 0 || results.some((r) => !['OK', 'FORBIDDEN'].includes(r.verdict))) process.exitCode = 1;
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch((error) => {
  console.error(`[menu-sweep] ${error.message}`);
  process.exitCode = 2;
});
