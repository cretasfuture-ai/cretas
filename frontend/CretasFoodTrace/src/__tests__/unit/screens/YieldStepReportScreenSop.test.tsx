/**
 * 工序 SOP 入口 —— 报工屏真实入口 (渲染 YieldStepReportScreen 本体, 只桩 API/store):
 *  - 本道工序挂了 SOP: 头卡出现「查看 SOP（N）」, 展开列出文件, 点一份拿**新签的**下载地址并交给系统打开;
 *  - 阴性对照: 没有 SOP → 没有入口, 也没有错误条;
 *  - 哨兵任务 (__MATERIAL_INPUT__ / __FINAL_OUTPUT__) 不请求 SOP;
 *  - 加载失败写出原因并可重试, 不装作「没有 SOP」;
 *  - 打开失败用 appAlert 说清楚 (不是静默)。
 * 测试骨架 (mock 清单 / 任务与出成率桩) 取自 YieldStepReportScreenCcp.test.tsx。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

// ── React Navigation: 与 YieldStepReportScreenAutoWaste.test.tsx 同一套最小 mock ──
// 路由参数可按用例改 (审查中 9 的用例要从「点开第 1 道」进入)
const mockOpenURL = jest.fn();
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
    Linking: { openURL: (...args: unknown[]) => mockOpenURL(...args) },
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
const mockListStepCheckpoints = jest.fn((..._a: unknown[]) => Promise.resolve({ success: true, code: 200, message: 'ok', data: [] }));
const mockRecordCcp = jest.fn();
jest.mock('../../../services/api/ccpApi', () => ({
  ccpApi: {
    listStepCheckpoints: (...args: unknown[]) => mockListStepCheckpoints(...args),
    record: (...args: unknown[]) => mockRecordCcp(...args),
  },
}));

const mockSopList = jest.fn();
const mockSopSignedUrl = jest.fn();
jest.mock('../../../services/api/attachmentApi', () => ({
  attachmentApi: {
    list: (...args: unknown[]) => mockSopList(...args),
    getSignedDownloadUrl: (...args: unknown[]) => mockSopSignedUrl(...args),
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


function sopFile(id: string, fileName: string, fileType = 'application/pdf') {
  return {
    id, factoryId: 'F006', entityType: 'WORK_PROCESS', entityId: 'WP-PACK', fileName, fileUrl: `https://oss.invalid/${fileName}`,
    fileSize: 204800, fileType, fileCategory: 'DOCUMENT', fileStorage: 'OSS', uploadedBy: 1,
    uploadedAt: '2026-11-04T09:00:00', uploadSource: 'WEB',
  };
}

async function renderScreen(task: WorkProcessTask) {
  const yieldData = buildYield(task, { totalInput: 99, inputUnit: 'kg' });
  mockedYieldApi.listWorkProcessTasks.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [task] });
  mockedProcessingApi.getBatchById.mockResolvedValue({
    success: true, code: 200, message: 'ok',
    data: { id: 501, productType: '卤猪蹄', productTypeId: 'PT-1', batchNumber: 'B-20260817-501', targetQuantity: 99, status: 'IN_PROGRESS' } as any,
  });
  mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: yieldData });
  mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { ...emptyLimits, workProcessTaskId: task.id, inputUnit: 'kg', outputUnit: 'kg' } });
  mockedYieldApi.getOutputOptions.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { items: [] } });
  render(<YieldStepReportScreen />);
  // 头卡里的产品名出现 = 屏幕已渲染完 (与 SOP 请求无关的稳定锚点)
  await waitFor(() => expect(screen.getByText('卤猪蹄')).toBeTruthy());
}

beforeEach(() => {
  jest.clearAllMocks();
  mockSopList.mockReset();
  mockSopSignedUrl.mockReset();
  mockOpenURL.mockReset();
  mockRouteParams = { batchId: 501, batchNumber: 'B-20260817-501' };
  useAuthStore.setState({ user: mockSupervisor, isAuthenticated: true, tokens: null, isLoading: false });
});

describe('YieldStepReportScreen — 工序 SOP 入口', () => {
  it('本道挂了 SOP: 头卡出现「查看 SOP（2）」, 展开后点一份 → 取新签地址并交给系统打开', async () => {
    mockSopList.mockResolvedValue([sopFile('A1', '定量包装标准.pdf'), sopFile('A2', '成品外观.png', 'image/png')]);
    mockSopSignedUrl.mockResolvedValue('https://oss.invalid/signed?sig=1');
    mockOpenURL.mockResolvedValue(true);
    await renderScreen(buildTask({ workProcessId: 'WP-PACK' }));

    await waitFor(() => expect(screen.getByText('查看 SOP（2）')).toBeTruthy());
    expect(mockSopList).toHaveBeenCalledWith('WORK_PROCESS', 'WP-PACK');
    expect(screen.queryByTestId('process-sop-list')).toBeNull();

    fireEvent.press(screen.getByTestId('process-sop-toggle'));
    expect(screen.getByText('定量包装 的 SOP')).toBeTruthy();
    expect(screen.getByText('定量包装标准.pdf')).toBeTruthy();

    fireEvent.press(screen.getByTestId('process-sop-file-A1'));
    await waitFor(() => expect(mockOpenURL).toHaveBeenCalledTimes(1));
    expect(mockSopSignedUrl).toHaveBeenCalledWith('A1');
    expect(mockOpenURL).toHaveBeenCalledWith('https://oss.invalid/signed?sig=1');
    // 绝不能把私有存储的原始地址交给系统
    expect(mockOpenURL).not.toHaveBeenCalledWith('https://oss.invalid/定量包装标准.pdf');
  });

  it('阴性对照: 没有 SOP → 没有入口也没有错误条 (请求确实发了, 才算「没有」)', async () => {
    mockSopList.mockResolvedValue([]);
    await renderScreen(buildTask({ workProcessId: 'WP-PACK' }));
    await waitFor(() => expect(mockSopList).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByTestId('process-sop-loading')).toBeNull());
    expect(screen.queryByTestId('process-sop-entry')).toBeNull();
    expect(screen.queryByTestId('process-sop-error')).toBeNull();
    expect(screen.queryByText(/查看 SOP/)).toBeNull();
  });

  it('哨兵批次的领料屏 (__MATERIAL_INPUT__) 不请求 SOP、没有入口 (有人把入口加进哨兵屏就会红; 阳性对照在第一条)', async () => {
    mockSopList.mockResolvedValue([sopFile('A1', 'x.pdf')]);
    await renderScreen(buildTask({ workProcessId: '__MATERIAL_INPUT__' }));
    expect(mockSopList).not.toHaveBeenCalled();
    expect(screen.queryByTestId('process-sop-entry')).toBeNull();
    expect(screen.queryByTestId('process-sop-error')).toBeNull();
  });

  it('加载失败: 写出原因 + 重试, 不当成「没有 SOP」; 重试成功后出现入口', async () => {
    mockSopList.mockRejectedValueOnce({ response: { data: { message: '您的角色 [操作员] 无权查看 [WORK_PROCESS] 附件' } } });
    await renderScreen(buildTask({ workProcessId: 'WP-PACK' }));
    await waitFor(() => expect(screen.getByTestId('process-sop-error')).toBeTruthy());
    expect(screen.getByText(/SOP 暂时加载失败：您的角色 \[操作员\] 无权查看/)).toBeTruthy();

    mockSopList.mockResolvedValueOnce([sopFile('A1', 'x.pdf')]);
    fireEvent.press(screen.getByTestId('process-sop-retry'));
    await waitFor(() => expect(screen.getByText('查看 SOP（1）')).toBeTruthy());
    expect(screen.queryByTestId('process-sop-error')).toBeNull();
  });

  it('打开失败: appAlert 讲清楚原因和下一步, 列表还在、可以再点', async () => {
    mockSopList.mockResolvedValue([sopFile('A1', 'x.pdf')]);
    mockSopSignedUrl.mockRejectedValueOnce(new Error('下载 URL 生成失败 — OSS 不可用'));
    await renderScreen(buildTask({ workProcessId: 'WP-PACK' }));
    await waitFor(() => expect(screen.getByText('查看 SOP（1）')).toBeTruthy());
    fireEvent.press(screen.getByTestId('process-sop-toggle'));
    fireEvent.press(screen.getByTestId('process-sop-file-A1'));

    await waitFor(() => expect(screen.getByText('打不开这份 SOP')).toBeTruthy());
    expect(screen.getByText(/下载 URL 生成失败 — OSS 不可用/)).toBeTruthy();
    expect(screen.getByText(/请联系生产主管/)).toBeTruthy();
    expect(mockOpenURL).not.toHaveBeenCalled();
    expect(screen.getByTestId('process-sop-file-A1')).toBeTruthy();
  });
});
