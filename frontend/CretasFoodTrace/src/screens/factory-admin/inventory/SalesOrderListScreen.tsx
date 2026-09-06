import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { View, StyleSheet, FlatList, Alert, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { useNavigation, CommonActions, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { salesApiClient, SalesOrder } from '../../../services/api/salesApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';
import { useAuthStore } from '../../../store/authStore';
import { isMobileBusinessObserver } from '../../../utils/mobileRoleBoundaries';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

type ApiActionError = {
  message?: string;
  response?: {
    data?: {
      message?: string;
      actionHint?: string;
    };
  };
};

const getActionErrorMessage = (error: unknown) => {
  const apiError = error as ApiActionError;
  const message = apiError.response?.data?.message || apiError.message || '操作失败';
  const actionHint = apiError.response?.data?.actionHint;
  return actionHint ? `${message}\n${actionHint}` : message;
};

/**
 * 列表加载失败的文案。
 *
 * 与 getActionErrorMessage 的差别是**故意的**: 这里 ⛔ 不读 `error.message`。
 * 网络层错误(超时/断网)的 `error.message` 是 axios 原文案 "Network Error" /
 * "Request failed with status code 500", 摆在列表页上不如一句「加载销售单失败」。
 * 只有后端真给了业务文案时才原样展示 —— 目前唯一会走到这里的业务 403 是
 * SalesController.listOrdersByStatus 对 PENDING_FINANCE_REVIEW 的 RBAC 分支
 * (「无权查看待财审销售单」)。当前 4 个 tab 走不到它, 但将来加「待财务审核」tab 时,
 * 用户必须看到**那句话**而不是被一句通用的「加载销售单失败」误导成系统故障。
 */
const getLoadErrorMessage = (error: unknown) => {
  const apiError = error as ApiActionError;
  const backendMessage = apiError?.response?.data?.message;
  if (typeof backendMessage !== 'string' || backendMessage.trim() === '') return '加载销售单失败';
  const actionHint = apiError.response?.data?.actionHint;
  return actionHint ? `${backendMessage}\n${actionHint}` : backendMessage;
};

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
  // T-SHORT-SHIP (2026-09-04): 缺此条会落到下面 `|| { label: item.status, ... }` 兜底,
  // 把英文码原样显示给用户 —— 与后端 SalesOrderStatus.SHORT_CLOSED.getDisplayName() 同文案。
  SHORT_CLOSED: { label: '短交结案', color: '#909399' },
};

export default function SalesOrderListScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((state) => state.user);
  const isOperationsReadOnly = isMobileBusinessObserver(user);
  const [orders, setOrders] = useState<SalesOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [selectedOrder, setSelectedOrder] = useState<SalesOrder | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  // BUG-SALES-ORDER-LIST-STATUS-TAB (2026-09-06): 状态 tab 从来没筛过列表。
  //
  // 旧写法是 `const params: any = {...}; if (statusFilter !== 'all') params.status = statusFilter;`
  // 然后打 GET /orders —— 而 SalesController.listOrders(factoryId, keyword, page, size)
  // **没有 status 形参**, Spring 把这个 query param 静默丢掉 ⇒ 列表恒为全量。
  // `: any` 正是让它编译得过的那一层: 去掉它, `params.status` 当场是类型错误。
  //
  // 而底部合计走 ListSummaryServiceImpl(按 filterConditions.status 过滤, 真筛了)
  // ⇒ 两侧口径不一致, 用户在「已确认」上看到「共 0 条 / ¥0.00」配着 4 张卡片。
  // 按状态筛必须打 /orders/by-status(采购列表 PurchaseOrderListScreen 一直是这么做的)。
  const loadOrders = useCallback(async () => {
    try {
      const params = { page: 1, size: 50 };
      const res = statusFilter !== 'all'
        ? await salesApiClient.getOrdersByStatus(statusFilter as SalesOrder['status'], params)
        : await salesApiClient.getOrders(params);
      if (res.success && res.data) {
        setOrders(res.data.content || []);
      }
    } catch (error) { Alert.alert('错误', getLoadErrorMessage(error)); }
    finally { setLoading(false); setRefreshing(false); }
  }, [statusFilter]);

  useEffect(() => { loadOrders(); }, [loadOrders]);
  useFocusEffect(useCallback(() => { loadOrders(); }, [loadOrders]));
  const onRefresh = () => { setRefreshing(true); loadOrders(); };

  // U-FOOTER-1 (Track I): sticky footer summary, filter mirrors list filter
  const summaryRequest = useMemo(
    () => ({ filterConditions: statusFilter !== 'all' ? { status: statusFilter } : {} }),
    [statusFilter],
  );
  const { summary, refresh: refreshSummary } = useListSummary('salesOrder', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  const handleAction = async (orderId: string, action: string) => {
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
        loadOrders();
      }
    } catch (error) {
      Alert.alert('错误', getActionErrorMessage(error));
      loadOrders();
    }
  };

  // UX-A2 (Track H): row-action bottom sheet
  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => navigation.navigate('SalesOrderDetail', { orderId: e.id }),
    submit: (e: RowContext) => handleAction(e.id, 'confirm'),
    cancel: (e: RowContext) => handleAction(e.id, 'cancel'),
    'print-pdf': (e: RowContext) => { void safePrint('sales-order', e.id); },
    copy: (e: RowContext) => Alert.alert('复制', `复制单据 ${e.id} (待接 API)`),
  }), [navigation]);

  const sheetCtx: RowContext = selectedOrder
    ? { status: selectedOrder.status, id: selectedOrder.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('salesOrder', sheetCtx, { handlers });

  const openSheet = (order: SalesOrder) => { setSelectedOrder(order); setActionSheetVisible(true); };

  const renderOrder = ({ item }: { item: SalesOrder }) => {
    const status = STATUS_MAP[item.status] || { label: item.status, color: '#909399' };
    // Sprint3-G S-LOCK-1: 行内 锁/备/缺. 缺 > 0 红, 销售员一眼判断催生产 / 紧急采购.
    const lockedQty = Number(item.lockedQty || 0);
    const reservedQty = Number(item.reservedQty || 0);
    const shortageQty = Number(item.shortageQty || 0);
    const hasShortage = shortageQty > 0;
    return (
      <Card style={styles.card}
        testID={`sales-order-card-${item.id}`}
        onPress={() => navigation.navigate('SalesOrderDetail', { orderId: item.id })}
        onLongPress={isOperationsReadOnly ? undefined : () => openSheet(item)}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleMedium" style={styles.orderNumber}>{item.orderNumber}</Text>
            <Chip style={[styles.chip, { backgroundColor: status.color + '20' }]} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>客户</Text>
            <Text style={styles.value}>{item.customerName || item.customerId}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>金额</Text>
            <Text style={[styles.value, styles.amount]}>¥{formatNumberWithCommas(item.totalAmount)}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>下单日期</Text>
            <Text style={styles.value}>{item.orderDate}</Text>
          </View>
          <View style={styles.lockReserveShortageRow}>
            <View style={[styles.lrsChip, styles.lrsLock]}>
              <Text style={styles.lrsText}>锁:{lockedQty}</Text>
            </View>
            <View style={[styles.lrsChip, styles.lrsReserve]}>
              <Text style={styles.lrsText}>备:{reservedQty}</Text>
            </View>
            <View style={[styles.lrsChip, hasShortage ? styles.lrsShortage : styles.lrsZero]}>
              <Text style={[styles.lrsText, hasShortage && styles.lrsTextShortage]}>缺:{shortageQty}</Text>
            </View>
          </View>
          {!isOperationsReadOnly && item.status === 'DRAFT' && (
            <View style={styles.actions}>
              <Button mode="contained" compact testID={`sales-order-confirm-${item.id}`} onPress={() => handleAction(item.id, 'confirm')} style={styles.actionBtn}>确认</Button>
              <Button mode="outlined" compact testID={`sales-order-cancel-${item.id}`} onPress={() => handleAction(item.id, 'cancel')} style={styles.actionBtn}>取消</Button>
            </View>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container} testID="sales-order-list-screen">
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="销售订单" />
        {!isOperationsReadOnly && (
          <Appbar.Action icon="plus" testID="sales-order-create-action" onPress={() => navigation.navigate('SalesOrderCreate')} />
        )}
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <View style={styles.filterRow}>
        {/* testID 是 ASCII 且逐 tab 唯一 —— E2E/单测靠它点中确定的那一档,
            也是本次改动在制品里的核验标记(中文标记会被 strings 吞掉)。
            ⚠️ value 必须逐字等于后端 SalesOrderStatus 的枚举名: /orders/by-status
            把它绑成 SalesOrderStatus, 拼错不是「筛不到」而是 400。 */}
        <SegmentedButtons
          value={statusFilter}
          onValueChange={setStatusFilter}
          buttons={[
            { value: 'all', label: '全部', testID: 'sales-order-list-status-all' },
            { value: 'DRAFT', label: '草稿', testID: 'sales-order-list-status-DRAFT' },
            { value: 'CONFIRMED', label: '已确认', testID: 'sales-order-list-status-CONFIRMED' },
            { value: 'COMPLETED', label: '已完成', testID: 'sales-order-list-status-COMPLETED' },
          ]}
        />
      </View>

      <View style={styles.listWrap}>
        {loading ? (
          <ActivityIndicator style={styles.loader} size="large" />
        ) : (
          <FlatList
            testID="sales-order-list"
            data={orders}
            keyExtractor={item => item.id}
            renderItem={renderOrder}
            contentContainerStyle={styles.list}
            refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            ListEmptyComponent={<Text style={styles.empty}>暂无销售单</Text>}
          />
        )}
      </View>

      {!isOperationsReadOnly && <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: {
              entityType: 'SALES_ORDER',
              initialMessage: `分析当前销售单列表${formatSummaryForAI(summary, { filter: { status: statusFilter } })}`,
            },
          }))
        }
      />}

      {!isOperationsReadOnly && <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedOrder ? `销售单 ${selectedOrder.orderNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedOrder) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'SALES_ORDER', initialMessage: `${selectedOrder.orderNumber}: ` },
          }));
        }}
      />}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  filterRow: { paddingHorizontal: 12, paddingVertical: 8, backgroundColor: '#fff' },
  listWrap: { flex: 1 },
  loader: { flex: 1, justifyContent: 'center' },
  list: { padding: 12, paddingBottom: 80 },
  card: { marginBottom: 10, borderRadius: 10 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  orderNumber: { fontWeight: '700' },
  chip: { height: 26 },
  cardRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  label: { color: '#909399', fontSize: 13 },
  value: { fontSize: 13, color: '#333' },
  amount: { fontWeight: '600', color: '#67c23a' },
  actions: { flexDirection: 'row', gap: 8, marginTop: 10, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#f0f0f0' },
  actionBtn: { flex: 1 },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
  // Sprint3-G S-LOCK-1: 锁/备/缺 chip — 行内显示, 缺 > 0 红色高亮
  lockReserveShortageRow: { flexDirection: 'row', gap: 6, marginTop: 8, paddingTop: 6, borderTopWidth: 1, borderTopColor: '#f0f0f0' },
  lrsChip: { flex: 1, paddingVertical: 3, paddingHorizontal: 8, borderRadius: 4, borderWidth: 1, alignItems: 'center' },
  lrsLock: { backgroundColor: '#f0f4ff', borderColor: '#cfd8e8' },
  lrsReserve: { backgroundColor: '#f0f9eb', borderColor: '#c2e7b0' },
  lrsZero: { backgroundColor: '#f4f4f5', borderColor: '#e9e9eb' },
  lrsShortage: { backgroundColor: '#fef0f0', borderColor: '#fbc4c4' },
  lrsText: { fontSize: 12, color: '#606266' },
  lrsTextShortage: { color: '#f56c6c', fontWeight: '600' },
});
