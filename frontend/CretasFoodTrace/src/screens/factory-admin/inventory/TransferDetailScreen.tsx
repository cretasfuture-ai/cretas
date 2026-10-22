import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, DataTable, Divider, TextInput } from 'react-native-paper';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { transferApiClient, InternalTransfer } from '../../../services/api/transferApiClient';
import { useAuthStore } from '../../../store/authStore';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { createWarehouseDeliveryUuid, getOrCreateWarehouseDeliveryRequestKey, loadWarehouseDeliveryPending, persistResolvedWarehouseDelivery, saveWarehouseDeliveryPending, validateWarehouseReceipt, warehouseDeliveryMatchesPending, type DeliveryQuantity, type WarehouseDeliveryPending } from '../../../utils/warehouseTransferDelivery';
import { hasModulePermission } from '../../../utils/permissionHelper';
import type { WarehouseTransferDelivery } from '../../../utils/warehouseTransferDelivery';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  REQUESTED: { label: '已申请', color: '#e6a23c' },
  APPROVED: { label: '已批准', color: '#409eff' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
  SHIPPED: { label: '已发运', color: '#e6a23c' },
  RECEIVED: { label: '已签收', color: '#409eff' },
  CONFIRMED: { label: '已确认', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
};

const STEPS = ['草稿', '已申请', '已批准', '已发运', '已签收', '已确认'];
const STEP_KEYS = ['DRAFT', 'REQUESTED', 'APPROVED', 'SHIPPED', 'RECEIVED', 'CONFIRMED'];

export default function TransferDetailScreen() {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProp<FAManagementStackParamList, 'TransferDetail'>>();
  const { transferId } = route.params;
  const { user } = useAuthStore();
  const factoryId = user?.factoryId || user?.factoryUser?.factoryId || '';

  const [transfer, setTransfer] = useState<InternalTransfer | null>(null);
  const [loading, setLoading] = useState(true);
  const [delivery, setDelivery] = useState<WarehouseTransferDelivery | null>(null);
  const [deliveryLoading, setDeliveryLoading] = useState(false);
  const [receiptQuantities, setReceiptQuantities] = useState<Record<number, number>>({});
  const [submittingDelivery, setSubmittingDelivery] = useState(false);
  const [requestKey, setRequestKey] = useState<string | null>(null);
  const [deliveryUnknown, setDeliveryUnknown] = useState(false);
  const [pendingDelivery, setPendingDelivery] = useState<WarehouseDeliveryPending | null>(null);
  const canWriteWarehouse = hasModulePermission(user, 'warehouse', 'write');

  useEffect(() => { loadTransfer(); }, [transferId]);
  useEffect(() => { (async () => { try { const uid = String(user?.id || 'unknown'); const pending = await loadWarehouseDeliveryPending(factoryId, uid, transferId); if (pending) { setRequestKey(pending.requestKey); if (pending.state !== 'resolved') { setPendingDelivery(pending); setReceiptQuantities(pending.itemQuantities); setDeliveryUnknown(pending.state === 'unknown'); } } else { const next = await getOrCreateWarehouseDeliveryRequestKey(factoryId, uid, transferId); if (next) setRequestKey(next); else Alert.alert('无法保存收货请求', '本机存储不可用，已阻止仓内收货。'); } } catch (error) { Alert.alert('无法恢复收货请求', error instanceof Error ? error.message : '本机存储不可用，已阻止仓内收货。'); } })(); }, [factoryId, transferId, user?.id]);

  const loadTransfer = async () => {
    try {
      setLoading(true);
      const res = await transferApiClient.getTransfer(transferId);
      if (res.success) setTransfer(res.data);
    } catch { Alert.alert('错误', '加载失败'); }
    finally { setLoading(false); }
  };

  const loadDelivery = async () => {
    try { setDeliveryLoading(true); const res = await transferApiClient.getWarehouseDelivery(transferId, factoryId); if (res.success) { setDelivery(res.data); if (pendingDelivery && requestKey && warehouseDeliveryMatchesPending(res.data, pendingDelivery)) { const next = createWarehouseDeliveryUuid(); if (await persistResolvedWarehouseDelivery(pendingDelivery, next)) { setPendingDelivery(null); setDeliveryUnknown(false); setReceiptQuantities({}); setRequestKey(next); } else { setDeliveryUnknown(true); Alert.alert('收货结果已核对但本机未完成安全轮换', '请保持原请求号并重试刷新。'); } } } }
    catch (error) { setDeliveryUnknown(true); Alert.alert('仓内交付状态读取失败', `${error instanceof Error ? error.message : '请刷新重试'}\n请刷新页面查看真实状态。`); }
    finally { setDeliveryLoading(false); }
  };
  useEffect(() => { loadDelivery(); }, [transferId, factoryId, pendingDelivery, requestKey]);
  const refreshAll = async () => { await Promise.all([loadTransfer(), loadDelivery()]); };
  const dispatchWarehouse = async () => { if (!canWriteWarehouse || submittingDelivery) return; setSubmittingDelivery(true); try { const res = await transferApiClient.dispatchWarehouseTransfer(transferId, factoryId); if (res.success) { await refreshAll(); Alert.alert('成功', '仓内发运已记录'); } else Alert.alert('仓内发运未完成', res.message || '后端拒绝了仓内发运，请检查权限和批次。'); } catch (error) { Alert.alert('仓内发运失败', error instanceof Error ? error.message : '请刷新后重试'); } finally { setSubmittingDelivery(false); } };
  const receiveWarehouse = async () => { if (!canWriteWarehouse || !delivery || !requestKey || submittingDelivery) return; const rows: DeliveryQuantity[] = delivery.items.map(item => ({ itemId: item.id, unit: item.unit, pendingQuantity: item.pendingQuantity, quantity: receiptQuantities[item.id] || 0 })); const checked = validateWarehouseReceipt(rows); if (!checked.valid) { Alert.alert('请检查收货数量', checked.reason || '至少填写一行本次收货数量'); return; } if (pendingDelivery && JSON.stringify(pendingDelivery.itemQuantities) !== JSON.stringify(checked.quantities)) { Alert.alert('待核请求不可修改', '请保持原数量和请求号，先刷新核对结果。'); return; } const envelope: WarehouseDeliveryPending = { factoryId, userId: String(user?.id || 'unknown'), transferId, requestKey, itemQuantities: checked.quantities, state: 'pending' }; if (!(await saveWarehouseDeliveryPending(envelope))) { Alert.alert('无法保存收货请求', '本机存储不可用，已阻止发送以避免重复收货。'); return; } setPendingDelivery(envelope); setSubmittingDelivery(true); try { const res = await transferApiClient.receiveWarehouseTransfer(transferId, { requestKey, itemQuantities: checked.quantities }, factoryId); if (!res.success) { await saveWarehouseDeliveryPending({ ...envelope, state: 'unknown' }); setDeliveryUnknown(true); Alert.alert('收货未完成', res.message || '后端拒绝了收货，请刷新查看真实状态。'); return; } setDelivery(res.data); if (!warehouseDeliveryMatchesPending(res.data, envelope)) { await saveWarehouseDeliveryPending({ ...envelope, state: 'unknown' }); setDeliveryUnknown(true); Alert.alert('收货结果未知', '请刷新核对相同请求，确认前不会更换请求号。'); return; } const next = createWarehouseDeliveryUuid(); if (!(await persistResolvedWarehouseDelivery(envelope, next))) { setDeliveryUnknown(true); Alert.alert('收货已提交但本机未完成安全轮换', '请刷新核对结果后再继续。'); return; } setPendingDelivery(null); setReceiptQuantities({}); setDeliveryUnknown(false); setRequestKey(next); Alert.alert('成功', '本次收货已记录'); } catch (error) { await saveWarehouseDeliveryPending({ ...envelope, state: 'unknown' }); setPendingDelivery(envelope); setDeliveryUnknown(true); await loadDelivery(); Alert.alert('收货结果未知', `${error instanceof Error ? error.message : '网络请求失败'}\n请刷新查看真实状态。`); } finally { setSubmittingDelivery(false); } };

  const handleAction = async (action: string) => {
    const labels: Record<string, string> = {
      request: '提交申请', approve: '审批通过', reject: '驳回',
      ship: '确认发运', receive: '确认签收', confirm: '确认入库', cancel: '取消',
    };
    Alert.alert('确认', `确认${labels[action]}？`, [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: async () => {
        try {
          const actionMap: Record<string, (id: string) => Promise<{ success: boolean; data: InternalTransfer }>> = {
            request: (id) => transferApiClient.requestTransfer(id),
            approve: (id) => transferApiClient.approveTransfer(id),
            reject: (id) => transferApiClient.rejectTransfer(id),
            ship: (id) => transferApiClient.shipTransfer(id),
            receive: (id) => transferApiClient.receiveTransfer(id),
            confirm: (id) => transferApiClient.confirmTransfer(id),
            cancel: (id) => transferApiClient.cancelTransfer(id),
          };
          const fn = actionMap[action];
          if (fn) {
            const res = await fn(transferId);
            if (res?.success) { Alert.alert('成功', '操作成功'); loadTransfer(); }
          }
        } catch { Alert.alert('错误', '操作失败'); }
      }},
    ]);
  };

  if (loading) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="调拨详情" /></Appbar.Header>
      <ActivityIndicator style={styles.loader} size="large" />
    </View>
  );

  if (!transfer) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="调拨详情" /></Appbar.Header>
      <Text style={styles.empty}>调拨单不存在</Text>
    </View>
  );

  const status = STATUS_MAP[transfer.status] || { label: transfer.status, color: '#909399' };
  const isOutbound = transfer.sourceFactoryId === factoryId;
  const currentStep = STEP_KEYS.indexOf(transfer.status);

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="调拨详情" />
        <Appbar.Action testID="transfer-detail-refresh" icon="refresh" onPress={refreshAll} />
      </Appbar.Header>

      <ScrollView style={styles.scroll}>
        {/* 状态流转 */}
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.stepsRow}>
              {STEPS.map((step, idx) => (
                <View key={idx} style={styles.stepItem}>
                  <View style={[styles.stepDot, idx <= currentStep && { backgroundColor: '#409eff' }]} />
                  <Text style={[styles.stepLabel, idx <= currentStep && { color: '#409eff' }]}>{step}</Text>
                  {idx < STEPS.length - 1 && <View style={[styles.stepLine, idx < currentStep && { backgroundColor: '#409eff' }]} />}
                </View>
              ))}
            </View>
          </Card.Content>
        </Card>

        {/* 基本信息 */}
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.row}>
              <Text variant="titleMedium" style={styles.bold}>{transfer.transferNumber}</Text>
              <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
            </View>
            <Divider style={styles.divider} />
            <InfoRow label="类型" value={transfer.transferType === 'HQ_TO_BRANCH' ? '总部→分部' : transfer.transferType === 'BRANCH_TO_BRANCH' ? '分部→分部' : '分部→总部'} />
            <InfoRow label="调出方" value={transfer.sourceFactory?.name || transfer.sourceFactoryId} />
            <InfoRow label="调入方" value={transfer.targetFactory?.name || transfer.targetFactoryId} />
            <InfoRow label="总金额" value={`¥${formatNumberWithCommas(transfer.totalAmount)}`} valueStyle={styles.amountText} />
            <InfoRow label="调拨日期" value={transfer.transferDate} />
            <InfoRow label="预计到达" value={transfer.expectedArrivalDate || '-'} />
            <InfoRow label="申请人" value={transfer.requestedBy || '-'} />
            <InfoRow label="审批人" value={transfer.approvedBy || '-'} />
            {transfer.remark ? <InfoRow label="备注" value={transfer.remark} /> : null}
          </Card.Content>
        </Card>

        {/* 明细 */}
        <Card style={styles.card}>
          <Card.Title title="调拨明细" titleVariant="titleSmall" />
          <Card.Content>
            <DataTable>
              <DataTable.Header>
                <DataTable.Title>类型</DataTable.Title>
                <DataTable.Title>品名</DataTable.Title>
                <DataTable.Title numeric>数量</DataTable.Title>
                <DataTable.Title numeric>已收</DataTable.Title>
              </DataTable.Header>
              {(transfer.items || []).map((item, idx) => (
                <DataTable.Row key={idx}>
                  <DataTable.Cell>{item.itemType === 'RAW_MATERIAL' ? '原料' : '成品'}</DataTable.Cell>
                  <DataTable.Cell>{item.materialTypeName || item.productTypeName || '-'}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.quantity} {item.unit}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.receivedQuantity}</DataTable.Cell>
                </DataTable.Row>
              ))}
            </DataTable>
          </Card.Content>
        </Card>

        {/* 操作按钮 */}
        <View style={styles.actionBar}>
          {transfer.status === 'DRAFT' && (
            <>
              <Button mode="contained" onPress={() => handleAction('request')} style={styles.actionBtn}>提交申请</Button>
              <Button mode="outlined" onPress={() => handleAction('cancel')} style={styles.actionBtn}>取消</Button>
            </>
          )}
          {transfer.status === 'REQUESTED' && (
            <>
              <Button mode="contained" onPress={() => handleAction('approve')} style={styles.actionBtn}>审批通过</Button>
              <Button mode="outlined" textColor="#f56c6c" onPress={() => handleAction('reject')} style={styles.actionBtn}>驳回</Button>
            </>
          )}
          {transfer.status === 'APPROVED' && isOutbound && transfer.sourceFactoryId !== transfer.targetFactoryId && (
            <Button mode="contained" onPress={() => handleAction('ship')} style={styles.actionBtn}>确认发运</Button>
          )}
          {transfer.status === 'APPROVED' && transfer.sourceFactoryId === transfer.targetFactoryId && (
            <Button mode="outlined" onPress={() => handleAction('confirm')} style={styles.actionBtn}>原一次确认入库</Button>
          )}
          {transfer.status === 'SHIPPED' && !isOutbound && transfer.sourceFactoryId !== transfer.targetFactoryId && (
            <Button mode="contained" onPress={() => handleAction('receive')} style={styles.actionBtn}>确认签收</Button>
          )}
          {transfer.status === 'RECEIVED' && !isOutbound && transfer.sourceFactoryId !== transfer.targetFactoryId && (
            <Button mode="contained" onPress={() => handleAction('confirm')} style={styles.actionBtn}>确认入库</Button>
          )}
        </View>

        {transfer.sourceFactoryId === transfer.targetFactoryId && (
          <Card style={styles.card}>
            <Card.Title title="仓内分步交付" />
            <Card.Content>
              {deliveryLoading ? <ActivityIndicator /> : !delivery ? <Text>交付状态读取失败，请点击刷新重试。</Text> : !delivery.supported ? <Text>此单暂不支持分步交付，请使用原一次确认流程或完善批次。</Text> : <>
                {deliveryUnknown ? <Text style={styles.unknownText}>上次收货结果未知，请刷新核对相同请求；未核实前不会更换请求号。</Text> : null}<Text style={styles.contextText}>单号：{delivery.transferNumber}　阶段：{delivery.staged ? (delivery.stage === 'CONFIRMED' ? '已收齐' : '部分待收') : '待发运'}</Text>
                    {delivery.items.map(item => <View key={item.id} style={styles.deliveryRow}><Text style={styles.deliveryName}>{item.itemName} · 批次 {item.sourceBatchId}</Text><Text>已发 {item.shippedQuantity} / 已收 {item.receivedQuantity} / 待收 {item.pendingQuantity} {item.unit}</Text>{item.receipts.map(receipt => <Text key={`${receipt.operationId}-${receipt.requestKey}`}>收货批次 {receipt.targetBatchId}：{receipt.quantity} {receipt.unit}</Text>)}{delivery.staged && item.pendingQuantity > 0 ? <TextInput testID={`warehouse-delivery-qty-${item.id}`} label="本次收货" value={String(receiptQuantities[item.id] ?? '')} onChangeText={value => setReceiptQuantities(current => ({ ...current, [item.id]: Number(value) || 0 }))} keyboardType="numeric" mode="outlined" disabled={submittingDelivery} style={styles.receiptInput} /> : null}</View>)}
                    <View style={styles.actionBar}>{!delivery.staged && transfer.status === 'APPROVED' && canWriteWarehouse ? <Button mode="contained" onPress={dispatchWarehouse} loading={submittingDelivery} disabled={submittingDelivery} style={styles.actionBtn}>确认仓内发运</Button> : null}{delivery.staged && delivery.items.some(item => item.pendingQuantity > 0) && canWriteWarehouse ? <Button testID="warehouse-delivery-submit" mode="contained" onPress={receiveWarehouse} loading={submittingDelivery} disabled={submittingDelivery} style={styles.actionBtn}>提交本次收货</Button> : null}</View>
              </>}
            </Card.Content>
          </Card>
        )}

        <View style={{ height: 30 }} />
      </ScrollView>
    </View>
  );
}

function InfoRow({ label, value, valueStyle }: { label: string; value: string; valueStyle?: any }) {
  return (
    <View style={styles.infoRow}>
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={[styles.infoValue, valueStyle]}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  scroll: { flex: 1 },
  loader: { flex: 1, justifyContent: 'center' },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
  card: { margin: 12, marginBottom: 0, borderRadius: 10 },
  row: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  bold: { fontWeight: '700' },
  divider: { marginVertical: 10 },
  infoRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 4 },
  infoLabel: { color: '#909399', fontSize: 13 },
  infoValue: { fontSize: 13, color: '#333' },
  amountText: { fontWeight: '700', color: '#409eff' },
  stepsRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 8 },
  stepItem: { alignItems: 'center', flex: 1 },
  stepDot: { width: 12, height: 12, borderRadius: 6, backgroundColor: '#ddd', marginBottom: 4 },
  stepLabel: { fontSize: 10, color: '#999' },
  stepLine: { position: 'absolute', top: 5, right: -20, width: 40, height: 2, backgroundColor: '#ddd' },
  actionBar: { flexDirection: 'row', gap: 10, padding: 12 },
  actionBtn: { flex: 1, minHeight: 44 },
  contextText: { fontSize: 15, marginBottom: 10 },
  deliveryRow: { paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#E5E7EB' },
  deliveryName: { fontSize: 16, fontWeight: '700', marginBottom: 4 },
  receiptInput: { marginTop: 8, minHeight: 44, fontSize: 24 },
  unknownText: { fontSize: 16, color: '#b45309', marginBottom: 8 },
});
