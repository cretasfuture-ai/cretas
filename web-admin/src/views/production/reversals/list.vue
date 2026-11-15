<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import type { TableColumn } from '@nuxt/ui';
import { badgeColor } from '@/utils/csBadge';
import { get, put } from '@/api/request';
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';
import type { TableRow } from '@/types/api';
import SelfApprovalReasonField from '@/components/approval/SelfApprovalReasonField.vue';
import { isSelfApproval, validateSelfApprovalReason } from '@/utils/selfApproval';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

// -------- 列表状态 --------
const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const statusFilter = ref('');

// -------- 审批 Dialog 状态 --------
const approvalDialogVisible = ref(false);
const approvalLoading = ref(false);
const approvalTarget = ref<TableRow | null>(null);
const approvalOpinion = ref('');
const approvalAction = ref<'approve' | 'reject'>('approve');
// 本人审批 (纪律排查 B1): 当前登录人就是撤回申请人时, 批准必须写原因
const selfApprovalReason = ref('');
const currentUserId = computed(() => authStore.user?.id);
const isSelfApprove = computed(() => approvalAction.value === 'approve'
  && isSelfApproval(approvalTarget.value?.submittedBy as number | string | undefined, currentUserId.value));

// -------- 拒绝原因 --------
const REJECT_REASONS = [
  '数据仍需保留',
  '流程审批未完成',
  '成品已部分出库',
  '申请信息有误',
  '其他',
] as const;
const rejectReasonSelected = ref('数据仍需保留');
const rejectReasonItems = REJECT_REASONS.map(r => ({ label: r, value: r }));
const statusItems = [
  { label: '全部', value: 'ALL' },
  { label: '待审批', value: 'PENDING' },
  { label: '已批准', value: 'APPROVED' },
  { label: '已撤回', value: 'DONE' },
  { label: '已驳回', value: 'REJECTED' },
];
// USelect 的选项值不能是空串: 页面内用 ALL 表示不筛, 请求时还原成空串
const statusSelect = computed({
  get: () => statusFilter.value || 'ALL',
  set: (v: string) => { statusFilter.value = v === 'ALL' ? '' : v; },
});
const columns: TableColumn<TableRow>[] = [
  { id: 'batch', header: '批次 ID' },
  { id: 'scope', header: '撤回范围' },
  { id: 'status', header: '状态' },
  { id: 'reason', header: '申请原因' },
  { id: 'reverted', header: '已回退流水' },
  { id: 'submittedBy', header: '提交人 ID' },
  { id: 'approvedBy', header: '审批人 ID' },
  { id: 'approvedAt', header: '审批时间' },
  { id: 'createdAt', header: '申请时间' },
  { id: 'actions', header: '操作' },
];
const txnCount = (r: TableRow) => (Array.isArray(r.revertedTxnIds) ? r.revertedTxnIds.length : 0);
const rejectReasonOther = ref('');

onMounted(() => {
  loadData();
});

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params = statusFilter.value ? `?status=${statusFilter.value}` : '';
    const res = await get<TableRow[]>(`/${factoryId.value}/reversals${params}`);
    if (res.success) {
      tableData.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch (e) {
    handleCatchError(e, '加载撤回申请列表失败，请检查网络');
  } finally {
    loading.value = false;
  }
}

function getStatusType(status: string): 'warning' | 'success' | 'info' | 'danger' {
  const map: Record<string, 'warning' | 'success' | 'info' | 'danger'> = {
    PENDING: 'warning',
    APPROVED: 'info',
    DONE: 'success',
    REJECTED: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '待审批',
    APPROVED: '已批准',
    DONE: '已撤回',
    REJECTED: '已驳回',
  };
  return map[status?.toUpperCase()] || status || '-';
}

function getReversalScopeText(scope: string): string {
  return scope === 'WHOLE_ORDER' ? '整单撤回' : scope || '-';
}

// 点击审批/拒绝 — 打开 Dialog 并设置上下文
function openApprovalDialog(row: TableRow, action: 'approve' | 'reject') {
  approvalTarget.value = row;
  approvalAction.value = action;
  approvalOpinion.value = '';
  selfApprovalReason.value = '';
  rejectReasonSelected.value = '数据仍需保留';
  rejectReasonOther.value = '';
  approvalDialogVisible.value = true;
}

// 确认审批
async function confirmApproval() {
  if (!factoryId.value || !approvalTarget.value) return;
  const logId = approvalTarget.value.id as number;

  // 拒绝时拼理由
  let finalReason = approvalOpinion.value;
  if (approvalAction.value === 'reject') {
    const selected = rejectReasonSelected.value;
    const other = rejectReasonOther.value.trim();
    finalReason = selected === '其他' ? (other || '其他') : (other ? `${selected}: ${other}` : selected);
  }

  approvalLoading.value = true;
  try {
    if (approvalAction.value === 'approve') {
      if (isSelfApprove.value) {
        const invalid = validateSelfApprovalReason(selfApprovalReason.value);
        if (invalid) {
          ElMessage({ message: invalid, type: 'error', duration: 0, showClose: true });
          return;
        }
      }
      await put(`/${factoryId.value}/reversals/${logId}/approve`,
        isSelfApprove.value ? { selfApprovalReason: selfApprovalReason.value.trim() } : {});
      ElMessage.success('已批准撤回申请，系统正在执行撤回');
    } else {
      await put(`/${factoryId.value}/reversals/${logId}/reject`, { reason: finalReason });
      ElMessage.success('已驳回撤回申请');
    }
    approvalDialogVisible.value = false;
    loadData();
  } catch (e) {
    handleCatchError(e, approvalAction.value === 'approve' ? '审批操作失败' : '驳回操作失败');
  } finally {
    approvalLoading.value = false;
  }
}

// 跳转批次详情
function goToBatch(batchId: number) {
  router.push(`/production/batches/${batchId}`);
}
</script>

<template>
  <div class="page-container">
    <div class="cs-page-head">
      <div>
        <h2>报工撤回申请</h2>
        <p>管理生产批次的整单撤回申请与审批</p>
      </div>
      <div class="head-actions">
        <USelect v-model="statusSelect" :items="statusItems" class="w-36" @update:model-value="loadData" />
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
      </div>
    </div>

    <section class="cs-card">
      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table">
        <template #empty>暂无撤回申请</template>
        <template #batch-cell="{ row }">
          <UButton variant="link" class="font-semibold" @click="goToBatch(row.original.batchId as number)">#{{ row.original.batchId }}</UButton>
        </template>
        <template #scope-cell="{ row }">
          <UBadge color="neutral" variant="soft">{{ getReversalScopeText(row.original.reversalScope as string) }}</UBadge>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(getStatusType(row.original.status as string))" variant="soft">{{ getStatusText(row.original.status as string) }}</UBadge>
        </template>
        <template #reason-cell="{ row }"><span class="muted">{{ row.original.reason || '—' }}</span></template>
        <template #reverted-cell="{ row }">
          <span v-if="txnCount(row.original) > 0" class="ok">{{ txnCount(row.original) }} 笔</span>
          <span v-else class="muted">—</span>
        </template>
        <template #submittedBy-cell="{ row }"><span class="muted">{{ row.original.submittedBy || '—' }}</span></template>
        <template #approvedBy-cell="{ row }"><span class="muted">{{ row.original.approvedBy || '—' }}</span></template>
        <template #approvedAt-cell="{ row }"><span class="muted">{{ row.original.approvedAt ? formatDateTime(row.original.approvedAt as string) : '—' }}</span></template>
        <template #createdAt-cell="{ row }"><span class="muted">{{ formatDateTime(row.original.createdAt as string) }}</span></template>
        <template #actions-cell="{ row }">
          <div v-if="row.original.status === 'PENDING' && canWrite" class="row-actions">
            <UButton size="xs" color="success" @click="openApprovalDialog(row.original, 'approve')">批准</UButton>
            <UButton size="xs" color="error" variant="outline" @click="openApprovalDialog(row.original, 'reject')">驳回</UButton>
          </div>
          <UButton v-else size="xs" variant="link" @click="goToBatch(row.original.batchId as number)">查看批次</UButton>
        </template>
      </UTable>
    </section>

    <!-- ===== 审批弹窗 ===== -->
    <UModal
      v-model:open="approvalDialogVisible"
      :title="approvalAction === 'approve' ? '批准撤回申请' : '驳回撤回申请'"
      description="审批整单撤回申请"
      :dismissible="false"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only' }"
    >
      <template #body>
        <template v-if="approvalTarget">
          <!-- 批次上下文（fool-proof Rule 2: context 必带） -->
          <UAlert color="info" variant="subtle" icon="i-lucide-info" class="mb-4" :title="`撤回对象: 批次 #${approvalTarget.batchId}`">
            <template #description>
              <div class="ctx">
                <span v-if="approvalTarget.planId">关联计划: <strong>{{ approvalTarget.planId }}</strong><br></span>
                撤回范围: <strong>{{ getReversalScopeText(approvalTarget.reversalScope as string) }}</strong><br>
                <template v-if="txnCount(approvalTarget) > 0">
                  将回退流水: <strong>{{ txnCount(approvalTarget) }} 笔</strong>（含原料回补 / WIP 作废 / 成品软删）<br>
                </template>
                申请原因: <strong>{{ approvalTarget.reason || '（未填写）' }}</strong>
              </div>
            </template>
          </UAlert>

          <!-- 拒绝时: 原因 dropdown（fool-proof Rule 3） -->
          <template v-if="approvalAction === 'reject'">
            <UFormField label="驳回原因" required>
              <USelect v-model="rejectReasonSelected" :items="rejectReasonItems" placeholder="选择驳回原因" class="w-full" data-testid="reject-reason" />
            </UFormField>
            <UTextarea
              v-if="rejectReasonSelected === '其他'"
              v-model="rejectReasonOther"
              :rows="3"
              placeholder="请补充说明驳回原因"
              :maxlength="300"
              class="w-full mt-2"
            />
            <UTextarea
              v-else
              v-model="rejectReasonOther"
              :rows="2"
              :placeholder="`可选: 补充说明（已选: ${rejectReasonSelected}）`"
              :maxlength="200"
              class="w-full mt-2"
            />
          </template>

          <!-- 批准时: 可选意见 -->
          <template v-else>
            <UFormField label="审批意见（可选）">
              <UTextarea v-model="approvalOpinion" :rows="3" placeholder="可填写审批意见，不填写则默认通过" :maxlength="200" class="w-full" />
            </UFormField>
            <UAlert
              color="warning"
              variant="subtle"
              icon="i-lucide-triangle-alert"
              class="mt-3"
              title="批准后将自动执行整单撤回: 原料回补、WIP 作废、成品软删、移动均价重算。此操作不可逆。"
            />
            <SelfApprovalReasonField v-if="isSelfApprove" v-model="selfApprovalReason" action-label="审批" />
          </template>
        </template>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="approvalDialogVisible = false">取消</UButton>
        <UButton :color="approvalAction === 'approve' ? 'success' : 'error'" :loading="approvalLoading" @click="confirmApproval">
          {{ approvalAction === 'approve' ? '确认批准' : '确认驳回' }}
        </UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-container { padding: var(--cs-space-4); }
.head-actions { display: flex; gap: var(--cs-space-2); align-items: center; }
.row-actions { display: flex; gap: var(--cs-space-2); }
.muted { color: var(--cs-muted); }
.ok { color: var(--cs-success); font-weight: 500; }
.ctx { line-height: 1.6; color: var(--cs-muted); }
</style>
