import React, { forwardRef, useImperativeHandle } from 'react';
import { View } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';

const takePictureAsync = jest.fn();

jest.mock('expo-camera', () => ({
  CameraView: forwardRef((_props: unknown, ref) => {
    useImperativeHandle(ref, () => ({ takePictureAsync }));
    return <View testID="mock-camera-view" />;
  }),
  useCameraPermissions: () => [
    { granted: true },
    jest.fn(async () => ({ granted: true })),
  ],
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, right: 0, bottom: 0, left: 0 }),
}));

jest.mock('react-native-svg', () => {
  const ReactRuntime = require('react');
  const { View: NativeView } = require('react-native');
  const SvgMock = ({ children }: { children?: React.ReactNode }) => (
    <NativeView testID="mock-guide-svg">{children}</NativeView>
  );
  return {
    __esModule: true,
    default: SvgMock,
    Polygon: (props: object) => ReactRuntime.createElement(NativeView, { ...props, testID: 'mock-guide-polygon' }),
    Line: (props: object) => ReactRuntime.createElement(NativeView, { ...props, testID: 'mock-guide-bottom-line' }),
  };
});

const QILabelQcCameraModal = require('../../../screens/quality-inspector/QILabelQcCameraModal').default;

describe('QILabelQcCameraModal', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    takePictureAsync.mockResolvedValue({
      uri: 'file://capture.jpg',
      width: 3024,
      height: 4032,
    });
  });

  it('keeps a rejected photo in the camera and gives one retake message', async () => {
    const onAccepted = jest.fn();
    const checkQuality = jest.fn(async () => ({
      accepted: false,
      reasonCode: 'TRAY_CROPPED_AT_SIDE' as const,
      message: '左右下角托盘没有拍全，请后退一点或切换广角重拍',
      imageWidth: 3024,
      imageHeight: 4032,
      trayCount: 18,
      sharpness: 900,
      leftMarginRatio: 0.04,
      rightMarginRatio: 0.002,
    }));

    render(
      <QILabelQcCameraModal
        visible
        contextLabel="CPLIUSHANMEN0015 · BATCH-01"
        onClose={jest.fn()}
        onAccepted={onAccepted}
        checkQuality={checkQuality}
      />,
    );

    await act(async () => {
      fireEvent.press(screen.getByTestId('qi-label-qc-camera-shutter'));
    });

    expect(await screen.findByText('左右下角托盘没有拍全，请后退一点或切换广角重拍')).toBeTruthy();
    expect(onAccepted).not.toHaveBeenCalled();
    expect(screen.getByTestId('mock-camera-view')).toBeTruthy();
  });

  it('automatically accepts a clear and complete photo without a checklist', async () => {
    const onAccepted = jest.fn();
    const checkQuality = jest.fn(async () => ({
      accepted: true,
      reasonCode: null,
      message: '照片清晰，托盘已完整入框',
      imageWidth: 3024,
      imageHeight: 4032,
      trayCount: 18,
      sharpness: 1100,
      leftMarginRatio: 0.04,
      rightMarginRatio: 0.03,
    }));

    render(
      <QILabelQcCameraModal
        visible
        contextLabel="CPLIUSHANMEN0015 · BATCH-01"
        onClose={jest.fn()}
        onAccepted={onAccepted}
        checkQuality={checkQuality}
      />,
    );

    fireEvent.press(screen.getByTestId('qi-label-qc-camera-shutter'));

    await waitFor(() => expect(onAccepted).toHaveBeenCalledWith(expect.objectContaining({
      uri: 'file://capture.jpg',
      width: 3024,
      height: 4032,
    })));
    expect(screen.queryByText('放大看一下，标签清楚吗？')).toBeNull();
  });
});
