/**
 * RN逐屏视觉走查 —— 只读。
 *
 * 做法：登录一个角色 → 用导航 ref 广度优先地走遍它**实际挂载得到**的每一条路由
 * → 每屏截一张全页图 + 存一份可读文本。
 *
 * ⛔ 全程不点击任何业务按钮，只做导航。截图是产物，判断由人来做。
 *
 * 为什么用运行时 BFS 而不是静态解析导航器源码：
 *   导航器里留着成块被注释掉的 <Stack.Screen>（连 import 一起注释）。静态解析
 *   会把它们数成活路由 —— 偏差方向恰好是**把已下线的屏说成还在**。运行中的
 *   navigator 自己报的 routeNames 没有这个问题。
 *
 * 判据（每次跑都写进 summary.json，不写进注释里当承诺）：
 *   - 登录后必须读得到 auth store 里的 username/factoryId，否则整轮作废
 *   - 每屏记录导航后**实际停在哪条路径**；与目标不符的单独标出来
 *     （「跳过去了」和「跳过去但被弹回来了」在截图上长得一样）
 */
import { test } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  waitForApp,
  navRefReady,
  login,
  authSummary,
  rootState,
  collectRouteNames,
  navigateTo,
  currentPath,
  screenText,
  navAlive,
  type NavPath,
} from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const PASS = process.env.VSHOT_PASS ?? '123456';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out';
const SETTLE = Number(process.env.VSHOT_SETTLE ?? 2600);
const MAX_SCREENS = Number(process.env.VSHOT_MAX ?? 600);
const ROUND = process.env.VSHOT_ROUND ?? '1';
const MAX_SEGMENTS = Number(process.env.VSHOT_SEGMENTS ?? 6);

type Rec = {
  n: number;
  target: string;
  landed: string;
  file: string;
  bytes: number;
  textLen: number;
  navError?: string;
  consoleErrors: string[];
  redbox: boolean;
  crashedApp: boolean;
  shots: number;
};

test('walk every mounted route and screenshot it', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });

  let consoleBuf: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleBuf.push(m.text().slice(0, 300));
  });
  page.on('pageerror', (e) => consoleBuf.push('PAGEERROR: ' + e.message.slice(0, 300)));

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, PASS);

  const auth = await authSummary(page);
  // 阳性对照：没有它，一整轮登录页的截图和「跑完了」一模一样。
  if (!auth?.username || !auth?.isAuthenticated) {
    throw new Error('login did not take; the whole round would be screenshots of the login screen');
  }
  console.log('AUTH ' + JSON.stringify(auth));

  await navRefReady(page);
  await page.waitForTimeout(5000);

  const home = await currentPath(page);
  console.log('HOME PATH ' + home);

  // ---- BFS over navigation paths -------------------------------------------
  const seen = new Set<string>();
  const queue: NavPath[] = [];
  const records: Rec[] = [];

  const enqueueFrom = async () => {
    const st = await rootState(page);
    const nav = collectRouteNames(st);
    for (const [prefix, routes] of nav) {
      const base = prefix ? prefix.split('/') : [];
      for (const r of routes) {
        const p = [...base, r];
        const key = p.join('/');
        if (!seen.has(key)) {
          seen.add(key);
          queue.push(p);
        }
      }
    }
  };

  await enqueueFrom();

  let n = 0;
  while (queue.length && n < MAX_SCREENS) {
    const target = queue.shift() as NavPath;
    const key = target.join('/');
    n += 1;
    consoleBuf = [];

    const res = await navigateTo(page, target);
    await page.waitForTimeout(SETTLE);

    const landed = await currentPath(page);
    const safe = key.replace(/[^\w.-]+/g, '_');
    const file = `${String(n).padStart(3, '0')}__${safe}.png`;
    let bytes = 0;
    let shots = 0;
    try {
      // Shot 1 is what the user sees on arrival -- keep it separate, because
      // "the number that matters is below the fold" is itself a finding.
      await page.screenshot({ path: path.join(OUT, file) });
      bytes = fs.statSync(path.join(OUT, file)).size;
      shots = 1;
      // Scroll the way a person does and stop when the picture stops changing.
      //
      // The first version set `scrollTop` on the largest overflow:auto div and
      // trusted it. It produced five byte-identical copies of the same screen --
      // it had picked a container that scrolls without moving anything visible.
      // Comparing consecutive shots makes "it did not scroll" impossible to
      // mistake for "there was nothing below".
      let prev = fs.readFileSync(path.join(OUT, file));
      await page.mouse.move(206, 500);
      for (let s = 1; s < MAX_SEGMENTS; s++) {
        await page.mouse.wheel(0, 760);
        await page.waitForTimeout(750);
        const seg = `${String(n).padStart(3, '0')}__${safe}__s${s + 1}.png`;
        const segPath = path.join(OUT, seg);
        await page.screenshot({ path: segPath });
        const cur = fs.readFileSync(segPath);
        if (cur.equals(prev)) {
          fs.unlinkSync(segPath); // bottom reached (or nothing scrolls here)
          break;
        }
        prev = cur;
        shots += 1;
      }
      await page.mouse.wheel(0, -760 * MAX_SEGMENTS);
      await page.waitForTimeout(300);
    } catch (e: any) {
      consoleBuf.push('SCREENSHOT_FAIL: ' + String(e?.message ?? e));
    }

    let text = '';
    let rawText = '';
    try {
      const st = await screenText(page);
      text = st.visible;
      rawText = st.raw;
    } catch {
      /* page may be mid-navigation */
    }
    const redbox = /Unhandled JS Exception|Render Error|componentStack/i.test(text);

    // A crash here blanks the page and every later screenshot becomes the same
    // white rectangle. Detect it, record it against the screen that caused it,
    // and rebuild the session before continuing.
    const alive = await navAlive(page);
    records.push({
      n,
      target: key,
      landed,
      file,
      bytes,
      textLen: text.length,
      navError: res.ok ? undefined : res.error,
      consoleErrors: consoleBuf.slice(0, 6),
      redbox,
      crashedApp: !alive,
      shots,
    });
    fs.appendFileSync(
      path.join(OUT, `text-round${ROUND}.jsonl`),
      JSON.stringify({ n, target: key, landed, text, rawLen: rawText.length }) + '\n',
      'utf8',
    );

    console.log(
      `${String(n).padStart(3, '0')} ${key}` +
        (landed === key ? '' : `   [landed:${landed}]`) +
        (res.ok ? '' : `   [NAVERR:${res.error}]`) +
        (redbox ? '   [REDBOX]' : '') +
        (alive ? '' : '   [APP CRASHED -> reloading]'),
    );

    if (!alive) {
      await page.goto(APP, { waitUntil: 'domcontentloaded' });
      await waitForApp(page);
      const stillIn = await authSummary(page);
      if (!stillIn?.isAuthenticated) {
        await login(page, USER, PASS);
      }
      await navRefReady(page);
      await page.waitForTimeout(5000);
    }

    // Newly mounted navigators only become visible after we land on them.
    await enqueueFrom();
  }

  fs.writeFileSync(
    path.join(OUT, `summary-round${ROUND}.json`),
    JSON.stringify({ auth, home, total: records.length, queueLeft: queue.length, records }, null, 2),
    'utf8',
  );
  console.log(`DONE screens=${records.length} queueLeft=${queue.length}`);
});
