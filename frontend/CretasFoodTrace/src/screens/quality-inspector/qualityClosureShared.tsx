/**
 * 质检员「不良品处置 / 批次召回」几页共用的状态块与样式 (UX Flow Analysis F8/F10:
 * 加载中 / 失败可重试 / 真正为空 三态分开, 不让用户分不清「没有」和「没加载出来」)。
 */
import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { Button } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';

import { QI_COLORS } from '../../types/qualityInspector';
import type { DispositionAction } from '../../types/qualityDisposition';
import type { DispositionCandidate, PendingDisposition } from '../../services/api/qualityDispositionApiClient';

/**
 * 等待审批卡片的批次身份 (fool-proof Rule 2: 批次号 + 品名, 不显示内部 ID)。
 * 后端 /quality-disposition/pending 只回 productionBatchId (PendingDispositionDTO.batchNumber 从未赋值),
 * 所以用已加载的待处置列表对上: 先按质检记录 ID (entityId), 再按生产批次 ID。
 * 对不上就如实显示「批次 #ID」并标明没查到批次号, ⛔ 不编。
 */
export function resolvePendingBatch(
  row: PendingDisposition,
  candidates: DispositionCandidate[],
): { title: string; productName: string | null; matched: boolean } {
  if (row.batchNumber) {
    const byBatch = candidates.find((c) => c.productionBatchId === row.productionBatchId);
    return { title: row.batchNumber, productName: byBatch?.productName ?? null, matched: true };
  }
  const hit = candidates.find((c) => c.id === row.entityId)
    ?? (row.productionBatchId != null
      ? candidates.find((c) => c.productionBatchId === row.productionBatchId && !!c.batchNumber)
      : undefined);
  if (hit?.batchNumber) {
    return { title: hit.batchNumber, productName: hit.productName ?? null, matched: true };
  }
  return {
    title: row.productionBatchId != null ? `批次 #${row.productionBatchId}` : '批次未知',
    productName: hit?.productName ?? null,
    matched: false,
  };
}

export function LoadingBlock({ text = '正在读取…' }: { text?: string }) {
  return (
    <View style={shared.stateBox} testID="qc-loading">
      <ActivityIndicator size="large" color={QI_COLORS.primary} />
      <Text style={shared.stateText}>{text}</Text>
    </View>
  );
}

export function ErrorBlock({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <View style={shared.stateBox} testID="qc-error">
      <Ionicons name="cloud-offline-outline" size={40} color={QI_COLORS.danger} />
      <Text style={[shared.stateText, { color: QI_COLORS.danger }]}>{message}</Text>
      <Text style={shared.stateHint}>请检查网络后点「重试」；一直失败请联系质量负责人。</Text>
      <Button mode="contained" onPress={onRetry} style={shared.stateButton} buttonColor={QI_COLORS.primary}>
        重试
      </Button>
    </View>
  );
}

export function EmptyBlock({ text, hint, actionText, onAction }: {
  text: string;
  hint?: string;
  actionText?: string;
  onAction?: () => void;
}) {
  return (
    <View style={shared.stateBox} testID="qc-empty">
      <Ionicons name="checkmark-done-circle-outline" size={44} color={QI_COLORS.primary} />
      <Text style={shared.stateText}>{text}</Text>
      {hint ? <Text style={shared.stateHint}>{hint}</Text> : null}
      {actionText && onAction ? (
        <Button mode="outlined" onPress={onAction} style={shared.stateButton} textColor={QI_COLORS.primary}>
          {actionText}
        </Button>
      ) : null}
    </View>
  );
}

/** 处置动作白话 (UX F4: 每个动作一句人话说明)。 */
export const ACTION_TEXT: Record<string, { label: string; hint: string; color: string }> = {
  RELEASE: { label: '直接放行', hint: '这批没问题，可以正常使用 / 出货', color: '#1B8A3A' },
  CONDITIONAL_RELEASE: { label: '条件放行', hint: '有小问题，按限制条件使用', color: '#B7791F' },
  REWORK: { label: '返工', hint: '退回重新加工后再检', color: '#C05621' },
  SCRAP: { label: '报废', hint: '整批不能用，按报废处理', color: '#C53030' },
  SPECIAL_APPROVAL: { label: '特批放行', hint: '不达标但特殊批准使用', color: '#6B46C1' },
  HOLD: { label: '先隔离', hint: '暂时不动，等进一步判断', color: '#2B6CB0' },
};

export function actionLabel(code: string | null | undefined): string {
  return (code && ACTION_TEXT[code]?.label) || String(code ?? '-');
}

export const APPROVAL_LEVEL_TEXT: Record<string, string> = {
  SUPERVISOR: '主管',
  QUALITY_HEAD: '质量负责人',
  QUALITY_MANAGER: '质量负责人',
  FACTORY_MANAGER: '厂长',
  MANAGER: '经理',
};

export function approverText(level: string | null | undefined): string {
  if (!level) return '有审批权限的人';
  return APPROVAL_LEVEL_TEXT[String(level).toUpperCase()] ?? '有审批权限的人';
}

export function resultText(result: string | null | undefined): string {
  if (result === 'FAIL' || result === 'FAILED') return '不合格';
  if (result === 'CONDITIONAL') return '有条件放行';
  if (result === 'PASS') return '复检合格';
  return String(result ?? '-');
}

/** 非放行动作必选原因 (fool-proof Rule 3)。 */
export const DISPOSITION_REASONS: Record<string, string[]> = {
  CONDITIONAL_RELEASE: ['外观轻微瑕疵', '规格略有偏差', '客户已同意让步接收', '其他'],
  REWORK: ['可返工修复', '包装/标签需重做', '重量不足需补足', '其他'],
  SCRAP: ['微生物/安全指标超标', '异物无法去除', '变质/异味', '返工后仍不合格', '其他'],
  SPECIAL_APPROVAL: ['客户书面特批', '内部降级使用', '其他'],
  HOLD: ['等待复检结果', '等待供应商确认', '原因不明需调查', '其他'],
};

export function reasonsFor(action: DispositionAction | string | null | undefined): string[] {
  return (action && DISPOSITION_REASONS[action]) || [];
}

export const shared = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 16, paddingBottom: 40 },
  card: {
    backgroundColor: QI_COLORS.card,
    borderRadius: 14,
    padding: 16,
    marginBottom: 12,
    overflow: 'hidden',
  },
  cardTitle: { fontSize: 18, fontWeight: '800', color: QI_COLORS.text },
  cardSub: { marginTop: 4, fontSize: 14, color: QI_COLORS.textSecondary, lineHeight: 20 },
  row: { flexDirection: 'row', alignItems: 'center' },
  /** 标题 + 右上角状态标签: 长批次号在标题里换行, 标签保持完整 (真机 PB-PLAN-… 把标签挤出屏)。 */
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  titleInRow: { flex: 1, flexShrink: 1, marginRight: 10 },
  tagInRow: { flexShrink: 0, marginTop: 2 },
  tag: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 10, alignSelf: 'flex-start' },
  tagText: { fontSize: 13, fontWeight: '700' },
  sectionTitle: { marginTop: 8, marginBottom: 10, fontSize: 16, fontWeight: '800', color: QI_COLORS.text },
  note: { fontSize: 13, color: QI_COLORS.textSecondary, lineHeight: 19 },
  stateBox: { alignItems: 'center', paddingVertical: 40, paddingHorizontal: 20 },
  stateText: { marginTop: 12, fontSize: 16, fontWeight: '700', color: QI_COLORS.text, textAlign: 'center' },
  stateHint: { marginTop: 6, fontSize: 13, color: QI_COLORS.textSecondary, textAlign: 'center', lineHeight: 19 },
  stateButton: { marginTop: 16, minWidth: 140 },
  primaryButton: { marginTop: 8, borderRadius: 12 },
  primaryButtonContent: { minHeight: 52 },
});
