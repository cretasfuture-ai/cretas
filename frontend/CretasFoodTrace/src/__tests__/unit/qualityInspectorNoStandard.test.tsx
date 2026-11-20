import React from 'react';
import { render, screen, waitFor } from '@testing-library/react-native';
import QIFormScreen from '../../screens/quality-inspector/QIFormScreen';

// 用真实的 zh-CN 词条: 既验屏幕接上了, 也验 JSON 里真有这两个 key (key 不存在会返回 undefined)
// eslint-disable-next-line @typescript-eslint/no-var-requires
const zh = require('../../i18n/locales/zh-CN/quality.json') as Record<string, unknown>;
const lookup = (key: string): string | undefined =>
  key.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], zh) as string | undefined;

const mockAppAlert = jest.fn();
jest.mock('../../components/ui/AppDialog', () => ({ appAlert: (...args: unknown[]) => mockAppAlert(...args) }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ replace: jest.fn(), navigate: jest.fn(), goBack: jest.fn() }),
  useRoute: () => ({ params: { batchId: '1885', batchNumber: 'PB-1885' } }),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => lookup(key.replace(/^quality:/, '')) ?? `MISSING:${key}` }),
}));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../store/authStore', () => ({ useAuthStore: () => ({ user: { id: 1557 } }) }));
jest.mock('../../services/api/attachmentApi', () => ({ attachmentApi: {} }));
jest.mock('../../services/api/qualityInspectorApi', () => ({
  qualityInspectorApi: { getBatchDetail: jest.fn(), getDynamicQualityForm: jest.fn(), submitDynamicInspection: jest.fn() },
}));

const NO_STANDARD_TEXT = '该产品还没有配置质检标准，请联系质量经理在网页端配置后再检验';

function backendError(status: number, message: string, hint?: string) {
  const error = new Error(hint ? `${message}\n\n${hint}` : message) as Error & { response: unknown };
  error.response = { status, data: { success: false, message, actionHint: hint } };
  return error;
}

describe('打开没有质检标准的批次 (D-S301-m-01)', () => {
  const api = jest.requireMock('../../services/api/qualityInspectorApi').qualityInspectorApi;

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    api.getBatchDetail.mockResolvedValue({ id: '1885', batchNumber: 'PB-1885', productName: 'QA product A', quantity: 1, unit: 'kg', status: 'pending', createdAt: '', factoryId: 'F006' });
  });

  it('弹窗和屏上都说「没配质检标准」, 不再是「无法加载批次信息」; 仍然进不了表单', async () => {
    api.getDynamicQualityForm.mockRejectedValue(backendError(409, '该产品未配置启用的质检标准', '请先为产品配置并启用质检标准'));
    render(<QIFormScreen />);

    await waitFor(() => expect(mockAppAlert).toHaveBeenCalledTimes(1));
    expect(mockAppAlert).toHaveBeenCalledWith('该产品还没有质检标准', NO_STANDARD_TEXT);
    expect(screen.getByText(NO_STANDARD_TEXT)).toBeTruthy();
    expect(screen.queryByText('无法加载批次信息')).toBeNull();
    // 拦截行为不变: 没有提交按钮, 有返回
    expect(screen.queryByText('提交质检结果')).toBeNull();
    expect(screen.getByText('返回批次')).toBeTruthy();
    expect(api.submitDynamicInspection).not.toHaveBeenCalled();
  });

  it('阴性: 断网这类未知失败仍用通用文案', async () => {
    api.getDynamicQualityForm.mockRejectedValue(new Error('Network Error'));
    render(<QIFormScreen />);

    await waitFor(() => expect(mockAppAlert).toHaveBeenCalledTimes(1));
    expect(mockAppAlert).toHaveBeenCalledWith('加载批次失败', '无法加载批次信息');
    expect(screen.queryByText(NO_STANDARD_TEXT)).toBeNull();
    expect(screen.queryByText('提交质检结果')).toBeNull();
  });

  it('阴性: 别的业务拒绝显示后端原话, 不冒充没配标准', async () => {
    api.getDynamicQualityForm.mockRejectedValue(backendError(404, '批次不存在'));
    render(<QIFormScreen />);

    await waitFor(() => expect(mockAppAlert).toHaveBeenCalledTimes(1));
    expect(mockAppAlert).toHaveBeenCalledWith('加载批次失败', '批次不存在');
    expect(screen.queryByText(NO_STANDARD_TEXT)).toBeNull();
  });
});
