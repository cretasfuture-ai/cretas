<script setup lang="ts">
/**
 * Sprint4-H Q-PROCESS-1 — 工序质检不良记录列表 (PC).
 *
 * <p>展示所有不良记录, 支持按状态 / 缺陷类型过滤. 行内操作:
 *   - OPEN → 分派处理 (dialog: handlingAction + assignedTo)
 *   - IN_PROGRESS → 闭环 (dialog: closeNotes)
 *
 * <p>+ 登记不良按钮触发 record dialog (qualityInspectionId 手动输入,
 *    后续可换为 inspection picker).
 *
 * RBAC: 显示需 quality:read (路由 module: 'quality'), 写操作后端 @RequirePermission("quality:read_write").
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Plus } from '@element-plus/icons-vue';
import {
  listDefects,
  recordDefect,
  assignDefect,
  closeDefect,
  DEFECT_STATUS_LABELS,
  DEFECT_TYPE_LABELS,
  listQualityInspectionSources,
  getQualityInspectionSource,
  listQualityAssigneeDirectory,
  type QualityDefect,
  type DefectStatus,
  type DefectType,
  type QualityInspectionSource,
  type QualityAssigneeDirectoryUser,
} from '@/api/qualityDefect';

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const route = useRoute();

const loading = ref(false);
const rows = ref<QualityDefect[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const filterStatus = ref<DefectStatus | ''>('');
const filterType = ref<DefectType | ''>('');

const showRecordDialog = ref(false);
const showActionDialog = ref(false);
const submitting = ref(false);

const recordForm = ref({
  qualityInspectionId: '',
  defectType: 'OTHER' as DefectType,
  quantity: 1,
  cause: '',
});

const actionMode = ref<'assign' | 'close'>('assign');
const currentDefect = ref<QualityDefect | null>(null);
const actionForm = ref({
  handlingAction: '',
  assignedTo: null as number | null,
  closeNotes: '',
});

const inspectionSources = ref<QualityInspectionSource[]>([]);
const assigneeDirectory = ref<QualityAssigneeDirectoryUser[]>([]);
const sourceLoading = ref(false);
const directoryLoading = ref(false);
const sourceError = ref('');
const directoryError = ref('');
let sourceRequestGeneration = 0;
let directoryRequestGeneration = 0;
const selectedSource = computed(() => inspectionSources.value.find(
  source => source.id === recordForm.value.qualityInspectionId,
));
const selectedAssigneeIsValid = computed(() =>
  actionForm.value.assignedTo == null
  || assigneeDirectory.value.some(user => user.id === actionForm.value.assignedTo),
);

const STATUS_OPTIONS: DefectStatus[] = ['OPEN', 'IN_PROGRESS', 'CLOSED'];
const TYPE_OPTIONS: DefectType[] = [
  'APPEARANCE', 'WEIGHT', 'COMPOSITION', 'MICROBIAL',
  'PACKAGING', 'FOREIGN_OBJECT', 'SENSORY', 'SHELF_LIFE', 'OTHER',
];

async function load() {
  if (!factoryId.value) {
    ElMessage.warning('未登录或缺少工厂上下文');
    return;
  }
  loading.value = true;
  try {
    const res = await listDefects(factoryId.value, {
      status: filterStatus.value || undefined,
      defectType: filterType.value || undefined,
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res.success && res.data) {
      rows.value = res.data.content;
      pagination.value.total = res.data.totalElements;
    }
  } finally {
    loading.value = false;
  }
}

function statusTagType(status: DefectStatus): string {
  if (status === 'CLOSED') return 'success';
  if (status === 'IN_PROGRESS') return 'warning';
  return 'danger';
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  load();
}

function handleFilterChange() {
  pagination.value.page = 1;
  load();
}

function inspectionLabel(source: QualityInspectionSource): string {
  const sourceName = source.batchNumber || source.productName || `质检 ${source.id.slice(0, 8)}`;
  const date = source.inspectionDate ? ` · ${source.inspectionDate}` : '';
  const result = source.result ? ` · ${source.result}` : '';
  return `${sourceName}${date}${result}`;
}

function assigneeLabel(user: QualityAssigneeDirectoryUser): string {
  const name = user.fullName || user.username || `用户 #${user.id}`;
  const department = user.department || user.roleCode;
  return department ? `${name} · ${department}` : name;
}

async function loadInspectionSources(initialInspectionId?: string) {
  const requestFactoryId = factoryId.value;
  const requestGeneration = ++sourceRequestGeneration;
  inspectionSources.value = [];
  sourceError.value = '';
  if (!requestFactoryId) return;
  sourceLoading.value = true;
  try {
    const [listResponse, initialResponse] = await Promise.all([
      listQualityInspectionSources(requestFactoryId),
      initialInspectionId
        ? getQualityInspectionSource(requestFactoryId, initialInspectionId)
        : Promise.resolve(null),
    ]);
    if (requestFactoryId !== factoryId.value || requestGeneration !== sourceRequestGeneration) return;
    if (!listResponse.success || !listResponse.data?.content) {
      sourceError.value = listResponse.message || '质检来源读取失败，请重试';
    }
    const sources = listResponse.success && listResponse.data?.content
      ? listResponse.data.content
      : [];
    const initial = initialResponse?.success ? initialResponse.data : null;
    inspectionSources.value = initial && !sources.some(source => source.id === initial.id)
      ? [initial, ...sources]
      : sources;
    if (initialInspectionId && !inspectionSources.value.some(source => source.id === initialInspectionId)) {
      sourceError.value = '该质检来源不存在或不属于当前工厂，无法登记不良';
    }
  } catch (error) {
    if (requestFactoryId !== factoryId.value || requestGeneration !== sourceRequestGeneration) return;
    sourceError.value = error instanceof Error ? error.message : '质检来源读取失败，请重试';
  } finally {
    if (requestFactoryId === factoryId.value && requestGeneration === sourceRequestGeneration) {
      sourceLoading.value = false;
    }
  }
}

async function loadAssigneeDirectory() {
  const requestFactoryId = factoryId.value;
  const requestGeneration = ++directoryRequestGeneration;
  assigneeDirectory.value = [];
  directoryError.value = '';
  if (!requestFactoryId) return;
  directoryLoading.value = true;
  try {
    const response = await listQualityAssigneeDirectory(requestFactoryId);
    if (requestFactoryId !== factoryId.value || requestGeneration !== directoryRequestGeneration) return;
    if (!response.success || !Array.isArray(response.data)) {
      directoryError.value = response.message || '处理人员工厂名录读取失败，请重试';
      return;
    }
    assigneeDirectory.value = response.data.filter(user => user.isActive === true);
  } catch (error) {
    if (requestFactoryId !== factoryId.value || requestGeneration !== directoryRequestGeneration) return;
    directoryError.value = error instanceof Error ? error.message : '处理人员工厂名录读取失败，请重试';
  } finally {
    if (requestFactoryId === factoryId.value && requestGeneration === directoryRequestGeneration) {
      directoryLoading.value = false;
    }
  }
}

function openRecordDialog(initialInspectionId = typeof route.query.inspectionId === 'string'
  ? route.query.inspectionId
  : '') {
  recordForm.value = {
    qualityInspectionId: initialInspectionId,
    defectType: 'OTHER',
    quantity: 1,
    cause: '',
  };
  showRecordDialog.value = true;
  void loadInspectionSources(initialInspectionId || undefined);
}

async function handleRecordSubmit() {
  if (!recordForm.value.qualityInspectionId.trim()) {
    ElMessage.warning('请选择质检来源');
    return;
  }
  if (recordForm.value.quantity <= 0) {
    ElMessage.warning('不良数量必须 > 0');
    return;
  }
  if (sourceError.value || !selectedSource.value) {
    ElMessage.warning('请选择当前工厂可用的真实质检来源');
    return;
  }
  submitting.value = true;
  try {
    const res = await recordDefect(factoryId.value, {
      qualityInspectionId: recordForm.value.qualityInspectionId.trim(),
      defectType: recordForm.value.defectType,
      quantity: recordForm.value.quantity,
      cause: recordForm.value.cause.trim() || null,
    });
    if (res.success) {
      ElMessage.success('已登记不良');
      showRecordDialog.value = false;
      await load();
    }
  } finally {
    submitting.value = false;
  }
}

function openAssignDialog(row: QualityDefect) {
  currentDefect.value = row;
  actionMode.value = 'assign';
  actionForm.value = {
    handlingAction: row.handlingAction || '',
    assignedTo: row.assignedTo,
    closeNotes: '',
  };
  showActionDialog.value = true;
  void loadAssigneeDirectory();
}

function openCloseDialog(row: QualityDefect) {
  currentDefect.value = row;
  actionMode.value = 'close';
  actionForm.value = {
    handlingAction: '',
    assignedTo: null,
    closeNotes: '',
  };
  showActionDialog.value = true;
}

async function handleActionSubmit() {
  if (!currentDefect.value) return;
  submitting.value = true;
  try {
    if (actionMode.value === 'assign') {
      if (!actionForm.value.handlingAction.trim()) {
        ElMessage.warning('处置动作必填');
        submitting.value = false;
        return;
      }
      if (!selectedAssigneeIsValid.value) {
        ElMessage.warning('处理人已失效或不属于当前工厂，请重新选择');
        submitting.value = false;
        return;
      }
      if (directoryError.value && actionForm.value.assignedTo != null) {
        ElMessage.warning('处理人员工厂名录不可用，请刷新后重新选择');
        submitting.value = false;
        return;
      }
      const res = await assignDefect(factoryId.value, currentDefect.value.id, {
        handlingAction: actionForm.value.handlingAction.trim(),
        assignedTo: actionForm.value.assignedTo,
      });
      if (res.success) {
        ElMessage.success('已分派处理');
        showActionDialog.value = false;
        await load();
      }
    } else {
      const res = await closeDefect(factoryId.value, currentDefect.value.id, {
        closeNotes: actionForm.value.closeNotes.trim() || null,
      });
      if (res.success) {
        ElMessage.success('已闭环');
        showActionDialog.value = false;
        await load();
      }
    }
  } finally {
    submitting.value = false;
  }
}

watch(factoryId, () => {
  sourceRequestGeneration += 1;
  directoryRequestGeneration += 1;
  inspectionSources.value = [];
  assigneeDirectory.value = [];
  rows.value = [];
  pagination.value.total = 0;
  sourceError.value = '';
  directoryError.value = '';
  showRecordDialog.value = false;
  showActionDialog.value = false;
  recordForm.value = {
    qualityInspectionId: '',
    defectType: 'OTHER',
    quantity: 1,
    cause: '',
  };
  actionForm.value = { handlingAction: '', assignedTo: null, closeNotes: '' };
  currentDefect.value = null;
});

onMounted(load);
</script>

<template>
  <div class="defect-list">
    <div class="page-header">
      <div>
        <h2 class="title">工序质检不良</h2>
        <p class="subtitle">登记 → 分派 → 闭环 完整流程</p>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openRecordDialog()">登记不良</el-button>
      </div>
    </div>

    <div class="filter-row">
      <el-select
        v-model="filterStatus"
        placeholder="状态"
        clearable
        style="width: 160px"
        @change="handleFilterChange"
      >
        <el-option
          v-for="s in STATUS_OPTIONS"
          :key="s"
          :label="DEFECT_STATUS_LABELS[s]"
          :value="s"
        />
      </el-select>
      <el-select
        v-model="filterType"
        placeholder="缺陷类型"
        clearable
        style="width: 200px"
        @change="handleFilterChange"
      >
        <el-option
          v-for="t in TYPE_OPTIONS"
          :key="t"
          :label="DEFECT_TYPE_LABELS[t]"
          :value="t"
        />
      </el-select>
    </div>

    <el-table v-loading="loading" :data="rows" stripe empty-text="暂无不良记录" style="width: 100%">
      <el-table-column label="缺陷类型" min-width="120">
        <template #default="{ row }">
          <el-tag>{{ DEFECT_TYPE_LABELS[String(row.defectType) as DefectType] || row.defectType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" min-width="100" align="right" />
      <el-table-column prop="qualityInspectionId" label="关联质检 ID" min-width="180" show-overflow-tooltip />
      <el-table-column prop="cause" label="原因" min-width="180" show-overflow-tooltip />
      <el-table-column prop="handlingAction" label="处置动作" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.handlingAction || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ DEFECT_STATUS_LABELS[String(row.status) as DefectStatus] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="登记时间" min-width="160" />
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'OPEN'"
            link
            type="primary"
            @click="openAssignDialog(row)"
          >
            分派
          </el-button>
          <el-button
            v-if="row.status !== 'CLOSED'"
            link
            type="success"
            @click="openCloseDialog(row)"
          >
            闭环
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="pagination.total > 0"
      class="pagination"
      :current-page="pagination.page"
      :page-size="pagination.size"
      :total="pagination.total"
      layout="total, prev, pager, next, jumper"
      background
      @current-change="handlePageChange"
    />

    <!-- 登记不良 dialog -->
    <el-dialog v-model="showRecordDialog" title="登记质检不良" width="500px">
      <el-form label-position="top">
        <el-form-item label="质检来源" required>
          <el-select
            v-model="recordForm.qualityInspectionId"
            filterable
            clearable
            :loading="sourceLoading"
            :disabled="!!sourceError"
            placeholder="选择真实质检来源"
            style="width: 100%"
          >
            <el-option
              v-for="source in inspectionSources"
              :key="source.id"
              :label="inspectionLabel(source)"
              :value="source.id"
            />
          </el-select>
          <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon />
          <el-alert
            v-else-if="selectedSource"
            :title="`来源：${inspectionLabel(selectedSource)}`"
            :description="selectedSource.productName ? `品名：${selectedSource.productName}` : undefined"
            type="info"
            :closable="false"
          />
        </el-form-item>
        <el-form-item label="缺陷类型" required>
          <el-select v-model="recordForm.defectType" style="width: 100%">
            <el-option
              v-for="t in TYPE_OPTIONS"
              :key="t"
              :label="DEFECT_TYPE_LABELS[t]"
              :value="t"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="不良数量" required>
          <el-input-number v-model="recordForm.quantity" :min="0.0001" :precision="4" :step="1" style="width: 200px" />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="recordForm.cause" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRecordDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleRecordSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 分派 / 闭环 dialog -->
    <el-dialog
      v-model="showActionDialog"
      :title="actionMode === 'assign' ? '分派处理' : '闭环验证'"
      width="500px"
    >
      <el-form label-position="top">
        <template v-if="actionMode === 'assign'">
          <el-form-item label="处置动作" required>
            <el-input
              v-model="actionForm.handlingAction"
              type="textarea"
              :rows="2"
              placeholder="返工 / 报废 / 降级 / 退回供应商 / 其他"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
          <el-form-item label="处理人（可选）">
            <el-select
              v-model="actionForm.assignedTo"
              clearable
              filterable
              :loading="directoryLoading"
              :disabled="!!directoryError && actionForm.assignedTo == null"
              placeholder="选择本厂活跃人员"
              style="width: 100%"
            >
              <el-option
                v-for="user in assigneeDirectory"
                :key="user.id"
                :label="assigneeLabel(user)"
                :value="user.id"
              />
            </el-select>
            <el-alert
              v-if="actionForm.assignedTo != null && !selectedAssigneeIsValid"
              title="当前处理人已失效或不属于当前工厂，请重新选择"
              type="error"
              :closable="false"
              show-icon
            />
            <el-alert
              v-else-if="directoryError"
              :title="directoryError"
              type="error"
              :closable="false"
              show-icon
            />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="闭环备注 (验证结论)">
            <el-input
              v-model="actionForm.closeNotes"
              type="textarea"
              :rows="3"
              placeholder="可选"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="showActionDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleActionSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.defect-list {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-header .title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: #303133;
}
.page-header .subtitle {
  font-size: 13px;
  color: #909399;
  margin: 0;
}
.filter-row {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}
.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
