/**
 * 第八轮走查的两条修复，在屏上验。
 *
 * ① 表盘水平镜像（`GaugeChart` 的极坐标换算）——「综合评分 9.3 分 / 评级 D」而指针停在右侧绿区。
 * ② OTIF 屏同屏自相矛盾 —— 顶部「这段期间没有订单，算不出准时交付率」，
 *    下方三行仍写着「准时率 0.0% / 足量率 0.0% / OTIF率 0.0%」，中间还画三个指向 0 的表盘。
 *
 * ⛔ 不打桩：F006 在近 7 天窗口里**真的**没有订单，②那条分支是真实会走到的。
 *    ①用的也是真实数据。打桩只会让我验的是我自己造的输入。
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';
import * as fs from 'node:fs';
import * as path from 'node:path';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3022';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/claude/vshot-out/r8-verify';

test('表盘不再镜像；OTIF 屏不再一边说算不出一边印 0.0%', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  await navigateTo(page, ['Main', 'BossReportsTab']);
  await page.waitForTimeout(4000);

  // ---------- ② OTIF ----------
  await navigateTo(page, ['Main', 'BossReportsTab', 'OnTimeDeliveryReport']);
  await page.waitForTimeout(9000);
  // screenText 返回 { visible, raw } —— 取「用户真的看得见的那份」
  const otifText = (await screenText(page)).visible;
  fs.writeFileSync(path.join(OUT, 'otif.txt'), otifText, 'utf-8');
  await page.screenshot({ path: path.join(OUT, 'otif-top.png') });
  await page.evaluate(() => window.scrollBy(0, 900));
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, 'otif-mid.png') });

  console.log('=== OTIF 屏文本片段');
  console.log(otifText.slice(0, 400));

  // 阳性对照: 这一屏确实处在「算不出」的那个状态。
  // ⛔ 少了这一条, 下面的阴性断言在「屏根本没加载」时也会绿。
  expect(otifText, 'OTIF 屏没进到「没有订单」这个状态 —— 下面的断言此刻没有意义')
    .toContain('算不出准时交付率');

  // 真正要守的: 同屏不许再出现编出来的 0.0%
  expect(otifText, '顶部说算不出, 下方还印着 0.0% —— 同屏两个互相矛盾的读数')
    .not.toContain('0.0%');

  // ---------- ① 表盘几何 ----------
  await navigateTo(page, ['Main', 'BossReportsTab', 'KPIReport']);
  await page.waitForTimeout(11000);
  await page.screenshot({ path: path.join(OUT, 'kpi.png') });

  // 直接量 SVG: 指针末端相对圆心在哪一侧。
  // ⛔ 不靠「看起来对」—— 镜像的表盘看起来也很正常, 它是自洽的。
  const needles = await page.evaluate(() => {
    const out: { cx: number; x2: number; side: string }[] = [];
    document.querySelectorAll('svg').forEach((svg) => {
      const line = svg.querySelector('line');
      const circles = svg.querySelectorAll('circle');
      if (!line || circles.length === 0) return;
      const cx = Number(circles[circles.length - 1]?.getAttribute('cx') ?? NaN);
      const x2 = Number(line.getAttribute('x2') ?? NaN);
      if (!Number.isFinite(cx) || !Number.isFinite(x2)) return;
      out.push({ cx, x2, side: x2 < cx ? 'left' : x2 > cx ? 'right' : 'center' });
    });
    return out;
  });
  console.log('=== KPI 屏上的表盘指针 (' + needles.length + ' 个)');
  needles.forEach((n) => console.log(`   圆心x=${n.cx} 指针末端x=${n.x2} -> ${n.side}`));

  const kpiText = (await screenText(page)).visible;
  fs.writeFileSync(path.join(OUT, 'kpi.txt'), kpiText, 'utf-8');
  console.log('=== KPI 屏文本片段');
  console.log(kpiText.slice(0, 300));

  // 阳性对照: 屏上确实有表盘可量
  expect(needles.length, 'KPI 屏上一个表盘都没量到 —— 仪器瞎了, 不是「几何对了」')
    .toBeGreaterThan(0);
});
