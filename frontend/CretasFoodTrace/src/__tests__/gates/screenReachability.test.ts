/**
 * 每个屏都得有人走得到 —— 棘轮，只禁增长。
 *
 * ## 为什么需要这一条
 *
 * 2026-08 同一个形态反复出现，四次都是**做完了、后端通了、屏也注册了，
 * 但没有任何地方能走过去**：
 *
 *   · web-admin `#87` 仓储空间 / `#96` 车间大屏 —— 菜单零登记，只能手打 URL；
 *     `#87` 的后果是生产 `factory_feature_config` 里 `WAREHOUSE_LAYOUT` 一行都没有
 *   · RN「草稿管理」 —— 报工失败存成草稿，而 `navigate('DraftReports')` 全仓调用数 **0**，
 *     用户的工作静默累积且无法恢复（已在 PR #130 接上）
 *   · RN「可配置质检项」 —— 后端 8 个端点 + 两张表 + 配置屏都齐活，
 *     `navigate('QualityCheckItemConfig')` 调用数 **0**，生产 3 条质检项 / **0 条绑定**
 *
 * ⇒ 「做完功能」这件事里，此前**没有任何一步是「确认用户能走到」**。
 *   web-admin 侧已有等价的闸（`web-admin/src/router/__tests__/routeReachability.spec.ts`，
 *   存量 91）；这是 RN 侧的那一道。
 *
 * ## 判据
 *
 * 一个屏算「可达」，满足其一即可：
 *   1. 它是 `Tab.Screen` —— 标签栏可见即可达
 *   2. 它是某个 Navigator 的落地页：显式 `initialRouteName`，
 *      **或**该 Navigator 里第一个声明的 Screen（React Navigation 的默认行为）
 *   3. 全仓**非 navigation 目录**的源码里出现了它的名字字面量 ——
 *      这一条同时覆盖 `navigate('X')` / `push('X')` / `replace('X')`
 *      和各处 `screen: 'X'` 的快捷操作登记表（本仓有十几张）
 *
 * 都不满足 = 只有写死跳转才进得去 = 对真实用户不存在。
 *
 * ## ⚠️ 代理性与已知盲区（写明，不靠加启发式逼近）
 *
 *  · **动态拼接的屏名看不见**：`navigate(\`${prefix}Detail\`)` 判不出，会误报。
 *    ⇒ 所以是**棘轮**而不是硬红，误报只需登记一次。
 *  · **不判权限**：注册了、跳得到，但当前角色看不到，本闸算可达。那是另一个问题。
 *  · **按状态条件渲染的屏看不见**：`AppNavigator` 里 `Loading` 只在 `!featuresLoaded`
 *    那个分支里注册，那时它是该 Navigator 唯一的 Screen ⇒ 它靠**状态**可达，不靠 navigate。
 *    本闸把它算成孤儿 —— **这是一个已知假阳性**，被棘轮预算吸收，⛔ 没有单独开白名单
 *    （白名单是藏东西的地方；预算里多一个已知假阳性，比多一个没人看的白名单诚实）。
 *  · **只看 RN**：web-admin 侧由它自己那道闸负责。
 *
 * ## 🔴 建闸时踩过的那一脚（留给下一个人）
 *
 * 第一版只把**显式** `initialRouteName` 当落地页，读出 **56** 个孤儿 ——
 * 其中 `DSHome` / `HRHome` / `BossOverview` / `FactoryAdminMain` / `Loading` 一眼就不对。
 * 真因：**React Navigation 在没写 `initialRouteName` 时，用第一个声明的 Screen 作落地页**，
 * 而本仓 65 个 navigator 里只有 9 处写了显式的。补上「隐式落地页」后是 **35**。
 *
 * ⇒ 判据要跟着框架的**默认行为**走，不是跟着显式写法走。
 *   21 个假阳性足以让一道闸当天被关掉（形态 E：窄而可信 > 宽而被关掉）。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.join(__dirname, '../..');
const NAV_DIR = path.join(SRC, 'navigation');

/** ⛔ 先剥注释：被注释掉的注册不算屏，注释里提到的名字也不算「有人跳过去」。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name !== 'node_modules') walk(full, acc);
    } else if (/\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

const NAV_FILES = walk(NAV_DIR);

/**
 * ⚠️ 用 path.sep 拼分隔符, ⛔ 不写任何反斜杠转义。
 * 建这道闸时就因为一层 heredoc 把 `/\\/g` 吃成 `/\/g`,
 * 文件当场语法错 —— 而它是**写入时**被吃掉的, 落盘那一刻就已经是坏的。
 */
const NAV_MARKER = path.sep + 'navigation' + path.sep;

const declared = new Map<string, string>(); // screen name -> navigator file
const landing = new Set<string>();          // 落地页(显式 + 隐式)

for (const file of NAV_FILES) {
  const src = stripComments(fs.readFileSync(file, 'utf8'));

  const screenHits: Array<{ name: string; comp: string; index: number }> = [];
  for (const m of src.matchAll(/<([A-Za-z]*)\.Screen\b([\s\S]{0,400}?)\/?>/g)) {
    const body = m[2];
    if (!body) continue;
    const name = /name=["']([A-Za-z0-9_]+)["']/.exec(body)?.[1];
    if (!name) continue;
    screenHits.push({ name, comp: m[1] ?? '', index: m.index ?? 0 });
    if (!declared.has(name)) declared.set(name, file);
    if (m[1] === 'Tab') landing.add(name); // 标签页可见即可达
  }

  for (const m of src.matchAll(/initialRouteName=\{?["']([A-Za-z0-9_]+)["']/g)) {
    if (m[1]) landing.add(m[1]);
  }

  // 隐式落地页：每个 Navigator 里第一个 Screen
  // 隐式落地页：每个 Navigator 里第一个 Screen。
  //
  // ⚠️ ⛔ 这里刻意**不**用 new RegExp(模板字符串) 去拼正则 —— 模板里 \b 是**退格符**、
  //    \s 是字母 s，正则会**静默地永不匹配**。本闸第一版正是如此：隐式落地页一个都没认出来，
  //    孤儿数从 35 虚报成 53，BossOverview / DispatcherMain 这些落地页全被误报成孤儿。
  //    改成复用上面已经解析好的 screenHits，不再构造任何带转义的正则。
  const navs = [...src.matchAll(/<([A-Za-z]*)\.Navigator\b/g)];
  navs.forEach((nav, i) => {
    const from = nav.index ?? 0;
    const to = i + 1 < navs.length ? navs[i + 1]?.index ?? src.length : src.length;
    const firstInThisNav = screenHits.find(
      (h) => h.comp === nav[1] && h.index > from && h.index < to,
    );
    if (firstInThisNav) landing.add(firstInThisNav.name);
  });
}

/**
 * 引用池：全部**非 navigation 目录**的源码。
 *
 * ⛔ 同样要剥注释 —— 否则一句 `// TODO: navigate('X')` 就能让 X 变「可达」，
 *    闸会把**打算做但没做**的事当成已经做了。本仓多次栽在「闸把注释当被测对象」上。
 */
const REFERENCES = walk(SRC)
  // ⛔ 排除本闸自己 —— 它的阴性对照里写着 'DSHome' / 'Loading' 这些屏名,
  //    若把自己算进引用池, 闸提到谁谁就「可达」。实测: 少算 1 个(Loading), 35 -> 34。
  //    本仓反复栽在「闸把自己的文字当成被测对象」上, 这是同一形状的又一例。
  .filter((f) => !f.includes(NAV_MARKER) && f !== __filename)
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

const ORPHANS = [...declared.keys()]
  .filter((name) => !landing.has(name))
  .filter((name) => !REFERENCES.includes(`'${name}'`) && !REFERENCES.includes(`"${name}"`))
  .sort();

/**
 * 建闸当天的存量：**35 个**。
 *
 * ⛔ 只许变小。往上抬 = 又上线了一个用户走不到的屏，那正是本闸要拦的那件事。
 *
 * ⚠️ 存量里都是真屏，不是空壳 —— 抽验能定位到组件的 6 个合计 **4226 行**，
 *    其中 5 个是 SmartBI 看板（`SalesFunnel` 893 / `ProcurementDashboard` 845 /
 *    `InventoryDashboard` 790 / `ProductionDashboard` 752 / `QualityDashboard` 683）。
 *    另 29 个用简易 import 解析没定位到组件，**真实行数只会更多**。
 */
const ORPHAN_BUDGET = 35;

describe('屏幕可达性（棘轮：上线即走不到的屏只许变少）', () => {
  it('阳性对照：仪器确实读到了 navigator 与源码', () => {
    // 任一读空都会让下面那条「孤儿数没超预算」假绿。
    expect(NAV_FILES.length).toBeGreaterThan(50);
    expect(declared.size).toBeGreaterThan(400);
    expect(landing.size).toBeGreaterThan(50);
    expect(REFERENCES.length).toBeGreaterThan(1_000_000);
  });

  it('阴性对照：有人跳过去的屏不会被判成孤儿', () => {
    // DraftReports 是 PR #130 刚接上入口的那个；它必须判为可达，
    // 否则说明「有人 navigate 过去」这一条判据坏了。
    expect(declared.has('DraftReports')).toBe(true);
    expect(ORPHANS).not.toContain('DraftReports');
  });

  it('阴性对照：落地页不算孤儿（含未写 initialRouteName 的隐式落地页）', () => {
    // 这几个是各自 Navigator 里第一个声明的 Screen，没写 initialRouteName。
    // 第一版正是漏了这一条，把它们误报成孤儿。
    for (const home of ['DSHome', 'HRHome']) {
      expect(ORPHANS).not.toContain(home);
    }
  });

  it(`走不到的屏不超过 ${ORPHAN_BUDGET} 个（只许变少）`, () => {
    // ⚠️ jest 的 expect **不接受**第二个「消息」参数（那是 vitest 的 API，
    //    在 jest 里会被静默忽略 ⇒ 闸红了却不告诉你红在哪）。所以先手抛带清单的错误。
    if (ORPHANS.length > ORPHAN_BUDGET) {
      const list = ORPHANS.join('\n  ');
      throw new Error(
        `走不到的屏 ${ORPHANS.length} 个（预算 ${ORPHAN_BUDGET}）：\n  ${list}\n\n` +
          '新增了一个用户走不到的屏？请加一处 navigate(...)，或登记进快捷操作表。\n' +
          '修好了几个？把 ORPHAN_BUDGET 调小。⛔ 不许调大。',
      );
    }
    expect(ORPHANS.length).toBeLessThanOrEqual(ORPHAN_BUDGET);
  });
});
