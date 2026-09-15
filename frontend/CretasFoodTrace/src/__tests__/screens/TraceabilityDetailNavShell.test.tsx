/**
 * 溯源详情的导航壳 + 查询时间格式（demo-nav-shell / 评审 P0 + P2）—— 跑在**真实屏幕**上。
 *
 * 2026-09-15 真机：详情页顶部直接是溯源码卡，没标题没返回（全文件 0 处 Appbar / goBack）；
 * 底部「查询时间: 9/15/2026, 6:46:38 PM」是整屏唯一一处英文日期。
 *
 * 守的行为（每条阴性断言都配一条同屏「必发生」的读数，硬约束 9）：
 * ① 四个分支（正文 / 出错）都有返回键，按下去真的调 goBack —— 不是画了个箭头
 * ② 标题：有批次号时是批次号；没有批次号（出错分支）退回「溯源详情」
 * ③ 查询时间走 formatters 的 "YYYY-MM-DD HH:mm"，⛔ 不再是 toLocaleString 的英文
 * ④ 没有 queryTime 整行不出现（⛔ 没有占位横杠）
 * ⑤ 加工环节的时间行同一把尺子
 *
 * 变异对照（2026-09-16 做过，每条先打印可观测量确认变异到达再看红）：
 *  · 正文分支去掉 `{header}` ⇒ ① 红（trace-detail-back 找不到）
 *  · 查询时间换回 `new Date(...).toLocaleString()` ⇒ ③ 红（正则 2026-09-15 18:46 不匹配）
 */
import React from 'react';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';

import TraceabilityDetailScreen from '../../screens/traceability/TraceabilityDetailScreen';
import type { FullTraceResponse, ProcessingStageInfo } from '../../services/api/traceabilityApiClient';

const BATCH = 'SL-DEMO-BATCH-V1';
const SYSTEM_CODE = 'TR-CATTLE_3701_001-20260915-8125';

const mockGetFullTrace = jest.fn();
const mockGoBack = jest.fn();
// jest.mock 工厂会被提升，只能引用 mock 前缀的变量 —— 用一个可变容器让每条用例改路由参数。
const mockRoute: { params: Record<string, unknown> } = { params: { batchNumber: BATCH } };

jest.mock('@react-navigation/native', () => ({
  useRoute: () => mockRoute,
  useNavigation: () => ({ navigate: jest.fn(), goBack: mockGoBack }),
}));

// 单测里没有 i18n 实例 ⇒ 直接查 zh-CN 的 common bundle，断言里的中文与用户屏上看到的同源。
jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/common.json');
  const lookup = (key: string): string => {
    const raw = key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
    return typeof raw === 'string' ? raw : key;
  };
  return { useTranslation: () => ({ t: lookup }) };
});

jest.mock('../../services/api/traceabilityApiClient', () => ({
  traceabilityApiClient: {
    getFullTrace: (...args: unknown[]) => mockGetFullTrace(...args),
  },
}));

function traceResponse(overrides: Partial<FullTraceResponse> = {}): FullTraceResponse {
  return {
    production: {
      batchNumber: BATCH,
      productName: '分割牛肉',
      productType: 'PT-1',
      productionDate: '2026-09-15',
      qualityStatus: 'PENDING',
      factoryName: '胜龙牛业',
      factoryId: 'CATTLE_3701_001',
    },
    materials: [],
    processingStages: [],
    qualityInspections: [],
    shipments: [],
    traceCode: SYSTEM_CODE,
    // ⚠️ 不带 Z：formatters 用本地 getters，这样断言值与 TZ 无关。
    queryTime: '2026-09-15T18:46:38',
    lineage: null,
    ...overrides,
  } as FullTraceResponse;
}

beforeEach(() => {
  mockGetFullTrace.mockReset();
  mockGoBack.mockReset();
  mockRoute.params = { batchNumber: BATCH };
});

describe('① 顶部导航壳：返回键接到 goBack', () => {
  it('正文分支：返回键在，按下去 goBack 恰好被调一次', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-card')).toBeTruthy());
    const back = getByTestId('trace-detail-back');
    expect(back.props.accessibilityLabel).toBe('返回');

    // 阳性对照：按之前 0 次 —— 否则「被调了 1 次」可能是渲染期就调过
    expect(mockGoBack).toHaveBeenCalledTimes(0);
    fireEvent.press(back);
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('出错分支（未找到批次）：用户不会卡死 —— 返回键仍在且可用', async () => {
    mockGetFullTrace.mockResolvedValue(null);
    const { getByTestId, getByText } = render(<TraceabilityDetailScreen />);

    // 「必发生」：错误文案先渲染出来，再说返回键
    await waitFor(() => expect(getByText('未找到该批次的溯源信息')).toBeTruthy());
    fireEvent.press(getByTestId('trace-detail-back'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });
});

describe('② 标题', () => {
  it('有批次号 ⇒ 标题就是批次号（用户刚输入的那个），不是泛泛的「溯源详情」', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-code-card')).toBeTruthy());
    const title = within(getByTestId('trace-detail-title'));
    expect(title.getByText(BATCH)).toBeTruthy();
    expect(title.queryByText('溯源详情')).toBeNull();
  });

  it('路由没带批次号 ⇒ 出错分支的标题退回「溯源详情」', async () => {
    mockRoute.params = {};
    const { getByTestId, getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('缺少批次号参数')).toBeTruthy());
    expect(within(getByTestId('trace-detail-title')).getByText('溯源详情')).toBeTruthy();
    // 阳性对照：这条路根本没查 API —— 标题不是从响应里来的
    expect(mockGetFullTrace).not.toHaveBeenCalled();
  });
});

describe('③④ 查询时间', () => {
  it('"YYYY-MM-DD HH:mm"，中文界面里不再有 "9/15/2026, 6:46:38 PM"', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse());
    const { getByTestId } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByTestId('trace-query-time')).toBeTruthy());
    const text = ([] as unknown[]).concat(getByTestId('trace-query-time').props.children).join('');
    expect(text).toMatch(/^查询时间 2026-09-15 18:46$/);
    // 阴性对照：旧格式的三个特征（斜杠 / 逗号 / AM-PM）一个都不许回来
    expect(text).not.toMatch(/\/|,|AM|PM/);
  });

  it('没有 queryTime ⇒ 整行不出现，⛔ 不显示「查询时间: -」', async () => {
    mockGetFullTrace.mockResolvedValue(traceResponse({ queryTime: undefined }));
    const { getByTestId, queryByTestId, queryByText } = render(<TraceabilityDetailScreen />);

    // 「必发生」：正文渲染出来了
    await waitFor(() => expect(getByTestId('trace-code-card')).toBeTruthy());
    expect(queryByTestId('trace-query-time')).toBeNull();
    expect(queryByText(/查询时间/)).toBeNull();
  });
});

describe('⑤ 加工环节的时间行同一把尺子', () => {
  it('"时间: 2026-09-15 08:05 ~ 09:30"', async () => {
    const stage: ProcessingStageInfo = {
      stageName: '分割',
      stageType: 'CUTTING',
      stageOrder: 1,
      startTime: '2026-09-15T08:05:00',
      endTime: '2026-09-15T09:30:00',
    } as ProcessingStageInfo;
    mockGetFullTrace.mockResolvedValue(traceResponse({ processingStages: [stage] }));
    const { getByText } = render(<TraceabilityDetailScreen />);

    await waitFor(() => expect(getByText('分割')).toBeTruthy());
    expect(getByText('时间: 2026-09-15 08:05 ~ 09:30')).toBeTruthy();
  });
});
