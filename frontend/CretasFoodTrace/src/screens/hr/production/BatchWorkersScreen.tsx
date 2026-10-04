import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { View, FlatList, StyleSheet, RefreshControl, TouchableOpacity } from 'react-native';
import { Text, Card, Avatar, ActivityIndicator, IconButton } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, useRoute, useFocusEffect, RouteProp } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';
import { appAlert } from '../../../components/ui/AppDialog';
import { schedulingApiClient, type BatchWorkerStatus } from '../../../services/api/schedulingApiClient';
import { useAuthStore } from '../../../store/authStore';
import { type User } from '../../../types/auth';
import { hasModulePermission } from '../../../utils/permissionHelper';
import { HR_THEME, type HRStackParamList } from '../../../types/hrNavigation';
import { actorSnapshot, ModuleAccessError, requireProductionPlanRead, type ActorSnapshot } from './BatchAssignmentScreen';

type RouteParams = RouteProp<HRStackParamList, 'BatchWorkers'>;
type BatchWorker = {
  id: number;
  userId: number;
  name: string;
  department: string;
  workMinutes: number | null;
  status: BatchWorkerStatus;
};
type AccessFailure = 'moduleDenied' | 'factoryDisabled' | 'preflightFailed';

function canWriteProduction(user: User | null): boolean {
  return hasModulePermission(user, 'production', 'write');
}

function statusConfig(t: (key: string) => string): Record<BatchWorkerStatus, { label: string; color: string }> {
  return {
    assigned: { label: t('production.batchWorkers.status.assigned'), color: HR_THEME.info },
    working: { label: t('production.batchWorkers.status.working'), color: HR_THEME.success },
    completed: { label: t('production.batchWorkers.status.completed'), color: HR_THEME.primary },
    paused: { label: t('production.batchWorkers.status.paused'), color: HR_THEME.warning },
    unknown: { label: t('production.batchWorkers.status.unknown'), color: HR_THEME.textMuted },
  };
}

function formatMinutes(minutes: number | null, noData: string): string {
  if (minutes === null) return noData;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

export default function BatchWorkersScreen() {
  const navigation = useNavigation();
  const route = useRoute<RouteParams>();
  const { batchId, batchName } = route.params;
  const { t } = useTranslation('hr');
  const authUser = useAuthStore((state) => state.user);
  const snapshot = useMemo(() => actorSnapshot(authUser), [authUser]);
  const screenOwnerKey = snapshot ? `${snapshot.key}:${batchId}` : null;
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [workers, setWorkers] = useState<BatchWorker[]>([]);
  const [workersOwnerKey, setWorkersOwnerKey] = useState<string | null>(null);
  const [accessFailure, setAccessFailure] = useState<AccessFailure | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const actorRevision = useRef(0);
  const requestRevision = useRef(0);

  useEffect(() => {
    actorRevision.current += 1;
    requestRevision.current += 1;
    if (snapshot && actorSnapshot(useAuthStore.getState().user)?.key === snapshot.key) {
      setWorkers([]); setWorkersOwnerKey(null); setAccessFailure(null); setLoadFailed(false); setLoading(true);
    }
  }, [snapshot?.key, batchId]);

  const isLiveActor = useCallback((current: ActorSnapshot) => actorSnapshot(useAuthStore.getState().user)?.key === current.key, []);
  const isCurrent = useCallback((current: ActorSnapshot, revision: number) => actorRevision.current === revision && isLiveActor(current), [isLiveActor]);
  const loadData = useCallback(async () => {
    const current = snapshot;
    if (!current || !isLiveActor(current)) return;
    const actorRevisionAtStart = actorRevision.current;
    const request = ++requestRevision.current;
    setLoading(true); setRefreshing(false); setWorkers([]); setWorkersOwnerKey(null); setAccessFailure(null); setLoadFailed(false);
    try {
      await requireProductionPlanRead(current);
      if (!isCurrent(current, actorRevisionAtStart)) return;
      const response = await schedulingApiClient.getBatchWorkers(batchId, current.factoryId);
      if (!isCurrent(current, actorRevisionAtStart) || request !== requestRevision.current) return;
      setWorkers(response.content.map((worker) => ({ id: worker.id, userId: worker.userId, name: worker.userName, department: worker.workType, workMinutes: worker.workMinutes, status: worker.status })));
      setWorkersOwnerKey(screenOwnerKey);
    } catch (error) {
      if (!isCurrent(current, actorRevisionAtStart) || request !== requestRevision.current) return;
      setWorkersOwnerKey(screenOwnerKey);
      if (error instanceof ModuleAccessError) setAccessFailure(error.reason); else setLoadFailed(true);
    } finally {
      if (isCurrent(current, actorRevisionAtStart) && request === requestRevision.current) { setLoading(false); setRefreshing(false); }
    }
  }, [batchId, isCurrent, isLiveActor, screenOwnerKey, snapshot]);

  useFocusEffect(useCallback(() => {
    void loadData();
    return () => {
      actorRevision.current += 1;
      requestRevision.current += 1;
      const current = snapshot;
      if (current && isLiveActor(current)) { setWorkers([]); setWorkersOwnerKey(null); }
    };
  }, [isLiveActor, loadData, snapshot]));
  const onRefresh = useCallback(() => {
    const current = snapshot;
    if (current && isLiveActor(current)) { setRefreshing(true); void loadData(); }
  }, [isLiveActor, loadData, snapshot]);
  const configs = statusConfig(t);
  const visibleWorkers = workersOwnerKey === screenOwnerKey ? workers : [];

  const handleRemoveWorker = (worker: BatchWorker, rowOwnerKey: string | null) => {
    const current = actorSnapshot(useAuthStore.getState().user);
    const pageOwnerKey = screenOwnerKey;
    const currentPageOwnerKey = current ? `${current.key}:${batchId}` : null;
    const revisionAtPrompt = actorRevision.current;
    if (!current || !pageOwnerKey || pageOwnerKey !== currentPageOwnerKey || rowOwnerKey !== currentPageOwnerKey) return;
    let handled = false;
    appAlert(t('production.batchWorkers.removeWorker'), t('production.batchWorkers.removeConfirm', { name: worker.name }), [
      { text: t('common.cancel'), style: 'cancel' },
      { text: t('common.confirm'), style: 'destructive', onPress: () => {
        if (handled) return;
        handled = true;
        void (async () => {
          const currentUser = useAuthStore.getState().user;
          if (!isCurrent(current, revisionAtPrompt) || pageOwnerKey !== currentPageOwnerKey || rowOwnerKey !== currentPageOwnerKey || !canWriteProduction(currentUser)) {
            appAlert(t('messages.error'), t('production.batchWorkers.staleAction'));
            return;
          }
          try {
            await requireProductionPlanRead(current);
            const afterPreflightUser = useAuthStore.getState().user;
            if (!isCurrent(current, revisionAtPrompt) || pageOwnerKey !== currentPageOwnerKey || rowOwnerKey !== currentPageOwnerKey || !canWriteProduction(afterPreflightUser)) {
              appAlert(t('messages.error'), t('production.batchWorkers.staleAction'));
              return;
            }
            if (!isCurrent(current, revisionAtPrompt) || pageOwnerKey !== currentPageOwnerKey || rowOwnerKey !== currentPageOwnerKey) {
              appAlert(t('messages.error'), t('production.batchWorkers.staleAction'));
              return;
            }
            await schedulingApiClient.removeWorkerFromBatch(batchId, worker.userId, current.factoryId);
            if (isCurrent(current, revisionAtPrompt) && pageOwnerKey === currentPageOwnerKey && rowOwnerKey === currentPageOwnerKey) void loadData();
          } catch {
            if (isCurrent(current, revisionAtPrompt) && pageOwnerKey === currentPageOwnerKey && rowOwnerKey === currentPageOwnerKey) appAlert(t('messages.error'), t('production.batchWorkers.removeFailed'));
          }
        })();
      } },
    ]);
  };

  if (loading && !accessFailure && !loadFailed) return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;
  if (!screenOwnerKey || workersOwnerKey !== screenOwnerKey) return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;
  if (accessFailure || loadFailed) {
    const title = accessFailure === 'moduleDenied' ? t('production.batchWorkers.accessDenied') : accessFailure === 'factoryDisabled' ? t('production.batchWorkers.factoryDisabled') : t('production.batchWorkers.loadFailed');
    const hint = accessFailure === 'moduleDenied' ? t('production.batchWorkers.accessDeniedHint') : accessFailure === 'factoryDisabled' ? t('production.batchWorkers.factoryDisabledHint') : t('production.batchWorkers.loadFailedHint');
    return <SafeAreaView style={styles.container} edges={['top']}><View style={styles.header}><TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}><MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} /></TouchableOpacity><View style={styles.headerCenter}><Text style={styles.headerTitle}>{t('production.batchWorkers.title')}</Text><Text style={styles.headerSubtitle} numberOfLines={1}>{batchName}</Text></View><View style={styles.placeholder} /></View><View style={styles.errorContainer}><MaterialCommunityIcons name="shield-alert-outline" size={56} color={HR_THEME.danger} /><Text style={styles.errorText}>{title}</Text><Text style={styles.errorHint}>{hint}</Text></View></SafeAreaView>;
  }

  const totalMinutes = visibleWorkers.reduce<number | null>((sum, worker) => sum === null || worker.workMinutes === null ? null : sum + worker.workMinutes, 0);
    return <SafeAreaView style={styles.container} edges={['top']}>
    <View style={styles.header}><TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}><MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} /></TouchableOpacity><View style={styles.headerCenter}><Text style={styles.headerTitle}>{t('production.batchWorkers.title')}</Text><Text style={styles.headerSubtitle} numberOfLines={1}>{batchName}</Text></View><View style={styles.placeholder} /></View>
    <FlatList data={visibleWorkers} keyExtractor={(item) => String(item.id)} contentContainerStyle={styles.listContent} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />} ListHeaderComponent={<View style={styles.summaryCard}><View style={styles.summaryItem}><Text style={styles.summaryValue}>{visibleWorkers.length}</Text><Text style={styles.summaryLabel}>{t('production.batchWorkers.totalCount')}</Text></View><View style={styles.divider} /><View style={styles.summaryItem}><Text style={styles.summaryValue}>{formatMinutes(totalMinutes, t('common.noData'))}</Text><Text style={styles.summaryLabel}>{t('production.batchWorkers.totalHours')}</Text></View><View style={styles.divider} /><View style={styles.summaryItem}><Text style={styles.summaryValue}>{visibleWorkers.filter((worker) => worker.status === 'working').length}</Text><Text style={styles.summaryLabel}>{t('production.batchWorkers.working')}</Text></View></View>} renderItem={({ item }) => { const config = configs[item.status]; return <Card style={styles.card}><Card.Content style={styles.cardContent}><Avatar.Text size={44} label={item.name.substring(0, 1) || 'U'} style={{ backgroundColor: HR_THEME.primary }} /><View style={styles.workerInfo}><View style={styles.nameRow}><Text style={styles.workerName}>{item.name || t('common.noData')}</Text><View style={[styles.statusBadge, { backgroundColor: `${config.color}20` }]}><Text style={[styles.statusText, { color: config.color }]}>{config.label}</Text></View></View><Text style={styles.department}>{item.department || t('production.batchWorkers.noDepartment')}</Text><View style={styles.timeRow}><MaterialCommunityIcons name="clock-outline" size={14} color={HR_THEME.textSecondary} /><Text style={styles.timeText}>{t('production.batchWorkers.workDuration')}: {formatMinutes(item.workMinutes, t('common.noData'))}</Text></View></View><IconButton testID={`batch-worker-remove-${item.userId}`} icon="close" size={20} iconColor={HR_THEME.danger} onPress={() => handleRemoveWorker(item, workersOwnerKey)} /></Card.Content></Card>; }} ListEmptyComponent={<View style={styles.emptyContainer}><MaterialCommunityIcons name="account-group-outline" size={64} color={HR_THEME.textMuted} /><Text style={styles.emptyText}>{t('production.batchWorkers.empty')}</Text><Text style={styles.emptySubtext}>{t('production.batchWorkers.emptyHint')}</Text></View>} />
  </SafeAreaView>;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background }, loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 12, backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border }, backBtn: { padding: 4 }, headerCenter: { flex: 1, alignItems: 'center', marginHorizontal: 16 }, headerTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary }, headerSubtitle: { fontSize: 12, color: HR_THEME.textSecondary, marginTop: 2 }, placeholder: { width: 32 }, listContent: { padding: 16 }, summaryCard: { flexDirection: 'row', backgroundColor: HR_THEME.cardBackground, borderRadius: 12, padding: 16, marginBottom: 16 }, summaryItem: { flex: 1, alignItems: 'center' }, summaryValue: { fontSize: 24, fontWeight: 'bold', color: HR_THEME.primary }, summaryLabel: { fontSize: 12, color: HR_THEME.textSecondary, marginTop: 4 }, divider: { width: 1, backgroundColor: HR_THEME.border, marginVertical: 8 }, card: { borderRadius: 12, marginBottom: 12, backgroundColor: HR_THEME.cardBackground }, cardContent: { flexDirection: 'row', alignItems: 'center' }, workerInfo: { flex: 1, marginLeft: 12 }, nameRow: { flexDirection: 'row', alignItems: 'center' }, workerName: { fontSize: 15, fontWeight: '500', color: HR_THEME.textPrimary }, statusBadge: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: 10, marginLeft: 8 }, statusText: { fontSize: 10, fontWeight: '500' }, department: { fontSize: 13, color: HR_THEME.textSecondary, marginTop: 2 }, timeRow: { flexDirection: 'row', alignItems: 'center', marginTop: 4 }, timeText: { fontSize: 12, color: HR_THEME.textSecondary, marginLeft: 4 }, emptyContainer: { alignItems: 'center', paddingVertical: 60 }, emptyText: { marginTop: 16, fontSize: 16, color: HR_THEME.textSecondary }, emptySubtext: { marginTop: 8, fontSize: 14, color: HR_THEME.textMuted }, errorContainer: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32 }, errorText: { marginTop: 16, fontSize: 17, color: HR_THEME.textPrimary, textAlign: 'center' }, errorHint: { marginTop: 8, fontSize: 14, color: HR_THEME.textSecondary, textAlign: 'center' },
});
