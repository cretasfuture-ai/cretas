import React, { forwardRef, useImperativeHandle } from 'react';
import { View } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';

const takePictureAsync = jest.fn();
const manipulateAsync = jest.fn();

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

jest.mock('expo-image-manipulator', () => ({
  manipulateAsync: (...args: unknown[]) => manipulateAsync(...args),
  SaveFormat: { JPEG: 'jpeg' },
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
    manipulateAsync.mockResolvedValue({
      uri: 'file://capture-compressed.jpg',
      width: 1200,
      height: 1600,
    });
  });

  it('accepts the photo immediately and reports a later quality advisory without blocking', async () => {
    const onAccepted = jest.fn();
    const onQualityAdvisory = jest.fn();
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
        onQualityAdvisory={onQualityAdvisory}
        checkQuality={checkQuality}
      />,
    );

    await act(async () => {
      fireEvent.press(screen.getByTestId('qi-label-qc-camera-shutter'));
    });

    await waitFor(() => expect(onAccepted).toHaveBeenCalledWith(expect.objectContaining({
      uri: 'file://capture-compressed.jpg',
      width: 1200,
      height: 1600,
    })));
    expect(checkQuality).toHaveBeenCalledWith(expect.objectContaining({
      uri: 'file://capture-compressed.jpg',
      width: 1200,
      height: 1600,
    }));
    await waitFor(() => expect(onQualityAdvisory).toHaveBeenCalledWith(
      '左右下角托盘没有拍全，请后退一点或切换广角重拍',
    ));
  });

  it('does not wait for a slow quality check before accepting the photo', async () => {
    const onAccepted = jest.fn();
    const onQualityAdvisory = jest.fn();
    const checkQuality = jest.fn(() => new Promise<never>(() => undefined));

    render(
      <QILabelQcCameraModal
        visible
        contextLabel="CPLIUSHANMEN0015 · BATCH-01"
        onClose={jest.fn()}
        onAccepted={onAccepted}
        onQualityAdvisory={onQualityAdvisory}
        checkQuality={checkQuality}
      />,
    );

    fireEvent.press(screen.getByTestId('qi-label-qc-camera-shutter'));

    await waitFor(() => expect(onAccepted).toHaveBeenCalledWith(expect.objectContaining({
      uri: 'file://capture-compressed.jpg',
      width: 1200,
      height: 1600,
    })));
    expect(manipulateAsync).toHaveBeenCalledWith(
      'file://capture.jpg',
      [{ resize: { height: 1600 } }],
      { compress: 0.72, format: 'jpeg' },
    );
    expect(checkQuality).toHaveBeenCalledTimes(1);
    expect(onQualityAdvisory).not.toHaveBeenCalled();
    expect(screen.queryByText('检查中…')).toBeNull();
  });

  it('still accepts the photo when the background quality request fails', async () => {
    const onAccepted = jest.fn();
    const onQualityAdvisory = jest.fn();
    const checkQuality = jest.fn(async () => {
      throw new Error('network timeout');
    });

    render(
      <QILabelQcCameraModal
        visible
        contextLabel="CPLIUSHANMEN0015 · BATCH-01"
        onClose={jest.fn()}
        onAccepted={onAccepted}
        onQualityAdvisory={onQualityAdvisory}
        checkQuality={checkQuality}
      />,
    );

    fireEvent.press(screen.getByTestId('qi-label-qc-camera-shutter'));

    await waitFor(() => expect(onAccepted).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(checkQuality).toHaveBeenCalledTimes(1));
    expect(onQualityAdvisory).not.toHaveBeenCalled();
    expect(screen.queryByTestId('qi-label-qc-camera-error')).toBeNull();
  });
});
