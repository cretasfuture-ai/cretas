/**
 * 缺口 13 —— 报工屏的关键控制点录入栏:
 *  - 本道没挂 CCP → 不显示, 父组件拿到 ready + [] (不挡提交, 阴性对照);
 *  - 挂了 → 先显示合格范围; 未填完挡提交; 超限当场红字;
 *  - 加载失败 → 挡提交 (fail-closed) + 重新加载出路。
 */
import React, { useState } from 'react';
import { Text } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import CcpReadingsSection from '../../../components/processing/CcpReadingsSection';
import {
  buildCcpReadings, ccpSubmitBlockReason, CcpLoadStatus, isCcpOutOfLimit, parseCcpValue,
} from '../../../components/processing/ccpReadings';
import { ccpApi, CcpStepCheckpoint } from '../../../services/api/ccpApi';

jest.mock('../../../services/api/ccpApi', () => {
  const actual = jest.requireActual('../../../services/api/ccpApi');
  return { ...actual, ccpApi: { listStepCheckpoints: jest.fn(), record: jest.fn() } };
});
const mockedApi = ccpApi as jest.Mocked<typeof ccpApi>;

const CPS: [CcpStepCheckpoint, CcpStepCheckpoint] = [
  { checkpointId: 1, checkpointCode: 'CCP-T', name: '中心温度', hazardType: 'BIOLOGICAL', criticalLimitMin: 75,
    criticalLimitMax: 100, unit: '℃', monitoringProcedure: '红外测温', correctiveAction: '继续加热 5 分钟' },
  { checkpointId: 2, checkpointCode: 'CCP-H', name: '保温时间', hazardType: 'BIOLOGICAL', criticalLimitMin: 15,
    criticalLimitMax: 60, unit: 'min', monitoringProcedure: null, correctiveAction: null },
];

/** 模拟父组件: 用和报工屏同一个 ccpSubmitBlockReason 决定「能不能提交」。 */
const Harness: React.FC<{ workProcessId: string | null }> = ({ workProcessId }) => {
  const [status, setStatus] = useState<CcpLoadStatus>('loading');
  const [cps, setCps] = useState<CcpStepCheckpoint[]>([]);
  const [values, setValues] = useState<Record<number, string>>({});
  const onLoaded = React.useCallback((s: CcpLoadStatus, c: CcpStepCheckpoint[]) => { setStatus(s); setCps(c); }, []);
  const onChange = React.useCallback((id: number, t: string) => setValues((p) => ({ ...p, [id]: t })), []);
  const reason = ccpSubmitBlockReason(status, cps, values);
  return (
    <>
      <CcpReadingsSection workProcessId={workProcessId} values={values} onChangeValue={onChange} onLoaded={onLoaded} />
      <ReasonText reason={reason} />
    </>
  );
};
const ReasonText: React.FC<{ reason: string | null }> = ({ reason }) => (
  <Text testID="block">{reason ?? 'CAN_SUBMIT'}</Text>
);

describe('CCP 纯判据', () => {
  it('闭区间 / 文本解析 / 组装', () => {
    expect(isCcpOutOfLimit(75, CPS[0])).toBe(false);
    expect(isCcpOutOfLimit(100, CPS[0])).toBe(false);
    expect(isCcpOutOfLimit(74.9, CPS[0])).toBe(true);
    expect(parseCcpValue(' 78。5 ')).toBe(78.5);
    expect(parseCcpValue('abc')).toBeNull();
    expect(parseCcpValue('')).toBeNull();
    expect(buildCcpReadings(CPS, { 1: '80', 2: '30' })).toEqual([
      { checkpointId: 1, measuredValue: 80 }, { checkpointId: 2, measuredValue: 30 },
    ]);
    expect(ccpSubmitBlockReason('error', [], {})).toContain('重新加载');
    expect(ccpSubmitBlockReason('ready', [], {})).toBeNull();
  });
});

describe('CcpReadingsSection', () => {
  beforeEach(() => jest.clearAllMocks());

  it('阴性对照: 本道没挂 CCP → 不显示, 不挡提交', async () => {
    mockedApi.listStepCheckpoints.mockResolvedValue({ success: true, data: [] } as never);
    render(<Harness workProcessId="WP-PLAIN" />);
    await waitFor(() => expect(screen.getByTestId('block').props.children).toBe('CAN_SUBMIT'));
    expect(screen.queryByTestId('ccp-section')).toBeNull();
  });

  it('挂了 CCP: 显示范围; 没填完挡提交; 超限红字; 填完可提交', async () => {
    mockedApi.listStepCheckpoints.mockResolvedValue({ success: true, data: CPS } as never);
    render(<Harness workProcessId="WP-COOK" />);
    expect(await screen.findByText('合格范围：75 ~ 100 ℃')).toBeTruthy();
    expect(screen.getByTestId('block').props.children).toBe('请先填写：中心温度、保温时间');

    fireEvent.changeText(screen.getByTestId('ccp-input-CCP-T'), '70');
    expect(screen.getByTestId('ccp-out-CCP-T')).toBeTruthy();
    expect(screen.getByTestId('block').props.children).toBe('请先填写：保温时间');

    fireEvent.changeText(screen.getByTestId('ccp-input-CCP-H'), '30');
    expect(screen.queryByTestId('ccp-out-CCP-H')).toBeNull();
    expect(screen.getByTestId('block').props.children).toBe('CAN_SUBMIT');
  });

  it('加载失败 → 挡提交 + 重新加载', async () => {
    mockedApi.listStepCheckpoints.mockRejectedValueOnce(new Error('网络断开'));
    mockedApi.listStepCheckpoints.mockResolvedValueOnce({ success: true, data: [] } as never);
    render(<Harness workProcessId="WP-COOK" />);
    expect(await screen.findByTestId('ccp-reload')).toBeTruthy();
    expect(screen.getByTestId('block').props.children).toContain('重新加载');
    fireEvent.press(screen.getByTestId('ccp-reload'));
    await waitFor(() => expect(screen.getByTestId('block').props.children).toBe('CAN_SUBMIT'));
  });
});
