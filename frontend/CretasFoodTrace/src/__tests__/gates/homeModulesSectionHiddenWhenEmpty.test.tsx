/**
 * 首页「功能模块」段的**屏幕层**契约 —— `homeModulesNonEmptyPerRole.test.ts` 的第二层。
 *
 * 那一条是静态分析：模块集筛出来几张卡、路由注册没注册。
 * 但「机制在、没接上」的经典长相正是：`resolveHomeModules` 写得好好的，
 * 而屏幕自己没用它 / 或者仍然无条件渲染那个标题。
 * ⇒ 这里用 RNTL 渲染**真的 HomeScreen**，两侧都验：
 *
 *   正向：sales_manager 拿到 5 张卡，标题在，每张卡都能按 testID 找到
 *   阴性：权限为空 ⇒ 0 张卡，**「功能模块」这四个字一次都不出现**
 *         （这次缺陷的可见症状就是「一个标题，下面空无一物」）
 *
 * ⚠️ 阴性对照配一个「一定会发生」的读数（硬约束 9）：同一次渲染里
 *    问候语必须还在。它不在 = 屏根本没渲染出来，那时候「标题不见了」毫无意义。
 */
import React from 'react';
import { render } from '@testing-library/react-native';

import HomeScreen from '../../screens/main/HomeScreen';
import { makeUser } from './lib/roleReachability';
import type { FactoryUser, User } from '../../types/auth';
import zhHome from '../../i18n/locales/zh-CN/home.json';

const SECTION_TITLE = zhHome.sections.modules; // 「功能模块」，⛔ 不在测试里另抄一份中文
const GREETING = zhHome.greeting; // 「你好」——「一定会发生」的那个读数

let currentUser: User | null = null;
let currentModuleSet = 'sales';

const NAV = { dispatch: jest.fn(), navigate: jest.fn() };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => NAV,
  useRoute: () => ({ params: { moduleSet: currentModuleSet } }),
  CommonActions: { navigate: (arg: unknown) => ({ type: 'NAVIGATE', payload: arg }) },
}));

jest.mock('../../store/authStore', () => ({
  useAuthStore: () => ({ user: currentUser }),
}));

jest.mock('../../store/factoryFeatureStore', () => ({
  useFactoryFeatureStore: () => ({ isModuleEnabled: () => true }),
}));

// QuickStatsPanel 会打接口，本闸只关心模块宫格 ⇒ 换成一个可见的占位。
jest.mock('../../screens/main/components/QuickStatsPanel', () => {
  const { Text } = require('react-native');
  const React2 = require('react');
  return { QuickStatsPanel: () => React2.createElement(Text, null, 'quick-stats-stub') };
});

// 真的 i18n 实例在单测里没初始化 ⇒ 直接查 zh-CN 的 bundle，
// 这样断言里出现的中文和用户屏上看到的是**同一个来源**。
jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/home.json');
  const lookup = (key: string): string =>
    key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle) ?? key;
  return { useTranslation: () => ({ t: lookup }) };
});

function withPermissions(role: string, permissions: string[]): FactoryUser {
  const base = makeUser(role);
  if (base.userType !== 'factory') throw new Error('期望工厂用户');
  return { ...base, factoryUser: { ...base.factoryUser, permissions } };
}

describe('首页「功能模块」段：有卡才渲染标题', () => {
  afterEach(() => {
    currentUser = null;
    currentModuleSet = 'sales';
  });

  it('正向：sales_manager 看到标题 + 5 张卡，每张都点得到', () => {
    currentUser = withPermissions('sales_manager', ['sales:*']);
    currentModuleSet = 'sales';
    const { queryByTestId, queryAllByText } = render(<HomeScreen />);

    expect(queryByTestId('home-modules-section')).not.toBeNull();
    expect(queryAllByText(SECTION_TITLE).length).toBe(1);

    const found = ['salesOrders', 'customers', 'arAp', 'priceList', 'returnOrders'].filter(
      (id) => queryByTestId('home-module-' + id) !== null
    );
    // eslint-disable-next-line no-console
    console.warn('[home-section] sales 渲染到的卡: ' + found.join(','));
    expect(found).toHaveLength(5);
  });

  it('🔴 阴性对照：权限为空 ⇒ 0 张卡，且「功能模块」标题一次都不出现', () => {
    currentUser = withPermissions('sales_manager', []);
    currentModuleSet = 'sales';
    const { queryByTestId, queryAllByText } = render(<HomeScreen />);

    // 硬约束 9：先证明这一屏**真的渲染出来了**，否则「标题没了」是假读数。
    expect(queryAllByText(GREETING, { exact: false }).length).toBeGreaterThan(0);

    expect(queryByTestId('home-modules-section')).toBeNull();
    expect(queryAllByText(SECTION_TITLE)).toHaveLength(0);
    expect(queryByTestId('home-module-salesOrders')).toBeNull();
  });

  it('点一张卡：跨 Tab 跳转带上 { screen: nestedScreen }', () => {
    currentUser = withPermissions('sales_manager', ['sales:*']);
    currentModuleSet = 'sales';
    NAV.dispatch.mockClear();
    const { getByTestId } = render(<HomeScreen />);

    getByTestId('home-module-salesOrders').props.onPress();

    expect(NAV.dispatch).toHaveBeenCalledTimes(1);
    const action = NAV.dispatch.mock.calls[0]![0] as { payload: unknown };
    // eslint-disable-next-line no-console
    console.warn('[home-section] 点击派发的 action: ' + JSON.stringify(action));
    expect(action.payload).toEqual({
      name: 'SMSalesTab',
      params: { screen: 'SalesOrderList' },
    });
  });
});
