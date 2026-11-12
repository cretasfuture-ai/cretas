/**
 * 质检员 App · 超时处置 (单条): 复检放行 / 判不合格, 结论必填。
 *
 * 照网页 ProcessTimeoutEventsTab.vue 的处置对话框: 处置结果不设默认值 (放行是食品安全决定, 不让人顺手点确认);
 * 选了就写清后果; 常用结论一点即填。POST /process-time-limits/events/{id}/decision (quality:read_write)。
 */
import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button, TouchableRipple } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, type RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { appAlert } from '../../components/ui/AppDialog';
import { processTimeLimitApi, type ProcessTimeLimitDecision } from '../../services/api/processTimeLimitApi';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { formatDuration } from '../../utils/processTimeLimit';
import { canDecideTimeoutEvent } from '../../utils/qualityClosurePermissions';
import { shared } from './qualityClosureShared';
import {
  appendPreset,
  decisionConsequence,
  decisionLabel,
  formatBackendTime,
  overdueText,
  presetsFor,
  startText,
  submitBlockedReason,
  timeoutQuantity,
} from './qiTimeoutView';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;
type Route = RouteProp<QualityInspectorStackParamList, 'QITimeoutDecision'>;

export default function QITimeoutDecisionScreen() {
  const navigation = useNavigation<Nav>();
  const { event } = useRoute<Route>().params;
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canDecide = canDecideTimeoutEvent(user);
  const [openedAt] = useState(Date.now());

  const [decision, setDecision] = useState<ProcessTimeLimitDecision | null>(null);
  const [conclusion, setConclusion] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const batch = event.batchNumber || '批次号缺失';
  const blocked = submitBlockedReason(decision, conclusion);
  const consequence = decisionConsequence(event, decision);

  const goBackToList = () => navigation.goBack();

  const doSubmit = async () => {
    if (!factoryId || !decision || blocked) return;
    setSubmitting(true);
    try {
      const res = await processTimeLimitApi.decide(event.id, { decision, conclusion: conclusion.trim() }, factoryId);
      if (!res?.success) throw new Error(res?.message || '处置没有成功');
      appAlert(`${res.message || '已处置'} ${event.batchNumber ?? ''}`.trim(), undefined, [{ text: '好', onPress: goBackToList }]);
    } catch (e) {
      // 别人已经处置过: 这条不在待处置清单里了, 留在表单上只会再失败一次 (UX T4)
      let stillOpen = true;
      try {
        const open = await processTimeLimitApi.listOpenEvents(factoryId);
        stillOpen = !open?.success || (open.data ?? []).some((x) => x.id === event.id);
      } catch {
        stillOpen = true;
      }
      appAlert(
        '处置没有提交成功',
        `${getErrorMsg(e)}${stillOpen ? '\n\n你选的内容还在，确认后可以再提交。' : '\n\n这条已经被处理过了，返回清单查看最新情况。'}`,
        stillOpen ? undefined : [{ text: '返回清单', onPress: goBackToList }],
      );
    } finally {
      setSubmitting(false);
    }
  };

  const onSubmit = () => {
    if (!decision || blocked) return;
    appAlert(
      `${decisionLabel(decision)} — ${batch}`,
      `工序：${event.processName || '—'}\n超时数量：${timeoutQuantity(event)}\n${consequence}\n复检结论：${conclusion.trim()}`,
      [
        { text: '再看看', style: 'cancel' },
        { text: `确认${decisionLabel(decision)}`, style: decision === 'REJECTED' ? 'destructive' : 'default', onPress: () => { void doSubmit(); } },
      ],
    );
  };

  return (
    <ScrollView style={shared.screen} contentContainerStyle={shared.content} keyboardShouldPersistTaps="handled" testID="qi-timeout-decision-screen">
      <View style={[shared.card, styles.headCard]}>
        <Text style={shared.cardTitle}>{batch}</Text>
        <Text style={shared.cardSub}>工序：{event.processName || '—'} · 时限 {formatDuration(event.limitMinutes)}</Text>
        <Text style={shared.cardSub}>起算：{startText(event)}</Text>
        <Text style={shared.cardSub}>截止：{formatBackendTime(event.deadlineAt)} · 已超时 {overdueText(event, openedAt)}</Text>
        <Text style={[shared.cardSub, styles.qty]}>超时数量：{timeoutQuantity(event)}</Text>
      </View>

      {!canDecide ? (
        <View style={[shared.card, styles.warnCard]} testID="qi-timeout-decision-readonly">
          <Text style={styles.warnText}>你的账号只能查看。复检放行或判不合格需要质检写权限，请联系质量负责人开通。</Text>
        </View>
      ) : (
        <>
          <Text style={shared.sectionTitle}>1. 处置结果</Text>
          <View style={styles.choiceRow}>
            {(['RELEASED', 'REJECTED'] as const).map((d) => {
              const active = decision === d;
              const color = d === 'RELEASED' ? QI_COLORS.primary : QI_COLORS.danger;
              return (
                <TouchableRipple
                  key={d}
                  style={[styles.choice, active && { borderColor: color, backgroundColor: d === 'RELEASED' ? '#E8F8F0' : '#FDECEC' }]}
                  onPress={() => setDecision(d)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected: active }}
                  testID={`qi-timeout-decision-${d}`}
                >
                  <View style={styles.choiceInner}>
                    <Ionicons
                      name={d === 'RELEASED' ? 'checkmark-circle-outline' : 'close-circle-outline'}
                      size={28}
                      color={active ? color : QI_COLORS.textSecondary}
                    />
                    <Text style={[styles.choiceText, active && { color }]}>{decisionLabel(d)}</Text>
                  </View>
                </TouchableRipple>
              );
            })}
          </View>
          {consequence ? <Text style={styles.consequence} testID="qi-timeout-consequence">{consequence}</Text> : null}

          {decision && (
            <>
              <Text style={shared.sectionTitle}>2. 复检结论（必填）</Text>
              <View style={styles.chips}>
                {presetsFor(decision).map((p) => (
                  <TouchableRipple
                    key={p}
                    style={styles.chip}
                    onPress={() => setConclusion((c) => appendPreset(c, p))}
                    accessibilityRole="button"
                    testID={`qi-timeout-preset-${p}`}
                  >
                    <Text style={styles.chipText}>{p}</Text>
                  </TouchableRipple>
                ))}
              </View>
              <TextInput
                style={styles.input}
                value={conclusion}
                onChangeText={setConclusion}
                placeholder="例如：感官正常，中心温度复测 4℃ 合格"
                maxLength={2000}
                multiline
                testID="qi-timeout-conclusion"
              />
            </>
          )}

          <Button
            mode="contained"
            buttonColor={decision === 'REJECTED' ? QI_COLORS.danger : QI_COLORS.primary}
            style={[shared.primaryButton, { marginTop: 18 }]}
            contentStyle={shared.primaryButtonContent}
            disabled={!!blocked || submitting}
            loading={submitting}
            onPress={onSubmit}
            testID="qi-timeout-submit"
          >
            {decision ? `确认${decisionLabel(decision)}` : '确认处置'}
          </Button>
          {blocked ? <Text style={[shared.note, { textAlign: 'center', marginTop: 8 }]} testID="qi-timeout-blocked-reason">{blocked}</Text> : null}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  headCard: { borderLeftWidth: 4, borderLeftColor: QI_COLORS.danger },
  qty: { fontWeight: '700', color: QI_COLORS.text },
  warnCard: { backgroundColor: '#FFF8E6' },
  warnText: { color: '#8A5A00', fontSize: 14, lineHeight: 20 },
  choiceRow: { flexDirection: 'row', gap: 12 },
  choice: {
    flex: 1,
    minHeight: 72,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
    justifyContent: 'center',
  },
  choiceInner: { alignItems: 'center', paddingVertical: 10 },
  choiceText: { marginTop: 4, fontSize: 17, fontWeight: '800', color: QI_COLORS.text },
  consequence: { marginTop: 10, fontSize: 14, lineHeight: 20, color: '#8A5A00', fontWeight: '700' },
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
  chipText: { fontSize: 15, color: QI_COLORS.text },
  input: {
    marginTop: 12,
    minHeight: 80,
    padding: 12,
    fontSize: 16,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
    textAlignVertical: 'top',
  },
});
