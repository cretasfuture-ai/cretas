import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { View, FlatList, StyleSheet, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Card, Chip, Avatar, ActivityIndicator, Searchbar } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useFocusEffect, CommonActions } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';
import { canvasApiClient } from '../../../services/api/canvasApiClient';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { useAuthStore } from '../../../store/authStore';
import { HR_THEME, type BatchAssignmentItem } from '../../../types/hrNavigation';
import type { User } from '../../../types/auth';
import type { UserModuleAccessView } from '../../../services/api/canvasApiClient';

type Translate = (key: string, options?: Record<string, unknown>) => string;
type ModuleAccessWithFactory = UserModuleAccessView & { factoryEnabled?: boolean; enabled?: boolean };
type AccessFailure = 'moduleDenied' | 'factoryDisabled' | 'preflightFailed';
export type ActorSnapshot = { key: string; userId: string; factoryId: string };

export class ModuleAccessError extends Error {
  constructor(readonly reason: AccessFailure) { super(reason); }
}

export function actorSnapshot(user: User | null): ActorSnapshot | null {
  if (!user || user.userType !== 'factory' || !user.factoryUser.factoryId) return null;
  const permissions = [...user.factoryUser.permissions].sort().join(',');
  const modulePermissions = JSON.stringify(user.factoryUser.modulePermissions || {});
  return { key: `${user.id}:${user.factoryUser.factoryId}:${user.factoryUser.role}:${permissions}:${modulePermissions}`, userId: String(user.id), factoryId: user.factoryUser.factoryId };
}

export async function requireProductionPlanRead(snapshot: ActorSnapshot): Promise<void> {
  let access: UserModuleAccessView[];
  try {
    access = await canvasApiClient.getUserModuleAccess(snapshot.factoryId, snapshot.userId);
  } catch {
    throw new ModuleAccessError('preflightFailed');
  }
  const module = access.find((item) => item.moduleCode === 'production_plan' || item.permissionModule === 'production_plan') as ModuleAccessWithFactory | undefined;
  if (!module) throw new ModuleAccessError('moduleDenied');
  if (module.factoryEnabled === false || module.enabled === false) throw new ModuleAccessError('factoryDisabled');
  if (module.effectiveAllowed !== true) throw new ModuleAccessError('moduleDenied');
}

function normalizeBatchStatus(status: string): BatchAssignmentItem['status'] {
  switch (status.trim().toUpperCase()) {
    case 'IN_PROGRESS': case 'PROCESSING': return 'in_progress';
    case 'COMPLETED': return 'completed';
    case 'PLANNED': case 'PLANNING': case 'PENDING': return 'pending';
    default: return 'unknown';
  }
}

function getStatusConfig(t: Translate) {
  return {
    in_progress: { label: t('production.batchAssignment.status.inProgress'), color: HR_THEME.info, bgColor: '#e6f7ff' },
    pending: { label: t('production.batchAssignment.status.pending'), color: HR_THEME.warning, bgColor: '#fff7e6' },
    completed: { label: t('production.batchAssignment.status.completed'), color: HR_THEME.success, bgColor: '#f6ffed' },
    unknown: { label: t('production.batchAssignment.status.unknown'), color: HR_THEME.textMuted, bgColor: '#f5f5f5' },
  };
}

export default function BatchAssignmentScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const authUser = useAuthStore((state) => state.user);
  const snapshot = useMemo(() => actorSnapshot(authUser), [authUser]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [batches, setBatches] = useState<BatchAssignmentItem[]>([]);
  const [batchesOwnerKey, setBatchesOwnerKey] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const [accessFailure, setAccessFailure] = useState<AccessFailure | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const actorRevision = useRef(0);
  const requestRevision = useRef(0);

  useEffect(() => {
    actorRevision.current += 1;
    requestRevision.current += 1;
    if (snapshot && actorSnapshot(useAuthStore.getState().user)?.key === snapshot.key) {
      setBatches([]); setBatchesOwnerKey(null); setPage(1); setTotalPages(1);
      setAccessFailure(null); setLoadFailed(false); setLoading(true); setLoadingMore(false);
    }
  }, [snapshot?.key]);

  const isLiveActor = useCallback((current: ActorSnapshot) => actorSnapshot(useAuthStore.getState().user)?.key === current.key, []);
  const isCurrent = useCallback((current: ActorSnapshot, request: number) => request === requestRevision.current && isLiveActor(current), [isLiveActor]);
  const loadData = useCallback(async (pageToLoad = 1, append = false) => {
    const current = snapshot;
    if (!current || !isLiveActor(current)) return;
    const request = ++requestRevision.current;
    if (append) setLoadingMore(true);
    else { setLoading(true); setRefreshing(false); setBatches([]); setBatchesOwnerKey(null); setAccessFailure(null); setLoadFailed(false); setPage(1); setTotalPages(1); }
    try {
      await requireProductionPlanRead(current);
      if (!isCurrent(current, request)) return;
      const response = await schedulingApiClient.getBatchAssignments({ factoryId: current.factoryId, page: pageToLoad, size: 20 });
      if (!isCurrent(current, request)) return;
      const nextBatches = response.content.map((batch) => ({ ...batch, status: normalizeBatchStatus(batch.status) }));
      setBatches((previous) => append ? [...previous, ...nextBatches] : nextBatches);
      setBatchesOwnerKey(current.key);
      setPage(response.page);
      setTotalPages(response.totalPages);
    } catch (error) {
      if (!isCurrent(current, request)) return;
      setBatchesOwnerKey(current.key);
      if (error instanceof ModuleAccessError) setAccessFailure(error.reason); else setLoadFailed(true);
    } finally {
      if (isCurrent(current, request)) { setLoading(false); setRefreshing(false); setLoadingMore(false); }
    }
  }, [isCurrent, isLiveActor, snapshot]);

  useFocusEffect(useCallback(() => {
    void loadData();
    return () => {
      requestRevision.current += 1;
      const current = snapshot;
      if (current && isLiveActor(current)) { setBatches([]); setBatchesOwnerKey(null); setLoadingMore(false); }
    };
  }, [isLiveActor, loadData, snapshot]));
  const onRefresh = useCallback(() => {
    const current = snapshot;
    if (current && isLiveActor(current)) { setRefreshing(true); void loadData(1, false); }
  }, [isLiveActor, loadData, snapshot]);
  const onEndReached = useCallback(() => {
    const current = snapshot;
    if (!current || !isLiveActor(current) || loading || loadingMore || page >= totalPages) return;
    void loadData(page + 1, true);
  }, [isLiveActor, loadData, loading, loadingMore, page, snapshot, totalPages]);
  const visibleBatches = batchesOwnerKey === snapshot?.key ? batches : [];
  const filteredData = visibleBatches.filter((item) => item.batchNumber.toLowerCase().includes(searchQuery.toLowerCase()) || item.productName.toLowerCase().includes(searchQuery.toLowerCase()));
  const statusConfig = getStatusConfig(t);
  const canOpenBatch = useCallback(() => {
    const live = actorSnapshot(useAuthStore.getState().user);
    return Boolean(live && snapshot && live.key === snapshot.key && batchesOwnerKey === snapshot.key);
  }, [batchesOwnerKey, snapshot]);
  const nullable = (value: number | null, suffix = '') => value === null ? t('common.noData') : `${value}${suffix}`;

  if (!snapshot || batchesOwnerKey !== snapshot.key) return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;
  if (loading && !accessFailure && !loadFailed) return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;
  if (accessFailure || loadFailed) {
    const title = accessFailure === 'moduleDenied' ? t('production.batchAssignment.accessDenied') : accessFailure === 'factoryDisabled' ? t('production.batchAssignment.factoryDisabled') : t('production.batchAssignment.loadFailed');
    const hint = accessFailure === 'moduleDenied' ? t('production.batchAssignment.accessDeniedHint') : accessFailure === 'factoryDisabled' ? t('production.batchAssignment.factoryDisabledHint') : t('production.batchAssignment.loadFailedHint');
    return <SafeAreaView style={styles.container} edges={['top']}><View style={styles.header}><TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}><MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} /></TouchableOpacity><Text style={styles.headerTitle}>{t('production.batchAssignment.title')}</Text><View style={styles.placeholder} /></View><View style={styles.errorContainer}><MaterialCommunityIcons name="shield-alert-outline" size={56} color={HR_THEME.danger} /><Text style={styles.errorText}>{title}</Text><Text style={styles.errorHint}>{hint}</Text></View></SafeAreaView>;
  }

  return <SafeAreaView style={styles.container} edges={['top']}>
    <View style={styles.header}><TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}><MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} /></TouchableOpacity><Text style={styles.headerTitle}>{t('production.batchAssignment.title')}</Text><View style={styles.placeholder} /></View>
    <View style={styles.searchContainer}><Searchbar placeholder={t('production.batchAssignment.search')} value={searchQuery} onChangeText={setSearchQuery} style={styles.searchbar} /></View>
    <FlatList data={filteredData} keyExtractor={(item) => item.batchId} contentContainerStyle={styles.listContent} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />} onEndReached={onEndReached} onEndReachedThreshold={0.4} ListFooterComponent={loadingMore ? <ActivityIndicator size="small" color={HR_THEME.primary} /> : null} renderItem={({ item }) => {
      const config = statusConfig[item.status];
      return <TouchableOpacity onPress={() => { if (!canOpenBatch()) return; navigation.dispatch(CommonActions.navigate('BatchWorkers', { batchId: item.batchId, batchName: `${item.batchNumber} - ${item.productName}` })); }}><Card style={styles.card}><Card.Content><View style={styles.cardHeader}><View style={styles.batchInfo}><Text style={styles.batchNumber}>{item.batchNumber}</Text><Text style={styles.productName}>{item.productName}</Text></View><Chip mode="flat" textStyle={{ fontSize: 11, color: config.color }} style={[styles.statusChip, { backgroundColor: config.bgColor }]}>{config.label}</Chip></View><View style={styles.assignmentRow}><View style={styles.assignmentInfo}><MaterialCommunityIcons name="account-group" size={18} color={HR_THEME.textSecondary} /><Text style={styles.assignmentText}>{t('production.batchAssignment.assigned')} {nullable(item.assignedCount)}/{nullable(item.requiredCount)} {t('production.batchAssignment.required')}</Text></View><View style={styles.avatarGroup}>{item.assignedEmployees.slice(0, 4).map((employee, index) => <View key={employee.id} style={[styles.avatarWrapper, { marginLeft: index > 0 ? -8 : 0 }]}><Avatar.Text size={28} label={employee.name.substring(0, 1) || 'U'} style={{ backgroundColor: HR_THEME.primary }} /></View>)}</View></View><View style={styles.statsRow}><View style={styles.statItem}><MaterialCommunityIcons name="clock-outline" size={16} color={HR_THEME.info} /><Text style={styles.statText}>{nullable(item.totalWorkHours, 'h')} {t('production.batchAssignment.workHours')}</Text></View><View style={styles.statItem}><MaterialCommunityIcons name="currency-cny" size={16} color={HR_THEME.success} /><Text style={styles.statText}>{item.laborCost === null ? t('common.noData') : `¥${item.laborCost.toLocaleString()}`}</Text></View></View></Card.Content></Card></TouchableOpacity>;
    }} ListEmptyComponent={<View style={styles.emptyContainer}><MaterialCommunityIcons name="clipboard-list-outline" size={64} color={HR_THEME.textMuted} /><Text style={styles.emptyText}>{t('production.batchAssignment.empty')}</Text></View>} />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background }, loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 12, backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border }, backBtn: { padding: 4 }, headerTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary }, placeholder: { width: 32 }, searchContainer: { padding: 16, backgroundColor: HR_THEME.cardBackground }, searchbar: { backgroundColor: HR_THEME.background, elevation: 0 }, listContent: { padding: 16 }, card: { borderRadius: 12, marginBottom: 12, backgroundColor: HR_THEME.cardBackground }, cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }, batchInfo: { flex: 1 }, batchNumber: { fontSize: 16, fontWeight: '600', color: HR_THEME.textPrimary }, productName: { fontSize: 13, color: HR_THEME.textSecondary, marginTop: 2 }, statusChip: { height: 24 }, assignmentRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }, assignmentInfo: { flexDirection: 'row', alignItems: 'center' }, assignmentText: { fontSize: 14, color: HR_THEME.textSecondary, marginLeft: 6 }, avatarGroup: { flexDirection: 'row' }, avatarWrapper: { zIndex: 1 }, statsRow: { flexDirection: 'row', paddingTop: 12, borderTopWidth: 1, borderTopColor: HR_THEME.border }, statItem: { flexDirection: 'row', alignItems: 'center', marginRight: 20 }, statText: { fontSize: 13, color: HR_THEME.textSecondary, marginLeft: 6 }, emptyContainer: { alignItems: 'center', paddingVertical: 60 }, emptyText: { marginTop: 16, fontSize: 14, color: HR_THEME.textMuted }, errorContainer: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 }, errorText: { marginTop: 16, fontSize: 17, color: HR_THEME.textPrimary, textAlign: 'center' }, errorHint: { marginTop: 8, fontSize: 14, color: HR_THEME.textSecondary, textAlign: 'center' },
});
