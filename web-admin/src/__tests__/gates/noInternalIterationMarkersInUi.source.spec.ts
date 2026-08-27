import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * ⛔ 内部迭代编号（Sprint N / Loop N）不许出现在**会渲染给用户**的位置。
 *
 * <h2>🔴 2026-08-28 逐屏视觉走查抓到（接口层、类型检查、既有测试全都抓不到）</h2>
 *
 * <p>f006（<b>真实客户</b>租户）的销售老板工作台，页面标题旁边挂着一个
 * <code>Sprint 8 P1 (2026-05-20)</code> 的灰标签；「今日待发清单」旁边写着
 * <code>Sprint 10 Loop 1 — AI 闭环发货</code>。</p>
 *
 * <p>全量扫下来 <b>40 处 / 21 个文件</b>，分三类：</p>
 *
 * <pre>
 *   A 纯版本徽标      8 处  七个工作台 + ECN 列表的 &lt;el-tag&gt;Sprint 8 Px (2026-05-20)&lt;/el-tag&gt;
 *                          对客户零信息量, 而那个日期是三个月前 ⇒ 看起来像半成品软件
 *   B 用内部编号承诺   ~25   「日历视图将在 Sprint 5 上线」「9 待 Sprint 5」「自定义 (Sprint 6)」
 *                          客户不知道 Sprint 5 是什么时候, 而那个时间点早就过了
 *                          ⇒ 等于一个**过期的承诺**。改成「尚未开放」, ⛔ 不承诺时间
 *   C 内部实现细节     3 处  「实体在 Sprint 9 分支已实现, 待合并至 main」
 *                          ⇒ 把 git 分支状态告诉客户
 * </pre>
 *
 * <p>其中两处比标签更糟, 因为它们**离开了页面**：</p>
 * <ul>
 *   <li><code>PurchaserWorkdesk</code> 把 <code>'低库存补货 — Loop 3 一键采购下单'</code>
 *       写进采购单备注 ⇒ 内部黑话**存进业务单据**</li>
 *   <li><code>WarehouseKeeperWorkdesk</code> 把 <code>'确认收货 (Sprint 10 Loop 2)'</code>
 *       当 <code>userInput</code> 发给 AI ⇒ 进对话历史, 可能被回显</li>
 * </ul>
 *
 * <h2>⚠️ 只扫 &lt;template&gt; 会漏掉三分之一</h2>
 *
 * <p>第一版扫描只看 <code>&lt;template&gt;</code>，数出 28 处。而
 * <code>ElMessage</code> / <code>ElMessageBox</code> 的文案写在 <code>&lt;script&gt;</code> 里，
 * <b>同样会弹给用户</b> —— 补上之后是 40 处。⇒ 本闸扫**整个文件**（剥注释后）。</p>
 *
 * <h2>⚠️ 这是源码级代理判据</h2>
 *
 * <p>「这个串会不会走到用户面前」静态分析判不出来。本闸用的代理判据是
 * <b>「剥掉注释之后还出现 Sprint N / Loop N」</b>：</p>
 *
 * <ul>
 *   <li>看不见的：拼接出来的（<code>`Sprint ${n}`</code>）、写成别的措辞的内部编号</li>
 *   <li>可能误报的：注释之外**确实需要**这个词的场合（目前 0 处；真出现时
 *       在下面的 ALLOW 里具名登记并写理由，⛔ 不要把闸调宽）</li>
 * </ul>
 *
 * <p>建闸当天违例 <b>0</b> 处（40 处已在同一 PR 清掉）⇒ <b>硬红，不需要棘轮</b>。</p>
 */

const ROOT = resolve(__dirname, '../..');           // web-admin/src

/** 具名豁免：出现在这里的必须写清「为什么这个位置的内部编号是对的」。目前为空。 */
const ALLOW: ReadonlyArray<{ file: string; reason: string }> = [];

const MARKER = /Sprint\s*\d+|Loop\s*\d+/;

/**
 * ⚠️ 第一行的 CRLF 归一**不能省** —— 本仓 .vue 文件行尾混用 CRLF 和 LF。
 *
 * JS 里 `.` 不匹配 `\r`（它和 `\n` 一样是行终止符），所以在 CRLF 文件上
 * `l.replace(/\/\/.*$/, '')` **一行都剥不掉**：`$` 在 `\r` 之后，而 `.` 跨不过去。
 *
 * 🔴 实测代价：同一份 `views/finance/payments/list.vue`，Python 写的扫描器数出 0 处
 * （Python 的 `.` **匹配** `\r`），这道 JS 闸数出 3 处 —— 两把尺子读同一份文件给出
 * 不同的数，而两边的正则字面量**一模一样**。差别只在两种语言对 `\r` 的定义。
 */
function stripComments(src: string): string {
  return src
    .replace(/\r\n/g, '\n')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(l => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** 递归收集 web-admin/src 下的源文件（⛔ 不引新依赖，与隔壁 gate 同一写法）。 */
function collect(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      collect(abs, out);
      continue;
    }
    if (name.endsWith('.d.ts') || name.includes('.spec.')) continue;
    if (name.endsWith('.vue') || name.endsWith('.ts')) out.push(abs);
  }
  return out;
}

const files = collect(ROOT).sort();

describe('UI 里不许出现内部迭代编号 (Sprint N / Loop N)', () => {
  it('阳性对照: 真的扫到了源文件, 且扫到了那几个已知的工作台', () => {
    expect(files.length, '一个文件都没扫到 ⇒ 下面那条断言是恒真式').toBeGreaterThan(400);
    const rels = files.map(f => relative(ROOT, f).replace(/\\/g, '/'));
    for (const f of [
      'views/workdesk/SalesOwnerWorkdesk.vue',
      'views/workdesk/FinanceManagerWorkdesk.vue',
      'views/sales/reports/dashboard.vue',
      'components/widgets/DashboardGrid.vue',
    ]) {
      expect(rels, `${f} 应在扫描范围内 —— 它是 2026-08-28 实测的违例点之一`).toContain(f);
    }
  });

  it('阴性对照: 认得出那个形状, 且不误伤注释', () => {
    expect(MARKER.test('<el-tag>Sprint 8 P1 (2026-05-20)</el-tag>')).toBe(true);
    expect(MARKER.test('Sprint 10 Loop 1 — AI 闭环发货')).toBe(true);
    expect(MARKER.test('日历视图将在 Sprint 5 上线')).toBe(true);
    // 注释里写内部编号是**对的** —— 那是给开发者的。闸不该拦它。
    expect(MARKER.test(stripComments('// Sprint 6 W4-B: 工资策略'))).toBe(false);
    expect(MARKER.test(stripComments('<!-- Tab 2 (Sprint 5 Track G stub) -->'))).toBe(false);
    expect(MARKER.test(stripComments('/* @since 2026-05-19 (Sprint 6 W1-B) */'))).toBe(false);
    // 🔴 CRLF 那一维必须单独喂一次。第一版对照只喂了 LF, 于是它绿着放行了一个
    //    「在 CRLF 文件上一行注释都剥不掉」的 stripComments —— 三条纯注释被报成违例。
    //    ⇒ 对照没覆盖的那一维, 就是闸失效而对照不会响的那一维。
    expect(MARKER.test(stripComments('// Sprint 6 W3-A — inline 3-chip\r\nconst a = 1;\r\n'))).toBe(false);
    // 正确的替代措辞不该被误报
    expect(MARKER.test('日历视图尚未开放')).toBe(false);
  });

  it('⛔ 剥掉注释之后, 没有任何源文件还留着内部迭代编号', () => {
    const allow = new Set(ALLOW.map(a => a.file));
    const hits: string[] = [];
    for (const abs of files) {
      const rel = relative(ROOT, abs).replace(/\\/g, '/');
      if (allow.has(rel)) continue;
      const body = stripComments(readFileSync(abs, 'utf8'));
      body.split('\n').forEach((line, i) => {
        if (MARKER.test(line)) hits.push(`${rel}:${i + 1}  ${line.trim().slice(0, 100)}`);
      });
    }
    expect(
      hits,
      'Sprint N / Loop N 是**我们内部的迭代编号**, 客户不知道它指哪一天,\n' +
        '而那些日期通常早已过去 ⇒ 写在 UI 上等于一个过期的承诺。\n' +
        '· 纯版本徽标 → 删掉\n' +
        '· 「将在 Sprint N 上线」 → 「尚未开放」(⛔ 不要承诺时间)\n' +
        '· 内部实现细节(分支状态等) → 删掉\n' +
        '· 确实需要保留的 → 在本文件 ALLOW 里具名登记并写理由\n' +
        hits.join('\n'),
    ).toEqual([]);
  });
});
