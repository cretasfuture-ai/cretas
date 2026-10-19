import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import PlanDetailScreen from '../../../screens/dispatcher/plan/PlanDetailScreen';

const mockNavigate = jest.fn();
const mockGetProductionPlanById = jest.fn();
const mockGetMaterialAdvisory = jest.fn();
const mockSchedulingReschedule = jest.fn();
const mockNavigation = { navigate: mockNavigate, goBack: jest.fn() };
const mockT = (key: string) => key;

jest.mock('../../../services/api/productionPlanApiClient', () => ({
  productionPlanApiClient: {
    getProductionPlanById: (...args: unknown[]) => mockGetProductionPlanById(...args),
    getMaterialAdvisory: (...args: unknown[]) => mockGetMaterialAdvisory(...args),
    cancelProductionPlan: jest.fn(),
    completeProduction: jest.fn(),
  },
}));

jest.mock('../../../services/api/schedulingApiClient', () => ({
  schedulingApiClient: {
    reschedule: (...args: unknown[]) => mockSchedulingReschedule(...args),
  },
}));

jest.mock('../../../services/api/printApiClient', () => ({ safePrint: jest.fn() }));

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
  useRoute: () => ({ params: mockRouteParams }),
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: mockT }),
}));

jest.mock('expo-linear-gradient', () => ({
  LinearGradient: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));

const productionPlan = {
  id: 'prod-plan-999',
  planNumber: 'PROD-999',
  productTypeName: '冷冻鱼排',
  productUnit: 'kg',
  plannedQuantity: 100,
  actualQuantity: 0,
  status: 'IN_PROGRESS',
};

const mockRouteParams = { planId: productionPlan.id, planData: productionPlan };

describe('PlanDetailScreen production-plan boundary', () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    mockGetProductionPlanById.mockReset();
    mockGetMaterialAdvisory.mockReset();
    mockSchedulingReschedule.mockReset();
    mockGetProductionPlanById.mockResolvedValue({ success: true, data: productionPlan });
    mockGetMaterialAdvisory.mockResolvedValue({ success: false, data: null });
  });

  it('uses the uppercase production status gate and navigates to scheduling reason capture without crossing domains', async () => {
    const view = render(<PlanDetailScreen />);

    await waitFor(() => expect(view.getByText('记录原因')).toBeTruthy());
    expect(mockGetProductionPlanById).toHaveBeenCalledWith('prod-plan-999');
    fireEvent.press(view.getByTestId('record-adjustment-reason-button'));

    expect(mockNavigate).toHaveBeenCalledWith('AIReschedule');
    expect(mockNavigate).not.toHaveBeenCalledWith('AIReschedule', expect.anything());
    expect(mockSchedulingReschedule).not.toHaveBeenCalled();
  });
});
