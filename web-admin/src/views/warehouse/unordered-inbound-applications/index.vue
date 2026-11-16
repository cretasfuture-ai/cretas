<script setup lang="ts">
/**
 * 组件全部是 Nuxt UI (UTable / UTabs / UModal / UFormField / UInput / USelectMenu / UTextarea / UBadge) + --cs-*；
 * 弹窗整体 UModal（含撤回确认，取代 ElMessageBox），里面不放 Element 控件。提示仍用 ElMessage（命令式服务）。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import { get } from '@/api/request';
import {
  approveCustomerMaterialArrival,
  cancelCustomerMaterialArrival,
  createCustomerMaterialArrival,
  listCustomerMaterialArrivals,
  rejectCustomerMaterialArrival,
  type CustomerMaterialArrivalNotice,
  type CustomerMaterialArrivalStatus,
  type UnorderedInboundReason,
} from '@/api/customerMaterialArrival';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { TableRow } from '@/types/api';

type ApplicationTab = 'ALL' | 'PENDING_APPROVAL' | 'OPEN' | 'REJECTED' | 'CANCELLED';

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canCreate = computed(() => permissionStore.canWrite('operations'));
const canApprove = computed(() => permissionStore.canWrite('warehouse'));

const loading = ref(false);
const submitting = ref(false);
const reviewing = ref(false);
const withdrawing = ref(false);
const createVisible = ref(false);
const reviewVisible = ref(false);
const withdrawVisible = ref(false);
const reviewAction = ref<'approve' | 'reject'>('approve');
const selected = ref<CustomerMaterialArrivalNotice | null>(null);
const activeTab = ref<ApplicationTab>('ALL');
const keyword = ref('');
const rows = ref<CustomerMaterialArrivalNotice[]>([]);
const customers = ref<TableRow[]>([]);
const reviewRemark = ref('');
const reviewReason = ref('');
const rejectReasonOptions = [
  { value: 'MISSING_INFORMATION', label: '资料不完整' },
  { value: 'UNVERIFIED_SOURCE', label: '来源无法核实' },
  { value: 'UNCLEAR_OWNERSHIP', label: '库存归属不清' },
  { value: 'ARRIVAL_INFORMATION_ERROR', label: '预计到达信息有误' },
  { value: 'OTHER', label: '其他' },
];

const form = reactive({
  reason: 'CUSTOMER_MATERIAL' as UnorderedInboundReason,
  customerId: '',
  expectedArrivalAt: '',
  contactName: '',
  contactPhone: '',
  remark: '',
});
// datetime-local 给 "YYYY-MM-DDTHH:mm"，后端要 "YYYY-MM-DDTHH:mm:ss"
const expectedLocal = computed({
  get: () => form.expectedArrivalAt.slice(0, 16),
  set: (v: string) => { form.expectedArrivalAt = v ? (v.length === 16 ? `${v}:00` : v) : ''; },
});

const reasonText: Record<UnorderedInboundReason, string> = {
  CUSTOMER_MATERIAL: '客户来料',
  GIFT: '赠予',
  OTHER: '其他',
};
const statusMeta: Record<CustomerMaterialArrivalStatus, { text: string; type: ElementTagType }> = {
  PENDING_APPROVAL: { text: '待审批', type: 'warning' },
  OPEN: { text: '审批通过', type: 'success' },
  PARTIALLY_RECEIVED: { text: '审批通过', type: 'success' },
  RECEIVED: { text: '审批通过', type: 'success' },
  REJECTED: { text: '审批驳回', type: 'danger' },
  CANCELLED: { text: '已撤回', type: 'info' },
};

const R_ACTION = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<CustomerMaterialArrivalNotice>[] = [
  { accessorKey: 'noticeNumber', header: '申请单号' },
  { id: 'reason', header: '入库原因' },
  { id: 'owner', header: '库存归属' },
  { id: 'expectedArrivalAt', header: '预计到达' },
  { id: 'status', header: '审批状态' },
  { id: 'handoff', header: '任务交接' },
  { id: 'remark', header: '申请说明' },
  { id: 'reviewRemark', header: '审批意见' },
  { id: 'actions', header: '操作', meta: R_ACTION },
];

const customerRequired = computed(() => form.reason === 'CUSTOMER_MATERIAL');
const ownershipPreview = computed(() => customerRequired.value
  ? '客户所有：只能用于所选客户'
  : '公司所有：进入本厂普通库存');
const customerItems = computed(() => customers.value.map((c) => ({ label: String(c.name ?? ''), value: String(c.id) })));
const counts = computed(() => ({
  ALL: rows.value.length,
  PENDING_APPROVAL: rows.value.filter((row) => row.status === 'PENDING_APPROVAL').length,
  OPEN: rows.value.filter((row) => ['OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED'].includes(row.status)).length,
  REJECTED: rows.value.filter((row) => row.status === 'REJECTED').length,
  CANCELLED: rows.value.filter((row) => row.status === 'CANCELLED').length,
}));
const tabItems = computed<TabsItem[]>(() => [
  { label: `全部 ${counts.value.ALL}`, value: 'ALL' },
  { label: `待审批 ${counts.value.PENDING_APPROVAL}`, value: 'PENDING_APPROVAL' },
  { label: `审批通过 ${counts.value.OPEN}`, value: 'OPEN' },
  { label: `审批驳回 ${counts.value.REJECTED}`, value: 'REJECTED' },
  { label: `已撤回 ${counts.value.CANCELLED}`, value: 'CANCELLED' },
]);
const filteredRows = computed(() => {
  const normalized = keyword.value.trim().toLocaleLowerCase('zh-CN');
  return rows.value.filter((row) => {
    const tabMatch = activeTab.value === 'ALL'
      || (activeTab.value === 'OPEN'
        ? ['OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED'].includes(row.status)
        : row.status === activeTab.value);
    const keywordMatch = !normalized || [
      row.noticeNumber, row.customerName, row.contactName, row.remark, row.reviewRemark,
    ].some((value) => String(value || '').toLocaleLowerCase('zh-CN').includes(normalized));
    return tabMatch && keywordMatch;
  });
});

async function load() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const response = await listCustomerMaterialArrivals(factoryId.value, false);
    rows.value = response.success && Array.isArray(response.data) ? response.data : [];
    window.dispatchEvent(new CustomEvent('cretas:task-badges-refresh'));
  } finally {
    loading.value = false;
  }
}

async function loadCustomers() {
  if (!factoryId.value || !canCreate.value) return;
  const response = await get(`/${factoryId.value}/customers`, {
    params: { page: 1, size: 200 },
    _silent: true,
  } as never);
  customers.value = Array.isArray(response.data?.content) ? response.data.content : [];
}

function openCreate() {
  Object.assign(form, {
    reason: 'CUSTOMER_MATERIAL', customerId: '', expectedArrivalAt: '',
    contactName: '', contactPhone: '', remark: '',
  });
  createVisible.value = true;
}

function setReason(reason: UnorderedInboundReason) {
  form.reason = reason;
  onReasonChange();
}

function onReasonChange() {
  if (!customerRequired.value) form.customerId = '';
}

async function submit() {
  if (customerRequired.value && !form.customerId) {
    ElMessage.warning('客户来料必须选择归属客户');
    return;
  }
  submitting.value = true;
  try {
    const response = await createCustomerMaterialArrival(factoryId.value, {
      reason: form.reason,
      customerId: form.customerId || undefined,
      expectedArrivalAt: form.expectedArrivalAt || undefined,
      contactName: form.contactName.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined,
    });
    if (!response.success) throw new Error(response.message || '创建失败');
    ElMessage.success('申请已提交，审批通过前不会进入入库任务');
    createVisible.value = false;
    activeTab.value = 'PENDING_APPROVAL';
    await load();
  } finally {
    submitting.value = false;
  }
}

function openReview(row: CustomerMaterialArrivalNotice, action: 'approve' | 'reject') {
  selected.value = row;
  reviewAction.value = action;
  reviewRemark.value = '';
  reviewReason.value = '';
  reviewVisible.value = true;
}

async function submitReview() {
  if (!selected.value) return;
  if (reviewAction.value === 'reject' && !reviewReason.value) {
    ElMessage.warning('请选择驳回原因');
    return;
  }
  if (reviewAction.value === 'reject' && reviewReason.value === 'OTHER' && !reviewRemark.value.trim()) {
    ElMessage.warning('选择“其他”时请填写具体原因');
    return;
  }
  const selectedReason = rejectReasonOptions.find((option) => option.value === reviewReason.value)?.label;
  const effectiveRemark = reviewAction.value === 'reject'
    ? [selectedReason, reviewRemark.value.trim()].filter(Boolean).join('：')
    : reviewRemark.value;
  reviewing.value = true;
  try {
    const response = reviewAction.value === 'approve'
      ? await approveCustomerMaterialArrival(factoryId.value, selected.value.id, effectiveRemark)
      : await rejectCustomerMaterialArrival(factoryId.value, selected.value.id, effectiveRemark);
    if (!response.success) throw new Error(response.message || '审批失败');
    ElMessage.success(reviewAction.value === 'approve'
      ? '审批通过，已交接到入库任务与批次'
      : '申请已驳回');
    reviewVisible.value = false;
    await load();
  } finally {
    reviewing.value = false;
  }
}

function withdraw(row: CustomerMaterialArrivalNotice) {
  selected.value = row;
  withdrawVisible.value = true;
}

async function confirmWithdraw() {
  if (!selected.value) return;
  withdrawing.value = true;
  try {
    const response = await cancelCustomerMaterialArrival(factoryId.value, selected.value.id);
    if (!response.success) throw new Error(response.message || '撤回失败');
    ElMessage.success('申请已撤回');
    withdrawVisible.value = false;
    await load();
  } finally {
    withdrawing.value = false;
  }
}

function goToTask(row: CustomerMaterialArrivalNotice) {
  void router.push({
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
      arrivalNoticeId: row.id,
    },
  });
}

function ownerText(row: CustomerMaterialArrivalNotice): string {
  return row.reason === 'CUSTOMER_MATERIAL'
    ? `客户所有 · ${row.customerName || '客户待核对'}`
    : '公司所有';
}

function handoffText(row: CustomerMaterialArrivalNotice): string {
  if (row.status === 'OPEN') return '已生成待入库任务';
  if (row.status === 'PARTIALLY_RECEIVED') return '入库任务处理中';
  if (row.status === 'RECEIVED') return '入库任务已完成';
  return '审批通过后生成';
}

onMounted(() => { void Promise.allSettled([load(), loadCustomers()]); });
</script>

<template>
  <div class="application-page" data-testid="unordered-inbound-applications">
    <header class="cs-page-head">
      <div>
        <p class="eyebrow">仓储管理 / 申请审批</p>
        <h2>无订单入库申请</h2>
        <p>这里只处理申请和审批。审批通过后，系统才把来源交接到“入库任务与批次”。</p>
      </div>
      <UButton v-if="canCreate" icon="i-lucide-plus" data-testid="unordered-inbound-create" @click="openCreate">发起申请</UButton>
    </header>

    <UAlert
      class="mb-3"
      color="info"
      variant="subtle"
      icon="i-lucide-info"
      title="职责边界：本页不处理实物、数量、仓库或库存批次；审批通过仅生成可追溯的仓储任务。"
    />

    <section class="cs-card">
      <UTabs v-model="activeTab" :items="tabItems" :content="false" variant="link" class="px-4" />

      <div class="toolbar">
        <UInput v-model="keyword" icon="i-lucide-search" placeholder="搜索申请单号 / 客户 / 说明" class="w-80" data-testid="unordered-inbound-keyword">
          <template v-if="keyword" #trailing>
            <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click="keyword = ''" />
          </template>
        </UInput>
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="load">刷新</UButton>
      </div>

      <UTable :data="filteredRows" :columns="columns" :loading="loading" class="cs-table" data-testid="unordered-inbound-table">
        <template #empty>当前分类暂无申请</template>
        <template #reason-cell="{ row }">{{ reasonText[row.original.reason as UnorderedInboundReason] || row.original.reason }}</template>
        <template #owner-cell="{ row }">{{ ownerText(row.original) }}</template>
        <template #expectedArrivalAt-cell="{ row }">{{ row.original.expectedArrivalAt || '' }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusMeta[row.original.status as CustomerMaterialArrivalStatus].type)" variant="soft">
            {{ statusMeta[row.original.status as CustomerMaterialArrivalStatus].text }}
          </UBadge>
        </template>
        <template #handoff-cell="{ row }">{{ handoffText(row.original) }}</template>
        <template #remark-cell="{ row }"><span class="clip" :title="row.original.remark || ''">{{ row.original.remark || '' }}</span></template>
        <template #reviewRemark-cell="{ row }"><span class="clip" :title="row.original.reviewRemark || ''">{{ row.original.reviewRemark || '' }}</span></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <template v-if="row.original.status === 'PENDING_APPROVAL'">
              <UButton v-if="canApprove" size="xs" variant="link" color="success" @click="openReview(row.original, 'approve')">通过</UButton>
              <UButton v-if="canApprove" size="xs" variant="link" color="error" @click="openReview(row.original, 'reject')">驳回</UButton>
              <UButton v-if="canCreate" size="xs" variant="link" color="neutral" @click="withdraw(row.original)">撤回</UButton>
            </template>
            <UButton v-else-if="['OPEN', 'PARTIALLY_RECEIVED'].includes(row.original.status)" size="xs" variant="link" @click="goToTask(row.original)">
              查看关联任务
            </UButton>
            <span v-else>—</span>
          </div>
        </template>
      </UTable>
    </section>

    <UModal v-model:open="createVisible" title="发起无订单入库申请" description="提交后进入待审批" :dismissible="false" :ui="{ content: 'max-w-[560px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <UAlert color="info" variant="subtle" icon="i-lucide-info" title="提交后进入待审批，不会直接生成入库任务或增加库存。" class="mb-3" />
        <div class="form">
          <UFormField label="入库原因" required>
            <div class="seg">
              <UButton value="CUSTOMER_MATERIAL" size="sm" :color="form.reason === 'CUSTOMER_MATERIAL' ? 'primary' : 'neutral'" :variant="form.reason === 'CUSTOMER_MATERIAL' ? 'solid' : 'outline'" @click="setReason('CUSTOMER_MATERIAL')">客户来料</UButton>
              <UButton value="GIFT" size="sm" :color="form.reason === 'GIFT' ? 'primary' : 'neutral'" :variant="form.reason === 'GIFT' ? 'solid' : 'outline'" @click="setReason('GIFT')">赠予</UButton>
              <UButton value="OTHER" size="sm" :color="form.reason === 'OTHER' ? 'primary' : 'neutral'" :variant="form.reason === 'OTHER' ? 'solid' : 'outline'" @click="setReason('OTHER')">其他</UButton>
            </div>
          </UFormField>
          <UFormField :label="customerRequired ? '归属客户' : '关联客户'" :required="customerRequired">
            <USelectMenu v-model="form.customerId" value-key="value" :items="customerItems" :placeholder="customerRequired ? '必须选择库存归属客户' : '可选'" class="w-full" data-testid="unordered-inbound-customer">
              <template v-if="form.customerId" #trailing>
                <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清空" @click.stop="form.customerId = ''" />
              </template>
            </USelectMenu>
          </UFormField>
          <UFormField label="库存归属"><UBadge color="neutral" variant="soft">{{ ownershipPreview }}</UBadge></UFormField>
          <UFormField label="预计到达"><UInput v-model="expectedLocal" type="datetime-local" class="w-full" /></UFormField>
          <UFormField label="联系人"><UInput v-model="form.contactName" :maxlength="100" class="w-full" /></UFormField>
          <UFormField label="联系电话"><UInput v-model="form.contactPhone" :maxlength="50" class="w-full" /></UFormField>
          <UFormField label="申请说明"><UTextarea v-model="form.remark" :rows="3" :maxlength="1000" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createVisible = false">取消</UButton>
        <UButton :loading="submitting" @click="submit">提交审批</UButton>
      </template>
    </UModal>

    <UModal v-model:open="reviewVisible" :title="reviewAction === 'approve' ? '审批通过' : '审批驳回'" description="填写审批意见" :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="form">
          <p class="review-target">申请单：{{ selected?.noticeNumber }}</p>
          <USelectMenu v-if="reviewAction === 'reject'" v-model="reviewReason" value-key="value" :items="rejectReasonOptions" placeholder="选择驳回原因" class="w-full" />
          <UTextarea
            v-if="reviewAction === 'approve' || reviewReason === 'OTHER'"
            v-model="reviewRemark"
            :rows="4"
            :maxlength="1000"
            class="w-full"
            :placeholder="reviewAction === 'approve' ? '可填写审批意见' : '请填写具体驳回原因'"
          />
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="reviewVisible = false">取消</UButton>
        <UButton :color="reviewAction === 'approve' ? 'success' : 'error'" :loading="reviewing" @click="submitReview">
          {{ reviewAction === 'approve' ? '确认通过并交接任务' : '确认驳回' }}
        </UButton>
      </template>
    </UModal>

    <UModal v-model:open="withdrawVisible" title="撤回无订单入库申请" description="确认撤回申请" :ui="{ content: 'max-w-[440px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <p class="review-target">确认撤回申请 {{ selected?.noticeNumber }}？</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="withdrawVisible = false">返回</UButton>
        <UButton color="warning" :loading="withdrawing" data-testid="unordered-inbound-withdraw-confirm" @click="confirmWithdraw">确认撤回</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.application-page { padding: var(--cs-space-4); }
.eyebrow { color: var(--cs-primary) !important; font-size: var(--cs-font-small) !important; margin: 0 0 2px !important; }
.toolbar { display: flex; justify-content: flex-end; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-3) var(--cs-space-5); }
.row-actions { display: flex; justify-content: flex-end; gap: var(--cs-space-1); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.seg { display: flex; gap: var(--cs-space-2); }
.review-target { margin: 0; color: var(--cs-muted); }
.clip { display: inline-block; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
</style>
