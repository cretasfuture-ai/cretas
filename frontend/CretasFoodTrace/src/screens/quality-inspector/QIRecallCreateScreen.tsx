/**
 * 质检员 App · 发起批次召回: 选批次 → 看服务端预览 (库存 / 出货客户) → 选原因 → 确认。
 * 召回范围与剩余成品隔离由后端 POST /quality-recalls 计算并执行; 原因选项与网页一致。
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button, TouchableRipple } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { appAlert } from '../../components/ui/AppDialog';
import {
  qualityRecallAPI,
  type RecallBatchContext,
  type RecallBatchOption,
} from '../../services/api/qualityRecallApiClient';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { canWriteQualityClosure } from '../../utils/qualityClosurePermissions';
import { EmptyBlock, ErrorBlock, LoadingBlock, shared } from './qualityClosureShared';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;

/** 与网页 recalls/index.vue reasonOptions 同一份。 */
const RECALL_REASONS = ['产品质量异常', '标签或批次信息错误', '客户投诉', '其他'];

export default function QIRecallCreateScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canWrite = canWriteQualityClosure(user);

  const [batches, setBatches] = useState<RecallBatchOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [picked, setPicked] = useState<RecallBatchOption | null>(null);
  const [preview, setPreview] = useState<RecallBatchContext | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [reason, setReason] = useState('');
  const [extra, setExtra] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadBatches = useCallback(async () => {
    if (!factoryId) {
      setError('读不到当前工厂，请退出后重新登录');
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      setBatches(await qualityRecallAPI.listBatchOptions(factoryId));
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '生产批次读取失败');
    } finally {
      setLoading(false);
    }
  }, [factoryId]);

  useEffect(() => { void loadBatches(); }, [loadBatches]);

  const loadPreview = useCallback(async (batch: RecallBatchOption) => {
    if (!factoryId) return;
    setPicked(batch);
    setPreview(null);
    setPreviewError('');
    setPreviewLoading(true);
    try {
      setPreview(await qualityRecallAPI.preview(factoryId, Number(batch.id)));
    } catch (e) {
      setPreviewError(getErrorMsg(e) || '批次召回预览读取失败');
    } finally {
      setPreviewLoading(false);
    }
  }, [factoryId]);

  const filtered = useMemo(() => {
    const k = keyword.trim().toLowerCase();
    if (!k) return batches.slice(0, 50);
    return batches.filter((b) => `${b.batchNumber ?? ''} ${b.productName ?? ''}`.toLowerCase().includes(k)).slice(0, 50);
  }, [batches, keyword]);

  const finalReason = reason === '其他' ? extra.trim() : [reason, extra.trim()].filter(Boolean).join('：');
  const canSubmit = canWrite && !!preview && finalReason.length > 0 && !submitting;

  const doCreate = async () => {
    if (!factoryId || !preview) return;
    setSubmitting(true);
    try {
      const created = await qualityRecallAPI.create(factoryId, { batchId: preview.batchId, reason: finalReason });
      navigation.replace('QIRecallDetail', { recallId: String(created.id) });
    } catch (e) {
      appAlert('召回没有发起成功', `${getErrorMsg(e)}\n\n你选的内容还在，确认后可以再试。`);
    } finally {
      setSubmitting(false);
    }
  };

  const onSubmit = () => {
    if (!canSubmit || !preview) return;
    const shipped = preview.shipments.length;
    appAlert(
      `确认召回 — ${preview.batchNumber}`,
      `${preview.productName}\n剩余库存 ${preview.remainingInventory} ${preview.unit} 会被隔离；`
        + `${shipped > 0 ? `已出货给 ${shipped} 家客户，之后要逐家记录联系。` : '这批还没有出货记录。'}\n原因：${finalReason}`,
      [
        { text: '再看看', style: 'cancel' },
        { text: '确认召回', style: 'destructive', onPress: () => { void doCreate(); } },
      ],
    );
  };

  if (!canWrite) {
    return (
      <View style={shared.screen} testID="qi-recall-create-forbidden">
        <EmptyBlock text="你的账号不能发起召回" hint="需要质检写权限，请联系质量负责人开通。" actionText="返回" onAction={() => navigation.goBack()} />
      </View>
    );
  }

  return (
    <ScrollView style={shared.screen} contentContainerStyle={shared.content} keyboardShouldPersistTaps="handled" testID="qi-recall-create-screen">
      <Text style={shared.sectionTitle}>1. 选择要召回的批次</Text>
      {picked ? (
        <View style={[shared.card, styles.picked]}>
          <Text style={shared.cardTitle}>{picked.batchNumber || `批次 #${picked.id}`}</Text>
          <Text style={shared.cardSub}>{picked.productName || ''}</Text>
          <Button mode="text" textColor={QI_COLORS.secondary} onPress={() => { setPicked(null); setPreview(null); setReason(''); setExtra(''); }} testID="qi-recall-change-batch">
            换一个批次
          </Button>
        </View>
      ) : (
        <>
          <TextInput
            style={styles.search}
            value={keyword}
            onChangeText={setKeyword}
            placeholder="输入批次号或品名查找"
            testID="qi-recall-batch-search"
          />
          {loading ? <LoadingBlock /> : error ? <ErrorBlock message={error} onRetry={() => { void loadBatches(); }} />
            : filtered.length === 0 ? <EmptyBlock text="没有找到批次" hint="换个批次号或品名试试。" />
              : filtered.map((b) => (
                <TouchableRipple
                  key={String(b.id)}
                  style={[shared.card, { paddingVertical: 12 }]}
                  onPress={() => { void loadPreview(b); }}
                  accessibilityRole="button"
                  testID={`qi-recall-batch-${b.id}`}
                >
                  <View>
                    <Text style={[shared.cardTitle, { fontSize: 16 }]}>{b.batchNumber || `批次 #${b.id}`}</Text>
                    <Text style={shared.cardSub}>{b.productName || '未登记品名'}</Text>
                  </View>
                </TouchableRipple>
              ))}
        </>
      )}

      {picked && (previewLoading ? <LoadingBlock text="正在核对库存和出货…" />
        : previewError ? <ErrorBlock message={previewError} onRetry={() => { void loadPreview(picked); }} />
          : preview ? (
            <>
              <Text style={shared.sectionTitle}>2. 核对召回范围</Text>
              <View style={shared.card} testID="qi-recall-preview">
                <Text style={shared.cardSub}>剩余库存：{preview.remainingInventory} {preview.unit}（发起后会被隔离）</Text>
                {preview.shipments.length === 0 ? (
                  <Text style={shared.cardSub}>这批还没有出货记录。</Text>
                ) : preview.shipments.map((s) => (
                  <Text key={s.shipmentId} style={shared.cardSub}>· {s.customerName}：出货 {s.quantity} {s.unit}（单号 {s.shipmentNumber}）</Text>
                ))}
              </View>

              <Text style={shared.sectionTitle}>3. 选择召回原因</Text>
              <View style={styles.chips}>
                {RECALL_REASONS.map((r) => (
                  <TouchableRipple
                    key={r}
                    style={[styles.chip, reason === r && styles.chipActive]}
                    onPress={() => setReason(r)}
                    accessibilityRole="radio"
                    accessibilityState={{ selected: reason === r }}
                    testID={`qi-recall-reason-${r}`}
                  >
                    <Text style={[styles.chipText, reason === r && styles.chipTextActive]}>{r}</Text>
                  </TouchableRipple>
                ))}
              </View>
              {reason !== '' && (
                <TextInput
                  style={styles.input}
                  value={extra}
                  onChangeText={setExtra}
                  placeholder={reason === '其他' ? '请写明原因（必填）' : '补充说明（可不填）'}
                  multiline
                  testID="qi-recall-reason-text"
                />
              )}
              <Button
                mode="contained"
                buttonColor={QI_COLORS.danger}
                style={[shared.primaryButton, { marginTop: 18 }]}
                contentStyle={shared.primaryButtonContent}
                disabled={!canSubmit}
                loading={submitting}
                onPress={onSubmit}
                testID="qi-recall-submit"
              >
                发起召回并隔离剩余成品
              </Button>
              {!finalReason && <Text style={[shared.note, { textAlign: 'center', marginTop: 8 }]}>选好原因后才能发起</Text>}
            </>
          ) : null)}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  picked: { borderLeftWidth: 4, borderLeftColor: QI_COLORS.danger },
  search: {
    minHeight: 48,
    marginBottom: 12,
    paddingHorizontal: 14,
    fontSize: 16,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
  },
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
  chipActive: { borderColor: QI_COLORS.danger, backgroundColor: '#FDECEC' },
  chipText: { fontSize: 15, color: QI_COLORS.text },
  chipTextActive: { color: QI_COLORS.danger, fontWeight: '700' },
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
