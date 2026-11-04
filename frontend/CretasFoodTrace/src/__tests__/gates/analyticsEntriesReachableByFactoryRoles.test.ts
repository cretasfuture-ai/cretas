/**
 * 有经营分析权限的工厂角色，必须在手机上进得了「AI 分析报告」和「时间范围成本分析」。
 *
 * ## 为什么需要这一条（2026-11-04）
 *
 * `AIReportList` / `TimeRangeCostAnalysis` / `AIAnalysisDetail` 此前**只**注册在
 * `ProcessingStackNavigator`，那棵树只挂在 `MainNavigator`（AppNavigator 只把
 * platform_admin / permission_admin 派给它）。Owner 裁定开放给有 analytics 读权限的
 * 工厂角色：
 *
 *   factory_super_admin             → BossNavigator（BossOverviewStack）
 *   finance_manager / cashier       → FinanceNavigator（隐藏 Tab → AnalyticsReportStackNavigator）
 *   workshop_supervisor / department_admin → WorkshopSupervisorNavigator（WSHomeStackNavigator）
 *
 * 形状同 `factoryBossReachesFieldEntries.test.ts`：机制在、没接上。
 *
 * ## 四层，⛔ 缺一层都留得下缺口
 *
 * | 层 | 守什么 | 只有这一层时漏掉什么 |
 * |---|---|---|
 * | ① 联通图 | 从入口屏**沿源码里的 navigate 字面量**算出的下一跳全集，不手抄 | 屏里新增一个跳转，注册表没人补 |
 * | ② 三棵树都注册 | 入口屏 + 全部下一跳都在各自的 stack 里，且挂的是**同一个屏文件** | 注册了落地屏、漏了详情屏 ⇒ 点开 route not found |
 * | ③ 角色可达 | 沿 AppNavigator 真实分流，五个角色走得到那三个屏文件 | 树注册了但这个角色根本不走那棵树 |
 * | ④ 入口受权限控制 | 有 analytics 读权限看得到、没有看不到（且按下去开对的路由） | 路由注册了而入口没接；或入口谁都看得到 ⇒ 点进去 403 |
 *
 * ⚠️ 「注册」不等于「授权」：三条路由在三棵树里是无条件注册的，授权由入口可见性承担，
 * 所以 ④ 必须有「没权限 ⇒ 看不到」的阴性对照。
 */
import fs from 'fs';
import path from 'path';
import React from 'react';
import { act, cleanup, fireEvent, render } from '@testing-library/react-native';

import {
  NAV_DIR,
  makeUser,
  parseRoleDispatch,
  reachableScreensFor,
  relative,
  resolveComponent,
  screensIn,
  type ScopeKey,
} from './lib/roleReachability';
import type { FactoryUser, User } from '../../types/auth';
import { ANALYTICS_ENTRIES, analyticsEntriesFor } from '../../utils/analyticsEntries';
import { analyticsQuickActions, WORKSHOP_SUP_ACTIONS } from '../../store/quickActionsStore';
import { resolveHomeModules } from '../../screens/main/homeModules';
import { useAuthStore } from '../../store/authStore';
import BossOverviewScreen from '../../screens/factory-admin/home/BossOverviewScreen';

const mockNavigation = { navigate: jest.fn(), dispatch: jest.fn(), goBack: jest.fn() };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
}));

const DISPATCH = parseRoleDispatch();

const PROCESSING: ScopeKey = {
  file: path.join(NAV_DIR, 'ProcessingStackNavigator.tsx'),
  scope: 'ProcessingStackNavigator',
};

/** 三棵树里承载这三条路由的那个 stack（AST 读注册，⛔ 不做文本匹配）。 */
const TREES: ReadonlyArray<{ name: string; roles: string[]; stack: ScopeKey }> = [
  {
    name: 'BossNavigator.BossOverviewStack',
    roles: ['factory_super_admin'],
    stack: { file: path.join(NAV_DIR, 'BossNavigator.tsx'), scope: 'BossOverviewStack' },
  },
  {
    name: 'FinanceNavigator → AnalyticsReportStackNavigator',
    roles: ['finance_manager', 'cashier'],
    stack: { file: path.join(NAV_DIR, 'AnalyticsReportStackNavigator.tsx'), scope: 'AnalyticsReportStackNavigator' },
  },
  {
    name: 'WorkshopSupervisorNavigator → WSHomeStackNavigator',
    roles: ['workshop_supervisor', 'department_admin'],
    stack: { file: path.join(NAV_DIR, 'workshop-supervisor/WSHomeStackNavigator.tsx'), scope: 'WSHomeStackNavigator' },
  },
];

// ============================================================
// ① 联通图：从源码算出来，不手抄
// ============================================================

function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** 屏文件里 `navigate('X'` / `push('X'` 的字面量目标。 */
function navigateTargets(file: string): string[] {
  const src = stripComments(fs.readFileSync(file, 'utf8'));
  const out = new Set<string>();
  for (const m of src.matchAll(/\b(?:navigate|push)\(\s*['"]([A-Za-z][\w]*)['"]/g)) out.add(m[1]!);
  return [...out];
}

/** 规范注册（ProcessingStackNavigator）：路由名 → 屏文件。 */
function canonicalFiles(): Map<string, string> {
  const out = new Map<string, string>();
  for (const reg of screensIn(PROCESSING)) {
    const target = resolveComponent(reg.navFile, reg.componentRef);
    if (target) out.set(reg.screenName, target.file);
  }
  return out;
}

/** 入口屏 + 沿 navigate 字面量走到的全部下一跳（只在规范注册里找得到屏文件的才继续展开）。 */
function connectedRoutes(): { routes: string[]; files: Map<string, string> } {
  const canon = canonicalFiles();
  const seen = new Set<string>();
  const queue: string[] = ANALYTICS_ENTRIES.map((e) => e.route);
  while (queue.length > 0) {
    const route = queue.shift()!;
    if (seen.has(route)) continue;
    seen.add(route);
    const file = canon.get(route);
    if (!file) continue;
    for (const next of navigateTargets(file)) queue.push(next);
  }
  return { routes: [...seen].sort(), files: canon };
}

const CONNECTED = connectedRoutes();

describe('① 联通图（从屏源码算出，不手抄）', () => {
  it('阳性对照：算出了入口屏 + 已知的下一跳 AIAnalysisDetail', () => {
    // 没有这一条，下面的「每棵树都包含」在集合为空时是恒真式。
    expect(CONNECTED.routes).toEqual(
      expect.arrayContaining(['AIReportList', 'TimeRangeCostAnalysis', 'AIAnalysisDetail']),
    );
    expect(CONNECTED.routes.length).toBeGreaterThanOrEqual(3);
    expect(CONNECTED.files.size).toBeGreaterThan(20);
  });

  it('阴性对照：不是什么路由都算进联通图', () => {
    expect(CONNECTED.routes).not.toContain('__NoSuchRoute__');
    expect(CONNECTED.routes).not.toContain('BossOverview');
  });

  it('联通图里每个路由在规范注册里都找得到屏文件（否则这一层读不出它的下一跳）', () => {
    for (const r of CONNECTED.routes) expect(CONNECTED.files.has(r)).toBe(true);
  });
});

// ============================================================
// ② 三棵树都注册了入口屏和全部下一跳
// ============================================================

describe('② 三棵树都注册了入口屏 + 全部下一跳，且挂的是同一个屏文件', () => {
  it.each(TREES)('$name', ({ stack }) => {
    const regs = screensIn(stack);
    // 阳性对照：AST 真的读到了这个 stack
    expect(regs.length).toBeGreaterThanOrEqual(CONNECTED.routes.length);
    const byName = new Map(regs.map((r) => [r.screenName, r]));
    for (const route of CONNECTED.routes) {
      const reg = byName.get(route);
      expect({ route, registered: reg !== undefined }).toEqual({ route, registered: true });
      const target = resolveComponent(reg!.navFile, reg!.componentRef);
      // 路由名对上而组件挂错文件 ⇒ 点进去是另一个屏
      expect({ route, file: target ? relative(target.file) : null }).toEqual({
        route,
        file: relative(CONNECTED.files.get(route)!),
      });
    }
  });

  it('阴性对照：解析器不是什么名字都认', () => {
    for (const t of TREES) expect(screensIn(t.stack).map((r) => r.screenName)).not.toContain('__NoSuchRoute__');
  });

  it('FinanceAnalyticsTab 不占底栏：只写 tabBarButton: () => null 会留一格空位（真机实测）', () => {
    const source = fs.readFileSync(path.join(NAV_DIR, 'FinanceNavigator.tsx'), 'utf8');
    const block = source.slice(source.indexOf('name="FinanceAnalyticsTab"'));
    const options = block.slice(0, block.indexOf('/>'));
    expect(options).toContain("tabBarItemStyle: { display: 'none' }");
  });

  it('FinanceNavigator 真的挂了 FinanceAnalyticsTab，且它指向 AnalyticsReportStackNavigator', () => {
    const regs = screensIn({ file: path.join(NAV_DIR, 'FinanceNavigator.tsx'), scope: 'FinanceNavigator' });
    const tab = regs.find((r) => r.screenName === 'FinanceAnalyticsTab');
    expect(tab).toBeDefined();
    const target = resolveComponent(tab!.navFile, tab!.componentRef);
    expect(target && relative(target.file)).toBe('navigation/AnalyticsReportStackNavigator.tsx');
  });
});

// ============================================================
// ③ 角色可达（沿 AppNavigator 的真实分流）
// ============================================================

describe('③ 五个角色沿真实分流走得到三个屏文件', () => {
  const roles = TREES.flatMap((t) => t.roles);

  it('阳性对照：分流表里有这些角色', () => {
    for (const r of roles) expect(DISPATCH.some((d) => d.role === r)).toBe(true);
  });

  it.each(roles)('%s', (role) => {
    const reached = [...reachableScreensFor(role, DISPATCH).keys()].map(relative);
    for (const route of CONNECTED.routes) {
      expect({ role, route, file: reached.includes(relative(CONNECTED.files.get(route)!)) }).toEqual({
        role,
        route,
        file: true,
      });
    }
  });

  it('阴性对照：没分到这三棵树的角色（operator）走不到入口屏', () => {
    const reached = [...reachableScreensFor('operator', DISPATCH).keys()].map(relative);
    expect(reached).not.toContain(relative(CONNECTED.files.get('AIReportList')!));
  });
});

// ============================================================
// ④ 入口受 analytics 读权限控制
// ============================================================

function withModules(user: User, modulePermissions: Record<string, 'rw' | 'r' | '-'>): FactoryUser {
  if (user.userType !== 'factory') throw new Error('期望工厂用户: ' + user.userType);
  return { ...user, factoryUser: { ...user.factoryUser, modulePermissions } };
}

const WITH_ANALYTICS = { analytics: 'r', finance: 'rw', production: 'rw' } as const;
const WITHOUT_ANALYTICS = { analytics: '-', finance: 'rw', production: 'rw' } as const;

describe('④-a 共享判据 analyticsEntriesFor', () => {
  it('有 analytics 读权限 ⇒ 两个入口；没有 ⇒ 空；未登录 ⇒ 空', () => {
    const base = makeUser('factory_super_admin');
    expect(analyticsEntriesFor(withModules(base, WITH_ANALYTICS)).map((e) => e.route)).toEqual([
      'AIReportList',
      'TimeRangeCostAnalysis',
    ]);
    expect(analyticsEntriesFor(withModules(base, WITHOUT_ANALYTICS))).toEqual([]);
    expect(analyticsEntriesFor(null)).toEqual([]);
  });
});

describe('④-b 工厂超管：总览页「经营分析」', () => {
  beforeEach(() => {
    mockNavigation.navigate.mockClear();
  });
  afterEach(() => {
    // 先卸载再清 store，否则还挂着的组件收到更新会报 act 警告
    cleanup();
    act(() => {
      useAuthStore.setState({ user: null });
    });
  });

  it.each(ANALYTICS_ENTRIES.map((e) => [e.testID, e.route] as const))('有权限：%s 按下去打开 %s', (testID, route) => {
    useAuthStore.setState({ user: withModules(makeUser('factory_super_admin'), WITH_ANALYTICS) });
    const { getByTestId, getByText } = render(React.createElement(BossOverviewScreen));
    // 阳性对照（硬约束 9）：既有入口照常工作，证明渲染→按下→navigate 这条链是活的
    fireEvent.press(getByText('生产进度'));
    expect(mockNavigation.navigate).toHaveBeenLastCalledWith('BossProductionPlans');
    mockNavigation.navigate.mockClear();

    fireEvent.press(getByTestId(testID));
    expect(mockNavigation.navigate).toHaveBeenCalledTimes(1);
    expect(mockNavigation.navigate).toHaveBeenCalledWith(route);
  });

  it('阴性对照：没有 analytics 读权限 ⇒ 入口一个都没有（同一屏、同一渲染路径，既有入口仍在）', () => {
    useAuthStore.setState({ user: withModules(makeUser('factory_super_admin'), WITHOUT_ANALYTICS) });
    const { queryByTestId, getByText } = render(React.createElement(BossOverviewScreen));
    expect(getByText('生产进度')).toBeTruthy();
    for (const e of ANALYTICS_ENTRIES) expect(queryByTestId(e.testID)).toBeNull();
  });
});

describe('④-c 财务线：首页功能卡', () => {
  const allEnabled = (): boolean => true;
  const identityT = (k: string): string => k;
  const ids = (u: User) => resolveHomeModules('finance', u, allEnabled, identityT);

  it.each(['finance_manager', 'cashier'])('%s 有权限：两张卡都在，指向隐藏 Tab 里的入口屏', (role) => {
    const cards = ids(withModules(makeUser(role), WITH_ANALYTICS));
    const byId = new Map(cards.map((c) => [c.id, c]));
    // 阳性对照：既有的卡照常出（否则下面的「没有」可能只是整个模块集空了）
    expect(byId.has('arAp')).toBe(true);
    expect(byId.get('aiReports')).toMatchObject({ route: 'FinanceAnalyticsTab', nestedScreen: 'AIReportList' });
    expect(byId.get('timeRangeCost')).toMatchObject({ route: 'FinanceAnalyticsTab', nestedScreen: 'TimeRangeCostAnalysis' });
  });

  it.each(['finance_manager', 'cashier'])('阴性对照：%s 没有 analytics 读权限 ⇒ 两张卡都没有，其它卡仍在', (role) => {
    const cards = ids(withModules(makeUser(role), WITHOUT_ANALYTICS));
    const idList = cards.map((c) => c.id);
    expect(idList).toContain('arAp');
    expect(idList).not.toContain('aiReports');
    expect(idList).not.toContain('timeRangeCost');
  });

  it('卡片的 route/nestedScreen 与入口定义一致（形态 D：两份必须钉一致）', () => {
    const cards = ids(withModules(makeUser('finance_manager'), WITH_ANALYTICS));
    const nested = cards.filter((c) => c.route === 'FinanceAnalyticsTab').map((c) => c.nestedScreen);
    expect(nested.sort()).toEqual(ANALYTICS_ENTRIES.map((e) => e.route).sort());
  });
});

describe('④-d 车间主管：首页快捷操作', () => {
  const actionScreens = (u: User): string[] =>
    [...WORKSHOP_SUP_ACTIONS, ...analyticsQuickActions(analyticsEntriesFor(u))].map((a) => a.screen);

  it.each(['workshop_supervisor', 'department_admin'])('%s 有权限 ⇒ 两个入口加进快捷操作；没有 ⇒ 一个都不加', (role) => {
    const granted = actionScreens(withModules(makeUser(role), WITH_ANALYTICS));
    const denied = actionScreens(withModules(makeUser(role), WITHOUT_ANALYTICS));
    expect(granted).toEqual(expect.arrayContaining(['AIReportList', 'TimeRangeCostAnalysis']));
    expect(denied).not.toContain('AIReportList');
    expect(denied).not.toContain('TimeRangeCostAnalysis');
    // 既有快捷操作两边都在（阳性对照）
    expect(denied).toContain('ProcessOperation');
  });

  it('WSHomeScreen 真的用这条链：allActions 来自 analyticsEntriesFor，且每个入口路由都有 navigate 分支', () => {
    const src = stripComments(
      fs.readFileSync(path.join(NAV_DIR, '../screens/workshop-supervisor/home/WSHomeScreen.tsx'), 'utf8'),
    );
    expect(src).toMatch(/analyticsEntriesFor\(user\)/);
    expect(src).toMatch(/allActions=\{quickActions\}/);
    expect(src).not.toMatch(/allActions=\{WORKSHOP_SUP_ACTIONS\}/);
    for (const e of ANALYTICS_ENTRIES) {
      expect(src).toContain(`case '${e.route}':`);
      expect(src).toContain(`navigation.navigate('${e.route}')`);
    }
  });
});

// ============================================================
// ⑤ AIReportListScreen 的文案键在两个语言包里都取得到
// ============================================================
// 2026-11-04: 屏用 t('aiReports.fab')，en-US 有 `fab`，zh-CN 却只有 `fabLabel`
// （整个 aiReports 段是另一套键名，屏用到的键里 zh-CN 缺一大半）⇒ 中文用户看到的是
// key 原文。i18nKeysResolve 只是不许增长的棘轮，这里对这一屏单独钉死。
describe('⑤ AIReportListScreen 的 t() 键在 zh-CN 与 en-US 都取得到', () => {
  const LOCALES = path.join(NAV_DIR, '../i18n/locales');
  const SCREEN = path.join(NAV_DIR, '../screens/processing/AIReportListScreen.tsx');

  function lookup(locale: string, key: string): unknown {
    let cur: unknown = JSON.parse(fs.readFileSync(path.join(LOCALES, locale, 'processing.json'), 'utf8'));
    for (const part of key.split('.')) {
      if (cur && typeof cur === 'object' && part in (cur as Record<string, unknown>)) {
        cur = (cur as Record<string, unknown>)[part];
      } else {
        return undefined;
      }
    }
    return cur;
  }

  const src = stripComments(fs.readFileSync(SCREEN, 'utf8'));
  const literalKeys = [...new Set([...src.matchAll(/\bt\(\s*['"](aiReports\.[\w.]+)['"]/g)].map((m) => m[1]!))];
  // 模板键 t(`aiReports.reportType.${x}`) 读不出具体值：展开成屏里 typeMap 的四个取值
  const dynamicKeys = ['batch', 'weekly', 'monthly', 'custom'].map((k) => `aiReports.reportType.${k}`);
  const allKeys = [...literalKeys, ...dynamicKeys];

  it('阳性对照：扫到了足够多的键，且包含被点名的 aiReports.fab', () => {
    expect(literalKeys.length).toBeGreaterThan(15);
    expect(literalKeys).toContain('aiReports.fab');
  });

  it.each(['zh-CN', 'en-US'])('%s：每个键都是非空字符串', (locale) => {
    const missing = allKeys.filter((k) => {
      const v = lookup(locale, k);
      return typeof v !== 'string' || v.length === 0;
    });
    expect(missing).toEqual([]);
  });

  it('阴性对照：lookup 查不存在的键返回 undefined（不是恒有值）', () => {
    expect(lookup('zh-CN', 'aiReports.__nope__')).toBeUndefined();
  });
});
