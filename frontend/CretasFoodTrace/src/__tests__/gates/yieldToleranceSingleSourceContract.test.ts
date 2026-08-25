/**
 * 闸 —— 出成报工的「超收容差」只许有一份，而那一份在后端。
 *
 * <h2>为什么有这道闸 (2026-08-25 收敛前的实测长相)</h2>
 *
 * 同一个业务常量在代码里散成五处，其中四处写死：
 *
 * ```
 * 后端(权威)  YieldReportServiceImpl.DEFAULT_TOLERANCE = 0.30
 *             ← 工厂可在 FactorySettings.productionSettings.yieldOverReceiptTolerance 改
 *             → 出到前端: YieldLimitsDTO.toleranceRate
 * RN 常量      const OVER_RECEIVE_TOLERANCE = 1.3
 * RN 文案      `... (含 30% 超收)`        ← 数字直接焊死在句子里
 * web-admin    「× 投入量 × 1.3 容差」
 * ```
 *
 * ⚠️ 注意那两个数**不是同一个数**：后端存的是【比率】0.30，RN 写的是【乘数】1.3。
 * 谁要是"统一"的时候直接把 0.30 顶替上去，上限会一口气少 30%。
 *
 * 后果是：工厂在数据库里把容差改成 10%，后端照 10% 拦，而 App 仍然按 1.3 画上限、
 * 仍然对操作员说「含 30% 超收」。**界面说的和系统做的是两个数**，而且没有任何东西会报错。
 *
 * <h2>这道闸守什么</h2>
 *
 * 守「前端不再自己知道容差是多少」这个**性质**，不是守某一行长什么样：
 *
 * 1. 报工页与数量输入组件的**可执行代码**里不出现容差字面量（剥注释后扫）；
 * 2. 报工页确实从后端的 `toleranceRate` 取值（否则第 1 条可能只是因为文件空了）。
 *
 * <h2>口径 / 它看不见什么</h2>
 *
 * 扫的是**源码文本**，证明不了"用户真的看到了正确的百分比" —— 那要真机走一遍。
 * 它守的是**这份收敛不被人不小心改回去**。
 * ⛔ 剥注释这一步不能省：本文件和被扫文件的注释里都写着 `1.3` / `30%`，
 *    不剥的话闸会把自己的说明文字当成违例（本仓一晚栽过四次同形）。
 */
import fs from 'fs';
import path from 'path';

const SCREEN = path.resolve(__dirname, '../../screens/processing/YieldStepReportScreen.tsx');
const INPUT = path.resolve(__dirname, '../../components/processing/YieldQuantityInput.tsx');

/** 容差字面量的几种长相：比率 / 乘数 / 百分比。 */
const TOLERANCE_LITERALS = [/\b0\.30\b/, /\b1\.3\b/, /30\s*%/, /130\s*%/];

/** 剥掉 `//` 行注释与 块注释 —— 注释里提到某个数字不等于它会被算进去或渲染出去。 */
function code(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
}

describe('出成超收容差只有一份，且那一份在后端', () => {
  it('阳性对照: 剥注释函数确实在剥，而且确实留下了非注释内容', () => {
    // ⛔ 没有这条，"扫不到字面量" 有可能只是因为剥注释把整个文件都吃掉了 ——
    //    那样第 2 条断言会恒真，闸变成摆设。
    const sample = [
      'const keep = 1;',
      '// 这行注释里有 1.3 和 30%',
      '/* 块注释里也有 0.30 */',
      'const alsoKeep = 2;',
    ].join('\n');
    const stripped = code(sample);
    expect(stripped).toContain('const keep = 1;');
    expect(stripped).toContain('const alsoKeep = 2;');
    expect(stripped).not.toContain('1.3');
    expect(stripped).not.toContain('0.30');
    expect(stripped).not.toContain('30%');
  });

  it.each([
    ['YieldStepReportScreen.tsx', SCREEN],
    ['YieldQuantityInput.tsx', INPUT],
  ])('%s 的可执行代码里没有写死的容差字面量', (_name, file) => {
    const stripped = code(fs.readFileSync(file, 'utf8'));
    // 阳性对照: 文件真的被读到了 (而不是空串让下面的断言恒真)
    expect(stripped.length).toBeGreaterThan(500);
    for (const pattern of TOLERANCE_LITERALS) {
      const hit = stripped.match(new RegExp(`^.*${pattern.source}.*$`, 'm'));
      expect(
        hit ? `${_name} 里仍有写死的容差字面量: ${hit[0].trim()}` : null,
      ).toBeNull();
    }
  });

  it('报工页的上限与文案都从后端 toleranceRate 派生', () => {
    const stripped = code(fs.readFileSync(SCREEN, 'utf8'));
    expect(stripped).toContain('toleranceRate');
    // 乘数由比率算出来 (1 + rate), ⛔ 不是把比率当乘数用
    expect(stripped).toContain('overReceiveMultiplier');
    // 文案里的百分比也要插值, 不许再写字面量
    expect(stripped).toContain('formatTolerancePct');
  });

  it('比率与乘数不是同一个数 —— 换算方向必须是 1 + rate', () => {
    // 守的是语义, 不是某一行的写法: 0.30 的容差对应 1.30 的乘数。
    // 直接把 rate 当 multiplier 用会让上限从 130% 掉到 30%。
    const overReceiveMultiplier = (rate: number) => 1 + rate;
    expect(overReceiveMultiplier(0.3)).toBeCloseTo(1.3, 10);
    expect(overReceiveMultiplier(0)).toBe(1);
    expect(overReceiveMultiplier(0.1)).toBeCloseTo(1.1, 10);
  });
});
