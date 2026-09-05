import React, { useEffect, useMemo, useState } from 'react';
import { Alert, ScrollView, StyleSheet, View } from 'react-native';
import { Appbar, ActivityIndicator, Card, Chip, List, Searchbar, SegmentedButtons, Text } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';

import { shipmentApiClient, ShipmentRecord, ShipmentStats } from '../../services/api/shipmentApiClient';
import { customerApiClient, Customer } from '../../services/api/customerApiClient';
import { useAuthStore } from '../../store/authStore';
import { logger } from '../../utils/logger';
import { settleAllIndependently } from '../../utils/settleAllIndependently';

const shipmentLogger = logger.createContextLogger('ShipmentManagement');

/**
 * Legacy shipment history is intentionally read-only.
 * New deliveries must be created from a sales order and confirmed by warehouse so batch
 * allocation and inventory deduction happen on the canonical sales-delivery chain.
 */
export default function ShipmentManagementScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();
  const [shipments, setShipments] = useState<ShipmentRecord[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [stats, setStats] = useState<ShipmentStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterStatus, setFilterStatus] = useState('all');

  const roleCode = user?.factoryUser?.role || user?.roleCode || 'viewer';
  const canView = user?.userType === 'platform'
    || ['factory_super_admin', 'permission_admin', 'department_admin'].includes(roleCode);

  /**
   * 🔴 原来这三路用 `Promise.all` 一起等。owner 2026-09-06 裁定「客户名单只有
   * 销售/财务/工厂管理员/运营 能看」之后, `GET .../customers` 加了权限门, 而本屏的
   * 可见角色里 `permission_admin` (L1 sales/finance 均为 '-') 会在客户那一路拿到 403 ——
   * `Promise.all` 整体 reject ⇒ **出货记录和统计也一并没了**, 整屏只剩一个
   * 「加载历史出货数据失败」。
   *
   * ⚠️ 「客户名字显示成 ID」是裁定的后果; 「整屏出货记录消失」是连坐, 不是裁定要的 ——
   * 出货记录本身跟客户名单的权限无关。⇒ 逐路独立 settle (同 #590 的
   * `ProductionPlanManagementScreen.loadOptions`)。
   *
   * 客户那一路失败时只降级成"显示 customerId 而不是客户名"(见 `customerNames` map 的
   * 兜底), ⛔ 不弹窗 —— 弹窗只留给"出货记录本身没拿到"这一种真失败, 保证最多一个 Alert。
   */
  const loadData = async () => {
    setLoading(true);
    const { values, failedKeys, failureReasons } = await settleAllIndependently({
      shipments: shipmentApiClient.getShipments({ factoryId: user?.factoryId, page: 0, size: 100 }),
      customers: customerApiClient.getCustomers({ factoryId: user?.factoryId, page: 1, size: 100 }),
      stats: shipmentApiClient.getShipmentStats(user?.factoryId),
    });

    if (values.shipments) setShipments(values.shipments.data || []);
    if (values.customers) setCustomers(values.customers.data || []);
    if (values.stats) setStats(values.stats);

    failedKeys.forEach((key) => {
      shipmentLogger.warn(`加载${String(key)}失败 (不影响其它路)`, {
        reason: String(failureReasons[key]),
      });
    });

    // 只有"出货记录"这一路失败才算这屏真的没数据 —— 客户名单/统计缺失都只是降级。
    if (failedKeys.includes('shipments')) {
      Alert.alert('错误', '加载历史出货数据失败');
    }

    setLoading(false);
  };

  useEffect(() => {
    loadData();
  }, []);

  const customerNames = useMemo(
    () => new Map(customers.map((customer) => [customer.id, customer.name])),
    [customers],
  );

  const filteredShipments = useMemo(() => shipments.filter((shipment) => {
    if (filterStatus !== 'all' && shipment.status !== filterStatus) return false;
    const query = searchQuery.trim().toLowerCase();
    if (!query) return true;
    return shipment.shipmentNumber?.toLowerCase().includes(query)
      || shipment.productName?.toLowerCase().includes(query)
      || shipment.trackingNumber?.toLowerCase().includes(query);
  }), [filterStatus, searchQuery, shipments]);

  if (!canView) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <Appbar.Header>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title="历史出货记录" />
        </Appbar.Header>
        <View style={styles.centered}>
          <List.Icon icon="lock" color="#999" />
          <Text>您没有权限访问此页面</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="历史出货记录" subtitle="只读" />
        <Appbar.Action icon="refresh" onPress={loadData} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        <Card style={styles.noticeCard}>
          <Card.Content>
            <Text variant="titleSmall">旧手工出货写链已停用</Text>
            <Text style={styles.noticeText}>
              新发货请从销售订单创建销售发货单，再由仓库在待确认发货单中确认实际数量。只有该流程会分配批次并扣减库存。
            </Text>
          </Card.Content>
        </Card>

        <Searchbar
          placeholder="搜索历史单号、产品、物流单号"
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.card}
        />
        <Card style={styles.card}>
          <Card.Content>
            <SegmentedButtons
              value={filterStatus}
              onValueChange={setFilterStatus}
              buttons={[
                { value: 'all', label: '全部' },
                { value: 'pending', label: '待发货' },
                { value: 'shipped', label: '已发货' },
                { value: 'delivered', label: '已送达' },
              ]}
            />
          </Card.Content>
        </Card>

        {stats && (
          <Card style={styles.card}>
            <Card.Content style={styles.statsRow}>
              <Stat value={stats.total} label="总数" />
              <Stat value={stats.pending} label="待发货" />
              <Stat value={stats.shipped} label="运输中" />
              <Stat value={stats.delivered} label="已送达" />
            </Card.Content>
          </Card>
        )}

        {loading ? (
          <View style={styles.centered}><ActivityIndicator size="large" /></View>
        ) : filteredShipments.length === 0 ? (
          <Card style={styles.card}><Card.Content><Text>暂无历史出货记录</Text></Card.Content></Card>
        ) : filteredShipments.map((shipment) => (
          <Card key={shipment.id} style={styles.card}>
            <Card.Content>
              <View style={styles.headerRow}>
                <View style={styles.flex}>
                  <Text variant="titleMedium">{shipment.shipmentNumber}</Text>
                  <Text>{shipment.productName}</Text>
                </View>
                <Chip compact>{statusLabel(shipment.status)}</Chip>
              </View>
              <Text style={styles.detail}>客户：{customerNames.get(shipment.customerId) || shipment.customerId}</Text>
              <Text style={styles.detail}>数量：{shipment.quantity} {shipment.unit}</Text>
              <Text style={styles.detail}>日期：{shipment.shipmentDate}</Text>
              {shipment.trackingNumber && (
                <Text style={styles.detail}>物流：{shipment.logisticsCompany || '-'} {shipment.trackingNumber}</Text>
              )}
            </Card.Content>
          </Card>
        ))}
        <View style={styles.bottomPadding} />
      </ScrollView>
    </SafeAreaView>
  );
}

function Stat({ value, label }: { value: number; label: string }) {
  return (
    <View style={styles.stat}>
      <Text variant="titleLarge">{value}</Text>
      <Text style={styles.detail}>{label}</Text>
    </View>
  );
}

function statusLabel(status: ShipmentRecord['status']): string {
  return ({ pending: '待发货', shipped: '已发货', delivered: '已送达', returned: '已退货' })[status] || status;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  content: { flex: 1 },
  centered: { padding: 40, alignItems: 'center', justifyContent: 'center' },
  card: { marginHorizontal: 16, marginTop: 12 },
  noticeCard: { margin: 16, marginBottom: 0, backgroundColor: '#fff8e1' },
  noticeText: { marginTop: 8, color: '#6d4c41', lineHeight: 20 },
  statsRow: { flexDirection: 'row', justifyContent: 'space-around' },
  stat: { alignItems: 'center' },
  headerRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  flex: { flex: 1 },
  detail: { color: '#666', marginTop: 6 },
  bottomPadding: { height: 24 },
});
