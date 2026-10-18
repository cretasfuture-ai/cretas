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
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Check, Close } from '@element-plus/icons-vue';
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
    void router.push({ path: '/quality/disposals', query: { disposalId: disposalRecordId.value } });
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
  try {
    await ElMessageBox.confirm(
      `凭证 ${voucher.value.voucherNumber} (¥${fmtAmount(totalDebit.value)}) 即将过账, 之后将不可编辑. 确认?`,
      '过账确认',
      { confirmButtonText: '确认过账', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;  // user cancelled
  }
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
  <div class="voucher-detail" v-loading="loading">
    <el-alert
      v-if="errorMessage"
      class="vd-error"
      type="error"
      :title="errorMessage"
      show-icon
      :closable="false"
    />
    <!-- Header: 上下文 (Rule 2) + 平衡 status (Rule 1) -->
    <div class="vd-header">
      <el-button :icon="ArrowLeft" plain @click="onBack">返回</el-button>

      <template v-if="voucher">
        <h2 class="vd-title">
          凭证 {{ voucher.voucherNumber }}
          <el-tag v-if="statusBadge" :type="statusBadge.type as any" effect="dark" class="vd-status-tag">
            {{ statusBadge.text }}
          </el-tag>
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
            <el-button
              type="primary"
              link
              :loading="refundLookupLoading"
              :disabled="refundLookupLoading"
              @click="openRefundContext"
            >查看退款与资金流水</el-button>
          </span>
          <span v-if="linkedVoucherId" class="vd-meta-item vd-refund-source">
            <el-button type="primary" link @click="openLinkedVoucher">
              {{ isVoucherReversal ? '查看原始凭证' : '查看红冲凭证' }}
            </el-button>
          </span>
          <span v-if="disposalOrigin" class="vd-meta-item">
            <el-button type="primary" link @click="openDisposalLink('original')">原凭证 {{ disposalOrigin.voucherNumber }}（已冲销）</el-button>
          </span>
          <span v-if="disposalRed" class="vd-meta-item">
            <el-button type="primary" link @click="openDisposalLink('red')">红字凭证 {{ disposalRed.voucherNumber }}</el-button>
          </span>
          <span v-if="disposalRecordId" class="vd-meta-item">
            <el-button type="primary" link @click="openDisposalLink('record')">查看报废审批</el-button>
          </span>
        </div>
        <p v-if="isDisposalRevision" class="vd-meta-item">原凭证与红字凭证相抵，本次修订分录重新按核定成本记账。</p>
        <el-alert v-if="disposalLookupError" type="error" :title="disposalLookupError" :closable="false" show-icon>
          <el-button link type="primary" @click="loadVoucher">重新读取关联凭证</el-button>
        </el-alert>
        <el-alert
          v-if="refundLookupError"
          class="vd-refund-error"
          type="error"
          :title="refundLookupError"
          :closable="false"
          show-icon
        />
      </template>
    </div>

    <!-- Balance summary card (Rule 1: 预先边界 — 用户写新分录前已看到平衡是否 OK) -->
    <el-card v-if="voucher" class="vd-balance-card" shadow="hover">
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
          <el-tag
            v-if="balanced"
            type="success"
            effect="dark"
            size="large"
            :icon="Check"
          >
 借贷平衡
          </el-tag>
          <el-tag
            v-else
            type="danger"
            effect="dark"
            size="large"
            :icon="Close"
          >
 借贷不平衡
          </el-tag>
        </div>
      </div>
    </el-card>

    <!-- 借/贷 双栏 table -->
    <el-card v-if="voucher" class="vd-entries-card" shadow="hover">
      <template #header>
        <div class="vd-entries-header">
          <span>凭证分录 ({{ voucher.entries.length }} 条)</span>
        </div>
      </template>

      <el-table
        :data="voucher.entries"
        border
        stripe
        size="default"
        :empty-text="'暂无分录'"
      >
        <el-table-column prop="lineNo" label="行号" width="64" align="center" />
        <el-table-column prop="subjectCode" label="科目编码" width="110" />
        <el-table-column prop="subjectName" label="科目名称" min-width="140" />
        <el-table-column label="辅助核算" min-width="160">
          <template #default="{ row }">
            <span v-if="row.auxiliaryType && row.auxiliaryEntityId" class="vd-aux">
              <el-tooltip :content="row.auxiliaryEntityId" placement="top">
                <span>{{ auxiliaryDisplay(row) }}</span>
              </el-tooltip>
            </span>
            <span v-else class="vd-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="摘要" min-width="180" show-overflow-tooltip />
        <!-- 借方栏 (left, 蓝色) -->
        <el-table-column label="借方金额" width="140" align="right" class-name="vd-col-debit">
          <template #default="{ row }">
            <span v-if="row.debit && row.debit > 0" class="vd-amount-debit">
              ¥{{ fmtAmount(row.debit) }}
            </span>
            <span v-else class="vd-muted">—</span>
          </template>
        </el-table-column>
        <!-- 贷方栏 (right, 橙色) -->
        <el-table-column label="贷方金额" width="140" align="right" class-name="vd-col-credit">
          <template #default="{ row }">
            <span v-if="row.credit && row.credit > 0" class="vd-amount-credit">
              ¥{{ fmtAmount(row.credit) }}
            </span>
            <span v-else class="vd-muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Actions: 过账 / 作废 (Rule 1 precondition + Rule 5 dead-end nav) -->
    <div class="vd-actions" v-if="voucher">
      <div class="vd-actions-hint">
        <template v-if="isReturnRefund">
          <el-text type="warning">
            客户退款凭证需由退货退款流程核对；不能在此作废或重复过账，请先核对资金流水。
          </el-text>
        </template>
        <template v-else-if="isVoucherReversal">
          <el-text type="warning">
            红字冲销凭证由原业务凭证流程维护；不能在此作废或重复过账，请先核对原始凭证。
          </el-text>
        </template>
        <template v-else-if="isDisposalVoucher">
          <el-text type="warning">报废凭证不能单独作废或冲销；请通过成本更正流程同步处理报废损失和账务。</el-text>
        </template>
        <template v-else-if="isReversed">
          <el-text type="warning">凭证已冲销，不可再次过账或作废。</el-text>
        </template>
        <template v-else-if="isPosted">
          <el-text type="info">
            凭证已过账, 不可编辑. 如需修改请联系财务管理员先反结账.
          </el-text>
        </template>
        <template v-else-if="isVoid">
          <el-text type="danger">凭证已作废, 不可恢复.</el-text>
        </template>
        <template v-else-if="!balanced">
          <el-text type="warning">
            凭证借贷不平衡, 差额 ¥{{ fmtAmount(Math.abs(difference)) }}, 不可过账.
          </el-text>
        </template>
        <template v-else-if="!canWrite">
          <el-text type="warning">您没有财务写入权限, 无法操作.</el-text>
        </template>
        <template v-else>
          <el-text type="success">凭证已平衡, 可过账.</el-text>
        </template>
      </div>
      <div class="vd-actions-buttons">
        <el-button
          type="primary"
          :disabled="!canPost"
          @click="onPost"
        >
          过账
        </el-button>
        <el-button
          v-if="!isProtectedRefundVoucher"
          type="danger"
          plain
          :disabled="!canVoid"
          @click="openVoidDialog"
        >
          作废
        </el-button>
      </div>
    </div>

    <!-- 作废 dialog (Rule 3 标准原因 dropdown + 自由 textarea fallback) -->
    <el-dialog
      v-model="voidDialogVisible"
      title="作废凭证 — 选择原因"
      width="500"
      :close-on-click-modal="false"
    >
      <div class="vd-void-context" v-if="voucher">
        <p>
          <strong>凭证号:</strong> {{ voucher.voucherNumber }} ({{ voucherTypeLabel }})
        </p>
        <p>
          <strong>金额:</strong> ¥{{ fmtAmount(totalDebit) }}
        </p>
        <p>
          <strong>状态:</strong>
          <el-tag v-if="statusBadge" :type="statusBadge.type as any" effect="dark" size="small">
            {{ statusBadge.text }}
          </el-tag>
        </p>
      </div>
      <el-form label-position="top">
        <el-form-item label="作废原因" required>
          <el-select v-model="voidReasonSelected" placeholder="请选择标准原因" style="width: 100%">
            <el-option
              v-for="opt in STANDARD_VOID_REASONS"
              :key="opt.value"
              :value="opt.value"
              :label="opt.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="voidReasonSelected === '其他'"
          label="详细原因 (必填)"
          required
        >
          <el-input
            v-model="voidReasonOther"
            type="textarea"
            :rows="3"
            placeholder="请说明作废原因 (供审计追溯)"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="voidDialogVisible = false">取消</el-button>
        <el-button
          type="danger"
          :disabled="voidSubmitDisabled"
          @click="onVoidSubmit"
        >
          确认作废
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.voucher-detail {
  padding: 20px;
}

.vd-error {
  margin-bottom: 16px;
}

.vd-header {
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.vd-title {
  font-size: 20px;
  font-weight: 600;
  margin: 8px 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.vd-status-tag {
  font-size: 13px;
}

.vd-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.vd-meta-item strong {
  color: var(--el-text-color-primary);
  margin-right: 4px;
}

.vd-balance-card {
  margin-bottom: 16px;
}

.vd-balance-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1.4fr;
  gap: 24px;
  align-items: center;
}

.vd-balance-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vd-balance-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.vd-balance-value {
  font-size: 20px;
  font-weight: 600;
  font-family: 'Courier New', monospace;
}

.vd-debit {
  color: #1d8cf8;
}

.vd-credit {
  color: #fd7e14;
}

.vd-balanced {
  color: var(--el-color-success);
}

.vd-unbalanced {
  color: var(--el-color-danger);
}

.vd-balance-status {
  justify-content: center;
  align-items: center;
}

.vd-entries-card {
  margin-bottom: 16px;
}

.vd-entries-header {
  font-weight: 600;
  font-size: 15px;
}

:deep(.vd-col-debit) {
  background-color: rgba(29, 140, 248, 0.05);
}

:deep(.vd-col-credit) {
  background-color: rgba(253, 126, 20, 0.05);
}

.vd-amount-debit {
  font-family: 'Courier New', monospace;
  color: #1d8cf8;
  font-weight: 600;
}

.vd-amount-credit {
  font-family: 'Courier New', monospace;
  color: #fd7e14;
  font-weight: 600;
}

.vd-muted {
  color: var(--el-text-color-placeholder);
}

.vd-aux {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 2px 6px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
}

.vd-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--el-fill-color-blank);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.vd-actions-hint {
  flex: 1;
}

.vd-actions-buttons {
  display: flex;
  gap: 12px;
}

.vd-void-context {
  background: var(--el-fill-color-light);
  padding: 12px 16px;
  border-radius: 4px;
  margin-bottom: 16px;
}

.vd-void-context p {
  margin: 4px 0;
  font-size: 13px;
}
</style>
