/**
 * AI 对话写操作确认卡 (RN)。
 *
 * 只负责展示与按钮; 预览 / 确认请求、防重复提交由 AIChatScreen 负责。
 * 状态与文案的来源见 ./aiWriteConfirm.ts, UX 依据见
 * docs/specs/rn-ai-chat-write-confirm-2026-10-04.md (UX Flow Analysis F1-F9)。
 *
 * ⛔ 颜色 / 字号只走 useTheme() token 与 Paper variant (同 AIChatScreen 的闸)。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { Text, TouchableRipple, useTheme } from 'react-native-paper';
import type { AppTheme } from '../../theme';
import {
  impactText,
  isDestructive,
  isPreviewExpired,
  previewNote,
  previewRows,
  quoteIntent,
  remainingSeconds,
  EXPIRED_NOTICE,
  type WriteConfirmState,
} from './aiWriteConfirm';

interface Props {
  state: WriteConfirmState;
  onConfirm: () => void;
  onCancel: () => void;
  onRegenerate: () => void;
}

export function AIWriteConfirmCard({ state, onConfirm, onCancel, onRegenerate }: Props) {
  const theme = useTheme<AppTheme>();
  const tone = useMemo(() => ({
    card: { backgroundColor: theme.colors.surface, borderColor: theme.colors.primary },
    dangerCard: { backgroundColor: theme.colors.surface, borderColor: theme.colors.error },
    askCard: { backgroundColor: theme.colors.surfaceVariant, borderColor: theme.colors.outlineVariant },
    onSurface: { color: theme.colors.onSurface },
    muted: { color: theme.colors.onSurfaceVariant },
    primaryText: { color: theme.colors.primary },
    errorText: { color: theme.colors.onErrorContainer },
    impact: { backgroundColor: theme.colors.primaryContainer },
    dangerImpact: { backgroundColor: theme.colors.errorContainer },
    notice: { backgroundColor: theme.colors.errorContainer },
    primaryButton: { backgroundColor: theme.colors.primary },
    dangerButton: { backgroundColor: theme.colors.error },
    disabledButton: { backgroundColor: theme.colors.surfaceVariant },
    secondaryButton: { borderColor: theme.colors.outline, backgroundColor: theme.colors.surface },
    onPrimary: { color: theme.colors.onPrimary },
    rowDivider: { borderBottomColor: theme.colors.outlineVariant },
  }), [theme]);

  // 倒计时: 只在 ready 态每秒刷一次 (本地时钟 + expiresInSeconds, 不比服务器时钟)
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    if (state.phase !== 'ready' || state.action?.expiresInSeconds == null) return undefined;
    setNowMs(Date.now());
    const timer = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [state.phase, state.action, state.receivedAtMs]);

  const intentLabel = quoteIntent(state.intentName);

  if (state.phase === 'cancelled' || state.phase === 'done') {
    return (
      <View testID="ai-confirm-card" style={[styles.collapsed, tone.card]}>
        <Text testID="ai-confirm-collapsed" variant="bodyMedium" style={tone.muted}>
          {intentLabel} {state.phase === 'cancelled' ? '已取消' : (state.doneLabel ?? '已执行')}
        </Text>
      </View>
    );
  }

  if (state.phase === 'preparing') {
    return (
      <View testID="ai-confirm-card" style={[styles.card, tone.card]}>
        <Text variant="titleMedium" style={tone.onSurface}>{intentLabel}需要你确认</Text>
        <View testID="ai-confirm-preparing" style={styles.inlineRow} accessibilityLiveRegion="polite">
          <ActivityIndicator size="small" color={theme.colors.primary} />
          <Text variant="bodyMedium" style={[styles.inlineText, tone.muted]}>正在生成操作预览…</Text>
        </View>
      </View>
    );
  }

  if (state.phase === 'asking') {
    // 追问不是报错: 中性底色 + 问题原话, 没有标题、没有红色提示条、没有按钮 (回答就是下一句话)
    return (
      <View testID="ai-confirm-card" style={[styles.card, tone.askCard]} accessibilityLiveRegion="polite">
        <Text testID="ai-confirm-question" variant="bodyLarge" style={tone.onSurface}>{state.notice}</Text>
      </View>
    );
  }

  const expired = state.phase === 'ready' && isPreviewExpired(state, nowMs);
  const left = state.phase === 'ready' ? remainingSeconds(state, nowMs) : null;
  const destructive = isDestructive(state);
  const executing = state.phase === 'executing';
  const rows = state.phase === 'ready' || executing ? previewRows(state.action) : [];
  const note = state.phase === 'ready' || executing ? previewNote(state.action) : undefined;
  const noticeText = expired ? EXPIRED_NOTICE : state.notice;
  const showRegenerate = expired || ((state.phase === 'blocked' || state.phase === 'failed') && state.canRegenerate);
  const showActions = state.phase === 'ready' || executing;
  const confirmDisabled = executing || expired;
  const confirmLabel = destructive ? '我已了解影响，确认执行' : '确认执行';

  return (
    <View testID="ai-confirm-card" style={[styles.card, destructive ? tone.dangerCard : tone.card]}>
      <Text variant="titleMedium" style={tone.onSurface}>
        {state.phase === 'blocked' ? `${intentLabel}暂时不能执行` : state.phase === 'failed' ? `${intentLabel}没有执行成功` : `${intentLabel}请确认`}
      </Text>

      {showActions && state.action?.description ? (
        <Text variant="bodyMedium" style={[styles.gap, tone.onSurface]}>{state.action.description}</Text>
      ) : null}

      {rows.length > 0 ? (
        <View testID="ai-confirm-rows" style={styles.gap}>
          {rows.map((row) => (
            <View key={row.key} style={[styles.row, tone.rowDivider]}>
              <Text variant="bodyMedium" style={[styles.rowLabel, tone.muted]}>{row.label}</Text>
              <Text variant="bodyLarge" style={[styles.rowValue, tone.onSurface]}>
                {row.before !== undefined ? `${row.before} → ${row.value}` : row.value}
              </Text>
            </View>
          ))}
        </View>
      ) : null}

      {note ? <Text variant="bodyMedium" style={[styles.gap, tone.onSurface]}>{note}</Text> : null}

      {showActions && !expired ? (
        <View style={[styles.impact, destructive ? tone.dangerImpact : tone.impact]}>
          <Text testID="ai-confirm-impact" variant="bodyMedium" style={destructive ? tone.errorText : tone.onSurface}>
            {impactText(state)}
          </Text>
        </View>
      ) : null}

      {noticeText ? (
        <View style={[styles.impact, tone.notice]} accessibilityLiveRegion="assertive">
          <Text testID="ai-confirm-notice" variant="bodyMedium" style={tone.errorText}>{noticeText}</Text>
        </View>
      ) : null}

      {showActions && left !== null && !expired ? (
        <Text testID="ai-confirm-countdown" variant="bodySmall" style={[styles.gap, tone.muted]}>
          {left} 秒内有效
        </Text>
      ) : null}

      {showActions ? (
        <View style={styles.actions}>
          <TouchableRipple
            testID="ai-confirm-execute"
            accessibilityRole="button"
            accessibilityLabel={`${confirmLabel}：${state.intentName}`}
            accessibilityState={{ disabled: confirmDisabled, busy: executing }}
            disabled={confirmDisabled}
            onPress={onConfirm}
            style={[
              styles.button,
              styles.primaryButton,
              confirmDisabled ? tone.disabledButton : (destructive ? tone.dangerButton : tone.primaryButton),
            ]}
          >
            <View style={styles.inlineRow}>
              {executing ? <ActivityIndicator size="small" color={theme.colors.onPrimary} /> : null}
              <Text variant="titleMedium" style={[executing ? styles.inlineText : null, confirmDisabled ? tone.muted : tone.onPrimary]}>
                {executing ? '正在执行…' : confirmLabel}
              </Text>
            </View>
          </TouchableRipple>
          <TouchableRipple
            testID="ai-confirm-cancel"
            accessibilityRole="button"
            accessibilityLabel={`取消：${state.intentName}`}
            accessibilityState={{ disabled: executing }}
            disabled={executing}
            onPress={onCancel}
            style={[styles.button, styles.secondaryButton, tone.secondaryButton]}
          >
            <Text variant="titleMedium" style={executing ? tone.muted : tone.primaryText}>取消</Text>
          </TouchableRipple>
        </View>
      ) : null}

      {showRegenerate ? (
        <TouchableRipple
          testID="ai-confirm-regenerate"
          accessibilityRole="button"
          accessibilityLabel={`重新生成预览：${state.intentName}`}
          onPress={onRegenerate}
          style={[styles.button, styles.secondaryButton, styles.regenerate, tone.secondaryButton]}
        >
          <Text variant="titleMedium" style={tone.primaryText}>重新生成预览</Text>
        </TouchableRipple>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    marginTop: 8,
    borderWidth: 1,
    borderRadius: 12,
    padding: 14,
    maxWidth: '92%',
  },
  collapsed: {
    marginTop: 8,
    borderWidth: 1,
    borderRadius: 12,
    paddingVertical: 10,
    paddingHorizontal: 14,
    alignSelf: 'flex-start',
  },
  gap: {
    marginTop: 8,
  },
  inlineRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 4,
  },
  inlineText: {
    marginLeft: 8,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 6,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowLabel: {
    marginRight: 12,
  },
  rowValue: {
    flexShrink: 1,
    textAlign: 'right',
  },
  impact: {
    marginTop: 10,
    borderRadius: 8,
    padding: 10,
  },
  actions: {
    flexDirection: 'row',
    marginTop: 14,
    gap: 12,
  },
  /** ≥48pt 触控目标, 两个按钮间距 12 (防误触) */
  button: {
    minHeight: 48,
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 16,
  },
  primaryButton: {
    flex: 2,
  },
  secondaryButton: {
    flex: 1,
    borderWidth: 1,
  },
  regenerate: {
    marginTop: 12,
    alignSelf: 'stretch',
  },
});
