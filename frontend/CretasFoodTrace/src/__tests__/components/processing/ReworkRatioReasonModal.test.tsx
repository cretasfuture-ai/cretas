/**
 * 缺口 03 回料比例上限 (App 报工)。三层:
 * ① utils/reworkRatio: 错误识别 (只认 409 + REWORK_RATIO_REASON_REQUIRED) 与原因校验;
 * ② ReworkRatioReasonModal: 后端那句话、5 个原因按钮、「其他」才出输入框、没选原因不可继续;
 * ③ YieldStepReportScreen 接线: 两处 INPUT 提交都带 reworkRatio, 被拦时弹窗而不是失败提示。
 */
import React from 'react';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { render, fireEvent, screen } from '@testing-library/react-native';
import ReworkRatioReasonModal from '../../../components/processing/ReworkRatioReasonModal';
import {
  REWORK_REASONS,
  REWORK_REASON_OTHER,
  reworkReasonBlockReason,
  reworkReasonRequiredMessage,
} from '../../../utils/reworkRatio';

const MSG = '回料占比 5%，超过该产品上限 3%，必须填写超限原因';

describe('reworkRatio utils', () => {
  it('只认 409 + REWORK_RATIO_REASON_REQUIRED, 返回后端那句话', () => {
    const err = { response: { status: 409, data: { errorCode: 'REWORK_RATIO_REASON_REQUIRED', message: MSG } } };
    expect(reworkReasonRequiredMessage(err)).toBe(MSG);
  });
  it('别的错误码 / 状态码 / 网络错误都不算', () => {
    expect(reworkReasonRequiredMessage({ response: { status: 409, data: { errorCode: 'OVER_RECEIPT' } } })).toBeNull();
    expect(reworkReasonRequiredMessage({ response: { status: 400, data: { errorCode: 'REWORK_RATIO_REASON_REQUIRED' } } })).toBeNull();
    expect(reworkReasonRequiredMessage(new Error('Network Error'))).toBeNull();
    expect(reworkReasonRequiredMessage(null)).toBeNull();
  });
  it('原因选项与后端 STANDARD_REASONS 一致', () => {
    expect([...REWORK_REASONS]).toEqual(['库存回料需尽快消化', '按主管口头调整', '主料不足', '品质允许', '其他']);
  });
  it('没选原因 / 选「其他」没写说明不放行', () => {
    expect(reworkReasonBlockReason('', '')).not.toBeNull();
    expect(reworkReasonBlockReason(REWORK_REASON_OTHER, '  ')).not.toBeNull();
    expect(reworkReasonBlockReason(REWORK_REASON_OTHER, '客户要求')).toBeNull();
    expect(reworkReasonBlockReason('主料不足', '')).toBeNull();
  });
});

describe('ReworkRatioReasonModal', () => {
  it('message 为 null 不显示弹窗', () => {
    render(<ReworkRatioReasonModal message={null} onConfirm={jest.fn()} onCancel={jest.fn()} />);
    expect(screen.queryByTestId('rework-ratio-modal')).toBeNull();
  });

  it('显示后端那句话; 没选原因时「继续提交」点了不触发', () => {
    const onConfirm = jest.fn();
    render(<ReworkRatioReasonModal message={MSG} onConfirm={onConfirm} onCancel={jest.fn()} />);
    expect(screen.getByTestId('rework-ratio-message').props.children).toBe(MSG);
    expect(screen.getByTestId('rework-ratio-block')).toBeTruthy();
    fireEvent.press(screen.getByTestId('rework-ratio-confirm'));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('选标准原因后可继续, 回调带原因; 不出「其他」输入框', () => {
    const onConfirm = jest.fn();
    render(<ReworkRatioReasonModal message={MSG} onConfirm={onConfirm} onCancel={jest.fn()} />);
    fireEvent.press(screen.getByTestId('rework-ratio-reason-库存回料需尽快消化'));
    expect(screen.queryByTestId('rework-ratio-note')).toBeNull();
    fireEvent.press(screen.getByTestId('rework-ratio-confirm'));
    expect(onConfirm).toHaveBeenCalledWith({ reason: '库存回料需尽快消化', note: undefined });
  });

  it('选「其他」才出输入框, 写了才放行', () => {
    const onConfirm = jest.fn();
    render(<ReworkRatioReasonModal message={MSG} onConfirm={onConfirm} onCancel={jest.fn()} />);
    fireEvent.press(screen.getByTestId('rework-ratio-reason-其他'));
    fireEvent.press(screen.getByTestId('rework-ratio-confirm'));
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.changeText(screen.getByTestId('rework-ratio-note'), '客户加急');
    fireEvent.press(screen.getByTestId('rework-ratio-confirm'));
    expect(onConfirm).toHaveBeenCalledWith({ reason: '其他', note: '客户加急' });
  });

  it('「返回核对」只触发取消', () => {
    const onConfirm = jest.fn();
    const onCancel = jest.fn();
    render(<ReworkRatioReasonModal message={MSG} onConfirm={onConfirm} onCancel={onCancel} />);
    fireEvent.press(screen.getByTestId('rework-ratio-cancel'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});

describe('YieldStepReportScreen 接线 (源码闸)', () => {
  const src = readFileSync(resolve(__dirname, '../../../screens/processing/YieldStepReportScreen.tsx'), 'utf-8');
  const a = src.indexOf('const handleSubmitInput = useCallback');
  const b = src.indexOf('阶段 2a: 提交本段工时');
  const seg = src.slice(a, b);

  it('handleSubmitInput 两处 INPUT 请求都带 reworkRatio', () => {
    expect(seg.match(/reworkRatio: reworkRatioRef\.current/g)?.length).toBe(2);
  });
  it('两处 catch 都先判回料超限弹窗, 再走通用失败提示', () => {
    expect(seg.match(/reworkReasonRequiredMessage\(error\)/g)?.length).toBe(2);
    expect(seg.match(/setReworkPrompt\(reworkMsg\)/g)?.length).toBe(2);
  });
  it('成功后清掉已选原因, 弹窗挂在屏幕上', () => {
    expect(seg.match(/reworkRatioRef\.current = null/g)?.length).toBeGreaterThanOrEqual(4);
    expect(src).toContain('<ReworkRatioReasonModal');
  });
});
