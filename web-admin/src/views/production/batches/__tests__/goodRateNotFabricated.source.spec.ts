import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 良品数未知时，界面不许显示一个「良品率」。
 *
 * <h2>2026-08-26 六膳门 prod 实测（界面走查抓到，接口层没抓到）</h2>
 *
 * <p>批次 <code>10823</code> 的详情页同时显示：
 * <code>良品数量 —</code> / <code>不良品数量 —</code> / 而 <b><code>良品率 100.0%</code></b>。
 *
 * <p>根因不是「派生值没跟着清」这么简单 —— <code>batch.yieldRate</code> 这一列
 * <b>被两个不同口径写</b>：</p>
 *
 * <ul>
 *   <li><code>ProductionBatch.calculateMetrics()</code>：<code>良品数 / 实际产量</code>（良品率）</li>
 *   <li><code>ClerkProcessEntryServiceImpl:754</code>：<code>逐道产出 / 投入</code>（<b>出成率</b>）</li>
 * </ul>
 *
 * <p>10823 是后者写的（逐道 8kg 投入 → 8kg 产出 ⇒ 100%），而它的
 * <code>good_quantity</code> 是 <code>NULL</code>。⇒ <b>页面把一个出成率标成了良品率，
 * 告诉厂长「良品率 100%」，而真实良品率根本不知道。</b></p>
 *
 * <h2>为什么钉在这里而不是后端</h2>
 *
 * <p>「一列两口径」是后端的债（形态 D），收敛它要动两条写入路径，属于产品决策。
 * 而<b>无论那一列是谁写的</b>，良品率的定义都是 良品/实际 —— 良品数未知时它就
 * <b>不可知</b>。所以这道闸守的是一个更强、也更简单的判据：
 * <b>基数未知 ⇒ 派生率不许显示。</b></p>
 *
 * <p>⚠️ 本仓规则原话：<b>毛利对了不算，毛利率对了才算</b> —— 修复要按最不显眼的那个验收。
 * <code>V20260826_05/06</code> 已把库里的假「零不良」清成 NULL，这一条补上界面这一侧。</p>
 *
 * <h2>已知盲区</h2>
 *
 * <p>这是<b>源码级</b>判据（扫模板与计算属性），不是渲染级。它挡不住「换个变量名再写死一个 100%」，
 * 但能让「把 <code>batch.yieldRate</code> 直接摆到良品率位置」这个具体动作必须先绕过一条写着理由的断言。</p>
 */
const SRC = readFileSync(
  resolve(__dirname, '../../../../views/production/batches/detail.vue'),
  'utf8',
);

/** 剥注释 —— 否则本闸会把上面这段说明里的 `batch.yieldRate` 当成被测对象。 */
function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

const CODE = stripComments(SRC);

describe('良品率：基数未知就不许显示', () => {
  it('阳性对照：文件读得到且确实含良品率这一块（防剥注释剥成空串后恒绿）', () => {
    expect(CODE.length).toBeGreaterThan(5000);
    expect(CODE).toContain('良品率');
    expect(CODE).toContain('goodRateDisplay');
  });

  it('良品率的显示值走 goodRateDisplay，⛔ 不直接摆 batch.yieldRate', () => {
    // 直接把 formatPercent(batch.yieldRate) 摆进模板 = 回到 2026-08-26 那个缺陷:
    // good_quantity 为 NULL 时照样显示 100%。
    expect(CODE).not.toContain('{{ formatPercent(batch.yieldRate) }}');
  });

  it('goodRateDisplay 在 goodQuantity 为空时返回占位符而不是数字', () => {
    // 判据落在「有没有对 goodQuantity 做空值判断」上 —— 没有这一条,
    // 这个计算属性就只是换了个名字的 formatPercent(batch.yieldRate)。
    const block = CODE.slice(
      CODE.indexOf('const goodRateDisplay'),
      CODE.indexOf('const goodRateKnown'),
    );
    expect(block).toContain('goodQuantity');
    expect(block).toMatch(/goodQuantity\s*==\s*null/);
  });

  it('未知时不上色 —— 否则「未知」会被涂绿, 看起来像达标', () => {
    // 三个颜色判断都必须先过 goodRateKnown, 不然 undefined >= 95 虽为 false,
    // 但 batch.yieldRate 有值(出成率)时会把一个非良品率的数涂成绿色。
    expect(CODE).toContain("'text-success': goodRateKnown && batch.yieldRate >= 95");
    expect(CODE).toContain("'text-danger': goodRateKnown && batch.yieldRate > 0");
  });
});
