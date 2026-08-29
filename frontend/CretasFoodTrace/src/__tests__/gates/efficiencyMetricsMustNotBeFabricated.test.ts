import fs from 'fs';
import path from 'path';

/**
 * 效率分析屏的四个数，必须来自真实读数或明说「算不出」。
 *
 * ## 实测（2026-08-29 逐屏走查）
 *
 * 屏上（F006，本周）：
 *
 * ```
 * 效率指标
 *   设备OEE     0.0%
 *   设备利用率   0.0%
 *   人员效率     0.0%
 *   整体效率     0.0%
 * ```
 *
 * 四个数没有一个是它标签说的那个量：
 *
 * | 屏上写的 | 代码里实际是什么 |
 * |---|---|
 * | 设备OEE   | `stats.averageOEE`，字段不在就 `0` |
 * | 设备利用率 | `activeCount / totalCount` —— 「有几台设备是 active 状态」，不是利用率 |
 * | 人员效率   | **硬编码的字面 `0`**，从来没有任何计算 |
 * | 整体效率   | **硬编码的字面 `0`**，同上 |
 *
 * 而 catch 分支写着「设备统计加载失败，使用默认数据」→ 四个全设 0：
 * **「接口挂了」和「四项指标都是 0%」在屏上长得一模一样。**
 *
 * ## 判据
 *
 * - ⛔ 不许把这些指标兜底成 `0`（`?? 0` / `: 0`）
 * - ⛔ 不许在失败分支里塞一组 0 当「默认数据」
 * - 「设备利用率」这个字段名不许再指向 `activeCount/totalCount`
 *   —— 真正的产能利用率在产能利用率报表里，分母是产线小时产能 × 工厂工时
 *
 * ⚠️ 它是**文本判据**，看不见间接引用；是兜底，不是免检。
 * 真正的兜底是逐屏看一眼那四个数。
 */
const SCREEN = path.join(
  __dirname, '..', '..', 'screens', 'reports', 'EfficiencyReportScreen.tsx'
);

/** ⛔ 先剥注释 —— 否则闸会把自己的说明文字数成违例（本仓记过这条）。 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '');
}

describe('效率分析屏不许编造指标', () => {
  let code: string;

  beforeAll(() => {
    code = stripComments(fs.readFileSync(SCREEN, 'utf-8'));
  });

  it('阳性对照: 真的读到了这个屏的源码 —— 否则下面几条是无意义的绿', () => {
    expect(code.length).toBeGreaterThan(2000);
    expect(code).toContain('efficiencyStats');
    expect(code).toContain('laborEfficiency');
  });

  it('🔴 四个指标都不许兜底成 0', () => {
    const offenders = ['equipmentOEE', 'activeEquipmentRatio', 'laborEfficiency', 'overallEfficiency']
      .filter((field) => new RegExp(`${field}\\s*(\\?\\?|\\|\\|)\\s*0\\b`).test(code));

    expect(offenders).toEqual([]);
  });

  it('🔴 人员效率 / 整体效率 不许是硬编码的 0', () => {
    // 原来是 `laborEfficiency: 0,` —— 一个从来没算过的字面量
    expect(code).not.toMatch(/laborEfficiency:\s*0\s*,/);
    expect(code).not.toMatch(/overallEfficiency:\s*0\s*,/);
  });

  it('🔴 失败分支不许塞一组 0 当「默认数据」', () => {
    // 「接口挂了」和「四项都是 0%」不能在屏上长得一样
    expect(code).not.toContain('使用默认数据');
    const catchBlocks = code.split('catch').slice(1).join('catch');
    expect(catchBlocks).not.toMatch(/equipmentOEE:\s*0\s*,/);
  });

  it('「设备利用率」不许再由 activeCount/totalCount 冒充', () => {
    // 那个比值是「有几台设备是 active 状态」, 与产能利用率是两个量。
    // 现在它叫 activeEquipmentRatio —— 名字对得上它真实的含义。
    expect(code).not.toMatch(/equipmentUtilization:\s*stats\.activeCount/);
    expect(code).toContain('activeEquipmentRatio');
  });

  it('算不出时给「—」, 且不画进度条', () => {
    expect(code).toContain("? '—'");
    // 每个指标都要有「null 就不画条」的守卫
    for (const field of ['equipmentOEE', 'activeEquipmentRatio', 'laborEfficiency', 'overallEfficiency']) {
      expect(code).toMatch(new RegExp(`efficiencyStats\\.${field} == null \\? null : \\(`));
    }
  });

  it('阴性对照: 闸不许宽到禁止一切 `?? 0` —— 只管这四个指标', () => {
    // 构造一段「别处的 ?? 0」, 它不该被判成违例
    const unrelated = "const pageSize = query.size ?? 0;";
    const offenders = ['equipmentOEE', 'activeEquipmentRatio', 'laborEfficiency', 'overallEfficiency']
      .filter((field) => new RegExp(`${field}\\s*(\\?\\?|\\|\\|)\\s*0\\b`).test(unrelated));
    expect(offenders).toEqual([]);
  });
});
