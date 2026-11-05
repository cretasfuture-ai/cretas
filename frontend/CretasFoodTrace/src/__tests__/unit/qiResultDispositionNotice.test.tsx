import React from 'react';
import { render, screen, waitFor } from '@testing-library/react-native';
import QIResultScreen from '../../screens/quality-inspector/QIResultScreen';

/**
 * D-S302-01: 不合格批后补合格检验, 结果页不能只写「合格」让质检员以为放行了。
 */
const mockParams: { recordId: string; passed: boolean; dispositionRequired?: boolean } = { recordId: 'qi-1', passed: true };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn() }),
  useRoute: () => ({ params: mockParams }),
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../components/quality', () => ({ DispositionSuggestion: () => null }));
jest.mock('../../services/api/qualityInspectorApi', () => ({
  qualityInspectorApi: { getRecordDetail: jest.fn().mockResolvedValue({ result: 'PASS', passed: true }) },
}));

describe('质检结果页 · 批次还要处置', () => {
  it('后端说还要处置时写明批次不能放行', async () => {
    mockParams.dispositionRequired = true;
    render(<QIResultScreen />);
    await waitFor(() => expect(screen.getByText(/批次还不能放行/)).toBeTruthy());
  });

  it('阴性: 普通合格不出现这句话', async () => {
    mockParams.dispositionRequired = undefined;
    render(<QIResultScreen />);
    await waitFor(() => expect(screen.getByText('result.passedDescription')).toBeTruthy());
    expect(screen.queryByText(/批次还不能放行/)).toBeNull();
  });
});
