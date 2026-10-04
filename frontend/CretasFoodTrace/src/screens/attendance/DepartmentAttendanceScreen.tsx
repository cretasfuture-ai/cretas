import React, { useState, useCallback, useEffect, useRef } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl } from 'react-native';
import {
  Text,
  Appbar,
  Card,
  Chip,
  DataTable,
  ActivityIndicator,
  Divider,
  SegmentedButtons,
  Surface,
} from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import DateTimePicker from '@react-native-community/datetimepicker';
import { useAuthStore } from '../../store/authStore';
import { timeclockApiClient } from '../../services/api/timeclockApiClient';
import { getFactoryId, type User } from '../../types/auth';
import { hasModulePermission } from '../../utils/permissionHelper';

/**
 * 格式化日期为 ISO 字符串 (YYYY-MM-DD)
 */
const formatDate = (date: Date): string => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
};

type AttendanceRecord = {
  userId?: number | string;
  userName?: string;
  employeeName?: string;
  clockInTime?: string | null;
  clockOutTime?: string | null;
  workDuration?: number | string | null;
  workHours?: number | string | null;
  status?: string;
};

type AttendancePayload = { records: AttendanceRecord[] } & Record<string, unknown>;

type AttendanceActorContext = {
  actorKey: string;
  factoryId: string;
  user: User;
  generation: number;
  department: string;
  date: string;
  queryKey: string;
  requestId: number;
};

function attendanceActorKey(user: User | null | undefined): string {
  if (!user) return 'none';
  const factoryId = getFactoryId(user) || '';
  const userId = user.id != null ? String(user.id) : user.username || '';
  const role = user.userType === 'factory' ? user.factoryUser?.role || '' : user.userType;
  const permissions = user.userType === 'factory' ? JSON.stringify(user.factoryUser?.modulePermissions || {}) : '';
  return `${user.userType}:${userId}:${factoryId}:${role}:${permissions}`;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function parseAttendanceResponse(value: unknown): AttendancePayload {
  if (!isObject(value) || value.success !== true || !isObject(value.data) || !Array.isArray(value.data.records)) {
    throw new Error('部门考勤响应无效');
  }
  const records: AttendanceRecord[] = [];
  for (const rawRecord of value.data.records) {
    if (!isObject(rawRecord)) throw new Error('部门考勤记录无效');
    const userId = rawRecord.userId;
    const userName = rawRecord.userName;
    const employeeName = rawRecord.employeeName;
    if (userId !== undefined && typeof userId !== 'number' && typeof userId !== 'string') throw new Error('部门考勤记录无效');
    if (userName !== undefined && typeof userName !== 'string') throw new Error('部门考勤记录无效');
    if (employeeName !== undefined && typeof employeeName !== 'string') throw new Error('部门考勤记录无效');
    if (userId === undefined && userName === undefined && employeeName === undefined) throw new Error('部门考勤记录无效');
    records.push({
      userId: userId as number | string | undefined,
      userName: userName as string | undefined,
      employeeName: employeeName as string | undefined,
      clockInTime: typeof rawRecord.clockInTime === 'string' ? rawRecord.clockInTime : null,
      clockOutTime: typeof rawRecord.clockOutTime === 'string' ? rawRecord.clockOutTime : null,
      workDuration: typeof rawRecord.workDuration === 'number' || typeof rawRecord.workDuration === 'string' ? rawRecord.workDuration : null,
      workHours: typeof rawRecord.workHours === 'number' || typeof rawRecord.workHours === 'string' ? rawRecord.workHours : null,
      status: typeof rawRecord.status === 'string' ? rawRecord.status : undefined,
    });
  }
  return { ...value.data, records };
}

/**
 * 部门考勤查询页面
 * P2-考勤: 集成getDepartmentAttendance API
 *
 * 功能:
 * - 按部门和日期查询考勤
 * - 显示部门员工打卡列表
 * - 出勤率统计
 * - 迟到/早退/缺勤标记
 */
export default function DepartmentAttendanceScreen() {
  const capturedUser = useAuthStore((state) => state.user);
  const actorKey = attendanceActorKey(capturedUser);
  const previousUserRef = useRef<User | null>(capturedUser);
  const userGenerationRef = useRef(0);
  if (previousUserRef.current !== capturedUser) {
    previousUserRef.current = capturedUser;
    userGenerationRef.current += 1;
  }
  return <DepartmentAttendanceContent key={`${actorKey}:${userGenerationRef.current}`} capturedUser={capturedUser} actorKey={actorKey} />;
}

function DepartmentAttendanceContent({ capturedUser, actorKey: renderActorKey }: { capturedUser: User | null; actorKey: string }) {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');

  // UI状态
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  // 查询参数
  const [selectedDepartment, setSelectedDepartment] = useState('PROCESSING'); // 默认加工部
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [showDatePicker, setShowDatePicker] = useState(false);

  // 数据状态
  const [attendanceData, setAttendanceData] = useState<AttendancePayload | null>(null);
  const [attendanceRecords, setAttendanceRecords] = useState<AttendanceRecord[]>([]);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadedQueryKey, setLoadedQueryKey] = useState<string | null>(null);
  const mountedRef = useRef(false);
  const generationRef = useRef(0);
  const requestSequenceRef = useRef(0);
  const refreshSequenceRef = useRef(0);
  const currentQueryRef = useRef('');
  const previousQueryRef = useRef('');
  const currentQueryKey = `${selectedDepartment}:${formatDate(selectedDate)}`;
  if (previousQueryRef.current !== currentQueryKey) {
    previousQueryRef.current = currentQueryKey;
    requestSequenceRef.current += 1;
    refreshSequenceRef.current += 1;
  }
  currentQueryRef.current = currentQueryKey;

  /**
   * 部门列表
   */
  const departments = [
    { value: 'PROCESSING', label: t('attendance.department.departments.processing'), code: 'PROCESSING' },
    { value: 'QUALITY', label: t('attendance.department.departments.quality'), code: 'QUALITY' },
    { value: 'LOGISTICS', label: t('attendance.department.departments.logistics'), code: 'LOGISTICS' },
    { value: 'MANAGEMENT', label: t('attendance.department.departments.management'), code: 'MANAGEMENT' },
  ];

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
    };
  }, []);

  const liveActorMatches = useCallback(() => {
    const liveUser = useAuthStore.getState().user;
    return mountedRef.current
      && capturedUser !== null
      && liveUser === capturedUser
      && attendanceActorKey(liveUser) === renderActorKey
      && hasModulePermission(liveUser, 'hr', 'read');
  }, [capturedUser, renderActorKey]);

  const captureContext = useCallback((): AttendanceActorContext | null => {
    if (!liveActorMatches() || !capturedUser) return null;
    const factoryId = getFactoryId(capturedUser);
    if (!factoryId) return null;
    const queryKey = `${selectedDepartment}:${formatDate(selectedDate)}`;
    if (currentQueryRef.current !== queryKey) return null;
    return {
      actorKey: renderActorKey,
      factoryId,
      user: capturedUser,
      generation: generationRef.current,
      department: selectedDepartment,
      date: formatDate(selectedDate),
      queryKey,
      requestId: 0,
    };
  }, [capturedUser, liveActorMatches, renderActorKey, selectedDate, selectedDepartment]);

  const isCurrentOwnerContext = useCallback((context: AttendanceActorContext) => {
    const liveUser = useAuthStore.getState().user;
    return liveActorMatches()
      && context.generation === generationRef.current
      && context.user === liveUser
      && context.actorKey === attendanceActorKey(liveUser)
      && context.factoryId === getFactoryId(liveUser)
      && context.queryKey === currentQueryRef.current;
  }, [liveActorMatches]);

  const isCurrentContext = useCallback((context: AttendanceActorContext) => (
    isCurrentOwnerContext(context) && context.requestId === requestSequenceRef.current
  ), [isCurrentOwnerContext]);

  /** 加载部门考勤数据 */
  const loadDepartmentAttendance = useCallback(async () => {
    const context = captureContext();
    if (!context) return;
    const requestId = ++requestSequenceRef.current;
    const requestContext = { ...context, requestId };
    setLoading(true);
    setLoadFailed(false);
    try {
      const response = await timeclockApiClient.getDepartmentAttendance(
        requestContext.department,
        requestContext.date,
        requestContext.factoryId
      );
      if (!isCurrentContext(requestContext)) return;
      const payload = parseAttendanceResponse(response);
      if (!isCurrentContext(requestContext)) return;
      setAttendanceData(payload);
      setAttendanceRecords(payload.records);
      setLoadedQueryKey(requestContext.queryKey);
    } catch {
      if (!isCurrentContext(requestContext)) return;
      setAttendanceData(null);
      setAttendanceRecords([]);
      setLoadedQueryKey(requestContext.queryKey);
      setLoadFailed(true);
    } finally {
      if (isCurrentContext(requestContext)) setLoading(false);
    }
  }, [captureContext, isCurrentContext]);

  /**
   * 刷新数据
   */
  const handleRefresh = useCallback(async () => {
    const context = captureContext();
    if (!context) return;
    const refreshSequence = ++refreshSequenceRef.current;
    setRefreshing(true);
    try {
      await loadDepartmentAttendance();
    } finally {
      if (isCurrentOwnerContext(context) && refreshSequenceRef.current === refreshSequence) setRefreshing(false);
    }
  }, [captureContext, isCurrentOwnerContext, loadDepartmentAttendance]);

  /**
   * 页面聚焦时加载数据
   */
  useFocusEffect(
    useCallback(() => {
      if (liveActorMatches()) void loadDepartmentAttendance();
      return () => {
        generationRef.current += 1;
        requestSequenceRef.current += 1;
        refreshSequenceRef.current += 1;
      };
    }, [liveActorMatches, loadDepartmentAttendance])
  );

  /**
   * 获取状态标签
   */
  const getStatusChip = (status: string) => {
    const statusMap: Record<
      string,
      { label: string; color: string; bgColor: string }
    > = {
      present: { label: t('attendance.department.statusLabels.present'), color: '#4CAF50', bgColor: '#E8F5E9' },
      late: { label: t('attendance.department.statusLabels.late'), color: '#FF9800', bgColor: '#FFF3E0' },
      early_leave: { label: t('attendance.department.statusLabels.earlyLeave'), color: '#FF9800', bgColor: '#FFF3E0' },
      absent: { label: t('attendance.department.statusLabels.absent'), color: '#F44336', bgColor: '#FFEBEE' },
      on_leave: { label: t('attendance.department.statusLabels.onLeave'), color: '#2196F3', bgColor: '#E3F2FD' },
    };

    const config = statusMap[status] ?? statusMap['absent'];

    return (
      <Chip
        mode="flat"
        compact
        style={{ backgroundColor: config?.bgColor ?? '#FFF3E0' }}
        textStyle={{ color: config?.color ?? '#FF9800', fontSize: 12 }}
      >
        {config?.label ?? t('attendance.department.statusLabels.absent')}
      </Chip>
    );
  };

  /**
   * 计算统计数据
   */
  const queryIsLoaded = loadedQueryKey === currentQueryKey;
  const visibleAttendanceRecords = queryIsLoaded ? attendanceRecords : [];
  const visibleLoadFailed = queryIsLoaded && loadFailed;
  const visibleLoading = loading || !queryIsLoaded;

  const calculateStats = () => {
    const total = visibleAttendanceRecords.length;
    const present = visibleAttendanceRecords.filter(
      (r) => r.status === 'present' || r.clockInTime
    ).length;
    const late = visibleAttendanceRecords.filter((r) => r.status === 'late').length;
    const absent = visibleAttendanceRecords.filter(
      (r) => r.status === 'absent' || !r.clockInTime
    ).length;
    const attendanceRate = total > 0 ? (present / total) * 100 : 0;

    return {
      total,
      present,
      late,
      absent,
      attendanceRate,
    };
  };

  const stats = calculateStats();

  const liveUserForRender = useAuthStore.getState().user;
  const canReadHr = liveUserForRender === capturedUser
    && attendanceActorKey(liveUserForRender) === renderActorKey
    && hasModulePermission(liveUserForRender, 'hr', 'read');

  /**
   * 快捷日期选择
   */
  const setQuickDate = (days: number, ownerQueryKey: string) => {
    if (!mountedRef.current || currentQueryRef.current !== ownerQueryKey) return;
    const newDate = new Date();
    newDate.setDate(newDate.getDate() + days);
    setSelectedDate(newDate);
  };

  const setDepartment = (department: string, ownerQueryKey: string) => {
    if (mountedRef.current && currentQueryRef.current === ownerQueryKey) setSelectedDepartment(department);
  };

  const setDateFromPicker = (date: Date | undefined, ownerQueryKey: string) => {
    if (mountedRef.current && currentQueryRef.current === ownerQueryKey && date) setSelectedDate(date);
  };

  if (!canReadHr) {
    return <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('attendance.department.title')} />
      </Appbar.Header>
      <View style={styles.errorContainer}><Text style={styles.errorText}>暂无部门考勤权限</Text></View>
    </View>;
  }

  if (visibleLoadFailed) {
    return <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('attendance.department.title')} />
      </Appbar.Header>
      <View style={styles.errorContainer}>
        <Text style={styles.errorText}>{t('attendance.department.errors.loadFailed')}</Text>
        <Chip onPress={() => void handleRefresh()}>{t('common.retry')}</Chip>
      </View>
    </View>;
  }

  return (
    <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('attendance.department.title')} />
        <Appbar.Action icon="refresh" onPress={loadDepartmentAttendance} />
      </Appbar.Header>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {/* 日期选择 */}
        <Card style={styles.card} mode="elevated">
          <Card.Content>
            <Text variant="bodyMedium" style={styles.sectionLabel}>
              {t('attendance.department.queryDate')}
            </Text>
            <View style={styles.dateRow}>
              <Chip
                icon="calendar"
                onPress={() => setShowDatePicker(true)}
                style={styles.dateChip}
              >
                {selectedDate.toLocaleDateString('zh-CN')}
              </Chip>

              {/* 快捷日期 */}
              <View style={styles.quickDates}>
                <Chip
                  mode={
                    formatDate(selectedDate) === formatDate(new Date()) ? 'flat' : 'outlined'
                  }
                  compact
                  onPress={() => setDateFromPicker(new Date(), currentQueryKey)}
                  style={styles.quickDateChip}
                >
                  {t('attendance.department.today')}
                </Chip>
                <Chip
                  mode="outlined"
                  compact
                  onPress={() => setQuickDate(-1, currentQueryKey)}
                  style={styles.quickDateChip}
                >
                  {t('attendance.department.yesterday')}
                </Chip>
              </View>
            </View>
          </Card.Content>
        </Card>

        {/* 部门选择 */}
        <Card style={styles.card} mode="elevated">
          <Card.Content>
            <Text variant="bodyMedium" style={styles.sectionLabel}>
              {t('attendance.department.selectDepartment')}
            </Text>
            <View style={styles.departmentGrid}>
              {departments.map((dept) => (
                <Chip
                  key={dept.value}
                  mode={selectedDepartment === dept.value ? 'flat' : 'outlined'}
                  selected={selectedDepartment === dept.value}
                  onPress={() => setDepartment(dept.value, currentQueryKey)}
                  style={styles.departmentChip}
                >
                  {dept.label}
                </Chip>
              ))}
            </View>
          </Card.Content>
        </Card>

        {/* 统计概览 */}
        <Surface style={styles.statsCard} elevation={1}>
          <Text variant="titleMedium" style={styles.statsTitle}>
            {t('attendance.department.attendanceStats')}
          </Text>
          <Divider style={styles.divider} />

          {visibleLoading ? (
            <View style={styles.loadingContainer}>
              <ActivityIndicator size="small" />
              <Text style={styles.loadingText}>{t('attendance.department.loading')}</Text>
            </View>
          ) : (
            <View style={styles.statsGrid}>
              <View style={styles.statBox}>
                <Text style={styles.statValue}>{stats.total}</Text>
                <Text style={styles.statLabel}>{t('attendance.department.shouldAttend')}</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#4CAF50' }]}>{stats.present}</Text>
                <Text style={styles.statLabel}>{t('attendance.department.actualAttend')}</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#FF9800' }]}>{stats.late}</Text>
                <Text style={styles.statLabel}>{t('attendance.stats.late')}</Text>
              </View>
              <View style={styles.statBox}>
                <Text style={[styles.statValue, { color: '#F44336' }]}>{stats.absent}</Text>
                <Text style={styles.statLabel}>{t('attendance.stats.absent')}</Text>
              </View>
            </View>
          )}

          {!loading && stats.total > 0 && (
            <View style={styles.attendanceRateContainer}>
              <Text style={styles.attendanceRateLabel}>{t('attendance.department.attendanceRate')}</Text>
              <Text style={styles.attendanceRateValue}>
                {Number(stats.attendanceRate ?? 0).toFixed(1)}%
              </Text>
            </View>
          )}
        </Surface>

        {/* 员工打卡列表 */}
        <Card style={styles.card} mode="elevated">
          <Card.Title title={t('attendance.department.employeeRecords')} titleVariant="titleMedium" />
          <DataTable>
            <DataTable.Header>
              <DataTable.Title>{t('attendance.department.name')}</DataTable.Title>
              <DataTable.Title>{t('attendance.department.clockInTime')}</DataTable.Title>
              <DataTable.Title>{t('attendance.department.clockOutTime')}</DataTable.Title>
              <DataTable.Title numeric>{t('attendance.department.workHours')}</DataTable.Title>
              <DataTable.Title>{t('attendance.department.status')}</DataTable.Title>
            </DataTable.Header>

            {visibleLoading ? (
              <View style={styles.loadingContainer}>
                <ActivityIndicator size="large" />
              </View>
            ) : visibleAttendanceRecords.length === 0 ? (
              <View style={styles.emptyContainer}>
                <Text variant="bodyMedium" style={styles.emptyText}>
                  {t('attendance.department.noRecords')}
                </Text>
              </View>
            ) : (
              visibleAttendanceRecords.map((record, index) => (
                <DataTable.Row key={record.userId || index}>
                  <DataTable.Cell>
                    <Text variant="bodyMedium">{record.userName || record.employeeName || `员工${record.userId}`}</Text>
                  </DataTable.Cell>
                  <DataTable.Cell>
                    {record.clockInTime
                      ? new Date(record.clockInTime).toLocaleTimeString('zh-CN', {
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : '-'}
                  </DataTable.Cell>
                  <DataTable.Cell>
                    {record.clockOutTime
                      ? new Date(record.clockOutTime).toLocaleTimeString('zh-CN', {
                          hour: '2-digit',
                          minute: '2-digit',
                        })
                      : '-'}
                  </DataTable.Cell>
                  <DataTable.Cell numeric>
                    {record.workDuration
                      ? (Number(record.workDuration) / 60).toFixed(1)
                      : record.workHours
                      ? Number(record.workHours).toFixed(1)
                      : '-'}
                  </DataTable.Cell>
                  <DataTable.Cell>
                    {getStatusChip(record.status || (record.clockInTime ? 'present' : 'absent'))}
                  </DataTable.Cell>
                </DataTable.Row>
              ))
            )}
          </DataTable>
        </Card>

        <View style={styles.bottomPadding} />
      </ScrollView>

      {/* 日期选择器 */}
      {showDatePicker && (
        <DateTimePicker
          value={selectedDate}
          mode="date"
          display="default"
          onChange={(event, date) => {
            setShowDatePicker(false);
            setDateFromPicker(date, currentQueryKey)
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  errorContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16,
    padding: 24,
  },
  errorText: {
    color: '#B00020',
    textAlign: 'center',
  },
  content: {
    flex: 1,
  },
  card: {
    margin: 16,
    marginBottom: 0,
  },
  sectionLabel: {
    color: '#666',
    marginBottom: 12,
    fontWeight: '500',
  },
  dateRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  dateChip: {
    backgroundColor: '#E3F2FD',
  },
  quickDates: {
    flexDirection: 'row',
    gap: 8,
  },
  quickDateChip: {
    height: 32,
  },
  departmentGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  departmentChip: {
    minWidth: 80,
  },
  statsCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    margin: 16,
    marginBottom: 0,
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
  attendanceRateContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
  },
  attendanceRateLabel: {
    fontSize: 16,
    fontWeight: '600',
    color: '#212121',
  },
  attendanceRateValue: {
    fontSize: 24,
    fontWeight: '700',
    color: '#4CAF50',
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
});
