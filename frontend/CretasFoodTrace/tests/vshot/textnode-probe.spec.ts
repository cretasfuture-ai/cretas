/**
 * 抓「文本节点直接放在 <View> 里」的完整 console 消息 + 组件栈。
 *
 * web 上这只是一条 console error；**真机上 RN 会抛**
 * `Text strings must be rendered within a <Text> component`。
 * ⇒ 这一类是「web 比真机宽容」的典型，两轮走查对它都是偏乐观的，必须定位到源码。
 *
 * 三个屏在走查里命中过：KPIReport / PurchaseOrderCreate / WHSettings。
 */
import { test } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const ROUTE = (process.env.VSHOT_ROUTE ?? 'Main/BossReportsTab/KPIReport').split('/');

test('locate the stray text node', async ({ page }) => {
  const msgs: string[] = [];
  page.on('console', (m) => {
    const t = m.text();
    if (t.includes('text node') || t.includes('Text strings must be rendered')) msgs.push(t);
  });
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);
  await page.waitForTimeout(5000);
  await navigateTo(page, ROUTE);
  await page.waitForTimeout(5000);
  console.log('=== text-node messages: ' + msgs.length);
  msgs.forEach((m) => console.log('---\n' + m.slice(0, 2500)));

  // 消息本身不带组件栈，所以换个问法：在 DOM 里找「直接文本子节点只有空白」的 View。
  // RN Web 的 View 渲染成 div 且带 css-view-* 类名。
  const found = await page.evaluate(() => {
    const out: string[] = [];
    document.querySelectorAll<HTMLElement>('div[class*="css-view"]').forEach((el) => {
      for (const n of Array.from(el.childNodes)) {
        const t = n.textContent ?? '';
        if (n.nodeType === Node.TEXT_NODE && t.length > 0 && !t.trim()) {
          const sib = Array.from(el.children)
            .slice(0, 3)
            .map((c) => (c as HTMLElement).innerText?.slice(0, 40) ?? '');
          out.push(
            JSON.stringify({
              cls: el.className.slice(0, 140),
              testID: el.getAttribute('data-testid'),
              text: JSON.stringify(t),
              siblings: sib,
            }),
          );
        }
      }
    });
    return out;
  });
  console.log('=== whitespace text nodes inside a View: ' + found.length);
  found.slice(0, 10).forEach((f) => console.log('   ' + f));
});
