/**
 * 人员详情屏幕
 *
 * 功能:
 * - 员工基本信息 (头像/姓名/工号/状态)
 * - 工作信息 (车间/职位/入职日期/合同类型)
 * - 技能等级展示
 * - 绩效指标 (效率/出勤/质量)
 * - 近期任务记录
 * - 操作按钮 (调动/编辑/停用)
 *
 * @version 1.0.0
 * @since 2025-12-28
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { useNavigation, useRoute, RouteProp, CommonActions } from '@react-navigation/native';
import { Ionicons } from '@expo/vector-icons';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator } from 'react-native-paper';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { personnelApiClient } from '../../../services/api/personnelApiClient';
import { hrApiClient } from '../../../services/api/hrApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { isAxiosError } from 'axios';
import { useAuthStore } from '../../../store/authStore';
import { hasModulePermission } from '../../../utils/permissionHelper';
import type { DispatcherEmployee } from '../../../types/dispatcher';
import { formatDate } from '../../../utils/formatters';
import type { User } from '../../../types/auth';

// 调度员主题色
const DISPATCHER_THEME = {
  primary: '#722ed1',
  secondary: '#a18cd1',
  accent: '#fbc2eb',
  success: '#52c41a',
  warning: '#fa8c16',
  danger: '#ff4d4f',
  info: '#1890ff',
  background: '#f5f5f5',
  cardBackground: '#ffffff',
  border: '#d9d9d9',
};

// 类型定义
interface Skill {
  name: string;
  level: number;
  maxLevel: number;
}

interface PerformanceMetric {
  label: string;
  value: number | null;
  trend: 'up' | 'down' | 'stable';
  trendValue: string;
}

interface RecentTask {
  id: string;
  name: string;
  date: string;
  status: 'completed' | 'in_progress' | 'cancelled';
  hours: number;
}

interface Employee {
  id: string;
  name: string;
  avatar: string;
  employeeCode: string;
  status: 'working' | 'idle' | 'off_duty' | 'on_leave';
  workshop: string;
  position: string;
  hireDate: string;
  tenure: string;
  contractType: 'full_time' | 'part_time' | 'temporary' | 'dispatch';
  contractEndDate?: string;
  phone: string;
  skills: Skill[];
  performance: PerformanceMetric[];
  weeklyHours: number;
  maxWeeklyHours: number;
  overtimeHours: number;
  recentTasks: RecentTask[];
}

// Route params type
type PersonnelDetailRouteParams = {
  PersonnelDetail: {
    personnelId: string;
  };
};

const unknownPerformance: PerformanceMetric[] = [
  { label: '工作效率', value: null, trend: 'stable', trendValue: '-' },
  { label: '出勤率', value: null, trend: 'stable', trendValue: '-' },
  { label: '质量评分', value: null, trend: 'stable', trendValue: '-' },
  { label: '协作评分', value: null, trend: 'stable', trendValue: '-' },
];

interface PersonnelOwner {
  user: User | null;
  userId?: number;
  factoryId?: string;
  generation: number;
  personnelId?: string;
}

interface LoadOptions {
  refresh?: boolean;
}

export default function PersonnelDetailScreen() {
  const { t } = useTranslation('dispatcher');
  const navigation = useNavigation();
  const route = useRoute<RouteProp<PersonnelDetailRouteParams, 'PersonnelDetail'>>();
  const { user } = useAuthStore();
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [employee, setEmployee] = useState<Employee | null>(null);
  const [readError, setReadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const [performanceError, setPerformanceError] = useState<string | null>(null);
  const actorRef = useRef<{ user: User | null; generation: number }>({ user: user || null, generation: 0 });
  const ownerRef = useRef<PersonnelOwner | null>(null);
  const requestSeqRef = useRef(0);
  const [dataOwnerKey, setDataOwnerKey] = useState<string | null>(null);
  const translationRef = useRef(t);
  translationRef.current = t;

  // Get employee ID from route params
  const personnelId = route.params?.personnelId;

  if (actorRef.current.user !== user) {
    actorRef.current = { user, generation: actorRef.current.generation + 1 };
  }

  const currentOwner: PersonnelOwner = {
      user: user || null,
      userId: user?.id,
      factoryId: user?.factoryId,
      generation: actorRef.current.generation,
      personnelId,
  };
  ownerRef.current = currentOwner;
  const ownerKey = `${currentOwner.userId || 'none'}:${currentOwner.factoryId || ''}:${currentOwner.personnelId || ''}:${currentOwner.generation}`;

  useEffect(() => {
    setReadError(null);
    setErrorOwnerKey(null);
    setPerformanceError(null);
    setEmployee(null);
    setDataOwnerKey(null);
    setLoading(true);
    setRefreshing(false);
  }, [ownerKey]);

  const isCurrentContext = (owner: PersonnelOwner): boolean => {
    const liveUser = useAuthStore.getState().user;
    const current = ownerRef.current;
    return Boolean(current && liveUser === owner.user && liveUser?.id === owner.userId
      && current.user === owner.user && current.userId === owner.userId
      && current.generation === owner.generation && current.personnelId === owner.personnelId
      && liveUser?.factoryId === owner.factoryId);
  };

  // Calculate tenure from hire date
  const calculateTenure = (hireDate: string): string => {
    const hire = new Date(hireDate);
    const now = new Date();
    const years = Math.floor((now.getTime() - hire.getTime()) / (365.25 * 24 * 60 * 60 * 1000));
    const months = Math.floor(((now.getTime() - hire.getTime()) % (365.25 * 24 * 60 * 60 * 1000)) / (30 * 24 * 60 * 60 * 1000));
    if (years > 0) {
      return `${years}年${months}个月`;
    }
    return `${months}个月`;
  };

  const loadData = useCallback(async (capturedOwner: PersonnelOwner | null, options: LoadOptions = {}) => {
    const owner = capturedOwner;
    const liveUser = useAuthStore.getState().user;
    if (!owner || !owner.personnelId || !liveUser || liveUser !== owner.user || !isCurrentContext(owner)) {
      if (!owner) {
        setReadError('读取员工信息失败');
        setErrorOwnerKey('none');
        setEmployee(null);
        setDataOwnerKey('none');
        setLoading(false);
      }
      return;
    }
    const requestSequence = ++requestSeqRef.current;

    const employeeId = parseInt(owner.personnelId, 10);
    if (!Number.isInteger(employeeId) || employeeId <= 0) {
      setReadError('员工编号无效');
      setErrorOwnerKey(String(owner.userId || 'none') + ':' + (owner.factoryId || '') + ':' + (owner.personnelId || '') + ':' + owner.generation);
      setEmployee(null);
      setDataOwnerKey(String(owner.userId || 'none') + ':' + (owner.factoryId || '') + ':' + (owner.personnelId || '') + ':' + owner.generation);
      setLoading(false);
      return;
    }

    try {
      setReadError(null);
      setErrorOwnerKey(null);
      setPerformanceError(null);
      setEmployee(null);
      setDataOwnerKey(null);
      setLoading(true);
      if (options.refresh) setRefreshing(true);

      const detailRes = await schedulingApiClient.getEmployeeDetail(employeeId, owner.factoryId);
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      if (!detailRes.success || !detailRes.data) throw new Error('未找到该员工信息');

      const empData = detailRes.data;
      let status: Employee['status'] = 'off_duty';
      const empStatus = empData.status?.toLowerCase();
      if (empStatus === 'working' || empStatus === 'on_duty') status = 'working';
      else if (empStatus === 'idle' || empStatus === 'available') status = 'idle';
      else if (empStatus === 'on_leave' || empStatus === 'leave') status = 'on_leave';

      let contractType: Employee['contractType'] = 'full_time';
      const hireType = empData.hireType?.toLowerCase();
      if (hireType === 'temporary') contractType = 'temporary';
      else if (hireType === 'dispatch') contractType = 'dispatch';
      else if (hireType === 'part_time') contractType = 'part_time';

      let skills: Skill[] = [];
      if (empData.skillLevels) {
        if (Array.isArray(empData.skillLevels)) {
          skills = (empData.skillLevels as Array<{ skillName?: string; name?: string; level?: number }>).map((skill) => ({
            name: skill.skillName || skill.name || '未知技能',
            level: typeof skill.level === 'number' ? skill.level : 1,
            maxLevel: 5,
          }));
        } else {
          skills = Object.entries(empData.skillLevels).map(([name, level]) => ({
            name,
            level: typeof level === 'number' ? level : 1,
            maxLevel: 5,
          }));
        }
      }

      const employeeDetail: Employee = {
        id: String(empData.id),
        name: empData.fullName || empData.username || '未知',
        avatar: (empData.fullName || empData.username || '未知').charAt(0),
        employeeCode: empData.employeeCode || String(empData.id),
        status,
        workshop: empData.workshopName || empData.departmentName || '未分配',
        position: empData.position || '员工',
        hireDate: empData.hireDate ?? formatDate(new Date()),
        tenure: calculateTenure(empData.hireDate || new Date().toISOString()),
        contractType,
        phone: empData.phone ? empData.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : '未设置',
        skills,
        performance: unknownPerformance.map((metric) => ({ ...metric })),
        weeklyHours: empData.todayWorkHours ?? 0,
        maxWeeklyHours: 40,
        overtimeHours: 0,
        recentTasks: [],
      };

      if (!isCurrentContext(owner)) return;
      setEmployee(employeeDetail);
      setDataOwnerKey(`${owner.userId || 'none'}:${owner.factoryId || ''}:${owner.personnelId || ''}:${owner.generation}`);
      setLoading(false);

      try {
        const moduleAccess = await hrShiftApiClient.getSelfModuleAccess(owner.userId || 0, owner.factoryId);
        if (!isCurrentContext(owner)) return;
        const latestUser = useAuthStore.getState().user;
        const canReadPerformance = Boolean(latestUser && hasModulePermission(latestUser, 'hr', 'read')
          && moduleAccess.some((module) => module.moduleCode === 'hr_employee'
            && module.permissionModule === 'hr' && module.effectiveAllowed));
        if (!canReadPerformance) throw new Error('无权读取员工绩效');
        const perfData = await hrApiClient.getEmployeePerformance(employeeId, { factoryId: owner.factoryId });
        if (!isCurrentContext(owner)) return;
        if (perfData) {
          setEmployee({ ...employeeDetail, performance: [
            { label: '工作效率', value: perfData.efficiency ?? null, trend: 'stable', trendValue: '-' },
            { label: '出勤率', value: perfData.attendance ?? null, trend: 'stable', trendValue: '-' },
            { label: '质量评分', value: null, trend: 'stable', trendValue: '-' },
            { label: '协作评分', value: null, trend: 'stable', trendValue: '-' },
          ] });
        }
      } catch (perfError) {
        if (isCurrentContext(owner)) {
          setPerformanceError(perfError instanceof Error ? perfError.message : '读取员工绩效失败');
        }
      }
    } catch (error) {
      if (requestSequence !== requestSeqRef.current || !isCurrentContext(owner)) return;
      console.error('Failed to load employee detail:', error);
      setReadError(error instanceof Error ? error.message : '读取员工信息失败');
      setErrorOwnerKey(String(owner.userId || 'none') + ':' + (owner.factoryId || '') + ':' + (owner.personnelId || '') + ':' + owner.generation);
      if (isAxiosError(error) && error.response?.status === 404) Alert.alert('错误', '未找到该员工信息');
      setEmployee(null);
      setDataOwnerKey(String(owner.userId || 'none') + ':' + (owner.factoryId || '') + ':' + (owner.personnelId || '') + ':' + owner.generation);
    } finally {
      if (requestSequence === requestSeqRef.current && isCurrentContext(owner)) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, []);

  const onRefresh = useCallback(async () => {
    void loadData(currentOwner, { refresh: true });
  }, [loadData, ownerKey]);

  // Load data on mount
  React.useEffect(() => {
    void loadData(currentOwner);
  }, [loadData, ownerKey]);

  if (loading || dataOwnerKey !== ownerKey || (!employee && !readError)) {
    return <View style={styles.loadingContainer}><ActivityIndicator size="large" color={DISPATCHER_THEME.primary} /></View>;
  }

  if (!employee) {
    return (
      <SafeAreaView style={styles.container} edges={['top']}>
        <View style={styles.errorCard}>
          <Text style={styles.errorText}>{readError && errorOwnerKey === ownerKey ? readError : '读取员工信息失败'}</Text>
          <TouchableOpacity onPress={() => { void loadData(currentOwner); }}>
            <Text style={styles.retryText}>{t('common.retry')}</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  const handleGoBack = () => {
    navigation.goBack();
  };

  const handleTransfer = () => {
    navigation.dispatch(
      CommonActions.navigate('PersonnelTransfer', {
        employeeId: employee.id,
        employeeName: employee.name,
        currentWorkshop: employee.workshop,
      })
    );
  };

  const handleEdit = () => {
    Alert.alert(t('common.info'), t('personnelDetailScreen.editInDevelopment'));
  };

  const handleDisable = () => {
    Alert.alert(
      t('personnelDetailScreen.confirmDisable'),
      t('personnelDetailScreen.disableWarning', { name: employee.name }),
      [
        { text: t('common.cancel'), style: 'cancel' },
        { text: t('personnelDetailScreen.confirmDisableButton'), style: 'destructive', onPress: () => {} },
      ]
    );
  };

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'working': return DISPATCHER_THEME.info;
      case 'idle': return DISPATCHER_THEME.success;
      case 'off_duty': return '#999';
      case 'on_leave': return DISPATCHER_THEME.warning;
      default: return '#999';
    }
  };

  const getStatusText = (status: string) => {
    switch (status) {
      case 'working': return t('personnelDetailScreen.status.working');
      case 'idle': return t('personnelDetailScreen.status.idle');
      case 'off_duty': return t('personnelDetailScreen.status.offDuty');
      case 'on_leave': return t('personnelDetailScreen.status.onLeave');
      default: return status;
    }
  };

  const getContractTypeText = (type: string) => {
    switch (type) {
      case 'full_time': return t('personnelDetailScreen.contractType.fullTime');
      case 'part_time': return t('personnelDetailScreen.contractType.partTime');
      case 'temporary': return t('personnelDetailScreen.contractType.temporary');
      case 'dispatch': return t('personnelDetailScreen.contractType.dispatch');
      default: return type;
    }
  };

  const getTaskStatusColor = (status: string) => {
    switch (status) {
      case 'completed': return DISPATCHER_THEME.success;
      case 'in_progress': return DISPATCHER_THEME.info;
      case 'cancelled': return '#999';
      default: return '#999';
    }
  };

  const getTaskStatusText = (status: string) => {
    switch (status) {
      case 'completed': return t('personnelDetailScreen.taskStatus.completed');
      case 'in_progress': return t('personnelDetailScreen.taskStatus.inProgress');
      case 'cancelled': return t('personnelDetailScreen.taskStatus.cancelled');
      default: return status;
    }
  };

  const renderSkillBar = (skill: Skill) => (
    <View key={skill.name} style={styles.skillItem}>
      <View style={styles.skillHeader}>
        <Text style={styles.skillName}>{skill.name}</Text>
        <Text style={styles.skillLevel}>Lv.{skill.level}</Text>
      </View>
      <View style={styles.skillBarBg}>
        <View
          style={[
            styles.skillBarFill,
            { width: `${(skill.level / skill.maxLevel) * 100}%` },
          ]}
        />
      </View>
    </View>
  );

  const renderPerformanceMetric = (metric: PerformanceMetric) => (
    <View key={metric.label} style={styles.metricItem}>
      <View style={styles.metricHeader}>
        <Text style={styles.metricLabel}>{metric.label}</Text>
        <View style={styles.metricTrend}>
          <Ionicons
            name={
              metric.trend === 'up'
                ? 'arrow-up'
                : metric.trend === 'down'
                ? 'arrow-down'
                : 'remove'
            }
            size={12}
            color={
              metric.trend === 'up'
                ? DISPATCHER_THEME.success
                : metric.trend === 'down'
                ? DISPATCHER_THEME.danger
                : '#999'
            }
          />
          <Text
            style={[
              styles.metricTrendValue,
              {
                color:
                  metric.trend === 'up'
                    ? DISPATCHER_THEME.success
                    : metric.trend === 'down'
                    ? DISPATCHER_THEME.danger
                    : '#999',
              },
            ]}
          >
            {metric.trendValue}
          </Text>
        </View>
      </View>
      <View style={styles.metricBarBg}>
        <View
          style={[
            styles.metricBarFill,
            {
              width: `${metric.value ?? 0}%`,
              backgroundColor:
                metric.value === null
                ? DISPATCHER_THEME.border
                : metric.value >= 90
                  ? DISPATCHER_THEME.success
                  : metric.value >= 70
                  ? DISPATCHER_THEME.warning
                  : DISPATCHER_THEME.danger,
            },
          ]}
        />
      </View>
      <Text style={styles.metricValue}>{metric.value === null ? '未知' : `${metric.value}%`}</Text>
    </View>
  );

  const renderRecentTask = (task: RecentTask) => (
    <View key={task.id} style={styles.taskItem}>
      <View style={styles.taskLeft}>
        <Text style={styles.taskName}>{task.name}</Text>
        <Text style={styles.taskDate}>{task.date}</Text>
      </View>
      <View style={styles.taskRight}>
        <Text style={[styles.taskStatus, { color: getTaskStatusColor(task.status) }]}>
          {getTaskStatusText(task.status)}
        </Text>
        {task.hours > 0 && (
          <Text style={styles.taskHours}>{task.hours}h</Text>
        )}
      </View>
    </View>
  );

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* 头部 */}
      <LinearGradient
        colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.header}
      >
        <TouchableOpacity style={styles.backButton} onPress={handleGoBack}>
          <Ionicons name="arrow-back" size={24} color="#fff" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('personnelDetailScreen.title')}</Text>
        <TouchableOpacity onPress={handleEdit}>
          <Ionicons name="create-outline" size={24} color="#fff" />
        </TouchableOpacity>
      </LinearGradient>

      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {readError && errorOwnerKey === ownerKey && (
          <View style={styles.errorCard}>
            <Text style={styles.errorText}>{readError}</Text>
            <TouchableOpacity onPress={() => { void loadData(currentOwner); }}>
              <Text style={styles.retryText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </View>
        )}
        {performanceError && (
          <View style={styles.errorCard}>
            <Text style={styles.errorText}>{performanceError}</Text>
            <TouchableOpacity onPress={() => { void loadData(currentOwner); }}>
              <Text style={styles.retryText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </View>
        )}
        {/* 员工信息卡片 */}
        <View style={styles.profileCard}>
          <LinearGradient
            colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.accent]}
            style={styles.profileAvatar}
          >
            <Text style={styles.profileAvatarText}>{employee.avatar}</Text>
          </LinearGradient>

          <View style={styles.profileInfo}>
            <View style={styles.profileNameRow}>
              <Text style={styles.profileName}>{employee.name}</Text>
              <Text style={styles.profileCode}>({employee.employeeCode})</Text>
            </View>
            <View style={styles.profileStatusRow}>
              <View
                style={[
                  styles.statusBadge,
                  { backgroundColor: getStatusColor(employee.status) + '20' },
                ]}
              >
                <Text style={[styles.statusText, { color: getStatusColor(employee.status) }]}>
                  {getStatusText(employee.status)}
                </Text>
              </View>
              <Text style={styles.contractType}>
                {getContractTypeText(employee.contractType)}
              </Text>
            </View>
          </View>
        </View>

        {/* 工作信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('personnelDetailScreen.workInfo')}</Text>
          <View style={styles.infoGrid}>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>{t('personnelDetailScreen.workshop')}</Text>
              <Text style={styles.infoValue}>{employee.workshop}</Text>
            </View>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>{t('personnelDetailScreen.position')}</Text>
              <Text style={styles.infoValue}>{employee.position}</Text>
            </View>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>{t('personnelDetailScreen.hireDate')}</Text>
              <Text style={styles.infoValue}>{employee.hireDate}</Text>
            </View>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>{t('personnelDetailScreen.tenure')}</Text>
              <Text style={styles.infoValue}>{employee.tenure}</Text>
            </View>
          </View>
        </View>

        {/* 工时统计 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('personnelDetailScreen.weeklyHours')}</Text>
          <View style={styles.hoursCard}>
            <View style={styles.hoursMain}>
              <Text style={styles.hoursValue}>{employee.weeklyHours}</Text>
              <Text style={styles.hoursUnit}>/ {employee.maxWeeklyHours}h</Text>
            </View>
            <View style={styles.hoursExtra}>
              <Text style={styles.overtimeLabel}>{t('personnelDetailScreen.overtime')}</Text>
              <Text style={styles.overtimeValue}>{employee.overtimeHours}h</Text>
            </View>
          </View>
          <View style={styles.hoursBar}>
            <View
              style={[
                styles.hoursBarFill,
                { width: `${(employee.weeklyHours / employee.maxWeeklyHours) * 100}%` },
              ]}
            />
          </View>
        </View>

        {/* 技能等级 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('personnelDetailScreen.skillLevel')}</Text>
          <View style={styles.skillsGrid}>
            {employee.skills.map(renderSkillBar)}
          </View>
        </View>

        {/* 绩效指标 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('personnelDetailScreen.performanceMetrics')}</Text>
          <View style={styles.metricsGrid}>
            {employee.performance.map(renderPerformanceMetric)}
          </View>
        </View>

        {/* 近期任务 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('personnelDetailScreen.recentTasks')}</Text>
          <View style={styles.tasksList}>
            {employee.recentTasks.map(renderRecentTask)}
          </View>
        </View>

        {/* 操作按钮 */}
        <View style={styles.actionButtons}>
          <TouchableOpacity style={styles.transferButton} onPress={handleTransfer}>
            <Ionicons name="swap-horizontal" size={18} color={DISPATCHER_THEME.primary} />
            <Text style={styles.transferButtonText}>{t('personnelDetailScreen.transfer')}</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.disableButton} onPress={handleDisable}>
            <Ionicons name="ban-outline" size={18} color={DISPATCHER_THEME.danger} />
            <Text style={styles.disableButtonText}>{t('personnelDetailScreen.disable')}</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: DISPATCHER_THEME.background,
  },
  errorCard: {
    margin: 16,
    padding: 12,
    borderRadius: 8,
    backgroundColor: '#fff1f0',
  },
  errorText: {
    color: DISPATCHER_THEME.danger,
    marginBottom: 8,
  },
  retryText: {
    color: DISPATCHER_THEME.primary,
    fontWeight: '600',
  },
  container: {
    flex: 1,
    backgroundColor: DISPATCHER_THEME.background,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 16,
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#fff',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 32,
  },
  profileCard: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
  },
  profileAvatar: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 16,
  },
  profileAvatarText: {
    fontSize: 24,
    color: '#fff',
    fontWeight: '600',
  },
  profileInfo: {
    flex: 1,
  },
  profileNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
  },
  profileName: {
    fontSize: 20,
    fontWeight: '600',
    color: '#333',
  },
  profileCode: {
    fontSize: 14,
    color: '#999',
  },
  profileStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 4,
  },
  statusText: {
    fontSize: 12,
    fontWeight: '500',
  },
  contractType: {
    fontSize: 13,
    color: '#666',
  },
  section: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  infoGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  infoItem: {
    width: '50%',
    marginBottom: 12,
  },
  infoLabel: {
    fontSize: 12,
    color: '#999',
    marginBottom: 4,
  },
  infoValue: {
    fontSize: 14,
    color: '#333',
  },
  hoursCard: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  hoursMain: {
    flexDirection: 'row',
    alignItems: 'baseline',
  },
  hoursValue: {
    fontSize: 32,
    fontWeight: '700',
    color: DISPATCHER_THEME.primary,
  },
  hoursUnit: {
    fontSize: 14,
    color: '#999',
    marginLeft: 4,
  },
  hoursExtra: {
    alignItems: 'flex-end',
  },
  overtimeLabel: {
    fontSize: 12,
    color: '#999',
  },
  overtimeValue: {
    fontSize: 16,
    fontWeight: '600',
    color: DISPATCHER_THEME.warning,
  },
  hoursBar: {
    height: 8,
    backgroundColor: '#f0f0f0',
    borderRadius: 4,
    overflow: 'hidden',
  },
  hoursBarFill: {
    height: '100%',
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 4,
  },
  skillsGrid: {
    gap: 12,
  },
  skillItem: {
    gap: 6,
  },
  skillHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  skillName: {
    fontSize: 13,
    color: '#666',
  },
  skillLevel: {
    fontSize: 13,
    color: DISPATCHER_THEME.primary,
    fontWeight: '500',
  },
  skillBarBg: {
    height: 6,
    backgroundColor: '#f0f0f0',
    borderRadius: 3,
    overflow: 'hidden',
  },
  skillBarFill: {
    height: '100%',
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 3,
  },
  metricsGrid: {
    gap: 16,
  },
  metricItem: {
    gap: 6,
  },
  metricHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  metricLabel: {
    fontSize: 13,
    color: '#666',
  },
  metricTrend: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
  },
  metricTrendValue: {
    fontSize: 11,
  },
  metricBarBg: {
    height: 8,
    backgroundColor: '#f0f0f0',
    borderRadius: 4,
    overflow: 'hidden',
  },
  metricBarFill: {
    height: '100%',
    borderRadius: 4,
  },
  metricValue: {
    fontSize: 12,
    color: '#666',
    textAlign: 'right',
  },
  tasksList: {
    gap: 8,
  },
  taskItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  taskLeft: {
    flex: 1,
  },
  taskName: {
    fontSize: 13,
    color: '#333',
    marginBottom: 2,
  },
  taskDate: {
    fontSize: 11,
    color: '#999',
  },
  taskRight: {
    alignItems: 'flex-end',
  },
  taskStatus: {
    fontSize: 12,
  },
  taskHours: {
    fontSize: 11,
    color: '#999',
    marginTop: 2,
  },
  actionButtons: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 8,
  },
  transferButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingVertical: 12,
    backgroundColor: DISPATCHER_THEME.primary + '10',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: DISPATCHER_THEME.primary,
  },
  transferButtonText: {
    fontSize: 14,
    fontWeight: '500',
    color: DISPATCHER_THEME.primary,
  },
  disableButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingVertical: 12,
    backgroundColor: DISPATCHER_THEME.danger + '10',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: DISPATCHER_THEME.danger,
  },
  disableButtonText: {
    fontSize: 14,
    fontWeight: '500',
    color: DISPATCHER_THEME.danger,
  },
});
