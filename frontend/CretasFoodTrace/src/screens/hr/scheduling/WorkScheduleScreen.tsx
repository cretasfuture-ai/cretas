import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Modal,
  RefreshControl,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  View,
} from 'react-native';
import { ActivityIndicator, Button, Card, FAB, Text, TextInput } from 'react-native-paper';
import { Picker } from '@react-native-picker/picker';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { hrShiftApiClient, type AttendanceShift, type ShiftAssignment } from '../../../services/api/hrShiftApiClient';
import { userApiClient, type UserDTO } from '../../../services/api/userApiClient';
import { useAuthStore } from '../../../store/authStore';
import { hasModulePermission } from '../../../utils/permissionHelper';
import { formatDate as formatLocalYmd } from '../../../utils/formatters';
import { HR_THEME } from '../../../types/hrNavigation';
import type { User } from '../../../types/auth';
import { appAlert } from '../../../components/ui/AppDialog';

interface EmployeeOption {
  id: number;
  name: string;
  factoryId?: string;
  isActive: boolean;
}

interface ViewOwner {
  user: User;
  userId: number;
  factoryId: string;
  generation: number;
  date: string;
}

interface SaveSnapshot {
  owner: ViewOwner;
  employeeId: number;
  shiftId: string;
  notes: string;
}

interface ClearSnapshot {
  owner: ViewOwner;
  id: string;
  factoryId: string;
  workDate: string;
}

interface MutationToken {
  kind: 'save' | 'clear';
  owner: ViewOwner;
}

interface LoadOptions {
  refresh?: boolean;
}

export default function WorkScheduleScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('hr');
  const user = useAuthStore((state) => state.user);
  const factoryId = user?.factoryId;
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedDate, setSelectedDate] = useState(new Date());
  const [assignments, setAssignments] = useState<ShiftAssignment[]>([]);
  const [shifts, setShifts] = useState<AttendanceShift[]>([]);
  const [employees, setEmployees] = useState<EmployeeOption[]>([]);
  const [selectedEmployeeId, setSelectedEmployeeId] = useState('');
  const [selectedShiftId, setSelectedShiftId] = useState('');
  const [notes, setNotes] = useState('');
  const [formOpen, setFormOpen] = useState(false);
  const [readError, setReadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const [selfModuleAccess, setSelfModuleAccess] = useState<Awaited<ReturnType<typeof hrShiftApiClient.getSelfModuleAccess>> | null>(null);
  const actorRef = useRef<{ user: User | null; generation: number }>({ user: user || null, generation: 0 });
  const ownerRef = useRef<ViewOwner | null>(null);
  const employeesRef = useRef<EmployeeOption[]>([]);
  const shiftsRef = useRef<AttendanceShift[]>([]);
  const assignmentsRef = useRef<ShiftAssignment[]>([]);
  const formContextRef = useRef<{ owner: ViewOwner; employeeId: number; shiftId: string; notes: string } | null>(null);
  const requestSeqRef = useRef(0);
  const mutationRef = useRef<MutationToken | null>(null);
  const confirmingRef = useRef(false);
  const [dataOwnerKey, setDataOwnerKey] = useState<string | null>(null);
  const translationRef = useRef(t);
  translationRef.current = t;

  if (actorRef.current.user !== user) {
    actorRef.current = { user, generation: actorRef.current.generation + 1 };
  }

  const dateString = formatLocalYmd(selectedDate);
  const currentOwner = user && factoryId && Number.isInteger(user.id)
    ? { user, userId: user.id, factoryId, generation: actorRef.current.generation, date: dateString }
    : null;
  ownerRef.current = currentOwner;
  const ownerKey = currentOwner
    ? `${currentOwner.userId}:${currentOwner.factoryId}:${currentOwner.date}:${currentOwner.generation}`
    : 'none';
  employeesRef.current = employees;
  shiftsRef.current = shifts;
  assignmentsRef.current = assignments;
  const hasHrEmployeeWriteModule = Boolean(
    selfModuleAccess?.some((module) => module.moduleCode === 'hr_employee'
      && module.permissionModule === 'hr' && module.effectiveAllowed)
  );
  const canWrite = Boolean(
    hasHrEmployeeWriteModule
      && hasModulePermission(user, 'hr', 'write'),
  );
  const hasSchedulingChoices = shifts.length > 0 && employees.length > 0;

  const sameOwner = (left: ViewOwner, right: ViewOwner): boolean => (
    left.user === right.user
      && left.userId === right.userId
      && left.factoryId === right.factoryId
      && left.generation === right.generation
      && left.date === right.date
  );

  const isCurrentContext = (owner: ViewOwner): boolean => {
    const liveUser = useAuthStore.getState().user;
    const current = ownerRef.current;
    return Boolean(liveUser && liveUser === owner.user
      && liveUser.id === owner.userId
      && liveUser.factoryId === owner.factoryId
      && actorRef.current.user === owner.user
      && actorRef.current.generation === owner.generation
      && current && sameOwner(current, owner));
  };

  const hasSelfModule = (modules: Awaited<ReturnType<typeof hrShiftApiClient.getSelfModuleAccess>>) => (
    modules.some((module) => module.moduleCode === 'hr_employee'
      && module.permissionModule === 'hr' && module.effectiveAllowed)
  );

  useEffect(() => {
    setRefreshing(false);
    setFormOpen(false);
    formContextRef.current = null;
    setReadError(null);
    setErrorOwnerKey(null);
    setDataOwnerKey(null);
    if (mutationRef.current && (!currentOwner || !sameOwner(mutationRef.current.owner, currentOwner))) {
      mutationRef.current = null;
      confirmingRef.current = false;
      setSaving(false);
    }
  }, [ownerKey]);

  const loadData = useCallback(async (capturedOwner: ViewOwner | null, options: LoadOptions = {}) => {
    const owner = capturedOwner;
    const liveUser = useAuthStore.getState().user;
    if (!owner || liveUser !== owner.user || !isCurrentContext(owner)) {
      if (!owner) {
        setReadError(translationRef.current('scheduling.workSchedule.noFactory'));
        setErrorOwnerKey('none');
        setAssignments([]);
        setShifts([]);
        setEmployees([]);
        setSelfModuleAccess(null);
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
      setDataOwnerKey(null);
      setLoading(true);
      if (options.refresh) setRefreshing(true);
      const moduleAccess = await hrShiftApiClient.getSelfModuleAccess(owner.userId, owner.factoryId);
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      setSelfModuleAccess(moduleAccess);
      const currentLiveUser = useAuthStore.getState().user;
      if (!hasSelfModule(moduleAccess) || !hasModulePermission(currentLiveUser, 'hr', 'read')) {
        throw new Error(translationRef.current('scheduling.workSchedule.permissionRequired'));
      }
      const [assignmentRows, activeShifts, employeePage] = await Promise.all([
        hrShiftApiClient.listAssignments({ startDate: owner.date, endDate: owner.date, factoryId: owner.factoryId }),
        hrShiftApiClient.listActiveShifts(owner.factoryId),
        userApiClient.getUsers({ factoryId: owner.factoryId, page: 1, size: 100 }),
      ]);
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      const scopedAssignments = assignmentRows.filter((assignment) => assignment.factoryId === owner.factoryId && assignment.workDate === owner.date);
      const scopedShifts = activeShifts.filter((shift) => shift.factoryId === owner.factoryId && shift.isActive === true);
      const scopedEmployees = (employeePage.content || []).filter((employee: UserDTO) => Number.isInteger(employee.id) && employee.id > 0
        && employee.isActive === true
        && employee.factoryId === owner.factoryId).map((employee: UserDTO) => ({
        id: employee.id,
        name: employee.fullName || employee.username || String(employee.id),
        factoryId: employee.factoryId,
        isActive: true,
      }));
      setAssignments(scopedAssignments);
      setShifts(scopedShifts);
      setEmployees(scopedEmployees);
      setDataOwnerKey(`${owner.userId}:${owner.factoryId}:${owner.date}:${owner.generation}`);
    } catch (error) {
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      setReadError(error instanceof Error ? error.message : translationRef.current('scheduling.workSchedule.readFailed'));
      setErrorOwnerKey(String(owner.userId) + ':' + owner.factoryId + ':' + owner.date + ':' + owner.generation);
      setAssignments([]);
      setShifts([]);
      setEmployees([]);
      setSelfModuleAccess(null);
      setDataOwnerKey(String(owner.userId) + ':' + owner.factoryId + ':' + owner.date + ':' + owner.generation);
    } finally {
      if (requestSequence === requestSeqRef.current && isCurrentContext(owner)) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  useFocusEffect(useCallback(() => {
    void loadData(currentOwner);
  }, [loadData, ownerKey]));

  const changeDate = (days: number) => {
    const nextDate = new Date(selectedDate);
    nextDate.setDate(nextDate.getDate() + days);
    setSelectedDate(nextDate);
    setLoading(true);
  };

  const openForm = () => {
    if (!canWrite) {
      appAlert(t('scheduling.workSchedule.permissionTitle'), t('scheduling.workSchedule.permissionRequired'));
      return;
    }
    if (shifts.length === 0 || employees.length === 0) {
      appAlert(t('scheduling.workSchedule.noShiftTitle'), t('scheduling.workSchedule.noShiftConfigured'));
      return;
    }
    setSelectedEmployeeId(employees[0] ? String(employees[0].id) : '');
    setSelectedShiftId(shifts[0]?.id || '');
    setNotes('');
    const owner = currentOwner;
    if (owner) {
      formContextRef.current = {
        owner,
        employeeId: employees[0]?.id || 0,
        shiftId: shifts[0]?.id || '',
        notes: '',
      };
    }
    setFormOpen(true);
  };

  const guardLiveActor = async (expectedOwner: ViewOwner, snapshot?: SaveSnapshot | ClearSnapshot) => {
    const owner = currentOwner;
    const liveUser = useAuthStore.getState().user;
    if (!owner || !sameOwner(owner, expectedOwner) || !isCurrentContext(expectedOwner)
      || !liveUser || !hasModulePermission(liveUser, 'hr', 'write')) {
      throw new Error(t('scheduling.workSchedule.permissionChanged'));
    }
    if (snapshot && 'employeeId' in snapshot) {
      const form = formContextRef.current;
      if (!form || !sameOwner(form.owner, expectedOwner)
        || form.employeeId !== snapshot.employeeId || form.shiftId !== snapshot.shiftId
        || form.notes !== snapshot.notes) {
        throw new Error(t('scheduling.workSchedule.permissionChanged'));
      }
      const employee = employeesRef.current.find((item) => item.id === snapshot.employeeId);
      const shift = shiftsRef.current.find((item) => item.id === snapshot.shiftId);
      if (!employee || employee.id <= 0 || !employee.isActive
        || employee.factoryId !== expectedOwner.factoryId
        || !shift || shift.factoryId !== expectedOwner.factoryId || shift.isActive !== true) {
        throw new Error(t('scheduling.workSchedule.invalidForm'));
      }
    } else if (snapshot) {
      const latest = assignmentsRef.current.find((item) => String(item.id) === snapshot.id);
      if (!latest || latest.factoryId !== expectedOwner.factoryId || latest.workDate !== expectedOwner.date
        || !String(latest.id).trim()) {
        throw new Error(t('scheduling.workSchedule.permissionChanged'));
      }
    }
    const liveAccess = await hrShiftApiClient.getSelfModuleAccess(expectedOwner.userId, expectedOwner.factoryId);
    const liveAfterAccess = useAuthStore.getState().user;
    if (!hasSelfModule(liveAccess) || !liveAfterAccess
      || !hasModulePermission(liveAfterAccess, 'hr', 'read')
      || !hasModulePermission(liveAfterAccess, 'hr', 'write')) {
      throw new Error(t('scheduling.workSchedule.permissionChanged'));
    }
    if (!isCurrentContext(expectedOwner)) throw new Error(t('scheduling.workSchedule.permissionChanged'));
    return expectedOwner.factoryId;
  };

  const saveAssignment = () => {
    if (confirmingRef.current || mutationRef.current) return;
    const owner = currentOwner;
    if (!owner) {
      appAlert(t('scheduling.workSchedule.permissionTitle'), t('scheduling.workSchedule.permissionChanged'));
      return;
    }
    const form = formContextRef.current;
    const employeeId = form?.employeeId || Number(selectedEmployeeId);
    const shiftId = form?.shiftId || selectedShiftId;
    const existing = assignmentsRef.current.find((assignment) => assignment.userId === employeeId);
    const selectedEmployee = employeesRef.current.find((employee) => employee.id === employeeId);
    const selectedShift = shiftsRef.current.find((shift) => shift.id === shiftId);
    if (!employeeId || !selectedShiftId || !selectedEmployee || !selectedShift) {
      appAlert(t('scheduling.workSchedule.invalidFormTitle'), t('scheduling.workSchedule.invalidForm'));
      return;
    }
    const message = existing
      ? t('scheduling.workSchedule.overwriteConfirm', {
        employee: selectedEmployee.name,
        date: owner.date,
        shift: selectedShift.shiftName,
      })
      : t('scheduling.workSchedule.saveConfirm', {
        employee: selectedEmployee.name,
        date: owner.date,
        shift: selectedShift.shiftName,
      });
    const snapshot: SaveSnapshot = { owner, employeeId, shiftId, notes: form?.notes ?? notes.trim() };
    confirmingRef.current = true;
    appAlert(t('scheduling.workSchedule.confirmTitle'), message, [
      { text: t('common.cancel'), style: 'cancel', onPress: () => { confirmingRef.current = false; } },
      { text: t('common.confirm'), onPress: () => { confirmingRef.current = false; void performSave(snapshot); } },
    ]);
  };

  const performSave = async (snapshot: SaveSnapshot) => {
    if (mutationRef.current) return;
    const token: MutationToken = { kind: 'save', owner: snapshot.owner };
    mutationRef.current = token;
    try {
      const liveFactoryId = await guardLiveActor(snapshot.owner, snapshot);
      setSaving(true);
      const response = await hrShiftApiClient.assign({
        userId: snapshot.employeeId,
        workDate: snapshot.owner.date,
        shiftId: snapshot.shiftId,
        ...(snapshot.notes ? { notes: snapshot.notes } : {}),
      }, liveFactoryId || undefined);
      if (!response.success) throw new Error(response.message || t('scheduling.workSchedule.saveFailed'));
      if (!isCurrentContext(snapshot.owner)) return;
      setFormOpen(false);
      formContextRef.current = null;
      await loadData(snapshot.owner);
      if (!isCurrentContext(snapshot.owner)) return;
      appAlert(t('common.success'), t('scheduling.workSchedule.saveSuccess'));
    } catch (error) {
      if (isCurrentContext(snapshot.owner)) {
        appAlert(t('scheduling.workSchedule.saveFailedTitle'), error instanceof Error
          ? error.message : t('scheduling.workSchedule.saveFailed'));
      }
    } finally {
      if (mutationRef.current === token) mutationRef.current = null;
      if (isCurrentContext(snapshot.owner)) setSaving(false);
    }
  };

  const clearAssignment = (assignment: ShiftAssignment) => {
    if (confirmingRef.current || mutationRef.current) return;
    const owner = currentOwner;
    if (!owner) return;
    const employee = employeesRef.current.find((item) => item.id === assignment.userId);
    const snapshot: ClearSnapshot = {
      owner,
      id: String(assignment.id),
      factoryId: assignment.factoryId,
      workDate: assignment.workDate,
    };
    confirmingRef.current = true;
    appAlert(
      t('scheduling.workSchedule.clearTitle'),
      t('scheduling.workSchedule.clearConfirm', { employee: employee?.name || assignment.userId, date: assignment.workDate }),
      [
        { text: t('common.cancel'), style: 'cancel', onPress: () => { confirmingRef.current = false; } },
        { text: t('common.delete'), style: 'destructive', onPress: () => { confirmingRef.current = false; void performClear(snapshot); } },
      ]
    );
  };

  const performClear = async (snapshot: ClearSnapshot) => {
    if (mutationRef.current) return;
    const token: MutationToken = { kind: 'clear', owner: snapshot.owner };
    mutationRef.current = token;
    try {
      const liveFactoryId = await guardLiveActor(snapshot.owner, snapshot);
      setSaving(true);
      const response = await hrShiftApiClient.remove(snapshot.id, liveFactoryId);
      if (!response.success) throw new Error(response.message || t('scheduling.workSchedule.clearFailed'));
      if (!isCurrentContext(snapshot.owner)) return;
      await loadData(snapshot.owner);
      if (!isCurrentContext(snapshot.owner)) return;
      appAlert(t('common.success'), t('scheduling.workSchedule.clearSuccess'));
    } catch (error) {
      if (isCurrentContext(snapshot.owner)) {
        appAlert(t('scheduling.workSchedule.clearFailedTitle'), error instanceof Error
          ? error.message : t('scheduling.workSchedule.clearFailed'));
      }
    } finally {
      if (mutationRef.current === token) mutationRef.current = null;
      if (isCurrentContext(snapshot.owner)) setSaving(false);
    }
  };

  const shiftById = new Map(shifts.map((shift) => [shift.id, shift]));
  const employeeById = new Map(employees.map((employee) => [employee.id, employee]));

  if (loading || dataOwnerKey !== ownerKey) {
    return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={HR_THEME.primary} /></View>;
  }

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
          <MaterialCommunityIcons name="arrow-left" size={24} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('scheduling.workSchedule.title')}</Text>
        <View style={styles.placeholder} />
      </View>

      <View style={styles.dateSelector}>
        <TouchableOpacity onPress={() => changeDate(-1)} style={styles.dateArrow} accessibilityLabel={t('scheduling.workSchedule.previousDay')}>
          <MaterialCommunityIcons name="chevron-left" size={28} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
        <Text style={styles.dateText}>{dateString}</Text>
        <TouchableOpacity onPress={() => changeDate(1)} style={styles.dateArrow} accessibilityLabel={t('scheduling.workSchedule.nextDay')}>
          <MaterialCommunityIcons name="chevron-right" size={28} color={HR_THEME.textPrimary} />
        </TouchableOpacity>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { void loadData(currentOwner, { refresh: true }); }} />}
      >
        {readError && errorOwnerKey === ownerKey && (
          <Card style={styles.noticeCard}>
            <Card.Content>
              <Text style={styles.errorText}>{readError}</Text>
              <Button onPress={() => { void loadData(currentOwner); }}>{t('common.retry')}</Button>
            </Card.Content>
          </Card>
        )}
        {shifts.length === 0 && !readError && (
          <Card style={styles.noticeCard}>
            <Card.Content>
              <Text style={styles.noticeTitle}>{t('scheduling.workSchedule.noShiftConfigured')}</Text>
              <Text style={styles.noticeText}>{t('scheduling.workSchedule.noShiftNextStep')}</Text>
            </Card.Content>
          </Card>
        )}
        {shifts.length > 0 && employees.length === 0 && !readError && (
          <Card style={styles.noticeCard}>
            <Card.Content>
              <Text style={styles.noticeTitle}>{t('scheduling.workSchedule.employee')}</Text>
              <Text style={styles.noticeText}>{t('scheduling.workSchedule.chooseEmployee')}</Text>
            </Card.Content>
          </Card>
        )}
        {assignments.length === 0 && shifts.length > 0 && !readError ? (
          <View style={styles.emptyContainer}>
            <MaterialCommunityIcons name="calendar-blank-outline" size={64} color={HR_THEME.textMuted} />
            <Text style={styles.emptyText}>{t('scheduling.workSchedule.empty')}</Text>
          </View>
        ) : assignments.map((assignment) => {
          const shift = shiftById.get(assignment.shiftId);
          const employee = employeeById.get(assignment.userId);
          return (
            <Card key={assignment.id} style={styles.assignmentCard}>
              <Card.Content>
                <View style={styles.assignmentHeader}>
                  <View style={styles.assignmentMain}>
                    <Text style={styles.employeeName}>{employee?.name || `#${assignment.userId}`}</Text>
                    <Text style={styles.shiftName}>{shift?.shiftName || assignment.shiftId}</Text>
                  </View>
                  {canWrite && <Button compact onPress={() => clearAssignment(assignment)} disabled={saving}>{t('common.delete')}</Button>}
                </View>
                <Text style={styles.shiftTime}>
                  {shift ? `${shift.startTime} - ${shift.endTime}` : t('scheduling.workSchedule.unknownShift')}
                </Text>
                <Text style={styles.statusText}>{assignment.status || t('scheduling.workSchedule.statusUnknown')}</Text>
              </Card.Content>
            </Card>
          );
        })}
        <View style={styles.bottomSpacer} />
      </ScrollView>

      {canWrite && <FAB
        icon="plus"
        accessibilityLabel={t('scheduling.workSchedule.addTitle')}
        style={styles.fab}
        onPress={openForm}
        color="#fff"
        disabled={saving || !hasSchedulingChoices}
      />}

      <Modal visible={formOpen} transparent animationType="slide" onRequestClose={() => !saving && setFormOpen(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>{t('scheduling.workSchedule.addTitle')}</Text>
            <Text style={styles.fieldLabel}>{t('scheduling.workSchedule.employee')}</Text>
            <View style={styles.pickerWrap}>
              <Picker selectedValue={selectedEmployeeId} onValueChange={(value) => {
                setSelectedEmployeeId(value);
                if (formContextRef.current) formContextRef.current = { ...formContextRef.current, employeeId: Number(value) };
              }} enabled={!saving}>
                <Picker.Item label={t('scheduling.workSchedule.chooseEmployee')} value="" />
                {employees.map((employee) => <Picker.Item key={employee.id} label={employee.name} value={String(employee.id)} />)}
              </Picker>
            </View>
            <Text style={styles.fieldLabel}>{t('scheduling.workSchedule.shift')}</Text>
            <View style={styles.pickerWrap}>
              <Picker selectedValue={selectedShiftId} onValueChange={(value) => {
                setSelectedShiftId(value);
                if (formContextRef.current) formContextRef.current = { ...formContextRef.current, shiftId: value };
              }} enabled={!saving}>
                <Picker.Item label={t('scheduling.workSchedule.chooseShift')} value="" />
                {shifts.map((shift) => <Picker.Item key={shift.id} label={`${shift.shiftName} (${shift.startTime}-${shift.endTime})`} value={shift.id} />)}
              </Picker>
            </View>
            <TextInput label={t('scheduling.workSchedule.notes')} value={notes} onChangeText={(value) => {
              setNotes(value);
              if (formContextRef.current) formContextRef.current = { ...formContextRef.current, notes: value.trim() };
            }} disabled={saving} />
            <View style={styles.modalActions}>
              <Button onPress={() => setFormOpen(false)} disabled={saving}>{t('common.cancel')}</Button>
              <Button mode="contained" onPress={saveAssignment} loading={saving} disabled={saving}>{t('common.save')}</Button>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: HR_THEME.background },
  loadingContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: HR_THEME.background },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingVertical: 12, backgroundColor: HR_THEME.cardBackground, borderBottomWidth: 1, borderBottomColor: HR_THEME.border },
  backBtn: { padding: 4 },
  headerTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary },
  placeholder: { width: 32 },
  dateSelector: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', padding: 12, backgroundColor: HR_THEME.cardBackground },
  dateArrow: { padding: 6 },
  dateText: { fontSize: 16, fontWeight: '600', color: HR_THEME.textPrimary },
  content: { flex: 1, padding: 16 },
  noticeCard: { marginBottom: 16, backgroundColor: HR_THEME.cardBackground },
  noticeTitle: { fontWeight: '600', color: HR_THEME.warning },
  noticeText: { marginTop: 8, color: HR_THEME.textSecondary },
  errorText: { color: HR_THEME.danger },
  emptyContainer: { alignItems: 'center', paddingVertical: 80 },
  emptyText: { marginTop: 16, color: HR_THEME.textMuted },
  assignmentCard: { marginBottom: 12, backgroundColor: HR_THEME.cardBackground },
  assignmentHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  assignmentMain: { flex: 1 },
  employeeName: { fontSize: 16, fontWeight: '600', color: HR_THEME.textPrimary },
  shiftName: { marginTop: 4, color: HR_THEME.primary },
  shiftTime: { marginTop: 8, color: HR_THEME.textSecondary },
  statusText: { marginTop: 6, color: HR_THEME.textMuted },
  bottomSpacer: { height: 100 },
  fab: { position: 'absolute', right: 16, bottom: 20, backgroundColor: HR_THEME.primary },
  modalBackdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.45)' },
  modalCard: { padding: 20, borderTopLeftRadius: 18, borderTopRightRadius: 18, backgroundColor: HR_THEME.cardBackground },
  modalTitle: { fontSize: 18, fontWeight: '600', color: HR_THEME.textPrimary, marginBottom: 12 },
  fieldLabel: { marginTop: 8, marginBottom: 4, color: HR_THEME.textSecondary },
  pickerWrap: { borderWidth: 1, borderColor: HR_THEME.border, borderRadius: 8, overflow: 'hidden' },
  modalActions: { flexDirection: 'row', justifyContent: 'flex-end', marginTop: 16 },
});
