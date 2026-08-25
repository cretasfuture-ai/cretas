/**
 * Workshop Supervisor 批次管理
 * 包含: 搜索、筛选标签、批次列表
 */
import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { useFactoryFeatureStore } from '../../../store/factoryFeatureStore';
import { TutorialOverlay } from '../../../components/common/TutorialOverlay';
import { useTutorialStore, TUTORIAL_BATCHES, TUTORIAL_ENABLED, useTutorialTarget } from '../../../store/tutorialStore';
import { WSBatchesStackParamList } from '../../../types/navigation';
import { processingApiClient, ProcessingBatch } from '../../../services/api/processingApiClient';

type NavigationProp = NativeStackNavigationProp<WSBatchesStackParamList, 'WSBatches'>;

/**
 * 批次卡片的展示模型。
 *
 * 每个字段都必须能从后端 ProcessingBatch 推出来 —— 推不出来的字段一律 undefined，
 * 由渲染层留白，⛔ 不许兜一个看起来合理的默认值。
 * (CLAUDE.md 核心原则 1: 禁止降级处理 —— 不返回假数据，明确显示错误)
 */
interface Batch {
  id: string;
  batchNumber: string;
  productName: string;
  targetQuantity: number;
  /** 后端 actualQuantity；未报工时为 undefined，不是 0 */
  currentQuantity?: number;
  /** currentQuantity/targetQuantity 推出；任一缺失则 undefined */
  progress?: number;
  /** 后端原始状态枚举 ProductionBatchStatus */
  rawStatus: string;
  /** 归一化后的分组：与筛选标签一一对应 */
  status: BatchFilterKey;
  /** 后端 endTime；缺失则 undefined */
  endTime?: string;
}

type BatchFilterKey = 'in_progress' | 'pending' | 'completed' | 'other';

/**
 * 后端 ProductionBatchStatus -> 本屏筛选分组。
 * PLANNING / PRODUCING 是后端为兼容旧数据保留的同义值（见 ProductionBatchStatus.java）。
 */
const STATUS_TO_FILTER: Record<string, BatchFilterKey> = {
  PLANNED: 'pending',
  PLANNING: 'pending',
  IN_PROGRESS: 'in_progress',
  PRODUCING: 'in_progress',
  COMPLETED: 'completed',
  PAUSED: 'other',
  CANCELLED: 'other',
};

const STATUS_LABEL: Record<string, string> = {
  PLANNED: '待开始',
  PLANNING: '待开始',
  IN_PROGRESS: '生产中',
  PRODUCING: '生产中',
  COMPLETED: '已完成',
  PAUSED: '已暂停',
  CANCELLED: '已取消',
};

/**
 * 筛选标签。
 * `count` 不再写死 —— 由后端分页响应的 totalElements 填，取不到就留 null（渲染成不带括号的裸标签）。
 */
const FILTER_TABS: { key: 'all' | BatchFilterKey; label: string; apiStatus?: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'in_progress', label: '进行中', apiStatus: 'IN_PROGRESS' },
  { key: 'pending', label: '待开始', apiStatus: 'PLANNED' },
  { key: 'completed', label: '已完成', apiStatus: 'COMPLETED' },
];

/** 一次拉多少条列表数据 */
const PAGE_SIZE = 50;

function toBatch(b: ProcessingBatch): Batch {
  const rawStatus = String(b.status ?? '').toUpperCase();
  const hasActual = typeof b.actualQuantity === 'number';
  const progress =
    hasActual && b.targetQuantity > 0
      ? Math.round(((b.actualQuantity as number) / b.targetQuantity) * 100)
      : undefined;
  return {
    id: String(b.id),
    batchNumber: b.batchNumber,
    productName: b.productType,
    targetQuantity: b.targetQuantity,
    currentQuantity: hasActual ? (b.actualQuantity as number) : undefined,
    progress,
    rawStatus,
    status: STATUS_TO_FILTER[rawStatus] ?? 'other',
    endTime: b.endTime,
  };
}

function formatTime(iso?: string): string | undefined {
  if (!iso) return undefined;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

export function WSBatchesScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('workshop');
  const insets = useSafeAreaInsets();
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState('all');
  const [refreshing, setRefreshing] = useState(false);

  // Tutorial
  const activeTutorial = useTutorialStore(s => s.activeTutorial);
  const activeStep = useTutorialStore(s => s.activeStep);
  const completedBatches = useTutorialStore(s => s.completedTutorials[TUTORIAL_BATCHES.id]);
  const showTutorial = activeTutorial === TUTORIAL_BATCHES.id;
  const tgtFilter = useTutorialTarget('batches-filter');
  const tgtBatchList = useTutorialTarget('batches-first-card');
  const tgtProcessBtn = useTutorialTarget('batches-process-btn');

  useEffect(() => {
    if (TUTORIAL_ENABLED && !completedBatches && activeTutorial === null) {
      const timer = setTimeout(() => {
        const s = useTutorialStore.getState();
        if (!s.activeTutorial && !s.completedTutorials[TUTORIAL_BATCHES.id]) {
          s.startTutorial(TUTORIAL_BATCHES.id);
        }
      }, 800);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [completedBatches]);

  useEffect(() => {
    if (showTutorial) {
      setTimeout(() => {
        tgtFilter.measure();
        tgtBatchList.measure();
        tgtProcessBtn.measure();
      }, 200);
    }
  }, [showTutorial]);

  // 批次数据 — 全部来自 GET /api/mobile/{factoryId}/processing/batches
  const [batches, setBatches] = useState<Batch[]>([]);
  const [counts, setCounts] = useState<Record<string, number | null>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * 拉数据。
   * 四个请求各自带 status 参数（"全部" 那个不带），用后端分页响应里的 totalElements
   * 作为标签计数 —— 那是真实总数，不是本页条数。
   * ⛔ 任何一个请求失败就整体报错，不许拿剩下的凑一个看起来正常的页面。
   */
  const loadData = useCallback(async () => {
    try {
      setError(null);
      const responses = await Promise.all(
        FILTER_TABS.map(tab =>
          processingApiClient.getBatches({
            page: 1,
            size: PAGE_SIZE,
            ...(tab.apiStatus ? { status: tab.apiStatus } : {}),
          })
        )
      );

      const failed = responses.find(r => !r.success);
      if (failed) {
        throw new Error(failed.message || '批次接口返回失败');
      }

      const nextCounts: Record<string, number | null> = {};
      FILTER_TABS.forEach((tab, i) => {
        const total = responses[i]?.data?.totalElements;
        nextCounts[tab.key] = typeof total === 'number' ? total : null;
      });
      setCounts(nextCounts);

      // 列表内容用 "全部" 那一路的返回，避免同一条批次在几路里重复。
      const allContent = responses[0]?.data?.content;
      if (!Array.isArray(allContent)) {
        throw new Error('批次接口返回的分页结构不符合预期');
      }
      setBatches(allContent.map(toBatch));
    } catch (err) {
      console.error('[WSBatches] 加载批次列表失败:', err);
      setBatches([]);
      setCounts({});
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 从开工/完工屏返回时重新拉一次，否则看到的是上一次的快照
  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  // 筛选批次
  const filteredBatches = batches.filter(batch => {
    if (activeFilter !== 'all' && batch.status !== activeFilter) return false;
    if (searchQuery && !batch.batchNumber.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 获取状态样式
  const getStatusStyle = (status: BatchFilterKey) => {
    switch (status) {
      case 'in_progress':
        return { bg: '#e6f7ff', color: '#1890ff' };
      case 'pending':
        return { bg: '#f5f5f5', color: '#8c8c8c' };
      case 'completed':
        return { bg: '#f6ffed', color: '#52c41a' };
      default:
        return { bg: '#fff7e6', color: '#faad14' };
    }
  };

  return (
    <View style={styles.container}>
      {/* 头部 */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <Text style={styles.headerTitle}>{t('batches.title')}</Text>
        <View ref={tgtProcessBtn.ref} onLayout={tgtProcessBtn.onLayout} style={{ flexDirection: 'row', gap: 8 }}>
          {useFactoryFeatureStore.getState().isProcessMode() && (
            <TouchableOpacity
              testID="ws-process-task-btn"
              style={[styles.addBtn, { backgroundColor: '#0891B2' }]}
              onPress={() => navigation.navigate('ProcessTaskList' as never)}
            >
              <Icon source="format-list-checks" size={24} color="#fff" />
            </TouchableOpacity>
          )}
          <TouchableOpacity
            style={styles.addBtn}
            onPress={() => navigation.navigate('BatchStart', {})}
          >
            <Icon source="plus" size={24} color="#fff" />
          </TouchableOpacity>
        </View>
      </View>

      {/* 搜索栏 */}
      <View style={styles.searchContainer}>
        <View style={styles.searchBar}>
          <Icon source="magnify" size={20} color="#999" />
          <TextInput
            style={styles.searchInput}
            placeholder={t('batches.searchPlaceholder')}
            placeholderTextColor="#999"
            value={searchQuery}
            onChangeText={setSearchQuery}
          />
        </View>
      </View>

      {/* 筛选标签 */}
      <View ref={tgtFilter.ref} onLayout={tgtFilter.onLayout} style={styles.filterContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          {FILTER_TABS.map(tab => (
            <TouchableOpacity
              key={tab.key}
              style={[styles.filterTab, activeFilter === tab.key && styles.filterTabActive]}
              onPress={() => setActiveFilter(tab.key)}
            >
              <Text style={[styles.filterTabText, activeFilter === tab.key && styles.filterTabTextActive]}>
                {counts[tab.key] === null || counts[tab.key] === undefined
                  ? tab.label
                  : `${tab.label} (${counts[tab.key]})`}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      </View>

      {/* 批次统计 — 数字来自各状态分页响应的 totalElements，取不到时显示 "—" */}
      <View style={styles.statsRow}>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#1890ff' }]}>{counts.in_progress ?? '—'}</Text>
          <Text style={styles.statLabel}>进行中</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#faad14' }]}>{counts.pending ?? '—'}</Text>
          <Text style={styles.statLabel}>待开始</Text>
        </View>
        <View style={styles.statItem}>
          <Text style={[styles.statValue, { color: '#52c41a' }]}>{counts.completed ?? '—'}</Text>
          <Text style={styles.statLabel}>已完成</Text>
        </View>
      </View>

      {/* 批次列表 */}
      <View ref={tgtBatchList.ref} onLayout={tgtBatchList.onLayout} style={{ flex: 1 }} collapsable={false}>
      <ScrollView
        style={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
      >
        {loading && (
          <View style={styles.stateBox}>
            <ActivityIndicator size="large" color="#667eea" />
            <Text style={styles.stateText}>{t('common.loading')}</Text>
          </View>
        )}

        {/* 加载失败 —— 明确报错, ⛔ 不退回任何本地数据 */}
        {!loading && error && (
          <View style={styles.stateBox}>
            <Icon source="cloud-off-outline" size={44} color="#C0C4CC" />
            <Text style={styles.stateText}>批次加载失败：{error}</Text>
            <TouchableOpacity style={styles.retryBtn} onPress={onRefresh}>
              <Text style={styles.retryBtnText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </View>
        )}

        {!loading && !error && filteredBatches.length === 0 && (
          <View style={styles.stateBox}>
            <Icon source="tray" size={44} color="#C0C4CC" />
            <Text style={styles.stateText}>{t('common.noData')}</Text>
          </View>
        )}

        {!loading && !error && filteredBatches.map(batch => {
          const statusStyle = getStatusStyle(batch.status);
          const endTimeText = formatTime(batch.endTime);
          return (
            <TouchableOpacity
              key={batch.id}
              style={styles.batchCard}
              onPress={() => navigation.navigate('BatchDetail', { batchId: batch.id })}
            >
              <View style={styles.batchHeader}>
                <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
                <View style={[styles.stageBadge, { backgroundColor: statusStyle.bg }]}>
                  <Text style={[styles.stageBadgeText, { color: statusStyle.color }]}>
                    {STATUS_LABEL[batch.rawStatus] ?? batch.rawStatus}
                  </Text>
                </View>
              </View>

              <View style={styles.batchInfo}>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>产品</Text>
                  <Text style={styles.batchInfoValue}>{batch.productName}</Text>
                </View>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>目标</Text>
                  <Text style={styles.batchInfoValue}>{batch.targetQuantity}kg</Text>
                </View>
                <View style={styles.batchInfoItem}>
                  <Text style={styles.batchInfoLabel}>
                    {batch.status === 'completed' ? '完成时间' : '进度'}
                  </Text>
                  {/*
                    进度只有在后端给了 actualQuantity 时才算得出来。
                    ⛔ 没报工不等于 0% —— 这里显示 "—"。
                  */}
                  <Text style={styles.batchInfoValue}>
                    {batch.status === 'completed'
                      ? endTimeText ?? '—'
                      : batch.progress === undefined ? '—' : `${batch.progress}%`}
                  </Text>
                </View>
              </View>

              {batch.status === 'in_progress' && batch.progress !== undefined && (
                <View style={styles.progressSection}>
                  <View style={styles.progressBar}>
                    <View style={[styles.progressFill, { width: `${Math.min(100, Math.max(0, batch.progress))}%` }]} />
                  </View>
                  <View style={styles.progressInfo}>
                    <Text style={styles.progressText}>{batch.currentQuantity}kg / {batch.targetQuantity}kg</Text>
                    {endTimeText ? <Text style={styles.progressTime}>预计 {endTimeText}</Text> : null}
                  </View>
                </View>
              )}
            </TouchableOpacity>
          );
        })}
        <View style={{ height: 100 }} />
      </ScrollView>
      </View>

      {/* 悬浮添加按钮 */}
      <TouchableOpacity
        style={styles.fab}
        onPress={() => navigation.navigate('BatchStart', {})}
      >
        <Icon source="plus" size={28} color="#fff" />
      </TouchableOpacity>

      {/* Tutorial */}
      <TutorialOverlay
        visible={showTutorial}
        steps={TUTORIAL_BATCHES.steps}
        currentStep={activeStep}
        onNext={() => useTutorialStore.getState().nextStep(TUTORIAL_BATCHES.steps.length)}
        onSkip={() => useTutorialStore.getState().skipTutorial()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingVertical: 16,
    backgroundColor: '#667eea',
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#fff',
  },
  addBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255,255,255,0.2)',
    justifyContent: 'center',
    alignItems: 'center',
  },

  // 搜索栏
  searchContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
  },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    paddingHorizontal: 12,
    height: 40,
  },
  searchInput: {
    flex: 1,
    marginLeft: 8,
    fontSize: 14,
    color: '#333',
  },

  // 筛选标签
  filterContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  filterTab: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    marginRight: 8,
    borderRadius: 16,
    backgroundColor: '#f5f5f5',
  },
  filterTabActive: {
    backgroundColor: '#667eea',
  },
  filterTabText: {
    fontSize: 14,
    color: '#666',
  },
  filterTabTextActive: {
    color: '#fff',
  },

  // 统计
  statsRow: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    marginBottom: 8,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 24,
    fontWeight: 'bold',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },

  // 列表
  listContainer: {
    flex: 1,
    paddingHorizontal: 16,
    paddingTop: 8,
  },
  batchCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  stateBox: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 48,
  },
  stateText: {
    marginTop: 12,
    fontSize: 14,
    color: '#606266',
    textAlign: 'center',
    paddingHorizontal: 24,
  },
  retryBtn: {
    marginTop: 16,
    paddingHorizontal: 20,
    paddingVertical: 8,
    backgroundColor: '#667eea',
    borderRadius: 6,
  },
  retryBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '500',
  },
  batchHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  batchNumber: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  stageBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  stageBadgeText: {
    fontSize: 12,
    fontWeight: '500',
  },
  batchInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  batchInfoItem: {
    alignItems: 'center',
  },
  batchInfoLabel: {
    fontSize: 12,
    color: '#999',
    marginBottom: 4,
  },
  batchInfoValue: {
    fontSize: 14,
    color: '#333',
    fontWeight: '500',
  },
  progressSection: {
    marginTop: 12,
  },
  progressBar: {
    height: 6,
    backgroundColor: '#f0f0f0',
    borderRadius: 3,
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#667eea',
    borderRadius: 3,
  },
  progressInfo: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 8,
  },
  progressText: {
    fontSize: 12,
    color: '#666',
  },
  progressTime: {
    fontSize: 12,
    color: '#999',
  },

  // FAB
  fab: {
    position: 'absolute',
    right: 20,
    bottom: 90,
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#667eea',
    justifyContent: 'center',
    alignItems: 'center',
    elevation: 4,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.25,
    shadowRadius: 4,
  },
});

export default WSBatchesScreen;
