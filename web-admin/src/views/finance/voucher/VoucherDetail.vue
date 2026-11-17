<script setup lang="ts">
/**
 * Sprint 7 T1 F-VOUCHER-2 Phase D — 凭证详情 借/贷 双栏视图.
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 1 预先显示边界: header 显示 借合计 / 贷合计 / 差额 + 平衡 ✓/✗ tag,
 *   过账 button :disabled 当 status != DRAFT 或 unbalanced
 * - Rule 2 上下文带身份: 标题 "凭证 {voucherNumber} — {type} — {description}"
 * - Rule 3 作废 reason dropdown: 5 标准原因 + "其他"
 * - Rule 5 dead-end nav: 状态显示 + 行动建议 ("已过账, 不可编辑, 如需修改请先反结账")
 *
 * Route: /finance/voucher/:id
 */
import { ref, computed, watch, onActivated, onDeactivated, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 ElMessageBox)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { getReturnRefundByRequest } from '@/api/inventory';
import {
  getVoucherDetail,
  getVoucherByBusiness,
  postVoucher,
  voidVoucher,
  sumDebit,
  sumCredit,
  isBalanced,
  VOUCHER_STATUS_LABEL,
  VOUCHER_TYPE_LABEL,
  AUXILIARY_TYPE_LABEL,
  SOURCE_BUSINESS_TYPE_LABEL as EXISTING_SOURCE_LABEL,
  type Voucher,
} from '@/api/voucher';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();

const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));
const voucherId = computed(() => String(route.params.id ?? ''));
const isVoucherPage = computed(() => route.name === 'FinanceVoucherDetail');
const pageActive = ref(true);

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const voucher = ref<Voucher | null>(null);
const errorMessage = ref('');

type VoucherRequestContext = {
  sequence: number;
  factoryId: string;
  voucherId: string;
  routeId: string;
  actorId: number | string | undefined;
};

let requestSequence = 0;

function captureContext(): VoucherRequestContext {
  const routeId = String(route.params.id ?? '');
  return {
    sequence: ++requestSequence,
    factoryId: factoryId.value,
    voucherId: voucherId.value,
    routeId,
    actorId: authStore.user?.id,
  };
}

function isCurrentContext(context: VoucherRequestContext): boolean {
  return pageActive.value && isVoucherPage.value
    && context.sequence === requestSequence
    && context.factoryId === factoryId.value
    && context.voucherId === voucherId.value
    && context.routeId === String(route.params.id ?? '')
    && context.actorId === authStore.user?.id;
}

function errorText(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  return fallback;
}

// ============================================================
// Computed: 借/贷 合计 + 平衡 status (Rule 1 预先边界)
// ============================================================
const totalDebit = computed(() => {
  return voucher.value ? sumDebit(voucher.value.entries) : 0;
});

const totalCredit = computed(() => {
  return voucher.value ? sumCredit(voucher.value.entries) : 0;
});

const difference = computed(() => totalDebit.value - totalCredit.value);

const balanced = computed(() => {
  return voucher.value ? isBalanced(voucher.value.entries) : false;
});

const isDraft = computed(() => voucher.value?.status === 'DRAFT');
const isPosted = computed(() => voucher.value?.status === 'POSTED');
const isVoid = computed(() => voucher.value?.status === 'VOID');
const isReversed = computed(() => voucher.value?.status === 'REVERSED');
const isReturnRefund = computed(() => voucher.value?.sourceBusinessType === 'RETURN_REFUND');
type VoucherWithReversalLinks = Voucher & {
  originalVoucherId?: string | null;
  reversalVoucherId?: string | null;
};
const voucherWithLinks = computed(() => voucher.value as VoucherWithReversalLinks | null);
const isVoucherReversal = computed(() => voucher.value?.sourceBusinessType === 'VOUCHER_REVERSAL');
const isDisposalVoucher = computed(() => ['DISPOSAL_RECORD','DISPOSAL_RECORD_REVISION'].includes(voucher.value?.sourceBusinessType || ''));
const isProtectedRefundVoucher = computed(() => isReturnRefund.value || isVoucherReversal.value || isDisposalVoucher.value);
const linkedVoucherId = computed(() => {
  const current = voucherWithLinks.value;
  if (!current) return null;
  return isVoucherReversal.value
    ? current.originalVoucherId || current.sourceBusinessId || null
    : current.reversalVoucherId || null;
});

// 过账 button 启用条件 (Rule 1 precondition):
//   有写权限 + 是 DRAFT + 平衡 ✓
const canPost = computed(() => canWrite.value && isDraft.value && balanced.value);

// 作废 button: 任何非 VOID 状态都可作废
const canVoid = computed(() => canWrite.value && !isVoid.value && !isReversed.value && !isProtectedRefundVoucher.value);

const refundLookupLoading = ref(false);
const refundLookupError = ref('');
const disposalOrigin = ref<Voucher | null>(null);
const disposalRed = ref<Voucher | null>(null);
const disposalLookupError = ref('');
const isDisposalRevision = computed(() => voucher.value?.sourceBusinessType === 'DISPOSAL_RECORD_REVISION');
const disposalRecordId = computed(() => {
  const current = voucher.value;
  if (current?.sourceBusinessType === 'DISPOSAL_RECORD') {
    return /^[1-9]\d*$/.test(current.sourceBusinessId || '') ? current.sourceBusinessId : null;
  }
  return isDisposalRevision.value
    ? /^(\d+)#r[1-9]\d*$/.exec(current?.sourceBusinessId || '')?.[1] || null : null;
});

function openDisposalLink(kind: 'original' | 'red' | 'record') {
  const context = captureContext();
  if (!isCurrentContext(context) || !isDisposalVoucher.value) return;
  const id = kind === 'original' ? disposalOrigin.value?.id : disposalRed.value?.id;
  if (kind === 'record' && disposalRecordId.value) {
    // 财务经理没有质检模块, 进 /quality/disposals 会被路由闸挡回去
    const disposalPage = permissionStore.canAccess('quality') ? '/quality/disposals' : '/finance/disposal-approvals';
    void router.push({ path: disposalPage, query: { disposalId: disposalRecordId.value } });
  } else if (id) void router.push(`/finance/voucher/${encodeURIComponent(id)}`);
}

async function loadDisposalLinks(context: VoucherRequestContext, current: Voucher) {
  if (current.sourceBusinessType !== 'DISPOSAL_RECORD_REVISION') return;
  try {
    const recordId = /^(\d+)#r[1-9]\d*$/.exec(current.sourceBusinessId || '')?.[1];
    if (!recordId) throw new Error('报废修订记录缺少有效的原始单号');
    const original = await getVoucherByBusiness(context.factoryId, 'DISPOSAL_RECORD', recordId);
    if (!isCurrentContext(context)) return;
    if (!original || original.factoryId !== context.factoryId || original.sourceBusinessType !== 'DISPOSAL_RECORD'
        || original.sourceBusinessId !== recordId || original.status !== 'REVERSED' || !original.reversalVoucherId) {
      throw new Error('未找到匹配的原报废凭证和红字冲销记录，请财务核对');
    }
    const red = await getVoucherDetail(context.factoryId, original.reversalVoucherId);
    if (!isCurrentContext(context)) return;
    if (red.factoryId !== context.factoryId || red.id !== original.reversalVoucherId
        || red.sourceBusinessType !== 'VOUCHER_REVERSAL' || red.sourceBusinessId !== original.id
        || red.originalVoucherId !== original.id || red.status !== 'POSTED') {
      throw new Error('红字凭证与原报废凭证不匹配，请财务核对');
    }
    disposalOrigin.value = original;
    disposalRed.value = red;
  } catch (error) {
    if (isCurrentContext(context)) disposalLookupError.value = errorText(error, '报废关联凭证读取失败，请重新读取');
  }
}

const statusBadge = computed(() => {
  if (!voucher.value) return null;
  return VOUCHER_STATUS_LABEL[voucher.value.status];
});

const voucherTypeLabel = computed(() => {
  if (!voucher.value) return '';
  if (isVoucherReversal.value) return sourceBusinessTypeLabel(voucher.value.sourceBusinessType);
  if (['DISPOSAL_RECORD', 'DISPOSAL_RECORD_REVISION'].includes(voucher.value.sourceBusinessType)) return '存货报废损失';
  return VOUCHER_TYPE_LABEL[voucher.value.voucherType] ?? voucher.value.voucherType;
});

const SOURCE_BUSINESS_TYPE_LABEL: Record<string, string> = {
  ...EXISTING_SOURCE_LABEL,
  RETURN_ORDER: '销售退货',
  RETURN_REFUND: '客户退款',
  VOUCHER_REVERSAL: '红字冲销凭证',
  DISPOSAL_RECORD: '报废审批',
  DISPOSAL_RECORD_REVISION: '报废成本修订',
  CASH_RECEIPT: '现金收款',
  CASH_PAYMENT: '现金付款',
};

function sourceBusinessTypeLabel(value: string | null | undefined): string {
  if (!value) return '未知来源';
  return SOURCE_BUSINESS_TYPE_LABEL[value] ?? value;
}

function openLinkedVoucher() {
  const linkedId = String(linkedVoucherId.value || '').trim();
  if (!linkedId) return;
  const context = captureContext();
  if (!isCurrentContext(context)) return;
  void router.push(`/finance/voucher/${encodeURIComponent(linkedId)}`);
}

async function openRefundContext() {
  if (!voucher.value || !isReturnRefund.value || refundLookupLoading.value) return;
  const context = captureContext();
  const currentVoucherId = voucher.value.id;
  const requestId = String(voucher.value.sourceBusinessId || '').trim();
  if (!requestId) {
    refundLookupError.value = '退款凭证缺少退款申请号，无法定位资金流水';
    return;
  }
  refundLookupLoading.value = true;
  refundLookupError.value = '';
  try {
    const refundView = await getReturnRefundByRequest(context.factoryId, requestId);
    if (!isCurrentContext(context) || voucher.value?.id !== currentVoucherId) return;
    const request = refundView.request;
    if (!refundView.returnOrderId || !request || request.id !== requestId || !request.ledger?.id) {
      throw new Error('退款申请缺少匹配的退款流水，无法定位详情');
    }
    await router.push({
      path: `/finance/return-refunds/${encodeURIComponent(refundView.returnOrderId)}`,
      query: { refundRequestId: request.id, ledgerId: request.ledger.id },
    });
  } catch (error) {
    if (isCurrentContext(context)) refundLookupError.value = errorText(error, '退款申请读取失败，请重新读取');
  } finally {
    if (isCurrentContext(context)) refundLookupLoading.value = false;
  }
}

// ============================================================
// Format helpers
// ============================================================
function fmtAmount(v: number | null | undefined): string {
  if (v === null || v === undefined) return '';
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function auxiliaryDisplay(entry: { auxiliaryType?: string | null; auxiliaryEntityId?: string | null }): string {
  if (!entry.auxiliaryType || !entry.auxiliaryEntityId) return '';
  const label = AUXILIARY_TYPE_LABEL[entry.auxiliaryType as keyof typeof AUXILIARY_TYPE_LABEL] ?? entry.auxiliaryType;
  // 仅展示 ID 后缀 (避免 UUID 过长), 完整 ID 通过 tooltip 显示
  const shortId = entry.auxiliaryEntityId.length > 12
    ? entry.auxiliaryEntityId.slice(0, 12) + '…'
    : entry.auxiliaryEntityId;
  return `${label} ${shortId}`;
}

// ============================================================
// Data load
// ============================================================
async function loadVoucher() {
  const context = captureContext();
  disposalOrigin.value = null;
  disposalRed.value = null;
  disposalLookupError.value = '';
  if (!pageActive.value || !isVoucherPage.value) {
    voucher.value = null;
    loading.value = false;
    errorMessage.value = '';
    refundLookupLoading.value = false;
    refundLookupError.value = '';
    return;
  }
  if (!context.factoryId || !context.voucherId) {
    voucher.value = null;
    errorMessage.value = '缺少工厂或凭证信息，无法加载凭证';
    return;
  }
  loading.value = true;
  voucher.value = null;
  errorMessage.value = '';
  refundLookupLoading.value = false;
  refundLookupError.value = '';
  try {
    const loadedVoucher = await getVoucherDetail(context.factoryId, context.voucherId);
    if (isCurrentContext(context)) {
      voucher.value = loadedVoucher;
      await loadDisposalLinks(context, loadedVoucher);
    }
  } catch (e) {
    if (isCurrentContext(context)) {
      voucher.value = null;
      errorMessage.value = errorText(e, '凭证加载失败，请刷新后重试');
    }
  } finally {
    if (isCurrentContext(context)) loading.value = false;
  }
}

// ============================================================
// Actions
// ============================================================
async function onPost() {
  if (!voucher.value) return;
  const context = captureContext();
  const currentVoucher = voucher.value;
  // Rule 2 context: 二次确认携带凭证号 + 金额
  const confirmed = await confirmRef.value!.ask({
    title: '过账确认',
    message: `凭证 ${voucher.value.voucherNumber} (¥${fmtAmount(totalDebit.value)}) 即将过账, 之后将不可编辑. 确认?`,
    confirmText: '确认过账',
  });
  if (confirmed === null) return;  // user cancelled
  if (!isCurrentContext(context) || !voucher.value || voucher.value.id !== currentVoucher.id) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    const postedVoucher = await postVoucher(context.factoryId, currentVoucher.id);
    if (isCurrentContext(context)) {
      voucher.value = postedVoucher;
      ElMessage.success(`凭证 ${postedVoucher.voucherNumber} 已过账`);
    }
  } catch (e) {
    if (isCurrentContext(context)) {
      voucher.value = null;
      errorMessage.value = errorText(e, '过账失败，请刷新后重试');
    }
  } finally {
    if (isCurrentContext(context)) loading.value = false;
  }
}

const STANDARD_VOID_REASONS = [
  { value: '凭证录入错误', label: '凭证录入错误' },
  { value: '业务取消', label: '业务取消' },
  { value: '科目错误', label: '科目错误' },
  { value: '金额错误', label: '金额错误' },
  { value: '重复生成', label: '重复生成' },
  { value: '其他', label: '其他 (需补充原因)' },
];

const voidDialogVisible = ref(false);
const voidReasonSelected = ref('');
const voidReasonOther = ref('');

function openVoidDialog() {
  if (!canVoid.value) return;
  voidReasonSelected.value = '';
  voidReasonOther.value = '';
  voidDialogVisible.value = true;
}

const voidReasonFinal = computed(() => {
  if (voidReasonSelected.value === '其他') {
    return voidReasonOther.value.trim();
  }
  return voidReasonSelected.value;
});

const voidSubmitDisabled = computed(() => {
  if (!voidReasonSelected.value) return true;
  if (voidReasonSelected.value === '其他' && !voidReasonOther.value.trim()) return true;
  return false;
});

async function onVoidSubmit() {
  if (!voucher.value || !canVoid.value || voidSubmitDisabled.value) return;
  const context = captureContext();
  const currentVoucher = voucher.value;
  if (!isCurrentContext(context)) return;
  loading.value = true;
  errorMessage.value = '';
  try {
    await voidVoucher(context.factoryId, currentVoucher.id, voidReasonFinal.value);
    if (isCurrentContext(context)) {
      ElMessage.success('凭证已作废');
      voidDialogVisible.value = false;
      await loadVoucher();
    }
  } catch (e) {
    if (isCurrentContext(context)) {
      voucher.value = null;
      errorMessage.value = errorText(e, '作废失败，请刷新后重试');
    }
  } finally {
    if (isCurrentContext(context)) loading.value = false;
  }
}

const VOID_REASON_ITEMS = STANDARD_VOID_REASONS.map(o => ({ label: o.label, value: o.value }));
function onVoidOpenChange(next: boolean) { voidDialogVisible.value = next; }

const entryColumns = computed<CsTableColumn<Voucher['entries'][number]>[]>(() => [
  { id: 'lineNo', header: '行号', meta: { class: { th: 'text-center', td: 'text-center' } } },
  { id: 'subjectCode', header: '科目编码' },
  { id: 'subjectName', header: '科目名称' },
  { id: 'auxiliary', header: '辅助核算' },
  { id: 'description', header: '摘要' },
  { id: 'debit', header: '借方金额', meta: { class: { th: 'text-right', td: 'text-right vd-col-debit' } } },
  { id: 'credit', header: '贷方金额', meta: { class: { th: 'text-right', td: 'text-right vd-col-credit' } } },
]);

function onBack() {
  router.back();
}

watch(
  () => [factoryId.value, voucherId.value, authStore.user?.id, isVoucherPage.value] as const,
  () => { void loadVoucher(); },
  { immediate: true },
);
onDeactivated(() => {
  pageActive.value = false;
  ++requestSequence;
  voucher.value = null;
  loading.value = false;
  refundLookupLoading.value = false;
  disposalOrigin.value = null;
  disposalRed.value = null;
  disposalLookupError.value = '';
});
onActivated(() => {
  if (pageActive.value) return;
  pageActive.value = true;
  void loadVoucher();
});
onBeforeUnmount(() => { pageActive.value = false; ++requestSequence; });
</script>

<template>
  <div class="voucher-detail" data-testid="voucher-detail">
    <p v-if="loading" class="vd-loading">加载中…</p>
    <UAlert
      v-if="errorMessage"
      class="vd-error"
      color="error" variant="soft" icon="i-lucide-circle-alert"
      :title="errorMessage"
    />
    <!-- Header: 上下文 (Rule 2) + 平衡 status (Rule 1) -->
    <div class="vd-header">
      <div><UButton color="neutral" variant="outline" icon="i-lucide-arrow-left" @click="onBack">返回</UButton></div>

      <template v-if="voucher">
        <h2 class="vd-title">
          凭证 {{ voucher.voucherNumber }}
          <UBadge v-if="statusBadge" :color="badgeColor(statusBadge.type as ElementTagType)" class="vd-status-tag">
            {{ statusBadge.text }}
          </UBadge>
        </h2>
        <div class="vd-meta">
          <span class="vd-meta-item">
            <strong>类型:</strong> {{ voucherTypeLabel }}
          </span>
          <span class="vd-meta-item">
            <strong>日期:</strong> {{ voucher.voucherDate }}
          </span>
          <span class="vd-meta-item" v-if="voucher.description">
            <strong>摘要:</strong> {{ voucher.description }}
          </span>
          <span class="vd-meta-item">
            <strong>来源:</strong> {{ sourceBusinessTypeLabel(voucher.sourceBusinessType) }} / {{ voucher.sourceBusinessId }}
          </span>
          <span v-if="isReturnRefund" class="vd-meta-item vd-refund-source">
            <UButton
              variant="link"
              :loading="refundLookupLoading"
              :disabled="refundLookupLoading"
              @click="openRefundContext"
            >查看退款与资金流水</UButton>
          </span>
          <span v-if="linkedVoucherId" class="vd-meta-item vd-refund-source">
            <UButton variant="link" @click="openLinkedVoucher">
              {{ isVoucherReversal ? '查看原始凭证' : '查看红冲凭证' }}
            </UButton>
          </span>
          <span v-if="disposalOrigin" class="vd-meta-item">
            <UButton variant="link" @click="openDisposalLink('original')">原凭证 {{ disposalOrigin.voucherNumber }}（已冲销）</UButton>
          </span>
          <span v-if="disposalRed" class="vd-meta-item">
            <UButton variant="link" @click="openDisposalLink('red')">红字凭证 {{ disposalRed.voucherNumber }}</UButton>
          </span>
          <span v-if="disposalRecordId" class="vd-meta-item">
            <UButton variant="link" @click="openDisposalLink('record')">查看报废审批</UButton>
          </span>
        </div>
        <p v-if="isDisposalRevision" class="vd-meta-item">原凭证与红字凭证相抵，本次修订分录重新按核定成本记账。</p>
        <div v-if="disposalLookupError" class="vd-lookup-error">
          <UAlert color="error" variant="soft" icon="i-lucide-circle-alert" :title="disposalLookupError" />
          <UButton variant="link" @click="loadVoucher">重新读取关联凭证</UButton>
        </div>
        <UAlert
          v-if="refundLookupError"
          class="vd-refund-error"
          color="error" variant="soft" icon="i-lucide-circle-alert"
          :title="refundLookupError"
        />
      </template>
    </div>

    <!-- Balance summary card (Rule 1: 预先边界 — 用户写新分录前已看到平衡是否 OK) -->
    <section v-if="voucher" class="vd-card vd-balance-card">
      <div class="vd-balance-grid">
        <div class="vd-balance-cell">
          <div class="vd-balance-label">借方合计</div>
          <div class="vd-balance-value vd-debit">¥{{ fmtAmount(totalDebit) }}</div>
        </div>
        <div class="vd-balance-cell">
          <div class="vd-balance-label">贷方合计</div>
          <div class="vd-balance-value vd-credit">¥{{ fmtAmount(totalCredit) }}</div>
        </div>
        <div class="vd-balance-cell">
          <div class="vd-balance-label">差额</div>
          <div
            class="vd-balance-value"
            :class="{ 'vd-unbalanced': !balanced, 'vd-balanced': balanced }"
          >
            {{ balanced ? '¥0.00' : `¥${fmtAmount(Math.abs(difference))}` }}
          </div>
        </div>
        <div class="vd-balance-cell vd-balance-status">
          <UBadge v-if="balanced" color="success" size="lg" icon="i-lucide-check">借贷平衡</UBadge>
          <UBadge v-else color="error" size="lg" icon="i-lucide-x">借贷不平衡</UBadge>
        </div>
      </div>
    </section>

    <!-- 借/贷 双栏 table -->
    <section v-if="voucher" class="vd-card vd-entries-card">
      <header class="vd-entries-header">
        <span>凭证分录 ({{ voucher.entries.length }} 条)</span>
      </header>

      <UTable :data="voucher.entries" :columns="entryColumns" class="cs-table" data-testid="voucher-entries-table">
        <template #empty>暂无分录</template>
        <template #lineNo-cell="{ row }">{{ row.original.lineNo }}</template>
        <template #subjectCode-cell="{ row }">{{ row.original.subjectCode }}</template>
        <template #subjectName-cell="{ row }">{{ row.original.subjectName }}</template>
        <template #auxiliary-cell="{ row }">
          <!-- 完整 ID 放原生 title (不用 UTooltip) -->
          <span v-if="row.original.auxiliaryType && row.original.auxiliaryEntityId" class="vd-aux" :title="row.original.auxiliaryEntityId">{{ auxiliaryDisplay(row.original) }}</span>
          <span v-else class="vd-muted">—</span>
        </template>
        <template #description-cell="{ row }"><span :title="row.original.description || ''">{{ row.original.description || '' }}</span></template>
        <!-- 借方栏 (left, 蓝色) -->
        <template #debit-cell="{ row }">
          <span v-if="row.original.debit && row.original.debit > 0" class="vd-amount-debit">¥{{ fmtAmount(row.original.debit) }}</span>
          <span v-else class="vd-muted">—</span>
        </template>
        <!-- 贷方栏 (right, 橙色) -->
        <template #credit-cell="{ row }">
          <span v-if="row.original.credit && row.original.credit > 0" class="vd-amount-credit">¥{{ fmtAmount(row.original.credit) }}</span>
          <span v-else class="vd-muted">—</span>
        </template>
      </UTable>
    </section>

    <!-- Actions: 过账 / 作废 (Rule 1 precondition + Rule 5 dead-end nav) -->
    <div class="vd-actions" v-if="voucher">
      <div class="vd-actions-hint">
        <span v-if="isReturnRefund" class="vd-hint-warning">
          客户退款凭证需由退货退款流程核对；不能在此作废或重复过账，请先核对资金流水。
        </span>
        <span v-else-if="isVoucherReversal" class="vd-hint-warning">
          红字冲销凭证由原业务凭证流程维护；不能在此作废或重复过账，请先核对原始凭证。
        </span>
        <span v-else-if="isDisposalVoucher" class="vd-hint-warning">报废凭证不能单独作废或冲销；请通过成本更正流程同步处理报废损失和账务。</span>
        <span v-else-if="isReversed" class="vd-hint-warning">凭证已冲销，不可再次过账或作废。</span>
        <span v-else-if="isPosted" class="vd-hint-info">
          凭证已过账, 不可编辑. 如需修改请联系财务管理员先反结账.
        </span>
        <span v-else-if="isVoid" class="vd-hint-danger">凭证已作废, 不可恢复.</span>
        <span v-else-if="!balanced" class="vd-hint-warning">
          凭证借贷不平衡, 差额 ¥{{ fmtAmount(Math.abs(difference)) }}, 不可过账.
        </span>
        <span v-else-if="!canWrite" class="vd-hint-warning">您没有财务写入权限, 无法操作.</span>
        <span v-else class="vd-hint-success">凭证已平衡, 可过账.</span>
      </div>
      <div class="vd-actions-buttons">
        <UButton v-if="isDraft" :disabled="!canPost" data-testid="voucher-post" @click="onPost">过账</UButton>
        <UButton
          v-if="!isProtectedRefundVoucher"
          color="error"
          variant="outline"
          :disabled="!canVoid"
          data-testid="voucher-void"
          @click="openVoidDialog"
        >作废</UButton>
      </div>
    </div>

    <!-- 作废 dialog (Rule 3 标准原因 dropdown + 自由 textarea fallback) -->
    <UModal
      :open="voidDialogVisible"
      title="作废凭证 — 选择原因"
      description="选择作废原因后确认作废"
      :dismissible="false"
      :ui="{ content: 'max-w-[500px]', description: 'sr-only', footer: 'justify-end' }"
      @update:open="onVoidOpenChange"
    >
      <template #body>
        <div class="vd-void-context" v-if="voucher">
          <p>
            <strong>凭证号:</strong> {{ voucher.voucherNumber }} ({{ voucherTypeLabel }})
          </p>
          <p>
            <strong>金额:</strong> ¥{{ fmtAmount(totalDebit) }}
          </p>
          <p>
            <strong>状态:</strong>
            <UBadge v-if="statusBadge" :color="badgeColor(statusBadge.type as ElementTagType)" size="sm">
              {{ statusBadge.text }}
            </UBadge>
          </p>
        </div>
        <div class="vd-void-form">
          <UFormField label="作废原因" required>
            <USelect v-model="voidReasonSelected" :items="VOID_REASON_ITEMS" placeholder="请选择标准原因" class="w-full" data-testid="voucher-void-reason" />
          </UFormField>
          <UFormField v-if="voidReasonSelected === '其他'" label="详细原因 (必填)" required>
            <UTextarea
              v-model="voidReasonOther"
              :rows="3"
              placeholder="请说明作废原因 (供审计追溯)"
              :maxlength="500"
              class="w-full"
              data-testid="voucher-void-other"
            />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="voidDialogVisible = false">取消</UButton>
        <UButton color="error" :disabled="voidSubmitDisabled" data-testid="voucher-void-confirm" @click="onVoidSubmit">确认作废</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.voucher-detail { padding: var(--cs-space-4); }
.vd-loading { color: var(--cs-muted); font-size: var(--cs-font-small); margin: 0 0 8px; }
.vd-error { margin-bottom: 16px; }
.vd-lookup-error { display: flex; align-items: center; gap: var(--cs-space-2); }
.vd-header { margin-bottom: 16px; display: flex; flex-direction: column; gap: 12px; }
.vd-title { font-size: 20px; font-weight: 600; margin: 8px 0; display: flex; align-items: center; gap: 12px; color: var(--cs-text); }
.vd-status-tag { font-size: 13px; }
.vd-meta { display: flex; flex-wrap: wrap; gap: 24px; color: var(--cs-muted); font-size: 13px; }
.vd-meta-item strong { color: var(--cs-text); margin-right: 4px; }
.vd-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); margin-bottom: 16px; }
.vd-balance-card { padding: 16px; }
.vd-balance-grid { display: grid; grid-template-columns: 1fr 1fr 1fr 1.4fr; gap: 24px; align-items: center; }
.vd-balance-cell { display: flex; flex-direction: column; gap: 4px; }
.vd-balance-label { font-size: 13px; color: var(--cs-muted); }
.vd-balance-value { font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
.vd-debit { color: #1d8cf8; }
.vd-credit { color: #fd7e14; }
.vd-balanced { color: var(--cs-success); }
.vd-unbalanced { color: var(--cs-danger); }
.vd-balance-status { justify-content: center; align-items: center; }
.vd-entries-header { font-weight: 600; font-size: 15px; padding: 12px 16px; border-bottom: 1px solid var(--cs-border); color: var(--cs-text); }
:deep(.vd-col-debit) { background-color: rgba(29, 140, 248, 0.05); }
:deep(.vd-col-credit) { background-color: rgba(253, 126, 20, 0.05); }
.vd-amount-debit { font-variant-numeric: tabular-nums; color: #1d8cf8; font-weight: 600; }
.vd-amount-credit { font-variant-numeric: tabular-nums; color: #fd7e14; font-weight: 600; }
.vd-muted { color: var(--cs-muted); }
.vd-aux { font-size: 12px; color: var(--cs-muted); padding: 2px 6px; background: var(--cs-surface-subtle); border-radius: 3px; }
.vd-actions { display: flex; justify-content: space-between; align-items: center; padding: 16px; background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); }
.vd-actions-hint { flex: 1; font-size: 13px; }
.vd-hint-warning { color: var(--cs-warning); }
.vd-hint-info { color: var(--cs-muted); }
.vd-hint-danger { color: var(--cs-danger); }
.vd-hint-success { color: var(--cs-success); }
.vd-actions-buttons { display: flex; gap: 12px; }
.vd-void-context { background: var(--cs-surface-subtle); padding: 12px 16px; border-radius: 4px; margin-bottom: 16px; }
.vd-void-context p { margin: 4px 0; font-size: 13px; }
.vd-void-form { display: grid; gap: var(--cs-space-3); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
