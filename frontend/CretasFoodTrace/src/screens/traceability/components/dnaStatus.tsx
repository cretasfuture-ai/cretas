import React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';

import type { AppTheme } from '../../../theme';
import { statusToneColors, ToneChip, type StatusTone, type ToneColors } from './statusTone';

/**
 * DNA 核验状态 —— 文案与色板的**唯一**出处。
 *
 * 四处显示同一个状态：来源链时间线节点、个体档案头部 chip、后代 / 同父后代行、
 * 拍照核验命中结果。2026-09-15 真机实测它们各有各的长相（时间线是棕字、档案头部是
 * 中性描边、后代行是蓝字），「DNA 不匹配」在档案头部看起来和「已采样」一样正常。
 * 同一状态在所有屏用同一颜色是这次的硬判据 —— 所以四处都必须经过这一个文件。
 *
 * ⚠️ 未知状态码<b>原样显示那个码</b>、用中性色，⛔ 不翻译成「未核验」之类看起来
 * 正常的值 —— 把不认识的输入翻译成一个合法值，正是本仓踩过多次的那个坑
 * （`yield_operator` 被渲染成「全无权限」，与真的无权限不可区分）。
 */
export const DNA_STATUS_LABEL: Record<string, string> = {
  UNSAMPLED: '未采样',
  SAMPLED: '已采样',
  MATCHED: 'DNA 已比中',
  MISMATCH: 'DNA 不匹配',
};

export function dnaStatusLabel(status?: string | null): string | null {
  if (!status) {
    return null;
  }
  return DNA_STATUS_LABEL[status] ?? status;
}

/**
 * 状态 → 色调。MATCHED 成功、SAMPLED 信息、UNSAMPLED 中性、MISMATCH 错误。
 * ⚠️ 只有这四个键；认不出来的码走 `neutral`（见 `dnaStatusTone`）。
 */
export const DNA_STATUS_TONE: Record<string, StatusTone> = {
  MATCHED: 'success',
  SAMPLED: 'info',
  UNSAMPLED: 'neutral',
  MISMATCH: 'error',
};

export function dnaStatusTone(status?: string | null): StatusTone {
  if (!status) {
    return 'neutral';
  }
  return DNA_STATUS_TONE[status] ?? 'neutral';
}

/** 给测试与非 chip 场景用：某个状态最终落到的底 / 字色。 */
export function dnaStatusColors(theme: AppTheme, status?: string | null): ToneColors {
  return statusToneColors(theme, dnaStatusTone(status));
}

export interface DnaStatusChipProps {
  status?: string | null;
  testID?: string;
  style?: StyleProp<ViewStyle>;
}

/**
 * 四处共用的 DNA 状态 chip。`status` 为空时不渲染 —— 没登记 DNA 状态不是一种状态，
 * ⛔ 不显示一个「未采样」顶上（那会让「没填」和「填了未采样」长得一样）。
 */
export function DnaStatusChip({ status, testID, style }: DnaStatusChipProps) {
  const label = dnaStatusLabel(status);
  if (!label) {
    return null;
  }
  return (
    <ToneChip tone={dnaStatusTone(status)} testID={testID} style={style}>
      {label}
    </ToneChip>
  );
}
