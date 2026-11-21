<script setup lang="ts">
import { CompatAlert, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
/**
 * 待审批请购单 — Sprint 6 W2-A.
 *
 * 列表 filter by status = PENDING_APPROVAL.
 * 给采购经理用 — 一键审批通过 / 驳回 (with reason dropdown).
 *
 * RBAC: 审批/驳回需 procurement:read_write (后端 @RequirePermission 强校验).
 *
 * Fool-proof Rule 3 (constrained inputs): 驳回原因走 dropdown + 其他时显 textarea
 * (不是纯 textarea 让用户瞎写无统计价值).
 */
import { ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useUserDirectory } from '@/composables/useUserDirectory';

import { Refresh } from '@element-plus/icons-vue';
import {
  listRequisitions,
  approveRequisition,
  rejectRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));

const tableData = ref<PurchaseRequisition[]>([]);
const loading = ref(false);
const pagination = ref({ page: 1, size: 20, total: 0 });

// 驳回 dialog state (Fool-proof Rule 3 — constrained dropdown + 'other' textarea)
const rejectDialogVisible = ref(false);
const rejectingRow = ref<PurchaseRequisition | null>(null);
const rejectReasonCode = ref<string>('');
const rejectReasonOther = ref<string>('');

const REJECT_REASONS: { code: string; label: string }[] = [
  { code: 'BUDGET_EXCEEDED', label: t('procurement.requisitions_pending_approval.label_c0e2fd261ba0') },
  { code: 'STOCK_AVAILABLE', label: t('procurement.requisitions_pending_approval.label_43dc5c656a08') },
  { code: 'SUPPLIER_ISSUE', label: t('procurement.requisitions_pending_approval.label_3c157f52fc27') },
  { code: 'NEED_MORE_INFO', label: t('procurement.requisitions_pending_approval.label_99b62bf1306d') },
  { code: 'TIMING_ISSUE', label: t('procurement.requisitions_pending_approval.label_7bb6d42b4c4d') },
  { code: 'OTHER', label: t('procurement.requisitions_pending_approval.label_855302f641e8') },
];

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listRequisitions(factoryId.value, {
      status: 'PENDING_APPROVAL',
      page: pagination.value.page,
      size: pagination.value.size,
    });
    if (res?.success && res.data) {
      tableData.value = res.data.content ?? [];
      pagination.value.total = res.data.totalElements ?? 0;
    }
  } catch (e) {
    console.error('[待审批列表加载失败]', e);
  } finally {
    loading.value = false;
  }
}


// 缺陷 5 (2026-09-01, LIUSHANMEN): 「请购人」列原来渲染 `#{{ row.requesterId }}`,
// 生产上显示 `#1638` —— 一个数据库主键, 采购主管看不出是谁提的单。
// ⛔ 不为此开 hr 权限; 解析口径与回退规则见 `@/composables/useUserDirectory` 头部。
const { load: loadUserDirectory, personLabel } = useUserDirectory();

onMounted(() => {
  loadData();
  loadUserDirectory(factoryId.value);
});

function handlePageChange(p: number) {
  pagination.value.page = p;
  loadData();
}
function handleSizeChange(s: number) {
  pagination.value.size = s;
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  pagination.value.page = 1;
  loadData();
}
function goDetail(id: string) {
  router.push(`/procurement/requisitions/${id}`);
}

async function handleApprove(row: PurchaseRequisition) {
  const lines = Array.isArray(row.requestedItems) ? row.requestedItems.length : 0;
  // Fool-proof Rule 2 (context): 单号 + 行数 + 请购人 ID
  try {
    await ElMessageBox.confirm(
      `确认审批通过请购单 ${row.requisitionNumber}?\n\n` +
      `明细 ${lines} 行 · 请购人 #${row.requesterId}` +
      `${row.expectedDate ? ' · 期望交货 ' + row.expectedDate : ''}`,
      t('procurement.requisitions_pending_approval.message_642cfa74e9d8'),
      { type: 'success', confirmButtonText: t('procurement.requisitions_pending_approval.confirmbuttontext_1e9f2561b7cf') },
    );
  } catch {
    return;
  }
  if (!factoryId.value) return;
  try {
    const res = await approveRequisition(factoryId.value, row.id);
    if (res?.success) {
      ElMessage.success(`已审批通过 ${row.requisitionNumber}`);
      await loadData();
    }
  } catch (e) {
    console.error('[审批失败]', e);
  }
}

function openRejectDialog(row: PurchaseRequisition) {
  rejectingRow.value = row;
  rejectReasonCode.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

const finalRejectReason = computed(() => {
  if (!rejectReasonCode.value) return '';
  if (rejectReasonCode.value === 'OTHER') {
    return rejectReasonOther.value.trim();
  }
  const found = REJECT_REASONS.find((r) => r.code === rejectReasonCode.value);
  return found ? found.label : '';
});

const canConfirmReject = computed(() => {
  if (!rejectReasonCode.value) return false;
  if (rejectReasonCode.value === 'OTHER') {
    return rejectReasonOther.value.trim().length >= 5;
  }
  return true;
});

async function confirmReject() {
  if (!canConfirmReject.value) return;
  if (!rejectingRow.value || !factoryId.value) return;
  const reason = finalRejectReason.value;
  try {
    const res = await rejectRequisition(
      factoryId.value,
      rejectingRow.value.id,
      reason,
    );
    if (res?.success) {
      ElMessage.success(`已驳回 ${rejectingRow.value.requisitionNumber}`);
      rejectDialogVisible.value = false;
      rejectingRow.value = null;
      await loadData();
    }
  } catch (e) {
    console.error('[驳回失败]', e);
  }
}
</script>

<template>
  <div class="page-wrapper">
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('procurement.requisitions_pending_approval.text_309d71433218') }}</span>
            <span class="data-count">
              {{ t('procurement.requisitions_pending_approval.text_76e547a8fa54') }} {{ pagination.total }} {{ t('procurement.requisitions_pending_approval.text_63bccfbae41c') }}
              <span v-if="pagination.total > 0" class="urgent">{{ t('procurement.requisitions_pending_approval.text_46427598a504') }}</span>
            </span>
          </div>
          <div class="header-right">
            <el-button :icon="Refresh" @click="handleRefresh">{{ t('procurement.requisitions_pending_approval.action_aee887434131') }}</el-button>
          </div>
        </div>
      </template>

      <CompatAlert
        v-if="!canWrite"
        :title="t('procurement.requisitions_pending_approval.title_d64e87e27ed6')"
        :description="t('procurement.requisitions_pending_approval.description_ac4e583259bd')"
        color="warning"
        :close="false"
        style="margin-bottom: 16px"
      />

      <CompatTable
        v-loading="loading"
        :data="tableData"
        stripe
        border
        style="width: 100%"
      >
        <template #empty><ListEmptyExplain empty-title="当前没有待审批的请购单" empty-hint="请购单提交后会出现在这里等待审批；没有则说明都已处理。" /></template>
        <CompatTableColumn prop="requisitionNumber" :label="t('procurement.requisitions_pending_approval.label_5c2eb0883fd7')" width="200" />
        <CompatTableColumn prop="requesterId" :label="t('procurement.requisitions_pending_approval.label_a81fcb4a6474')" width="100" align="center">
          <template #default="{ row }">{{ personLabel(row.requesterId) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('procurement.requisitions_pending_approval.label_206ea896472e')" min-width="240">
          <template #default="{ row }">
            <span v-if="!row.requestedItems || row.requestedItems.length === 0">
              -
            </span>
            <span v-else>
              {{ row.requestedItems.length }} {{ t('procurement.requisitions_pending_approval.text_f2a069e1d7fa') }}
              <span class="muted">
                {{ row.requestedItems
                  .slice(0, 2)
                  .map((it: { materialName?: string }) => it.materialName || '-')
                  .join(', ') }}{{ row.requestedItems.length > 2 ? ' …' : '' }}
              </span>
            </span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="expectedDate" :label="t('procurement.requisitions_pending_approval.label_d57fee96cba3')" width="120" />
        <CompatTableColumn :label="t('procurement.requisitions_pending_approval.label_6320b4a8722a')" width="100" align="center">
          <template #default="{ row }">
            <el-tag
              :type="REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.type || 'info'"
              size="small"
            >
              {{ REQUISITION_STATUS_MAP[row.status as PurchaseRequisitionStatus]?.text || row.status }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="createdAt" :label="t('procurement.requisitions_pending_approval.label_6bc352ca0ce0')" width="170">
          <template #default="{ row }">
            {{ row.createdAt ? String(row.createdAt).replace('T', ' ').slice(0, 16) : '-' }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('procurement.requisitions_pending_approval.label_ed31fbb483ee')" width="260" fixed="right" align="center">
          <template #default="{ row }">
            <UButton color="primary" variant="link" size="sm" @click="goDetail(row.id)">
              {{ t('procurement.requisitions_pending_approval.action_979a332955c8') }}
            </UButton>
            <UButton
              v-if="canWrite"
              color="success"
              variant="link"
              size="sm"
              @click="handleApprove(row)"
            >
              {{ t('procurement.requisitions_pending_approval.action_1e9f2561b7cf') }}
            </UButton>
            <UButton
              v-if="canWrite"
              color="error"
              variant="link"
              size="sm"
              @click="openRejectDialog(row)"
            >
              {{ t('procurement.requisitions_pending_approval.action_089e6ed3f55f') }}
            </UButton>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <div class="pagination-wrapper">
        <CompatPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </UCard>

    <!-- 驳回 dialog — Fool-proof Rule 3: dropdown + 'other' textarea -->
    <CompatDialog
      v-model="rejectDialogVisible"
      :title="t('procurement.requisitions_pending_approval.title_b90c4c545755')"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="rejectingRow">
        <div class="reject-context">
          {{ t('procurement.requisitions_pending_approval.text_0a2c04dac49d') }} <strong>{{ rejectingRow.requisitionNumber }}</strong> {{ t('procurement.requisitions_pending_approval.text_8ba62d4a6481') }} <strong>{{ personLabel(rejectingRow.requesterId) }}</strong>
        </div>
      </template>
      <CompatForm label-width="100px" style="margin-top: 12px">
        <CompatFormItem :label="t('procurement.requisitions_pending_approval.label_1245e49f52f3')" required>
          <CompatSelect
            v-model="rejectReasonCode"
            :placeholder="t('procurement.requisitions_pending_approval.placeholder_da590a8fe3ce')"
            style="width: 100%"
          >
            <CompatOption
              v-for="r in REJECT_REASONS"
              :key="r.code"
              :label="r.label"
              :value="r.code"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem
          v-if="rejectReasonCode === 'OTHER'"
          :label="t('procurement.requisitions_pending_approval.label_b110d4b5ef2f')"
          required
        >
          <el-input
            v-model="rejectReasonOther"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="t('procurement.requisitions_pending_approval.placeholder_c5c12c4ec850')"
          />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="rejectDialogVisible = false">{{ t('procurement.requisitions_pending_approval.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="error"
          :disabled="!canConfirmReject"
          @click="confirmReject"
        >
          {{ t('procurement.requisitions_pending_approval.action_c68e99e0908b') }}
        </UButton>
      </template>
    </CompatDialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}
.page-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid #ebeef5;
  }
  :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 20px;
  }
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;
    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: #303133;
    }
    .data-count {
      font-size: 13px;
      color: #909399;
    }
    .urgent {
      color: #e6a23c;
      font-weight: 600;
    }
  }
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  margin-top: 16px;
}
.muted {
  color: #909399;
}
.reject-context {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
</style>
