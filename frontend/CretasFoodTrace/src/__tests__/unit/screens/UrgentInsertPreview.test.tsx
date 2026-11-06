import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import UrgentInsertScreen from '../../../screens/dispatcher/plan/UrgentInsertScreen';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { productTypeApiClient } from '../../../services/api/productTypeApiClient';

jest.mock('../../../services/api/schedulingApiClient', () => ({ schedulingApiClient: {
  getAvailableSlots: jest.fn(), createInsertPreview: jest.fn(), confirmUrgentInsert: jest.fn(), forceUrgentInsert: jest.fn(),
} }));
jest.mock('../../../services/api/productTypeApiClient', () => ({ productTypeApiClient: { getProductTypes: jest.fn() } }));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('expo-linear-gradient', () => ({ LinearGradient: require('react-native').View }));

const slots = [{ id: 'SLOT', productionLineId: 'LINE', productionLineName: 'actual line',
  startTime: '2027-01-05T09:00:00', endTime: '2027-01-05T11:00:00',
  availableCapacity: 0, recommendScore: 80, impactLevel: 'none' as const,
  requiredWorkers: 0, availableWorkers: 0, switchCostMinutes: 0 }];
const preview = { slotId: 'SLOT', previewId: 'immutable-preview', quantityUnit: 'PIECE' as const,
  plannedUnit: '个', previewQuantity: 17, affectedPlanCount: 0, impactLevel: 'none' as const, impactScore: 0,
  chainImpact: { directConflicts: 0, cascadeDelays: 0, maxDelayMinutes: 0 }, affectedPlans: [],
  resourceImpact: {}, recommendation: '', requiresApproval: false,
  isFeasible: true, resourcesVerified: true, feasibilityStatus: 'FEASIBLE' as const };

async function selectAndLoadSlots() {
  render(<UrgentInsertScreen />);
  await waitFor(() => expect(productTypeApiClient.getProductTypes).toHaveBeenCalled());
  fireEvent.press(screen.getByText('请选择产品类型'));
  await waitFor(() => expect(screen.getByText('count product')).toBeTruthy());
  fireEvent.press(screen.getByText('count product'));
  fireEvent.changeText(screen.getByPlaceholderText('请输入数量'), '17');
  fireEvent.changeText(screen.getByPlaceholderText('例：2025-12-29 18:00'), '2027-01-05 18:00');
  fireEvent.changeText(screen.getByPlaceholderText(/请说明紧急插单的原因/), 'test request');
  fireEvent.press(screen.getByText('AI 分析可用时段'));
  await waitFor(() => expect(screen.getByText('actual line')).toBeTruthy());
}

describe('urgent insert immutable preview UI', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    jest.mocked(productTypeApiClient.getProductTypes).mockResolvedValue({
      data: [{ id: 'PRODUCT', name: 'count product', unit: '个', factoryId: 'F006', productCode: 'COUNT',
        isActive: true, createdAt: '2027-01-01T00:00:00' }],
    });
    jest.mocked(schedulingApiClient.getAvailableSlots).mockResolvedValue({ success: true, message: 'ok', data: slots });
    jest.mocked(schedulingApiClient.createInsertPreview).mockResolvedValue({ success: true, message: 'ok', data: preview });
    jest.mocked(schedulingApiClient.confirmUrgentInsert).mockRejectedValue(new Error('test terminal response'));
    jest.mocked(schedulingApiClient.forceUrgentInsert).mockRejectedValue(new Error('test terminal response'));
  });

  afterEach(() => jest.restoreAllMocks());

  it('submits the server preview and product capacity unit unchanged', async () => {
    await selectAndLoadSlots();
    fireEvent.press(screen.getByText('actual line'));
    await waitFor(() => expect(screen.getByText('资源已核对，可行')).toBeTruthy());
    fireEvent.press(screen.getByText('确认插单'));
    fireEvent.press(screen.getByText('确认'));
    await waitFor(() => expect(schedulingApiClient.confirmUrgentInsert).toHaveBeenCalledWith(expect.objectContaining({
      previewId: 'immutable-preview', slotId: 'SLOT', productTypeId: 'PRODUCT', plannedQuantity: 17,
      quantityUnit: 'PIECE', requestedDeadline: '2027-01-05T18:00',
    })));
  });

  it('clears a loaded preview when the quantity changes', async () => {
    await selectAndLoadSlots();
    fireEvent.press(screen.getByText('actual line'));
    await waitFor(() => expect(screen.getByText('资源已核对，可行')).toBeTruthy());
    fireEvent.changeText(screen.getByPlaceholderText('请输入数量'), '18');
    expect(screen.queryByText('确认插单')).toBeNull();
    expect(schedulingApiClient.confirmUrgentInsert).not.toHaveBeenCalled();
  });

  it.each(['high', 'critical', 'low'] as const)('uses preview %s/approval authority even when the generic slot had no impact', async impactLevel => {
    jest.mocked(schedulingApiClient.createInsertPreview).mockResolvedValue({
      success: true, message: 'ok', data: { ...preview, impactLevel, requiresApproval: true },
    });
    await selectAndLoadSlots();
    fireEvent.press(screen.getByText('actual line'));
    await waitFor(() => expect(screen.getByText('资源已核对，可行')).toBeTruthy());
    fireEvent.press(screen.getByText('确认插单'));
    const buttons = jest.mocked(Alert.alert).mock.calls.at(-1)?.[2];
    act(() => { buttons?.find(button => button.text === 'common.confirm')?.onPress?.(); });
    fireEvent.press(screen.getByText('确认'));
    await waitFor(() => expect(schedulingApiClient.forceUrgentInsert).toHaveBeenCalledWith(expect.objectContaining({
      previewId: 'immutable-preview', forceInsert: true,
    })));
    expect(schedulingApiClient.confirmUrgentInsert).not.toHaveBeenCalled();
  });

  it('ignores an in-flight preview response for the previous form values', async () => {
    let finish: ((value: Awaited<ReturnType<typeof schedulingApiClient.createInsertPreview>>) => void) | undefined;
    jest.mocked(schedulingApiClient.createInsertPreview).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    await selectAndLoadSlots();
    fireEvent.press(screen.getByText('actual line'));
    fireEvent.changeText(screen.getByPlaceholderText('请输入数量'), '18');
    await act(async () => { finish?.({ success: true, message: 'ok', data: preview }); });
    expect(screen.queryByText('资源已核对，可行')).toBeNull();
    expect(screen.queryByText('确认插单')).toBeNull();
    expect(schedulingApiClient.confirmUrgentInsert).not.toHaveBeenCalled();
  });
});
