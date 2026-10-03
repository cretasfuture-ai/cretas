import React, { useState, useCallback, useEffect, useRef } from 'react';
import { View, StyleSheet, ScrollView, RefreshControl, TextInput as RNTextInput } from 'react-native';
import { appAlert } from '../../components/ui/AppDialog';
import { useTranslation } from 'react-i18next';
import {
  Text,
  Appbar,
  ActivityIndicator,
  Searchbar,
  FAB,
  Portal,
  Dialog,
  TextInput,
  IconButton,
  Menu,
  Chip,
} from 'react-native-paper';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { ProcessingStackParamList } from '../../types/navigation';
import {
  equipmentApiClient,
  type Equipment,
  type EquipmentStatus,
  type EquipmentType,
  type CreateEquipmentRequest,
} from '../../services/api/equipmentApiClient';
import { userApiClient, type UserDTO } from '../../services/api/userApiClient';
import { useAuthStore } from '../../store/authStore';
import { getFactoryId, type User } from '../../types/auth';
import { NeoCard, NeoButton, ScreenWrapper, StatusBadge } from '../../components/ui';
import { theme } from '../../theme';
import { logger } from '../../utils/logger';
import { hasModulePermission } from '../../utils/permissionHelper';

// Create EquipmentManagement context logger
const equipmentMgmtLogger = logger.createContextLogger('EquipmentManagement');

type NavigationProp = NativeStackNavigationProp<ProcessingStackParamList, 'EquipmentManagement'>;

type EquipmentActorContext = {
  actorKey: string;
  factoryId: string;
  generation: number;
  renderActorKey: string;
  renderUser: User | null | undefined;
};

function equipmentActorKey(user: User | null | undefined): string {
  if (!user) return 'none';
  const factoryId = getFactoryId(user) || '';
  const userId = user.id != null ? String(user.id) : user.username || '';
  const role = user.userType === 'factory' ? user.factoryUser?.role || '' : user.userType;
  const equipmentPermission = user.userType === 'factory'
    ? user.factoryUser?.modulePermissions?.equipment || ''
    : '';
  const hrPermission = user.userType === 'factory'
    ? user.factoryUser?.modulePermissions?.hr || ''
    : '';
  return `${user.userType}:${userId}:${factoryId}:${role}:${equipmentPermission}:${hrPermission}`;
}

export default function EquipmentManagementScreen() {
  const user = useAuthStore((state) => state.user);
  const actorKey = equipmentActorKey(user);
  const previousUserRef = useRef<User | null | undefined>(user);
  const generationRef = useRef(0);
  if (previousUserRef.current !== user) {
    previousUserRef.current = user;
    generationRef.current += 1;
  }
  return <EquipmentManagementContent key={`${actorKey}:${generationRef.current}`} />;
}

function EquipmentManagementContent() {
  const { t } = useTranslation('processing');
  const navigation = useNavigation<NavigationProp>();
  const { user } = useAuthStore();
  const renderActorKey = equipmentActorKey(user);
  const liveUserForRender = useAuthStore.getState().user;
  const liveActorKeyForRender = equipmentActorKey(liveUserForRender);
  const canReadEquipment = renderActorKey === liveActorKeyForRender
    && hasModulePermission(liveUserForRender, 'equipment', 'read');
  const canWriteEquipment = renderActorKey === liveActorKeyForRender
    && hasModulePermission(liveUserForRender, 'equipment', 'write');
  const canReadHr = renderActorKey === liveActorKeyForRender
    && hasModulePermission(liveUserForRender, 'hr', 'read');
  const generationRef = useRef(0);
  const actorKeyRef = useRef(renderActorKey);
  const mountedRef = useRef(false);
  if (actorKeyRef.current !== renderActorKey) {
    actorKeyRef.current = renderActorKey;
    generationRef.current += 1;
  }

  // State
  const [equipments, setEquipments] = useState<Equipment[]>([]);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<EquipmentStatus | 'all'>('all');
  const [typeFilter, setTypeFilter] = useState<EquipmentType | 'all'>('all');

  // CRUD State
  const [selectedEquipment, setSelectedEquipment] = useState<Equipment | null>(null);
  const [loadedActorKey, setLoadedActorKey] = useState<string | null>(null);
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showEditDialog, setShowEditDialog] = useState(false);
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const [showStatusMenu, setShowStatusMenu] = useState(false);

  // Operator selection State
  const [operatorUsers, setOperatorUsers] = useState<UserDTO[]>([]);
  const [showOperatorMenu, setShowOperatorMenu] = useState(false);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [operatorLoadError, setOperatorLoadError] = useState(false);

  // Form State
  const [formData, setFormData] = useState<CreateEquipmentRequest>({
    name: '',
    code: '',
    type: 'processing',
    model: '',
    manufacturer: '',
    purchaseDate: '',
    location: '',
    specifications: '',
    purchasePrice: undefined,
    depreciationYears: undefined,
    maintenanceInterval: undefined,
    operatorId: undefined,
    notes: '',
  });

  const captureActorContext = (): EquipmentActorContext => {
    const liveUser = useAuthStore.getState().user;
    return {
      actorKey: equipmentActorKey(liveUser),
      factoryId: getFactoryId(liveUser) || '',
      generation: generationRef.current,
      renderActorKey,
      renderUser: user,
    };
  };

  const isCurrentActorContext = (
    context: EquipmentActorContext,
    requireWrite = false,
    requireHrRead = false,
  ): boolean => {
    if (!mountedRef.current || context.generation !== generationRef.current) return false;
    const liveUser = useAuthStore.getState().user;
    const liveActorKey = equipmentActorKey(liveUser);
    const liveFactoryId = getFactoryId(liveUser) || '';
    if (!context.factoryId || context.actorKey !== context.renderActorKey
      || context.renderActorKey !== renderActorKey
      || liveUser !== context.renderUser
      || liveActorKey !== context.actorKey || liveFactoryId !== context.factoryId) {
      return false;
    }
    if (!hasModulePermission(liveUser, 'equipment', 'read')) return false;
    if (requireWrite && !hasModulePermission(liveUser, 'equipment', 'write')) return false;
    return !requireHrRead || hasModulePermission(liveUser, 'hr', 'read');
  };

  const canonicalSelectedEquipment = (context: EquipmentActorContext): Equipment | null => {
    if (!selectedEquipment || loadedActorKey !== context.actorKey) return null;
    const canonical = equipments.find((equipment) => equipment.id === selectedEquipment.id);
    return canonical?.factoryId === context.factoryId ? canonical : null;
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      generationRef.current += 1;
    };
  }, []);

  useEffect(() => {
    setEquipments([]);
    setLoadedActorKey(null);
    setLoading(false);
    setRefreshing(false);
    setLoadingUsers(false);
    setSelectedEquipment(null);
    setShowCreateDialog(false);
    setShowEditDialog(false);
    setShowDeleteDialog(false);
    setShowStatusMenu(false);
    setOperatorUsers([]);
    setOperatorLoadError(false);
  }, [renderActorKey, user]);

  // Load selectable operator users list
  const loadOperatorUsers = useCallback(async () => {
    const context = captureActorContext();
    if (!isCurrentActorContext(context, true, true)) return;
    setLoadingUsers(true);
    setOperatorLoadError(false);
    setOperatorUsers([]);
    try {
      // Load all users, can be filtered by role later
      const response = await userApiClient.getUsers({ factoryId: context.factoryId, size: 100 });
      // Handle paginated response
      const users = Array.isArray(response) ? response : (response && typeof response === 'object' && 'content' in response) ? (response as { content: unknown[] }).content || [] : [];
      const scopedUsers = (users as Array<UserDTO & { factoryId?: string; factoryUser?: { factoryId?: string } }>).filter((operator) => {
        const operatorFactoryId = operator.factoryId || operator.factoryUser?.factoryId;
        return !operatorFactoryId || operatorFactoryId === context.factoryId;
      });
      if (isCurrentActorContext(context, true, true)) setOperatorUsers(scopedUsers);
    } catch (error) {
      equipmentMgmtLogger.error('Failed to load operator users list', error as Error);
      if (isCurrentActorContext(context, true, true)) setOperatorLoadError(true);
    } finally {
      if (isCurrentActorContext(context, true, true)) setLoadingUsers(false);
    }
  }, [renderActorKey, user]);

  const loadEquipments = useCallback(async () => {
    const context = captureActorContext();
    if (!isCurrentActorContext(context)) return;
    setLoading(true);
    try {
      if (!context.factoryId) {
        appAlert(t('equipmentManagement.messages.loadFailed'), t('equipmentManagement.messages.factoryIdError'));
        return;
      }

      const params: any = {
        factoryId: context.factoryId,
        page: 1,
        size: 50,
        sortBy: 'createdAt',
        sortDirection: 'DESC',
      };

      if (statusFilter !== 'all') params.status = statusFilter;
      if (typeFilter !== 'all') params.type = typeFilter;
      if (searchQuery.trim()) params.keyword = searchQuery.trim();

      const result = await equipmentApiClient.getEquipments(params);
      let equipmentList: Equipment[] = [];

      if (result && result.content) {
        equipmentList = result.content.filter((equipment) => equipment.factoryId === context.factoryId);
      } else if (Array.isArray(result)) {
        equipmentList = (result as unknown as Equipment[])
          .filter((equipment) => equipment.factoryId === context.factoryId);
      }

      if (isCurrentActorContext(context)) {
        setEquipments(equipmentList);
        setLoadedActorKey(context.actorKey);
      }
    } catch (error) {
      if (isCurrentActorContext(context)) {
        setEquipments([]);
        setLoadedActorKey(context.actorKey);
      }
    } finally {
      if (isCurrentActorContext(context)) setLoading(false);
    }
  }, [renderActorKey, user, statusFilter, typeFilter, searchQuery, t]);

  const handleRefresh = async () => {
    const context = captureActorContext();
    if (!isCurrentActorContext(context)) return;
    setRefreshing(true);
    await loadEquipments();
    if (isCurrentActorContext(context)) setRefreshing(false);
  };

  useFocusEffect(
    useCallback(() => {
      void loadEquipments();
    }, [loadEquipments])
  );

  // CRUD Handlers (unchanged logic, just simplified for brevity in visual refactor)
  const handleCreateEquipment = async () => {
      const context = captureActorContext();
      if (!isCurrentActorContext(context, true)) return;
      // ... same logic as before ...
      // For brevity, assuming successful creation for now
      try {
        setLoading(true);
        await equipmentApiClient.createEquipment(formData, context.factoryId);
        if (!isCurrentActorContext(context)) return;
        equipmentMgmtLogger.info('Equipment created successfully', {
          equipmentName: formData.name,
          equipmentCode: formData.code,
          factoryId: context.factoryId,
        });
        setShowCreateDialog(false);
        void loadEquipments();
      } catch (e) {
        if (!isCurrentActorContext(context)) return;
        equipmentMgmtLogger.error('Failed to create equipment', e as Error, {
          equipmentName: formData.name,
          factoryId: context.factoryId,
        });
      } finally {
        if (isCurrentActorContext(context)) setLoading(false);
      }
  };

  const handleUpdateEquipment = async () => {
      const context = captureActorContext();
      if (!isCurrentActorContext(context, true)) return;
      const canonical = canonicalSelectedEquipment(context);
      if (!canonical) return;
      try {
          setLoading(true);
          await equipmentApiClient.updateEquipment(canonical.id, formData, context.factoryId);
          if (!isCurrentActorContext(context)) return;
          equipmentMgmtLogger.info('Equipment updated successfully', {
            equipmentId: canonical.id,
            equipmentName: formData.name,
            factoryId: context.factoryId,
          });
          setShowEditDialog(false);
          void loadEquipments();
      } catch(e) {
        if (!isCurrentActorContext(context)) return;
        equipmentMgmtLogger.error('Failed to update equipment', e as Error, {
          equipmentId: canonical.id,
          factoryId: context.factoryId,
        });
      } finally {
        if (isCurrentActorContext(context)) setLoading(false);
      }
  };

  const handleDeleteEquipment = async () => {
      const context = captureActorContext();
      if (!isCurrentActorContext(context, true)) return;
      const canonical = canonicalSelectedEquipment(context);
      if (!canonical) return;
      try {
          setLoading(true);
          await equipmentApiClient.deleteEquipment(canonical.id, context.factoryId);
          if (!isCurrentActorContext(context)) return;
          equipmentMgmtLogger.info('Equipment deleted successfully', {
            equipmentId: canonical.id,
            equipmentName: canonical.name,
            factoryId: context.factoryId,
          });
          setShowDeleteDialog(false);
          void loadEquipments();
      } catch(e) {
        if (!isCurrentActorContext(context)) return;
        equipmentMgmtLogger.error('Failed to delete equipment', e as Error, {
          equipmentId: canonical.id,
          factoryId: context.factoryId,
        });
      } finally {
        if (isCurrentActorContext(context)) setLoading(false);
      }
  };

  const handleUpdateStatus = async (newStatus: EquipmentStatus) => {
      const context = captureActorContext();
      if (!isCurrentActorContext(context, true)) return;
      const canonical = canonicalSelectedEquipment(context);
      if (!canonical) return;
      try {
          setLoading(true);
          await equipmentApiClient.updateEquipmentStatus(canonical.id, newStatus, context.factoryId);
          if (!isCurrentActorContext(context)) return;
          equipmentMgmtLogger.info('Equipment status updated successfully', {
            equipmentId: canonical.id,
            newStatus,
            factoryId: context.factoryId,
          });
          setShowStatusMenu(false);
          void loadEquipments();
      } catch(e) {
        if (!isCurrentActorContext(context)) return;
        equipmentMgmtLogger.error('Failed to update equipment status', e as Error, {
          equipmentId: canonical.id,
          newStatus,
          factoryId: context.factoryId,
        });
      } finally {
        if (isCurrentActorContext(context)) setLoading(false);
      }
  };

  // Dialog Openers
  const openCreateDialog = () => {
    setFormData({ name: '', code: '', type: 'processing', model: '', manufacturer: '', purchaseDate: '', location: '', specifications: '', operatorId: undefined, notes: '' });
    if (isCurrentActorContext(captureActorContext(), true, true)) void loadOperatorUsers();
    setShowCreateDialog(true);
  };

  const openEditDialog = (equipment: Equipment) => {
    setSelectedEquipment(equipment);
    setFormData({ ...equipment, purchasePrice: equipment.purchasePrice, depreciationYears: equipment.depreciationYears, maintenanceInterval: equipment.maintenanceInterval, operatorId: equipment.operatorId });
    if (isCurrentActorContext(captureActorContext(), true, true)) void loadOperatorUsers();
    setShowEditDialog(true);
  };

  const openDeleteDialog = (equipment: Equipment) => {
    setSelectedEquipment(equipment);
    setShowDeleteDialog(true);
  };

  const openStatusMenu = (equipment: Equipment) => {
    setSelectedEquipment(equipment);
    setShowStatusMenu(true);
  };

  const openOperatorMenu = () => {
    if (!isCurrentActorContext(captureActorContext(), true, true)) return;
    setShowOperatorMenu(true);
  };

  const selectOperator = (operatorId?: number) => {
    if (!isCurrentActorContext(captureActorContext(), true, true)) return;
    setFormData((current) => ({ ...current, operatorId }));
    setShowOperatorMenu(false);
  };

  const retryOperatorUsers = () => {
    if (!isCurrentActorContext(captureActorContext(), true, true)) return;
    void loadOperatorUsers();
  };

  const getStatusVariant = (status: EquipmentStatus) => {
    switch(status) {
        case 'active': return 'success';
        case 'maintenance': return 'warning';
        case 'inactive': return 'default';
        case 'scrapped': return 'error';
        default: return 'default';
    }
  };

  const getStatusLabel = (status: EquipmentStatus) => {
      const knownStatus = ['active', 'idle', 'inactive', 'maintenance', 'scrapped'].includes(status);
      return t(`equipmentManagement.status.${knownStatus ? status : 'unknown'}`);
  };

  const getTypeLabel = (type: EquipmentType | null | undefined) => {
      if (!type) return t('equipmentManagement.type.missing');
      const knownType = ['processing', 'refrigeration', 'packaging', 'transport', 'other'].includes(type);
      return t(`equipmentManagement.type.${knownType ? type : 'unknown'}`);
  };

  const getSelectedOperatorName = (): string => {
    if (!formData.operatorId) return t('equipmentManagement.dialogs.fields.noOperator');
    const operator = operatorUsers.find(u => u.id === formData.operatorId);
    return operator
      ? operator.realName
      : t('equipmentManagement.dialogs.fields.operatorBindingRetained', { id: formData.operatorId });
  };

  const renderOperatorField = () => {
    if (!canReadHr) {
      return (
        <Text testID="equipment-management-operator-hr-locked">
          {formData.operatorId
            ? t('equipmentManagement.dialogs.fields.operatorBindingRetained', { id: formData.operatorId })
            : t('equipmentManagement.dialogs.fields.operatorReadRequired')}
        </Text>
      );
    }
    return (
      <>
        <Menu
          visible={showOperatorMenu}
          onDismiss={() => setShowOperatorMenu(false)}
          anchor={
            <NeoButton
              testID="equipment-management-operator-picker"
              variant="outline"
              onPress={openOperatorMenu}
              style={styles.operatorButton}
            >
              {loadingUsers ? t('equipmentManagement.dialogs.fields.loadingOperators') : getSelectedOperatorName()}
            </NeoButton>
          }
          contentStyle={{ backgroundColor: 'white', maxHeight: 300 }}
        >
          <Menu.Item
            testID="equipment-management-operator-clear"
            onPress={() => selectOperator(undefined)}
            title={t('equipmentManagement.dialogs.fields.noOperator')}
          />
          {operatorUsers.map(operator => (
            <Menu.Item
              key={operator.id}
              testID={`equipment-management-operator-${operator.id}`}
              onPress={() => selectOperator(operator.id)}
              title={`${operator.realName} (${operator.position || operator.role})`}
            />
          ))}
        </Menu>
        {operatorLoadError && (
          <View>
            <Text testID="equipment-management-operator-load-error">
              {t('equipmentManagement.dialogs.fields.operatorLoadFailed')}
            </Text>
            <NeoButton testID="equipment-management-operator-retry" variant="ghost" onPress={retryOperatorUsers}>
              {t('equipmentManagement.dialogs.fields.retryOperators')}
            </NeoButton>
          </View>
        )}
      </>
    );
  };

  const visibleEquipments = canReadEquipment && loadedActorKey === renderActorKey
    ? equipments
    : [];

  return (
    <ScreenWrapper edges={['top']} backgroundColor={theme.colors.background}>
      <Appbar.Header elevated style={{ backgroundColor: theme.colors.surface }}>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('equipmentManagement.title')} titleStyle={{ fontWeight: '600' }} />
        <Appbar.Action icon="monitor-dashboard" onPress={() => navigation.navigate('EquipmentMonitoring')} />
      </Appbar.Header>

      <View style={styles.searchContainer}>
        <Searchbar
          placeholder={t('equipmentManagement.searchPlaceholder')}
          onChangeText={setSearchQuery}
          value={searchQuery}
          style={styles.searchbar}
          inputStyle={{ minHeight: 0 }}
          elevation={0}
        />
      </View>

      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterContainer}>
        {(['all', 'active', 'maintenance', 'inactive'] as const).map((status) => (
            <Chip
                key={status}
                mode={statusFilter === status ? 'flat' : 'outlined'}
                selected={statusFilter === status}
                onPress={() => setStatusFilter(status as EquipmentStatus | 'all')}
                style={styles.filterChip}
                textStyle={{ fontSize: 12 }}
                showSelectedOverlay
            >
                {status === 'all' ? t('equipmentManagement.filters.all') : getStatusLabel(status as EquipmentStatus)}
            </Chip>
        ))}
      </ScrollView>

      <ScrollView
        style={styles.content}
        contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />}
      >
        {loading && visibleEquipments.length === 0 ? (
          <View style={styles.centerContainer}><ActivityIndicator size="large" color={theme.colors.primary} /></View>
        ) : visibleEquipments.length === 0 ? (
          <View style={styles.centerContainer}>
            <Text style={styles.emptyText}>{t('equipmentManagement.empty.title')}</Text>
            {canWriteEquipment && <NeoButton testID="equipment-management-create-button" variant="primary" onPress={openCreateDialog} style={{ marginTop: 16 }}>{t('equipmentManagement.actions.create')}</NeoButton>}
          </View>
        ) : (
          visibleEquipments.map((equipment) => (
            <NeoCard key={equipment.id} style={styles.card} padding="m">
              <View style={styles.cardHeader}>
                <View>
                    <Text variant="titleMedium" style={styles.cardTitle}>{equipment.name}</Text>
                    <Text variant="bodySmall" style={styles.cardSubtitle}>{equipment.code}</Text>
                </View>
                <StatusBadge status={getStatusLabel(equipment.status)} variant={getStatusVariant(equipment.status)} />
              </View>

              <View style={styles.cardBody}>
                  <View style={styles.infoGrid}>
                      <View style={styles.infoItem}>
                          <Text style={styles.label}>{t('equipmentManagement.info.type')}</Text>
                          <Text style={styles.value}>{getTypeLabel(equipment.type)}</Text>
                      </View>
                      <View style={styles.infoItem}>
                          <Text style={styles.label}>{t('equipmentManagement.info.location')}</Text>
                          <Text style={styles.value}>{equipment.location || '-'}</Text>
                      </View>
                      <View style={styles.infoItem}>
                          <Text style={styles.label}>{t('equipmentManagement.info.model')}</Text>
                          <Text style={styles.value}>{equipment.model || '-'}</Text>
                      </View>
                      <View style={styles.infoItem}>
                          <Text style={styles.label}>{t('equipmentManagement.info.operator')}</Text>
                          <Text style={styles.value}>{equipment.operatorName || '-'}</Text>
                      </View>
                  </View>
              </View>

              <View style={styles.cardActions}>
                {canWriteEquipment && <View style={styles.leftActions}>
                  <IconButton testID={`equipment-management-edit-${equipment.id}`} icon="pencil-outline" size={20} onPress={() => openEditDialog(equipment)} />
                  <IconButton testID={`equipment-management-delete-${equipment.id}`} icon="trash-can-outline" size={20} iconColor={theme.colors.error} onPress={() => openDeleteDialog(equipment)} />
                </View>}
                <View style={styles.rightActions}>
                  {canWriteEquipment && <Menu
                    visible={showStatusMenu && selectedEquipment?.id === equipment.id}
                    onDismiss={() => { setShowStatusMenu(false); setSelectedEquipment(null); }}
                    anchor={<NeoButton testID={`equipment-management-status-${equipment.id}`} variant="ghost" size="small" onPress={() => openStatusMenu(equipment)}>{t('equipmentManagement.actions.status')}</NeoButton>}
                  >
                    <Menu.Item testID="equipment-management-status-active" onPress={() => handleUpdateStatus('active')} title={t('equipmentManagement.statusMenu.running')} />
                    <Menu.Item testID="equipment-management-status-maintenance" onPress={() => handleUpdateStatus('maintenance')} title={t('equipmentManagement.statusMenu.maintenance')} />
                    <Menu.Item testID="equipment-management-status-inactive" onPress={() => handleUpdateStatus('inactive')} title={t('equipmentManagement.statusMenu.inactive')} />
                  </Menu>}
                  <NeoButton variant="outline" size="small" onPress={() => navigation.navigate('EquipmentDetail', { equipmentId: equipment.id })}>{t('equipmentManagement.actions.detail')}</NeoButton>
                </View>
              </View>
            </NeoCard>
          ))
        )}
      </ScrollView>

      {canWriteEquipment && <FAB testID="equipment-management-fab" icon="plus" style={styles.fab} onPress={openCreateDialog} color="white" />}

      {/* Dialogs (Create/Edit/Delete) would go here, using Portal */}
      <Portal>
        <React.Fragment key={`${renderActorKey}:${canWriteEquipment ? 'write' : 'read'}`}>
        {canWriteEquipment && <>
        {showCreateDialog && <Dialog visible onDismiss={() => setShowCreateDialog(false)} style={{ backgroundColor: 'white' }}>
            <Dialog.Title>{t('equipmentManagement.dialogs.createTitle')}</Dialog.Title>
            <Dialog.ScrollArea style={{ maxHeight: 400, paddingHorizontal: 0 }}>
              <ScrollView>
                <View style={{ paddingHorizontal: 24 }}>
                  <TextInput label={t('equipmentManagement.dialogs.fields.name')} value={formData.name} onChangeText={text => setFormData({...formData, name: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.code')} value={formData.code} onChangeText={text => setFormData({...formData, code: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.model')} value={formData.model || ''} onChangeText={text => setFormData({...formData, model: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.location')} value={formData.location || ''} onChangeText={text => setFormData({...formData, location: text})} mode="outlined" style={styles.input} />

                  {/* Operator selection */}
                  <Text style={styles.fieldLabel}>{t('equipmentManagement.dialogs.fields.operator')}</Text>
                  {renderOperatorField()}

                  <TextInput label={t('equipmentManagement.dialogs.fields.notes')} value={formData.notes || ''} onChangeText={text => setFormData({...formData, notes: text})} mode="outlined" style={styles.input} multiline numberOfLines={2} />
                </View>
              </ScrollView>
            </Dialog.ScrollArea>
            <Dialog.Actions>
                <NeoButton variant="ghost" onPress={() => setShowCreateDialog(false)}>{t('equipmentManagement.dialogs.buttons.cancel')}</NeoButton>
                <NeoButton testID="equipment-management-submit-create" variant="primary" onPress={handleCreateEquipment}>{t('equipmentManagement.dialogs.buttons.create')}</NeoButton>
            </Dialog.Actions>
        </Dialog>}

        {/* Edit equipment dialog */}
        {showEditDialog && <Dialog visible onDismiss={() => setShowEditDialog(false)} style={{ backgroundColor: 'white' }}>
            <Dialog.Title>{t('equipmentManagement.dialogs.editTitle')}</Dialog.Title>
            <Dialog.ScrollArea style={{ maxHeight: 400, paddingHorizontal: 0 }}>
              <ScrollView>
                <View style={{ paddingHorizontal: 24 }}>
                  <TextInput label={t('equipmentManagement.dialogs.fields.name')} value={formData.name} onChangeText={text => setFormData({...formData, name: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.code')} value={formData.code} onChangeText={text => setFormData({...formData, code: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.model')} value={formData.model || ''} onChangeText={text => setFormData({...formData, model: text})} mode="outlined" style={styles.input} />
                  <TextInput label={t('equipmentManagement.dialogs.fields.location')} value={formData.location || ''} onChangeText={text => setFormData({...formData, location: text})} mode="outlined" style={styles.input} />

                  {/* Operator selection */}
                  <Text style={styles.fieldLabel}>{t('equipmentManagement.dialogs.fields.operator')}</Text>
                  {renderOperatorField()}

                  <TextInput label={t('equipmentManagement.dialogs.fields.notes')} value={formData.notes || ''} onChangeText={text => setFormData({...formData, notes: text})} mode="outlined" style={styles.input} multiline numberOfLines={2} />
                </View>
              </ScrollView>
            </Dialog.ScrollArea>
            <Dialog.Actions>
                <NeoButton variant="ghost" onPress={() => setShowEditDialog(false)}>{t('equipmentManagement.dialogs.buttons.cancel')}</NeoButton>
                <NeoButton testID="equipment-management-submit-edit" variant="primary" onPress={handleUpdateEquipment}>{t('equipmentManagement.dialogs.buttons.save')}</NeoButton>
            </Dialog.Actions>
        </Dialog>}

        {/* Simplified Delete Dialog */}
        {showDeleteDialog && <Dialog visible onDismiss={() => setShowDeleteDialog(false)} style={{ backgroundColor: 'white' }}>
            <Dialog.Title>{t('equipmentManagement.dialogs.deleteTitle')}</Dialog.Title>
            <Dialog.Content><Text>{t('equipmentManagement.dialogs.deleteMessage')}</Text></Dialog.Content>
            <Dialog.Actions>
                <NeoButton variant="ghost" onPress={() => setShowDeleteDialog(false)}>{t('equipmentManagement.dialogs.buttons.cancel')}</NeoButton>
                <NeoButton testID="equipment-management-confirm-delete" variant="danger" onPress={handleDeleteEquipment}>{t('equipmentManagement.dialogs.buttons.delete')}</NeoButton>
            </Dialog.Actions>
        </Dialog>}
        </>}
        </React.Fragment>
      </Portal>

    </ScreenWrapper>
  );
}

const styles = StyleSheet.create({
  searchContainer: {
      padding: 16,
      backgroundColor: theme.colors.surface,
      paddingBottom: 8,
  },
  searchbar: {
      backgroundColor: theme.colors.surfaceVariant,
      borderRadius: theme.custom.borderRadius.m,
      height: 44,
  },
  filterContainer: {
      backgroundColor: theme.colors.surface,
      paddingHorizontal: 16,
      paddingBottom: 12,
      maxHeight: 50,
  },
  filterChip: {
      marginRight: 8,
      height: 32,
  },
  content: {
      flex: 1,
  },
  scrollContent: {
      padding: 16,
      paddingBottom: 80,
  },
  centerContainer: {
      padding: 40,
      alignItems: 'center',
  },
  emptyText: {
      color: theme.colors.textSecondary,
      marginBottom: 16,
  },
  card: {
      marginBottom: 12,
  },
  cardHeader: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'flex-start',
      marginBottom: 12,
  },
  cardTitle: {
      fontWeight: '600',
      color: theme.colors.text,
  },
  cardSubtitle: {
      color: theme.colors.textTertiary,
  },
  cardBody: {
      marginBottom: 12,
  },
  infoGrid: {
      flexDirection: 'row',
      flexWrap: 'wrap',
  },
  infoItem: {
      width: '33%',
      marginBottom: 8,
  },
  label: {
      fontSize: 12,
      color: theme.colors.textSecondary,
  },
  value: {
      fontSize: 13,
      fontWeight: '500',
      color: theme.colors.text,
  },
  cardActions: {
      flexDirection: 'row',
      justifyContent: 'space-between',
      alignItems: 'center',
      borderTopWidth: 1,
      borderTopColor: theme.colors.outlineVariant,
      paddingTop: 8,
  },
  leftActions: {
      flexDirection: 'row',
  },
  rightActions: {
      flexDirection: 'row',
      gap: 8,
  },
  fab: {
      position: 'absolute',
      right: 16,
      bottom: 16,
      backgroundColor: theme.colors.primary,
      borderRadius: 16,
  },
  input: {
      marginBottom: 12,
      backgroundColor: 'white',
  },
  fieldLabel: {
      fontSize: 12,
      color: theme.colors.textSecondary,
      marginBottom: 4,
      marginTop: 4,
  },
  operatorButton: {
      marginBottom: 12,
      width: '100%',
  },
});
