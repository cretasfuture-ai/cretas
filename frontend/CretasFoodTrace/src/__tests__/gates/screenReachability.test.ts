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

/**
 * ⛔ 整个 `__tests__/gates/` 都要排除出引用池，不是只排除本文件。
 *
 * 原来写的是 `f !== __filename`，只挡住了本闸自己。但这个目录正在长出**别的**闸
 * （#146 在加 `roleReachability.test.ts` + `lib/roleReachability.ts`），
 * 而闸天然会大量引用屏名。任何一个闸只要写一句 `expect(...).toContain('SalesFunnel')`，
 * `SalesFunnel` 就**从此算「可达」**——存量凭空变小，而没有任何用户能多走到一个屏。
 *
 * ⚠️ 这不是假想：本闸自己就是这么栽过的（它的阴性对照里写着 'DSHome' / 'Loading'，
 *    早期没排除自己时少算了一个）。同一个形状，只是这次的载体是**隔壁文件**。
 *
 * 🔴 改之前先量过（硬约束 8）：当前 `__tests__/gates/` 下 10 个文件里，
 *    剥掉注释后**一个存量屏名都没有被引号引起来**⇒ 这次改动对今天的读数是 no-op
 *    （29 → 29）。它买的是「以后不会被隔壁文件悄悄放水」。
 */
const GATES_MARKER = path.sep + '__tests__' + path.sep + 'gates' + path.sep;

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
  .filter((f) => !f.includes(NAV_MARKER) && !f.includes(GATES_MARKER))
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

const ORPHANS = [...declared.keys()]
  .filter((name) => !landing.has(name))
  .filter((name) => !REFERENCES.includes(`'${name}'`) && !REFERENCES.includes(`"${name}"`))
  .sort();

/**
 * 建闸当天的存量 **35 个**；2026-08-26 逐个分类后退役 6 个 ⇒ **29 个**。
 *
 * ⛔ 只许变小。往上抬 = 又上线了一个用户走不到的屏，那正是本闸要拦的那件事。
 *
 * ## 🔴 订正：上一版这里写的「存量里都是真屏，不是空壳」是**错的**
 *
 * 上一版拿**行数**当「是不是真屏」的证据，还特意点名了 5 个 SmartBI 看板
 * （893 / 845 / 790 / 752 / 683 行）当作「体量很大所以是真的」。
 * 逐个打开之后：**那 5 个恰恰全是空壳**。它们的 `loadData()` 里，
 * 唯一那句 API 调用是**被注释掉的**，紧接着无条件
 * `setError('该功能数据接口尚未实现')`：
 *
 *     // const response = await smartBIApiClient.getProductionDashboard({...});
 *     setError(t('errors.notImplemented', { defaultValue: '该功能数据接口尚未实现…' }));
 *
 * 而且 `smartBIApiClient` 里**根本没有**这几个方法（`services/api/smartbi.ts` 里
 * grep 不到 `getProductionDashboard` / `getInventoryDashboard` / `getSalesFunnel` /
 * `getQualityDashboard` / `getProcurementDashboard`）。每个文件顶部还各有一个
 * `generateMockData()`，声明了但**一次都没被调用**。
 *
 * ⇒ **行数量的是「写了多少字」，不是「它做不做事」。** 这两个数没有关系：
 *   893 行的 `SalesFunnel` 打开就是一句错误提示。
 *   ⛔ 别再用体量给存量背书 —— 要判就打开看 `loadData` 里那句 await 在不在。
 *
 * ## 2026-08-26 退役的 6 个（都是**重复注册**，不是删功能）
 *
 * 五个组件一个都没删，只是各自从两个注册名收敛回一个：
 *
 *   OutboundDetail  ⇒ 留 WHOutboundDetail  (WHOutboundListScreen 跳它)
 *   InboundDetail   ⇒ 留 WHInboundDetail
 *   AlertHandle     ⇒ 留 WHAlertHandle     (WHAlertListScreen 跳它)
 *   TempMonitor     ⇒ 留 WHTempMonitor     (WHLocationManageScreen 跳它)
 *   TraceDetail     ⇒ 留 TraceabilityDetail(TraceabilityScreen 跳它)
 *                     整个 TraceStackNavigator.tsx 删除：它零 importer，
 *                     四条路由是 ProcessingStackNavigator 那一段的逐字复制。
 *   Membership      ⇒ 直接退役，无孪生。它是**另一个产品**的会员页
 *                     （卡面写死 `POMELOX MEMBER`，整屏英文，
 *                     `rewards.next_tier_progress` 这个 i18n key 当正文渲染出去，
 *                     四个 TouchableOpacity 全都没有 onPress，
 *                     `route` 指的 PointsMall/PointsHistory/MyCoupons/Referral
 *                     四个名字在全仓不存在，后端也没有任何会员/积分/券的域）。
 *                     曾在 5 个角色的 Profile 栈里各注册一次。
 *
 * ## 剩下 29 个的分类（逐条结论见 PR）
 *
 *   A 真功能只是漏入口   15   ⚠️ 其中 2 个连**所在的 navigator 都没被挂载**
 *   C 半成品/空壳         13   ⛔ 不许接 —— 接一个内部是假数据的屏比藏着更糟
 *   D 闸自己的假阳性       1   `Loading`（见上面「已知盲区」，⛔ 仍不开白名单）
 *
 * ⚠️ 两个「navigator 自己没挂载」的：`AIScheduleGenerate`(DSAIStack) /
 *    `PersonnelAttendance`(DSPersonnelStack) —— 这两个 Stack 在
 *    `DispatcherTabNavigator` 里 **import 了但没有对应的 `Tab.Screen`**
 *    （文件头注释说有 6 个 Tab，实际只渲染 3 个）。
 *    对它们**加一句 navigate 能让本闸变绿，而用户照样走不到** ——
 *    这是本闸的第 5 个已知盲区：它只问「屏在不在某个 navigator 里」，
 *    ⛔ 不问「那个 navigator 有没有被挂上去」。
 */
const ORPHAN_BUDGET = 29;

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
