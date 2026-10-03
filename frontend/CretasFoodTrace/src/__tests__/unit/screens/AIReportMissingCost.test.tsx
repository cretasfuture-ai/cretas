import React from 'react';
import MockAdapter from 'axios-mock-adapter';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import { createApiMock } from '../../utils/mockApiClient';
import { AIReportScreen } from '../../../screens/factory-admin/ai-analysis/AIReportScreen';

const mockNavigate = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: { user: null }) => unknown) => selector({ user: null }),
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key === 'aiReport.totalCost' ? '总成本' : key,
    i18n: { language: 'zh-CN' },
  }),
}));

const reportsUrl = '/api/mobile/CRETAS_2024_001/ai/reports';
const reportTitle = '日报 · 2026-10-03';
let apiMock: MockAdapter;

function replyWithCost(costFields: Record<string, unknown>) {
  apiMock.onGet(reportsUrl).reply(200, {
    success: true,
    data: {
      reports: [{
        id: 6001,
        reportType: 'daily',
        createdAt: '2026-10-03T08:00:00Z',
        periodStart: '2026-10-03',
        generationStatus: 'SUCCESS',
        summaryText: '合成报告摘要',
        ...costFields,
      }],
      total: 1,
    },
  });
}

async function renderCost(costFields: Record<string, unknown>) {
  replyWithCost(costFields);
  const screen = render(<AIReportScreen />);
  await waitFor(() => expect(screen.getByText(reportTitle)).toBeTruthy());
  expect(apiMock.history.get).toEqual([expect.objectContaining({ url: reportsUrl })]);
  expect([
    ...apiMock.history.post,
    ...apiMock.history.put,
    ...apiMock.history.patch,
    ...apiMock.history.delete,
  ]).toHaveLength(0);
  return screen;
}

beforeEach(() => {
  apiMock = createApiMock();
  mockNavigate.mockClear();
});

afterEach(() => {
  cleanup();
  apiMock.restore();
});

describe('AIReportScreen cost display through the report API adapter', () => {
  it.each<[string, unknown]>([
    ['null', null],
    ['empty string', ''],
    ['whitespace', '   '],
    ['invalid text', '未提供'],
    ['NaN', Number.NaN],
    ['positive infinity', Number.POSITIVE_INFINITY],
    ['negative infinity', Number.NEGATIVE_INFINITY],
    ['boolean', false],
    ['array', []],
    ['object', {}],
  ])('shows missing cost, not zero or a fabricated amount, for %s', async (_label, totalCost) => {
    const screen = await renderCost({ totalCost });
    expect(screen.getByText('总成本')).toBeTruthy();
    expect(screen.getByText('成本缺失')).toBeTruthy();
    expect(screen.queryByText(/¥/)).toBeNull();
    expect(screen.queryByText(/NaN|Infinity/)).toBeNull();
  });

  it.each<[number | string, string]>([
    [0, '¥0.00'],
    ['0', '¥0.00'],
    [12.5, '¥12.50'],
    ['12.5', '¥12.50'],
    [' 12.5 ', '¥12.50'],
    [1234.56, '¥1234.56'],
  ])('keeps the valid cost %s as %s', async (totalCost, expected) => {
    const screen = await renderCost({ totalCost });
    expect(screen.getByText('总成本')).toBeTruthy();
    expect(screen.getByText(expected)).toBeTruthy();
    expect(screen.queryByText('成本缺失')).toBeNull();
  });

  it('does not invent a cost field when the server omits it', async () => {
    const screen = await renderCost({ keyFindingsCount: 2 });
    expect(screen.getByText('aiReport.keyFindings')).toBeTruthy();
    expect(screen.queryByText('总成本')).toBeNull();
    expect(screen.queryByText('成本缺失')).toBeNull();
    expect(screen.queryByText(/¥/)).toBeNull();
  });

  it('keeps failure details visible without fabricating a zero cost', async () => {
    const screen = await renderCost({
      totalCost: null,
      generationStatus: 'FAILED',
      failureReason: '合成上游服务不可用',
    });
    expect(screen.getByText('生成失败')).toBeTruthy();
    expect(screen.getByText('合成上游服务不可用')).toBeTruthy();
    expect(screen.getByText('成本缺失')).toBeTruthy();
    expect(screen.queryByText('¥0.00')).toBeNull();
  });

  it('still opens the same report from its card when cost is missing', async () => {
    const screen = await renderCost({ totalCost: null });
    fireEvent.press(screen.getByText(reportTitle));
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('AIReportDetail', {
      reportId: 6001,
      reportType: 'daily',
      title: reportTitle,
    });
  });
});
