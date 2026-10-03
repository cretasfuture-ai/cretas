import fs from 'fs';
import path from 'path';
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

let mockUser: any;
const mockGetReportDetail = jest.fn();

jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: { user: any }) => unknown) => selector({ user: mockUser }),
}));
jest.mock('../../../services/api/aiApiClient', () => ({
  aiApiClient: { getReportDetail: (...args: unknown[]) => mockGetReportDetail(...args) },
}));
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: { reportId: 71, title: '经营报告' } }),
}));
jest.mock('../../../components/common/MarkdownRenderer', () => {
  const React = require('react');
  const { Text } = require('react-native');
  const Renderer = ({ content }: { content: string }) => (
    React.createElement(Text, { testID: 'markdown-renderer' }, `MARKDOWN:${content}`)
  );
  return { __esModule: true, default: Renderer, MarkdownRenderer: Renderer };
});

const AIReportDetailScreen = require('../../../screens/factory-admin/ai-analysis/AIReportDetailScreen').default;

const userFor = (role: string) => ({
  id: 81,
  userType: 'factory',
  factoryUser: { factoryId: 'F006', factoryType: 'FACTORY', role, permissions: [] },
});

beforeEach(() => {
  mockUser = userFor('finance_manager');
  mockGetReportDetail.mockReset();
});

describe('AIReportDetailScreen Markdown presentation', () => {
  it('passes the loaded report body to MarkdownRenderer instead of a raw Text body', async () => {
    const report = { success: true, analysis: '# 毛利\n\n**12.5%**', generatedAt: '2026-10-01' };
    mockGetReportDetail.mockResolvedValue(report);

    const view = render(<AIReportDetailScreen />);
    await waitFor(() => expect(view.getByTestId('markdown-renderer')).toBeTruthy());
    expect(view.getByTestId('markdown-renderer').props.children).toBe(`MARKDOWN:${report.analysis}`);
    expect(mockGetReportDetail).toHaveBeenCalledWith(71);
  });

  it('keeps reader isolation when a previous request resolves after the reader changes', async () => {
    let releasePrevious: ((value: any) => void) | undefined;
    mockGetReportDetail
      .mockImplementationOnce(() => new Promise((resolve) => { releasePrevious = resolve; }))
      .mockResolvedValueOnce({ success: true, analysis: '仓储可见正文', generatedAt: '2026-10-01' });

    const view = render(<AIReportDetailScreen />);
    mockUser = userFor('warehouse_manager');
    view.rerender(<AIReportDetailScreen />);
    await waitFor(() => expect(view.getByTestId('markdown-renderer').props.children).toContain('仓储可见正文'));

    await act(async () => { releasePrevious?.({ success: true, analysis: '财务正文', generatedAt: '2026-10-01' }); });
    expect(view.getByTestId('markdown-renderer').props.children).toContain('仓储可见正文');
    expect(view.getByTestId('markdown-renderer').props.children).not.toContain('财务正文');
  });

  it('keeps the existing error and retry path, while empty analysis remains empty', async () => {
    mockGetReportDetail.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({
      success: true,
      analysis: '',
      generatedAt: '2026-10-01',
    });
    const view = render(<AIReportDetailScreen />);
    await waitFor(() => expect(view.getByText('报告未能加载。请检查网络后重新加载。')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByText('重新加载')); });
    await waitFor(() => expect(mockGetReportDetail).toHaveBeenCalledTimes(2));
    expect(view.queryByTestId('markdown-renderer')).toBeNull();
  });
});

it('keeps the screen-level reader/sequence guard and delegates body rendering', () => {
  const source = fs.readFileSync(
    path.resolve(__dirname, '../../../screens/factory-admin/ai-analysis/AIReportDetailScreen.tsx'),
    'utf8',
  );
  expect(source).toContain("import MarkdownRenderer from '../../../components/common/MarkdownRenderer'");
  expect(source).toContain('requestSequence.current');
  expect(source).toContain('loadedReaderKey === readerKey');
  expect(source).toContain('<MarkdownRenderer content={visibleReport.analysis}');
  expect(source).not.toContain('<Text selectable style={styles.body}>{visibleReport.analysis}</Text>');
});
