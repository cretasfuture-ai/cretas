<script setup lang="ts">
import { CompatPagination, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import { computed, onMounted, ref } from 'vue';

import type { ElTable } from 'element-plus';
import {
  Aim,
  Check,
  Download,
  EditPen,
  Refresh,
  RefreshRight,
  Warning,
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import {
  archiveLabelQcTask,
  backupLabelQcTask,
  decideLabelQcTraining,
  exportLabelQcTrainingData,
  getLabelQcStatusCounts,
  getLabelQcTask,
  listLabelQcTasks,
  retryLabelQcTask,
  restoreLabelQcTask,
  reviewLabelQcTask,
  type LabelQcReviewRequest,
  type LabelQcTaskDetail,
  type LabelQcTaskStatus,
  type LabelQcTaskSummary,
} from '@/api/labelQc';
import LabelQcReviewWorkbench from './LabelQcReviewWorkbench.vue';
import {
  createBulkPhotoArchive,
  createPhotoArchive,
  downloadPhotoArchive,
} from './photoArchive';
import {
  prepareLabelQcReviewAttempt,
  type LabelQcReviewAttempt,
} from './reviewSubmission';
import { toDateString } from '@/utils/dateFormat';

const STATUS_LABELS: Record<LabelQcTaskStatus, string> = {
  DRAFT: '草稿',
  UPLOADING: '上传中',
  QUEUED: '排队中',
  ANALYZING: 'AI 初筛中',
  NEEDS_REVIEW: '待人工审核',
  AUTO_PASSED: 'AI 未见风险',
  REVIEWED: '已审核',
  ANALYSIS_FAILED: 'AI 异常',
};

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const loading = ref(false);
const rows = ref<LabelQcTaskSummary[]>([]);
const counts = ref<Partial<Record<LabelQcTaskStatus, number>>>({});
const selectedStatuses = ref<LabelQcTaskStatus[]>(['NEEDS_REVIEW', 'ANALYSIS_FAILED']);
// 按生产日期筛选，配合大页容量，让"某天的货"能一次全选、一次导出。
const productionDateRange = ref<[string, string] | null>(null);
const PAGE_SIZES = [20, 50, 100, 200, 500];
const pagination = ref({ page: 1, size: 20, total: 0 });
const drawerVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<LabelQcTaskDetail | null>(null);
const submitting = ref(false);
const pendingReviewAttempt = ref<LabelQcReviewAttempt | null>(null);
const retryingTaskId = ref<string | null>(null);
const reviewDirty = ref(false);
const allowDrawerClose = ref(false);
const queueMode = ref<'REVIEW' | 'MANAGE' | 'ARCHIVED'>('REVIEW');
const actionTaskId = ref<string | null>(null);
const queueTableRef = ref<InstanceType<typeof ElTable>>();
const selectedPhotoTasks = ref<LabelQcTaskSummary[]>([]);
const bulkBackupLoading = ref(false);

const selectedPhotoCount = computed(() => selectedPhotoTasks.value.reduce(
  (total, task) => total + task.photoCount,
  0,
));

const canManageTraining = computed(() => {
  const currentUser = authStore.user;
  return currentUser?.userType === 'factory'
    && currentUser.factoryUser.permissions.includes('system:read_write');
});

const canReview = computed(() => {
  const status = detail.value?.task.status;
  return status === 'NEEDS_REVIEW' || status === 'ANALYSIS_FAILED';
});

async function load() {
  if (!factoryId.value) {
    ElMessage.warning(t('quality.label_qc_index.message_4e5a6d0c6d20'));
    return;
  }
  queueTableRef.value?.clearSelection();
  selectedPhotoTasks.value = [];
  loading.value = true;
  try {
    const [listResponse, countResponse] = await Promise.all([
      listLabelQcTasks(factoryId.value, {
        statuses: selectedStatuses.value.length ? selectedStatuses.value : undefined,
        archived: queueMode.value === 'ARCHIVED',
        productionDateFrom: productionDateRange.value?.[0],
        productionDateTo: productionDateRange.value?.[1],
        page: pagination.value.page,
        size: pagination.value.size,
      }),
      getLabelQcStatusCounts(factoryId.value),
    ]);
    if (listResponse.success) {
      rows.value = listResponse.data.content;
      pagination.value.total = listResponse.data.totalElements;
    }
    if (countResponse.success) counts.value = countResponse.data.counts;
  } finally {
    loading.value = false;
  }
}

function handlePhotoSelectionChange(selection: LabelQcTaskSummary[]): void {
  selectedPhotoTasks.value = selection.filter((task) => task.status === 'REVIEWED');
}

function canSelectForPhotoBackup(task: LabelQcTaskSummary): boolean {
  return task.status === 'REVIEWED' && !bulkBackupLoading.value;
}

function changeFilter() {
  pagination.value.page = 1;
  void load();
}

function changeQueueMode(value: string | number | boolean | undefined) {
  const mode = String(value) as 'REVIEW' | 'MANAGE' | 'ARCHIVED';
  queueMode.value = mode;
  selectedStatuses.value = mode === 'REVIEW'
    ? ['NEEDS_REVIEW', 'ANALYSIS_FAILED']
    : ['AUTO_PASSED', 'REVIEWED'];
  changeFilter();
}

function changePage(page: number) {
  pagination.value.page = page;
  void load();
}

// 一页 200 / 500 条：按生产日期筛完后一次勾完导出，不用一页一页翻着导。
function changePageSize(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  void load();
}

function statusType(status: LabelQcTaskStatus): 'danger' | 'warning' | 'success' | 'info' {
  if (status === 'AUTO_PASSED' || status === 'REVIEWED') return 'success';
  if (status === 'ANALYSIS_FAILED') return 'danger';
  if (status === 'NEEDS_REVIEW') return 'warning';
  return 'info';
}

function statusText(status: LabelQcTaskStatus): string {
  return STATUS_LABELS[status];
}

async function openReview(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  if (pendingReviewAttempt.value?.taskId !== row.id) pendingReviewAttempt.value = null;
  reviewDirty.value = false;
  allowDrawerClose.value = false;
  drawerVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    const response = await getLabelQcTask(factoryId.value, row.id);
    if (response.success) detail.value = response.data;
  } finally {
    detailLoading.value = false;
  }
}

async function submitReview(payload: LabelQcReviewRequest, keyboardConfirmed = false) {
  if (!factoryId.value || !detail.value || submitting.value) return;
  if (!keyboardConfirmed) {
    try {
      await ElMessageBox.confirm(
        `即将提交 ${payload.photos.length} 张照片的人工真值。提交后仍需技术管理员确认，才会进入训练集。`,
        t('quality.label_qc_index.message_5b5d9b438757'),
        {
          type: 'warning',
          confirmButtonText: t('quality.label_qc_index.confirmbuttontext_9ebe36f8cf23'),
          cancelButtonText: t('quality.label_qc_index.cancelbuttontext_9d4a1e91691b'),
        },
      );
    } catch {
      return;
    }
  }
  if (!factoryId.value || !detail.value || submitting.value) return;

  const taskId = detail.value.task.id;
  const attempt = prepareLabelQcReviewAttempt(
    pendingReviewAttempt.value,
    taskId,
    detail.value.task.version,
    payload,
  );
  pendingReviewAttempt.value = attempt;
  submitting.value = true;
  try {
    const response = await reviewLabelQcTask(
      factoryId.value,
      taskId,
      {
        ...payload,
        expectedVersion: detail.value.task.version,
        reviewRequestId: attempt.requestId,
      },
    );
    if (!response.success) {
      ElMessage.error(response.message || '本批提交失败，当前标注已保留，请重新提交');
      return;
    }
    detail.value = response.data;
    reviewDirty.value = false;
    allowDrawerClose.value = true;
    pendingReviewAttempt.value = null;
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络异常';
    ElMessage.warning(`本批暂未确认提交成功，标注已保留，可直接重试：${message}`);
    return;
  } finally {
    submitting.value = false;
  }

  ElMessage.success(t('quality.label_qc_index.message_44ac0bd649f6'));
  try {
    await load();

    // 提交锁只覆盖真实写请求；刷新队列和打开下一批失败也不能卡住已完成批次。
    const next = queueMode.value === 'REVIEW'
      ? rows.value.find((row) => row.id !== taskId && row.status === 'NEEDS_REVIEW')
      : undefined;
    if (next) {
      ElMessage.success(`本批已完成，继续下一批：${next.batchNumber || '未填写批次'}`);
      await openReview(next);
    } else {
      drawerVisible.value = false;
      ElMessage.success(t('quality.label_qc_index.message_88f9fb861b34'));
    }
  } catch {
    drawerVisible.value = false;
    ElMessage.warning(t('quality.label_qc_index.message_df1734713da6'));
  }
}

async function archiveTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  try {
    await ElMessageBox.confirm(
      t('quality.label_qc_index.message_8a31ff1d02e7'),
      t('quality.label_qc_index.message_10919ca358da'),
      {
        type: 'warning',
        confirmButtonText: t('quality.label_qc_index.confirmbuttontext_7850c90df42f'),
        cancelButtonText: t('quality.label_qc_index.cancelbuttontext_2cd0f3be8738'),
      },
    );
  } catch {
    return;
  }
  actionTaskId.value = row.id;
  try {
    const response = await archiveLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success(t('quality.label_qc_index.message_86c27d331a6c'));
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

async function restoreTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  actionTaskId.value = row.id;
  try {
    const response = await restoreLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success(t('quality.label_qc_index.message_396e8265a73a'));
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

function downloadJson(payload: unknown, filename: string) {
  const content = JSON.stringify(payload, null, 2);
  const blob = new Blob([content], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

async function backupTask(row: LabelQcTaskSummary) {
  if (!factoryId.value) return;
  actionTaskId.value = row.id;
  try {
    const detailResponse = await getLabelQcTask(factoryId.value, row.id);
    if (!detailResponse.success) return;
    let archive: Awaited<ReturnType<typeof createPhotoArchive>>;
    try {
      archive = await createPhotoArchive(detailResponse.data);
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '照片归档失败，请重试');
      return;
    }
    const recordResponse = await backupLabelQcTask(factoryId.value, row.id);
    if (!recordResponse.success) return;
    downloadPhotoArchive(archive);
    ElMessage.success(`已下载 ${archive.photoCount} 张 JPG 照片，并记录本次备份时间`);
    await load();
  } finally {
    actionTaskId.value = null;
  }
}

async function backupSelectedTasks() {
  if (!factoryId.value || !selectedPhotoTasks.value.length) return;
  const selected = [...selectedPhotoTasks.value];
  try {
    await ElMessageBox.confirm(
      `将把已选 ${selected.length} 批、共 ${selectedPhotoCount.value} 张照片整理成一个 ZIP。每批使用独立目录，照片仍按 SKU、提交日期和时间命名。${
        selectedPhotoCount.value > 100
          ? '照片较多，打包需要几分钟，期间请不要关闭或刷新页面。'
          : ''
      }`,
      t('quality.label_qc_index.message_14c78ee55527'),
      {
        type: 'info',
        confirmButtonText: t('quality.label_qc_index.confirmbuttontext_541961415c3d'),
        cancelButtonText: t('quality.label_qc_index.cancelbuttontext_2cd0f3be8738'),
      },
    );
  } catch {
    return;
  }

  bulkBackupLoading.value = true;
  try {
    const details: LabelQcTaskDetail[] = [];
    for (const task of selected) {
      const response = await getLabelQcTask(factoryId.value, task.id);
      if (!response.success) {
        throw new Error(`${task.skuName}（${task.batchNumber || '未填写批次'}）详情读取失败，未下载残缺归档`);
      }
      details.push(response.data);
    }

    const archive = await createBulkPhotoArchive(details);
    const recordResults = await Promise.allSettled(selected.map(async (task) => {
      const response = await backupLabelQcTask(factoryId.value!, task.id);
      if (!response.success) throw new Error(task.id);
    }));
    downloadPhotoArchive(archive);

    const failedRecords = recordResults.filter((result) => result.status === 'rejected').length;
    if (failedRecords) {
      ElMessage.warning({
        message: `已完整下载 ${selected.length} 批、${archive.photoCount} 张 JPG；其中 ${failedRecords} 批的备份时间记录失败，请稍后重试。`,
        duration: 7000,
      });
    } else {
      ElMessage.success(`已完整下载 ${selected.length} 批、${archive.photoCount} 张 JPG，并记录备份时间`);
    }
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量照片归档失败，请重试');
  } finally {
    bulkBackupLoading.value = false;
  }
}

async function decideTraining(row: LabelQcTaskSummary, approved: boolean) {
  if (!factoryId.value || !canManageTraining.value) return;
  try {
    await ElMessageBox.confirm(
      approved
        ? '批准后，这条人工真值会进入技术管理员可导出的训练集。此操作不会自动训练或发布模型。'
        : '拒绝后，这条数据仍会保留，但不会进入训练集。',
      approved ? '确认进入训练集？' : '确认拒绝训练？',
      {
        type: approved ? 'warning' : 'info',
        confirmButtonText: approved ? '确认批准' : '确认拒绝',
        cancelButtonText: t('quality.label_qc_index.cancelbuttontext_2cd0f3be8738'),
      },
    );
  } catch {
    return;
  }
  actionTaskId.value = row.id;
  try {
    const response = await decideLabelQcTraining(factoryId.value, row.id, {
      approved,
      expectedVersion: row.version,
      notes: approved ? '技术管理员已复核' : '技术管理员拒绝进入训练集',
    });
    if (response.success) {
      ElMessage.success(approved ? '已批准进入训练集' : '已拒绝进入训练集');
      await load();
    }
  } finally {
    actionTaskId.value = null;
  }
}

async function retryAnalysis(row: LabelQcTaskSummary): Promise<boolean> {
  if (!factoryId.value) return false;
  retryingTaskId.value = row.id;
  try {
    const response = await retryLabelQcTask(factoryId.value, row.id);
    if (response.success) {
      ElMessage.success(t('quality.label_qc_index.message_c82ddcdfcbc4'));
      await load();
      return true;
    }
    return false;
  } finally {
    retryingTaskId.value = null;
  }
}

async function retryCurrentTask() {
  if (!detail.value) return;
  if (reviewDirty.value) {
    try {
      await ElMessageBox.confirm(
        t('quality.label_qc_index.message_1f9c2efaf0d2'),
        t('quality.label_qc_index.message_26a6d3f192ac'),
        {
          type: 'warning',
          confirmButtonText: t('quality.label_qc_index.confirmbuttontext_b87ed9187213'),
          cancelButtonText: t('quality.label_qc_index.cancelbuttontext_8e663277881e'),
        },
      );
    } catch {
      return;
    }
  }
  const retried = await retryAnalysis(detail.value.task);
  if (!retried) return;
  reviewDirty.value = false;
  allowDrawerClose.value = true;
  drawerVisible.value = false;
}

function updateReviewDirty(dirty: boolean): void {
  reviewDirty.value = dirty;
}

async function handleReviewBeforeClose(done: () => void): Promise<void> {
  if (allowDrawerClose.value) {
    allowDrawerClose.value = false;
    reviewDirty.value = false;
    done();
    return;
  }
  if (!reviewDirty.value) {
    done();
    return;
  }
  try {
    await ElMessageBox.confirm(
      t('quality.label_qc_index.message_975bcc0968d6'),
      t('quality.label_qc_index.message_950ddcf51b1c'),
      {
        type: 'warning',
        confirmButtonText: t('quality.label_qc_index.confirmbuttontext_70403791a994'),
        cancelButtonText: t('quality.label_qc_index.cancelbuttontext_8e663277881e'),
        distinguishCancelAndClose: true,
      },
    );
    reviewDirty.value = false;
    done();
  } catch {
    // 保留抽屉和当前标注，审核员可以继续操作。
  }
}

async function exportTrainingData() {
  if (!factoryId.value) return;
  const to = new Date();
  const from = new Date(to);
  from.setDate(from.getDate() - 7);
  const response = await exportLabelQcTrainingData(factoryId.value, {
    from: from.toISOString(),
    to: to.toISOString(),
    limit: 500,
  });
  if (!response.success) return;
  downloadJson(
    response.data,
    `label-qc-training-approved-${toDateString(to)}.json`,
  );
  ElMessage.success(`已导出 ${response.data.length} 张已批准训练照片`);
}

onMounted(load);
</script>

<template>
  <div class="label-qc-page">
    <header class="page-header">
      <div>
        <div class="eyebrow">QUALITY CONTROL · HUMAN IN THE LOOP</div>
        <h1>{{ t('quality.label_qc_index.text_14d81ce3dd48') }}</h1>
        <p>{{ t('quality.label_qc_index.text_bb0b10ea7902') }}</p>
      </div>
      <div class="header-actions">
        <el-button
          v-if="canManageTraining"
          :icon="Download"
          @click="exportTrainingData"
        >
          {{ t('quality.label_qc_index.action_90384af131b5') }}
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('quality.label_qc_index.action_aee887434131') }}</el-button>
      </div>
    </header>

    <section class="summary-grid" :aria-label="t('quality.label_qc_index.aria_label_5a7b0f327a64')">
      <article class="summary-card urgent">
        <div class="summary-icon"><Warning /></div>
        <div>
          <span>{{ t('quality.label_qc_index.text_8fb733f15296') }}</span>
          <strong>{{ counts.NEEDS_REVIEW ?? 0 }}</strong>
        </div>
      </article>
      <article class="summary-card">
        <div class="summary-icon"><Aim /></div>
        <div>
          <span>{{ t('quality.label_qc_index.text_ec6a0355d92f') }}</span>
          <strong>{{ (counts.QUEUED ?? 0) + (counts.ANALYZING ?? 0) }}</strong>
        </div>
      </article>
      <article class="summary-card success">
        <div class="summary-icon"><Check /></div>
        <div>
          <span>{{ t('quality.label_qc_index.text_f28461bb49c8') }}</span>
          <strong>{{ (counts.AUTO_PASSED ?? 0) + (counts.REVIEWED ?? 0) }}</strong>
        </div>
      </article>
    </section>

    <section class="queue-card">
      <div class="queue-toolbar">
        <div>
          <h2>
            {{ queueMode === 'REVIEW' ? t('quality.label_qc_index.text_49a3a4c80ab6') : queueMode === 'MANAGE' ? t('quality.label_qc_index.text_6ec165b14cb2') : t('quality.label_qc_index.text_cc65c17d4edc') }}
          </h2>
          <span>
            {{
              queueMode === 'REVIEW'
                ? t('quality.label_qc_index.text_31f2be419fdb')
                : queueMode === 'MANAGE'
                  ? t('quality.label_qc_index.text_6be177ad19a3')
                  : t('quality.label_qc_index.text_5e1722f7b6d7')
            }}
          </span>
        </div>
        <div class="queue-controls">
          <el-radio-group
            v-model="queueMode"
            size="large"
            @change="changeQueueMode"
          >
            <el-radio-button value="REVIEW">{{ t('quality.label_qc_index.text_8fb733f15296') }}</el-radio-button>
            <el-radio-button value="MANAGE">{{ t('quality.label_qc_index.text_6ec165b14cb2') }}</el-radio-button>
            <el-radio-button value="ARCHIVED">{{ t('quality.label_qc_index.text_cc65c17d4edc') }}</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-model="productionDateRange"
            type="daterange"
            unlink-panels
            clearable
            value-format="YYYY-MM-DD"
            range-separator="至"
            :start-placeholder="t('quality.label_qc_index.start_placeholder_422eac7d8c2d')"
            :end-placeholder="t('quality.label_qc_index.end_placeholder_dd45fc11025f')"
            style="width: 280px"
            @change="changeFilter"
          />
          <el-select
            v-if="queueMode === 'REVIEW'"
            v-model="selectedStatuses"
            multiple
            collapse-tags
            collapse-tags-tooltip
            :placeholder="t('quality.label_qc_index.placeholder_d5e74de3449e')"
            style="width: 240px"
            @change="changeFilter"
          >
            <el-option
              v-for="(label, status) in STATUS_LABELS"
              :key="status"
              :label="label"
              :value="status"
            />
          </el-select>
        </div>
      </div>

      <div v-if="queueMode !== 'REVIEW'" class="bulk-backup-bar">
        <div>
          <strong>{{ t('quality.label_qc_index.text_1717d5f9a905') }}</strong>
          <span>
            {{
              selectedPhotoTasks.length
                ? `已选 ${selectedPhotoTasks.length} 批，共 ${selectedPhotoCount} 张照片`
                : t('quality.label_qc_index.text_48d93d1f5de0')
            }}
          </span>
        </div>
        <el-button
          type="primary"
          :icon="Download"
          :disabled="selectedPhotoTasks.length === 0"
          :loading="bulkBackupLoading"
          @click="backupSelectedTasks"
        >
          {{ t('quality.label_qc_index.action_de86bbf0a2f2') }}
        </el-button>
      </div>

      <CompatTable
        ref="queueTableRef"
        v-loading="loading || bulkBackupLoading"
        :data="rows"
        row-key="id"
        class="queue-table"
        @selection-change="handlePhotoSelectionChange"
      >
        <CompatTableColumn
          v-if="queueMode !== 'REVIEW'"
          type="selection"
          width="48"
          align="center"
          :selectable="canSelectForPhotoBackup"
        />
        <CompatTableColumn :label="t('quality.label_qc_index.label_73905c19fd48')" min-width="210">
          <template #default="{ row }">
            <div class="sku-cell">
              <strong>{{ row.skuName }}</strong>
              <span>{{ row.skuCode }} · {{ row.batchNumber || t('quality.label_qc_index.text_fa21ec82ce7b') }}</span>
            </div>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="productionDate" :label="t('quality.label_qc_index.label_5f7ee6022bd9')" width="105" />
        <CompatTableColumn :label="t('quality.label_qc_index.label_81bb3938a8be')" width="120">
          <template #default="{ row }">
            <span>{{ row.photoCount }} {{ t('quality.label_qc_index.text_2c4761ebecea') }} </span>
            <strong class="candidate-count">{{ row.aiCandidateCount }} {{ t('quality.label_qc_index.text_34ee554a469c') }}</strong>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('quality.label_qc_index.label_6320b4a8722a')" width="95">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn
          v-if="queueMode !== 'REVIEW'"
          :label="t('quality.label_qc_index.label_eb90338f3237')"
          width="110"
        >
          <template #default="{ row }">
            <el-tag
              :type="
                row.status === 'AUTO_PASSED'
                  ? 'info'
                  : row.trainingStatus === 'APPROVED'
                  ? 'success'
                  : row.trainingStatus === 'REJECTED'
                    ? 'danger'
                    : 'warning'
              "
              effect="plain"
            >
              {{
                row.status === 'AUTO_PASSED'
                  ? t('quality.label_qc_index.text_83cdce6f6201')
                  : row.trainingStatus === 'APPROVED'
                  ? t('quality.label_qc_index.text_b42cb548c1d4')
                  : row.trainingStatus === 'REJECTED'
                    ? t('quality.label_qc_index.text_f098218a3032')
                    : t('quality.label_qc_index.text_e8b405734d47')
              }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="createdAt" :label="t('quality.label_qc_index.label_6bc352ca0ce0')" min-width="145">
          <template #default="{ row }">
            {{ new Date(row.createdAt).toLocaleString() }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn
          :label="t('quality.label_qc_index.label_ed31fbb483ee')"
          :width="queueMode === 'REVIEW' ? 210 : 330"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'NEEDS_REVIEW' || row.status === 'ANALYSIS_FAILED'"
              type="primary"
              :icon="EditPen"
              @click="openReview(row)"
            >
              {{ t('quality.label_qc_index.action_cee45f307836') }}
            </el-button>
            <UButton color="neutral" v-else variant="ghost" @click="openReview(row)">{{ t('quality.label_qc_index.action_db8db0530432') }}</UButton>
            <el-button
              v-if="row.status === 'ANALYSIS_FAILED'"
              text
              :icon="RefreshRight"
              :loading="retryingTaskId === row.id"
              @click="retryAnalysis(row)"
            >
              {{ t('quality.label_qc_index.action_40e2ca01d094') }}
            </el-button>
            <template v-if="row.status === 'REVIEWED'">
              <UButton color="neutral"
                variant="ghost"
                :loading="actionTaskId === row.id"
                :disabled="bulkBackupLoading"
                @click="backupTask(row)"
              >
                {{ t('quality.label_qc_index.action_3af3392516a7') }}
              </UButton>
              <UButton
                v-if="queueMode !== 'ARCHIVED'"
                variant="ghost"
                color="warning"
                :loading="actionTaskId === row.id"
                @click="archiveTask(row)"
              >
                {{ t('quality.label_qc_index.action_5292ab1a36c5') }}
              </UButton>
              <UButton
                v-else
                variant="ghost"
                color="primary"
                :loading="actionTaskId === row.id"
                @click="restoreTask(row)"
              >
                {{ t('quality.label_qc_index.action_e0534b8a4e46') }}
              </UButton>
              <UButton
                v-if="canManageTraining && row.trainingStatus !== 'APPROVED'"
                variant="ghost"
                color="success"
                :loading="actionTaskId === row.id"
                @click="decideTraining(row, true)"
              >
                {{ t('quality.label_qc_index.action_c683ddb5a268') }}
              </UButton>
              <UButton
                v-if="canManageTraining && row.trainingStatus !== 'REJECTED'"
                variant="ghost"
                color="error"
                :loading="actionTaskId === row.id"
                @click="decideTraining(row, false)"
              >
                {{ t('quality.label_qc_index.action_8332bc871364') }}
              </UButton>
            </template>
          </template>
        </CompatTableColumn>
        <template #empty>
          <div class="empty-state">
            <Check />
            <strong>{{ t('quality.label_qc_index.text_e6bb20a080e6') }}</strong>
            <span>{{ t('quality.label_qc_index.text_250cff123338') }}</span>
          </div>
        </template>
      </CompatTable>

      <div class="pagination-row">
        <CompatPagination
          background
          layout="total, sizes, prev, pager, next, jumper"
          :total="pagination.total"
          :page-size="pagination.size"
          :page-sizes="PAGE_SIZES"
          :current-page="pagination.page"
          @current-change="changePage"
          @size-change="changePageSize"
        />
      </div>
    </section>

    <el-drawer
      v-model="drawerVisible"
      size="96%"
      direction="rtl"
      :close-on-click-modal="false"
      :destroy-on-close="true"
      :before-close="handleReviewBeforeClose"
      class="review-drawer"
    >
      <template #header>
        <div class="drawer-heading">
          <div>
            <strong>{{ t('quality.label_qc_index.text_15a6c37ddc32') }}</strong>
            <span>{{ t('quality.label_qc_index.text_a26313397d81') }}</span>
          </div>
          <div class="drawer-statuses">
            <div v-if="reviewDirty" class="draft-status">{{ t('quality.label_qc_index.text_aa38cbb293e4') }}</div>
            <div class="review-rule">{{ t('quality.label_qc_index.text_896f13d464a1') }}</div>
          </div>
        </div>
      </template>

      <div v-loading="detailLoading" class="drawer-body">
        <LabelQcReviewWorkbench
          v-if="detail"
          :factory-id="factoryId"
          :detail="detail"
          :can-review="canReview"
          :submitting="submitting"
          :retrying="retryingTaskId === detail.task.id"
          @submit="submitReview"
          @retry="retryCurrentTask"
          @dirty-change="updateReviewDirty"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.label-qc-page {
  min-height: 100%;
  padding: 24px;
  color: #172a23;
  background:
    linear-gradient(135deg, rgba(0, 169, 135, .045), transparent 32%),
    #f4f6f3;
}

.page-header,
.queue-toolbar,
.drawer-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.page-header h1 {
  margin: 4px 0 6px;
  font-size: 28px;
  letter-spacing: -.03em;
}

.page-header p,
.queue-toolbar span {
  margin: 0;
  color: #65736e;
}

.eyebrow {
  color: #08745f;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .08em;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 24px 0 18px;
}

.summary-card {
  display: flex;
  min-height: 108px;
  align-items: center;
  gap: 14px;
  padding: 20px;
  border: 1px solid #dfe7e2;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 25px rgba(24, 46, 38, .05);
}

.summary-icon {
  display: grid;
  width: 46px;
  height: 46px;
  place-items: center;
  border-radius: 12px;
  color: #08745f;
  background: #e3f6ef;
}

.summary-icon :deep(svg) {
  width: 22px;
}

.summary-card.urgent .summary-icon {
  color: #b86600;
  background: #fff1d8;
}

.summary-card.success .summary-icon {
  color: #08745f;
  background: #def6ec;
}

.summary-card span,
.summary-card strong {
  display: block;
}

.summary-card span {
  color: #65736e;
  font-size: 13px;
}

.summary-card strong {
  margin-top: 3px;
  font-size: 30px;
}

.queue-card {
  overflow: hidden;
  border: 1px solid #dfe7e2;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 10px 30px rgba(24, 46, 38, .05);
}

.queue-toolbar {
  padding: 20px 22px;
  border-bottom: 1px solid #edf1ee;
}

.queue-toolbar h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.bulk-backup-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 22px;
  border-bottom: 1px solid #edf2f7;
  background: #f7fafc;
}

.bulk-backup-bar > div {
  display: grid;
  gap: 3px;
}

.bulk-backup-bar strong {
  color: #1a2332;
  font-size: 14px;
}

.bulk-backup-bar span {
  color: #7a8599;
  font-size: 12px;
}

.queue-controls {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.queue-table {
  width: 100%;
}

.sku-cell strong,
.sku-cell span {
  display: block;
}

.sku-cell span {
  margin-top: 4px;
  color: #69766f;
  font-size: 12px;
}

.candidate-count {
  color: #c25e00;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding: 18px 22px;
}

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 36px;
  color: #7a8781;
}

.empty-state :deep(svg) {
  width: 34px;
  color: #00a987;
}

.drawer-heading {
  width: 100%;
  padding-right: 28px;
}

.drawer-heading > div:first-child {
  display: grid;
  gap: 3px;
}

.drawer-heading strong {
  color: #172a23;
  font-size: 18px;
}

.drawer-heading span {
  color: #68766f;
  font-size: 12px;
}

.review-rule {
  padding: 7px 11px;
  border: 1px solid #f1c47c;
  border-radius: 8px;
  color: #965600;
  background: #fff7e7;
  font-size: 12px;
  font-weight: 800;
}

.drawer-statuses {
  display: flex;
  align-items: center;
  gap: 8px;
}

.draft-status {
  padding: 7px 11px;
  border: 1px solid #efb3ad;
  border-radius: 8px;
  color: #a83d34;
  background: #fff1ef;
  font-size: 12px;
  font-weight: 800;
}

.drawer-body {
  height: 100%;
  min-height: 400px;
}

:deep(.review-drawer .el-drawer__header) {
  height: 62px;
  margin: 0;
  padding: 10px 18px;
  border-bottom: 1px solid #dfe7e2;
}

:deep(.review-drawer .el-drawer__body) {
  overflow: hidden;
  padding: 0;
}

/* 标签密集审核必须保留整图、图层开关和逐盒判定三栏。全局短屏规则会把
   普通抽屉压到 560px，这个专用工作台在桌面短屏仍保持接近全屏。 */
:deep(.review-drawer.el-drawer) {
  width: 96% !important;
  max-width: 96vw;
}

@media (max-width: 900px) {
  :deep(.review-drawer.el-drawer) {
    width: 100% !important;
    max-width: 100vw;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .page-header,
  .queue-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .queue-controls {
    align-items: stretch;
    justify-content: flex-start;
  }

  .bulk-backup-bar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
