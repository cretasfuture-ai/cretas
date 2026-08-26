import fs from 'fs';
import path from 'path';

/**
 * 闸: 取「本地日历日」不许写成 UTC 截断。
 *
 * 缺陷长相: `d.toISOString().split('T')[0]`。toISOString 按 **UTC** 输出,
 * 东八区 00:00~07:59 本地已经是今天而 UTC 还是昨天 —— 整整差一天。
 * 早班仓库收货(6~8 点)正好落在这个窗口里。
 *
 * 后端全线 GMT+8(`spring.jackson.time-zone=GMT+8`, 服务器 Asia/Shanghai,
 * `LocalDate BETWEEN` 比的是业务日历日), 所以送 UTC 日**就是错的**, 不是口径之争。
 *
 * ⛔ 这道闸只管「截断成日历日」。整串 ISO(`createdAt: new Date().toISOString()`)
 *    是**时间戳**语义, 用 UTC 正确, 不在管辖范围。
 *
 * 🔴 这条不能靠 tsc 兜底: 清扫时脚本一度把
 *    `const formatDate = (d) => d.toISOString().split('T')[0]` 改写成
 *    `const formatDate = (d) => formatDate(d)` —— 自我递归、运行时栈溢出,
 *    而带显式 `: string` 返回标注的那 4 个文件 **tsc 一个字都没报**。
 *    ⇒ 判据必须自己扫源码, 不能拿「tsc 绿」当判据。
 */

const SRC = path.join(__dirname, '..', '..', '..');

// EXPR.toISOString().split('T')[0] / .slice(0,10) / .substring(0,10)
const TRUNCATION = /toISOString\(\)\s*(?:\.split\(\s*['"]T['"]\s*\)\s*\[\s*0\s*\]|\.slice\(\s*0\s*,\s*10\s*\)|\.substring\(\s*0\s*,\s*10\s*\))/;

const SKIP_DIRS = new Set(['__tests__', 'node_modules', '.expo', 'dist', 'build']);

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (SKIP_DIRS.has(entry.name)) continue;
      walk(path.join(dir, entry.name), out);
    } else if (/\.tsx?$/.test(entry.name) && !/\.(test|spec)\.tsx?$/.test(entry.name)) {
      out.push(path.join(dir, entry.name));
    }
  }
  return out;
}

/**
 * 逐行扫, 跳过注释行。
 *
 * ⚠️ 本仓栽过好几次「闸把自己写的注释当成被测对象」—— 这道闸的三处剩余命中
 *    当初全是注释(其中一条正是解释这个缺陷的那句), 不剔掉就是纯误报。
 * ⛔ 不做完整的块注释解析: 行首 `//` 和 `*`(块注释续行) 覆盖了本仓的实际写法,
 *    再复杂只会做出一个更难懂、误报更多的东西(形态 E)。
 */
function offendingLines(code: string): string[] {
  return code
    .split(String.fromCharCode(10))
    .map((line, i) => ({ line: line.trim(), no: i + 1 }))
    .filter(({ line }) => !line.startsWith('//') && !line.startsWith('*') && !line.startsWith('/*'))
    .filter(({ line }) => TRUNCATION.test(line))
    .map(({ line, no }) => no + ': ' + line.slice(0, 110));
}

describe('闸: 取本地日历日不许写成 UTC 截断', () => {
  const files = walk(path.join(SRC, 'screens'))
    .concat(walk(path.join(SRC, 'services')))
    .concat(walk(path.join(SRC, 'components')))
    .concat(walk(path.join(SRC, 'utils')))
    .concat(walk(path.join(SRC, 'hooks')));

  it('阳性对照: 确实扫到了成规模的源码(扫 0 个文件的闸永远是绿的)', () => {
    expect(files.length).toBeGreaterThan(200);
    const total = files.reduce((n, f) => n + fs.readFileSync(f, 'utf8').length, 0);
    expect(total).toBeGreaterThan(1_000_000);
  });

  it('阳性对照: 这个正则真的认得出缺陷长相(不是恒不匹配)', () => {
    expect(TRUNCATION.test("d.toISOString().split('T')[0]")).toBe(true);
    expect(TRUNCATION.test('d.toISOString().slice(0, 10)')).toBe(true);
    expect(TRUNCATION.test('d.toISOString().substring(0,10)')).toBe(true);
    // 阴性: 整串 ISO 是时间戳语义, 不该被这道闸管
    expect(TRUNCATION.test('createdAt: new Date().toISOString()')).toBe(false);
  });

  it('🔴 一处都不许有 —— 用 formatDate() 取本地日历日', () => {
    const offenders: Record<string, string[]> = {};
    for (const f of files) {
      const hits = offendingLines(fs.readFileSync(f, 'utf8'));
      if (hits.length) offenders[path.relative(SRC, f).split(path.sep).join('/')] = hits;
    }
    expect(offenders).toEqual({});
  });

  it('🔴 不许有自我递归的日期 helper(清扫时真的造出过, 而 tsc 没报)', () => {
    const recursive: string[] = [];
    for (const f of files) {
      const code = fs.readFileSync(f, 'utf8');
      for (const name of ['formatDate', 'formatLocalYmd']) {
        // const NAME = (...) => ... NAME(   /   function NAME(...) { ... NAME(
        const re = new RegExp('(?:const|function)\\s+' + name + '\\b[^;{]*(?:=>|\\{)[^;}]*\\b' + name + '\\s*\\(');
        if (re.test(code)) recursive.push(path.relative(SRC, f).split(path.sep).join('/') + ' -> ' + name);
      }
    }
    expect(recursive).toEqual([]);
  });
});
