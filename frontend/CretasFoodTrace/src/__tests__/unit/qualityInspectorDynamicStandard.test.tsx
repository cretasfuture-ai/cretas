import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import QIFormScreen from '../../screens/quality-inspector/QIFormScreen';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ replace: jest.fn(), navigate: jest.fn(), goBack: jest.fn() }),
  useRoute: () => ({ params: { batchId: '1885', batchNumber: 'PB-1885' } }),
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../store/authStore', () => ({ useAuthStore: () => ({ user: { id: 1557 } }) }));
jest.mock('../../services/api/attachmentApi', () => ({ attachmentApi: {} }));
jest.mock('../../services/api/qualityInspectorApi', () => ({
  qualityInspectorApi: {
    getBatchDetail: jest.fn(), getDynamicQualityForm: jest.fn(), submitDynamicInspection: jest.fn(),
  },
}));

describe('dynamic quality inspection screen', () => {
  const batchId = '1885';
  const api = jest.requireMock('../../services/api/qualityInspectorApi').qualityInspectorApi;

  beforeEach(() => {
    jest.clearAllMocks();
    api.getBatchDetail.mockResolvedValue({ id: batchId, batchNumber: 'PB-1885', productName: 'QA product A', quantity: 1, unit: 'kg', status: 'pending', createdAt: '', factoryId: 'F006' });
    api.getDynamicQualityForm.mockResolvedValue({
      batchId, batchNumber: 'PB-1885', productTypeId: 'QA-S301-A', productName: 'QA product A', standardRevision: 'rev-1',
      items: [{ bindingId: 'binding-a', itemId: 'item-a', itemVersion: 3, label: '温度', valueType: 'RANGE', unit: '°C', effectiveMinValue: 0, effectiveMaxValue: 10, effectiveIsRequired: true }],
    });
  });

  it('renders the product-bound item and sends its revision and identity', async () => {
    api.submitDynamicInspection.mockResolvedValue({ id: 'qi-1' });
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    const input = screen.getByPlaceholderText('填写实测值');
    fireEvent.changeText(input, '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(api.submitDynamicInspection).toHaveBeenCalledWith(batchId, expect.objectContaining({
      standardRevision: 'rev-1', sampleSize: 1,
      answers: [{ bindingId: 'binding-a', itemId: 'item-a', itemVersion: 3, value: '8.5' }],
    })));
  });

  it('blocks missing required input and preserves entered value after stale 409', async () => {
    api.submitDynamicInspection.mockRejectedValue(new Error('质检标准已更新，请刷新后重新确认'));
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    fireEvent.press(screen.getByText('提交质检结果'));
    expect(screen.getByText('请填写必检项目：温度')).toBeTruthy();
    const input = screen.getByPlaceholderText('填写实测值');
    fireEvent.changeText(input, '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(screen.getByText('质检标准已更新，请刷新后重新确认')).toBeTruthy());
    expect(input.props.value).toBe('8.5');
  });
});
