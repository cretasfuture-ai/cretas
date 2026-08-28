/**
 * 操作员的**主动作**：「我的工序任务」上点那张「可报工 ▶」的卡，能不能进到报工屏。
 *
 * <h3>线索（2026-08-29 按钮走查）</h3>
 *
 * 同一屏 5 个可点元素，最后那个「⚠ 1 种原料库存不足…」正常跳到了缺料明细，
 * 而「可报工 ▶」点下去**屏上一个字都没变、也没有任何请求**。
 *
 * ⚠️ 这是**线索不是诊断**：RN Web 上点文本节点接不住，必须点它外层的
 * `[role=button]` —— 走查工具自己就踩过。所以先分清是「按钮没反应」还是
 * 「我点错了元素」。
 *
 * <h3>判据</h3>
 *
 * 点完之后 `currentPath` 变没变。⛔ 不看「屏上有没有新文字」——
 * 那只能说明渲染变了，说明不了导航发生没发生。
 *
 * <h3>结论（2026-08-29 实测）：⛔ 不是缺陷</h3>
 *
 * <pre>
 *   点 #2（整张卡）  Main/.../OperatorAssignedProcess -> Main/.../YieldStepReport  ✅ 跳了
 *   点 #3「可报工 ▶」 路径不变                                                    ⚠️ 没跳走
 * </pre>
 *
 * 去源码确认：`可报工 ▶` 是个 **`Chip`**（与「等待中」成对的**状态标签**），
 * 没有 `onPress`；真正的点击目标是外层那张卡，而它工作正常。
 *
 * ⚠️ 之所以在走查里长得像「死按钮」，是因为 react-native-paper 的 `Chip`
 * 在 **web 上被渲染成 `<button>` 并吞掉点击**；native 上它是普通 View，
 * 触摸会穿透给卡片的 Pressable。
 * ▎与 `Alert.alert` 在 react-native-web 上是空函数**同一类 web-only 假象** ——
 * ▎而 OTA 只推 android/ios。
 *
 * <h3>⚠️ 这个探针自己有个缺陷，读它的输出时要知道</h3>
 *
 * 元素下标是**一次性算好的**。第一次跳转 + 返回之后列表会重渲染，
 * `nth(i)` 可能已经指向别的元素 —— 所以后面几张卡那几条「没跳走」**不可信**，
 * ⛔ 不能据此说「那些卡也点不动」。要量它们得每次点击前重新枚举。
 */
import { test } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, currentPath, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_operator1';

test('点「可报工」能不能进报工屏', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const fired: string[] = [];
  page.on('request', (r) => {
    if (r.method() !== 'GET' && r.url().includes('/api/')) fired.push(`${r.method()} ${r.url().split('?')[0]}`);
  });
  page.on('dialog', async (d) => { console.log(`[dialog] ${d.type()}: ${d.message()}`); await d.dismiss().catch(() => {}); });
  const errs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);
  await page.waitForTimeout(4000);
  await navigateTo(page, ['Main', 'OperatorReportTab']);
  await page.waitForTimeout(1200);
  await navigateTo(page, ['Main', 'OperatorReportTab', 'OperatorAssignedProcess']);
  await page.waitForTimeout(5000);

  console.log('进屏路径: ' + (await currentPath(page)));

  // 把这一屏所有「可点」的元素连同它们的层级关系打出来 ——
  // ⛔ 不要只报「点了没反应」, 先证明我点的是对的那个东西。
  const cands = await page.evaluate(() => {
    const vh = window.innerHeight;
    const out: { i: number; tag: string; role: string; text: string; w: number; h: number }[] = [];
    document.querySelectorAll<HTMLElement>('[role="button"], button, [tabindex]').forEach((el, i) => {
      const r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8 || r.top >= vh || r.bottom <= 0) return;
      out.push({
        i,
        tag: el.tagName,
        role: el.getAttribute('role') ?? '',
        text: (el.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 60),
        w: Math.round(r.width),
        h: Math.round(r.height),
      });
    });
    return out;
  });
  console.log('可点元素:');
  cands.forEach((c) => console.log(`  #${c.i} <${c.tag} role=${c.role}> ${c.w}x${c.h} "${c.text}"`));

  for (const c of cands.filter((x) => /可报工|报工/.test(x.text))) {
    const before = await currentPath(page);
    const mark = fired.length;
    const errMark = errs.length;
    await page.locator('[role="button"], button, [tabindex]').nth(c.i).click({ timeout: 8000 }).catch((e) => {
      console.log(`  点 #${c.i} 抛了: ${String(e).slice(0, 120)}`);
    });
    await page.waitForTimeout(3500);
    const after = await currentPath(page);
    console.log(`点 #${c.i} "${c.text}"`);
    console.log(`   路径 ${before} -> ${after}   ${before === after ? '⚠️ 没跳走' : '✅ 跳了'}`);
    console.log(`   非GET ${fired.length - mark} 条` + (fired.slice(mark).length ? `: ${fired.slice(mark).join(' | ')}` : ''));
    errs.slice(errMark, errMark + 2).forEach((e) => console.log(`   err: ${e.replace(/\s+/g, ' ')}`));
    if (before !== after) {
      console.log('   到了: ' + (await screenText(page)).visible.slice(0, 200).replace(/\s+/g, ' '));
      await navigateTo(page, ['Main', 'OperatorReportTab', 'OperatorAssignedProcess']);
      await page.waitForTimeout(3000);
    }
  }
});
