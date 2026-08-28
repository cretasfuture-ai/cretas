/**
 * 一个 navigator 里不许出现两个同名 Screen。
 *
 * ## 为什么需要这一条（2026-08-28 实测）
 *
 * `WHHomeStackNavigator.tsx` 里「仓库地图 / 库位」那 12 行（注释 + `WHLocationManage`
 * + `WHTempMonitor`）被**一字不差地贴了两遍**。React Navigation 在**挂载时**抛：
 *
 *     A navigator cannot contain multiple 'Screen' components with the same name
 *     (found duplicate screen named 'WHLocationManage')
 *
 * 后果不是某个屏打不开，是**整个 App 白屏**：`App.tsx` 没有顶层 ErrorBoundary，
 * 异常直接把 React 树卸载掉。`WHHomeStackNavigator` 只被 `WarehouseManagerTabNavigator`
 * 挂载 ⇒ 仓储主管这个角色登录之后**只看得到一片白**，没有任何报错文案。
 *
 * 引入它的是 `751b0d48d9 (#239)`，2026-08-27 23:25 合入 main。
 *
 * ## 既有的闸为什么全是绿的
 *
 * 重复路由名是 **React Navigation 的运行时不变量**：
 *
 *  · `navigatorMountContextReachability` 是**静态 AST 矛盾检测**，它不挂载 navigator，
 *    因此看不见任何「挂载时才抛」的东西。
 *  · `screenReachability` / `roleReachability` 问的是「这个名字有没有人到得到」。
 *    重复注册**不会**让一个名字变得不可达，两道闸都不会因此变红。
 *  · TypeScript 也不管：两个 `<Stack.Screen name="X">` 各自类型都对。
 *
 * ⇒ 这一类只有「真挂载一次」或「数名字」能抓到。本闸做后者。
 *
 * ## ⚠️ 代理判据（写明，⛔ 不靠加启发式逼近）
 *
 * 「同一个 navigator」用 **`<X.Screen>` 里的那个 `X`（同一文件内）** 当代理。
 * 本仓的写法是每个 navigator 一个 `const X = createXNavigator()`，所以成立；
 * 但这是代理，不是证明。它会漏掉的形态：
 *
 *  · 同一个变量在一个文件里被用于两个真正不同的 navigator（本仓无此写法）
 *  · `{cond && <Stack.Screen name="X"/>}` 与另一处 `name="X"` 互斥 ——
 *    本闸会**误报**。真出现时把它列进 `ALLOWED_CONDITIONAL_DUPES` 并写明理由，
 *    ⛔ 不要放宽判据本身。
 *  · 动态生成的 `name={expr}` —— 读不出来，本闸对它无能为力。
 *
 * ## 🔴 自检（⛔ 不要删）
 *
 * 「没查出重复」和「扫描器根本没扫到东西」读数一样。所以除了断言无重复，
 * 另有两条**必发生**读数：扫到的 navigator 数 / 扫到的注册数。掉到地板以下 = 仪器死了。
 */
import fs from 'fs';
import path from 'path';

const NAV_DIR = path.join(__dirname, '../../navigation');

/** ⛔ 剥注释：注释里成块躺着的 <Stack.Screen> 不是注册。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) walk(full, acc);
    else if (/\.tsx$/.test(name)) acc.push(full);
  }
  return acc;
}

/** 互斥分支里的同名注册，逐条登记 + 写理由。空着说明目前没有。 */
const ALLOWED_CONDITIONAL_DUPES: ReadonlyArray<{ file: string; navigator: string; route: string; why: string }> = [];

type Reg = { file: string; navigator: string; route: string };

function collectRegistrations(): Reg[] {
  const out: Reg[] = [];
  for (const file of walk(NAV_DIR)) {
    const src = stripComments(fs.readFileSync(file, 'utf8'));
    const re = /<(\w+)\.Screen\b([^>]*?)\/?>/gs;
    let m: RegExpExecArray | null;
    while ((m = re.exec(src)) !== null) {
      const navigator = m[1];
      const attrs = m[2];
      if (!navigator || !attrs) continue;
      const route = /\bname\s*=\s*["']([^"']+)["']/.exec(attrs)?.[1];
      if (!route) continue; // dynamic name -- out of this gate's reach
      out.push({ file: path.relative(NAV_DIR, file).replace(/\\/g, '/'), navigator, route });
    }
  }
  return out;
}

describe('navigator route names are unique within each navigator', () => {
  const regs = collectRegistrations();

  it('扫描器自检：扫到的 navigator 与注册数量没有掉到地板以下', () => {
    const navigators = new Set(regs.map((r) => `${r.file}:${r.navigator}`));
    // 2026-08-28 实测: 60 个 navigator / 645 条注册。留足余量，只拦「仪器死了」。
    expect(navigators.size).toBeGreaterThan(30);
    expect(regs.length).toBeGreaterThan(300);
  });

  it('同一个 navigator 里没有重复的 Screen name', () => {
    const seen = new Map<string, number>();
    for (const r of regs) {
      const key = `${r.file}:${r.navigator}:${r.route}`;
      seen.set(key, (seen.get(key) ?? 0) + 1);
    }

    const allowed = new Set(
      ALLOWED_CONDITIONAL_DUPES.map((a) => `${a.file}:${a.navigator}:${a.route}`),
    );

    const dupes = [...seen.entries()]
      .filter(([key, n]) => n > 1 && !allowed.has(key))
      .map(([key, n]) => `${key} 注册了 ${n} 次`);

    expect(dupes).toEqual([]);
  });
});
