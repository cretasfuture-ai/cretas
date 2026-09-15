import React from 'react';
import { StyleSheet, type StyleProp, type ViewStyle } from 'react-native';
import { Chip, useTheme } from 'react-native-paper';

import type { AppTheme } from '../../../theme';

/**
 * 状态 → 语义色调 → 主题 token。
 *
 * 溯源这几屏原来各自写 `chipColor + '20'` 拼透明度、`#4CAF50` / `#FF9800` 这类硬编码，
 * 同一个「合格」在三处三个绿。这里是**唯一**的「色调 → token」映射：
 * 屏上只决定「这个状态是哪一种色调」，⛔ 不再决定「用哪个色值」。
 *
 * | 色调 | 底 / 字 token | 用在 |
 * |---|---|---|
 * | success | workflow.doneBg / doneText | 合格、已送达、DNA 已比中 |
 * | info    | primaryContainer / onPrimaryContainer | 已发货、DNA 已采样 |
 * | pending | workflow.pendingBg / pendingText | 待检验、质检中、待发货、需返工 |
 * | error   | errorContainer / onErrorContainer | 不合格、报废、已退货、DNA 不匹配 |
 * | neutral | surfaceVariant / onSurfaceVariant | 未采样、认不出来的状态码 |
 *
 * 每一对底/字都是 `theme/index.ts` 里已经存在的 token（workflow 那组注释里写了
 * 「Background/text pairs verified WCAG AA」）。⛔ 不在这里新造色值。
 */
export type StatusTone = 'success' | 'info' | 'pending' | 'error' | 'neutral';

export interface ToneColors {
  backgroundColor: string;
  color: string;
}

export function statusToneColors(theme: AppTheme, tone: StatusTone): ToneColors {
  switch (tone) {
    case 'success':
      return { backgroundColor: theme.custom.workflow.doneBg, color: theme.custom.workflow.doneText };
    case 'info':
      return { backgroundColor: theme.colors.primaryContainer, color: theme.colors.onPrimaryContainer };
    case 'pending':
      return { backgroundColor: theme.custom.workflow.pendingBg, color: theme.custom.workflow.pendingText };
    case 'error':
      return { backgroundColor: theme.colors.errorContainer, color: theme.colors.onErrorContainer };
    case 'neutral':
      return { backgroundColor: theme.colors.surfaceVariant, color: theme.colors.onSurfaceVariant };
  }
}

export interface ToneChipProps {
  tone: StatusTone;
  children: string;
  testID?: string;
  style?: StyleProp<ViewStyle>;
}

/**
 * 带语义色调的状态 chip —— 溯源详情的质量状态 / 质检结果 / 出货状态、
 * 以及四处 DNA 状态（经 `DnaStatusChip`）都渲染成它。
 *
 * 字号沿用 Paper Chip 默认的 labelLarge（14sp），⛔ 不再写 `fontSize: 11/12`。
 */
export function ToneChip({ tone, children, testID, style }: ToneChipProps) {
  const theme = useTheme<AppTheme>();
  const colors = statusToneColors(theme, tone);
  return (
    <Chip
      compact
      mode="flat"
      testID={testID}
      style={[styles.chip, { backgroundColor: colors.backgroundColor }, style]}
      textStyle={{ color: colors.color }}
    >
      {children}
    </Chip>
  );
}

const styles = StyleSheet.create({
  chip: { alignSelf: 'flex-start' },
});
