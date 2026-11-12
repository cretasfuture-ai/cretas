/**
 * 质检员 App · 召回详情: 看每家客户出货 / 已退 / 待退、是否已联系; 记录客户联系、登记退货、结案。
 * 能否结案、卡在哪里由后端 canClose / blockers 给出, App 只展示 (UX F9: 不满足时按钮禁用并列出原因)。
 * 写按钮按 quality:read_write 判, 与后端同一门槛。
 */
import React, { useCallback, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { Button } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRoute, type RouteProp } from '@react-navigation/native';

import { appAlert, appPrompt } from '../../components/ui/AppDialog';
import {
  isRecallClosed,
  qualityRecallAPI,
  recallStatusText,
  type FactoryRecallDetail,
  type RecallShipmentSummary,
} from '../../services/api/qualityRecallApiClient';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { canWriteQualityClosure } from '../../utils/qualityClosurePermissions';
import { ErrorBlock, LoadingBlock, shared } from './qualityClosureShared';

type Route = RouteProp<QualityInspectorStackParamList, 'QIRecallDetail'>;

/** 退货数量是否在可退范围内 (fool-proof Rule 1: 上限先显示, 超了提交按钮禁用)。 */
export function returnQuantityError(input: string, remaining: number): string {
  const text = input.trim();
  if (!text) return '请填写退货数量';
  const n = Number(text);
  if (!Number.isFinite(n) || n <= 0) return '数量要大于 0';
  if (n > remaining) return `最多还能退 ${remaining}`;
  return '';
}

export default function QIRecallDetailScreen() {
  const { recallId } = useRoute<Route>().params;
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canWrite = canWriteQualityClosure(user);

  const [detail, setDetail] = useState<FactoryRecallDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [returnFor, setReturnFor] = useState<string | null>(null);
  const [returnQty, setReturnQty] = useState('');

  const load = useCallback(async (mode: 'initial' | 'refresh' = 'initial') => {
    if (!factoryId) {
      setError('读不到当前工厂，请退出后重新登录');
      setLoading(false);
      return;
    }
    if (mode === 'refresh') setRefreshing(true); else setLoading(true);
    try {
      setDetail(await qualityRecallAPI.get(factoryId, recallId));
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '召回详情读取失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId, recallId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const run = async (label: string, fn: () => Promise<unknown>, okText: string) => {
    setBusy(true);
    try {
      await fn();
      appAlert(okText);
      setReturnFor(null);
      setReturnQty('');
      await load('refresh');
    } catch (e) {
      appAlert(`${label}没有成功`, `${getErrorMsg(e)}\n\n请确认后重试。`);
    } finally {
      setBusy(false);
    }
  };

  const onAck = (s: RecallShipmentSummary) => {
    if (!factoryId || !detail) return;
    appPrompt(
      `记录客户联系 — ${s.customerName}`,
      `出货单 ${s.shipmentNumber}，${s.quantity} ${s.unit}。写下怎么联系的、客户怎么说。`,
      (note) => { void run('记录客户联系', () => qualityRecallAPI.acknowledge(factoryId, detail.id, { shipmentId: s.shipmentId, note }), '已记录客户联系'); },
      { placeholder: '例如：电话联系张经理，同意退回', testID: 'qi-recall-ack-note' },
    );
  };

  const onReturn = (s: RecallShipmentSummary) => {
    if (!factoryId || !detail) return;
    const qty = Number(returnQty.trim());
    appAlert(
      `登记退货 — ${s.customerName}`,
      `出货单 ${s.shipmentNumber}\n本次退回 ${qty} ${s.unit}（还能退 ${s.remainingReturnQuantity} ${s.unit}）`,
      [
        { text: '再看看', style: 'cancel' },
        {
          text: '确认登记',
          onPress: () => {
            void run('登记退货', () => qualityRecallAPI.createReturn(factoryId, detail.id, {
              shipmentId: s.shipmentId,
              quantity: qty,
              reason: `召回退货：${detail.reason}`,
            }), '已登记退货');
          },
        },
      ],
    );
  };

  const onClose = () => {
    if (!factoryId || !detail) return;
    appPrompt(
      `召回结案 — ${detail.batchNumber}`,
      '结案后不能再登记这次召回的客户联系和退货。请写一句结案说明。',
      (note) => { void run('结案', () => qualityRecallAPI.close(factoryId, detail.id, note), '召回已结案'); },
      { placeholder: '例如：客户已全部联系、退货已入库', testID: 'qi-recall-close-note' },
    );
  };

  if (loading) return <View style={shared.screen}><LoadingBlock /></View>;
  if (error || !detail) {
    return <View style={shared.screen}><ErrorBlock message={error || '召回详情读取失败'} onRetry={() => { void load(); }} /></View>;
  }

  const closed = isRecallClosed(detail.status);

  return (
    <ScrollView
      style={shared.screen}
      contentContainerStyle={shared.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { void load('refresh'); }} />}
      testID="qi-recall-detail-screen"
    >
      <View style={shared.card}>
        <View style={shared.titleRow}>
          <Text style={[shared.cardTitle, shared.titleInRow]}>{detail.batchNumber}</Text>
          <View style={[shared.tag, shared.tagInRow, { backgroundColor: closed ? '#EDF7EE' : '#FFF1E6' }]}>
            <Text style={[shared.tagText, { color: closed ? '#1B8A3A' : '#C05621' }]}>{recallStatusText(detail.status)}</Text>
          </View>
        </View>
        <Text style={shared.cardSub}>{detail.productName} · 召回单 {detail.eventCode}</Text>
        <Text style={shared.cardSub}>原因：{detail.reason}</Text>
        <Text style={shared.cardSub}>厂内剩余库存：{detail.remainingInventory} {detail.unit}</Text>
      </View>

      <Text style={shared.sectionTitle}>出货客户（{detail.shipments.length} 家）</Text>
      {detail.shipments.length === 0 && <Text style={shared.note}>这批没有出货记录，不需要联系客户。</Text>}
      {detail.shipments.map((s) => {
        const qtyError = returnFor === s.shipmentId ? returnQuantityError(returnQty, s.remainingReturnQuantity) : '';
        return (
          <View key={s.shipmentId} style={shared.card} testID={`qi-recall-shipment-${s.shipmentId}`}>
            <View style={[shared.row, { justifyContent: 'space-between' }]}>
              <Text style={[shared.cardTitle, { fontSize: 17, flex: 1 }]} numberOfLines={1}>{s.customerName}</Text>
              <View style={shared.row}>
                <Ionicons
                  name={s.acknowledged ? 'checkmark-circle' : 'alert-circle-outline'}
                  size={18}
                  color={s.acknowledged ? '#1B8A3A' : '#C05621'}
                />
                <Text style={{ marginLeft: 4, color: s.acknowledged ? '#1B8A3A' : '#C05621', fontWeight: '700' }}>
                  {s.acknowledged ? '已联系' : '未联系'}
                </Text>
              </View>
            </View>
            <Text style={shared.cardSub}>出货单 {s.shipmentNumber}</Text>
            <Text style={shared.cardSub}>
              出货 {s.quantity} {s.unit} · 已退 {s.returnedQuantity} · 退货在途 {s.pendingReturnQuantity} · 还能退 {s.remainingReturnQuantity}
            </Text>

            {canWrite && !closed && (
              <View style={[shared.row, { marginTop: 12, gap: 10, flexWrap: 'wrap' }]}>
                {!s.acknowledged && (
                  <Button mode="outlined" disabled={busy} onPress={() => onAck(s)} style={styles.smallBtn} testID={`qi-recall-ack-${s.shipmentId}`}>
                    记录客户联系
                  </Button>
                )}
                {s.remainingReturnQuantity > 0 && returnFor !== s.shipmentId && (
                  <Button
                    mode="outlined"
                    disabled={busy}
                    onPress={() => { setReturnFor(s.shipmentId); setReturnQty(''); }}
                    style={styles.smallBtn}
                    testID={`qi-recall-return-open-${s.shipmentId}`}
                  >
                    登记退货
                  </Button>
                )}
              </View>
            )}

            {returnFor === s.shipmentId && (
              <View style={styles.returnBox} testID={`qi-recall-return-box-${s.shipmentId}`}>
                <Text style={styles.limitText}>出货 {s.quantity}，已退 {s.returnedQuantity + s.pendingReturnQuantity}，还能退 {s.remainingReturnQuantity} {s.unit}</Text>
                <TextInput
                  style={styles.qtyInput}
                  value={returnQty}
                  onChangeText={setReturnQty}
                  keyboardType="numeric"
                  placeholder={`最多 ${s.remainingReturnQuantity}`}
                  testID={`qi-recall-return-qty-${s.shipmentId}`}
                />
                {qtyError ? <Text style={styles.errText}>{qtyError}</Text> : null}
                <View style={[shared.row, { gap: 10, marginTop: 10 }]}>
                  <Button mode="text" onPress={() => setReturnFor(null)} disabled={busy}>取消</Button>
                  <Button
                    mode="contained"
                    buttonColor={QI_COLORS.primary}
                    disabled={!!qtyError || busy}
                    loading={busy}
                    onPress={() => onReturn(s)}
                    testID={`qi-recall-return-submit-${s.shipmentId}`}
                  >
                    确认退货数量
                  </Button>
                </View>
              </View>
            )}
          </View>
        );
      })}

      {!closed && (
        <>
          <Text style={shared.sectionTitle}>结案</Text>
          {detail.blockers.length > 0 && (
            <View style={[shared.card, { backgroundColor: '#FFF8E6' }]} testID="qi-recall-blockers">
              <Text style={{ fontWeight: '700', color: '#8A5A00', marginBottom: 4 }}>还不能结案，因为：</Text>
              {detail.blockers.map((b, i) => (
                <Text key={`${b.code}-${i}`} style={{ color: '#8A5A00', lineHeight: 20 }}>· {b.message}</Text>
              ))}
            </View>
          )}
          {canWrite && (
            <Button
              mode="contained"
              buttonColor={QI_COLORS.primary}
              style={shared.primaryButton}
              contentStyle={shared.primaryButtonContent}
              disabled={!detail.canClose || busy}
              onPress={onClose}
              testID="qi-recall-close"
            >
              确认结案
            </Button>
          )}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  smallBtn: { borderRadius: 10 },
  returnBox: { marginTop: 12, padding: 12, borderRadius: 12, backgroundColor: '#F5F9FF' },
  limitText: { fontSize: 14, fontWeight: '700', color: QI_COLORS.secondary },
  qtyInput: {
    marginTop: 8,
    minHeight: 52,
    paddingHorizontal: 12,
    fontSize: 24,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: QI_COLORS.border,
    backgroundColor: QI_COLORS.card,
  },
  errText: { marginTop: 6, color: QI_COLORS.danger, fontSize: 13 },
});
