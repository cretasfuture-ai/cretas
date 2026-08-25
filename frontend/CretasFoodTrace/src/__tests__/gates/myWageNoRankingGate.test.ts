/**
 * ⛔ 闸: 「我的工资」屏不许碰任何排行 / 个人评级。
 *
 * <p>owner 裁定: <b>质量责任归工厂不归个人</b>, 且<b>奖励产出会诱导少报不良品</b>。
 * 所以这一屏没有排行榜、没有 A/B/C/D 评级、没有「你比同事快多少」。
 *
 * <p>危险在于这些东西<b>现成就在仓里</b>, 顺手 import 一个是很自然的动作:
 * <ul>
 *   <li>{@code src/components/smartbi/MobileRankingList.tsx} — 带前三名奖牌的 Top-N 组件</li>
 *   <li>{@code src/services/api/wageApiClient.ts} — {@code getTopEarners()} 工资排行榜 /
 *       {@code getEfficiencyRanking()} 人效排名 / {@code WorkerDailyEfficiency.rankInTeam}</li>
 *   <li>{@code src/services/api/personnelApiClient.ts} — {@code WorkHoursRankingItem}</li>
 *   <li>{@code src/services/api/productionAnalyticsApiClient.ts} — {@code getWorkerRanking()}</li>
 * </ul>
 *
 * <h2>⚠️ 为什么先剥注释再扫</h2>
 * <p>本仓栽过好几次「源码闸把我自己写的注释当成被测对象」—— 上面这段说明里就写着
 * {@code getTopEarners}、{@code MobileRankingList}, 被测文件的注释里也写着同样的词
 * (那些注释正是在解释「⛔ 不许用」)。裸 grep 会把它们全数成违例, 报出一堆假阳性,
 * 而假阳性正是让人把闸关掉的东西。⇒ 先剥掉注释再扫真代码。
 */

import * as fs from 'fs';
import * as path from 'path';

const ROOT = path.resolve(__dirname, '../../..');

/** 被守的文件 —— 「我的工资」这条链上的全部。 */
const GUARDED_FILES = [
  'src/screens/profile/MyWageScreen.tsx',
  'src/services/api/myWageApiClient.ts',
  'src/utils/myWageFormat.ts',
];

/** 出现在<b>真代码</b>里就算违例的标识符。 */
const FORBIDDEN_IDENTIFIERS = [
  'getTopEarners',
  'getEfficiencyRanking',
  'getWorkerRanking',
  'MobileRankingList',
  'RankingItem',
  'rankInTeam',
  'efficiencyScore',
  'efficiencyRating',
  'comparedToStandard',
  'WorkHoursRankingItem',
];

/** 不许 import 的模块 —— 光是引进来就说明走错方向了。 */
const FORBIDDEN_IMPORT_SPECIFIERS = [
  'wageApiClient',
  'MobileRankingList',
  'personnelApiClient',
  'productionAnalyticsApiClient',
  'smartbi',
];

/**
 * 剥掉 `//` 行注释和 `/* *\/` 块注释, 保留字符串字面量里的内容。
 * 够用即可 —— 被测的三个文件里没有含 `//` 的字符串字面量 (下面有阳性对照钉住它能用)。
 */
function stripComments(source: string): string {
  let out = '';
  let i = 0;
  let quote: string | null = null;
  while (i < source.length) {
    const ch = source[i]!;
    const next = source[i + 1];
    if (quote) {
      if (ch === '\\') {
        out += ch + (next ?? '');
        i += 2;
        continue;
      }
      if (ch === quote) {
        quote = null;
      }
      out += ch;
      i += 1;
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch;
      out += ch;
      i += 1;
      continue;
    }
    if (ch === '/' && next === '/') {
      while (i < source.length && source[i] !== '\n') i += 1;
      continue;
    }
    if (ch === '/' && next === '*') {
      i += 2;
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) i += 1;
      i += 2;
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

/** 取所有 import 语句的模块路径。 */
function importSpecifiers(code: string): string[] {
  const out: string[] = [];
  const re = /^\s*import\s[\s\S]*?from\s+['"]([^'"]+)['"]/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(code)) !== null) {
    out.push(m[1]!);
  }
  return out;
}

describe('⛔ 我的工资屏不许碰排行 / 个人评级', () => {
  // 🔴 仪器自检: 先证明这几个文件真的读得到, 而且剥注释真的在剥。
  //    读不到文件时下面所有断言都会「因为没东西可扫」而恒真。
  it('仪器自检: 被守文件都存在, 且剥注释确实生效', () => {
    for (const rel of GUARDED_FILES) {
      const full = path.join(ROOT, rel);
      expect(fs.existsSync(full)).toBe(true);
      expect(fs.readFileSync(full, 'utf8').length).toBeGreaterThan(500);
    }

    // 剥注释的阳性对照 —— 这三个文件的注释里**确实**提到了排行相关的词
    // (那些注释正是在解释「不许用」)。如果剥注释没生效, 下面的扫描会全部误报。
    const screen = fs.readFileSync(path.join(ROOT, GUARDED_FILES[0]!), 'utf8');
    expect(screen).toContain('MobileRankingList');            // 注释里提到了
    expect(stripComments(screen)).not.toContain('MobileRankingList'); // 剥完就没了
  });

  it.each(GUARDED_FILES)('%s 的真代码里没有排行 / 评级标识符', (rel) => {
    const code = stripComments(fs.readFileSync(path.join(ROOT, rel), 'utf8'));
    const hits = FORBIDDEN_IDENTIFIERS.filter((id) => code.includes(id));
    expect(
      hits.length === 0
        ? 'ok'
        : `${rel} 用到了 ${hits.join(' / ')} —— owner 裁定不做个人排行榜: `
          + '质量责任归工厂不归个人, 奖励产出会诱导少报不良品。',
    ).toBe('ok');
  });

  it.each(GUARDED_FILES)('%s 没有 import 任何排行相关模块', (rel) => {
    const specs = importSpecifiers(stripComments(fs.readFileSync(path.join(ROOT, rel), 'utf8')));
    const bad = specs.filter((s) => FORBIDDEN_IMPORT_SPECIFIERS.some((f) => s.includes(f)));
    expect(
      bad.length === 0 ? 'ok' : `${rel} import 了 ${bad.join(' / ')}`,
    ).toBe('ok');
  });

  // 阳性对照: 证明扫描逻辑真的会命中 —— 否则上面那两组「全绿」可能只是扫了个空串。
  it('阳性对照: 扫描逻辑对一段含违例的代码确实会命中', () => {
    const fake = "import { wageApiClient } from './wageApiClient';\nconst x = wageApiClient.getTopEarners();";
    const code = stripComments(fake);
    expect(FORBIDDEN_IDENTIFIERS.filter((id) => code.includes(id))).toContain('getTopEarners');
    expect(importSpecifiers(code)).toEqual(['./wageApiClient']);
  });
});
