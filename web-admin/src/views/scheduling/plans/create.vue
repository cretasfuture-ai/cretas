<script setup lang="ts">
/**
 * 创建调度计划页面
 * 支持手动创建; 后端能力 aiGeneration = AVAILABLE 时支持「按日产能自动排程」(APS v2, Steve 2026-10-28):
 * 按产品「一天能做多少」按天排, 一天做不完顺延; 人工日产能优先, 没填用历史估算;
 * 两样都没有时后端返回需补清单 (什么都没保存), 本页当场弹出让人填, 填完保存到产品并重新排。不自动派工。
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  createSchedulingPlan,
  generateSchedule,
  getPendingProductionPlans,
  getProductionLines,
  getSchedulingCapabilities,
  type DailyCapacityOverride,
  type DailyCapacityRequired,
  type PendingProductionPlan,
  type SchedulingCapabilities
} from '@/api/scheduling';
import {
  buildCapacityOverrides,
  capacityBlockReason,
  capacityKey,
  type CapacityInputs
} from './dailyCapacityForm';
import {
  buildManualSchedulePayload,
  validateManualSchedules,
  type ManualScheduleDraft
} from './manualSchedulingForm';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ArrowLeft,
  MagicStick,
  Plus,
  Delete
} from '@element-plus/icons-vue';
import type { TableRow } from '@/types/api';
import { toDateString } from '@/utils/dateFormat';
import SchedulingDiagnosticsPanel from './SchedulingDiagnosticsPanel.vue';

const router = useRouter();
const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);

const loading = ref(false);
const batches = ref<TableRow[]>([]);
const productionLines = ref<TableRow[]>([]);
const productionLinesLoadFailed = ref(false);
const capabilities = ref<SchedulingCapabilities | null>(null);
const capabilityLoadFailed = ref(false);

// 表单数据
const form = ref({
  planDate: toDateString(new Date()),
  selectedBatchIds: [] as number[],
  notes: ''
});
const manualDrafts = ref<ManualScheduleDraft[]>([]);
const useAI = ref(false);
const aiUnavailableReason = computed(() => capabilities.value?.aiGeneration?.reason
  || '能力状态加载失败，自动排程保持不可用；请检查网络后重试，或使用人工确认的排程草稿。');
const manualUnavailableReason = computed(() => capabilities.value?.manualCreation?.status === 'UNAVAILABLE'
  ? capabilities.value.manualCreation.reason : '');

// ==================== 按日产能自动排程 ====================
// 只听后端能力: aiGeneration 不是 AVAILABLE (含能力加载失败) 时这个入口不可点。
const aiAvailable = computed(() => capabilities.value?.aiGeneration?.status === 'AVAILABLE');
const pendingPlans = ref<PendingProductionPlan[]>([]);
const pendingPlansLoadFailed = ref(false);
const autoForm = ref({ planIds: [] as string[], productionLineId: '' });
// 两样都没有日产能时当场补填
const capacityDialogVisible = ref(false);
const capacityRequired = ref<DailyCapacityRequired[]>([]);
const capacityInputs = ref<CapacityInputs>({});
const capacityDialogBlockReason = computed(() => capacityBlockReason(capacityRequired.value, capacityInputs.value));

/** 预先告诉用户还差什么 (防呆 Rule 1): 空串 = 可以提交。 */
const autoBlockReason = computed(() => {
  if (autoForm.value.planIds.length === 0) return '请至少选择一个生产计划';
  if (!productionLinesLoadFailed.value && productionLines.value.length === 0) {
    return '工厂还没有可用的产线，不能自动排程；请先新建产线';
  }
  if (productionLines.value.length > 1 && !autoForm.value.productionLineId) {
    return `工厂有 ${productionLines.value.length} 条产线，请选择要排在哪条产线上`;
  }
  return '';
});

onMounted(() => {
  loadBatches();
  loadProductionLines();
  loadCapabilities();
});

async function loadCapabilities() {
  if (!factoryId.value) return;
  try {
    const response = await getSchedulingCapabilities(factoryId.value);
    if (response.success && response.data) capabilities.value = response.data;
    else capabilityLoadFailed.value = true;
    if (aiAvailable.value) await loadPendingPlans();
  } catch (error) {
    capabilityLoadFailed.value = true;
    console.error('加载排程能力失败:', error);
  }
}

// 尚未开工、可被排入调度计划的批次状态。ProductionBatchStatus 枚举没有 PENDING
// (旧代码传 status=PENDING 触发 IllegalArgumentException → 400, 批次列表恒为空,
// 排程计划页面无法创建, module-verify 2026-07-03 抓出)。PLANNED 是当前值,
// PLANNING 是历史遗留同义值 (见 ProductionBatchStatus.java 注释), 两者都算"待调度"。
const SCHEDULABLE_STATUSES = new Set(['PLANNED', 'PLANNING']);

async function loadBatches() {
  if (!factoryId.value) return;

  try {
    // 加载待调度的生产批次。后端单个 status 参数不支持传多值, 这里不传 status
    // 拉全量后前端过滤 PLANNED/PLANNING, 避免漏掉历史 PLANNING 数据。
    // Bug fix (sidebar audit): 后端 @Min(1) 要求 page>=1, 之前 page:0 触发 400 "Page index must not be less than zero"
    const response = await get(`/${factoryId.value}/processing/batches`, {
      params: {
        page: 1,
        size: 200
      }
    });
    if (response.success && response.data) {
      const content = (response.data.content || []) as TableRow[];
      batches.value = content.filter((b) => SCHEDULABLE_STATUSES.has(String(b.status || '').toUpperCase()));
    }
  } catch (error) {
    console.error('加载批次失败:', error);
  }
}

async function loadProductionLines() {
  if (!factoryId.value) return;

  try {
    const response = await getProductionLines(factoryId.value, 'active');
    if (response.success && response.data) {
      productionLines.value = response.data;
      if (productionLines.value.length === 1) {
        autoForm.value.productionLineId = String(productionLines.value[0].id);
      }
    } else {
      productionLinesLoadFailed.value = true;
    }
  } catch (error) {
    productionLinesLoadFailed.value = true;
    console.error('加载产线失败:', error);
  }
}

async function loadPendingPlans() {
  if (!factoryId.value) return;
  try {
    const response = await getPendingProductionPlans(factoryId.value);
    if (response.success && response.data) pendingPlans.value = response.data;
    else pendingPlansLoadFailed.value = true;
  } catch (error) {
    pendingPlansLoadFailed.value = true;
    console.error('加载待排产计划失败:', error);
  }
}

function selectAutoMode() {
  if (!aiAvailable.value) return;
  useAI.value = true;
}

function togglePlan(planId: string) {
  // 换了计划, 上一次「要补的产品」清单就不作数了
  capacityDialogVisible.value = false;
  const index = autoForm.value.planIds.indexOf(planId);
  if (index > -1) autoForm.value.planIds.splice(index, 1);
  else autoForm.value.planIds.push(planId);
}

function planOrder(planId: string) {
  return autoForm.value.planIds.indexOf(planId) + 1;
}

async function submitAutoSchedule(overrides: DailyCapacityOverride[] = []) {
  if (autoBlockReason.value) {
    ElMessage.warning(autoBlockReason.value);
    return;
  }
  try {
    loading.value = true;
    const response = await generateSchedule(factoryId.value!, {
      planDate: form.value.planDate,
      planIds: [...autoForm.value.planIds],
      ...(autoForm.value.productionLineId ? { productionLineId: autoForm.value.productionLineId } : {}),
      ...(overrides.length ? { dailyCapacityOverrides: overrides } : {}),
    });
    if (!response.success || !response.data) return;
    if (response.data.outcome === 'CAPACITY_REQUIRED') {
      // 两样都没有: 后端什么都没保存, 当场问每个产品一天能做多少
      openCapacityDialog(response.data.capacityRequired || []);
      return;
    }
    capacityDialogVisible.value = false;
    ElMessage.success(`已按日产能排出 ${response.data.lineSchedules.length} 个计划`);
    // warnings 只在生成这次返回 (按历史估算 / 人工与历史相差大 / 已完成不排), 带到详情页展示
    router.push({ path: `/scheduling/plans/${response.data.id}`, state: { apsWarnings: response.data.warnings || [] } });
  } catch (error) {
    // Interceptor already shows the backend message as a sticky toast.
    console.error('自动排程失败:', error);
  } finally {
    loading.value = false;
  }
}

function openCapacityDialog(required: DailyCapacityRequired[]) {
  if (required.length === 0) {
    // 后端说要补却没给清单: 不假装成功, 也不让人卡在一个空弹窗里
    ElMessage.warning('有产品缺日产能，但没有拿到清单；请刷新页面重试');
    return;
  }
  capacityRequired.value = required;
  const inputs: CapacityInputs = {};
  for (const item of required) inputs[capacityKey(item)] = capacityInputs.value[capacityKey(item)] ?? null;
  capacityInputs.value = inputs;
  capacityDialogVisible.value = true;
}

async function submitCapacityAndSchedule() {
  if (capacityDialogBlockReason.value) {
    ElMessage.warning(capacityDialogBlockReason.value);
    return;
  }
  // 后端收到就先存到产品 (即使这次还缺别的产品也已经存下), 所以只发这一轮填的, 不累积重发
  await submitAutoSchedule(buildCapacityOverrides(capacityRequired.value, capacityInputs.value));
}

function goBack() {
  router.push('/scheduling/plans');
}

function toggleBatchSelection(batchId: number) {
  const index = form.value.selectedBatchIds.indexOf(batchId);
  if (index > -1) {
    form.value.selectedBatchIds.splice(index, 1);
    manualDrafts.value = manualDrafts.value.filter(draft => draft.batchId !== batchId);
  } else {
    form.value.selectedBatchIds.push(batchId);
    const batch = batches.value.find(item => Number(item.id) === batchId);
    manualDrafts.value.push({
      batchId,
      productionLineId: '',
      plannedQuantity: Number(batch?.plannedQuantity) || '',
      plannedStartTime: '',
      plannedEndTime: '',
      sourceQuantity: Number(batch?.plannedQuantity) || undefined,
      sourceUnit: String(batch?.quantityUnit || batch?.unit || batch?.baseUnit || '').trim() || undefined,
    });
  }
}

function isBatchSelected(batchId: number) {
  return form.value.selectedBatchIds.includes(batchId);
}

function selectAllBatches() {
  clearSelection();
  batches.value.forEach(batch => toggleBatchSelection(Number(batch.id)));
}

function clearSelection() {
  form.value.selectedBatchIds = [];
  manualDrafts.value = [];
}

async function handleSubmit() {
  if (useAI.value) {
    if (!aiAvailable.value) {
      ElMessage.warning(aiUnavailableReason.value);
      return;
    }
    await submitAutoSchedule();
    return;
  }
  const validationErrors = validateManualSchedules(manualDrafts.value);
  if (validationErrors.length > 0) {
    ElMessage.warning(validationErrors[0]);
    return;
  }
  if (manualUnavailableReason.value) {
    ElMessage.warning(manualUnavailableReason.value);
    return;
  }

  try {
    loading.value = true;
    const response = await createSchedulingPlan(factoryId.value!, buildManualSchedulePayload(
      form.value.planDate, manualDrafts.value, form.value.notes,
    ));

    if (response.success && response.data) {
      ElMessage.success('人工确认的排程草稿已创建');
      router.push(`/scheduling/plans/${response.data.id}`);
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('创建失败:', error);
  } finally {
    loading.value = false;
  }
}

</script>

<template>
  <div class="create-plan-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
        <h1>创建调度计划</h1>
      </div>
    </div>

    <div class="content-wrapper">
      <!-- 基本信息 -->
      <el-card class="form-card">
        <template #header>
          <span>基本信息</span>
        </template>
        <el-form label-width="120px">
          <el-form-item label="计划日期">
            <el-date-picker
              v-model="form.planDate"
              type="date"
              placeholder="选择日期"
              value-format="YYYY-MM-DD"
              :disabled-date="(time: Date) => time < new Date(Date.now() - 86400000)"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="form.notes"
              type="textarea"
              :rows="2"
              placeholder="可选备注信息"
            />
          </el-form-item>
        </el-form>
      </el-card>

      <!-- 生成方式 -->
      <el-card class="mode-card">
        <template #header>
          <span>生成方式</span>
        </template>
        <div class="mode-selection">
          <div
            class="mode-option"
            :class="{ active: !useAI }"
            @click="useAI = false"
          >
            <el-icon class="mode-icon"><Plus /></el-icon>
            <div class="mode-info">
              <div class="mode-title">手动创建</div>
              <div class="mode-desc">人工填写每个批次的产线、数量和起止时间</div>
            </div>
          </div>
          <button
            v-if="aiAvailable"
            type="button"
            class="mode-option"
            :class="{ active: useAI }"
            data-testid="mode-daily-schedule"
            aria-describedby="ai-generation-reason"
            @click="selectAutoMode"
          >
            <el-icon class="mode-icon ai"><MagicStick /></el-icon>
            <div class="mode-info">
              <div class="mode-title">按日产能自动排程</div>
              <div id="ai-generation-reason" class="mode-desc">{{ capabilities?.aiGeneration?.reason }}</div>
            </div>
          </button>
          <button
            v-else
            type="button"
            class="mode-option unavailable"
            disabled
            aria-disabled="true"
            aria-describedby="ai-generation-unavailable-reason"
          >
            <el-icon class="mode-icon ai"><MagicStick /></el-icon>
            <div class="mode-info">
              <div class="mode-title">AI 智能生成（暂不可用）</div>
              <div id="ai-generation-unavailable-reason" class="mode-desc">{{ aiUnavailableReason }}</div>
            </div>
          </button>
        </div>

        <div v-if="!useAI" class="capability-notice" role="status">
          <strong>人工确认的排程草稿</strong>
          <span>{{ capabilityLoadFailed ? '能力状态暂不可读，自动化保持不可用；请使用人工确认的排程草稿，网络恢复后可重试。' : aiUnavailableReason }}</span>
          <span v-if="manualUnavailableReason">{{ manualUnavailableReason }}</span>
          <span>请逐项填写产线、基本单位整数数量和起止时间；系统不会自动分配工人，也不会替你确认工艺、物料或换产可行性。</span>
        </div>
      </el-card>

      <!-- 按日产能自动排程: 选计划 + 可选产线 -->
      <el-card v-if="useAI && aiAvailable" class="auto-card">
        <template #header>
          <div class="card-header">
            <span>选择要排的生产计划（按点选顺序先到先排）</span>
          </div>
        </template>
        <div class="auto-rules">
          从计划日期开始按天排：每个产品按「一天能做多少」算，一天做不完顺延到第二天；
          同一条产线一天的产能按点选顺序分着用。产品填了日产能用填的，没填用历史估算；
          两样都没有的，排的时候会当场问你，填一次就记在产品上。不会自动派工。
        </div>
        <div class="plan-list">
          <div
            v-for="plan in pendingPlans"
            :key="plan.id"
            class="plan-item"
            :class="{ selected: planOrder(plan.id) > 0 }"
            :data-testid="`aps-plan-${plan.id}`"
            @click="togglePlan(plan.id)"
          >
            <span class="plan-order">{{ planOrder(plan.id) > 0 ? planOrder(plan.id) : '' }}</span>
            <div class="batch-info">
              <div class="batch-number">{{ plan.planNumber }}</div>
              <div class="batch-product">{{ plan.productTypeName || '产品未标明' }} · 计划数量 {{ plan.plannedQuantity ?? '未知' }}</div>
            </div>
            <div class="plan-due">交期 {{ plan.expectedCompletionDate || '未设' }}</div>
          </div>
          <div v-if="pendingPlansLoadFailed" class="auto-hint error">待排产计划加载失败，请刷新页面重试。</div>
          <div v-else-if="pendingPlans.length === 0" class="auto-hint">没有待排产的生产计划（只列出状态为「待处理」、一个月内到期的计划）。</div>
        </div>
        <div class="auto-options">
          <label>产线
            <select v-model="autoForm.productionLineId" data-testid="aps-line">
              <option value="">{{ productionLines.length > 1 ? '请选择产线' : '自动（工厂只有一条产线）' }}</option>
              <option v-for="line in productionLines" :key="line.id" :value="line.id">{{ line.name }}</option>
            </select>
          </label>
        </div>
        <div v-if="autoBlockReason" class="auto-hint warning" data-testid="aps-block-reason">{{ autoBlockReason }}</div>
      </el-card>

      <!-- 两样都没有日产能: 当场补填 (保存到产品后重新排) -->
      <el-card v-if="useAI && capacityDialogVisible" class="capacity-card" data-testid="aps-capacity-required">
        <template #header>
          <div class="card-header">
            <span>这些产品还不知道一天能做多少，填一下就能排（会记在产品上，下次不用再填）</span>
          </div>
        </template>
        <div
          v-for="item in capacityRequired"
          :key="capacityKey(item)"
          class="capacity-row"
          :data-testid="`aps-capacity-${item.productTypeId}`"
        >
          <div class="capacity-question">
            <strong>{{ item.question }}</strong>
            <span class="auto-hint">
              计划：{{ item.planNumbers.join('、') }}
              <template v-if="item.reason === 'UNIT_MISMATCH'">
                ；产品上填的是 {{ item.currentQuantityPerDay }} {{ item.currentUnit }}/天，和计划单位 {{ item.unit }} 对不上
              </template>
              <template v-if="item.experienceNote">；{{ item.experienceNote }}</template>
            </span>
          </div>
          <label class="capacity-input">
            <input
              v-model.number="capacityInputs[capacityKey(item)]"
              type="number"
              min="0.01"
              step="any"
              inputmode="decimal"
              :aria-label="item.question"
            />
            <span>{{ item.unit }}/天</span>
          </label>
        </div>
        <div v-if="capacityDialogBlockReason" class="auto-hint warning" data-testid="aps-capacity-block-reason">
          {{ capacityDialogBlockReason }}
        </div>
        <div class="capacity-actions">
          <el-button @click="capacityDialogVisible = false">先不排</el-button>
          <el-button
            type="primary"
            :loading="loading"
            :disabled="!!capacityDialogBlockReason"
            data-testid="aps-capacity-submit"
            @click="submitCapacityAndSchedule"
          >
            保存日产能并排程
          </el-button>
        </div>
      </el-card>

      <!-- 选择批次 -->
      <el-card v-if="!useAI" class="batch-card">
        <template #header>
          <div class="card-header">
            <span>选择生产批次</span>
            <div class="header-actions">
              <el-button type="primary" link @click="selectAllBatches">全选</el-button>
              <el-button type="danger" link @click="clearSelection">清空</el-button>
            </div>
          </div>
        </template>

        <div class="batch-list" v-loading="loading">
          <div
            v-for="batch in batches"
            :key="batch.id"
            class="batch-item"
            :class="{ selected: isBatchSelected(batch.id) }"
            @click="toggleBatchSelection(batch.id)"
          >
            <el-checkbox :model-value="isBatchSelected(batch.id)" @click.stop />
            <div class="batch-info">
              <div class="batch-number">{{ batch.batchNumber }}</div>
              <div class="batch-product">{{ batch.productTypeName }}</div>
            </div>
            <div class="batch-quantity">
              <span class="qty-value">{{ batch.plannedQuantity }}</span>
              <span class="qty-label">计划数量 {{ batch.quantityUnit || batch.unit || batch.baseUnit || '基本单位未标明' }}</span>
            </div>
          </div>
          <el-empty v-if="batches.length === 0" description="暂无待调度的生产批次" />
        </div>

        <div class="selection-summary">
          已选择 <strong>{{ form.selectedBatchIds.length }}</strong> 个批次
        </div>
      </el-card>

      <el-card v-if="!useAI && manualDrafts.length" class="manual-schedule-card">
        <template #header><span>明确排程信息（每个批次必填）</span></template>
        <div v-for="draft in manualDrafts" :key="draft.batchId" class="manual-schedule-row">
          <div class="manual-schedule-context">
            <strong>{{ batches.find(batch => Number(batch.id) === draft.batchId)?.batchNumber || `批次 ${draft.batchId}` }}</strong>
            <span>预填来源：生产批次计划数量 {{ draft.sourceQuantity ?? '未知' }} {{ draft.sourceUnit || '基本单位未标明' }}，请人工确认后提交</span>
          </div>
          <label>生产线
            <select v-model="draft.productionLineId">
              <option value="">请选择生产线</option>
              <option v-for="line in productionLines" :key="line.id" :value="line.id">{{ line.name }}</option>
            </select>
          </label>
          <label>基本单位数量
            <input v-model.number="draft.plannedQuantity" type="number" min="1" step="1" inputmode="numeric" />
          </label>
          <label>开始时间
            <input v-model="draft.plannedStartTime" type="datetime-local" />
          </label>
          <label>结束时间
            <input v-model="draft.plannedEndTime" type="datetime-local" />
          </label>
        </div>
      </el-card>

      <SchedulingDiagnosticsPanel
        v-if="!useAI && manualDrafts.length === 0"
        :factory-id="factoryId"
      />
      <SchedulingDiagnosticsPanel
        v-for="diagnosticDraft in (useAI ? [] : manualDrafts)"
        :key="diagnosticDraft.batchId"
        :factory-id="factoryId"
        :production-line-id="diagnosticDraft.productionLineId || undefined"
        :batch-id="diagnosticDraft.batchId"
        :start-time="diagnosticDraft.plannedStartTime || undefined"
        :end-time="diagnosticDraft.plannedEndTime || undefined"
      />

      <!-- 提交按钮 -->
      <div class="submit-area">
        <el-button @click="goBack">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          :icon="useAI ? MagicStick : Plus"
          :disabled="useAI && !!autoBlockReason"
          @click="handleSubmit"
        >
          {{ useAI ? '按日产能排程' : '创建人工排程草稿' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.create-plan-page {
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

.content-wrapper {
  max-width: 900px;
}

.form-card,
.mode-card,
.batch-card,
.auto-card {
  margin-bottom: 20px;
}

.auto-rules {
  padding: 10px 12px;
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #606266;
  background: #ecf5ff;
  border-left: 4px solid #409eff;
}

.plan-list {
  max-height: 360px;
  overflow-y: auto;
}

.plan-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  margin-bottom: 8px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  cursor: pointer;

  &.selected {
    border-color: #409eff;
    background: rgba(64, 158, 255, 0.05);
  }

  .plan-order {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    border: 1px solid #dcdfe6;
    border-radius: 50%;
    font-size: 12px;
    color: #409eff;
  }

  .batch-info {
    flex: 1;

    .batch-number { font-weight: 600; color: #303133; }
    .batch-product { font-size: 12px; color: #909399; }
  }

  .plan-due { font-size: 12px; color: #909399; }
}

.auto-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(160px, 1fr));
  gap: 12px;
  margin-top: 12px;

  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: #606266;
    font-size: 12px;
  }

  select,
  input {
    min-height: 32px;
    padding: 4px 8px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
  }
}

.auto-hint {
  font-size: 12px;
  color: #909399;

  &.warning { margin-top: 10px; color: #b88230; }
  &.error { color: #f56c6c; }
}

.capacity-card {
  margin-bottom: 20px;
  border-color: #e6a23c;
}

.capacity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;

  &:last-of-type { border-bottom: none; }
}

.capacity-question {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.capacity-input {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  color: #606266;

  input {
    width: 140px;
    min-height: 32px;
    padding: 4px 8px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
  }
}

.capacity-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
}

.mode-selection {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.mode-option {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border: 2px solid #dcdfe6;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    border-color: #409EFF;
  }

  &.active {
    border-color: #409EFF;
    background: rgba(64, 158, 255, 0.05);
  }

  &.unavailable {
    font: inherit;
    text-align: left;
    cursor: not-allowed;
    background: #f5f7fa;

    &:hover {
      border-color: #dcdfe6;
    }
  }

  .mode-icon {
    font-size: 32px;
    color: #909399;

    &.ai {
      color: #E6A23C;
    }
  }

  .mode-info {
    .mode-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
      margin-bottom: 4px;
    }

    .mode-desc {
      font-size: 12px;
      color: #909399;
    }
  }
}

.ai-options,
.manual-options {
  padding-top: 20px;
  border-top: 1px solid #ebeef5;
}

.capability-notice {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  margin-top: 16px;
  color: #606266;
  background: #fdf6ec;
  border-left: 4px solid #e6a23c;
  font-size: 13px;
}

.manual-schedule-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) repeat(4, minmax(120px, 1fr));
  gap: 12px;
  align-items: end;
  padding: 14px 0;
  border-bottom: 1px solid #ebeef5;

  &:last-child { border-bottom: 0; }

  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
    color: #606266;
    font-size: 12px;
  }

  select,
  input {
    width: 100%;
    min-height: 32px;
    padding: 4px 8px;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    color: #303133;
    background: #fff;
  }
}

.manual-schedule-context {
  display: flex;
  flex-direction: column;
  gap: 4px;

  span {
    color: #909399;
    font-size: 12px;
    line-height: 1.4;
  }
}

.option-hint {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.batch-list {
  max-height: 400px;
  overflow-y: auto;
}

.batch-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: #f5f7fa;
  }

  &.selected {
    border-color: #409EFF;
    background: rgba(64, 158, 255, 0.05);
  }

  .batch-info {
    flex: 1;

    .batch-number {
      font-weight: 600;
      color: #303133;
    }

    .batch-product {
      font-size: 12px;
      color: #909399;
    }
  }

  .batch-quantity {
    text-align: right;

    .qty-value {
      font-size: 18px;
      font-weight: 600;
      color: #303133;
    }

    .qty-label {
      display: block;
      font-size: 12px;
      color: #909399;
    }
  }
}

.selection-summary {
  padding: 12px;
  text-align: center;
  color: #606266;
  border-top: 1px solid #ebeef5;
  margin-top: 16px;

  strong {
    color: #409EFF;
    font-size: 18px;
  }
}

.submit-area {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 20px;
}
</style>
