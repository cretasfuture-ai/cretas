import React, { useState, useEffect } from 'react';
import { View, StyleSheet, ScrollView, Alert } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, DataTable, Divider, RadioButton, Checkbox, TextInput } from 'react-native-paper';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { salesApiClient, SalesOrder } from '../../../services/api/salesApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { useAuthStore } from '../../../store/authStore';
import { MissingRouteParams } from '../../../components/common/MissingRouteParams';
import { isMobileBusinessObserver } from '../../../utils/mobileRoleBoundaries';
import { getErrorMsg } from '../../../utils/errorHandler';
import {
  canShortCloseSalesOrder,
  shortCloseSummary,
  shortCloseSubmitBlockReason,
  formatShortCloseQty,
  SHORT_CLOSE_REASON_OPTIONS,
  SHORT_CLOSE_CONSEQUENCE_TEXT,
  type ShortCloseForm,
} from '../../../utils/salesOrderShortClose';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  CONFIRMED: { label: '已确认', color: '#409eff' },
  PENDING_FINANCE_REVIEW: { label: '待财务审核', color: '#e6a23c' },
  FINANCE_APPROVED: { label: '财务已批准', color: '#67c23a' },
  FINANCE_REJECTED: { label: '财务已驳回', color: '#f56c6c' },
  PROCESSING: { label: '处理中', color: '#e6a23c' },
  PARTIAL_DELIVERED: { label: '部分发货', color: '#f56c6c' },
  COMPLETED: { label: '已完成', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
  // T-SHORT-SHIP (2026-09-04): 缺此条会落到下面 `|| { label: order.status, ... }` 兜底,
  // 把英文码原样显示给用户 —— 与后端 SalesOrderStatus.SHORT_CLOSED.getDisplayName() 同文案。
  SHORT_CLOSED: { label: '短交结案', color: '#909399' },
};

export default function SalesOrderDetailScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((state) => state.user);
  const isOperationsReadOnly = isMobileBusinessObserver(user);
  const route = useRoute<RouteProp<FAManagementStackParamList, 'SalesOrderDetail'>>();
  // ⚠️ 不能写成 `const { orderId } = route.params` —— 没有 params 时它**直接抛**，
  //    而 AppErrorBoundary 接住的是整棵导航树: 一屏崩掉, 整个 App 白屏只能重来。
  //    (2026-08-29 全树走查实测: 43 条路由里就这一屏和采购单详情是这个形状。)
  const { orderId } = (route.params ?? {}) as { orderId?: string };

  const [order, setOrder] = useState<SalesOrder | null>(null);
  const [loading, setLoading] = useState(true);

  // T-SHORT-SHIP: 短交结案 —— 镜像 web-admin detail.vue 的对话框 (原因单选 + 补充说明 +
  // 「我已确认」勾选)。⛔ 不做成 Alert 直接确认: 后端 reasonCode 必填, 一句 Alert 收不到它,
  // 而且短交结案是终态, owner 口径「必须是显式的、经过确认的点击，绝不能隐式发生」。
  const [shortCloseVisible, setShortCloseVisible] = useState(false);
  const [shortCloseSubmitting, setShortCloseSubmitting] = useState(false);
  const [shortCloseForm, setShortCloseForm] = useState<ShortCloseForm>({
    reasonCode: '', notes: '', acknowledged: false,
  });

  useEffect(() => { if (orderId) loadOrder(); }, [orderId]);

  // ⛔ 这个 early return 必须在**所有 hook 之后** —— 提到 hook 中间就违反 hooks 规则。
  if (!orderId) {
    return (
      <MissingRouteParams
        title="销售单详情"
        hint="请从销售单列表点开一张单子进入。"
        onBack={() => navigation.goBack()}
      />
    );
  }

  const loadOrder = async () => {
    try {
      setLoading(true);
      const res = await salesApiClient.getOrder(orderId);
      if (res.success) setOrder(res.data);
    } catch { Alert.alert('错误', '加载失败'); }
    finally { setLoading(false); }
  };

  const handleAction = async (action: string) => {
    const labels: Record<string, string> = { confirm: '确认订单', cancel: '取消订单' };
    Alert.alert('确认', `确认${labels[action]}？`, [
      { text: '取消', style: 'cancel' },
      { text: '确定', onPress: async () => {
        try {
          let res;
          if (action === 'confirm') res = await salesApiClient.confirmOrder(orderId);
          else if (action === 'cancel') res = await salesApiClient.cancelOrder(orderId);
          if (res?.success) {
            const status = res.data?.status;
            const message = action === 'confirm' && status === 'FINANCE_APPROVED'
              ? '订单未触发审批阈值，已免审通过'
              : action === 'confirm' && status === 'PENDING_FINANCE_REVIEW'
                ? '订单已进入财务审核'
                : '操作成功';
            Alert.alert('成功', message);
            loadOrder();
          }
        } catch { Alert.alert('错误', '操作失败'); }
      }},
    ]);
  };

  const openShortCloseDialog = () => {
    setShortCloseForm({ reasonCode: '', notes: '', acknowledged: false });
    setShortCloseVisible(true);
  };

  const submitShortClose = async () => {
    if (!order || shortCloseSubmitting) return;
    // 本地拦一道 (少一次往返), 后端 SalesServiceImpl 仍会自己再判一遍。
    const blocked = shortCloseSubmitReason;
    if (blocked) { Alert.alert('还差一步', blocked); return; }
    setShortCloseSubmitting(true);
    try {
      const res = await salesApiClient.closeShortShipment(orderId, {
        reasonCode: shortCloseForm.reasonCode as Exclude<ShortCloseForm['reasonCode'], ''>,
        notes: shortCloseForm.notes.trim() || undefined,
      });
      if (res?.success) {
        setShortCloseVisible(false);
        // ⚠️ 状态文案从**后端返回的 status** 走 STATUS_MAP 取, ⛔ 不在这里写死「短交结案」——
        // 后端要是没真的改状态, 这句话就得跟着变 (measurement-and-wiring 形态 B)。
        const nextLabel = STATUS_MAP[res.data?.status ?? '']?.label ?? res.data?.status ?? '';
        Alert.alert('成功', `${res.message || '订单已按短交结案'}${nextLabel ? `\n当前状态：${nextLabel}` : ''}`);
        loadOrder();
      } else {
        // 后端返回 success=false 时不能当成功 (核心原则 1: 禁止降级处理)。
        Alert.alert('短交结案失败', res?.message || '后端未确认结案结果，请刷新订单查看最新状态');
      }
    } catch (e) {
      // 403 / 409 / 400 一律把**后端那句话**原样给用户 (getErrorMsg 会带上 actionHint)。
      Alert.alert('短交结案失败', getErrorMsg(e));
    } finally {
      setShortCloseSubmitting(false);
    }
  };

  // ⚠️ 这三个派生值必须在 early return **之前**声明 —— 放到后面的话, loading 那一次渲染
  //    产生的闭包会捕获到未初始化的绑定 (TDZ)。
  const shortClose = shortCloseSummary(order?.items);
  const canShortClose = canShortCloseSalesOrder(order, user);
  const shortCloseSubmitReason = shortCloseSubmitBlockReason(shortCloseForm);

  if (loading) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="销售单详情" /></Appbar.Header>
      <ActivityIndicator style={styles.loader} size="large" />
    </View>
  );

  if (!order) return (
    <View style={styles.container}>
      <Appbar.Header><Appbar.BackAction onPress={() => navigation.goBack()} /><Appbar.Content title="销售单详情" /></Appbar.Header>
      <Text style={styles.empty}>订单不存在</Text>
    </View>
  );

  const status = STATUS_MAP[order.status] || { label: order.status, color: '#909399' };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="销售单详情" />
        <Appbar.Action icon="refresh" onPress={loadOrder} />
      </Appbar.Header>

      <ScrollView style={styles.scroll}>
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.row}>
              <Text variant="titleMedium" style={styles.bold}>{order.orderNumber}</Text>
              <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
            </View>
            <Divider style={styles.divider} />
            <InfoRow label="客户" value={order.customerName || order.customerId} />
            <InfoRow label="总金额" value={`¥${formatNumberWithCommas(order.totalAmount)}`} valueStyle={styles.amountText} />
            <InfoRow label="下单日期" value={order.orderDate} />
            <InfoRow label="预计交付" value={order.expectedDeliveryDate || '-'} />
            {order.remark ? <InfoRow label="备注" value={order.remark} /> : null}
          </Card.Content>
        </Card>

        <Card style={styles.card}>
          <Card.Title title="订单明细" titleVariant="titleSmall" />
          <Card.Content>
            <DataTable>
              <DataTable.Header>
                <DataTable.Title>产品</DataTable.Title>
                <DataTable.Title numeric>数量</DataTable.Title>
                <DataTable.Title numeric>已发</DataTable.Title>
                <DataTable.Title numeric>单价</DataTable.Title>
              </DataTable.Header>
              {(order.items || []).map((item, idx) => (
                <DataTable.Row key={idx}>
                  {/* ⚠️ 后端在这个响应里发的是 productName（实测 'SOP-20260817-01-黄油鸡-成品800g'），
                      productTypeName 恒为 undefined ⇒ 原来这一格显示的是 productTypeId 那串 UUID。
                      与成品库存屏同一个字段错位, 同一个 UUID(eb0aa47b-…)。 */}
                  <DataTable.Cell>{item.productName || item.productTypeName || item.productTypeId}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.quantity} {item.unit}</DataTable.Cell>
                  <DataTable.Cell numeric>{item.deliveredQuantity}</DataTable.Cell>
                  <DataTable.Cell numeric>¥{formatNumberWithCommas(item.unitPrice)}</DataTable.Cell>
                </DataTable.Row>
              ))}
            </DataTable>
          </Card.Content>
        </Card>

        {(order.deliveryRecords || []).length > 0 && (
          <Card style={styles.card}>
            <Card.Title title="发货记录" titleVariant="titleSmall" />
            <Card.Content>
              {order.deliveryRecords!.map((rec, idx) => (
                <View key={idx} style={styles.recordItem}>
                  <View style={styles.row}>
                    <Text style={styles.recordNum}>{rec.deliveryNumber}</Text>
                    <Text style={styles.recordDate}>{rec.deliveryDate}</Text>
                  </View>
                  {(rec.items || []).map((di, j) => (
                    <Text key={j} style={styles.recordDetail}>
                      {di.productName || di.productTypeName || di.productTypeId}: {di.deliveredQuantity} {di.unit}
                    </Text>
                  ))}
                </View>
              ))}
            </Card.Content>
          </Card>
        )}

        {!isOperationsReadOnly && <View style={styles.actionBar}>
          {order.status === 'DRAFT' && (
            <>
              <Button mode="contained" onPress={() => handleAction('confirm')} style={styles.actionBtn}>确认订单</Button>
              <Button mode="outlined" onPress={() => handleAction('cancel')} style={styles.actionBtn}>取消</Button>
            </>
          )}
          {/* T-SHORT-SHIP: 短交结案。可见性判定在 utils/salesOrderShortClose.ts
              (状态 PARTIAL_DELIVERED × sales:read_write × 实发缺口>0), ⛔ 不在这里内联条件 ——
              内联的话单测就只能挂到整棵组件树上, 那道闸实际上没人守。 */}
          {canShortClose && (
            <Button
              mode="contained"
              onPress={openShortCloseDialog}
              style={styles.actionBtn}
              buttonColor="#e6a23c"
              testID="sales-order-short-close-button"
            >短交结案</Button>
          )}
        </View>}

        <View style={{ height: 30 }} />
      </ScrollView>

      {shortCloseVisible && (
        <View style={styles.dialogOverlay}>
          <ScrollView style={styles.dialogScroll} contentContainerStyle={styles.dialogScrollContent}>
            <View style={styles.dialogBox}>
              <Text variant="titleMedium" style={styles.dialogTitle}>短交结案 — {order.orderNumber}</Text>

              {/* 后果说明 —— 文案取自后端 SalesOrderStatus.SHORT_CLOSED.description。 */}
              <View style={styles.dialogWarnBlock}>
                <Text variant="bodySmall" style={styles.dialogWarnText}>{SHORT_CLOSE_CONSEQUENCE_TEXT}</Text>
                <Text variant="bodySmall" style={styles.dialogWarnText}>
                  结案后订单进入终态，剩余数量不会再安排发货。若还要补发，请先取消本操作。
                </Text>
              </View>

              {/* 逐行 订购/已发/短交 —— 数字全部来自订单明细, ⛔ 没有任何写死的示例数。 */}
              <Text variant="labelLarge" style={styles.dialogSectionLabel}>本次结案锁定的缺口</Text>
              <DataTable>
                <DataTable.Header>
                  <DataTable.Title style={styles.dialogProductCol}>产品</DataTable.Title>
                  <DataTable.Title numeric>订购</DataTable.Title>
                  <DataTable.Title numeric>已发</DataTable.Title>
                  <DataTable.Title numeric>短交</DataTable.Title>
                </DataTable.Header>
                {shortClose.shortLines.map((line, idx) => (
                  <DataTable.Row key={idx} testID={`sales-order-short-close-line-${idx}`} style={styles.dialogLineRow}>
                    {/* ⚠️ DataTable.Cell 默认单行截断 —— 实拍走查里产品名被切成「SOP-202…」,
                        用户看不出这一行到底是哪个产品。这里显式给两行 + 更宽的列。 */}
                    <DataTable.Cell style={styles.dialogProductCol}>
                      <Text variant="bodySmall" numberOfLines={2}>{line.productLabel}</Text>
                    </DataTable.Cell>
                    <DataTable.Cell numeric>{formatShortCloseQty(line.ordered)}</DataTable.Cell>
                    <DataTable.Cell numeric>{formatShortCloseQty(line.delivered)}</DataTable.Cell>
                    <DataTable.Cell numeric>{formatShortCloseQty(line.shortfall)}</DataTable.Cell>
                  </DataTable.Row>
                ))}
              </DataTable>
              <Text variant="bodyMedium" style={styles.dialogTotal} testID="sales-order-short-close-total">
                合计短交 {formatShortCloseQty(shortClose.outstanding)}{shortClose.unit ? ` ${shortClose.unit}` : ''}
              </Text>

              <Divider style={styles.divider} />

              <Text variant="labelLarge" style={styles.dialogSectionLabel}>短交结案原因</Text>
              <RadioButton.Group
                onValueChange={(v) => setShortCloseForm((f) => ({ ...f, reasonCode: v as ShortCloseForm['reasonCode'] }))}
                value={shortCloseForm.reasonCode}
              >
                {SHORT_CLOSE_REASON_OPTIONS.map((opt) => (
                  <View key={opt.value} style={styles.radioRow}>
                    <RadioButton value={opt.value} testID={`sales-order-short-close-reason-${opt.value}`} />
                    <Text variant="bodyMedium" style={styles.radioLabel}>{opt.label}</Text>
                  </View>
                ))}
              </RadioButton.Group>

              <TextInput
                mode="outlined"
                label={shortCloseForm.reasonCode === 'OTHER' ? '补充说明（必填）' : '补充说明（选填，≤500字）'}
                value={shortCloseForm.notes}
                onChangeText={(t) => setShortCloseForm((f) => ({ ...f, notes: t }))}
                multiline
                numberOfLines={3}
                style={styles.notesInput}
                testID="sales-order-short-close-notes"
              />

              <View style={styles.radioRow}>
                <Checkbox
                  status={shortCloseForm.acknowledged ? 'checked' : 'unchecked'}
                  onPress={() => setShortCloseForm((f) => ({ ...f, acknowledged: !f.acknowledged }))}
                  testID="sales-order-short-close-ack"
                />
                <Text variant="bodySmall" style={styles.radioLabel}>
                  我已与客户核对，确认剩余数量不再发货
                </Text>
              </View>

              <View style={styles.dialogActions}>
                <Button
                  mode="outlined"
                  onPress={() => setShortCloseVisible(false)}
                  disabled={shortCloseSubmitting}
                  style={styles.dialogBtn}
                  testID="sales-order-short-close-cancel"
                >取消</Button>
                <Button
                  mode="contained"
                  onPress={submitShortClose}
                  loading={shortCloseSubmitting}
                  // 勾选/选原因之前按钮就是禁用的 —— ⛔ 不靠「点了再弹一句警告」拦。
                  disabled={shortCloseSubmitting || shortCloseSubmitReason !== null}
                  style={styles.dialogBtn}
                  testID="sales-order-short-close-confirm"
                >确认短交结案</Button>
              </View>
            </View>
          </ScrollView>
        </View>
      )}
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
  amountText: { fontWeight: '700', color: '#67c23a' },
  recordItem: { paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  recordNum: { fontWeight: '600', fontSize: 13 },
  recordDate: { fontSize: 12, color: '#909399' },
  recordDetail: { fontSize: 12, color: '#666', marginTop: 2, marginLeft: 8 },
  actionBar: { flexDirection: 'row', gap: 10, padding: 12 },
  actionBtn: { flex: 1 },
  // T-SHORT-SHIP 对话框 —— 与同目录 PurchaseExceptionListScreen 的决策对话框同一套形状。
  dialogOverlay: {
    position: 'absolute',
    top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.5)',
    zIndex: 100,
  },
  dialogScroll: { flex: 1 },
  dialogScrollContent: { flexGrow: 1, justifyContent: 'center', padding: 20 },
  dialogBox: { width: '100%', maxWidth: 480, alignSelf: 'center', backgroundColor: '#fff', borderRadius: 12, padding: 20, elevation: 8 },
  dialogTitle: { fontWeight: '700', marginBottom: 12, color: '#1B65A8' },
  dialogWarnBlock: { backgroundColor: '#FDF6EC', borderRadius: 8, padding: 10, marginBottom: 10 },
  dialogWarnText: { color: '#9c6b16', marginBottom: 2 },
  dialogSectionLabel: { marginTop: 6, marginBottom: 4, color: '#333' },
  dialogTotal: { marginTop: 8, fontWeight: '700', color: '#e6a23c' },
  dialogProductCol: { flex: 2.2 },
  dialogLineRow: { minHeight: 56 },
  radioRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 2 },
  radioLabel: { flex: 1, color: '#333' },
  notesInput: { marginTop: 8, backgroundColor: '#fff' },
  dialogActions: { flexDirection: 'row', gap: 10, marginTop: 16 },
  dialogBtn: { flex: 1 },
});
