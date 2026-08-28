/**
 * `t('...')` 取不到译文时，i18next 把 **key 本身**打在屏幕上。
 *
 * ## 为什么需要这一条（2026-08-28 逐屏走查实测）
 *
 * 「添加员工」整屏的字段名长这样：
 *
 * ```
 * 添加员工
 * staff.add.sections.basicInfo
 * staff.add.fields.username
 * staff.add.fields.fullName
 * staff.add.fields.phone
 * ...
 * ```
 *
 * 成因：`zh-CN/hr.json` 里 `staff.add` 只有 `{title, success, failed}` 三个键，
 * 屏上用到的另外 21 个一个都没有。**i18next 取不到不报错、不抛异常**，
 * 就把 key 原样渲染出来。于是 HR 主管分不清哪个框填手机号、哪个填邮箱 ——
 * 而这是这个角色最主要的录入入口。
 *
 * ⚠️ 这一类对 **TypeScript / 单测 / 接口检查全瞎**：`t()` 的入参是字符串，
 * 谁也不会去核对那个字符串在 JSON 里存不存在。**只有把屏打开看才发现得了**，
 * 而且还得恰好打开那一屏。
 *
 * 首轮全仓扫描：**5041 个字面量 key 里 564 个取不到**（40 个文件）。
 * 本闸建立时已修掉 45 个（`hr` 39 + `smartbi` 3 + 考勤失败态 2×2）。
 *
 * ## 判据（棘轮，只禁增长）
 *
 * 一次性硬红会得到一道当天被关掉的闸（形态 E）。所以钉的是**不许再涨**，
 * 并且另有两条「必发生」读数当仪器自检：扫到的文件数 / 检查的 key 数。
 * 它们掉到地板以下 = 扫描器死了，与「代码变干净了」完全是两回事。
 *
 * ## ⚠️ 代理判据与已知盲区（写明，⛔ 不靠加启发式逼近）
 *
 *  · **只认字面量 key**：`t(\`a.${x}\`)` / `t(someVar)` 一律看不见。
 *  · **只查命名空间唯一的文件**：一个文件里出现两个 `useTranslation('x'|'y')` 时
 *    无法判断某个 key 属于哪一个，直接跳过（跳过数量也打出来）。
 *  · **带 `defaultValue` 的不算缺失**：那时候屏上显示的是兜底文案，不是 key。
 *  · 只查 `zh-CN`。en-US 的缺口不在本闸范围内。
 *  · ⇒ 因此 564 / 519 都是**下界**，不是全集。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.join(__dirname, '../..');
const LOCALE_DIR = path.join(SRC, 'i18n/locales/zh-CN');

/** 2026-08-28 建闸时的实测值。⛔ 只许降，不许升。 */
// 2026-08-28 收紧：519 -> 486。
//
// ⚠️ 两个数**不是同一把尺子**量的，必须连口径一起报（否则会被读成只降了 33）：
//   · 同一把旧尺子：519 -> 482  —— 这 37 个是真的修好了
//     (25 处把 `t('ns.key')` 改成 i18next 的跨 namespace 写法 `t('ns:key')`,
//      18 处的键其实在另一个 namespace 里)
//   · 换成新尺子后：482 -> 486  —— 涨的 4 个不是退步, 是闸原来**看不见**冒号写法,
//     现在能查了, 于是查出 4 处目标确实不存在的跨 ns 调用。
//
// ⛔ 剩下的 486 里绝大多数(platform 128 / home 114 / management 86)在**走查从没到过的树**里。
// 它们需要的是「照着屏上写文案」, 不是凭空造 400 条中文 —— 那正是这一轮在清的东西。
// ⇒ 下一步应当先走那三棵树, 边走边写, ⛔ 不要批量填。
const MISSING_KEY_BUDGET = 486;

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name !== 'node_modules' && name !== '__tests__') walk(full, acc);
    } else if (/\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

function loadBundles(): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const f of fs.readdirSync(LOCALE_DIR)) {
    if (f.endsWith('.json')) out[f.replace(/\.json$/, '')] = JSON.parse(fs.readFileSync(path.join(LOCALE_DIR, f), 'utf8'));
  }
  return out;
}

function lookup(bundles: Record<string, unknown>, ns: string, key: string): string | null {
  // i18next 跨 namespace 写法 `t('other:some.key')` —— 冒号是默认 nsSeparator,
  // 与 useTranslation() 声明的 ns 无关。
  //
  // ⚠️ 2026-08-28: 闸原来既不匹配也不解析冒号, 这类调用**对它完全不可见**。
  // 把一批 t('common.yes')(在 ns=home 的文件里实际解析成 home.common.yes, 取不到)
  // 改成正确的 t('common:yes') 之后, 闸把改好的那些直接跳过了 ——
  // 一道看不见正确写法的闸, 既不会因为修好而变绿, 也不会因为写错而变红。
  const sep = key.indexOf(':');
  if (sep > 0) {
    return lookup(bundles, key.slice(0, sep), key.slice(sep + 1));
  }
  let cur: unknown = bundles[ns];
  for (const part of key.split('.')) {
    if (cur && typeof cur === 'object' && part in (cur as Record<string, unknown>)) {
      cur = (cur as Record<string, unknown>)[part];
    } else {
      return null;
    }
  }
  return typeof cur === 'string' ? cur : null;
}

const NS_RE = /useTranslation\(\s*(?:\[\s*)?['"]([\w-]+)['"]/g;
const T_RE = /\bt\(\s*['"]([A-Za-z][\w.:-]*)['"]\s*(,|\))/g;

type Scan = {
  filesChecked: number;
  keysChecked: number;
  missing: string[];
  skippedMultiNs: number;
};

function scan(): Scan {
  const bundles = loadBundles();
  const res: Scan = { filesChecked: 0, keysChecked: 0, missing: [], skippedMultiNs: 0 };

  for (const file of walk(SRC)) {
    const txt = fs.readFileSync(file, 'utf8');
    const nss = new Set<string>();
    for (const m of txt.matchAll(NS_RE)) if (m[1]) nss.add(m[1]);
    if (nss.size === 0) continue;
    if (nss.size > 1) {
      res.skippedMultiNs += 1;
      continue;
    }
    const ns = [...nss][0]!;
    res.filesChecked += 1;

    for (const m of txt.matchAll(T_RE)) {
      const key = m[1]!;
      if (!key.includes('.') && !key.includes(':')) continue; // 裸词多半是变量, 不是 key path
      if (m[2] === ',') {
        // 带 defaultValue 的调用: 取不到 key 时屏上是兜底文案, 不是 key
        const window = txt.slice(m.index! + m[0].length, m.index! + m[0].length + 200);
        if (window.includes('defaultValue')) continue;
      }
      res.keysChecked += 1;
      if (lookup(bundles, ns, key) === null) res.missing.push(`${ns}:${key}`);
    }
  }
  return res;
}

describe('每个字面量 t() 键都要取得到译文', () => {
  const result = scan();

  it('扫描器自检：文件数与 key 数没有掉到地板以下', () => {
    // 2026-08-28 实测: 249 个文件 / 5041 个 key。留足余量, 只拦「仪器死了」。
    expect(result.filesChecked).toBeGreaterThan(150);
    expect(result.keysChecked).toBeGreaterThan(3000);
  });

  it('扫描器自检：绝大多数 key 是取得到的（否则是查表坏了，不是应用坏了）', () => {
    const missRate = result.missing.length / Math.max(1, result.keysChecked);
    expect(missRate).toBeLessThan(0.5);
  });

  it(`缺失的键不许再增加（棘轮，当前预算 ${MISSING_KEY_BUDGET}）`, () => {
    const sample = [...new Set(result.missing)].sort().slice(0, 15).join('\n  ');
    if (result.missing.length > MISSING_KEY_BUDGET) {
      throw new Error(
        `缺失键 ${result.missing.length} 个 > 预算 ${MISSING_KEY_BUDGET}。\n` +
          `新增的键要么补进 src/i18n/locales/zh-CN/，要么给 t() 加 defaultValue。\n` +
          `⛔ 不要直接调高预算。样例:\n  ${sample}`,
      );
    }
    expect(result.missing.length).toBeLessThanOrEqual(MISSING_KEY_BUDGET);
  });
});
