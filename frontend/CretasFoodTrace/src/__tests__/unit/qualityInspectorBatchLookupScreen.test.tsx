import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import QIScanScreen from '../../screens/quality-inspector/QIScanScreen';

const mockReplace = jest.fn();
const mockNavigate = jest.fn();
const mockRequestPermission = jest.fn();
let mockPermissionGranted = true;
let scanPayload = '';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ replace: mockReplace, navigate: mockNavigate }),
}));
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('expo-camera', () => {
  const React = require('react');
  const { TouchableOpacity } = require('react-native');
  return {
    CameraView: ({ onBarcodeScanned }: { onBarcodeScanned?: (value: { type: string; data: string }) => void }) => (
      <TouchableOpacity
        testID="mock-camera-scan"
        onPress={() => onBarcodeScanned?.({ type: 'qr', data: scanPayload })}
      />
    ),
    Camera: {},
    useCameraPermissions: () => [{ granted: mockPermissionGranted }, mockRequestPermission],
  };
});
jest.mock('@expo/vector-icons', () => ({ Ionicons: () => null }));
jest.mock('../../components/ui/AppDialog', () => ({ appAlert: jest.fn(), appPrompt: jest.fn() }));
jest.mock('../../services/api/qualityInspectorApi', () => ({
  qualityInspectorApi: {
    getBatchByQRCode: jest.fn(),
    getBatchDetail: jest.fn(),
  },
}));

describe('QIScanScreen batch lookup callback', () => {
  const api = jest.requireMock('../../services/api/qualityInspectorApi').qualityInspectorApi;
  const dialogs = jest.requireMock('../../components/ui/AppDialog');

  beforeEach(() => {
    jest.clearAllMocks();
    mockPermissionGranted = true;
    scanPayload = '';
    api.getBatchByQRCode.mockResolvedValue({ id: 'B-LOOKUP', batchNumber: 'PB-LOOKUP', factoryId: 'F006' });
    api.getBatchDetail.mockResolvedValue({ id: 'B-ID', batchNumber: 'PB-ID', factoryId: 'F006' });
  });

  it('passes only parsed JSON batchNumber to the exact lookup API', async () => {
    scanPayload = JSON.stringify({ batchNumber: 'PB-JSON' });
    const screen = render(<QIScanScreen />);
    fireEvent.press(screen.getByTestId('mock-camera-scan'));
    await waitFor(() => expect(api.getBatchByQRCode).toHaveBeenCalledWith('PB-JSON'));
    expect(api.getBatchByQRCode).not.toHaveBeenCalledWith(scanPayload);
    expect(mockReplace).toHaveBeenCalledWith('QIForm', { batchId: 'B-LOOKUP', batchNumber: 'PB-LOOKUP' });
  });

  it('passes a plain scanned batch number to the lookup API', async () => {
    scanPayload = 'PB-PLAIN';
    const screen = render(<QIScanScreen />);
    fireEvent.press(screen.getByTestId('mock-camera-scan'));
    await waitFor(() => expect(api.getBatchByQRCode).toHaveBeenCalledWith('PB-PLAIN'));
  });

  it('uses persisted batch detail when the scan payload already carries batchId', async () => {
    scanPayload = JSON.stringify({ batchId: 'B-ID', batchNumber: 'PB-ID' });
    const screen = render(<QIScanScreen />);
    fireEvent.press(screen.getByTestId('mock-camera-scan'));
    await waitFor(() => expect(api.getBatchDetail).toHaveBeenCalledWith('B-ID'));
    expect(api.getBatchByQRCode).not.toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledWith('QIForm', { batchId: 'B-ID', batchNumber: 'PB-ID' });
  });

  it('allows exact manual batch lookup without camera permission', async () => {
    mockPermissionGranted = false;
    const screen = render(<QIScanScreen />);
    expect(screen.queryByTestId('mock-camera-scan')).toBeNull();
    fireEvent.press(screen.getByTestId('qiscan-denied-manual-input'));
    expect(dialogs.appPrompt).toHaveBeenCalledWith(
      'scan.enterBatchNumber', 'scan.pleaseEnterBatchNumber', expect.any(Function),
      expect.objectContaining({ testID: 'qiscan-manual-batch-input' }),
    );
    expect(api.getBatchByQRCode).not.toHaveBeenCalled();
    const submit = dialogs.appPrompt.mock.calls[0][2] as (value: string) => Promise<void>;
    await act(async () => { await submit('  PB-MANUAL  '); });
    expect(api.getBatchByQRCode).toHaveBeenCalledTimes(1);
    expect(api.getBatchByQRCode).toHaveBeenCalledWith('PB-MANUAL');
    expect(mockReplace).toHaveBeenCalledWith('QIForm', { batchId: 'B-LOOKUP', batchNumber: 'PB-LOOKUP' });
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(mockRequestPermission).not.toHaveBeenCalled();
  });

  it('keeps an unknown manually entered batch rejected without a camera grant', async () => {
    mockPermissionGranted = false;
    api.getBatchByQRCode.mockRejectedValueOnce(new Error('batch not found'));
    const screen = render(<QIScanScreen />);
    fireEvent.press(screen.getByTestId('qiscan-denied-manual-input'));
    const submit = dialogs.appPrompt.mock.calls[0][2] as (value: string) => Promise<void>;
    await act(async () => { await submit('PB-MISSING'); });
    expect(dialogs.appAlert).toHaveBeenCalledWith('scan.scanFailed', 'scan.batchNotFound');
    expect(mockReplace).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(mockRequestPermission).not.toHaveBeenCalled();
    expect(screen.getByTestId('qiscan-denied-manual-input').props.disabled).toBe(false);
  });
});
