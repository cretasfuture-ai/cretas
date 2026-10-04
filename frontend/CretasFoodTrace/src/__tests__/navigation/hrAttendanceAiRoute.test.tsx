import React from 'react';
import { render } from '@testing-library/react-native';
import { Text, View } from 'react-native';

let registered: Array<{ name: string; component: React.ComponentType }> = [];
let currentUser: { factoryUser?: { modulePermissions?: Record<string, string> } } | null = {
  factoryUser: { modulePermissions: { analytics: '-' } },
};
const aiRender = jest.fn(() => <Text>real AI screen</Text>);

jest.mock('../..//store/authStore', () => ({
  useAuthStore: Object.assign((selector?: (state: { user: typeof currentUser }) => unknown) => selector ? selector({ user: currentUser }) : { user: currentUser }, {
    getState: () => ({ user: currentUser }),
  }),
}));
jest.mock('../../utils/permissionHelper', () => ({
  canUseAIQuery: (user: typeof currentUser) => user?.factoryUser?.modulePermissions?.analytics === 'r' || user?.factoryUser?.modulePermissions?.analytics === 'rw',
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key === 'attendance.manage.aiDenied' ? '当前账号没有经营分析权限。' : key }) }));
jest.mock('../../screens/factory-admin/ai-analysis/AIChatScreen', () => ({ __esModule: true, default: () => aiRender() }));
jest.mock('../../components/common/ScreenErrorBoundary', () => ({ screenErrorBoundary: ({ children }: { children?: React.ReactNode }) => <>{children}</> }));
jest.mock('../../navigation/HRTabNavigator', () => ({ __esModule: true, default: () => <View /> }));
jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children?: React.ReactNode }) => <View>{children}</View>,
    Screen: (props: { name: string; component: React.ComponentType }) => {
      registered.push({ name: props.name, component: props.component });
      return <View testID={`registered-${props.name}`} />;
    },
  }),
}));

import HRNavigator from '../../navigation/HRNavigator';

describe('hrAttendanceAiRoute', () => {
  beforeEach(() => {
    registered = [];
    aiRender.mockClear();
    currentUser = { factoryUser: { modulePermissions: { analytics: '-' } } };
  });

  it('registers a real AIChat screen and guards canonical analytics permission', () => {
    render(<HRNavigator />);
    const route = registered.find((screen) => screen.name === 'AIChat');
    expect(route).toBeDefined();
    const denied = render(React.createElement(route!.component));
    expect(denied.getByText('当前账号没有经营分析权限。')).toBeTruthy();
    expect(aiRender).not.toHaveBeenCalled();
    currentUser = { factoryUser: { modulePermissions: { analytics: 'r' } } };
    denied.rerender(React.createElement(route!.component));
    expect(aiRender).toHaveBeenCalledTimes(1);
  });
});
