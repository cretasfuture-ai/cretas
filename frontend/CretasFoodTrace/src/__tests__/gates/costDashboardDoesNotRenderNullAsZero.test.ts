import fs from 'fs';
import path from 'path';

/**
 * 后端把「不知道」发成 JSON `null`，⛔ 这个屏不许把它渲染成一个数。
 *
 * <h2>它守什么</h2>
 *
 * `CostAnalysisDashboard` 里三处产量指标原本都写成 `x !== undefined &&`。
 * 而 **`null !== undefined` 为真** —— 后端返回 `null` 时守卫形同虚设：
 *
 * | 字段 | 生产现状 | 旧守卫下显示 |
 * |---|---|---|
 * | `goodQuantity` | 全库 46 个批次**全为 NULL** | 🔴 `null 件` |
 * | `defectQuantity` | NULL | ✅ 侥幸安全（`null > 0` 为假） |
 * | `yieldRate` | 2026-08-28 起 NULL | 🔴 `0.0%` |
 *
 * `yieldRate` 那一处是 **#246 当场引入的回归**：#246 把「派生自编造良品数」的
 * `yield_rate` 清成 NULL（那是对的），于是这屏从「假的 100%（完美）」变成
 * 「假的 0.0%（全废）」。**0% 良品率是一个灾难性的真实读数**，与「还没算出来」含义相反。
 *
 * `goodQuantity` 那一处**早于 #246** —— `V20260826_05` 之后它就一直在给用户看 `null 件`。
 *
 * ⚠️ web-admin 的批次详情页 2026-08-26 就守对了（按 `goodQuantity == null` 判，显示「—」），
 * 手机这一侧漏了。**同一个判断两个端，只有一个端做对** —— 这正是「同一个东西两份，它一定会漂」。
 *
 * <h2>判据的代理性（必须写明）</h2>
 *
 * 真正想守的是「运行时不会把 null 渲染成数字」。静态扫描判不出运行时行为，
 * 所以用**源码级代理判据**：这个文件里这三个字段不许再出现 `!== undefined` 形状的守卫。
 *
 * ⚠️ 已知盲区：换个写法（先解构到局部变量、或 `!= null` 之外的等价写法）本闸看不出来；
 * 别的屏里同类问题本闸也不管（⛔ 故意的 —— 宽而误报的闸最终会被关掉，
 * 见本仓「闸的完备性和闸的存活是矛盾的」）。它的作用是**让「把 `!== undefined` 改回来」
 * 这个动作必须显式绕过一道写着理由的闸**。
 */

const SRC = path.resolve(__dirname, '../..');
const FILE = path.join(SRC, 'screens/processing/CostAnalysisDashboard.tsx');

/** 逐行剥掉注释 —— 本仓多次栽在「闸把自己写的注释当成被测对象」。 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map(line => line.replace(/\/\/.*$/, ''))
    .join('\n');
}

describe('CostAnalysisDashboard: null 不许被渲染成数字', () => {
  const body = stripComments(fs.readFileSync(FILE, 'utf8'));

  it('阳性对照：这把尺子读到的是真源码，且能认出 !== undefined 这个形状', () => {
    // 🔴 没有这一条，下面每条 not.toMatch 都可能只是「文件没读到」或「正则永不匹配」
    expect(body.length).toBeGreaterThan(5_000);
    expect('foo.bar !== undefined').toMatch(/\w+\.\w+\s*!==\s*undefined/);
  });

  it.each(['goodQuantity', 'defectQuantity', 'yieldRate'])(
    'batch.%s 不许用 `!== undefined` 当守卫（null 会穿过去）',
    field => {
      const bad = new RegExp(`batch\\.${field}\\s*!==\\s*undefined`);
      expect(body).not.toMatch(bad);
    },
  );

  it.each(['goodQuantity', 'yieldRate'])(
    'batch.%s 必须用 typeof === "number" 守（null / undefined / 非数字一律不渲染）',
    field => {
      const good = new RegExp(`typeof\\s+batch\\.${field}\\s*===\\s*'number'`);
      expect(body).toMatch(good);
    },
  );
});
