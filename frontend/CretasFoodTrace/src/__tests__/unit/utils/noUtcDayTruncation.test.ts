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
 * 🔴 2026-08-26 第二轮: 第一版只走 `.ts`/`.tsx`, **漏掉了 `.js`** ——
 *    `services/api/audit-api-clients.js` 里还有一处, 而闸看不见它,
 *    于是「0 处」是个假读数。扩展名过滤自己造出了盲区。
 *    ⇒ 现在覆盖 ts/tsx/js/jsx/mjs/cjs, 并由下面那条自检钉住:
 *    **闸必须真的扫到非 .ts 文件**, 否则它随时会被悄悄收窄回去。
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
    } else if (/\.(tsx?|jsx?|mjs|cjs)$/.test(entry.name) && !/\.(test|spec)\.(tsx?|jsx?)$/.test(entry.name)) {
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
/**
 * 具名豁免: 这些地方**必须**用 UTC 读回, 不是缺陷。
 *
 * ⛔ 加豁免的门槛: 那个 Date 是**刻意构造的 UTC 时刻**(不是「此刻」),
 *    用本地 getter 读回反而会叠上设备时区偏移 —— 也就是说,
 *    在这些地方本闸的规则是**反的**。
 * ⚠️ 豁免必须具名到「文件 + 那一行的特征串」, ⛔ 不许整文件放行。
 *
 * 🔴 2026-08-27: **当前为空 —— 一条豁免都不需要。**
 *    唯一那条(`AIChatScreen.tsx` 的 `shanghaiOffsetMs`)已经随
 *    `utils/shanghaiDate.ts` 抽出而消失: 那个 helper 改成「推 8 小时后读 **UTC 分量**」,
 *    根本不用截断 ISO 串, 于是本闸的规则在那儿也不再是「反的」。
 *    ⇒ 机制留着(下次真需要时不用重建), 但**清单空着才是好状态**。
 *    ⛔ 往这里加条目前先问一句: 换个写法能不能让它压根不命中本闸?
 */
const ALLOWLIST: ReadonlyArray<{ file: string; contains: string; why: string }> = [];

function offendingLines(code: string, rel: string): string[] {
  const allowed = ALLOWLIST.filter((a) => rel === a.file);
  return code
    .split(String.fromCharCode(10))
    .map((line, i) => ({ line: line.trim(), no: i + 1 }))
    .filter(({ line }) => !line.startsWith('//') && !line.startsWith('*') && !line.startsWith('/*'))
    .filter(({ line }) => TRUNCATION.test(line))
    .filter(({ line }) => !allowed.some((a) => line.includes(a.contains)))
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

  it('🔴 阳性对照: 闸必须真的扫到非 .ts 文件(扩展名过滤造过一次盲区)', () => {
    // 第一版只走 ts/tsx, 漏掉了 services/api/audit-api-clients.js 里的一处,
    // 「0 处」于是是个假读数。这条钉住覆盖面, 防止有人把过滤悄悄收窄回去。
    const nonTs = files.filter((f) => !/\.tsx?$/.test(f));
    expect(nonTs.length).toBeGreaterThan(0);
  });

  it('阳性对照: 这个正则真的认得出缺陷长相(不是恒不匹配)', () => {
    expect(TRUNCATION.test("d.toISOString().split('T')[0]")).toBe(true);
    expect(TRUNCATION.test('d.toISOString().slice(0, 10)')).toBe(true);
    expect(TRUNCATION.test('d.toISOString().substring(0,10)')).toBe(true);
    // 阴性: 整串 ISO 是时间戳语义, 不该被这道闸管
    expect(TRUNCATION.test('createdAt: new Date().toISOString()')).toBe(false);
  });

  it('🔴 豁免清单不许过期 —— 每条都必须仍能对上一处真实代码', () => {
    // 豁免最容易的坏法是**留在那儿放行别的东西**: 被豁免的代码删了/改了,
    // 清单却没跟着删, 于是它开始默默放行一个本该被拦的新写法。
    // ⇒ 每条豁免都要能指到一处**当前确实存在**的命中。
    //
    // ⚠️ 清单为空时这条 for 循环零次迭代 —— 那样它就是个**恒真式**(形态 B′),
    //    绿得毫无信息。所以先把「有几条」显式断言出来: 读这份报告的人
    //    看到的是「当前 0 条豁免」这个事实, 而不是一个什么都没查的绿。
    expect({ 当前豁免条数: ALLOWLIST.length })
      .toEqual({ 当前豁免条数: 0 });

    for (const a of ALLOWLIST) {
      const full = path.join(SRC, a.file);
      // ⚠️ jest 的 expect **不支持**第二个 message 参数(那是 vitest 的写法),
      //    写了会 TS2554 编译失败 -> `Tests: 0 total`(没跑, 不是绿)。
      //    ⇒ 把上下文放进被断言的对象里。
      expect({ 豁免文件: a.file, 存在: fs.existsSync(full) })
        .toEqual({ 豁免文件: a.file, 存在: true });
      const code = fs.readFileSync(full, 'utf8');
      const still = code
        .split(String.fromCharCode(10))
        .some((l) => l.includes(a.contains) && TRUNCATION.test(l));
      expect({ 豁免: a.file + ' / ' + a.contains, 仍然命中本闸: still })
        .toEqual({ 豁免: a.file + ' / ' + a.contains, 仍然命中本闸: true });
    }
  });

  it('🔴 一处都不许有 —— 用 formatDate() 取本地日历日', () => {
    const offenders: Record<string, string[]> = {};
    for (const f of files) {
      const rel = path.relative(SRC, f).split(path.sep).join('/');
      const hits = offendingLines(fs.readFileSync(f, 'utf8'), rel);
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
