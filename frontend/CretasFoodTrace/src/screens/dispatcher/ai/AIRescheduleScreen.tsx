/**
 * AI 重排建议页
 *
 * 功能:
 * - 选择需要查看的调度计划
 * - 记录调整原因入口
 * - What-If 场景分析
 *
 * @version 1.0.0
 * @since 2026-01-22
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  Alert,
  Modal,
  TextInput,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { DISPATCHER_THEME, SchedulingPlan } from '../../../types/dispatcher';
import {
  schedulingOptimizationApiClient,
  WhatIfResult,
  WhatIfRequest,
} from '../../../services/api/schedulingOptimizationApiClient';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { formatDate } from '../../../utils/formatters';

// 图标名称类型
type IconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
const REASON_PLAN_STATUSES = ['draft', 'confirmed', 'in_progress'];
type ReasonSchedulingPlan = SchedulingPlan & { planName?: string };

// What-If 场景选项
const WHATIF_SCENARIOS: { value: WhatIfRequest['scenario']; label: string; icon: IconName }[] = [
  { value: 'add_worker', label: '增加工人', icon: 'account-plus' },
  { value: 'remove_worker', label: '减少工人', icon: 'account-minus' },
  { value: 'change_shift', label: '更换班次', icon: 'clock-outline' },
  { value: 'add_equipment', label: '增加设备', icon: 'hammer-wrench' },
  { value: 'priority_change', label: '调整优先级', icon: 'sort-ascending' },
];

export default function AIRescheduleScreen() {
  const navigation = useNavigation<any>();
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 计划列表
  const [plans, setPlans] = useState<ReasonSchedulingPlan[]>([]);
  const [selectedPlanId, setSelectedPlanId] = useState<string | null>(null);
  const [adjustmentReason, setAdjustmentReason] = useState('');
  const [isSavingAdjustmentReason, setIsSavingAdjustmentReason] = useState(false);
  const savingAdjustmentReason = useRef(false);

  // What-If 分析
  const [showWhatIfModal, setShowWhatIfModal] = useState(false);
  const [whatIfScenario, setWhatIfScenario] = useState<WhatIfRequest['scenario'] | null>(null);
  const [whatIfResult, setWhatIfResult] = useState<WhatIfResult | null>(null);
  const [analyzingWhatIf, setAnalyzingWhatIf] = useState(false);

  // 加载计划列表
  const loadPlans = useCallback(async () => {
    try {
      setError(null);
      const today = formatDate(new Date());
      const response = await schedulingApiClient.getPlans({
        startDate: today,
        endDate: today,
        status: 'draft,confirmed,in_progress',
        page: 0,
        size: 20,
      });

      if (!response.success || !response.data || !Array.isArray(response.data.content)) {
        throw new Error(response.message || '调度计划加载失败，请重新加载。');
      }
      const activePlans = response.data.content.filter((plan) =>
        REASON_PLAN_STATUSES.includes(plan.status.toLowerCase())
      );
      setPlans(activePlans);
      setSelectedPlanId((current) => activePlans.some((plan) => plan.id === current)
        ? current : activePlans[0]?.id ?? null);
    } catch (err) {
      setPlans([]);
      setSelectedPlanId(null);
      console.error('加载计划列表失败:', err);
      const message = err instanceof Error ? err.message : '加载失败';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadPlans();
  }, [loadPlans]);

  const onRefresh = useCallback(async () => {
    if (savingAdjustmentReason.current) return;
    setRefreshing(true);
    await loadPlans();
    setRefreshing(false);
  }, [loadPlans]);

  // 执行 What-If 分析
  const handleWhatIfAnalysis = async () => {
    if (!selectedPlanId || !whatIfScenario) {
      Alert.alert('提示', '请先选择计划和场景');
      return;
    }

    setAnalyzingWhatIf(true);
    try {
      const request: WhatIfRequest = {
        scenario: whatIfScenario,
        parameters: {
          planId: selectedPlanId,
          // 根据场景添加默认参数
          ...(whatIfScenario === 'add_worker' && { count: 2 }),
          ...(whatIfScenario === 'remove_worker' && { count: 1 }),
          ...(whatIfScenario === 'change_shift' && { newShift: 'full_day' }),
        },
      };

      const result = await schedulingOptimizationApiClient.whatIf(request);
      if (result.success && result.data) {
        setWhatIfResult(result.data);
      } else {
        throw new Error(result.message || 'What-If 分析失败');
      }
    } catch (err) {
      console.error('What-If 分析失败:', err);
      Alert.alert('错误', err instanceof Error ? err.message : 'What-If 分析失败');
    } finally {
      setAnalyzingWhatIf(false);
    }
  };

  // AI 自动重排暂不可用；只在当前 SchedulingPlan 上记录调整原因。
  const handleSaveAdjustmentReason = async () => {
    if (savingAdjustmentReason.current) return;
    if (!selectedPlanId) {
      Alert.alert('提示', '请先选择需要记录调整原因的计划');
      return;
    }
    if (!adjustmentReason.trim()) {
      Alert.alert('提示', '请输入调整原因');
      return;
    }

    const targetPlanId = selectedPlanId;
    const reason = adjustmentReason.trim();
    savingAdjustmentReason.current = true;
    setIsSavingAdjustmentReason(true);
    try {
      const fresh = await schedulingApiClient.getPlan(targetPlanId);
      if (!fresh.success || !fresh.data || fresh.data.id !== targetPlanId
          || !REASON_PLAN_STATUSES.includes(fresh.data.status.toLowerCase())) {
        throw new Error('计划状态已变化，请刷新后重新选择。');
      }
      const result = await schedulingApiClient.reschedule({
        planId: targetPlanId,
        reason,
      });
      if (result.success && result.data?.id === targetPlanId) {
        Alert.alert('成功', '调整原因已保存，排程时间和人员未变。', [
          {
            text: '确定',
            onPress: () => {
              setAdjustmentReason('');
              onRefresh();
            },
          },
        ]);
      } else if (result.success) {
        Alert.alert('结果待核对', '保存结果未确认。请刷新调度计划核对原因，避免重复保存。');
      } else {
        Alert.alert('失败', result.message || '记录调整原因失败');
      }
    } catch (err) {
      Alert.alert('错误', err instanceof Error ? err.message : '记录调整原因失败');
    } finally {
      savingAdjustmentReason.current = false;
      setIsSavingAdjustmentReason(false);
    }
  };

  // 获取状态颜色
  const getStatusColor = (status: string) => {
    switch (status.toLowerCase()) {
      case 'confirmed': return DISPATCHER_THEME.primary;
      case 'in_progress': return DISPATCHER_THEME.success;
      case 'completed': return '#52c41a';
      case 'cancelled': return '#999';
      default: return '#666';
    }
  };

  // 获取状态文本
  const getStatusText = (status: string) => {
    switch (status.toLowerCase()) {
      case 'draft': return '草稿';
      case 'confirmed': return '已确认';
      case 'in_progress': return '进行中';
      case 'completed': return '已完成';
      case 'cancelled': return '已取消';
      default: return status;
    }
  };

  // 格式化变化值
  const formatChange = (value: number) => {
    const percent = Math.round(value * 100);
    if (percent > 0) return `+${percent}%`;
    if (percent < 0) return `${percent}%`;
    return '0%';
  };

  // 获取变化颜色
  const getChangeColor = (value: number) => {
    if (value > 0) return DISPATCHER_THEME.success;
    if (value < 0) return DISPATCHER_THEME.danger;
    return '#999';
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      {/* Header */}
      <LinearGradient
        colors={[DISPATCHER_THEME.primary, DISPATCHER_THEME.secondary, DISPATCHER_THEME.accent]}
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

        <View style={styles.headerIcon}>
          <MaterialCommunityIcons name="calendar-refresh" size={32} color="#fff" />
        </View>
        <Text style={styles.headerTitle}>调整原因记录</Text>
        <Text style={styles.headerSubtitle}>记录调整原因 + What-If 分析</Text>
      </LinearGradient>

      {/* Loading State */}
      {loading && (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color={DISPATCHER_THEME.primary} />
          <Text style={styles.loadingText}>正在加载计划列表...</Text>
        </View>
      )}

      {/* Error State */}
      {error && !loading && (
        <View style={styles.errorContainer}>
          <MaterialCommunityIcons name="alert-circle-outline" size={48} color={DISPATCHER_THEME.danger} />
          <Text style={styles.errorText}>{error}</Text>
          <TouchableOpacity style={styles.retryButton} onPress={loadPlans}>
            <Text style={styles.retryButtonText}>重新加载</Text>
          </TouchableOpacity>
        </View>
      )}

      {/* Content */}
      {!loading && !error && (
        <ScrollView
          style={styles.scrollView}
          contentContainerStyle={styles.scrollContent}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={onRefresh}
              colors={[DISPATCHER_THEME.primary]}
              tintColor={DISPATCHER_THEME.primary}
            />
          }
          showsVerticalScrollIndicator={false}
        >
          {/* 选择计划 */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={styles.cardTitleRow}>
                <MaterialCommunityIcons name="clipboard-list" size={20} color="#333" />
                <Text style={styles.cardTitle}>选择计划</Text>
                <View style={styles.badge}>
                  <Text style={styles.badgeText}>{plans.length} 个</Text>
                </View>
              </View>
            </View>

            {plans.length === 0 ? (
              <View style={styles.emptyState}>
                <MaterialCommunityIcons name="calendar-blank" size={48} color="#ccc" />
                <Text style={styles.emptyText}>暂无可记录调整原因的计划</Text>
              </View>
            ) : (
              plans.map((plan) => (
                <TouchableOpacity
                  key={plan.id}
                  testID={`scheduling-plan-${plan.id}`}
                  style={[
                    styles.planItem,
                    selectedPlanId === plan.id && styles.planItemSelected,
                  ]}
                  onPress={() => setSelectedPlanId(plan.id)}
                  disabled={isSavingAdjustmentReason}
                >
                  <View style={[
                    styles.radioButton,
                    selectedPlanId === plan.id && styles.radioButtonSelected,
                  ]}>
                    {selectedPlanId === plan.id && (
                      <View style={styles.radioButtonInner} />
                    )}
                  </View>
                  <View style={styles.planInfo}>
                    <View style={styles.planHeader}>
                      <Text style={styles.planDate}>{plan.planDate}</Text>
                      <View style={[styles.statusBadge, { backgroundColor: getStatusColor(plan.status) + '20' }]}>
                        <Text style={[styles.statusText, { color: getStatusColor(plan.status) }]}>
                          {getStatusText(plan.status)}
                        </Text>
                      </View>
                    </View>
                    <Text style={styles.planMeta}>计划：{plan.planName || '名称未提供'}</Text>
                  </View>
                </TouchableOpacity>
              ))
            )}
          </View>

          {/* 调整原因记录 */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={styles.cardTitleRow}>
                <MaterialCommunityIcons name="note-edit-outline" size={20} color="#333" />
                <Text style={styles.cardTitle}>调整原因记录</Text>
              </View>
            </View>
            <Text style={styles.adjustmentHint}>
              AI 自动重排暂不可用。这里只记录调整原因，计划时间和人员资源不会改变。
            </Text>
            {plans.find((plan) => plan.id === selectedPlanId)?.notes ? (
              <Text testID="selected-plan-notes" style={styles.adjustmentHint}>
                备注与调整记录：{plans.find((plan) => plan.id === selectedPlanId)?.notes}
              </Text>
            ) : null}
            <TextInput
              testID="adjustment-reason-input"
              style={styles.adjustmentReasonInput}
              placeholder="请输入调整原因"
              placeholderTextColor="#999"
              value={adjustmentReason}
              onChangeText={setAdjustmentReason}
              multiline
              numberOfLines={3}
              textAlignVertical="top"
              editable={!isSavingAdjustmentReason}
            />
            <TouchableOpacity
              testID="adjustment-reason-save"
              style={[styles.rescheduleButton, (!selectedPlanId || !adjustmentReason.trim()) && styles.rescheduleButtonDisabled]}
              onPress={handleSaveAdjustmentReason}
              disabled={!selectedPlanId || !adjustmentReason.trim() || isSavingAdjustmentReason}
            >
              {isSavingAdjustmentReason ? (
                <ActivityIndicator color="#fff" size="small" />
              ) : (
                <Text style={styles.rescheduleButtonText}>保存调整原因</Text>
              )}
            </TouchableOpacity>
          </View>

          {/* What-If 分析 */}
          <View style={styles.card}>
            <View style={styles.cardHeader}>
              <View style={styles.cardTitleRow}>
                <MaterialCommunityIcons name="flask" size={20} color="#333" />
                <Text style={styles.cardTitle}>What-If 分析</Text>
                <View style={styles.aiBadge}>
                  <Text style={styles.aiBadgeText}>AI模拟</Text>
                </View>
              </View>
              <TouchableOpacity onPress={() => setShowWhatIfModal(true)}>
                <Text style={styles.actionLink}>开始分析</Text>
              </TouchableOpacity>
            </View>

            {whatIfResult ? (
              <View style={styles.whatIfResultContainer}>
                <Text style={styles.whatIfScenario}>场景: {whatIfResult.scenario}</Text>
                <View style={styles.metricsComparison}>
                  <View style={styles.metricItem}>
                    <Text style={styles.metricLabel}>完成概率</Text>
                    <Text style={styles.metricValue}>
                      {Math.round(whatIfResult.originalMetrics.completionProbability * 100)}%
                      {' -> '}
                      {Math.round(whatIfResult.projectedMetrics.completionProbability * 100)}%
                    </Text>
                    <Text style={[styles.metricChange, { color: getChangeColor(whatIfResult.impact.completionChange) }]}>
                      {formatChange(whatIfResult.impact.completionChange)}
                    </Text>
                  </View>
                  <View style={styles.metricItem}>
                    <Text style={styles.metricLabel}>效率</Text>
                    <Text style={styles.metricValue}>
                      {Math.round(whatIfResult.originalMetrics.efficiency * 100)}%
                      {' -> '}
                      {Math.round(whatIfResult.projectedMetrics.efficiency * 100)}%
                    </Text>
                    <Text style={[styles.metricChange, { color: getChangeColor(whatIfResult.impact.efficiencyChange) }]}>
                      {formatChange(whatIfResult.impact.efficiencyChange)}
                    </Text>
                  </View>
                  <View style={styles.metricItem}>
                    <Text style={styles.metricLabel}>公平性</Text>
                    <Text style={styles.metricValue}>
                      {Math.round(whatIfResult.originalMetrics.fairnessScore * 100)}
                      {' -> '}
                      {Math.round(whatIfResult.projectedMetrics.fairnessScore * 100)}
                    </Text>
                    <Text style={[styles.metricChange, { color: getChangeColor(whatIfResult.impact.fairnessChange) }]}>
                      {formatChange(whatIfResult.impact.fairnessChange)}
                    </Text>
                  </View>
                </View>
                <View style={styles.recommendationBox}>
                  <MaterialCommunityIcons name="lightbulb-on" size={16} color={DISPATCHER_THEME.success} />
                  <Text style={styles.recommendationText}>{whatIfResult.recommendation}</Text>
                </View>
                {whatIfResult.risks && whatIfResult.risks.length > 0 && (
                  <View style={styles.risksBox}>
                    <Text style={styles.risksTitle}>潜在风险:</Text>
                    {whatIfResult.risks.map((risk, index) => (
                      <Text key={index} style={styles.riskText}>- {risk}</Text>
                    ))}
                  </View>
                )}
              </View>
            ) : (
              <View style={styles.whatIfPlaceholder}>
                <MaterialCommunityIcons name="flask-outline" size={40} color="#ccc" />
                <Text style={styles.whatIfPlaceholderText}>
                  点击"开始分析"模拟不同场景对排程的影响
                </Text>
              </View>
            )}
          </View>

          <View style={{ height: 100 }} />
        </ScrollView>
      )}

      {/* What-If Modal */}
      <Modal
        visible={showWhatIfModal}
        transparent
        animationType="slide"
      >
        <View style={styles.modalOverlay}>
          <View style={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.modalTitle}>选择分析场景</Text>
              <TouchableOpacity onPress={() => setShowWhatIfModal(false)}>
                <MaterialCommunityIcons name="close" size={24} color="#666" />
              </TouchableOpacity>
            </View>

            <ScrollView style={styles.modalBody}>
              {WHATIF_SCENARIOS.map((scenario) => (
                <TouchableOpacity
                  key={scenario.value}
                  style={[
                    styles.scenarioItem,
                    whatIfScenario === scenario.value && styles.scenarioItemSelected,
                  ]}
                  onPress={() => setWhatIfScenario(scenario.value)}
                >
                  <View style={[
                    styles.scenarioIcon,
                    whatIfScenario === scenario.value && styles.scenarioIconSelected,
                  ]}>
                    <MaterialCommunityIcons
                      name={scenario.icon}
                      size={24}
                      color={whatIfScenario === scenario.value ? '#fff' : DISPATCHER_THEME.primary}
                    />
                  </View>
                  <Text style={styles.scenarioLabel}>{scenario.label}</Text>
                  {whatIfScenario === scenario.value && (
                    <MaterialCommunityIcons name="check" size={20} color={DISPATCHER_THEME.primary} />
                  )}
                </TouchableOpacity>
              ))}
            </ScrollView>

            <View style={styles.modalFooter}>
              <TouchableOpacity
                style={styles.modalCancelBtn}
                onPress={() => setShowWhatIfModal(false)}
              >
                <Text style={styles.modalCancelText}>取消</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalConfirmBtn, !whatIfScenario && styles.modalConfirmBtnDisabled]}
                onPress={() => {
                  setShowWhatIfModal(false);
                  handleWhatIfAnalysis();
                }}
                disabled={!whatIfScenario || analyzingWhatIf}
              >
                {analyzingWhatIf ? (
                  <ActivityIndicator color="#fff" size="small" />
                ) : (
                  <Text style={styles.modalConfirmText}>开始分析</Text>
                )}
              </TouchableOpacity>
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
    paddingVertical: 20,
    paddingHorizontal: 16,
    alignItems: 'center',
  },
  backButton: {
    position: 'absolute',
    left: 12,
    top: 16,
    padding: 4,
  },
  headerIcon: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: 'rgba(255,255,255,0.2)',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#fff',
    marginBottom: 4,
  },
  headerSubtitle: {
    fontSize: 14,
    color: 'rgba(255,255,255,0.9)',
  },
  loadingContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    fontSize: 14,
    color: '#666',
    marginTop: 12,
  },
  errorContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 20,
  },
  errorText: {
    fontSize: 14,
    color: DISPATCHER_THEME.danger,
    marginTop: 12,
    textAlign: 'center',
  },
  retryButton: {
    marginTop: 20,
    backgroundColor: DISPATCHER_THEME.primary,
    paddingHorizontal: 24,
    paddingVertical: 10,
    borderRadius: 8,
  },
  retryButtonText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '500',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
  },
  card: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    shadowColor: DISPATCHER_THEME.primary,
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 8,
    elevation: 3,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 16,
  },
  cardTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '500',
    color: '#333',
  },
  badge: {
    backgroundColor: DISPATCHER_THEME.primary + '20',
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 10,
  },
  badgeText: {
    fontSize: 11,
    color: DISPATCHER_THEME.primary,
  },
  aiBadge: {
    backgroundColor: DISPATCHER_THEME.primary,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  aiBadgeText: {
    fontSize: 10,
    fontWeight: '500',
    color: '#fff',
  },
  actionLink: {
    fontSize: 12,
    color: DISPATCHER_THEME.primary,
  },
  emptyState: {
    alignItems: 'center',
    padding: 24,
  },
  emptyText: {
    fontSize: 14,
    color: '#999',
    marginTop: 12,
  },
  planItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    backgroundColor: '#f9f9f9',
    borderRadius: 8,
    marginBottom: 8,
    gap: 12,
  },
  planItemSelected: {
    backgroundColor: DISPATCHER_THEME.primary + '10',
    borderWidth: 1,
    borderColor: DISPATCHER_THEME.primary,
  },
  radioButton: {
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: '#d9d9d9',
    alignItems: 'center',
    justifyContent: 'center',
  },
  radioButtonSelected: {
    borderColor: DISPATCHER_THEME.primary,
  },
  radioButtonInner: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: DISPATCHER_THEME.primary,
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
  planDate: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  statusText: {
    fontSize: 11,
    fontWeight: '500',
  },
  planMeta: {
    fontSize: 12,
    color: '#999',
  },
  whatIfPlaceholder: {
    alignItems: 'center',
    padding: 24,
  },
  whatIfPlaceholderText: {
    fontSize: 13,
    color: '#999',
    textAlign: 'center',
    marginTop: 12,
  },
  adjustmentHint: {
    fontSize: 13,
    color: '#666',
    lineHeight: 20,
    marginBottom: 12,
  },
  adjustmentReasonInput: {
    borderWidth: 1,
    borderColor: '#e0e0e0',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    color: '#333',
    minHeight: 80,
    marginBottom: 12,
  },
  whatIfResultContainer: {
    padding: 12,
    backgroundColor: '#f9f9f9',
    borderRadius: 8,
  },
  whatIfScenario: {
    fontSize: 13,
    color: '#666',
    marginBottom: 12,
  },
  metricsComparison: {
    flexDirection: 'row',
    gap: 8,
  },
  metricItem: {
    flex: 1,
    backgroundColor: '#fff',
    padding: 10,
    borderRadius: 8,
    alignItems: 'center',
  },
  metricLabel: {
    fontSize: 11,
    color: '#999',
    marginBottom: 4,
  },
  metricValue: {
    fontSize: 12,
    color: '#333',
    fontWeight: '500',
  },
  metricChange: {
    fontSize: 11,
    fontWeight: '600',
    marginTop: 4,
  },
  recommendationBox: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    backgroundColor: '#f6ffed',
    padding: 10,
    borderRadius: 8,
    marginTop: 12,
    gap: 8,
  },
  recommendationText: {
    flex: 1,
    fontSize: 12,
    color: '#52c41a',
    lineHeight: 18,
  },
  risksBox: {
    backgroundColor: '#fff2f0',
    padding: 10,
    borderRadius: 8,
    marginTop: 8,
  },
  risksTitle: {
    fontSize: 12,
    fontWeight: '500',
    color: DISPATCHER_THEME.danger,
    marginBottom: 4,
  },
  riskText: {
    fontSize: 11,
    color: '#666',
    marginTop: 2,
  },
  rescheduleButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 16,
    borderRadius: 12,
    backgroundColor: DISPATCHER_THEME.primary,
    gap: 8,
  },
  rescheduleButtonDisabled: {
    backgroundColor: '#bfbfbf',
  },
  rescheduleButtonText: {
    fontSize: 16,
    fontWeight: '600',
    color: '#fff',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'flex-end',
  },
  modalContent: {
    backgroundColor: '#fff',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '70%',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
  },
  modalBody: {
    padding: 16,
  },
  scenarioItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 14,
    backgroundColor: '#f9f9f9',
    borderRadius: 10,
    marginBottom: 10,
    gap: 12,
  },
  scenarioItemSelected: {
    backgroundColor: DISPATCHER_THEME.primary + '10',
    borderWidth: 1,
    borderColor: DISPATCHER_THEME.primary,
  },
  scenarioIcon: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: DISPATCHER_THEME.primary + '15',
    alignItems: 'center',
    justifyContent: 'center',
  },
  scenarioIconSelected: {
    backgroundColor: DISPATCHER_THEME.primary,
  },
  scenarioLabel: {
    flex: 1,
    fontSize: 15,
    color: '#333',
  },
  modalFooter: {
    flexDirection: 'row',
    padding: 16,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: '#f0f0f0',
  },
  modalCancelBtn: {
    flex: 1,
    padding: 14,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#d9d9d9',
    alignItems: 'center',
  },
  modalCancelText: {
    fontSize: 15,
    color: '#666',
  },
  modalConfirmBtn: {
    flex: 1,
    padding: 14,
    borderRadius: 8,
    backgroundColor: DISPATCHER_THEME.primary,
    alignItems: 'center',
  },
  modalConfirmBtnDisabled: {
    backgroundColor: '#d9d9d9',
  },
  modalConfirmText: {
    fontSize: 15,
    fontWeight: '500',
    color: '#fff',
  },
});
