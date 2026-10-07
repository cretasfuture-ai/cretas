/**
 * 「我的」页「退出登录」确认框 —— Expo Web 上点了没反应 (2026-10-06 真机)。
 *
 * 根因: 确认框走 `Alert.alert` (react-native-web 里是 `static alert() {}`, 什么都不显示, 回调永不触发),
 * 或在 web 分支里走 `window.confirm` (内置浏览器 / 自动化 / iframe 里被静默吞掉 → 返回 false → 什么都不发生)。
 * 修法: 七个「我的」页统一走 `appAlert` (RN <Modal>, 宿主挂在 App.tsx 根), web 与原生同一条路径。
 *
 * 守的行为 (真实组件挂载, 不桩屏内的 handleLogout):
 *   ① web: 点「退出登录」→ 页面里出现确认框 (Alert.alert 在这里是 no-op, 与 react-native-web 一致; window.confirm 不存在)
 *   ② web: 点确认 → 真 authStore.logout 执行 (isAuthenticated=false, tokens 清空); 导航型屏另断言 reset 回 Auth
 *   ③ web: 点取消 → 不退出 (阴性对照; 配 ② 证明按钮不是「永远点不动」)
 *   ④ 原生 (宿主未挂载): 退回 Alert.alert 且带「取消 / 退出」按钮, 调用其确认回调 → 真退出 (原生行为不变)
 *
 * 变异 (见提交说明): 把某屏的 appAlert 换回 Alert.alert ⇒ 该屏 ①②③ 红 (弹框根本不出现), ④ 仍绿 (原生本来就好的)。
 */
import React from 'react';
import { Platform } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Alert } from 'react-native';

import { AppDialogHost, registeredDialogHostCount } from '../../../components/ui/AppDialog';
import { useAuthStore } from '../../../store/authStore';
import type { AuthTokens, User } from '../../../types/auth';

// i18n 在 jest 里不初始化: t(key) 返回 key 本身, 按钮文案即 key。
jest.mock('react-i18next', () => {
  // t 必须引用稳定: 屏内有 useCallback([t]) → useEffect, 每次渲染换新 t 会无限重载
  const t = (k: string) => k;
  const i18n = { language: 'zh-CN', changeLanguage: () => undefined };
  const value = { t, i18n };
  return { useTranslation: () => value };
});
jest.mock('../../../services/api/dashboardApiClient', () => ({
  dashboardApiClient: { getDashboardOverview: jest.fn(() => Promise.resolve(null)) },
}));

const mockDispatch = jest.fn();
jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: jest.fn(),
    goBack: jest.fn(),
    setOptions: jest.fn(),
    addListener: jest.fn(() => jest.fn()),
    dispatch: mockDispatch,
  }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: jest.fn(),
  CommonActions: { reset: (payload: unknown) => ({ type: 'RESET', payload }), navigate: jest.fn() },
}));

jest.mock('expo-localization', () => ({ getLocales: jest.fn(() => [{ languageCode: 'zh' }]) }));
jest.mock('../../../i18n', () => ({ __esModule: true, default: { changeLanguage: jest.fn() }, changeLanguage: jest.fn() }));
jest.mock('expo-linear-gradient', () => ({ LinearGradient: 'LinearGradient' }));
jest.mock('expo-application', () => ({ nativeApplicationVersion: '1.0.0' }));
jest.mock('../../../services/api/platformApiClient', () => ({
  platformAPI: { getPlatformStatistics: jest.fn(() => Promise.resolve({ success: false })) },
}));
jest.mock('../../../services/api/userApiClient', () => ({ userApiClient: { changePassword: jest.fn() } }));

type Case = {
  name: string;
  load: () => React.ComponentType;
  role: string;
  /** 点哪个按钮触发退出 */
  press: () => Promise<Awaited<ReturnType<typeof screen.findByText>>>;
  /** 点击确认后应当回登录页 (屏内自己 dispatch reset) */
  resetsToAuth: boolean;
};

const CASES: Case[] = [
  {
    name: 'ProfileScreen (operator / 通用「我的」)',
    load: () => require('../../../screens/profile/ProfileScreen').default,
    role: 'operator',
    press: () => screen.findByText('退出登录'),
    resetsToAuth: true,
  },
  {
    name: 'QIProfileScreen (质检员)',
    load: () => require('../../../screens/quality-inspector/QIProfileScreen').default,
    role: 'quality_inspector',
    press: () => screen.findByTestId('qi-profile-logout-button'),
    resetsToAuth: false,
  },
  {
    name: 'WHProfileScreen (仓管)',
    load: () => require('../../../screens/warehouse/profile/WHProfileScreen').default,
    role: 'warehouse_worker',
    press: () => screen.findByText('profile.logout'),
    resetsToAuth: false,
  },
  {
    name: 'FAProfileScreen (工厂管理员)',
    load: () => require('../../../screens/factory-admin/profile/FAProfileScreen').FAProfileScreen,
    role: 'factory_super_admin',
    press: () => screen.findByText('menu.logout'),
    resetsToAuth: false,
  },
  {
    name: 'WSProfileScreen (车间主管)',
    load: () => require('../../../screens/workshop-supervisor/profile/WSProfileScreen').WSProfileScreen,
    role: 'workshop_supervisor',
    press: () => screen.findByText('profile.menu.logout'),
    resetsToAuth: false,
  },
  {
    name: 'DSProfileScreen (调度员)',
    load: () => require('../../../screens/dispatcher/profile/DSProfileScreen').default,
    role: 'dispatcher',
    press: () => screen.findByText('退出登录'),
    resetsToAuth: true,
  },
  {
    name: 'HRProfileScreen (HR)',
    load: () => require('../../../screens/hr/profile/HRProfileScreen').default,
    role: 'hr_admin',
    press: () => screen.findByText('profile.logout'),
    resetsToAuth: false,
  },
];

const TOKENS = { accessToken: 'a', refreshToken: 'r' } as unknown as AuthTokens;

function signIn(role: string) {
  const user = {
    id: 7,
    userType: 'factory',
    username: 'tester',
    fullName: '测试员',
    factoryUser: { role, factoryId: 'F006' },
  } as unknown as User;
  useAuthStore.getState().login(user, TOKENS);
}

const originalOS = Platform.OS;
const originalWindow = (globalThis as { window?: unknown }).window;

afterEach(() => {
  (Platform as { OS: string }).OS = originalOS;
  (globalThis as { window?: unknown }).window = originalWindow;
  useAuthStore.getState().logout();
});

describe.each(CASES)('「退出登录」确认框 — $name', (c) => {
  beforeEach(() => {
    jest.clearAllMocks();
    signIn(c.role);
  });

  describe('web (Alert.alert 是 no-op, window.confirm 被吞)', () => {
    beforeEach(() => {
      (Platform as { OS: string }).OS = 'web';
      // 内置浏览器 / 自动化里 window.confirm 永远返回 false (或根本没有): 老代码的 web 分支在这里什么都不会发生
      (globalThis as { window?: unknown }).window = { confirm: jest.fn(() => false), alert: jest.fn() };
    });

    function mountWithRootHost() {
      const Screen = c.load();
      // 与 App.tsx 一致: 屏幕 + 根上一个 AppDialogHost
      const view = render(
        <>
          <Screen />
          <AppDialogHost />
        </>,
      );
      expect(registeredDialogHostCount()).toBeGreaterThanOrEqual(1); // 宿主确实挂上了 (没有它, 点了也没框)
      return view;
    }

    it('点「退出登录」→ 页面里出现确认框 (带 取消 / 退出 两个按钮)', async () => {
      mountWithRootHost();
      expect(screen.queryByTestId('app-dialog-btn-0')).toBeNull(); // 点之前没有框 (对照)
      const btn = await c.press();
      act(() => {
        fireEvent.press(btn);
      });
      expect(screen.getByTestId('app-dialog-btn-0')).toBeTruthy();
      expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy();
      expect(useAuthStore.getState().isAuthenticated).toBe(true); // 点按钮本身不退出, 要等确认
    });

    it('点确认 → 真退出 (isAuthenticated=false, tokens 清空)', async () => {
      mountWithRootHost();
      const btn = await c.press();
      act(() => {
        fireEvent.press(btn);
      });
      act(() => {
        fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
      });
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(useAuthStore.getState().tokens).toBeNull();
      expect(screen.queryByTestId('app-dialog-btn-1')).toBeNull(); // 框已关
      if (c.resetsToAuth) {
        expect(mockDispatch).toHaveBeenCalledTimes(1);
        expect(mockDispatch.mock.calls[0][0]).toMatchObject({
          type: 'RESET',
          payload: { routes: [{ name: 'Auth' }] },
        });
      }
    });

    it('点取消 → 不退出 (阴性对照; 与上一条成对, 证明不是「按钮永远点不动」)', async () => {
      mountWithRootHost();
      const btn = await c.press();
      act(() => {
        fireEvent.press(btn);
      });
      act(() => {
        fireEvent.press(screen.getByTestId('app-dialog-btn-0'));
      });
      expect(useAuthStore.getState().isAuthenticated).toBe(true);
      expect(useAuthStore.getState().tokens).not.toBeNull();
      expect(mockDispatch).not.toHaveBeenCalled();
      expect(screen.queryByTestId('app-dialog-btn-1')).toBeNull(); // 框已关
    });
  });

  describe('原生 (宿主未挂载 → 退回原生 Alert.alert, 行为与改前一致)', () => {
    beforeEach(() => {
      (Platform as { OS: string }).OS = 'ios';
    });

    it('点「退出登录」→ Alert.alert(标题, 账号上下文, [取消, 退出]); 调用确认回调 → 真退出; 取消回调不退出', async () => {
      const Screen = c.load();
      render(<Screen />);
      expect(registeredDialogHostCount()).toBe(0); // 前提: 这一条真的走在「没有宿主」的路径上
      const btn = await c.press();
      act(() => {
        fireEvent.press(btn);
      });
      const alertMock = Alert.alert as jest.Mock;
      expect(alertMock).toHaveBeenCalledTimes(1);
      const [, message, buttons] = alertMock.mock.calls[0] as [string, string, Array<{ text: string; style?: string; onPress?: () => void }>];
      expect(typeof message === 'string' && message.length > 0).toBe(true); // 确认文案没丢 (多数屏带账号上下文, HR 是 i18n 文案)
      expect(buttons).toHaveLength(2);
      const [cancelBtn, confirmBtn] = buttons;
      expect(cancelBtn?.style).toBe('cancel');
      expect(confirmBtn?.style).toBe('destructive');

      act(() => {
        cancelBtn?.onPress?.();
      });
      expect(useAuthStore.getState().isAuthenticated).toBe(true); // 取消不退出
      act(() => {
        confirmBtn?.onPress?.();
      });
      expect(useAuthStore.getState().isAuthenticated).toBe(false);
      expect(useAuthStore.getState().tokens).toBeNull();
      if (c.resetsToAuth) expect(mockDispatch).toHaveBeenCalledTimes(1);
    });
  });
});
