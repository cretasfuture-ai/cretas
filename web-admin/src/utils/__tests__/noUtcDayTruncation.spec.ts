import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 闸: 取「本地日历日」不许写成 UTC 截断。
 *
 * 缺陷长相: `d.toISOString().split('T')[0]`。toISOString 按 **UTC** 输出,
 * 东八区 00:00~07:59 本地已经是今天而 UTC 还是昨天 —— 整整差一天。
 *
 * 不是口径之争: 后端全线 GMT+8(`spring.jackson.time-zone=GMT+8`,
 * 服务器 Asia/Shanghai, `LocalDate BETWEEN` 比的是业务日历日) ——
 * 送 UTC 日**就是错的**。用 `@/utils/dateFormat` 的 `toDateString()`。
 *
 * ⛔ 只管「截断成日历日」。整串 ISO(`createdAt: new Date().toISOString()`)
 *    是时间戳语义, 用 UTC 正确, 不在管辖范围。
 *
 * ⚠️ dateFormat.ts 顶上早就记着同族的坑(PR #468 §6 P1-A) —— 共识一直有,
 *    只是没有一道闸把它钉在全仓, 于是 79 处照样长了出来。**注释挡不住的写成闸。**
 *
 * 🔴 2026-08-26 第二轮: 第一版只走 `.ts`/`.vue`, **漏掉了 `.js`** ——
 *    RN 侧 `services/api/audit-api-clients.js` 里还有一处而闸看不见它,
 *    于是「0 处」是个假读数。扩展名过滤自己造出了盲区。
 *    ⇒ 现在覆盖 ts/tsx/js/jsx/mjs/cjs/vue, 并由下面那条自检钉住:
 *    **闸必须真的扫到非 .ts 文件**, 否则它随时会被悄悄收窄回去。
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(HERE, '..', '..');

const TRUNCATION =
  /toISOString\(\)\s*(?:\.split\(\s*['"]T['"]\s*\)\s*\[\s*0\s*\]|\.slice\(\s*0\s*,\s*10\s*\)|\.substring\(\s*0\s*,\s*10\s*\)|\.substr\(\s*0\s*,\s*10\s*\))/;

const SKIP_DIRS = new Set(['__tests__', 'node_modules', 'dist', 'build', '.vite']);

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      walk(path.join(dir, entry.name), out);
    } else if (/\.(ts|tsx|js|jsx|mjs|cjs|vue)$/.test(entry.name) && !/\.(spec|test)\.(ts|js)$/.test(entry.name)) {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

/**
 * 逐行扫, 跳过注释行。
 *
 * ⚠️ 本仓多次栽在「闸把自己写的注释当成被测对象」—— 清扫时留下的解释性注释
 *    里就写着这个缺陷的长相, 不剔掉就是纯误报。
 */
function offendingLines(code: string): string[] {
  return code
    .split(String.fromCharCode(10))
    .map((line, i) => ({ line: line.trim(), no: i + 1 }))
    .filter(({ line }) => !line.startsWith('//') && !line.startsWith('*') && !line.startsWith('/*'))
    .filter(({ line }) => TRUNCATION.test(line))
    .map(({ line, no }) => `${no}: ${line.slice(0, 110)}`);
}

describe('闸: 取本地日历日不许写成 UTC 截断', () => {
  const files = walk(SRC);

  it('阳性对照: 确实扫到了成规模的源码(扫 0 个文件的闸永远是绿的)', () => {
    expect(files.length).toBeGreaterThan(300);
    const total = files.reduce((n, f) => n + fs.readFileSync(f, 'utf8').length, 0);
    expect(total).toBeGreaterThan(2_000_000);
  });

  it('🔴 阳性对照: 闸必须真的扫到非 .ts 文件(扩展名过滤造过一次盲区)', () => {
    // 第一版只走 ts/vue; RN 侧同族的闸因此漏了一个 .js 文件, 「0 处」是假读数。
    // 这条钉住覆盖面, 防止有人把过滤悄悄收窄回去。
    const nonTs = files.filter((f) => !/\.ts$/.test(f));
    expect(nonTs.length).toBeGreaterThan(0);
  });

  it('阳性对照: 这个正则真的认得出缺陷长相(不是恒不匹配)', () => {
    expect(TRUNCATION.test("d.toISOString().split('T')[0]")).toBe(true);
    expect(TRUNCATION.test('d.toISOString().slice(0, 10)')).toBe(true);
    expect(TRUNCATION.test('d.toISOString().substr(0,10)')).toBe(true);
    // 阴性: 整串 ISO 是时间戳语义, 不该被这道闸管
    expect(TRUNCATION.test('createdAt: new Date().toISOString()')).toBe(false);
  });

  it('🔴 一处都不许有 —— 用 toDateString() 取本地日历日', () => {
    const offenders: Record<string, string[]> = {};
    for (const f of files) {
      const hits = offendingLines(fs.readFileSync(f, 'utf8'));
      if (hits.length) offenders[path.relative(SRC, f).split(path.sep).join('/')] = hits;
    }
    expect(offenders).toEqual({});
  });
});
