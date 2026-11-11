/**
 * 纪律排查 A12: 本厂开了「缺料时拦开工」, 手机上开批次被 409 SHORTAGE_CASE_OPEN 拦下时,
 * 不能只弹「开始失败」(死路) —— 要弹原因选择, 选了原因带 forceStartReason 重开, 开成后进签到。
 * 阴性对照: 别的失败照旧弹「开始失败」, 不出原因弹窗。
 */
import React from 'react';
import { render, fireEvent, waitFor, screen, act } from '@testing-library/react-native';
import { Alert } from 'react-native';
import NfcCheckinScreen from '../../../screens/processing/NfcCheckinScreen';
import { workReportingApiClient } from '../../../services/api/workReportingApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

jest.mock('../../../services/api/workReportingApiClient', () => ({
  workReportingApiClient: {
    checkin: jest.fn(),
    checkout: jest.fn(),
    getCheckinList: jest.fn(),
    getTodayCheckins: jest.fn(),
  },
}));
jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatches: jest.fn(),
    startProduction: jest.fn(),
  },
}));
jest.mock('../../../components/processing/BarcodeScannerModal', () => ({
  __esModule: true,
  default: () => null,
  BarcodeScannerModal: () => null,
}));
jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: jest.fn(),
  useNavigationState: (selector: (state: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

const api = processingApiClient as jest.Mocked<typeof processingApiClient>;
const reporting = workReportingApiClient as jest.Mocked<typeof workReportingApiClient>;

const SHORTAGE_MSG = '计划 PP-QA-1 关联的缺料还没解决 (1 种物料: 猪大骨 缺 6 kg), 本厂设置为缺料时不能开工';
const planned = { id: 201, batchNumber: 'PB-QA-201', productType: '酱牛肉', status: 'PLANNED' };
const pageOf = (content: unknown[]) => ({
  success: true, code: 200, message: '成功',
  data: { content, totalElements: content.length, totalPages: 1, size: 10, number: 0, first: true, last: true, empty: content.length === 0 },
});
const user: FactoryUser = {
  id: 22, username: 'workshop_sup1', email: 'ws1@cretas.com', fullName: '王主管', isActive: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z', userType: 'factory', factoryId: 'F006',
  factoryUser: { role: 'workshop_supervisor', factoryId: 'F006', permissions: [] },
};

/** appAlert 在测试里退回 Alert.alert: 找到「开始生产」按钮并按下 */
function pressStartInAlert(alertSpy: jest.SpyInstance) {
  const call = alertSpy.mock.calls.find((c) => c[0] === '开始生产');
  const buttons = (call?.[2] ?? []) as Array<{ text: string; onPress?: () => void }>;
  buttons.find((b) => b.text === '开始生产')?.onPress?.();
}

describe('NfcCheckinScreen · 缺料时开工要原因 (A12)', () => {
  let alertSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    api.startProduction.mockReset();   // clearAllMocks 不清排队的 *Once 返回值, 上一条没用完的会串到下一条
    alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    useAuthStore.setState({ user, isAuthenticated: true, tokens: null, isLoading: false });
    api.getBatches.mockImplementation(((params?: { status?: string }) =>
      Promise.resolve(pageOf(params?.status === 'PLANNED' ? [planned] : []))) as never);
    reporting.getCheckinList.mockResolvedValue({ success: true, code: 200, message: '成功', data: [] } as never);
  });

  afterEach(() => alertSpy.mockRestore());

  it('被缺料闸拦下 ⇒ 弹原因; 选原因后带 forceStartReason 重开并进入签到', async () => {
    api.startProduction
      .mockRejectedValueOnce({ response: { status: 409, data: { errorCode: 'SHORTAGE_CASE_OPEN', message: SHORTAGE_MSG } } })
      .mockResolvedValueOnce({ success: true, code: 200, message: 'ok', data: {} } as never);

    render(<NfcCheckinScreen />);
    await waitFor(() => expect(screen.getByText('PB-QA-201')).toBeTruthy());
    fireEvent.press(screen.getByText('PB-QA-201'));
    await act(async () => { pressStartInAlert(alertSpy); await new Promise((r) => setTimeout(r, 50)); });

    await waitFor(() => expect(screen.getByTestId('force-start-message').props.children).toBe(SHORTAGE_MSG));
    expect(alertSpy).not.toHaveBeenCalledWith('开始失败', expect.anything());
    expect(api.startProduction).toHaveBeenNthCalledWith(1, '201', 22, undefined, undefined);

    fireEvent.press(screen.getByTestId('force-start-reason-缺的料今天能到'));
    await act(async () => { fireEvent.press(screen.getByTestId('force-start-confirm')); await new Promise((r) => setTimeout(r, 50)); });

    await waitFor(() => expect(api.startProduction).toHaveBeenNthCalledWith(2, '201', 22, undefined, '缺的料今天能到'));
    await waitFor(() => expect(reporting.getCheckinList).toHaveBeenCalled());   // 开成后进入签到
    expect(screen.queryByTestId('force-start-modal')).toBeNull();
  });

  it('阴性: 别的失败照旧「开始失败」, 不出原因弹窗', async () => {
    api.startProduction.mockRejectedValueOnce(new Error('批次状态不允许开始生产: IN_PROGRESS'));

    render(<NfcCheckinScreen />);
    await waitFor(() => expect(screen.getByText('PB-QA-201')).toBeTruthy());
    fireEvent.press(screen.getByText('PB-QA-201'));
    await act(async () => { pressStartInAlert(alertSpy); await new Promise((r) => setTimeout(r, 50)); });

    await waitFor(() => expect(alertSpy).toHaveBeenCalledWith('开始失败', '批次状态不允许开始生产: IN_PROGRESS'));
    expect(screen.queryByTestId('force-start-modal')).toBeNull();
  });
});
