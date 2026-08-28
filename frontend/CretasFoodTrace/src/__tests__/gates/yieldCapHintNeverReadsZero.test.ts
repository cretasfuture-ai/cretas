/**
 * 闸 —— 「可投上限约 N」这句话里的 N，不许把一个正数说成 0。
 *
 * <h2>实测长相（2026-08-29，小米真机走报工链）</h2>
 *
 * ```
 * 工序 SOP-20260817-01-黄油鸡-原料处理, 计划数量 0.04 kg
 * 弹窗:      「计划 0.04 kg, 可投上限约 0 (含 30% 超收)」
 * 实际提交 0.04 kg -> 成功
 * ```
 *
 * 成因是显示端 `Math.round(plannedMax)`：`Math.round(0.04 * 1.3)` = `Math.round(0.052)` = 0。
 * 而**真正生效的** `plannedMax` 没有取整 ⇒ 界面说的和系统拦的是两个数，且不报错。
 *
 * ⚠️ 它能活到现在，是因为唯一被检验过的例子是大数量（组件 docstring: 计划 998 → 1297）。
 * ⇒ 所以下面既钉小量级（新行为），也钉大量级（**证明我没有为了修小的把大的弄坏**）。
 *
 * <h2>口径 / 它看不见什么</h2>
 *
 * 这道闸测的是格式化函数的**行为**，外加一条源码断言防止调用点被改回 `Math.round`。
 * 它证明不了"操作员真的看到了正确的数" —— 那需要真机走一遍（本次缺陷正是那样发现的）。
 */
import fs from 'fs';
import path from 'path';

import { formatQtyHint } from '../../screens/processing/yieldHintFormat';

const SCREEN = path.resolve(__dirname, '../../screens/processing/YieldStepReportScreen.tsx');

describe('可投上限提示: 正数不许被显示成 0', () => {
  it('🔴 复现真机那一例: 计划 0.04 × 1.3 = 0.052, 不再显示成 0', () => {
    const plannedMax = 0.04 * 1.3;
    expect(formatQtyHint(plannedMax)).toBe('0.052');
    // 「0」在这句话里不是近似值, 是「一点都不能投」
    expect(formatQtyHint(plannedMax)).not.toBe('0');
  });

  it('⛔ 不变式: 任何正数都不会被格式化成 "0"', () => {
    const samples = [0.052, 0.04, 0.01, 0.009, 0.00052, 1e-6, 0.3849, 0.5, 1, 99.4];
    for (const v of samples) {
      expect({ v, out: formatQtyHint(v) }).toEqual({ v, out: expect.not.stringMatching(/^-?0$/) });
    }
  });

  it('阳性对照: 大量级仍按原样取整(证明修小的没把大的弄坏)', () => {
    // 组件 docstring 里的既有例子: 计划 998 kg → 可投上限约 1297
    expect(formatQtyHint(998 * 1.3)).toBe('1297');
    expect(formatQtyHint(1500)).toBe('1500');
  });

  it('0 本身仍然是 "0"(合法读数, 不该被改造)', () => {
    expect(formatQtyHint(0)).toBe('0');
  });

  it('🔴 调用点不许改回 Math.round —— 那正是本次缺陷', () => {
    const src = fs.readFileSync(SCREEN, 'utf8');
    // 阳性对照: 真的读到了那句文案(读不到最像「一切正常」)
    expect(src).toContain('可投上限约');
    expect(src).toContain('formatQtyHint(plannedMax)');
    expect(src).not.toContain('Math.round(plannedMax)');
  });
});
