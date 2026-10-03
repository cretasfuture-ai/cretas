import React, { useState, useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl, Alert, TouchableOpacity } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  Chip,
  DataTable,
  ActivityIndicator,
  Divider,
  Surface,
  SegmentedButtons,
  Button,
  IconButton,
  ProgressBar,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import DateTimePicker from '@react-native-community/datetimepicker';
import { useAuthStore } from '../../store/authStore';
import { processingApiClient, ProcessingBatch } from '../../services/api/processingApiClient';
import { dashboardAPI, ProductionPeriod, ProductionSummary } from '../../services/api/dashboardApiClient';
import { reportApiClient, RealtimeData, CapacityUtilizationReport } from '../../services/api/reportApiClient';
import { canvasApiClient } from '../../services/api/canvasApiClient';
import { getFactoryId, User } from '../../types/auth';
import { hasModulePermission } from '../../utils/permissionHelper';
import { handleError } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { ReportStackParamList } from '../../types/navigation';
import { formatDate } from '../../utils/formatters';

// 创建ProductionReport专用logger
const productionReportLogger = logger.createContextLogger('ProductionReport');

/**
 * 生产报表页面
 * 集成数据来源:
 * - processingApiClient: 加工批次数据、生产统计
 *
 * 展示内容:
 * - 生产总量统计
 * - 批次完成情况
 * - 日/周/月生产趋势
 */
type NavigationProp = NativeStackNavigationProp<ReportStackParamList>;

// 主标签类型
type MainTab = 'overview' | 'realtime' | 'capacity';

const REPORT_READ_MODULES = ['dashboard', 'warehouse', 'production', 'inventory', 'quality', 'finance'] as const;

function getCanonicalActorKey(user: User | null): string {
  if (!user) return 'anonymous';
  const actor = user.userType === 'factory' ? user.factoryUser : user.platformUser;
  const modulePermissions = user.userType === 'factory' ? user.factoryUser.modulePermissions : undefined;
  return JSON.stringify({
    userType: user.userType,
    userId: user.id,
    factoryId: getFactoryId(user),
    role: actor?.role ?? user.role ?? '',
    permissions: [...(actor?.permissions ?? [])].sort(),
    modulePermissions: modulePermissions
      ? Object.entries(modulePermissions).sort(([left], [right]) => left.localeCompare(right))
      : [],
  });
}

function hasProductionSummaryRead(user: User | null): boolean {
  if (!user) return false;
  const actor = user.userType === 'factory' ? user.factoryUser : user.platformUser;
  return REPORT_READ_MODULES.some((module) => hasModulePermission(user, module, 'read'))
    || (actor?.permissions ?? []).includes('procurement:price:view');
}

function hasFinancialReportRead(user: User | null): boolean {
  if (!user) return false;
  const actor = user.userType === 'factory' ? user.factoryUser : user.platformUser;
  return hasModulePermission(user, 'finance', 'read')
    || (actor?.permissions ?? []).includes('procurement:price:view');
}

function ProductionReportScreenContent() {
  const navigation = useNavigation<NavigationProp>();
  const { user } = useAuthStore();
  const { t } = useTranslation('reports');

  // 主标签状态
  const [mainTab, setMainTab] = useState<MainTab>('overview');
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [timeRange, setTimeRange] = useState<ProductionPeriod>('week');

  // 数据状态
  const [productionStats, setProductionStats] = useState<ProductionSummary | null>(null);
  const [productionStatsError, setProductionStatsError] = useState<string | null>(null);
  const [recentBatches, setRecentBatches] = useState<ProcessingBatch[]>([]);
  const [recentBatchesError, setRecentBatchesError] = useState<string | null>(null);

  // 实时监控数据状态
  const [realtimeData, setRealtimeData] = useState<RealtimeData | null>(null);
  const [realtimeLoading, setRealtimeLoading] = useState(false);
  const [realtimeError, setRealtimeError] = useState<string | null>(null);

  // 产能利用数据状态
  const [capacityData, setCapacityData] = useState<CapacityUtilizationReport | null>(null);
  const [capacityLoading, setCapacityLoading] = useState(false);
  const [capacityError, setCapacityError] = useState<string | null>(null);
  const requestGenerationRef = useRef(0);
  const ownerKey = useMemo(() => getCanonicalActorKey(user), [user]);

  // 同一屏的三个 tab 共用 owner generation；身份/工厂/角色变化时清空旧读数，
  // 任何迟到的旧请求都不能再回填任一 tab。
  useLayoutEffect(() => {
    requestGenerationRef.current += 1;
    setProductionStats(null);
    setProductionStatsError(null);
    setRecentBatches([]);
    setRecentBatchesError(null);
    setRealtimeData(null);
    setRealtimeError(null);
    setCapacityData(null);
    setCapacityError(null);
    setLoading(false);
    setRealtimeLoading(false);
    setCapacityLoading(false);
  }, [ownerKey]);

  const isLiveOwner = (expectedOwnerKey: string): boolean => {
    const liveUser = useAuthStore.getState().user;
    return expectedOwnerKey === ownerKey && getCanonicalActorKey(liveUser) === expectedOwnerKey;
  };

  /**
   * 加载生产数据
   */
  const loadProductionData = async () => {
    const generation = ++requestGenerationRef.current;
    const expectedOwnerKey = ownerKey;
    const currentUser = useAuthStore.getState().user;
    if (!isLiveOwner(expectedOwnerKey)) return;
    const canReadSummary = hasProductionSummaryRead(currentUser);
    if (!canReadSummary && !currentUser) {
      setProductionStats(null);
      setProductionStatsError(t('production.permissionDenied'));
      setRecentBatches([]);
      setRecentBatchesError(t('production.permissionDenied'));
      return;
    }
    setLoading(true);
    setProductionStats(null);
    setProductionStatsError(null);
    setRecentBatches([]);
    setRecentBatchesError(null);
    try {
      const factoryId = getFactoryId(currentUser);

      if (!factoryId) {
        Alert.alert(t('production.error'), t('production.cannotGetFactoryInfo'));
        return;
      }

      productionReportLogger.debug('加载生产报表数据', { timeRange, factoryId });

      const period: ProductionPeriod = timeRange;
      const [statisticsResult, accessResult] = await Promise.allSettled([
        canReadSummary
          ? dashboardAPI.getProductionStatistics({ period }, factoryId)
          : Promise.resolve({ success: false, data: null, message: t('production.permissionDenied') }),
        canvasApiClient.getUserModuleAccess(factoryId, currentUser?.id ?? ''),
      ]);

      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      if (statisticsResult.status === 'fulfilled') {
        const statisticsResponse = statisticsResult.value;
        const summary = statisticsResponse.success ? statisticsResponse.data?.productionSummary : null;
        if (summary) {
          setProductionStats(summary);
          setProductionStatsError(null);
          productionReportLogger.info('生产统计摘要加载成功', {
            totalBatches: summary.totalBatches,
            completionRate: summary.completionRate,
            factoryId,
            period,
          });
        } else {
          setProductionStats(null);
          setProductionStatsError(statisticsResponse.message || t('production.noStatsData'));
        }
      } else {
        setProductionStats(null);
        setProductionStatsError(statisticsResult.reason instanceof Error
          ? statisticsResult.reason.message : t('production.loadProductionDataFailed'));
      }

      if (accessResult.status !== 'fulfilled') {
        setRecentBatches([]);
        setRecentBatchesError(t('production.loadRecentBatchesFailed'));
      } else {
        const canReadSamples = accessResult.value.some(
          (access) => access.moduleCode === 'production_plan' && access.effectiveAllowed
        );
        if (!canReadSamples) {
          setRecentBatches([]);
          setRecentBatchesError(t('production.permissionDenied'));
        } else {
          try {
            const batchesResponse = await processingApiClient.getBatches({ page: 1, size: 10, factoryId });
            if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
            if (batchesResponse.success) {
              setRecentBatches(batchesResponse.data?.content ?? []);
              setRecentBatchesError(null);
            } else {
              setRecentBatches([]);
              setRecentBatchesError(batchesResponse.message || t('production.loadRecentBatchesFailed'));
            }
          } catch (sampleError) {
            if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
            setRecentBatches([]);
            setRecentBatchesError(sampleError instanceof Error
              ? sampleError.message : t('production.loadRecentBatchesFailed'));
          }
        }
      }
    } catch (error) {
      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      productionReportLogger.error('加载生产报表失败', error as Error, {
        factoryId: getFactoryId(useAuthStore.getState().user),
        timeRange,
      });
      handleError(error, {
        title: t('production.loadFailed'),
        customMessage: t('production.loadProductionDataFailed'),
      });
      setProductionStats(null);
      setProductionStatsError(error instanceof Error ? error.message : t('production.loadProductionDataFailed'));
    } finally {
      if (generation === requestGenerationRef.current && isLiveOwner(expectedOwnerKey)) setLoading(false);
    }
  };

  /**
   * 加载实时监控数据
   */
  const loadRealtimeData = async () => {
    const generation = ++requestGenerationRef.current;
    const expectedOwnerKey = ownerKey;
    const currentUser = useAuthStore.getState().user;
    if (!isLiveOwner(expectedOwnerKey)) return;
    if (!hasFinancialReportRead(currentUser)) {
      setRealtimeData(null);
      setRealtimeError(t('production.permissionDenied'));
      return;
    }
    setRealtimeLoading(true);
    setRealtimeData(null);
    setRealtimeError(null);
    try {
      const factoryId = getFactoryId(currentUser);
      if (!factoryId) {
        productionReportLogger.warn('无法获取工厂ID');
        return;
      }

      productionReportLogger.debug('加载实时监控数据', { factoryId });
      const response = await reportApiClient.getRealtimeData(factoryId);
      // 解包嵌套的响应格式 {code, message, data}
      const actualData = (response && typeof response === 'object' && 'data' in response) ? (response as { data: typeof response }).data : response;
      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      setRealtimeData(actualData);
      setRealtimeError(null);
      productionReportLogger.info('实时监控数据加载成功', {
        runningPlans: actualData.runningPlans,
        todayOutput: actualData.todayOutput,
      });
    } catch (error) {
      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      productionReportLogger.error('加载实时监控数据失败', error as Error);
      setRealtimeData(null);
      setRealtimeError(error instanceof Error ? error.message : t('production.loadRealtimeFailed'));
    } finally {
      if (generation === requestGenerationRef.current && isLiveOwner(expectedOwnerKey)) setRealtimeLoading(false);
    }
  };

  /**
   * 加载产能利用数据
   */
  const loadCapacityData = async () => {
    const generation = ++requestGenerationRef.current;
    const expectedOwnerKey = ownerKey;
    const currentUser = useAuthStore.getState().user;
    if (!isLiveOwner(expectedOwnerKey)) return;
    if (!hasFinancialReportRead(currentUser)) {
      setCapacityData(null);
      setCapacityError(t('production.permissionDenied'));
      return;
    }
    setCapacityLoading(true);
    setCapacityData(null);
    setCapacityError(null);
    try {
      const factoryId = getFactoryId(currentUser);
      if (!factoryId) {
        productionReportLogger.warn('无法获取工厂ID');
        return;
      }

      // 计算日期范围：最近7天
      const endDate = new Date();
      const startDate = new Date();
      startDate.setDate(startDate.getDate() - 6);


      productionReportLogger.debug('加载产能利用数据', {
        factoryId,
        startDate: formatDate(startDate),
        endDate: formatDate(endDate),
      });

      const response = await reportApiClient.getCapacityUtilizationReport({
        factoryId,
        startDate: formatDate(startDate),
        endDate: formatDate(endDate),
      });
      // 解包嵌套的响应格式 {code, message, data}
      const actualData = (response && typeof response === 'object' && 'data' in response) ? (response as { data: typeof response }).data : response;
      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      setCapacityData(actualData);
      setCapacityError(null);
      productionReportLogger.info('产能利用数据加载成功', {
        averageUtilization: actualData.averageUtilization,
        equipmentCount: actualData.equipmentUtilization?.length ?? 0,
      });
    } catch (error) {
      if (generation !== requestGenerationRef.current || !isLiveOwner(expectedOwnerKey)) return;
      productionReportLogger.error('加载产能利用数据失败', error as Error);
      setCapacityData(null);
      setCapacityError(error instanceof Error ? error.message : t('production.loadCapacityFailed'));
    } finally {
      if (generation === requestGenerationRef.current && isLiveOwner(expectedOwnerKey)) setCapacityLoading(false);
    }
  };

  /**
   * 刷新数据
   */
  const handleRefresh = async () => {
    setRefreshing(true);
    if (mainTab === 'overview') {
      await loadProductionData();
    } else if (mainTab === 'realtime') {
      await loadRealtimeData();
    } else if (mainTab === 'capacity') {
      await loadCapacityData();
    }
    setRefreshing(false);
  };

  /**
   * 获取当前Tab的刷新函数
   */
  const getCurrentTabRefreshFn = () => {
    if (mainTab === 'overview') return loadProductionData;
    if (mainTab === 'realtime') return loadRealtimeData;
    if (mainTab === 'capacity') return loadCapacityData;
    return loadProductionData;
  };

  /**
   * 页面聚焦时加载数据
   */
  useFocusEffect(
    useCallback(() => {
      if (mainTab === 'overview') {
        loadProductionData();
      } else if (mainTab === 'realtime') {
        loadRealtimeData();
      } else if (mainTab === 'capacity') {
        loadCapacityData();
      }
      return () => {
        requestGenerationRef.current += 1;
      };
    }, [timeRange, mainTab, ownerKey])
  );

  /**
   * 格式化数量
   */
  const formatQuantity = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '-';
    return value.toFixed(2);
  };

  const getBatchUnit = (batch: ProcessingBatch): string | null => {
    const unit = (batch as ProcessingBatch & { unit?: string }).unit;
    const trimmed = unit?.trim();
    return trimmed ? trimmed : null;
  };

  const formatSummaryUnits = (summary: ProductionSummary): string => {
    if (summary.unitBreakdown.length === 0) return t('production.noReportedQuantity');
    return summary.unitBreakdown.map((item) => item.reportedBatches > 0
      ? `${formatQuantity(item.reportedQuantity)} ${item.unit}`
      : `${item.unit}: ${t('production.noReportedQuantity')}`).join('\n');
  };

  /**
   * 获取状态标签
   */
  const getStatusChip = (status: string) => {
    const statusMap: Record<
      string,
      { label: string; color: string; bgColor: string }
    > = {
      completed: { label: t('production.statusCompleted'), color: '#4CAF50', bgColor: '#E8F5E9' },
      COMPLETED: { label: t('production.statusCompleted'), color: '#4CAF50', bgColor: '#E8F5E9' },
      in_progress: { label: t('production.statusInProgress'), color: '#2196F3', bgColor: '#E3F2FD' },
      IN_PROGRESS: { label: t('production.statusInProgress'), color: '#2196F3', bgColor: '#E3F2FD' },
      producing: { label: t('production.statusProducing'), color: '#2196F3', bgColor: '#E3F2FD' },
      PRODUCING: { label: t('production.statusProducing'), color: '#2196F3', bgColor: '#E3F2FD' },
      planned: { label: t('production.statusPlanned'), color: '#FF9800', bgColor: '#FFF3E0' },
      PLANNED: { label: t('production.statusPlanned'), color: '#FF9800', bgColor: '#FFF3E0' },
      planning: { label: t('production.statusPlanning'), color: '#FF9800', bgColor: '#FFF3E0' },
      PLANNING: { label: t('production.statusPlanning'), color: '#FF9800', bgColor: '#FFF3E0' },
      pending: { label: t('production.statusPlanned'), color: '#FF9800', bgColor: '#FFF3E0' },
      paused: { label: t('production.statusPaused'), color: '#FF9800', bgColor: '#FFF3E0' },
      PAUSED: { label: t('production.statusPaused'), color: '#FF9800', bgColor: '#FFF3E0' },
      cancelled: { label: t('production.statusCancelled'), color: '#F44336', bgColor: '#FFEBEE' },
      CANCELLED: { label: t('production.statusCancelled'), color: '#F44336', bgColor: '#FFEBEE' },
    };

    const config = statusMap[status] || statusMap['pending'] || { label: t('status.unknown'), color: '#666', bgColor: '#f5f5f5' };

    return (
      <Chip
        mode="flat"
        compact
        style={{ backgroundColor: config.bgColor }}
        textStyle={{ color: config.color, fontSize: 12 }}
      >
        {config.label}
      </Chip>
    );
  };

  /**
   * 渲染概览标签内容
   */
  const renderOverviewTab = () => (
    <>
      {/* 时间范围选择 */}
      <Surface style={styles.timeRangeCard} elevation={1}>
        <Text variant="bodyMedium" style={styles.sectionLabel}>
          {t('production.timeRange')}
        </Text>
        <SegmentedButtons
          value={timeRange}
          onValueChange={setTimeRange}
          buttons={[
            { value: 'today', label: t('production.today') },
            { value: 'week', label: t('production.rollingSevenDays') },
            { value: 'month', label: t('production.thisMonth') },
          ]}
          style={styles.segmentedButtons}
        />
      </Surface>

      {/* 统计概览 */}
      <Surface style={styles.statsCard} elevation={1}>
        <Text variant="titleMedium" style={styles.statsTitle}>
          {t('production.productionStats')}
        </Text>
        <Divider style={styles.divider} />

        {loading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="small" />
            <Text style={styles.loadingText}>{t('common.loading')}</Text>
          </View>
        ) : productionStats ? (
          <View style={styles.statsGrid}>
            <View style={styles.statBox}>
              <Text style={styles.statValue}>{productionStats.totalBatches}</Text>
              <Text style={styles.statLabel}>{t('production.productionBatches')}</Text>
            </View>
            <View style={styles.statBox}>
              <Text style={[styles.statValue, { color: '#4CAF50' }]}>
                {formatSummaryUnits(productionStats)}
              </Text>
              <Text style={styles.statLabel}>{t('production.outputByUnit')}</Text>
            </View>
            <View style={styles.statBox}>
              <Text style={[styles.statValue, { color: '#2196F3' }]}>
                {productionStats.completionRate === null ? t('production.unknownRate') : `${productionStats.completionRate.toFixed(1)}%`}
              </Text>
              <Text style={styles.statLabel}>{t('production.completionRate')}</Text>
            </View>
            <View style={styles.statBox}>
              <Text style={[styles.statValue, { color: '#FF9800' }]}>
                {productionStats.missingActualQuantityCount > 0
                  ? `${productionStats.missingActualQuantityCount}`
                  : '0'}
              </Text>
              <Text style={styles.statLabel}>{t('production.missingActualQuantity')}</Text>
            </View>
            <Text style={styles.summaryRangeText}>
              {t('production.statisticsRange')}: {productionStats.startDate} - {productionStats.endDate}
            </Text>
            {productionStats.unknownUnitCount > 0 && (
              <Text style={styles.summaryWarningText}>
                {t('production.unknownUnit')}: {productionStats.unknownUnitCount}
              </Text>
            )}
            {productionStats.missingUnitCount > 0 && (
              <Text style={styles.summaryWarningText}>
                {t('production.missingUnit')}: {productionStats.missingUnitCount}
              </Text>
            )}
          </View>
        ) : productionStatsError ? (
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>{productionStatsError}</Text>
          </View>
        ) : (
          <View style={styles.emptyContainer}>
            <Text style={styles.emptyText}>{t('production.noStatsData')}</Text>
          </View>
        )}
      </Surface>

      {/* 最近批次列表 */}
      <Card style={styles.card} mode="elevated">
          <Card.Title title={`${t('production.recentBatches')} · ${t('production.recentTen')}`} titleVariant="titleMedium" />
        <DataTable>
          <DataTable.Header>
            <DataTable.Title>{t('production.batchNumber')}</DataTable.Title>
            <DataTable.Title>{t('production.product')}</DataTable.Title>
            <DataTable.Title numeric>{t('production.output')}</DataTable.Title>
            <DataTable.Title>{t('production.status')}</DataTable.Title>
          </DataTable.Header>

          {loading ? (
            <View style={styles.loadingContainer}>
              <ActivityIndicator size="large" />
            </View>
          ) : recentBatchesError ? (
            <View style={styles.emptyContainer}>
              <Text variant="bodyMedium" style={styles.emptyText}>{recentBatchesError}</Text>
              <Button mode="outlined" onPress={loadProductionData} style={{ marginTop: 16 }}>
                {t('production.retry')}
              </Button>
            </View>
          ) : recentBatches.length === 0 ? (
            <View style={styles.emptyContainer}>
              <Text variant="bodyMedium" style={styles.emptyText}>
                {t('production.noBatchRecords')}
              </Text>
            </View>
          ) : (
            recentBatches.slice(0, 10).map((batch, index) => (
              <DataTable.Row key={batch.id || index}>
                <DataTable.Cell>
                  <Text variant="bodySmall">{batch.batchNumber || `批次${batch.id}`}</Text>
                </DataTable.Cell>
                <DataTable.Cell>
                  <Text variant="bodySmall">
                    {batch.productType || batch.productTypeId || '-'}
                  </Text>
                </DataTable.Cell>
                <DataTable.Cell numeric>
                  <Text variant="bodySmall">
                    {batch.actualQuantity === null || batch.actualQuantity === undefined
                      ? t('production.noReportedQuantity')
                      : `${formatQuantity(batch.actualQuantity)} ${getBatchUnit(batch) ?? t('production.unitMissing')}`}
                  </Text>
                </DataTable.Cell>
                <DataTable.Cell>{getStatusChip(batch.status)}</DataTable.Cell>
              </DataTable.Row>
            ))
          )}
        </DataTable>
      </Card>
    </>
  );

  /**
   * 渲染实时监控标签内容 - 使用API数据
   */
  const renderRealtimeTab = () => {
    const getStatusColor = (status: string) => {
      return status === 'running' ? '#4CAF50' : status === 'stopped' ? '#F44336' : '#FF9800';
    };

    // 计算设备统计
    const runningEquipment = realtimeData?.equipmentStatus?.['running'] ?? 0;
    const stoppedEquipment = realtimeData?.equipmentStatus?.['stopped'] ?? 0;
    const maintenanceEquipment = realtimeData?.equipmentStatus?.['maintenance'] ?? 0;
    const totalEquipment = runningEquipment + stoppedEquipment + maintenanceEquipment;

    if (realtimeLoading) {
      return (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>{t('common.loading')}</Text>
        </View>
      );
    }

    if (!realtimeData) {
      return (
        <View style={styles.emptyContainer}>
          <Text style={styles.emptyText}>{realtimeError ?? t('production.noRealtimeData')}</Text>
          <Button mode="outlined" onPress={loadRealtimeData} style={{ marginTop: 16 }}>
            {t('production.retry')}
          </Button>
        </View>
      );
    }

    return (
      <View style={styles.realtimeContainer}>
        {/* 实时产量概览 */}
        <Surface style={styles.realtimeCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>
            实时产量监控
          </Text>
          <Divider style={styles.divider} />

          {/* 当前产量 */}
          <View style={styles.realtimeMainStats}>
            <View style={styles.realtimeMainItem}>
              <Text style={styles.realtimeMainValue}>{realtimeData.todayOutput ?? 0}</Text>
              <Text style={styles.realtimeMainLabel}>今日产量</Text>
            </View>
            <View style={styles.realtimeMainItem}>
              <Text style={[styles.realtimeMainValue, { color: '#2196F3' }]}>{realtimeData.runningPlans ?? 0}</Text>
              <Text style={styles.realtimeMainLabel}>进行中计划</Text>
            </View>
            <View style={styles.realtimeMainItem}>
              <Text style={[styles.realtimeMainValue, { color: totalEquipment > 0 ? '#4CAF50' : '#999' }]}>
                {totalEquipment > 0 ? ((runningEquipment / totalEquipment) * 100).toFixed(1) : 0}%
              </Text>
              <Text style={styles.realtimeMainLabel}>设备运行率</Text>
            </View>
          </View>

          {/* 设备状态概览 */}
          <Divider style={styles.divider} />
          <View style={styles.realtimeStatusRow}>
            <View style={styles.realtimeStatusItem}>
              <IconButton icon="play-circle" size={20} iconColor="#4CAF50" />
              <Text style={styles.realtimeStatusText}>
                运行中: {runningEquipment}
              </Text>
            </View>
            <View style={styles.realtimeStatusItem}>
              <IconButton icon="stop-circle" size={20} iconColor="#F44336" />
              <Text style={styles.realtimeStatusText}>
                已停止: {stoppedEquipment}
              </Text>
            </View>
            <View style={styles.realtimeStatusItem}>
              <IconButton icon="wrench" size={20} iconColor="#FF9800" />
              <Text style={styles.realtimeStatusText}>
                维护中: {maintenanceEquipment}
              </Text>
            </View>
          </View>
        </Surface>

        {/* 设备状态详情 */}
        <Surface style={styles.realtimeCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>
            设备状态汇总
          </Text>
          <Divider style={styles.divider} />
          <View style={styles.lineStatusList}>
            {Object.entries(realtimeData.equipmentStatus ?? {}).map(([status, count], index) => {
              const statusLabels: Record<string, string> = {
                running: '运行中',
                stopped: '已停止',
                maintenance: '维护中',
                idle: '空闲',
              };
              const statusColors: Record<string, string> = {
                running: '#4CAF50',
                stopped: '#F44336',
                maintenance: '#FF9800',
                idle: '#9E9E9E',
              };
              return (
                <View key={status} style={styles.lineStatusItem}>
                  <View style={styles.lineStatusLeft}>
                    <View style={[styles.lineStatusDot, { backgroundColor: statusColors[status] ?? '#666' }]} />
                    <Text style={styles.lineStatusName}>{statusLabels[status] ?? status}</Text>
                    <Chip
                      mode="flat"
                      compact
                      style={{ backgroundColor: status === 'running' ? '#E8F5E9' : status === 'stopped' ? '#FFEBEE' : '#FFF3E0' }}
                      textStyle={{ color: statusColors[status] ?? '#666', fontSize: 10 }}
                    >
                      {count} 台
                    </Chip>
                  </View>
                  <Text style={styles.lineStatusOutput}>
                    {totalEquipment > 0 ? ((count / totalEquipment) * 100).toFixed(1) : 0}%
                  </Text>
                </View>
              );
            })}
          </View>
        </Surface>
      </View>
    );
  };

  /**
   * 渲染产能利用标签内容 - 使用API数据
   */
  const renderCapacityTab = () => {
    const getUtilizationColor = (utilization: number) => {
      if (utilization >= 80) return '#4CAF50';
      if (utilization >= 60) return '#FF9800';
      return '#F44336';
    };

    if (capacityLoading) {
      return (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>{t('common.loading')}</Text>
        </View>
      );
    }

    if (!capacityData) {
      return (
        <View style={styles.emptyContainer}>
          <Text style={styles.emptyText}>{capacityError ?? t('production.noCapacityData')}</Text>
          <Button mode="outlined" onPress={loadCapacityData} style={{ marginTop: 16 }}>
            {t('production.retry')}
          </Button>
        </View>
      );
    }

    const averageUtilization = capacityData.averageUtilization;
    const formatUtilization = (value: number | null | undefined) =>
      value == null ? t('production.unknownRate') : `${value.toFixed(1)}%`;

    return (
      <View style={styles.capacityContainer}>
        {/* 产能利用率概览 */}
        <Surface style={styles.capacityCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>
            {t('production.capacityUtilization')} · {t('production.rollingSevenDays')}
          </Text>
          <Divider style={styles.divider} />

          {/* 总体利用率 */}
          <View style={styles.capacityMainSection}>
            <View style={styles.capacityGauge}>
              <Text style={[styles.capacityMainValue, { color: averageUtilization == null ? '#999' : getUtilizationColor(averageUtilization) }]}>
                {formatUtilization(averageUtilization)}
              </Text>
              <Text style={styles.capacityMainLabel}>{t('production.averageUtilization')}</Text>
            </View>
            <View style={styles.capacityDetails}>
              <View style={styles.capacityDetailRow}>
                <Text style={styles.capacityDetailLabel}>{t('production.dailyCapacity')}</Text>
                <Text style={styles.capacityDetailValue}>{capacityData.dailyCapacity ?? t('production.unknownRate')}</Text>
              </View>
              <View style={styles.capacityDetailRow}>
                <Text style={styles.capacityDetailLabel}>{t('production.totalEquipment')}</Text>
                <Text style={[styles.capacityDetailValue, { color: '#2196F3' }]}>{capacityData.totalEquipment}</Text>
              </View>
            </View>
          </View>
          {capacityData.capacityBasis && (
            <Text style={styles.capacitySectionTitle}>{t('production.capacityBasis')}: {capacityData.capacityBasis}</Text>
          )}
          {capacityData.utilizationTarget != null && (
            <Text style={styles.capacitySectionTitle}>{t('production.utilizationTarget')}: {formatUtilization(capacityData.utilizationTarget)}</Text>
          )}
        </Surface>

        {/* 每日真实利用率 */}
        <Surface style={styles.capacityCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>
            {t('production.dailyUtilization')}
          </Text>
          <Divider style={styles.divider} />
          <View style={styles.equipmentCapacityList}>
            {capacityData.dailyUtilization.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Text style={styles.emptyText}>{t('production.noDailyUtilization')}</Text>
              </View>
            ) : (
              capacityData.dailyUtilization.slice(-7).map((day) => (
                <View key={day.date} style={styles.equipmentCapacityItem}>
                  <View style={styles.equipmentCapacityHeader}>
                    <Text style={styles.equipmentName}>{day.date}</Text>
                    <Text style={[styles.equipmentUtilization, { color: day.utilization == null ? '#999' : getUtilizationColor(day.utilization) }]}>
                      {formatUtilization(day.utilization)}
                    </Text>
                  </View>
                  <Text style={styles.equipmentCapacityText}>
                    {t('production.dailyOutput')}: {day.output}
                  </Text>
                </View>
              ))
            )}
          </View>
        </Surface>

        {/* 各设备真实利用率 */}
        <Surface style={styles.capacityCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>{t('production.equipmentUtilization')}</Text>
          <Divider style={styles.divider} />
          <View style={styles.equipmentCapacityList}>
            {capacityData.equipmentUtilization.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Text style={styles.emptyText}>{t('production.noEquipmentUtilization')}</Text>
              </View>
            ) : capacityData.equipmentUtilization.map((equipment) => (
              <View key={equipment.equipmentId} style={styles.equipmentCapacityItem}>
                <View style={styles.equipmentCapacityHeader}>
                  <Text style={styles.equipmentName}>{equipment.equipmentName}</Text>
                  <Text style={[styles.equipmentUtilization, { color: equipment.utilization == null ? '#999' : getUtilizationColor(equipment.utilization) }]}>
                    {formatUtilization(equipment.utilization)}
                  </Text>
                </View>
                {equipment.utilization != null && (
                  <ProgressBar
                    progress={equipment.utilization / 100}
                    color={getUtilizationColor(equipment.utilization)}
                    style={styles.equipmentProgressBar}
                  />
                )}
                <Text style={styles.equipmentCapacityText}>{equipment.status}</Text>
              </View>
            ))}
          </View>
        </Surface>
      </View>
    );
  };

  /**
   * 渲染当前标签内容
   */
  const renderTabContent = () => {
    switch (mainTab) {
      case 'overview':
        return renderOverviewTab();
      case 'realtime':
        return renderRealtimeTab();
      case 'capacity':
        return renderCapacityTab();
      default:
        return renderOverviewTab();
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="生产监控" />
        <Appbar.Action icon="refresh" onPress={getCurrentTabRefreshFn()} />
      </Appbar.Header>

      {/* 主标签切换 */}
      <Surface style={styles.mainTabContainer} elevation={2}>
        <SegmentedButtons
          value={mainTab}
          onValueChange={(value) => setMainTab(value as MainTab)}
          buttons={[
            { value: 'overview', label: '生产概览', icon: 'chart-box' },
            { value: 'realtime', label: '实时监控', icon: 'pulse' },
            { value: 'capacity', label: '产能利用', icon: 'gauge' },
          ]}
          style={styles.mainTabButtons}
        />
      </Surface>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
        }
      >
        {renderTabContent()}
        <View style={styles.bottomPadding} />
      </ScrollView>
    </SafeAreaView>
  );
}

export default function ProductionReportScreen() {
  const { user } = useAuthStore();
  const ownerKey = getCanonicalActorKey(user);
  return <ProductionReportScreenContent key={ownerKey} />;
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  mainTabContainer: {
    backgroundColor: '#FFF',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  mainTabButtons: {
    // 默认样式即可
  },
  content: {
    flex: 1,
  },
  timeRangeCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    margin: 16,
    marginBottom: 8,
  },
  sectionLabel: {
    color: '#666',
    marginBottom: 12,
    fontWeight: '500',
  },
  segmentedButtons: {
    marginTop: 4,
  },
  statsCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    margin: 16,
    marginBottom: 8,
  },
  statsTitle: {
    fontWeight: '600',
    color: '#212121',
  },
  divider: {
    marginVertical: 12,
  },
  loadingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  loadingText: {
    marginLeft: 12,
    color: '#999',
  },
  statsGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
  },
  statBox: {
    width: '48%',
    alignItems: 'center',
    padding: 16,
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
    marginBottom: 12,
  },
  statValue: {
    fontSize: 28,
    fontWeight: '700',
    color: '#2196F3',
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 13,
    color: '#666',
  },
  summaryRangeText: {
    width: '100%',
    color: '#666',
    fontSize: 12,
    marginTop: 12,
    textAlign: 'center',
  },
  summaryWarningText: {
    width: '100%',
    color: '#B45309',
    fontSize: 12,
    marginTop: 4,
    textAlign: 'center',
  },
  card: {
    margin: 16,
    marginBottom: 0,
    marginTop: 8,
  },
  emptyContainer: {
    padding: 40,
    alignItems: 'center',
  },
  emptyText: {
    color: '#999',
  },
  bottomPadding: {
    height: 80,
  },
  // 快速访问卡片样式
  quickAccessCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 20,
    margin: 16,
  },
  quickAccessHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  quickAccessIconContainer: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: '#F5F5F5',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 16,
  },
  quickAccessIcon: {
    margin: 0,
  },
  quickAccessInfo: {
    flex: 1,
  },
  quickAccessTitle: {
    fontWeight: '600',
    color: '#212121',
    marginBottom: 4,
  },
  quickAccessDesc: {
    color: '#666',
    lineHeight: 18,
  },
  quickAccessDivider: {
    marginVertical: 16,
  },
  quickAccessFeatures: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    marginBottom: 16,
  },
  featureItem: {
    alignItems: 'center',
  },
  quickAccessButton: {
    borderRadius: 8,
  },
  quickAccessButtonContent: {
    paddingVertical: 6,
    flexDirection: 'row-reverse',
  },
  // 实时监控Tab样式
  realtimeContainer: {
    padding: 16,
    gap: 16,
  },
  realtimeCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
  },
  realtimeMainStats: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  realtimeMainItem: {
    alignItems: 'center',
  },
  realtimeMainValue: {
    fontSize: 28,
    fontWeight: '700',
    color: '#4CAF50',
  },
  realtimeMainLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  realtimeStatusRow: {
    flexDirection: 'row',
    justifyContent: 'space-around',
  },
  realtimeStatusItem: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  realtimeStatusText: {
    fontSize: 12,
    color: '#666',
  },
  lineStatusList: {
    gap: 12,
  },
  lineStatusItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#F0F0F0',
  },
  lineStatusLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  lineStatusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  lineStatusName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  lineStatusOutput: {
    fontSize: 14,
    color: '#666',
  },
  // 产能利用Tab样式
  capacityContainer: {
    padding: 16,
    gap: 16,
  },
  capacityCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
  },
  capacityMainSection: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
  },
  capacityGauge: {
    alignItems: 'center',
  },
  capacityMainValue: {
    fontSize: 42,
    fontWeight: '700',
  },
  capacityMainLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  capacityDetails: {
    gap: 8,
  },
  capacityDetailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: 140,
  },
  capacityDetailLabel: {
    fontSize: 13,
    color: '#666',
  },
  capacityDetailValue: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  capacitySectionTitle: {
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  timeDistribution: {
    flexDirection: 'row',
    height: 24,
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 8,
  },
  timeBlock: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  timeBlockText: {
    fontSize: 10,
    fontWeight: '600',
  },
  timeLegend: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 16,
  },
  timeLegendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  timeLegendDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  timeLegendText: {
    fontSize: 11,
    color: '#666',
  },
  equipmentCapacityList: {
    gap: 16,
  },
  equipmentCapacityItem: {
    marginBottom: 4,
  },
  equipmentCapacityHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  equipmentName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  equipmentUtilization: {
    fontSize: 16,
    fontWeight: '700',
  },
  equipmentProgressBar: {
    height: 8,
    borderRadius: 4,
    marginBottom: 4,
  },
  equipmentCapacityText: {
    fontSize: 11,
    color: '#999',
  },
});
