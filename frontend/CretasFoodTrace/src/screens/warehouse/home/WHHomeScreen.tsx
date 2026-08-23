/**
 * 仓储工作台首页
 * 功能: 出入库任务概览、今日统计、库存预警
 * 参照: /docs/prd/prototype/warehouse/index.html
 *
 * API集成:
 * - dashboardAPI - 获取仪表板概览统计
 * - shipmentApiClient - 获取发货任务列表
 * - materialBatchApiClient - 获取入库批次和库存预警
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import {
  Text,
  Card,
  Surface,
  Icon,
  Chip,
  SegmentedButtons,
  ActivityIndicator,
  useTheme,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { WHHomeStackParamList } from '../../../types/navigation';
import { useAuthStore } from '../../../store/authStore';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { dashboardAPI } from '../../../services/api/dashboardApiClient';
import { shipmentApiClient, ShipmentRecord } from '../../../services/api/shipmentApiClient';
import { materialBatchApiClient, MaterialBatch } from '../../../services/api/materialBatchApiClient';
import { purchaseApiClient, PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { handleError, getErrorMsg } from '../../../utils/errorHandler';
import { logger } from '../../../utils/logger';
import { formatDate, formatNumberWithCommas } from '../../../utils/formatters';
import { toBatchWorkStatus } from '../../../utils/materialBatchStatus';

// 主题色
/**
 * 「待收货」的采购单状态 —— 与 WHPurchaseReceiveListScreen 保持同一口径。
 * ⚠️ 两处若漂开，首页计数和列表页就会对不上（本仓形态 D 的常见长相）。
 */
const RECEIVABLE_STATUSES: PurchaseOrder['status'][] = ['APPROVED', 'FINANCE_APPROVED', 'PARTIAL_RECEIVED'];

const THEME_COLOR = '#4CAF50';

// 类型定义
interface OutboundTask {
  id: string;
  orderId: string;
  customer: string;
  product: string;
  quantity: number;
  unit: string;
  status: 'waiting' | 'packing' | 'packed' | 'shipped';
  dispatchTime: string;
  urgent: boolean;
}

interface InboundTask {
  id: string;
  batchId: string;
  supplier: string;
  material: string;
  quantity: number;
  unit: string;
  status: 'pending' | 'arrived' | 'inspecting' | 'completed';
  arrivalTime: string;
}

interface AlertItem {
  id: string;
  materialName: string;
  materialType: string;
  currentStock: number;
  safetyStock: number;
  unit: string;
  isExpiring: boolean;
}

type NavigationProp = NativeStackNavigationProp<WHHomeStackParamList>;

export default function WHHomeScreen() {
  const { t } = useTranslation('warehouse');
  const navigation = useNavigation<NavigationProp>();
  const theme = useTheme();
  const { user } = useAuthStore();
  const { isScreenEnabled } = useFactoryFeatureStore();

  // 状态
  const [activeTab, setActiveTab] = useState<'outbound' | 'inbound'>('outbound');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 数据
  const [outboundTasks, setOutboundTasks] = useState<OutboundTask[]>([]);
  const [inboundTasks, setInboundTasks] = useState<InboundTask[]>([]);
  // M-11 (2026-08-20): 待处理入库任务的真实条数 —— 与 inboundTasks 分开，
  // 后者为显示用途只保留前5条，用 inboundTasks.length 当"入库任务(N)"的
  // N 在待处理任务超过5条时会被砍掉超出部分。
  const [pendingInboundCount, setPendingInboundCount] = useState(0);
  // M-21 (2026-08-20): 待发货**总条数**（后端 totalElements），⛔ 不是 outboundTasks.length
  // —— 那个请求写死 size:10，用它当总数时 N 永远 ≤ 10。与 M-11 修的入库侧同形。
  const [pendingOutboundCount, setPendingOutboundCount] = useState(0);
  const [alerts, setAlerts] = useState<AlertItem[]>([]);
  const [stats, setStats] = useState({
    todayInbound: 0, // 今日入库数量总和 (kg) —— M-11: 之前这里存的是批次数(COUNT)，套了 kg 单位
    todayInboundOrders: 0, // 今日入库批次数 ("N 单") —— M-11 新增，与上面的 kg 数分开
    /*
     * 🔴 M-21 (2026-08-20): 这里原来叫 todayOutbound，取的是
     * `overview.todayStats.ordersCompleted`，而后端那个字段是
     * `todayStats.put("ordersCompleted", completedBatches)` —— **今日完成的生产批次数**。
     *
     * 它被当成「今日出库」显示，还套了 **kg** 单位。三处都不对:
     *   ① 是「个数」不是重量, 却挂着 kg
     *   ② 是**生产**完成数, 与出库/发货毫无关系
     *   ③ 标签写「今日出库」, 而这一栏讲的是发货
     *
     * ⚠️ 与 M-11 的「把批数当 kg」是同一个形状 —— M-11 只修了入库那一半。
     * ⇒ 直接**删掉这个数**。App 侧目前**没有**任何真实的「今日发货量」来源
     *   (shipment 列表按 status 拉、不带日期聚合)，
     *   ⛔ 而拿手上的字段凑一个出来，正是这条缺陷本身。
     */
    alertCount: 0,
  });

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setError(null);
      logger.info('[WHHomeScreen] 开始加载仓储首页数据');

      // 并行加载所有数据
      const [
        shipmentsResult,
        batchesResult,
        lowStockResult,
        expiringResult,
        dashboardResult,
      ] = await Promise.allSettled([
        // 1. 获取发货任务 (待发货/已发货)
        // /shipments 后端 0-based 分页; 传 page:1 会漏掉第 0 页 (待发货任务全在 page 0)
        shipmentApiClient.getShipments({ status: 'pending', page: 0, size: 10 }),
        /*
         * 2. 待收货任务。
         *
         * 🔴 2026-08-20 换掉了数据源。原来拉的是 `getMaterialBatches`（物料批次），
         * 而**批次是「入库之后产生的东西」，不是「待入库的任务」** —— 拿它当待办，
         * 界面上就会冒出「质检中 / 已预留 / 已用完」这类**批次生命周期**状态
         * （Steve：「理论上就没有这个东西，一个任务就是待入库」）。
         *
         * ⚠️ M-11 和 M-23 都是在这个错误数据源上打补丁：先过滤已完成、再补全状态映射，
         * 数字是准了，但**语义一直是错的**。⇒ 改成与「待收货任务」列表页同一个来源。
         */
        Promise.allSettled(
          RECEIVABLE_STATUSES.map((st) =>
            purchaseApiClient.getOrdersByStatus(st, { page: 1, size: 20 }, user?.factoryId),
          ),
        ),
        // 3. 获取低库存预警
        materialBatchApiClient.getLowStockBatches(),
        // 4. 获取即将过期批次
        materialBatchApiClient.getExpiringBatches(7),
        // 5. 获取仪表板概览
        dashboardAPI.getDashboardOverview('today'),
      ]);

      // 处理发货任务
      if (shipmentsResult.status === 'fulfilled' && shipmentsResult.value?.data) {
        const shipments = shipmentsResult.value.data;
        const mappedTasks: OutboundTask[] = shipments.map((s: ShipmentRecord) => ({
          id: s.id,
          orderId: s.shipmentNumber || s.orderNumber || s.id,
          customer: s.customerId || t('messages.unknownCustomer'),
          product: s.productName || '商品',
          quantity: s.quantity || 0,
          unit: s.unit || 'kg',
          status: mapShipmentStatus(s.status),
          dispatchTime: s.shipmentDate ? `${formatDate(s.shipmentDate)} 发出` : t('messages.toBeArranged'),
          urgent: false, // 后端没有urgent字段，默认false
        }));
        setOutboundTasks(mappedTasks);
        // M-21 (2026-08-20): tab 上的「发货任务 (N)」之前用 mappedTasks.length —— 那是
        // **这一页**的条数，而这里写死了 size:10，所以 N 永远 ≤ 10。
        // ⚠️ 与 M-11 修掉的入库侧是同一个形状（拿展示列表的长度当总数），
        //    当时只修了入库这一半。⇒ 用后端给的 totalElements。
        setPendingOutboundCount(shipmentsResult.value.totalElements ?? mappedTasks.length);
        logger.info(`[WHHomeScreen] 加载发货任务成功: 展示${mappedTasks.length}条/共${shipmentsResult.value.totalElements}条`);
      } else {
        logger.warn('[WHHomeScreen] 加载发货任务失败，使用空列表');
        setOutboundTasks([]);
        setPendingOutboundCount(0);
      }

      // 处理待收货任务
      if (batchesResult.status === 'fulfilled') {
        const settled = (batchesResult.value ?? []) as PromiseSettledResult<
          { data?: { content?: PurchaseOrder[] } }
        >[];
        const orders: PurchaseOrder[] = settled
          .filter((r): r is PromiseFulfilledResult<{ data?: { content?: PurchaseOrder[] } }> =>
            r.status === 'fulfilled')
          .flatMap((r) => r.value?.data?.content ?? []);

        const mapped: InboundTask[] = orders.map((o) => {
          // 还差多少没收 —— 这才是「这单还要不要人处理」的判据
          const remaining = (o.items ?? []).reduce(
            (sum, it) => sum + Math.max(0, Number(it.quantity || 0) - Number(it.receivedQuantity || 0)),
            0,
          );
          const firstItem = (o.items ?? [])[0];
          return {
            id: o.id,
            batchId: o.orderNumber || o.id,
            supplier: o.supplierName || o.supplierId || t('messages.unknownSupplier'),
            material: (o.items ?? []).length > 1
              ? `${firstItem?.materialName ?? t('messages.unknownMaterial')} 等 ${(o.items ?? []).length} 项`
              : (firstItem?.materialName ?? t('messages.unknownMaterial')),
            quantity: remaining,
            unit: firstItem?.unit || 'kg',
            // 待收货任务只有一种状态：待入库。⛔ 不再借用批次生命周期状态。
            status: 'pending' as const,
            arrivalTime: o.expectedDeliveryDate
              ? `${t('home.inboundTask.expectedDate', { defaultValue: '预计到货' })}: ${formatDate(o.expectedDeliveryDate)}`
              : t('home.status.pending'),
          };
        }).filter((task) => task.quantity > 0);   // 已收满的不再是待办

        setInboundTasks(mapped.slice(0, 5)); // 只显示前5条
        setPendingInboundCount(mapped.length);
        logger.info(`[WHHomeScreen] 加载待收货任务成功: 共${mapped.length}条`);
      } else {
        logger.warn('[WHHomeScreen] 加载待收货任务失败，使用空列表');
        setInboundTasks([]);
        setPendingInboundCount(0);
      }

      // 处理库存预警
      const alertItems: AlertItem[] = [];
      if (lowStockResult.status === 'fulfilled') {
        const lowStockBatches = lowStockResult.value?.data ?? [];
        lowStockBatches.forEach((b: MaterialBatch) => {
          alertItems.push({
            id: b.id,
            materialName: b.materialName || b.batchNumber || t('messages.unknownMaterial'),
            materialType: b.storageType || 'fresh',
            currentStock: b.remainingQuantity || 0,
            safetyStock: 100, // 安全库存暂时硬编码
            unit: 'kg',
            isExpiring: false,
          });
        });
      }
      if (expiringResult.status === 'fulfilled') {
        const expiringBatches = expiringResult.value?.data ?? [];
        expiringBatches.forEach((b: MaterialBatch) => {
          alertItems.push({
            id: b.id,
            materialName: b.materialName || b.batchNumber || t('messages.unknownMaterial'),
            materialType: b.storageType || 'fresh',
            currentStock: b.remainingQuantity || 0,
            safetyStock: 100,
            unit: 'kg',
            isExpiring: true,
          });
        });
      }
      setAlerts(alertItems.slice(0, 5)); // 只显示前5个预警
      logger.info(`[WHHomeScreen] 加载库存预警成功: ${alertItems.length}条`);

      // 处理仪表板统计
      if (dashboardResult.status === 'fulfilled' && dashboardResult.value?.data) {
        const overview = dashboardResult.value.data;
        setStats({
          // M-11 (2026-08-20): materialReceivedQuantity 是 SUM(kg)，materialReceived 是 COUNT(批次/单)——
          // 之前这里把 COUNT 塞进了 kg 卡片，两个量不能混用同一个字段。
          todayInbound: overview.todayStats?.materialReceivedQuantity ?? 0,
          todayInboundOrders: overview.todayStats?.materialReceived || 0,
          // 🔴 M-21: 这里原来还有两个字段, 都被删了 ——
          //   todayOutbound  ← todayStats.ordersCompleted  (今日完成的**生产批次数**, 却按 kg 显示)
          //   pendingOutbound ← summary.activeBatches      (在产**批次数**, 却当「待发货 N 单」显示)
          // 两个都是「拿一个手边的数去顶一个我们没有的数」。待发货的真值改用
          // shipments 接口的 totalElements (pendingOutboundCount)。
          alertCount: overview.summary?.activeAlerts || alertItems.length,
        });
        logger.info('[WHHomeScreen] 加载仪表板统计成功');
      } else {
        // 使用默认统计
        setStats({
          todayInbound: 0,
          todayInboundOrders: 0,
          alertCount: alertItems.length,
        });
      }


    } catch (err) {
      logger.error('[WHHomeScreen] 加载数据失败:', err);
      setError('加载失败');
      handleError(err, { title: t('messages.loadFailed') });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // 状态映射函数
  const mapShipmentStatus = (status: string | undefined): 'waiting' | 'packing' | 'packed' | 'shipped' => {
    switch (status?.toLowerCase()) {
      case 'pending': return 'waiting';
      case 'shipped': return 'shipped';
      case 'delivered': return 'shipped';
      default: return 'waiting';
    }
  };

  // 2026-08-20: 映射收敛到 utils/materialBatchStatus（原来这里和入库列表页各写一份，且已漂）。
  const mapBatchStatus = (status: string | undefined): 'pending' | 'arrived' | 'inspecting' | 'completed' =>
    toBatchWorkStatus(status);

  useEffect(() => {
    loadData();
  }, [loadData]);

  /*
   * 跨 Stack 跳转 —— 首页在 WHHomeTab，而收货/发货的屏挂在 WHInboundTab / WHOutboundTab。
   * ⚠️ 用 getParent() 切到对应 tab，⛔ 不在首页栈里重复注册那些屏
   *   （重复注册会产生两份独立实例，回退行为和刷新时机都会对不上）。
   */
  const goToReceiving = useCallback(() => {
    const parent = navigation.getParent();
    if (parent) parent.navigate('WHInboundTab' as never);
  }, [navigation]);

  const goToDelivery = useCallback(() => {
    const parent = navigation.getParent();
    if (parent) parent.navigate('WHOutboundTab' as never);
  }, [navigation]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 渲染状态标签
  const renderStatusBadge = (status: string, type: 'outbound' | 'inbound') => {
    let label = '';
    let color = '';

    if (type === 'outbound') {
      switch (status) {
        case 'waiting':
          label = t('home.status.waiting');
          color = '#FF9800';
          break;
        case 'packing':
          label = t('home.status.packing');
          color = '#2196F3';
          break;
        case 'packed':
          label = t('home.status.packed');
          color = '#4CAF50';
          break;
        case 'shipped':
          label = t('home.status.shipped');
          color = '#9E9E9E';
          break;
      }
    } else {
      switch (status) {
        case 'pending':
          label = t('home.status.pending');
          color = '#FF9800';
          break;
        case 'arrived':
          label = t('home.status.arrived');
          color = '#2196F3';
          break;
        case 'inspecting':
          label = t('home.status.inspecting');
          color = '#9C27B0';
          break;
        case 'completed':
          label = t('home.status.completed');
          color = '#4CAF50';
          break;
      }
    }

    return (
      <Chip
        mode="flat"
        style={{ backgroundColor: color + '20' }}
        textStyle={{ color, fontSize: 12 }}
        compact
      >
        {label}
      </Chip>
    );
  };

  // 渲染发货任务卡片
  const renderOutboundCard = (task: OutboundTask) => (
    <TouchableOpacity
      key={task.id}
      onPress={() => navigation.navigate('OutboundDetail', { orderId: task.id })}
    >
      <Card style={[styles.taskCard, task.urgent && styles.urgentCard]}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleSmall" style={styles.orderId}>
              {task.orderId}
            </Text>
            {renderStatusBadge(task.status, 'outbound')}
          </View>

          <View style={styles.cardInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.customer')}</Text>
              <Text style={styles.infoValue}>{task.customer}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.product')}</Text>
              <Text style={styles.infoValue}>{task.product}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.outboundTask.quantity')}</Text>
              <Text style={styles.infoValue}>
                {task.quantity} {task.unit}
              </Text>
            </View>
          </View>

          <View style={styles.cardFooter}>
            <Text
              style={[
                styles.dispatchTime,
                task.urgent && styles.urgentText,
              ]}
            >
              {task.urgent && t('home.outboundTask.urgent')}
              {task.dispatchTime}
            </Text>
            {/*
              🔴 2026-08-20: 这个 onPress 原来只有一行注释「// 跳转到打包页面」——
              **空实现**。用户点了没有任何反应（本仓「界面在说一件它不做的事」的又一例）。

              ⚠️ 文案也改了：原来叫「开始打包」，而这套系统的发货逻辑里**没有打包这一步**
              （Steve：「我们本来的发货逻辑其实就是开始发货，发货就完事了」）。
              界面上多出一个业务里不存在的动作，比按钮点不动更误导。
            */}
            <TouchableOpacity
              style={styles.actionBtn}
              onPress={(e) => {
                e.stopPropagation();
                goToDelivery();
              }}
            >
              <Text style={styles.actionText}>{t('home.outboundTask.startShipping')}</Text>
            </TouchableOpacity>
          </View>
        </Card.Content>
      </Card>
    </TouchableOpacity>
  );

  // 渲染入库任务卡片
  const renderInboundCard = (task: InboundTask) => (
    <TouchableOpacity
      key={task.id}
      onPress={() => navigation.navigate('InboundDetail', { batchId: task.id })}
    >
      <Card style={styles.taskCard}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <Text variant="titleSmall" style={styles.orderId}>
              {task.batchId}
            </Text>
            {renderStatusBadge(task.status, 'inbound')}
          </View>

          <View style={styles.cardInfo}>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.supplier')}</Text>
              <Text style={styles.infoValue}>{task.supplier}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.goods')}</Text>
              <Text style={styles.infoValue}>{task.material}</Text>
            </View>
            <View style={styles.infoRow}>
              <Text style={styles.infoLabel}>{t('home.inboundTask.expectedQuantity')}</Text>
              <Text style={styles.infoValue}>
                {task.quantity} {task.unit}
              </Text>
            </View>
          </View>

          <View style={styles.cardFooter}>
            <Text
              style={[
                styles.dispatchTime,
                task.status === 'arrived' && styles.successText,
              ]}
            >
              {task.arrivalTime}
            </Text>
            {/* M-11 (2026-08-20): status === 'completed' 表示这批已经入库/确认完成
                (对应后端 available/depleted/expired)，不该再显示可操作按钮 ——
                之前无条件渲染，已完成的批次旁边还挂着一个点了没反应的"确认入库"。
                注: inboundTasks 现在已经在 loadData 里过滤掉了 completed 状态，
                这里的判断是第二道防线 (哪天这张卡被复用到别的、未过滤的数据源上
                也不会露出这个矛盾按钮)。 */}
            {/* 🔴 2026-08-20: 这个按钮原来**连 onPress 都没有** —— 纯装饰，点了当然没反应。 */}
            {task.status !== 'completed' && (
              <TouchableOpacity style={styles.actionBtn} onPress={goToReceiving}>
                <Text style={styles.actionText}>{t('home.inboundTask.confirmInbound')}</Text>
              </TouchableOpacity>
            )}
          </View>
        </Card.Content>
      </Card>
    </TouchableOpacity>
  );

  if (loading) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={THEME_COLOR} />
      </View>
    );
  }

  // 错误状态
  if (error && outboundTasks.length === 0 && inboundTasks.length === 0) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="cloud-off-outline" size={48} color="#C0C4CC" />
          <Text style={{ color: '#606266', marginTop: 12, fontSize: 14 }}>加载失败，请检查网络</Text>
          <TouchableOpacity
            style={{ marginTop: 16, paddingHorizontal: 20, paddingVertical: 8, backgroundColor: '#10b981', borderRadius: 6 }}
            onPress={() => loadData()}
          >
            <Text style={{ color: '#fff', fontSize: 14, fontWeight: '500' }}>重试</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <Surface style={styles.header}>
        <View style={styles.headerContent}>
          <Text variant="headlineSmall" style={styles.headerTitle}>
            {t('home.title')}
          </Text>
          <Text variant="bodySmall" style={styles.headerSubtitle}>
            {/* M-11 (2026-08-20): 之前用 inboundTasks.length —— 那是"入库任务"
                展示列表的条数(已过滤+砍到5条)，不是"今日入库了几单"。改用后端
                today's批次COUNT (stats.todayInboundOrders)，与任务列表条数解耦。 */}
            {t('home.headerSubtitle', { inbound: stats.todayInboundOrders, pending: pendingOutboundCount })}
          </Text>
        </View>
        {/*
          2026-08-20: 移除头部的通知铃铛 —— 它的 onPress 是空的 `() => {}}`，
          而全仓**没有任何通知屏**可跳（navigation.ts 里没有对应路由）。
          与同屏那两个「点了没反应」的按钮是同一个毛病：显示了一个做不到的动作。
          ⇒ 接不上就不显示；将来做了通知中心再加回来。
        */}
      </Surface>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            colors={[THEME_COLOR]}
          />
        }
      >
        {/*
          R-3 ④ (2026-08-20): 这里原来是「库存状态」业务流程图
          (WorkflowVisualizer modules={['inventory']} —— 就是那个
           「8 需关注 → 52 使用中 → 29 可用」的三节点卡)。

          Steve 的原话：「整个页面都弄得稍微简单一点，什么库存状态不需要了。」

          判据是「每张卡都要能回答『我现在该做什么』」——
          这张卡回答的是「库存长什么样」，不是一个动作，而且它占掉了整个首屏，
          把真正要干的活(发货任务 / 入库任务)挤到了折叠线以下。

          ⛔ 只在**仓储首页**移除，组件本身保留 —— 其它角色的首页还在用它。
          需要看库存分布仍可从下方「库存预警」或库存列表进入。
        */}

        {/* 领料调拨入口 */}
        <TouchableOpacity
          style={{ marginHorizontal: 16, marginBottom: 12, backgroundColor: '#fff', borderRadius: 12, padding: 16, flexDirection: 'row', alignItems: 'center', elevation: 2 }}
          onPress={() => navigation.navigate('TransferList' as never)}
        >
          <View style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: '#FFF7ED', alignItems: 'center', justifyContent: 'center', marginRight: 12 }}>
            <Icon source="package-down" size={24} color="#EA580C" />
          </View>
          <View style={{ flex: 1 }}>
            <Text variant="titleSmall" style={{ fontWeight: '600', color: '#333' }}>领料调拨</Text>
            <Text style={{ fontSize: 12, color: '#999', marginTop: 2 }}>查看和审批生产领料申请</Text>
          </View>
          <Icon source="chevron-right" size={24} color="#ccc" />
        </TouchableOpacity>

        {/* 任务Tab切换 */}
        {(isScreenEnabled('OutboundManagement') || isScreenEnabled('InboundManagement')) && (
        <View style={styles.tabContainer}>
          <SegmentedButtons
            value={activeTab}
            onValueChange={(value) => setActiveTab(value as 'outbound' | 'inbound')}
            buttons={[
              ...(isScreenEnabled('OutboundManagement') ? [{
                value: 'outbound' as const,
                label: `${t('home.tabs.outbound')} (${outboundTasks.length})`,
              }] : []),
              ...(isScreenEnabled('InboundManagement') ? [{
                value: 'inbound' as const,
                // M-11: 用未截断的待处理条数，不用展示列表的 inboundTasks.length
                label: `${t('home.tabs.inbound')} (${pendingInboundCount})`,
              }] : []),
            ]}
            style={styles.segmentedButtons}
          />
        </View>
        )}

        {/* 任务列表 */}
        {((activeTab === 'outbound' && isScreenEnabled('OutboundManagement')) ||
          (activeTab === 'inbound' && isScreenEnabled('InboundManagement'))) && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {activeTab === 'outbound'
                ? t('home.sections.todayOutbound')
                : t('home.sections.todayInbound')}
            </Text>
            <TouchableOpacity>
              <Text style={styles.sectionMore}>{t('home.sections.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {activeTab === 'outbound'
            ? outboundTasks.map(renderOutboundCard)
            : inboundTasks.map(renderInboundCard)}
        </View>
        )}

        {/*
          R-3 ② (2026-08-20): 统计卡**跟着 tab 走**。

          原来是固定的四张卡（今日入库 / 今日出库 / 待发货 / 库存预警），
          不管当前在哪个 tab 都摆在那儿 —— Steve 的原话:
          「出货那一栏应该只有今日出库或者待出库的事情，库存一点都不需要。」

          去掉的三张各有各的理由，⛔ 不是为了少而少:
          · 今日出库(kg)  —— 数本身是假的(生产批次数当 kg)，见 M-21，整卡删除
          · 库存预警(项)  —— 下面就有一整个「库存预警」区块，这里是同一个数说两遍
          · 另一 tab 的数 —— 当前步骤用不到
        */}
        <View style={styles.statsGrid}>
          {activeTab === 'inbound' ? (
            <>
              <Surface style={styles.statCard}>
                <View style={[styles.statIcon, { backgroundColor: '#E3F2FD' }]}>
                  <Text style={{ fontSize: 20 }}>📦</Text>
                </View>
                <Text style={styles.statTitle}>{t('home.stats.todayInbound')}</Text>
                {/* M-11: 后端 BigDecimal 会带 6 位小数 (如 10.000000)，格式化成 "10" / "10.50" */}
                <Text style={styles.statValue}>{formatNumberWithCommas(stats.todayInbound)}</Text>
                <Text style={styles.statUnit}>{t('home.units.kg')}</Text>
              </Surface>

              <Surface style={styles.statCard}>
                <View style={[styles.statIcon, { backgroundColor: '#FFF3E0' }]}>
                  <Text style={{ fontSize: 20 }}>⏳</Text>
                </View>
                <Text style={styles.statTitle}>{t('home.stats.pendingInbound')}</Text>
                <Text style={styles.statValue}>{pendingInboundCount}</Text>
                <Text style={styles.statUnit}>{t('home.units.orders')}</Text>
              </Surface>
            </>
          ) : (
            <Surface style={styles.statCard}>
              <View style={[styles.statIcon, { backgroundColor: '#FFF3E0' }]}>
                <Text style={{ fontSize: 20 }}>⏳</Text>
              </View>
              <Text style={styles.statTitle}>{t('home.stats.pendingOutbound')}</Text>
              {/* M-21: 真值来自 shipments 的 totalElements, ⛔ 不是在产批次数 */}
              <Text style={styles.statValue}>{pendingOutboundCount}</Text>
              <Text style={styles.statUnit}>{t('home.units.orders')}</Text>
            </Surface>
          )}
        </View>

        {/* 库存预警 */}
        {isScreenEnabled('AlertHandling') && (
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('home.alerts.title')}
            </Text>
            <TouchableOpacity>
              <Text style={styles.sectionMore}>{t('home.sections.viewAll')}</Text>
            </TouchableOpacity>
          </View>

          {alerts.map((alert) => (
            <TouchableOpacity
              key={alert.id}
              onPress={() => navigation.navigate('AlertHandle', { alertId: alert.id })}
            >
              <Surface style={styles.alertCard}>
                <View
                  style={[
                    styles.alertIcon,
                    {
                      backgroundColor: alert.isExpiring ? '#FFEBEE' : '#FFF3E0',
                    },
                  ]}
                >
                  <Text style={{ fontSize: 16 }}>
                    {alert.isExpiring ? '🔴' : '🟡'}
                  </Text>
                </View>
                <View style={styles.alertContent}>
                  <Text style={styles.alertName}>{alert.materialName}</Text>
                  <Text style={styles.alertDetail}>
                    {t('home.alerts.safetyStock')}: {alert.safetyStock}
                    {alert.unit}
                    {alert.isExpiring && ` | ${t('home.alerts.expiring')}`}
                  </Text>
                </View>
                <Text style={styles.alertValue}>
                  {alert.currentStock}
                  {alert.unit}
                </Text>
              </Surface>
            </TouchableOpacity>
          ))}
        </View>
        )}

        {/*
          2026-08-20: 温控监控整块移除（Steve：「暂时用不着」）。
          ⚠️ 它本来就一直是空的 —— 上面的加载逻辑写着「后端暂无温控API，显示空状态」，
          所以这张卡片长期显示「暂无温控设备数据」，占着首屏却回答不了「我现在该做什么」。
          ⛔ 只从**仓储首页**移除；TempMonitor 那个屏和路由保留，将来接了后端可直接挂回来。
        */}

        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: THEME_COLOR,
  },
  headerContent: {
    flex: 1,
  },
  headerTitle: {
    color: '#fff',
    fontWeight: 'bold',
  },
  headerSubtitle: {
    color: 'rgba(255,255,255,0.8)',
    marginTop: 2,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  tabContainer: {
    marginBottom: 16,
  },
  segmentedButtons: {
    backgroundColor: '#fff',
  },
  section: {
    marginBottom: 20,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontWeight: '600',
    color: '#333',
  },
  sectionMore: {
    color: THEME_COLOR,
    fontSize: 14,
  },
  taskCard: {
    marginBottom: 12,
    backgroundColor: '#fff',
    borderRadius: 12,
  },
  urgentCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#F44336',
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  orderId: {
    fontWeight: '600',
    color: '#333',
  },
  cardInfo: {
    marginBottom: 12,
  },
  infoRow: {
    flexDirection: 'row',
    marginBottom: 6,
  },
  infoLabel: {
    width: 60,
    color: '#999',
    fontSize: 13,
  },
  infoValue: {
    flex: 1,
    color: '#333',
    fontSize: 13,
  },
  cardFooter: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
    paddingTop: 12,
  },
  dispatchTime: {
    color: '#666',
    fontSize: 13,
  },
  urgentText: {
    color: '#F44336',
    fontWeight: '600',
  },
  successText: {
    color: '#4CAF50',
  },
  actionBtn: {
    backgroundColor: THEME_COLOR,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  actionText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '500',
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginBottom: 20,
    gap: 12,
  },
  statCard: {
    width: '47%',
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#fff',
    alignItems: 'center',
  },
  statIcon: {
    width: 40,
    height: 40,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 8,
  },
  statTitle: {
    fontSize: 12,
    color: '#666',
    marginBottom: 4,
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
  },
  statUnit: {
    fontSize: 12,
    color: '#999',
  },
  alertCard: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    borderRadius: 10,
    backgroundColor: '#fff',
    marginBottom: 10,
  },
  alertIcon: {
    width: 36,
    height: 36,
    borderRadius: 18,
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  alertContent: {
    flex: 1,
  },
  alertName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginBottom: 2,
  },
  alertDetail: {
    fontSize: 12,
    color: '#999',
  },
  alertValue: {
    fontSize: 16,
    fontWeight: '600',
    color: '#F44336',
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#4CAF50',
    marginRight: 6,
  },
  statusText: {
    fontSize: 13,
    color: '#4CAF50',
  },
});
