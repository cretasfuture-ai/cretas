import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 渲染 `/ai-intents/execute` 的回执之前，⛔ 必须先看 `status`。
 *
 * ## 🔴 2026-08-28 生产实测抓到
 *
 * 财务主管工作台的主区块「月度经营摘要」**永远显示一句拒答**：
 *
 * ```
 * 「月度财务复盘」是写入/修改操作，执行前需要确认。
 * ```
 *
 * 成因只有一行：
 *
 * ```ts
 * formattedText.value = response.formattedText || response.message || '(无输出)';
 * ```
 *
 * **它完全不看 `status`** —— 只要 HTTP 200 就当成功。于是后端的
 * `WRITE_CONFIRM_REQUIRED` 回执被挂在成功的标题下当摘要显示，
 * 这个工作台的核心功能**一天都没工作过**。
 *
 * ⚠️ 这不是一屏的问题：建闸当天 7 个工作台是**同一行写法**。
 * （`smart-bi/AIQuery.vue` 是对的 —— 它按 `responseStatus` 分支，
 *  `utils/intentResponseStatus.ts` 的状态清单就是从它抄的。）
 *
 * ## 🔴 这道闸自己先假绿了一次，记在这里
 *
 * 第一版判据写成 `/intentNonAnswerLabel|isIntentAnswer/`。变异（把调用换成 `null`）
 * **没能让它红** —— 因为它匹配到的是 **import 行和注释里提到的名字**，不是真正的调用。
 * 「grep 到的是『提到』，不是『调用』」是本仓反复记过的形态。
 * ⇒ 判据改成：**剥掉 import 与注释之后**，仍存在 `xxx(` 形式的调用。
 *
 * ## 为什么是棘轮而不是硬红
 *
 * 建闸当天存量 **6** 处（财务那屏已在同一个 PR 里修掉）。
 * 一上来硬红就是一道当天被关掉的闸（形态 E）⇒ 只禁增长。
 *
 * ⛔ 想让这个数变小，是去改那些屏；**不是**往 `KNOWN` 里加名字。
 * 每改好一屏，从 `KNOWN` 里删掉它。
 */

const WORKDESK_DIR = resolve(__dirname, '..');

/** 渲染回执正文的那种写法。 */
const RENDER_PATTERN = /\bformattedText\.value\s*=\s*\w+\.formattedText\s*\|\|/;

/** 判读 status 的唯一实现（utils/intentResponseStatus.ts）——⚠️ 必须是**调用**。 */
const CONSULTS_STATUS = /(intentNonAnswerLabel|isIntentAnswer)\s*\(/;

/** 剥掉 import 行与注释 —— 它们只是「提到」这个名字，不是在用它。 */
function withoutImportsAndComments(source: string): string {
  return source
    .split(/\r?\n/)
    .filter((line) => !/^\s*import\b/.test(line))
    .join('\n')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/<!--[\s\S]*?-->/g, '');
}

/**
 * 建闸当天的存量。⚠️ 是**清单**不是计数 —— 计数会掩盖「修好一个、同时弄坏一个」。
 */
const KNOWN_NOT_CONSULTING_STATUS = [
  'ProductionManagerWorkdesk.vue',
  'PurchaserWorkdesk.vue',
  'QualityChiefWorkdesk.vue',
  'QualityManagerWorkdesk.vue',
  'SalesOwnerWorkdesk.vue',
  'WarehouseKeeperWorkdesk.vue',
];

function workdeskFiles(): string[] {
  return readdirSync(WORKDESK_DIR).filter((name) => name.endsWith('.vue'));
}

describe('工作台渲染 AI 回执前必须先看 status', () => {
  it('阳性对照：这把尺子认得出「渲染回执」这个形状', () => {
    // ⛔ 没有这条，下面的「0 处新增」可能只是因为正则谁也匹配不上。
    const rendering = workdeskFiles().filter((name) =>
      RENDER_PATTERN.test(readFileSync(join(WORKDESK_DIR, name), 'utf8')));
    expect(rendering.length, '一个渲染点都没扫到 —— 正则过期了，本次读数作废').toBeGreaterThan(0);
  });

  it('修好的那屏确实在看 status（否则本闸的基准是假的）', () => {
    const source = readFileSync(join(WORKDESK_DIR, 'FinanceManagerWorkdesk.vue'), 'utf8');
    expect(RENDER_PATTERN.test(source), 'FinanceManagerWorkdesk 不再渲染回执了？判据需要重写').toBe(true);
    expect(CONSULTS_STATUS.test(withoutImportsAndComments(source)),
      'FinanceManagerWorkdesk 不再看 status 了 —— 「月度经营摘要」会重新变回一句拒答').toBe(true);
  });

  it('棘轮：不看 status 的工作台只许变少', () => {
    const offenders = workdeskFiles().filter((name) => {
      const source = readFileSync(join(WORKDESK_DIR, name), 'utf8');
      return RENDER_PATTERN.test(source) && !CONSULTS_STATUS.test(withoutImportsAndComments(source));
    }).sort();

    const added = offenders.filter((name) => !KNOWN_NOT_CONSULTING_STATUS.includes(name));
    expect(added,
      '新增了「渲染 AI 回执却不看 status」的工作台。'
      + '⛔ 不要把它加进 KNOWN —— 用 utils/intentResponseStatus.ts 的 intentNonAnswerLabel 判一下。').toEqual([]);

    const fixed = KNOWN_NOT_CONSULTING_STATUS.filter((name) => !offenders.includes(name));
    expect(offenders.length + fixed.length,
      `存量清单与实际对不上（修好 ${fixed.length} 个后应同步从 KNOWN 里删掉）`)
      .toBe(KNOWN_NOT_CONSULTING_STATUS.length);
  });
});
