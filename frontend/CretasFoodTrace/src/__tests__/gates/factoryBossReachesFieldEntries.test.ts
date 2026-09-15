/**
 * 工厂超管必须走得到三个**通用现场能力屏**：溯源查询 / 拍照核验 / 源头个体档案。
 *
 * ## 为什么需要这一条（2026-09-15）
 *
 * `Traceability` / `TraceabilityDetail` / `VisionVerify` / `SourceIndividualForm`
 * 这四条路由此前**只**注册在 `ProcessingStackNavigator.tsx` 里，而那棵树唯一的
 * 挂载点是 `MainNavigator`（platform_admin / equipment_admin / quality_manager /
 * permission_admin）。
 *
 * 而 `AppNavigator.tsx` 把**非餐饮**的 `factory_super_admin` 分流到 `BossNavigator`
 * （`isRestaurant(user) ? <FactoryAdminNavigator /> : <BossNavigator />`）⇒
 * 工厂管理员账号在 App 里**一个都到不了**：屏在、组件在、后端接口在，
 * 只是这条路上没有人注册它们。这正是「机制在、没接上」。
 *
 * 本闸守住接上之后别再掉。
 *
 * ## 三层，⛔ 缺一层都留得下缺口
 *
 * | 层 | 守什么 | 只有这一层时漏掉什么 |
 * |---|---|---|
 * | ① 可达性（分析器沿注册树真的走一遍） | 这个角色走得到那四个**屏文件** | 注册了但改了路由名 ⇒ 按钮点了报 route not found |
 * | ② 同一个 stack（AST 读注册） | 四条路由**都**在 `BossOverviewStack` 里 | 只注册落地屏、漏掉它跳转的详情屏 |
 * | ③ 真渲染 + 真按下去 | 总览页上**真有**那三个按钮且打开对的路由 | 路由注册了而入口没接，用户仍然到不了 |
 *
 * ⚠️ 屏间跳转关系决定了 ② 不能省：`TraceabilityScreen` 会
 * `navigate('TraceabilityDetail', { batchNumber })`，`VisionVerifyScreen` 会
 * `navigate('SourceIndividualForm', …)` —— 四条路由是一张联通图，不是三个孤立入口。
 *
 * ## ⚠️ 已知盲区（继承自 lib，⛔ 不靠加启发式逼近）
 *
 * `parseRoleDispatch` 把三元的**两支都算可达**，所以本闸**不能**用来证明
 * 「餐饮用户走不到这三个屏」。它只证明正向那一半：工厂超管这条路上，
 * 这四条路由在可达集合里。反向那一半由 `navigatorMountContextReachability`
 * 的矛盾检测负责。
 */
import React from 'react';
import path from 'path';
import { fireEvent, render } from '@testing-library/react-native';

import {
  ALL_ROLES,
  NAV_DIR,
  parseRoleDispatch,
  reachableScreensFor,
  relative,
  screensIn,
} from './lib/roleReachability';
import BossOverviewScreen from '../../screens/factory-admin/home/BossOverviewScreen';

const mockNavigation = { navigate: jest.fn(), dispatch: jest.fn(), goBack: jest.fn() };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
}));

const BOSS = 'factory_super_admin';
const DISPATCH = parseRoleDispatch();

/** 四条路由注册的那个 stack —— ② 层直接问它，⛔ 不做文本匹配。 */
const BOSS_OVERVIEW_STACK = {
  file: path.join(NAV_DIR, 'BossNavigator.tsx'),
  scope: 'BossOverviewStack',
};

/** 四条必须注册的路由名。⛔ 少钉一条，删掉它不会红。 */
const MUST_REGISTER = [
  'Traceability',
  'TraceabilityDetail',
  'VisionVerify',
  'SourceIndividualForm',
  // 2026-09-15: 拍照核验「查看档案」与来源链上的个体行都跳这一屏 (FEAT-RN-SOURCE-INDIVIDUAL-DETAIL)。
  'SourceIndividualDetail',
  // 2026-09-16: 首页「源头个体」瓷砖改落到只读列表; 列表点行 → Detail, 右上 + → Form。
  'SourceIndividualList',
] as const;

/** 四个屏文件 —— 路由名对上了但组件挂错文件，这一层会红。 */
const MUST_REACH = [
  'screens/traceability/TraceabilityScreen.tsx',
  'screens/traceability/TraceabilityDetailScreen.tsx',
  'screens/shared/VisionVerifyScreen.tsx',
  'screens/shared/SourceIndividualFormScreen.tsx',
  'screens/shared/SourceIndividualDetailScreen.tsx',
  'screens/shared/SourceIndividualListScreen.tsx',
];

/** 总览页上的三个入口：testID → 它按下去必须打开的路由。 */
const ENTRIES: ReadonlyArray<readonly [string, string]> = [
  ['boss-entry-traceability', 'Traceability'],
  ['boss-entry-vision-verify', 'VisionVerify'],
  // 2026-09-16: 落到只读列表, ⛔ 不再直达登记表 (评审 P1: 老板点名词想看「我有哪些牛」, 落进 10 字段表单)。
  ['boss-entry-source-individual', 'SourceIndividualList'],
];

function reachableFiles(role: string): string[] {
  return [...reachableScreensFor(role, DISPATCH).keys()].map(relative);
}

function reachableRoutes(role: string): string[] {
  const out = new Set<string>();
  for (const routes of reachableScreensFor(role, DISPATCH).values()) {
    for (const r of routes) out.add(r);
  }
  return [...out];
}

function registeredRoutes(): string[] {
  return screensIn(BOSS_OVERVIEW_STACK).map((s) => s.screenName);
}

describe('① 工厂超管走得到现场能力屏', () => {
  it('阳性对照：分析器确实算出了这个角色的可达集合', () => {
    // ⛔ 没有这一条，下面的 toContain 在「集合为空」时会红得莫名其妙，
    //    而在集合恒大时又可能假绿。先证明仪器活着。
    expect(ALL_ROLES).toContain(BOSS);
    const files = reachableFiles(BOSS);
    expect(files.length).toBeGreaterThan(30);
    // 这个角色的落地页 —— 它不在，说明是分流没解析出来，不是现场能力屏的问题
    expect(files).toContain('screens/factory-admin/home/BossOverviewScreen.tsx');
  });

  it.each(MUST_REACH)('屏文件 %s 在 factory_super_admin 的可达集合里', (rel) => {
    expect(reachableFiles(BOSS)).toContain(rel);
  });

  it.each(MUST_REGISTER)('路由名 %s 在 factory_super_admin 的可达路由集合里', (route) => {
    // ⚠️ 与上一条不是重复：屏文件对上而路由名改掉的话，按钮点下去是 route not found。
    expect(reachableRoutes(BOSS)).toContain(route);
  });

  it('阴性对照：不是「什么屏都算可达」', () => {
    const files = reachableFiles(BOSS);
    expect(files).not.toContain('screens/__NoSuchScreen__.tsx');
    // 平台端的屏不该出现在工厂超管的可达集合里。
    expect(files.every((f) => !f.includes('screens/platform/'))).toBe(true);
  });
});

describe('② 四条路由注册在同一个 stack 里', () => {
  it('阳性对照：AST 真的读到了 BossOverviewStack 的注册（否则下面全是恒真式）', () => {
    // 存量 7 条（BossOverview / BossProductionPlans / BossSalesOrders / SalesOrderDetail /
    // BossPurchaseOrders / PurchaseOrderDetail / BossFinishedGoods）+ 本次 4 条。
    // 留余量，只拦「AST 什么都没读到」。
    expect(registeredRoutes().length).toBeGreaterThan(7);
    expect(registeredRoutes()).toContain('BossOverview');
  });

  it.each(MUST_REGISTER)('%s 注册在 BossOverviewStack 里', (route) => {
    expect(registeredRoutes()).toContain(route);
  });

  it('阴性对照：解析器不是什么名字都认', () => {
    expect(registeredRoutes()).not.toContain('__NoSuchRoute__');
  });

  it('三个入口按钮指向的路由，都真的注册在这个 stack 里', () => {
    // 形态 D：按钮里的路由名和注册里的路由名是两份，必须钉住它们一致。
    const registered = registeredRoutes();
    for (const [, route] of ENTRIES) expect(registered).toContain(route);
  });
});

describe('③ 总览页上真有这三个入口', () => {
  beforeEach(() => {
    mockNavigation.navigate.mockClear();
  });

  it('阳性对照：既有入口「生产进度」照常工作（证明渲染→按下→navigate 这条链是活的）', () => {
    // 硬约束 9：没有这个「必然发生」的读数，下面三条找不到 testID 时
    // 分不清是「按钮没加」还是「屏根本没渲染出来」。
    const { getByText } = render(React.createElement(BossOverviewScreen));
    fireEvent.press(getByText('生产进度'));
    expect(mockNavigation.navigate).toHaveBeenCalledWith('BossProductionPlans');
  });

  it.each(ENTRIES)('%s 按下去打开 %s', (testID, route) => {
    const { getByTestId } = render(React.createElement(BossOverviewScreen));
    fireEvent.press(getByTestId(testID));
    expect(mockNavigation.navigate).toHaveBeenCalledTimes(1);
    expect(mockNavigation.navigate).toHaveBeenCalledWith(route);
  });

  it('阴性对照：getByTestId 不是什么都找得到', () => {
    const { queryByTestId } = render(React.createElement(BossOverviewScreen));
    expect(queryByTestId('boss-entry-__nonexistent__')).toBeNull();
  });
});
