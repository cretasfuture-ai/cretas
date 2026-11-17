<script setup lang="ts">
/**
 * Sprint 7 T2 F-PERIOD — 期间结账管理 admin UI.
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 2 (context): 每行显 {year}-{month} + 状态 tag
 * - Rule 3 (constrained input): 反结账原因 dropdown + 其他 textarea
 * - Rule 5 (dead-end nav): CLOSED 行的 "尝试修改凭证" 走确认弹窗
 *   "期间已结账, 是否反结账?" → 跳转此页 + auto-open dialog
 *
 * Route: /finance/accounting-period
 * Permission: finance:read_write (Controller 层 enforce)
 */
import { ref, computed, watch, onMounted, onActivated, onDeactivated } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { get, post } from '@/api/request';
import { badgeColor } from '@/utils/csBadge';
import { enumLabel, RECONCILIATION_STATUS_LABELS } from '@/utils/enumDisplay';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 Element 的命令式确认框: 它在 Nuxt UI 弹层之上点不了)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import {
  computeAdjustWindow,
  reconciliationTagType,
} from './monthCloseHelpers';
import { periodReturnTarget } from '@/utils/accountingPeriodRecovery';

// ============================================================
// Types — mirror backend AccountingPeriod entity
// ============================================================
type PeriodStatus = 'OPEN' | 'PENDING_CLOSE' | 'CLOSED';

interface AccountingPeriod {
  id: string;
  factoryId: string;
  year: number;
  month: number;
  status: PeriodStatus;
  openedAt?: string;
  openedBy?: number;
  closedAt?: string;
  closedBy?: number;
  reopenedAt?: string;
  reopenedBy?: number;
  reopenReason?: string;
  approvalWorkflowInstanceId?: string;
  createdAt?: string;
  updatedAt?: string;
  // Wave2 月结自动闭环字段
  adjustDeadline?: string;
  adjustWindowState?: string;
  reconciliationStatus?: string;
  reconciliationSummary?: string;
  totalRevenueSnapshot?: number;
  netProfitSnapshot?: number;
  reportReadyAt?: string;
}

// Wave2 月结对账预览 DTO
interface CheckItem {
  name: string;
  passed: boolean;
  severity: 'BLOCKING' | 'WARNING' | 'INFO';
  detail: string;
  value?: unknown;
}
interface MonthCloseReconciliation {
  factoryId: string;
  year: number;
  month: number;
  canClose: boolean;
  reconciliationStatus: string;
  checks: CheckItem[];
  summary: string;
  adjustWindowState?: string;
}

// ============================================================
// Store / route
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId ?? '');
const canWrite = computed(() => permissionStore.canWrite('finance'));

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();
const loading = ref(false);
const periods = ref<AccountingPeriod[]>([]);
const periodError = ref('');
const mutationBusy = ref(false);
let periodRequest = 0;
let lifecycleGeneration = 0;
let pageIsActive = false;
let hasActivated = false;

// ============================================================
// Wave2 月结 (Month-Close) state
// ============================================================
const monthClosePreviewVisible = ref(false);
const monthCloseLoading = ref(false);
const monthClosePreview = ref<MonthCloseReconciliation | null>(null);
const monthCloseError = ref('');
const monthCloseTarget = ref<{
  year: number;
  month: number;
  factoryId: string;
  actorId: number | string | undefined;
  generation: number;
} | null>(null);

// ============================================================
// Open / close dialog state
// ============================================================
const openDialogVisible = ref(false);
const openForm = ref({ year: new Date().getFullYear(), month: new Date().getMonth() + 1 });

const closeDialogVisible = ref(false);
// 反结账 dialog (Rule 3 — dropdown + 其他 textarea)
const reopenDialogVisible = ref(false);
const reopenForm = ref<{
  period: AccountingPeriod | null;
  reasonType: string;
  reasonExtra: string;
}>({
  period: null,
  reasonType: '',
  reasonExtra: '',
});

type ActionContext = {
  generation: number;
  factoryId: string;
  actorId: number | string | undefined;
};

function captureActionContext(): ActionContext {
  return {
    generation: lifecycleGeneration,
    factoryId: factoryId.value,
    actorId: authStore.user?.id,
  };
}

function isCurrentAction(context: ActionContext): boolean {
  return context.generation === lifecycleGeneration
    && context.factoryId === factoryId.value
    && context.actorId === authStore.user?.id
    && pageIsActive;
}

function clearMutationDialogs() {
  openDialogVisible.value = false;
  reopenDialogVisible.value = false;
  closeDialogVisible.value = false;
  monthClosePreviewVisible.value = false;
  monthClosePreview.value = null;
  monthCloseError.value = '';
  monthCloseTarget.value = null;
  mutationBusy.value = false;
}

const REOPEN_REASON_PRESETS = [
  { value: '财务发现少入一笔凭证', label: '少入凭证 — 财务核对发现遗漏' },
  { value: '审计退回, 需补录调整', label: '审计退回 — 需补录调整凭证' },
  { value: '系统对账发现差异, 需要调整', label: '对账差异 — 需要调整凭证' },
  { value: '上级单位要求重新报表', label: '上级要求 — 重新生成报表' },
  { value: '其他', label: '其他 (请填写具体原因)' },
];

// ============================================================
// Status label / type
// ============================================================
const STATUS_LABEL: Record<PeriodStatus, string> = {
  OPEN: '开账中',
  PENDING_CLOSE: '审批中',
  CLOSED: '已结账',
};

const STATUS_TAG_TYPE: Record<PeriodStatus, 'success' | 'warning' | 'danger'> = {
  OPEN: 'success',
  PENDING_CLOSE: 'warning',
  CLOSED: 'danger',
};

// ============================================================
// API calls
// ============================================================
async function loadPeriods() {
  const request = ++periodRequest;
  const context = captureActionContext();
  const sourceQuery = JSON.stringify(route.query);
  periods.value = [];
  periodError.value = '';
  if (!context.factoryId || !pageIsActive) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const res = await get<AccountingPeriod[]>(
      `/${context.factoryId}/finance/accounting-periods`
    );
    if (request !== periodRequest || !isCurrentAction(context) || sourceQuery !== JSON.stringify(route.query)) return;
    if (res.success && Array.isArray(res.data)) {
      if (res.data.some(period => period.factoryId !== context.factoryId)) {
        periodError.value = '期间读取结果与当前工厂不一致，请刷新后重试';
        return;
      }
      periods.value = res.data;
      openDeepLinkedReopenDialog();
    } else if (res.success === false) {
      periodError.value = res.message || '期间读取失败，请重试';
    }
  } catch (error) {
    if (request === periodRequest && isCurrentAction(context) && sourceQuery === JSON.stringify(route.query)) {
      periodError.value = error instanceof Error ? error.message : '期间读取失败，请重试';
    }
  } finally {
    if (request === periodRequest && isCurrentAction(context)) loading.value = false;
  }
}

function openDeepLinkedReopenDialog() {
  const queryYear = Number(route.query.year);
  const queryMonth = Number(route.query.month);
  const hasDeepLink = Number.isInteger(queryYear) && Number.isInteger(queryMonth)
    && queryYear >= 2000 && queryMonth >= 1 && queryMonth <= 12;
  if (!hasDeepLink) return;
  const hasRecoveryMarker = Object.prototype.hasOwnProperty.call(route.query, '_periodRecovery');
  if (hasRecoveryMarker && !periodReturnTarget(route.query, factoryId.value, authStore.user?.id)) {
    periodError.value = '期间恢复链接已失效，请从原退款详情重新发起';
    return;
  }
  const target = periods.value.find(
    period => period.year === queryYear && period.month === queryMonth && period.status === 'CLOSED'
      && period.factoryId === factoryId.value,
  );
  if (target && canWrite.value) openReopenDialog(target);
  else if (target && !canWrite.value) periodError.value = '当前账号只有只读权限，不能反结账';
  else if (!target) periodError.value = `未找到 ${queryYear}-${String(queryMonth).padStart(2, '0')} 的已结账期间`;
}

function periodAdjustWindow(row: AccountingPeriod) {
  if (row.adjustWindowState === 'OPEN_WINDOW') return { state: 'OPEN_WINDOW' as const, label: '调整窗口开放' };
  if (row.adjustWindowState === 'LOCKED') return { state: 'LOCKED' as const, label: '已锁定' };
  if (row.adjustWindowState === 'NOT_CLOSED') return { state: 'NOT_CLOSED' as const, label: '—' };
  return computeAdjustWindow(row.status, row.adjustDeadline);
}

// ============================================================
// Actions: open / requestClose / confirmClose / reopen
// ============================================================
async function handleOpenPeriod() {
  if (!canWrite.value || !factoryId.value || mutationBusy.value) return;
  const context = captureActionContext();
  mutationBusy.value = true;
  try {
    const res = await post<AccountingPeriod>(
      `/${context.factoryId}/finance/accounting-periods/open`,
      { year: openForm.value.year, month: openForm.value.month }
    );
    if (isCurrentAction(context) && res.success) {
      ElMessage.success('期间已打开');
      openDialogVisible.value = false;
      await loadPeriods();
    } else if (isCurrentAction(context) && res.success === false) {
      periodError.value = res.message || '打开期间失败，请重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) periodError.value = error instanceof Error ? error.message : '打开期间失败，请重试';
  } finally {
    if (isCurrentAction(context)) mutationBusy.value = false;
  }
}

async function handleRequestClose(period: AccountingPeriod) {
  if (!canWrite.value || mutationBusy.value || period.factoryId !== factoryId.value) return;
  const context = captureActionContext();
  mutationBusy.value = true;
  try {
    const confirmed = await confirmRef.value!.ask({
      title: '发起结账',
      message: `确认对 ${period.year}-${String(period.month).padStart(2, '0')} 期间发起结账? ` +
        '确认前请在会计凭证中核对本期单据. ' +
        '提交后将走财务负责人审批流程. ' +
        '审批通过前凭证仍可修改; 审批通过后凭证将被锁定.',
      confirmText: '发起结账',
    });
    if (confirmed === null) return;
    if (!isCurrentAction(context)) return;
    const res = await post<AccountingPeriod>(
      `/${context.factoryId}/finance/accounting-periods/request-close`,
      { year: period.year, month: period.month }
    );
    if (isCurrentAction(context) && res.success) {
      ElMessage.success('结账请求已提交, 等待财务负责人审批');
      await loadPeriods();
    } else if (isCurrentAction(context) && res.success === false) {
      periodError.value = res.message || '发起结账失败，请重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) {
      periodError.value = error instanceof Error ? error.message : '发起结账失败，请重试';
    }
  } finally {
    if (isCurrentAction(context)) mutationBusy.value = false;
  }
}

async function handleConfirmClose(period: AccountingPeriod) {
  if (!canWrite.value || mutationBusy.value || period.factoryId !== factoryId.value) return;
  const context = captureActionContext();
  mutationBusy.value = true;
  try {
    const confirmed = await confirmRef.value!.ask({
      title: '确认结账',
      message: `确认结账 ${period.year}-${String(period.month).padStart(2, '0')} 期间? ` +
        '确认前请在会计凭证中核对本期单据. ' +
        '结账后所有凭证不可修改 / 过账 / 作废. ' +
        '如需修改, 必须先反结账 (审计日志会留痕).',
      confirmText: '确认结账',
      danger: true,
    });
    if (confirmed === null) return;
    if (!isCurrentAction(context)) return;
    const res = await post<AccountingPeriod>(
      `/${context.factoryId}/finance/accounting-periods/confirm-close`,
      { year: period.year, month: period.month }
    );
    if (isCurrentAction(context) && res.success) {
      ElMessage.success('期间已结账, 凭证写入已锁定');
      await loadPeriods();
    } else if (isCurrentAction(context) && res.success === false) {
      periodError.value = res.message || '确认结账失败，请重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) {
      periodError.value = error instanceof Error ? error.message : '确认结账失败，请重试';
    }
  } finally {
    if (isCurrentAction(context)) mutationBusy.value = false;
  }
}

function openReopenDialog(period: AccountingPeriod) {
  if (!canWrite.value || mutationBusy.value || period.factoryId !== factoryId.value) return;
  reopenForm.value = {
    period,
    reasonType: '',
    reasonExtra: '',
  };
  reopenDialogVisible.value = true;
}

async function handleReopen() {
  const form = reopenForm.value;
  if (!canWrite.value || mutationBusy.value || !form.period || form.period.factoryId !== factoryId.value) return;
  const context = captureActionContext();
  const returnTarget = periodReturnTarget(route.query, context.factoryId, context.actorId);

  // 防呆 Rule 3: dropdown 选项 + 其他 textarea
  let reason = form.reasonType;
  if (form.reasonType === '其他') {
    if (!form.reasonExtra.trim()) {
      ElMessage.error('选择"其他"时, 必须填写具体原因');
      return;
    }
    reason = form.reasonExtra.trim();
  } else if (!form.reasonType) {
    ElMessage.error('请选择反结账原因');
    return;
  }

  mutationBusy.value = true;
  try {
    const res = await post<AccountingPeriod>(
      `/${context.factoryId}/finance/accounting-periods/reopen`,
      {
        year: form.period.year,
        month: form.period.month,
        reason,
      }
    );
    if (isCurrentAction(context) && res.success) {
      ElMessage.success('期间已反结账, 凭证可继续修改');
      reopenDialogVisible.value = false;
      await loadPeriods();
      if (returnTarget && isCurrentAction(context)) await router.push(returnTarget);
    } else if (isCurrentAction(context) && res.success === false) {
      periodError.value = res.message || '反结账失败，请重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) periodError.value = error instanceof Error ? error.message : '反结账失败，请重试';
  } finally {
    if (isCurrentAction(context)) mutationBusy.value = false;
  }
}

// ============================================================
// Wave2 月结: 一键月结流程
//   1. GET /preview → 弹对账预显 dialog (防呆 R1)
//   2. canClose=false 时 disable 确认 button
//   3. 确认 → POST /execute → 成功后 dead-end 跳报表 (防呆 R5)
// ============================================================
async function handleMonthClose(period: AccountingPeriod) {
  if (!canWrite.value || !factoryId.value || mutationBusy.value || period.factoryId !== factoryId.value) return;
  // 幂等防呆 R4: 已 CLOSED 不应到这 (按钮已隐藏), 防御性再挡一次
  if (period.status === 'CLOSED') {
    ElMessage.warning('该期间已结账, 不能重复结账');
    return;
  }
  const context = captureActionContext();
  monthCloseTarget.value = {
    year: period.year,
    month: period.month,
    factoryId: context.factoryId,
    actorId: context.actorId,
    generation: context.generation,
  };
  monthClosePreview.value = null;
  monthCloseError.value = '';
  mutationBusy.value = true;
  monthCloseLoading.value = true;
  monthClosePreviewVisible.value = true;
  try {
    const res = await get<MonthCloseReconciliation>(
      `/${context.factoryId}/finance/month-close/preview`,
      { params: { year: period.year, month: period.month } }
    );
    if (!isCurrentAction(context)) return;
    if (res.success && res.data && res.data.factoryId === context.factoryId
      && res.data.year === period.year && res.data.month === period.month) {
      monthClosePreview.value = res.data;
    } else if (res.success === false) {
      monthCloseError.value = res.message || '月结对账读取失败，请重试';
    } else {
      monthCloseError.value = '月结对账结果与当前期间不一致，请刷新后重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) monthCloseError.value = error instanceof Error ? error.message : '月结对账读取失败，请重试';
  } finally {
    if (isCurrentAction(context)) {
      monthCloseLoading.value = false;
      mutationBusy.value = false;
    }
  }
}

async function confirmMonthClose() {
  const target = monthCloseTarget.value;
  if (!target || !canWrite.value || mutationBusy.value || target.factoryId !== factoryId.value
    || target.actorId !== authStore.user?.id || target.generation !== lifecycleGeneration) return;
  if (!monthClosePreview.value?.canClose) {
    ElMessage.error('对账校验未通过, 无法结账');
    return;
  }
  const context = captureActionContext();
  mutationBusy.value = true;
  monthCloseLoading.value = true;
  try {
    const res = await post<{
      adjustDeadline?: string;
      reportReadyAt?: string;
      message?: string;
      incomeStatement?: { netProfit?: number };
    }>(
      `/${context.factoryId}/finance/month-close/execute`,
      { year: target.year, month: target.month }
    );
    if (isCurrentAction(context) && res.success) {
      monthClosePreviewVisible.value = false;
      monthClosePreview.value = null;
      monthCloseError.value = '';
      await loadPeriods();
      // 防呆 R5 dead-end nav: 结账后跳报表
      const viewReport = await confirmRef.value!.ask({
        title: '月结完成',
        message: `${target.year}-${String(target.month).padStart(2, '0')} 月结完成, 利润表已生成. 是否立即查看财务报表?`,
        confirmText: '查看报表',
      });
      // 用户选"稍后" (null), 不跳转
      if (viewReport !== null) router.push('/finance/three-statements');
    } else if (isCurrentAction(context) && res.success === false) {
      monthCloseError.value = res.message || '月结执行失败，请重试';
    }
  } catch (error) {
    if (isCurrentAction(context)) monthCloseError.value = error instanceof Error ? error.message : '月结执行失败，请重试';
  } finally {
    if (isCurrentAction(context)) {
      monthCloseLoading.value = false;
      mutationBusy.value = false;
    }
  }
}

// ============================================================
// Mounted / activated — check route query for ?year=&month= deep-link
// (Rule 5 dead-end nav: PeriodClosedException actionHint 跳此页 + auto-open dialog)
// ============================================================
function invalidatePageContext() {
  lifecycleGeneration += 1;
  periodRequest += 1;
  periods.value = [];
  periodError.value = '';
  loading.value = false;
  monthCloseLoading.value = false;
  clearMutationDialogs();
}

watch(
  () => [factoryId.value, authStore.user?.id, JSON.stringify(route.query)],
  () => {
    if (!pageIsActive) return;
    invalidatePageContext();
    void loadPeriods();
  },
);

onMounted(() => {
  pageIsActive = true;
  void loadPeriods();
});

onActivated(() => {
  pageIsActive = true;
  if (hasActivated) {
    invalidatePageContext();
    void loadPeriods();
  }
  hasActivated = true;
});

onDeactivated(() => {
  pageIsActive = false;
  invalidatePageContext();
});
const reconciliationText = (status: string | undefined) => enumLabel(status, RECONCILIATION_STATUS_LABELS);

const columns: CsTableColumn<AccountingPeriod>[] = [
  { id: 'period', header: '期间' },
  { id: 'status', header: '状态' },
  { id: 'openedAt', header: '打开时间' },
  { id: 'closedAt', header: '结账时间' },
  { id: 'window', header: '调整窗口' },
  { id: 'report', header: '月结报表' },
  { id: 'reopen', header: '最近反结账' },
  { id: 'actions', header: '操作' },
];
const checkColumns: CsTableColumn<CheckItem>[] = [
  { accessorKey: 'name', header: '校验项' },
  { id: 'result', header: '结果' },
  { id: 'detail', header: '说明' },
];
</script>

<template>
  <div class="page" data-testid="finance-period-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>期间结账管理</h2>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" icon="i-lucide-refresh-cw" :loading="loading" data-testid="period-refresh" @click="loadPeriods">刷新</UButton>
          <UButton icon="i-lucide-lock-open" :disabled="!canWrite || mutationBusy" data-testid="period-open" @click="openDialogVisible = true">打开期间</UButton>
        </div>
      </header>

      <div class="body">
        <UAlert color="info" variant="soft" icon="i-lucide-info" title="期间结账机制说明">
          <template #description>
            <ul class="rules">
              <li><b>开账中</b>: 凭证可写入 / 过账 / 作废</li>
              <li><b>审批中</b>: 财务发起结账, 等待财务负责人审批, 凭证仍可修改</li>
              <li><b>已结账</b>: 存在调整窗口时可在窗口内调整; 未设置窗口或窗口结束后锁定. 月结报表以本期实际生成结果为准. 反结账必须填写原因 (审计日志)</li>
              <li><b>一键月结</b>: 月初触发 — 对账校验 → 生成利润表 → 快照锁定 → 结账 + 开启 20 天调整窗口 (月初出报表, 留调整窗口)</li>
              <li>每月 1 号 02:00 自动对上月 OPEN 期间发起结账请求</li>
            </ul>
          </template>
        </UAlert>

        <UAlert v-if="periodError" color="error" variant="soft" icon="i-lucide-circle-alert" class="mt-3" data-testid="period-error" :title="periodError" />

        <UTable :data="periods" :columns="columns" :loading="loading" class="cs-table mt-3" data-testid="period-table">
          <template #empty>尚无期间记录 — 点击「打开期间」开始</template>
          <template #period-cell="{ row }">
            <span class="period-name">{{ row.original.year }}-{{ String(row.original.month).padStart(2, '0') }}</span>
          </template>
          <template #status-cell="{ row }">
            <UBadge :color="badgeColor(STATUS_TAG_TYPE[row.original.status as PeriodStatus])" variant="soft">{{ STATUS_LABEL[row.original.status as PeriodStatus] }}</UBadge>
          </template>
          <template #openedAt-cell="{ row }">
            <span v-if="row.original.openedAt">{{ row.original.openedAt.replace('T', ' ').slice(0, 19) }}</span>
            <span v-else class="muted">—</span>
          </template>
          <template #closedAt-cell="{ row }">
            <span v-if="row.original.closedAt">{{ row.original.closedAt.replace('T', ' ').slice(0, 19) }}</span>
            <span v-else class="muted">—</span>
          </template>
          <!-- Wave2 月结: 调整窗口截止 + 倒计时 (邓总20天窗口) -->
          <template #window-cell="{ row }">
            <template v-if="row.original.status === 'CLOSED'">
              <UBadge :color="periodAdjustWindow(row.original).state === 'OPEN_WINDOW' ? 'warning' : 'neutral'" variant="soft">{{ periodAdjustWindow(row.original).label }}</UBadge>
              <div v-if="row.original.adjustDeadline" class="muted small">截止 {{ row.original.adjustDeadline.replace('T', ' ').slice(0, 10) }}</div>
            </template>
            <span v-else class="muted">—</span>
          </template>
          <!-- Wave2 月结: 净利润快照 + 对账结论 -->
          <template #report-cell="{ row }">
            <template v-if="row.original.status === 'CLOSED' && row.original.reportReadyAt">
              <div>
                <UBadge :color="badgeColor(reconciliationTagType(row.original.reconciliationStatus))" variant="soft">对账 {{ reconciliationText(row.original.reconciliationStatus) }}</UBadge>
              </div>
              <div class="muted small">净利 {{ row.original.netProfitSnapshot != null ? row.original.netProfitSnapshot : '—' }}</div>
            </template>
            <span v-else class="muted">—</span>
          </template>
          <template #reopen-cell="{ row }">
            <div v-if="row.original.reopenedAt">
              <div>{{ row.original.reopenedAt.replace('T', ' ').slice(0, 19) }}</div>
              <div class="muted small">{{ row.original.reopenReason }}</div>
            </div>
            <span v-else class="muted">—</span>
          </template>
          <template #actions-cell="{ row }">
            <div class="row-actions">
              <!-- Wave2 一键月结: OPEN / PENDING_CLOSE 行可触发 (CLOSED 隐藏, 防重复 R4) -->
              <UButton v-if="row.original.status !== 'CLOSED'" color="success" variant="link" size="xs" :disabled="!canWrite || mutationBusy" data-testid="period-month-close" @click="handleMonthClose(row.original)">一键月结</UButton>
              <UButton v-if="row.original.status === 'OPEN'" color="warning" variant="link" size="xs" :disabled="!canWrite || mutationBusy" data-testid="period-request-close" @click="handleRequestClose(row.original)">发起结账</UButton>
              <UButton v-if="row.original.status === 'PENDING_CLOSE'" color="error" variant="link" size="xs" :disabled="!canWrite || mutationBusy" data-testid="period-confirm-close" @click="handleConfirmClose(row.original)">确认结账</UButton>
              <UButton v-if="row.original.status === 'CLOSED'" variant="link" size="xs" :disabled="!canWrite || mutationBusy" data-testid="period-reopen" @click="openReopenDialog(row.original)">反结账</UButton>
            </div>
          </template>
        </UTable>
      </div>
    </section>

    <!-- 打开期间 -->
    <UModal
      v-model:open="openDialogVisible"
      title="打开期间"
      description="打开一个会计期间"
      :ui="{ content: 'max-w-[400px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <UFormField label="年度">
            <UInputNumber v-model="openForm.year" :min="2020" :max="2100" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ useGrouping: false }" class="w-full" data-testid="period-open-year" />
          </UFormField>
          <UFormField label="月份">
            <UInputNumber v-model="openForm.month" :min="1" :max="12" :step-snapping="false" :increment="false" :decrement="false" class="w-full" data-testid="period-open-month" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="openDialogVisible = false">取消</UButton>
        <UButton :loading="mutationBusy" :disabled="!canWrite || mutationBusy" data-testid="period-open-submit" @click="handleOpenPeriod">确认</UButton>
      </template>
    </UModal>

    <!-- 反结账 — 防呆 Rule 3 dropdown + 其他 textarea -->
    <UModal
      v-model:open="reopenDialogVisible"
      title="反结账"
      description="反结账需选择原因"
      :ui="{ content: 'max-w-[500px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div v-if="reopenForm.period" class="form">
          <UAlert color="warning" variant="soft" icon="i-lucide-triangle-alert"
            :title="`反结账 ${reopenForm.period.year}-${String(reopenForm.period.month).padStart(2,'0')} 期间`"
            description="反结账后, 该期间凭证可继续修改 / 过账 / 作废. 操作会留审计日志 (反结账人 / 时间 / 原因永久保留)." />
          <UFormField label="原因类别" required>
            <USelect v-model="reopenForm.reasonType" :items="REOPEN_REASON_PRESETS" placeholder="请选择反结账原因" class="w-full" data-testid="period-reopen-reason" />
          </UFormField>
          <UFormField v-if="reopenForm.reasonType === '其他'" label="具体原因" required>
            <UTextarea v-model="reopenForm.reasonExtra" :rows="3" :maxlength="500" placeholder="请详细描述反结账原因 (审计日志永久保留)" class="w-full" data-testid="period-reopen-extra" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="reopenDialogVisible = false">取消</UButton>
        <UButton color="error" :loading="mutationBusy" :disabled="!canWrite || mutationBusy" data-testid="period-reopen-submit" @click="handleReopen">确认反结账</UButton>
      </template>
    </UModal>

    <!-- Wave2 一键月结: 对账预显 (防呆 R1 预先显示边界) -->
    <UModal
      v-model:open="monthClosePreviewVisible"
      title="月结对账预览"
      description="月结前的对账校验结果"
      :ui="{ content: 'max-w-[600px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form" data-testid="month-close-preview">
          <div v-if="monthCloseLoading" class="muted">正在读取对账结果…</div>
          <UAlert v-if="monthCloseError" color="error" variant="soft" icon="i-lucide-circle-alert" :title="monthCloseError" />
          <template v-if="monthClosePreview">
            <UAlert
              :color="monthClosePreview.canClose ? (monthClosePreview.reconciliationStatus === 'PASS' ? 'success' : 'warning') : 'error'"
              variant="soft"
              icon="i-lucide-info"
              :title="`${monthClosePreview.year}-${String(monthClosePreview.month).padStart(2,'0')} 月结对账 — ${monthClosePreview.canClose ? '可执行结账' : '存在阻塞项, 暂不可结账'}`"
              description="月结将: 对账校验 → 生成利润表 → 快照锁定 → 结账并开启 20 天调整窗口. 结账后报表立即生成, 调整窗口内凭证仍可调整, 窗口结束后锁定." />
            <UTable :data="monthClosePreview.checks" :columns="checkColumns" class="cs-table" data-testid="month-close-checks">
              <template #result-cell="{ row }">
                <UBadge :color="row.original.passed ? 'success' : (row.original.severity === 'BLOCKING' ? 'error' : 'warning')" variant="soft">{{ row.original.passed ? '通过' : (row.original.severity === 'BLOCKING' ? '阻塞' : '提示') }}</UBadge>
              </template>
              <template #detail-cell="{ row }"><span :class="{ muted: row.original.passed }">{{ row.original.detail }}</span></template>
            </UTable>
          </template>
          <div v-else-if="!monthCloseLoading && !monthCloseError" class="muted">无对账数据</div>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="monthClosePreviewVisible = false">取消</UButton>
        <UButton color="success" :loading="monthCloseLoading" :disabled="!canWrite || mutationBusy || !monthClosePreview?.canClose" data-testid="month-close-confirm" @click="confirmMonthClose">确认月结</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.body { padding: var(--cs-space-4) var(--cs-space-5); }
.rules { padding-left: 20px; margin: 5px 0; list-style: disc; }
.form { display: grid; gap: var(--cs-space-3); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.period-name { font-family: 'Consolas', monospace; font-weight: 600; font-size: 15px; }
.muted { color: var(--cs-muted); }
.small { font-size: 12px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 4px; min-height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
