<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useBusinessMode } from '@/composables/useBusinessMode';
import request, { get, post } from '@/api/request';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowLeft, Download, Edit, InfoFilled } from '@element-plus/icons-vue';
import { handleCatchError } from '@/utils/errorToast';
import { formatAmount } from '@/utils/tableFormatters';
import NotFoundEmpty from '@/components/common/NotFoundEmpty.vue';
import DocumentTraceDrawer from '@/components/DocumentTraceDrawer.vue';
import { getPurchaseOrderDocumentTrace } from '@/api/documentTrace';
import AttachmentList from '@/components/attachment/AttachmentList.vue';
import AttachmentDropZone from '@/components/attachment/AttachmentDropZone.vue';
import type { TableRow } from '@/types/api';
import {
  APPROVAL_INSTANCE_STATUS_LABELS,
  PURCHASE_ORDER_INVOICE_STATUS_LABELS,
  SETTLEMENT_TYPE_LABELS,
  enumLabel,
} from '@/utils/enumDisplay';
import { canonicalUnitCode, formatPriceUnit, formatUnitPrice, pricingAmountPreview } from '@/utils/unitPricing';
import { canDownloadSupplyOrder } from './purchaseOrderActionIa';
import { purchaseTaxIncludedUnitPrice } from './purchaseOrderTaxInclusivePricing';
// M-10 (2026-08-20 手动测试台账): 只借用类型做完全映射防漂移检查 (Record 要求覆盖
// PurchaseOrderStatus 全部 11 个值), 详情页本身仍显示精确状态、不做归类, 所以不引入
// 归类相关的常量/函数, 也不改这份 statusMap 自带的 financeReviewedBy 特判逻辑。
import type { PurchaseOrderStatusValue } from '@/constants/purchaseOrderStatus';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const { label } = useBusinessMode();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('procurement'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const orderId = computed(() => route.params.id as string);

const loading = ref(false);
const submitting = ref(false);
const order = ref<TableRow | null>(null);
const notFound = ref(false);
const notFoundMessage = ref('');
const attachmentRefreshKey = ref(0);

// 客户 2026-07-30: 单据上看不到上下游、也没有「返回原单」——「严重影响工作效率」
// 采购单的「原单」= purchase_orders.sales_order_id 指向的销售订单 (直采单没有, 属正常)
const documentTraceVisible = ref(false);
function loadDocumentTrace() {
  const fid = factoryId.value;
  // 禁止降级: 没有工厂上下文就明确报错, 不用空串去调接口拿一个看不懂的 400
  if (!fid) return Promise.reject(new Error('当前账号未绑定工厂，无法查询单据追踪'));
  return getPurchaseOrderDocumentTrace(fid, orderId.value);
}

interface ApprovalProgress {
  hasInstance: boolean;
  instanceId?: string;
  status?: string;
  currentNodeNames?: string[];
  approverRoles?: string[];
  assignees?: string[];
  initiatedAt?: string;
  completedAt?: string;
  stayMinutes?: number;
  deepLink?: string;
  message?: string;
}
const approvalProgress = ref<ApprovalProgress>({ hasInstance: false });

// 三价对比
interface PriceComparison {
  materialTypeId: string;
  materialName: string;
  materialCode: string;
  unit: string;
  bomStandardPrice: number | null;
  movingAvgPrice: number | null;
  currentPrice: number | null;
  varianceFromBom: number | null;
  varianceFromAvg: number | null;
  priceAlert: boolean;
  bomProductNames: string;
  // Day 8-9 (三价对比 bug): 数据源缺失诊断 — 解释为何某价 null,
  // 帮 F006 仓管区分"数据 bug"和"业务正常空态"
  dataSourceHint: string | null;
}
const priceComparisons = ref<PriceComparison[]>([]);
const priceLoading = ref(false);
/**
 * 三价对比 collapse 的展开态 —— **默认展开**。
 *
 * owner 2026-08-20: 「这个事还是蛮重要的, 如果价格有误差…」。此前要点一下才展开,
 * 于是「-20.3% 异常」那种标红行默认是藏着的 —— 而它正是财务在这一页要做的判断。
 * ⛔ 不要改回默认折叠。
 */
const priceCollapseActive = ref<string[]>(['price']);
// Day 8-9 三价对比 bug 修复: 移除 priceLoaded 短路.
// 旧逻辑: 一次加载后 priceLoaded=true, 用户再展开 collapse 不重拉, 导致新建采购单
// → 入库 → 回此页, 用户看到旧三价对比 (空 / stale). 新逻辑: 每次展开都重拉,
// 保证用户看到的就是当前后端真实状态. 无明显性能影响 (查询走 BOM + raw_material
// 两表, 已有索引).

// M-10: 原来这里只有 10 项, 漏了 CLOSED (11 个后端状态里唯一缺的一个) —— 落到这个
// 状态的单据在详情页会走 enumLabel() 兜底, 没有专属样式。类型标注改成
// Record<PurchaseOrderStatusValue, ...> (完全映射) 后, 以后后端再加状态、这里漏掉
// 映射会直接编译报错, 不会再静默兜底。
const statusMap: Record<PurchaseOrderStatusValue, { text: string; type: string }> = {
  DRAFT: { text: '草稿', type: 'info' },
  SUBMITTED: { text: '已提交', type: 'warning' },
  WORKFLOW_RUNNING: { text: '审批中', type: 'warning' },
  APPROVED: { text: '已审批', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待财务审核', type: 'warning' },
  FINANCE_APPROVED: { text: '财务已审核', type: 'success' },
  FINANCE_REJECTED: { text: '财务驳回', type: 'danger' },
  PARTIAL_RECEIVED: { text: '部分收货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
  CLOSED: { text: '已关闭', type: 'info' },
};

/**
 * 采购状态文案 —— `FINANCE_APPROVED` 要分两种情况说。
 *
 * 后端只有在审批历史里**确实存在财务角色的通过动作**时才写 `financeReviewedBy`；
 * 工厂没配财务节点时那个字段是空的。这时单据确实可以往下走
 * (收货门禁认的就是它), 但**没有人做过财务审核** —— 再显示「财务已审核」就是在骗人。
 */
function purchaseStatusText(o: { status?: string; financeReviewedBy?: number | null } | null): string {
  if (!o?.status) return '';
  if (o.status === 'FINANCE_APPROVED' && !o.financeReviewedBy) return '无需财务审核（未设置审批节点）';
  return statusMap[o.status as PurchaseOrderStatusValue]?.text || enumLabel(o.status);
}

function purchaseStatusType(o: { status?: string; financeReviewedBy?: number | null } | null): string {
  if (!o?.status) return 'info';
  if (o.status === 'FINANCE_APPROVED' && !o.financeReviewedBy) return 'info';
  return statusMap[o.status as PurchaseOrderStatusValue]?.type || 'info';
}

/**
 * 收货进度文案 —— owner 2026-08-20 明确这一栏财务要看 (关系到能不能付款)。
 *
 * 🔴 后端**没有** `receiptStatus` 这个字段。全后端 `grep -rn "receiptStatus\|receipt_status"
 * --include=*.java` 命中 **0 条** (同一把尺子 grep `invoiceStatus` 命中 6 个文件, 证明
 * 仪器是活的)。此前模板里写的 `order.receiptStatus ? … : …` 那一支是永远到不了的死代码,
 * 真正在起作用的是它的 else —— 而 else 数的是**本单收货记录数组的长度**。
 *
 * 那个数组现在随「本单收货记录」区块一起去掉了 (财务不需要看), 所以这里换成权威读数:
 *   - `order.fullyReceived` —— 后端 `PurchaseOrder.isFullyReceived()` 这个 @Transient
 *     getter 被 Jackson 序列化出来的布尔值。**「是否全部到货」的判定留在后端一处**,
 *     ⛔ 前端不重算 (重算就是同一个口径的第二份, 必漂)。
 *   - 「一件没收 / 收了一部分」这个区分后端没有表达, 由 items[].receivedQuantity 数出来。
 *
 * ⛔ 拿不到 `fullyReceived` 时返回 '-' 而不是猜一个状态: 读不到就显示读不到。
 */
function receiptStateText(o: TableRow | null): string {
  const items = Array.isArray(o?.items) ? (o.items as TableRow[]) : [];
  if (!items.length) return '-';
  if (o?.fullyReceived === true) return '已全部到货';
  if (o?.fullyReceived !== false) return '-';
  const receivedLines = items.filter((item) => Number(item.receivedQuantity || 0) > 0).length;
  return receivedLines === 0 ? '尚未收货' : `部分到货（${receivedLines}/${items.length} 项已收）`;
}

function purchaseLineAmount(row: TableRow) {
  return pricingAmountPreview({
    quantity: row.quantity as number,
    unit: String(row.unit || ''),
    quantityUnit: String(row.quantityUnit || row.unit || ''),
    unitPrice: row.unitPrice as number,
    priceUnit: String(row.priceUnit || row.unit || ''),
    lineAmount: row.lineAmount as number,
    convertedPricingQuantity: row.convertedPricingQuantity as number,
  });
}

function goEditDraft() {
  router.push({ path: '/procurement/orders', query: { edit: orderId.value } });
}

onMounted(() => {
  loadOrder();
  loadApprovalProgress();
  // owner 2026-08-20: 三价对比「这个事还是蛮重要的, 如果价格有误差…」——
  // 默认展开, 所以数据要在挂载时就拉, 不能再等 collapse 的 @change 事件。
  // 仍然受 canViewPrice 约束: 没有看价权限的人整块 collapse 都不渲染。
  if (canViewPrice.value) loadPriceComparison();
});

async function loadOrder() {
  if (!factoryId.value || !orderId.value) return;
  loading.value = true;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}`);
    if (res.success) order.value = res.data;
  } catch (err: any) {
    const status = err?.status ?? err?.response?.status;
    const code = err?.code ?? err?.response?.data?.code;
    if (status === 404 || status === 403 || code === 'NOT_FOUND' || code === 'FORBIDDEN') {
      notFound.value = true;
      notFoundMessage.value = err?.message || err?.response?.data?.message || '记录不存在';
    }
    // axios interceptor shows toast already (Bug #319 fix), component doesn't add fallback
  }
  finally { loading.value = false; }
}

function goFinanceDetails(): void {
  router.push({
    path: '/finance/ar-ap',
    query: {
      direction: 'AP',
      purchaseOrderId: orderId.value,
    },
  });
}

async function loadApprovalProgress() {
  if (!factoryId.value || !orderId.value) return;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}/approval-progress`);
    if (res.success) approvalProgress.value = res.data || { hasInstance: false };
  } catch {
    approvalProgress.value = { hasInstance: false, message: '审批进度暂不可用' };
  }
}

function goApprovalProgress(): void {
  router.push({
    path: '/workflow/my-created',
    query: approvalProgress.value.instanceId
      ? { instanceId: approvalProgress.value.instanceId }
      : { businessEntityType: 'PURCHASE_ORDER', businessEntityId: orderId.value },
  });
}

async function handleAction(action: string) {
  if (submitting.value) return;
  const map: Record<string, { label: string; url: string }> = {
    submit: { label: '提交', url: `/${factoryId.value}/purchase/orders/${orderId.value}/submit` },
    cancel: { label: '取消', url: `/${factoryId.value}/purchase/orders/${orderId.value}/cancel` },
  };
  const a = map[action];
  if (!a) return;
  try {
    await ElMessageBox.confirm(`确认${a.label}？`, '操作确认');
  } catch { return; }
  submitting.value = true;
  try {
    const res = await post(a.url);
    if (res.success) {
      ElMessage.success(`${a.label}成功`);
      await Promise.all([loadOrder(), loadApprovalProgress()]);
    }
    else { ElMessage.error(res.message || `${a.label}失败，请重试`); }
  } catch (e) { handleCatchError(e, `${a.label}失败，请检查网络`); }
  finally { submitting.value = false; }
}

function goWarehouseReceive(): void {
  router.push({
    path: '/warehouse/materials',
    query: {
      view: 'receiving',
      purchaseOrderId: orderId.value,
      orderNo: String(order.value?.orderNumber || ''),
    },
  });
}

async function loadPriceComparison() {
  // Day 8-9 三价对比 bug fix: 不再用 priceLoaded 短路, 每次展开都重拉.
  // 防御 priceLoading reentrancy 即可.
  if (priceLoading.value || !factoryId.value || !orderId.value) return;
  priceLoading.value = true;
  try {
    const res = await get(`/${factoryId.value}/purchase/orders/${orderId.value}/price-comparison`);
    if (res.success) {
      priceComparisons.value = Array.isArray(res.data) ? res.data : [];
    }
  } catch { /* axios interceptor already displayed error toast */ }
  finally { priceLoading.value = false; }
}

// Day 8-9 三价对比 bug fix: 计算"是否多数行缺失数据源" — 用于显示 banner 解释,
// 区分"业务正常 (新原料/未入库)"和"系统 bug".
const priceDataSourceHintBanner = computed(() => {
  if (priceComparisons.value.length === 0) return null;
  const missing = priceComparisons.value.filter(p => p.dataSourceHint != null).length;
  if (missing === 0) return null;
  // 至少 1 行有 hint → 显示通用解释 banner
  return `三价对比基于"BOM 标准价 + 历次入库的移动均价". 新原料 / 尚未入库 / 未配 BOM 时部分对比项为空 — 这是业务正常状态, 不是数据 bug. ${missing}/${priceComparisons.value.length} 行有诊断信息, 鼠标悬停查看.`;
});

function formatVariance(val: number | null): string {
  if (val == null) return '-';
  const sign = val > 0 ? '+' : '';
  return `${sign}${val.toFixed(1)}%`;
}

function varianceClass(val: number | null): string {
  if (val == null) return '';
  return val > 0 ? 'variance-up' : val < 0 ? 'variance-down' : '';
}

function formatTaxRate(rate: unknown): string {
  if (rate == null || rate === '') return '-';
  const numeric = Number(rate);
  if (!Number.isFinite(numeric)) return '-';
  const percent = numeric <= 1 ? numeric * 100 : numeric;
  return `${percent.toFixed(2).replace(/\.?0+$/, '')}%`;
}

function priceRowClassName({ row }: { row: PriceComparison }): string {
  return row.priceAlert ? 'price-alert-row' : '';
}

/**
 * P0/N3 (六扇门): 下载对外供货单 PDF — 后端 `external=true` 会完全移除价格列。
 *
 * 🔴 2026-08-20 owner: 原来这里还有一个内部版按钮 (`external=false`), 已移除。
 *    owner 实测「点了任何图标没用, 下载下来是一个我打不开的东西」。
 *
 *    **坏因 (已定位到行, 不是「本来就该没有」)**:
 *    `PurchaseOrderPdfServiceImpl.java:205-219` 内部版分支里
 *      `grandTotal = grandTotal.add(lineAmount);`
 *    而 `PurchaseOrderItem.getLineAmount()` 在 `unitPrice == null || quantity == null`
 *    时**故意返回 null** (实体注释: 返回 null 而不是 ZERO, 避免把缺价泄露成"免费"),
 *    且 `unit_price` 是 nullable 列 ⇒ 只要有一行没填单价就 NPE。
 *    NPE 被 `catch (Exception e)` 吞成 `BusinessException(500, "PDF 生成失败: null")`。
 *    对外版免疫: 它强制 `hidePrice=true`, 那一行根本不执行。
 *    ⚠️ 触发条件恰好只覆盖内部版按钮的可见人群 —— 该按钮 `v-if="canViewPrice"`,
 *    而只有有看价权限的人才会走到累加那一行。
 *
 *    ⛔ 按钮虽然移除了, 后端那个 NPE 仍在。要恢复内部版必须先修后端那一行
 *    (null 要么跳过累加、要么让整单显式报"存在未定价行"), ⛔ 不要只把按钮加回来。
 */
const pdfDownloading = ref(false);
async function handleDownloadSupplyOrderPdf() {
  if (!factoryId.value || !orderId.value || pdfDownloading.value) return;
  pdfDownloading.value = true;
  try {
    const response = await request.get(
      `/${factoryId.value}/purchase/orders/${orderId.value}/pdf`,
      {
        params: { external: true },
        responseType: 'blob',
      }
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    const orderNum = order.value?.orderNumber || orderId.value;
    link.download = `供货单_对外_${orderNum}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success('对外供货单下载成功');
  } catch (e) {
    // axios interceptor 已经 toast — 但 blob 响应 error 跳过 interceptor json parse, 兜底
    console.error('[PDF 下载失败]', e);
    ElMessage({
      type: 'error',
      message: 'PDF 下载失败,请稍后重试',
      duration: 0,
      showClose: true,
    });
  } finally {
    pdfDownloading.value = false;
  }
}

</script>

<template>
  <NotFoundEmpty v-if="notFound"
    :description="notFoundMessage"
    return-path="/procurement/orders" />
  <div v-else class="page-wrapper" v-loading="loading">
    <el-card class="page-card" shadow="never">
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <el-button :icon="ArrowLeft" @click="router.push('/procurement/orders')">返回</el-button>
            <span class="page-title">{{ label('purchaseOrder') }}详情</span>
            <!-- 🔴 2026-08-20: 这一页**采购也在用** (workflow/my-created「我发起的」、
                 requisitions/detail「查看采购单」、sales/orders/detail 都跳这里, 且路由
                 只有一条 `procurement/orders/:id`)。所以采购动作 ⛔ 不许直接删,
                 按 owner 的第二条要求「按角色/权限区分」—— 统一挂 canWrite:
                 procurement_manager 是 `procurement: 'rw'` ⇒ 看得见;
                 finance_manager / cashier 是 `procurement: 'r'` ⇒ 看不见。
                 单据追溯查的是上下游单据链 (下游收货/入库单是审批后才产生的), 归采购。 -->
            <el-button v-if="order && canWrite" plain @click="documentTraceVisible = true">单据追溯</el-button>
            <el-tag v-if="order" :type="purchaseStatusType(order)" size="large">
              {{ purchaseStatusText(order) }}
            </el-tag>
          </div>
          <div class="header-right" v-if="order">
            <!-- N3: 对外供货单给供应商, 后端 external=true 会完全移除价格列. -->
            <!-- M-3 (2026-08-20): DRAFT 还没提交/审批, 不能先把对外供货单发出去 -->
            <!-- 时间轴判据: 「把供货单发给供应商」是**审批通过之后**的履约动作 ——
                 `canDownloadSupplyOrder` 挡掉 DRAFT 正是因为这个。财务在审批时不做这件事,
                 所以同样收进 canWrite (采购仍然照旧看得到, 行为一个字没改)。 -->
            <el-button v-if="canWrite && canDownloadSupplyOrder(order.status)" type="primary" :icon="Download" :loading="pdfDownloading" @click="handleDownloadSupplyOrderPdf()">对外供货单</el-button>
            <template v-if="canWrite">
            <el-button v-if="order.status === 'DRAFT'" :icon="Edit" @click="goEditDraft">编辑草稿</el-button>
            <el-button v-if="order.status === 'DRAFT'" type="warning" :loading="submitting" @click="handleAction('submit')">提交审批</el-button>
            <el-button v-if="['DRAFT','SUBMITTED'].includes(order.status)" type="danger" :disabled="submitting" @click="handleAction('cancel')">取消</el-button>
            </template>
            <el-button v-if="order.status !== 'DRAFT'" plain @click="goApprovalProgress">查看审批进度 / 前往 OA</el-button>
          </div>
        </div>
      </template>

      <template v-if="order">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="订单编号">{{ order.orderNumber }}</el-descriptions-item>
          <el-descriptions-item :label="label('supplier')">{{ order.supplierName || order.supplier?.name || order.supplierId }}</el-descriptions-item>
          <!-- owner 2026-08-20: 「采购类型」「期望交货」与付款判断关系弱 ⇒ 去掉。
               ⚠️ 两个字段后端照发 (order.purchaseType / order.expectedDeliveryDate),
               只是这一页不显示 —— ⛔ 不要理解成后端没有它们。 -->
          <el-descriptions-item label="下单日期">{{ order.orderDate }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="总金额">{{ formatAmount(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="税额">{{ formatAmount(order.taxAmount) }}</el-descriptions-item>
          <!-- OA 状态的码来自 ApprovalWorkflowInstance.InstanceStatus (RUNNING/APPROVED/…),
               ⛔ 不能用全局表兜底 —— RUNNING 在审批语境是「审批中」, 别处会是「运行中」。 -->
          <el-descriptions-item label="OA 状态">{{ approvalProgress.hasInstance ? enumLabel(approvalProgress.status, APPROVAL_INSTANCE_STATUS_LABELS) : (approvalProgress.message || '尚未发起') }}</el-descriptions-item>
          <!-- owner 2026-08-20: 「当前审批节点」「待处理角色/人员」「节点停留」三格去掉 ——
               头上已经有「查看审批进度 / 前往 OA」这个入口, 流程走到哪一步去 OA 看。
               ⚠️ 保留「OA 状态」和「提交时间」: 前者是这一单批没批的结论 (审批当下的事实),
               后者是它在财务手上压了多久的起点, 两者都不是「审批之后才产生」的。
               🔴 `ApprovalProgress` 接口上 currentNodeNames / approverRoles / assignees /
               stayMinutes 四个字段**故意保留** —— 它们是 approval-progress 接口的真实响应
               形状, 删掉会让下一个人以为后端不下发。 -->
          <el-descriptions-item label="提交时间">{{ approvalProgress.initiatedAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="合同号">{{ order.contractNumber || '-' }}</el-descriptions-item>
          <!-- 结算方式的中文原来是写死在这一行的行内字面量 (与后端 SettlementType 各一份),
               已收进 enumDisplay 的 SETTLEMENT_TYPE_LABELS 并由 EnumDisplayCoverage 钉住。 -->
          <el-descriptions-item label="结算方式">
            <span v-if="order.settlementType">{{ enumLabel(order.settlementType, SETTLEMENT_TYPE_LABELS) }}</span>
            <span v-else>-</span>
          </el-descriptions-item>
          <!-- owner 2026-08-20: 「开票提醒」去掉 —— 它是提醒采购去催票的天数配置,
               与「这一单批不批 / 付不付款」无关。⚠️ 字段 order.invoiceReminderDays 仍在后端,
               工厂默认值也照旧生效, 只是不摆在这一页。 -->
          <el-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-heading finance-heading">
          <h3>采购履约与财务状态（只读）</h3>
          <el-button plain @click="goFinanceDetails">查看财务详情</el-button>
        </div>
        <!-- 🔴 2026-08-20 实测: 这一块 8 个字段里, 后端详情接口 `GET /purchase/orders/{id}`
             只下发 `invoiceStatus` 一个。该接口返回的是 JPA 实体 `PurchaseOrder` 本身
             (PurchaseController.java:161-171, 无 DTO 无 mapper), 所以"响应里有什么"
             == "实体上有什么"。逐个核对 PurchaseOrder.java 的结果:
               payableAmount / paidAmount / unpaidAmount / paymentDueDate / dueDate
               / paymentStatus  —— 实体上**没有**这些列;
               receiptStatus    —— 全后端 grep 命中 0 条, 这个标识符根本不存在。
             真实数据在 `ArApTransaction` (settledAmount / outstandingAmount /
             paymentStatus / dueDate), 要靠上面那个「查看财务详情」按钮跳过去看。
             ⛔ 在补上后端字段之前, 这里不许拿别的数顶替 —— 显示不出来就显示不出来,
             兜底成 0 会让财务照着一个假的应付金额去付款。 -->
        <el-descriptions :column="4" border>
          <el-descriptions-item label="采购状态">{{ purchaseStatusText(order) }}</el-descriptions-item>
          <el-descriptions-item label="收货状态">{{ receiptStateText(order) }}</el-descriptions-item>
          <el-descriptions-item label="发票状态">{{ order.invoiceStatus ? enumLabel(order.invoiceStatus, PURCHASE_ORDER_INVOICE_STATUS_LABELS) : '未归集' }}</el-descriptions-item>
          <!-- 「付款状态」暂时隐藏 (owner 2026-08-20)。
               🔴 后端详情接口返回的是 JPA 实体 `PurchaseOrder` 本身, 而实体上**没有**
               `paymentStatus` 这一列 ⇒ 这一格恒为「未归集」, 从来没显示过真实付款状态。
               恒定的假读数比没有更糟: 财务会照着「未归集」判断这单还没付。
               ⛔ 不要写成「这个字段不需要」—— 财务**需要**它, 是后端还没下发。
               ⚙️ 恢复条件: 后端把 `ArApTransaction.paymentStatus` 归集到采购单详情响应
               (与 payableAmount / paidAmount / unpaidAmount 同一批), 那时把下面这行放回来:
                 <el-descriptions-item label="付款状态">{{ order.paymentStatus ? enumLabel(order.paymentStatus) : '未归集' }}</el-descriptions-item>
               在那之前, 付款情况看右上角「查看财务详情」。 -->
          <el-descriptions-item v-if="canViewPrice" label="应付金额">{{ order.payableAmount == null ? '-' : formatAmount(order.payableAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="已付金额">{{ order.paidAmount == null ? '-' : formatAmount(order.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewPrice" label="未付金额">{{ order.unpaidAmount == null ? '-' : formatAmount(order.unpaidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="付款到期日">{{ order.paymentDueDate || order.dueDate || '-' }}</el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">{{ label('rawMaterial') }}明细</h3>
        <!-- T3-6 fix (issue #523): widen 规格 + 原料名称 columns + scroll-x fallback to prevent 字段挤压 / tooltip overlay 盖住邻列. -->
        <el-table :data="order.items || []" border stripe style="width: 100%" :scrollbar-always-on="true">
          <el-table-column prop="materialName" :label="label('rawMaterial')" min-width="180" show-overflow-tooltip />
          <!-- owner 2026-08-20: 「每个原料都应该有自己的规格, 规格里说清 kg 怎么换算成
               箱/件, 不需要两列, **以规格为主**」⇒ 原来的「箱数」列已去掉。
               ⚠️ `specification` 是 purchase_order_items 上的**真实列** (length=200),
               但它**只在建单时从表单载荷写入**, 后端详情接口不做任何补全, 也**不会**
               回退到原料档案 (RawMaterialType 上根本没有 specification 这一列)。
               所以这里显示 '-' 是「建单时没填」, 不是没渲染 —— 要让它有值得在
               建单表单那一侧录, 或由后端补一条回退到原料档案的口径。 -->
          <el-table-column prop="specification" label="规格" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.specification || '-' }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="采购数量" width="110" align="right" />
          <el-table-column prop="unit" label="单位" width="80" align="center" />
          <el-table-column v-if="canViewPrice" prop="unitPrice" label="含税单价" width="120" align="right">
            <template #default="{ row }">
              {{ formatUnitPrice(purchaseTaxIncludedUnitPrice(row.unitPrice, row.taxRate)) }} {{ formatPriceUnit(canonicalUnitCode(row.priceUnit || row.unit)) }}
            </template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" prop="taxRate" label="税率" width="90" align="right">
            <template #default="{ row }">{{ formatTaxRate(row.taxRate) }}</template>
          </el-table-column>
          <!-- 🔴 这一页的读者是**财务**。owner 2026-08-20: 「财务需要看到的跟实际采购
               需要看的不一样…财务不需要看的东西我就不给他看」⇒ 逐行的收货进度列已去掉。
               ⚠️ 理由是**读者不需要**, ⛔ 不是「因为现在恒为 0 所以删」——
               将来这单收了货、那一列有值了, 它**同样**不该出现在这一页。
               订单级的收货进度仍然在上面「采购履约与财务状态」里 (财务判断能不能付款要用)。 -->
          <el-table-column v-if="canViewPrice" label="小计" width="130" align="right">
            <template #default="{ row }">
              <span v-if="purchaseLineAmount(row).amount != null">{{ formatAmount(purchaseLineAmount(row).amount) }}</span>
              <span v-else class="text-secondary">{{ purchaseLineAmount(row).message }}</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 默认展开 (v-model 初值 ['price']) —— 数据在 onMounted 就拉好了。
             @change 保留: 用户手动折叠再展开时重拉一次, 避免看到 stale 的对比。 -->
        <el-collapse v-if="canViewPrice" v-model="priceCollapseActive" style="margin: 20px 0 12px" @change="(val: string[]) => { if (val.includes('price')) loadPriceComparison(); }">
          <el-collapse-item title="三价对比分析" name="price">
            <div v-loading="priceLoading">
              <!-- Day 8-9 三价对比 bug fix: 数据源缺失时显示 banner 解释, 区分业务空态 vs bug -->
              <el-alert
                v-if="priceDataSourceHintBanner"
                type="info"
                :closable="false"
                show-icon
                style="margin-bottom: 12px"
                :title="priceDataSourceHintBanner"
              />
              <el-alert v-if="priceComparisons.some(p => p.priceAlert)" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
                存在价格偏差超过10%的原料，请关注标红行
              </el-alert>
              <el-table :data="priceComparisons" border stripe :row-class-name="priceRowClassName" size="small" v-if="priceComparisons.length">
                <el-table-column prop="materialName" label="原料名称" min-width="140" />
                <el-table-column label="BOM标准价" width="120" align="right">
                  <template #default="{ row }">{{ row.bomStandardPrice != null ? formatAmount(row.bomStandardPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="移动均价" width="120" align="right">
                  <template #default="{ row }">{{ row.movingAvgPrice != null ? formatAmount(row.movingAvgPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="当前采购价" width="120" align="right">
                  <template #default="{ row }">{{ row.currentPrice != null ? formatAmount(row.currentPrice) : '-' }}</template>
                </el-table-column>
                <el-table-column label="BOM偏差" width="100" align="center">
                  <template #default="{ row }">
                    <span :class="varianceClass(row.varianceFromBom)">{{ formatVariance(row.varianceFromBom) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="均价偏差" width="100" align="center">
                  <template #default="{ row }">
                    <span :class="varianceClass(row.varianceFromAvg)">{{ formatVariance(row.varianceFromAvg) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="预警" width="70" align="center">
                  <template #default="{ row }">
                    <el-tag v-if="row.priceAlert" type="danger" size="small">异常</el-tag>
                    <el-tag v-else type="success" size="small">正常</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="bomProductNames" label="关联产品" min-width="120" show-overflow-tooltip />
                <!-- Day 8-9 三价对比 bug fix: 数据源诊断列 (仅有 hint 行显示 ⓘ icon + tooltip) -->
                <el-table-column label="说明" width="60" align="center">
                  <template #default="{ row }">
                    <el-tooltip v-if="row.dataSourceHint" :content="row.dataSourceHint" placement="left">
                      <el-icon style="color: #409eff; cursor: help"><InfoFilled /></el-icon>
                    </el-tooltip>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
              </el-table>
              <!-- Issue #781: priceLoaded 短路已 Day 8-9 移除 (line 71-75 comment).
                   旧 template 仍引用 priceLoaded 是死引用 (undefined), 始终为 falsy
                   → empty 永不显示. 修法: 直接用 !priceLoading + length===0 判定. -->
              <el-empty v-else-if="!priceLoading && priceComparisons.length === 0" description="暂无三价对比数据" :image-size="60" />
            </div>
          </el-collapse-item>
        </el-collapse>

        <!-- 🔴 owner 2026-08-20 定的口径: **本单**的收货明细财务不需要看, 这一块整个去掉
             (它查的是 `receiving/receipts/by-order/{orderId}`, 单还在审批中时必然是空表 ——
             但删它的理由是「读者不需要」, ⛔ 不是「现在是空的」)。
             财务真正要的是**该原料过去收过几次、什么价** —— 配合上面的三价对比判断
             本单单价合不合理。owner: 「历史记录还是要看的」。

             ⛔ 后端目前**没有**「按原料查历史收货」的接口 (现有的只能按采购单查),
             所以这里只把位置留出来, ⛔ 不拿 by-order 那个接口硬凑一个假的"历史"——
             那会把「本单一次没收」显示成「这个原料从来没收过货」, 正好是最误导财务的读数。 -->
        <div class="section-heading">
          <h3>{{ label('rawMaterial') }}历史{{ label('receive') }}记录</h3>
          <el-button type="primary" plain @click="goWarehouseReceive">前往仓储收货任务</el-button>
        </div>
        <el-empty
          description="该原料的历史收货记录尚未接入 —— 后端还没有「按原料查历史收货」的接口。在补上之前这里不显示任何数据，以免与本单收货记录混淆。"
          :image-size="60"
        />

        <!-- 附件 (C-ATT-1) — 合同 / 报价 / 收货单 / 现场照片
             🔴 owner 2026-08-20: 附件**内容是审批依据, 要留**; 但**上传 / 删除是采购的动作**,
             财务视角下这一块应该是只读列表。
             ⇒ 列表 `:readonly="!canWrite"` 收掉删除按钮, 拖拽上传区整块 `v-if="canWrite"`。
             采购 (procurement: 'rw') 的体验一个字没变; 财务 (procurement: 'r') 只剩看。
             ⛔ 这只是收前端入口 —— 真正的写保护在后端, 不要把这行当成权限。 -->
        <h3 style="margin: 20px 0 12px">附件</h3>
        <AttachmentList
          entity-type="PURCHASE_ORDER"
          :entity-id="String(orderId)"
          :factory-id="factoryId"
          :refresh-key="attachmentRefreshKey"
          :readonly="!canWrite"
        />
        <div v-if="canWrite" style="margin-top: 12px">
          <AttachmentDropZone
            entity-type="PURCHASE_ORDER"
            :entity-id="String(orderId)"
            :factory-id="factoryId"
            business-tag="PURCHASE_DOC"
            @uploaded="attachmentRefreshKey++"
          />
        </div>
      </template>
    </el-card>

    <DocumentTraceDrawer
      v-model="documentTraceVisible"
      title="采购订单单据追踪"
      anchor-label="采购订单"
      :fetcher="loadDocumentTrace"
    />
  </div>
</template>

<style lang="scss" scoped>
.page-wrapper { height: 100%; width: 100%; display: flex; flex-direction: column; }
.page-card { flex: 1; display: flex; flex-direction: column;
  :deep(.el-card__header) { padding: 16px 20px; border-bottom: 1px solid #ebeef5; }
  :deep(.el-card__body) { flex: 1; padding: 20px; overflow-y: auto; }
}
.card-header { display: flex; justify-content: space-between; align-items: center;
  .header-left { display: flex; align-items: center; gap: 12px;
    .page-title { font-size: 16px; font-weight: 600; color: #303133; }
  }
  .header-right { display: flex; gap: 8px; }
}
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 20px 0 12px; }
.section-heading h3 { margin: 0; }
.finance-heading { margin-top: 20px; }
// 三价对比样式
:deep(.price-alert-row) {
  background-color: #fef0f0 !important;
  td { background-color: #fef0f0 !important; }
}
.variance-up { color: #f56c6c; font-weight: 600; }
.variance-down { color: #67c23a; font-weight: 600; }
</style>
