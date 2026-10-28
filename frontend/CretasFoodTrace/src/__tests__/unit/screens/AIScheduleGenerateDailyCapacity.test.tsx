// @ts-nocheck
/**
 * APS v2 (Steve 2026-10-28): 两样都没有日产能时 App 排程页当场问「一天能做多少」,
 * 填完带 dailyCapacityOverrides 重新提交; 排出来后按天显示哪天做多少。
 * 跑真实屏幕 + 真实 dailyCapacityPrompt, 只桩 API 边界。
 */
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import {
  buildCapacityOverrides,
  capacityBlockReason,
  capacityKey,
  describeCapacity,
  describeDateRange,
  describeDays,
} from '../../../screens/dispatcher/ai/dailyCapacityPrompt';

const mockGenerate = jest.fn();
const mockGetPlans = jest.fn();
const mockAppAlert = jest.fn();

jest.mock('../../../services/api/schedulingApiClient', () => ({
  schedulingApiClient: {
    generateSchedule: (...args: unknown[]) => mockGenerate(...args),
    confirmPlan: jest.fn(),
  },
}));
jest.mock('../../../services/api/productionPlanApiClient', () => ({
  productionPlanApiClient: {
    getProductionPlans: (...args: unknown[]) => mockGetPlans(...args),
  },
}));
jest.mock('../../../components/ui/AppDialog', () => ({
  appAlert: (...args: unknown[]) => mockAppAlert(...args),
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn(), navigate: jest.fn() }),
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaView: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('expo-linear-gradient', () => ({
  LinearGradient: ({ children, ...props }: any) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));
jest.mock('@expo/vector-icons', () => ({
  MaterialCommunityIcons: ({ name }: any) => {
    const { Text } = require('react-native');
    return <Text>{name}</Text>;
  },
}));

import AIScheduleGenerateScreen from '../../../screens/dispatcher/ai/AIScheduleGenerateScreen';

const beef = {
  productTypeId: 'PT-BEEF', productName: '酱牛肉', unit: '袋', jointPlan: false,
  question: '「酱牛肉」一天能做多少 袋？', reason: 'MISSING', planNumbers: ['PLAN-B'],
};
const scheduled = {
  success: true,
  data: {
    id: 'SP-1', outcome: 'SCHEDULED', warnings: [],
    lineSchedules: [{
      id: 'S-1', planNumber: 'PLAN-B', productionLineName: '一号线', assignedWorkers: 0,
      plannedStartTime: '2026-10-29T00:00:00', plannedEndTime: '2026-10-31T23:59:00',
      dailyAllocations: {
        unit: '袋', capacityPerDay: 300, capacitySource: 'MANUAL', capacitySourceLabel: '人工填写',
        days: [
          { date: '2026-10-29', quantity: 300, shareOfDay: 1 },
          { date: '2026-10-30', quantity: 300, shareOfDay: 1 },
          { date: '2026-10-31', quantity: 100, shareOfDay: 0.333334 },
        ],
      },
    }],
  },
};

describe('dailyCapacityPrompt', () => {
  it('blocks until every product has a positive number and builds overrides in the plan unit', () => {
    expect(capacityBlockReason([beef], {})).toContain('酱牛肉');
    expect(capacityBlockReason([beef], { [capacityKey(beef)]: '0' })).toContain('酱牛肉');
    expect(capacityBlockReason([beef], { [capacityKey(beef)]: '300' })).toBe('');
    expect(buildCapacityOverrides([beef], { [capacityKey(beef)]: ' 300.456 ' }))
      .toEqual([{ productTypeId: 'PT-BEEF', quantityPerDay: 300.46, unit: '袋' }]);
    expect(() => buildCapacityOverrides([beef], {})).toThrow('酱牛肉');
  });

  it('describes the days, the date range and where the capacity came from', () => {
    const alloc = scheduled.data.lineSchedules[0].dailyAllocations;
    expect(describeDays(alloc)).toBe('10-29 300袋 · 10-30 300袋 · 10-31 100袋');
    expect(describeDateRange(alloc)).toBe('10-29 ~ 10-31');
    expect(describeCapacity(alloc)).toBe('日产能 300袋/天（人工填写）');
    expect(describeDays(null)).toBeNull();
  });
});

describe('AIScheduleGenerateScreen daily capacity flow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetPlans.mockResolvedValue({ success: true, data: { content: [
      { id: 'PP-B', planNumber: 'PLAN-B', productName: '酱牛肉', plannedQuantity: 700, priority: 5 },
    ] } });
  });

  it('asks on the spot for the missing daily capacity, resubmits with overrides, then shows the days', async () => {
    mockGenerate
      .mockResolvedValueOnce({ success: true, data: { id: null, outcome: 'CAPACITY_REQUIRED', lineSchedules: [], capacityRequired: [beef] } })
      .mockResolvedValueOnce(scheduled);
    const screen = render(<AIScheduleGenerateScreen />);
    await waitFor(() => expect(screen.getByText('全选')).toBeTruthy());
    fireEvent.press(screen.getByText('全选'));
    fireEvent.press(screen.getByText('生成排程 (1 个计划)'));

    await waitFor(() => expect(screen.getByTestId('capacity-required-modal')).toBeTruthy());
    expect(mockGenerate).toHaveBeenCalledTimes(1);
    expect(mockGenerate.mock.calls[0][0]).not.toHaveProperty('dailyCapacityOverrides');
    expect(screen.getByText('「酱牛肉」一天能做多少 袋？')).toBeTruthy();
    expect(screen.getByTestId('capacity-block-reason').props.children).toContain('酱牛肉');
    // 没填完按「保存并排程」不会提交
    fireEvent.press(screen.getByTestId('capacity-submit'));
    expect(mockGenerate).toHaveBeenCalledTimes(1);

    fireEvent.changeText(screen.getByTestId('capacity-input-PT-BEEF'), '300');
    fireEvent.press(screen.getByTestId('capacity-submit'));

    await waitFor(() => expect(mockGenerate).toHaveBeenCalledTimes(2));
    expect(mockGenerate.mock.calls[1][0]).toMatchObject({
      planIds: ['PP-B'],
      dailyCapacityOverrides: [{ productTypeId: 'PT-BEEF', quantityPerDay: 300, unit: '袋' }],
    });
    await waitFor(() => expect(screen.getByTestId('daily-days-S-1')).toBeTruthy());
    expect(screen.getByTestId('daily-days-S-1').props.children).toBe('10-29 300袋 · 10-30 300袋 · 10-31 100袋');
    expect(screen.getByTestId('daily-capacity-S-1').props.children).toBe('日产能 300袋/天（人工填写）');
    expect(screen.getByText('10-29 ~ 10-31')).toBeTruthy();
  });
});
