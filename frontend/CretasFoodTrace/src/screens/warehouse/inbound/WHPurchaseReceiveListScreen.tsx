/**
 * SP6 收货 — 采购单选择页 (无扫码直接选单流)
 *
 * 场景: 仓管员手机上直接选择待收货的采购单, 不需要扫 PDF QR.
 * 补全了 WHReceiptCreateScreen 只能通过扫码到达的缺口.
 *
 * 入口:
 *   WHInboundListScreen → "选采购单收货" 按钮 → 本页
 *   本页 → 点任意一张采购单卡片 → WHReceiptCreate
 *
 * 显示状态过滤: FINANCE_APPROVED / PARTIAL_RECEIVED
 *   (DRAFT/SUBMITTED/PENDING_FINANCE_REVIEW 未审批通过, 不能收货)
 *   (COMPLETED/CLOSED/CANCELLED 不需要收货)
 *
 * 防呆 Rule 1: 卡片显示订单数量 + 已收数量 + 待收数量 (上限可收多少)
 * 防呆 Rule 2: 卡片标题显示 供应商名 + 订单号 + 期望交货日 (上下文)
 */

import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native';
import {
  ActivityIndicator,
  Appbar,
  Badge,
  Button,
  Card,
  Chip,
  Text,
  TouchableRipple,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { isAxiosError } from 'axios';

import { WHInboundStackParamList } from '../../../types/navigation';
import {
  purchaseApiClient,
  PurchaseOrder,
} from '../../../services/api/purchaseApiClient';
import { handleError } from '../../../utils/errorHandler';
import { useAuthStore } from '../../../store/authStore';
import {
  CustomerMaterialArrivalTask,
  warehouseReceivingApiClient,
} from '../../../services/api/warehouseReceivingApiClient';
import { UNORDERED_INBOUND_REASON_LABEL } from './unorderedInboundReceiving';
import { sortReceivingOrders } from '../warehouseList.logic';

/** 加载失败态 (区别于"真的没有待收货采购单"的正常空态) */
interface LoadFailure {
  title: string;
  message: string;
  hint?: string;
  isPermission: boolean;
}

/**
 * 把三路 getOrdersByStatus 请求全部失败的原因翻成仓管员看得懂的话.
 *
 * fool-proof-design 4位一体: message 用后端原话 (PermissionInterceptor 403 body 的
 * message/actionHint 字段), 不用 axios 通用 "Request failed with status code 403".
 * 这里专门区分 403(权限) vs 其他(网络/5xx), 避免用户把"没权限"误当"工厂没有待收货单".
 */
function describeListFailure(reason: unknown): LoadFailure {
  if (isAxiosError(reason)) {
    const status = reason.response?.status;
    const data = reason.response?.data as { message?: string; actionHint?: string } | undefined;
    const backendMsg = data?.message;
    const actionHint = data?.actionHint;

    if (!reason.response) {
      return {
        title: '网络连接失败',
        message: '手机暂时连不上服务器，请检查网络后重试。',
        isPermission: false,
      };
    }
    if (status === 403) {
      return {
        title: '无采购收货权限',
        message: backendMsg || '您的账号无权限查看待收货采购单。',
        hint: actionHint || '请联系工厂管理员开通 [采购管理] 或 [仓储管理] 权限',
        isPermission: true,
      };
    }
    if (status === 401) {
      return {
        title: '登录已过期',
        message: '请重新登录后再试。',
        isPermission: false,
      };
    }
    return {
      title: '加载待收货采购单失败',
      message: backendMsg || `服务器返回错误 (${status ?? '未知'})，请稍后重试。`,
      hint: actionHint,
      isPermission: false,
    };
  }
  return {
    title: '加载待收货采购单失败',
    message: reason instanceof Error ? reason.message : '未知错误，请重试。',
    isPermission: false,
  };
}

type Nav = NativeStackNavigationProp<WHInboundStackParamList, 'WHPurchaseReceiveList'>;

/** 可收货的订单状态 */
const RECEIVABLE_STATUSES: PurchaseOrder['status'][] = [
  'FINANCE_APPROVED',
  'PARTIAL_RECEIVED',
];

const STATUS_LABEL: Record<string, { text: string; color: string; bg: string }> = {
  FINANCE_APPROVED:  { text: '待收货', color: '#1565c0', bg: '#e3f2fd' },
  PARTIAL_RECEIVED:  { text: '部分入库', color: '#e65100', bg: '#fff3e0' },
};

export { sortReceivingOrders } from '../warehouseList.logic';

export function getOrderQuantityPresentation(order: PurchaseOrder) {
  const items = order.items || [];
  const normalizedUnits = items.map((item) => item.unit?.trim()).filter(Boolean) as string[];
  const units = new Set(normalizedUnits);
  const uniformUnit = items.length > 0 && normalizedUnits.length === items.length && units.size === 1
    ? normalizedUnits[0]
    : null;
  const planned = items.reduce((sum, item) => sum + (item.quantity || 0), 0);
  const received = items.reduce((sum, item) => sum + (item.receivedQuantity || 0), 0);
  return { items, uniformUnit, planned, received, pending: Math.max(0, planned - received) };
}

export default function WHPurchaseReceiveListScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId;

  const [orders, setOrders] = useState<PurchaseOrder[]>([]);
  const [arrivalTasks, setArrivalTasks] = useState<CustomerMaterialArrivalTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusHasMore, setStatusHasMore] = useState<Partial<Record<PurchaseOrder['status'], boolean>>>({
    FINANCE_APPROVED: true,
    PARTIAL_RECEIVED: true,
  });
  const statusPagesRef = useRef<Partial<Record<PurchaseOrder['status'], number>>>({
    FINANCE_APPROVED: 0,
    PARTIAL_RECEIVED: 0,
  });
  const hasMore = RECEIVABLE_STATUSES.some((status) => statusHasMore[status]);
  const [loadingMore, setLoadingMore] = useState(false);
  // 区分"请求失败"(权限/网络/服务器) vs "请求成功但确实没有待收货单" — 前者绝不能静默当空态显示.
  const [loadError, setLoadError] = useState<LoadFailure | null>(null);
  const [arrivalLoadError, setArrivalLoadError] = useState<LoadFailure | null>(null);

  const PAGE_SIZE = 20;

  // ============================================================
  // 数据加载: 并行拉三个状态, 合并排序 (同状态按业务时间倒序)
  // ============================================================
  const fetchOrders = useCallback(
    async (pageNum: number, append: boolean) => {
      try {
        const requests = RECEIVABLE_STATUSES.map((status) => ({
          status,
          page: append ? (statusPagesRef.current[status] ?? 0) + 1 : pageNum,
        }));
        const results = await Promise.allSettled(
          requests.map(({ status, page }) =>
            purchaseApiClient.getOrdersByStatus(
              status,
              { page, size: PAGE_SIZE },
              factoryId,
            ),
          ),
        );

        const rejectedResults = results.filter(
          (r): r is PromiseRejectedResult => r.status === 'rejected',
        );
        const fulfilledCount = results.length - rejectedResults.length;
        const firstRejected = rejectedResults[0];

        // 全部 3 个状态的请求都失败 (而不是"某状态碰巧没有单据") — 这是请求本身失败
        // (权限不足 / 网络 / 服务器错误), 不是"工厂真的没有待收货采购单". Promise.allSettled
        // 会吞掉每个 rejection 静默过滤, 之前这里直接当空结果处理 → 仓库员被 403 拒绝时
        // 页面显示"暂无待收货采购单", 看不出是权限问题 (fool-proof-design 4位一体违规:
        // 静默降级、无 next-action). 现在显式区分, loud 展示且不清空已有数据(翻页场景).
        if (fulfilledCount === 0 && firstRejected) {
          const info = describeListFailure(firstRejected.reason);
          setLoadError(info);
          if (append) {
            // 翻页失败: 保留已加载列表，只提示，不清空
            handleError(firstRejected.reason, {
              title: info.title,
              customMessage: info.hint ? `${info.message}\n${info.hint}` : info.message,
            });
          }
          return;
        }

        if (firstRejected) {
          const info = describeListFailure(firstRejected.reason);
          setLoadError(info);
          if (append) {
            handleError(firstRejected.reason, {
              title: info.title,
              customMessage: info.hint ? `${info.message}\n${info.hint}` : info.message,
            });
          }
        } else if (!append) {
          setLoadError(null);
        }

        const merged: PurchaseOrder[] = [];
        results.forEach((r, index) => {
          if (r.status === 'fulfilled' && r.value?.data?.content) {
            const request = requests[index];
            if (!request) return;
            merged.push(...r.value.data.content);
            const status = request.status;
            const requestedPage = request.page;
            statusPagesRef.current[status] = requestedPage;
            setStatusHasMore((previous) => ({
              ...previous,
              [status]: requestedPage < (r.value.data.totalPages ?? requestedPage),
            }));
          }
        });

        const sortedPage = sortReceivingOrders(merged);

        if (append) {
          setOrders((prev) => {
            // 去重 (同一 page 不同 status 可能有重叠)
            const existing = new Set(prev.map((o) => o.id));
            return sortReceivingOrders([...prev, ...sortedPage.filter((o) => !existing.has(o.id))]);
          });
        } else if (firstRejected) {
          setOrders((prev) => {
            // 部分来源失败时保留旧列表，同时补入本次成功来源，避免刷新后丢任务。
            const existing = new Set(prev.map((o) => o.id));
            return prev.length > 0
              ? sortReceivingOrders([...prev, ...sortedPage.filter((o) => !existing.has(o.id))])
              : sortedPage;
          });
        } else {
          setOrders(sortedPage);
        }
      } catch (err) {
        handleError(err, { title: '加载采购单失败' });
      }
    },
    [factoryId],
  );

  const fetchArrivalTasks = useCallback(async () => {
    try {
      const response = await warehouseReceivingApiClient.listCustomerMaterialArrivalTasks(
        undefined,
        factoryId,
      );
      setArrivalTasks(response.data || []);
      setArrivalLoadError(null);
    } catch (error) {
      setArrivalLoadError(describeListFailure(error));
    }
  }, [factoryId]);

  useFocusEffect(
    useCallback(() => {
      let active = true;
      void (async () => {
        setLoading(true);
        statusPagesRef.current = { FINANCE_APPROVED: 0, PARTIAL_RECEIVED: 0 };
        setStatusHasMore({ FINANCE_APPROVED: true, PARTIAL_RECEIVED: true });
        await Promise.all([fetchOrders(1, false), fetchArrivalTasks()]);
        if (active) setLoading(false);
      })();
      return () => {
        active = false;
      };
    }, [fetchArrivalTasks, fetchOrders]),
  );

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    statusPagesRef.current = { FINANCE_APPROVED: 0, PARTIAL_RECEIVED: 0 };
    setStatusHasMore({ FINANCE_APPROVED: true, PARTIAL_RECEIVED: true });
    await Promise.all([fetchOrders(1, false), fetchArrivalTasks()]);
    setRefreshing(false);
  }, [fetchArrivalTasks, fetchOrders]);

  const onLoadMore = useCallback(async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    await fetchOrders(1, true);
    setLoadingMore(false);
  }, [loadingMore, hasMore, fetchOrders]);

  // ============================================================
  // 点击选单 → 跳到收货页
  // ============================================================
  const handleSelect = useCallback(
    (order: PurchaseOrder) => {
      navigation.navigate('WHReceiptCreate', {
        purchaseOrderId: order.id,
        orderNumber: order.orderNumber,
      });
    },
    [navigation],
  );

  const handleSelectArrival = useCallback(
    (task: CustomerMaterialArrivalTask) => {
      navigation.navigate('WHUnorderedInboundReceive', {
        noticeId: task.sourceId,
        noticeNumber: task.sourceNumber,
      });
    },
    [navigation],
  );

  type ReceivingListItem =
    | { kind: 'arrival'; task: CustomerMaterialArrivalTask }
    | { kind: 'purchase'; order: PurchaseOrder };

  const receivingItems = useMemo<ReceivingListItem[]>(
    () => [
      ...arrivalTasks.map((task) => ({ kind: 'arrival' as const, task })),
      ...orders.map((order) => ({ kind: 'purchase' as const, order })),
    ],
    [arrivalTasks, orders],
  );

  // ============================================================
  // 渲染单张卡片
  // ============================================================
  const renderItem = useCallback(
    ({ item }: { item: ReceivingListItem }) => {
      if (item.kind === 'arrival') {
        const task = item.task;
        return (
          <TouchableRipple
            onPress={() => handleSelectArrival(task)}
            accessibilityLabel={`处理无订单入库预告 ${task.sourceNumber}`}
            rippleColor="rgba(76, 175, 80, 0.15)"
          >
            <Card style={[styles.card, styles.arrivalCard]}>
              <Card.Content>
                <View style={styles.cardHeaderRow}>
                  <Text style={styles.orderNumber}>{task.sourceNumber}</Text>
                  <Chip compact style={styles.arrivalChip} textStyle={styles.arrivalChipText}>
                    无订单入库
                  </Chip>
                </View>
                <View style={styles.metaRow}>
                  <MaterialCommunityIcons name="account-box-outline" size={14} color="#718096" />
                  <Text style={styles.metaText}>{task.customerName || '未指定客户'}</Text>
                </View>
                <View style={styles.metaRow}>
                  <MaterialCommunityIcons name="package-down" size={14} color="#718096" />
                  <Text style={styles.metaText}>
                    {UNORDERED_INBOUND_REASON_LABEL[task.inboundReason] || task.inboundReason}
                    {' · '}{task.statusLabel}
                  </Text>
                </View>
                {task.expectedArrivalAt && (
                  <View style={styles.metaRow}>
                    <MaterialCommunityIcons name="calendar-clock" size={14} color="#718096" />
                    <Text style={styles.metaText}>预计到货：{task.expectedArrivalAt.replace('T', ' ')}</Text>
                  </View>
                )}
                {task.activeReceiptCount > 0 && (
                  <Text style={styles.handoffText}>
                    已有 {task.activeReceiptCount} 次入库，可由仓管或管理员继续处理
                  </Text>
                )}
                <View style={styles.actionRow}>
                  <Button
                    mode="contained"
                    compact
                    icon="package-down"
                    onPress={() => handleSelectArrival(task)}
                    style={styles.receiveButton}
                    labelStyle={styles.receiveButtonLabel}
                  >
                    核实并入库
                  </Button>
                </View>
              </Card.Content>
            </Card>
          </TouchableRipple>
        );
      }

      const order = item.order;
      const statusConf = STATUS_LABEL[order.status] ?? {
        text: order.status,
        color: '#616161',
        bg: '#f5f5f5',
      };

      const quantity = getOrderQuantityPresentation(order);

      return (
        <TouchableRipple
          onPress={() => handleSelect(order)}
          accessibilityLabel={`选择采购单 ${order.orderNumber} 进行收货`}
          rippleColor="rgba(76, 175, 80, 0.15)"
        >
          <Card style={styles.card}>
            <Card.Content>
              {/* 顶行: 订单号 + 状态 Chip */}
              <View style={styles.cardHeaderRow}>
                <Text style={styles.orderNumber}>{order.orderNumber}</Text>
                <Chip
                  compact
                  style={[styles.statusChip, { backgroundColor: statusConf.bg }]}
                  textStyle={[styles.statusText, { color: statusConf.color }]}
                >
                  {statusConf.text}
                </Chip>
              </View>

              {/* 防呆 Rule 2: 供应商 + 期望交货日 (上下文) */}
              <View style={styles.metaRow}>
                <MaterialCommunityIcons name="factory" size={14} color="#718096" />
                <Text style={styles.metaText}>
                  {order.supplierName || '未知供应商'} · 共 {quantity.items.length} 项
                </Text>
              </View>
              {order.expectedDeliveryDate && (
                <View style={styles.metaRow}>
                  <MaterialCommunityIcons name="calendar-clock" size={14} color="#718096" />
                  <Text style={styles.metaText}>
                    期望交货: {order.expectedDeliveryDate}
                  </Text>
                </View>
              )}

              {/* 防呆 Rule 1: 待收数量汇总 */}
              {quantity.items.length > 0 && quantity.uniformUnit && (
                <View style={styles.qtyRow}>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>订单合计</Text>
                    <Text style={styles.qtyStatValue}>{quantity.planned} {quantity.uniformUnit}</Text>
                  </View>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>已收</Text>
                    <Text style={[styles.qtyStatValue, { color: '#2e7d32' }]}>
                      {quantity.received} {quantity.uniformUnit}
                    </Text>
                  </View>
                  <View style={styles.qtyStat}>
                    <Text style={styles.qtyStatLabel}>待收</Text>
                    <Text
                      style={[
                        styles.qtyStatValue,
                        { color: quantity.pending > 0 ? '#e65100' : '#757575' },
                      ]}
                    >
                      {quantity.pending} {quantity.uniformUnit}
                    </Text>
                  </View>
                </View>
              )}

              {!quantity.uniformUnit && quantity.items.length > 0 && (
                <Text style={styles.mixedUnitHint}>多单位订单，请按下面明细逐项核对</Text>
              )}
              <View style={styles.itemLines}>
                {quantity.items.slice(0, 3).map((line) => {
                  const pending = Math.max(0, (line.quantity || 0) - (line.receivedQuantity || 0));
                  const unit = line.unit || '单位未标注';
                  return (
                    <View key={line.id} style={styles.itemLine}>
                      <Text style={styles.itemName}>{line.materialName || line.materialTypeId}</Text>
                      <Text style={styles.itemQuantity}>
                        应收 {line.quantity || 0} {unit} · 已收 {line.receivedQuantity || 0} {unit} · 待收 {pending} {unit}
                      </Text>
                    </View>
                  );
                })}
                {quantity.items.length > 3 && <Text style={styles.moreItems}>还有 {quantity.items.length - 3} 项，点进后查看</Text>}
                {quantity.items.length === 0 && <Text style={styles.itemsHint}>暂无物料明细</Text>}
              </View>

              <View style={styles.actionRow}>
                <Button
                  mode="contained"
                  compact
                  icon="check-circle-outline"
                  onPress={() => handleSelect(order)}
                  style={styles.receiveButton}
                  labelStyle={styles.receiveButtonLabel}
                >
                  开始收货
                </Button>
              </View>
            </Card.Content>
          </Card>
        </TouchableRipple>
      );
    },
    [handleSelect, handleSelectArrival],
  );

  // ============================================================
  // Footer loader
  // ============================================================
  const ListFooter = useCallback(
    () =>
      loadingMore ? (
        <View style={styles.footerLoader}>
          <ActivityIndicator size="small" />
          <Text style={styles.footerText}>加载更多...</Text>
        </View>
      ) : null,
    [loadingMore],
  );

  const ListEmpty = useCallback(
    () =>
      loading ? null : (
        <View style={styles.emptyContainer}>
          <MaterialCommunityIcons
            name="package-variant-closed"
            size={56}
            color="#bdbdbd"
          />
          <Text style={styles.emptyTitle}>暂无待收货任务</Text>
          <Text style={styles.emptyHint}>
            已通过财务审核的采购单和运营创建的无订单入库预告会在此显示
          </Text>
          <Button
            mode="outlined"
            icon="refresh"
            onPress={onRefresh}
            style={{ marginTop: 16 }}
          >
            刷新
          </Button>
        </View>
      ),
    [loading, onRefresh],
  );

  // ============================================================
  // 加载失败态 — 与"暂无待收货采购单"视觉上明确区分 (红色警示图标 + 具体后端原因 +
  // next-action 提示), 防止仓库员把"无权限"误当"工厂没有待收货单据". (fool-proof-design
  // Rule 5 + 4位一体)
  // ============================================================
  const LoadErrorView = useCallback(() => {
    const visibleError = loadError || arrivalLoadError;
    if (!visibleError) return null;
    return (
      <View style={styles.errorContainer}>
        <MaterialCommunityIcons
          name={visibleError.isPermission ? 'lock-alert-outline' : 'alert-circle-outline'}
          size={56}
          color="#c62828"
        />
        <Text style={styles.errorTitle}>{visibleError.title}</Text>
        <Text style={styles.errorMessage}>{visibleError.message}</Text>
        {visibleError.hint && <Text style={styles.errorHint}>{visibleError.hint}</Text>}
        <Button
          mode="contained"
          icon="refresh"
          onPress={onRefresh}
          style={{ marginTop: 16 }}
        >
          重试
        </Button>
      </View>
    );
  }, [arrivalLoadError, loadError, onRefresh]);

  const PartialFailureNotice = useCallback(() => {
    if (!loadError && !arrivalLoadError) return null;
    const hiddenSource = loadError ? '采购待办' : '无订单入库待办';
    return (
      <View style={styles.partialWarning}>
        <MaterialCommunityIcons name="alert-outline" size={18} color="#B45309" />
        <Text style={styles.partialWarningText}>{hiddenSource}加载失败，当前仅显示已加载任务。请点击重试。</Text>
        <Button mode="text" compact onPress={onRefresh}>重试</Button>
      </View>
    );
  }, [arrivalLoadError, loadError]);

  // ============================================================
  // Render
  // ============================================================
  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="待收货任务" subtitle="采购与无订单入库统一处理" />
        {receivingItems.length > 0 && (
          <Badge style={styles.badge}>{receivingItems.length}</Badge>
        )}
      </Appbar.Header>

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>加载待收货任务中...</Text>
        </View>
      ) : receivingItems.length === 0 && loadError && arrivalLoadError ? (
        <LoadErrorView />
      ) : (
        <FlatList
          data={receivingItems}
          keyExtractor={(item) => item.kind === 'arrival' ? `arrival-${item.task.taskId}` : `purchase-${item.order.id}`}
          renderItem={renderItem}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
          onEndReached={onLoadMore}
          onEndReachedThreshold={0.3}
          ListHeaderComponent={PartialFailureNotice}
          ListFooterComponent={ListFooter}
          ListEmptyComponent={ListEmpty}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f7fa' },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
  loadingText: { fontSize: 14, color: '#666' },

  listContent: { padding: 12, paddingBottom: 32 },

  card: { marginBottom: 12, borderRadius: 10 },
  arrivalCard: { borderLeftWidth: 4, borderLeftColor: '#1890FF' },
  arrivalChip: { height: 24, borderRadius: 12, backgroundColor: '#E3F2FD' },
  arrivalChipText: { fontSize: 12, lineHeight: 14, color: '#1565C0' },
  handoffText: { color: '#1565C0', fontSize: 12, lineHeight: 18, marginVertical: 8 },

  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  orderNumber: { fontSize: 15, fontWeight: '700', color: '#1a202c', flex: 1, marginRight: 8 },

  statusChip: { height: 24, borderRadius: 12 },
  statusText: { fontSize: 12, lineHeight: 14 },

  metaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    marginBottom: 3,
  },
  metaText: { fontSize: 13, color: '#718096' },

  qtyRow: {
    flexDirection: 'row',
    backgroundColor: '#f7fafc',
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 4,
    marginTop: 8,
    marginBottom: 6,
  },
  qtyStat: { flex: 1, alignItems: 'center' },
  qtyStatLabel: { fontSize: 11, color: '#718096' },
  qtyStatValue: { fontSize: 16, fontWeight: '700', color: '#2d3748', marginTop: 2 },

  itemsHint: { fontSize: 12, color: '#a0aec0', lineHeight: 18, marginBottom: 8 },
  mixedUnitHint: { color: '#B45309', fontSize: 12, marginTop: 8, marginBottom: 4 },
  itemLines: { gap: 6, marginBottom: 8 },
  itemLine: { backgroundColor: '#F7FAFC', borderRadius: 6, padding: 8 },
  itemName: { color: '#334155', fontSize: 13, fontWeight: '600' },
  itemQuantity: { color: '#475569', fontSize: 12, lineHeight: 18, marginTop: 3 },
  moreItems: { color: '#64748B', fontSize: 12 },

  actionRow: { flexDirection: 'row', justifyContent: 'flex-end' },
  receiveButton: { borderRadius: 20 },
  receiveButtonLabel: { fontSize: 13 },

  footerLoader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 16,
    gap: 8,
  },
  footerText: { fontSize: 13, color: '#718096' },

  emptyContainer: {
    alignItems: 'center',
    paddingTop: 60,
    paddingHorizontal: 32,
  },
  emptyTitle: { fontSize: 16, fontWeight: '600', color: '#4a5568', marginTop: 16 },
  emptyHint: {
    fontSize: 13,
    color: '#718096',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },

  // 加载失败态 (403/网络/服务器) — 红色警示, 与上方中性灰的"暂无数据"空态明确区分.
  errorContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingTop: 60,
    paddingHorizontal: 32,
  },
  errorTitle: { fontSize: 16, fontWeight: '700', color: '#c62828', marginTop: 16 },
  errorMessage: {
    fontSize: 14,
    color: '#4a5568',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },
  errorHint: {
    fontSize: 13,
    color: '#e65100',
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },
  partialWarning: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: '#FFFBEB',
    borderColor: '#FDE68A',
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    marginBottom: 12,
  },
  partialWarningText: { color: '#92400E', fontSize: 12, flex: 1, lineHeight: 18 },

  badge: { marginRight: 12, backgroundColor: '#1976d2' },
});
