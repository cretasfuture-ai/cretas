/**
 * 工时成本分析
 *
 * 功能:
 * - 总成本/总工时/人均工时统计
 * - 部门成本分布饼图
 * - 员工工时排行榜
 *
 * 对应原型: /docs/prd/prototype/hr-admin/labor-cost.html
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback, useLayoutEffect, useMemo, useRef } from 'react';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
} from 'react-native';
import { Text, Card, SegmentedButtons, Avatar, ActivityIndicator } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';
import wageApiClient, { type LaborCostAnalysis } from '../../../services/api/wageApiClient';
import hrShiftApiClient from '../../../services/api/hrShiftApiClient';
import { periodDates, type LaborCostPeriod } from '../../../utils/laborCostData';
import { useAuthStore } from '../../../store/authStore';
import { getRoleCode, hasModulePermission } from '../../../utils/permissionHelper';
import type { User } from '../../../types/auth';
import {
  HR_THEME,
  type WorkerHoursRank,
} from '../../../types/hrNavigation';

type LaborCostSummaryReadback = {
  period: string;
  totalCost: number | null;
  totalWorkMinutes: number | null;
  avgHourlyRate: number | null;
  participatingBatches: number | null;
  participatingEmployees: number;
};

type DepartmentHoursDistribution = {
  departmentId: string;
  departmentName: string;
  totalHours: number;
  color?: string;
};

type Period = LaborCostPeriod;

type LaborCostContext = Readonly<{
  userId: number | null;
  factoryId: string | null;
  userType: string;
  role: string;
  permissions: string;
  period: Period;
  readAllowed: boolean;
}>;

function captureContext(user: User | null, period: Period): LaborCostContext {
  return {
    userId: user?.id ?? null,
    factoryId: user?.factoryId ?? null,
    userType: user?.userType ?? '',
    role: getRoleCode(user),
    permissions: JSON.stringify(user?.userType === 'factory' ? user.factoryUser?.modulePermissions ?? {} : {}),
    period,
    readAllowed: hasModulePermission(user, 'hr', 'read'),
  };
}

function contextKey(context: LaborCostContext): string {
  return JSON.stringify([context.userId, context.factoryId, context.userType, context.role,
    context.permissions, context.readAllowed, context.period]);
}

export default function LaborCostScreen() {
  const [period, setPeriod] = useState<Period>('month');
  // Subscribe to the primitive fingerprint so in-place actor/permission updates
  // cannot reuse the previous keyed instance or its immutable request owner.
  const fingerprint = useAuthStore((state) => contextKey(captureContext(state.user, period)));
  const context = useMemo(() => captureContext(useAuthStore.getState().user, period), [fingerprint, period]);
  return <LaborCostScreenInner key={fingerprint} context={context} onPeriodChange={setPeriod} />;
}

function LaborCostScreenInner({ context, onPeriodChange }: { context: LaborCostContext; onPeriodChange: (period: Period) => void }) {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const period = context.period;
  const setPeriod = onPeriodChange;
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [summary, setSummary] = useState<LaborCostSummaryReadback | null>(null);
  const [deptDistribution, setDeptDistribution] = useState<DepartmentHoursDistribution[]>([]);
  const [workerRank, setWorkerRank] = useState<WorkerHoursRank[]>([]);
  const [wageAnalysis, setWageAnalysis] = useState<LaborCostAnalysis | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);
  const ownerKeyRef = useRef('');
  const activeRef = useRef(false);

  const ownerKey = contextKey(context);
  useLayoutEffect(() => {
    ownerKeyRef.current = ownerKey;
    activeRef.current = true;
    setLoading(true);
    setError(null);
    setSummary(null);
    setWageAnalysis(null);
    setDeptDistribution([]);
    setWorkerRank([]);
    return () => { activeRef.current = false; };
  }, [ownerKey]);

  const liveMatches = useCallback(() =>
    contextKey(captureContext(useAuthStore.getState().user, context.period)) === ownerKey,
  [context.period, ownerKey]);

  const loadData = useCallback(async (isRefresh = false) => {
    if (!activeRef.current || ownerKey !== ownerKeyRef.current || !liveMatches()) return;
    // Reserve the attempt before self access: a slow earlier permission read
    // must never supersede a newer refresh or survive blur/refocus.
    const sequence = ++requestSeqRef.current;
    const isCurrent = () => activeRef.current && sequence === requestSeqRef.current
      && ownerKey === ownerKeyRef.current && liveMatches();
    setError(null);
    if (!summary) setLoading(true);
    setRefreshing(isRefresh && summary !== null);
    try {
      if (!context.userId || !context.factoryId || !context.readAllowed) {
        throw new Error(!context.userId ? '当前账号未登录'
          : !context.factoryId ? '无法获取工厂信息' : '当前账号没有人事读取权限');
      }
      const moduleAccess = await hrShiftApiClient.getSelfModuleAccess(context.userId, context.factoryId);
      if (!isCurrent()) return;
      if (!moduleAccess.some((module) => module.moduleCode === 'hr_employee'
        && module.permissionModule === 'hr' && module.effectiveAllowed === true)) {
        throw new Error('当前账号没有员工数据读取权限');
      }
      const { startDate, endDate } = periodDates(context.period);
      const [summaryData, deptData, rankData, wageData] = await Promise.all([
        timeStatsApiClient.getLaborCostSummary({ period: context.period, startDate, endDate, factoryId: context.factoryId }),
        timeStatsApiClient.getCostByDepartment({ period: context.period, startDate, endDate, factoryId: context.factoryId }),
        timeStatsApiClient.getWorkerHoursRank({ period: context.period, startDate, endDate, limit: 10, factoryId: context.factoryId }),
        wageApiClient.analyzeLaborCost(startDate, endDate, context.factoryId),
      ]);
      if (!isCurrent()) return;
      setSummary({ ...summaryData, period: context.period });
      setWageAnalysis(wageData);
      const colors = ['#667eea', '#52c41a', '#fa8c16', '#1890ff', '#722ed1', '#eb2f96'];
      setDeptDistribution(deptData.map((department, index) => ({
        ...department, color: colors[index % colors.length],
      })));
      setWorkerRank(rankData);
    } catch (caught) {
      if (!isCurrent()) return;
      setSummary(null);
      setWageAnalysis(null);
      setDeptDistribution([]);
      setWorkerRank([]);
      setError(caught instanceof Error ? caught.message : '加载失败，请重试');
    } finally {
      if (isCurrent()) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [context, liveMatches, ownerKey, summary]);

  // Keep the focus effect stable when a successful read changes summary.
  const loaderRef = useRef(loadData);
  loaderRef.current = loadData;
  useFocusEffect(useCallback(() => {
    if (!liveMatches()) return;
    activeRef.current = true;
    void loaderRef.current();
    return () => { activeRef.current = false; requestSeqRef.current += 1; };
  }, [liveMatches]));

  const onRefresh = useCallback(() => { void loadData(true); }, [loadData]);

  const formatMinutes = (minutes: number): string => {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    return `${hours}h ${mins}m`;
  };

  const formatCurrency = (amount: number): string => {
    return `¥${amount.toLocaleString()}`;
  };

  const displayCurrency = (amount: number | null | undefined) =>
    amount == null || Number.isNaN(amount) ? '—' : formatCurrency(amount);

  if (loading) {
    return (
      <View testID="labor-cost-loading" style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={HR_THEME.primary} />
      </View>
    );
  }

  if (error) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.loadingContainer}>
          <Text accessibilityRole="alert">{error}</Text>
          <TouchableOpacity onPress={onRefresh} style={styles.retryButton}>
            <Text>重试</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>工时成本</Text>
        <View style={styles.placeholder} />
      </View>

      <View style={styles.periodSelector}>
        <SegmentedButtons
          value={period}
          onValueChange={(value) => setPeriod(value as Period)}
          buttons={[
            { value: 'week', label: '本周' },
            { value: 'month', label: '本月' },
            { value: 'quarter', label: '本季' },
          ]}
          style={styles.segmented}
        />
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {error && <Text accessibilityRole="alert">{error}</Text>}
        {/* 统计卡片 */}
        <View style={styles.statsRow}>
          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <MaterialCommunityIcons
                name="currency-cny"
                size={24}
                color={HR_THEME.primary}
              />
              <Text testID="labor-cost-total-wage" style={styles.statValue}>
                {displayCurrency(wageAnalysis ? wageAnalysis.totalWage : summary?.totalCost)}
              </Text>
              <Text style={styles.statLabel}>总成本</Text>
            </Card.Content>
          </Card>
          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <MaterialCommunityIcons
                name="clock-outline"
                size={24}
                color={HR_THEME.info}
              />
              <Text style={styles.statValue}>
                {summary?.totalWorkMinutes == null ? '—' : formatMinutes(summary.totalWorkMinutes)}
              </Text>
              <Text style={styles.statLabel}>总工时</Text>
            </Card.Content>
          </Card>
        </View>

        <View style={styles.statsRow}>
          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <MaterialCommunityIcons
                name="account-group"
                size={24}
                color={HR_THEME.success}
              />
              <Text style={styles.statValue}>
                {summary?.participatingEmployees ?? '—'}
              </Text>
              <Text style={styles.statLabel}>参与员工</Text>
            </Card.Content>
          </Card>
          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <MaterialCommunityIcons
                name="chart-timeline"
                size={24}
                color={HR_THEME.warning}
              />
              <Text style={styles.statValue}>
                {displayCurrency(summary?.avgHourlyRate)}
              </Text>
              <Text style={styles.statLabel}>时薪均值</Text>
            </Card.Content>
          </Card>
        </View>

        {/* 部门成本分布 */}
        <Card style={styles.sectionCard}>
          <Card.Content>
            <Text style={styles.sectionTitle}>部门工时分布</Text>
            {deptDistribution.length === 0 ? (
              <Text style={styles.emptyText}>暂无数据</Text>
            ) : (
              deptDistribution.map((dept, index) => (
                <View key={dept.departmentId} style={styles.deptRow}>
                  <View style={styles.deptInfo}>
                    <View
                      style={[styles.deptColor, { backgroundColor: dept.color }]}
                    />
                    <Text style={styles.deptName}>{dept.departmentName}</Text>
                  </View>
                  <View style={styles.deptStats}>
                    <Text style={styles.deptCost}>{dept.totalHours.toFixed(1)}h</Text>
                  </View>
                </View>
              ))
            )}
          </Card.Content>
        </Card>

        {/* 员工工时排行 */}
        <Card style={styles.sectionCard}>
          <Card.Content>
            <Text style={styles.sectionTitle}>员工工时排行</Text>
            {workerRank.length === 0 ? (
              <Text style={styles.emptyText}>暂无数据</Text>
            ) : (
              workerRank.map((worker) => (
                <TouchableOpacity
                  key={worker.userId}
                  style={styles.rankRow}
                  onPress={() =>
                    navigation.dispatch(CommonActions.navigate('StaffDetail', { staffId: worker.userId }))
                  }
                >
                  <View style={styles.rankBadge}>
                    <Text style={styles.rankNumber}>{worker.rank}</Text>
                  </View>
                  <Avatar.Text
                    size={36}
                    label={worker.userName?.substring(0, 1) || 'U'}
                    style={{ backgroundColor: HR_THEME.primary }}
                  />
                  <View style={styles.rankInfo}>
                    <Text style={styles.rankName}>{worker.userName}</Text>
                    <Text style={styles.rankDept}>{worker.department || '未分配'}</Text>
                  </View>
                  <Text style={styles.rankHours}>{worker.totalHours.toFixed(1)}h</Text>
                </TouchableOpacity>
              ))
            )}
          </Card.Content>
        </Card>

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: HR_THEME.background,
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: HR_THEME.background,
  },
  retryButton: {
    marginTop: 16,
    paddingHorizontal: 20,
    paddingVertical: 10,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: HR_THEME.cardBackground,
    borderBottomWidth: 1,
    borderBottomColor: HR_THEME.border,
  },
  backBtn: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
  },
  placeholder: {
    width: 32,
  },
  periodSelector: {
    padding: 16,
    backgroundColor: HR_THEME.cardBackground,
  },
  segmented: {
    backgroundColor: HR_THEME.background,
  },
  content: {
    flex: 1,
    padding: 16,
  },
  statsRow: {
    flexDirection: 'row',
    marginBottom: 12,
  },
  statCard: {
    flex: 1,
    marginHorizontal: 4,
    borderRadius: 12,
    backgroundColor: HR_THEME.cardBackground,
  },
  statContent: {
    alignItems: 'center',
    paddingVertical: 16,
  },
  statValue: {
    fontSize: 20,
    fontWeight: 'bold',
    color: HR_THEME.textPrimary,
    marginTop: 8,
  },
  statLabel: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
    marginTop: 4,
  },
  sectionCard: {
    borderRadius: 12,
    marginBottom: 16,
    backgroundColor: HR_THEME.cardBackground,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
    marginBottom: 16,
  },
  emptyText: {
    textAlign: 'center',
    color: HR_THEME.textMuted,
    paddingVertical: 20,
  },
  deptRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: HR_THEME.border,
  },
  deptInfo: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  deptColor: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 12,
  },
  deptName: {
    fontSize: 14,
    color: HR_THEME.textPrimary,
  },
  deptStats: {
    alignItems: 'flex-end',
  },
  deptCost: {
    fontSize: 14,
    fontWeight: '500',
    color: HR_THEME.textPrimary,
  },
  deptPercent: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
  },
  rankRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: HR_THEME.border,
  },
  rankBadge: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: HR_THEME.primary + '20',
    justifyContent: 'center',
    alignItems: 'center',
    marginRight: 12,
  },
  rankNumber: {
    fontSize: 12,
    fontWeight: 'bold',
    color: HR_THEME.primary,
  },
  rankInfo: {
    flex: 1,
    marginLeft: 12,
  },
  rankName: {
    fontSize: 14,
    fontWeight: '500',
    color: HR_THEME.textPrimary,
  },
  rankDept: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
  },
  rankHours: {
    fontSize: 14,
    fontWeight: '600',
    color: HR_THEME.primary,
  },
  bottomSpacer: {
    height: 80,
  },
});
