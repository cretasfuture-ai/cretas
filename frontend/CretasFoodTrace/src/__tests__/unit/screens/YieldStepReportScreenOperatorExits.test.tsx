/**
 * 工人报工屏 —— 出口 / 必填标记 / 时段默认值 / 成功反馈 (P5 + P7, 2026-08-26 走查)。
 *
 * <h3>这四条守的分别是</h3>
 * P5a 红星只长在「空着就提交不了」的字段上 —— 走查前屏上唯一的红星在「领料批次」,
 *     而真正会把提交挡回来的投入量/产出量一颗星都没有, 工人是提交之后才被弹窗告知的。
 * P5b 时段默认值必须来自真实时钟 —— 走查前硬编码 07:00~15:00, 与打开屏幕的时间无关,
 *     工人不动它直接提交, 库里就多一条「干了 8 小时」的假班次 (工时和人工成本按它算)。
 * P7a 投入阶段 / 生产阶段原本零个 goBack 元素 —— 进去了只能杀 App。
 * P7b 最常见那条完工路径 (markComplete=true 且本道没有半成品产出) 提交成功后一个字都不弹,
 *     与 handleSubmitSegment 2026-08-18 踩的是同一个形态。
 *
 * <h3>口径</h3>
 * 断言跑在产品真实入口上 (渲染 YieldStepReportScreen 本体, 只桩 API / store),
 * 逐个 testID 取元素, 不做整棵树的 toContain。
 *
 * ⚠️ 下面那一大段 mock 与 YieldStepReportScreenAutoWaste / ...ImbalanceConfirm 同构 ——
 *    jest.mock 是按模块 hoist 的, 抽不成共享文件, 本仓已有的两份也是这么复制的。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';

// ── React Navigation: setup.ts 的全局 mock 对 v7 ESM build 会 requireActual 崩掉,
//    这里用不 spread actual 的最小 mock 覆盖它 (同 StaffAIAnalysisScreen.test.tsx 的做法). ──
// goBack / replace 用稳定的 mock —— 原 harness 每次调用都新建一个 jest.fn(), 断言不到。
const mockGoBack = jest.fn();
const mockReplace = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({
    navigate: jest.fn(),
    goBack: mockGoBack,
    replace: mockReplace,
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({
    params: { batchId: 501, batchNumber: 'B-20260826-501' },
  }),
}));

jest.mock('expo-image-manipulator', () => ({
  manipulateAsync: jest.fn(() => Promise.resolve({ uri: 'file://mock-manipulated.jpg' })),
  SaveFormat: { JPEG: 'jpeg' },
}));

// __mocks__/react-native.js (共享 jest 手写 mock, moduleNameMapper 全局指到它) 没有
// PanResponder —— YieldStepReportScreen 里的 TimeRangeSlider (时段报工块) 用它做拖拽滑轨。
// 只在本测试文件局部补上, 不改共享 mock 文件本身 (那是全仓所有 RN 测试都吃的文件,
// 改它风险/影响面大得多)。
// ⚠️ 两种更直接的写法都踩了坑, 记录一下别再走回头路:
//   1. jest.requireActual('react-native') —— moduleNameMapper 对 'react-native' 是宽泛正则
//      匹配, requireActual 绕不开它, 会一头撞进 react-native preset 自己的 jest/mockComponent.js
//      内部逻辑并崩溃 (「Cannot read properties of undefined (reading 'displayName')」)。
//   2. require('../../../../__mocks__/react-native.js') —— 这个路径本身仍然含 "react-native"
//      子串, 同一条正则又把它重新导向这个 jest.mock 工厂, 递归到 Maximum call stack exceeded。
// ⇒ 干脆不经过任何 require('react-native' 相关路径), 直接在本文件内联最小实现
//    (与共享 mock 文件同构, 但物理上是两份独立代码, 不会互相递归)。
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
    // TimeRangeSlider 用它算 SLIDER_WIDTH (模块加载期常量, 只要不是 0/NaN 就行).
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
    // 缺陷 A 真正要补的那个键 —— TimeRangeSlider 的拖拽手柄靠它, 渲染阶段只需要
    // create() 不抛错并给出 panHandlers 让 {...startPan.panHandlers} 能 spread。
    PanResponder: {
      create: () => ({ panHandlers: {} }),
    },
  };
});

// ── theme/index.ts 在这套 jest 环境下从 react-native-paper 拿 MD3LightTheme 会拿到 undefined
//    (与本缺陷无关的既有 infra 问题, ProcessTaskListScreen.test.tsx 同样绕过) —— 整个 theme 换成静态对象. ──
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

// NeoCard/NeoButton/ScreenWrapper 都从 react-native-paper (间接经 theme/index.ts 的
// MD3LightTheme) 取值, 而 react-native-paper 在这套自定义 react-native mock 下没法正常工作
// (它的 Button/Card 等内部实现依赖一堆我们没有理由在这里重建的 RN 原生行为)。这三个都是纯展示
// 容器组件, 不影响本缺陷要验证的文本/testID —— 直接换成最小 View/Text/TouchableOpacity 包装。
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
// YieldStepReportScreen 直接 `import { TouchableRipple } from 'react-native-paper'` (SP3 二次加工
// 首道块用, 本测试场景走不到那个分支, 但模块顶层 import 仍会执行, 必须能解析成功).
jest.mock('react-native-paper', () => {
  const { TouchableOpacity } = require('react-native');
  return { TouchableRipple: ({ children, onPress, ...props }: any) => <TouchableOpacity onPress={onPress} {...props}>{children}</TouchableOpacity> };
});

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

// ScreenWrapper 用它做顶层容器; jest 环境下这个包解不出真实 SafeAreaView (同 StaffAIAnalysisScreen.test.tsx 做法).
jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return {
    SafeAreaView: ({ children, ...props }: any) => <View {...props}>{children}</View>,
    useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
  };
});

import YieldStepReportScreen, { currentClockHHMM } from '../../../screens/processing/YieldStepReportScreen';
import { yieldReportApi, WorkProcessTask, BatchYieldDTO, YieldLimitsDTO } from '../../../services/api/yieldReportApi';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

const mockedYieldApi = yieldReportApi as jest.Mocked<typeof yieldReportApi>;
const mockedProcessingApi = processingApiClient as jest.Mocked<typeof processingApiClient>;

const mockOperator: FactoryUser = {
  id: 77,
  username: 'operator_zhang',
  email: 'zhang@cretas.com',
  fullName: '张操作员',
  isActive: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory',
  factoryId: 'F006',
  factoryUser: { role: 'operator', factoryId: 'F006', permissions: [] },
};

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
    batchNumber: 'B-20260826-501',
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

/** 桩齐首屏需要的 API; phase 决定停在哪个阶段。 */
function stubApis(phase: 'AWAITING_INPUT' | 'IN_PRODUCTION'): void {
  const task = buildTask();
  const yieldData = buildYield(
    task,
    phase === 'AWAITING_INPUT' ? { phase: 'AWAITING_INPUT', totalInput: null } : { phase: 'IN_PRODUCTION' },
  );

  mockedYieldApi.listWorkProcessTasks.mockResolvedValue({ success: true, code: 200, message: 'ok', data: [task] });
  mockedProcessingApi.getBatchById.mockResolvedValue({
    success: true,
    code: 200,
    message: 'ok',
    data: {
      id: 501,
      productType: '卤猪蹄',
      productTypeId: 'PT-1',
      batchNumber: 'B-20260826-501',
      targetQuantity: 99,
      status: 'IN_PROGRESS',
    } as never,
  });
  mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: yieldData });
  mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { ...emptyLimits, workProcessTaskId: task.id, inputUnit: 'kg', outputUnit: task.outputUnit ?? 'kg' } });
  mockedYieldApi.getOutputOptions.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { items: [] } });
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({ user: mockOperator, isAuthenticated: true, tokens: null, isLoading: false });
});

describe('投入定额缺失信息与计划产出语义', () => {
  function stubPlan(task: WorkProcessTask): void {
    stubApis('AWAITING_INPUT');
    mockedYieldApi.listWorkProcessTasks.mockResolvedValue({
      success: true, code: 200, message: 'ok', data: [task],
    });
    mockedProcessingApi.getBatchById.mockResolvedValue({
      success: true, code: 200, message: 'ok',
      data: { id: 501, productType: '卤猪蹄', productTypeId: 'PT-1',
        batchNumber: 'B-20260826-501', targetQuantity: 1, unit: '盒', status: 'IN_PROGRESS' } as never,
    });
  }

  it('任务未设投入定额时明确提示，不从一盒成品目标推成kg计划或上限', async () => {
    stubPlan(buildTask({ plannedQuantity: null, plannedUnit: 'kg' }));
    render(<YieldStepReportScreen />);

    await waitFor(() => expect(screen.getByTestId('yield-input-plan-missing')).toHaveTextContent(
      '投入定额未设置。请按实际领料量填写，并联系生产主管确认。',
    ));
    expect(screen.queryByText(/计划数量\s+1\s+kg/)).toBeNull();
    expect(screen.queryByText(/计划产出\s+1\s+kg/)).toBeNull();
    expect(screen.queryByText(/可投上限约\s+1\.3/)).toBeNull();
  });

  it('即使投入产出同为kg，任务计划量仍明确为产出而非投入定额', async () => {
    stubPlan(buildTask({ plannedQuantity: 99, plannedUnit: 'kg' }));
    render(<YieldStepReportScreen />);

    await waitFor(() => expect(screen.getByTestId('yield-output-plan')).toHaveTextContent('计划产出 99 kg'));
    expect(screen.getByTestId('yield-input-plan-missing')).toBeTruthy();
    expect(screen.queryByText(/可投上限约\s+128\.7/)).toBeNull();
  });

  it('已有明确投入计划时保留原计划分支，不误报未设置', async () => {
    stubPlan(buildTask({ plannedInputQuantity: 10 }));
    render(<YieldStepReportScreen />);

    await waitFor(() => expect(screen.getByTestId('yield-input-qty')).toBeTruthy());
    expect(screen.queryByTestId('yield-input-plan-missing')).toBeNull();
    fireEvent.press(screen.getByTestId('yield-input-qty-tap-to-edit'));
    expect(screen.getByText(/计划 10 kg/)).toBeTruthy();
  });
});

describe('P5b 时段默认值必须来自真实时钟, 不许是硬编码班次', () => {
  it('currentClockHHMM 跟着传入的时刻走 (对齐到滑轨自己的 15 分步长)', () => {
    expect(currentClockHHMM(new Date(2026, 7, 26, 10, 7))).toBe('10:00');
    expect(currentClockHHMM(new Date(2026, 7, 26, 10, 8))).toBe('10:15');
    expect(currentClockHHMM(new Date(2026, 7, 26, 13, 20))).toBe('13:15');
    // 阴性对照: 同一个函数在不同时刻必须给出不同的值 —— 硬编码常量过不了这一条
    expect(currentClockHHMM(new Date(2026, 7, 26, 6, 0))).not.toBe(
      currentClockHHMM(new Date(2026, 7, 26, 18, 0)),
    );
    // 边界: 23:58 不许翻成第二天的 00:00
    expect(currentClockHHMM(new Date(2026, 7, 26, 23, 58))).toBe('23:45');
  });

  it('过程报工屏打开时起止是同一个时刻 (时长 0), 并提示要自己拖', async () => {
    // 🔴 必须钉死时钟。这条断言的阴性对照是「假班次的两端 07:00 / 15:00 不许出现」，
    //    而默认值现在**来自真实时钟**并向下取整到 15 分钟 —— 当跑批时刻恰好落在
    //    07:00~07:14 或 15:00~15:14，真实默认值**就是**那个串，起止两处都显示它，
    //    `queryByText` 于是抛「Found multiple elements」。
    //    ⇒ 这条阴性对照原本**每天有两个 15 分钟的窗口必红**。
    //    2026-08-28 22:00:33Z 实测在 main 上红了（自建 runner 本地时间 15:00:33）。
    //    ⚠️ 只 fake Date：整套 timer 都 fake 掉会让 waitFor 挂死。
    jest.useFakeTimers({
      now: new Date(2026, 7, 26, 10, 7),
      doNotFake: [
        'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'setImmediate',
        'clearImmediate', 'nextTick', 'queueMicrotask', 'performance',
        'requestAnimationFrame', 'cancelAnimationFrame',
      ],
    });
    try {
      stubApis('IN_PRODUCTION');
      render(<YieldStepReportScreen />);
      await waitFor(() => expect(screen.getByTestId('time-range-slider')).toBeTruthy());

      expect(screen.getByTestId('time-range-zero-hint')).toBeTruthy();
      // 阳性对照: 钉死的那个时刻**确实**被当成了默认值 —— 否则下面两条
      // 「07:00 / 15:00 不出现」在任何实现下都成立（包括压根不显示时间）。
      expect(screen.getAllByText('10:00').length).toBeGreaterThan(0);
      // 阴性对照: 那个假班次的两端不许再作为默认值出现
      expect(screen.queryByText('07:00')).toBeNull();
      expect(screen.queryByText('15:00')).toBeNull();
    } finally {
      jest.useRealTimers();
    }
  });

  it('时长为 0 时提交被挡回来, 而不是记一段 0 分钟的工时', async () => {
    stubApis('IN_PRODUCTION');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-submit-segment-btn')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-submit-segment-btn'));

    expect(screen.getByText('请选出本段的起止时间')).toBeTruthy();
    expect(mockedYieldApi.submitReport).not.toHaveBeenCalled();
  });
});

describe('P5a 红星只长在「空着就提交不了」的字段上', () => {
  it('投入阶段: 投入量带红星', async () => {
    stubApis('AWAITING_INPUT');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-input-qty')).toBeTruthy());

    expect(screen.getByTestId('yield-input-qty-required-mark')).toBeTruthy();
  });

  it('完工出成: 产出量带红星, 而选填的留样没有', async () => {
    stubApis('IN_PRODUCTION');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy());
    fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
    await waitFor(() => expect(screen.getByTestId('yield-output-qty')).toBeTruthy());

    expect(screen.getByTestId('yield-output-qty-required-mark')).toBeTruthy();
    // 星到处都是等于没有星 —— 选填字段必须没有
    expect(screen.queryByTestId('yield-sample-retain-required-mark')).toBeNull();
  });
});

describe('P7a 投入阶段 / 生产阶段都要有出口', () => {
  it('投入阶段有返回按钮; 什么都没填时直接走人', async () => {
    stubApis('AWAITING_INPUT');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-leave-input-btn')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-leave-input-btn'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('生产阶段有返回按钮', async () => {
    stubApis('IN_PRODUCTION');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-leave-production-btn')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-leave-production-btn'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('有没提交的录入时不许静默丢弃 —— 先问一句, 确认了才走', async () => {
    stubApis('IN_PRODUCTION');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy());
    fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
    await waitFor(() => expect(screen.getByTestId('yield-output-qty')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
    fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), '64');
    fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));

    fireEvent.press(screen.getByTestId('yield-leave-production-btn'));
    // 没走成, 先弹了确认框
    expect(mockGoBack).not.toHaveBeenCalled();
    expect(screen.getByText('还没提交就要离开?')).toBeTruthy();

    // 「丢弃并离开」才真的走
    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });
});

describe('P7b 完工提交成功必须有反馈, 且三条提示一条都不许被盖掉', () => {
  async function submitOutputAndConfirm(): Promise<void> {
    stubApis('IN_PRODUCTION');
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-go-output-step-btn')).toBeTruthy());
    fireEvent.press(screen.getByTestId('yield-go-output-step-btn'));
    await waitFor(() => expect(screen.getByTestId('yield-output-qty')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-output-qty-tap-to-edit'));
    fireEvent.changeText(screen.getByTestId('yield-output-qty-modal-input'), '64');
    fireEvent.press(screen.getByTestId('yield-output-qty-modal-confirm'));

    fireEvent.press(screen.getByTestId('yield-submit-output-btn'));
    // 标题在不平衡时会带前缀 (⚠️ 投入产出不平衡 — 完工出成确认), 用正则不钉死那半句
    await waitFor(() => expect(screen.getByText(/完工出成确认/)).toBeTruthy());
    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
  }

  it('markComplete=true 且本道没有半成品产出时也要弹一条「本道已完工」', async () => {
    mockedYieldApi.submitReport.mockResolvedValue({
      success: true,
      code: 200,
      message: 'ok',
      data: { alert: null, balanceWarning: null, cumulativeOutput: 64 } as never,
    });
    await submitOutputAndConfirm();

    await waitFor(() => expect(screen.getByText('本道已完工')).toBeTruthy());
    expect(screen.getByText(/64 kg 已记为本道产出/)).toBeTruthy();
  });

  it('后端同时带回两条告警时, 三条提示依次都要露面 (不许被成功提示盖掉)', async () => {
    mockedYieldApi.submitReport.mockResolvedValue({
      success: true,
      code: 200,
      message: 'ok',
      data: {
        alert: null,
        balanceWarning: '偏差 35% (投入 99, 产出 64)',
        semiOutputNotConfigured: '本道未配半成品产出编号, 下一道领不到',
        cumulativeOutput: 64,
      } as never,
    });
    await submitOutputAndConfirm();

    // 第一条: 物料平衡偏差
    await waitFor(() => expect(screen.getByText('⚠️ 物料平衡偏差')).toBeTruthy());
    fireEvent.press(screen.getByTestId('app-dialog-btn-0'));
    // 第二条: 产出没进半成品库 —— 走查前它被「成功」那条盖掉, 从来没人看见过
    expect(screen.getByText('产出没进半成品库')).toBeTruthy();
    fireEvent.press(screen.getByTestId('app-dialog-btn-0'));
    // 第三条: 成功
    expect(screen.getByText('本道已完工')).toBeTruthy();
  });
});

describe('P7a 出口提示不许误报 —— 预填的投入量不算「工人填过的东西」', () => {
  /** 两道工序: 第 1 道已完成 (产出 88kg), 第 2 道待投入 → 投入量被预填成 88。 */
  function stubTwoStepBatch(): void {
    const step1 = buildTask({ id: 9001, processOrder: 1, processName: '卤制', status: 'COMPLETED' });
    const step2 = buildTask({ id: 9002, processOrder: 2, processName: '定量包装' });
    const first = buildYield(step1, {
      workProcessTaskId: step1.id,
      processOrder: 1,
      processName: '卤制',
      totalInput: 99,
      totalOutput: 88,
      outputUnit: 'kg',
      phase: 'COMPLETED',
    });
    const second = buildYield(step2, {
      workProcessTaskId: step2.id,
      processOrder: 2,
      processName: '定量包装',
      totalInput: null,
      phase: 'AWAITING_INPUT',
    });
    const yieldData: BatchYieldDTO = { ...first, steps: [...first.steps, ...second.steps] };

    mockedYieldApi.listWorkProcessTasks.mockResolvedValue({
      success: true, code: 200, message: 'ok', data: [step1, step2],
    });
    mockedProcessingApi.getBatchById.mockResolvedValue({
      success: true,
      code: 200,
      message: 'ok',
      data: {
        id: 501,
        productType: '卤猪蹄',
        productTypeId: 'PT-1',
        batchNumber: 'B-20260826-501',
        targetQuantity: 99,
        status: 'IN_PROGRESS',
      } as never,
    });
    mockedYieldApi.getYield.mockResolvedValue({ success: true, code: 200, message: 'ok', data: yieldData });
    mockedYieldApi.getYieldLimits.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { ...emptyLimits, workProcessTaskId: step2.id, inputUnit: 'kg', outputUnit: '盒' } });
    mockedYieldApi.getOutputOptions.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { items: [] } });
  }

  it('阳性对照: 投入量确实是预填的 (上道产出 88)', async () => {
    stubTwoStepBatch();
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-input-qty')).toBeTruthy());

    expect(screen.getByTestId('yield-input-qty-tap-to-edit')).toHaveTextContent(/88/);
  });

  it('只是预填、工人一个字没改 → 返回不该弹「还没提交就要离开」', async () => {
    stubTwoStepBatch();
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-leave-input-btn')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-leave-input-btn'));

    expect(screen.queryByText('还没提交就要离开?')).toBeNull();
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('工人把预填值改掉了 → 还是要问一句', async () => {
    stubTwoStepBatch();
    render(<YieldStepReportScreen />);
    await waitFor(() => expect(screen.getByTestId('yield-input-qty')).toBeTruthy());

    fireEvent.press(screen.getByTestId('yield-input-qty-tap-to-edit'));
    fireEvent.changeText(screen.getByTestId('yield-input-qty-modal-input'), '75');
    fireEvent.press(screen.getByTestId('yield-input-qty-modal-confirm'));

    fireEvent.press(screen.getByTestId('yield-leave-input-btn'));

    expect(screen.getByText('还没提交就要离开?')).toBeTruthy();
    expect(mockGoBack).not.toHaveBeenCalled();
  });
});
