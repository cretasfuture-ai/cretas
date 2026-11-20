import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockGetReportDetail = jest.fn();
const mockDownload = jest.fn();
const mockShare = jest.fn();

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: { user: unknown }) => unknown) => selector({
    user: { id: 1, userType: 'factory', factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role: 'finance_manager', permissions: [] } },
  }),
}));
jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: {
    getReportDetail: (...args: unknown[]) => mockGetReportDetail(...args),
    downloadReportFile: (...args: unknown[]) => mockDownload(...args),
  },
}));
jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: (...args: unknown[]) => mockShare(...args),
}));
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: { reportId: 71, title: '经营报告' } }),
}));
jest.mock('../../../components/common/MarkdownRenderer', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const Renderer = ({ content }: { content: string }) => React.createElement(Text, null, content);
  return { __esModule: true, default: Renderer, MarkdownRenderer: Renderer };
});

const AIReportDetailScreen = require('../../../screens/factory-admin/ai-analysis/AIReportDetailScreen').default;

beforeEach(() => { mockGetReportDetail.mockReset(); mockDownload.mockReset(); mockShare.mockReset(); });

describe('AIReportDetailScreen 导出', () => {
  it('成功的报告可导出 PDF：下载服务端文件后交给系统分享', async () => {
    mockGetReportDetail.mockResolvedValue({ success: true, analysis: '正文', generatedAt: '2026-11-17' });
    mockDownload.mockResolvedValue('file:///cache/operating-report-71.pdf');
    const view = render(<AIReportDetailScreen />);
    await waitFor(() => expect(view.getByText('导出 PDF')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByText('导出 PDF')); });
    await waitFor(() => expect(mockShare).toHaveBeenCalled());
    expect(mockDownload).toHaveBeenCalledWith(71, 'pdf');
    expect(mockShare.mock.calls[0][0]).toBe('file:///cache/operating-report-71.pdf');
    expect(mockShare.mock.calls[0][1]).toMatchObject({ mimeType: 'application/pdf' });
  });

  it('生成失败的报告不提供导出（阴性对照）', async () => {
    mockGetReportDetail.mockResolvedValue({ success: false, generationStatus: 'FAILED', analysis: '', errorMessage: '失败', generatedAt: '2026-11-17' });
    const view = render(<AIReportDetailScreen />);
    await waitFor(() => expect(view.getByText('生成失败')).toBeTruthy());
    expect(view.queryByText('导出 PDF')).toBeNull();
    expect(view.queryByText('导出 PPT')).toBeNull();
  });
});
