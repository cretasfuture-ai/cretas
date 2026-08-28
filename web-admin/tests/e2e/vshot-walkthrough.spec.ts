import { readFileSync, mkdirSync, existsSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';

/**
 * 逐屏视觉验收: 把菜单可达的每一条路由各截一张图，供人眼逐屏走查。
 *
 * ⚠️ 这**不是**断言型 E2E。它一条业务断言都没有，唯一的产出是截图。
 * 「屏上有没有问题」由人看图判定 —— 机器扫描抓不到「产品名是一串 UUID」
 * 「已超期 10 而总计划 1」「Sprint 8 P1 挂在标题旁边」这类东西。
 *
 * ⛔ 全程只读: 只 goto + 截图，不点任何按钮、不提交任何表单。
 */

const HERE = dirname(fileURLToPath(import.meta.url));

const BASE = process.env.VSHOT_BASE || 'http://139.196.165.140:8086';
const USER = process.env.VSHOT_USER || 'f006_admin';
const PASS = process.env.VSHOT_PASS || '123456';
const OUT = process.env.VSHOT_OUT || resolve(HERE, '../../../my-prototype-logistics/vshots');
const START = Number(process.env.VSHOT_START || '0');
const END = Number(process.env.VSHOT_END || '999');

interface Route { path: string; title: string }
const routes: Route[] = JSON.parse(
  readFileSync(resolve(HERE, 'vshot-routes.json'), 'utf8'),
);

function safeName(i: number, title: string): string {
  const clean = title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 40);
  return `${String(i + 1).padStart(3, '0')}-${clean}.png`;
}

test('逐屏截图', async ({ page }) => {
  test.setTimeout(60 * 60 * 1000);
  if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true });

  // ── 登录 ──────────────────────────────────────────────────────────
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input').first().fill(USER);
  await page.locator('input[type="password"]').first().fill(PASS);
  await page.getByRole('button', { name: /登\s*录|登录/ }).first().click();
  await page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 30_000 });

  // 🔴 硬性前置: 证明我们真的登在 F006 上。
  //   凭证规则要求「写前用实时登录响应证明 factoryUser.factoryId == 'F006'」——
  //   本脚本只读, 但这一步同样是**阳性对照**: 拿不到身份说明登录没成功,
  //   后面 145 张截图会全是登录页, 而那看起来和「跑完了」一模一样。
  const identity = await page.evaluate(() => {
    const keys = Object.keys(localStorage);
    const blob = keys.map(k => `${k}=${localStorage.getItem(k)}`).join('\n');
    return blob;
  });
  expect(identity, '登录后 localStorage 里应能看到 F006 —— 看不到说明没真正登进去').toContain('F006');

  const log: string[] = [];
  const slice = routes.slice(START, Math.min(END, routes.length));
  for (const [k, r] of slice.entries()) {
    const i = START + k;
    const file = resolve(OUT, safeName(i, r.title));
    try {
      await page.goto(`${BASE}${r.path.startsWith('/') ? '' : '/'}${r.path}`, {
        waitUntil: 'domcontentloaded',
        timeout: 30_000,
      });
      // 给列表接口留出返回时间; ⛔ 不用 networkidle —— 这些页面有轮询, 永远不 idle。
      await page.waitForTimeout(Number(process.env.VSHOT_WAIT || 3500));
      await page.screenshot({ path: file, fullPage: true });
      log.push(`OK   ${safeName(i, r.title)}  ${r.path}`);
    } catch (e) {
      // ⛔ 失败不 continue 掉信息: 记下来并仍然截一张, 失败的那一屏往往信息量最大。
      try { await page.screenshot({ path: file, fullPage: false }); } catch { /* ignore */ }
      log.push(`FAIL ${safeName(i, r.title)}  ${r.path}  ${(e as Error).message.split('\n')[0]}`);
    }
    if ((k + 1) % 10 === 0) console.log(`… ${k + 1}/${slice.length}`);
  }
  writeFileSync(resolve(OUT, `_capture-log-${START}-${END}.txt`), log.join('\n'), 'utf8');
  console.log(log.filter(l => l.startsWith('FAIL')).join('\n') || '(无失败)');
  console.log(`共 ${log.length} 屏, 失败 ${log.filter(l => l.startsWith('FAIL')).length}`);
});
