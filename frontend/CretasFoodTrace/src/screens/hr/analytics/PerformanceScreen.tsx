/**
 * 绩效分析
 *
 * 功能:
 * - 绩效等级分布
 * - 优秀/需关注员工统计
 * - 员工绩效列表
 *
 * 对应原型: /docs/prd/prototype/hr-admin/performance.html
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  View,
  FlatList,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
} from 'react-native';
import { Text, Card, Chip, Avatar, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { hrApiClient } from '../../../services/api/hrApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { useAuthStore } from '../../../store/authStore';
import { hasModulePermission } from '../../../utils/permissionHelper';
import type { User } from '../../../types/auth';
import {
  HR_THEME,
  PERFORMANCE_GRADE_CONFIG,
  type PerformanceStats,
  type EmployeePerformance,
  type PerformanceGrade,
} from '../../../types/hrNavigation';

type Period = 'week' | 'month' | 'quarter' | 'year';

interface PerformanceOwner {
  user: User;
  userId: number;
  factoryId?: string;
  generation: number;
  period: Period;
}

interface LoadOptions {
  refresh?: boolean;
}

export default function PerformanceScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const user = useAuthStore((state) => state.user);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [period, setPeriod] = useState<Period>('month');
  const [stats, setStats] = useState<PerformanceStats | null>(null);
  const [employees, setEmployees] = useState<EmployeePerformance[]>([]);
  const [readError, setReadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const actorRef = useRef<{ user: User | null; generation: number }>({ user: user || null, generation: 0 });
  const ownerRef = useRef<PerformanceOwner | null>(null);
  const requestSeqRef = useRef(0);
  const [dataOwnerKey, setDataOwnerKey] = useState<string | null>(null);
  const translationRef = useRef(t);
  translationRef.current = t;

  if (actorRef.current.user !== user) {
    actorRef.current = { user, generation: actorRef.current.generation + 1 };
  }

  const currentOwner = user && Number.isInteger(user.id)
      ? { user, userId: user.id, factoryId: user.factoryId, generation: actorRef.current.generation, period }
      : null;
  ownerRef.current = currentOwner;
  const ownerKey = currentOwner
    ? `${currentOwner.userId}:${currentOwner.factoryId || ''}:${currentOwner.period}:${currentOwner.generation}`
    : 'none';
  const getPerformanceOwnerKey = (owner: PerformanceOwner): string => (
    String(owner.userId) + ':' + (owner.factoryId || '') + ':' + owner.period + ':' + owner.generation
  );

  useEffect(() => {
    setReadError(null);
    setErrorOwnerKey(null);
    setStats(null);
    setEmployees([]);
    setDataOwnerKey(null);
    setLoading(true);
    setRefreshing(false);
  }, [ownerKey]);

  const isCurrentContext = (owner: PerformanceOwner): boolean => {
    const liveUser = useAuthStore.getState().user;
    const current = ownerRef.current;
    return Boolean(liveUser && current && liveUser === owner.user && liveUser.id === owner.userId
      && liveUser.factoryId === owner.factoryId && actorRef.current.user === owner.user
      && actorRef.current.generation === owner.generation && current.period === owner.period);
  };

  const hasSelfModule = (modules: Awaited<ReturnType<typeof hrShiftApiClient.getSelfModuleAccess>>) => (
    modules.some((module) => module.moduleCode === 'hr_employee'
      && module.permissionModule === 'hr' && module.effectiveAllowed)
  );

  const loadData = useCallback(async (capturedOwner: PerformanceOwner | null, options: LoadOptions = {}) => {
    const owner = capturedOwner;
    const liveUser = useAuthStore.getState().user;
    if (!owner || liveUser !== owner.user || !isCurrentContext(owner)) {
      if (!owner) {
        setReadError(translationRef.current('analytics.performance.readFailed'));
        setErrorOwnerKey('none');
        setStats(null);
        setEmployees([]);
        setDataOwnerKey('none');
        setLoading(false);
        setRefreshing(false);
      }
      return;
    }
    const requestSequence = ++requestSeqRef.current;
    try {
      setReadError(null);
      setErrorOwnerKey(null);
      setStats(null);
      setEmployees([]);
      setDataOwnerKey(null);
      setLoading(true);
      if (options.refresh) setRefreshing(true);
      if (!hasModulePermission(liveUser, 'hr', 'read')) {
        throw new Error(translationRef.current('analytics.performance.readFailed'));
      }
      const moduleAccess = await hrShiftApiClient.getSelfModuleAccess(owner.userId, owner.factoryId);
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      const latestUser = useAuthStore.getState().user;
      if (!hasSelfModule(moduleAccess) || !latestUser
        || !hasModulePermission(latestUser, 'hr', 'read')
        || !isCurrentContext(owner)) {
        throw new Error(translationRef.current('analytics.performance.readFailed'));
      }
      const statsData = await hrApiClient.getPerformanceStats({ period: owner.period, factoryId: owner.factoryId });

      if (requestSequence === requestSeqRef.current && isCurrentContext(owner)) {
        setStats(statsData as PerformanceStats);
        setEmployees(statsData.employees as EmployeePerformance[]);
        setDataOwnerKey(`${owner.userId}:${owner.factoryId || ''}:${owner.period}:${owner.generation}`);
      }
    } catch (error) {
      if (requestSequence === requestSeqRef.current && isCurrentContext(owner)) {
        console.error('加载绩效数据失败:', error);
        setReadError(error instanceof Error ? error.message : translationRef.current('analytics.performance.readFailed'));
        setErrorOwnerKey(getPerformanceOwnerKey(owner));
        setStats(null);
        setEmployees([]);
        setDataOwnerKey(getPerformanceOwnerKey(owner));
      }
    } finally {
      if (requestSequence === requestSeqRef.current && isCurrentContext(owner)) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void loadData(currentOwner);
    }, [loadData, ownerKey])
  );

  const onRefresh = useCallback(() => {
    void loadData(currentOwner, { refresh: true });
  }, [loadData, ownerKey]);

  const renderHeader = () => (
    <View>
      {/* 统计卡片 */}
      <View style={styles.statsRow}>
        <Card style={styles.statCard}>
          <Card.Content style={styles.statContent}>
            <MaterialCommunityIcons
              name="chart-arc"
              size={24}
              color={HR_THEME.primary}
            />
            <Text style={styles.statValue}>
              {stats?.avgScore == null ? '-' : stats.avgScore.toFixed(1)}
            </Text>
            <Text style={styles.statLabel}>{t('analytics.performance.stats.avgScore', { count: stats?.sampleSize ?? 0 })}</Text>
          </Card.Content>
        </Card>
        <Card style={styles.statCard}>
          <Card.Content style={styles.statContent}>
            <MaterialCommunityIcons
              name="star"
              size={24}
              color={HR_THEME.success}
            />
            <Text style={styles.statValue}>
              {stats?.sampleSize ? stats.excellentCount : '-'}
            </Text>
            <Text style={styles.statLabel}>{t('analytics.performance.stats.excellent')}</Text>
          </Card.Content>
        </Card>
        <Card style={styles.statCard}>
          <Card.Content style={styles.statContent}>
            <MaterialCommunityIcons
              name="alert-circle"
              size={24}
              color={HR_THEME.warning}
            />
            <Text style={styles.statValue}>
              {stats?.sampleSize ? stats.needAttentionCount : '-'}
            </Text>
            <Text style={styles.statLabel}>{t('analytics.performance.stats.needsAttention')}</Text>
          </Card.Content>
        </Card>
      </View>

      {/* 等级分布 */}
      <Card style={styles.distributionCard}>
        <Card.Content>
          <Text style={styles.sectionTitle}>{t('analytics.performance.gradeDistribution')}</Text>
          <View style={styles.gradeBar}>
            {stats?.gradeDistribution?.map((item) => {
              const config = PERFORMANCE_GRADE_CONFIG[item.grade];
              if (!config) return null;
              return (
                <View
                  key={item.grade}
                  style={[
                    styles.gradeSegment,
                    {
                      flex: item.percentage || 1,
                      backgroundColor: config.color,
                    },
                  ]}
                />
              );
            })}
          </View>
          <View style={styles.gradeLegend}>
            {Object.entries(PERFORMANCE_GRADE_CONFIG).map(([grade, config]) => (
              <View key={grade} style={styles.legendItem}>
                <View
                  style={[styles.legendDot, { backgroundColor: config.color }]}
                />
                <Text style={styles.legendText}>{config.label}</Text>
              </View>
            ))}
          </View>
        </Card.Content>
      </Card>

      <Text style={styles.listTitle}>{t('analytics.performance.employeeList')}</Text>
    </View>
  );

  const renderItem = ({ item }: { item: EmployeePerformance }) => {
    const gradeConfig = item.grade ? PERFORMANCE_GRADE_CONFIG[item.grade] : null;

    return (
      <TouchableOpacity
        onPress={() =>
          navigation.dispatch(CommonActions.navigate('StaffDetail', { staffId: item.userId }))
        }
      >
        <Card style={styles.employeeCard}>
          <Card.Content style={styles.employeeContent}>
            <Avatar.Text
              size={44}
              label={item.userName?.substring(0, 1) || 'U'}
              style={{ backgroundColor: HR_THEME.primary }}
            />
            <View style={styles.employeeInfo}>
              <Text style={styles.employeeName}>{item.userName}</Text>
              <Text style={styles.employeeDept}>
                {item.department} · {item.position || '员工'}
              </Text>
            </View>
            <View style={styles.scoreContainer}>
              <Text style={[styles.score, { color: gradeConfig?.color || HR_THEME.textMuted }]}>
                {item.score == null ? '-' : item.score}
              </Text>
              {gradeConfig && <Chip
                mode="flat"
                textStyle={{ fontSize: 10, color: gradeConfig.color }}
                style={[styles.gradeChip, { backgroundColor: gradeConfig.bgColor }]}
              >
                {gradeConfig.label}
              </Chip>}
            </View>
          </Card.Content>
        </Card>
      </TouchableOpacity>
    );
  };

  if (loading || dataOwnerKey !== ownerKey) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
            <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>{t('analytics.performance.title')}</Text>
          <View style={styles.placeholder} />
        </View>
        <View style={styles.periodSelector}>
          <SegmentedButtons
            value={period}
            onValueChange={(value) => setPeriod(value as Period)}
            buttons={[
              { value: 'week', label: t('analytics.performance.period.week') },
              { value: 'month', label: t('analytics.performance.period.month') },
              { value: 'quarter', label: t('analytics.performance.period.quarter') },
              { value: 'year', label: t('analytics.performance.period.year') },
            ]}
            style={styles.segmented}
          />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={HR_THEME.primary} />
        </View>
      </SafeAreaView>
    );
  }

  if (readError && errorOwnerKey === ownerKey) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.header}>
          <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
            <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>{t('analytics.performance.title')}</Text>
          <View style={styles.placeholder} />
        </View>
        <Card style={styles.errorCard}>
          <Card.Content>
            <Text style={styles.errorText}>{readError}</Text>
            <TouchableOpacity onPress={() => { void loadData(currentOwner); }}>
              <Text style={styles.retryText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </Card.Content>
        </Card>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('analytics.performance.title')}</Text>
        <View style={styles.placeholder} />
      </View>

      <View style={styles.periodSelector}>
        <SegmentedButtons
          value={period}
          onValueChange={(value) => setPeriod(value as Period)}
          buttons={[
            { value: 'week', label: t('analytics.performance.period.week') },
            { value: 'month', label: t('analytics.performance.period.month') },
            { value: 'quarter', label: t('analytics.performance.period.quarter') },
            { value: 'year', label: t('analytics.performance.period.year') },
          ]}
          style={styles.segmented}
        />
      </View>

      <FlatList
        data={employees}
        keyExtractor={(item) => String(item.userId)}
        renderItem={renderItem}
        ListHeaderComponent={renderHeader}
        contentContainerStyle={styles.listContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <MaterialCommunityIcons
              name="chart-box-outline"
              size={64}
              color={HR_THEME.textMuted}
            />
            <Text style={styles.emptyText}>{t('analytics.performance.empty')}</Text>
          </View>
        }
      />
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
  errorCard: {
    marginHorizontal: 16,
    marginBottom: 8,
    backgroundColor: HR_THEME.cardBackground,
  },
  errorText: {
    color: HR_THEME.danger,
    marginBottom: 8,
  },
  retryText: {
    color: HR_THEME.primary,
    fontWeight: '600',
  },
  segmented: {
    backgroundColor: HR_THEME.background,
  },
  listContent: {
    padding: 16,
  },
  statsRow: {
    flexDirection: 'row',
    marginBottom: 16,
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
    fontSize: 24,
    fontWeight: 'bold',
    color: HR_THEME.textPrimary,
    marginTop: 8,
  },
  statLabel: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
    marginTop: 4,
  },
  distributionCard: {
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
  gradeBar: {
    flexDirection: 'row',
    height: 12,
    borderRadius: 6,
    overflow: 'hidden',
  },
  gradeSegment: {
    minWidth: 4,
  },
  gradeLegend: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginTop: 12,
  },
  legendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 16,
    marginTop: 8,
  },
  legendDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 6,
  },
  legendText: {
    fontSize: 12,
    color: HR_THEME.textSecondary,
  },
  listTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: HR_THEME.textPrimary,
    marginBottom: 12,
  },
  employeeCard: {
    borderRadius: 12,
    marginBottom: 12,
    backgroundColor: HR_THEME.cardBackground,
  },
  employeeContent: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  employeeInfo: {
    flex: 1,
    marginLeft: 12,
  },
  employeeName: {
    fontSize: 15,
    fontWeight: '500',
    color: HR_THEME.textPrimary,
  },
  employeeDept: {
    fontSize: 13,
    color: HR_THEME.textSecondary,
    marginTop: 2,
  },
  scoreContainer: {
    alignItems: 'flex-end',
  },
  score: {
    fontSize: 24,
    fontWeight: 'bold',
  },
  gradeChip: {
    height: 22,
    marginTop: 4,
  },
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyText: {
    marginTop: 16,
    fontSize: 14,
    color: HR_THEME.textMuted,
  },
});
