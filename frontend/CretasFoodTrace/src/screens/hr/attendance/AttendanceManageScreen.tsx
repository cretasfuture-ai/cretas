/** HR 考勤管理：所有读请求都绑定当前账号、工厂和有效的人事模块。 */

import React, { useCallback, useRef, useState } from 'react';
import { View, FlatList, StyleSheet, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Card, Searchbar, Chip, Avatar, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import type { NavigationProp } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { timeclockApiClient, type ClockRecord } from '../../../services/api/timeclockApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { useAuthStore } from '../../../store/authStore';
import { HR_THEME, type HRStackParamList } from '../../../types/hrNavigation';
import type { User } from '../../../types/auth';
import { hasModulePermission, canUseAIQuery } from '../../../utils/permissionHelper';
import { StickyFooterSummary } from '../../../components/list';
import { formatDate } from '../../../utils/formatters';

type ViewMode = 'today' | 'history';
type LoadState = 'loading' | 'ready' | 'empty' | 'error' | 'malformed' | 'denied';
type RecordStatus = 'normal' | 'late' | 'early_leave' | 'late_and_early_leave' | 'absent' | 'working' | 'ended' | 'unknown';
const PAGE_SIZE = 50;

interface AttendanceRecord {
  id: string;
  userId: number;
  userName: string;
  department?: string;
  clockInTime?: string;
  clockOutTime?: string;
  workMinutes?: number;
  status: RecordStatus;
  date: string;
}

interface AttendanceOwner {
  user: User;
  userId: number;
  factoryId: string;
  generation: number;
}

interface AttendanceSnapshot extends AttendanceOwner {
  ownerKey: string;
  mode: ViewMode;
  page: number;
  queryKey: string;
  filterKey: string;
  range: { startDate: string; endDate: string };
}

interface AttendanceFocusIdentity {
  user: User;
  userId: number;
  factoryId: string;
  generation: number;
  ownerKey: string;
  mode: ViewMode;
  range: { startDate: string; endDate: string };
}

function hasValidAttendanceOwner(user: User | null, factoryId: string | null): user is User {
  return Boolean(user && factoryId && Number.isInteger(user.id) && user.id > 0);
}

function canReadAttendanceHr(user: User | null): boolean {
  return hasModulePermission(user, 'hr', 'read');
}

function canShowAttendanceAI(
  snapshot: AttendanceSnapshot | null,
  selfHrOwnerKey: string | null,
  stateOwned: boolean,
  loadState: LoadState,
  user: User | null,
): boolean {
  return Boolean(snapshot && selfHrOwnerKey === snapshot.ownerKey && stateOwned
    && (loadState === 'ready' || loadState === 'empty') && canUseAIQuery(user));
}

function getFactoryId(user: User | null): string | null {
  if (!user) return null;
  if (user.userType === 'factory') return user.factoryUser.factoryId || user.factoryId || null;
  return user.factoryId || null;
}

function getCurrentMonthRange(): { startDate: string; endDate: string } {
  const now = new Date();
  return { startDate: formatDate(new Date(now.getFullYear(), now.getMonth(), 1)), endDate: formatDate(now) };
}

function mapStatus(record: ClockRecord): RecordStatus {
  const runtimeRecord = record as ClockRecord & { status?: string; attendanceStatus?: string };
  const attendanceStatus = runtimeRecord.attendanceStatus?.toUpperCase();
  if (attendanceStatus === 'LATE_AND_EARLY_LEAVE') return 'late_and_early_leave';
  if (attendanceStatus === 'LATE') return 'late';
  if (attendanceStatus === 'EARLY_LEAVE') return 'early_leave';
  if (attendanceStatus === 'ABSENT') return 'absent';
  if (attendanceStatus === 'NORMAL') {
    if (runtimeRecord.status?.toUpperCase() === 'OFF_WORK' || record.clockOutTime) return 'ended';
    return 'normal';
  }
  const status = runtimeRecord.status?.toUpperCase();
  if (status === 'WORKING' || status === 'ON_BREAK') return 'working';
  if (status === 'OFF_WORK') return 'ended';
  if (status === 'LATE') return 'late';
  if (status === 'EARLY_LEAVE') return 'early_leave';
  if (status === 'LATE_AND_EARLY_LEAVE') return 'late_and_early_leave';
  if (status === 'ABSENT') return 'absent';
  if (clockOutTimePresent(record)) return 'ended';
  if (record.clockInTime) return 'working';
  return 'unknown';
}

function clockOutTimePresent(record: ClockRecord): boolean {
  return Boolean(record.clockOutTime);
}

function isValidClockRecord(value: unknown): value is ClockRecord {
  if (!value || typeof value !== 'object') return false;
  const record = value as Partial<ClockRecord>;
  return Number.isInteger(record.userId) && (record.userId as number) > 0
    && (record.workDuration == null || (Number.isFinite(record.workDuration) && record.workDuration >= 0));
}

function toAttendanceRecord(record: ClockRecord): AttendanceRecord {
  return {
    id: String(record.id ?? record.userId),
    userId: record.userId,
    userName: `用户${record.userId}`,
    clockInTime: record.clockInTime,
    clockOutTime: record.clockOutTime,
    workMinutes: record.workDuration,
    status: mapStatus(record),
    date: formatDate(record.clockInTime) || '',
  };
}

function validInteger(value: unknown, minimum: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= minimum;
}

function isValidHistoryPage(data: unknown, requestedPage: number): data is {
  content: ClockRecord[];
  totalElements: number;
  totalPages?: number;
  page?: number;
  currentPage?: number;
  size?: number;
  pageSize?: number;
} {
  if (!data || typeof data !== 'object') return false;
  const page = data as {
    content?: unknown;
    totalElements?: unknown;
    totalPages?: unknown;
    page?: unknown;
    currentPage?: unknown;
    size?: unknown;
    pageSize?: unknown;
  };
  if (!Array.isArray(page.content) || !page.content.every(isValidClockRecord)
    || !validInteger(page.totalElements, 0) || page.content.length > PAGE_SIZE
    || page.content.length > page.totalElements) return false;
  if (page.totalPages !== undefined && !validInteger(page.totalPages, 0)) return false;
  if (page.page !== undefined && (!validInteger(page.page, 1) || page.page !== requestedPage)) return false;
  if (page.currentPage !== undefined && (!validInteger(page.currentPage, 1) || page.currentPage !== requestedPage)) return false;
  if (page.size !== undefined && !validInteger(page.size, 1)) return false;
  if (page.pageSize !== undefined && !validInteger(page.pageSize, 1)) return false;
  const expectedPages = Math.ceil(page.totalElements / PAGE_SIZE);
  if (page.totalPages !== undefined && page.totalPages !== expectedPages
    && !(page.totalElements === 0 && page.totalPages === 1)) return false;
  if (requestedPage > Math.max(1, expectedPages)) return false;
  return true;
}

export default function AttendanceManageScreen() {
  const navigation = useNavigation<NavigationProp<HRStackParamList>>();
  const user = useAuthStore((state) => state.user);
  const { t } = useTranslation('hr');
  const translationRef = useRef(t);
  translationRef.current = t;
  const [viewMode, setViewMode] = useState<ViewMode>('today');
  const [searchQuery, setSearchQuery] = useState('');
  const [page, setPage] = useState(1);
  const [records, setRecords] = useState<AttendanceRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [loadedQueryKey, setLoadedQueryKey] = useState<string | null>(null);
  const [loadingQueryKey, setLoadingQueryKey] = useState<string | null>(null);
  const [refreshingQueryKey, setRefreshingQueryKey] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [selfHrOwnerKey, setSelfHrOwnerKey] = useState<string | null>(null);
  const requestSeqRef = useRef(0);
  const actorRef = useRef<{ user: User | null; generation: number }>({ user: user || null, generation: 0 });
  if (actorRef.current.user !== user) actorRef.current = { user, generation: actorRef.current.generation + 1 };

  const factoryId = getFactoryId(user);
  const owner = hasValidAttendanceOwner(user, factoryId)
    ? { user, userId: user.id, factoryId: factoryId!, generation: actorRef.current.generation }
    : null;
  const hrReadKey = canReadAttendanceHr(user) ? 'hr-read' : 'hr-denied';
  const ownerKey = owner ? `${owner.userId}:${owner.factoryId}:${owner.generation}:${hrReadKey}` : `none:${hrReadKey}`;
  const [historyRange, setHistoryRange] = useState(getCurrentMonthRange);
  const filterKey = searchQuery.trim().toLowerCase();
  const snapshot: AttendanceSnapshot | null = owner ? {
    ...owner,
    ownerKey,
    mode: viewMode,
    page,
    queryKey: `${ownerKey}:${viewMode}:${page}:${historyRange.startDate}:${historyRange.endDate}`,
    filterKey,
    range: historyRange,
  } : null;
  const contextRef = useRef<AttendanceSnapshot | null>(null);
  contextRef.current = snapshot;

  const isSnapshotCurrent = useCallback((candidate: AttendanceSnapshot, includeFilter = false): boolean => {
    const current = contextRef.current;
    const liveUser = useAuthStore.getState().user;
    return Boolean(current && current.ownerKey === candidate.ownerKey && current.queryKey === candidate.queryKey
      && (!includeFilter || current.filterKey === candidate.filterKey)
      && liveUser === candidate.user && liveUser.id === candidate.userId
      && getFactoryId(liveUser) === candidate.factoryId
      && canReadAttendanceHr(liveUser));
  }, []);

  const hasSelfHrModule = useCallback((modules: Awaited<ReturnType<typeof hrShiftApiClient.getSelfModuleAccess>>) => (
    modules.some((module) => module.moduleCode === 'hr_employee'
      && module.permissionModule === 'hr' && module.effectiveAllowed)
  ), []);

  const finish = useCallback((target: AttendanceSnapshot, sequence: number, state: LoadState, message: string) => {
    if (sequence !== requestSeqRef.current || !isSnapshotCurrent(target)) return;
    setRecords([]);
    setTotal(0);
    setErrorMessage(message);
    setLoadState(state);
    setLoadedQueryKey(target.queryKey);
    setLoadingQueryKey(null);
    setRefreshingQueryKey(null);
    if (state === 'denied') setSelfHrOwnerKey(null);
  }, [isSnapshotCurrent]);

  const loadData = useCallback(async (
    target: AttendanceSnapshot,
    preflight: AttendanceSnapshot = target,
    options: { refresh?: boolean } = {},
  ) => {
    // This check must happen before sequence/state changes, including for retained callbacks.
    const liveUser = useAuthStore.getState().user;
    if (!isSnapshotCurrent(preflight) || !liveUser || !canReadAttendanceHr(liveUser)) return;
    const sequence = ++requestSeqRef.current;
    setLoadingQueryKey(target.queryKey);
    setLoadedQueryKey(null);
    setRecords([]);
    setTotal(0);
    setErrorMessage(null);
    setLoadState('loading');
    if (options.refresh) setRefreshingQueryKey(target.queryKey);

    try {
      const moduleAccess = await hrShiftApiClient.getSelfModuleAccess(target.userId, target.factoryId);
      if (sequence !== requestSeqRef.current || !isSnapshotCurrent(target)) return;
      const latestUser = useAuthStore.getState().user;
      if (!latestUser || !canReadAttendanceHr(latestUser) || !hasSelfHrModule(moduleAccess)) {
        finish(target, sequence, 'denied', translationRef.current('attendance.manage.noPermission'));
        return;
      }
      setSelfHrOwnerKey(target.ownerKey);

      if (target.mode === 'today') {
        const response = await timeclockApiClient.getTodayRecords({ factoryId: target.factoryId });
        if (sequence !== requestSeqRef.current || !isSnapshotCurrent(target)) return;
        if (!response || response.success !== true) {
          finish(target, sequence, 'error', response?.message || translationRef.current('attendance.manage.loadFailed'));
          return;
        }
        if (!response.data || !Array.isArray(response.data.records) || !response.data.records.every(isValidClockRecord)) {
          finish(target, sequence, 'malformed', translationRef.current('attendance.manage.malformed'));
          return;
        }
        const mapped = response.data.records.map(toAttendanceRecord);
        setRecords(mapped);
        setTotal(mapped.length);
        setErrorMessage(null);
        setLoadState(mapped.length === 0 ? 'empty' : 'ready');
        setLoadedQueryKey(target.queryKey);
        setLoadingQueryKey(null);
        setRefreshingQueryKey(null);
      } else {
        const response = await timeclockApiClient.getHistoryRecords({
          factoryId: target.factoryId,
          startDate: target.range.startDate,
          endDate: target.range.endDate,
          page: target.page,
          size: PAGE_SIZE,
        });
        if (sequence !== requestSeqRef.current || !isSnapshotCurrent(target)) return;
        if (!response || response.success !== true) {
          finish(target, sequence, 'error', response?.message || translationRef.current('attendance.manage.loadFailed'));
          return;
        }
        if (!isValidHistoryPage(response.data, target.page)) {
          finish(target, sequence, 'malformed', translationRef.current('attendance.manage.malformed'));
          return;
        }
        const mapped = response.data.content.map(toAttendanceRecord);
        setRecords(mapped);
        setTotal(response.data.totalElements);
        setErrorMessage(null);
        setLoadState(mapped.length === 0 ? 'empty' : 'ready');
        setLoadedQueryKey(target.queryKey);
        setLoadingQueryKey(null);
        setRefreshingQueryKey(null);
      }
    } catch (error) {
      if (sequence !== requestSeqRef.current || !isSnapshotCurrent(target)) return;
      finish(target, sequence, 'error', error instanceof Error ? error.message : translationRef.current('attendance.manage.loadFailed'));
    }
  }, [finish, hasSelfHrModule, isSnapshotCurrent]);

  const focusIdentity: AttendanceFocusIdentity | null = snapshot ? {
    user: snapshot.user,
    userId: snapshot.userId,
    factoryId: snapshot.factoryId,
    generation: snapshot.generation,
    ownerKey: snapshot.ownerKey,
    mode: snapshot.mode,
    range: snapshot.range,
  } : null;
  const isFocusCurrent = useCallback((captured: AttendanceFocusIdentity): boolean => {
    const current = contextRef.current;
    const liveUser = useAuthStore.getState().user;
    return Boolean(current && current.ownerKey === captured.ownerKey && current.mode === captured.mode
      && current.range.startDate === captured.range.startDate && current.range.endDate === captured.range.endDate
      && liveUser === captured.user && liveUser.id === captured.userId
      && getFactoryId(liveUser) === captured.factoryId
      && canReadAttendanceHr(liveUser));
  }, []);
  useFocusEffect(useCallback(() => {
    const captured = focusIdentity;
    // The callback must remain bound to the actor and mode that created it.
    // Reading contextRef is allowed only after that immutable identity passes the live check.
    if (!captured || !isFocusCurrent(captured)) return undefined;
    const current = contextRef.current;
    if (!current || current.ownerKey !== captured.ownerKey || current.mode !== captured.mode) return undefined;
    const latestRange = getCurrentMonthRange();
    if (latestRange.startDate !== captured.range.startDate || latestRange.endDate !== captured.range.endDate) {
      setHistoryRange(latestRange);
      return () => {
        if (isFocusCurrent(captured)) requestSeqRef.current += 1;
      };
    }
    const target: AttendanceSnapshot = {
      ...captured,
      page: 1,
      queryKey: `${captured.ownerKey}:${captured.mode}:1:${captured.range.startDate}:${captured.range.endDate}`,
      filterKey: current.filterKey,
    };
    if (current.page !== 1) setPage(1);
    void loadData(target, { ...current });
    return () => {
      if (isFocusCurrent(captured)) requestSeqRef.current += 1;
    };
  }, [focusIdentity?.ownerKey, focusIdentity?.mode, focusIdentity?.range.startDate, focusIdentity?.range.endDate, isFocusCurrent, loadData]));

  const stateOwned = Boolean(snapshot && loadedQueryKey === snapshot.queryKey);
  const visibleRecords = stateOwned ? records : [];
  const visibleTotal = stateOwned ? total : 0;
  const filteredData = visibleRecords.filter((item) => item.userName.toLowerCase().includes(filterKey)
    || (item.department || '').toLowerCase().includes(filterKey));
  const totalPages = Math.max(1, Math.ceil(visibleTotal / PAGE_SIZE));
  const aiEnabled = canShowAttendanceAI(snapshot, selfHrOwnerKey, stateOwned, loadState, user);

  const navigateToAI = useCallback((captured: AttendanceSnapshot, count: number, apiTotal: number) => {
    const liveUser = useAuthStore.getState().user;
    if (!isSnapshotCurrent(captured, true) || !canUseAIQuery(liveUser)
      || selfHrOwnerKey !== captured.ownerKey) return;
    navigation.navigate('AIChat', {
      entityType: 'ATTENDANCE',
      initialMessage: `分析考勤${captured.mode === 'today' ? '今日' : `${captured.range.startDate}至${captured.range.endDate}`}；当前筛选页面 ${count} 条，接口总数 ${captured.mode === 'history' ? apiTotal : count} 条。只基于这段明确范围和当前已加载子集，不代表全量历史。`,
    });
  }, [isSnapshotCurrent, navigation, selfHrOwnerKey]);

  const statusLabels: Record<RecordStatus, string> = {
    normal: t('attendance.manage.status.normal'), late: t('attendance.manage.status.late'),
    early_leave: t('attendance.manage.status.earlyLeave'),
    late_and_early_leave: t('attendance.manage.status.lateAndEarlyLeave'),
    absent: t('attendance.manage.status.absent'),
    working: t('attendance.manage.status.working'), ended: t('attendance.manage.status.ended'),
    unknown: t('attendance.manage.status.unknown'),
  };
  const formatTime = (time?: string) => {
    if (!time) return '-';
    const date = new Date(time);
    return Number.isNaN(date.getTime()) ? '-' : `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  };
  const formatMinutes = (minutes?: number) => minutes == null ? '-' : `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
  const renderItem = ({ item }: { item: AttendanceRecord }) => (
    <Card style={styles.card} testID={`attendance-record-${item.id}`}>
      <Card.Content style={styles.cardContent}>
        <Avatar.Text size={44} label={item.userName.substring(0, 1) || 'U'} style={{ backgroundColor: HR_THEME.primary }} />
        <View style={styles.info}>
          <View style={styles.nameRow}><Text style={styles.name}>{item.userName}</Text><Chip testID={`attendance-status-${item.id}`} mode="flat" textStyle={{ fontSize: 10, color: HR_THEME.primary }} style={styles.statusChip}>{statusLabels[item.status]}</Chip></View>
          <Text style={styles.department}>{item.date || t('attendance.manage.unknownDate')}</Text>
          <View style={styles.timeRow}><Text style={styles.timeText}>{formatTime(item.clockInTime)}</Text><Text style={styles.timeText}>{formatTime(item.clockOutTime)}</Text><Text style={styles.timeText}>{formatMinutes(item.workMinutes)}</Text></View>
        </View>
      </Card.Content>
    </Card>
  );

  if (!snapshot || !canReadAttendanceHr(user)) return <View style={styles.loadingContainer}><Text testID="attendance-permission-hint">{t('attendance.manage.noPermission')}</Text></View>;
  const rangeLabel = snapshot.mode === 'history'
    ? `${t('attendance.manage.currentMonth')} ${snapshot.range.startDate} ~ ${snapshot.range.endDate}`
    : `${t('attendance.manage.todayRange')} ${formatDate(new Date())}`;
  const uniqueUsers = new Set(filteredData.map((item) => item.userId)).size;
  const footerStats = [
    { label: t('attendance.manage.summary.records'), value: filteredData.length, format: 'number' as const },
    { label: t('attendance.manage.summary.users'), value: uniqueUsers, format: 'number' as const },
    ...(snapshot.mode === 'history' ? [{ label: t('attendance.manage.summary.apiTotal'), value: visibleTotal, format: 'number' as const }] : []),
  ];
  const statusText = stateOwned && loadState === 'error' ? errorMessage || t('attendance.manage.loadFailed')
    : stateOwned && loadState === 'malformed' ? t('attendance.manage.malformed')
      : stateOwned && loadState === 'denied' ? errorMessage || t('attendance.manage.noPermission')
        : stateOwned && loadState === 'empty' ? t('attendance.manage.empty') : null;
  const showLoading = loadingQueryKey === snapshot.queryKey || !stateOwned;
  const refreshForSnapshot = () => {
    if (!isSnapshotCurrent(snapshot)) return;
    void loadData(snapshot, snapshot, { refresh: true });
  };
  const pageForSnapshot = (nextPage: number) => {
    if (nextPage < 1 || nextPage > totalPages || nextPage === snapshot.page || !isSnapshotCurrent(snapshot)) return;
    const target: AttendanceSnapshot = {
      ...snapshot,
      page: nextPage,
      queryKey: `${snapshot.ownerKey}:${snapshot.mode}:${nextPage}:${snapshot.range.startDate}:${snapshot.range.endDate}`,
    };
    setPage(nextPage);
    void loadData(target, snapshot);
  };
  const aiForSnapshot = () => navigateToAI(snapshot, filteredData.length, visibleTotal);

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}><Text style={styles.headerTitle}>{t('attendance.manage.title')}</Text>{aiEnabled && <TouchableOpacity testID="attendance-ai-header" accessibilityLabel={t('attendance.manage.ai')} onPress={aiForSnapshot}><MaterialCommunityIcons name="robot-outline" size={24} color="#fff" /></TouchableOpacity>}</View>
      <View style={styles.filterContainer}>
        <SegmentedButtons value={snapshot.mode} onValueChange={(value) => { if (!isSnapshotCurrent(snapshot)) return; setPage(1); setViewMode(value as ViewMode); }} buttons={[{ value: 'today', label: t('attendance.department.today') }, { value: 'history', label: t('attendance.history.title') }]} style={styles.segmented} />
        <Text testID="attendance-date-range" style={styles.rangeText}>{rangeLabel}</Text>
      </View>
      <View style={styles.searchContainer}><Searchbar placeholder={t('staff.search.placeholder')} value={searchQuery} onChangeText={setSearchQuery} style={styles.searchbar} /></View>
      <View style={{ flex: 1 }}>
        {showLoading ? <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>
          : statusText && (loadState === 'error' || loadState === 'malformed' || loadState === 'denied') ? <View style={styles.emptyContainer} testID={`attendance-${loadState}`}><MaterialCommunityIcons name="alert-circle-outline" size={48} color={HR_THEME.danger} /><Text style={styles.emptyText}>{statusText}</Text></View>
            : <FlatList data={filteredData} keyExtractor={(item) => item.id} renderItem={renderItem} contentContainerStyle={styles.listContent} refreshControl={<RefreshControl refreshing={refreshingQueryKey === snapshot.queryKey} onRefresh={refreshForSnapshot} />} ListEmptyComponent={<View style={styles.emptyContainer} testID="attendance-empty"><MaterialCommunityIcons name="calendar-check-outline" size={64} color={HR_THEME.textMuted} /><Text style={styles.emptyText}>{statusText || t('attendance.manage.empty')}</Text></View>} />}
      </View>
      <StickyFooterSummary stats={footerStats} pagination={snapshot.mode === 'history' ? { currentPage: snapshot.page, totalPages, pageSize: PAGE_SIZE, totalItems: visibleTotal, onPageChange: pageForSnapshot } : undefined} onAIAnalyze={aiEnabled ? aiForSnapshot : undefined} loading={showLoading} empty={loadState === 'empty' && stateOwned} emptyText={t('attendance.manage.empty')} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 16, backgroundColor: HR_THEME.primary },
  headerTitle: { fontSize: 22, fontWeight: 'bold', color: '#fff' },
  filterContainer: { padding: 16, backgroundColor: HR_THEME.cardBackground },
  segmented: { backgroundColor: HR_THEME.background },
  rangeText: { marginTop: 8, fontSize: 12, color: HR_THEME.textSecondary },
  searchContainer: { paddingHorizontal: 16, paddingBottom: 16, backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border },
  searchbar: { backgroundColor: HR_THEME.background, elevation: 0 },
  listContent: { padding: 16 },
  card: { borderRadius: 12, marginBottom: 12, backgroundColor: HR_THEME.cardBackground },
  cardContent: { flexDirection: 'row', alignItems: 'center' },
  info: { flex: 1, marginLeft: 12 },
  nameRow: { flexDirection: 'row', alignItems: 'center' },
  name: { fontSize: 15, fontWeight: '500', color: HR_THEME.textPrimary, marginRight: 8 },
  statusChip: { height: 22 },
  department: { fontSize: 13, color: HR_THEME.textSecondary, marginTop: 2 },
  timeRow: { flexDirection: 'row', marginTop: 8 },
  timeText: { fontSize: 12, color: HR_THEME.textSecondary, marginRight: 16 },
  emptyContainer: { alignItems: 'center', paddingVertical: 60, paddingHorizontal: 16 },
  emptyText: { marginTop: 16, fontSize: 14, color: HR_THEME.textMuted, textAlign: 'center' },
});
