import React from 'react';
import { Pressable, Text } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ replace: jest.fn() }),
}));

jest.mock('@react-native-community/datetimepicker', () => ({
  __esModule: true,
  default: 'DateTimePicker',
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
}));

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: { user: { factoryId: string } }) => unknown) =>
    selector({ user: { factoryId: 'LIUSHANMEN' } }),
}));

jest.mock('../../../services/api/productTypeApiClient', () => ({
  productTypeApiClient: {
    getActiveFinishedProductOptions: jest.fn(async () => []),
  },
}));

jest.mock('../../../screens/quality-inspector/QILabelQcCameraModal', () => ({
  __esModule: true,
  default: ({ visible, onAccepted }: {
    visible: boolean;
    onAccepted: (asset: { uri: string; width: number; height: number }) => void;
  }) => visible ? (
    <Pressable
      testID="mock-camera-accept"
      onPress={() => onAccepted({ uri: 'file://checked.jpg', width: 3024, height: 4032 })}
    >
      <Text>应用内相机</Text>
    </Pressable>
  ) : null,
}));

const QILabelQcCreateScreen = require('../../../screens/quality-inspector/QILabelQcCreateScreen').default;

describe('QILabelQcCreateScreen capture quality flow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('opens the in-app camera directly and removes the old reading/checklist steps', async () => {
    render(<QILabelQcCreateScreen />);

    expect(screen.queryByText(/拍近：/)).toBeNull();
    expect(screen.queryByText('放大看一下，标签清楚吗？')).toBeNull();

    fireEvent.press(screen.getByTestId('qi-label-qc-create-add-photo-button'));
    expect(await screen.findByText('应用内相机')).toBeTruthy();

    fireEvent.press(screen.getByTestId('mock-camera-accept'));

    await waitFor(() => expect(screen.getByText('已自动检查')).toBeTruthy());
    expect(screen.queryByText('应用内相机')).toBeNull();
  });
});
