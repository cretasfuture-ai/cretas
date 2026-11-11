import React from 'react';
import { render, screen, waitFor } from '@testing-library/react-native';
import QIResultScreen from '../../screens/quality-inspector/QIResultScreen';

/**
 * 工厂开关 quality.inspection.fail_scope (2026-11-11): 只有按本厂设置不判整批不合格的项目不合格时,
 * 结论是条件合格, 结果页要说出是哪几项、由质量经理决定 —— 不能只写一句笼统的「有条件合格」。
 */
const mockParams: { recordId: string; passed: boolean; reviewItems?: string[]; dispositionRequired?: boolean } = { recordId: 'qi-1', passed: false };
const mockRecord: { result: string; passed: boolean; batchId: string } = { result: 'CONDITIONAL', passed: false, batchId: '1885' };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: jest.fn() }),
  useRoute: () => ({ params: mockParams }),
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../components/quality', () => {
  const mockReact = jest.requireActual('react');
  const { Text } = require('react-native');
  return { DispositionSuggestion: () => mockReact.createElement(Text, null, '处置建议组件') };
});
jest.mock('../../services/api/qualityInspectorApi', () => ({
  qualityInspectorApi: { getRecordDetail: jest.fn(() => Promise.resolve(mockRecord)) },
}));

describe('质检结果页 · 次要项交质量经理决定', () => {
  it('条件合格且带标出的项目: 点名项目并说明由质量经理决定', async () => {
    mockParams.reviewItems = ['包装外观', '标签位置'];
    render(<QIResultScreen />);
    await waitFor(() => expect(screen.getByText(/包装外观、标签位置 不合格/)).toBeTruthy());
    expect(screen.getByText(/已通知质量经理决定/)).toBeTruthy();
    // 不再同时叫质检员自己去提交处置 (独立审查 F3)
    expect(screen.getByText(/你不用再操作/)).toBeTruthy();
    expect(screen.queryByText(/请提交隔离、返工、报废/)).toBeNull();
    expect(screen.queryByText('处置建议组件')).toBeNull();
  });

  it('批次之前已判不合格时以处置那句为准, 不说「不判整批不合格」', async () => {
    mockParams.reviewItems = ['包装外观'];
    mockParams.dispositionRequired = true;
    render(<QIResultScreen />);
    await waitFor(() => expect(screen.getByText(/批次还不能放行/)).toBeTruthy());
    expect(screen.queryByText(/不判整批不合格/)).toBeNull();
    mockParams.dispositionRequired = undefined;
  });

  it('阴性: 没有标出项目的条件合格仍是原来的处置说明', async () => {
    mockParams.reviewItems = undefined;
    render(<QIResultScreen />);
    await waitFor(() => expect(screen.getByText(/完成处置审批前不得进入包装或出货流程/)).toBeTruthy());
    expect(screen.queryByText(/已通知质量经理决定/)).toBeNull();
    expect(screen.getByText('处置建议组件')).toBeTruthy();
  });
});
