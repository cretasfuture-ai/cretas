import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * OA 页面渲染 moduleCode 时，必须优先用后端下发的 `moduleLabel`，
 * 且 ⛔ 不许各自维护一份中文兜底表。
 *
 * <h2>🔴 2026-08-28 逐屏走查抓到（接口层抓不到，因为接口返回是对的）</h2>
 *
 * <p>f006_admin →「个人 OA / 我发起的」，「模块」列里三行显示成大写英文
 * <code>INVENTORY_ADJUSTMENT</code>，而同一列其他行是中文（库存调拨/销售订单/采购订单）。</p>
 *
 * <p>根因：<b>六个 OA 页面各写各的中文表，三种不同内容</b>：</p>
 *
 * <pre>
 *   pending.vue          4 条  独有 INVENTORY_ADJUSTMENT，缺 DISPOSAL/TRANSFER/VOUCHER/ECN
 *   my-created.vue       7 条  缺 INVENTORY_ADJUSTMENT   ← 截图看到的那三行
 *   acted.vue            6 条  缺 INVENTORY_ADJUSTMENT 和 INVENTORY_TRANSFER
 *   copied.vue           6 条  同上
 *   admin-running.vue    6 条  同上
 *   my-participated.vue  6 条  同上
 * </pre>
 *
 * <p>而 <b>后端早就在下发权威中文名</b>：<code>DecisionTypeMetadataRegistry</code>（30+ 码）
 * 经 <code>WorkflowInstancePendingDTO.moduleLabel</code> 下发，而
 * <code>/pending</code>、<code>/my-created</code>、<code>/acted</code>、<code>/copied</code>、
 * <code>/my-participated</code>、<code>/admin-running</code>
 * <b>六个端点返回的是同一个 DTO</b>，都带这个字段。</p>
 *
 * <p>⇒ 典型的「产出端有了，消费端收得到吗」：<b>只有 pending.vue 读了它</b>，
 * 另外五个只查自己那张表。</p>
 *
 * <p>⚠️ <code>pending.vue</code> 原来那段注释<b>早就预言了</b>「加在这里只会再漂一次」——
 * 它在隔壁五个文件漂了。⇒ 这道闸就是那句注释的执行版。</p>
 *
 * <h2>判据</h2>
 *
 * <ol>
 *   <li>没有任何 OA 页面自己定义 <code>function moduleLabel</code> 或
 *       <code>const MODULE_LABELS</code>（唯一一份在 <code>moduleLabels.ts</code>）</li>
 *   <li>每个渲染 moduleCode 的页面都写成 <code>row.moduleLabel || ...</code></li>
 * </ol>
 *
 * <p>⚠️ 这是<b>源码级代理判据</b>：它挡不住「换个写法绕过去」，
 * 作用是让「再写一份本地表」这个动作必须显式绕过一道写着理由的闸。</p>
 */

const VIEWS = resolve(__dirname, '..');

function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(l => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

const vueFiles = readdirSync(VIEWS).filter(f => f.endsWith('.vue'));

describe('OA moduleCode 中文名: 一份表 + 优先用后端下发的', () => {
  it('阳性对照: 真的读到了那些 .vue（否则下面全是恒真式）', () => {
    expect(vueFiles.length).toBeGreaterThanOrEqual(6);
    for (const f of ['pending.vue', 'my-created.vue', 'acted.vue', 'copied.vue']) {
      expect(vueFiles).toContain(f);
    }
  });

  it('阴性对照: 这两条正则认得出它们要抓的形状', () => {
    expect(/function\s+moduleLabel\s*\(/.test('function moduleLabel(moduleCode: string)')).toBe(true);
    expect(/const\s+MODULE_LABELS\s*:/.test('const MODULE_LABELS: Record<string, string> = {')).toBe(true);
  });

  it('⛔ 没有任何 OA 页面自己维护中文兜底表', () => {
    const hits: string[] = [];
    for (const f of vueFiles) {
      const body = stripComments(readFileSync(join(VIEWS, f), 'utf8'));
      if (/function\s+moduleLabel\s*\(/.test(body)) hits.push(`${f}: 自己定义了 function moduleLabel`);
      if (/const\s+MODULE_LABELS\s*:/.test(body)) hits.push(`${f}: 自己定义了 const MODULE_LABELS`);
    }
    expect(
      hits,
      `唯一一份在 moduleLabels.ts。各写各的会漂 —— 2026-08-28 实测六个页面三种不同内容。\n` +
        hits.join('\n'),
    ).toEqual([]);
  });

  it('每个渲染 moduleCode 的页面都优先读后端下发的 row.moduleLabel', () => {
    const hits: string[] = [];
    for (const f of vueFiles) {
      const body = stripComments(readFileSync(join(VIEWS, f), 'utf8'));
      // 只看那些真的把 moduleCode 渲染成标签的页面
      if (!/enumLabel\(\s*row\.moduleCode/.test(body) && !/\{\{\s*row\.moduleCode\s*\}\}/.test(body)) continue;
      if (!/row\.moduleLabel\s*\|\|/.test(body)) hits.push(`${f}: 渲染了 moduleCode 却没有 \`row.moduleLabel ||\` 兜底顺序`);
    }
    expect(
      hits,
      `后端 DecisionTypeMetadataRegistry 的中文名经 WorkflowInstancePendingDTO.moduleLabel 下发,\n` +
        `六个 OA 端点都带它。不读它 = 用一张必然漂的本地表覆盖权威值。\n` +
        hits.join('\n'),
    ).toEqual([]);
  });
});
