/**
 * 把整棵路由树走一遍, 数清楚「不带参数打开这一屏会不会把整个 App 打崩」。
 *
 * ⚠️ 量的不是「用户会这么走」—— 用户从列表点进去总是带 id 的。
 *    量的是**健壮性**: 一屏崩掉之后, 错误边界是兜住那一屏, 还是把整棵导航树端掉。
 *
 * 🔴 仪器上踩过的坑(写下来免得下次又栽):
 *    崩溃恢复要整页 reload, 而 reload 会把**已经挂载的 tab 全部清空** ——
 *    第一版因此只发现 12 条路由就收敛了, 看起来像「这棵树就这么大」。
 *    ⇒ 每次 reload 之后必须把已知容器重新挂一遍, 并且**每走一步就重新收集**,
 *      不能按「轮」收集。
 */
import { test } from '@playwright/test';
import { waitForApp, navRefReady, login, rootState, collectRouteNames, navigateTo } from './vshot-lib';
const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';

test('整棵树: 不带参数进屏会不会打崩 App', async ({ page }) => {
  test.setTimeout(30 * 60_000);
  const errs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 400)); });
  page.on('dialog', (d) => d.dismiss().catch(() => {}));

  const boot = async () => {
    await page.goto(APP, { waitUntil: 'domcontentloaded' });
    await waitForApp(page);
    // ⛔ 不要 catch 掉 —— 登录失败会让整个走查停在登录栈上,
    //    而那看起来就像「走完了, 0 崩」。实测踩过一次(清缓存后 @env 空了)。
    await login(page, USER, '123456');
    await navRefReady(page);
    await page.waitForTimeout(4500);
    const st = await rootState(page);
    const names = (st && st.routeNames) || [];
    if (names.includes('Login') || names.includes('EnhancedLogin')) {
      throw new Error('登录后仍在登录栈 —— 仪器坏了, 读数作废。routeNames=' + names.join(','));
    }
  };
  await boot();

  const containers: string[] = [];          // 已知的容器路由, reload 之后要重挂
  const seen = new Set<string>();
  const crashed: { route: string; err: string }[] = [];
  const ok: string[] = [];

  const remount = async () => {
    for (const c of containers) {
      await navigateTo(page, c.split('/')).catch(() => {});
      await page.waitForTimeout(500);
    }
  };
  const recover = async () => {
    const retry = page.getByTestId('app-error-boundary-retry');
    if ((await retry.count()) > 0) { await retry.click().catch(() => {}); await page.waitForTimeout(2000); }
    if ((await rootState(page)) === null) { await boot(); await remount(); }
  };

  const harvest = async (): Promise<string[][]> => {
    const m = collectRouteNames(await rootState(page));
    const fresh: string[][] = [];
    for (const [prefix, names] of m) {
      if (prefix && !containers.includes(prefix)) containers.push(prefix);
      for (const n of names as string[]) {
        const p = [...prefix.split('/').filter(Boolean), n];
        if (!seen.has(p.join('/'))) fresh.push(p);
      }
    }
    return fresh;
  };

  const queue: string[][] = await harvest();
  queue.forEach((p) => seen.add(p.join('/')));
  let n = 0;
  while (queue.length && n < 400) {
    const t = queue.shift() as string[];
    n += 1;
    const key = t.join('/');
    const mark = errs.length;
    await navigateTo(page, t).catch(() => {});
    await page.waitForTimeout(1100);
    const dead = (await rootState(page)) === null;
    const boundary = await page.getByTestId('app-error-boundary-retry').count();
    if (dead || boundary > 0) {
      const e = (errs.slice(mark).find((x) => /TypeError|Cannot|is not a function|undefined is not/.test(x))
        ?? errs.slice(mark)[0] ?? '(没抓到 console error)').replace(/\s+/g, ' ').slice(0, 200);
      crashed.push({ route: key, err: e });
      console.log(`☠️ ${key}\n   ${e}`);
      await recover();
    } else {
      ok.push(key);
      const fresh = await harvest();
      for (const p of fresh) { seen.add(p.join('/')); queue.push(p); }
      if (fresh.length) console.log(`   +${fresh.length} 新路由 (自 ${key})`);
    }
  }
  console.log(`\n走过 ${n} 条 | 正常 ${ok.length} | 崩 ${crashed.length} | 队列剩余 ${queue.length}`);
  crashed.forEach((c, i) => console.log(`${String(i + 1).padStart(2)}. ${c.route}\n    ${c.err}`));
  console.log('\n--- 正常进得去的:');
  ok.forEach((r) => console.log('  ' + r));
});
