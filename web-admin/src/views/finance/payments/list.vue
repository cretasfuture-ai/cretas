<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import { formatAmount } from '@/utils/tableFormatters';
import type { TableRow } from '@/types/api';
import type { CsTableColumn } from '@/types/csTable';
// Sprint 6 W3-A — inline 3-chip link counter (文件 / 图片 / 合同).
import LinkChipCell from '@/components/list/LinkChipCell.vue';
import { useLinkChipCounts } from '@/composables/useLinkChipCounts';
import { enumLabel, PAYMENT_METHOD_LABELS, PAYMENT_RECORD_STATUS_LABELS } from '@/utils/enumDisplay';
import { salesOrderReceivableAmount, salesOrderRemainingAmount } from '@/views/sales/orders/salesOrderDisplay';
// 通用确认弹窗 (UModal, 取代 ElMessageBox: Element 的 MessageBox 在 Nuxt UI 弹层之上点不了)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { financeStatusColor } from '../financeDisplay';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('finance'));
const canViewPrice = computed(() => permissionStore.canViewPrice);

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();

// Sprint 6 W3-A — inline 3-chip 链接计数 (文件 / 图片 / 合同).
const { fetchLinkChipCounts, countsFor: linkCountsFor } =
  useLinkChipCounts(factoryId, 'PAYMENT_VOUCHER');

const loading = ref(false);
const loadFailed = ref(false);
const tableData = ref<TableRow[]>([]);
// 2026-06-21 fix: backend /finance/payments is 0-based (defaultValue=0); pagination.page is 0-based
// internally (UPagination 是 1-based, 模板里 +1 / -1 换算). Init must be 0 — else first load requests
// page index 1 (2nd page) and the list is empty whenever total<=size (pagination control hidden →
// unreachable), making 收款确认 (verify) impossible → SO stuck UNPAID.
const pagination = ref({ page: 0, size: 20, total: 0 });
const ALL = 'ALL';
const statusFilter = ref(ALL);
// 2026-07-14: paymentMethod 动态筛选下拉. PaymentMethod 是后端固定业务枚举, 数据无上限故不做
// "从数据里动态派生选项", 直接用 enumDisplay 的固定表。
const paymentMethodFilter = ref(ALL);
const searchKeyword = ref('');

const STATUS_ITEMS = [{ label: '全部状态', value: ALL }, ...Object.entries(PAYMENT_RECORD_STATUS_LABELS).map(([value, label]) => ({ label, value }))];
const METHOD_ITEMS = [{ label: '全部支付方式', value: ALL }, ...Object.entries(PAYMENT_METHOD_LABELS).map(([value, label]) => ({ label, value }))];
const METHOD_FORM_ITEMS = Object.entries(PAYMENT_METHOD_LABELS).map(([value, label]) => ({ label, value }));

const statusText = (c: unknown) => enumLabel(c, PAYMENT_RECORD_STATUS_LABELS);
const methodText = (c: unknown) => enumLabel(c, PAYMENT_METHOD_LABELS);

onMounted(() => {
  loadData();
  loadSalesOrderOptions();
});

// Apr 21 2026: load confirmed sales orders for dropdown in 录入收款 dialog
// E-FP-3 (fool-proof Rule 1): SalesOrder 实体已序列化 paidAmount (entity line 223),
// 故 dialog 可计算 + 显示 "可收余额" 并 gate input — 纯前端, 无需后端改动.
interface SalesOrderOption { id: string; orderNumber: string; customerName: string; totalAmount?: number; taxAmount?: number; discountAmount?: number; payableAmount?: number; paidAmount?: number }
const salesOrderOptions = ref<SalesOrderOption[]>([]);
async function loadSalesOrderOptions() {
  if (!factoryId.value) return;
  try {
    // Note: endpoint is /sales/orders (SalesController), not /sales-orders.
    // List all then filter to payable statuses client-side (CONFIRMED through
    // COMPLETED; exclude DRAFT / CANCELLED).
    const res = await get<{ content: (SalesOrderOption & { status?: string })[] }>(
      `/${factoryId.value}/sales/orders`,
      { params: { page: 1, size: 200 } }
    );
    if (res.success && res.data) {
      const payableStatuses = new Set([
        'CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED',
        'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED',
        // T-SHORT-SHIP (2026-09-04): 短交结案不冻结已发货部分的应收 —— 结案前的
        // AR 已按实发数量入账 (InvoiceServiceImpl#computeLineAmountForInvoice),
        // 漏掉这个值会让财务在这个下拉里找不到已短交结案的订单来录款。
        'SHORT_CLOSED',
      ]);
      salesOrderOptions.value = (res.data.content || []).filter(
        o => !o.status || payableStatuses.has(o.status)
      );
    }
  } catch { /* silent */ }
}

const salesOrderItems = computed(() => salesOrderOptions.value.map(o => ({
  value: String(o.id),
  label: `${o.orderNumber} · ${o.customerName || '-'} · ¥${salesOrderReceivableAmount(o).toLocaleString()}`,
})));

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    const params: TableRow = { page: pagination.value.page, size: pagination.value.size };
    if (statusFilter.value !== ALL) params.status = statusFilter.value;
    if (paymentMethodFilter.value !== ALL) params.paymentMethod = paymentMethodFilter.value;
    const res = await get(`/${factoryId.value}/finance/payments`, { params });
    if (res.success) {
      let rows = res.data.content || [];
      // Apr 20 Bug BR-12 fix: 加前端 keyword 搜索 (客户 / 收款单号 / 发票号)
      const kw = searchKeyword.value.trim();
      if (kw) {
        const lower = kw.toLowerCase();
        rows = rows.filter((r: TableRow) =>
          String(r.customerName || '').toLowerCase().includes(lower) ||
          String(r.paymentNumber || '').toLowerCase().includes(lower) ||
          String(r.invoiceNumber || '').toLowerCase().includes(lower)
        );
      }
      tableData.value = rows;
      pagination.value.total = res.data.totalElements || 0;

      // Sprint 6 W3-A — fire-and-forget batch 3-chip counts (文件/图片/合同).
      // EntityType=PAYMENT_VOUCHER → 收款凭证附件 (银行回单 / 转账截图 / 合同).
      void fetchLinkChipCounts(rows.map((r: TableRow) => String(r.id)).filter(Boolean));
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
  paymentMethodFilter.value = ALL;
  handleSearch();
}
// 筛选下拉变化: 重置回第一页再加载 (mirror system/logs/index.vue filter-bar 规范: 换筛选条件必回第一页).
function handleFilterChange() { pagination.value.page = 0; loadData(); }
function onPageUpdate(p: number) { pagination.value.page = p - 1; loadData(); }

async function handleVerify(id: string) {
  const ok = await confirmRef.value!.ask({ title: '确认', message: '确认此笔收款？', confirmText: '确认收款' });
  if (ok === null) return;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/${id}/verify`);
    if (res.success) { ElMessage.success('收款已确认'); loadData(); }
    else { ElMessage.error(res.message || '确认失败'); }
  } catch (e) { /* axios interceptor handles API errors */ }
}

async function handleReject(id: string) {
  const reason = await confirmRef.value!.ask({
    title: '驳回', message: '驳回后该笔收款不再计入已收金额。', confirmText: '驳回', danger: true,
    input: { label: '驳回原因', placeholder: '请输入驳回原因', required: true, type: 'textarea' },
  });
  if (reason === null) return;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/${id}/reject`, { reason });
    if (res.success) { ElMessage.success('已驳回'); loadData(); }
    else { ElMessage.error(res.message || '驳回失败'); }
  } catch (e) { /* axios interceptor handles API errors */ }
}

// 录入收款弹窗
const recordDialogVisible = ref(false);
const recordForm = ref({
  salesOrderId: '', amount: 0, paymentMethod: 'BANK_TRANSFER',
  paymentDate: '', paymentReference: '', remark: ''
});
const submitting = ref(false);

// E-FP-3 (fool-proof Rule 1): 选定 SO 后计算未收金额, 超限红字 + 禁用提交, 并在弹窗里
// 显示 "订单总额 / 已收 / 可收" 上下文 (仿 invoices/list.vue 开票防呆).
const selectedSO = computed<SalesOrderOption | null>(() =>
  salesOrderOptions.value.find(o => String(o.id) === recordForm.value.salesOrderId) || null
);
const remainingAmount = computed<number>(() => salesOrderRemainingAmount(selectedSO.value));
const recordOverLimit = computed<boolean>(() =>
  Number(recordForm.value.amount || 0) > remainingAmount.value + 0.001
);
// 选 SO → 预填收款金额为可收余额 (用户可改). 仅当当前金额为 0 时预填, 不覆盖用户已输.
function onSalesOrderSelect(v: unknown) {
  recordForm.value.salesOrderId = String(v ?? '');
  if (!recordForm.value.amount || recordForm.value.amount === 0) {
    recordForm.value.amount = remainingAmount.value;
  }
}

// 防呆: 关弹窗前若表单已被改动 (相对打开时的快照) 二次确认
const recordFormSnapshot = ref('');
function openRecordDialog() {
  recordFormSnapshot.value = JSON.stringify(recordForm.value);
  recordDialogVisible.value = true;
}
function recordFormIsDirty(): boolean {
  return JSON.stringify(recordForm.value) !== recordFormSnapshot.value;
}
async function requestCloseRecordDialog() {
  if (recordFormIsDirty()) {
    const ok = await confirmRef.value!.ask({ title: '提示', message: '有未保存内容，确认关闭？', confirmText: '确认关闭', danger: true });
    if (ok === null) return;
  }
  recordDialogVisible.value = false;
}
function onRecordOpenChange(next: boolean) {
  if (next) recordDialogVisible.value = true;
  else void requestCloseRecordDialog();
}

async function handleRecordSubmit() {
  // 防呆 Rule 4 defense-in-depth: 同一 JS tick 内快速双击, :disabled 响应式更新来不及生效,
  // 顶部同步 guard 兜底 (后端 409 幂等是最后一道防线, 这里省一次无谓请求)
  if (submitting.value) return;
  if (!recordForm.value.salesOrderId || !recordForm.value.amount) {
    ElMessage.warning('请选择销售订单并填写收款金额'); return;
  }
  if (recordOverLimit.value) {
    // sticky (duration:0 + showClose): 绕开 UI disable 的最后防线, 别让消息一闪而过.
    ElMessage({
      type: 'error',
      duration: 0,
      showClose: true,
      message: `收款金额超过可收余额 ¥${remainingAmount.value.toFixed(2)}, 请调低`,
    });
    return;
  }
  submitting.value = true;
  try {
    const res = await post(`/${factoryId.value}/finance/payments/record`, recordForm.value);
    if (res.success) {
      ElMessage.success('收款记录已创建');
      recordDialogVisible.value = false;
      loadData();
    } else { ElMessage.error(res.message || '创建失败'); }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { submitting.value = false; }
}

const columns = computed<CsTableColumn<TableRow>[]>(() => {
  const cols: CsTableColumn<TableRow>[] = [
    { accessorKey: 'paymentNumber', header: '收款编号' },
    { accessorKey: 'customerName', header: '客户' },
  ];
  // 金额列: 无查看价格权限整列去掉 (DOM 里既没有列头也没有数值, 不显示 0)
  if (canViewPrice.value) cols.push({ id: 'amount', header: '收款金额', meta: { class: { th: 'text-right', td: 'text-right' } } });
  cols.push(
    { id: 'paymentMethod', header: '方式' },
    { accessorKey: 'paymentDate', header: '收款日期' },
    { id: 'paymentReference', header: '流水号' },
    { id: 'receiptUrl', header: '凭证' },
    { id: 'status', header: '状态' },
    // 附件: 文件=转账记录/凭证, 图片=银行回单截图, 合同=关联销售/采购合同 (原列头 tooltip 的说明)
    { id: 'attachments', header: '附件(文件/图片/合同)' },
  );
  if (canWrite.value) cols.push({ id: 'actions', header: '操作' });
  return cols;
});
</script>

<template>
  <div class="page" data-testid="finance-payments-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>收款管理</h2>
        <span class="count">共 {{ pagination.total }} 笔</span>
        <div class="head-actions">
          <UButton v-if="canWrite && canViewPrice" icon="i-lucide-plus" data-testid="payment-create" @click="openRecordDialog">录入收款</UButton>
        </div>
      </header>

      <div class="filter-bar">
        <UInput v-model="searchKeyword" placeholder="搜索 客户/收款单号/发票号" class="w-56" data-testid="payment-keyword" @keyup.enter="handleSearch" />
        <USelect v-model="statusFilter" :items="STATUS_ITEMS" class="w-36" data-testid="payment-status-filter" @update:model-value="handleFilterChange" />
        <USelect v-model="paymentMethodFilter" :items="METHOD_ITEMS" class="w-40" data-testid="payment-method-filter" @update:model-value="handleFilterChange" />
        <UButton data-testid="payment-search" @click="handleSearch">搜索</UButton>
        <UButton color="neutral" variant="outline" @click="handleReset">重置</UButton>
      </div>

      <UAlert v-if="loadFailed" color="error" variant="soft" icon="i-lucide-circle-alert" class="mx-5 mb-3" data-testid="payment-load-error"
        title="收款列表暂时没取到，请点「搜索」重试。" />

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="payment-table">
        <template #empty>暂无收款记录</template>
        <template #amount-cell="{ row }"><span class="num">{{ formatAmount(row.original.amount as number | null) }}</span></template>
        <template #paymentMethod-cell="{ row }">{{ methodText(row.original.paymentMethod) }}</template>
        <template #paymentReference-cell="{ row }">{{ row.original.paymentReference || '—' }}</template>
        <template #receiptUrl-cell="{ row }">
          <a v-if="row.original.receiptUrl" :href="String(row.original.receiptUrl)" target="_blank" class="link">查看</a>
          <span v-else>—</span>
        </template>
        <template #status-cell="{ row }">
          <UBadge :color="financeStatusColor(row.original.status)" variant="soft">{{ statusText(row.original.status) }}</UBadge>
        </template>
        <template #attachments-cell="{ row }"><LinkChipCell :counts="linkCountsFor(String(row.original.id))" /></template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton v-if="row.original.status === 'PENDING'" color="success" variant="link" size="xs" data-testid="payment-verify" @click="handleVerify(String(row.original.id))">确认</UButton>
            <UButton v-if="row.original.status === 'PENDING'" color="error" variant="link" size="xs" data-testid="payment-reject" @click="handleReject(String(row.original.id))">驳回</UButton>
          </div>
        </template>
      </UTable>

      <footer v-if="pagination.total > pagination.size" class="pager">
        <UPagination :page="pagination.page + 1" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" @update:page="onPageUpdate" />
      </footer>
    </section>

    <!-- 录入收款弹窗 (E-FP-3 防呆 R1 retrofit) -->
    <UModal
      :open="recordDialogVisible"
      :title="selectedSO ? `录入收款 — ${selectedSO.customerName || '客户未知'} (${selectedSO.orderNumber})` : '录入收款'"
      description="录入一笔客户收款"
      :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }"
      @update:open="onRecordOpenChange"
    >
      <template #body>
        <div class="form">
          <UFormField label="销售订单" required>
            <USelectMenu
              :model-value="recordForm.salesOrderId || undefined"
              value-key="value"
              :items="salesOrderItems"
              placeholder="选择已确认的销售订单"
              class="w-full"
              data-testid="payment-sales-order"
              @update:model-value="onSalesOrderSelect"
            >
              <template #empty>暂无已确认的销售订单</template>
            </USelectMenu>
          </UFormField>
          <!-- E-FP-3 (fool-proof Rule 1): 显式列出 订单总额 / 已收 / 可收. paidAmount 由 SalesOrder 实体直接序列化。 -->
          <UAlert v-if="canViewPrice && selectedSO" color="info" variant="soft" icon="i-lucide-info" data-testid="payment-so-summary">
            <template #title>
              应收总额（含税）<b>¥{{ salesOrderReceivableAmount(selectedSO).toFixed(2) }}</b>
              · 已收 <b>¥{{ Number(selectedSO.paidAmount || 0).toFixed(2) }}</b>
              · <span :class="remainingAmount > 0 ? 'ok' : 'bad'">可收 <b>¥{{ remainingAmount.toFixed(2) }}</b></span>
            </template>
            <template v-if="remainingAmount === 0" #description>该订单已全额收款, 无需再次录入</template>
          </UAlert>
          <UFormField v-if="canViewPrice" label="收款金额" required :error="recordOverLimit ? `超过可收余额 ¥${remainingAmount.toFixed(2)}, 请调低` : undefined">
            <!-- 不绑 :max: 输入框会静默 clamp 回上限, 令超限判断恒为 false (2026-07-04 修)。
                 超限判断完全交给 recordOverLimit, 保证原始值可见并真实触发红字 + 禁用提交。 -->
            <UInputNumber v-model="recordForm.amount" :min="0" :step-snapping="false" :increment="false" :decrement="false" :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }" class="w-full" data-testid="payment-amount" />
          </UFormField>
          <UFormField label="支付方式"><USelect v-model="recordForm.paymentMethod" :items="METHOD_FORM_ITEMS" class="w-full" /></UFormField>
          <UFormField label="收款日期"><UInput v-model="recordForm.paymentDate" type="date" class="w-48" /></UFormField>
          <UFormField label="流水号"><UInput v-model="recordForm.paymentReference" placeholder="银行流水号/支付凭证号" class="w-full" /></UFormField>
          <UFormField label="备注"><UTextarea v-model="recordForm.remark" :rows="2" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="requestCloseRecordDialog">取消</UButton>
        <UButton
          :loading="submitting"
          :disabled="!recordForm.salesOrderId || !canViewPrice || recordOverLimit || (!!selectedSO && remainingAmount === 0)"
          data-testid="payment-submit"
          @click="handleRecordSubmit"
        >提交</UButton>
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
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.filter-bar { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); padding: var(--cs-space-3) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-3) var(--cs-space-5); border-top: 1px solid var(--cs-border); }
.form { display: grid; gap: var(--cs-space-3); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.num { font-variant-numeric: tabular-nums; }
.link { color: var(--cs-primary); }
.ok { color: var(--cs-success); }
.bad { color: var(--cs-danger); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
