<script setup lang="ts">
/**
 * 调度计划详情页
 * 包含甘特图、排程列表、工人分配、概率监控
 */
import { ref, onMounted, computed, watch, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  getSchedulingPlan,
  calculateBatchProbabilities,
  startSchedule,
  completeSchedule,
  updateScheduleProgress,
  reschedule,
  SchedulingPlan,
  LineSchedule,
  CompletionProbability,
  type ScheduleTaskTimelineEntry
} from '@/api/scheduling';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ArrowLeft,
  Refresh,
  Timer,
  User,
  Operation,
  Check,
  Edit,
  Loading
} from '@element-plus/icons-vue';
import echarts from '@/utils/echarts';
import SchedulingDiagnosticsPanel from './SchedulingDiagnosticsPanel.vue';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const canRecordReason = computed(() => permissionStore.canWrite('scheduling') || permissionStore.canWrite('production'));
const factoryId = computed(() => authStore.factoryId);
const planId = computed(() => route.params.id as string);

const loading = ref(false);
const plan = ref<SchedulingPlan | null>(null);

/**
 * 自动排程的提示 (缺工时 / 没有工艺图 / 默认 08:00 开工): 后端只在生成那次返回,
 * 创建页跳转时经 history.state 带过来; 直接打开详情页时没有。
 */
function readApsWarnings(): string[] {
  const state = typeof window !== 'undefined' ? (window.history.state as { apsWarnings?: unknown } | null) : null;
  const raw = state?.apsWarnings;
  return Array.isArray(raw) ? raw.filter((w): w is string => typeof w === 'string' && w.trim().length > 0) : [];
}
const apsWarnings = ref<string[]>(readApsWarnings());
const warningsMentionProcessSetup = computed(() => apsWarnings.value.some(w => w.includes('工序管理')));
// v1 (按工艺图) 的旧排程仍按工序时间线展示; v2 (按日产能) 的排程看下面的按天表。
const timelineSchedules = computed(() => (plan.value?.lineSchedules || [])
  .filter(s => Array.isArray(s.taskTimeline) && s.taskTimeline.length > 0));

/** APS v2: 有按天分配的排程。 */
const dailySchedules = computed(() => (plan.value?.lineSchedules || [])
  .filter(s => s.dailyAllocations && Array.isArray(s.dailyAllocations.days) && s.dailyAllocations.days.length > 0));

/** 哪天做哪个计划、做多少: 所有排程的按天分配摊平, 按日期 → 排程顺序。 */
const dailyRows = computed(() => {
  const rows: Array<{ key: string; date: string; label: string; quantity: number; unit: string; share: number; order: number }> = [];
  for (const s of dailySchedules.value) {
    const alloc = s.dailyAllocations!;
    for (const day of alloc.days) {
      rows.push({
        key: `${s.id}-${day.date}`,
        date: day.date,
        label: scheduleLabel(s),
        quantity: Number(day.quantity),
        unit: alloc.unit,
        share: Number(day.shareOfDay),
        order: s.sequenceOrder ?? 0,
      });
    }
  }
  return rows.sort((a, b) => a.date.localeCompare(b.date) || a.order - b.order);
});

function formatQty(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '—';
  return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

function formatShare(share: number) {
  return Number.isFinite(share) ? `${Math.round(share * 100)}%` : '—';
}

function scheduleLabel(s: LineSchedule) {
  if (s.batchNumber) return s.batchNumber;
  if (s.planNumber) return `计划 ${s.planNumber}`;
  return '未标明';
}

/** 时间线时刻: 与计划日期同一天只显示 HH:mm, 跨天带上月-日。 */
function formatTimelineTime(iso: string | null | undefined) {
  if (!iso) return '—';
  const day = iso.slice(0, 10);
  const clock = iso.slice(11, 16);
  return plan.value?.planDate && day !== plan.value.planDate ? `${day.slice(5)} ${clock}` : clock;
}

function timelineStatusText(status: ScheduleTaskTimelineEntry['status']) {
  const map: Record<string, string> = {
    SCHEDULED: '已排',
    DONE: '已完成（不占时间）',
    MISSING_DURATION: '缺预计工时',
    BLOCKED_BY_MISSING: '等上游工时',
  };
  return map[status] || status;
}

function goWorkProcesses() {
  router.push('/system/work-processes');
}
const probabilities = ref<CompletionProbability[]>([]);
const ganttChart = ref<echarts.ECharts | null>(null);
const ganttContainer = ref<HTMLElement | null>(null);
const refreshInterval = ref<number | null>(null);

// 选中的排程
const selectedSchedule = ref<LineSchedule | null>(null);
const progressDialogVisible = ref(false);
const progressValue = ref(0);
const recordingReason = ref(false);

async function recordAdjustmentReason() {
  const targetFactory = factoryId.value;
  const targetPlan = planId.value;
  if (!canRecordReason.value) return;
  if (!targetFactory || !targetPlan || plan.value?.id !== targetPlan || recordingReason.value) return;
  recordingReason.value = true;
  try {
    const { value } = await ElMessageBox.prompt(
      '仅保存调整原因，不修改时间或资源，也不会重算排程或恢复交期。',
      '记录调整原因',
      { confirmButtonText: '保存原因', cancelButtonText: '取消', inputType: 'textarea',
        inputValidator: (input: string) => !!input.trim() || '请输入调整原因' }
    );
    const reason = value.trim();
    if (!canRecordReason.value) return;
    if (!reason || targetFactory !== factoryId.value || targetPlan !== planId.value) return;
    const response = await reschedule(targetFactory, { planId: targetPlan, reason });
    if (!response.success) {
      ElMessage.error(response.message || '调整原因未保存');
      return;
    }
    if (targetFactory !== factoryId.value || targetPlan !== planId.value) return;
    await loadData();
    ElMessage.success('已记录调整原因，排程时间和资源未改变');
  } catch (error: unknown) {
    if (error !== 'cancel' && error !== 'close') console.error('记录调整原因失败:', error);
  } finally {
    recordingReason.value = false;
  }
}

onMounted(async () => {
  await loadData();
  initGanttChart();
  // 每分钟刷新
  refreshInterval.value = window.setInterval(() => {
    loadData(true);
  }, 60000);
});

let resizeRaf = 0;
function handleResize() {
  if (resizeRaf) return;
  resizeRaf = requestAnimationFrame(() => {
    ganttChart.value?.resize();
    resizeRaf = 0;
  });
}

let resizeObserver: ResizeObserver | null = null;

onUnmounted(() => {
  resizeObserver?.disconnect();
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value);
  }
  window.removeEventListener('resize', handleResize);
  if (ganttChart.value) {
    ganttChart.value.dispose();
  }
});

watch(() => plan.value, () => {
  updateGanttChart();
}, { deep: true });

async function loadData(silent = false) {
  if (!factoryId.value || !planId.value) return;

  if (!silent) {
    loading.value = true;
  }

  try {
    const [planRes, probRes] = await Promise.all([
      getSchedulingPlan(factoryId.value, planId.value),
      calculateBatchProbabilities(factoryId.value, planId.value)
    ]);

    if (planRes.success && planRes.data) {
      plan.value = planRes.data;
    }
    if (probRes.success && probRes.data) {
      probabilities.value = probRes.data;
    }
  } catch (error: any) {
    console.error('加载失败:', error);
    if (!silent) {
      if (!error?.actionHint) ElMessage.error('加载数据失败');
    }
  } finally {
    loading.value = false;
  }
}

function initGanttChart() {
  if (!ganttContainer.value) return;

  ganttChart.value = echarts.init(ganttContainer.value, 'cretas');
  updateGanttChart();

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize);
    resizeObserver.observe(ganttContainer.value);
  }
  window.addEventListener('resize', handleResize);
}

function updateGanttChart() {
  if (!ganttChart.value || !plan.value?.lineSchedules?.length) return;

  const schedules = plan.value.lineSchedules;
  const lines = [...new Set(schedules.map(s => s.productionLineName))];

  // 计算时间范围
  const times = schedules.flatMap(s => [
    new Date(s.plannedStartTime).getTime(),
    new Date(s.plannedEndTime).getTime()
  ]);
  const minTime = Math.min(...times);
  const maxTime = Math.max(...times);

  // 生成数据
  const data = schedules.map(s => {
    const lineIndex = lines.indexOf(s.productionLineName);
    const startTime = new Date(s.plannedStartTime).getTime();
    const endTime = new Date(s.plannedEndTime).getTime();
    const prob = s.predictedCompletionProb || 0;

    let color = '#409EFF'; // 默认蓝色
    if (s.status === 'completed') {
      color = '#67C23A'; // 绿色
    } else if (s.status === 'in_progress') {
      color = '#E6A23C'; // 黄色
    } else if (s.status === 'delayed' || prob < 0.7) {
      color = '#F56C6C'; // 红色
    }

    return {
      name: scheduleLabel(s),
      value: [lineIndex, startTime, endTime, s.completionRate || 0],
      itemStyle: { color },
      schedule: s
    };
  });

  const option: echarts.EChartsOption = {
    tooltip: {
      confine: true,
      formatter: ((params: unknown) => {
        const p = params as { data: Record<string, unknown> };
        const schedule = p.data.schedule as LineSchedule;
        const start = new Date(schedule.plannedStartTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        const end = new Date(schedule.plannedEndTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        const prob = schedule.predictedCompletionProb
          ? (schedule.predictedCompletionProb * 100).toFixed(0) + '%'
          : '-';

        return `
          <div style="padding: 8px;">
            <div style="font-weight: bold; margin-bottom: 8px;">${scheduleLabel(schedule)}</div>
            <div>产线: ${schedule.productionLineName}</div>
            <div>时间: ${start} - ${end}</div>
            <div>进度: ${schedule.completedQuantity || 0}/${schedule.targetQuantity}</div>
            <div>完成概率: ${prob}</div>
            <div>工人: ${schedule.assignedWorkerCount || 0} 人</div>
          </div>
        `;
      }) as never
    },
    grid: {
      left: 120,
      right: 40,
      top: 40,
      bottom: 40
    },
    xAxis: {
      type: 'time',
      min: minTime - 3600000, // 1小时前
      max: maxTime + 3600000, // 1小时后
      axisLabel: {
        formatter: (value: number) => {
          return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        }
      }
    },
    yAxis: {
      type: 'category',
      data: lines,
      axisLabel: {
        width: 100,
        overflow: 'truncate'
      }
    },
    series: [{
      type: 'custom',
      renderItem: ((_params: unknown, api: unknown) => {
        const a = api as {
          value: (idx: number) => number;
          coord: (xy: [number, number]) => [number, number];
          size: (xy: [number, number]) => [number, number];
          style: () => unknown;
        };
        const lineIndex = a.value(0);
        const startTime = a.value(1);
        const endTime = a.value(2);
        const progress = a.value(3);

        const startCoord = a.coord([startTime, lineIndex]);
        const endCoord = a.coord([endTime, lineIndex]);
        const height = a.size([0, 1])[1] * 0.6;

        const rectShape = {
          x: startCoord[0],
          y: startCoord[1] - height / 2,
          width: endCoord[0] - startCoord[0],
          height: height
        };

        return {
          type: 'group',
          children: [
            // 背景条
            {
              type: 'rect',
              shape: rectShape,
              style: a.style(),
              styleEmphasis: { shadowBlur: 10, shadowColor: 'rgba(0, 0, 0, 0.3)' }
            },
            // 进度条
            {
              type: 'rect',
              shape: {
                ...rectShape,
                width: rectShape.width * (progress / 100)
              },
              style: {
                fill: 'rgba(255, 255, 255, 0.3)'
              }
            }
          ]
        };
      }) as never,
      data: data,
      encode: {
        x: [1, 2],
        y: 0
      }
    }]
  };

  ganttChart.value.setOption(option);

  // 点击事件
  ganttChart.value.on('click', (params: Record<string, unknown>) => {
    const data = params.data as Record<string, unknown> | undefined;
    if (data?.schedule) {
      selectedSchedule.value = data.schedule as LineSchedule;
    }
  });
}

function goBack() {
  router.push('/scheduling/plans');
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    draft: 'info',
    confirmed: 'primary',
    in_progress: 'warning',
    completed: 'success',
    cancelled: 'danger',
    pending: 'info',
    delayed: 'danger'
  };
  return map[status] || 'info';
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    draft: '草稿',
    confirmed: '已确认',
    in_progress: '进行中',
    completed: '已完成',
    cancelled: '已取消',
    pending: '待开始',
    delayed: '已延期'
  };
  return map[status] || status;
}

function formatProbability(prob: number) {
  if (prob === null || prob === undefined) return '-';
  return (prob * 100).toFixed(0) + '%';
}

function getProbabilityColor(prob: number) {
  if (prob >= 0.9) return '#67C23A';
  if (prob >= 0.7) return '#E6A23C';
  if (prob >= 0.5) return '#F56C6C';
  return '#909399';
}

function getRiskLevel(prob: number) {
  if (prob >= 0.9) return '低风险';
  if (prob >= 0.7) return '中风险';
  if (prob >= 0.5) return '高风险';
  return '极高风险';
}

async function handleStartSchedule(schedule: LineSchedule) {
  try {
    await ElMessageBox.confirm(
      `确认开始 "${schedule.batchNumber}" 的生产吗？`,
      '开始生产',
      { type: 'info' }
    );

    loading.value = true;
    const response = await startSchedule(factoryId.value!, schedule.id);
    if (response.success) {
      ElMessage.success('已开始生产');
      loadData();
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleUpdateProgress(schedule: LineSchedule) {
  selectedSchedule.value = schedule;
  progressValue.value = schedule.completedQuantity || 0;
  progressDialogVisible.value = true;
}

async function submitProgress() {
  if (!selectedSchedule.value) return;

  try {
    loading.value = true;
    const response = await updateScheduleProgress(
      factoryId.value!,
      selectedSchedule.value.id,
      progressValue.value
    );
    if (response.success) {
      ElMessage.success('进度已更新');
      progressDialogVisible.value = false;
      loadData();
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('更新失败:', error);
  } finally {
    loading.value = false;
  }
}

async function handleCompleteSchedule(schedule: LineSchedule) {
  try {
    const { value: quantity } = await ElMessageBox.prompt(
      '请输入实际完成数量',
      '完成生产',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        inputValue: String(schedule.targetQuantity),
        inputPattern: /^\d+$/,
        inputErrorMessage: '请输入有效数字'
      }
    );

    loading.value = true;
    const response = await completeSchedule(factoryId.value!, schedule.id, Number(quantity));
    if (response.success) {
      ElMessage.success('已完成生产');
      loadData();
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    if (error !== 'cancel') console.error('操作失败:', error);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="plan-detail-page" v-loading="loading">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1>调度计划详情</h1>
        <el-tag v-if="plan" :type="getStatusType(plan.status)" size="large">
          {{ getStatusText(plan.status) }}
        </el-tag>
      </div>
      <div class="header-right">
        <el-button v-if="canRecordReason && plan" data-testid="record-adjustment-reason"
          :disabled="loading || recordingReason" @click="recordAdjustmentReason">记录调整原因</el-button>
        <el-button :icon="Refresh" @click="loadData(false)">刷新</el-button>
      </div>
    </div>

    <div v-if="apsWarnings.length" class="aps-warnings" data-testid="aps-warnings">
      <el-alert
        v-for="(warning, index) in apsWarnings"
        :key="index"
        :title="warning"
        type="warning"
        show-icon
        :closable="false"
      />
      <el-button v-if="warningsMentionProcessSetup" type="primary" link @click="goWorkProcesses">
        去工序管理填预计工时
      </el-button>
    </div>

    <el-card v-if="plan" data-testid="plan-notes">
      <template #header>计划备注与调整原因</template>
      <p>这里只记录原因，排程时间和资源未改变。</p>
      <p style="white-space: pre-wrap">{{ plan.notes || '暂无备注' }}</p>
    </el-card>

    <!-- 计划概览 -->
    <div class="plan-overview" v-if="plan">
      <el-card class="overview-card">
        <div class="overview-item">
          <el-icon class="overview-icon"><Timer /></el-icon>
          <div class="overview-content">
            <div class="overview-label">计划日期</div>
            <div class="overview-value">{{ plan.planDate }}</div>
          </div>
        </div>
      </el-card>
      <el-card class="overview-card">
        <div class="overview-item">
          <el-icon class="overview-icon"><Operation /></el-icon>
          <div class="overview-content">
            <div class="overview-label">批次数 / 排程数</div>
            <div class="overview-value">{{ plan.totalBatches }} / {{ plan.lineSchedules?.length || 0 }}</div>
          </div>
        </div>
      </el-card>
      <el-card class="overview-card">
        <div class="overview-item">
          <el-icon class="overview-icon"><User /></el-icon>
          <div class="overview-content">
            <div class="overview-label">工人数</div>
            <div class="overview-value">{{ plan.totalWorkers }}</div>
          </div>
        </div>
      </el-card>
      <el-card class="overview-card">
        <div class="overview-item">
          <div
            class="probability-gauge"
            :style="{ '--prob-color': getProbabilityColor(plan.averageCompletionProbability) }"
          >
            {{ formatProbability(plan.averageCompletionProbability) }}
          </div>
          <div class="overview-content">
            <div class="overview-label">平均完成概率</div>
            <div class="overview-value" :style="{ color: getProbabilityColor(plan.averageCompletionProbability) }">
              {{ getRiskLevel(plan.averageCompletionProbability) }}
            </div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 甘特图 -->
    <el-card class="gantt-card">
      <template #header>
        <div class="card-header">
          <span>排程甘特图</span>
          <div class="legend">
            <span class="legend-item"><span class="legend-color" style="background: #409EFF"></span>待开始</span>
            <span class="legend-item"><span class="legend-color" style="background: #E6A23C"></span>进行中</span>
            <span class="legend-item"><span class="legend-color" style="background: #67C23A"></span>已完成</span>
            <span class="legend-item"><span class="legend-color" style="background: #F56C6C"></span>延期/风险</span>
          </div>
        </div>
      </template>
      <div ref="ganttContainer" class="gantt-container"></div>
    </el-card>

    <!-- 排程列表 -->
    <el-card class="schedule-card">
      <template #header>
        <span>排程列表</span>
      </template>
      <el-table :data="plan?.lineSchedules || []" stripe border>
        <el-table-column prop="productionLineName" label="产线" width="150" />
        <el-table-column label="批次 / 计划" width="160">
          <template #default="{ row }">{{ scheduleLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="时间" width="200">
          <template #default="{ row }">
            {{ new Date(row.plannedStartTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}
            -
            {{ new Date(row.plannedEndTime).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress
              :percentage="row.completionRate || 0"
              :stroke-width="10"
            />
            <span class="progress-text">{{ row.completedQuantity ?? '未知' }}/{{ row.targetQuantity ?? '未知' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="工人" width="80" align="center">
          <template #default="{ row }">{{ row.assignedWorkerCount ?? '未知' }}</template>
        </el-table-column>
        <el-table-column prop="predictedCompletionProb" label="完成概率" width="100" align="center">
          <template #default="{ row }">
            <span :style="{ color: getProbabilityColor(row.predictedCompletionProb), fontWeight: 600 }">
              {{ formatProbability(row.predictedCompletionProb) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              type="primary"
              link
              size="small"
              :icon="Loading"
              @click="handleStartSchedule(row)"
            >
              开始
            </el-button>
            <el-button
              v-if="row.status === 'in_progress'"
              type="warning"
              link
              size="small"
              :icon="Edit"
              @click="handleUpdateProgress(row)"
            >
              更新进度
            </el-button>
            <el-button
              v-if="row.status === 'in_progress'"
              type="success"
              link
              size="small"
              :icon="Check"
              @click="handleCompleteSchedule(row)"
            >
              完成
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 自动排程 (按日产能): 哪天做哪个计划、做多少 -->
    <el-card v-if="dailySchedules.length" class="timeline-card" data-testid="daily-allocations">
      <template #header>
        <span>按天排程 — 哪天做哪个计划、做多少</span>
      </template>
      <ul class="daily-capacity-summary">
        <li v-for="schedule in dailySchedules" :key="`cap-${schedule.id}`" :data-testid="`daily-capacity-${schedule.id}`">
          <strong>{{ scheduleLabel(schedule) }}</strong>：
          日产能 {{ formatQty(schedule.dailyAllocations!.capacityPerDay) }} {{ schedule.dailyAllocations!.unit }}/天
          （{{ schedule.dailyAllocations!.capacitySourceLabel }}）；
          计划量 {{ formatQty(schedule.dailyAllocations!.plannedQuantity) }}，
          已入库 {{ formatQty(schedule.dailyAllocations!.reportedQuantity) }}，
          本次排 {{ formatQty(schedule.dailyAllocations!.remainingQuantity) }} {{ schedule.dailyAllocations!.unit }}
        </li>
      </ul>
      <el-table :data="dailyRows" size="small" border row-key="key">
        <el-table-column label="日期" width="120">
          <template #default="{ row }">{{ row.date }}</template>
        </el-table-column>
        <el-table-column label="计划" min-width="160">
          <template #default="{ row }">{{ row.label }}</template>
        </el-table-column>
        <el-table-column label="做多少" width="160">
          <template #default="{ row }">{{ formatQty(row.quantity) }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="占当天产能" width="120">
          <template #default="{ row }">{{ formatShare(row.share) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 旧的自动排程 (v1 按工艺图): 每条排程的工序时间线 -->
    <el-card
      v-for="schedule in timelineSchedules"
      :key="`timeline-${schedule.id}`"
      class="timeline-card"
      data-testid="task-timeline"
    >
      <template #header>
        <span>工序时间 — {{ scheduleLabel(schedule) }}（{{ schedule.productionLineName || '产线未标明' }}）</span>
      </template>
      <el-table :data="schedule.taskTimeline || []" size="small" border>
        <el-table-column label="工序" min-width="160">
          <template #default="{ row }">{{ row.processOrder }}. {{ row.taskName }}</template>
        </el-table-column>
        <el-table-column label="开始" width="120">
          <template #default="{ row }">{{ formatTimelineTime(row.start) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="120">
          <template #default="{ row }">{{ formatTimelineTime(row.end) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{ row }">{{ timelineStatusText(row.status) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <template v-if="factoryId && plan">
      <SchedulingDiagnosticsPanel
        v-for="schedule in plan.lineSchedules"
        :key="schedule.id"
        :factory-id="factoryId"
        :schedule-id="schedule.id"
      />
    </template>

    <!-- 进度更新对话框 -->
    <el-dialog v-model="progressDialogVisible" title="更新进度" width="400px">
      <div v-if="selectedSchedule">
        <el-form label-width="100px">
          <el-form-item label="批次号">
            <span>{{ selectedSchedule.batchNumber }}</span>
          </el-form-item>
          <el-form-item label="目标数量">
            <span>{{ selectedSchedule.targetQuantity }}</span>
          </el-form-item>
          <el-form-item label="已完成数量">
            <el-input-number
              v-model="progressValue"
              :min="0"
              :max="selectedSchedule.targetQuantity"
              :step="1"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="progressDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProgress" :loading="loading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.plan-detail-page {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100%;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 16px;

    h1 {
      font-size: 20px;
      font-weight: 600;
      color: #303133;
      margin: 0;
    }
  }
}

.plan-overview {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 20px;

  @media (max-width: 1200px) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

.overview-card {
  :deep(.el-card__body) {
    padding: 20px;
  }
}

.overview-item {
  display: flex;
  align-items: center;
  gap: 16px;

  .overview-icon {
    font-size: 32px;
    color: #409EFF;
  }

  .overview-content {
    .overview-label {
      font-size: 12px;
      color: #909399;
      margin-bottom: 4px;
    }

    .overview-value {
      font-size: 20px;
      font-weight: 600;
      color: #303133;
    }
  }

  .probability-gauge {
    width: 60px;
    height: 60px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
    font-weight: 700;
    color: var(--prob-color, #67C23A);
    border: 4px solid var(--prob-color, #67C23A);
  }
}

.gantt-card {
  margin-bottom: 20px;

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .legend {
      display: flex;
      gap: 16px;

      .legend-item {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        color: #606266;

        .legend-color {
          width: 12px;
          height: 12px;
          border-radius: 2px;
        }
      }
    }
  }
}

.gantt-container {
  height: 400px;
}

.schedule-card {
  .progress-text {
    font-size: 12px;
    color: #909399;
    margin-left: 8px;
  }
}

.aps-warnings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}

.timeline-card {
  margin-top: 20px;
}

.daily-capacity-summary {
  margin: 0 0 12px;
  padding-left: 18px;
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}
</style>
