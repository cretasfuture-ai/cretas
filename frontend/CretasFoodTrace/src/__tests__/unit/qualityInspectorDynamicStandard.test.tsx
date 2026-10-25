import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import QIFormScreen from '../../screens/quality-inspector/QIFormScreen';

const mockReplace = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ replace: mockReplace, navigate: jest.fn(), goBack: jest.fn() }),
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
    api.submitDynamicInspection.mockResolvedValue({ inspection: { id: 'qi-1', result: 'FAIL' }, standardRevision: 'rev-1' });
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    const input = screen.getByPlaceholderText('填写实测值');
    fireEvent.changeText(input, '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(api.submitDynamicInspection).toHaveBeenCalledWith(batchId, expect.objectContaining({
      standardRevision: 'rev-1', sampleSize: 1,
      answers: [{ bindingId: 'binding-a', itemId: 'item-a', itemVersion: 3, value: '8.5' }],
    })));
    expect(mockReplace).toHaveBeenCalledWith('QIResult', { recordId: 'qi-1', passed: false });
  });

  it('refreshes a stale standard on the mounted action, preserves input, and resubmits with the new revision', async () => {
    api.submitDynamicInspection
      .mockRejectedValueOnce(new Error('质检标准已更新，请刷新后重新确认'))
      .mockResolvedValueOnce({ inspection: { id: 'qi-refresh', result: 'PASS' }, standardRevision: 'rev-2' });
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    fireEvent.press(screen.getByText('提交质检结果'));
    expect(screen.getByText('请填写必检项目：温度')).toBeTruthy();
    const input = screen.getByPlaceholderText('填写实测值');
    fireEvent.changeText(input, '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(screen.getByText('质检标准已更新，请刷新后重新确认')).toBeTruthy());
    expect(input.props.value).toBe('8.5');

    api.getDynamicQualityForm.mockResolvedValueOnce({
      batchId, batchNumber: 'PB-1885', productTypeId: 'QA-S301-A', productName: 'QA product A', standardRevision: 'rev-2',
      items: [{ bindingId: 'binding-a', itemId: 'item-a', itemVersion: 4, label: '温度', valueType: 'RANGE', unit: '°C', effectiveMinValue: 0, effectiveMaxValue: 12, effectiveIsRequired: true }],
    });
    fireEvent.press(screen.getByText('刷新标准（保留已填写答案）'));
    await waitFor(() => expect(screen.getByText('标准版本：rev-2')).toBeTruthy());
    expect(screen.getByPlaceholderText('填写实测值').props.value).toBe('8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(api.submitDynamicInspection).toHaveBeenCalledTimes(2));
    expect(api.submitDynamicInspection).toHaveBeenLastCalledWith(batchId, expect.objectContaining({
      standardRevision: 'rev-2',
      answers: [{ bindingId: 'binding-a', itemId: 'item-a', itemVersion: 4, value: '8.5' }],
    }));
    expect(mockReplace).toHaveBeenCalledWith('QIResult', { recordId: 'qi-refresh', passed: true });
  });

  it('keeps the entered value when refreshing a stale standard fails', async () => {
    api.submitDynamicInspection.mockRejectedValueOnce(new Error('质检标准已更新，请刷新后重新确认'));
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    const input = screen.getByPlaceholderText('填写实测值');
    fireEvent.changeText(input, '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(screen.getByText('刷新标准（保留已填写答案）')).toBeTruthy());
    api.getDynamicQualityForm.mockRejectedValueOnce(new Error('网络暂不可用'));
    fireEvent.press(screen.getByText('刷新标准（保留已填写答案）'));
    await waitFor(() => expect(screen.getByText('网络暂不可用')).toBeTruthy());
    expect(screen.getByPlaceholderText('填写实测值').props.value).toBe('8.5');
  });

  it('does not navigate when the server omits the inspection id', async () => {
    api.submitDynamicInspection.mockResolvedValue({ inspection: { result: 'PASS' }, standardRevision: 'rev-1' });
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByText(/温度/)).toBeTruthy());
    fireEvent.changeText(screen.getByPlaceholderText('填写实测值'), '8.5');
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(screen.getByText('服务端未返回完整的质检结果，请保留当前填写内容后重试')).toBeTruthy());
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('renders boolean standards as explicit yes/no choices', async () => {
    api.getDynamicQualityForm.mockResolvedValue({
      batchId, batchNumber: 'PB-1885', productTypeId: 'QA-S301-A', productName: 'QA product A', standardRevision: 'rev-bool',
      items: [{ bindingId: 'binding-bool', itemId: 'item-bool', itemVersion: 1, label: '包装完好', valueType: 'BOOLEAN', unit: null, effectiveStandardValue: 'true', effectiveIsRequired: true }],
    });
    api.submitDynamicInspection.mockResolvedValue({ inspection: { id: 'qi-bool', result: 'PASS' }, standardRevision: 'rev-bool' });
    render(<QIFormScreen />);
    await waitFor(() => expect(screen.getByLabelText('包装完好 是')).toBeTruthy());
    fireEvent.press(screen.getByLabelText('包装完好 是'));
    fireEvent.press(screen.getByText('提交质检结果'));
    await waitFor(() => expect(api.submitDynamicInspection).toHaveBeenCalledWith(batchId, expect.objectContaining({
      answers: [{ bindingId: 'binding-bool', itemId: 'item-bool', itemVersion: 1, value: 'true' }],
    })));
  });
});
