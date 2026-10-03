import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { FactoryUser } from '../../types/auth';
import AIReportDetailScreen from '../../screens/factory-admin/ai-analysis/AIReportDetailScreen';
import AIReportScreen from '../../screens/factory-admin/ai-analysis/AIReportScreen';
import { aiApiClient } from '../../services/api/aiApiClient';

let mockUser: FactoryUser = { id: 81, username: 'synthetic', email: '', isActive: true, createdAt: '', updatedAt: '',
  userType: 'factory', factoryUser: { role: 'finance_manager', factoryId: 'TEST_REPORT', factoryType: 'FACTORY', permissions: [] } };
jest.mock('../../store/authStore', () => ({ useAuthStore: (selector: (state: { user: FactoryUser }) => unknown) => selector({ user: mockUser }) }));
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: { reportId: 71, title: '合成经营报告' } }),
  useNavigation: () => ({ navigate: jest.fn() }),
}));
jest.mock('../../services/api/aiApiClient', () => ({ aiApiClient: { getReportDetail: jest.fn(), getReports: jest.fn() } }));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh-CN' } }) }));
jest.mock('react-native-paper', () => {
  const React = require('react');
  const { Pressable, Text } = require('react-native');
  return { Icon: () => null,
    TouchableRipple: (props: React.PropsWithChildren<{ onPress?: () => void }>) => React.createElement(Pressable, props, props.children),
    Button: (props: React.PropsWithChildren<{ onPress?: () => void }>) => React.createElement(Pressable, props, React.createElement(Text, null, props.children)) };
});
const detail = jest.mocked(aiApiClient.getReportDetail);
const list = jest.mocked(aiApiClient.getReports);
const financial = { success: true, analysis: '合成毛利55321', generatedAt: '2026-10-01' };
const publicReport = { success: true, analysis: '合成库存余量，价格内容已裁剪', generatedAt: '2026-10-01' };
function warehouse() { mockUser = { ...mockUser, factoryUser: { ...mockUser.factoryUser, role: 'warehouse_manager' } }; }
beforeEach(() => {
  jest.clearAllMocks();
  mockUser = { ...mockUser, id: 81, factoryUser: { ...mockUser.factoryUser, role: 'finance_manager', factoryId: 'TEST_REPORT' } };
});

it('immediately removes the populated financial body on a reader change, then shows the new projection', async () => {
  let release: ((value: typeof publicReport) => void) | undefined;
  detail.mockResolvedValueOnce(financial).mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
  const view = render(<AIReportDetailScreen />);
  await waitFor(() => expect(view.getByText('合成毛利55321')).toBeTruthy());
  warehouse(); view.rerender(<AIReportDetailScreen />);
  expect(view.queryByText('合成毛利55321')).toBeNull();
  await act(async () => { release?.(publicReport); });
  await waitFor(() => expect(view.getByText(publicReport.analysis)).toBeTruthy());
  expect(view.queryByText(financial.analysis)).toBeNull();
});
it('ignores a late financial response from the previous reader', async () => {
  let release: ((value: typeof financial) => void) | undefined;
  detail.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; })).mockResolvedValueOnce(publicReport);
  const view = render(<AIReportDetailScreen />);
  warehouse(); view.rerender(<AIReportDetailScreen />);
  await waitFor(() => expect(view.getByText(publicReport.analysis)).toBeTruthy());
  await act(async () => { release?.(financial); });
  expect(view.queryByText(financial.analysis)).toBeNull();
  expect(view.getByText(publicReport.analysis)).toBeTruthy();
});
it('clears populated list summaries on a reader change and gives a retry action on failure', async () => {
  list.mockResolvedValueOnce({ reports: [{ reportId: 71, reportType: 'daily', title: '合成日报', createdAt: '2026-10-01', summary: financial.analysis }], total: 1 })
    .mockRejectedValueOnce(new Error('synthetic network fault')).mockResolvedValueOnce({ reports: [], total: 0 });
  const view = render(<AIReportScreen />);
  await waitFor(() => expect(view.getByText(financial.analysis)).toBeTruthy());
  warehouse(); view.rerender(<AIReportScreen />);
  expect(view.queryByText(financial.analysis)).toBeNull();
  await waitFor(() => expect(view.getByText('报告未能加载。请检查网络后重新加载。')).toBeTruthy());
  fireEvent.press(view.getAllByText('重新加载')[0]);
  await waitFor(() => expect(list).toHaveBeenCalledTimes(3));
});

it('shows a readable Markdown preview without hiding zero or negative business values', async () => {
  list.mockResolvedValueOnce({ reports: [{ reportId: 71, reportType: 'daily', title: '日报 · 2026-10-03 00:00:00', createdAt: '2026-10-03', summary: '# 经营分析报告\n\n## 销售\n- **订单数**：0单\n- 毛利：-¥1.25\n- [取数来源](https://example.test/source)' }], total: 1 });
  const view = render(<AIReportScreen />);
  await waitFor(() => expect(view.getByText('日报 · 2026-10-03')).toBeTruthy());
  expect(view.getByText('经营分析报告 销售 订单数：0单 毛利：-¥1.25 取数来源')).toBeTruthy();
  expect(view.queryByText(/00:00:00/)).toBeNull();
});
