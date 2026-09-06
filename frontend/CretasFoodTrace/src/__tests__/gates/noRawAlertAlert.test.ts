/**
 * 闸: `Alert.alert(` 从 RN 源码里退场 (react-native-web 上它是 `static alert() {}` —— 什么都不显示,
 * 回调永不触发; 2026-09-06 普查 1672 处 / 319 文件, 171 处是 ≥2 按钮的决策类 = web 上死按钮)。
 *
 * 替代是同签名的 `appAlert` (`src/components/ui/AppDialog.tsx`), 宿主已挂在 App.tsx 根。
 *
 * ## 棘轮 (按顶层目录)
 * - 已收敛的目录 (工人屏三目录, codemod 第一刀 2026-09-06): **必须为 0**, 新加一处就红。
 * - 其余目录: 各自不许增长 (基线 = 2026-09-06 实测); 下一刀收敛哪个目录就把它移进 ZERO 集合。
 * - `screens/restaurant/**`: 餐饮不管 (Steve 2026-09-06), 只登记不计。
 *
 * ## 三态
 * - 扫到的文件数 / 总调用数低于地板 ⇒ INSTRUMENT DEAD (剥注释的正则打空与「全清了」长得一样)。
 *
 * 剥注释后再数 —— `AppDialog.tsx` 的说明里就提到 `Alert.alert(`, 不剥会把闸自己的文案数进去。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.resolve(__dirname, '../..');

function stripComments(src: string): string {
  // 整行 // 与 行尾 //  都剥 (#678 终审 follow-up d: 之前只剥整行, `foo(); // Alert.alert(` 会被数进去)。
  // ⚠️ 已知盲区 (2026-09-07 订正: 上一版把形状写错了)。行尾那条要求 `//` 前有**空白**,
  //    所以 `'http://…'` 这种紧跟在 `:` 后面的**不受影响**; 真盲区是字符串字面量里的 ` //`
  //    (带前导空格), 全树 19 行, 其中 0 行后面跟着 `Alert.alert(` ⇒ 今天不漏数。
  //    登记, 不靠再加启发式逼近 —— 要精确只能上 AST。
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ').replace(/\s\/\/.*$/gm, ' ');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) {
      if (name === 'node_modules' || name === '__tests__' || name === 'test' || name === 'mocks') continue;
      walk(full, acc);
    } else if (/\.(ts|tsx)$/.test(name) && !/\.(test|spec)\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

/** 顶层桶: screens/<dir> 或 src 下一级目录名 */
function bucketOf(rel: string): string {
  const parts = rel.split('/');
  return parts[0] === 'screens' ? `screens/${parts[1] ?? ''}` : (parts[0] ?? '');
}

/** 已收敛 ⇒ 必须为 0 */
const ZERO = new Set(['screens/processing', 'screens/warehouse', 'screens/quality-inspector']);
/** 餐饮不管: 不进棘轮, 但钉死在实测值 (见下) */
const IGNORED = new Set(['screens/restaurant']);
/** 2026-09-06 实测 screens/restaurant 的 Alert.alert( 数; 餐饮屏不动 ⇒ 这个数不该变 */
const RESTAURANT_PINNED = 51;
/** 2026-09-06 实测 (codemod 第一刀之后), ⛔ 只许往下调 */
const BASELINE: Record<string, number> = {
  'screens/factory-admin': 357,
  'screens/platform': 297,
  'screens/management': 198,
  'screens/dispatcher': 154,
  components: 51,
  'screens/hr': 40,
  'screens/reports': 26,
  hooks: 23,
  'screens/attendance': 22,
  'screens/workshop-supervisor': 19,
  'screens/production': 17,
  'screens/factory': 16,
  'screens/alerts': 14,
  'screens/auth': 12,
  'screens/profile': 11,
  'screens/demo': 10,
  'screens/rd': 9,
  'screens/smartbi': 8,
  'screens/lowcode': 7,
  'screens/operations': 7,
  services: 7,
  'screens/common': 6,
  'screens/shared': 6,
  'screens/work': 6,
  'screens/logistics': 3,
  'screens/main': 3,
  'screens/settings': 3,
  'screens/traceability': 2,
  utils: 2,
};

const FILE_FLOOR = 300;

describe('Alert.alert ratchet (react-native-web no-op)', () => {
  const files = walk(SRC);
  const counts: Record<string, number> = {};
  let total = 0;
  for (const f of files) {
    const rel = path.relative(SRC, f).split(path.sep).join('/');
    // 替代品自己的原生兜底里合法地调 Alert.alert (宿主未挂载时退回原生) —— 不算「裸调」
    if (rel === 'components/ui/AppDialog.tsx') continue;
    const n = (stripComments(fs.readFileSync(f, 'utf8')).match(/\bAlert\.alert\(/g) ?? []).length;
    if (!n) continue;
    const b = bucketOf(rel);
    counts[b] = (counts[b] ?? 0) + n;
    total += n;
  }

  it('INSTRUMENT ALIVE: 扫到了足够多的源文件, 且未收敛目录里仍数得到调用 (全 0 = 正则打空, 不是全清了)', () => {
    expect(files.length).toBeGreaterThan(FILE_FLOOR);
    const outsideZero = Object.entries(counts).filter(([b]) => !ZERO.has(b)).reduce((s, [, n]) => s + n, 0);
    expect(outsideZero > 0 ? 'alive' : 'INSTRUMENT DEAD — 未收敛目录一处都数不到, 先查正则').toBe('alive');
    console.warn(`[alert-ratchet] files=${files.length} total=${total} buckets=${Object.keys(counts).length}`);
  });

  it('codemod 过的目录里不许留下未用的 `Alert` import (tsc 抓不到 —— 没开 noUnusedLocals)', () => {
    // 2026-09-07: #687 我报「清完 0 个」是**假读数** —— 扫描器的正则只认单引号
    // `from 'react-native'`, 而仓里大量文件写的是双引号 `from "react-native"` ⇒ 漏了 18 个
    // warehouse 文件。organizer 用两种方法各自量出 20 个才发现。⇒ 判据做成闸, 不靠人扫。
    const offenders: string[] = [];
    for (const f of files) {
      const rel = path.relative(SRC, f).split(path.sep).join('/');
      const bucket = bucketOf(rel);
      if (!ZERO.has(bucket)) continue;           // 只管已收敛的目录; 其余目录还在用 Alert.alert
      const code = stripComments(fs.readFileSync(f, 'utf8'));
      const specs = code.match(/import\s*\{[^}]*\}\s*from\s*['"]react-native['"]/g) ?? [];
      const importsAlert = specs.some((spec) => {
        const inner = spec.slice(spec.indexOf('{') + 1, spec.lastIndexOf('}'));
        return inner.split(',').map((t) => t.trim()).includes('Alert');
      });
      if (importsAlert && !/\bAlert\s*\./.test(code)) offenders.push(rel);
    }
    expect(offenders.join(', ')).toBe('');
  });

  it('根宿主挂在 App.tsx 上 (没有它 appAlert 在 web 上退回 window.alert, 真机退回原生 —— 不是本刀想要的路径)', () => {
    const app = stripComments(fs.readFileSync(path.resolve(SRC, '..', 'App.tsx'), 'utf8'));
    expect(app).toContain('<AppDialogHost />');
    expect(app).toMatch(/import \{ AppDialogHost \} from '\.\/src\/components\/ui\/AppDialog'/);
  });

  it('已收敛目录 (工人屏三目录) 必须为 0 —— 新加一处 Alert.alert 就红', () => {
    const leaks = [...ZERO].map((b) => [b, counts[b] ?? 0] as const).filter(([, n]) => n > 0);
    // 断言串里带明细: 红的时候直接看到是哪个目录漏了几处
    expect(leaks.map(([b, n]) => `${b}=${n} (已改用 appAlert, 不许再写 Alert.alert)`).join(', ')).toBe('');
  });

  it('其余目录不许增长 (餐饮目录只登记)', () => {
    const grown: string[] = [];
    const unknown: string[] = [];
    for (const [b, n] of Object.entries(counts)) {
      if (ZERO.has(b) || IGNORED.has(b)) continue;
      const base = BASELINE[b];
      if (base === undefined) { unknown.push(`${b}=${n}`); continue; }
      if (n > base) grown.push(`${b}: ${base} → ${n}`);
    }
    if (IGNORED.size) console.warn(`[alert-ratchet] 餐饮 (登记不计): ${[...IGNORED].map((b) => `${b}=${counts[b] ?? 0}`).join(', ')}`);
    // #678 终审 follow-up (a): 「不管」不等于「不看」—— 餐饮目录钉死在 2026-09-06 实测值, 变了 (无论方向) 都红:
    // 涨 = 有人在餐饮屏写了新 Alert.alert; 降 = 有人动了餐饮屏 (Steve: 餐饮不管, 动了要有人知道)。
    expect(counts['screens/restaurant'] ?? 0).toBe(RESTAURANT_PINNED);
    console.warn(`[alert-ratchet] per-bucket: ${Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([b, n]) => `${b}=${n}`).join(' ')}`);
    expect(unknown.map((x) => `${x} (新目录出现 Alert.alert, 用 appAlert 或登记基线)`).join(', ')).toBe('');
    expect(grown.map((x) => `${x} (Alert.alert 只许减少)`).join(', ')).toBe('');
  });
});
