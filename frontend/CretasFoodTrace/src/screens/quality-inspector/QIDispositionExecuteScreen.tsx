/**
 * 质检员 App · 不良品处置 (单批): 看系统建议 → 选处置方式 → 选原因 → 提交。
 *
 * 与网页 /quality/disposition 走同一组接口: /quality-disposition/evaluate (只读) → /execute。
 * 需不需要审批、谁审批, 全部由后端按工厂处置规则决定 (approvalInitiated), App 只展示。
 * 写按钮按 quality:read_write 判 (后端同一门槛), 不按岗位名。
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button, TouchableRipple } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { appAlert } from '../../components/ui/AppDialog';
import {
  qualityDispositionAPI,
  type DispositionActionInfo,
  type DispositionEvaluation,
  type DispositionResult,
} from '../../services/api/qualityDispositionApiClient';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import type { DispositionAction } from '../../types/qualityDisposition';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { canWriteQualityClosure } from '../../utils/qualityClosurePermissions';
import {
  ACTION_TEXT,
  ErrorBlock,
  LoadingBlock,
  actionLabel,
  approverText,
  reasonsFor,
  resultText,
  shared,
} from './qualityClosureShared';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;
type Route = RouteProp<QualityInspectorStackParamList, 'QIDispositionExecute'>;

const ACTION_ORDER = ['RELEASE', 'CONDITIONAL_RELEASE', 'REWORK', 'HOLD', 'SCRAP', 'SPECIAL_APPROVAL'];

export default function QIDispositionExecuteScreen() {
  const navigation = useNavigation<Nav>();
  const { candidate } = useRoute<Route>().params;
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canWrite = canWriteQualityClosure(user);

  const [actions, setActions] = useState<DispositionActionInfo[]>([]);
  const [evaluation, setEvaluation] = useState<DispositionEvaluation | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string>('');
  const [reason, setReason] = useState('');
  const [otherText, setOtherText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<DispositionResult | null>(null);

  const batchTitle = candidate.batchNumber || `批次 #${candidate.productionBatchId}`;

  const load = useCallback(async () => {
    if (!factoryId) {
      setError('读不到当前工厂，请退出后重新登录');
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const [acts, evalResult] = await Promise.all([
        qualityDispositionAPI.getAvailableActions(factoryId),
        canWrite
          ? qualityDispositionAPI.evaluateDisposition(factoryId, {
            inspectionId: candidate.id,
            productionBatchId: candidate.productionBatchId,
            inspectorId: candidate.inspectorId,
            inspectionDate: candidate.inspectionDate ?? undefined,
            sampleSize: Number(candidate.sampleSize ?? 0),
            passCount: Number(candidate.passCount ?? 0),
            failCount: Number(candidate.failCount ?? 0),
            passRate: candidate.passRate ?? undefined,
            result: candidate.result as 'PASS' | 'FAIL' | 'CONDITIONAL',
            notes: candidate.notes ?? undefined,
          })
          : Promise.resolve(null),
      ]);
      setActions(acts);
      setEvaluation(evalResult ?? null);
      if (evalResult?.recommendedAction) setSelected(evalResult.recommendedAction);
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '处置建议读取失败');
    } finally {
      setLoading(false);
    }
  }, [factoryId, canWrite, candidate]);

  useEffect(() => { void load(); }, [load]);

  const actionInfo = useMemo(() => {
    const byCode = new Map(actions.map((a) => [a.actionCode as string, a]));
    return ACTION_ORDER.filter((c) => byCode.has(c)).map((c) => byCode.get(c) as DispositionActionInfo);
  }, [actions]);

  const needsReason = selected !== '' && selected !== 'RELEASE';
  const reasonOptions = reasonsFor(selected);
  const finalReason = reason === '其他' ? otherText.trim() : [reason, otherText.trim()].filter(Boolean).join('：');
  const canSubmit = canWrite && selected !== '' && (!needsReason || finalReason.length > 0) && !submitting;
  const selectedInfo = actionInfo.find((a) => a.actionCode === selected);

  const pick = (code: string) => {
    setSelected(code);
    setReason('');
    setOtherText('');
  };

  const doSubmit = async () => {
    if (!factoryId || !user) return;
    setSubmitting(true);
    try {
      const res = await qualityDispositionAPI.executeDisposition(factoryId, {
        batchId: candidate.productionBatchId,
        inspectionId: candidate.id,
        actionCode: selected as DispositionAction,
        operatorComment: finalReason || undefined,
        executorId: user.id,
      });
      if (!res) throw new Error('服务器没有返回处置结果');
      setResult(res);
    } catch (e) {
      // 已选内容保留, 不清空 (UX F8)
      appAlert('处置没有提交成功', `${getErrorMsg(e)}\n\n你选的内容还在，确认后可以再点提交。`);
    } finally {
      setSubmitting(false);
    }
  };

  const onSubmit = () => {
    if (!canSubmit) return;
    const approval = selectedInfo?.requiresApproval
      ? `\n\n这个处置需要${approverText(selectedInfo.approvalLevel)}审批，提交后先送审。`
      : '';
    appAlert(
      `确认${actionLabel(selected)} — ${batchTitle}`,
      `${candidate.productName || ''}${finalReason ? `\n原因：${finalReason}` : ''}${approval}`,
      [
        { text: '再看看', style: 'cancel' },
        { text: '确认提交', onPress: () => { void doSubmit(); } },
      ],
    );
  };

  if (result) {
    const sent = !!result.approvalInitiated;
    return (
      <ScrollView style={shared.screen} contentContainerStyle={shared.content} testID="qi-disposition-result">
        <View style={[shared.card, { alignItems: 'center', paddingVertical: 28 }]}>
          <Ionicons
            name={sent ? 'time-outline' : 'checkmark-circle'}
            size={56}
            color={sent ? QI_COLORS.warning : QI_COLORS.primary}
          />
          <Text style={[shared.cardTitle, { marginTop: 12 }]}>
            {sent ? '已送审，等待审批' : `已${actionLabel(result.executedAction)}`}
          </Text>
          <Text style={[shared.cardSub, { textAlign: 'center' }]}>{batchTitle} · {candidate.productName || ''}</Text>
          <Text style={[shared.cardSub, { textAlign: 'center', marginTop: 10 }]}>{result.message}</Text>
          {result.nextSteps ? <Text style={[shared.note, { textAlign: 'center', marginTop: 6 }]}>{result.nextSteps}</Text> : null}
        </View>
        <Button
          mode="contained"
          buttonColor={QI_COLORS.primary}
          style={shared.primaryButton}
          contentStyle={shared.primaryButtonContent}
          onPress={() => navigation.goBack()}
          testID="qi-disposition-result-back"
        >
          返回处置列表
        </Button>
      </ScrollView>
    );
  }

  return (
    <ScrollView style={shared.screen} contentContainerStyle={shared.content} testID="qi-disposition-execute-screen">
      <View style={shared.card}>
        <Text style={shared.cardTitle}>{batchTitle}</Text>
        <Text style={shared.cardSub}>{candidate.productName || '未登记品名'}</Text>
        <Text style={shared.cardSub}>
          质检结果：{resultText(candidate.result)} · 抽检 {Number(candidate.sampleSize ?? 0)}，合格 {Number(candidate.passCount ?? 0)}，不合格 {Number(candidate.failCount ?? 0)}
        </Text>
        {candidate.notes ? <Text style={shared.cardSub}>质检备注：{candidate.notes}</Text> : null}
      </View>

      {loading ? <LoadingBlock text="正在读取系统建议…" /> : error ? <ErrorBlock message={error} onRetry={() => { void load(); }} /> : (
        <>
          {!canWrite && (
            <View style={[shared.card, styles.warnCard]} testID="qi-disposition-readonly">
              <Text style={styles.warnText}>你的账号只能查看，不能提交处置。需要处置请联系质量负责人开通质检写权限。</Text>
            </View>
          )}

          {evaluation && (
            <View style={[shared.card, styles.suggestCard]} testID="qi-disposition-suggestion">
              <Text style={styles.suggestLabel}>系统建议</Text>
              <Text style={[shared.cardTitle, { color: ACTION_TEXT[evaluation.recommendedAction]?.color ?? QI_COLORS.text }]}>
                {actionLabel(evaluation.recommendedAction)}
              </Text>
              <Text style={shared.cardSub}>
                {/[一-鿿]/.test(evaluation.reason || '') ? evaluation.reason : '根据本次质检结果给出，请结合现场情况确认。'}
              </Text>
            </View>
          )}

          {canWrite && (
            <>
              <Text style={shared.sectionTitle}>1. 选择处置方式</Text>
              {actionInfo.map((a) => {
                const meta = ACTION_TEXT[a.actionCode];
                const active = selected === a.actionCode;
                return (
                  <TouchableRipple
                    key={a.actionCode}
                    style={[styles.option, active && { borderColor: meta?.color ?? QI_COLORS.primary, backgroundColor: '#F7FFFB' }]}
                    onPress={() => pick(a.actionCode)}
                    accessibilityRole="radio"
                    accessibilityState={{ selected: active }}
                    testID={`qi-disposition-action-${a.actionCode}`}
                  >
                    <View style={shared.row}>
                      <Ionicons
                        name={active ? 'radio-button-on' : 'radio-button-off'}
                        size={24}
                        color={active ? meta?.color ?? QI_COLORS.primary : QI_COLORS.textSecondary}
                      />
                      <View style={{ flex: 1, marginLeft: 12 }}>
                        <Text style={[styles.optionTitle, { color: meta?.color ?? QI_COLORS.text }]}>
                          {meta?.label ?? a.actionName}
                          {evaluation?.recommendedAction === a.actionCode ? '（建议）' : ''}
                        </Text>
                        <Text style={shared.note}>{meta?.hint ?? a.description}</Text>
                        {a.requiresApproval && (
                          <Text style={styles.approvalTag}>需要{approverText(a.approvalLevel)}审批</Text>
                        )}
                      </View>
                    </View>
                  </TouchableRipple>
                );
              })}

              {needsReason && (
                <>
                  <Text style={shared.sectionTitle}>2. 选择原因</Text>
                  <View style={styles.chips}>
                    {reasonOptions.map((r) => (
                      <TouchableRipple
                        key={r}
                        style={[styles.chip, reason === r && styles.chipActive]}
                        onPress={() => setReason(r)}
                        accessibilityRole="radio"
                        accessibilityState={{ selected: reason === r }}
                        testID={`qi-disposition-reason-${r}`}
                      >
                        <Text style={[styles.chipText, reason === r && styles.chipTextActive]}>{r}</Text>
                      </TouchableRipple>
                    ))}
                  </View>
                  {reason !== '' && (
                    <TextInput
                      style={styles.input}
                      value={otherText}
                      onChangeText={setOtherText}
                      placeholder={reason === '其他' ? '请写明原因（必填）' : '补充说明（可不填）'}
                      multiline
                      testID="qi-disposition-reason-text"
                    />
                  )}
                </>
              )}

              <Button
                mode="contained"
                buttonColor={QI_COLORS.primary}
                style={[shared.primaryButton, { marginTop: 18 }]}
                contentStyle={shared.primaryButtonContent}
                disabled={!canSubmit}
                loading={submitting}
                onPress={onSubmit}
                testID="qi-disposition-submit"
              >
                {selected ? `提交：${actionLabel(selected)}` : '请先选择处置方式'}
              </Button>
              {needsReason && !finalReason && (
                <Text style={[shared.note, { textAlign: 'center', marginTop: 8 }]}>选好原因后才能提交</Text>
              )}
            </>
          )}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  warnCard: { backgroundColor: '#FFF8E6' },
  warnText: { color: '#8A5A00', fontSize: 14, lineHeight: 20 },
  suggestCard: { borderLeftWidth: 4, borderLeftColor: QI_COLORS.primary },
  suggestLabel: { fontSize: 13, color: QI_COLORS.textSecondary, marginBottom: 4 },
  option: {
    minHeight: 64,
    padding: 14,
    marginBottom: 10,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
  },
  optionTitle: { fontSize: 17, fontWeight: '800' },
  approvalTag: { marginTop: 4, fontSize: 12, fontWeight: '700', color: '#8A5A00' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 10 },
  chip: {
    minHeight: 44,
    paddingHorizontal: 14,
    justifyContent: 'center',
    borderRadius: 22,
    borderWidth: 1.5,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
  },
  chipActive: { borderColor: QI_COLORS.primary, backgroundColor: '#E8F8F0' },
  chipText: { fontSize: 15, color: QI_COLORS.text },
  chipTextActive: { color: QI_COLORS.primary, fontWeight: '700' },
  input: {
    marginTop: 12,
    minHeight: 72,
    padding: 12,
    fontSize: 16,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
    textAlignVertical: 'top',
  },
});
