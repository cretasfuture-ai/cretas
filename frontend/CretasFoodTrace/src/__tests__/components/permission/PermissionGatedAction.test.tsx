import React from 'react';
import { Alert } from 'react-native';
import { render, fireEvent } from '@testing-library/react-native';
import { Button } from 'react-native-paper';
import {
  PermissionGatedAction,
  PERMISSION_LOCKED_TITLE,
} from '../../../components/permission/PermissionGatedAction';

const REASON = '审批归工厂超管，你的角色只能查看。';

describe('PermissionGatedAction', () => {
  beforeEach(() => {
    (Alert.alert as jest.Mock).mockClear();
  });

  describe('granted = false（置灰但可见）', () => {
    it('被包的动作【仍然渲染出来】—— 这就是「置灰而不是隐藏」的全部意思', () => {
      const { getByTestId } = render(
        <PermissionGatedAction granted={false} reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={jest.fn()}>审批</Button>
        </PermissionGatedAction>
      );

      expect(getByTestId('approve-btn')).toBeTruthy();
    });

    it('被包的动作拿到 disabled=true', () => {
      const { getByTestId } = render(
        <PermissionGatedAction granted={false} reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={jest.fn()}>审批</Button>
        </PermissionGatedAction>
      );

      expect(getByTestId('approve-btn').props.disabled).toBe(true);
    });

    it('⛔ 业务 handler 被摘掉 —— 置灰的元素不能真的调用后端', () => {
      const onPress = jest.fn();
      const { getByTestId } = render(
        <PermissionGatedAction granted={false} reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={onPress}>审批</Button>
        </PermissionGatedAction>
      );

      // ① props 上没有 handler 了
      expect(getByTestId('approve-btn').props.onPress).toBeUndefined();
      // ② 就算硬点也不会触发
      fireEvent.press(getByTestId('approve-btn'));
      expect(onPress).not.toHaveBeenCalled();
    });

    it('点它会说清「这项归谁」—— ⛔ 不是灰掉不响应', () => {
      const { getByTestId } = render(
        <PermissionGatedAction granted={false} reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={jest.fn()}>审批</Button>
        </PermissionGatedAction>
      );

      fireEvent.press(getByTestId('gate'));

      expect(Alert.alert).toHaveBeenCalledWith(PERMISSION_LOCKED_TITLE, REASON);
    });

    it('外层可点区域把理由挂到无障碍属性上', () => {
      const { getByTestId } = render(
        <PermissionGatedAction granted={false} reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={jest.fn()}>审批</Button>
        </PermissionGatedAction>
      );

      expect(getByTestId('gate').props.accessibilityHint).toBe(REASON);
    });
  });

  describe('granted = true（原样可用）', () => {
    it('不加 disabled，业务 handler 原样保留并能触发', () => {
      const onPress = jest.fn();
      const { getByTestId } = render(
        <PermissionGatedAction granted reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={onPress}>审批</Button>
        </PermissionGatedAction>
      );

      expect(getByTestId('approve-btn').props.disabled).toBeFalsy();

      fireEvent.press(getByTestId('approve-btn'));
      expect(onPress).toHaveBeenCalledTimes(1);
    });

    it('不会弹「这项不归你」', () => {
      const { getByTestId } = render(
        <PermissionGatedAction granted reason={REASON} testID="gate">
          <Button testID="approve-btn" onPress={jest.fn()}>审批</Button>
        </PermissionGatedAction>
      );

      fireEvent.press(getByTestId('approve-btn'));
      expect(Alert.alert).not.toHaveBeenCalled();
    });
  });
});
