import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';

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

const picker = jest.requireMock('expo-image-picker') as {
  requestCameraPermissionsAsync: jest.Mock;
  launchCameraAsync: jest.Mock;
};
picker.requestCameraPermissionsAsync = jest.fn(async () => ({ granted: true }));
picker.launchCameraAsync = jest.fn();

const QILabelQcCreateScreen = require('../../../screens/quality-inspector/QILabelQcCreateScreen').default;
const alertMock = Alert.alert as jest.MockedFunction<typeof Alert.alert>;

const latestAlertButtons = () => {
  const latest = alertMock.mock.calls[alertMock.mock.calls.length - 1];
  return latest?.[2] ?? [];
};

describe('QILabelQcCreateScreen capture quality gate', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    picker.requestCameraPermissionsAsync.mockResolvedValue({ granted: true });
  });

  it('shows a before-capture prompt and requires a full-screen clarity confirmation', async () => {
    picker.launchCameraAsync.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://clear.jpg', width: 3024, height: 4032 }],
    });
    render(<QILabelQcCreateScreen />);

    fireEvent.press(screen.getByTestId('qi-label-qc-create-add-photo-button'));
    expect(alertMock).toHaveBeenLastCalledWith(
      '拍照前先对准',
      expect.stringContaining('白标和彩标要能分辨'),
      expect.any(Array),
    );

    await act(async () => {
      latestAlertButtons()[1]?.onPress?.();
    });

    expect(await screen.findByTestId('qi-label-qc-capture-quality-modal')).toBeTruthy();
    expect(screen.getByText('放大看一下，标签清楚吗？')).toBeTruthy();
    expect(screen.queryByText('已确认清晰')).toBeNull();

    fireEvent.press(screen.getByTestId('qi-label-qc-capture-quality-keep-button'));

    await waitFor(() => expect(screen.getByText('已确认清晰')).toBeTruthy());
    expect(screen.queryByTestId('qi-label-qc-capture-quality-modal')).toBeNull();
  });

  it('rejects low-resolution photos before they enter the confirmation queue', async () => {
    picker.launchCameraAsync.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file://small.jpg', width: 720, height: 1280 }],
    });
    render(<QILabelQcCreateScreen />);

    fireEvent.press(screen.getByTestId('qi-label-qc-create-add-photo-button'));
    await act(async () => {
      latestAlertButtons()[1]?.onPress?.();
    });

    await waitFor(() => {
      expect(alertMock).toHaveBeenCalledWith(
        '照片太小，不能用于审核',
        expect.stringContaining('照片只有 720×1280'),
      );
    });
    expect(screen.queryByTestId('qi-label-qc-capture-quality-modal')).toBeNull();
    expect(screen.queryByText('已确认清晰')).toBeNull();
  });
});
