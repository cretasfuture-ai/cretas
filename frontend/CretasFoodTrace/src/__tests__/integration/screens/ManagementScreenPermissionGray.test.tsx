/**
 * ManagementScreen —— 「没权限就隐藏」改成「置灰但可见」的元素级断言。
 *
 * ⚠️ 这个屏原来的写法是 `section.items.filter(item => !item.adminOnly || isAdmin)`,
 * 非管理员看到的是一张【少了几格】的卡(「系统管理」那张甚至整张消失)。
 * 本文件守的行为是: 那些格子【仍然渲染】, 但不可点、且点了会说清归谁。
 *
 * ⛔ 断言全部落在【那个元素】上(存在 / disabled / onPress / 弹窗文案),
 *    不做整文件 toContain。
 */
import React from 'react';
import { Alert } from 'react-native';
import { render, fireEvent } from '@testing-library/react-native';
import ManagementScreen from '../../../screens/management/ManagementScreen';
import { useAuthStore } from '../../../store/authStore';
import { PERMISSION_LOCKED_TITLE } from '../../../components/permission/PermissionGatedAction';

// 用【真的】 zh-CN 文案做查表, 而不是 t: key => key。
// 这样断言读的是用户真正看到的那句话, 且 i18n 键少了会当场红。
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const dict = require('../../../i18n/locales/zh-CN/management.json');
      const hit = key.split('.').reduce((o: any, k: string) => (o == null ? o : o[k]), dict);
      return typeof hit === 'string' ? hit : key;
    },
  }),
}));

jest.mock('../../../store/authStore', () => ({
  useAuthStore: jest.fn(),
}));

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

jest.mock('../../../components/ui', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  return {
    NeoCard: ({ children, style, ...props }: any) => <View style={style} {...props}>{children}</View>,
    NeoButton: ({ children, onPress, ...props }: any) => (
      <TouchableOpacity onPress={onPress} {...props}><Text>{children}</Text></TouchableOpacity>
    ),
    ScreenWrapper: ({ children }: any) => <View>{children}</View>,
    StatusBadge: ({ children }: any) => <Text>{children}</Text>,
  };
});

jest.mock('../../../theme', () => ({
  theme: {
    colors: {
      primary: '#1890ff',
      background: '#f5f5f5',
      surface: '#ffffff',
      text: '#1F2937',
      textSecondary: '#6B7280',
      textTertiary: '#9CA3AF',
    },
  },
}));

const LOCKED_REASON = require('../../../i18n/locales/zh-CN/management.json').locked.adminOnlyReason;
const LOCKED_BADGE = require('../../../i18n/locales/zh-CN/management.json').locked.adminOnlyBadge;

/** `users` 是 adminOnly 的格子; `suppliers` 不是 —— 后者当阴性对照。 */
const ADMIN_ONLY_TILE = 'users';
const OPEN_TILE = 'suppliers';

function mockUserRole(role: string, userType: 'factory' | 'platform' = 'factory') {
  (useAuthStore as unknown as jest.Mock).mockReturnValue({
    user: { userType, factoryUser: { role } },
  });
}

describe('ManagementScreen —— 无权限的入口置灰而不是隐藏', () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    (Alert.alert as jest.Mock).mockClear();
  });

  describe('非管理员（operator）', () => {
    beforeEach(() => mockUserRole('operator'));

    it('adminOnly 的格子【仍然渲染出来】—— 原来它是被 filter 掉的', () => {
      const { getByTestId } = render(<ManagementScreen />);
      expect(getByTestId(`management-tile-${ADMIN_ONLY_TILE}`)).toBeTruthy();
    });

    it('那个格子的入口是 disabled 的, 且业务 onPress 已被摘掉', () => {
      const { getByTestId } = render(<ManagementScreen />);
      const action = getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`);

      expect(action.props.disabled).toBe(true);
      expect(action.props.onPress).toBeUndefined();
    });

    it('⛔ 点它进不去那个屏 —— 置灰只改展示, 不放行', () => {
      const { getByTestId } = render(<ManagementScreen />);
      fireEvent.press(getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`));
      expect(mockNavigate).not.toHaveBeenCalled();
    });

    it('点它会说清「这项归谁」, 而不是没反应', () => {
      const { getByTestId } = render(<ManagementScreen />);
      fireEvent.press(getByTestId(`management-tile-${ADMIN_ONLY_TILE}`));

      expect(Alert.alert).toHaveBeenCalledWith(PERMISSION_LOCKED_TITLE, LOCKED_REASON);
      // 弹的是真文案, 不是 i18n 键
      expect(LOCKED_REASON).not.toContain('locked.');
    });

    it('格子上直接标出「仅管理员可进」, 不用点也能看出来', () => {
      const { getAllByText } = render(<ManagementScreen />);
      expect(getAllByText(LOCKED_BADGE).length).toBeGreaterThan(0);
    });

    it('阴性对照: 非 adminOnly 的格子照常可点, 没有被一起灰掉', () => {
      const { getByTestId } = render(<ManagementScreen />);
      const action = getByTestId(`management-tile-action-${OPEN_TILE}`);

      expect(action.props.disabled).toBeFalsy();
      fireEvent.press(action);
      expect(mockNavigate).toHaveBeenCalledWith('SupplierManagement');
    });
  });

  describe('管理员（factory_super_admin）', () => {
    beforeEach(() => mockUserRole('factory_super_admin'));

    it('同一个格子可点并正常导航', () => {
      const { getByTestId } = render(<ManagementScreen />);
      const action = getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`);

      expect(action.props.disabled).toBeFalsy();
      fireEvent.press(action);
      expect(mockNavigate).toHaveBeenCalledWith('UserManagement');
    });

    it('不会弹「这项不归你」', () => {
      const { getByTestId } = render(<ManagementScreen />);
      fireEvent.press(getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`));
      expect(Alert.alert).not.toHaveBeenCalled();
    });
  });
});
