import React from 'react';
import { render, screen, waitFor } from '@testing-library/react-native';
import QIRecordDetailScreen from '../../screens/quality-inspector/QIRecordDetailScreen';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
  useRoute: () => ({ params: { recordId: 'qi-snapshot' } }),
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));
jest.mock('../../components/attachment', () => ({ AttachmentList: () => null, AttachmentUploadButton: () => null }));
jest.mock('../../components/quality', () => ({ DispositionSuggestion: () => null }));
jest.mock('../../services/api/qualityInspectorApi', () => ({ qualityInspectorApi: { getRecordDetail: jest.fn() } }));

describe('dynamic quality record snapshot detail', () => {
  const mockRecordDetail = jest.requireMock('../../services/api/qualityInspectorApi').qualityInspectorApi.getRecordDetail as jest.Mock;
  it('renders the immutable saved standard and measured values', async () => {
    mockRecordDetail.mockResolvedValue({
      id: 'qi-snapshot', batchId: '1885', batchNumber: 'PB-1885', productName: 'QA product A',
      sampleSize: 1, appearance: { score: 0, notes: [] }, smell: { score: 0, notes: [] }, specification: { score: 0, notes: [] }, weight: { score: 0, notes: [] }, packaging: { score: 0, notes: [] },
      totalScore: 0, passRate: 0, result: 'FAIL', scoreDetailsRecorded: false, grade: 'D', passed: false,
      photos: [], inspector: { id: 1557, name: 'QA inspector' }, inspectedAt: '2026-10-24T00:00:00Z',
      standardBasis: 'DYNAMIC_PRODUCT_BINDING', standardRevision: 'F006:1885:rev-1',
      standardSnapshot: { items: [{ label: '中心温度', unit: '°C', minValue: 0, maxValue: 10, effectiveStandardValue: '5', value: '15', isRequired: true, itemVersion: 3, status: 'FAIL' }] },
    });
    render(<QIRecordDetailScreen />);
    await waitFor(() => expect(screen.getByText('保存的标准与实测明细')).toBeTruthy());
    expect(screen.getByText(/中心温度/)).toBeTruthy();
    expect(screen.getByText(/单位：°C · 版本：3/)).toBeTruthy();
    expect(screen.getByText(/标准：5 · 范围：0 ~ 10/)).toBeTruthy();
    expect(screen.getByText(/实测：15 · 必检 · 判定：FAIL/)).toBeTruthy();
  });
});
