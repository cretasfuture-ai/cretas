/**
 * 发货确认列表 (真发货 DLV-* 流程) — 仓管员主发货界面.
 *
 * 列出销售员创建的、待仓库确认的发货单 (SalesDeliveryRecord, DLV-*).
 * 仓管点"确认并发货" → 填实际发货数量 → 后端扣减成品库存 (真库存移动) + 转 SHIPPED.
 *
 * 区别于老"手工发货登记" (SH-*, /shipments) —— 旧链路已冻结，仅保留历史查询.
 * 客户原话 (六扇门 F006 仓管场景): "你告诉他这个东西你要收多少就行了" → 防呆: 计划数量作上限, 上下文带全.
 */

import React, { useState, useCallback, useRef } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { Text, Surface, Button, Searchbar, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { isAxiosError } from 'axios';
import { WHOutboundStackParamList } from '../../../types/navigation';
import {
  warehouseDeliveryApiClient,
  WarehouseDeliveryRecord,
} from '../../../services/api/warehouseDeliveryApiClient';
import { handleError } from '../../../utils/errorHandler';
import { displayUnit } from '../../../utils/packagingUnitConversion';
import { formatDateTime } from '../../../utils/formatters';
import {
  filterPendingDeliveries,
  isConfirmableDeliveryStatus,
  isCompletedDeliveryStatus,
  mergeDeliveryRecords,
  type DeliveryStatusFilter,
} from '../warehouseList.logic';

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList>;

interface StatusStyle {
  label: string;
  color: string;
  bgColor: string;
}
const DEFAULT_STATUS: StatusStyle = { label: '待仓库确认', color: '#f57c00', bgColor: '#fff3e0' };
const statusConfig: Record<string, StatusStyle> = {
  DRAFT: { label: '草稿', color: '#616161', bgColor: '#f5f5f5' },
  PENDING_WAREHOUSE_CONFIRM: DEFAULT_STATUS,
  PICKED: { label: '已拣货', color: '#1976d2', bgColor: '#e3f2fd' },
  PENDING_SPLIT: { label: '待分批', color: '#616161', bgColor: '#f5f5f5' },
  PARTIALLY_SCHEDULED: { label: '部分已安排', color: '#7b1fa2', bgColor: '#f3e5f5' },
  FULLY_SCHEDULED: { label: '已全部安排', color: '#7b1fa2', bgColor: '#f3e5f5' },
  PARTIALLY_SHIPPED: { label: '部分已发货', color: '#00838f', bgColor: '#e0f7fa' },
  SHIPPED: { label: '已发货', color: '#00838f', bgColor: '#e0f7fa' },
  DELIVERED: { label: '已签收', color: '#2e7d32', bgColor: '#e8f5e9' },
  SIGNED: { label: '已签收', color: '#2e7d32', bgColor: '#e8f5e9' },
  CANCELLED: { label: '已取消', color: '#757575', bgColor: '#eeeeee' },
  RETURNED: { label: '已退回', color: '#757575', bgColor: '#eeeeee' },
};

export { filterPendingDeliveries } from '../warehouseList.logic';

export function describeDeliveryListFailure(
  error: unknown,
  source: 'pending' | 'history' | 'both' = 'pending',
): { title: string; message: string; hint?: string } {
  const sourceLabel = source === 'history' ? '发货历史' : source === 'both' ? '待确认和发货历史' : '待确认发货单';
  if (isAxiosError(error)) {
    const data = error.response?.data as { message?: string; actionHint?: string } | undefined;
    if (!error.response) {
      return { title: `${sourceLabel}加载失败`, message: '手机暂时连不上服务器，请检查网络后重试。' };
    }
    return {
      title: `加载${sourceLabel}失败`,
      message: data?.message || `服务器返回错误 (${error.response.status})，请稍后重试。`,
      hint: data?.actionHint,
    };
  }
  return {
    title: `加载${sourceLabel}失败`,
    message: error instanceof Error ? error.message : '未知错误，请重试。',
  };
}

function customerText(row: WarehouseDeliveryRecord): string {
  return row.customerName || row.customerId || '未知客户';
}

function orderText(row: WarehouseDeliveryRecord): string {
  return row.orderNumber || row.salesOrderId || '无关联订单';
}

/**
 * 卡片上的货品 + 数量摘要 (防呆 Rule 2: 上下文必带身份信息)。
 *
 * 2026-08-21 收敛前, 这张卡只显示"X 个产品"这样一个数, 仓管看不出这单要发什么货,
 * 只能点进详情才知道 —— 与「待收货任务」列表页(WHPurchaseReceiveListScreen)的
 * pendingQtySummary 是同一类信息, 那边有这边没有。补上, 不新增按钮或跳转。
 */
export function deliveryItemLines(row: WarehouseDeliveryRecord) {
  return (row.items || []).map((item) => ({
    id: item.id,
    name: item.productName || `产品-${item.productTypeId || ''}`,
    quantityText: `计划发 ${item.deliveredQuantity ?? 0} ${displayUnit(item.unit) || '单位未标注'}`,
  }));
}

export function WHDeliveryConfirmListScreen() {
  const navigation = useNavigation<NavigationProp>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [pendingDeliveries, setPendingDeliveries] = useState<WarehouseDeliveryRecord[]>([]);
  const [historyDeliveries, setHistoryDeliveries] = useState<WarehouseDeliveryRecord[]>([]);
  const [pendingTotal, setPendingTotal] = useState(0);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [, setPendingPage] = useState(0);
  const [, setHistoryPage] = useState(0);
  const [pendingHasMore, setPendingHasMore] = useState(true);
  const [historyHasMore, setHistoryHasMore] = useState(true);
  const pendingPageRef = useRef(0);
  const historyPageRef = useRef(0);
  const pendingHasMoreRef = useRef(true);
  const historyHasMoreRef = useRef(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [selectedStatus, setSelectedStatus] = useState<DeliveryStatusFilter>('all');
  const [query, setQuery] = useState('');
  const [loadError, setLoadError] = useState<ReturnType<typeof describeDeliveryListFailure> | null>(null);

  const PAGE_SIZE = 20;

  const loadData = useCallback(async (append = false) => {
    const nextPendingPage = append ? pendingPageRef.current + 1 : 1;
    const nextHistoryPage = append ? historyPageRef.current + 1 : 1;
    const shouldLoadPending = !append || pendingHasMoreRef.current;
    const shouldLoadHistory = !append || historyHasMoreRef.current;
    if (append && !shouldLoadPending && !shouldLoadHistory) return;

    if (append) setLoadingMore(true);
    const [pendingResult, historyResult] = await Promise.allSettled([
      shouldLoadPending
        ? warehouseDeliveryApiClient.getPendingDeliveries({ page: nextPendingPage, size: PAGE_SIZE })
        : Promise.resolve(null),
      shouldLoadHistory
        ? warehouseDeliveryApiClient.getDeliveryHistory({ page: nextHistoryPage, size: PAGE_SIZE })
        : Promise.resolve(null),
    ]);

    const failures: ('pending' | 'history')[] = [];
    if (pendingResult.status === 'fulfilled' && pendingResult.value?.success && pendingResult.value.data) {
      const page = pendingResult.value.data;
      setPendingDeliveries((previous) => append
        ? [...previous, ...(page.content ?? []).filter((row) => !previous.some((item) => item.id === row.id))]
        : (page.content ?? []));
      const hasMorePage = nextPendingPage < (page.totalPages ?? nextPendingPage);
      pendingPageRef.current = nextPendingPage;
      pendingHasMoreRef.current = hasMorePage;
      setPendingPage(nextPendingPage);
      setPendingHasMore(hasMorePage);
      setPendingTotal(page.totalElements ?? page.content?.length ?? 0);
    } else {
      if (shouldLoadPending) {
        failures.push('pending');
        if (pendingResult.status === 'fulfilled') {
          handleError(new Error(pendingResult.value?.message || '服务器没有返回待确认发货单，请重试。'), { title: '加载待确认发货单失败' });
        } else {
          handleError(pendingResult.reason, { title: '加载待确认发货单失败' });
        }
      }
    }
    if (historyResult.status === 'fulfilled' && historyResult.value?.success && historyResult.value.data) {
      const page = historyResult.value.data;
      setHistoryDeliveries((previous) => append
        ? [...previous, ...(page.content ?? []).filter((row) => !previous.some((item) => item.id === row.id))]
        : (page.content ?? []));
      const hasMorePage = nextHistoryPage < (page.totalPages ?? nextHistoryPage);
      historyPageRef.current = nextHistoryPage;
      historyHasMoreRef.current = hasMorePage;
      setHistoryPage(nextHistoryPage);
      setHistoryHasMore(hasMorePage);
      setHistoryTotal(page.totalElements ?? page.content?.length ?? 0);
    } else {
      if (shouldLoadHistory) {
        failures.push('history');
        if (historyResult.status === 'fulfilled') {
          handleError(new Error(historyResult.value?.message || '服务器没有返回发货历史，请重试。'), { title: '加载发货历史失败' });
        } else {
          handleError(historyResult.reason, { title: '加载发货历史失败' });
        }
      }
    }

    if (failures.length === 0) {
      setLoadError(null);
    } else {
      const source = failures.length === 2 ? 'both' : failures[0];
      const firstFailure = failures[0] === 'pending' ? pendingResult : historyResult;
      const error = firstFailure.status === 'rejected'
        ? firstFailure.reason
        : new Error(firstFailure.value?.message || '部分发货数据加载失败，请重试。');
      setLoadError(describeDeliveryListFailure(error, source));
    }
    setLoading(false);
    setRefreshing(false);
    if (append) setLoadingMore(false);
  }, []);

  // 从确认页返回后自动刷新 (已确认的发货单应从待确认队列消失)
  useFocusEffect(
    useCallback(() => {
      void loadData();
    }, [loadData]),
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    pendingPageRef.current = 0;
    historyPageRef.current = 0;
    pendingHasMoreRef.current = true;
    historyHasMoreRef.current = true;
    setPendingPage(0);
    setHistoryPage(0);
    setPendingHasMore(true);
    setHistoryHasMore(true);
    void loadData(false);
  }, [loadData]);

  const onLoadMore = useCallback(() => {
    if (loadingMore || (!pendingHasMore && !historyHasMore)) return;
    void loadData(true);
  }, [historyHasMore, loadData, loadingMore, pendingHasMore]);

  const openConfirm = (row: WarehouseDeliveryRecord) => {
    navigation.navigate('WHDeliveryConfirm', {
      deliveryId: row.id,
      deliveryNumber: row.deliveryNumber,
    });
  };

  const deliveries = mergeDeliveryRecords(pendingDeliveries, historyDeliveries);
  const total = deliveries.length;
  const filteredDeliveries = filterPendingDeliveries(deliveries, selectedStatus, query);
  const showFullError = Boolean(loadError && deliveries.length === 0);

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>发货确认</Text>
          <Text style={styles.headerSubtitle}>加载中...</Text>
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载待确认发货单...</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>发货确认</Text>
          <Text style={styles.headerSubtitle}>待仓库确认 {pendingTotal} 单 · 历史 {historyTotal} 单</Text>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {/* 概念说明 (对齐 web-admin ConceptDisambiguationAlert) */}
        <Surface style={styles.noticeCard} elevation={1}>
          <MaterialCommunityIcons name="information-outline" size={20} color="#1976d2" />
          <Text style={styles.noticeText}>
            销售员创建的发货单列在这里。确认实际发货数量后，系统自动扣减成品库存并生成送货单。
            已发货、已签收、已取消等终态在“已完成/历史”中只读展示，不会再次扣库存。
          </Text>
        </Surface>

        {/* 二级入口: 老手工发货登记 (SH-*, 不扣库存)。
            2026-08-21 收敛: 原来是与下方发货单卡片同权重的整行按钮, 而它是旧流程、
            不是仓管的主操作 (主操作是点卡片确认发货) —— 降级成文本链接, 不占独立按钮位。
            ⛔ 不删这条路径: 部分历史单据仍要靠它查/登记。 */}
        <View style={styles.actionBar}>
          <TouchableRipple
            onPress={() => navigation.navigate('WHOutboundList')}
            testID="wh-goto-manual-shipment"
            style={styles.secondaryLinkTouchable}
          >
            <Text style={styles.secondaryLinkText}>查看手工发货历史（旧流程·不扣库存）</Text>
          </TouchableRipple>
        </View>

        {showFullError && loadError ? (
          <Surface style={styles.errorCard} elevation={1} testID="wh-outbound-load-error">
            <MaterialCommunityIcons name="alert-circle-outline" size={42} color="#C62828" />
            <Text style={styles.errorTitle}>{loadError.title}</Text>
            <Text style={styles.errorMessage}>{loadError.message}</Text>
            {loadError.hint && <Text style={styles.errorHint}>{loadError.hint}</Text>}
            <Button mode="contained" icon="refresh" onPress={onRefresh} testID="wh-outbound-retry">
              重试
            </Button>
          </Surface>
        ) : (
          <>
            {loadError && (
              <Surface style={styles.partialErrorCard} elevation={1} testID="wh-outbound-partial-error">
                <MaterialCommunityIcons name="alert-outline" size={22} color="#e65100" />
                <Text style={styles.partialErrorText}>{loadError.title}：{loadError.message}</Text>
                <Button mode="text" compact onPress={onRefresh} testID="wh-outbound-retry-partial">重试</Button>
              </Surface>
            )}
            <Searchbar
              placeholder="搜索发货单号 / 销售单 / 客户 / 货品"
              value={query}
              onChangeText={setQuery}
              style={styles.search}
              accessibilityLabel="搜索发货任务"
            />
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.filterContainer}>
              {([
                ['all', '全部', deliveries.length],
                ['pending', '待确认', deliveries.filter((row) => isConfirmableDeliveryStatus(row.status)).length],
                ['completed', '已完成/历史', deliveries.filter((row) => isCompletedDeliveryStatus(row.status)).length],
              ] as const).map(([value, label, count]) => (
                <Button
                  key={value}
                  mode={selectedStatus === value ? 'contained' : 'outlined'}
                  compact
                  onPress={() => setSelectedStatus(value)}
                  style={styles.statusFilterButton}
                >
                  {label}({count})
                </Button>
              ))}
            </ScrollView>

            {/* 列表 */}
            <View style={styles.listContainer}>
          {filteredDeliveries.length === 0 ? (
            <Surface style={styles.emptyCard} elevation={1}>
              <MaterialCommunityIcons name="package-variant-closed" size={40} color="#bbb" />
              <Text style={styles.emptyText}>{selectedStatus === 'all' ? '暂无待确认发货单' : '当前状态暂无发货单'}</Text>
              <Text style={styles.emptyHint}>销售员创建发货单后会出现在这里；已确认单据可查看发货历史</Text>
            </Surface>
          ) : (
            filteredDeliveries.map((row) => {
              const config = statusConfig[String(row.status).toUpperCase()] ?? DEFAULT_STATUS;
              const itemLines = deliveryItemLines(row);
              return (
                <TouchableRipple
                  key={row.id}
                  onPress={() => openConfirm(row)}
                  rippleColor="rgba(76, 175, 80, 0.15)"
                >
                  <Surface style={styles.orderCard} elevation={1}>
                    <View style={styles.cardHeader}>
                      <Text style={styles.orderNumber}>{row.deliveryNumber || `DLV-${row.id}`}</Text>
                      <View style={[styles.statusBadge, { backgroundColor: config.bgColor }]}>
                        <Text style={[styles.statusText, { color: config.color }]}>{config.label}</Text>
                      </View>
                    </View>

                    <View style={styles.cardContent}>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>客户</Text>
                        <Text style={styles.infoValue}>{customerText(row)}</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>销售单</Text>
                        <Text style={styles.infoValue}>{orderText(row)}</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>货品</Text>
                        <Text style={styles.infoValue}>共 {itemLines.length} 项</Text>
                      </View>
                      <View style={styles.infoRow}>
                        <Text style={styles.infoLabel}>计划日期</Text>
                        <Text style={styles.infoValue}>{row.deliveryDate || '-'}</Text>
                      </View>
                      {!!row.logisticsCompany && (
                        <View style={styles.infoRow}>
                          <Text style={styles.infoLabel}>物流</Text>
                          <Text style={styles.infoValue}>{row.logisticsCompany}</Text>
                        </View>
                      )}
                      {!!row.trackingNumber && (
                        <View style={styles.infoRow}>
                          <Text style={styles.infoLabel}>物流单号</Text>
                          <Text style={styles.infoValue}>{row.trackingNumber}</Text>
                        </View>
                      )}
                      {isCompletedDeliveryStatus(row.status) && (
                        <>
                          <View style={styles.infoRow}>
                            <Text style={styles.infoLabel}>操作者</Text>
                            <Text style={styles.infoValue}>{row.shippedBy ? `用户 ${row.shippedBy}` : '系统未记录'}</Text>
                          </View>
                          <View style={styles.infoRow}>
                            <Text style={styles.infoLabel}>更新时间</Text>
                            <Text style={styles.infoValue}>{row.updatedAt ? formatDateTime(row.updatedAt) || row.updatedAt : '-'}</Text>
                          </View>
                        </>
                      )}
                    </View>

                    <View style={styles.itemLines}>
                      {itemLines.length === 0 ? (
                        <Text style={styles.itemCountText}>暂无货品明细，点进后核对</Text>
                      ) : itemLines.slice(0, 3).map((item) => (
                        <View key={item.id} style={styles.itemLine}>
                          <Text style={styles.itemName}>{item.name}</Text>
                          <Text style={styles.itemQuantity}>{item.quantityText}</Text>
                        </View>
                      ))}
                      {itemLines.length > 3 && <Text style={styles.moreItems}>还有 {itemLines.length - 3} 项，点进后查看</Text>}
                    </View>

                    <View style={styles.cardFooter}>
                      <Text style={styles.itemCountText}>共 {itemLines.length} 项</Text>
                      <View style={styles.actionLink}>
                        <Text style={styles.actionText}>
                          {isCompletedDeliveryStatus(row.status) ? '查看发货记录' : '下一步：确认并发货'}
                        </Text>
                        <MaterialCommunityIcons name="chevron-right" size={16} color="#4CAF50" />
                      </View>
                    </View>
                  </Surface>
                </TouchableRipple>
              );
            })
            )}
            </View>
            {(pendingHasMore || historyHasMore) && (
              <Button
                mode="outlined"
                compact
                loading={loadingMore}
                disabled={loadingMore}
                onPress={onLoadMore}
                testID="wh-outbound-load-more"
                style={styles.loadMoreButton}
              >
                加载更多
              </Button>
            )}
          </>
        )}

        <View style={{ height: 24 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  header: { backgroundColor: '#4CAF50', paddingHorizontal: 16, paddingVertical: 16 },
  headerTitle: { fontSize: 20, fontWeight: 'bold', color: '#fff' },
  headerSubtitle: { fontSize: 13, color: 'rgba(255,255,255,0.9)', marginTop: 4 },
  content: { flex: 1 },
  noticeCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#e3f2fd',
    marginHorizontal: 16,
    marginTop: 16,
    padding: 12,
    borderRadius: 8,
    gap: 10,
  },
  noticeText: { flex: 1, fontSize: 13, color: '#1976d2' },
  actionBar: { paddingHorizontal: 16, paddingTop: 12, alignItems: 'flex-end' },
  secondaryLinkTouchable: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 4 },
  secondaryLinkText: { color: '#757575', fontSize: 13, textDecorationLine: 'underline' },
  search: { marginHorizontal: 16, marginTop: 12, backgroundColor: '#fff' },
  filterContainer: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 4, gap: 8 },
  statusFilterButton: { minHeight: 44, marginRight: 0, borderRadius: 18 },
  listContainer: { paddingHorizontal: 16, paddingTop: 12 },
  loadMoreButton: { marginHorizontal: 16, marginTop: 4, marginBottom: 12 },
  emptyCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 32,
    alignItems: 'center',
    marginTop: 8,
  },
  emptyText: { marginTop: 12, fontSize: 15, color: '#666', fontWeight: '600' },
  emptyHint: { marginTop: 4, fontSize: 12, color: '#999' },
  errorCard: { backgroundColor: '#fff', borderRadius: 12, padding: 28, alignItems: 'center', marginHorizontal: 16, marginTop: 12, gap: 8 },
  errorTitle: { color: '#C62828', fontSize: 16, fontWeight: '700', textAlign: 'center' },
  errorMessage: { color: '#4a5568', fontSize: 14, lineHeight: 20, textAlign: 'center' },
  errorHint: { color: '#e65100', fontSize: 13, lineHeight: 20, textAlign: 'center' },
  partialErrorCard: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#fff8e1', borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8, marginHorizontal: 16, marginTop: 12, gap: 8 },
  partialErrorText: { flex: 1, color: '#8d4b00', fontSize: 12, lineHeight: 18 },
  orderCard: { backgroundColor: '#fff', borderRadius: 12, padding: 16, marginBottom: 12 },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  orderNumber: { fontSize: 15, fontWeight: '600', color: '#333' },
  statusBadge: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 12 },
  statusText: { fontSize: 12, fontWeight: '500' },
  cardContent: { borderTopWidth: 1, borderTopColor: '#f0f0f0', paddingTop: 12 },
  infoRow: { flexDirection: 'row', marginBottom: 8 },
  infoLabel: { width: 60, fontSize: 13, color: '#999' },
  infoValue: { flex: 1, fontSize: 13, color: '#333' },
  cardFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 4,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  itemCountText: { fontSize: 12, color: '#999' },
  itemLines: { borderTopWidth: 1, borderTopColor: '#F0F0F0', paddingTop: 10, gap: 6 },
  itemLine: { backgroundColor: '#F7FAF8', borderRadius: 7, paddingHorizontal: 10, paddingVertical: 8 },
  itemName: { color: '#334155', fontSize: 13, fontWeight: '600' },
  itemQuantity: { color: '#2E7D32', fontSize: 14, fontWeight: '700', marginTop: 3 },
  moreItems: { color: '#64748B', fontSize: 12 },
  actionLink: { flexDirection: 'row', alignItems: 'center' },
  actionText: { fontSize: 13, color: '#4CAF50', fontWeight: '600' },
});

export default WHDeliveryConfirmListScreen;
