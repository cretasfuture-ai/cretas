import React, { useState, useCallback, useRef } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  RefreshControl,
} from 'react-native';
import {
  Text,
  Appbar,
  Surface,
  Chip,
  Divider,
  ActivityIndicator,
  Button,
  DataTable,
  ProgressBar,
} from 'react-native-paper';
import { useNavigation, useRoute, useFocusEffect } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ProcessingStackParamList } from '../../types/navigation';
import { equipmentApiClient, type MaintenanceRecord as APIMaintenanceRecord } from '../../services/api/equipmentApiClient';
import { useAuthStore } from '../../store/authStore';
import { handleError } from '../../utils/errorHandler';
import { logger } from '../../utils/logger';
import { useTranslation } from 'react-i18next';
import { formatDate } from '../../utils/formatters';
import { getFactoryId, type User } from '../../types/auth';
import { hasModulePermission } from '../../utils/permissionHelper';
import {
  normalizeEfficiencyReport,
  normalizeOeeScalar,
  type EquipmentEfficiencyMetrics,
  type EquipmentOeeMetrics,
} from '../../utils/equipmentDetailMetrics';

// Create EquipmentDetail context logger
const equipmentDetailLogger = logger.createContextLogger('EquipmentDetail');

// Types
type EquipmentDetailScreenNavigationProp = NativeStackNavigationProp<
  ProcessingStackParamList,
  'EquipmentDetail'
>;
type EquipmentDetailScreenRouteProp = RouteProp<
  ProcessingStackParamList,
  'EquipmentDetail'
>;

type EquipmentStatus = 'running' | 'idle' | 'maintenance' | 'offline';

function equipmentActorKey(user: User | null | undefined): string {
  if (!user) return 'none';
  const factoryId = getFactoryId(user) || '';
  const userId = user.id != null ? String(user.id) : user.username || '';
  const role = user.userType === 'factory' ? user.factoryUser?.role || '' : user.userType;
  const permissions = user.userType === 'factory' ? JSON.stringify(user.factoryUser?.modulePermissions || {}) : '';
  return `${user.userType}:${userId}:${factoryId}:${role}:${permissions}`;
}

interface EquipmentInfo {
  id: string;
  name: string;
  model: string;
  manufacturer: string;
  status: EquipmentStatus;
  location: string;
  installDate: string;
  lastMaintenanceDate?: string;
  nextMaintenanceDate?: string;
  operatorName?: string;  // Operator name
}

interface RealtimeParameters {
  temperature?: number;
  pressure?: number;
  speed?: number;
  power?: number;
  humidity?: number;
  vibration?: number;
}

interface MaintenanceRecord {
  id: string;
  date: string;
  type: string;
  technician: string;
  description: string;
  cost: number | null;
}

/**
 * Equipment Detail Screen
 * P1-005: Equipment Detail Page
 *
 * Features:
 * - Equipment basic information display
 * - Real-time parameter monitoring
 * - Maintenance history records
 * - Running status statistics
 * - Navigation to alert list
 */
export default function EquipmentDetailScreen() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<EquipmentDetailScreenNavigationProp>();
  const route = useRoute<EquipmentDetailScreenRouteProp>();
  const { equipmentId } = route.params;

  // Get user context
  const { user } = useAuthStore();

  // Data state
  const [equipment, setEquipment] = useState<EquipmentInfo | null>(null);
  const [parameters, setParameters] = useState<RealtimeParameters>({});
  const [maintenanceRecords, setMaintenanceRecords] = useState<MaintenanceRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  // Statistics
  const [uptime, setUptime] = useState<number | null>(null); // Percentage points from the server

  // Performance metrics
  const [oeeData, setOeeData] = useState<EquipmentOeeMetrics | null>(null);
  const [depreciatedValue, setDepreciatedValue] = useState<number | null>(null);
  const [usageStats, setUsageStats] = useState<EquipmentEfficiencyMetrics | null>(null);
  const [activeAlertsCount, setActiveAlertsCount] = useState<number | null>(null);
  const [dataOwnerKey, setDataOwnerKey] = useState<string | null>(null);
  const [loadError, setLoadError] = useState(false);
  const mountedRef = useRef(true);
  const requestSequenceRef = useRef(0);
  const currentQueryRef = useRef('');
  const previousQueryRef = useRef('');

  const capturedUser = user as User | null;
  const canonicalFactoryId = getFactoryId(capturedUser) || '';
  const actorKey = equipmentActorKey(capturedUser);
  const normalizedEquipmentId = String(equipmentId);
  const queryKey = `${actorKey}:${canonicalFactoryId}:${normalizedEquipmentId}`;
  if (previousQueryRef.current !== queryKey) {
    previousQueryRef.current = queryKey;
    currentQueryRef.current = queryKey;
    requestSequenceRef.current += 1;
  }
  React.useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestSequenceRef.current += 1;
    };
  }, []);

  const canNavigateToFactoryAlerts = () => {
    const liveUser = useAuthStore.getState().user as User | null;
    return currentQueryRef.current === queryKey
      && equipmentActorKey(liveUser) === actorKey
      && (getFactoryId(liveUser) || '') === canonicalFactoryId
      && String(equipmentId) === normalizedEquipmentId
      && hasModulePermission(liveUser, 'equipment', 'read');
  };

  const navigateToFactoryAlerts = () => {
    if (canNavigateToFactoryAlerts()) navigation.navigate('EquipmentAlerts', {});
  };

  useFocusEffect(
    useCallback(() => {
      void fetchEquipmentDetail();
      return () => {
        requestSequenceRef.current += 1;
      };
    }, [queryKey])
  );

  const fetchEquipmentDetail = async () => {
    const requestUser = useAuthStore.getState().user as User | null;
    const requestActorKey = equipmentActorKey(requestUser);
    const requestFactoryId = getFactoryId(requestUser) || '';
    const requestEquipmentId = Number(equipmentId);
    if (currentQueryRef.current !== queryKey
      || requestActorKey !== actorKey
      || requestFactoryId !== canonicalFactoryId
      || String(equipmentId) !== normalizedEquipmentId) return;

    const requestSequence = ++requestSequenceRef.current;
    const isCurrentRequest = () => mountedRef.current
      && requestSequenceRef.current === requestSequence
      && currentQueryRef.current === queryKey
      && requestActorKey === equipmentActorKey(useAuthStore.getState().user as User | null)
      && requestFactoryId === (getFactoryId(useAuthStore.getState().user as User | null) || '')
      && String(equipmentId) === normalizedEquipmentId;

    setLoading(true);
    setLoadError(false);
    setDataOwnerKey(null);
    setEquipment(null);
    setMaintenanceRecords([]);
    setOeeData(null);
    setDepreciatedValue(null);
    setUsageStats(null);
    setUptime(null);
    try {
      if (!requestFactoryId || !Number.isInteger(requestEquipmentId) || requestEquipmentId <= 0
        || !hasModulePermission(requestUser, 'equipment', 'read')) {
        throw new Error('equipment_detail_read_denied');
      }

      // Calculate date range for performance metrics (last 30 days)
      const endDate = formatDate(new Date());
      const startDate = formatDate(new Date(Date.now() - 30 * 24 * 60 * 60 * 1000));

      const [
        eq,
        maintenanceHistoryResult,
        oeeResult,
        depreciatedValueResult,
        usageStatsResult,
      ] = await Promise.all([
        equipmentApiClient.getEquipmentById(requestEquipmentId, requestFactoryId),
        equipmentApiClient.getEquipmentMaintenanceHistory(requestEquipmentId, requestFactoryId),
        equipmentApiClient.calculateOEE(requestEquipmentId, { startDate, endDate }, requestFactoryId).catch(() => null),
        equipmentApiClient.calculateDepreciatedValue(requestEquipmentId, requestFactoryId).catch(() => null),
        equipmentApiClient.getEquipmentEfficiencyReport(requestEquipmentId, { startDate, endDate }, requestFactoryId).catch(() => null),
      ]);

      if (!isCurrentRequest()) return;
      if (String(eq.id) !== String(requestEquipmentId) || eq.factoryId !== requestFactoryId) {
        throw new Error('equipment_detail_contract_invalid');
      }

      // Map Equipment status to EquipmentStatus
      const mapStatus = (status: string): EquipmentStatus => {
        switch (status) {
          case 'active':
            return 'running';
          case 'inactive':
            return 'idle';
          case 'maintenance':
            return 'maintenance';
          case 'scrapped':
            return 'offline';
          default:
            return 'idle';
        }
      };

      // Transform Equipment to EquipmentInfo
      const equipmentInfo: EquipmentInfo = {
        id: String(eq.id),
        name: eq.name,
        model: eq.model || 'N/A',
        manufacturer: eq.manufacturer || 'Unknown',
        status: mapStatus(eq.status),
        location: eq.location || 'N/A',
        installDate: eq.purchaseDate || 'Unknown',
        lastMaintenanceDate: eq.lastMaintenanceDate,
        nextMaintenanceDate: eq.nextMaintenanceDate,
        operatorName: eq.operatorName,
      };

      const history: APIMaintenanceRecord[] = maintenanceHistoryResult || [];
      const records: MaintenanceRecord[] = history.map((record) => ({
        id: String(record.id),
        date: record.maintenanceDate,
        type: 'Routine Maintenance',
        technician: record.performedBy || 'Unknown',
        description: record.description || 'N/A',
        cost: typeof record.cost === 'number' && Number.isFinite(record.cost) && record.cost >= 0 ? record.cost : null,
      }));

      const normalizedOee = normalizeOeeScalar(oeeResult);
      const normalizedUsage = normalizeEfficiencyReport(usageStatsResult, requestEquipmentId);
      const depreciatedValue = typeof depreciatedValueResult === 'number'
        && Number.isFinite(depreciatedValueResult)
        ? depreciatedValueResult
        : null;
      const serverUptime = normalizedUsage?.availability ?? normalizedUsage?.utilizationRate ?? null;

      // ✅ Note: Real-time IoT parameters are not yet implemented
      // TODO: Integrate with IoT system in Phase 4
      // For now, set empty parameters to indicate feature is pending
      setParameters({});
      setEquipment(equipmentInfo);
      setMaintenanceRecords(records);
      setOeeData(normalizedOee);
      setDepreciatedValue(depreciatedValue);
      setUsageStats(normalizedUsage);
      setUptime(serverUptime);
      setDataOwnerKey(queryKey);

    } catch (error) {
      if (!isCurrentRequest()) return;
      equipmentDetailLogger.error('设备详情读取失败', new Error('equipment_detail_read_failed'));

      handleError(error, {
        title: t('equipmentDetail.messages.loadFailed'),
        customMessage: t('equipmentDetail.messages.loadError'),
        logError: false,
      });

      setEquipment(null);
      setMaintenanceRecords([]);
      setOeeData(null);
      setDepreciatedValue(null);
      setUsageStats(null);
      setUptime(null);
      setDataOwnerKey(queryKey);
      setLoadError(true);
    } finally {
      if (isCurrentRequest()) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  };

  const handleRefresh = async () => {
    const liveUser = useAuthStore.getState().user as User | null;
    if (currentQueryRef.current !== queryKey
      || equipmentActorKey(liveUser) !== actorKey
      || (getFactoryId(liveUser) || '') !== canonicalFactoryId) return;
    setRefreshing(true);
    await fetchEquipmentDetail();
    if (currentQueryRef.current === queryKey && equipmentActorKey(useAuthStore.getState().user as User | null) === actorKey) {
      setRefreshing(false);
    }
  };

  // Helper functions
  const getStatusLabel = (status: EquipmentStatus): string => {
    return t(`equipmentDetail.status.${status}`);
  };

  const getStatusColor = (status: EquipmentStatus): string => {
    switch (status) {
      case 'running':
        return '#4CAF50';
      case 'idle':
        return '#2196F3';
      case 'maintenance':
        return '#FF9800';
      case 'offline':
        return '#9E9E9E';
    }
  };

  // Loading state
  if (loading || dataOwnerKey !== queryKey) {
    return (
      <View style={styles.container}>
        <Appbar.Header elevated>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={t('equipmentDetail.title')} />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" />
          <Text style={styles.loadingText}>{t('equipmentDetail.loading')}</Text>
        </View>
      </View>
    );
  }

  // Error state
  if (loadError || !equipment) {
    return (
      <View style={styles.container}>
        <Appbar.Header elevated>
          <Appbar.BackAction onPress={() => navigation.goBack()} />
          <Appbar.Content title={t('equipmentDetail.title')} />
        </Appbar.Header>
        <View style={styles.loadingContainer}>
          <Text style={styles.errorText}>{t('equipmentDetail.messages.notFound')}</Text>
          <Button mode="contained" onPress={fetchEquipmentDetail} style={{ marginTop: 16 }}>
            {t('equipmentDetail.retry')}
          </Button>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Appbar.Header elevated>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={equipment.name} />
        <Appbar.Action
          icon="bell"
          onPress={navigateToFactoryAlerts}
        />
      </Appbar.Header>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
        }
      >
        {/* Status Header */}
        <Surface style={styles.headerSection} elevation={1}>
          <View style={styles.headerRow}>
            <View style={styles.headerLeft}>
              <Text variant="headlineSmall" style={styles.equipmentName}>
                {equipment.name}
              </Text>
              <Text variant="bodyMedium" style={styles.model}>
                {equipment.model}
              </Text>
            </View>
            <Chip
              mode="flat"
              style={[
                styles.statusChip,
                { backgroundColor: getStatusColor(equipment.status) + '20' },
              ]}
              textStyle={[
                styles.statusChipText,
                { color: getStatusColor(equipment.status) },
              ]}
            >
              {getStatusLabel(equipment.status)}
            </Chip>
          </View>

          <View style={styles.statsRow}>
            <View style={styles.statItem}>
              <Text style={styles.statValue}>{uptime === null ? t('equipmentDetail.stats.unknown') : `${uptime}%`}</Text>
              <Text style={styles.statLabel}>{t('equipmentDetail.stats.uptime')}</Text>
            </View>
            <Divider style={styles.statDivider} />
            <View style={styles.statItem}>
              <Text style={styles.statValue}>{activeAlertsCount === null ? t('equipmentDetail.stats.unknown') : activeAlertsCount}</Text>
              <Text style={styles.statLabel}>{t('equipmentDetail.stats.activeAlerts')}</Text>
            </View>
            <Divider style={styles.statDivider} />
            <View style={styles.statItem}>
              <Text style={styles.statValue}>
                {maintenanceRecords.length}
              </Text>
              <Text style={styles.statLabel}>{t('equipmentDetail.stats.maintenanceCount')}</Text>
            </View>
          </View>
          {activeAlertsCount === null && (
            <Text style={styles.statsHint}>{t('equipmentDetail.stats.alertsHint')}</Text>
          )}
          <Text style={styles.statsHint}>{t('equipmentDetail.stats.reportHint')}</Text>
        </Surface>

        {/* Basic Information */}
        <Surface style={styles.section} elevation={1}>
          <Text variant="titleMedium" style={styles.sectionTitle}>
            {t('equipmentDetail.sections.basicInfo')}
          </Text>

          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.equipmentId')}</Text>
            <Text style={styles.infoValue}>{equipment.id}</Text>
          </View>

          <Divider style={styles.divider} />

          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.manufacturer')}</Text>
            <Text style={styles.infoValue}>{equipment.manufacturer}</Text>
          </View>

          <Divider style={styles.divider} />

          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.location')}</Text>
            <Text style={styles.infoValue}>{equipment.location}</Text>
          </View>

          <Divider style={styles.divider} />

          <View style={styles.infoRow}>
            <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.installDate')}</Text>
            <Text style={styles.infoValue}>{equipment.installDate}</Text>
          </View>

          {equipment.operatorName && (
            <>
              <Divider style={styles.divider} />
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.operator')}</Text>
                <Text style={styles.infoValue}>{equipment.operatorName}</Text>
              </View>
            </>
          )}

          {equipment.lastMaintenanceDate && (
            <>
              <Divider style={styles.divider} />
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.lastMaintenance')}</Text>
                <Text style={styles.infoValue}>
                  {equipment.lastMaintenanceDate}
                </Text>
              </View>
            </>
          )}

          {equipment.nextMaintenanceDate && (
            <>
              <Divider style={styles.divider} />
              <View style={styles.infoRow}>
                <Text style={styles.infoLabel}>{t('equipmentDetail.basicInfo.nextMaintenance')}</Text>
                <Text style={[styles.infoValue, styles.highlight]}>
                  {equipment.nextMaintenanceDate}
                </Text>
              </View>
            </>
          )}
        </Surface>

        {/* Realtime Parameters */}
        <Surface style={styles.section} elevation={1}>
          <Text variant="titleMedium" style={styles.sectionTitle}>
            {t('equipmentDetail.sections.realtimeParams')}
          </Text>

          {parameters.temperature !== undefined && (
            <View style={styles.parameterItem}>
              <View style={styles.parameterHeader}>
                <Text style={styles.parameterLabel}>{t('equipmentDetail.realtimeParams.temperature')}</Text>
                <Text style={styles.parameterValue}>
                  {parameters.temperature}°C
                </Text>
              </View>
              <ProgressBar
                progress={Math.abs(parameters.temperature) / 30}
                color="#2196F3"
                style={styles.progressBar}
              />
            </View>
          )}

          {parameters.pressure !== undefined && (
            <View style={styles.parameterItem}>
              <View style={styles.parameterHeader}>
                <Text style={styles.parameterLabel}>{t('equipmentDetail.realtimeParams.pressure')}</Text>
                <Text style={styles.parameterValue}>
                  {parameters.pressure} bar
                </Text>
              </View>
              <ProgressBar
                progress={parameters.pressure / 5}
                color="#4CAF50"
                style={styles.progressBar}
              />
            </View>
          )}

          {parameters.speed !== undefined && (
            <View style={styles.parameterItem}>
              <View style={styles.parameterHeader}>
                <Text style={styles.parameterLabel}>{t('equipmentDetail.realtimeParams.speed')}</Text>
                <Text style={styles.parameterValue}>
                  {parameters.speed} rpm
                </Text>
              </View>
              <ProgressBar
                progress={parameters.speed / 2000}
                color="#FF9800"
                style={styles.progressBar}
              />
            </View>
          )}

          {parameters.power !== undefined && (
            <View style={styles.parameterItem}>
              <View style={styles.parameterHeader}>
                <Text style={styles.parameterLabel}>{t('equipmentDetail.realtimeParams.power')}</Text>
                <Text style={styles.parameterValue}>
                  {parameters.power}%
                </Text>
              </View>
              <ProgressBar
                progress={parameters.power / 100}
                color="#9C27B0"
                style={styles.progressBar}
              />
            </View>
          )}
        </Surface>

        {/* Performance Analysis */}
        {(oeeData !== null || depreciatedValue !== null || usageStats !== null) && (
          <Surface style={styles.section} elevation={1}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('equipmentDetail.sections.performanceAnalysis')}
            </Text>

            {/* OEE Metrics */}
            {oeeData && (
              <>
                <View style={styles.oeeContainer}>
                  <Text style={styles.oeeLabel}>{t('equipmentDetail.performance.oee')}</Text>
                  <Text style={styles.oeeValue}>
                    {oeeData.oee.toFixed(1)}%
                  </Text>
                </View>

                <Divider style={styles.divider} />
              </>
            )}

            {/* Depreciated Value */}
            {depreciatedValue !== null && (
              <>
                <View style={styles.infoRow}>
                  <Text style={styles.infoLabel}>{t('equipmentDetail.performance.depreciatedValue')}</Text>
                  <Text style={[styles.infoValue, styles.valueHighlight]}>
                    ¥{depreciatedValue.toFixed(2)}
                  </Text>
                </View>

                <Divider style={styles.divider} />
              </>
            )}

            {/* Usage Statistics */}
            {usageStats && (
              <>
                <View style={styles.infoRow}>
                  <Text style={styles.infoLabel}>{t('equipmentDetail.performance.totalRuntime')}</Text>
                  <Text style={styles.infoValue}>
                    {usageStats.actualRunningHours === null ? 'N/A' : t('equipmentDetail.hours', { value: usageStats.actualRunningHours })}
                  </Text>
                </View>

                <Divider style={styles.divider} />

                <View style={styles.infoRow}>
                  <Text style={styles.infoLabel}>{t('equipmentDetail.performance.utilizationRate')}</Text>
                  <Text style={[styles.infoValue, styles.valueHighlight]}>
                    {usageStats.utilizationRate === null ? 'N/A' : `${usageStats.utilizationRate.toFixed(1)}%`}
                  </Text>
                </View>
              </>
            )}
          </Surface>
        )}

        {/* Maintenance Records */}
        <Surface style={styles.section} elevation={1}>
          <View style={styles.sectionHeader}>
            <Text variant="titleMedium" style={styles.sectionTitle}>
              {t('equipmentDetail.sections.maintenanceRecords')}
            </Text>
            <Chip mode="flat" style={styles.countChip}>
              {maintenanceRecords.length}
            </Chip>
          </View>

          {maintenanceRecords.length > 0 ? (
            <DataTable>
              <DataTable.Header>
                <DataTable.Title>{t('equipmentDetail.maintenanceRecords.date')}</DataTable.Title>
                <DataTable.Title>{t('equipmentDetail.maintenanceRecords.type')}</DataTable.Title>
                <DataTable.Title>{t('equipmentDetail.maintenanceRecords.technician')}</DataTable.Title>
                <DataTable.Title numeric>{t('equipmentDetail.maintenanceRecords.cost')}</DataTable.Title>
              </DataTable.Header>

              {maintenanceRecords.slice(0, 5).map((record) => (
                <DataTable.Row key={record.id}>
                  <DataTable.Cell>{record.date}</DataTable.Cell>
                  <DataTable.Cell>{record.type}</DataTable.Cell>
                  <DataTable.Cell>{record.technician}</DataTable.Cell>
                  <DataTable.Cell numeric>{record.cost === null ? 'N/A' : `¥${record.cost}`}</DataTable.Cell>
                </DataTable.Row>
              ))}
            </DataTable>
          ) : (
            <Text style={styles.emptyText}>{t('equipmentDetail.maintenanceRecords.empty')}</Text>
          )}
        </Surface>

        {/* Actions */}
        <View style={styles.actions}>
          <Button
            mode="outlined"
            icon="bell"
            onPress={navigateToFactoryAlerts}
            style={styles.actionButton}
          >
            {t('equipmentDetail.actions.viewAlerts')}
          </Button>
          <Button
            mode="contained"
            icon="wrench"
            onPress={() => {}}
            style={styles.actionButton}
          >
            {t('equipmentDetail.actions.maintenanceRecord')}
          </Button>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  loadingText: {
    marginTop: 16,
    color: '#666',
  },
  errorText: {
    color: '#F44336',
    fontSize: 16,
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 32,
  },
  headerSection: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 16,
  },
  headerLeft: {
    flex: 1,
  },
  equipmentName: {
    fontWeight: '700',
    color: '#212121',
  },
  model: {
    color: '#666',
    marginTop: 4,
  },
  statusChip: {},
  statusChipText: {
    fontWeight: '600',
    fontSize: 12,
  },
  statsRow: {
    flexDirection: 'row',
    paddingTop: 16,
    borderTopWidth: 1,
    borderTopColor: '#E0E0E0',
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 20,
    fontWeight: '700',
    color: '#212121',
    marginBottom: 4,
  },
  statLabel: {
    fontSize: 12,
    color: '#666',
  },
  statsHint: {
    color: '#666',
    fontSize: 12,
    marginTop: 12,
    textAlign: 'center',
  },
  statDivider: {
    width: 1,
    height: '100%',
  },
  section: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  sectionTitle: {
    marginBottom: 16,
    fontWeight: '600',
  },
  countChip: {
    backgroundColor: '#E3F2FD',
  },
  infoRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
  },
  infoLabel: {
    fontSize: 14,
    color: '#666',
  },
  infoValue: {
    fontSize: 14,
    fontWeight: '500',
    color: '#212121',
  },
  highlight: {
    color: '#2196F3',
    fontWeight: '600',
  },
  divider: {
    marginVertical: 8,
  },
  parameterItem: {
    marginBottom: 16,
  },
  parameterHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  parameterLabel: {
    fontSize: 14,
    color: '#666',
  },
  parameterValue: {
    fontSize: 16,
    fontWeight: '600',
    color: '#212121',
  },
  progressBar: {
    height: 8,
    borderRadius: 4,
  },
  emptyText: {
    textAlign: 'center',
    color: '#9E9E9E',
    paddingVertical: 16,
  },
  actions: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 8,
  },
  actionButton: {
    flex: 1,
  },
  oeeContainer: {
    alignItems: 'center',
    marginBottom: 16,
  },
  oeeLabel: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
  },
  oeeValue: {
    fontSize: 36,
    fontWeight: 'bold',
    color: '#4CAF50',
  },
  valueHighlight: {
    color: '#2196F3',
    fontWeight: '600',
  },
});
