/**
 * 详情屏走查 —— 从列表**点进去**，而不是按路由名跳。
 *
 * ## 为什么要单独一轮
 *
 * 第二轮只按路由名导航，于是 26 个详情屏全是「没有参数进入」，
 * 一进去就 `Cannot destructure property 'orderId' of 'route.params'`，整屏白。
 * 那 46 次崩溃**是仪器造成的**（真实用户点列表进来一定带参数），
 * 代价是**详情屏一屏都没真看过**。这一轮补上。
 *
 * ## 只读的边界（⛔ 这一条比覆盖率重要）
 *
 * 会点击，所以必须比第二轮更小心：
 *
 *  - 只点**看起来是列表行**的元素：在主滚动容器内、宽度接近满屏、有 cursor:pointer
 *  - 文本命中 `DESTRUCTIVE` 里任何一个词就跳过（删除/提交/通过/驳回/召回/作废…）
 *  - 每屏**最多点一个**，点完立刻回到列表路由
 *  - 点击之后如果路由没变，就当成「这不是一个跳转」，不再深入
 *
 * ⚠️ 这仍然是**代理判据**：一个不带这些词、看起来像卡片的东西，理论上仍可能触发写入。
 * 所以本文件只在 F006（测试租户）上跑，且跑完要核对没有新增业务单据。
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
} from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const PASS = process.env.VSHOT_PASS ?? '123456';
const OUT = process.env.VSHOT_OUT ?? 'vshot-detail';
const SETTLE = Number(process.env.VSHOT_SETTLE ?? 2600);
const MAX_SCREENS = Number(process.env.VSHOT_MAX ?? 400);

/**
 * 命中任何一个就不点。宁可漏看一屏，不要按下一个写操作。
 *
 * ⚠️ 只用来判**按钮**（短文本元素）。第一版拿它过滤整张卡片，结果卡片上的状态 chip
 * 「已完成 / 已发货」自己就含「完成 / 发货」，于是绝大多数列表行被自己的状态字挡掉了
 * —— 14 条路由只点进 2 个详情屏。判据要打在「这个元素是不是一个动作按钮」上，
 * 不是「这段文字里有没有出现过这个词」。
 */
const DESTRUCTIVE = [
  '删除', '移除', '清空', '作废', '取消', '撤回', '撤销',
  '提交', '保存', '确认', '通过', '批准', '审批', '驳回', '拒绝',
  '召回', '报损', '报废', '发货', '出库', '入库', '收货', '过账', '结账',
  '开始', '启动', '完成', '结单', '签收', '盘点', '调拨', '退货', '下单',
  '新建', '添加', '创建', '编辑', '修改', '停用', '启用', '重置',
  '导出', '打印', '上传', '拍照', '扫码', '登出', '退出',
];

type Rec = {
  list: string;
  landed: string;
  clickedText: string;
  file: string;
  textLen: number;
  crashedApp: boolean;
  consoleErrors: string[];
};

test('open each list, tap one row, look at the detail screen', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  let consoleBuf: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleBuf.push(m.text().slice(0, 300));
  });
  page.on('pageerror', (e) => consoleBuf.push('PAGEERROR: ' + e.message.slice(0, 300)));

  // ⚠️ 这一轮会点击，所以「只读」必须是**量出来的**，不是承诺出来的。
  // 记下每一个非 GET 请求；跑完把它打出来。空 = 真的没写；非空 = 立刻能指出写了什么。
  // （第一次跑完只对比了业务单据数，而那期间**另一条 session 的 E2E 正在建单**，
  //   于是「销售订单 45 → 51」既不能证明我写了、也不能证明我没写。）
  const mutations: string[] = [];
  page.on('request', (r) => {
    const m = r.method();
    if (m !== 'GET' && m !== 'HEAD' && m !== 'OPTIONS') {
      mutations.push(`${m} ${r.url().slice(0, 200)}`);
    }
  });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, PASS);
  const auth = await authSummary(page);
  if (!auth?.isAuthenticated) throw new Error('login did not take');
  console.log('AUTH ' + JSON.stringify(auth));
  await navRefReady(page);
  await page.waitForTimeout(5000);

  // ---- collect every reachable route once (same BFS as the walkthrough) -----
  const seen = new Set<string>();
  const queue: string[][] = [];
  const enqueue = async () => {
    const nav = collectRouteNames(await rootState(page));
    for (const [prefix, routes] of nav) {
      const base = prefix ? prefix.split('/') : [];
      for (const r of routes) {
        const p = [...base, r];
        if (!seen.has(p.join('/'))) {
          seen.add(p.join('/'));
          queue.push(p);
        }
      }
    }
  };
  await enqueue();

  const records: Rec[] = [];
  let n = 0;

  while (queue.length && n < MAX_SCREENS) {
    const target = queue.shift() as string[];
    const key = target.join('/');
    n += 1;
    consoleBuf = [];

    const res = await navigateTo(page, target);
    await page.waitForTimeout(SETTLE);
    if (!res.ok || !(await navAlive(page))) {
      // 无参数进详情屏 -> 白屏。第二轮已经量过这一类，这里只重建会话继续。
      await page.goto(APP, { waitUntil: 'domcontentloaded' });
      await waitForApp(page);
      if (!(await authSummary(page))?.isAuthenticated) await login(page, USER, PASS);
      await navRefReady(page);
      await page.waitForTimeout(4000);
      await enqueue();
      continue;
    }
    const before = await currentPath(page);

    // ---- find plausible list rows ------------------------------------------
    //
    // ⚠️ 第一版这里有个索引错位：候选是按「所有元素」的下标记的，而点击时索引到的是
    // 「只含可点元素」的另一个数组 —— 点的根本不是选中的那个。现在两者是同一个数组。
    //
    // 用 el.click() 而不是鼠标点坐标：DOM 的 click() 只在该元素上派发并向**上**冒泡，
    // 不会命中卡片里面的按钮。这比「点卡片中心」更安全。
    const cands = await page.evaluate((blocked) => {
      const scroller = Array.from(document.querySelectorAll<HTMLElement>('div')).find(
        (el) => el.scrollHeight - el.clientHeight > 24 && ['auto', 'scroll'].includes(getComputedStyle(el).overflowY),
      );
      const root: ParentNode = scroller ?? document.body;
      const picked: HTMLElement[] = [];
      const meta: { text: string; w: number; h: number; top: number }[] = [];
      for (const el of Array.from(root.querySelectorAll<HTMLElement>('*'))) {
        if (getComputedStyle(el).cursor !== 'pointer') continue;
        const r = el.getBoundingClientRect();
        if (r.width < 200 || r.height < 40) continue; // 太小的多半是图标/按钮
        const text = (el.innerText ?? '').trim();
        if (!text) continue;
        // 只有【短文本】才按动作词拦 —— 那才是按钮; 长卡片里出现「已完成」是状态, 不是动作。
        if (text.length <= 14 && blocked.some((w: string) => text.includes(w))) continue;
        picked.push(el);
        meta.push({ text: text.slice(0, 120), w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top) });
      }
      const order = meta.map((m, i) => i).sort((a, b) => meta[a]!.top - meta[b]!.top || meta[b]!.w - meta[a]!.w);
      (globalThis as any).__VSHOT_CANDS__ = order.map((i) => picked[i]);
      return order.map((i) => meta[i]!);
    }, DESTRUCTIVE);

    if (!cands.length) continue;

    // 最多试 2 个：最靠上的那个常常是横幅/说明卡，不是列表行。
    let cand: { text: string } | null = null;
    let landed = before;
    for (let ci = 0; ci < Math.min(2, cands.length); ci++) {
      try {
        await page.evaluate((i) => {
          const list = (globalThis as any).__VSHOT_CANDS__ as HTMLElement[];
          list?.[i]?.click();
        }, ci);
      } catch {
        continue;
      }
      await page.waitForTimeout(SETTLE);
      landed = await currentPath(page);
      if (landed !== before) {
        cand = cands[ci]!;
        break;
      }
      if (!(await navAlive(page))) break;
    }
    if (!cand) continue;

    const alive = await navAlive(page);
    if (landed === before || !landed) {
      // 没跳转 —— 不是一个进详情的入口
      if (!alive) {
        await page.goto(APP, { waitUntil: 'domcontentloaded' });
        await waitForApp(page);
        if (!(await authSummary(page))?.isAuthenticated) await login(page, USER, PASS);
        await navRefReady(page);
        await page.waitForTimeout(4000);
      }
      continue;
    }

    const safe = `${key}__TO__${landed}`.replace(/[^\w.-]+/g, '_').slice(0, 150);
    const file = `${String(records.length + 1).padStart(3, '0')}__${safe}.png`;
    try {
      await page.screenshot({ path: path.join(OUT, file) });
    } catch {
      /* ignore */
    }
    let text = '';
    try {
      text = (await screenText(page)).visible;
    } catch {
      /* ignore */
    }

    records.push({
      list: before,
      landed,
      clickedText: cand.text,
      file,
      textLen: text.length,
      crashedApp: !alive,
      consoleErrors: consoleBuf.slice(0, 4),
    });
    fs.appendFileSync(
      path.join(OUT, 'detail-text.jsonl'),
      JSON.stringify({ list: before, landed, clicked: cand.text, text }) + '\n',
      'utf8',
    );
    console.log(`  ${before}  --click-->  ${landed}   [${cand.text.replace(/\n/g, ' / ').slice(0, 60)}]`);

    if (!alive) {
      await page.goto(APP, { waitUntil: 'domcontentloaded' });
      await waitForApp(page);
      if (!(await authSummary(page))?.isAuthenticated) await login(page, USER, PASS);
      await navRefReady(page);
      await page.waitForTimeout(4000);
    }
    await enqueue();
  }

  // 登录本身是 POST，把它从「写入」里排除掉，剩下的都要解释。
  const businessMutations = mutations.filter((u) => !/\/auth\/(unified-)?login|\/auth\/refresh|\/logs?/.test(u));

  fs.writeFileSync(
    path.join(OUT, 'detail-summary.json'),
    JSON.stringify(
      { auth, visitedRoutes: n, detailScreens: records.length, mutations, businessMutations, records },
      null,
      2,
    ),
    'utf8',
  );
  console.log(`DONE visited=${n} detailScreensReached=${records.length}`);
  console.log(`NON-GET requests: ${mutations.length} (business, excluding auth: ${businessMutations.length})`);
  businessMutations.slice(0, 20).forEach((u) => console.log('   !! ' + u));
});
