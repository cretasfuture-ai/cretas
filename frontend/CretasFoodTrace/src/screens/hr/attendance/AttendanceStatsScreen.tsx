/**
 * 考勤统计
 *
 * @version 1.0.0
 * @since 2025-12-29
 */

import React, { useState, useCallback } from 'react';
import {
  View, ScrollView, StyleSheet, RefreshControl, TouchableOpacity, Dimensions,
} from 'react-native';
import { Text, Card, SegmentedButtons, ActivityIndicator } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';
import { HR_THEME } from '../../../types/hrNavigation';

type PeriodType = 'week' | 'month' | 'quarter';

interface AttendanceStats {
  totalEmployees: number;
  avgAttendanceRate: number;
  /** 后端没发就是 null。⛔ 不要兜底成 0 或 人数×8 —— 那会把「没有考勤数据」写成一个读数。 */
  totalWorkHours: number | null;
  avgDailyHours: number | null;
  lateCount: number;
  earlyLeaveCount: number;
  absentCount: number;
  dailyStats?: Array<{
    date: string;
    attendanceRate: number;
    workHours: number;
  }>;
  departmentStats?: Array<{
    departmentName: string;
    attendanceRate: number;
    avgHours: number;
    employeeCount: number;
  }>;
}

export default function AttendanceStatsScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [period, setPeriod] = useState<PeriodType>('week');
  const [stats, setStats] = useState<AttendanceStats | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      const data = await timeStatsApiClient.getAttendanceStats({ period });
      // API returns data directly
      if (data) {
        setLoadError(null);
        setStats({
          totalEmployees: data.totalEmployees,
          avgAttendanceRate: data.avgAttendanceRate,
          // ⛔ 这两个字段后端可能不发。以前写的是 `data.totalEmployees * 8` 和 `8`,
          // 于是一条考勤记录都没有的租户读到「总工时 176h / 日均工时 8.0h」,
          // 和同屏的「平均出勤率 0.0%」直接打架。取不到就是 null, 屏上显示「—」。
          totalWorkHours: 'totalWorkHours' in data ? (data as { totalWorkHours: number }).totalWorkHours : null,
          avgDailyHours: 'avgDailyHours' in data ? (data as { avgDailyHours: number }).avgDailyHours : null,
          lateCount: data.totalLateCount,
          earlyLeaveCount: data.totalEarlyLeaveCount,
          absentCount: data.totalAbsentCount,
          departmentStats: ('departmentStats' in data ? (data as { departmentStats: AttendanceStats['departmentStats'] }).departmentStats : []),
        });
      }
    } catch (error) {
      // ⛔ 这里以前塞的是一整套假数据(45 人 / 96.5% 出勤 / 1850 工时 / 四个部门),
      // 而这家租户只有 22 人、0 个部门 —— 接口挂了的时候 HR 看到的是一家不存在的公司,
      // 屏上没有任何一处说请求失败了。违反 CLAUDE.md 核心原则 1「禁止降级处理」。
      console.error('加载考勤统计失败', error);
      setStats(null);
      setLoadError(error instanceof Error && error.message ? error.message : t('messages.failed'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [period]);

  useFocusEffect(
    useCallback(() => {
      setLoading(true);
      loadData();
    }, [loadData])
  );

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  if (loading && !stats) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color={HR_THEME.primary} />
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('attendance.stats.title')}</Text>
        <View style={{ width: 32 }} />
      </View>

      <View style={styles.periodSelector}>
        <SegmentedButtons
          value={period}
          onValueChange={(value) => setPeriod(value as PeriodType)}
          buttons={[
            { value: 'week', label: t('attendance.stats.period.week') },
            { value: 'month', label: t('attendance.stats.period.month') },
            { value: 'quarter', label: t('attendance.stats.period.quarter') },
          ]}
          style={styles.segmented}
        />
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {loadError && (
          <Card style={styles.errorCard}>
            <Card.Content>
              <Text style={styles.errorTitle}>{t('attendance.stats.loadFailedTitle')}</Text>
              <Text style={styles.errorBody}>{t('attendance.stats.loadFailedBody')}</Text>
              <Text style={styles.errorDetail}>{loadError}</Text>
            </Card.Content>
          </Card>
        )}

        {/* 核心指标
            ⚠️ 失败时必须整块藏掉。只加一个红框、下面照旧渲染
            「0% 平均出勤率 / 0 在岗人数 / 0 迟到 / 0 早退 / 0 缺勤」，
            等于在「拿不到数据」的横幅底下继续给一排读数 —— 那还是编。
            2026-08-28 实测: 上一版就是这么半修的, 是把接口打成 404 才看出来的。*/}
        {!loadError && (
        <View style={styles.statsGrid}>
          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <View style={[styles.statIcon, { backgroundColor: HR_THEME.primary + '15' }]}>
                <MaterialCommunityIcons name="percent" size={24} color={HR_THEME.primary} />
              </View>
              <Text style={styles.statValue}>{stats?.avgAttendanceRate?.toFixed(1) ?? 0}%</Text>
              <Text style={styles.statLabel}>{t('attendance.stats.avgAttendanceRate')}</Text>
            </Card.Content>
          </Card>

          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <View style={[styles.statIcon, { backgroundColor: HR_THEME.info + '15' }]}>
                <MaterialCommunityIcons name="clock-outline" size={24} color={HR_THEME.info} />
              </View>
              <Text style={styles.statValue}>{stats?.avgDailyHours != null ? `${stats.avgDailyHours.toFixed(1)}h` : '—'}</Text>
              <Text style={styles.statLabel}>{t('attendance.stats.avgHoursPerDay')}</Text>
            </Card.Content>
          </Card>

          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <View style={[styles.statIcon, { backgroundColor: HR_THEME.success + '15' }]}>
                <MaterialCommunityIcons name="account-group" size={24} color={HR_THEME.success} />
              </View>
              <Text style={styles.statValue}>{stats?.totalEmployees ?? 0}</Text>
              <Text style={styles.statLabel}>{t('attendance.stats.onSiteCount')}</Text>
            </Card.Content>
          </Card>

          <Card style={styles.statCard}>
            <Card.Content style={styles.statContent}>
              <View style={[styles.statIcon, { backgroundColor: HR_THEME.warning + '15' }]}>
                <MaterialCommunityIcons name="timer-sand" size={24} color={HR_THEME.warning} />
              </View>
              <Text style={styles.statValue}>{stats?.totalWorkHours != null ? `${stats.totalWorkHours}h` : '—'}</Text>
              <Text style={styles.statLabel}>{t('attendance.stats.totalHours')}</Text>
            </Card.Content>
          </Card>
        </View>
        )}

        {/* 异常统计 */}
        {!loadError && (
        <Card style={styles.sectionCard}>
          <Card.Content>
            <Text style={styles.sectionTitle}>{t('attendance.stats.anomalyStats')}</Text>
            <View style={styles.anomalyGrid}>
              <View style={styles.anomalyItem}>
                <View style={[styles.anomalyIcon, { backgroundColor: '#fff7e6' }]}>
                  <MaterialCommunityIcons name="clock-alert" size={20} color={HR_THEME.warning} />
                </View>
                <Text style={styles.anomalyValue}>{stats?.lateCount ?? 0}</Text>
                <Text style={styles.anomalyLabel}>{t('attendance.stats.late')}</Text>
              </View>
              <View style={styles.anomalyItem}>
                <View style={[styles.anomalyIcon, { backgroundColor: '#e6f7ff' }]}>
                  <MaterialCommunityIcons name="exit-run" size={20} color={HR_THEME.info} />
                </View>
                <Text style={styles.anomalyValue}>{stats?.earlyLeaveCount ?? 0}</Text>
                <Text style={styles.anomalyLabel}>{t('attendance.stats.earlyLeave')}</Text>
              </View>
              <View style={styles.anomalyItem}>
                <View style={[styles.anomalyIcon, { backgroundColor: '#fff2f0' }]}>
                  <MaterialCommunityIcons name="account-off" size={20} color={HR_THEME.danger} />
                </View>
                <Text style={styles.anomalyValue}>{stats?.absentCount ?? 0}</Text>
                <Text style={styles.anomalyLabel}>{t('attendance.stats.absent')}</Text>
              </View>
            </View>
          </Card.Content>
        </Card>
        )}

        {/* 部门排行 */}
        {!loadError && (
        <Card style={styles.sectionCard}>
          <Card.Content>
            <Text style={styles.sectionTitle}>{t('attendance.stats.departmentRanking')}</Text>
            {stats?.departmentStats?.map((dept, index) => (
              <View key={index} style={styles.deptRow}>
                <View style={styles.deptRank}>
                  <Text style={[styles.rankText, index < 3 && { color: HR_THEME.primary }]}>
                    {index + 1}
                  </Text>
                </View>
                <View style={styles.deptInfo}>
                  <Text style={styles.deptName}>{dept.departmentName}</Text>
                  <Text style={styles.deptMeta}>{dept.employeeCount}人 · 日均{dept.avgHours.toFixed(1)}h</Text>
                </View>
                <View style={styles.deptRate}>
                  <Text style={[styles.rateValue, {
                    color: dept.attendanceRate >= 95 ? HR_THEME.success : HR_THEME.warning
                  }]}>
                    {dept.attendanceRate}%
                  </Text>
                </View>
              </View>
            ))}
          </Card.Content>
        </Card>
        )}

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 12,
    backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border,
  },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary },
  periodSelector: { padding: 16, backgroundColor: HR_THEME.cardBackground },
  segmented: { backgroundColor: HR_THEME.background },
  content: { flex: 1, padding: 16 },
  errorCard: {
    backgroundColor: '#FEF2F2',
    borderColor: '#FCA5A5',
    borderWidth: 1,
    marginBottom: 12,
  },
  errorTitle: { fontSize: 15, fontWeight: '600', color: '#B91C1C', marginBottom: 4 },
  errorBody: { fontSize: 13, lineHeight: 20, color: '#7F1D1D' },
  errorDetail: { fontSize: 12, color: '#9CA3AF', marginTop: 6 },
  statsGrid: { flexDirection: 'row', flexWrap: 'wrap', marginHorizontal: -6, marginBottom: 16 },
  statCard: { width: (Dimensions.get('window').width - 44) / 2, margin: 6, borderRadius: 12, backgroundColor: HR_THEME.cardBackground },
  statContent: { alignItems: 'center', paddingVertical: 16 },
  statIcon: { width: 48, height: 48, borderRadius: 24, justifyContent: 'center', alignItems: 'center', marginBottom: 8 },
  statValue: { fontSize: 24, fontWeight: 'bold', color: HR_THEME.textPrimary },
  statLabel: { fontSize: 12, color: HR_THEME.textSecondary, marginTop: 4 },
  sectionCard: { borderRadius: 12, marginBottom: 16, backgroundColor: HR_THEME.cardBackground },
  sectionTitle: { fontSize: 16, fontWeight: '600', color: HR_THEME.textPrimary, marginBottom: 16 },
  anomalyGrid: { flexDirection: 'row', justifyContent: 'space-around' },
  anomalyItem: { alignItems: 'center' },
  anomalyIcon: { width: 44, height: 44, borderRadius: 22, justifyContent: 'center', alignItems: 'center' },
  anomalyValue: { fontSize: 20, fontWeight: 'bold', color: HR_THEME.textPrimary, marginTop: 8 },
  anomalyLabel: { fontSize: 12, color: HR_THEME.textSecondary, marginTop: 2 },
  deptRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: HR_THEME.border },
  deptRank: { width: 28, height: 28, borderRadius: 14, backgroundColor: HR_THEME.background, justifyContent: 'center', alignItems: 'center' },
  rankText: { fontSize: 14, fontWeight: '600', color: HR_THEME.textSecondary },
  deptInfo: { flex: 1, marginLeft: 12 },
  deptName: { fontSize: 14, fontWeight: '500', color: HR_THEME.textPrimary },
  deptMeta: { fontSize: 12, color: HR_THEME.textSecondary, marginTop: 2 },
  deptRate: { alignItems: 'flex-end' },
  rateValue: { fontSize: 16, fontWeight: 'bold' },
  bottomSpacer: { height: 40 },
});
