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
/*
 * ⚠️ 这个常量原来是 'users'（在「系统管理」区）。改掉不是因为断言写错了，是**需求变了**：
 *
 * 置灰的价值来自**对比** —— 旁边有能点的，灰的那格才传达「这个我有、只是不归我」。
 * 「系统管理」(4/4) 和「工厂配置」(2/2) 对非管理员是 100% adminOnly，
 * 渲染出来是一个区标题压着一排全灰格子，没有对比对象 ⇒ 那两个区仍然整区隐藏。
 *
 * 所以要验「置灰而非隐藏」，必须挑一个**混合区**里的 adminOnly 格子：
 * 'work-types' 在「生产配置」(6 格 / 2 格 adminOnly)。
 */
const ADMIN_ONLY_TILE = 'work-types';
/** 同一个混合区里【不需要】管理员权限的格子 —— 阴性对照用 */
const OPEN_TILE = 'suppliers';
/** 整区全 adminOnly 的区里的格子 —— 用来验「全锁区仍然隐藏」 */
const FULLY_LOCKED_SECTION_TILE = 'users';

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
      expect(mockNavigate).toHaveBeenCalledWith('WorkTypeManagement');
    });

    it('不会弹「这项不归你」', () => {
      const { getByTestId } = render(<ManagementScreen />);
      fireEvent.press(getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`));
      expect(Alert.alert).not.toHaveBeenCalled();
    });

    it('阴性对照: 全锁区对管理员是【看得到】的 —— 隐藏只针对没权限的人', () => {
      const { getByTestId } = render(<ManagementScreen />);
      expect(getByTestId(`management-tile-${FULLY_LOCKED_SECTION_TILE}`)).toBeTruthy();
    });
  });

  /*
   * 整区全锁 ⇒ 整区隐藏。
   *
   * ⛔ 这不是退回「按权限过滤」—— 混合区里的 adminOnly 格子仍然置灰可见(上面那组断言)。
   *    收窄的只有「一格都不归你的那个区」: 它没有对比对象, 渲染出来只是把一堵墙
   *    从看不见变成看得见。
   */
  describe('整区全 adminOnly 的区, 对非管理员仍然整区隐藏', () => {
    beforeEach(() => mockUserRole('operator'));

    it('🔴 「系统管理」整区不渲染 —— 4 格全是 adminOnly', () => {
      const { queryByTestId } = render(<ManagementScreen />);
      expect(queryByTestId(`management-tile-${FULLY_LOCKED_SECTION_TILE}`)).toBeNull();
    });

    it('阴性对照: 混合区里的 adminOnly 格子【没有】被一起隐藏, 仍然置灰可见', () => {
      // 这一条是本规则的边界 —— 少了它, 「整区隐藏」会退化成「按权限过滤」而无人察觉
      const { getByTestId } = render(<ManagementScreen />);
      const action = getByTestId(`management-tile-action-${ADMIN_ONLY_TILE}`);
      expect(action.props.disabled).toBe(true);
    });

    it('阴性对照: 混合区里可用的格子照常可点', () => {
      const { getByTestId } = render(<ManagementScreen />);
      expect(getByTestId(`management-tile-action-${OPEN_TILE}`).props.disabled).toBeFalsy();
    });
  });
});
