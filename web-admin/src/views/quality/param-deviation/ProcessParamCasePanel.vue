<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { ApiError } from '@/types/api';
import {
  assignProcessParamCase,
  getProcessParamCase,
  listProcessParamCaseAssignees,
  listProcessParamCases,
  resolveProcessParamCase,
  startProcessParamCaseVerify,
  type ProcessParamCaseAssignee,
  type ProcessParamCaseDetail,
  type ProcessParamCaseResolution,
  type ProcessParamCaseView,
} from '@/api/processParamDeviationCase';

const props = defineProps<{
  factoryId: string;
  canWrite: boolean;
}>();

const rows = ref<ProcessParamCaseView[]>([]);
const loading = ref(false);
const loadError = ref('');
const detail = ref<ProcessParamCaseDetail | null>(null);
const detailLoading = ref(false);
const detailError = ref('');
const assignees = ref<ProcessParamCaseAssignee[]>([]);
const assigneesLoading = ref(false);
const assigneesError = ref('');
const selectedOwnerId = ref<number | null>(null);
const resolution = ref<ProcessParamCaseResolution | ''>('');
const resolutionNote = ref('');
const actionLoading = ref(false);
const actionError = ref('');
const caseFilter = ref<'open' | 'all'>('open');
const casePage = ref(0);
const detailTarget = ref<ProcessParamCaseView | null>(null);
let loadSequence = 0;
let detailSequence = 0;

const CASE_PAGE_SIZE = 50;

const resolutionOptions: Array<{ value: ProcessParamCaseResolution; label: string }> = [
  { value: 'REASONABLE', label: '偏离合理，保留记录' },
  { value: 'CORRECTED', label: '已纠正现场参数' },
  { value: 'STANDARD_UPDATE', label: '需要更新标准' },
  { value: 'DATA_ERROR', label: '报工数据错误' },
];

const isClosed = computed(() => detail.value?.caseView.stage === 'CLOSED');
const canAssign = computed(() => props.canWrite && !!detail.value && !isClosed.value);
const canStart = computed(() => canAssign.value
  && detail.value?.caseView.stage === 'DETECTED'
  && detail.value.caseView.ownerUserId != null);
const canResolve = computed(() => canAssign.value && detail.value?.caseView.stage === 'EXECUTING');

function errorText(error: unknown, fallback: string): string {
  if (error instanceof ApiError) return error.message || fallback;
  if (error instanceof Error) return error.message || fallback;
  return fallback;
}

function conflictText(error: unknown): string {
  const message = errorText(error, '服务器拒绝了这次操作');
  return error instanceof ApiError && error.status === 409
    ? `${message} 请刷新后重新确认，已填写的说明仍保留。`
    : message;
}

function stageLabel(row: ProcessParamCaseView): string {
  return row.stageLabel || ({ DETECTED: '待处理', EXECUTING: '核查中', CLOSED: '已关闭' }[row.stage] ?? row.stage);
}

function statusLabel(status: string): string {
  return status === 'HIGH' ? '偏高' : status === 'LOW' ? '偏低' : status === 'OK' ? '已恢复正常' : '状态待确认';
}

function valueText(value: number | null, unit: string | null): string {
  return value == null ? '—' : `${value}${unit ? ` ${unit}` : ''}`;
}

const emptyDescription = computed(() => {
  if (casePage.value > 0) return `第 ${casePage.value + 1} 页没有记录，可返回上一页或继续查询下一页`;
  return caseFilter.value === 'open' ? '当前没有待处理的普通工艺参数异常' : '当前没有普通工艺参数异常历史';
});

async function loadCases(page = casePage.value): Promise<void> {
  const factoryId = props.factoryId;
  casePage.value = page;
  const sequence = ++loadSequence;
  rows.value = [];
  loadError.value = '';
  if (!factoryId) return;
  loading.value = true;
  try {
    // Keep closed rows visible as read-only history; the backend defaults to
    // openOnly=true, so the panel opts into the full source-specific list.
    const response = await listProcessParamCases(factoryId, {
      openOnly: caseFilter.value === 'open',
      page,
      size: CASE_PAGE_SIZE,
    });
    if (sequence !== loadSequence) return;
    if (!response.success || !Array.isArray(response.data)) {
      throw new Error(response.message || '工艺参数异常列表读取失败');
    }
    rows.value = response.data;
  } catch (error) {
    if (sequence !== loadSequence) return;
    loadError.value = errorText(error, '工艺参数异常列表读取失败，请重试');
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}

function changeCaseFilter(): void {
  detail.value = null;
  detailTarget.value = null;
  detailError.value = '';
  casePage.value = 0;
  void loadCases(0);
}

function previousCasePage(): void {
  if (casePage.value > 0) void loadCases(casePage.value - 1);
}

function nextCasePage(): void {
  // A short or empty page is not evidence that this is the last visible page:
  // the backend reads a database page before applying source-visibility rules.
  // Let the user probe the next page explicitly.
  if (!loading.value) void loadCases(casePage.value + 1);
}

async function loadAssignees(): Promise<void> {
  if (!props.canWrite || !props.factoryId || assignees.value.length > 0 || assigneesLoading.value) return;
  assigneesLoading.value = true;
  assigneesError.value = '';
  try {
    const response = await listProcessParamCaseAssignees(props.factoryId);
    if (!response.success || !Array.isArray(response.data)) {
      throw new Error(response.message || '负责人名录读取失败');
    }
    assignees.value = response.data;
  } catch (error) {
    assigneesError.value = errorText(error, '负责人名录读取失败，请重试');
  } finally {
    assigneesLoading.value = false;
  }
}

async function loadDetail(row: ProcessParamCaseView, preserveDraft = false): Promise<void> {
  const factoryId = props.factoryId;
  const sequence = ++detailSequence;
  const draftResolution = resolution.value;
  const draftNote = resolutionNote.value;
  detailTarget.value = row;
  if (!preserveDraft) detail.value = null;
  detailError.value = '';
  actionError.value = '';
  selectedOwnerId.value = row.ownerUserId;
  if (!preserveDraft) {
    resolution.value = '';
    resolutionNote.value = '';
  }
  detailLoading.value = true;
  try {
    const response = await getProcessParamCase(factoryId, row.id);
    if (sequence !== detailSequence || factoryId !== props.factoryId) return;
    if (!response.success || !response.data) throw new Error(response.message || '异常详情读取失败');
    detail.value = response.data;
    selectedOwnerId.value = response.data.caseView.ownerUserId;
    if (preserveDraft) {
      resolution.value = draftResolution;
      resolutionNote.value = draftNote;
    } else {
      resolutionNote.value = response.data.resolutionNote || '';
    }
    if (response.data.caseView.stage !== 'CLOSED') await loadAssignees();
  } catch (error) {
    if (sequence !== detailSequence || factoryId !== props.factoryId) return;
    detailError.value = errorText(error, '异常详情读取失败，请重试');
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}

function openDetail(row: ProcessParamCaseView): void {
  void loadDetail(row);
}

function refreshDetail(): void {
  if (detailTarget.value) void loadDetail(detailTarget.value, true);
}

async function refreshAfterAction(factoryId: string, caseId: string): Promise<void> {
  if (factoryId !== props.factoryId || detail.value?.caseView.id !== caseId || !detailTarget.value) return;
  await loadDetail({ ...detailTarget.value, id: caseId });
  await loadCases();
}

async function assign(): Promise<void> {
  const current = detail.value;
  const factoryId = props.factoryId;
  const caseId = current?.caseView.id;
  const version = current?.caseView.version;
  if (!canAssign.value || !current || !caseId || version == null || selectedOwnerId.value == null) return;
  const ownerUserId = selectedOwnerId.value;
  actionLoading.value = true;
  actionError.value = '';
  try {
    const response = await assignProcessParamCase(factoryId, caseId, {
      ownerUserId,
      version,
    });
    if (!response.success) throw new Error(response.message || '指定负责人失败');
    ElMessage.success('已指定负责人');
    await refreshAfterAction(factoryId, caseId);
  } catch (error) {
    actionError.value = conflictText(error);
  } finally {
    actionLoading.value = false;
  }
}

async function startVerify(): Promise<void> {
  const current = detail.value;
  const factoryId = props.factoryId;
  const caseId = current?.caseView.id;
  const version = current?.caseView.version;
  if (!canStart.value || !current || !caseId || version == null) return;
  actionLoading.value = true;
  actionError.value = '';
  try {
    const response = await startProcessParamCaseVerify(factoryId, caseId, { version });
    if (!response.success) throw new Error(response.message || '开始核查失败');
    ElMessage.success('已开始核查');
    await refreshAfterAction(factoryId, caseId);
  } catch (error) {
    actionError.value = conflictText(error);
  } finally {
    actionLoading.value = false;
  }
}

async function resolveCase(): Promise<void> {
  const current = detail.value;
  const factoryId = props.factoryId;
  const caseId = current?.caseView.id;
  const version = current?.caseView.version;
  const selectedResolution = resolution.value;
  const note = resolutionNote.value.trim();
  if (!canResolve.value || !current || !caseId || version == null || !selectedResolution || !note) return;
  actionLoading.value = true;
  actionError.value = '';
  try {
    const response = await resolveProcessParamCase(factoryId, caseId, {
      resolution: selectedResolution,
      note,
      version,
    });
    if (!response.success) throw new Error(response.message || '提交处理结果失败');
    ElMessage.success('异常已关闭');
    await refreshAfterAction(factoryId, caseId);
  } catch (error) {
    // Keep the note and selected resolution after a 409 so the user can refresh
    // deliberately and re-confirm instead of losing an audit-relevant explanation.
    actionError.value = conflictText(error);
  } finally {
    actionLoading.value = false;
  }
}

watch(() => props.factoryId, () => {
  detail.value = null;
  detailTarget.value = null;
  assignees.value = [];
  casePage.value = 0;
  void loadCases(0);
});

onMounted(() => { void loadCases(); });
</script>

<template>
  <section class="case-panel" data-testid="process-param-case-panel">
    <div class="panel-header">
      <div>
        <h3>普通工艺参数异常</h3>
        <p>偏高 / 偏低单独记录、分派、核查；处理结果由负责人明确提交。</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" data-testid="param-case-refresh" @click="loadCases">刷新</el-button>
    </div>

    <div class="case-filter-row">
      <span class="filter-label">显示范围</span>
      <el-radio-group v-model="caseFilter" size="small" data-testid="param-case-filter" @change="changeCaseFilter">
        <el-radio-button label="open">待处理</el-radio-button>
        <el-radio-button label="all">全部历史</el-radio-button>
      </el-radio-group>
    </div>

    <el-alert v-if="loadError" type="error" :closable="false" data-testid="param-case-load-error">
      {{ loadError }}
      <el-button link type="danger" data-testid="param-case-retry" @click="loadCases">重试</el-button>
    </el-alert>
    <el-empty v-else-if="!loading && rows.length === 0" :description="emptyDescription" data-testid="param-case-empty" />

    <el-table v-else v-loading="loading" :data="rows" stripe data-testid="param-case-table">
      <el-table-column label="工序 / 参数" min-width="190">
        <template #default="{ row }">
          <div>{{ row.processName || '—' }}</div>
          <small>{{ row.paramLabel || row.paramKey }}<span v-if="row.unit">（{{ row.unit }}）</span></small>
        </template>
      </el-table-column>
      <el-table-column label="来源批次" min-width="140">
        <template #default="{ row }">{{ row.batchNumber || '—' }}</template>
      </el-table-column>
      <el-table-column label="偏离" width="85">
        <template #default="{ row }"><el-tag :type="row.status === 'HIGH' ? 'danger' : row.status === 'OK' ? 'success' : 'warning'">{{ statusLabel(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="阶段" width="105">
        <template #default="{ row }">{{ stageLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="负责人" min-width="120">
        <template #default="{ row }">{{ row.ownerName || '未指定' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :data-testid="`param-case-open-${row.id}`" @click="openDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loadError && !loading" class="case-pagination" data-testid="param-case-pagination">
      <el-button size="small" :disabled="casePage === 0 || loading" data-testid="param-case-prev" @click="previousCasePage">上一页</el-button>
      <span>第 {{ casePage + 1 }} 页</span>
      <el-button size="small" :disabled="loading" data-testid="param-case-next" @click="nextCasePage">下一页</el-button>
    </div>

    <el-dialog
      :model-value="detail != null || detailLoading || !!detailError"
      title="工艺参数异常详情"
      width="640px"
      @close="detail = null; detailError = ''; actionError = ''"
    >
      <div v-if="detailLoading && !detail" v-loading="true" data-testid="param-case-detail-loading" style="min-height: 120px" />
      <el-alert v-if="detailError" type="error" :closable="false" data-testid="param-case-detail-error">
        {{ detailError }}
        <el-button link type="danger" data-testid="param-case-detail-retry" @click="refreshDetail">重试</el-button>
      </el-alert>
      <template v-if="detail">
        <div class="detail-toolbar">
          <span>当前事实与版本需在操作前重新确认</span>
          <el-button link type="primary" :loading="detailLoading" data-testid="param-case-detail-refresh" @click="refreshDetail">刷新当前事实</el-button>
        </div>
        <el-descriptions :column="2" border size="small" data-testid="param-case-detail">
          <el-descriptions-item label="工序">{{ detail.caseView.processName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="参数">{{ detail.caseView.paramLabel || detail.caseView.paramKey }}</el-descriptions-item>
          <el-descriptions-item label="当前值">{{ valueText(detail.measuredValue, detail.caseView.unit) }}</el-descriptions-item>
          <el-descriptions-item label="标准值">{{ valueText(detail.standardValue, detail.caseView.unit) }}</el-descriptions-item>
          <el-descriptions-item label="标准范围">{{ detail.rangeMin ?? '—' }} ~ {{ detail.rangeMax ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="来源批次">{{ detail.caseView.batchNumber || '—' }}</el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ stageLabel(detail.caseView) }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ detail.caseView.version }}</el-descriptions-item>
        </el-descriptions>

        <el-alert v-if="actionError" class="action-error" type="warning" :closable="false" data-testid="param-case-action-error">
          {{ actionError }}
        </el-alert>
        <el-alert v-if="!canWrite" class="action-error" type="info" :closable="false" data-testid="param-case-readonly">
          当前账号没有处理权限，仅可查看。
        </el-alert>
        <el-alert v-if="isClosed" class="action-error" type="success" :closable="false" data-testid="param-case-closed">
          该异常已关闭，只读查看历史处理结果。
        </el-alert>

        <div v-if="canAssign" class="case-actions" data-testid="param-case-actions">
          <el-select
            v-model="selectedOwnerId"
            placeholder="选择本厂有效负责人"
            :loading="assigneesLoading"
            :disabled="actionLoading"
            data-testid="param-case-owner"
          >
            <el-option v-for="user in assignees" :key="user.id" :label="`${user.name} · ${user.role}`" :value="user.id" />
          </el-select>
          <el-button type="primary" :loading="actionLoading" :disabled="selectedOwnerId == null || !!assigneesError" data-testid="param-case-assign" @click="assign">指定负责人</el-button>
          <el-button v-if="canStart" :loading="actionLoading" data-testid="param-case-start" @click="startVerify">开始核查</el-button>
        </div>
        <p v-if="assigneesError" class="inline-error" data-testid="param-case-assignee-error">{{ assigneesError }}</p>

        <div v-if="canResolve" class="resolve-form" data-testid="param-case-resolve-form">
          <el-select v-model="resolution" placeholder="选择处理结果" data-testid="param-case-resolution">
            <el-option v-for="option in resolutionOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-model="resolutionNote" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="填写处理说明（必填）" data-testid="param-case-note" />
          <el-button type="primary" :loading="actionLoading" :disabled="!resolution || !resolutionNote.trim()" data-testid="param-case-resolve" @click="resolveCase">提交并关闭</el-button>
        </div>
        <div v-else-if="detail.resolutionNote" class="resolution-readback" data-testid="param-case-resolution-readback">
          处理结果：{{ resolutionOptions.find(option => option.value === detail?.resolutionCode)?.label || detail.resolutionCode || '—' }}；说明：{{ detail.resolutionNote }}
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.case-panel { margin-top: 28px; padding: 16px 20px 20px; border: 1px solid #ebeef5; border-radius: 8px; background: #fff; }
.panel-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.panel-header h3 { margin: 0 0 4px; font-size: 16px; color: #303133; }
.panel-header p { margin: 0; color: #909399; font-size: 12px; line-height: 1.5; }
.case-filter-row { display: flex; align-items: center; gap: 10px; margin: 4px 0 12px; }
.filter-label { color: #606266; font-size: 13px; }
.case-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 12px; color: #606266; font-size: 13px; }
.detail-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; color: #909399; font-size: 12px; }
.case-actions { display: flex; align-items: center; gap: 8px; margin-top: 16px; }
.case-actions .el-select { width: 250px; }
.resolve-form { display: grid; gap: 10px; margin-top: 16px; }
.resolve-form .el-select { width: 260px; }
.action-error { margin-top: 12px; }
.inline-error { margin: 8px 0 0; color: #f56c6c; font-size: 12px; }
.resolution-readback { margin-top: 16px; padding: 10px 12px; color: #606266; background: #f5f7fa; border-radius: 4px; line-height: 1.6; }
small { color: #909399; }
</style>
