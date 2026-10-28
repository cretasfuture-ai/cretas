/**
 * 成本差异「无法比较」走真实 API 适配层渲染两个报表页。
 *
 * 后端原来拿「实际成本 × 0.95」当标准成本, 屏上恒显示约 +5.26%。
 * 现在没有独立标准成本时后端下发 comparable=false + 差异 / 差异率 null,
 * 两个页面必须显示「无法比较：缺少独立标准成本」, 且不出现任何百分比 / 差异金额。
 * 每条阴性断言配一条阳性读数 (实际成本照样显示), 并配一条对照 (可比较时照样显示差异率)。
 */
import React from 'react';
import MockAdapter from 'axios-mock-adapter';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import { createApiMock } from '../../utils/mockApiClient';
import CostVarianceReportScreen from '../../../screens/reports/CostVarianceReportScreen';
import CostReportScreen from '../../../screens/reports/CostReportScreen';
import {
  COST_VARIANCE_NOT_COMPARABLE_TEXT,
  isCostVarianceComparable,
} from '../../../utils/costVarianceComparability';

jest.mock('@react-navigation/native', () => {
  const ReactActual = jest.requireActual<typeof import('react')>('react');
  return {
    useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
    useFocusEffect: (cb: () => void) => ReactActual.useEffect(cb, [cb]),
  };
});

jest.mock('../../../store/authStore', () => ({
  useAuthStore: () => ({
    user: { id: 1, userType: 'factory', factoryUser: { factoryId: 'F006' } },
  }),
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
    i18n: { language: 'zh-CN' },
  }),
}));

jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatches: jest.fn().mockResolvedValue({ success: true, data: { content: [] } }),
  },
}));

const costVarianceUrl = '/api/mobile/F006/reports/cost-variance';
let apiMock: MockAdapter;

/** 与 Java ProductionReportServiceImpl.getCostVarianceReport 修复后下发的形状一致 */
const notComparableReport = {
  factoryId: 'F006',
  startDate: '2026-10-17',
  endDate: '2026-10-24',
  comparable: false,
  comparabilityReason: '缺少独立标准成本，无法比较',
  totalBomCost: null,
  totalActualCost: 1000,
  totalVariance: null,
  totalVarianceRate: null,
  varianceStatus: 'UNKNOWN',
  productCount: 1,
  batchCount: 1,
  productVariances: [],
  anomalyProducts: [],
};

const comparableReport = {
  ...notComparableReport,
  comparable: true,
  comparabilityReason: null,
  totalBomCost: 970,
  totalVariance: 30,
  totalVarianceRate: 3.09,
  varianceStatus: 'NORMAL',
};

/** 分类 / 项目的差异字段全为 null —— 原来 `?? 0` 会显示成 ¥0.00 / 0.0% */
const nullCategoryAndItem = {
  varianceByCategory: [
    { category: '原料', plannedCost: null, actualCost: null, variance: null, varianceRate: null },
  ],
  topVarianceItems: [
    { category: '原料', itemName: '冻猪蹄', plannedCost: null, actualCost: null, variance: null, varianceRate: null },
  ],
};

const readingCategoryAndItem = {
  varianceByCategory: [
    { category: '原料', plannedCost: 390, actualCost: 400, variance: 10, varianceRate: 2.5 },
  ],
  topVarianceItems: [
    { category: '原料', itemName: '冻猪蹄', plannedCost: 400, actualCost: 394, variance: -6, varianceRate: -1.5 },
  ],
};

function reply(data: Record<string, unknown>) {
  apiMock.onGet(costVarianceUrl).reply(200, { success: true, data });
}

beforeEach(() => {
  apiMock = createApiMock();
});

afterEach(() => {
  cleanup();
  apiMock.restore();
});

describe('isCostVarianceComparable', () => {
  it('comparable=false 或差异率为 null 时不可比较', () => {
    expect(isCostVarianceComparable({ comparable: false, totalVarianceRate: null })).toBe(false);
    expect(isCostVarianceComparable({ comparable: false, totalVarianceRate: 5.26 })).toBe(false);
    expect(isCostVarianceComparable({ comparable: undefined, totalVarianceRate: null })).toBe(false);
    expect(isCostVarianceComparable(null)).toBe(false);
  });

  it('阴性对照: 有独立标准且差异率是数字时可比较', () => {
    expect(isCostVarianceComparable({ comparable: true, totalVarianceRate: 3.09 })).toBe(true);
  });
});

describe('CostVarianceReportScreen', () => {
  it('不可比较: 显示「无法比较」, 不出现任何百分比; 实际成本照样显示', async () => {
    reply(notComparableReport);
    const screen = render(<CostVarianceReportScreen />);

    await waitFor(() => expect(screen.getByText(COST_VARIANCE_NOT_COMPARABLE_TEXT)).toBeTruthy());
    expect(apiMock.history.get).toEqual([expect.objectContaining({ url: costVarianceUrl })]);
    expect(screen.getByText('¥1,000.00')).toBeTruthy();
    expect(screen.queryAllByText(/%/)).toHaveLength(0);
    expect(screen.queryByText('总差异金额')).toBeNull();
  });

  it('分类差异没有读数: 不显示 0.00% 或 ¥0.00', async () => {
    reply({ ...notComparableReport, ...nullCategoryAndItem });
    const screen = render(<CostVarianceReportScreen />);

    await waitFor(() => expect(screen.getAllByText('原料').length).toBeGreaterThan(0));
    expect(screen.getByText('冻猪蹄')).toBeTruthy();
    expect(screen.queryAllByText(/0\.00%/)).toHaveLength(0);
    expect(screen.queryAllByText(/¥0\.00/)).toHaveLength(0);
    expect(screen.getAllByText('无法比较').length).toBeGreaterThanOrEqual(2);
  });

  it('阴性对照: 可比较时照常显示差异率, 不显示「无法比较」', async () => {
    reply(comparableReport);
    const screen = render(<CostVarianceReportScreen />);

    await waitFor(() => expect(screen.getByText('+3.09%')).toBeTruthy());
    expect(screen.queryByText(COST_VARIANCE_NOT_COMPARABLE_TEXT)).toBeNull();
  });
});

describe('CostReportScreen 成本差异 tab', () => {
  async function openVarianceTab() {
    const screen = render(<CostReportScreen />);
    fireEvent.press(screen.getByText('成本差异'));
    await waitFor(() =>
      expect(apiMock.history.get.some((r) => r.url === costVarianceUrl)).toBe(true));
    return screen;
  }

  it('不可比较: 显示「无法比较」, 差异不显示成 ¥0.00 或任何金额', async () => {
    reply(notComparableReport);
    const screen = await openVarianceTab();

    await waitFor(() => expect(screen.getByText(COST_VARIANCE_NOT_COMPARABLE_TEXT)).toBeTruthy());
    expect(screen.getByText('¥1000.00')).toBeTruthy();
    expect(screen.queryByText(/¥0\.00/)).toBeNull();
    expect(screen.getAllByText('无法比较').length).toBeGreaterThanOrEqual(2);
  });

  it('分类差异 / 差异最大项目没有读数: 显示「无法比较」/「—」, ⛔ 不显示 0.0% 或 ¥0.00', async () => {
    reply({ ...notComparableReport, ...nullCategoryAndItem });
    const screen = await openVarianceTab();

    // 一定会出现的读数: 分类名和项目名都渲染出来了 (否则「没有 0.0%」可能只是那一块没渲染)
    await waitFor(() => expect(screen.getByText('原料')).toBeTruthy());
    expect(screen.getByText(/冻猪蹄/)).toBeTruthy();

    expect(screen.queryAllByText(/0\.0%/)).toHaveLength(0);
    expect(screen.queryAllByText(/¥0\.00/)).toHaveLength(0);
    expect(screen.getByText('—')).toBeTruthy();
    expect(screen.getByText('冻猪蹄: 无法比较')).toBeTruthy();
  });

  it('阴性对照: 分类差异有读数时照常显示百分比', async () => {
    reply({ ...comparableReport, ...readingCategoryAndItem });
    const screen = await openVarianceTab();

    await waitFor(() => expect(screen.getByText('+2.5%')).toBeTruthy());
    expect(screen.getByText('¥400.00')).toBeTruthy();
    expect(screen.getByText('冻猪蹄: -1.5% (¥6.00)')).toBeTruthy();
  });

  it('阴性对照: 可比较时显示标准成本与差异金额', async () => {
    reply(comparableReport);
    const screen = await openVarianceTab();

    await waitFor(() => expect(screen.getByText('¥970.00')).toBeTruthy());
    expect(screen.queryByText(COST_VARIANCE_NOT_COMPARABLE_TEXT)).toBeNull();
  });
});
