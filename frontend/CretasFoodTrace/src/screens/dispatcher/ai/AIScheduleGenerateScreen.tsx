/**
 * AI 自动生成排程屏幕
 *
 * 功能：
 * - 选择生产计划自动生成排程
 * - 配置班次类型和工人可用性
 * - 预览生成的排程
 * - 支持调整后保存
 *
 * 与 AIScheduleScreen 区别：
 * - AIScheduleScreen: 交互式 AI 调度中心，选择批次后 AI 排产
 * - AIScheduleGenerateScreen: 从生产计划批量自动生成排程
 *
 * @version 1.0.0
 * @since 2025-12-29
 */
import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  Alert,
  Modal,
  ActivityIndicator,
  TextInput,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { DISPATCHER_THEME, ProductionPlan, LineSchedule } from '../../../types/dispatcher';
import {
  schedulingApiClient,
  type DailyAllocations,
  type DailyCapacityOverride,
  type DailyCapacityRequired,
} from '../../../services/api/schedulingApiClient';
import {
  buildCapacityOverrides,
  capacityBlockReason,
  capacityKey,
  describeCapacity,
  describeSampleDays,
  initialCapacityInputs,
  describeDateRange,
  describeDays,
  type CapacityInputs,
} from './dailyCapacityPrompt';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';
import { formatDate } from '../../../utils/formatters';
import { appAlert } from '../../../components/ui/AppDialog';
import { getErrorMsg } from '../../../utils/errorHandler';

// 班次类型
type ShiftType = 'day' | 'night' | 'full_day';

const SHIFT_OPTIONS = [
  { value: 'day' as ShiftType, label: '白班', hours: '08:00 - 17:00', icon: 'white-balance-sunny' },
  { value: 'night' as ShiftType, label: '夜班', hours: '18:00 - 02:00', icon: 'weather-night' },
  { value: 'full_day' as ShiftType, label: '全天', hours: '08:00 - 22:00', icon: 'hours-24' },
] as const;

// 生成的排程接口
interface GeneratedSchedule {
  /** 排程 (LineSchedule) id, 列表 key 用; 同一次生成的排程共享 planId */
  scheduleId: string;
  planId: string;
  planNumber: string;
  productName: string;
  lineName: string;
  lineId: string;
  workerCount: number;
  startTime: string;
  endTime: string;
  /** APS v2 按天排: 「10-29 ~ 10-31」/「10-29 300袋 · …」/「日产能 300袋/天（人工填写）」; 旧排程为 null */
  dateRange: string | null;
  daysText: string | null;
  capacityText: string | null;
  /** 后端没有给出就是 null (按日产能排程不估效率/概率), ⛔ 不拿默认值冒充 */
  estimatedEfficiency: number | null;
  completionProbability: number | null;
}

export default function AIScheduleGenerateScreen() {
  const navigation = useNavigation<any>();
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(false);
  const [showGenerating, setShowGenerating] = useState(false);
  const [showResult, setShowResult] = useState(false);

  // 配置参数
  const [scheduleDate, setScheduleDate] = useState<string>(() => {
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    return formatDate(tomorrow) ?? '';
  });
  const [shiftType, setShiftType] = useState<ShiftType>('day');
  const [autoAssignWorkers, setAutoAssignWorkers] = useState(true);
  const [optimizeByLinUCB, setOptimizeByLinUCB] = useState(true);

  // 数据
  const [plans, setPlans] = useState<ProductionPlan[]>([]);
  const [selectedPlanIds, setSelectedPlanIds] = useState<Set<string>>(new Set());
  const [generatedSchedules, setGeneratedSchedules] = useState<GeneratedSchedule[]>([]);
  const [initialLoading, setInitialLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 统计数据
  const [totalWorkers, setTotalWorkers] = useState(0);
  const [avgEfficiency, setAvgEfficiency] = useState<number | null>(null);
  const [avgProbability, setAvgProbability] = useState<number | null>(null);

  // APS v2: 两样都没有日产能时当场补填
  const [capacityRequired, setCapacityRequired] = useState<DailyCapacityRequired[]>([]);
  const [capacityInputs, setCapacityInputs] = useState<CapacityInputs>({});
  const [showCapacityModal, setShowCapacityModal] = useState(false);
  const capacityBlock = capacityBlockReason(capacityRequired, capacityInputs);

  const loadPlans = useCallback(async (isRefresh = false) => {
    if (isRefresh) {
      setRefreshing(true);
    } else {
      setInitialLoading(true);
    }
    setError(null);

    try {
      // 加载待排产的生产计划 (状态为 pending 或 confirmed)
      const response = await productionPlanApiClient.getProductionPlans({
        status: 'PENDING',
        page: 1,
        size: 50,
      });

      if (response.success && response.data) {
        // response.data 是 PagedResponse<ProductionPlan>
        const planList = response.data.content ?? [];
        // 兼容两种类型，将 productionPlanApiClient 的结果转换为 dispatcher 的 ProductionPlan
        setPlans(planList as unknown as ProductionPlan[]);
        // 默认不选中任何计划，让用户手动选择
        setSelectedPlanIds(new Set());
      } else {
        setError('加载生产计划失败');
      }
    } catch (err) {
      console.error('Failed to load plans:', err);
      setError('加载生产计划失败，请稍后重试');
    } finally {
      setRefreshing(false);
      setInitialLoading(false);
    }
  }, []);

  useEffect(() => {
    loadPlans();
  }, [loadPlans]);

  const onRefresh = useCallback(() => {
    loadPlans(true);
  }, [loadPlans]);

  const togglePlanSelection = (planId: string) => {
    const newSelection = new Set(selectedPlanIds);
    if (newSelection.has(planId)) {
      newSelection.delete(planId);
    } else {
      newSelection.add(planId);
    }
    setSelectedPlanIds(newSelection);
  };

  const selectAllPlans = () => {
    if (selectedPlanIds.size === plans.length) {
      setSelectedPlanIds(new Set());
    } else {
      setSelectedPlanIds(new Set(plans.map(p => p.id)));
    }
  };

  const generateSchedule = async (overrides: DailyCapacityOverride[] = []) => {
    if (selectedPlanIds.size === 0) {
      Alert.alert('提示', '请至少选择一个生产计划');
      return;
    }

    setShowGenerating(true);

    try {
      // 调用 AI 生成排程 API
      const response = await schedulingApiClient.generateSchedule({
        planDate: scheduleDate,
        shiftType,
        planIds: Array.from(selectedPlanIds),
        autoAssignWorkers,
        enableLinUCB: optimizeByLinUCB,
        ...(overrides.length > 0 ? { dailyCapacityOverrides: overrides } : {}),
      });

      if (response.success && response.data && response.data.outcome === 'CAPACITY_REQUIRED') {
        // 两样都没有日产能: 后端什么都没保存, 当场问每个产品一天能做多少
        const required = response.data.capacityRequired ?? [];
        if (required.length === 0) {
          appAlert('排程没有生成', '有产品缺日产能，但没有拿到清单；请刷新后重试');
          return;
        }
        setCapacityRequired(required);
        // 之前填过的保留; 联产计划有建议值就预填 (可改, 点「保存并排程」才保存)
        setCapacityInputs(prev => initialCapacityInputs(required, prev));
        setShowCapacityModal(true);
        return;
      }

      if (response.success && response.data) {
        setShowCapacityModal(false);
        // 后端返回 SchedulingPlanDTO，直接作为 plan 使用
        // 注意：后端返回的是 SchedulingPlanDTO，包含 id, planDate, lineSchedules 等
        interface LineScheduleDTO {
          id?: string;
          batchNumber?: string;
          planNumber?: string;
          productionLineName?: string;
          productTypeName?: string;
          lineName?: string;
          productionLineId?: string;
          assignedWorkers?: number;
          plannedStartTime?: string;
          plannedEndTime?: string;
          dailyAllocations?: DailyAllocations | null;
          predictedEfficiency?: number;
          completionProbability?: number;
        }
        interface SchedulingPlanDTO {
          id: string;
          planName?: string;
          totalWorkers?: number;
          lineSchedules?: LineScheduleDTO[];
          warnings?: string[];
        }
        const plan = response.data as SchedulingPlanDTO;
        const lineSchedules = plan.lineSchedules || [];

        // 从 lineSchedules 构建每条产线的排程详情
        const schedules: GeneratedSchedule[] = lineSchedules.length > 0
          ? lineSchedules.map((schedule: LineScheduleDTO, index: number) => ({
              scheduleId: schedule.id ?? `${plan.id}-${index}`,
              planId: plan.id, // 这是 SchedulingPlan 的 ID，用于确认
              planNumber: schedule.planNumber ?? schedule.batchNumber ?? plan.planName ?? '-',
              productName: schedule.productTypeName ?? '-',
              lineName: schedule.productionLineName ?? schedule.lineName ?? '产线未标明',
              lineId: schedule.productionLineId ?? '',
              workerCount: schedule.assignedWorkers ?? 0,
              startTime: schedule.plannedStartTime ? new Date(schedule.plannedStartTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '-',
              endTime: schedule.plannedEndTime ? new Date(schedule.plannedEndTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '-',
              dateRange: describeDateRange(schedule.dailyAllocations),
              daysText: describeDays(schedule.dailyAllocations),
              capacityText: describeCapacity(schedule.dailyAllocations),
              estimatedEfficiency: schedule.predictedEfficiency != null ? Math.round(Number(schedule.predictedEfficiency)) : null,
              completionProbability: schedule.completionProbability != null ? Math.round(Number(schedule.completionProbability)) : null,
            }))
          : [{
              scheduleId: plan.id,
              planId: plan.id,
              planNumber: plan.planName ?? '-',
              productName: '-',
              lineName: '产线未标明',
              lineId: '',
              workerCount: plan.totalWorkers ?? 0,
              startTime: '-',
              endTime: '-',
              dateRange: null,
              daysText: null,
              capacityText: null,
              estimatedEfficiency: null,
              completionProbability: null,
            }];

        setGeneratedSchedules(schedules);

        // 计算统计
        if (schedules.length > 0) {
          const workers = schedules.reduce((sum, s) => sum + s.workerCount, 0);
          const average = (values: Array<number | null>) => {
            const known = values.filter((v): v is number => v != null);
            return known.length ? Math.round(known.reduce((a, b) => a + b, 0) / known.length) : null;
          };

          setTotalWorkers(workers);
          setAvgEfficiency(average(schedules.map(s => s.estimatedEfficiency)));
          setAvgProbability(average(schedules.map(s => s.completionProbability)));
        }

        setShowResult(true);
        // 按日产能排程的提示: 按历史估算 / 人工与历史相差大 / 已完成不排
        const warnings = (plan.warnings ?? []).filter(w => typeof w === 'string' && w.trim().length > 0);
        if (warnings.length > 0) {
          appAlert('排程提示', warnings.join('\n'));
        }
      } else {
        appAlert('排程没有生成', response.message ?? '排程生成失败');
      }
    } catch (err) {
      console.error('Generate schedule failed:', err);
      // 后端会说清楚为什么没排 (例如要选哪条产线、日产能单位不对), 原样给用户看
      appAlert('排程没有生成', getErrorMsg(err) || '排程生成失败，请稍后重试');
    } finally {
      setShowGenerating(false);
    }
  };

  const confirmSchedule = async () => {
    try {
      setLoading(true);

      // 确认生成的排程 - 所有 schedule 共享同一个 SchedulingPlan
      // 只需要确认一次（使用第一个 schedule 的 planId）
      const firstSchedule = generatedSchedules[0];
      if (!firstSchedule) {
        throw new Error('没有可确认的排程');
      }

      const planId = firstSchedule.planId;
      console.log('Confirming scheduling plan:', planId);

      const response = await schedulingApiClient.confirmPlan(planId);
      if (!response.success) {
        throw new Error(response.message ?? '确认排程失败');
      }

      Alert.alert(
        '排程已保存',
        `成功创建 ${generatedSchedules.length} 个产线排程`,
        [{ text: '确定', onPress: () => navigation.goBack() }]
      );
    } catch (err) {
      console.error('Apply schedule failed:', err);
      Alert.alert('错误', '保存排程失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const getPriorityColor = (priority: number) => {
    if (priority >= 8) return { bg: '#fff1f0', text: '#ff4d4f', label: '高' };
    if (priority >= 5) return { bg: '#fff7e6', text: '#fa8c16', label: '中' };
    return { bg: '#f6ffed', text: '#52c41a', label: '低' };
  };

  const getSourceLabel = (plan: ProductionPlan) => {
    switch (plan.sourceType) {
      case 'customer_order':
        return { label: '客户订单', color: '#1890ff' };
      case 'ai_forecast':
        return { label: 'AI预测', color: '#722ed1' };
      case 'urgent_insert':
        return { label: '紧急插单', color: '#ff4d4f' };
      case 'safety_stock':
        return { label: '安全库存', color: '#52c41a' };
      default:
        return { label: '手动', color: '#999' };
    }
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <LinearGradient
        colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={styles.header}
      >
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="chevron-left" size={28} color="#fff" />
        </TouchableOpacity>

        <View style={styles.headerContent}>
          <MaterialCommunityIcons name="auto-fix" size={28} color="#fff" />
          <Text style={styles.headerTitle}>AI 自动生成排程</Text>
        </View>
        <Text style={styles.headerSubtitle}>基于生产计划智能分配产线和人员</Text>
      </LinearGradient>

      <ScrollView
        style={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
        showsVerticalScrollIndicator={false}
      >
        {initialLoading ? (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color={DISPATCHER_THEME.primary} />
            <Text style={styles.loadingText}>加载生产计划...</Text>
          </View>
        ) : error ? (
          <View style={styles.errorContainer}>
            <MaterialCommunityIcons name="alert-circle-outline" size={48} color="#ff4d4f" />
            <Text style={styles.errorText}>{error}</Text>
            <TouchableOpacity style={styles.retryButton} onPress={() => loadPlans()}>
              <Text style={styles.retryButtonText}>重新加载</Text>
            </TouchableOpacity>
          </View>
        ) : !showResult ? (
          <>
            {/* 配置区域 */}
            <View style={styles.card}>
              <Text style={styles.cardTitle}>排程配置</Text>

              {/* 日期选择 */}
              <View style={styles.configRow}>
                <Text style={styles.configLabel}>排程日期</Text>
                <TouchableOpacity style={styles.dateInput}>
                  <MaterialCommunityIcons name="calendar" size={18} color={DISPATCHER_THEME.primary} />
                  <Text style={styles.dateInputText}>{scheduleDate}</Text>
                </TouchableOpacity>
              </View>

              {/* 班次选择 */}
              <View style={styles.configSection}>
                <Text style={styles.configLabel}>班次类型</Text>
                <View style={styles.shiftGrid}>
                  {SHIFT_OPTIONS.map((option) => (
                    <TouchableOpacity
                      key={option.value}
                      style={[
                        styles.shiftItem,
                        shiftType === option.value && styles.shiftItemActive,
                      ]}
                      onPress={() => setShiftType(option.value)}
                    >
                      <MaterialCommunityIcons
                        name={option.icon}
                        size={22}
                        color={shiftType === option.value ? '#fff' : DISPATCHER_THEME.primary}
                      />
                      <Text
                        style={[
                          styles.shiftLabel,
                          shiftType === option.value && styles.shiftLabelActive,
                        ]}
                      >
                        {option.label}
                      </Text>
                      <Text
                        style={[
                          styles.shiftHours,
                          shiftType === option.value && styles.shiftHoursActive,
                        ]}
                      >
                        {option.hours}
                      </Text>
                    </TouchableOpacity>
                  ))}
                </View>
              </View>

              {/* AI 选项 */}
              <View style={styles.configSection}>
                <Text style={styles.configLabel}>AI 优化选项</Text>

                <TouchableOpacity
                  style={styles.switchRow}
                  onPress={() => setAutoAssignWorkers(!autoAssignWorkers)}
                >
                  <View>
                    <Text style={styles.switchLabel}>自动分配工人</Text>
                    <Text style={styles.switchDesc}>基于技能和可用性自动分配</Text>
                  </View>
                  <View style={[styles.switch, autoAssignWorkers && styles.switchActive]}>
                    <View style={[styles.switchThumb, autoAssignWorkers && styles.switchThumbActive]} />
                  </View>
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.switchRow}
                  onPress={() => setOptimizeByLinUCB(!optimizeByLinUCB)}
                >
                  <View>
                    <Text style={styles.switchLabel}>LinUCB 人员优化</Text>
                    <Text style={styles.switchDesc}>使用强化学习优化工人分配</Text>
                  </View>
                  <View style={[styles.switch, optimizeByLinUCB && styles.switchActive]}>
                    <View style={[styles.switchThumb, optimizeByLinUCB && styles.switchThumbActive]} />
                  </View>
                </TouchableOpacity>
              </View>
            </View>

            {/* 生产计划选择 */}
            <View style={styles.card}>
              <View style={styles.cardHeader}>
                <Text style={styles.cardTitle}>选择生产计划</Text>
                <TouchableOpacity onPress={selectAllPlans}>
                  <Text style={styles.selectAllText}>
                    {selectedPlanIds.size === plans.length ? '取消全选' : '全选'}
                  </Text>
                </TouchableOpacity>
              </View>

              {plans.length === 0 ? (
                <View style={styles.emptyState}>
                  <MaterialCommunityIcons name="clipboard-text-outline" size={48} color="#d9d9d9" />
                  <Text style={styles.emptyStateText}>暂无待排产的生产计划</Text>
                  <Text style={styles.emptyStateSubtext}>请先创建生产计划</Text>
                </View>
              ) : plans.map((plan) => {
                const priorityStyle = getPriorityColor(plan.priority);
                const sourceStyle = getSourceLabel(plan);
                return (
                  <TouchableOpacity
                    key={plan.id}
                    style={styles.planItem}
                    onPress={() => togglePlanSelection(plan.id)}
                  >
                    <View
                      style={[
                        styles.checkbox,
                        selectedPlanIds.has(plan.id) && styles.checkboxChecked,
                      ]}
                    >
                      {selectedPlanIds.has(plan.id) && (
                        <MaterialCommunityIcons name="check" size={14} color="#fff" />
                      )}
                    </View>

                    <View style={styles.planInfo}>
                      <View style={styles.planHeader}>
                        <Text style={styles.planNumber}>{plan.planNumber}</Text>
                        <View style={[styles.priorityBadge, { backgroundColor: priorityStyle.bg }]}>
                          <Text style={[styles.priorityText, { color: priorityStyle.text }]}>
                            {priorityStyle.label}
                          </Text>
                        </View>
                      </View>
                      <Text style={styles.planProduct}>
                        {plan.productTypeName} · {plan.plannedQuantity}{plan.unit}
                      </Text>
                      <View style={styles.planMeta}>
                        <View style={styles.sourceTag}>
                          <Text style={[styles.sourceText, { color: sourceStyle.color }]}>
                            {sourceStyle.label}
                          </Text>
                        </View>
                        {plan.crValue && (
                          <Text style={styles.crText}>CR: {plan.crValue}</Text>
                        )}
                      </View>
                    </View>
                  </TouchableOpacity>
                );
              })}
            </View>

            {/* 生成按钮 */}
            <TouchableOpacity onPress={() => generateSchedule()}>
              <LinearGradient
                colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
                start={{ x: 0, y: 0 }}
                end={{ x: 1, y: 0 }}
                style={styles.generateButton}
              >
                <MaterialCommunityIcons name="auto-fix" size={22} color="#fff" />
                <Text style={styles.generateButtonText}>
                  生成排程 ({selectedPlanIds.size} 个计划)
                </Text>
              </LinearGradient>
            </TouchableOpacity>
          </>
        ) : (
          <>
            {/* 统计概览 */}
            <View style={styles.statsRow}>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{generatedSchedules.length}</Text>
                <Text style={styles.statLabel}>排程数</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={styles.statValue}>{totalWorkers}</Text>
                <Text style={styles.statLabel}>所需工人</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={[styles.statValue, { color: '#52c41a' }]}>{avgEfficiency != null ? `${avgEfficiency}%` : '—'}</Text>
                <Text style={styles.statLabel}>预计效率</Text>
              </View>
              <View style={styles.statItem}>
                <Text style={[styles.statValue, { color: DISPATCHER_THEME.primary }]}>{avgProbability != null ? `${avgProbability}%` : '—'}</Text>
                <Text style={styles.statLabel}>完成概率</Text>
              </View>
            </View>

            {/* 生成结果 */}
            <View style={styles.card}>
              <Text style={styles.cardTitle}>排程预览</Text>

              {generatedSchedules.map((schedule, index) => (
                <View key={schedule.scheduleId} style={styles.scheduleItem}>
                  <View style={styles.scheduleHeader}>
                    <Text style={styles.scheduleNumber}>{schedule.planNumber}</Text>
                    <View style={styles.scheduleTime}>
                      <MaterialCommunityIcons name="clock-outline" size={14} color="#666" />
                      <Text style={styles.scheduleTimeText}>
                        {schedule.dateRange ?? `${schedule.startTime} - ${schedule.endTime}`}
                      </Text>
                    </View>
                  </View>

                  <Text style={styles.scheduleProduct}>{schedule.productName}</Text>
                  {schedule.capacityText ? (
                    <Text style={styles.scheduleDailyText} testID={`daily-capacity-${schedule.scheduleId}`}>
                      {schedule.capacityText}
                    </Text>
                  ) : null}
                  {schedule.daysText ? (
                    <Text style={styles.scheduleDailyText} testID={`daily-days-${schedule.scheduleId}`}>
                      {schedule.daysText}
                    </Text>
                  ) : null}

                  <View style={styles.scheduleDetails}>
                    <View style={styles.scheduleDetail}>
                      <MaterialCommunityIcons name="factory" size={14} color="#666" />
                      <Text style={styles.scheduleDetailText}>{schedule.lineName}</Text>
                    </View>
                    <View style={styles.scheduleDetail}>
                      <MaterialCommunityIcons name="account-group" size={14} color="#666" />
                      <Text style={styles.scheduleDetailText}>{schedule.workerCount} 人</Text>
                    </View>
                    <View style={styles.scheduleDetail}>
                      <MaterialCommunityIcons name="speedometer" size={14} color="#52c41a" />
                      <Text style={[styles.scheduleDetailText, { color: '#52c41a' }]}>
                        {schedule.estimatedEfficiency != null ? `${schedule.estimatedEfficiency}%` : '—'}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.probabilityBar}>
                    <View
                      style={[
                        styles.probabilityFill,
                        { width: `${schedule.completionProbability ?? 0}%` },
                      ]}
                    />
                  </View>
                  <Text style={styles.probabilityText}>
                    完成概率: {schedule.completionProbability != null ? `${schedule.completionProbability}%` : '未估算'}
                  </Text>
                </View>
              ))}
            </View>

            {/* 操作按钮 */}
            <View style={styles.actionButtons}>
              <TouchableOpacity
                style={styles.outlineButton}
                onPress={() => setShowResult(false)}
              >
                <Text style={styles.outlineButtonText}>重新生成</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.solidButton}
                onPress={confirmSchedule}
                disabled={loading}
              >
                <LinearGradient
                  colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary]}
                  start={{ x: 0, y: 0 }}
                  end={{ x: 1, y: 0 }}
                  style={styles.solidButtonGradient}
                >
                  {loading ? (
                    <ActivityIndicator color="#fff" size="small" />
                  ) : (
                    <Text style={styles.solidButtonText}>确认并保存</Text>
                  )}
                </LinearGradient>
              </TouchableOpacity>
            </View>
          </>
        )}

        <View style={{ height: 100 }} />
      </ScrollView>

      {/* 两样都没有日产能: 当场补填, 保存到产品后重新排 */}
      <Modal visible={showCapacityModal} transparent animationType="fade" onRequestClose={() => setShowCapacityModal(false)}>
        <View style={styles.loadingOverlay}>
          <View style={styles.capacityContent} testID="capacity-required-modal">
            <Text style={styles.capacityTitle}>这些产品还不知道一天能做多少</Text>
            <Text style={styles.capacityHint}>填一下就能排，会记在产品上，下次不用再填</Text>
            <ScrollView style={styles.capacityList}>
              {capacityRequired.map(item => (
                <View key={capacityKey(item)} style={styles.capacityRow} testID={`capacity-row-${item.productTypeId}`}>
                  <Text style={styles.capacityQuestion}>{item.question}</Text>
                  <Text style={styles.capacityMeta}>
                    计划：{item.planNumbers.join('、')}
                    {item.reason === 'UNIT_MISMATCH'
                      ? `；产品上填的是 ${item.currentQuantityPerDay ?? '-'} ${item.currentUnit ?? ''}/天，和计划单位 ${item.unit} 对不上`
                      : ''}
                    {item.experienceNote ? `；${item.experienceNote}` : ''}
                  </Text>
                  {describeSampleDays(item) ? (
                    <Text style={styles.capacityMeta} testID={`capacity-samples-${item.productTypeId}`}>
                      {describeSampleDays(item)}
                    </Text>
                  ) : null}
                  {item.suggestedPerDay ? (
                    <Text style={styles.capacitySuggestion} testID={`capacity-suggestion-${item.productTypeId}`}>
                      {`建议 ${item.suggestedPerDay} ${item.unit}/天，已预填，可以改；点「保存并排程」才会保存`}
                    </Text>
                  ) : null}
                  <View style={styles.capacityInputRow}>
                    <TextInput
                      style={styles.capacityInput}
                      keyboardType="decimal-pad"
                      placeholder="一天能做多少"
                      value={capacityInputs[capacityKey(item)] ?? ''}
                      onChangeText={text => setCapacityInputs(prev => ({ ...prev, [capacityKey(item)]: text }))}
                      testID={`capacity-input-${item.productTypeId}`}
                      accessibilityLabel={item.question}
                    />
                    <Text style={styles.capacityUnit}>{item.unit}/天</Text>
                  </View>
                </View>
              ))}
            </ScrollView>
            {capacityBlock ? <Text style={styles.capacityBlock} testID="capacity-block-reason">{capacityBlock}</Text> : null}
            <View style={styles.capacityActions}>
              <TouchableOpacity style={styles.outlineButton} onPress={() => setShowCapacityModal(false)}>
                <Text style={styles.outlineButtonText}>先不排</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.capacitySubmit, capacityBlock ? styles.capacitySubmitDisabled : null]}
                disabled={!!capacityBlock}
                testID="capacity-submit"
                onPress={() => {
                  if (capacityBlock) return;
                  setShowCapacityModal(false);
                  generateSchedule(buildCapacityOverrides(capacityRequired, capacityInputs));
                }}
              >
                <Text style={styles.solidButtonText}>保存并排程</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      {/* 生成中弹窗 */}
      <Modal visible={showGenerating} transparent animationType="fade">
        <View style={styles.loadingOverlay}>
          <View style={styles.loadingContent}>
            <ActivityIndicator size="large" color={DISPATCHER_THEME.primary} />
            <Text style={styles.loadingTitle}>AI 正在生成排程...</Text>
            <View style={styles.loadingSteps}>
              <View style={styles.loadingStep}>
                <MaterialCommunityIcons name="check-circle" size={16} color="#52c41a" />
                <Text style={styles.loadingStepText}>分析生产计划</Text>
              </View>
              <View style={styles.loadingStep}>
                <MaterialCommunityIcons name="check-circle" size={16} color="#52c41a" />
                <Text style={styles.loadingStepText}>匹配产线资源</Text>
              </View>
              <View style={styles.loadingStep}>
                <ActivityIndicator size="small" color={DISPATCHER_THEME.primary} />
                <Text style={styles.loadingStepText}>优化人员分配 (LinUCB)</Text>
              </View>
              <View style={styles.loadingStep}>
                <MaterialCommunityIcons name="circle-outline" size={16} color="#d9d9d9" />
                <Text style={[styles.loadingStepText, { color: '#999' }]}>计算完成概率</Text>
              </View>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    paddingTop: 16,
    paddingBottom: 20,
    paddingHorizontal: 16,
  },
  backButton: {
    position: 'absolute',
    left: 8,
    top: 12,
    padding: 4,
    zIndex: 1,
  },
  headerContent: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    marginBottom: 4,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#fff',
  },
  headerSubtitle: {
    fontSize: 13,
    color: 'rgba(255,255,255,0.85)',
    textAlign: 'center',
  },
  content: {
    flex: 1,
    padding: 16,
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 2,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 12,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginBottom: 12,
  },
  configRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  configLabel: {
    fontSize: 14,
    color: '#666',
    marginBottom: 8,
  },
  configSection: {
    marginBottom: 16,
  },
  dateInput: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f9f9f9',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 8,
  },
  dateInputText: {
    fontSize: 14,
    color: '#333',
  },
  shiftGrid: {
    flexDirection: 'row',
    gap: 10,
  },
  shiftItem: {
    flex: 1,
    backgroundColor: '#f9f9f9',
    borderRadius: 10,
    padding: 12,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  shiftItemActive: {
    backgroundColor: DISPATCHER_THEME.primary,
    borderColor: DISPATCHER_THEME.primary,
  },
  shiftLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    marginTop: 6,
  },
  shiftLabelActive: {
    color: '#fff',
  },
  shiftHours: {
    fontSize: 11,
    color: '#999',
    marginTop: 2,
  },
  shiftHoursActive: {
    color: 'rgba(255,255,255,0.8)',
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#f9f9f9',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  switchLabel: {
    fontSize: 14,
    color: '#333',
    fontWeight: '500',
  },
  switchDesc: {
    fontSize: 12,
    color: '#999',
    marginTop: 2,
  },
  switch: {
    width: 44,
    height: 24,
    borderRadius: 12,
    backgroundColor: '#d9d9d9',
    justifyContent: 'center',
    padding: 2,
  },
  switchActive: {
    backgroundColor: DISPATCHER_THEME.primary,
  },
  switchThumb: {
    width: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: '#fff',
  },
  switchThumbActive: {
    alignSelf: 'flex-end',
  },
  selectAllText: {
    fontSize: 13,
    color: DISPATCHER_THEME.primary,
  },
  planItem: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#f9f9f9',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  checkbox: {
    width: 20,
    height: 20,
    borderRadius: 4,
    borderWidth: 2,
    borderColor: '#d9d9d9',
    marginRight: 12,
    marginTop: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxChecked: {
    backgroundColor: DISPATCHER_THEME.primary,
    borderColor: DISPATCHER_THEME.primary,
  },
  planInfo: {
    flex: 1,
  },
  planHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  planNumber: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  priorityBadge: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  priorityText: {
    fontSize: 11,
    fontWeight: '500',
  },
  planProduct: {
    fontSize: 13,
    color: '#666',
    marginBottom: 6,
  },
  planMeta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  sourceTag: {
    backgroundColor: '#f0f0f0',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  sourceText: {
    fontSize: 11,
  },
  crText: {
    fontSize: 11,
    color: '#999',
  },
  generateButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 14,
    borderRadius: 10,
    gap: 8,
  },
  generateButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#fff',
  },
  statsRow: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 2,
  },
  statItem: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    fontSize: 22,
    fontWeight: '600',
    color: '#333',
  },
  statLabel: {
    fontSize: 12,
    color: '#999',
    marginTop: 4,
  },
  scheduleItem: {
    backgroundColor: '#f9f9f9',
    borderRadius: 10,
    padding: 12,
    marginBottom: 10,
  },
  scheduleHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  scheduleNumber: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  scheduleTime: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  scheduleTimeText: {
    fontSize: 12,
    color: '#666',
  },
  scheduleProduct: {
    fontSize: 13,
    color: '#666',
    marginBottom: 8,
  },
  scheduleDetails: {
    flexDirection: 'row',
    gap: 16,
    marginBottom: 10,
  },
  scheduleDetail: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  scheduleDetailText: {
    fontSize: 12,
    color: '#666',
  },
  probabilityBar: {
    height: 4,
    backgroundColor: '#e0e0e0',
    borderRadius: 2,
    marginBottom: 4,
  },
  probabilityFill: {
    height: '100%',
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 2,
  },
  probabilityText: {
    fontSize: 11,
    color: '#999',
  },
  actionButtons: {
    flexDirection: 'row',
    gap: 12,
  },
  outlineButton: {
    flex: 1,
    padding: 14,
    borderWidth: 1.5,
    borderColor: DISPATCHER_THEME.primary,
    borderRadius: 10,
    alignItems: 'center',
  },
  outlineButtonText: {
    fontSize: 15,
    fontWeight: '500',
    color: DISPATCHER_THEME.primary,
  },
  solidButton: {
    flex: 1,
    borderRadius: 10,
    overflow: 'hidden',
  },
  solidButtonGradient: {
    padding: 14,
    alignItems: 'center',
  },
  solidButtonText: {
    fontSize: 15,
    fontWeight: '600',
    color: '#fff',
  },
  loadingOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingContent: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 24,
    width: 280,
    alignItems: 'center',
  },
  loadingTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
    marginTop: 16,
    marginBottom: 20,
  },
  loadingSteps: {
    width: '100%',
    gap: 12,
  },
  loadingStep: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  loadingStepText: {
    fontSize: 13,
    color: '#333',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 60,
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: '#666',
  },
  errorContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 60,
  },
  errorText: {
    marginTop: 12,
    fontSize: 14,
    color: '#ff4d4f',
    textAlign: 'center',
  },
  retryButton: {
    marginTop: 16,
    paddingHorizontal: 20,
    paddingVertical: 10,
    backgroundColor: DISPATCHER_THEME.primary,
    borderRadius: 8,
  },
  retryButtonText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: '500',
  },
  emptyState: {
    justifyContent: 'center',
    alignItems: 'center',
    paddingVertical: 32,
  },
  emptyStateText: {
    marginTop: 12,
    fontSize: 14,
    color: '#999',
  },
  emptyStateSubtext: {
    marginTop: 4,
    fontSize: 12,
    color: '#bbb',
  },
  scheduleDailyText: {
    fontSize: 12,
    color: '#595959',
    marginBottom: 6,
  },
  capacityContent: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 20,
    width: '90%',
    maxHeight: '80%',
  },
  capacityTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: '#262626',
  },
  capacityHint: {
    marginTop: 4,
    fontSize: 12,
    color: '#8c8c8c',
  },
  capacityList: {
    marginTop: 12,
  },
  capacityRow: {
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  capacityQuestion: {
    fontSize: 14,
    color: '#262626',
  },
  capacityMeta: {
    marginTop: 2,
    fontSize: 12,
    color: '#8c8c8c',
  },
  capacitySuggestion: {
    marginTop: 2,
    fontSize: 12,
    color: '#1677ff',
  },
  capacityInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 8,
  },
  capacityInput: {
    flex: 1,
    minHeight: 44,
    borderWidth: 1,
    borderColor: '#d9d9d9',
    borderRadius: 8,
    paddingHorizontal: 12,
    fontSize: 16,
  },
  capacityUnit: {
    fontSize: 14,
    color: '#595959',
  },
  capacityBlock: {
    marginTop: 8,
    fontSize: 12,
    color: '#d48806',
  },
  capacityActions: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 16,
  },
  capacitySubmit: {
    flex: 1,
    padding: 14,
    borderRadius: 10,
    alignItems: 'center',
    backgroundColor: DISPATCHER_THEME.primary,
  },
  capacitySubmitDisabled: {
    opacity: 0.4,
  },
});
