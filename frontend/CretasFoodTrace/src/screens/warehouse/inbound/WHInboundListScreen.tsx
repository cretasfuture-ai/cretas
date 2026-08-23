/** 仓管入库首页：采购收货任务是真值，物料批次不再冒充待收货任务。 */
import React, { useCallback, useMemo, useState } from 'react';
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

type NavigationProp = NativeStackNavigationProp<WHInboundStackParamList>;
export type ReceivingStatus = 'pending' | 'partial' | 'completed';
type FilterStatus = 'all' | ReceivingStatus;

export const INBOUND_ORDER_STATUSES: PurchaseOrder['status'][] = [
  'APPROVED',
  'FINANCE_APPROVED',
  'PARTIAL_RECEIVED',
  'COMPLETED',
];

export function toReceivingStatus(status: PurchaseOrder['status']): ReceivingStatus {
  if (status === 'PARTIAL_RECEIVED') return 'partial';
  if (status === 'COMPLETED') return 'completed';
  return 'pending';
}

const STATUS_PRIORITY: Record<ReceivingStatus, number> = { pending: 0, partial: 1, completed: 2 };

export function sortReceivingOrders(orders: PurchaseOrder[]): PurchaseOrder[] {
  return [...orders].sort((a, b) => {
    const statusDiff = STATUS_PRIORITY[toReceivingStatus(a.status)] - STATUS_PRIORITY[toReceivingStatus(b.status)];
    if (statusDiff !== 0) return statusDiff;
    const aDate = a.expectedDeliveryDate || a.updatedAt || a.createdAt || '';
    const bDate = b.expectedDeliveryDate || b.updatedAt || b.createdAt || '';
    return toReceivingStatus(a.status) === 'completed'
      ? bDate.localeCompare(aDate)
      : aDate.localeCompare(bDate);
  });
}

const STATUS_STYLE: Record<ReceivingStatus, { label: string; color: string; bg: string }> = {
  pending: { label: '待收货', color: '#1565C0', bg: '#E3F2FD' },
  partial: { label: '部分入库', color: '#E65100', bg: '#FFF3E0' },
  completed: { label: '已完成', color: '#2E7D32', bg: '#E8F5E9' },
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
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<FilterStatus>('all');
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      const results = await Promise.allSettled(
        INBOUND_ORDER_STATUSES.map((status) =>
          purchaseApiClient.getOrdersByStatus(status, { page: 1, size: 50 }, factoryId),
        ),
      );
      const fulfilled = results.filter(
        (result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof purchaseApiClient.getOrdersByStatus>>> =>
          result.status === 'fulfilled',
      );
      // 四类状态任意一路失败都不能显示“不完整但看起来正常”的计数。
      // 仓管会据此判断是否还有车要收，少一类比整页报错更危险。
      if (fulfilled.length !== INBOUND_ORDER_STATUSES.length) {
        const firstFailure = results.find((result): result is PromiseRejectedResult => result.status === 'rejected');
        throw firstFailure?.reason || new Error('待收货任务加载失败');
      }
      const unique = new Map<string, PurchaseOrder>();
      fulfilled.forEach(({ value }) => {
        (value?.data?.content || []).forEach((order) => unique.set(order.id, order));
      });
      setOrders(sortReceivingOrders([...unique.values()]));
      setLoadError(null);
    } catch (error) {
      setOrders([]);
      setLoadError(error instanceof Error ? error.message : '请检查网络或账号权限后重试');
      handleError(error, { title: '加载入库任务失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useFocusEffect(useCallback(() => { void loadData(); }, [loadData]));

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    void loadData();
  }, [loadData]);

  const stats = useMemo(() => ({
    all: orders.length,
    pending: orders.filter((order) => toReceivingStatus(order.status) === 'pending').length,
    partial: orders.filter((order) => toReceivingStatus(order.status) === 'partial').length,
    completed: orders.filter((order) => toReceivingStatus(order.status) === 'completed').length,
  }), [orders]);

  const visibleOrders = useMemo(() => orders.filter((order) => {
    if (filter !== 'all' && toReceivingStatus(order.status) !== filter) return false;
    const normalized = query.trim().toLowerCase();
    if (!normalized) return true;
    return order.orderNumber.toLowerCase().includes(normalized)
      || (order.supplierName || '').toLowerCase().includes(normalized)
      || (order.items || []).some((item) => (item.materialName || '').toLowerCase().includes(normalized));
  }), [filter, orders, query]);

  const openOrder = useCallback((order: PurchaseOrder) => {
    if (toReceivingStatus(order.status) === 'completed') return;
    navigation.navigate('WHReceiptCreate', {
      purchaseOrderId: order.id,
      orderNumber: order.orderNumber,
    });
  }, [navigation]);

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
            onPress={() => navigation.navigate('WHPurchaseReceiveList')} style={styles.primaryButton}>
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
            ['completed', '已完成', stats.completed],
          ] as const).map(([value, label, count]) => (
            <Chip key={value} selected={filter === value} onPress={() => setFilter(value)}
              style={[styles.filter, filter === value && styles.filterActive]}
              textStyle={filter === value ? styles.filterTextActive : undefined}>
              {label}({count})
            </Chip>
          ))}
        </ScrollView>

        <View style={styles.list}>
          {loadError ? (
            <Surface style={styles.empty} elevation={1}>
              <MaterialCommunityIcons name="alert-circle-outline" size={42} color="#C62828" />
              <Text style={styles.errorTitle}>入库任务加载失败</Text><Text style={styles.emptyHint}>{loadError}</Text>
              <Button mode="contained" icon="refresh" onPress={onRefresh}>重试</Button>
            </Surface>
          ) : visibleOrders.length === 0 ? (
            <Surface style={styles.empty} elevation={1}>
              <MaterialCommunityIcons name="package-variant-closed" size={42} color="#BDBDBD" />
              <Text style={styles.emptyTitle}>当前筛选下没有入库任务</Text>
            </Surface>
          ) : visibleOrders.map((order) => {
            const status = toReceivingStatus(order.status);
            const config = STATUS_STYLE[status];
            return (
              <TouchableRipple key={order.id} disabled={status === 'completed'} onPress={() => openOrder(order)}
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
                    <Text style={[styles.actionText, status === 'completed' && styles.doneText]}>{status === 'completed' ? '已全部入库' : '开始收货 ›'}</Text>
                  </View>
                </Surface>
              </TouchableRipple>
            );
          })}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
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
  filters: { paddingHorizontal: 16, paddingVertical: 12 },
  filter: { marginRight: 8, backgroundColor: '#FFF' },
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
  emptyTitle: { color: '#666', fontSize: 15, fontWeight: '600' },
  errorTitle: { color: '#C62828', fontSize: 16, fontWeight: '700' },
  emptyHint: { color: '#666', fontSize: 13, textAlign: 'center', lineHeight: 20 },
});
