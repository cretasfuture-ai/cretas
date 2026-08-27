/**
 * 工序任务列表 —— 筛选档必须「带真实计数」, 且计数不许把「没问到」说成「是 0」。
 *
 * 竞品(黑湖小工单)的筛选下拉每个选项自带计数, **选之前就知道有多少条**。
 * 我们这一屏原本三个档一个数都没有 —— 用户必须先点进去才知道有没有东西。
 *
 * 这道闸守三件事:
 *   ① 数印出来了, 且来自 `getTaskCounts`(服务端总数, 口径见
 *      processTaskApiClient.taskCounts.test.ts)。
 *   ② 三态: 问到了就印(**含 0**), 没问到就印**裸标签**(不带括号), ⛔ 不许 `?? 0`。
 *   ③ 计数是「有多少条」, 列表只取前一页 —— 两者不等时必须**说出来**。
 *      ⛔ 沉默的截断比没有计数更糟: 用户会以为数据丢了。
 */
import React from 'react';
import { render, waitFor, screen, within } from '@testing-library/react-native';
import ProcessTaskListScreen from '../../../screens/processing/ProcessTaskListScreen';
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

jest.mock('../../../services/api/processTaskApiClient', () => ({
  processTaskApiClient: {
    getActiveTasks: jest.fn(),
    getTasks: jest.fn(),
    getTaskCounts: jest.fn(),
  },
}));

jest.mock('../../../utils/errorHandler', () => ({ handleError: jest.fn() }));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
  SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
}));

jest.mock('../../../components/ui', () => {
  const { View, Text, TouchableOpacity } = require('react-native');
  return {
    NeoCard: ({ children, style, ...props }: any) => <View style={style} {...props}>{children}</View>,
    NeoButton: ({ children, onPress, ...props }: any) => (
      <TouchableOpacity onPress={onPress} {...props}><Text>{children}</Text></TouchableOpacity>
    ),
    ScreenWrapper: ({ children }: any) => <View>{children}</View>,
  };
});

jest.mock('../../../theme', () => ({
  theme: {
    colors: {
      primary: '#1890ff', background: '#f5f5f5', surface: '#ffffff',
      surfaceVariant: '#f0f0f0', text: '#1F2937', textSecondary: '#6B7280',
      textTertiary: '#9CA3AF', error: '#ef4444', outlineVariant: '#e5e7eb',
    },
    custom: { borderRadius: { m: 8, l: 12 } },
  },
}));

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({ params: {} }),
  // deps 必须是 [cb] —— 屏幕靠回调 identity 变化在切档后重新拉数。
  useFocusEffect: (cb: () => void) => {
    const React = require('react');
    React.useEffect(() => { cb(); }, [cb]);
  },
  useNavigationState: (selector: (s: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

const mockedApi = processTaskApiClient as jest.Mocked<typeof processTaskApiClient>;

const mockUser: FactoryUser = {
  id: 22, username: 'ws1', email: 'ws1@cretas.com', fullName: '王主管',
  isActive: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory', factoryId: 'F001',
  factoryUser: { role: 'workshop_supervisor', factoryId: 'F001', permissions: [] },
};

/** 造 n 条任务 —— 列表长度要能独立于计数变化, 否则 ③ 无法测。 */
function makeTasks(n: number) {
  return Array.from({ length: n }, (_, i) => ({
    id: `task-${i + 1}`,
    factoryId: 'F001',
    productTypeId: 'PT-001',
    productTypeName: '鸡肉香肠',
    workProcessId: `WP-${i + 1}`,
    processName: `工序${i + 1}`,
    processCategory: '热加工',
    unit: 'kg',
    plannedQuantity: 100,
    completedQuantity: 0,
    pendingQuantity: 0,
    status: 'IN_PROGRESS' as const,
  }));
}

async function renderScreen(opts: {
  counts?: { active: number | null; completed: number | null; all: number | null };
  taskCount?: number;
}) {
  const { counts = { active: 19, completed: 300, all: 348 }, taskCount = 2 } = opts;
  mockedApi.getTaskCounts.mockResolvedValue(counts as never);
  mockedApi.getActiveTasks.mockResolvedValue({ success: true, data: makeTasks(taskCount) } as never);
  mockedApi.getTasks.mockResolvedValue({ success: true, data: { content: makeTasks(taskCount) } } as never);

  const view = render(<ProcessTaskListScreen />);
  await waitFor(() => expect(mockedApi.getTaskCounts).toHaveBeenCalled());
  return view;
}

/**
 * 元素级切片: 只在**筛选器那一块**里找标签, ⛔ 不整屏 getByText。
 *
 * ⚠️ 必须切片, 否则会撞车: 任务卡自己也印状态文案「进行中」
 * (STATUS_CONFIG['IN_PROGRESS'].label), 整屏查会同时命中卡片上的那些,
 * 得到 "Found multiple elements" —— 那时断言量的已经不是筛选档了。
 */
function tab() {
  return within(screen.getByTestId('process-task-filter'));
}

beforeEach(() => {
  jest.clearAllMocks();
  useAuthStore.setState({ user: mockUser, isAuthenticated: true, tokens: null, isLoading: false });
});

describe('① 筛选档带真实计数', () => {
  it('三个档各自印出自己的数', async () => {
    await renderScreen({ counts: { active: 19, completed: 300, all: 348 } });

    await waitFor(() => expect(tab().getByText('进行中 (19)')).toBeTruthy());
    expect(tab().getByText('已完成 (300)')).toBeTruthy();
    expect(tab().getByText('全部 (348)')).toBeTruthy();
  });

  it('阳性对照: 换一组数, 标签跟着变 —— 否则上一条可能撞上了写死的常量', async () => {
    await renderScreen({ counts: { active: 4, completed: 1, all: 7 } });

    await waitFor(() => expect(tab().getByText('进行中 (4)')).toBeTruthy());
    expect(tab().getByText('已完成 (1)')).toBeTruthy();
    expect(tab().getByText('全部 (7)')).toBeTruthy();
    // 上一条用的那组数不许还在屏上
    expect(tab().queryByText('进行中 (19)')).toBeNull();
  });
});

describe('② 三态: 读到就印(含 0), 读不到印裸标签', () => {
  it('🔴 全都没问到: 三个档都是裸标签, ⛔ 一个 (0) 都不许出现', async () => {
    await renderScreen({ counts: { active: null, completed: null, all: null } });

    await waitFor(() => expect(tab().getByText('进行中')).toBeTruthy());
    expect(tab().getByText('已完成')).toBeTruthy();
    expect(tab().getByText('全部')).toBeTruthy();
    // 「进行中 (0)」会被读成「现在没有在做的活」, 而真相是我们没查到。
    expect(tab().queryByText('进行中 (0)')).toBeNull();
    expect(tab().queryByText('已完成 (0)')).toBeNull();
    expect(tab().queryByText('全部 (0)')).toBeNull();
  });

  it('🔴 真的是 0 时必须印 (0) —— 否则上一条是恒真式', async () => {
    await renderScreen({ counts: { active: 0, completed: 0, all: 0 }, taskCount: 0 });

    await waitFor(() => expect(tab().getByText('进行中 (0)')).toBeTruthy());
    expect(tab().getByText('已完成 (0)')).toBeTruthy();
    expect(tab().getByText('全部 (0)')).toBeTruthy();
    // 敢报 0 才有意义:「现在一件在做的活都没有」正是用户想知道的。
    expect(tab().queryByText('进行中')).toBeNull();
  });

  it('部分没问到: 问到的照印, 没问到的裸标签 —— 一档失败不连坐', async () => {
    await renderScreen({ counts: { active: 5, completed: null, all: 12 } });

    await waitFor(() => expect(tab().getByText('进行中 (5)')).toBeTruthy());
    expect(tab().getByText('全部 (12)')).toBeTruthy();
    expect(tab().getByText('已完成')).toBeTruthy();
    expect(tab().queryByText('已完成 (0)')).toBeNull();
  });

  it('getTaskCounts 抛错时退回裸标签, 且**列表照常渲染** —— 计数问不到不许拖垮列表', async () => {
    mockedApi.getTaskCounts.mockRejectedValue(new Error('网络断了') as never);
    mockedApi.getActiveTasks.mockResolvedValue({ success: true, data: makeTasks(2) } as never);

    render(<ProcessTaskListScreen />);

    await waitFor(() => expect(screen.getByText('工序1')).toBeTruthy());
    expect(tab().getByText('进行中')).toBeTruthy();
    expect(tab().queryByText('进行中 (0)')).toBeNull();
  });
});

describe('③ 计数是「有多少条」, 列表只取前一页 —— 差额必须说出来', () => {
  it('🔴 总数 348 而列表只有 2 条时, 屏上必须写明差额', async () => {
    await renderScreen({ counts: { active: 348, completed: 1, all: 400 }, taskCount: 2 });

    await waitFor(() => expect(screen.getByTestId('process-task-truncation')).toBeTruthy());
    const notice = screen.getByTestId('process-task-truncation');
    expect(notice.props.children).toContain('348');
    expect(notice.props.children).toContain('2');
  });

  it('阴性对照: 总数与列表相等时不打扰用户', async () => {
    await renderScreen({ counts: { active: 2, completed: 1, all: 3 }, taskCount: 2 });

    await waitFor(() => expect(tab().getByText('进行中 (2)')).toBeTruthy());
    expect(screen.queryByTestId('process-task-truncation')).toBeNull();
  });

  it('阴性对照: 总数没问到时不编差额 —— 没有 N 就说不出「共 N 条」', async () => {
    await renderScreen({ counts: { active: null, completed: null, all: null }, taskCount: 2 });

    await waitFor(() => expect(tab().getByText('进行中')).toBeTruthy());
    expect(screen.queryByTestId('process-task-truncation')).toBeNull();
  });
});
