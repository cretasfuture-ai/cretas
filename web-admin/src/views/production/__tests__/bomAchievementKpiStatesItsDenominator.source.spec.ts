import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 「BOM达成率分析」的三张派生 KPI 必须写出**它们是基于几个批次算的**。
 *
 * <h2>🔴 这条是「部署之后逐屏视觉验收」才抓到的 —— 而且抓到的是我自己上一轮的修复</h2>
 *
 * <p>上一轮（PR #260）修掉了「没有计划数据 ⇒ 后端返回达成率 100%」，
 * 部署后重新截图，达成率那一列确实变成了「—」，真实的 100% 也没被误伤。</p>
 *
 * <p><b>但卡片行还是这样：</b></p>
 *
 * <pre>
 *   总批次数  8      平均达成率  100%      用量偏差批次数  0      最低达成率  100%
 * </pre>
 *
 * <p>用户读到的是「8 个批次平均达成率 100%、零偏差」。
 * 而那个 100% <b>只来自 1 个批次</b> —— 另外 7 个没有计划数据，达成率是「—」。</p>
 *
 * <p>▎<b>不编数字只是第一步；不说分母仍然会误导。</b></p>
 *
 * <p>⚠️ 这是本仓「修复要按最不显眼的那个验收」的又一个实例：
 * 最显眼的那个（达成率列）先对上了，人就会停下来，
 * 而派生出来的那三张卡还在说同一个谎的另一半。</p>
 *
 * <h2>判据</h2>
 *
 * <ol>
 *   <li>三张<b>派生</b> KPI（平均 / 偏差计数 / 最低）各自挂一行口径说明</li>
 *   <li>口径由实际值算出来，⛔ 不是写死的一句话
 *       —— 「(空=零新增)」那种无条件标签本仓踩过</li>
 * </ol>
 *
 * <p>⚠️「总批次数」那张<b>不挂</b>：它的分母就是它自己，挂上去是噪声。</p>
 */

const SCREEN = resolve(__dirname, '../BomAchievementView.vue');

describe('BOM达成率 KPI: 说清分母', () => {
  it('阳性对照: 真的读到了那个屏, 且它确实有四张 KPI 卡', () => {
    const src = readFileSync(SCREEN, 'utf8');
    expect(src.length, '源码读成空 ⇒ 下面全是恒真式').toBeGreaterThan(2000);
    for (const label of ['总批次数', '平均达成率', '最低达成率', '用量偏差批次数']) {
      expect(src, `应有「${label}」卡`).toContain(label);
    }
  });

  it('三张派生 KPI 各挂一行口径说明(总批次数那张不挂 —— 它的分母是自己)', () => {
    const src = readFileSync(SCREEN, 'utf8');
    const occurrences = (src.match(/kpi-caliber"?>\{\{\s*kpiCaliber\s*\}\}/g) || []).length;
    expect(
      occurrences,
      '平均达成率 / 用量偏差批次数 / 最低达成率 三张都要说清分母。\n' +
        '实拍(2026-08-28 部署后): 「总批次数 8」紧挨「平均达成率 100%」,\n' +
        '而那个 100% 只来自 1 个批次 —— 另外 7 个没有计划数据。',
    ).toBe(3);
  });

  it('⛔ 口径由实际值算出来, 不是写死的一句话', () => {
    const src = readFileSync(SCREEN, 'utf8');
    // 计算属性里必须真的引用 ratedCount 与 total —— 否则它就是个无条件标签
    const fn = src.match(/const kpiCaliber = computed\(\(\) => \{([\s\S]*?)\n\}\);/)?.[1] ?? '';
    expect(fn, '找不到 kpiCaliber 计算属性').not.toBe('');
    expect(fn, '口径必须引用「有达成率的批次数」').toContain('ratedCount');
    expect(fn, '口径必须引用「总批次数」').toContain('total');
    // 本仓踩过「(空=零新增)」这种无条件结论标签 —— 这里显式排除那个形状
    expect(fn, '⛔ 不许无条件返回同一句话')
      .toMatch(/if\s*\(/);
  });

  it('阴性对照: kpi 对象真的算了 ratedCount(否则上面那条只是在检查一个恒为 undefined 的字段)', () => {
    const src = readFileSync(SCREEN, 'utf8');
    expect(src, 'kpi computed 里应有 ratedCount').toMatch(/ratedCount:\s*rowsWithRate\.length/);
    expect(src, 'rowsWithRate 应是「有达成率的行」')
      .toMatch(/rowsWithRate\s*=\s*rows\.filter\(r => r\.achievementRate != null\)/);
  });
});
