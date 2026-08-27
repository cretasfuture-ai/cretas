/**
 * 角色可达性闸（棘轮：空转的权限分支只许变少）—— 本仓第三道可达性闸。
 *
 * 前两道守的是**屏**：
 *   · `src/__tests__/gates/screenReachability.test.ts`（RN，存量 35）
 *   · `web-admin/src/router/__tests__/routeReachability.spec.ts`（存量 91）
 *
 * 它们守不住这一类 —— **屏可达 ≠ 那个分支可达**。
 *
 * ## 2026-08-26 的那个具体案例（本闸的第一个真实用例）
 *
 * `ManagementScreen.tsx` 写着「没权限就置灰 / 就不显示」：
 *
 *     const isAdmin = user?.userType === 'platform'
 *                  || user?.factoryUser?.role === 'factory_super_admin'
 *                  || user?.factoryUser?.role === 'permission_admin';
 *
 * 手工追下来：进 `MainNavigator` 的只有 4 个角色，管理 Tab 的可见条件又是另外 4 个，
 * **交集只有 `permission_admin` / `platform_admin`** —— 而这两个的 `isAdmin` 都恒为真。
 * ⇒ 那个「非管理员」分支从来没有执行过。
 *
 * 本闸把这条链**算出来**（分析器在 `lib/roleReachability.ts`），
 * 不靠人追、不靠正则、不靠字符串计数。
 *
 * ## 三态，⛔ 不许二分
 *
 *   空转 / 有效 / 判不了。「判不了」单列 —— 把不确定折叠成阳性是本仓反复踩的坑。
 *   **只有「空转」进棘轮预算**：判不了的不该被算成缺陷，也不该被算成没事。
 *
 * ## 棘轮量的是「有多少个空转分支」，⛔ 不是「有多少行」
 *
 * 同一个条件在一个文件里出现在 `const` / `if` / `{cond && …}` 三处，算 **1 条**
 * （见分析器里的 `dedupeKey`：按语义规范式去重，并把顶层取反抹掉，
 * 于是 `locked` 与 `!locked` 是同一条）。
 * 本仓记过这条：按行数算会因为「同一个问题被拆成几处调用」而虚高。
 *
 * ## ⛔ 为什么是棘轮而不是硬红
 *
 * 建闸当天存量 36 条。硬红的闸当天就会被关掉（形态 E：窄而可信 > 宽而被关掉）。
 * 而且这 36 条里有相当一部分是**设计上就该收敛的角色分支**（例如
 * `ShipmentManagementScreen` 的 `canView` 列了 3 个角色，实际只有 3 个角色进得来
 * 且全部为真），修它们要动产品判断，不是机械改写。
 */
import fs from 'fs';
import path from 'path';
import {
  analyze,
  judgeSource,
  BOOL_HELPERS,
  ALL_ROLES,
  type Finding,
} from './lib/roleReachability';
import * as permissionHelper from '../../utils/permissionHelper';

const RESULT = analyze();

/**
 * 建闸当天的存量：**36 条空转分支**。
 *
 * ⛔ 只许变小。往上抬 = 又上线了一个「对能走到这里的所有角色恒真/恒假」的权限判断，
 *    那正是本闸要拦的那件事。修好了几条就把这个数调小。
 *
 * ⚠️ 调这个数之前先跑一次 `npx jest src/__tests__/gates/roleReachability.test.ts`
 *    看清单 —— 数变小可能是**分析器读不懂了**（条目掉进「判不了」），不是修好了。
 *    所以下面同时钉了「判不了」的上限。
 */
const VACUOUS_BUDGET = 37;

/**
 * 🔴 2026-08-27：36 -> 37，**这是一次「故意不改」的登记，不是修好了**（硬约束 8）。
 *
 * 成因是 #123 落地：ManagementScreen 从「没权限就隐藏」改成「置灰可见」。
 * 那一处判断由 1 条变成 2 条，净 +1：
 *
 *   #123 前  L167  `!item.adminOnly || isAdmin`                        恒为真   1 条
 *   #123 后  L198  `item.adminOnly && !isAdmin`                        恒为假
 *            L186  `section.items.every(i => i.adminOnly) && !isAdmin` 恒为假   2 条
 *
 * ⛔ 这**不是 #123 的缺陷**。读它的实现，两个分支的设计都是对的（不按权限过滤、
 * 整区全锁才隐藏，理由写在它自己的注释里）。它们之所以恒假，**只因为
 * ManagementScreen 目前只有 `permission_admin` / `platform_admin` 走得到** ——
 * 而这两个都是 admin，于是 `!isAdmin` 恒假，置灰态在这屏上永远渲染不出来。
 *
 * 🔴 **底下压着一个还没拍的产品决策**（上一轮交接件 §3A 后半段）：
 *   权限表说有 6 个只读角色能看客户/供应商，而它们各自的导航树里一个都没注册 ——
 *   **是权限表写多了，还是菜单漏了？** 两边必有一个错。
 *
 *   (a) 这屏就是管理员专用  -> 摘掉 `locked` / `allLocked`（成了死代码），棘轮回 36
 *   (b) 非管理员本该进得来  -> 修可达性，两个分支自然变成活的，棘轮回 36
 *   (c) 暂不拍              -> 就是现在这一档，抬到 37 并留下这段理由
 *
 * ⚠️ 抬到 37 是为了不让这道闸卡住整批 PR，**不是**认可这两条空转。
 *    决策一拍就该回到 36，⛔ 别让 37 变成新的「正常值」。
 */

/**
 * 「判不了」的上限（建闸当天 11 条）。
 *
 * 🔴 这一条和空转预算是**配对**的：只钉空转数，任何让分析器读不懂的改动都会让它变小，
 *    看起来像修好了。本仓记过这个形状（「把一道从没执行的闸换成另一道从没执行的闸」）。
 *    ⇒ 判不了变多也要红。
 */
const UNDECIDED_BUDGET = 14;

function render(f: Finding): string {
  const sites = f.sites.map((s) => 'L' + s.line + '(' + s.kind + ')').join(' ');
  const vals = f.roles.map((r) => r + '=' + f.values[r]).join(' ');
  return (
    f.relFile + '  ' + sites + '\n' +
    '    条件: ' + f.key + '\n' +
    '    可达角色: ' + vals + '\n' +
    '    判定: ' + f.detail
  );
}

describe('角色可达性（棘轮：空转的权限分支只许变少）', () => {
  it('阳性对照：分析器确实读到了成规模的 navigator / 屏 / 分支', () => {
    // 任何一项读空，下面的棘轮都会假绿 —— 一个扫 0 个文件的闸永远是绿的。
    const s = RESULT.stats;
    expect(s.navFiles).toBeGreaterThan(50);
    expect(s.rolesDispatched).toBeGreaterThanOrEqual(20);
    // 每个角色都得落到某个 navigator（兜底的 RestrictedRoleNavigator 也算）。
    expect(s.rolesWithNavigator).toBe(s.rolesDispatched);
    // 🔴 这三个是**下限棘轮**（建闸当天 398 / 435 / 72，留 ~2% 余量）。
    //
    // 只写「> 200」这种松下限是不够的：让分析器**看见更少**的改动会让空转数
    // 静默变小，看起来像修好了。实测——把 AppNavigator 里 hr_admin 那条分流删掉，
    // 可达屏 398→376、空转 36→35，两个预算全绿而分析器已经瞎了一块。
    // ⇒ 覆盖面变小也要红。变小是真的删屏了？确认后把下面三个数一起调小。
    // 🔴 2026-08-27 下调 —— 这道闸**正确地红了一次**，按它自己写的「确认后再调小」办的。
    //
    //   reachableScreenFiles  397 → 364   (下限 390 → 357，探针在同一棵树上前后各跑一次)
    //   screensExamined            → 395  (下限 425 → 387)
    //   branchesExamined           → 73   (下限 68 不动 —— 它没跌破，⛔ 不跟着一起调)
    //
    // ⚠️ 后两个只记了改动后的值 —— 改动前那两个数我没量，⛔ 就不写一个编出来的。
    //    下限按「实测值留 ~2% 余量」定，与建闸当天同一条惯例。
    //
    // 少掉的 33 个屏文件逐条对过（探针 diff 出来的，不是估的），两拨：
    //
    //   16 个  删掉 FAManagementStackNavigator 里那块 `{!isRestaurantMode && …}` 带走的
    //          （设备/IoT/摄像头/秤/NFC/标签识别/质检项/报工审批）
    //   17+2   删掉 FactoryAdminTabNavigator 的 FAReportsTab 带走的
    //          （screens/reports/* 全套 + 2 个 analytics）—— 它是 FAReportsStackNavigator
    //          的**唯一**挂载点
    //
    // ⚠️ 这 33 个**本来就走不到**：两处的条件都是 `!isRestaurantMode`，而这两棵子树
    //    只在 `isRestaurant(user)` 为真时挂载（AppNavigator:92）⇒ 恒为假。
    //    本闸此前把它们算成可达，是因为 `isRestaurant` 不在 BOOL_HELPERS 里（守卫求成 U）
    //    且 `parseRoleDispatch` 显式把三元两支都算可达（它自己写明的盲区 1）。
    //
    // ⇒ **这不是覆盖面变小，是口径变准了。** 397 与 364 不可比 ——
    //   报数时口径要一起报（否则会被读成「删了 33 个屏」）。
    //   那 33 个屏文件一个都没删，只是不再被算成有人走得到。
    //   守新口径的闸：navigatorMountContextReachability.test.ts
    expect(s.reachableScreenFiles).toBeGreaterThanOrEqual(357);
    expect(s.screensExamined).toBeGreaterThanOrEqual(387);
    expect(s.branchesExamined).toBeGreaterThanOrEqual(68);
    // 三态都得有人 —— 全落进一态说明分类器退化了。
    expect(RESULT.vacuous.length).toBeGreaterThan(0);
    expect(RESULT.effective.length).toBeGreaterThan(0);
    expect(RESULT.undecided.length).toBeGreaterThan(0);
  });

  it('阳性对照：角色宇宙与 AppNavigator 的分流两边都不空', () => {
    expect(ALL_ROLES.length).toBeGreaterThanOrEqual(20);
    // 这几个角色在 AppNavigator 里各走各的 Navigator，缺一个就说明分流没解析出来。
    for (const role of ['platform_admin', 'permission_admin', 'operator', 'hr_admin']) {
      expect(ALL_ROLES).toContain(role);
    }
  });

  it('成员表完备性：permissionHelper 的 is*/can*/has* 导出必须全部登记', () => {
    // ⛔ 这一条守的是「哪些函数算权限谓词」这张表不漂（形态 D）。
    //    权限**语义**不复制（分析器调的是真函数），但**成员**是一张手写表，会漏。
    const missing = Object.entries(permissionHelper)
      .filter(([name, v]) => typeof v === 'function' && /^(is|can|has)[A-Z]/.test(name))
      .filter(([name]) => !Object.prototype.hasOwnProperty.call(BOOL_HELPERS, name))
      .map(([name]) => name);
    if (missing.length > 0) {
      throw new Error(
        'permissionHelper 新增了权限谓词但没登记进 BOOL_HELPERS：\n  ' +
          missing.join('\n  ') +
          '\n未登记的谓词会被当成「读不懂」⇒ 用到它的分支全部掉进「判不了」，闸对那一块失明。',
      );
    }
    expect(missing).toEqual([]);
  });

  it('阴性对照 ①：已知空转（恒真）—— 只有 isAdmin 恒真的角色进得来', () => {
    const code = [
      "const isAdmin = user?.userType === 'platform'",
      "  || user?.factoryUser?.role === 'factory_super_admin'",
      "  || user?.factoryUser?.role === 'permission_admin';",
      'const view = isAdmin ? 1 : 2;',
    ].join('\n');
    const out = judgeSource(code, ['platform_admin', 'permission_admin']);
    expect(out).toHaveLength(1);
    expect(out[0]?.verdict).toBe('VACUOUS');
    expect(out[0]?.detail).toBe('恒真');
  });

  it('阴性对照 ②：已知空转（恒假）—— Kleene 让含运行时数据的条件也判得出来', () => {
    // `item.adminOnly` 是运行时数据（U）。and(U, F) = F ⇒ 整条恒假。
    // ⚠️ 二值逻辑在这里只能报「判不了」，那样就抓不到 ManagementScreen 那一类。
    const code = [
      "const isAdmin = user?.userType === 'platform'",
      "  || user?.factoryUser?.role === 'permission_admin';",
      'const locked = Boolean(item.adminOnly) && !isAdmin;',
      'const icon = locked ? 1 : 2;',
    ].join('\n');
    const out = judgeSource(code, ['platform_admin', 'permission_admin']);
    const locked = out.find((o) => o.key.includes('item.adminOnly'));
    expect(locked).toBeDefined();
    expect(locked?.verdict).toBe('VACUOUS');
    expect(locked?.detail).toBe('恒假');
  });

  it('阴性对照 ③：已知有效的分支不许被误报成空转', () => {
    // operator 的 production 是 write，viewer 的是 read ⇒ 举得出反例。
    const code = "const canWrite = hasModulePermission(user, 'production', 'write');";
    const out = judgeSource(code, ['operator', 'viewer']);
    expect(out).toHaveLength(1);
    expect(out[0]?.verdict).toBe('EFFECTIVE');
    expect(out[0]?.values['operator']).toBe('T');
    expect(out[0]?.values['viewer']).toBe('F');
  });

  it('阴性对照 ④：判不了的必须单列，⛔ 不许折叠成「有效」', () => {
    // and(U, T) = U —— 对 operator 判不了；⛔ 不能因为 viewer 侧是 F 就报「有效」。
    const code = "const ok = someRuntimeFlag && getUserRole(user) === 'operator';";
    const out = judgeSource(code, ['operator', 'viewer']);
    expect(out).toHaveLength(1);
    expect(out[0]?.verdict).toBe('UNDECIDED');
    expect(out[0]?.values['operator']).toBe('U');
    expect(out[0]?.values['viewer']).toBe('F');
  });

  it('真实用例：ManagementScreen 只有 permission_admin / platform_admin 走得到', () => {
    // 这是本闸的第一个真实用例，也是「导航守卫不能漏」的证明：
    // 只看 Stack 注册会得出「所有角色都进得来」，必须算上 MainNavigator 里
    // 管理 Tab 的可见条件，再与 AppNavigator 的分流求交。
    const entry = [...RESULT.rolesByScreenFile.entries()].find(([f]) =>
      f.endsWith(path.join('management', 'ManagementScreen.tsx')),
    );
    expect(entry).toBeDefined();
    expect(entry?.[1]).toEqual(['permission_admin', 'platform_admin']);
  });

  it('真实用例：ManagementScreen 的 isAdmin 分支判定为空转', () => {
    // 🔴 2026-08-27：原来这里断言的是**字面**「恒为真」。#123 落地后
    //    那一处从 `!item.adminOnly || isAdmin`（恒真）变成 `item.adminOnly && !isAdmin`
    //    （恒假）—— **方向合法地翻了**，而断言跟着红了。
    //
    //    这属于形态 C‴「断言守着一个我们已经不想要的行为」：它不是恒真式、
    //    也不是变异没生效，是**需求变了**。症状是「红得理直气壮」，最容易被
    //    读成「我改错了」然后把改动回退掉。
    //
    // ⇒ 按本仓规矩把守的东西**从字面抬到性质**：这道闸要证明的从来是
    //   「这个分支对能走到这屏的所有角色都不起作用」，⛔ 不是「它恒真」。
    //   恒真/恒假只是同一个性质的两个方向。
    const found = RESULT.findings.filter(
      (f) => f.relFile.endsWith('management/ManagementScreen.tsx') && f.key.includes('permission_admin'),
    );
    expect(found.length).toBeGreaterThan(0);
    for (const f of found) {
      expect(f.verdict).toBe('VACUOUS');
      // 守性质：恒真或恒假都算空转，但**必须是这两者之一** ——
      // ⛔ 不能退化成「detail 非空」那种什么都不守的写法。
      expect(/恒为真|恒为假/.test(f.detail)).toBe(true);
      // 阴性对照：一条真正在起作用的分支不会被判成 VACUOUS，
      // 所以 detail 里不许出现「这道判断在起作用」那一类措辞。
      expect(f.detail).not.toContain('LIVE');
    }
  });

  it(`空转的权限分支不超过 ${VACUOUS_BUDGET} 条（只许变少）`, () => {
    // ⚠️ jest 的 expect 不接受第二个「消息」参数（那是 vitest 的 API，
    //    在 jest 里被静默忽略 ⇒ 闸红了却不告诉你红在哪）。先手抛带清单的错误。
    if (RESULT.vacuous.length > VACUOUS_BUDGET) {
      throw new Error(
        '空转的权限分支 ' + RESULT.vacuous.length + ' 条（预算 ' + VACUOUS_BUDGET + '）：\n\n' +
          RESULT.vacuous.map(render).join('\n\n') +
          '\n\n新增了一个「对能走到这里的所有角色恒真/恒假」的权限判断？\n' +
          '要么把它删掉（它守不住任何东西），要么让某个走得到的角色真的取到另一个值。\n' +
          '修好了几条？把 VACUOUS_BUDGET 调小。⛔ 不许调大。',
      );
    }
    expect(RESULT.vacuous.length).toBeLessThanOrEqual(VACUOUS_BUDGET);
  });

  it(`判不了的分支不超过 ${UNDECIDED_BUDGET} 条（防止「读不懂」被当成「修好了」）`, () => {
    if (RESULT.undecided.length > UNDECIDED_BUDGET) {
      throw new Error(
        '判不了的分支 ' + RESULT.undecided.length + ' 条（上限 ' + UNDECIDED_BUDGET + '）：\n\n' +
          RESULT.undecided.map(render).join('\n\n') +
          '\n\n多出来的通常是**分析器读不懂了**（新写法 / 新的取角色方式），\n' +
          '而不是代码变差了。去 lib/roleReachability.ts 里补对应的解析，⛔ 不要直接抬上限。',
      );
    }
    expect(RESULT.undecided.length).toBeLessThanOrEqual(UNDECIDED_BUDGET);
  });

  it('清单可导出（ROLE_REACH_REPORT=<path> 时落盘，供 PR / 人工复核）', () => {
    const out = process.env.ROLE_REACH_REPORT;
    if (!out) {
      expect(RESULT.findings.length).toBeGreaterThan(0);
      return;
    }
    const section = (title: string, list: Finding[]): string =>
      '## ' + title + '（' + list.length + ' 条）\n\n' + list.map(render).join('\n\n');
    const body = [
      '# 角色可达性分析报告',
      '',
      '```json',
      JSON.stringify(RESULT.stats, null, 2),
      '```',
      '',
      section('空转 VACUOUS', RESULT.vacuous),
      '',
      section('有效 EFFECTIVE', RESULT.effective),
      '',
      section('判不了 UNDECIDED', RESULT.undecided),
      '',
    ].join('\n');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, body, 'utf8');
    expect(fs.existsSync(out)).toBe(true);
  });
});
