/**
 * 纪律排查 A12 / A10: 后端拦下要原因才能继续的通用弹窗与判别函数。
 * ① utils/requiredReason: 只认 409 + 指定错误码; 原因校验; 发给后端的那句话怎么拼;
 * ② RequiredReasonModal: 后端那句话、固定原因按钮、「其他」才出输入框、没选原因不可继续;
 * ③ YieldStepReportScreen 接线 (源码闸): 超收不再是「确认吗」, 而是原因弹窗 + forceSubmitReason。
 */
import React from 'react';
import { readFileSync } from 'fs';
import { resolve } from 'path';
import { render, fireEvent, screen } from '@testing-library/react-native';
import RequiredReasonModal from '../../../components/processing/RequiredReasonModal';
import {
  FORCE_START_REASONS,
  OVER_RECEIPT,
  REASON_OTHER,
  SHORTAGE_CASE_OPEN,
  blockedMessage,
  composeReason,
  reasonBlock,
} from '../../../utils/requiredReason';

const MSG = '计划 PP-QA-1 关联的缺料还没解决 (1 种物料: 猪大骨 缺 6 kg), 本厂设置为缺料时不能开工';

describe('requiredReason utils', () => {
  it('只认 409 + 指定的错误码, 返回后端那句话', () => {
    const err = { response: { status: 409, data: { errorCode: SHORTAGE_CASE_OPEN, message: MSG } } };
    expect(blockedMessage(err, SHORTAGE_CASE_OPEN)).toBe(MSG);
  });
  it('别的错误码 / 状态码 / 网络错误都不算 (阴性)', () => {
    expect(blockedMessage({ response: { status: 409, data: { errorCode: OVER_RECEIPT } } }, SHORTAGE_CASE_OPEN)).toBeNull();
    expect(blockedMessage({ response: { status: 400, data: { errorCode: SHORTAGE_CASE_OPEN } } }, SHORTAGE_CASE_OPEN)).toBeNull();
    expect(blockedMessage(new Error('Network Error'), SHORTAGE_CASE_OPEN)).toBeNull();
    expect(blockedMessage(null, SHORTAGE_CASE_OPEN)).toBeNull();
  });
  it('没选原因 / 选「其他」没写说明不放行', () => {
    expect(reasonBlock('', '')).not.toBeNull();
    expect(reasonBlock(REASON_OTHER, '  ')).not.toBeNull();
    expect(reasonBlock(REASON_OTHER, '客户要求')).toBeNull();
    expect(reasonBlock('客户急单', '')).toBeNull();
  });
  it('「其他」发写的那句; 标准原因发按钮文字, 写了补充接在后面', () => {
    expect(composeReason(REASON_OTHER, ' 设备换线 ')).toBe('设备换线');
    expect(composeReason('客户急单', '')).toBe('客户急单');
    expect(composeReason('客户急单', '下午发车')).toBe('客户急单：下午发车');
  });
});

describe('RequiredReasonModal', () => {
  const props = {
    title: '缺料还没解决',
    hint: '确实要现在开工，请选一个原因',
    reasons: FORCE_START_REASONS,
    confirmLabel: '仍然开工',
    cancelLabel: '先不开工',
    testIDPrefix: 'force-start',
  };

  it('message 为 null 不显示', () => {
    render(<RequiredReasonModal {...props} message={null} onConfirm={jest.fn()} onCancel={jest.fn()} />);
    expect(screen.queryByTestId('force-start-modal')).toBeNull();
  });

  it('显示后端那句话; 没选原因时继续按钮点了不触发', () => {
    const onConfirm = jest.fn();
    render(<RequiredReasonModal {...props} message={MSG} onConfirm={onConfirm} onCancel={jest.fn()} />);
    expect(screen.getByTestId('force-start-message').props.children).toBe(MSG);
    fireEvent.press(screen.getByTestId('force-start-confirm'));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('选标准原因后可继续; 选「其他」要写一句', () => {
    const onConfirm = jest.fn();
    render(<RequiredReasonModal {...props} message={MSG} onConfirm={onConfirm} onCancel={jest.fn()} />);
    fireEvent.press(screen.getByTestId('force-start-reason-客户急单'));
    fireEvent.press(screen.getByTestId('force-start-confirm'));
    expect(onConfirm).toHaveBeenLastCalledWith('客户急单');

    fireEvent.press(screen.getByTestId('force-start-reason-其他'));
    fireEvent.press(screen.getByTestId('force-start-confirm'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    fireEvent.changeText(screen.getByTestId('force-start-note'), '设备先空转调试');
    fireEvent.press(screen.getByTestId('force-start-confirm'));
    expect(onConfirm).toHaveBeenLastCalledWith('设备先空转调试');
  });

  it('提交中继续按钮不放行; 取消只触发取消', () => {
    const onConfirm = jest.fn();
    const onCancel = jest.fn();
    render(<RequiredReasonModal {...props} message={MSG} submitting onConfirm={onConfirm} onCancel={onCancel} />);
    fireEvent.press(screen.getByTestId('force-start-confirm'));
    expect(onConfirm).not.toHaveBeenCalled();
  });
});

describe('YieldStepReportScreen 超收接线 (源码闸, 纪律排查 A10)', () => {
  const src = readFileSync(resolve(__dirname, '../../../screens/processing/YieldStepReportScreen.tsx'), 'utf-8');

  it('OVER_RECEIPT 不再只弹「确认超收提交」, 而是原因弹窗', () => {
    expect(src).not.toContain("'确认超收提交'");
    expect(src).toContain('setOverReceipt({ req, message: actionHint })');
    expect(src).toContain('<RequiredReasonModal');
    expect(src).toContain('reasons={OVER_RECEIPT_REASONS}');
  });
  it('强制提交带上原因', () => {
    expect(src).toMatch(/forceSubmit: true, forceSubmitReason/);
  });
});

describe('开批次被缺料闸拦下的接线 (源码闸, 纪律排查 A12)', () => {
  for (const file of ['NfcCheckinScreen.tsx', 'TeamBatchReportScreen.tsx']) {
    const src = readFileSync(resolve(__dirname, `../../../screens/processing/${file}`), 'utf-8');
    it(`${file}: 被 SHORTAGE_CASE_OPEN 拦下时弹原因, 选了原因带 forceStartReason 重开`, () => {
      expect(src).toContain('blockedMessage(error, SHORTAGE_CASE_OPEN)');
      expect(src).toContain('setForceStart({ batch, message: blocked })');
      expect(src).toMatch(/startProduction\([\s\S]{0,80}forceStartReason/);
      expect(src).toContain('reasons={FORCE_START_REASONS}');
    });
  }
});

describe('调度完成计划 (源码闸, 纪律排查 A6 同形 —— 独立审查 #1596)', () => {
  const src = readFileSync(resolve(__dirname, '../../../screens/dispatcher/plan/PlanDetailScreen.tsx'), 'utf-8');
  it('不再拿计划量当实际产量提交', () => {
    expect(src).not.toMatch(/completeProduction\(planId, plan\?\.quantity/);
    expect(src).toContain('testID="complete-plan-actual"');
    expect(src).toMatch(/completeProduction\(\s*planId, actualValue/);
  });
  it('超产被拦时接着问原因, 带原因重提', () => {
    expect(src).toContain("blockedMessage(e, 'PRODUCTION_OVER_PLAN_REASON_REQUIRED')");
    expect(src).toContain('completing.reasonPrompt ? completing.reason.trim() : undefined');
  });
});
