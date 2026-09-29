import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, getParent: () => null }),
}));

import WHHomeScreen from '../../../../screens/warehouse/home/WHHomeScreen';

it('仓管首页点击仓库地图后直接请求打开平面图', () => {
  const screen = render(<WHHomeScreen />);
  fireEvent.press(screen.getByTestId('wh-home-warehouse-map'));
  expect(mockNavigate).toHaveBeenCalledWith('WHLocationManage', { openMap: true });
});
