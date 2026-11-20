<script setup lang="ts">
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { formatAmount } from '@/utils/tableFormatters';
import { formatDateTime } from '@/utils/dateFormat';
import { handleCatchError } from '@/utils/errorToast';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import { enumLabel, INVOICE_STATUS_LABELS, INVOICE_TYPE_LABELS } from '@/utils/enumDisplay';
import type { TableRow } from '@/types/api';
import type { CsTableColumn } from '@/types/csTable';
// 🔴 2026-08-22: 财务侧「订单速览」只读抽屉。
//   owner 要的形状: 导航栏只有财务自己的内容, 但财务要能看到订单详情,
//   ⛔ 又不能从导航栏跨进销售/采购模块。
//   ⇒ 抽屉**不注册任何路由** —— 物理上进不了侧边栏, URL 也始终停在 /finance/*。
import OrderPeekDrawer from '../components/OrderPeekDrawer.vue';
import InvoicePdfDownload from '@/components/finance/InvoicePdfDownload.vue';
import AiUploadHint from '@/components/common/AiUploadHint.vue';
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { financeStatusColor } from '../financeDisplay';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'finance');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('finance', nodeId);
  if (!primary) return;
  statusFilter.value = primary;
  pagination.value.page = 0;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('finance', nodeId)}" (显示状态: ${enumLabel(primary, INVOICE_STATUS_LABELS)}). finance 是 Invoice+Payment 复合, done 节点实为 payment.VERIFIED 但本表仅 invoice — 详情请去收款管理.`);
}
function handleWorkflowAITrigger() {
  ElMessage.info('AI 入口待 Day 7 接入');
}

const loading = ref(false);
const loadFailed = ref(false);
const tableData = ref<TableRow[]>([]);
// 2026-06-21 fix: backend /finance/invoices is 0-based (defaultValue=0); pagination.page is 0-based
// internally (UPagination 是 1-based, 模板里 +1 / -1 换算). Init must be 0 — else first load requests
// page index 1 (2nd page) and the list is empty whenever total<=size (pagination control hidden → unreachable).
const pagination = ref({ page: 0, size: 20, total: 0 });
const ALL = 'ALL';
const statusFilter = ref(ALL);
const invoiceTypeFilter = ref(ALL);
const searchKeyword = ref('');
type FailedInvoiceTodo = {
  salesOrderId: string;
  orderNumber: string;
  attempts: number;
  lastError: string;
  updatedAt: string;
};
const failedTodos = ref<FailedInvoiceTodo[]>([]);

// 状态/类型中文来自 enumDisplay (全仓同源); 状态/类型是固定业务枚举, 不做动态派生。
const STATUS_ITEMS = [{ label: '全部状态', value: ALL }, ...Object.entries(INVOICE_STATUS_LABELS).map(([value, label]) => ({ label, value }))];
const TYPE_ITEMS = [{ label: '全部类型', value: ALL }, ...Object.entries(INVOICE_TYPE_LABELS).map(([value, label]) => ({ label, value }))];
const statusText = (c: unknown) => enumLabel(c, INVOICE_STATUS_LABELS);
const typeText = (c: unknown) => (c ? enumLabel(c, INVOICE_TYPE_LABELS) : '—');

onMounted(() => {
  // Bug #40: finance 点"开票审核"菜单时 URL 带 ?status=REQUESTED 自动过滤
  const qs = route.query.status;
  if (typeof qs === 'string' && INVOICE_STATUS_LABELS[qs]) statusFilter.value = qs;
  loadData();
  loadFailedTodos();
});

async function loadFailedTodos() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/finance/invoice-todos/failed`);
    failedTodos.value = res.success && Array.isArray(res.data) ? res.data : [];
  } catch {
    failedTodos.value = [];
  }
}

async function retryFailedTodo(todo: FailedInvoiceTodo) {
  const ok = await confirmRef.value!.ask({
    title: '重新排队',
    message: `确认重新处理销售单 ${todo.orderNumber} 的自动开票待办？`,
    confirmText: '重新排队',
  });
  if (ok === null) return;
  try {
    await post(`/${factoryId.value}/finance/invoice-todos/${todo.salesOrderId}/retry`, {});
    ElMessage.success('已重新排队，系统将自动重试');
    await loadFailedTodos();
  } catch (error) {
    handleCatchError(error, '重新排队失败');
  }
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value !== ALL) params.status = statusFilter.value;
    if (invoiceTypeFilter.value !== ALL) params.invoiceType = invoiceTypeFilter.value;
    const res = await get(`/${factoryId.value}/finance/invoices`, { params });
    if (res.success) {
      let rows = res.data.content || [];
      // Apr 20 Bug BR-11 fix: 前端补 keyword 搜索 (客户 / 发票号), 后端无 keyword 参数
      const kw = searchKeyword.value.trim();
      if (kw) {
        const lower = kw.toLowerCase();
        rows = rows.filter((r: TableRow) =>
          String(r.customerName || '').toLowerCase().includes(lower) ||
          String(r.invoiceNumber || '').toLowerCase().includes(lower) ||
          String(r.orderNumber || '').toLowerCase().includes(lower)
        );
      }
      tableData.value = rows;
      pagination.value.total = res.data.totalElements || 0;
    } else {
      loadFailed.value = true;
    }
  } catch { loadFailed.value = true; /* axios interceptor already displayed error toast */ }
  finally { loading.value = false; }
}

function handleSearch() { pagination.value.page = 0; loadData(); }
function handleReset() {
  searchKeyword.value = '';
  statusFilter.value = ALL;
  invoiceTypeFilter.value = ALL;
  handleSearch();
}
// 筛选下拉 change 时同 handleSearch 一样把 page 归零 (system/logs/index.vue 同款 filter-bar 模式)
function handleFilterChange() { pagination.value.page = 0; loadData(); }
function onPageUpdate(p: number) { pagination.value.page = p - 1; loadData(); }

async function handleAction(id: string, action: 'approve' | 'reject' | 'issue') {
  const labels = { approve: '审核通过', reject: '驳回', issue: '开具发票' };
  try {
    if (action === 'reject') {
      const notes = await confirmRef.value!.ask({
        title: '驳回', message: '驳回后申请人需重新提交开票申请。', confirmText: '驳回', danger: true,
        input: { label: '驳回原因', placeholder: '请输入驳回原因', required: true, type: 'textarea' },
      });
      if (notes === null) return;
      await post(`/${factoryId.value}/finance/invoices/${id}/reject`, { notes });
    } else if (action === 'issue') {
      // Bug #4 (R7 fix 2026-04-16): 后端硬规则要求 PDF 附件, 开对话框让用户选文件
      // 防呆 R2 (fool-proof-design 2026-05-17): 弹窗时携 row context 显示 invoice号 + 客户 + 申请金额
      issueTargetId.value = id;
      issueTargetRow.value = tableData.value.find(r => r.id === id) || null;
      issuePdfFile.value = null;
      issueDialogVisible.value = true;
      return;
    } else {
      const ok = await confirmRef.value!.ask({ title: '确认', message: `确认${labels[action]}？` });
      if (ok === null) return;
      await post(`/${factoryId.value}/finance/invoices/${id}/${action}`);
    }
    ElMessage.success(`${labels[action]}成功`);
    loadData();
  } catch (e) {
    // R26 follow-up: handleCatchError gates on !err.status — only fires fallback for true network errors,
    // 不在 axios 拦截器 toast 之上再叠一条「操作失败」。
    handleCatchError(e, '操作失败,请检查网络');
  }
}

// Bug #4 fix: 开具发票弹窗 + PDF 上传
const issueDialogVisible = ref(false);
const issueTargetId = ref('');
const issueTargetRow = ref<TableRow | null>(null);  // 防呆 R2: dialog context
const issuePdfFile = ref<File | null>(null);
const issuing = ref(false);

// F-INV-1 gap-fill: 开具成功后展示 OCR 结果, 与申请金额对比
interface IssueResponse {
  success: boolean;
  data?: {
    amount?: number;
    ocrInvoiceNumber?: string | null;
    ocrAmount?: number | null;
    ocrTaxAmount?: number | null;
    ocrConfidence?: number | null;
    ocrErrorMessage?: string | null;
  };
  message?: string;
}

async function submitIssue() {
  if (!issuePdfFile.value) { ElMessage.warning('请选择发票 PDF 文件'); return; }
  issuing.value = true;
  try {
    const formData = new FormData();
    formData.append('file', issuePdfFile.value);
    const res = await post<IssueResponse['data']>(
      `/${factoryId.value}/finance/invoices/${issueTargetId.value}/issue`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    ElMessage.success('开具发票成功');
    issueDialogVisible.value = false;
    surfaceOcrResult(res.data);
    loadData();
  } catch (e) {
    handleCatchError(e, '开具失败');
  } finally {
    issuing.value = false;
  }
}

function surfaceOcrResult(data?: IssueResponse['data']) {
  if (!data) return;
  // 防呆 4-位一体 (fool-proof-design 2026-05-17):
  //   a. backend message → b. UI 同文 → c. sticky (duration:0+showClose) → d. next action
  // OCR 失败 / 金额不一致 都是 financier 不能错过的关键信息, 必 sticky.
  if (data.ocrErrorMessage) {
    ElMessage({
      message: `发票已开具,但 OCR 识别失败: ${data.ocrErrorMessage} — 请打开 发票PDF 人工核对金额`,
      type: 'warning',
      duration: 0,
      showClose: true,
    });
    return;
  }
  if (data.ocrInvoiceNumber != null || data.ocrAmount != null) {
    const conf = data.ocrConfidence != null ? Math.round(data.ocrConfidence * 100) : null;
    const mismatch = data.ocrAmount != null && data.amount != null
        && Math.abs(Number(data.ocrAmount) - Number(data.amount)) > 0.01;
    if (mismatch) {
      ElMessage({
        message: `OCR 金额 ¥${data.ocrAmount} 与申请金额 ¥${data.amount} 不一致 ` +
          `(发票号 ${data.ocrInvoiceNumber || '?'}, 置信度 ${conf}%) — ` +
          `请人工核对 PDF 后决定是否驳回重开`,
        type: 'warning',
        duration: 0,         // sticky — 不能错过
        showClose: true,
      });
    } else {
      ElMessage({
        message: `OCR 识别: 发票号 ${data.ocrInvoiceNumber || '?'}, ` +
          `金额 ¥${data.ocrAmount} (置信度 ${conf}%) — 与申请金额一致`,
        type: 'success',
        duration: 5000,
      });
    }
  }
}

function ocrTitle(row: TableRow): string {
  const lines = [`OCR 发票号: ${row.ocrInvoiceNumber || '—'}`];
  if (canViewPrice.value) {
    lines.push(`OCR 金额: ¥${row.ocrAmount ?? '—'}`, `OCR 税额: ¥${row.ocrTaxAmount ?? '—'}`);
  }
  lines.push(`置信度: ${row.ocrConfidence != null ? Math.round(Number(row.ocrConfidence) * 100) + '%' : '—'}`);
  return lines.join('\n');
}

function isOcrMatch(row: TableRow): boolean {
  // F-INV-1 gap-fill: 金额相差 ≤ ¥0.01 视为匹配 (浮点容差)
  if (row.ocrAmount == null || row.amount == null) return false;
  return Math.abs(Number(row.ocrAmount) - Number(row.amount)) <= 0.01;
}

/** 原生 file 输入: 仅 PDF。 */
function handlePdfInput(e: Event) {
  const input = e.target as HTMLInputElement;
  const raw = input.files?.[0] ?? null;
  if (raw && !raw.name.toLowerCase().endsWith('.pdf')) {
    ElMessage.warning('仅支持 PDF 文件');
    issuePdfFile.value = null;
    input.value = '';
    return;
  }
  issuePdfFile.value = raw;
}

// ── 订单速览(只读抽屉) ────────────────────────────────────────────
// ⛔ 只有「打开」这一个动作, 没有任何写操作 —— 能力不是靠挡出来的, 是一开始就不给。
const peekOrderId = ref<string | null>(null);
const peekVisible = ref(false);
function peekOrder(id: string) {
  peekOrderId.value = id;
  peekVisible.value = true;
}

const columns = computed<CsTableColumn<TableRow>[]>(() => {
  const cols: CsTableColumn<TableRow>[] = [
    { accessorKey: 'invoiceNumber', header: '发票编号' },
    { id: 'order', header: '关联订单' },
    { accessorKey: 'customerName', header: '客户' },
  ];
  // 价税合计: 无查看价格权限整列去掉 (不显示 0 / 遮蔽值)
  if (canViewPrice.value) cols.push({ id: 'totalAmount', header: '价税合计', meta: { class: { th: 'text-right', td: 'text-right' } } });
  cols.push(
    { id: 'invoiceType', header: '类型' },
    { id: 'status', header: '状态' },
    { id: 'requestedAt', header: '申请时间' },
    { id: 'pdf', header: '发票PDF' },
    { id: 'ocr', header: 'OCR' },
  );
  if (canWrite.value) cols.push({ id: 'actions', header: '操作' });
  return cols;
});
</script>

<template>
  <div class="page" data-testid="finance-invoices-page">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      title="财务工作流"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="handleWorkflowAITrigger"
    />

    <UAlert v-if="failedTodos.length" color="error" variant="soft" icon="i-lucide-circle-alert" class="mb-4" data-testid="invoice-failed-todos">
      <template #title>有 {{ failedTodos.length }} 条自动开票待办已停止重试</template>
      <template #description>
        <div v-for="todo in failedTodos" :key="todo.salesOrderId" class="todo-row">
          <strong>{{ todo.orderNumber }}</strong>
          <span>已尝试 {{ todo.attempts }} 次：{{ todo.lastError }}</span>
          <UButton v-if="canWrite" color="error" variant="link" size="xs" data-testid="invoice-retry-todo" @click="retryFailedTodo(todo)">重新排队</UButton>
        </div>
      </template>
    </UAlert>

    <section class="cs-card">
      <header class="cs-card-head">
        <h2>开票管理</h2>
        <span class="count">共 {{ pagination.total }} 张</span>
        <div class="head-actions">
          <UBadge color="neutral" variant="outline">销售订单完成后自动生成</UBadge>
        </div>
      </header>

      <div class="filter-bar">
        <!-- Apr 20 Bug BR-11 fix: 加 keyword 搜索 (客户 / 发票号 / 订单号) -->
        <UInput v-model="searchKeyword" placeholder="搜索 客户/发票号/订单号" class="w-56" data-testid="invoice-keyword" @keyup.enter="handleSearch" />
        <USelect v-model="statusFilter" :items="STATUS_ITEMS" class="w-36" data-testid="invoice-status-filter" @update:model-value="handleFilterChange" />
        <USelect v-model="invoiceTypeFilter" :items="TYPE_ITEMS" class="w-40" data-testid="invoice-type-filter" @update:model-value="handleFilterChange" />
        <UButton data-testid="invoice-search" @click="handleSearch">搜索</UButton>
        <UButton color="neutral" variant="outline" @click="handleReset">重置</UButton>
      </div>

      <UAlert v-if="loadFailed" color="error" variant="soft" icon="i-lucide-circle-alert" class="mx-5 mb-3" data-testid="invoice-load-error"
        title="发票列表暂时没取到，请点「搜索」重试。" />

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="invoice-table">
        <template #empty><ListEmptyExplain empty-title="还没有销售发票" empty-hint="销售订单发货并对账后，在这里开具或登记发票。" /></template>
        <!-- 🔴 2026-08-22: 「关联订单」列。点它开只读抽屉, ⛔ 不跳转。 -->
        <template #order-cell="{ row }">
          <UButton v-if="row.original.salesOrderId" variant="link" size="xs" data-testid="invoice-peek-order" @click="peekOrder(String(row.original.salesOrderId))">
            {{ row.original.orderNumber || '查看订单' }}
          </UButton>
          <span v-else>—</span>
        </template>
        <template #requestedAt-cell="{ row }">{{ formatDateTime(row.original.requestedAt as string | null) }}</template>
        <template #totalAmount-cell="{ row }"><span class="num">{{ formatAmount(row.original.totalAmount as number | null) }}</span></template>
        <template #invoiceType-cell="{ row }">{{ typeText(row.original.invoiceType) }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="financeStatusColor(row.original.status)" variant="soft">{{ statusText(row.original.status) }}</UBadge>
        </template>
        <template #pdf-cell="{ row }">
          <InvoicePdfDownload v-if="row.original.invoicePdfUrl" :factory-id="factoryId" :invoice-id="row.original.id" :file-name="row.original.invoiceFileName" />
          <span v-else>—</span>
        </template>
        <!-- F-INV-1 gap-fill: OCR 识别结果 (供财务核对) -->
        <template #ocr-cell="{ row }">
          <span v-if="row.original.status !== 'ISSUED'" class="faint">—</span>
          <!-- 原生 title 提示: Nuxt UI 的 UTooltip 需要全局 UApp 包裹 (另议), 没有时会抛 TooltipProvider 缺失 -->
          <UBadge v-else-if="row.original.ocrErrorMessage" color="error" variant="soft" :title="`OCR 失败: ${row.original.ocrErrorMessage}`">失败</UBadge>
          <UBadge v-else-if="row.original.ocrInvoiceNumber || row.original.ocrAmount" :color="isOcrMatch(row.original) ? 'success' : 'warning'" variant="soft" :title="ocrTitle(row.original)" data-testid="invoice-ocr-badge">{{ isOcrMatch(row.original) ? '匹配' : '待核对' }}</UBadge>
          <span v-else class="faint">未识别</span>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton v-if="row.original.status === 'REQUESTED'" color="success" variant="link" size="xs" data-testid="invoice-approve" @click="handleAction(String(row.original.id), 'approve')">审核</UButton>
            <UButton v-if="row.original.status === 'REQUESTED'" color="error" variant="link" size="xs" data-testid="invoice-reject" @click="handleAction(String(row.original.id), 'reject')">驳回</UButton>
            <UButton v-if="row.original.status === 'APPROVED'" variant="link" size="xs" data-testid="invoice-issue" @click="handleAction(String(row.original.id), 'issue')">开具</UButton>
          </div>
        </template>
      </UTable>

      <footer v-if="pagination.total > pagination.size" class="pager">
        <UPagination :page="pagination.page + 1" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="onPageUpdate" />
      </footer>
    </section>

    <!-- Bug #4 R7 fix: 开具发票 + PDF 上传 (防呆 R2 retrofit 2026-05-17) -->
    <UModal
      v-model:open="issueDialogVisible"
      :title="issueTargetRow ? `开具发票 — ${issueTargetRow.invoiceNumber} · ${issueTargetRow.customerName || '客户未知'}` : '开具发票'"
      description="上传发票 PDF 并开具"
      :ui="{ content: 'max-w-[560px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="form">
          <!-- 防呆 R2: 显式列出 发票号 / 客户 / 申请金额 — financier 上传前先确认 -->
          <dl v-if="issueTargetRow" class="desc" data-testid="invoice-issue-summary">
            <dt>发票号</dt><dd>{{ issueTargetRow.invoiceNumber }}</dd>
            <dt>客户</dt><dd>{{ issueTargetRow.customerName || '—' }}</dd>
            <template v-if="canViewPrice">
              <dt>申请价税合计</dt>
              <dd>
                ¥{{ Number(issueTargetRow.totalAmount || 0).toFixed(2) }}
                <span class="muted">(不含税 ¥{{ Number(issueTargetRow.amount || 0).toFixed(2) }} + 税 ¥{{ Number(issueTargetRow.taxAmount || 0).toFixed(2) }})</span>
              </dd>
            </template>
          </dl>
          <UFormField label="发票 PDF" required>
            <input type="file" accept="application/pdf,.pdf" class="file-input" data-testid="invoice-pdf-input" @change="handlePdfInput" />
            <AiUploadHint kind="pdf" />
            <div class="muted">仅 PDF, 上传后销售可从订单详情下载. 系统将自动 OCR 识别金额, 与申请金额对比.</div>
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="issueDialogVisible = false">取消</UButton>
        <UButton :loading="issuing" :disabled="!issuePdfFile" data-testid="invoice-issue-submit" @click="submitIssue">确认开具</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
    <!-- 🔴 只读订单速览。⛔ 不是路由 ⇒ 侧边栏到不了, URL 不变。 -->
    <OrderPeekDrawer v-model:visible="peekVisible" :order-id="peekOrderId" kind="sales" />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.filter-bar { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); padding: var(--cs-space-3) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.form { display: grid; gap: var(--cs-space-3); }
.desc { display: grid; grid-template-columns: 7em 1fr; gap: var(--cs-space-2) var(--cs-space-3); margin: 0; font-size: 13px; padding: var(--cs-space-3); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.desc dt { color: var(--cs-muted); }
.desc dd { margin: 0; color: var(--cs-text); }
.muted { color: var(--cs-muted); font-size: 12px; margin-top: 4px; }
.faint { color: var(--cs-muted); }
.file-input { font-size: 13px; }
.todo-row { display: flex; align-items: center; gap: var(--cs-space-3); margin-top: var(--cs-space-2); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.num { font-variant-numeric: tabular-nums; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
