/** 仓管入库首页：采购收货任务是真值，物料批次不再冒充待收货任务。 */
import React, { useCallback, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Chip, Searchbar, Surface, Text, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { WHInboundStackParamList } from '../../../types/navigation';
import { purchaseApiClient, PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { useAuthStore } from '../../../store/authStore';
import { handleError } from '../../../utils/errorHandler';
import { warehouseReceivingApiClient } from '../../../services/api/warehouseReceivingApiClient';
import { FrontlineAIEntry } from '../../../components/ai/FrontlineAIEntry';
import {
  countInboundTasks,
  filterReceivingOrders,
  mergeReceivingOrders,
  sortReceivingOrders,
  statusesToFetchForReceivingPage,
  toReceivingStatus,
  type InboundCounts,
  type ReceivingStatus,
} from '../warehouseList.logic';

type NavigationProp = NativeStackNavigationProp<WHInboundStackParamList>;
type FilterStatus = 'all' | ReceivingStatus;

export const INBOUND_ORDER_STATUSES: PurchaseOrder['status'][] = [
  'FINANCE_APPROVED',
  'PARTIAL_RECEIVED',
  'COMPLETED',
  'CLOSED',
];

export { countInboundTasks, sortReceivingOrders, toReceivingStatus } from '../warehouseList.logic';
export { filterReceivingOrders } from '../warehouseList.logic';
export type { InboundCounts, ReceivingStatus } from '../warehouseList.logic';
// 状态优先级固定为 pending: 0, partial: 1, completed: 2。

const STATUS_STYLE: Record<ReceivingStatus, { label: string; color: string; bg: string }> = {
  pending: { label: '待收货', color: '#1565C0', bg: '#E3F2FD' },
  partial: { label: '部分入库', color: '#E65100', bg: '#FFF3E0' },
  completed: { label: '已收货', color: '#2E7D32', bg: '#E8F5E9' },
};

function lineQuantity(order: PurchaseOrder): string {
  if (!order.items?.length) return '暂无物料明细';
  const lines = order.items.slice(0, 3).map((item) => {
    const name = item.materialName || item.materialTypeId;
    const pending = Math.max(0, (item.quantity || 0) - (item.receivedQuantity || 0));
    return `${name}：待收 ${pending} ${item.unit || '单位未标注'}`;
  });
  return lines.join('\n') + (order.items.length > 3 ? `\n还有 ${order.items.length - 3} 项` : '');
}

export default function WHInboundListScreen() {
  const navigation = useNavigation<NavigationProp>();
  const factoryId = useAuthStore((state) => state.user?.factoryId);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [unorderedInboundCount, setUnorderedInboundCount] = useState(0);
  const [inboundCounts, setInboundCounts] = useState<InboundCounts>({ all: 0, pending: 0, partial: 0, completed: 0 });
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<FilterStatus>('all');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [statusHasMore, setStatusHasMore] = useState<Partial<Record<PurchaseOrder['status'], boolean>>>(() =>
    Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true])) as Partial<Record<PurchaseOrder['status'], boolean>>,
  );
  const [loadingMore, setLoadingMore] = useState(false);
  const statusPagesRef = useRef<Partial<Record<PurchaseOrder['status'], number>>>({});
  const statusHasMoreRef = useRef<Partial<Record<PurchaseOrder['status'], boolean>>>(
    Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true])) as Partial<Record<PurchaseOrder['status'], boolean>>,
  );
  const statusTotalsRef = useRef<Partial<Record<PurchaseOrder['status'], { totalElements?: number; content?: PurchaseOrder[] }>>>({});
  // status -> page that failed; a retry must not advance successful sources.
  const failedStatusesRef = useRef<Partial<Record<PurchaseOrder['status'], number>>>({});
  const unorderedInboundCountRef = useRef(0);
  const PAGE_SIZE = 20;
  const hasMore = INBOUND_ORDER_STATUSES.some((status) => statusHasMore[status]);

  const loadData = useCallback(async (mode: 'refresh' | 'append' | 'retryFailed' = 'refresh') => {
    try {
      const append = mode !== 'refresh';
      const retryFailed = mode === 'retryFailed';
      const statuses = retryFailed
        ? (Object.keys(failedStatusesRef.current) as PurchaseOrder['status'][])
        : statusesToFetchForReceivingPage(INBOUND_ORDER_STATUSES, statusHasMoreRef.current, append);
      if (append && statuses.length === 0) return;
      const requests = statuses.map((status) => ({
        status,
        page: retryFailed
          ? (failedStatusesRef.current[status] ?? (statusPagesRef.current[status] ?? 0) + 1)
          : append ? (statusPagesRef.current[status] ?? 0) + 1 : 1,
      }));
      const [results, arrivalResults] = await Promise.all([
        Promise.allSettled(requests.map(({ status, page }) =>
          purchaseApiClient.getOrdersByStatus(status, { page, size: PAGE_SIZE }, factoryId))),
        append ? Promise.resolve(null) : Promise.allSettled([
          warehouseReceivingApiClient.listCustomerMaterialArrivalTasks(undefined, factoryId),
        ]),
      ]);
      const arrivalResult = arrivalResults?.[0];
      const fulfilled = results.filter(
        (result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof purchaseApiClient.getOrdersByStatus>>> =>
          result.status === 'fulfilled',
      );
      const firstFailure = results.find((result): result is PromiseRejectedResult => result.status === 'rejected');
      // On append, exhausted sources are intentionally absent from requests;
      // compare with the actual request set rather than all four statuses.
      const statusLoadFailed = fulfilled.length !== requests.length;
      const arrivalLoadFailed = !append && (!arrivalResult || arrivalResult.status === 'rejected');
      const hasLoadFailure = statusLoadFailed || arrivalLoadFailed;
      const arrivalTasks = arrivalResult?.status === 'fulfilled' ? (arrivalResult.value?.data || []) : [];
      const pageOrders: PurchaseOrder[] = [];
      results.forEach((result, index) => {
        const request = requests[index];
        const status = request?.status;
        if (!status) return;
        if (result.status !== 'fulfilled') {
          failedStatusesRef.current[status] = request.page;
          return;
        }
        delete failedStatusesRef.current[status];
        statusPagesRef.current[status] = request.page;
        const hasMorePage = request.page < (result.value.data.totalPages ?? request.page);
        statusHasMoreRef.current[status] = hasMorePage;
        setStatusHasMore((previous) => ({ ...previous, [status]: hasMorePage }));
        statusTotalsRef.current[status] = result.value.data;
        pageOrders.push(...(result.value?.data?.content || []));
      });
      const nextOrders = sortReceivingOrders(pageOrders);
      if (!hasLoadFailure && !append) {
        setOrders(nextOrders);
        setUnorderedInboundCount(arrivalTasks.length);
        unorderedInboundCountRef.current = arrivalTasks.length;
        setInboundCounts(countInboundTasks(statusTotalsRef.current, arrivalTasks.length));
        setLoadError(null);
      } else if (!hasLoadFailure && append) {
        setOrders((previous) => mergeReceivingOrders(previous, pageOrders));
        setLoadError(null);
      } else {
        // 部分/全部失败时保留上次可信列表，不把不完整数据伪装成全量成功。
        setLoadError('部分入库任务加载失败，请点击重试。');
        if (firstFailure) handleError(firstFailure.reason, { title: '入库任务部分加载失败' });
        else if (arrivalLoadFailed) {
          const arrivalReason = arrivalResult && arrivalResult.status === 'rejected'
            ? arrivalResult.reason
            : new Error('无订单入库任务加载失败');
          handleError(arrivalReason, { title: '入库任务部分加载失败' });
        }
        setOrders((previous) => {
          if (!append && previous.length === 0) return nextOrders;
          return mergeReceivingOrders(previous, pageOrders);
        });
      }
      if (append) {
        setInboundCounts(countInboundTasks(statusTotalsRef.current, unorderedInboundCountRef.current));
      }
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : '请检查网络或账号权限后重试');
      handleError(error, { title: '加载入库任务失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useFocusEffect(useCallback(() => {
    const retryFailed = Object.keys(failedStatusesRef.current).length > 0;
    if (!retryFailed) {
      statusPagesRef.current = {};
      statusHasMoreRef.current = Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true]));
      statusTotalsRef.current = {};
      setStatusHasMore(Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true])));
    }
    void loadData(retryFailed ? 'retryFailed' : 'refresh');
  }, [loadData]));

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    const retryFailed = Object.keys(failedStatusesRef.current).length > 0;
    if (!retryFailed) {
      statusPagesRef.current = {};
      statusHasMoreRef.current = Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true]));
      statusTotalsRef.current = {};
      setStatusHasMore(Object.fromEntries(INBOUND_ORDER_STATUSES.map((status) => [status, true])));
    }
    void loadData(retryFailed ? 'retryFailed' : 'refresh');
  }, [loadData]);

  const onLoadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    await loadData('append');
    setLoadingMore(false);
  }, [hasMore, loadData, loadingMore]);

  const stats = inboundCounts;

  const visibleOrders = useMemo(() => filterReceivingOrders(orders, filter, query), [filter, orders, query]);

  const openOrder = useCallback((order: PurchaseOrder) => {
    // 终态采购单进入同一详情页只读回看；详情页会根据状态禁用写操作。
    navigation.navigate('WHReceiptCreate', {
      purchaseOrderId: order.id,
      orderNumber: order.orderNumber,
    });
  }, [navigation]);

  const openReceivingTasks = useCallback(
    () => navigation.navigate('WHPurchaseReceiveList'),
    [navigation],
  );

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}><Text style={styles.headerTitle}>入库管理</Text></View>
        <View style={styles.center}><ActivityIndicator size="large" color="#4CAF50" /><Text>正在加载入库任务…</Text></View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']} testID="wh-inbound-home">
      <View style={styles.header}>
        <Text style={styles.headerTitle}>入库管理</Text>
        <Text style={styles.headerSubtitle}>待处理 {stats.pending + stats.partial} 单</Text>
      </View>
      <ScrollView refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
        <View style={styles.actions}>
          <Button mode="contained" icon="clipboard-check-outline" contentStyle={styles.primaryContent}
            onPress={openReceivingTasks} style={styles.primaryButton}>
            待收货任务
          </Button>
          <Button testID="wh-scan-inbound" mode="outlined" icon="qrcode-scan"
            contentStyle={styles.largeActionContent} style={styles.largeAction}
            onPress={() => navigation.navigate('WHScanOperation', { type: 'inbound' })}>
            扫码入库
          </Button>
          <Button testID="wh-production-receipt-entry" mode="outlined" icon="package-down"
            contentStyle={styles.largeActionContent} style={styles.largeAction}
            onPress={() => navigation.navigate('WHTransitLedger')}>
            生产成品入库
          </Button>
        </View>

        <Searchbar placeholder="搜索订单号 / 物料 / 供应商" value={query} onChangeText={setQuery} style={styles.search} />
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filters}>
          {([
            ['all', '全部', stats.all],
            ['pending', '待收货', stats.pending],
            ['partial', '部分入库', stats.partial],
            ['completed', '已收货', stats.completed],
          ] as const).map(([value, label, count]) => (
            <Chip key={value} selected={filter === value} onPress={() => setFilter(value)}
              style={[styles.filter, filter === value && styles.filterActive]}
              textStyle={filter === value ? styles.filterTextActive : undefined}>
              {label}({count})
            </Chip>
          ))}
        </ScrollView>

        <View style={styles.list}>
          {loadError && orders.length === 0 ? (
            <Surface style={styles.empty} elevation={1}>
              <MaterialCommunityIcons name="alert-circle-outline" size={42} color="#C62828" />
              <Text style={styles.errorTitle}>入库任务加载失败</Text><Text style={styles.emptyHint}>{loadError}</Text>
              <Button mode="contained" icon="refresh" onPress={onRefresh}>重试</Button>
            </Surface>
          ) : visibleOrders.length === 0 && !hasMore && !(filter !== 'completed' && !query.trim() && stats.pending > 0) && !(loadError && orders.length > 0) ? (
            <Surface style={styles.empty} elevation={1}>
              <MaterialCommunityIcons name="package-variant-closed" size={42} color="#BDBDBD" />
              <Text style={styles.emptyTitle}>当前筛选下没有入库任务</Text>
            </Surface>
          ) : (
            <>
              {loadError && orders.length > 0 && (
                <Surface style={styles.warning} elevation={1}>
                  <Text style={styles.errorTitle}>部分入库任务加载失败</Text>
                  <Text style={styles.emptyHint}>{loadError}</Text>
                  <Button mode="outlined" icon="refresh" onPress={onRefresh}>重试失败来源</Button>
                </Surface>
              )}
              {filter !== 'completed' && !query.trim() && unorderedInboundCount > 0 && (
                <TouchableRipple
                  onPress={openReceivingTasks}
                  rippleColor="rgba(21,101,192,0.15)"
                  style={styles.cardTouch}
                >
                  <Surface style={styles.card} elevation={1}>
                    <View style={styles.cardHeader}>
                      <Text style={styles.orderNumber}>无订单入库</Text>
                      <View style={[styles.badge, { backgroundColor: '#E3F2FD' }]}>
                        <Text style={{ color: '#1565C0' }}>待处理</Text>
                      </View>
                    </View>
                    <Text style={styles.lines}>有 {unorderedInboundCount} 单无订单入库预告，点击进入待收货任务处理</Text>
                  </Surface>
                </TouchableRipple>
              )}
              {visibleOrders.map((order) => {
            const status = toReceivingStatus(order.status);
            const config = STATUS_STYLE[status];
            return (
              <TouchableRipple key={order.id} onPress={() => openOrder(order)}
                rippleColor="rgba(76,175,80,0.15)" style={styles.cardTouch}>
                <Surface style={styles.card} elevation={1}>
                  <View style={styles.cardHeader}>
                    <Text style={styles.orderNumber}>{order.orderNumber}</Text>
                    <View style={[styles.badge, { backgroundColor: config.bg }]}><Text style={{ color: config.color }}>{config.label}</Text></View>
                  </View>
                  <View style={styles.metaRow}><Text style={styles.metaLabel}>供应商</Text><Text style={styles.metaValue}>{order.supplierName || '未知供应商'}</Text></View>
                  <View style={styles.metaRow}><Text style={styles.metaLabel}>物料</Text><Text style={styles.metaValue}>共 {order.items?.length || 0} 项</Text></View>
                  <Text style={styles.lines}>{lineQuantity(order)}</Text>
                  <View style={styles.footer}>
                    <Text style={styles.dateText}>期望交货：{order.expectedDeliveryDate || '未填写'}</Text>
                    <Text style={[styles.actionText, status === 'completed' && styles.doneText]}>{status === 'completed' ? '已全部收货' : '开始收货 ›'}</Text>
                  </View>
                </Surface>
              </TouchableRipple>
            );
              })}
              {hasMore && (
                <Button
                  testID="wh-inbound-load-more"
                  mode="outlined"
                  icon="chevron-down"
                  onPress={() => void onLoadMore()}
                  disabled={loadingMore}
                >
                  {loadingMore ? '正在加载更多…' : '加载更多入库任务'}
                </Button>
              )}
            </>
          )}
        </View>
      </ScrollView>
      {/* 一线 AI 助手入口 (仅 warehouse_worker; 本屏与仓储主管共用, 组件自己判角色+熔断开关) */}
      <FrontlineAIEntry containerStyle={styles.aiEntryDock} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  aiEntryDock: { paddingHorizontal: 16, paddingVertical: 8 },
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  header: { backgroundColor: '#4CAF50', paddingHorizontal: 16, paddingVertical: 16 },
  headerTitle: { color: '#FFF', fontSize: 22, fontWeight: '700' },
  headerSubtitle: { color: 'rgba(255,255,255,0.9)', fontSize: 14, marginTop: 5 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', gap: 12 },
  actions: { padding: 16, gap: 10 },
  primaryButton: { borderRadius: 10, backgroundColor: '#1565C0' },
  primaryContent: { minHeight: 56 },
  largeAction: { borderRadius: 10, borderColor: '#A5D6A7' },
  largeActionContent: { minHeight: 56 },
  search: { marginHorizontal: 16, backgroundColor: '#FFF' },
  filters: { paddingHorizontal: 16, paddingVertical: 12, gap: 8 },
  filter: { minHeight: 44, marginRight: 0, backgroundColor: '#FFF' },
  filterActive: { backgroundColor: '#4CAF50' },
  filterTextActive: { color: '#FFF' },
  list: { paddingHorizontal: 16, paddingBottom: 28 },
  cardTouch: { borderRadius: 12, marginBottom: 12 },
  card: { backgroundColor: '#FFF', borderRadius: 12, padding: 16 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 12 },
  orderNumber: { flex: 1, fontSize: 16, fontWeight: '700', color: '#263238' },
  badge: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 14 },
  metaRow: { flexDirection: 'row', marginBottom: 6 },
  metaLabel: { width: 58, fontSize: 13, color: '#8A8A8A' },
  metaValue: { flex: 1, fontSize: 14, color: '#333' },
  lines: { backgroundColor: '#F7FAF8', color: '#2F4F3A', fontSize: 14, lineHeight: 23, padding: 10, borderRadius: 8, marginTop: 4 },
  footer: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginTop: 12 },
  dateText: { flex: 1, color: '#8A8A8A', fontSize: 12 },
  actionText: { color: '#2E7D32', fontSize: 14, fontWeight: '700' },
  doneText: { color: '#757575' },
  empty: { backgroundColor: '#FFF', borderRadius: 12, padding: 30, alignItems: 'center', gap: 10 },
  warning: { backgroundColor: '#FFF8E1', borderRadius: 12, padding: 16, marginBottom: 12, gap: 6 },
  emptyTitle: { color: '#666', fontSize: 15, fontWeight: '600' },
  errorTitle: { color: '#C62828', fontSize: 16, fontWeight: '700' },
  emptyHint: { color: '#666', fontSize: 13, textAlign: 'center', lineHeight: 20 },
});
