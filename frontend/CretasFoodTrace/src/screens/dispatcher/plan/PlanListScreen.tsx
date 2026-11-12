/**
 * 生产计划列表 (六扇门精简版)
 *
 * 核心功能:
 * - 状态筛选 (全部/待执行/进行中/已完成)
 * - 搜索
 * - 计划卡片 (真实API数据)
 * - 新建计划入口
 */

import React, { useState, useCallback, useMemo, useRef } from 'react';
import {
  View, Text, StyleSheet, FlatList, TouchableOpacity,
  RefreshControl, ActivityIndicator, Alert, Modal,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Searchbar } from 'react-native-paper';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { DISPATCHER_THEME } from '../../../types/dispatcher';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import {
  PLAN_PAGE_SIZE, coverageNote, filterPlans, hasMorePlans, mergePlanPage, summarizePlans,
} from './planListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';

/**
 * 排产工具四项。
 *
 * 这四个屏 2026-08-27 之前【一次都没被打开过】—— 它们注册在
 * `DSPlanStackNavigator.tsx:59-62`，可全仓没有任何 `navigate` 指向它们
 * （可达性棘轮闸 `screenReachability.test.ts` 把它们记在 35 个孤儿里）。
 *
 * ⛔ `desc` 写「用户能用它做什么」，不是功能名的同义反复。
 *    「甘特图 —— 甘特图视图」等于没说；对着排产的人要说「谁跟谁撞了」。
 */
const PLAN_TOOLS = [
  {
    route: 'PlanGantt',
    icon: 'chart-timeline',
    label: '排程时间轴',
    desc: '看哪几个计划挤在同一天、谁跟谁撞了',
  },
  {
    route: 'UrgentInsert',
    icon: 'flash',
    label: '紧急插单',
    desc: '临时插一单，先看它会挤掉谁',
  },
  {
    route: 'MixedBatch',
    icon: 'call-merge',
    label: '合并同类批次',
    desc: '同一产品的几个计划并成一批做',
  },
  {
    route: 'ResourceOverview',
    icon: 'factory',
    label: '产线与设备',
    desc: '现在哪条线空着、哪台设备停着',
  },
] as const;

interface PlanItem {
  id: string;
  planNumber?: string;
  productTypeName?: string;
  productName?: string;
  plannedQuantity?: number;
  actualQuantity?: number;
  plannedUnit?: string;
  productUnit?: string;
  plannedDate?: string;
  planDate?: string;
  status?: string;
  sourceCustomerName?: string;
  processName?: string;
  sourceType?: string;
  createdAt?: string;
}

const STATUS_TABS = [
  { key: 'all', label: '全部' },
  { key: 'PLANNED', label: '待执行' },
  { key: 'IN_PROGRESS', label: '进行中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' },
];

const getStatusStyle = (status?: string) => {
  switch (status?.toUpperCase()) {
    case 'DRAFT': return { bg: '#f5f5f5', color: '#999', label: '草稿' };
    case 'PLANNED': case 'PENDING': return { bg: '#fff7e6', color: '#fa8c16', label: '待执行' };
    case 'CONFIRMED': return { bg: '#e6f7ff', color: '#1890ff', label: '已确认' };
    case 'IN_PROGRESS': return { bg: '#e6f7ff', color: '#1890ff', label: '进行中' };
    case 'COMPLETED': return { bg: '#f6ffed', color: '#52c41a', label: '已完成' };
    case 'CANCELLED': return { bg: '#fff1f0', color: '#ff4d4f', label: '已取消' };
    default: return { bg: '#f5f5f5', color: '#999', label: status || '未知' };
  }
};

export default function PlanListScreen() {
  const navigation = useNavigation<any>();
  /**
   * 已从后端加载的全部页 (未筛选)。2026-11-12 起分页累加, 不再只取第一页 100 条;
   * 页签 / 搜索在这份上做 (filterPlans), 切换页签或搜索不用重新请求。
   */
  const [loadedPlans, setLoadedPlans] = useState<PlanItem[]>([]);
  const loadedRef = useRef<PlanItem[]>([]);
  /** 后端全部计划数 (未筛选, PageResponse.totalElements); 读不到为 null。 */
  const [serverTotal, setServerTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [activeTab, setActiveTab] = useState('all');
  const [search, setSearch] = useState('');
  /*
   * 排产工具面板。
   *
   * ⛔ 这四个屏【本来就注册在同一个 Stack 里】(DSPlanStackNavigator:59-62)，
   *    走不到只是因为没有任何地方 navigate 过去 —— 不是缺功能，是缺入口。
   *
   * ⛔ 为什么不在顶栏并排放四个按钮: 顶栏已经有「返回 / 标题 / 新建」，
   *    再加四个就挤成一排图标, 而这四件事都不是每天都做的。
   *    收进一个「排产工具」入口 ⇒ 顶栏只多一个, 展开才见四项, 每项带一句人话说明。
   */
  const [toolsOpen, setToolsOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [selectedPlan, setSelectedPlan] = useState<PlanItem | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  /** 屏上这份: 已加载的全部页按页签 + 搜索过滤 (列表、计数、底部汇总都用它)。 */
  const plans = useMemo(
    () => filterPlans(loadedPlans, activeTab, search, Date.now()),
    [loadedPlans, activeTab, search],
  );

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => {
      const plan = plans.find(p => p.id === e.id);
      if (plan) navigation.navigate('PlanDetail', { planId: e.id, planData: plan });
    },
    cancel: (e: RowContext) => Alert.alert('取消', `取消计划 ${e.id} (待接 cancel API)`),
    'print-pdf': (e: RowContext) => { void safePrint('production-work-order', e.id); },
    copy: (e: RowContext) => Alert.alert('复制', `复制计划 ${e.id} (待接 API)`),
  }), [navigation, plans]);

  const sheetCtx: RowContext = selectedPlan
    ? { status: (selectedPlan.status || '').toUpperCase(), id: selectedPlan.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('productionPlan', sheetCtx, { handlers });

  const openSheet = (plan: PlanItem) => { setSelectedPlan(plan); setActionSheetVisible(true); };

  /** 读一页 (1 起); 第 1 页替换, 之后追加。返回这一页之后还有没有。 */
  const fetchPage = useCallback(async (p: number): Promise<boolean> => {
    const res = await productionPlanApiClient.getProductionPlans({ page: p, size: PLAN_PAGE_SIZE } as any);
    const dataObj = (res?.data || res) as any;
    if (res?.success === false || !dataObj) throw new Error((res as any)?.message || '生产计划读取失败');
    const list = dataObj.content || (Array.isArray(dataObj) ? dataObj : []);
    const items: PlanItem[] = Array.isArray(list) ? list : [];
    const totalRaw = Number(dataObj.totalElements);
    const total = Number.isFinite(totalRaw) && totalRaw >= 0 ? totalRaw : null;
    // 用 ref 算合并结果: setState 的更新函数不一定当场执行, 不能靠它拿「已加载多少」来判断还有没有下一页
    const next = mergePlanPage(loadedRef.current, items, p);
    loadedRef.current = next;
    setLoadedPlans(next);
    const more = hasMorePlans(next.length, total, items.length);
    setServerTotal(total);
    setHasMore(more);
    setPage(p);
    return more;
  }, []);

  const loadPlans = useCallback(async (p = 1, isRefresh = false) => {
    try {
      if (p === 1 && !isRefresh) setLoading(true);
      if (p > 1) setLoadingMore(true);
      await fetchPage(p);
      setLoadError(null);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : '生产计划读取失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
      setLoadingMore(false);
    }
  }, [fetchPage]);

  /** 「加载剩余」: 按页 (每页 100) 往下读到底, 不是一次拉一万条。 */
  const loadAllRemaining = useCallback(async () => {
    setLoadingMore(true);
    try {
      let p = page;
      let more = hasMore;
      while (more) {
        p += 1;
        more = await fetchPage(p);
      }
      setLoadError(null);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : '生产计划读取失败');
    } finally {
      setLoadingMore(false);
    }
  }, [fetchPage, hasMore, page]);

  const coverage = coverageNote(loadedPlans.length, serverTotal, hasMore);

  useFocusEffect(useCallback(() => { void loadPlans(1); }, [loadPlans]));

  // U-FOOTER-1: 底部汇总按屏上这份列表算, 跟着页签和搜索走 (2026-11-12 验收: 原来筛选后仍是全部 156 条)。
  // 后端 list-summary 表达不了本屏的前端筛选 (待执行 = PLANNED+PENDING / 去旧草稿 / 搜计划名), 见 planListSummary.ts。
  const summary = useMemo(
    () => ({ entityType: 'productionPlan', stats: summarizePlans(plans) }),
    [plans],
  );

  const onRefresh = () => { setRefreshing(true); loadPlans(1, true); };
  const onEndReached = () => { if (hasMore && !loadingMore && !loading) void loadPlans(page + 1); };

  const renderPlanCard = useCallback(({ item }: { item: PlanItem }) => {
    const s = getStatusStyle(item.status);
    const planned = item.plannedQuantity ?? 0;
    const actual = item.actualQuantity ?? 0;
    const progress = planned > 0 ? Math.min((actual / planned) * 100, 100) : 0;

    return (
      <TouchableOpacity
        style={styles.card}
        activeOpacity={0.7}
        onPress={() => navigation.navigate('PlanDetail', { planId: item.id, planData: item })}
        onLongPress={() => openSheet(item)}
      >
        {/* Top row: plan name + status */}
        <View style={styles.cardTop}>
          <View style={{ flex: 1 }}>
            <Text style={styles.cardTitle} numberOfLines={1}>
              {item.productTypeName || item.productName || (item as any).planName || item.planNumber || '计划'}
            </Text>
            {(item.sourceCustomerName || (item as any).createdByName) ? (
              <Text style={styles.cardSub}>{item.sourceCustomerName ? `客户: ${item.sourceCustomerName}` : `创建人: ${(item as any).createdByName}`}</Text>
            ) : null}
          </View>
          <View style={[styles.statusTag, { backgroundColor: s.bg }]}>
            <Text style={[styles.statusText, { color: s.color }]}>{s.label}</Text>
          </View>
        </View>
        {item.status?.toUpperCase() === 'DRAFT' && (
          <Text style={{ fontSize: 11, color: '#fa8c16', marginBottom: 6 }}>草稿将在24小时后自动清理</Text>
        )}

        {/* Info row */}
        <View style={styles.cardInfo}>
          {planned > 0 && (
            <View style={styles.infoItem}>
              <MaterialCommunityIcons name="package-variant" size={16} color="#999" />
              <Text style={styles.infoText}>{planned} kg</Text>
            </View>
          )}
          {(item.processName || (item as any).totalBatches != null) ? (
            <View style={styles.infoItem}>
              <MaterialCommunityIcons name="cog" size={16} color="#999" />
              <Text style={styles.infoText}>{item.processName || `${(item as any).totalBatches ?? 0} 批次`}</Text>
            </View>
          ) : null}
          <View style={styles.infoItem}>
            <MaterialCommunityIcons name="calendar" size={16} color="#999" />
            <Text style={styles.infoText}>{item.plannedDate || item.planDate || (item as any).planDate || '-'}</Text>
          </View>
        </View>

        {/* Progress */}
        {item.status?.toUpperCase() === 'IN_PROGRESS' && (
          <View style={styles.progressRow}>
            <View style={styles.progressTrack}>
              <View style={[styles.progressFill, { width: `${progress}%` }]} />
            </View>
            <Text style={styles.progressText}>{progress.toFixed(0)}%</Text>
          </View>
        )}

        {/* Source type badge */}
        {item.sourceType && item.sourceType !== 'MANUAL' && (
          <View style={styles.sourceRow}>
            <Text style={styles.sourceLabel}>
              {item.sourceType === 'EXCEL_IMPORT' ? 'Excel导入' :
               item.sourceType === 'AI_CHAT' ? 'AI创建' : item.sourceType}
            </Text>
          </View>
        )}
      </TouchableOpacity>
    );
  }, [navigation]);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="chevron-left" size={28} color="#333" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>生产计划</Text>
        <TouchableOpacity
          style={styles.toolsBtn}
          onPress={() => setToolsOpen(true)}
          testID="plan-tools-entry"
          accessibilityRole="button"
          accessibilityLabel="排产工具"
        >
          <MaterialCommunityIcons name="tune-variant" size={20} color={DISPATCHER_THEME.primary} />
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.newBtn}
          onPress={() => navigation.navigate('PlanCreate')}
        >
          <MaterialCommunityIcons name="plus" size={20} color={DISPATCHER_THEME.primary} />
          <Text style={styles.newBtnText}>新建</Text>
        </TouchableOpacity>
      </View>

      {/* 排产工具 —— 四个屏各自带一句人话说明, ⛔ 不用功能名当说明 */}
      <Modal
        visible={toolsOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setToolsOpen(false)}
      >
        <TouchableOpacity
          style={styles.toolsBackdrop}
          activeOpacity={1}
          onPress={() => setToolsOpen(false)}
          testID="plan-tools-backdrop"
        >
          <View style={styles.toolsSheet}>
            <Text style={styles.toolsTitle}>排产工具</Text>
            {PLAN_TOOLS.map((tool) => (
              <TouchableOpacity
                key={tool.route}
                style={styles.toolRow}
                testID={`plan-tool-${tool.route}`}
                accessibilityRole="button"
                onPress={() => {
                  setToolsOpen(false);
                  navigation.navigate(tool.route as never);
                }}
              >
                <MaterialCommunityIcons
                  name={tool.icon as never}
                  size={22}
                  color={DISPATCHER_THEME.primary}
                  style={styles.toolIcon}
                />
                <View style={styles.toolTextWrap}>
                  <Text style={styles.toolLabel}>{tool.label}</Text>
                  <Text style={styles.toolDesc}>{tool.desc}</Text>
                </View>
                <MaterialCommunityIcons name="chevron-right" size={20} color="#bbb" />
              </TouchableOpacity>
            ))}
          </View>
        </TouchableOpacity>
      </Modal>

      {/* Search */}
      <View style={styles.searchWrap}>
        <Searchbar
          placeholder="搜索计划编号/产品名称..."
          value={search}
          onChangeText={setSearch}
          onSubmitEditing={() => loadPlans(1)}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
          elevation={0}
        />
      </View>

      {/* Status Tabs */}
      <View style={styles.tabRow}>
        {STATUS_TABS.map(tab => (
          <TouchableOpacity
            key={tab.key}
            style={[styles.tab, activeTab === tab.key && styles.tabActive]}
            onPress={() => { setActiveTab(tab.key); }}
          >
            <Text style={[styles.tabText, activeTab === tab.key && styles.tabTextActive]}>
              {tab.label}
            </Text>
          </TouchableOpacity>
        ))}
        <View style={{ flex: 1 }} />
        {/* 没加载完时只写已显示多少, 原因与「加载剩余」在列表下方的黄条里 */}
        <Text style={styles.totalText} testID="plan-list-count" numberOfLines={1}>
          {hasMore ? `已显示 ${plans.length} 条` : `共 ${plans.length} 条`}
        </Text>
      </View>

      {/* Plan List wrapped so sticky footer can sit below */}
      <View style={{ flex: 1 }}>
      {loading && plans.length === 0 ? (
        <ActivityIndicator size="large" style={{ flex: 1 }} color={DISPATCHER_THEME.primary} />
      ) : (
        <FlatList
          data={plans}
          renderItem={renderPlanCard}
          keyExtractor={item => item.id}
          contentContainerStyle={styles.listContent}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          onEndReached={onEndReached}
          onEndReachedThreshold={0.3}
          ListFooterComponent={
            loadingMore ? (
              <ActivityIndicator style={{ marginVertical: 16 }} color={DISPATCHER_THEME.primary} testID="plan-list-loading-more" />
            ) : loadError && loadedPlans.length > 0 ? (
              <TouchableOpacity style={styles.moreBtn} onPress={() => { void loadPlans(page + 1); }} testID="plan-list-retry-more">
                <Text style={styles.moreBtnText}>没读到后面的计划：{loadError}，点这里重试</Text>
              </TouchableOpacity>
            ) : hasMore ? (
              <TouchableOpacity style={styles.moreBtn} onPress={() => { void loadPlans(page + 1); }} testID="plan-list-load-more">
                <Text style={styles.moreBtnText}>加载更多（每次 {PLAN_PAGE_SIZE} 条）</Text>
              </TouchableOpacity>
            ) : null
          }
          ListEmptyComponent={
            <View style={styles.empty}>
              <MaterialCommunityIcons name="clipboard-text-outline" size={56} color="#ddd" />
              <Text style={styles.emptyText}>暂无计划</Text>
              <TouchableOpacity
                style={styles.emptyBtn}
                onPress={() => navigation.navigate('PlanCreate')}
              >
                <Text style={styles.emptyBtnText}>创建第一个计划</Text>
              </TouchableOpacity>
            </View>
          }
        />
      )}
      </View>

      {coverage ? (
        <View style={styles.coverageBar} testID="plan-list-coverage">
          <Text style={styles.coverageText}>{coverage}</Text>
          <TouchableOpacity onPress={() => { void loadAllRemaining(); }} disabled={loadingMore} testID="plan-list-load-all">
            <Text style={styles.coverageAction}>
              {loadingMore ? '加载中…' : `加载剩余 ${serverTotal != null ? Math.max(0, serverTotal - loadedPlans.length) + ' 条' : ''}`}
            </Text>
          </TouchableOpacity>
        </View>
      ) : null}

      <StickyFooterSummary
        stats={summary.stats}
        loading={loading && plans.length === 0}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: {
              entityType: 'PRODUCTION_PLAN',
              initialMessage: `分析当前生产计划列表${formatSummaryForAI(summary, { filter: { status: activeTab, keyword: search.trim() }, note: coverage ?? undefined })}`,
            },
          }))
        }
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedPlan ? `生产计划 ${selectedPlan.planNumber || selectedPlan.id}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedPlan) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'PRODUCTION_PLAN', initialMessage: `${selectedPlan.planNumber || selectedPlan.id}: ` },
          }));
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },

  // Header
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 12, paddingVertical: 12, backgroundColor: '#fff',
    borderBottomWidth: 1, borderBottomColor: '#f0f0f0',
  },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 18, fontWeight: '700', color: '#333' },
  newBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 16, borderWidth: 1, borderColor: DISPATCHER_THEME.primary },
  newBtnText: { fontSize: 14, fontWeight: '600', color: DISPATCHER_THEME.primary },

  // 排产工具 —— 顶栏只多一个图标, 展开才见四项
  toolsBtn: {
    width: 34, height: 34, borderRadius: 17, marginRight: 8,
    alignItems: 'center', justifyContent: 'center',
    borderWidth: 1, borderColor: '#e0e0e0',
  },
  toolsBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'flex-end' },
  toolsSheet: {
    backgroundColor: '#fff', borderTopLeftRadius: 16, borderTopRightRadius: 16,
    paddingTop: 16, paddingBottom: 28, paddingHorizontal: 16,
  },
  toolsTitle: { fontSize: 16, fontWeight: '700', color: '#222', marginBottom: 8, paddingHorizontal: 4 },
  toolRow: {
    flexDirection: 'row', alignItems: 'center',
    paddingVertical: 14, paddingHorizontal: 4,
    borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#eee',
  },
  toolIcon: { width: 32 },
  toolTextWrap: { flex: 1 },
  toolLabel: { fontSize: 15, fontWeight: '600', color: '#222' },
  toolDesc: { fontSize: 12.5, color: '#888', marginTop: 2 },

  // Search
  searchWrap: { paddingHorizontal: 16, paddingTop: 12, paddingBottom: 4, backgroundColor: '#fff' },
  searchBar: { backgroundColor: '#f5f5f5', borderRadius: 10, height: 42 },
  searchInput: { fontSize: 14, minHeight: 0 },

  // Tabs
  tabRow: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 10, backgroundColor: '#fff', gap: 6 },
  tab: { paddingHorizontal: 14, paddingVertical: 6, borderRadius: 16, backgroundColor: '#f5f5f5' },
  tabActive: { backgroundColor: DISPATCHER_THEME.primary },
  tabText: { fontSize: 13, fontWeight: '500', color: '#666' },
  tabTextActive: { color: '#fff' },
  totalText: { fontSize: 12, color: '#999' },
  moreBtn: { marginVertical: 12, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  moreBtnText: { fontSize: 14, color: DISPATCHER_THEME.primary, fontWeight: '600' },
  coverageBar: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8,
    paddingHorizontal: 16, paddingVertical: 8, backgroundColor: '#FFF8E6', borderTopWidth: 1, borderTopColor: '#F5E3B3',
  },
  coverageText: { flex: 1, fontSize: 12, color: '#8A5A00', lineHeight: 17 },
  coverageAction: { fontSize: 13, fontWeight: '700', color: DISPATCHER_THEME.primary, paddingVertical: 6 },

  // List
  listContent: { padding: 16, paddingBottom: 40 },

  // Card
  card: {
    backgroundColor: '#fff', borderRadius: 14, padding: 16, marginBottom: 12,
    shadowColor: '#000', shadowOffset: { width: 0, height: 2 }, shadowOpacity: 0.04, shadowRadius: 8, elevation: 2,
  },
  cardTop: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 },
  cardTitle: { fontSize: 17, fontWeight: '600', color: '#333', flex: 1, marginRight: 8 },
  cardSub: { fontSize: 13, color: '#999', marginTop: 2 },
  statusTag: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 10 },
  statusText: { fontSize: 12, fontWeight: '600' },

  // Info row
  cardInfo: { flexDirection: 'row', gap: 16, marginBottom: 4 },
  infoItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  infoText: { fontSize: 13, color: '#666' },

  // Progress
  progressRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 10 },
  progressTrack: { flex: 1, height: 6, backgroundColor: '#f0f0f0', borderRadius: 3, overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: DISPATCHER_THEME.primary, borderRadius: 3 },
  progressText: { fontSize: 12, color: '#999', width: 32, textAlign: 'right' },

  // Source
  sourceRow: { marginTop: 8 },
  sourceLabel: { fontSize: 11, color: DISPATCHER_THEME.primary, backgroundColor: DISPATCHER_THEME.primary + '12', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 8, alignSelf: 'flex-start' },

  // Empty
  empty: { alignItems: 'center', paddingTop: 80 },
  emptyText: { fontSize: 15, color: '#999', marginTop: 12 },
  emptyBtn: { marginTop: 16, paddingHorizontal: 20, paddingVertical: 10, backgroundColor: DISPATCHER_THEME.primary, borderRadius: 20 },
  emptyBtnText: { color: '#fff', fontSize: 14, fontWeight: '600' },
});
