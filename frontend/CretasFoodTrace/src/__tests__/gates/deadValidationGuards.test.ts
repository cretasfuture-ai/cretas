/**
 * 闸: 「守卫写在 disabled 按钮后面 ⇒ 用户永远触发不到」这一族不许增长。
 *
 * 2026-09-07 由 organizer 的 Expo Web 探针发现 (仓储树 4 处, 实测强点
 * `INNER-VALIDATION dialogCount 0 -> 0`)。全 App 普查后是 **37 处**, 不是 4 处。
 *
 * ## 它为什么值得钉
 * 这些 `if (X) { appAlert(...); return; }` **今天无害** (按钮 disabled, 到不了),
 * 但在代码评审里长得像活的防呆 —— 将来谁说「这里守卫会弹」就是**假读数**。
 *
 * ## 判据是代理, 两个方向都不准 (写明, 不靠加启发式逼近)
 * - **假阳性**: 同一文件里不同按钮 / 不同 handler 的条件撞了名 (例如 `evidenceUploading`
 *   既是竞态自保又出现在别的按钮的 disabled 里) —— 那类「双保险」是对的, 不该改。
 * - **假阴性 ①**: 语义相同而**写法不同**的一对逃得掉。实测例: `WHReceiptCreateScreen` 的守卫写
 *   `validItems.length === 0`, 按钮写 `totalReceivedRows === 0`, 两者是同一个谓词 —— 本闸数不到它,
 *   organizer 是靠**行为探针**发现的。
 * - **假阴性 ②** (#699 终审补): `GUARD` 的 `if\s*\(([^)]{1,120})\)` **看不见条件里带括号的情形**
 *   (`if (!isValid(x))`); `DISABLED` 的 `[^}]{1,200}` 看不见嵌套花括号。
 * - **它是源码文本闸, 不是渲染测试**: 把提示 JSX 挪进一个永不渲染的分支, 本闸照样绿。
 *   「提示真的会渲染」这个证据来自浏览器探针 (2026-09-07 Expo Web), **不来自这道闸**。
 *
 * ⇒ 这个数是**下界**, 是「同一种**写法**有几处」而不是「同一种**行为**有几处」。
 *   ⛔ 不许哪天被人改写成「全 App 共 37 处」。
 *
 * ## 处置分类 (2026-09-07 pilot, 见 PR body)
 * - 边界**已可见**在按钮旁 (`已选 N 项` / `提交入库 (N 行)`) ⇒ disabled 是对的, 守卫标注为防御性即可。
 * - 边界**不可见** (没选仓库 / 没选方案, 按钮在底部栏) ⇒ 保留 disabled **并补一行提示**说明缺什么
 *   (fool-proof Rule 1「预先显示边界」+ Rule 5「不要 dead-end」)。⛔ 不放开按钮 —— 工人屏上把提交键
 *   变成「能点但会失败」是更差的交易。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../..');

const GUARD = /if\s*\(([^)]{1,120})\)\s*\{\s*(?:appAlert|Alert\.alert)\s*\([\s\S]{0,400}?\breturn\b/g;
const DISABLED = /disabled=\{([^}]{1,200})\}/g;

function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      walk(full, acc);
    } else if (name.endsWith('.tsx')) {
      acc.push(full);
    }
  }
  return acc;
}

function census(): string[] {
  const hits: string[] = [];
  for (const f of walk(SRC)) {
    const code = stripComments(fs.readFileSync(f, 'utf8'));
    const disabledExprs = [...code.matchAll(DISABLED)].map((m) => m[1] ?? '');
    if (!disabledExprs.length) continue;
    for (const gm of code.matchAll(GUARD)) {
      const cond = (gm[1] ?? '').trim();
      if (cond.length < 3) continue;
      if (disabledExprs.some((d) => d.includes(cond))) {
        hits.push(`${path.relative(SRC, f).split(path.sep).join('/')}: ${cond}`);
      }
    }
  }
  return hits.sort();
}

/** 2026-09-07 实测。⛔ 只许往下调 —— 每处置一处就减一次, 不许因为「新屏也这么写」而上调。 */
const BASELINE = 37;
/**
 * 下限: 读数低于它 ⇒ 先怀疑扫描器坏了, 不是「这一族变少了」。
 * 取 30 (= 37 − 7) 是保守值: 一刀最多处置得动个位数, 真要一次少 8 处以上, 那个 PR 自己会连基线一起改。
 */
const FLOOR = 30;

describe('dead validation guards (behind disabled buttons) ratchet', () => {
  const hits = census();

  it('INSTRUMENT ALIVE: 扫得到文件, 且这一族的读数在合理区间 (下限防「部分瞎掉被读成变好了」)', () => {
    expect(walk(SRC).length).toBeGreaterThan(300);
    console.warn(`[dead-guards] ${hits.length} 处 (基线 ${BASELINE}, 下限 ${FLOOR})`);
    // 🔴 2026-09-07 补: 原来只拒绝 hits === 0, 于是一个**部分**坏掉的正则读到 3 也能过 —— 而且
    //    `3 <= 37` 会被读成「这一族缩小了, 真好」, 正是本文件注释里警告的形态 A。
    //    棘轮只有上界时, 闸自己坏掉的读数与「清理见效」完全同形 ⇒ 必须配下限。
    //    ⚠️ 下限不是「不许修好」: 真收敛到 30 以下时, 连同基线一起下调, 并在 PR 里贴逐处的去向。
    expect(
      hits.length >= FLOOR
        ? 'alive'
        : `INSTRUMENT SUSPECT — 只数到 ${hits.length} 处 (下限 ${FLOOR})。先查正则, 再谈「变少了」`,
    ).toBe('alive');
  });

  it('不许增长', () => {
    expect(
      hits.length <= BASELINE
        ? ''
        : `dead-guard 从 ${BASELINE} 涨到 ${hits.length}:\n  ${hits.join('\n  ')}\n`
          + '⇒ 新写的守卫在 disabled 按钮后面, 用户触发不到。要么让边界可见(提示行), 要么别把它当防呆。',
    ).toBe('');
  });

  it('pilot: 边界不可见的两处已补可见提示 (光有 disabled = 灰按钮 + 无解释)', () => {
    // ⚠️ 只钉 testID 不够: 变异「把提示文字清空」照样绿 (2026-09-07 实测 MD1 不红) —— 那是守空气。
    //    要钉的是**渲染出来的那句话**: 非空、且告诉工人去做什么 (含「请先」)。
    const hintOf = (src: string, testId: string): string => {
      const m = new RegExp(`testID="${testId}"[^>]*>\\s*([^<]*?)\\s*<`, 's').exec(src);
      return (m?.[1] ?? '').trim();
    };

    const stocktake = fs.readFileSync(path.join(SRC, 'screens/warehouse/inventory/WHInventoryCheckScreen.tsx'), 'utf8');
    const stocktakeHint = hintOf(stocktake, 'stocktake-initiate-hint');
    expect(stocktakeHint).toContain('请先');
    expect(stocktakeHint).toContain('仓库');
    expect(stocktake).toMatch(/!selectedWarehouseId && !initiating/);

    const alert = fs.readFileSync(path.join(SRC, 'screens/warehouse/alerts/AlertDetailScreen.tsx'), 'utf8');
    const alertHint = hintOf(alert, 'alert-submit-hint');
    expect(alertHint).toContain('请先');
    expect(alertHint).toContain('方案');
    expect(alert).toMatch(/!selectedSolution && !processing/);
  });

  it('pilot: 四处守卫都仍然保留 disabled 防错 (⛔ 不许为了让守卫说话而放开提交键)', () => {
    const cases: Array<[string, RegExp]> = [
      ['screens/warehouse/inventory/WHInventoryCheckScreen.tsx', /disabled=\{initiating \|\| !selectedWarehouseId\}/],
      ['screens/warehouse/alerts/AlertDetailScreen.tsx', /disabled=\{processing \|\| !selectedSolution\}/],
      ['screens/warehouse/alerts/InventoryAlertScreen.tsx', /disabled=\{selectedAlerts\.size === 0\}/],
      ['screens/warehouse/inbound/WHReceiptCreateScreen.tsx', /disabled=\{submitting \|\| totalReceivedRows === 0\}/],
    ];
    const missing = cases
      .filter(([rel, re]) => !re.test(fs.readFileSync(path.join(SRC, rel), 'utf8')))
      .map(([rel]) => rel);
    expect(missing.join(', ')).toBe('');
  });
});
