<script setup lang="ts">
/**
 * 请购单详情 — Sprint 6 W2-A.
 *
 * State machine actions wired:
 *   - DRAFT → 提交审批 (submit)
 *   - PENDING_APPROVAL → 审批通过 (approve) / 驳回 (reject with reason dropdown)
 *   - APPROVED → 转 PO (convertToPO with supplier select; idempotent backend)
 *
 * Fool-proof Rule 2 (context): all dialogs include 单号 + 行数 + 请购人.
 * Fool-proof Rule 3 (constrained inputs): reject reason dropdown + OTHER textarea.
 * Fool-proof Rule 4 (idempotent): convertToPO backend dedupes by convertedPoId.
 */
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft } from '@element-plus/icons-vue';
import { get } from '@/api/request';
import { useUserDirectory } from '@/composables/useUserDirectory';
import {
  getRequisition,
  submitRequisition,
  approveRequisition,
  rejectRequisition,
  convertToPO,
  deleteRequisition,
  REQUISITION_STATUS_MAP,
  type PurchaseRequisition,
  type PurchaseRequisitionStatus,
} from '@/api/purchaseRequisition';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId);
const requisitionId = computed(() => route.params.id as string);
const currentUserId = computed(() => authStore.user?.id ?? null);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');
const canManageOwnDraft = computed(() => {
  if (canWrite.value) return true;
  if (!isRestaurant.value || !permissionStore.canAccess('procurement')) return false;
  return ['warehouse_manager', 'restaurant_manager'].includes(authStore.currentRole);
});
const detailTitle = computed(() => (isRestaurant.value ? '报货单详情' : '请购单详情'));
const submitButtonText = computed(() => (isRestaurant.value ? '提交采购计划' : '提交审批'));
const expectedDateLabel = computed(() => (isRestaurant.value ? '希望到货' : '期望交货'));
// 缺陷 5 (2026-09-01, LIUSHANMEN): 列头原来叫「请购人ID」且值是 `#1638` ——
// 列头和值是**一起**定义成内部标识符的, 所以两者要一起改 (与缺陷 7 同形)。
const requesterLabel = computed(() => (isRestaurant.value ? '报货人' : '请购人'));
const requesterDeptLabel = computed(() => (isRestaurant.value ? '报货部门' : '请购部门'));
const reasonLabel = computed(() => (isRestaurant.value ? '报货原因' : '申请原因'));
const detailDividerText = computed(() => (isRestaurant.value ? '报货明细' : '请购明细'));

const loading = ref(false);
const submitting = ref(false);
const requisition = ref<PurchaseRequisition | null>(null);
const notFound = ref(false);
const notFoundMessage = ref('');

// Bug #6 (PR #96): 仅 DRAFT + requester 本人 可删 (backend 同步 enforce)
const canDelete = computed(
  () =>
    canManageOwnDraft.value &&
    requisition.value?.status === 'DRAFT' &&
    currentUserId.value !== null &&
    requisition.value?.requesterId === currentUserId.value,
);

interface Supplier {
  id: string;
  name: string;
}
const suppliers = ref<Supplier[]>([]);

// ─────────────────────────────────────────────────────────────────────────
// 缺陷 5 / 6 / 7 (2026-09-01, LIUSHANMEN 生产走查) —— 同一个形态的三个实例:
// **把数据库内部标识符摆到操作者面前当业务编号用**。
//   5: 请购人/审批人渲染成 `#1638` (users.id)
//   6: 「关联PO」渲染成 `97141602-6db9-…` (purchase_orders.id)
//   7: 「建议供应商 ID」整列渲染成 `d0b9c77a-…` (suppliers.id)
// 本仓 `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` 已在 RN 餐饮侧定过口径
// (「供应商与食材只使用业务名称展示, 移除内部 ID 泄漏」), web-admin 采购侧此前没跟上。
//
// ▎三处共同的回退口径: **查不到名字就回退显示原 ID, ⛔ 不回退成空白**。
//   「查不到这个人/这家供应商」与「这一格没有值」是两件事, 回退成空白会把前者
//   伪装成后者 (本仓形态 A¹⁰: 兜底的默认值会把「我不知道」翻译成一个假读数)。
// ─────────────────────────────────────────────────────────────────────────

// 人名解析抽在 `@/composables/useUserDirectory` —— 请购单的 列表 / 待审批 / 详情
// 三个页面都要它, ⛔ 不在三处各抄一份 (形态 D: 同一个东西有三份, 它一定会漂)。
// 端点选型、hr 权限为什么不能开、以及「查不到回退 #id 而不是空白」的理由,
// 全部写在那个文件的头部。
const { load: loadUserDirectory, personLabel } = useUserDirectory();

/** 建议供应商显示: 查得到显示名称, 查不到回退原 ID。 */
function supplierLabel(supplierId: string | null | undefined): string {
  if (!supplierId) return '-';
  const hit = suppliers.value.find((s) => String(s.id) === String(supplierId));
  return hit?.name ? String(hit.name) : String(supplierId);
}

// 缺陷 6: 「关联PO」要显示 **PO 单号**, 而跳转仍然用 UUID。
// ⚠️ 显示和跳转**必须拆开** —— goPO() 用的还是 convertedPoId, 一个字没改。
const convertedPoNumber = ref<string>('');

async function loadConvertedPoNumber(poId: string) {
  if (!factoryId.value || !poId) return;
  try {
    // 采购订单详情端点的 @RequirePermission 含 procurement:read ⇒ 采购主管进得来。
    const res = await get<{ orderNumber?: string }>(
      `/${factoryId.value}/purchase/orders/${poId}`,
      { _silent: true } as never,
    );
    convertedPoNumber.value = String(res?.data?.orderNumber || '');
  } catch {
    // 拿不到单号就回退显示 UUID (见 template), ⛔ 不显示空白
  }
}

// 驳回 dialog state (same constrained-dropdown as pending-approval.vue)
const rejectDialogVisible = ref(false);
const rejectReasonCode = ref<string>('');
const rejectReasonOther = ref<string>('');
const REJECT_REASONS: { code: string; label: string }[] = [
  { code: 'BUDGET_EXCEEDED', label: '预算超支' },
  { code: 'STOCK_AVAILABLE', label: '现有库存可满足, 暂不采购' },
  { code: 'SUPPLIER_ISSUE', label: '建议供应商不合格 / 替换' },
  { code: 'NEED_MORE_INFO', label: '信息不全, 请补充后再提' },
  { code: 'TIMING_ISSUE', label: '时间不合适 (排程冲突 / 节假日)' },
  { code: 'OTHER', label: '其他原因 (请说明)' },
];

// 转 PO dialog state
const convertDialogVisible = ref(false);
const convertSupplierId = ref<string>('');

async function loadRequisition() {
  if (!factoryId.value || !requisitionId.value) return;
  loading.value = true;
  try {
    const res = await getRequisition(factoryId.value, requisitionId.value);
    if (res?.success && res.data) {
      requisition.value = res.data;
      // Pre-fill convert dialog supplier from first item's suggestedSupplierId
      const firstItem = res.data.requestedItems?.[0];
      const suggested =
        firstItem &&
        typeof firstItem === 'object' &&
        'suggestedSupplierId' in firstItem
          ? String((firstItem as { suggestedSupplierId?: string }).suggestedSupplierId || '')
          : '';
      if (suggested) convertSupplierId.value = suggested;
      // 缺陷 6: 每次重载都重算单号 —— loadRequisition 在转PO/审批之后会被再调一次,
      // 不清掉会让「已转PO」之前的空值或上一单的号留在屏幕上。
      convertedPoNumber.value = '';
      if (res.data.convertedPoId) {
        await loadConvertedPoNumber(String(res.data.convertedPoId));
      }
    }
  } catch (err: unknown) {
    const e = err as { status?: number; response?: { status?: number; data?: { code?: string; message?: string } }; code?: string; message?: string };
    const status = e?.status ?? e?.response?.status;
    const code = e?.code ?? e?.response?.data?.code;
    if (status === 404 || status === 403 || code === 'NOT_FOUND' || code === 'FORBIDDEN') {
      notFound.value = true;
      notFoundMessage.value = e?.message || e?.response?.data?.message || '请购单不存在或无权访问';
    }
    // axios interceptor already showed sticky toast
  } finally {
    loading.value = false;
  }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content?: Supplier[] }>(
      `/${factoryId.value}/suppliers`,
      { params: { page: 1, size: 200 } },
    );
    suppliers.value = res?.data?.content ?? [];
  } catch {
    // interceptor handles
  }
}

onMounted(async () => {
  await Promise.all([loadRequisition(), loadSuppliers(), loadUserDirectory(factoryId.value)]);
});

const lineCount = computed(() =>
  Array.isArray(requisition.value?.requestedItems)
    ? requisition.value!.requestedItems.length
    : 0,
);

async function handleSubmit() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  try {
    await ElMessageBox.confirm(
      `确认${isRestaurant.value ? '提交采购计划' : '提交审批'}: ${r.requisitionNumber} (${lineCount.value} 行明细)?\n\n` +
      `${isRestaurant.value ? '报货人' : '请购人'} ${personLabel(r.requesterId)}` +
      `${r.expectedDate ? ` · ${expectedDateLabel.value} ${r.expectedDate}` : ''}`,
      isRestaurant.value ? '提交采购计划' : '提交审批',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await submitRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success(isRestaurant.value ? '已提交给采购' : '已提交审批');
      await loadRequisition();
    }
  } catch (e) {
    console.error('[提交失败]', e);
  } finally {
    submitting.value = false;
  }
}

async function handleApprove() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  try {
    await ElMessageBox.confirm(
      `确认审批通过请购单 ${r.requisitionNumber}?\n\n` +
      `明细 ${lineCount.value} 行 · ${isRestaurant.value ? '报货人' : '请购人'} ${personLabel(r.requesterId)}`,
      '审批通过',
      { type: 'success', confirmButtonText: '通过' },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await approveRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success('已审批通过');
      await loadRequisition();
    }
  } catch (e) {
    console.error('[审批失败]', e);
  } finally {
    submitting.value = false;
  }
}

function openRejectDialog() {
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
  if (!canConfirmReject.value || !requisition.value || submitting.value) return;
  submitting.value = true;
  try {
    const res = await rejectRequisition(
      factoryId.value,
      requisition.value.id,
      finalRejectReason.value,
    );
    if (res?.success) {
      ElMessage.success('已驳回');
      rejectDialogVisible.value = false;
      await loadRequisition();
    }
  } catch (e) {
    console.error('[驳回失败]', e);
  } finally {
    submitting.value = false;
  }
}

function openConvertDialog() {
  convertDialogVisible.value = true;
}

async function confirmConvert() {
  if (!convertSupplierId.value) {
    ElMessage.warning('请选择 PO 供应商');
    return;
  }
  if (!requisition.value || submitting.value) return;
  submitting.value = true;
  try {
    const res = await convertToPO(
      factoryId.value,
      requisition.value.id,
      convertSupplierId.value,
    );
    if (res?.success && res.data) {
      ElMessage.success(`已转为采购订单 ${res.data.orderNumber}`);
      convertDialogVisible.value = false;
      await loadRequisition();
    }
  } catch (e) {
    console.error('[转PO失败]', e);
  } finally {
    submitting.value = false;
  }
}

function goPO() {
  if (requisition.value?.convertedPoId) {
    router.push(`/procurement/orders/${requisition.value.convertedPoId}`);
  }
}

async function handleDelete() {
  if (!requisition.value || submitting.value) return;
  const r = requisition.value;
  // Fool-proof Rule 2: dialog 含单号 + 行数; Rule 5: backend 仅 DRAFT + requester 本人可删
  try {
    await ElMessageBox.confirm(
      `确认删除请购单 ${r.requisitionNumber} (${lineCount.value} 行明细)?\n\n此操作不可恢复.`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const res = await deleteRequisition(factoryId.value, r.id);
    if (res?.success) {
      ElMessage.success('请购单已删除');
      router.back();
    }
  } catch (e) {
    // Interceptor 已展示 sticky toast (含 backend actionHint); dedupe fallback log
    if (e !== 'cancel') console.error('[删除失败]', e);
  } finally {
    submitting.value = false;
  }
}

function goBack() {
  router.back();
}
</script>

<template>
  <div class="page-wrapper">
    <el-page-header :icon="ArrowLeft" @back="goBack">
      <template #content>
        <span class="page-header-title">{{ detailTitle }}</span>
      </template>
    </el-page-header>

    <div v-if="notFound" class="empty-state">
      <el-empty :description="notFoundMessage || '请购单不存在'">
        <el-button @click="goBack">返回</el-button>
      </el-empty>
    </div>

    <el-card v-else v-loading="loading" class="content-card" shadow="never">
      <template v-if="requisition">
        <div class="header-row">
          <div class="header-left">
            <span class="req-number">{{ requisition.requisitionNumber }}</span>
            <el-tag
              :type="REQUISITION_STATUS_MAP[requisition.status as PurchaseRequisitionStatus]?.type || 'info'"
              size="default"
            >
              {{ REQUISITION_STATUS_MAP[requisition.status as PurchaseRequisitionStatus]?.text || requisition.status }}
            </el-tag>
          </div>
          <div class="header-actions">
            <el-button
              v-if="requisition.status === 'DRAFT' && canManageOwnDraft"
              type="warning"
              :loading="submitting"
              @click="handleSubmit"
            >
              {{ submitButtonText }}
            </el-button>
            <el-button
              v-if="canDelete"
              type="danger"
              :loading="submitting"
              @click="handleDelete"
            >
              删除
            </el-button>
            <el-button
              v-if="requisition.status === 'PENDING_APPROVAL' && canWrite"
              type="success"
              :loading="submitting"
              @click="handleApprove"
            >
              审批通过
            </el-button>
            <el-button
              v-if="requisition.status === 'PENDING_APPROVAL' && canWrite"
              type="danger"
              @click="openRejectDialog"
            >
              驳回
            </el-button>
            <el-button
              v-if="requisition.status === 'APPROVED' && canWrite"
              type="primary"
              :loading="submitting"
              @click="openConvertDialog"
            >
              转采购订单
            </el-button>
            <el-button
              v-if="requisition.convertedPoId"
              type="primary"
              plain
              @click="goPO"
            >
              查看关联 PO
            </el-button>
          </div>
        </div>

        <el-descriptions :column="2" border style="margin-top: 16px">
          <el-descriptions-item :label="requesterLabel">
            {{ personLabel(requisition.requesterId) }}
          </el-descriptions-item>
          <el-descriptions-item :label="requesterDeptLabel">
            {{ requisition.requesterDeptId || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="expectedDateLabel">
            {{ requisition.expectedDate || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ requisition.createdAt ? String(requisition.createdAt).replace('T', ' ').slice(0, 19) : '-' }}
          </el-descriptions-item>
          <!-- 审批人与请购人同源同形 (都是 users.id), 一起改 —— 本仓「改共享结构前
               先数, 改完再数」: 这一页 requesterId/approverId 共 5 个渲染点。
               ⚠️ 这里**只改显示**, ⛔ 没有碰审批逻辑 —— 请购单能否自批 (审计里的
               待核 8) 是另一件事, 未在本轮改动范围内。 -->
          <el-descriptions-item label="审批人">
            {{ personLabel(requisition.approverId) }}
          </el-descriptions-item>
          <el-descriptions-item label="审批时间">
            {{ requisition.approvedAt ? String(requisition.approvedAt).replace('T', ' ').slice(0, 19) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.rejectionReason" label="驳回原因" :span="2">
            <span style="color: #f56c6c">{{ requisition.rejectionReason }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.convertedPoId" label="关联PO" :span="2">
            <!-- 缺陷 6: 显示 PO 单号, 跳转仍用 convertedPoId (见 goPO) —— 两者拆开。 -->
            <el-button type="primary" link @click="goPO">
              {{ convertedPoNumber || requisition.convertedPoId }}
            </el-button>
            <span v-if="requisition.convertedAt" style="margin-left: 12px; color: #909399">
              {{ String(requisition.convertedAt).replace('T', ' ').slice(0, 19) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.reason" :label="reasonLabel" :span="2">
            {{ requisition.reason }}
          </el-descriptions-item>
          <el-descriptions-item v-if="requisition.remark" label="备注" :span="2">
            {{ requisition.remark }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider>{{ detailDividerText }} ({{ lineCount }} 行)</el-divider>

        <el-table
          :data="requisition.requestedItems || []"
          stripe
          border
          empty-text="无明细"
          style="width: 100%"
        >
          <el-table-column type="index" label="#" width="60" align="center" />
          <el-table-column label="物料" min-width="180">
            <template #default="{ row }">
              {{ row.materialName || row.materialTypeId || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="数量" width="120" align="right">
            <template #default="{ row }">{{ row.quantity ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="单位" width="80" align="center">
            <template #default="{ row }">{{ row.unit || '-' }}</template>
          </el-table-column>
          <!-- 缺陷 7: 列头原来就叫「建议供应商 ID」—— 不是渲染疏忽, 是列的定义本身
               把内部主键当成了要展示的东西 ⇒ **列头和值一起改**。 -->
          <el-table-column label="建议供应商" width="200" show-overflow-tooltip>
            <template #default="{ row }">
              {{ supplierLabel(row.suggestedSupplierId) }}
            </template>
          </el-table-column>
          <el-table-column label="行备注" min-width="180">
            <template #default="{ row }">{{ row.remark || '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 驳回 dialog -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="驳回请购单"
      width="520px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="requisition">
        <div class="reject-context">
          单号: <strong>{{ requisition.requisitionNumber }}</strong> ·
          {{ requesterLabel }}: <strong>{{ personLabel(requisition.requesterId) }}</strong>
        </div>
      </template>
      <el-form label-width="100px" style="margin-top: 12px">
        <el-form-item label="驳回原因" required>
          <el-select
            v-model="rejectReasonCode"
            placeholder="请选择"
            style="width: 100%"
          >
            <el-option
              v-for="r in REJECT_REASONS"
              :key="r.code"
              :label="r.label"
              :value="r.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="rejectReasonCode === 'OTHER'" label="详细说明" required>
          <el-input
            v-model="rejectReasonOther"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请填写至少 5 字的具体原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="!canConfirmReject"
          :loading="submitting"
          @click="confirmReject"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>

    <!-- 转 PO dialog -->
    <el-dialog
      v-model="convertDialogVisible"
      title="转采购订单"
      width="480px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <template v-if="requisition">
        <div class="reject-context">
          单号: <strong>{{ requisition.requisitionNumber }}</strong> ·
          {{ lineCount }} 行明细
        </div>
      </template>
      <el-form label-width="120px" style="margin-top: 12px">
        <el-form-item label="PO 供应商" required>
          <el-select
            v-model="convertSupplierId"
            placeholder="请选择 (整张 PO 走同一供应商)"
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="s in suppliers"
              :key="s.id"
              :label="s.name"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="hint" style="margin-top: 8px">
        提示: 重复点击不会创建多张 PO (后端幂等). 如果已转过会直接返回已有 PO.
      </div>
      <template #footer>
        <el-button @click="convertDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!convertSupplierId"
          :loading="submitting"
          @click="confirmConvert"
        >
          创建 PO
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
}
.page-header-title {
  font-size: 15px;
  font-weight: 600;
}
.empty-state {
  display: flex;
  justify-content: center;
  align-items: center;
  flex: 1;
}
.content-card {
  flex: 1;
}
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  .req-number {
    font-size: 18px;
    font-weight: 600;
    color: #303133;
  }
}
.header-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.reject-context {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
}
.hint {
  font-size: 12px;
  color: #909399;
}
</style>
