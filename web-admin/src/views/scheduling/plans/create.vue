<script setup lang="ts">
/**
 * 创建调度计划页面
 * 支持手动创建和AI自动生成
 */
import { ref, onMounted, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  createSchedulingPlan,
  getProductionLines,
  getSchedulingCapabilities,
  type SchedulingCapabilities
} from '@/api/scheduling';
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
    }
  } catch (error) {
    console.error('加载产线失败:', error);
  }
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
    ElMessage.warning(aiUnavailableReason.value);
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

        <div class="capability-notice" role="status">
          <strong>人工确认的排程草稿</strong>
          <span>{{ capabilityLoadFailed ? '能力状态暂不可读，自动化保持不可用；请使用人工确认的排程草稿，网络恢复后可重试。' : aiUnavailableReason }}</span>
          <span v-if="manualUnavailableReason">{{ manualUnavailableReason }}</span>
          <span>请逐项填写产线、基本单位整数数量和起止时间；系统不会自动分配工人，也不会替你确认工艺、物料或换产可行性。</span>
        </div>
      </el-card>

      <!-- 选择批次 -->
      <el-card class="batch-card">
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

      <el-card v-if="manualDrafts.length" class="manual-schedule-card">
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
        v-if="manualDrafts.length === 0"
        :factory-id="factoryId"
      />
      <SchedulingDiagnosticsPanel
        v-for="diagnosticDraft in manualDrafts"
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
          :icon="Plus"
          @click="handleSubmit"
        >
          创建人工排程草稿
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
.batch-card {
  margin-bottom: 20px;
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
