/**
 * 缺口 13 CCP —— 报工屏真实入口 (渲染 YieldStepReportScreen 本体, 只桩 API/store):
 *  - 本道挂了 CCP: 没填完时「标记完工」不可点, 并写明还差哪项; 填完可提交;
 *  - CCP 实测值随完工出成请求一起提交 (ccpReadings, 后端同一事务落库), 不再单独补交 (审查中 7);
 *    后端回报偏离时弹「已转待检」提示;
 *  - 阴性对照: 本道没挂 CCP → 不挡提交, 请求里不带 ccpReadings;
 *  - 审查中 9: 换到同一工序的另一道任务 (workProcessId 相同) 后, 按钮不会卡在「正在加载关键控制点」。
 * 测试骨架 (mock 清单 / 任务与出成率桩) 原样取自 YieldStepReportScreenImbalanceConfirm.test.tsx。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

// ── React Navigation: 与 YieldStepReportScreenAutoWaste.test.tsx 同一套最小 mock ──
// 路由参数可按用例改 (审查中 9 的用例要从「点开第 1 道」进入)
let mockRouteParams: Record<string, unknown> = { batchId: 501, batchNumber: 'B-20260817-501' };
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({
    params: mockRouteParams,
  }),
}));

jest.mock('expo-image-manipulator', () => ({
  manipulateAsync: jest.fn(() => Promise.resolve({ uri: 'file://mock-manipulated.jpg' })),
  SaveFormat: { JPEG: 'jpeg' },
}));

// 与 YieldStepReportScreenAutoWaste.test.tsx 同一份内联 react-native mock (物理上独立一份,
// 不经过任何 require('react-native' 相关路径, 避免 moduleNameMapper 递归 — 见那份文件的注释).
jest.mock('react-native', () => {
  const ReactLib = require('react');
  const createMockComponent = (name: string) => {
    const component = ({ children, ...props }: any) => ReactLib.createElement(name, props, children);
    component.displayName = name;
    return component;
  };
  return {
    View: createMockComponent('View'),
    Text: createMockComponent('Text'),
    TouchableOpacity: createMockComponent('TouchableOpacity'),
    TouchableHighlight: createMockComponent('TouchableHighlight'),
    TouchableWithoutFeedback: createMockComponent('TouchableWithoutFeedback'),
    Pressable: createMockComponent('Pressable'),
    ScrollView: createMockComponent('ScrollView'),
    FlatList: createMockComponent('FlatList'),
    SectionList: createMockComponent('SectionList'),
    TextInput: createMockComponent('TextInput'),
    Image: createMockComponent('Image'),
    ImageBackground: createMockComponent('ImageBackground'),
    Modal: createMockComponent('Modal'),
    ActivityIndicator: createMockComponent('ActivityIndicator'),
    Switch: createMockComponent('Switch'),
    KeyboardAvoidingView: createMockComponent('KeyboardAvoidingView'),
    SafeAreaView: createMockComponent('SafeAreaView'),
    StatusBar: createMockComponent('StatusBar'),
    RefreshControl: createMockComponent('RefreshControl'),
    StyleSheet: {
      create: (styles: any) => styles,
      flatten: (style: any) => (Array.isArray(style) ? Object.assign({}, ...style) : style || {}),
      hairlineWidth: 0.5,
      absoluteFill: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
      absoluteFillObject: { position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 },
    },
    Platform: { OS: 'ios', select: (obj: any) => obj.ios || obj.default, Version: 14 },
    Dimensions: {
      get: () => ({ width: 375, height: 812, scale: 2, fontScale: 1 }),
      addEventListener: jest.fn(),
      removeEventListener: jest.fn(),
    },
    Alert: { alert: jest.fn() },
    Animated: {
      View: createMockComponent('Animated.View'),
      Text: createMockComponent('Animated.Text'),
      Value: jest.fn().mockImplementation(() => ({ setValue: jest.fn(), interpolate: jest.fn(() => ({ __getValue: () => 0 })) })),
      timing: jest.fn(() => ({ start: jest.fn((cb: any) => cb && cb({ finished: true })) })),
    },
    Keyboard: { dismiss: jest.fn(), addListener: jest.fn(() => ({ remove: jest.fn() })), removeListener: jest.fn() },
    NativeModules: {},
    NativeEventEmitter: jest.fn().mockImplementation(() => ({ addListener: jest.fn(), removeAllListeners: jest.fn() })),
    useColorScheme: jest.fn(() => 'light'),
    useWindowDimensions: jest.fn(() => ({ width: 375, height: 812, scale: 2, fontScale: 1 })),
    I18nManager: { isRTL: false },
    InteractionManager: { runAfterInteractions: jest.fn((cb: any) => { if (typeof cb === 'function') cb(); return { then: jest.fn(), cancel: jest.fn() }; }) },
    PanResponder: {
      create: () => ({ panHandlers: {} }),
    },
  };
});

jest.mock('../../../theme', () => ({
  theme: {
    colors: {
      primary: '#1890ff',
      onPrimary: '#ffffff',
      secondaryContainer: '#E6F7FF',
      onSecondaryContainer: '#0050B3',
      error: '#ef4444',
      onError: '#ffffff',
      background: '#f5f5f5',
      surface: '#ffffff',
      surfaceVariant: '#f0f0f0',
      text: '#1F2937',
      textSecondary: '#6B7280',
      textTertiary: '#9CA3AF',
      outlineVariant: '#e5e7eb',
      outline: '#DCDFE6',
      onSurface: '#1F2937',
      onSurfaceVariant: '#6B7280',
      elevation: { level1: '#ffffff' },
    },
    custom: {
      borderRadius: { s: 4, m: 8, l: 12 },
      spacing: { xs: 4, s: 8, m: 12, l: 16, xl: 24 },
      shadows: {
        small: {},
        medium: {},
        large: {},
      },
    },
  },
}));

jest.mock('../../../components/ui/ScreenWrapper', () => {
  const { View } = require('react-native');
  return { ScreenWrapper: ({ children }: any) => <View>{children}</View> };
});
jest.mock('../../../components/ui/NeoCard', () => {
  const { View } = require('react-native');
  return { NeoCard: ({ children, style, testID }: any) => <View style={style} testID={testID}>{children}</View> };
});
jest.mock('../../../components/ui/NeoButton', () => {
  const { TouchableOpacity, Text } = require('react-native');
  return {
    NeoButton: ({ children, onPress, disabled, testID }: any) => (
      <TouchableOpacity onPress={onPress} disabled={disabled} testID={testID}>
        <Text>{children}</Text>
      </TouchableOpacity>
    ),
  };
});
jest.mock('react-native-paper', () => {
  const { TouchableOpacity } = require('react-native');
  return { TouchableRipple: ({ children, onPress, ...props }: any) => <TouchableOpacity onPress={onPress} {...props}>{children}</TouchableOpacity> };
});

// 缺口 13: 可控的 CCP 桩
const mockListStepCheckpoints = jest.fn();
const mockRecordCcp = jest.fn();
jest.mock('../../../services/api/ccpApi', () => ({
  ccpApi: {
    listStepCheckpoints: (...args: unknown[]) => mockListStepCheckpoints(...args),
    record: (...args: unknown[]) => mockRecordCcp(...args),
  },
}));

jest.mock('../../../services/api/yieldReportApi', () => {
  const actual = jest.requireActual('../../../services/api/yieldReportApi');
  return {
    ...actual,
    yieldReportApi: {
      listWorkProcessTasks: jest.fn(),
      getYield: jest.fn(),
      getYieldLimits: jest.fn(),
      getOutputOptions: jest.fn(),
      submitReport: jest.fn(),
      recordMaterialInput: jest.fn(),
      settleDay: jest.fn(),
      listReports: jest.fn(),
      listAssignedWorkProcessTasks: jest.fn(),
      listTaskMaterialOptions: jest.fn(),
      listWip: jest.fn(),
      postBatchReversal: jest.fn(),
      listAvailableWip: jest.fn(),
      uploadEvidence: jest.fn(),
    },
  };
});

jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatchById: jest.fn(),
    getBatches: jest.fn(),
  },
}));

jest.mock('../../../utils/errorHandler', () => ({
  handleError: jest.fn(),
}));

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return {
    SafeAreaView: ({ children, ...props }: any) => <View {...props}>{children}</View>,
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
  };
});

import YieldStepReportScreen from '../../../screens/processing/YieldStepReportScreen';
import { yieldReportApi, WorkProcessTask, BatchYieldDTO, YieldLimitsDTO } from '../../../services/api/yieldReportApi';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

const mockedYieldApi = yieldReportApi as jest.Mocked<typeof yieldReportApi>;
const mockedProcessingApi = processingApiClient as jest.Mocked<typeof processingApiClient>;

const mockSupervisor: FactoryUser = {
  id: 30,
  username: 'workshop_sup2',
  email: 'ws2@cretas.com',
  fullName: '李主管',
  isActive: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory',
  factoryId: 'F006',
  factoryUser: {
    role: 'workshop_supervisor',
    factoryId: 'F006',
    permissions: [],
  },
};

/** 建一条已进入 IN_PRODUCTION 阶段的单任务批次 (reportedInput/inputUnit/outputUnit 可参数化). */
function buildTask(overrides: Partial<WorkProcessTask> = {}): WorkProcessTask {
  return {
    id: 9001,
    factoryId: 'F006',
    productionBatchId: 501,
    productWorkProcessId: 1,
    workProcessId: 'WP-PACK',
    productTypeId: 'PT-1',
    processOrder: 1,
    status: 'IN_PROGRESS',
    plannedQuantity: 99,
    plannedUnit: 'kg',
    processName: '定量包装',
    outputUnit: null,
    ...overrides,
  };
}

function buildYield(
  task: WorkProcessTask,
  stepOverrides: Partial<BatchYieldDTO['steps'][number]> = {},
): BatchYieldDTO {
  return {
    batchId: 501,
    batchNumber: 'B-20260817-501',
    firstStepInput: 99,
    lastStepOutput: null,
    firstStepInputUnit: 'kg',
    lastStepOutputUnit: null,
    cumulativeYieldRate: null,
    complete: false,
    totalWorkMinutes: null,
    totalWorkers: null,
    totalLaborCost: null,
    totalMaterialCost: null,
    totalCost: null,
    steps: [
      {
        workProcessTaskId: task.id,
        processOrder: task.processOrder,
        processName: task.processName ?? null,
        totalInput: 99,
        totalOutput: null,
        inputUnit: 'kg',
        outputUnit: task.outputUnit ?? null,
        yieldRate: null,
        unitComparable: null,
        carryover: null,
        yieldAlert: null,
        totalWorkMinutes: null,
        totalWorkers: null,
        laborCost: null,
        materialCost: null,
        stepCost: null,
        photos: null,
        laborSegments: null,
        processedQuantity: null,
        processedUnit: null,
        stageOutputQuantity: null,
        stageOutputUnit: null,
        segmentWasteQuantity: null,
        segmentWasteUnit: null,
        byproducts: null,
        wasteQuantity: null,
        sampleRetainQuantity: null,
        phase: 'IN_PRODUCTION',
        inputPhotos: null,
        outputPhotos: null,
        inputPhotoAnnotations: null,
        outputPhotoAnnotations: null,
        ...stepOverrides,
      },
    ],
  };
}

const emptyLimits: YieldLimitsDTO = {
  targetQuantity: null,
  standardYieldMax: null,
  unit: 'kg',
  alreadyReported: null,
  toleranceRate: null,
  maxAllowed: null,
  remaining: null,
  wipAvailable: null,
  wipAvailableUnit: null,
  sourceWipNo: null,
  message: '未配置标准出成上限',
};


const CPS = [
  { checkpointId: 1, checkpointCode: 'CCP-T', name: '中心温度', hazardType: 'BIOLOGICAL', criticalLimitMin: 75,
    criticalLimitMax: 100, unit: '℃', monitoringProcedure: null, correctiveAction: '继续加热 5 分钟' },
];

async function renderToOutputStage(task: WorkProcessTask) {
  const yieldData = buildYield(task, { totalInput: 99, inputUnit: 'kg' });
  mockedYieldApi.listWorkProcessTasks.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [task] });
  mockedProcessingApi.getBatchById.mockResolvedValue({
    success: true, code: 200, message: 'ok',
    data: { id: 501, productType: '卤猪蹄', productTypeId: 'PT-1', batchNumber: 'B-20260817-501', targetQuantity: 99, status: 'IN_PROGRESS' } as any,
  });
  mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: yieldData });
  mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { ...emptyLimits, workProcessTaskId: task.id, inputUnit: 'kg', outputUnit: 'kg' } });
  mockedYieldApi.getOutputOptions.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { items: [] } });
  mockedYieldApi.submitReport.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { balanceWarning: undefined } as any });

  render(<YieldStepReportScreen />);
  await waitFor(() => expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy());
  fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
  await waitFor(() => expect(screen.getByTestId('yield-output-qty')).toBeTruthy());
  fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
  fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), '90');
  fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));
  await waitFor(() => expect(screen.getByTestId('yield-submit-output-btn')).toBeTruthy());
}

beforeEach(() => {
  jest.clearAllMocks();
  mockRouteParams = { batchId: 501, batchNumber: 'B-20260817-501' };
  useAuthStore.setState({ user: mockSupervisor, isAuthenticated: true, tokens: null, isLoading: false });
});

const CCP_DEVIATION_RESULT = {
  reportId: 1, yieldRate: null,
  ccp: { deviationCount: 1, heldOutputs: 1, recordCount: 1,
    message: '有 1 项 CCP 超出限值，已记录偏离；批次 B-20260817-501 的产出已转为待检' },
};

describe('YieldStepReportScreen — 关键控制点 (CCP)', () => {
  it('挂了 CCP: 没填完不能完工; 实测值随完工出成请求一起提交, 后端回报偏离时弹待检提示', async () => {
    mockListStepCheckpoints.mockResolvedValue({ success: true, code: 200, message: 'ok', data: CPS });
    const task = buildTask({ workProcessId: 'WP-COOK', outputUnit: null });
    await renderToOutputStage(task);
    mockedYieldApi.submitReport.mockResolvedValue({ success: true, code: 200, message: 'ok', data: CCP_DEVIATION_RESULT as any });

    await waitFor(() => expect(screen.getByTestId('ccp-block-reason')).toBeTruthy());
    expect(screen.getByText('请先填写：中心温度')).toBeTruthy();
    expect(screen.getByTestId('yield-submit-output-btn').props.disabled).toBe(true);
    expect(screen.getByText('合格范围：75 ~ 100 ℃')).toBeTruthy();

    fireEvent.changeText(screen.getByTestId('ccp-input-CCP-T'), '70');
    expect(screen.getByTestId('ccp-out-CCP-T')).toBeTruthy();
    await waitFor(() => expect(screen.getByTestId('yield-submit-output-btn').props.disabled).toBe(false));

    fireEvent.press(screen.getByTestId('yield-submit-output-btn'));
    await waitFor(() => expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy());
    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));

    await waitFor(() => expect(mockedYieldApi.submitReport).toHaveBeenCalledTimes(1));
    const req = mockedYieldApi.submitReport.mock.calls[0]?.[1];
    expect(req).toEqual(expect.objectContaining({
      reportKind: 'OUTPUT',
      ccpReadings: [{ checkpointId: 1, measuredValue: 70 }],
    }));
    // 不再有「报工之后单独补交 CCP」那一跳 (那一跳失败就是产出入账了、CCP 没交上的半态)
    expect(mockRecordCcp).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText('关键控制点超出范围')).toBeTruthy());
  });

  it('阴性对照: 本道没挂 CCP → 不挡完工, 不发 CCP 请求', async () => {
    mockListStepCheckpoints.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [] });
    const task = buildTask({ workProcessId: 'WP-PACK', outputUnit: null });
    await renderToOutputStage(task);
    await waitFor(() => expect(screen.getByTestId('yield-submit-output-btn').props.disabled).toBe(false));
    expect(screen.queryByTestId('ccp-section')).toBeNull();
    fireEvent.press(screen.getByTestId('yield-submit-output-btn'));
    await waitFor(() => expect(screen.getByTestId('app-dialog-btn-1')).toBeTruthy());
    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
    await waitFor(() => expect(mockedYieldApi.submitReport).toHaveBeenCalledTimes(1));
    expect(mockedYieldApi.submitReport.mock.calls[0]?.[1]).not.toHaveProperty('ccpReadings');
    expect(mockRecordCcp).not.toHaveBeenCalled();
  });

  it('审查中 9: 停在完工出成时被指派到同工序的另一道任务 → 按钮可用, 不卡在「正在加载关键控制点」', async () => {
    mockListStepCheckpoints.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [] });
    const t1 = buildTask({ id: 9001, processOrder: 1, workProcessId: 'WP-PACK', outputUnit: null });
    const t2 = buildTask({ id: 9002, processOrder: 2, workProcessId: 'WP-PACK', outputUnit: null });
    mockRouteParams = { batchId: 501, batchNumber: 'B-20260817-501', assignedWorkProcessTaskId: 9001 };
    mockedYieldApi.listWorkProcessTasks.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [t1] });
    mockedProcessingApi.getBatchById.mockResolvedValue({
      success: true, code: 200, message: 'ok',
      data: { id: 501, productType: '卤猪蹄', productTypeId: 'PT-1', batchNumber: 'B-20260817-501', targetQuantity: 99, status: 'IN_PROGRESS' } as any,
    });
    mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: buildYield(t1, { totalInput: 99, inputUnit: 'kg' }) });
    mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { ...emptyLimits, inputUnit: 'kg', outputUnit: 'kg' } });
    mockedYieldApi.getOutputOptions.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { items: [] } });

    const view = render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy());
    fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
    await waitFor(() => expect(screen.getByTestId('yield-submit-output-btn')).toBeTruthy());
    const loadsBefore = mockListStepCheckpoints.mock.calls.length;

    // 同一屏还开着时被改派到同工序的第 2 道 (路由参数变 → 重新加载任务; 第 1 道已不在本人可见列表里)
    mockRouteParams = { batchId: 501, batchNumber: 'B-20260817-501', assignedWorkProcessTaskId: 9002 };
    mockedYieldApi.listWorkProcessTasks.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [t2] });
    const t2Yield = { success: true, code: 200, message: 'ok', data: buildYield(t2, { totalInput: 90, inputUnit: 'kg' }) };
    mockedYieldApi.getYield.mockResolvedValue(t2Yield);
    // 第 2 道的出成率数据晚到 (慢网络; 固定制造, 不靠运气): 屏幕的重载是任务 / 批次 / 出成率三路一起等齐了
    // 才换道 (loadAll 的 Promise.all), 所以换道发生在 getYield 回来之后。测试若只等到「任务列表拉了第 2 次」
    // 就往下点, 点的是第 1 道的完工出成; 数据一回来阶段切到第 2 道的「过程报工」, 后面的元素就找不到了
    // —— CI 上偶发红的成因。这里固定让它晚到 50ms, 未等重载完成的写法必红。
    let t2YieldArrived = false;
    mockedYieldApi.getYield.mockImplementationOnce(() => new Promise((resolve) => {
      setTimeout(() => {
        t2YieldArrived = true;
        resolve(t2Yield);
      }, 50);
    }));
    view.rerender(<YieldStepReportScreen />);
    // 必然发生的读数: 确实换到了第 2 道 (任务列表重新拉过)
    await waitFor(() => expect(mockedYieldApi.listWorkProcessTasks).toHaveBeenCalledTimes(2));
    // 重载完成的读数: 第 2 道成了当前道之后, 屏幕才按第 2 道的任务 id 去查单位/上限 (只在换道生效后发生)。
    // 等到它才往下走 —— 此时出成率已到、阶段已按第 2 道定下来, 不会再被迟到的数据切走。
    await waitFor(() => expect(mockedYieldApi.getYieldLimits).toHaveBeenCalledWith(501, 9002));
    expect(t2YieldArrived).toBe(true);

    // 现行界面换道会把阶段切回「过程报工」(CCP 栏卸载); 不依赖这一点 —— 仍停在完工出成 (CCP 栏没卸载,
    // 正是审查中 9 描述的情形) 就直接往下走。
    const goOutput = screen.queryByTestId('yield-go-output-step-btn');
    if (goOutput) fireEvent.press(goOutput);
    await waitFor(() => expect(screen.getByTestId('yield-output-qty')).toBeTruthy());
    fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
    fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), '80');
    fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));
    await waitFor(() => expect(screen.getByTestId('yield-submit-output-btn').props.disabled).toBe(false));
    // 第 2 道的 CCP 重新查过 (同工序也要查: 父组件换道时已把 CCP 状态重置)
    expect(mockListStepCheckpoints.mock.calls.length).toBeGreaterThan(loadsBefore);
  });
});
