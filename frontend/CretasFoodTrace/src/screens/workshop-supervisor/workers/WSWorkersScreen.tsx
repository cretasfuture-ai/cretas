/**
 * Workshop Supervisor 人员管理
 * 包含: 搜索、筛选标签、人员列表（含临时工标识）
 *
 * 2026-08-25 改造: 数据源从「写死在组件里」换成 GET /users + GET /timeclock/admin/history。
 * 删掉的是 6 名恒定显示的假员工 —— 王建国 EMP-001 切片操作员 效率评分 96、
 * 张伟 TMP-003 临时工、陈志强 EMP-005「请假」等，外加写死的 8/1/1/2 统计和
 * 一个只 setTimeout 不发请求的下拉刷新。
 * 违反 CLAUDE.md 核心原则 1「禁止降级处理 —— 不返回假数据，明确显示错误」。
 * 回归闸: src/__tests__/unit/screens/workshopSupervisorNoFabricatedDataContract.test.ts
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
import { WSWorkersStackParamList } from '../../../types/navigation';
import { TutorialOverlay } from '../../../components/common/TutorialOverlay';
import { TUTORIAL_WORKERS, useTutorialTarget, useTutorial } from '../../../store/tutorialStore';
import { userApiClient, UserDTO } from '../../../services/api/userApiClient';
import { timeclockApiClient, ClockRecord } from '../../../services/api/timeclockApiClient';
import { DutyState, resolveDuty } from './workerDutyState.logic';

type NavigationProp = NativeStackNavigationProp<WSWorkersStackParamList, 'WSWorkers'>;

/**
 * 员工卡片展示模型。
 *
 * 名册来自 GET /api/mobile/{factoryId}/users，在岗状态来自
 * GET /api/mobile/{factoryId}/timeclock/admin/history（今天这一天），两者按 userId 关联。
 *
 * ⛔ 以下三样后端没有对应数据，所以卡片上**不显示**，而不是本地编一个：
 *   - 效率评分 A/B/C/D  用户接口不返回效率；真要有得接 /time-stats/workers
 *   - 当前任务          用户接口不返回；批次维度在 /processing/batches/{id}/workers
 *   - 请假 / 缺勤        考勤表上「没打卡」既可能是请假也可能是缺勤 ——
 *                      数据里不存在这个区分，写代码造不出来（只报「未打卡」）
 */
interface Worker {
  id: number;
  name: string;
  /** 后端 employeeCode（工号）；没有就退回 username，⛔ 不编一个 EMP-00X */
  employeeLabel: string;
  /** roleDisplayName / position / departmentDisplayName 里第一个有值的 */
  roleLabel?: string;
  isTemporary: boolean;
  isActive: boolean;
  /** 考勤接口不可用时为 undefined —— 与「今天没打卡」是两回事 */
  duty?: DutyState;
  /** 后端 workDurationMinutes；没有就 undefined */
  workMinutes?: number;
  clockInTime?: string;
}

const DUTY_META: Record<DutyState, { label: string; color: string }> = {
  on_duty:         { label: '在岗',   color: '#52c41a' },
  clocked_out:     { label: '已下班', color: '#8c8c8c' },
  not_clocked_in:  { label: '未打卡', color: '#faad14' },
};

/**
 * 筛选标签。
 * ⛔ 原来的「请假」档已删 —— 后端没有请假数据源，那一档只能靠编。
 * count 由实际拉到的名册算出，不写死。
 */
const FILTER_TABS: { key: 'all' | 'on_duty' | 'temporary'; labelKey: string }[] = [
  { key: 'all', labelKey: 'all' },
  { key: 'on_duty', labelKey: 'onDuty' },
  { key: 'temporary', labelKey: 'temporary' },
];

const PAGE_SIZE = 200;

/** 今日日期，YYYY-MM-DD */
function todayStr(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function toWorker(u: UserDTO, record: ClockRecord | undefined, attendanceAvailable: boolean): Worker {
  const raw = record as unknown as { workDurationMinutes?: number; workDuration?: number } | undefined;
  const minutes = raw?.workDurationMinutes ?? raw?.workDuration;
  return {
    id: u.id,
    name: u.fullName || u.realName || u.username,
    employeeLabel: u.employeeCode || u.username,
    roleLabel: u.roleDisplayName || u.position || u.departmentDisplayName || undefined,
    isTemporary: u.isTemporaryWorker === true,
    isActive: u.isActive !== false,
    duty: resolveDuty(record, attendanceAvailable),
    workMinutes: typeof minutes === 'number' ? minutes : undefined,
    clockInTime: record?.clockInTime,
  };
}

function formatClock(iso?: string): string | undefined {
  if (!iso) return undefined;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

export function WSWorkersScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('workshop');
  const insets = useSafeAreaInsets();
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState('all');
  const [refreshing, setRefreshing] = useState(false);

  // Tutorial
  const tgtFilter = useTutorialTarget('workers-filter');
  const tgtList = useTutorialTarget('workers-list');
  const tut = useTutorial(TUTORIAL_WORKERS, () => {
    tgtFilter.measure(); tgtList.measure();
  });

  // 员工名册 + 今日考勤 —— 全部来自后端
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  /** 考勤接口是否拿到了数据。false 时不显示在岗状态, ⛔ 不把「不知道」显示成「离岗」 */
  const [attendanceAvailable, setAttendanceAvailable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      setError(null);

      // 名册是硬依赖：拿不到就整屏报错
      const rosterPage = await userApiClient.getUsers({ page: 1, size: PAGE_SIZE });
      if (!rosterPage || !Array.isArray(rosterPage.content)) {
        throw new Error('用户接口返回的分页结构不符合预期');
      }

      // 考勤是软依赖：拿不到就不显示在岗状态（而不是把所有人显示成离岗）
      let byUser = new Map<number, ClockRecord>();
      let available = false;
      try {
        const day = todayStr();
        const res = await timeclockApiClient.getHistoryRecords({
          startDate: day,
          endDate: day,
          page: 1,
          size: 500,
        });
        const records = res?.data?.content;
        if (res?.success && Array.isArray(records)) {
          byUser = new Map(records.map(r => [r.userId, r]));
          available = true;
        }
      } catch (attErr) {
        console.warn('[WSWorkers] 今日考勤加载失败，在岗状态显示为未知:', attErr);
      }

      setAttendanceAvailable(available);
      setWorkers(rosterPage.content.map(u => toWorker(u, byUser.get(u.id), available)));
      setTotal(typeof rosterPage.totalElements === 'number' ? rosterPage.totalElements : null);
    } catch (err) {
      console.error('[WSWorkers] 加载员工列表失败:', err);
      setWorkers([]);
      setTotal(null);
      setAttendanceAvailable(false);
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  // 筛选员工
  const filteredWorkers = workers.filter(worker => {
    if (activeFilter === 'temporary' && !worker.isTemporary) return false;
    if (activeFilter === 'on_duty' && worker.duty !== 'on_duty') return false;
    if (searchQuery && !worker.name.includes(searchQuery) && !worker.employeeLabel.toLowerCase().includes(searchQuery.toLowerCase())) return false;
    return true;
  });

  // 标签计数 —— 从实际名册算，不写死
  const tabCount = (key: string): number | null => {
    if (key === 'all') return total ?? workers.length;
    if (key === 'temporary') return workers.filter(w => w.isTemporary).length;
    if (key === 'on_duty') return attendanceAvailable ? workers.filter(w => w.duty === 'on_duty').length : null;
    return null;
  };

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 获取在岗状态样式；考勤不可用时明说「未知」
  const getStatusStyle = (duty?: DutyState) => {
    if (!duty) return { bg: '#bfbfbf', text: t('workers.status.unknown') };
    const meta = DUTY_META[duty];
    return { bg: meta.color, text: meta.label };
  };

  return (
    <View style={styles.container}>
      {/* 头部 */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <Text style={styles.headerTitle}>{t('workers.title')}</Text>
        <TouchableOpacity
          style={styles.clockBtn}
          onPress={() => navigation.navigate('ClockIn')}
        >
          <Icon source="clock-check-outline" size={24} color="#fff" />
        </TouchableOpacity>
      </View>

      {/* 搜索栏 */}
      <View style={styles.searchContainer}>
        <View style={styles.searchBar}>
          <Icon source="magnify" size={20} color="#999" />
          <TextInput
            style={styles.searchInput}
            placeholder={t('workers.searchPlaceholder')}
            placeholderTextColor="#999"
            value={searchQuery}
            onChangeText={setSearchQuery}
          />
        </View>
      </View>

      {/* 筛选标签 */}
      <View ref={tgtFilter.ref} onLayout={tgtFilter.onLayout} style={styles.filterContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false}>
          {FILTER_TABS.map(tab => {
            const count = tabCount(tab.key);
            return (
              <TouchableOpacity
                key={tab.key}
                style={[styles.filterTab, activeFilter === tab.key && styles.filterTabActive]}
                onPress={() => setActiveFilter(tab.key)}
              >
                <Text style={[styles.filterTabText, activeFilter === tab.key && styles.filterTabTextActive]}>
                  {count === null
                    ? t(`workers.filters.${tab.labelKey}`)
                    : `${t(`workers.filters.${tab.labelKey}`)} (${count})`}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>
      </View>

      {/*
        人员统计。
        ⛔ 原来的「请假 / 缺勤」两档已删 —— 考勤表上「今天没打卡」既可能是请假也可能是
        缺勤，后端没有能区分这两件事的字段，所以只报得出「未打卡」。
        考勤接口不可用时前两档显示 "—"，⛔ 不显示 0（0 会被读成「没人在岗」）。
      */}
      <View style={styles.statsRow}>
        <View style={[styles.statItem, { backgroundColor: '#f6ffed' }]}>
          <Text style={[styles.statValue, { color: '#52c41a' }]}>
            {attendanceAvailable ? workers.filter(w => w.duty === 'on_duty').length : '—'}
          </Text>
          <Text style={styles.statLabel}>{t('workers.stats.onDuty')}</Text>
        </View>
        <View style={[styles.statItem, { backgroundColor: '#fff7e6' }]}>
          <Text style={[styles.statValue, { color: '#faad14' }]}>
            {attendanceAvailable ? workers.filter(w => w.duty === 'not_clocked_in').length : '—'}
          </Text>
          <Text style={styles.statLabel}>未打卡</Text>
        </View>
        <View style={[styles.statItem, { backgroundColor: '#e6f7ff' }]}>
          <Text style={[styles.statValue, { color: '#1890ff' }]}>
            {workers.filter(w => w.isTemporary).length}
          </Text>
          <Text style={styles.statLabel}>{t('workers.stats.temporary')}</Text>
        </View>
        <View style={[styles.statItem, { backgroundColor: '#f5f5f5' }]}>
          <Text style={[styles.statValue, { color: '#666' }]}>{total ?? '—'}</Text>
          <Text style={styles.statLabel}>总人数</Text>
        </View>
      </View>

      {/* 人员列表 */}
      <View ref={tgtList.ref} onLayout={tgtList.onLayout} style={{ flex: 1 }} collapsable={false}>
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
            <Text style={styles.stateText}>人员加载失败：{error}</Text>
            <TouchableOpacity style={styles.retryBtn} onPress={onRefresh}>
              <Text style={styles.retryBtnText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* 名册拿到了但考勤没拿到 —— 说清楚哪一半是缺的, 不假装在岗状态已知 */}
        {!loading && !error && !attendanceAvailable && workers.length > 0 && (
          <View style={styles.noticeBox}>
            <Icon source="information-outline" size={16} color="#faad14" />
            <Text style={styles.noticeText}>今日考勤数据暂不可用，在岗状态显示为未知</Text>
          </View>
        )}

        {!loading && !error && filteredWorkers.length === 0 && (
          <View style={styles.stateBox}>
            <Icon source="tray" size={44} color="#C0C4CC" />
            <Text style={styles.stateText}>{t('common.noData')}</Text>
          </View>
        )}

        {!loading && !error && filteredWorkers.map(worker => {
          const statusStyle = getStatusStyle(worker.duty);
          const clockIn = formatClock(worker.clockInTime);
          const metaLine = [worker.employeeLabel, worker.roleLabel].filter(Boolean).join(' | ');

          return (
            <TouchableOpacity
              key={worker.id}
              style={styles.workerCard}
              onPress={() => navigation.navigate('WorkerDetail', { workerId: worker.id })}
            >
              <View style={styles.workerAvatar}>
                <Text style={styles.workerAvatarText}>{worker.name.charAt(0)}</Text>
                <View style={[styles.statusDot, { backgroundColor: statusStyle.bg }]} />
              </View>

              <View style={styles.workerInfo}>
                <View style={styles.workerNameRow}>
                  <Text style={styles.workerName}>{worker.name}</Text>
                  {worker.isTemporary && (
                    <View style={styles.tempBadge}>
                      <Text style={styles.tempBadgeText}>{t('workers.tempBadge')}</Text>
                    </View>
                  )}
                </View>
                <Text style={styles.workerMeta}>{metaLine}</Text>
                {clockIn && (
                  <Text style={styles.workerTask}>{t('workers.clockIn.clockTime', { time: clockIn })}</Text>
                )}
              </View>

              <View style={styles.workerRight}>
                <View style={[styles.statusBadge, { backgroundColor: `${statusStyle.bg}20` }]}>
                  <Text style={[styles.statusBadgeText, { color: statusStyle.bg }]}>{statusStyle.text}</Text>
                </View>
                {/* 工时只有后端给了 workDurationMinutes 才显示, ⛔ 缺失不显示 0h */}
                {worker.workMinutes !== undefined && (
                  <Text style={styles.hoursText}>
                    {t('workers.hours', { hours: (worker.workMinutes / 60).toFixed(1) })}
                  </Text>
                )}
              </View>
            </TouchableOpacity>
          );
        })}
        <View style={{ height: 100 }} />
      </ScrollView>
      </View>
      <TutorialOverlay
        visible={tut.visible}
        steps={TUTORIAL_WORKERS.steps}
        currentStep={tut.step}
        onNext={tut.onNext}
        onSkip={tut.onSkip}
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
  clockBtn: {
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
    gap: 8,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 12,
    borderRadius: 8,
  },
  statValue: {
    fontSize: 20,
    fontWeight: 'bold',
  },
  statLabel: {
    fontSize: 11,
    color: '#666',
    marginTop: 4,
  },

  // 列表
  listContainer: {
    flex: 1,
    paddingHorizontal: 16,
    paddingTop: 8,
  },
  workerCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  workerAvatar: {
    width: 48,
    height: 48,
    borderRadius: 24,
    backgroundColor: '#667eea',
    justifyContent: 'center',
    alignItems: 'center',
    position: 'relative',
  },
  workerAvatarText: {
    fontSize: 18,
    fontWeight: '600',
    color: '#fff',
  },
  statusDot: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    width: 14,
    height: 14,
    borderRadius: 7,
    borderWidth: 2,
    borderColor: '#fff',
  },
  workerInfo: {
    flex: 1,
    marginLeft: 12,
  },
  workerNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  workerName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  tempBadge: {
    marginLeft: 6,
    paddingHorizontal: 6,
    paddingVertical: 2,
    backgroundColor: '#e6f7ff',
    borderRadius: 4,
    borderWidth: 1,
    borderColor: '#91d5ff',
  },
  tempBadgeText: {
    fontSize: 10,
    color: '#1890ff',
    fontWeight: '500',
  },
  workerMeta: {
    fontSize: 13,
    color: '#999',
    marginTop: 4,
  },
  workerTask: {
    fontSize: 12,
    color: '#667eea',
    marginTop: 4,
  },
  workerRight: {
    alignItems: 'center',
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 10,
    marginBottom: 4,
  },
  statusBadgeText: {
    fontSize: 11,
    fontWeight: '500',
  },
  hoursText: {
    fontSize: 12,
    color: '#999',
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
  noticeBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#fffbe6',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    marginBottom: 8,
  },
  noticeText: {
    flex: 1,
    fontSize: 12,
    color: '#8c6d1f',
  },
});

export default WSWorkersScreen;
