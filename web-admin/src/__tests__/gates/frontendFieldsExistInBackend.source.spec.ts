import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 前端读的字段名，后端得真的产出 —— 棘轮，只许变少。
 *
 * <h2>为什么需要它：同一形态 2026-08-28 一晚出现三次</h2>
 *
 * <pre>
 *   质检标准页   statistics.enabledItems         后端 0 处 → `|| 0` → 卡片显示「已启用 0」
 *                                                而同屏表格里那条写着「启用」
 *   财务概览页   overview.monthlyTransactionCount 后端 0 处 → 显示「本月交易笔数 0」
 *                                                而下面列表里本月交易一大把
 *   采购单详情   order.unpaidAmount / paymentDueDate  后端实体上根本没有这些列
 * </pre>
 *
 * 三次都是同一个机制：**前端读了一个后端从不产出的字段 → `undefined` → 被 `|| 0` 兜底
 * → 显示成一个很有说服力的 0**。而 0 在这些位置上，和真实读数长得一模一样。
 *
 * ⚠️ 前两个是 2026-08-28 用浏览器逐屏走查（视觉验收）才发现的 ——
 * 接口层、类型检查、单测**全都抓不到**：TS 里那个对象是 `any`/`TableRow`，
 * 后端是 `Map<String,Object>`，两边都没有类型约束跨过那条线。
 *
 * <h2>判据（故意宽松，为了几乎不误报）</h2>
 *
 * <ol>
 *   <li>只看 <code>.vue</code> 的 <code>&lt;template&gt;</code> 段</li>
 *   <li>只看**直接从接口响应整体赋值**的 ref（<code>xxx.value = res.data</code> 这种形状）</li>
 *   <li>断言 <code>xxx.someField</code> 里的 <code>someField</code> 在
 *       <b>Java 或 Python 后端源码里出现过</b></li>
 * </ol>
 *
 * <p>⛔ <b>不</b>要求它出现在同一个端点的响应里 —— 那需要跨语言类型推导。
 * 这里只抓最硬的那一类：<b>整个后端都没有这个名字</b>。
 * 于是它会漏（名字在别处出现就放过），但几乎不会误报 ——
 * 这正是要的方向：<b>宁可窄而可信，不要宽而被关掉</b>。</p>
 *
 * <p>⚠️ 语料必须同时含 Java 和 Python：第一版只扫 Java，把 SmartBI / 餐饮分析
 * （Python 后端服务的）那几个报成了违例。加上 Python 之后 21 → 17。
 * <b>少一个语料 = 造一批假阳性。</b></p>
 *
 * <h2>为什么是棘轮而不是硬红</h2>
 *
 * <p>建闸当天存量 <b>17 处</b>，其中一部分是**已经登记过的挂账**——
 * 比如 <code>procurement/orders/detail.vue</code> 里那段注释白纸黑字写着
 * 「实体上没有这些列 …… ⛔ 不要写成『这个字段不需要』—— 财务需要它，是后端还没下发」
 * 并给了恢复条件。⇒ 一上来硬红就是一道当天被关掉的闸。</p>
 *
 * <p>⇒ 冻住**具体名单**（不是只冻一个数字）：新增的红，已有的放行。
 * ⚠️ 冻名单而不是冻计数 —— 修好一个同时新增一个时计数不变，而名单会变。</p>
 */

const HERE = resolve(__dirname);
const WEB = resolve(HERE, '../..');           // web-admin/src
const REPO = resolve(WEB, '../..');           // repo root
const VIEWS = join(WEB, 'views');
const CORPORA: Array<[string, string]> = [
  [join(REPO, 'backend', 'java', 'cretas-api', 'src', 'main', 'java'), '.java'],
  [join(REPO, 'backend', 'python'), '.py'],
];

/**
 * 建闸当天（2026-08-28）的存量。⛔ 只许变少。
 *
 * 新增一条就红；要放行必须显式加进来，并写清楚为什么后端不产出它。
 */
const BASELINE: ReadonlySet<string> = new Set([
  'views/calibration/CalibrationDetailView.vue :: session.sessionType',
  'views/calibration/CalibrationDetailView.vue :: session.targetMetrics',
  'views/calibration/CalibrationListView.vue :: statistics.averageImprovement',
  // 🔎 2026-08-28 视觉验收当场抓到的：KPI 显示「本月交易笔数 0」而列表里本月交易一大把
  // 📌 已登记挂账：详见该文件里那段注释（后端实体上没有这些列，等后端下发）
  'views/procurement/orders/detail.vue :: order.paymentDueDate',
  'views/procurement/orders/detail.vue :: order.unpaidAmount',
  'views/scheduling/index.vue :: dashboard.todaySchedules',
  'views/scheduling/realtime/index.vue :: dashboard.lineStats',
  'views/scheduling/realtime/index.vue :: dashboard.todaySchedules',
  'views/smart-bi/RestaurantV2Dashboard.vue :: reviewStats.lastUpload',
]);

function walk(dir: string, ext: string, out: string[] = []): string[] {
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    if (name === 'node_modules' || name === '__pycache__' || name.startsWith('venv')) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, ext, out);
    else if (name.endsWith(ext)) out.push(p);
  }
  return out;
}

function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .split('\n')
    .map(l => l.replace(/\/\/.*$/, ''))
    .join('\n');
}

/** 后端(Java + Python)源码里出现过的全部标识符。 */
function backendIdentifiers(): { words: Set<string>; fileCount: number } {
  const words = new Set<string>();
  let fileCount = 0;
  for (const [root, ext] of CORPORA) {
    for (const f of walk(root, ext)) {
      fileCount++;
      for (const m of readFileSync(f, 'utf8').matchAll(/[A-Za-z_][A-Za-z0-9_]*/g)) {
        words.add(m[0]);
        // 🔴 2026-08-28: Python 侧用 snake_case, 前端读的是 camelCase ——
        //   `total_balance` 与 `totalBalance` 在这把尺子眼里是两个词, 于是
        //   **7 条误报**全出在同一个 Python 后端的屏上(target-hierarchy.vue):
        //   monthTarget / costDataSufficient / marginAmount / targetMargin /
        //   dataMissing / elapsedPct / (costDataSufficient 两处)。
        //   逐条核对过后端确实有对应的 snake_case 字段。
        // ⇒ 把 snake_case 也归一成 camelCase 加进语料。基线因此 17 → 10。
        if (m[0].includes('_')) {
          const parts = m[0].split('_');
          words.add(parts[0] + parts.slice(1).map(x => x.charAt(0).toUpperCase() + x.slice(1)).join(''));
        }
      }
    }
  }
  return { words, fileCount };
}

function violations(words: Set<string>): string[] {
  const found: string[] = [];
  for (const f of walk(VIEWS, '.vue')) {
    const raw = readFileSync(f, 'utf8');
    const refs = new Set<string>();
    for (const m of raw.matchAll(/(\w+)\.value\s*=\s*\w*[Rr]es\w*\.(?:value\.)?data/g)) refs.add(m[1]);
    for (const m of raw.matchAll(/(\w+)\.value\s*=\s*response\.data/g)) refs.add(m[1]);
    if (refs.size === 0) continue;
    const ti = raw.indexOf('<template>');
    const te = raw.lastIndexOf('</template>');
    if (ti < 0 || te < 0) continue;
    const tpl = stripComments(raw.slice(ti, te));
    const rel = relative(WEB, f).split(sep).join('/');
    for (const ref of refs) {
      for (const m of tpl.matchAll(new RegExp(`\\b${ref}\\.([A-Za-z][A-Za-z0-9_]*)`, 'g'))) {
        const field = m[1];
        if (field === 'value' || field === 'length') continue;
        if (!words.has(field)) found.push(`${rel} :: ${ref}.${field}`);
      }
    }
  }
  return [...new Set(found)].sort();
}

describe('跨语言字段名: 前端读的, 后端得真的产出', () => {
  const { words, fileCount } = backendIdentifiers();

  it('阳性对照: 后端语料真的读到了(否则「找不到」全是假的)', () => {
    // 🔴 少了这一条, 语料为空时【每一个】前端字段都会被报成违例
    expect(fileCount).toBeGreaterThan(1_000);
    expect(words.size).toBeGreaterThan(50_000);
    expect(words.has('getStatistics')).toBe(true);   // Java 侧确实读到了
    expect(words.has('factory_id')).toBe(true);      // Python 侧确实读到了
  });

  it('阴性对照: 这把尺子认得出一个不存在的标识符', () => {
    expect(words.has('__NoSuchIdentifierEverWritten__')).toBe(false);
  });

  it('⛔ 不许新增: 前端读了一个后端整个源码里都没有的字段名', () => {
    const now = violations(words);
    const added = now.filter(v => !BASELINE.has(v));
    expect(
      added,
      `这些字段前端读了但 Java/Python 后端【整个源码里都没有这个名字】——\n` +
        `  ${added.join('\n  ')}\n` +
        `⇒ 页面上它会是 undefined, 而 \`|| 0\` 之类的兜底会把它显示成一个很有说服力的 0。\n` +
        `修法: 要么后端产出它, 要么前端改成后端真有的名字。\n` +
        `⛔ 不要直接加进 BASELINE —— 那是给【已登记挂账】留的口子, 不是给新债留的。`,
    ).toEqual([]);
  });

  it('存量只许变少（修好了就把它从 BASELINE 里删掉）', () => {
    const now = new Set(violations(words));
    const stale = [...BASELINE].filter(v => !now.has(v));
    expect(
      stale,
      `这些已经不在违例里了 —— 修好了是好事, 把它们从 BASELINE 删掉, 棘轮才收得紧:\n  ${stale.join('\n  ')}`,
    ).toEqual([]);
  });
});
