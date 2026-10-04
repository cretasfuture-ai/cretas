import React, { useState, useEffect, useRef } from 'react';
import { View, StyleSheet, ScrollView } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  List,
  ActivityIndicator,
  Button,
  SegmentedButtons,
  Divider,
  ProgressBar,
} from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { timeStatsApiClient } from '../../services/api/timeStatsApiClient';
import { useAuthStore } from '../../store/authStore';
import { logger } from '../../utils/logger';
import { formatDate } from '../../utils/formatters';
import { getFactoryId, type User } from '../../types/auth';
import { hasModulePermission } from '../../utils/permissionHelper';
import {
  AttendanceTimeStats,
  parseDepartmentAttendanceStats,
  parseFactoryAttendanceStats,
  parsePersonalAttendanceStats,
} from '../../utils/attendanceStats';

// 创建AttendanceStatistics专用logger
const attendanceStatsLogger = logger.createContextLogger('AttendanceStatistics');

type StatsState = { queryKey: string; value: AttendanceTimeStats };
type ErrorState = { queryKey: string; message: string };

function attendanceActorKey(user: User | null | undefined): string {
  if (!user) return 'none';
  const factoryId = getFactoryId(user) || '';
  const userId = user.id != null ? String(user.id) : user.username || '';
  const role = user.userType === 'factory' ? user.factoryUser?.role || '' : user.userType;
  const permissions = user.userType === 'factory' ? JSON.stringify(user.factoryUser?.modulePermissions || {}) : '';
  return `${user.userType}:${userId}:${factoryId}:${role}:${permissions}`;
}

/**
 * 工时统计页面
 * 权限：操作员查看个人、部门管理员查看本部门、super_admin查看全厂
 * 功能：日报、月报、部门统计、员工统计
 */

export default function AttendanceStatisticsScreen() {
  const navigation = useNavigation();
  const { user } = useAuthStore();
  const { t } = useTranslation('hr');

  const [loading, setLoading] = useState(true);
  const [timePeriod, setTimePeriod] = useState('today'); // today, week, month, custom
  const [viewDimension, setViewDimension] = useState('personal'); // personal, department, factory
  const [statsState, setStatsState] = useState<StatsState | null>(null);
  const [errorState, setErrorState] = useState<ErrorState | null>(null);
  const requestIdRef = useRef(0);
  const mountedRef = useRef(true);
  const currentQueryRef = useRef('');
  const previousQueryRef = useRef('');

  // 权限控制
  const userType = user?.userType || 'factory';
  const roleCode = user?.factoryUser?.role || 'viewer';
  const isPlatformAdmin = userType === 'platform';
  const isSuperAdmin = roleCode === 'factory_super_admin';
  const isDepartmentAdmin = roleCode === 'department_admin';

  // 根据权限限制可查看的维度
  const canViewFactory = isPlatformAdmin || isSuperAdmin;
  const canViewDepartment = canViewFactory || isDepartmentAdmin;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestIdRef.current += 1;
    };
  }, []);

  // 根据时间周期生成日期范围
  const getDateRange = (): { startDate: string; endDate: string } => {
    const today = new Date();
    let startDate: Date;
    let endDate: Date = today;

    switch (timePeriod) {
      case 'today':
        startDate = today;
        break;
      case 'week':
        // 本周（周一到今天）
        const dayOfWeek = today.getDay();
        const diff = dayOfWeek === 0 ? 6 : dayOfWeek - 1; // 周日是0，调整为从周一开始
        startDate = new Date(today);
        startDate.setDate(today.getDate() - diff);
        break;
      case 'month':
        // 本月
        startDate = new Date(today.getFullYear(), today.getMonth(), 1);
        break;
      case 'custom':
        // 自定义，默认最近30天
        startDate = new Date(today);
        startDate.setDate(today.getDate() - 30);
        break;
      default:
        startDate = today;
    }

    return {
      startDate: formatDate(startDate) as string, // YYYY-MM-DD格式
      endDate: formatDate(endDate) as string,
    };
  };

  const capturedUser = user as User | null;
  const factoryId = getFactoryId(capturedUser) || '';
  const actorKey = attendanceActorKey(capturedUser);
  const dateRange = getDateRange();
  const queryKey = `${actorKey}:${factoryId}:${timePeriod}:${viewDimension}:${dateRange.startDate}:${dateRange.endDate}`;
  if (previousQueryRef.current !== queryKey) {
    previousQueryRef.current = queryKey;
    currentQueryRef.current = queryKey;
    requestIdRef.current += 1;
  }

  const visibleStats = statsState?.queryKey === queryKey ? statsState.value : null;
  const visibleError = errorState?.queryKey === queryKey ? errorState.message : null;
  const visibleLoading = loading || (!visibleStats && !visibleError);

  useEffect(() => {
    void loadStats();
  }, [queryKey, roleCode]);

  const liveActorMatches = (requiresHr = false) => {
    const liveUser = useAuthStore.getState().user as User | null;
    return mountedRef.current
      && capturedUser !== null
      && liveUser === capturedUser
      && attendanceActorKey(liveUser) === actorKey
      && getFactoryId(liveUser) === factoryId
      && (!requiresHr || hasModulePermission(liveUser, 'hr', 'read'));
  };

  const loadStats = async () => {
    if (currentQueryRef.current !== queryKey) return;
    const requiresHr = viewDimension !== 'personal';
    const canReadSelectedDimension = viewDimension === 'personal'
      || (viewDimension === 'department' ? canViewDepartment : canViewFactory);
    if (!canReadSelectedDimension || !liveActorMatches(requiresHr)) {
      setStatsState(null);
      setErrorState({ queryKey, message: t('attendance.manage.noPermission') });
      setLoading(false);
      return;
    }
    const requestId = ++requestIdRef.current;
    const ownerQueryKey = queryKey;
    const ownerUser = capturedUser;
    const ownerFactoryId = factoryId;
    const isCurrent = () => mountedRef.current
      && requestId === requestIdRef.current
      && currentQueryRef.current === ownerQueryKey
      && liveActorMatches(requiresHr)
      && canReadSelectedDimension
      && getFactoryId(useAuthStore.getState().user as User | null) === ownerFactoryId;

    setStatsState(null);
    setErrorState(null);
    setLoading(true);
    try {
      if (!ownerUser || !ownerFactoryId) {
        throw new Error('当前账号缺少工厂信息');
      }
      let parsedStats: AttendanceTimeStats;
      if (viewDimension === 'personal') {
        const userId = ownerUser.id;
        if (!userId) throw new Error(t('attendance.statistics.errors.noUserId'));
        const response = await timeStatsApiClient.getEmployeeTimeStats(Number(userId), dateRange, ownerFactoryId);
        parsedStats = parsePersonalAttendanceStats(response, timePeriod);
      } else if (viewDimension === 'department') {
        const department = ownerUser.factoryUser?.department;
        if (!department) throw new Error('当前账号缺少部门信息');
        const response = await timeStatsApiClient.getDepartmentTimeStats(department, dateRange, ownerFactoryId);
        parsedStats = parseDepartmentAttendanceStats(response, department, timePeriod);
      } else if (timePeriod === 'month') {
        const response = await timeStatsApiClient.getMonthlyStats(new Date().getFullYear(), new Date().getMonth() + 1, ownerFactoryId);
        parsedStats = parseFactoryAttendanceStats(response, timePeriod);
      } else if (timePeriod === 'week') {
        const response = await timeStatsApiClient.getWeeklyStats(dateRange.startDate, ownerFactoryId);
        parsedStats = parseFactoryAttendanceStats(response, timePeriod);
      } else {
        const response = await timeStatsApiClient.getDailyStats(dateRange.endDate, ownerFactoryId);
        parsedStats = parseFactoryAttendanceStats(response, timePeriod);
      }
      if (isCurrent()) setStatsState({ queryKey: ownerQueryKey, value: parsedStats });
    } catch (error) {
      if (isCurrent()) {
        const message = error instanceof Error && error.message ? error.message : t('attendance.statistics.errors.loadFailed');
        setStatsState(null);
        setErrorState({ queryKey: ownerQueryKey, message });
        attendanceStatsLogger.error('加载统计数据失败', new Error('attendance_stats_read_failed'));
      }
    } finally {
      if (isCurrent()) setLoading(false);
    }
  };

  const getPeriodLabel = () => {
    switch (timePeriod) {
      case 'today': return t('attendance.statistics.periods.today');
      case 'week': return t('attendance.statistics.periods.week');
      case 'month': return t('attendance.statistics.periods.month');
      case 'custom': return t('attendance.statistics.periods.custom');
      default: return timePeriod;
    }
  };

  const getDimensionLabel = () => {
    switch (viewDimension) {
      case 'personal': return t('attendance.statistics.dimensionLabels.personal');
      case 'department': return t('attendance.statistics.dimensionLabels.department');
      case 'factory': return t('attendance.statistics.dimensionLabels.factory');
      default: return viewDimension;
    }
  };

  return (
    <View style={styles.container}>
      {/* Header */}
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('attendance.statistics.title')} />
        <Appbar.Action testID="attendance-statistics-refresh" icon="refresh" onPress={loadStats} />
      </Appbar.Header>

      <ScrollView style={styles.content}>
        {/* Time Period Selector */}
        <Card style={styles.selectorCard}>
          <Card.Content>
            <Text style={styles.selectorLabel}>{t('attendance.statistics.timeRange')}</Text>
            <SegmentedButtons
              value={timePeriod}
              onValueChange={setTimePeriod}
              buttons={[
                { value: 'today', label: t('attendance.statistics.periods.today') },
                { value: 'week', label: t('attendance.statistics.periods.week') },
                { value: 'month', label: t('attendance.statistics.periods.month') },
              ]}
            />
          </Card.Content>
        </Card>

        {/* Dimension Selector */}
        <Card style={styles.selectorCard}>
          <Card.Content>
            <Text style={styles.selectorLabel}>{t('attendance.statistics.dimension')}</Text>
            <SegmentedButtons
              value={viewDimension}
              onValueChange={setViewDimension}
              buttons={[
                { value: 'personal', label: t('attendance.statistics.dimensions.personal'), disabled: false },
                { value: 'department', label: t('attendance.statistics.dimensions.department'), disabled: !canViewDepartment },
                { value: 'factory', label: t('attendance.statistics.dimensions.factory'), disabled: !canViewFactory },
              ]}
            />
          </Card.Content>
        </Card>

        {visibleLoading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" />
            <Text style={styles.loadingText}>{t('attendance.statistics.loading')}</Text>
          </View>
        ) : visibleError ? (
          <Card style={styles.errorCard} testID="attendance-statistics-error">
            <Card.Content>
              <Text style={styles.errorTitle}>{t('attendance.stats.loadFailedTitle')}</Text>
              <Text style={styles.errorText}>{visibleError}</Text>
              <Button testID="attendance-statistics-retry" mode="contained" onPress={() => { void loadStats(); }}>
                {t('common.retry')}
              </Button>
            </Card.Content>
          </Card>
        ) : visibleStats ? (
          <>
            {/* Stats Summary Card */}
            <Card style={styles.summaryCard}>
              <Card.Content>
                <Text style={styles.summaryTitle}>
                  {getDimensionLabel()} - {getPeriodLabel()}
                </Text>
                <Divider style={styles.divider} />

                <View style={styles.statsGrid}>
                  <View style={styles.statBox}>
                    <Text style={styles.statValue}>{visibleStats.totalHours.toFixed(1)}</Text>
                    <Text style={styles.statLabel}>{t('attendance.statistics.totalHours')}</Text>
                  </View>
                  <View style={styles.statBox}>
                    <Text style={[styles.statValue, { color: '#2E7D32' }]}>
                      {visibleStats.regularHours.toFixed(1)}
                    </Text>
                    <Text style={styles.statLabel}>{t('attendance.statistics.regularHours')}</Text>
                  </View>
                  <View style={styles.statBox}>
                    <Text style={[styles.statValue, { color: '#D32F2F' }]}>
                      {visibleStats.overtimeHours.toFixed(1)}
                    </Text>
                    <Text style={styles.statLabel}>{t('attendance.statistics.overtimeHours')}</Text>
                  </View>
                  <View style={styles.statBox}>
                    <Text style={styles.statValue}>
                      {visibleStats.averageHours.toFixed(1)}
                    </Text>
                    <Text style={styles.statLabel}>
                      {t(viewDimension === 'personal'
                        ? 'attendance.statistics.avgDailyHours'
                        : 'attendance.statistics.avgEmployeeHours')}
                    </Text>
                  </View>
                </View>

                {/* Visual Progress */}
                {visibleStats.totalHours > 0 && (
                  <View style={styles.progressSection}>
                    <View style={styles.progressRow}>
                      <Text style={styles.progressLabel}>{t('attendance.statistics.regularPercent')}</Text>
                      <Text style={styles.progressValue}>
                        {((visibleStats.regularHours / visibleStats.totalHours) * 100).toFixed(1)}%
                      </Text>
                    </View>
                    <ProgressBar
                      progress={visibleStats.regularHours / visibleStats.totalHours}
                      color="#2E7D32"
                      style={styles.progressBar}
                    />

                    <View style={styles.progressRow}>
                      <Text style={styles.progressLabel}>{t('attendance.statistics.overtimePercent')}</Text>
                      <Text style={styles.progressValue}>
                        {((visibleStats.overtimeHours / visibleStats.totalHours) * 100).toFixed(1)}%
                      </Text>
                    </View>
                    <ProgressBar
                      progress={visibleStats.overtimeHours / visibleStats.totalHours}
                      color="#D32F2F"
                      style={styles.progressBar}
                    />
                  </View>
                )}
              </Card.Content>
            </Card>

            {/* Empty State for Personal View */}
            {viewDimension === 'personal' && visibleStats.totalHours === 0 && (
              <Card style={styles.emptyCard}>
                <Card.Content style={styles.emptyContent}>
                  <List.Icon icon="clock-outline" color="#999" />
                  <Text style={styles.emptyText}>{t('attendance.statistics.noData')}</Text>
                  <Text style={styles.emptyHint}>{t('attendance.statistics.noDataHint')}</Text>
                </Card.Content>
              </Card>
            )}
          </>
        ) : null}

        <View style={styles.bottomPadding} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  content: {
    flex: 1,
  },
  loadingContainer: {
    padding: 40,
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  errorCard: {
    margin: 16,
    marginBottom: 8,
  },
  errorTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 8,
    color: '#B71C1C',
  },
  errorText: {
    color: '#666',
    marginBottom: 12,
  },
  selectorCard: {
    margin: 16,
    marginBottom: 8,
  },
  selectorLabel: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
  },
  summaryCard: {
    margin: 16,
    marginBottom: 8,
  },
  summaryTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 8,
  },
  divider: {
    marginVertical: 12,
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
    fontWeight: 'bold',
    color: '#2196F3',
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
    marginTop: 4,
  },
  progressSection: {
    marginTop: 16,
  },
  progressRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  progressLabel: {
    fontSize: 13,
    color: '#666',
  },
  progressValue: {
    fontSize: 13,
    fontWeight: 'bold',
    color: '#333',
  },
  progressBar: {
    height: 8,
    borderRadius: 4,
    marginBottom: 12,
  },
  emptyCard: {
    margin: 16,
  },
  emptyContent: {
    alignItems: 'center',
    padding: 40,
  },
  emptyText: {
    fontSize: 16,
    color: '#999',
    marginTop: 8,
  },
  emptyHint: {
    fontSize: 14,
    color: '#bbb',
    marginTop: 8,
  },
  bottomPadding: {
    height: 80,
  },
});
