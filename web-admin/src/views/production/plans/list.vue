<script setup lang="ts">
import { CompatAlert, CompatDatePicker, CompatDescriptions, CompatDescriptionsItem, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import { ref, computed, onMounted, nextTick, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { pickSettlementEligibleWarehouses, summarizeStockByWarehouse } from './rawWarehousePicker';
import { applyPickedMaterialBatchToLine, rawConsumptionDisplayLabel } from './settlementRawBatchDisplay';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { useProductionPlanCustomerReadAccess } from './productionPlanCustomerReadAccess';
import type { ProductionPlanCustomerOption } from './productionPlanCustomerSelection';
import { get, post, put } from '@/api/request';
import { ElSelect } from 'element-plus';
import { pickPrefilled, readReadinessPrefill } from '@/views/factory-readiness/readinessPrefill';
import {
  DEFAULT_OVER_RECEIVE_RULE,
  overReceiveBlockedMessage,
  overReceiveExceeds,
  overReceiveHelp,
  overReceiveMax,
} from '@/utils/overReceiveRule';
import { Plus, Search, Refresh, VideoPause, CircleCheck, Download, ChatDotRound, Printer, Warning, ArrowDown } from '@element-plus/icons-vue';
import { formatDateTimeCell } from '@/utils/tableFormatters';
import { handleCatchError } from '@/utils/errorToast';
import { confirmDiscardIfDirty } from '@/utils/confirmDiscardChanges';
import {
  downloadImportTemplate,
  importProductionPlans,
  exportProductionPlans,
  getSupervisors,
  listAvailableWip,
  createSecondaryPlan,
  getReportModeDefault,
  getMaterialAdvisory,
  getProductionSettlement,
  recalculateSettledPlanCosts,
  confirmProductionWarehouseReceipt,
  getOverReceiveRule,
  type OverReceiveRule,
  clearProductionTransitLedger,
  interimSettle,
  repinPlanAuthority,
  requestReverseInterimSettle,
  approveReversalRequest,
  rejectReversalRequest,
  listReversalRequests,
  type InterimSettleReversalRequest,
  stopProduction,
  resolveWorkflowByOutputs,
  findPinnedWorkflow,
  getProductionDocumentTrace,
} from '@/api/productionPlan';
import type {
  ProductionOutputLine,
  ProductionPlanMaterialAdvisory,
  ProductionSettlementStatus,
  WipInventoryItem,
  WorkflowResolutionCandidate,
} from '@/api/productionPlan';
import { getProductWorkProcesses } from '@/api/processProduction';
import type { ProductWorkProcessItem } from '@/api/processProduction';
import { copyProductionPlan } from '@/api/orderCopy';
import CanvasDynamicFields from '@/components/canvas/CanvasDynamicFields.vue';
import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue';
import { PRODUCTION_PLAN_CONFIG } from '@/components/ai-entry/types';
import { WorkflowBar } from '@/components/workflow';
import { useWorkflowStats } from '@/composables/useWorkflowStats';
import { getBucketPrimaryStatus, getBucketLabel } from '@/types/workflow';
import type { TableRow } from '@/types/api';
import {
  ListColumnSelector,
  TableFooter,
  useBusinessTableColumns,
} from '@/components/list';
import { useListSummary } from '@/composables/useListSummary';
import { formatSummaryForAI } from '@/utils/aiSummaryContext';
import type { ListSummaryRequest } from '@/types/listSummary';
import { safePrint, printWorkOrderMulti } from '@/api/printApi';
import {
  getPlanStatusText,
  getPlanStatusType,
  planRowClassNameByStatus,
  planStatusClass,
} from './statusVisuals';
import {
  resolvePlanWorkflowCandidates,
  workflowCandidateBindingProductTypeId,
  workflowCandidateExtraOutputs,
  workflowCandidateOutputIds,
  workflowCandidateProcessSummary,
  workflowCandidateTopologyLabel,
  type PlanWorkflowResolutionMode,
} from './productionPlanWorkflowResolution';
import WorkflowRoutePreview from './components/WorkflowRoutePreview.vue';
import ProcessSheet from '../components/processSheet/ProcessSheet.vue';
import ProductionSummaryDialog from '../components/ProductionSummaryDialog.vue';
import PlanConcurrentReadiness from './components/PlanConcurrentReadiness.vue';
import PlanOutsourceProcesses from './components/PlanOutsourceProcesses.vue';
import {
  documentTraceTarget,
  traceDocumentLabel,
} from './documentTrace';
import type { ProductionDocumentTrace, ProductionTraceDocument } from '@/types/productionDocumentTrace';
import { currentAppScrollTop, returnContextQuery } from '@/utils/returnNavigation';
import { productionPlanAiGuard, findUniqueProductByName } from '@/utils/aiEntryGuards';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit } from '@/utils/unitPricing';
import { enumLabel, traceStatusLabel } from '@/utils/enumDisplay';
import {
  cancellationAudit,
  formatPlanActualQuantity,
  planSheetProductLabel,
  sourceOrderBusinessNumber,
  sourceOrderDisplay,
  sourceOrderTarget,
} from './productionPlanListPresentation';
import { RAW_WORKFLOW_OWNER_ANCHOR_LABEL, isRawWorkflowOwnerAnchorName } from '@/constants/rawWorkflowOwnerAnchor';
import {
  DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS,
  PRODUCTION_DOCUMENT_CHAPTERS,
  downloadProductionDocumentPack,
  type ProductionDocumentChapter,
} from './productionDocumentPack';
import {
  plannedQuantityForPayload,
  plannedQuantityRequired,
} from './productionPlanQuantity';
import { finishedGoodPlanOptions } from './productionPlanProductOptions';
import { useNativeDemoAdapters } from '@/demo/f006NativeBridge';
import { nativeDemoState } from '@/demo/f006NativeBridge';
import { createNativeProductionAdapters } from '@/demo/nativeProductionSteps';
import { productionPlanType, productionPlanTypeLabel } from './productionPlanType';
import {
  productionPlanCustomerLabel,
  resolveProductionPlanCustomerSelection,
  selectableProductionPlanCustomers,
  type ProductionPlanOutputOwnership,
} from './productionPlanCustomerSelection';
import {
  hasProductionMoreCommand,
  planEditBlockedReason,
  planEditMenuLabel,
  planEditScope,
  productionMoreCommands,
  repinAuthorityBlockedReason,
  type PlanEditScope,
} from './productionMoreActions';
import { classifySettlementFailure, isNotSettledPayload } from './settlementStatusFailure';
import { toDateString } from '@/utils/dateFormat';
import {
  finishedGoodsBatchCandidates,
  assignProcessOperatorTarget,
  PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP,
} from './assignProcessOperatorEntry';

const router = useRouter();
const route = useRoute();
const { goCreate } = useCreateAndReturn();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));
const canConfirmReceiptWrite = computed(() =>
  permissionStore.canWrite('warehouse')
  || permissionStore.canWrite('production')
  || permissionStore.canWrite('scheduling')
);
const canViewPrice = computed(() => permissionStore.canViewPrice);
useNativeDemoAdapters('production-plans-native-demo', createNativeProductionAdapters({
  canWrite: () => canWrite.value,
  canConfirmReceiptWrite: () => canConfirmReceiptWrite.value,
  canViewPrice: () => canViewPrice.value,
  preparePlan: async ({state,ui}) => {
    await ui.click('[data-testid="production-plan-open-create"]','打开原生新建计划');
    await loadSelectableSalesOrders();
    const soId=String(state.entities.salesOrder?.id||'');
    await ui.until(()=>selectableSalesOrders.value.some(row=>String(row.id)===soId),'本轮销售需求加载');
    await ui.fill('来源销售订单',()=>{planForm.value.sourceType='CUSTOMER_ORDER';planForm.value.sourceOrderId=soId;handleSalesOrderSelect(soId)});
    await ui.until(()=>planForm.value.sourceOrderItemIds.length>0&&hasActiveWorkflow.value&&planForm.value.skipProcessReporting===false&&Boolean(planForm.value.processName),'销售产品与逐工序工艺加载');
  },
  openProcessEntry: async ({state,ui}) => {
    const plan=await ui.read(`/production-plans/${state.entities.productionPlan?.id||''}`);
    const batch=await ui.read(`/processing/batches/${state.entities.productionBatch?.id||''}`);
    if(!plan||typeof plan!=='object'||Array.isArray(plan)||!batch||typeof batch!=='object'||Array.isArray(batch))throw new Error('本轮计划或批次无法回读');
    const actualPlan=plan as Record<string,unknown>, actualBatch=batch as Record<string,unknown>;
    if(String(actualPlan.id)!==String(state.entities.productionPlan?.id)||String(actualBatch.productionPlanId)!==String(actualPlan.id)||actualPlan.skipProcessReporting!==false)throw new Error('本轮计划与逐工序生产批次不一致');
    await ui.fill('打开本轮逐道报工',()=>showProcessEntry(actualPlan));
  },
}, ['production-plan-create', 'production-batch-create', 'process-report-1', 'process-report-2', 'process-report-3', 'process-report-4', 'process-report-5', 'process-report-6', 'production-settle', 'finished-goods-receive']));
const productionColumnMax = 6;
const {
  columns: productionColumnOptions,
  visibleKeys: productionVisibleColumnKeys,
  isVisible: isProductionColumnVisible,
  reset: resetProductionColumns,
} = useBusinessTableColumns({
  storageKey: 'cretas:production-plans:visible-columns:v1',
  columns: () => [
    { key: 'customer', label: t('production.plans_list.label_ad4af312dbc2') },
    { key: 'process', label: t('production.plans_list.label_648cd106a0b7') },
    { key: 'batchDate', label: t('production.plans_list.label_4ab95394a48e') },
    { key: 'plannedQuantity', label: t('production.plans_list.label_79c31e394ad1') },
    { key: 'actualQuantity', label: t('production.plans_list.label_dbf1981b4fdb') },
    { key: 'plannedDate', label: t('production.plans_list.label_011247215d19') },
    { key: 'inventoryStatus', label: t('production.plans_list.label_a54c13487c75') },
    { key: 'materialReference', label: t('production.plans_list.label_407a177f0d11') },
    { key: 'nextStep', label: t('production.plans_list.label_acfc4e74a650') },
    { key: 'workers', label: t('production.plans_list.label_7dd57ce90957') },
    { key: 'supervisor', label: t('production.plans_list.label_8f4fdba6fb72') },
    { key: 'source', label: t('production.plans_list.label_a488e93d69cc') },
    { key: 'reportMode', label: t('production.plans_list.label_32ac10e7e9ae') },
    { key: 'createdAt', label: t('production.plans_list.label_07ec86e0f1d4') },
  ],
  defaults: ['batchDate', 'plannedQuantity', 'actualQuantity', 'plannedDate', 'materialReference', 'nextStep'],
  max: productionColumnMax,
});

// U-NAV-1 业务流程图导航 (Sprint 2 Track G + FU Chat 3 bucket-filter)
const { stats: workflowStats, loading: workflowLoading } = useWorkflowStats(factoryId, 'production');
function handleWorkflowNodeClick(nodeId: string) {
  const primary = getBucketPrimaryStatus('production', nodeId);
  if (!primary) return;
  searchForm.value.status = primary;
  pagination.value.page = 1;
  loadData();
  ElMessage.success(`已切到 "${getBucketLabel('production', nodeId)}" (显示状态: ${primary}). bucket 含多个状态, 想看其他请打开状态下拉切换.`);
}

const loading = ref(false);
const actionLoading = ref(false);
const entryPreparingPlanId = ref<string | null>(null);
const tableData = ref<TableRow[]>([]);
const materialAdvisoryMap = ref<Record<string, ProductionPlanMaterialAdvisory>>({});
const settlementStatusMap = ref<Record<string, ProductionSettlementStatus>>({});
const settlementStatusErrorMap = ref<Record<string, string>>({});
// 该计划【没有】仓库确认单(存货生产走小结时本来就没有) —— 合法状态, 不是加载失败。
const settlementStatusAbsentMap = ref<Record<string, boolean>>({});
// #726 SP12: 批量合并打印工单 — 多选状态
const selectedPlans = ref<TableRow[]>([]);
const productionBatchMode = ref(false);
const productionStatusFilters = [
  { text: '待生产', value: 'PENDING' },
  { text: '进行中', value: 'IN_PROGRESS' },
  { text: '已完成', value: 'COMPLETED' },
  { text: '已取消', value: 'CANCELLED' },
];
const productionSourceFilters = [
  { text: '存货生产', value: 'SAFETY_STOCK' },
  { text: '销售订单', value: 'CUSTOMER_ORDER' },
  { text: '历史导入', value: 'EXCEL_IMPORT' },
  { text: '历史 AI 创建', value: 'AI_CHAT' },
  { text: '历史 AI 预测', value: 'AI_FORECAST' },
  { text: '历史手动', value: 'MANUAL' },
];

function compareNullableNumber(left: unknown, right: unknown): number {
  const a = Number(left);
  const b = Number(right);
  if (!Number.isFinite(a) && !Number.isFinite(b)) return 0;
  if (!Number.isFinite(a)) return 1;
  if (!Number.isFinite(b)) return -1;
  return a - b;
}

function filterProductionStatus(value: string, row: TableRow): boolean {
  return String(row.status || '').toUpperCase() === value;
}

function filterProductionSource(value: string, row: TableRow): boolean {
  const source = String(row.sourceType || 'MANUAL').toUpperCase();
  return source === value;
}

function toggleProductionBatchMode() {
  productionBatchMode.value = !productionBatchMode.value;
  if (!productionBatchMode.value) {
    selectedPlans.value = [];
  }
}
const importInputRef = ref<HTMLInputElement | null>(null);
const documentPackVisible = ref(false);
const documentPackLoading = ref(false);
const documentPackPlan = ref<TableRow | null>(null);
const selectedDocumentChapters = ref<ProductionDocumentChapter[]>([
  ...DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS,
]);
const documentTraceVisible = ref(false);
const documentTraceLoading = ref(false);
const documentTrace = ref<ProductionDocumentTrace | null>(null);
type ArchiveCenterTab = 'documents' | 'trace' | 'accounting';
const archiveCenterVisible = ref(false);
const archiveCenterPlan = ref<TableRow | null>(null);
const archiveCenterTab = ref<ArchiveCenterTab>('documents');

function openArchiveCenter(row: TableRow) {
  archiveCenterPlan.value = row;
  archiveCenterTab.value = 'documents';
  archiveCenterVisible.value = true;
}

async function openDocumentTrace(row: TableRow) {
  if (!factoryId.value) return;
  documentTraceVisible.value = true;
  documentTraceLoading.value = true;
  documentTrace.value = null;
  try {
    const response = await getProductionDocumentTrace(factoryId.value, String(row.id));
    if (!response.success || !response.data) {
      throw new Error(response.message || '加载单据追踪失败');
    }
    documentTrace.value = response.data;
  } catch (error) {
    handleCatchError(error, '加载单据追踪失败');
  } finally {
    documentTraceLoading.value = false;
  }
}

/**
 * 缺陷 51:「分配工序操作员」入口 (owner 拍板: 挪进生产计划的操作里, 不再只挂在批次详情)。
 *
 * 每行按需解析 (「更多」下拉打开时才拉), 不是列表一加载就对每一行都请求一遍 ——
 * 复用 openDocumentTrace 同一个 API (getProductionDocumentTrace), 从中挑出
 * documentType === 'PRODUCTION_BATCH' 的条目 (已排除 CLERK_WIP 内部工件, 见
 * assignProcessOperatorEntry.ts 顶部注释)。菜单项的灰显/提示文案由这份缓存驱动,
 * 而不是点了才知道 —— 与 repin-authority 同一条防呆规矩(预先显示边界)。
 */
interface AssignOperatorRowState {
  loading: boolean;
  /** null = 尚未解析过 */
  candidates: ProductionTraceDocument[] | null;
  error?: string;
}
const assignOperatorState = ref<Record<string, AssignOperatorRowState>>({});
const assignOperatorPickerVisible = ref(false);
const assignOperatorPickerRow = ref<TableRow | null>(null);

/** 打开「更多」下拉时惰性解析一次；已在解析中或已有结果就不重复请求。 */
async function ensureAssignOperatorCandidates(row: TableRow): Promise<void> {
  const key = String(row.id || '');
  if (!key || !factoryId.value) return;
  const existing = assignOperatorState.value[key];
  if (existing && (existing.loading || existing.candidates !== null)) return;
  assignOperatorState.value[key] = { loading: true, candidates: null };
  try {
    const response = await getProductionDocumentTrace(factoryId.value, key);
    if (!response.success || !response.data) {
      assignOperatorState.value[key] = {
        loading: false,
        candidates: null,
        error: response.message || '加载生产批次信息失败',
      };
      return;
    }
    assignOperatorState.value[key] = {
      loading: false,
      candidates: finishedGoodsBatchCandidates(response.data),
    };
  } catch (error) {
    assignOperatorState.value[key] = {
      loading: false,
      candidates: null,
      error: error instanceof Error ? error.message : '加载生产批次信息失败',
    };
  }
}

/** 「分配工序操作员」灰显时展示的原因；可点时返回空串。 */
function assignOperatorDisabledReason(row: TableRow): string {
  const state = assignOperatorState.value[String(row.id || '')];
  if (!state || state.loading) return '正在获取该计划的生产批次…';
  if (state.error) return state.error;
  if (!state.candidates || state.candidates.length === 0) return PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP;
  return '';
}

/** 点击「分配工序操作员」：唯一候选直接跳转；多个候选弹选择框，不悄悄取第一个。 */
function handleAssignProcessOperator(row: TableRow) {
  const candidates = assignOperatorState.value[String(row.id || '')]?.candidates || [];
  if (candidates.length === 0) return; // 灰显时不会派发到这里，防御性 no-op
  if (candidates.length === 1) {
    void router.push(assignProcessOperatorTarget(candidates[0].documentId));
    return;
  }
  assignOperatorPickerRow.value = row;
  assignOperatorPickerVisible.value = true;
}

function goToAssignOperatorBatch(document: ProductionTraceDocument) {
  assignOperatorPickerVisible.value = false;
  void router.push(assignProcessOperatorTarget(document.documentId));
}

/**
 * 就地展开, **不跳页** (客户 2026-07-31:「最多只是把单据信息放出来, 不会做跳转」)。
 *
 * 原本是 router.push 跳到别的模块, 生产计划这一屏就没了, 想看下一条得从头再来。
 * 与 components/DocumentTraceDrawer.vue 同一口径 —— 那边是销售/采购/调拨三处共用的抽屉,
 * 这一份是生产计划自己的; 两边行为必须一致, 否则同一个功能在不同页面表现不同。
 */
const expandedTraceKey = ref('');
const traceDocumentKey = (d: ProductionTraceDocument) => `${d.documentType}-${d.documentId}`;

function toggleTraceDocument(document: ProductionTraceDocument) {
  const key = traceDocumentKey(document);
  expandedTraceKey.value = expandedTraceKey.value === key ? '' : key;
}

function traceDirectionLabel(direction?: string) {
  if (direction === 'UPSTREAM') return '上游来源';
  if (direction === 'EXECUTION') return '生产执行';
  if (direction === 'DOWNSTREAM') return '结算与出库';
  return '关联单据';
}
function handleSelectionChange(rows: TableRow[]) {
  selectedPlans.value = rows;
}
async function handleMultiPrint() {
  const ids = selectedPlans.value.map((r) => String(r.id));
  await printWorkOrderMulti(factoryId.value, ids);
}

function handleDataCommand(command: 'template' | 'import' | 'export') {
  if (command === 'template') {
    void handleDownloadTemplate();
  } else if (command === 'import') {
    importInputRef.value?.click();
  } else {
    void handleExport();
  }
}

function handleNativeImportChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) void handleImportFile({ raw: file });
  input.value = '';
}

function handleBatchCommand(command: 'print-work-orders') {
  if (command === 'print-work-orders') void handleMultiPrint();
}

type ProductionDocumentCommand =
  | 'work-order'
  | 'material-requisition'
  | 'batching-sheet'
  | 'document-pack';

function handleProductionDocumentCommand(command: ProductionDocumentCommand, row: TableRow) {
  if (!canPrintPlanDocuments(String(row.status || ''))) {
    ElMessage.warning(t('production.plans_list.message_e2bbf9412240'));
    return;
  }
  if (command === 'document-pack') {
    documentPackPlan.value = row;
    selectedDocumentChapters.value = [...DEFAULT_PRODUCTION_DOCUMENT_CHAPTERS];
    documentPackVisible.value = true;
    return;
  }
  const docType = command === 'work-order'
    ? 'production-work-order'
    : command === 'material-requisition'
      ? 'consolidated-material-requisition'
      : 'batching-sheet';
  const label = command === 'work-order' ? '生产工单' : command === 'material-requisition' ? '领料单' : '配料单';
  void safePrint(docType, factoryId.value, String(row.id), {
    fileName: `${label}_${row.planNumber || row.id}`,
  });
}

async function submitProductionDocumentPack() {
  const row = documentPackPlan.value;
  if (!row || !factoryId.value) return;
  documentPackLoading.value = true;
  try {
    await downloadProductionDocumentPack(
      factoryId.value,
      String(row.id),
      String(row.planNumber || row.id),
      selectedDocumentChapters.value,
    );
    documentPackVisible.value = false;
  } catch {
    // downloadProductionDocumentPack already gives a fail-closed, actionable error.
  } finally {
    documentPackLoading.value = false;
  }
}

type TraceAndCostCommand = 'trace' | 'yield-cost' | 'summary';

function handleTraceAndCostCommand(command: TraceAndCostCommand, row: TableRow) {
  if (command === 'trace') {
    void openDocumentTrace(row);
    return;
  }
  if (command === 'summary') {
    void handleOpenSummary(row);
    return;
  }
  if (String(row.status || '').toUpperCase() !== 'COMPLETED') {
    ElMessage.info(t('production.plans_list.message_8641705852fa'));
    return;
  }
  const orderNumberOrId = sourceOrderBusinessNumber(row) || String(row.sourceOrderId || '').trim();
  if (!orderNumberOrId) {
    ElMessage.warning(t('production.plans_list.message_0182abc884f5'));
    return;
  }
  void router.push({
    path: '/production-analytics/yield-cost',
    query: { orderId: orderNumberOrId },
  });
}

function openArchiveDocument(command: ProductionDocumentCommand) {
  const row = archiveCenterPlan.value;
  if (!row) return;
  if (command === 'document-pack') {
    archiveCenterVisible.value = false;
  }
  handleProductionDocumentCommand(command, row);
}

function openArchiveTrace() {
  const row = archiveCenterPlan.value;
  if (!row) return;
  archiveCenterVisible.value = false;
  void openDocumentTrace(row);
}

function openArchiveSummary() {
  const row = archiveCenterPlan.value;
  if (!row) return;
  archiveCenterVisible.value = false;
  void handleOpenSummary(row);
}

function openArchiveYieldCost() {
  const row = archiveCenterPlan.value;
  if (!row) return;
  if (String(row.status || '').toUpperCase() === 'COMPLETED') {
    archiveCenterVisible.value = false;
  }
  handleTraceAndCostCommand('yield-cost', row);
}

type ProductionActionCommand =
  | 'repin-authority'
  | 'complete'
  | 'interim-settle'
  | 'process-entry'
  | 'edit'
  | 'copy'
  | 'assign-process-operator'
  | 'start-secondary'
  | 'app-reporting'
  | 'generate-transfer'
  | 'reverse-interim-settle'
  | 'stop-production'
  | 'cancel';

function canShowProductionActions(row: TableRow): boolean {
  return canWrite.value && isUnfinishedStatus(String(row.status || ''));
}

function processEntryActionLabel(row: TableRow): string {
  return String(row.status || '').toUpperCase() === 'IN_PROGRESS' ? '继续录入' : '逐道录入';
}

function settlementActionLabel(row: TableRow): string {
  return row.sourceType === 'SAFETY_STOCK' ? '生产小结' : '核对结单';
}

interface RowWorkflowPreviewState {
  loading: boolean;
  candidate?: WorkflowResolutionCandidate;
  error?: string;
}

const rowWorkflowPreviews = ref<Record<string, RowWorkflowPreviewState>>({});

// ⚠️ 硬约束「改共享结构前先数」的登记项 —— 本文件里「回落成裸 ID」有**两种**形状:
//
//   ① `targetFinishedGoodNames` / 候选产出标签: 直接 `name || id`, **零个名字字段兜底**
//      → 生产上实际打出了裸 UUID(2026-08-28 实拍, 20 条计划里 8 条) ⇒ **本 PR 已修 3 处**
//
//   ② `productTypeName || productName || productTypeId` 三段式: **先试两个名字字段**,
//      只有后端两个都没下发才会到 ID。⇒ 生产上未观测到, 且这 11 处散在对话框标题 /
//      确认框文案 / 归档卡片, 各自上下文不同, 统一改需要逐处判断「空值该显示什么」。
//      ⇒ **登记, 本 PR 不改** (⛔ 不是漏了)。当前 11 处:
//         2029 / 2636 / 2697 / 2916 / 3063 / 3232 / 3279 / 3394 / 4433 / 4666 / 6226
//      再看到裸 UUID 的实拍时, 从这份清单里找。
function planFinishedProductNames(row: TableRow): string[] {
  const targetIds = Array.isArray(row.targetFinishedGoodIds)
    ? row.targetFinishedGoodIds.map(String)
    : [];
  // 目录查不到 (该账号的 /product-types/active 没拿到, 或确实不在目录里) 时, 单产品计划用计划自带的品名,
  // 而不是对着一个明明有名字的计划写「未知产品」(D-S2-09)。多产出计划无法逐个对名, 仍如实显示查不到。
  const ownName = String(row.productTypeName || row.productName || '').trim();
  const usableOwnName = ownName && !isRawWorkflowOwnerAnchorName(ownName) ? ownName : '';
  // 先用服务端随计划下发的名称 (本厂目录解析, 与浏览器是否拿到产品目录无关), 再用本页拉到的目录。
  const serverNames = (row.targetFinishedGoodNames ?? {}) as Record<string, string>;
  const names = targetFinishedGoodNames(targetIds).filter(Boolean).map((label, index) =>
    label === UNKNOWN_OUTPUT_PRODUCT_LABEL && serverNames[targetIds[index]]
      ? serverNames[targetIds[index]]
      : label === UNKNOWN_OUTPUT_PRODUCT_LABEL && usableOwnName && targetIds.length === 1
        && String(row.productTypeId ?? '') === targetIds[0]
        ? usableOwnName
        : label === UNKNOWN_OUTPUT_PRODUCT_LABEL && !productCatalogLoaded.value
          ? PRODUCT_CATALOG_LOADING_LABEL
          : label);
  if (names.length > 0) return names;
  // ⚠️ 与上面同一条路径: targetFinishedGoodIds 为空时走这里, 原来同样会打出裸 UUID。
  // productTypeId 本身为空是合法的(没指定成品), 那种情况给 '-' 才诚实, 不要说「未识别 SKU」。
  if (isRawWorkflowOwnerAnchorName(row.productTypeName) || isRawWorkflowOwnerAnchorName(row.productName)) {
    return [RAW_WORKFLOW_OWNER_ANCHOR_LABEL];
  }
  return [String(
    row.productTypeName
    || row.productName
    || (row.productTypeId ? resolveOutputProductLabel(row.productTypeId) : '-'),
  )];
}

function planFinishedGoodIds(row: TableRow): string[] {
  if (Array.isArray(row.targetFinishedGoodIds) && row.targetFinishedGoodIds.length > 0) {
    return row.targetFinishedGoodIds.map(String).filter(Boolean);
  }
  return row.productTypeId ? [String(row.productTypeId)] : [];
}

async function ensureRowWorkflowPreview(row: TableRow): Promise<void> {
  const key = String(row.id || '');
  if (!key || rowWorkflowPreviews.value[key]?.candidate || rowWorkflowPreviews.value[key]?.loading) return;
  const outputIds = planFinishedGoodIds(row);
  if (!factoryId.value || outputIds.length === 0) {
    rowWorkflowPreviews.value[key] = { loading: false, error: '该计划未记录可解析的成品标识' };
    return;
  }
  rowWorkflowPreviews.value[key] = { loading: true };
  try {
    const response = await resolveWorkflowByOutputs(factoryId.value, outputIds);
    const resolvedCandidates = response.success && response.data
      ? resolvePlanWorkflowCandidates(outputIds, response.data.candidates || []).candidates
      : [];
    const selectedWorkflowId = Number(row.selectedWorkflowId);
    const selectedWorkflowVersion = Number(row.selectedWorkflowVersion);
    const hasPinnedWorkflow = Number.isFinite(selectedWorkflowId) && Number.isFinite(selectedWorkflowVersion);
    let candidate = hasPinnedWorkflow
      ? resolvedCandidates.find((item) =>
          item.workflowId === selectedWorkflowId
          && item.definitionVersion === selectedWorkflowVersion)
      : resolvedCandidates[0];
    if (!candidate && hasPinnedWorkflow) {
      // 计划固定的版本已被新版本替换, 不在「当前启用」里: 直接读计划固定的那一版, 不拿启用版本顶替。
      const pinned = await findPinnedWorkflow(factoryId.value, selectedWorkflowId, selectedWorkflowVersion);
      candidate = pinned.success && pinned.data ? pinned.data : undefined;
    }
    rowWorkflowPreviews.value[key] = candidate
      ? { loading: false, candidate }
      : {
          loading: false,
          error: hasPinnedWorkflow
            ? `未读取到计划固定的工序路线 v${selectedWorkflowVersion}`
            : response.message || '未找到已启用的工序路线',
        };
  } catch (error) {
    rowWorkflowPreviews.value[key] = {
      loading: false,
      error: error instanceof Error ? error.message : '工序路线加载失败',
    };
  }
}

function handlePrimarySettlementAction(row: TableRow) {
  if (row.sourceType === 'SAFETY_STOCK') {
    void handleInterimSettle(row);
    return;
  }
  void handleComplete(row);
}

function handleProductionActionCommand(command: ProductionActionCommand, row: TableRow) {
  switch (command) {
    case 'complete':
      void handleComplete(row);
      break;
    case 'interim-settle':
      void handleInterimSettle(row);
      break;
    case 'process-entry':
      void openProcessEntry(row);
      break;
    case 'edit':
      void handleEditPlan(row);
      break;
    case 'copy':
      void handleCopyPlan(row);
      break;
    case 'assign-process-operator':
      handleAssignProcessOperator(row);
      break;
    case 'start-secondary':
      void handleStart(row);
      break;
    case 'app-reporting':
      void handleCreateBatch(row);
      break;
    case 'generate-transfer':
      void handleGenerateTransfer(row);
      break;
    case 'reverse-interim-settle':
      void handleReverseInterimSettle(row);
      break;
    case 'stop-production':
      void handleStopProduction(row);
      break;
    case 'repin-authority':
      void handleRepinAuthority(row);
      break;
    case 'cancel':
      void handleCancel(row);
      break;
    default:
      ElMessage.error(t('production.plans_list.message_adbaddeb4e8e'));
  }
}

function yieldCostActionLabel(row: TableRow): string {
  return String(row.status || '').toUpperCase() === 'COMPLETED'
    ? '成品出厂核算'
    : '成品出厂核算（结单后）';
}

/**
 * 打开来源销售订单。这个按钮**就在「查看单据追溯」旁边**(档案与核算 → 计划档案 tab),
 * 客户 2026-07-21 说的「通过单据追踪进入到其他业务模块后回不来」指的就是这一跳:
 * 原来是一句光秃秃的 router.push, 落到销售订单详情之后, 那儿的「返回」是写死回
 * /sales/orders 列表, 回不到这张计划表, 更别说回到原来滚到的位置。
 *
 * 现在把「从哪儿来 + 当时滚到哪儿」挂在 URL 上: `_returnTo` 让全局的 ReturnBanner
 * 显形(它一直挂在 AppLayout 上, 只是没人给它喂参数), `_returnScroll` 让回来时
 * 落回原来的位置。详见 utils/returnNavigation.ts。
 */
function openSourceOrder(row: TableRow) {
  const target = sourceOrderTarget(row);
  if (!target) return;
  const base = typeof target === 'string' ? { path: target } : target;
  void router.push({
    ...base,
    query: {
      ...(base.query || {}),
      ...returnContextQuery(route.fullPath, currentAppScrollTop()),
    },
  });
}
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchForm = ref({
  keyword: '',
  status: 'UNFINISHED'
});
const STATUS_FILTER_LABELS: Record<string, string> = {
  UNFINISHED: '未完成', PREPARED: '草稿', PLANNED: '待执行', PENDING: '待执行 (PENDING)',
  IN_PROGRESS: '进行中', PAUSED: '暂停', COMPLETED: '已结单', CANCELLED: '已取消',
};
// Rule 1 (fool-proof-design): 按编号/关键词搜索在受限状态筛选下 0 结果时, 提示切到"全部"重试,
// 而不是让用户误以为查不到这个计划 (#3 blocking-bug 场景: 按已知计划号搜, 状态默认"未完成")。
const emptyText = computed(() => {
  if (loading.value || tableData.value.length > 0) return '暂无数据';
  const status = searchForm.value.status;
  if (searchForm.value.keyword && status) {
    const label = STATUS_FILTER_LABELS[status] || status;
    return `当前筛选[${label}]无结果，试试切换状态筛选为「全部」`;
  }
  return '暂无数据';
});

// Keep list and footer on the exact same filter contract. The backend groups quantities
// by canonical unit and only computes completion rates inside a comparable unit group.
const summaryRequest = computed<ListSummaryRequest>(() => ({
  filterConditions: {
    ...(searchForm.value.status ? { status: searchForm.value.status } : {}),
    ...(searchForm.value.keyword.trim() ? { keyword: searchForm.value.keyword.trim() } : {}),
  },
}));
const { summary: footerSummary, loading: footerLoading } = useListSummary('productionPlan', summaryRequest);

// ========== SP2 独立再加工/返工计划对话框 ==========
const secondaryDialogVisible = ref(false);
const secondaryDialogLoading = ref(false);
const wipListLoading = ref(false);
const wipList = ref<WipInventoryItem[]>([]);
const secondaryForm = ref<{
  wipId: number | null;
  quantity: number;
  productTypeId: string;
  plannedDate: string;
}>({
  wipId: null,
  quantity: 0,
  productTypeId: '',
  plannedDate: '',
});
const selectedWip = computed(() =>
  wipList.value.find((w) => w.id === secondaryForm.value.wipId) ?? null
);

async function handleCreateSecondary() {
  if (!factoryId.value) return;
  wipListLoading.value = true;
  secondaryForm.value = { wipId: null, quantity: 0, productTypeId: '', plannedDate: tomorrowStr() };
  secondaryDialogVisible.value = true;
  try {
    const res = await listAvailableWip(factoryId.value);
    if (res.success && Array.isArray(res.data)) {
      wipList.value = res.data;
    } else {
      ElMessage({ message: res.message || '加载半成品库存失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '加载半成品库存失败');
  } finally {
    wipListLoading.value = false;
  }
}

function handleWipSelect(wipId: number) {
  const wip = wipList.value.find((w) => w.id === wipId);
  if (wip) {
    // 防呆 Rule 1: 预填最大可用量
    secondaryForm.value.quantity = Number(wip.availableQuantity) || 0;
    // WIP 的 productTypeId 是当前半成品，不等于二次加工的目标成品。
    // 保持目标为空，要求操作者明确选择有 Workflow 覆盖的产出，避免
    // 把半成品本身误提交为目标并得到 WORKFLOW_SINGLE_OUTPUT_NOT_FOUND。
    secondaryForm.value.productTypeId = '';
  }
}

async function submitSecondaryPlan() {
  if (!factoryId.value) return;
  const form = secondaryForm.value;
  if (!form.wipId) {
    ElMessage.warning(t('production.plans_list.message_1686d8866413'));
    return;
  }
  if (form.quantity <= 0) {
    ElMessage.warning(t('production.plans_list.message_218063ec8988'));
    return;
  }
  if (!form.productTypeId) {
    ElMessage.warning(t('production.plans_list.message_7c7a6ba5ffd9'));
    return;
  }
  const wip = selectedWip.value;
  if (wip && form.quantity > Number(wip.availableQuantity)) {
    ElMessage({
      message: `超出可用量: 需要 ${form.quantity}，可用 ${wip.availableQuantity}`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  secondaryDialogLoading.value = true;
  try {
    const res = await createSecondaryPlan(factoryId.value, {
      wipId: form.wipId,
      quantity: form.quantity,
      productTypeId: form.productTypeId,
      plannedDate: form.plannedDate || undefined,
    });
    if (res.success) {
      ElMessage.success(t('production.plans_list.message_0a4d1f428845'));
      secondaryDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '创建独立再加工/返工计划失败');
  } finally {
    secondaryDialogLoading.value = false;
  }
}
// ========== /SP2 ==========

// 新建计划对话框
const dialogVisible = ref(false);
const dialogLoading = ref(false);
const planForm = ref({
  productTypeId: '',
  plannedQuantity: undefined as number | undefined,
  aiRequestedUnit: '',
  plannedDate: '',
  notes: '',
  estimatedWorkers: undefined as number | undefined,
  assignedSupervisorId: '' as string | undefined,
  customerId: '',
  sourceCustomerName: '',
  outputOwnership: 'COMPANY_OWNED' as ProductionPlanOutputOwnership,
  materialSupplyMode: 'FACTORY_SUPPLIED' as 'FACTORY_SUPPLIED' | 'CUSTOMER_SUPPLIED',
  processName: '',
  batchDate: '',
  // 新建入口只保留存货生产与销售订单；默认进入日常存货生产。
  sourceType: 'SAFETY_STOCK' as 'CUSTOMER_ORDER' | 'SAFETY_STOCK',
  sourceOrderId: '' as string | undefined,
  sourceOrderItemId: '' as string | undefined,
  // 以销定产多产品 (2026-06-24): 选 SO 默认带出全部产品行, 多选可取消; 每行各建一张计划。
  sourceOrderItemIds: [] as string[],
  // SP5 多SO合并工单: 追加的额外销售订单ID列表 (不含 primarySourceOrderId, 提交前合并)
  extraSourceOrderIds: [] as string[],
  customFields: {} as TableRow,
  // Wave2 六扇门: 免工序报工开关 (null→后端默认 true, 新建默认 true = 两点报工)
  skipProcessReporting: true as boolean | null,
  // 非 CUSTOMER_ORDER 来源：按所选成品集合解析一个完整 Workflow。
  targetFinishedGoodIds: [] as string[],
  resolvedCandidates: [] as WorkflowResolutionCandidate[],
  // 覆盖了所选成品、但还多产别的联产成品的图。只用于把「未找到」换成说得清的提示(2026-09-02)。
  jointSupersets: [] as WorkflowResolutionCandidate[],
  selectedCandidateWorkflowId: null as number | null,
  resolutionMode: '' as '' | PlanWorkflowResolutionMode,
});
const planCreateRequestId = ref('');
const planCreateFingerprint = ref('');

function newPlanCreateRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `plan-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

function bindPlanCreateRequestId<T extends Record<string, unknown>>(
  payload: T,
): T & { clientRequestId: string } {
  const fingerprint = JSON.stringify(payload);
  if (!planCreateRequestId.value
    || (planCreateFingerprint.value && planCreateFingerprint.value !== fingerprint)) {
    planCreateRequestId.value = newPlanCreateRequestId();
  }
  planCreateFingerprint.value = fingerprint;
  return { ...payload, clientRequestId: planCreateRequestId.value };
}
const productTypes = ref<TableRow[]>([]);
const productCatalogError = ref('');
// 产品目录还在加载时, 查不到只是「还没到」, 不是「档案查不到」—— 不能在这段时间里把计划写成未知产品。
const productTypesSettled = ref(false);
const rawMaterialTypesSettled = ref(false);
const productCatalogLoaded = computed(() => productTypesSettled.value && rawMaterialTypesSettled.value);
// 副产 SKU 不在 product_types 里 — 它们是原料 (raw_material_types)。核对结单/仓库确认入库
// 两个对话框的「终端产出」行按 productTypeId 找名字时两张表都要查，否则副产行会读成
// 「未知产品」(与 PR #2839 App 待确认入库屏同一个坑)。
const rawMaterialTypesForNames = ref<Array<{ id: string; name: string }>>([]);
// 生产计划只能以明确标记为 FINISHED_PRODUCT 的 SKU 为目标。半成品和分类缺失
// 的历史记录都不能靠名称或单位猜测后进入候选，避免误建生产计划。
const finishedGoodProductTypes = computed(() => finishedGoodPlanOptions(productTypes.value));
const bomProcesses = ref<string[]>([]);
// A3: full work-process objects for read-only display (ordered by processOrder)
const productWorkProcessList = ref<TableRow[]>([]);
// Wave2: 当前产品是否已配置工序 (0工序→强制两点, 开关 disabled 锁定勾选)
const hasProcesses = computed(() => productWorkProcessList.value.length > 0);
// raw-centric 多SKU (2026-07-13): 产品由「产品工序 Workflow」驱动 (有 enabled activation) →
// 逐道报工经 workflow materialize, 没有 legacy PWP 也不能强制两点 (否则 spawnTasks 走两点绕过 materialize → 逐道抽屉空)。
const hasActiveWorkflow = ref(false);
// 报工模式开关锁死条件:
//   - workflow 驱动 → 锁在「逐道报工」(skip=false), 防误切两点绕过 materialize
//   - 已选产品且 0 legacy 工序且无 workflow → 锁在「免工序」(强制两点)
//   - 其余(未选产品 / 有 legacy 工序)可自由切换 (防呆 Rule 5: 不制造 dead-end)
const reportModeLocked = computed(() =>
  !!planForm.value.productTypeId && (hasActiveWorkflow.value || !hasProcesses.value));
const { customers, customerReadStatus, customerReadHint, canRetryCustomers, loadCustomers } = useProductionPlanCustomerReadAccess(
  factoryId, permissionStore, async (factory) => {
    const res = await get<ProductionPlanCustomerOption[] | { content: ProductionPlanCustomerOption[] }>(
      `/${factory}/customers`, { params: { size: 200 } },
    );
    if (!res.success) throw new Error(res.message || '客户列表加载失败');
    const rows = Array.isArray(res.data) ? res.data : res.data?.content;
    if (!Array.isArray(rows)) throw new Error('客户列表响应无效，请重试');
    return rows;
  },
);

const resolvedWorkflowCandidate = computed<WorkflowResolutionCandidate | undefined>(() =>
  planForm.value.resolvedCandidates.find(
    (candidate) => candidate.workflowId === planForm.value.selectedCandidateWorkflowId
  )
);
const hasResolvedWorkflowCandidate = computed(() => !!resolvedWorkflowCandidate.value);

// A5: today helper
function todayStr(): string {
  return toDateString(new Date());
}

// T135: tomorrow helper (计划生产日默认 = 今天 + 1)
function tomorrowStr(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  return toDateString(d);
}
const selectableSalesOrders = ref<TableRow[]>([]);
const salesOrdersLoading = ref(false);
let _selectableSalesOrdersInflight: Promise<void> | null = null;

async function loadSelectableSalesOrders() {
  if (!factoryId.value) return;
  if (_selectableSalesOrdersInflight) return _selectableSalesOrdersInflight;
  _selectableSalesOrdersInflight = (async () => {
    salesOrdersLoading.value = true;
    try {
      const res = await get(`/${factoryId.value}/production-plans/sales-orders/selectable`);
      if (res.success && Array.isArray(res.data)) {
        selectableSalesOrders.value = res.data;
      } else if (res.success === false) {
        ElMessage.error(res.message || '加载销售订单失败');
      }
    } catch (e) {
      // UX polish (2026-05-20): interceptor handles 4xx/5xx with backend message;
      // fallback only for network errors (避免双 toast).
      handleCatchError(e, '加载销售订单失败');
    } finally {
      salesOrdersLoading.value = false;
      _selectableSalesOrdersInflight = null;
    }
  })();
  return _selectableSalesOrdersInflight;
}

function handleSourceTypeChange(val: string) {
  sourceOrderIdError.value = false;
  if (val === 'CUSTOMER_ORDER') {
    handlePlanCustomerChange('');
    if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  } else {
    planForm.value.sourceOrderId = '';
    planForm.value.sourceOrderItemId = '';
  }
}

// 「来源销售订单」校验失败态 — 防呆: 空字段红框锚定 + 自动聚焦, 不只弹个 toast 让用户自己找。
const sourceOrderIdError = ref(false);
const sourceOrderSelectRef = ref<InstanceType<typeof ElSelect> | null>(null);

// P0-12: 当前选中订单的可选产品行
const selectedOrderItems = computed<TableRow[]>(() => {
  const oid = planForm.value.sourceOrderId;
  if (!oid) return [];
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(oid));
  return so && Array.isArray(so.items) ? (so.items as TableRow[]) : [];
});

function handleSalesOrderSelect(orderId: string) {
  sourceOrderIdError.value = false;
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(orderId));
  // 切换订单时清空已选行
  planForm.value.sourceOrderItemId = '';
  planForm.value.sourceOrderItemIds = [];
  planForm.value.productTypeId = '';
  productWorkProcessList.value = [];
  if (so) {
    planForm.value.sourceCustomerName = String(so.customerName || '');
    // 以销定产 (2026-06-24): 默认带出全部产品行 (多选), 用户可取消不需要的。每行各建一张计划。
    const items = Array.isArray(so.items) ? (so.items as TableRow[]) : [];
    planForm.value.sourceOrderItemIds = items.map((it) => String(it.id));
    // 兼容单产品: 仅 1 行时也回填单字段 (产品类型/客户), 供 UI 摘要展示
    if (items.length === 1) {
      planForm.value.sourceOrderItemId = String(items[0].id);
      handleSalesOrderItemSelect(String(items[0].id));
    }
  }
}

// P0-12: 选中销售订单行后,自动回填产品/客户
function handleSalesOrderItemSelect(itemId: string) {
  const item = selectedOrderItems.value.find((it) => String(it.id) === String(itemId));
  if (!item) return;
  if (item.productTypeId) {
    planForm.value.productTypeId = String(item.productTypeId);
    handleProductChange(planForm.value.productTypeId);
  }
  // 客户名已在选订单时回填,这里再补一次以防订单未选时直接选行
  const so = selectableSalesOrders.value.find((o) => String(o.id) === String(planForm.value.sourceOrderId));
  if (so && so.customerName) {
    planForm.value.sourceCustomerName = String(so.customerName);
  }
}

// Import/Export & reference data
const supervisors = ref<TableRow[]>([]);

// AI Entry Drawer
const aiEntryVisible = ref(false);

// Fable 审计修复 (问题1 — 多租户安全): 工厂级免工序报工默认值。
// F006=true (新建默认勾选两点); 其他工厂=false (默认不勾, 逐道)。取数失败兜底 false (安全=逐道)。
const skipReportingFactoryDefault = ref(false);
async function loadReportModeDefault() {
  if (!factoryId.value) return;
  try {
    const res = await getReportModeDefault(factoryId.value);
    skipReportingFactoryDefault.value = res.data === true;
  } catch {
    skipReportingFactoryDefault.value = false;
  }
}

onMounted(() => {
  loadData();
  loadProductTypes();
  loadRawMaterialTypesForNames();
  loadReferenceData();
  loadReportModeDefault();
  void maybeReopenFromQuery();
  void maybeOpenProcessEntryFromQuery();
});

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/production-plans`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchForm.value.keyword || undefined,
        status: searchForm.value.status || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
      void loadMaterialAdvisories(tableData.value);
      void loadSettlementStatuses(tableData.value);
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载生产计划失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

async function loadMaterialAdvisories(rows: TableRow[]) {
  if (!factoryId.value) return;
  const unfinishedRows = rows.filter((row) =>
    ['PENDING', 'IN_PROGRESS'].includes(String(row.status || '').toUpperCase())
  );
  if (unfinishedRows.length === 0) {
    materialAdvisoryMap.value = {};
    return;
  }
  const entries = await Promise.allSettled(
    unfinishedRows.map(async (row) => {
      const planId = String(row.id);
      const res = await getMaterialAdvisory(factoryId.value, planId);
      if (!res.success || !res.data) {
        throw new Error(res.message || '加载原料预警失败');
      }
      return [planId, res.data] as const;
    })
  );
  const next: Record<string, ProductionPlanMaterialAdvisory> = {};
  let failed = false;
  entries.forEach((entry) => {
    if (entry.status === 'fulfilled') {
      next[entry.value[0]] = entry.value[1];
    } else {
      failed = true;
    }
  });
  materialAdvisoryMap.value = next;
  if (failed) {
    ElMessage({ message: t('production.plans_list.message_4c1cafa09e3c'), type: 'error', duration: 0, showClose: true });
  }
}

async function loadSettlementStatuses(rows: TableRow[]) {
  if (!factoryId.value) return;
  const settledRows = rows.filter((row) =>
    String(row.status || '').toUpperCase() === 'COMPLETED'
  );
  if (settledRows.length === 0) {
    settlementStatusMap.value = {};
    settlementStatusErrorMap.value = {};
    settlementStatusAbsentMap.value = {};
    return;
  }
  // 🔴 2026-08-19 客户实际反馈: 结单成功后右上角一直弹红, 换到别的页面回来还弹,
  //    而筛「已结单」就不弹。真因是这里**批量**给每个 COMPLETED 计划拉结单状态,
  //    列表里有一条**过往**的存货生产计划(PLAN-1786724332978, 走小结)在
  //    production_settlements 里根本没有行 → 后端 404 → 拦截器弹全局红条。
  //    弹的是【别的计划】, 而用户以为是自己刚做的操作失败了。
  //
  // ⇒ 两件事分开:
  //    ① `_silent`: 这是尽力而为的列表增强, 失败已经逐行接住(下面的 errors), 不该再弹全局。
  //    ② 404 不是「加载失败」, 是「这个计划没有仓库确认单」—— 一个**合法状态**,
  //       ⛔ 不许标成红色的「加载失败」(本仓形态 A¹¹: 先问这里的空是不是合法状态)。
  const entries = await Promise.allSettled(
    settledRows.map(async (row) => {
      const planId = String(row.id);
      const res = await getProductionSettlement(factoryId.value, planId, { _silent: true });
      if (!res.success || !res.data) {
        throw new Error(res.message || '加载结单状态失败');
      }
      // 2026-08-19 起后端如实回答: 没有仓库确认单 → 200 + settled=false (不再抛 404)。
      // 老后端不返回这个字段, isNotSettledPayload 认 === false, 不会误判。
      if (isNotSettledPayload(res.data)) {
        return [planId, null] as const;
      }
      return [planId, res.data] as const;
    })
  );
  const next: Record<string, ProductionSettlementStatus> = {};
  const errors: Record<string, string> = {};
  const absent: Record<string, boolean> = {};
  entries.forEach((entry, index) => {
    const planId = String(settledRows[index].id);
    if (entry.status === 'fulfilled') {
      const [key, data] = entry.value;
      if (data === null) {
        absent[key] = true;
      } else {
        next[key] = data;
      }
      return;
    }
    const failure = classifySettlementFailure(entry.reason);
    if (failure.kind === 'absent') {
      absent[planId] = true;
      return;
    }
    errors[planId] = failure.message;
  });
  settlementStatusMap.value = next;
  settlementStatusErrorMap.value = errors;
  settlementStatusAbsentMap.value = absent;
}

async function loadProductTypes() {
  if (!factoryId.value) return;
  try {
    // 用 /active 端点取全部启用产品(非分页数组), 避免默认分页 size=20 截断
    // (F006 176 产品曾只加载前 20 个, 客户端 filterable 下拉搜不到老产品 e.g. 猪舌门腔)
    const response = await get(`/${factoryId.value}/product-types/active`);
    if (response.success && response.data) {
      productTypes.value = response.data.content || response.data || [];
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载产品类型失败');
    }
  } catch (error: any) {
    console.error('加载产品类型失败:', error);
    // 不再吞掉: 目录没拿到时页面会把没有服务端名称的产出写成「未知产品」, 用户必须知道那是因为目录没加载, 而不是产品真的不存在。
    productCatalogError.value = error?.message || '产品目录加载失败';
    ElMessage({
      message: `产品目录没有加载成功（${productCatalogError.value}），列表里部分产品名可能显示为「未知产品」，请刷新重试`,
      type: 'error', duration: 0, showClose: true,
    });
  } finally {
    productTypesSettled.value = true;
  }
}

// 仅供「终端产出」UUID→名字解析用 (resolveOutputProductLabel)。查不到时静默兜底为空数组
// — 这两个对话框本来就不是这个列表的主用途，加载失败不应该弹错误打断文员核对结单。
async function loadRawMaterialTypesForNames() {
  if (!factoryId.value) return;
  try {
    const response = await get(`/${factoryId.value}/raw-material-types/active`);
    if (response.success && response.data) {
      rawMaterialTypesForNames.value = response.data.content || response.data || [];
    }
  } catch (error) {
    console.error('加载原料类型(用于终端产出命名)失败:', error);
  } finally {
    rawMaterialTypesSettled.value = true;
  }
}

const selectablePlanCustomers = computed(() => selectableProductionPlanCustomers(customers.value));

function handlePlanCustomerChange(customerId: string | null | undefined) {
  const selection = resolveProductionPlanCustomerSelection(customerId, customers.value, planForm.value.materialSupplyMode);
  planForm.value.customerId = selection.customerId;
  planForm.value.sourceCustomerName = selection.sourceCustomerName;
  planForm.value.outputOwnership = selection.outputOwnership;
  if (!selection.customerId) planForm.value.materialSupplyMode = 'FACTORY_SUPPLIED';
}

async function loadBomProcesses(productTypeId: string) {
  hasActiveWorkflow.value = false;
  if (!factoryId.value || !productTypeId) {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    return;
  }
  // raw-centric 多SKU (2026-07-13): 先探产品是否由 Workflow 驱动 (有 enabled activation)。
  // workflow 产品逐道报工经 materialize, 即便没有 legacy PWP 也必须 skip=false, 否则
  // spawnTasks 走两点报工绕过 materializeIfActive → 逐道抽屉空。诚实降级: 探测失败视为无 workflow。
  try {
    const act = await get<{ enabled?: boolean } | null>(
      `/${factoryId.value}/product-process-workflows/${productTypeId}/activation`);
    hasActiveWorkflow.value = !!(act.success && act.data && act.data.enabled === true);
  } catch {
    hasActiveWorkflow.value = false;
  }
  try {
    // B1 fix (2026-05-10): 工序下拉应读"产品工序配置"(ProductWorkProcess),
    // 不是 LaborCostConfig (人工成本). 后端按 processOrder asc 返回.
    // Ref: docs/qa-audits/2026-05-10-customer-meeting-9bug-audit.md §B1
    // A3: also populate productWorkProcessList for read-only display.
    const res = await getProductWorkProcesses(factoryId.value, productTypeId);
    if (res.success && res.data && Array.isArray(res.data)) {
      productWorkProcessList.value = res.data as TableRow[];
      const names = res.data.map((item: TableRow) => String(item.processName || '')).filter(Boolean);
      bomProcesses.value = [...new Set(names)];
      // T135 ITEM #4 (BLOCKING): wire all process names into planForm.processName so the
      // CUSTOMER_ORDER backend validation (processName required) passes.
      // Backend checks: request.getProcessName() != null && !isBlank().
      planForm.value.processName = names.length > 0
        ? names.join('、')
        : (hasActiveWorkflow.value ? 'Workflow 逐道报工' : '两点报工');
    } else {
      bomProcesses.value = [];
      productWorkProcessList.value = [];
      planForm.value.processName = hasActiveWorkflow.value ? 'Workflow 逐道报工' : '';
    }
  } catch {
    bomProcesses.value = [];
    productWorkProcessList.value = [];
    planForm.value.processName = hasActiveWorkflow.value ? 'Workflow 逐道报工' : '';
  }
  // 最终报工模式: workflow 驱动 → 逐道 (skip=false, 让 materialize 生效);
  // 否则 0 legacy 工序 → 强制两点 (skip=true); 有 legacy 工序 → 保留当前选择。
  if (hasActiveWorkflow.value) {
    planForm.value.skipProcessReporting = false;
  } else if (productWorkProcessList.value.length === 0) {
    planForm.value.skipProcessReporting = true;
  }
}

function handleProductChange(productTypeId: string) {
  if (!productTypeId) return;
  const product = productTypes.value.find((p: TableRow) => p.id === productTypeId);
  if (product) {
    // A product may carry a stable customer id. Only that id may auto-select a customer;
    // a free-form relatedCustomer name is not sufficient ownership evidence.
    if (!planForm.value.customerId && product.customerId) {
      handlePlanCustomerChange(String(product.customerId));
    }
  }
  // Load BOM processes for the selected product
  loadBomProcesses(productTypeId);
}

// ========== raw-centric 多SKU (2026-07-13): 多选「生产成品」→ 解析共用 raw workflow ==========
// 非 CUSTOMER_ORDER 来源专用 (设计文档 §13)。
const resolvingWorkflow = ref(false);
const workflowCandidateDialogVisible = ref(false);
const confirmingWorkflowCandidate = ref(false);
const pendingCandidateWorkflowId = ref<number | null>(null);
let preferredWorkflowSelection: {
  workflowId: number;
  definitionVersion: number;
  targetKey: string;
} | null = null;
let workflowResolveDebounceTimer: ReturnType<typeof setTimeout> | null = null;
// 防竞态: 快速切换选中集时, 老的 in-flight 请求可能比新请求晚回, 用单调递增代
// (generation) 丢弃过期响应, 避免旧结果覆盖新选择的解析结果。
let workflowResolveGeneration = 0;

function resetWorkflowResolutionState() {
  workflowResolveGeneration += 1;
  planForm.value.resolutionMode = '';
  planForm.value.resolvedCandidates = [];
  planForm.value.jointSupersets = [];
  planForm.value.selectedCandidateWorkflowId = null;
  workflowCandidateDialogVisible.value = false;
  pendingCandidateWorkflowId.value = null;
}

async function applyResolvedWorkflowCandidate(candidate: WorkflowResolutionCandidate) {
  const bindingProductTypeId = workflowCandidateBindingProductTypeId(
    candidate,
    planForm.value.targetFinishedGoodIds,
  );
  if (!bindingProductTypeId) {
    planForm.value.productTypeId = '';
    hasActiveWorkflow.value = false;
    productWorkProcessList.value = [];
    ElMessage.error(t('production.plans_list.message_3a9dad51dbf7'));
    return;
  }
  planForm.value.productTypeId = bindingProductTypeId;
  await loadBomProcesses(bindingProductTypeId);
}

function openWorkflowCandidateDialog() {
  const candidates = planForm.value.resolvedCandidates;
  if (candidates.length === 0) return;
  pendingCandidateWorkflowId.value = planForm.value.selectedCandidateWorkflowId
    ?? (candidates.length === 1 ? candidates[0].workflowId : null);
  workflowCandidateDialogVisible.value = true;
}

function candidateExtraOutputNames(candidate: WorkflowResolutionCandidate): string[] {
  const extraIds = new Set(workflowCandidateExtraOutputs(
    candidate,
    planForm.value.targetFinishedGoodIds,
  ));
  return (candidate.terminalOutputs || [])
    .filter((output) => extraIds.has(output.productTypeId))
    .map((output) => output.productName || resolveOutputProductLabel(output.productTypeId));
}

async function confirmWorkflowCandidateSelection() {
  const candidate = planForm.value.resolvedCandidates.find(
    (item) => item.workflowId === pendingCandidateWorkflowId.value,
  );
  if (!candidate) {
    ElMessage.warning(t('production.plans_list.message_674fd2581546'));
    return;
  }
  const completeOutputs = workflowCandidateOutputIds(candidate);
  const extraOutputs = workflowCandidateExtraOutputs(
    candidate,
    planForm.value.targetFinishedGoodIds,
  );
  if (extraOutputs.length > 0) {
    preferredWorkflowSelection = {
      workflowId: candidate.workflowId,
      definitionVersion: candidate.definitionVersion,
      targetKey: completeOutputs.join(','),
    };
    workflowCandidateDialogVisible.value = false;
    planForm.value.targetFinishedGoodIds = completeOutputs;
    ElMessage.success(t('production.plans_list.message_60b787dd8aba'));
    return;
  }
  confirmingWorkflowCandidate.value = true;
  try {
    planForm.value.selectedCandidateWorkflowId = candidate.workflowId;
    await applyResolvedWorkflowCandidate(candidate);
    workflowCandidateDialogVisible.value = false;
  } finally {
    confirmingWorkflowCandidate.value = false;
  }
}

function goToWorkflowConfig(finishedGoodIds: string[]) {
  const productTypeId = finishedGoodIds.length === 1 ? finishedGoodIds[0] : '';
  router.push(productTypeId
    ? `/system/product-processes?productTypeId=${encodeURIComponent(productTypeId)}`
    : '/system/product-processes');
}

async function resolveTargetFinishedGoods(ids: string[]): Promise<void> {
  if (!factoryId.value || ids.length === 0) return;
  const myGeneration = ++workflowResolveGeneration;
  resolvingWorkflow.value = true;
  try {
    const res = await resolveWorkflowByOutputs(factoryId.value, ids);
    if (myGeneration !== workflowResolveGeneration) return; // 已被更新的选择取代, 丢弃过期响应
    if (!res.success || !res.data) {
      handleCatchError(new Error(res.message || '解析工序图失败'), '解析工序图失败');
      return;
    }
    const resolution = resolvePlanWorkflowCandidates(ids, res.data.candidates || []);
    planForm.value.resolutionMode = resolution.mode;
    planForm.value.resolvedCandidates = resolution.candidates;
    planForm.value.jointSupersets = resolution.jointSupersets;
    const preferred = preferredWorkflowSelection?.targetKey === ids.join(',')
      ? resolution.candidates.find((candidate) =>
          candidate.workflowId === preferredWorkflowSelection?.workflowId
          && candidate.definitionVersion === preferredWorkflowSelection?.definitionVersion)
      : undefined;
    preferredWorkflowSelection = null;
    if (preferred) {
      planForm.value.selectedCandidateWorkflowId = preferred.workflowId;
      await applyResolvedWorkflowCandidate(preferred);
    } else if (resolution.candidates.length === 1 && resolution.candidates[0].exactMatch) {
      const candidate = resolution.candidates[0];
      planForm.value.selectedCandidateWorkflowId = candidate.workflowId;
      await applyResolvedWorkflowCandidate(candidate);
    } else if (resolution.candidates.length > 0) {
      // 同层歧义，或唯一候选包含额外联产成品：必须弹窗显式确认。
      planForm.value.selectedCandidateWorkflowId = null;
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
      pendingCandidateWorkflowId.value = resolution.candidates.length === 1
        ? resolution.candidates[0].workflowId : null;
      workflowCandidateDialogVisible.value = true;
    } else {
      // 无候选或数据异常出现多个激活候选时，必须显式处理，不能退回 legacy 产品路径。
      planForm.value.selectedCandidateWorkflowId = null;
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
    }
  } catch (e) {
    if (myGeneration === workflowResolveGeneration) {
      preferredWorkflowSelection = null;
      handleCatchError(e, '解析工序图失败');
    }
  } finally {
    if (myGeneration === workflowResolveGeneration) resolvingWorkflow.value = false;
  }
}

watch(
  () => planForm.value.targetFinishedGoodIds.join(','),
  () => {
    if (workflowResolveDebounceTimer) clearTimeout(workflowResolveDebounceTimer);
    resetWorkflowResolutionState();
    const ids = planForm.value.targetFinishedGoodIds.slice();
    if (ids.length === 0) {
      planForm.value.productTypeId = '';
      hasActiveWorkflow.value = false;
      productWorkProcessList.value = [];
      return;
    }
    workflowResolveDebounceTimer = setTimeout(() => {
      void resolveTargetFinishedGoods(ids);
    }, 300);
  }
);

/**
 * 「这个成品由一张**联产**工艺图产出」—— 有它时就不该再说「未找到工艺图」。
 *
 * 🔴 2026-09-02 实测(本机 UAT, 汉堡肉产品族): 只选「美式谷饲牛肉饼」时, 后端
 * `resolve-by-outputs` 返回的是 `匹配到包含额外联产成品的 Workflow，请确认完整产出集合`
 * + 4 个终端产出, 而界面显示「未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置」
 * ⇒ 把用户支去重配一张**本来就正确**的图。
 *
 * ⛔ 精确匹配那条规则(D6)不动 —— 这里只负责把「为什么不行」说清楚, 并给一键补选。
 */
const jointSupersetHint = computed(() => {
  const superset = planForm.value.jointSupersets[0];
  if (!superset) return null;
  const requested = new Set(planForm.value.targetFinishedGoodIds);
  const outputs = superset.terminalOutputs || [];
  return {
    allIds: outputs.map((o) => o.productTypeId).filter(Boolean),
    extraNames: outputs.filter((o) => !requested.has(o.productTypeId))
      .map((o) => o.productName).filter(Boolean),
  };
});

/** 一键把联产图的全部终端产出补进「生产成品」—— 补完 watch 会自动重解析。 */
function adoptJointSupersetOutputs() {
  const hint = jointSupersetHint.value;
  if (!hint || hint.allIds.length === 0) return;
  planForm.value.targetFinishedGoodIds = hint.allIds.slice();
}

const nonSoSubmitBlocked = computed(() => {
  if (planForm.value.sourceType === 'CUSTOMER_ORDER') return false;
  const ids = planForm.value.targetFinishedGoodIds;
  if (ids.length === 0) return false;
  if (resolvingWorkflow.value || planForm.value.resolutionMode === '') return true;
  if (planForm.value.resolutionMode === 'NONE') return true;
  return planForm.value.selectedCandidateWorkflowId == null;
});
// ========== /raw-centric 多SKU ==========

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}

function handleRefresh() {
  searchForm.value = { keyword: '', status: 'UNFINISHED' };
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

function handleCreate() {
  const today = todayStr();
  planForm.value = {
    productTypeId: '',
    plannedQuantity: undefined,
    aiRequestedUnit: '',
    // T135 ITEM #3: 计划生产日默认 = 今天 + 1 天
    plannedDate: tomorrowStr(),
    notes: '',
    estimatedWorkers: undefined,
    assignedSupervisorId: '',
    customerId: '',
    sourceCustomerName: '',
    outputOwnership: 'COMPANY_OWNED',
    materialSupplyMode: 'FACTORY_SUPPLIED',
    processName: '',
    batchDate: today,
    sourceType: 'SAFETY_STOCK',
    sourceOrderId: '',
    sourceOrderItemId: '',
    sourceOrderItemIds: [],
    // SP5: 重置追加SO列表
    extraSourceOrderIds: [],
    customFields: {} as TableRow,
    // Fable 审计修复 (问题1 — 多租户安全): 默认值取工厂配置 (F006=true 两点 / 其他=false 逐道),
    // 不再全系统硬编码 true。产品加载后若 0 工序仍自动锁定为 true (loadBomProcesses)。
    skipProcessReporting: skipReportingFactoryDefault.value,
    // raw-centric 多SKU (2026-07-13): 重置多选成品 + 工序图解析状态。
    targetFinishedGoodIds: [],
    resolvedCandidates: [],
    jointSupersets: [],
    selectedCandidateWorkflowId: null,
    resolutionMode: '',
  };
  productWorkProcessList.value = [];
  hasActiveWorkflow.value = false;
  resolvingWorkflow.value = false;
  workflowCandidateDialogVisible.value = false;
  pendingCandidateWorkflowId.value = null;
  preferredWorkflowSelection = null;
  workflowResolveGeneration += 1;
  planCreateRequestId.value = newPlanCreateRequestId();
  planCreateFingerprint.value = '';
  // T135 ITEM #1: 默认 CUSTOMER_ORDER — 预加载可选销售订单列表
  if (selectableSalesOrders.value.length === 0) loadSelectableSalesOrders();
  dialogVisible.value = true;
}

function goAddOrderItems(soId: string) {
  if (!soId) return;
  goCreate(`/sales/orders/${soId}?editItems=1`, {
    reopen: `/production/plans?reopenPlan=1&planSO=${soId}`,
  });
}

// 建厂就绪页「新建第一张生产计划」: 打开新建计划并带入已生效工艺的成品。
async function maybeOpenFromReadiness(): Promise<boolean> {
  const prefill = readReadinessPrefill(route.query, ['productTypeId']);
  if (!prefill) return false;
  handleCreate();
  await loadProductTypes();
  if (!pickPrefilled(productTypes.value, prefill.productTypeId)) {
    ElMessage.warning(t('production.plans_list.message_e919d0761c9b'));
  } else {
    planForm.value.productTypeId = String(prefill.productTypeId);
    handleProductChange(String(prefill.productTypeId));
  }
  const { fromReadiness: _a, productTypeId: _b, ...rest } = route.query as Record<string, unknown>;
  await router.replace({ query: rest as any });
  return true;
}

async function maybeReopenFromQuery() {
  if (await maybeOpenFromReadiness()) return;
  const q = route.query;
  const soId = (q.reopenPlan === '1' && typeof q.planSO === 'string' && q.planSO)
    || (q.action === 'create' && typeof q.salesOrderId === 'string' && q.salesOrderId)
    || '';
  if (!soId) return;
  // handleCreate is synchronous and fires loadSelectableSalesOrders without await.
  // We must await the SO list ourselves before calling handleSalesOrderSelect.
  handleCreate();
  await loadSelectableSalesOrders();
  planForm.value.sourceType = 'CUSTOMER_ORDER';
  planForm.value.sourceOrderId = String(soId);
  handleSalesOrderSelect(String(soId));
  const { reopenPlan: _a, planSO: _b, salesOrderId: _c, action: _d, ...rest } = q as Record<string, unknown>;
  router.replace({ query: rest as any });
}

async function submitPlan() {
  if (!planForm.value.plannedDate) {
    ElMessage.warning(t('production.plans_list.message_4cd57edb4325'));
    return;
  }
  if (!factoryId.value) return;

  // 以销定产 (2026-06-24): 来源=销售订单 → 按选中的产品行批量建计划 (每行一张, 产品/数量取自 SO 行)。
  const isSoDriven = planForm.value.sourceType === 'CUSTOMER_ORDER';
  if (isSoDriven) {
    if (!planForm.value.sourceOrderId) {
      // 防呆: 红框锚定 + 自动聚焦空字段 + sticky 可关闭错误提示 (不是 3 秒自动消失的 toast)。
      sourceOrderIdError.value = true;
      void nextTick(() => sourceOrderSelectRef.value?.focus());
      ElMessage({ message: t('production.plans_list.message_c802f7260f7a'), type: 'error', duration: 0, showClose: true });
      return;
    }
    if (!planForm.value.sourceOrderItemIds || planForm.value.sourceOrderItemIds.length === 0) {
      ElMessage.warning(t('production.plans_list.message_9c66a18999f9'));
      return;
    }
    if (!planForm.value.batchDate) {
      ElMessage.warning(t('production.plans_list.message_12b4d83680a7'));
      return;
    }
    dialogLoading.value = true;
    try {
      const payload = {
        ...bindPlanCreateRequestId({
          sourceOrderId: planForm.value.sourceOrderId,
          itemIds: planForm.value.sourceOrderItemIds,
          // 🔴 2026-08-19 (测试同学 8-03「合并订单无法合并数量」: 主 10 + 关联 5 → 计划只有 10):
          //    「合并订单」选择器一直在往 planForm.extraSourceOrderIds 里塞订单, 而这个
          //    payload **从来没带上它** —— 收集了却不发, 是个 write-only 的死状态
          //    (形态 B: 机制在, 没接上)。三个症状(数量没合并 / 计划详情只关联主订单 /
          //    关联订单不显示「生产中」)都是这一个根因。
          extraSourceOrderIds: planForm.value.extraSourceOrderIds.length > 0
            ? planForm.value.extraSourceOrderIds
            : undefined,
          plannedDate: planForm.value.plannedDate,
          batchDate: planForm.value.batchDate,
          estimatedWorkers: planForm.value.estimatedWorkers,
          assignedSupervisorId: planForm.value.assignedSupervisorId || undefined,
          notes: planForm.value.notes || undefined,
          skipProcessReporting: planForm.value.skipProcessReporting,
        }),
        confirmPotentialDuplicate: false,
      };
      const createBatchOnce = () => post(
        `/${factoryId.value}/production-plans/batch-from-so`,
        payload,
        {
          headers: { 'Idempotency-Key': planCreateRequestId.value },
          _handledErrorCodes: ['POTENTIAL_DUPLICATE_PRODUCTION_PLAN'],
        },
      );
      let response: Awaited<ReturnType<typeof createBatchOnce>>;
      try {
        response = await createBatchOnce();
      } catch (error: unknown) {
        const duplicate = error as {
          status?: number;
          code?: string;
          response?: { status?: number; data?: { code?: string; message?: string } };
          message?: string;
        };
        const status = duplicate.status ?? duplicate.response?.status;
        const code = duplicate.code ?? duplicate.response?.data?.code;
        if (status !== 409 || code !== 'POTENTIAL_DUPLICATE_PRODUCTION_PLAN') {
          throw error;
        }
        await ElMessageBox.confirm(
          duplicate.response?.data?.message || duplicate.message || '近期存在内容相同的生产计划，仍要继续创建吗？',
          t('production.plans_list.message_6af3054656fe'),
          {
            type: 'warning',
            confirmButtonText: t('production.plans_list.confirmbuttontext_165bfed05e6b'),
            cancelButtonText: t('production.plans_list.cancelbuttontext_84eef12f27a4'),
            distinguishCancelAndClose: true,
          },
        );
        planCreateRequestId.value = newPlanCreateRequestId();
        payload.clientRequestId = planCreateRequestId.value;
        payload.confirmPotentialDuplicate = true;
        response = await createBatchOnce();
      }
      if (response.success) {
        const n = Array.isArray(response.data) ? response.data.length : planForm.value.sourceOrderItemIds.length;
        ElMessage.success(`已生成 ${n} 张生产计划`);
        dialogVisible.value = false;
        loadData();
      } else {
        ElMessage.error(response.message || '创建失败');
      }
    } catch (error: any) {
      console.error('[以销定产批量建计划失败]', error);
    } finally {
      dialogLoading.value = false;
    }
    return;
  }

  // 非 SO 来源：完全匹配优先；无完全匹配时允许最小联产超集并要求确认完整产出。
  if (planForm.value.targetFinishedGoodIds.length === 0) {
    ElMessage.warning(t('production.plans_list.message_4ac660bdcdf9'));
    return;
  }
  if (nonSoSubmitBlocked.value) {
    if (planForm.value.resolutionMode === 'NONE') {
      const hint = jointSupersetHint.value;
      ElMessage.warning(hint
        ? `这个成品由联产工艺图产出，需要把 ${hint.extraNames.join('、')} 一起选上`
        : planForm.value.targetFinishedGoodIds.length === 1
          ? '未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置'
          : '未找到共享的工序 Workflow，请分开创建生产计划');
    } else {
      ElMessage.warning(t('production.plans_list.message_25c17fc65350'));
    }
    return;
  }
  if (!planForm.value.productTypeId || !resolvedWorkflowCandidate.value) {
    ElMessage.warning(t('production.plans_list.message_2862d71a4a31'));
    return;
  }
  // 存货生产按实际报工/小结确认产量，计划数量仅作可选参考；其他来源继续严格要求正数。
  const needQty = plannedQuantityRequired(planForm.value.sourceType);
  if (needQty && (
    !Number.isFinite(planForm.value.plannedQuantity)
    || Number(planForm.value.plannedQuantity) <= 0
  )) {
    ElMessage.warning(t('production.plans_list.message_c11365d8764e'));
    return;
  }
  dialogLoading.value = true;
  try {
    const {
      resolvedCandidates: _resolvedCandidates,
      selectedCandidateWorkflowId: _selectedCandidateWorkflowId,
      resolutionMode,
      targetFinishedGoodIds,
      aiRequestedUnit: _aiRequestedUnit,
      ...rest
    } = planForm.value;
    let payload: Record<string, unknown> = { ...rest };
    payload.plannedQuantity = plannedQuantityForPayload(
      planForm.value.sourceType,
      planForm.value.plannedQuantity,
    );
    if (resolutionMode === 'SINGLE_OUTPUT' || resolutionMode === 'SHARED_MULTI_OUTPUT') {
      payload.targetFinishedGoodIds = targetFinishedGoodIds;
      payload.selectedWorkflowId = resolvedWorkflowCandidate.value?.workflowId;
      payload.selectedWorkflowVersion = resolvedWorkflowCandidate.value?.definitionVersion;
    }
    payload = bindPlanCreateRequestId(payload);
    const createOnce = (confirmPotentialDuplicate: boolean) => post(
      `/${factoryId.value}/production-plans`,
      { ...payload, confirmPotentialDuplicate },
      {
        headers: { 'Idempotency-Key': planCreateRequestId.value },
        _handledErrorCodes: ['POTENTIAL_DUPLICATE_PRODUCTION_PLAN'],
      },
    );
    let response: Awaited<ReturnType<typeof createOnce>>;
    try {
      response = await createOnce(false);
    } catch (error: unknown) {
      const duplicate = error as {
        status?: number;
        code?: string;
        response?: { status?: number; data?: { code?: string; message?: string } };
        message?: string;
      };
      const status = duplicate.status ?? duplicate.response?.status;
      const code = duplicate.code ?? duplicate.response?.data?.code;
      if (status !== 409 || code !== 'POTENTIAL_DUPLICATE_PRODUCTION_PLAN') {
        throw error;
      }
      await ElMessageBox.confirm(
        duplicate.response?.data?.message || duplicate.message || '近期存在内容相同的生产计划，仍要继续创建吗？',
        t('production.plans_list.message_6af3054656fe'),
        {
          type: 'warning',
          confirmButtonText: t('production.plans_list.confirmbuttontext_165bfed05e6b'),
          cancelButtonText: t('production.plans_list.cancelbuttontext_84eef12f27a4'),
          distinguishCancelAndClose: true,
        },
      );
      // A duplicate warning is not a successful insert. Explicit confirmation starts a
      // new creation intent and therefore must use a fresh idempotency key.
      planCreateRequestId.value = newPlanCreateRequestId();
      payload.clientRequestId = planCreateRequestId.value;
      response = await createOnce(true);
    }
    if (response.success) {
      const createdPlanNumber = (response.data as { planNumber?: string } | undefined)?.planNumber;
      ElMessage.success(createdPlanNumber ? `创建成功：${createdPlanNumber}` : '创建成功');
      dialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '创建失败');
    }
  } catch (error: unknown) {
    // 409 WORKFLOW_RESOLUTION_NOT_COVERED: 双保险 — interceptor 已弹 sticky toast,
    // 这里额外给「去产品工艺画布」跳转按钮 (Rule 5: 不留死胡同)。
    const err = error as { status?: number; code?: string; message?: string };
    if (err?.status === 409 && err?.code === 'WORKFLOW_SELECTED_VERSION_CHANGED') {
      resetWorkflowResolutionState();
      ElMessageBox.alert(
        err.message || '所选 Workflow 已被切换或失效，请重新选择',
        t('production.plans_list.message_4cc14707b3f1'),
        { confirmButtonText: t('production.plans_list.confirmbuttontext_52332b5bd714'), type: 'warning' },
      ).then(() => {
        void resolveTargetFinishedGoods(planForm.value.targetFinishedGoodIds.slice());
      }).catch(() => { /* 用户关闭，保持提交阻塞 */ });
    } else if (err?.status === 409 && err?.code === 'WORKFLOW_RESOLUTION_NOT_COVERED') {
      ElMessageBox.confirm(
        err.message || (planForm.value.targetFinishedGoodIds.length === 1
          ? '未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置'
          : '未找到共享的工序 Workflow，请分开创建生产计划'),
        t('production.plans_list.message_cffe26226a5e'),
        { confirmButtonText: t('production.plans_list.confirmbuttontext_2195342e2da0'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738'), type: 'warning' }
      ).then(() => {
        goToWorkflowConfig(planForm.value.targetFinishedGoodIds);
      }).catch(() => { /* 用户取消, 静默 */ });
    } else {
      // Interceptor shows specific toast; dedupe fallback
      console.error('[失败]', error);
    }
  } finally {
    dialogLoading.value = false;
  }
}

async function handleStart(row: TableRow) {
  if (actionLoading.value) return;
  try {
    await ElMessageBox.confirm(t('production.plans_list.message_01ad7eab1416'), t('production.plans_list.message_f56c6c82203b'), { type: 'warning' });
    actionLoading.value = true;
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/start`, undefined, SHORTAGE_HANDLED);
    if (response.success) {
      ElMessage.success(t('production.plans_list.message_e15a89c1dbfa'));
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    // S4-08: 工厂开了「缺料时拦开工」, 后端以 SHORTAGE_CASE_OPEN 拦下 —— 由强制开工对话框一处说明缺什么,
    // 并给一次「填写原因后强制开工」的机会, 原因记进缺料异常的处理记录。
    if (error?.code === 'SHORTAGE_CASE_OPEN') {
      await forceStart(row, error.message);
      return;
    }
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

/**
 * 开工 / 转批次第一次请求带上它: SHORTAGE_CASE_OPEN 由下面的强制开工对话框接手, 拦截器不再同时弹一条
 * 「操作被拒绝」(2026-11-03 验收: 两个提示叠在一起)。其它错误照常由拦截器提示。
 */
const SHORTAGE_HANDLED = { _handledErrorCodes: ['SHORTAGE_CASE_OPEN'] };

/**
 * S4-08 强制开工原因。开工 / 转批次 / 逐道录入 (都会让待处理计划开工) 被 SHORTAGE_CASE_OPEN 拦下时共用;
 * detail 是后端说明缺什么的那句话 (拦截器不再弹它, 由这里展示)。用户放弃返回 null。
 */
async function askForceStartReason(detail?: string): Promise<string | null> {
  try {
    const r = await ElMessageBox.prompt(
      (detail ? `${detail}\n\n` : '')
        + '这个计划关联的缺料还没解决。确需先开工，请写明原因（会记入「缺料异常」的处理记录）。',
      t('production.plans_list.message_eac9e9af1715'),
      {
        inputValidator: (v: string) => (v && v.trim().length > 0) || '原因不能为空',
        confirmButtonText: t('production.plans_list.confirmbuttontext_3c6bdfd8a51d'),
        cancelButtonText: t('production.plans_list.cancelbuttontext_73dc65e0b4ef'),
        type: 'warning',
      },
    );
    return (r as { value: string }).value.trim();
  } catch {
    return null;
  }
}

/**
 * 转批次 = 待处理计划开工, 同样会被「缺料时拦开工」拦下 (2026-11-03 验收 D-S410-01)。
 * 拦下时问原因并带原因重试一次; 用户放弃返回 null, 其它错误照常抛出 (拦截器已提示)。
 */
async function postCreateBatch(row: TableRow) {
  const url = `/${factoryId.value}/production-plans/${row.id}/create-batch`;
  try {
    return await post(url, undefined, SHORTAGE_HANDLED);
  } catch (error: any) {
    if (error?.code !== 'SHORTAGE_CASE_OPEN') throw error;
    const reason = await askForceStartReason(error.message);
    if (!reason) return null;
    return await post(url, { forceStartReason: reason });
  }
}

async function forceStart(row: TableRow, detail?: string) {
  const reason = await askForceStartReason(detail);
  if (!reason) return; // 用户放弃
  actionLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/start`, { forceStartReason: reason });
    if (response.success) {
      ElMessage.success(t('production.plans_list.message_8dc911e41419'));
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') console.error('[强制开工失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 逐工序电子表格抽屉 (SP-F) ====================
const entryDrawerVisible = ref(false);
const entryRow = ref<any>(null);
// 与列表「生产成品」列同源; 多产出计划的 productTypeName 是内部锚点名, 不能直接显示。
const entryProductLabel = computed(() =>
  (entryRow.value ? planSheetProductLabel(planFinishedProductNames(entryRow.value)) : ''));
// 防呆: 关抽屉前若有未保存草稿行 (ProcessDataTable 聚合上报) 二次确认, 见 handleEntryDrawerBeforeClose
const processSheetRef = ref<{ hasUnsavedRows: boolean } | null>(null);

function isStepwise(row: any): boolean {
  return row.skipProcessReporting === false;
}

async function openProcessEntry(row: any) {
  const rowId = String(row?.id || '');
  if (entryPreparingPlanId.value !== null) return;
  entryPreparingPlanId.value = rowId;
  // raw-centric 多SKU (2026-07-13): workflow 驱动的计划逐道报工需先 materialize 批次
  // (逐道抽屉只读 workflow-config, 无批次 → 返空 → 落回 legacy archetype tab)。
  // 存货生产(SAFETY_STOCK)无「转批次」按钮, 手动/预测虽有但用户也可能直接点逐道录入 →
  // 这里对「PENDING + 产品有 active workflow」的计划自动 create-batch, 使逐道抽屉直接出全工序。
  // 非 workflow 或已转批次(IN_PROGRESS)计划不受影响 (跳过, 保持原行为)。
  try {
    // ⚠️ 这里以前只认 PENDING, 注释写着「已转批次(IN_PROGRESS)计划跳过」——
    //   但 `/start`(开始生产) 会把状态置成 IN_PROGRESS **而不建批次**,
    //   于是「开始生产 → 逐道录入」的计划批次永远建不出来, 抽屉空白且结单也被拒, 彻底卡死。
    //   IN_PROGRESS ≠ 已转批次。后端 create-batch 已改为「IN_PROGRESS 且尚未有批次」才补建,
    //   已有批次的仍返回 409, 所以这里放行是安全的。
    const planStatus = String(row?.status || '').toUpperCase();
    const needsBatch = planStatus === 'PENDING' || planStatus === 'IN_PROGRESS';
    if (row?.id && row?.productTypeId && needsBatch && factoryId.value) {
      const act = await get<{ enabled?: boolean } | null>(
        `/${factoryId.value}/product-process-workflows/${row.productTypeId}/activation`);
      if (act.success && act.data && act.data.enabled === true) {
        // 后端幂等: 已有批次时直接返回既有批次(不报错), 所以这里每次调用都是安全的。
        const br = await postCreateBatch(row);
        if (br === null) return; // 缺料未解决且用户放弃强制开工: 不开抽屉
        if (br.success) {
          row.status = 'IN_PROGRESS';
          loadData();
        }
      }
    }
  } catch (error) {
    // 无法确认/物化 Workflow 时不能打开 legacy 逐道录入，否则同一计划会混用两套工序单位。
    console.error('[production-plan] Workflow 批次物化失败，阻止逐道录入', error);
    // 业务/API 错误已由请求拦截器显示具体原因；只有纯网络错误才补充 fallback，避免双 toast。
    handleCatchError(error, '生产批次准备失败，请检查网络后重试');
    return;
  } finally {
    if (entryPreparingPlanId.value === rowId) {
      entryPreparingPlanId.value = null;
    }
  }
  showProcessEntry(row);
}

function showProcessEntry(row: TableRow): void {
  entryRow.value = row;
  entryDrawerVisible.value = true;
}

function onEntrySubmitted() { loadData(); }

// Bug2 fix (fool-proof-design.md Rule 5 no-dead-end): 生产批次详情页 (batches/detail.vue)
// 之前是死胡同 — 没有任何入口能跳到这里的「逐道录入」抽屉。它现在带
// `?openProcessEntryPlan=<planId>` 跳过来, 这里直接按 id 查计划 (不依赖 tableData 分页,
// 目标计划可能不在当前页) 并自动打开抽屉, 完了清掉 query 防止刷新重复弹。
async function maybeOpenProcessEntryFromQuery() {
  const planId = route.query.openProcessEntryPlan;
  if (!planId || typeof planId !== 'string' || !factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/production-plans/${planId}`);
    if (res.success && res.data) {
      openProcessEntry(res.data);
    } else {
      ElMessage.error(t('production.plans_list.message_058c479fd70b'));
    }
  } catch {
    // Interceptor already shows sticky toast for ApiError.
  } finally {
    const { openProcessEntryPlan: _drop, ...rest } = route.query;
    router.replace({ query: rest });
  }
}

async function handleEntryDrawerBeforeClose(done: () => void) {
  const dirty = processSheetRef.value?.hasUnsavedRows === true;
  if (await confirmDiscardIfDirty(dirty)) done();
}

// ==================== 核对结单 dialog (#742 / 6.12 revised) ====================
interface SettlementRawConsumptionForm {
  materialBatchId: string;
  productTypeId?: string | null;
  materialTypeId?: string | null;
  batchNumber?: string | null;
  // 缺陷 48 (A): 手工选批次时立即带上物料名, 供摘要行渲染「批次号 · 物料名」,
  // ⛔ 不靠 materialBatchOptions 在渲染那一刻还在(它可能已经清空/没加载过)。
  materialName?: string | null;
  unit?: string | null;
  warehouseId?: string | null;
  workflowMaterialNodeId?: string | null;
  workflowInputPortId?: string | null;
  quantity: number;
  note: string;
  source?: 'PROCESS_REPORT' | 'MANUAL';
}

interface SettlementWipConsumptionForm {
  semiFinishedInventoryId: number | null;
  quantity: number;
  note: string;
}

interface SettlementCompleteForm {
  actualQuantity: number;
  /** 不良品清点结论三态: COUNTED / NONE_CONFIRMED / NOT_COUNTED。空串=尚未回答。 */
  defectCountOutcome: '' | 'COUNTED' | 'NONE_CONFIRMED' | 'NOT_COUNTED';
  defectQuantity: number;
  semiFinishedOutputQuantity: number;
  manualOverrideReason: string;
  otherManualOverrideReason: string;
  rawMaterialConsumptions: SettlementRawConsumptionForm[];
  semiFinishedConsumptions: SettlementWipConsumptionForm[];
  workerCount: number;
  workMinutes: number;
  laborSegments: Array<{
    workerId?: number | null;
    workerName?: string | null;
    workType?: string | null;
    minutes: number;
    headcount: number;
    note?: string | null;
  }>;
  laborDeferredReason: string;
  varianceReason: string;
  otherVarianceReason: string;
}

interface MaterialBatchOption {
  id: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialTypeName?: string | null;
  batchNumber?: string | null;
  currentQuantity?: number | string | null;
  quantity?: number | string | null;
  quantityUnit?: string | null;
  unit?: string | null;
  warehouseId?: string | null;
}

interface FactoryWarehouseOption {
  id: string;
  code?: string | null;
  type?: string | null;
  name?: string | null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function isMaterialBatchOption(value: unknown): value is MaterialBatchOption {
  return isRecord(value) && typeof value.id === 'string';
}

function extractMaterialBatchRows(payload: unknown): MaterialBatchOption[] {
  if (Array.isArray(payload)) {
    return payload.filter(isMaterialBatchOption);
  }
  if (isRecord(payload) && Array.isArray(payload.content)) {
    return payload.content.filter(isMaterialBatchOption);
  }
  return [];
}

const SETTLEMENT_VARIANCE_REASON_OPTIONS = [
  { value: '现场称重差异', label: t('production.plans_list.label_38123951fa67') },
  { value: '原料状态差异', label: t('production.plans_list.label_d280d6358e84') },
  { value: '临时调整产量', label: t('production.plans_list.label_b9114ffc26cc') },
  { value: '客户/销售变更', label: t('production.plans_list.label_c180f93beca7') },
  { value: '其他', label: t('production.plans_list.label_a871e12fcc51') },
];
const completeDialogVisible = ref(false);
const completeRow = ref<TableRow | null>(null);
// 幂等 key 在打开弹框时一次性生成，重试提交复用同一 key (防呆 Rule 4)
const settlementIdempotencyKey = ref('');

// ===== 阅读汇总 dialog =====
const summaryDialogVisible = ref(false);
const summaryPlanId = ref('');
const summaryPlanNumber = ref('');
const summaryProductName = ref('');
function handleOpenSummary(row: TableRow) {
  summaryPlanId.value = String(row.id || '');
  summaryPlanNumber.value = String(row.planNumber || '');
  summaryProductName.value = String(row.productName || row.productTypeName || '');
  summaryDialogVisible.value = true;
}
// ===== /阅读汇总 =====

// ===== Phase 2A: 核对结单自动预填 (报工→核算自动化) =====
type SettlementIssueSeverity = 'BLOCKER' | 'INFO';
interface SettlementPrefillIssue {
  code: string;
  message: string;
  field?: string | null;
  severity?: SettlementIssueSeverity;
}
interface SettlementPrefillResponse {
  prefill: {
    actualFinishedQuantity?: number | null;
    actualSemiFinishedQuantity?: number | null;
    quantityVarianceReason?: string | null;
    rawMaterialConsumptions?: Array<{
      materialBatchId?: string | null;
      productTypeId?: string | null;
      materialTypeId?: string | null;
      batchNumber?: string | null;
      unit?: string | null;
      warehouseId?: string | null;
      workflowMaterialNodeId?: string | null;
      workflowInputPortId?: string | null;
      quantity?: number | null;
      note?: string | null;
    }> | null;
    laborSegments?: Array<{
      workerId?: number | null;
      workerName?: string | null;
      workType?: string | null;
      minutes?: number | null;
      headcount?: number | null;
      note?: string | null;
    }> | null;
    laborDeferredReason?: string | null;
    terminalOutputs?: Array<{
      productTypeId?: string | null;
      batchNumber?: string | null;
      quantity?: number | null;
      unit?: string | null;
    }> | null;
  } | null;
  audit: { clean: boolean; issues: SettlementPrefillIssue[] } | null;
}
const settlementPrefillLoading = ref(false);
const settlementPrefillIssues = ref<SettlementPrefillIssue[]>([]);
const settlementPrefillApplied = ref(false);
// 后端审计 clean (只看 BLOCKER); 未带入数据时 false
const settlementPrefillCleanFromServer = ref(false);
// Clean prefill remains the fast path, but operators may explicitly add WIP
// consumed outside this plan.  The opt-in flag keeps the normal dialog compact.
const settlementManualWipVisible = ref(false);
const settlementTerminalOutputs = ref<Array<{
  productTypeId: string;
  batchNumber: string;
  quantity: number;
  unit: string;
}>>([]);
// 阻塞级问题 (令一键确认禁用 + 顶部 warning)
const settlementBlockerIssues = computed(
  () => settlementPrefillIssues.value.filter((i) => (i.severity ?? 'BLOCKER') === 'BLOCKER'),
);
// 提示级问题 (仅展示, 不阻塞)
const settlementInfoIssues = computed(
  () => settlementPrefillIssues.value.filter((i) => i.severity === 'INFO'),
);
// 后端审计 clean=true 即可走确认式结单；正常路径不再重复校验或回传客户端事实副本。
// 缺失工时、批次或产量会由后端审计作为 BLOCKER 返回，再进入下方异常补录路径。
const settlementPrefillClean = computed(
  () => settlementPrefillApplied.value
    && settlementPrefillCleanFromServer.value,
);
// 某字段是否被预填审计标记为待人工补全 (BLOCKER 级才高亮)
function settlementFieldHasIssue(field: string): boolean {
  return settlementBlockerIssues.value.some((i) => i.field === field);
}
const materialBatchListLoading = ref(false);
const materialBatchOptions = ref<MaterialBatchOption[]>([]);
// 缺陷 48 (B): 结单可用库存参考覆盖的仓 (RAW/LOGISTICS/WORKSHOP), 与后端写路径守卫
// ensureMaterialBatchAllowedForSettlement 同口径 —— 不是单一仓, 也不是工厂全部仓。
const settlementEligibleWarehouses = ref<FactoryWarehouseOption[]>([]);

// ===== 防呆 Rule 1: 原料领用下拉 BOM 预过滤 (#结单原料越权 fix) =====
// 后端结单提交守卫 (ensureMaterialBatchAllowedForSettlement) 会在提交后才 409 拒绝不属于
// 产品当前 BOM 的原料批次。这里提前拿到同一份判定结果，把下拉预先收窄到 BOM 允许的批次，
// 用户选不到 BOM 外的批次，而不是提交后才被告知选错了。后端 409 守卫原样保留作兜底 (defense in depth)。
interface SettlementBomEligibilityResponse {
  restricted: boolean;
  bomFound: boolean;
  materialTypeIds: string[];
}
// null = 尚未加载/加载失败 → 视为不限制 (回退显示全部批次, 后端 409 仍兜底校验)
const settlementBomEligibility = ref<SettlementBomEligibilityResponse | null>(null);
const bomFilteredMaterialBatchOptions = computed(() => {
  const eligibility = settlementBomEligibility.value;
  if (!eligibility || !eligibility.restricted) {
    return materialBatchOptions.value;
  }
  if (!eligibility.bomFound || eligibility.materialTypeIds.length === 0) {
    return [];
  }
  const allowed = new Set(eligibility.materialTypeIds.map(String));
  return materialBatchOptions.value.filter(
    (batch) => !!batch.materialTypeId && allowed.has(String(batch.materialTypeId)),
  );
});
// Rule 5 (dead-end → next action): 产品无生效 BOM / BOM 无原料明细时，说明原因 + 引导去配置 BOM
const bomFilterBlockedMessage = computed(() => {
  const eligibility = settlementBomEligibility.value;
  if (!eligibility || !eligibility.restricted) return '';
  if (!eligibility.bomFound) {
    return '该产品当前没有生效 BOM，不能核对原料领用；请先配置该产品的 BOM 配方。';
  }
  if (eligibility.materialTypeIds.length === 0) {
    return '该产品当前 BOM 没有原料明细，不能核对原料领用；请先维护 BOM 原料明细。';
  }
  return '';
});
function goConfigBomFromSettlement() {
  router.push('/production/bom');
}
const completeForm = ref<SettlementCompleteForm>({
  actualQuantity: 0,
  defectCountOutcome: '',
  defectQuantity: 0,
  semiFinishedOutputQuantity: 0,
  manualOverrideReason: '',
  otherManualOverrideReason: '',
  rawMaterialConsumptions: [],
  semiFinishedConsumptions: [],
  workerCount: 0,
  workMinutes: 0,
  laborSegments: [],
  laborDeferredReason: '',
  varianceReason: '',
  otherVarianceReason: '',
});
const completeProductName = computed(() => {
  const r = completeRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const completePlanNumber = computed(() => {
  const r = completeRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const completeAdvisory = computed(() => {
  const r = completeRow.value;
  if (!r) return null;
  return getPlanAdvisory(r) || null;
});
const completePlannedQuantity = computed(() => {
  const r = completeRow.value;
  if (!r) return 0;
  return Number(r.plannedQuantity || 0);
});
const completePlannedUnit = computed(() => {
  const r = completeRow.value;
  return r?.plannedUnit ? String(r.plannedUnit) : null;
});
const completeActualQuantity = computed(() => Number(completeForm.value.actualQuantity || 0));
const completeSemiFinishedOutputQuantity = computed(() => Number(completeForm.value.semiFinishedOutputQuantity || 0));
const completeIsOverPlan = computed(() => {
  const planned = completePlannedQuantity.value;
  return planned > 0 && completeActualQuantity.value > planned;
});
const completeVarianceReasonReady = computed(() => {
  if (!completeIsOverPlan.value) return true;
  const reason = completeForm.value.varianceReason;
  if (!reason) return false;
  if (reason !== '其他') return true;
  return Boolean(completeForm.value.otherVarianceReason.trim());
});

function selectedMaterialBatch(batchId: string): MaterialBatchOption | null {
  return materialBatchOptions.value.find((b) => b.id === batchId) ?? null;
}

// 缺陷 48 (A): 用户在下拉里选中批次的那一刻, 把 batchNumber/物料名带到这一行上,
// ⛔ 不等到结单真正提交、下一次 prefill 回填才有 —— 见 settlementRawBatchDisplay.ts。
function applyPickedRawMaterialBatch(line: SettlementRawConsumptionForm): void {
  applyPickedMaterialBatchToLine(line, selectedMaterialBatch(line.materialBatchId));
}

function selectedWipForSettlement(wipId: number | null): WipInventoryItem | null {
  if (wipId == null) return null;
  return wipList.value.find((w) => w.id === wipId) ?? null;
}

function materialBatchAvailable(batch: MaterialBatchOption | null): number {
  if (!batch) return 0;
  return Number(batch.currentQuantity ?? batch.quantity ?? 0) || 0;
}

function materialBatchUnit(batch: MaterialBatchOption | null): string {
  return String(batch?.quantityUnit || batch?.unit || '（单位未配置）');
}

function materialBatchLabel(batch: MaterialBatchOption): string {
  const name = batch.materialName || batch.materialTypeName || batch.materialTypeId || '原料';
  return `${name} | ${batch.batchNumber || batch.id} | 可用 ${materialBatchAvailable(batch)}${materialBatchUnit(batch)}`;
}

function rawLineDisabledReason(line: SettlementRawConsumptionForm, index: number): string {
  if (!line.materialBatchId) return `第 ${index + 1} 行原料领用必须选择批次`;
  if (!line.quantity || line.quantity <= 0) return `第 ${index + 1} 行原料领用数量必须大于 0`;
  // 逐道报工行已经由后端按计划、BOM、仓库和当前库存审计，不依赖“当前可选批次”下拉。
  // 报工后批次通常已从物流仓转入生产仓，不能因为它不在物流仓下拉里就丢弃真实领用。
  if (line.source === 'PROCESS_REPORT') return '';
  const batch = selectedMaterialBatch(line.materialBatchId);
  if (!batch) return `第 ${index + 1} 行原料批次不存在或未加载`;
  const available = materialBatchAvailable(batch);
  if (line.quantity > available) return `第 ${index + 1} 行原料领用超出可用量 ${available}${materialBatchUnit(batch)}`;
  return '';
}

function wipLineDisabledReason(line: SettlementWipConsumptionForm, index: number): string {
  if (line.semiFinishedInventoryId == null) return `第 ${index + 1} 行半成品领用必须选择 WIP`;
  const wip = selectedWipForSettlement(line.semiFinishedInventoryId);
  if (!wip) return `第 ${index + 1} 行半成品库存不存在或未加载`;
  if (!line.quantity || line.quantity < 0.01) return `第 ${index + 1} 行半成品领用数量必须至少为 0.01`;
  const available = Number(wip.availableQuantity || 0);
  if (line.quantity > available) return `第 ${index + 1} 行半成品领用超出可用量 ${available}${wip.unit || ''}`;
  return '';
}

const firstRawLineError = computed(() => {
  for (let i = 0; i < completeForm.value.rawMaterialConsumptions.length; i += 1) {
    const error = rawLineDisabledReason(completeForm.value.rawMaterialConsumptions[i], i);
    if (error) return error;
  }
  return '';
});
const firstWipLineError = computed(() => {
  for (let i = 0; i < completeForm.value.semiFinishedConsumptions.length; i += 1) {
    const error = wipLineDisabledReason(completeForm.value.semiFinishedConsumptions[i], i);
    if (error) return error;
  }
  return '';
});
// 人工补录结单: 事实来自手填而不是报工, 后端要求原因并只认生产写权限 (审计落在结单上)。
const MANUAL_OVERRIDE_REASON_OPTIONS = ['报工数据缺失，人工补录', '外部半成品领用补录', '报工与实际不符，人工核对', '其他'];
const settlementNeedsManualReason = computed(
  () => !settlementPrefillClean.value || completeForm.value.semiFinishedConsumptions.length > 0,
);
const settlementManualReason = computed(() => (
  completeForm.value.manualOverrideReason === '其他'
    ? completeForm.value.otherManualOverrideReason.trim()
    : completeForm.value.manualOverrideReason
));
const settlementManualBlockedReason = computed(() => {
  if (!settlementNeedsManualReason.value) return '';
  // 权限在后端判 (production:read_write, 403 文案说得清); 这里混进权限会抬「判不了的分支」上限
  return settlementManualReason.value ? '' : '请选择人工补录原因';
});
const completeSubmitDisabledReason = computed(() => {
  if (settlementPrefillClean.value && firstWipLineError.value) return firstWipLineError.value;
  if (settlementManualBlockedReason.value) return settlementManualBlockedReason.value;
  if (completeActualQuantity.value + completeSemiFinishedOutputQuantity.value <= 0) return '请填写有效的成品或半成品实际产量';
  if (!completeVarianceReasonReady.value) return '实际产量超过计划时必须选择差异原因';
  const hasMaterialLines = completeForm.value.rawMaterialConsumptions.length > 0
    || completeForm.value.semiFinishedConsumptions.length > 0;
  if (!hasMaterialLines) return '请至少录入一条原料/半成品实际领用明细';
  if (firstRawLineError.value) return firstRawLineError.value;
  if (firstWipLineError.value) return firstWipLineError.value;
  if (completeForm.value.laborSegments.length === 0 && !completeForm.value.laborDeferredReason) {
    if (!completeForm.value.workerCount || completeForm.value.workerCount <= 0) return '请录入实际人数';
    if (!completeForm.value.workMinutes || completeForm.value.workMinutes <= 0) return '请录入实际工时分钟';
  }
  return '';
});
const completeCanSubmit = computed(
  () => settlementPrefillClean.value
    ? !firstWipLineError.value && !settlementManualBlockedReason.value
    : completeSubmitDisabledReason.value === '',
);

function buildSettlementIdempotencyKey(row: TableRow): string {
  // 生成并保存到 settlementIdempotencyKey，确保重试时复用同一 key (防呆 Rule 4)
  const planId = String(row.id || row.planNumber || 'unknown');
  const key = `web-settle-${planId}-${Date.now()}`;
  settlementIdempotencyKey.value = key;
  return key;
}

function addRawConsumptionLine() {
  completeForm.value.rawMaterialConsumptions.push({
    materialBatchId: '',
    quantity: 0,
    note: '',
    source: 'MANUAL',
  });
}

function removeRawConsumptionLine(index: number) {
  completeForm.value.rawMaterialConsumptions.splice(index, 1);
}

function addWipConsumptionLine() {
  settlementManualWipVisible.value = true;
  completeForm.value.semiFinishedConsumptions.push({
    semiFinishedInventoryId: null,
    quantity: 0,
    note: '',
  });
}

async function openSettlementManualWip() {
  settlementManualWipVisible.value = true;
  if (!factoryId.value) return;
  wipListLoading.value = true;
  try {
    const res = await listAvailableWip(factoryId.value);
    if (res.success && Array.isArray(res.data)) {
      wipList.value = res.data.filter((wip) => Number(wip.availableQuantity || 0) > 0);
    } else {
      wipList.value = [];
      ElMessage({ message: res.message || '半成品可用库存加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
    }
  } catch (error: unknown) {
    handleCatchError(error, '半成品可用库存加载失败');
  } finally {
    wipListLoading.value = false;
  }
}

function removeWipConsumptionLine(index: number) {
  completeForm.value.semiFinishedConsumptions.splice(index, 1);
}

function isFactoryWarehouseOption(value: unknown): value is FactoryWarehouseOption {
  return isRecord(value) && typeof value.id === 'string';
}

/**
 * 结单可用库存参考该覆盖哪些仓 —— 缺陷 48 (B)。
 *
 * 不能只挑 `pickRawWarehouse` 选中的**一个**仓：后端结单写路径守卫
 * `ensureMaterialBatchAllowedForSettlement` (ProductionPlanServiceImpl.java)
 * 同时放行 RAW/LOGISTICS/WORKSHOP 三类仓的批次 —— 领料会把原料从原料仓
 * 物理迁到生产仓 (WORKSHOP) 后再报工消耗，那是合法料流。只查一个仓会把
 * 已经在生产仓、报工在用的批次漏掉，显示假的「可用 0」。
 * 也不能查工厂全部仓：FINISHED/RD/WIP 等类型后端结单闸不认，算进「可用」
 * 会显示一个提交必被 409 拒绝的假可用量。见 rawWarehousePicker.ts。
 */
async function ensureSettlementEligibleWarehouses(): Promise<FactoryWarehouseOption[]> {
  if (settlementEligibleWarehouses.value.length > 0) return settlementEligibleWarehouses.value;
  if (!factoryId.value) return [];
  const res = await get<unknown>(`/${factoryId.value}/factory/warehouses`);
  if (!res.success || !Array.isArray(res.data)) {
    ElMessage({ message: res.message || '原料仓列表加载失败，请刷新后重试', type: 'error', duration: 0, showClose: true });
    return [];
  }
  const warehouses = res.data.filter(isFactoryWarehouseOption);
  const eligible = pickSettlementEligibleWarehouses(warehouses);
  if (eligible.length === 0) {
    ElMessage({ message: t('production.plans_list.message_db514a5e5ffd'), type: 'error', duration: 0, showClose: true });
    return [];
  }
  settlementEligibleWarehouses.value = eligible;
  return eligible;
}

function settlementWarehouseName(warehouseId: string | null | undefined): string | null {
  if (!warehouseId) return null;
  return settlementEligibleWarehouses.value.find((w) => w.id === warehouseId)?.name || null;
}

/**
 * 同一物料在多个(结单认可的)仓库都有可用量时，给出「主仓 40 · 生产仓 10」
 * 这样的分布说明，取代旧的单仓「可用 0」误判 (缺陷 48 (B))。只有一个仓有
 * 库存时不必要地展示分布反而添噪音，故只在覆盖 ≥2 个仓时才显示。
 */
function materialWarehouseBreakdown(batch: MaterialBatchOption | null): string {
  if (!batch || !batch.materialTypeId) return '';
  const entries = materialBatchOptions.value
    .filter((b) => b.materialTypeId === batch.materialTypeId && !!b.warehouseId)
    .map((b) => ({ warehouseId: String(b.warehouseId), quantity: materialBatchAvailable(b) }));
  const distinctWarehouses = new Set(entries.map((e) => e.warehouseId));
  if (distinctWarehouses.size < 2) return '';
  return summarizeStockByWarehouse(entries, settlementWarehouseName);
}

async function loadSettlementInventoryOptions(row: TableRow) {
  if (!factoryId.value) return;
  materialBatchListLoading.value = true;
  wipListLoading.value = true;
  settlementBomEligibility.value = null;
  const planId = row.id ? String(row.id) : '';
  try {
    const eligibleWarehouses = await ensureSettlementEligibleWarehouses();
    if (eligibleWarehouses.length === 0) {
      materialBatchOptions.value = [];
      wipList.value = [];
      return;
    }
    const eligibleWarehouseIds = new Set(eligibleWarehouses.map((w) => w.id));
    const bomEligibilityPromise = planId
      ? get<SettlementBomEligibilityResponse>(
          `/${factoryId.value}/production-plans/${planId}/settlement-bom-eligibility`,
        )
      : Promise.resolve(null);
    const [materialRes, wipRes, bomRes] = await Promise.allSettled([
      // 不传 warehouseId —— 结单认可 RAW/LOGISTICS/WORKSHOP 三类仓, 单仓范围会漏掉
      // 已领入生产仓的批次; 下面按 eligibleWarehouseIds 过滤, 不会因此纳入 FINISHED/RD/WIP。
      get<unknown>(`/${factoryId.value}/material-batches/status/AVAILABLE`, {
        params: {
          size: 200,
        },
      }),
      listAvailableWip(factoryId.value),
      bomEligibilityPromise,
    ]);
    if (materialRes.status === 'fulfilled' && materialRes.value.success) {
      materialBatchOptions.value = extractMaterialBatchRows(materialRes.value.data)
        .filter((batch) => materialBatchAvailable(batch) > 0)
        .filter((batch) => !!batch.warehouseId && eligibleWarehouseIds.has(batch.warehouseId));
    } else {
      materialBatchOptions.value = [];
      ElMessage({ message: t('production.plans_list.message_0022ca25bf9c'), type: 'error', duration: 0, showClose: true });
    }
    if (wipRes.status === 'fulfilled' && wipRes.value.success && Array.isArray(wipRes.value.data)) {
      wipList.value = wipRes.value.data.filter((wip) => Number(wip.availableQuantity || 0) > 0);
    } else {
      wipList.value = [];
      ElMessage({ message: t('production.plans_list.message_6b8dd063f174'), type: 'error', duration: 0, showClose: true });
    }
    // 防呆 Rule 1: BOM 预过滤加载失败时不阻塞结单 —— 回退为不限制显示全部批次，
    // 后端结单提交守卫仍会 409 兜底校验 BOM (defense in depth)，只是失去"预先显示边界"的效果。
    if (bomRes.status === 'fulfilled' && bomRes.value && bomRes.value.success && bomRes.value.data) {
      settlementBomEligibility.value = bomRes.value.data;
    } else {
      settlementBomEligibility.value = null;
      if (planId) {
        ElMessage({
          message: t('production.plans_list.message_ce279c94993f'),
          type: 'warning',
          duration: 0,
          showClose: true,
        });
      }
    }
  } finally {
    materialBatchListLoading.value = false;
    wipListLoading.value = false;
  }
}

function buildRawConsumptionPayload() {
  return completeForm.value.rawMaterialConsumptions.map((line) => {
    const batch = selectedMaterialBatch(line.materialBatchId);
    return {
      materialBatchId: line.materialBatchId,
      productTypeId: line.productTypeId || null,
      materialTypeId: line.materialTypeId || batch?.materialTypeId || null,
      batchNumber: line.batchNumber || batch?.batchNumber || null,
      quantity: line.quantity,
      unit: line.unit || materialBatchUnit(batch),
      warehouseId: line.warehouseId || batch?.warehouseId || null,
      workflowMaterialNodeId: line.workflowMaterialNodeId || null,
      workflowInputPortId: line.workflowInputPortId || null,
      note: line.note || null,
    };
  });
}

function buildWipConsumptionPayload() {
  return completeForm.value.semiFinishedConsumptions.map((line) => {
    const wip = selectedWipForSettlement(line.semiFinishedInventoryId);
    return {
      semiFinishedInventoryId: line.semiFinishedInventoryId,
      batchNumber: wip?.intermediateBatchNo || null,
      quantity: line.quantity,
      unit: wip?.unit || null,
      note: line.note || null,
    };
  });
}

/**
 * Phase 2A: 从逐道报工 derive 出结单汇总 + 审计, 灌入 completeForm。
 * 报工原料行由后端按计划/BOM/仓库/库存审计后原样带入，不再依赖当前物流仓下拉。
 * 批次报工后已进入生产仓是正常料流，不能把“不在物流仓下拉”误判为批次不存在。
 */
async function applySettlementPrefill(row: TableRow) {
  settlementPrefillApplied.value = false;
  settlementPrefillIssues.value = [];
  settlementPrefillCleanFromServer.value = false;
  settlementManualWipVisible.value = false;
  settlementTerminalOutputs.value = [];
  if (!factoryId.value) return;
  settlementPrefillLoading.value = true;
  try {
    const res = await get<SettlementPrefillResponse>(
      `/${factoryId.value}/production-plans/${row.id}/settlement-prefill`,
    );
    if (!res.success || !res.data) {
      // 预填失败不阻塞结单 — 退回手填, 给出提示 (BLOCKER, 让人确认手填)
      settlementPrefillIssues.value = [{
        code: 'PREFILL_LOAD_FAILED',
        message: res.message || '自动预填加载失败，请手工录入结单数据。',
        severity: 'BLOCKER',
      }];
      return;
    }
    const issues = res.data.audit?.issues ?? [];
    const prefill = res.data.prefill;
    const carriedIssues: SettlementPrefillIssue[] = [...issues];
    let serverClean = res.data.audit?.clean ?? false;

    if (prefill) {
      settlementTerminalOutputs.value = (prefill.terminalOutputs ?? [])
        .map((output) => ({
          productTypeId: String(output.productTypeId || ''),
          batchNumber: String(output.batchNumber || ''),
          quantity: Number(output.quantity || 0),
          unit: String(output.unit || ''),
        }))
        .filter((output) => output.quantity > 0);
      // 实际产量 (derive 不出时留空, 用户手填)
      if (prefill.actualFinishedQuantity != null) {
        completeForm.value.actualQuantity = Number(prefill.actualFinishedQuantity);
      }
      if (prefill.actualSemiFinishedQuantity != null) {
        completeForm.value.semiFinishedOutputQuantity = Number(prefill.actualSemiFinishedQuantity);
      }
      // 差异原因 (自动"无显著差异"或留空)
      if (prefill.quantityVarianceReason) {
        const matched = SETTLEMENT_VARIANCE_REASON_OPTIONS.some(
          (o) => o.value === prefill.quantityVarianceReason,
        );
        if (matched) {
          completeForm.value.varianceReason = prefill.quantityVarianceReason;
        } else {
          completeForm.value.varianceReason = '其他';
          completeForm.value.otherVarianceReason = prefill.quantityVarianceReason;
        }
      }
      // 原料领用: 后端审计后的逐道报工是计划结算事实，完整保留批次与单位元数据。
      const rawLines = prefill.rawMaterialConsumptions ?? [];
      completeForm.value.rawMaterialConsumptions = [];
      for (const line of rawLines) {
        const batchId = line.materialBatchId ? String(line.materialBatchId) : '';
        const qty = Number(line.quantity || 0);
        if (!batchId || qty <= 0) continue;
        completeForm.value.rawMaterialConsumptions.push({
          materialBatchId: batchId,
          productTypeId: line.productTypeId ? String(line.productTypeId) : null,
          materialTypeId: line.materialTypeId ? String(line.materialTypeId) : null,
          batchNumber: line.batchNumber ? String(line.batchNumber) : null,
          unit: line.unit ? String(line.unit) : null,
          warehouseId: line.warehouseId ? String(line.warehouseId) : null,
          workflowMaterialNodeId: line.workflowMaterialNodeId
            ? String(line.workflowMaterialNodeId)
            : null,
          workflowInputPortId: line.workflowInputPortId
            ? String(line.workflowInputPortId)
            : null,
          quantity: qty,
          note: line.note || '自动带入自逐道报工',
          source: 'PROCESS_REPORT',
        });
      }
      // 人效: 保留逐道工时段用于真实结算，同时显示汇总，不再伪造成一条“PC文员汇总”。
      const segs = prefill.laborSegments ?? [];
      completeForm.value.laborSegments = segs
        .map((s) => ({
          workerId: s.workerId ?? null,
          workerName: s.workerName ?? null,
          workType: s.workType ?? null,
          minutes: Number(s.minutes || 0),
          headcount: Number(s.headcount || 1),
          note: s.note ?? null,
        }))
        .filter((s) => s.minutes > 0 && s.headcount > 0);
      completeForm.value.laborDeferredReason = prefill.laborDeferredReason || '';
      if (segs.length > 0) {
        let totalMinutes = 0;
        let maxHeadcount = 0;
        for (const s of segs) {
          totalMinutes += Number(s.minutes || 0);
          maxHeadcount = Math.max(maxHeadcount, Number(s.headcount || 0));
        }
        if (totalMinutes > 0) completeForm.value.workMinutes = totalMinutes;
        if (maxHeadcount > 0) completeForm.value.workerCount = maxHeadcount;
      }
    }
    settlementPrefillIssues.value = carriedIssues;
    settlementPrefillCleanFromServer.value = serverClean;
    settlementPrefillApplied.value = true;
  } catch (error: unknown) {
    settlementPrefillIssues.value = [{
      code: 'PREFILL_ERROR',
      message: t('production.plans_list.message_4a41dca50c4c'),
      severity: 'BLOCKER',
    }];
    console.error('[结单预填失败]', error);
  } finally {
    settlementPrefillLoading.value = false;
  }
}

async function handleComplete(row: TableRow) {
  if (actionLoading.value) return;
  completeRow.value = row;
  // 幂等 key 在打开弹框时生成一次，submitComplete 直接复用，重试不会产生新 key (防呆 Rule 4)
  buildSettlementIdempotencyKey(row);
  // 默认填充计划数量, 便于一键提交; 用户可改
  completeForm.value = {
    actualQuantity: Number(row.plannedQuantity || 0),
    // ⛔ 默认【空】而不是 NOT_COUNTED —— 空表示「还没被问」, 选 NOT_COUNTED 是人主动
    //    答的「没清点」。两者在台账上是不同的责任, 不许由默认值代答。
    defectCountOutcome: '',
    defectQuantity: 0,
    semiFinishedOutputQuantity: 0,
    manualOverrideReason: '',
    otherManualOverrideReason: '',
    rawMaterialConsumptions: [],
    semiFinishedConsumptions: [],
    workerCount: Number(row.estimatedWorkers || 0),
    workMinutes: 0,
    laborSegments: [],
    laborDeferredReason: '',
    varianceReason: '',
    otherVarianceReason: '',
  };
  settlementPrefillApplied.value = false;
  settlementPrefillIssues.value = [];
  settlementPrefillCleanFromServer.value = false;
  settlementTerminalOutputs.value = [];
  completeDialogVisible.value = true;
  // 正常结单只拉取逐道报工汇总；仅存在阻塞异常时，才加载可选批次/WIP供人工补录。
  await applySettlementPrefill(row);
  if (settlementBlockerIssues.value.length > 0) {
    await loadSettlementInventoryOptions(row);
  }
  // 防呆: 预填完成后才快照 (自动带入的数据不算"用户改动"), 之后用户手改才计入 dirty
  completeFormSnapshot.value = JSON.stringify(completeForm.value);
}

// 防呆: 核对结单 dialog 关闭前若表单已被用户改动 (相对预填完成时的快照) 二次确认
const completeFormSnapshot = ref('');
function completeFormIsDirty(): boolean {
  return JSON.stringify(completeForm.value) !== completeFormSnapshot.value;
}
async function requestCloseCompleteDialog() {
  if (await confirmDiscardIfDirty(completeFormIsDirty())) {
    completeDialogVisible.value = false;
  }
}
async function handleCompleteDialogBeforeClose(done: () => void) {
  if (await confirmDiscardIfDirty(completeFormIsDirty())) done();
}

async function submitComplete() {
  if (!completeRow.value) return;
  if (!completeCanSubmit.value) {
    ElMessage({
      message: completeSubmitDisabledReason.value || '请先完成结单核对项',
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  actionLoading.value = true;
  try {
    const hasManualWip = settlementPrefillClean.value
      && completeForm.value.semiFinishedConsumptions.length > 0;
    const payload = settlementPrefillClean.value && !hasManualWip
      ? {
          idempotencyKey: settlementIdempotencyKey.value,
          confirm: true,
          // 报工里没有剩余半成品这个事实, 只能来自这个输入框; 填了就要发, 否则后端按 0 结单 (H5)。
          ...(completeSemiFinishedOutputQuantity.value > 0
            ? { actualSemiFinishedQuantity: completeSemiFinishedOutputQuantity.value }
            : {}),
        }
      : {
          // Exception override path. The normal path above never echoes server facts
          // back; only a human-resolved blocker uses these fields.
          // confirm 必须是 true: 后端 confirm=false 只预览不结单 (D-S315-02)。
          idempotencyKey: settlementIdempotencyKey.value,
          confirm: true,
          manualOverride: true,
          manualOverrideReason: settlementManualReason.value,
          actualFinishedQuantity: completeActualQuantity.value,
          defectCountOutcome: completeForm.value.defectCountOutcome || null,
          defectQuantity: completeForm.value.defectCountOutcome === 'COUNTED'
            ? Number(completeForm.value.defectQuantity || 0)
            : null,
          actualSemiFinishedQuantity: Number(completeForm.value.semiFinishedOutputQuantity || 0),
          quantityUnit: completeRow.value.unit || completeRow.value.quantityUnit || null,
          quantityVarianceReason: completeForm.value.varianceReason,
          quantityVarianceNote: completeForm.value.varianceReason === '其他'
            ? completeForm.value.otherVarianceReason.trim()
            : null,
          rawMaterialConsumptions: buildRawConsumptionPayload(),
          semiFinishedConsumptions: buildWipConsumptionPayload(),
          auxiliaryConsumptions: [] as Array<Record<string, unknown>>,
          laborDeferredReason: completeForm.value.laborDeferredReason || null,
          laborSegments: completeForm.value.laborSegments.length > 0
            ? completeForm.value.laborSegments
            : completeForm.value.laborDeferredReason
              ? []
              : [{
                  workerName: 'PC文员补录',
                  workType: '生产结单异常补录',
                  minutes: Number(completeForm.value.workMinutes || 0),
                  headcount: Number(completeForm.value.workerCount || 1),
                }],
        };
    const response = await post(
      `/${factoryId.value}/production-plans/${completeRow.value.id}/settle`,
      payload,
    );
    if (response.success) {
      ElMessage.success(response.message || '生产结单已提交，下一步请仓库确认入库');
      completeDialogVisible.value = false;
      searchForm.value.status = 'COMPLETED';
      loadData();
    } else {
      ElMessage({ message: response.message || '结单提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error: unknown) {
    if (error !== 'cancel') console.error('[提交失败]', error);
    handleCatchError(error, '结单提交失败，请检查网络');
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 仓库确认入库 dialog (6.12 中转仓) ====================
const RECEIPT_VARIANCE_REASON_OPTIONS = [
  { value: '仓库实收短少', label: t('production.plans_list.label_cdbf06e8e17c') },
  { value: '生产少交', label: t('production.plans_list.label_cced89629fd4') },
  { value: '称重误差', label: t('production.plans_list.label_b9fe24807cf7') },
  { value: '破损/返工待处理', label: t('production.plans_list.label_1c52dde3d99a') },
  { value: '其他', label: t('production.plans_list.label_a871e12fcc51') },
];
const RECEIPT_RESPONSIBILITY_OPTIONS = [
  { value: 'PRODUCTION', label: t('production.plans_list.label_67ddf0744ca4') },
  { value: 'WAREHOUSE', label: t('production.plans_list.label_c99c1e62d011') },
  { value: 'WEIGHING_ERROR', label: t('production.plans_list.label_b9fe24807cf7') },
];
const CLEARING_REASON_OPTIONS = [
  { value: '生产侧已处理', label: t('production.plans_list.label_62adc1a24339') },
  { value: '仓库侧已处理', label: t('production.plans_list.label_3bb2f0e914e3') },
  { value: '称重误差已复核', label: t('production.plans_list.label_0b0ac357e615') },
  { value: '财务已确认差异', label: t('production.plans_list.label_10fd9dd7f25b') },
  { value: '其他', label: t('production.plans_list.label_a871e12fcc51') },
];
const receiptDialogVisible = ref(false);
const receiptLoading = ref(false);
const receiptRow = ref<TableRow | null>(null);
// 幂等 key 在打开弹框时一次性生成，重试提交复用同一 key (防呆 Rule 4)
const receiptIdempotencyKey = ref('');
const receiptSettlement = ref<ProductionSettlementStatus | null>(null);
const receiptForm = ref({
  receivedQuantity: 0,
  quantityUnit: '',
  outputLines: [] as Array<ProductionOutputLine & {
    receivedQuantity: number;
    originalReceivedQuantity: number;
  }>,
  varianceReason: '',
  otherVarianceReason: '',
  responsibilitySide: '',
  varianceNote: '',
});
const clearingDialogVisible = ref(false);
const clearingLoading = ref(false);
const clearingRow = ref<TableRow | null>(null);
const clearingSettlement = ref<ProductionSettlementStatus | null>(null);
const clearingForm = ref({
  clearingReason: '',
  otherClearingReason: '',
  clearingNote: '',
});

function getSettlementStatus(row: TableRow): ProductionSettlementStatus | null {
  return settlementStatusMap.value[String(row.id)] ?? null;
}

function getSettlementStatusError(row: TableRow): string {
  return settlementStatusErrorMap.value[String(row.id)] || '';
}

/** 该计划没有仓库确认单 —— 合法状态(存货生产走小结), ⛔ 不是加载失败。 */
function isSettlementAbsent(row: TableRow): boolean {
  return settlementStatusAbsentMap.value[String(row.id)] === true;
}

function postingStatusText(status?: string | null): string {
  const map: Record<string, string> = {
    PENDING_WAREHOUSE_RECEIPT: '待仓库确认',
    PENDING_CLEARING: '中转挂账',
    POSTED_WITH_TOLERANCE: '已入库(容差)',
    POSTED: '已入库',
    PENDING_POSTING: '待过账',
  };
  return map[String(status || '')] || String(status || '未结单');
}

function postingStatusType(status?: string | null): string {
  const map: Record<string, string> = {
    PENDING_WAREHOUSE_RECEIPT: 'warning',
    PENDING_CLEARING: 'danger',
    POSTED_WITH_TOLERANCE: 'success',
    POSTED: 'success',
    PENDING_POSTING: 'info',
  };
  return map[String(status || '')] || 'info';
}

function canConfirmReceipt(row: TableRow): boolean {
  const settlement = getSettlementStatus(row);
  return canConfirmReceiptWrite.value
    && String(row.status || '').toUpperCase() === 'COMPLETED'
    && settlement?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT';
}

function canClearTransit(row: TableRow): boolean {
  const settlement = getSettlementStatus(row);
  return canConfirmReceiptWrite.value
    && String(row.status || '').toUpperCase() === 'COMPLETED'
    && settlement?.postingStatus === 'PENDING_CLEARING';
}

const costRecalculatingPlanId = ref<string | null>(null);

function canRecalculateSettledCost(row: TableRow): boolean {
  return canWrite.value && canViewPrice.value
    && String(row.status || '').toUpperCase() === 'COMPLETED'
    && ['POSTED', 'POSTED_WITH_TOLERANCE'].includes(String(getSettlementStatus(row)?.postingStatus || ''));
}

async function handleRecalculateSettledCost(row: TableRow) {
  const planId = String(row.id || '');
  if (!planId || !factoryId.value) return;
  await ElMessageBox.confirm(
    t('production.plans_list.message_edd7ceb5fb7c'),
    t('production.plans_list.message_10382eb89660'),
    { type: 'warning', confirmButtonText: t('production.plans_list.confirmbuttontext_62481d7e7a11'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') },
  );
  costRecalculatingPlanId.value = planId;
  try {
    const res = await recalculateSettledPlanCosts(factoryId.value, planId);
    if (!res.success || !res.data) {
      ElMessage({ message: res.message || '成本重算失败', type: 'error', duration: 0, showClose: true });
      return;
    }
    const d = res.data;
    const sharedUnitCost = d.sharedUnitCost == null ? '—' : `¥${Number(d.sharedUnitCost).toFixed(4)}`;
    let successMessage = `成本重算完成：总成本 ¥${Number(d.authoritativeTotalCost).toFixed(4)}，单位成本 ${sharedUnitCost}；同步成品 ${d.finishedGoodsBatchCount} 行、回料 ${d.reworkWipCount} 行`;
    // 客供主料成本口径 (a)（owner 裁定，2026-09-04）：这次重算里按加工口径显式记 0 的客供料
    // (customerSuppliedItems) 之前只有字段没有消费方——直接展示后端已经拼好的人话版
    // (customerSuppliedNote)，⛔ 不在前端重新拼一遍格式（本仓形态 D：同一个东西有两份必定漂）。
    if (d.customerSuppliedNote) {
      successMessage += `；${d.customerSuppliedNote}`;
    }
    // 辅料先估（owner 裁定，2026-09-06）：这次总成本里含按 BOM 配比估出来的辅料时必须说出来——
    // 估的和实际领用的在同一个数字里长得一模一样。同上：直接展示后端拼好的 estimatedAuxNote。
    // ⛔ 那个中文标签的字面量只许存在于后端常量 OrderCostBreakdownService.ESTIMATED_AUX_LABEL；
    //    本文件里连注释都不写它，好让 settledCostRecalculation.source.spec.ts 的阴性对照
    //    （断言本文件搜不到那个字面量）真的能红（本仓形态 D）。
    if (d.estimatedAuxNote) {
      successMessage += `；${d.estimatedAuxNote}`;
    }
    const hasCaveat = Boolean(d.customerSuppliedNote || d.estimatedAuxNote);
    ElMessage({ message: successMessage, type: 'success', duration: hasCaveat ? 8000 : 3000, showClose: true });
    await loadSettlementStatuses([row]);
  } catch (error) {
    if (String(error).includes('cancel')) return;
    handleCatchError(error, '成本重算失败');
  } finally {
    costRecalculatingPlanId.value = null;
  }
}

// canStopPlan / canCancelPlan 已并入 productionMoreActions.ts —— 停产/取消的判据必须和
// 「更多」的显隐闸出自同一处, 不再在本文件里留第二份。

const receiptProductName = computed(() => {
  const r = receiptRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const receiptPlanNumber = computed(() => {
  const r = receiptRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const receiptReportedQuantity = computed(() =>
  Number(receiptSettlement.value?.actualFinishedQuantity || 0)
);
const receiptHasOutputLines = computed(() => receiptForm.value.outputLines.length > 0);
// 纪律排查 A11: 多收怎么处理由本厂配置; 读不到规则时按默认硬拦 (与服务端一致), ⛔ 不按宽处理
const receiptOverReceiveRule = ref<OverReceiveRule>({ ...DEFAULT_OVER_RECEIVE_RULE });
const receiptMaxQuantity = computed<number | undefined>(() =>
  overReceiveMax(receiptReportedQuantity.value, receiptOverReceiveRule.value)
);
const receiptOverReceiveHelp = computed(() =>
  overReceiveHelp(receiptReportedQuantity.value, receiptDisplayUnit.value, receiptOverReceiveRule.value)
);
const receiptExceedsAllowed = computed(() =>
  overReceiveExceeds(receiptReportedQuantity.value, receiptReceivedQuantity.value, receiptOverReceiveRule.value)
);
const receiptReceivedQuantity = computed(() =>
  Number(receiptForm.value.receivedQuantity || 0)
);
const receiptUnit = computed(() =>
  canonicalUnitCode(receiptForm.value.quantityUnit || receiptSettlement.value?.quantityUnit) || '件'
);
const receiptDisplayUnit = computed(() => displayUnit(receiptUnit.value));
const receiptUsesInterimFinishedGoods = computed(() =>
  Boolean(receiptSettlement.value?.finishedGoodsBatchId)
  && receiptSettlement.value?.postingStatus === 'PENDING_WAREHOUSE_RECEIPT'
);
const receiptTolerance = computed(() => {
  const unit = receiptUnit.value.toLowerCase();
  if (unit === 'kg' || unit === '公斤' || unit === '千克') return 10;
  if (unit === 'g' || unit === '克') return 10000;
  return 0;
});
const receiptVarianceQuantity = computed(() =>
  receiptReportedQuantity.value - receiptReceivedQuantity.value
);
const receiptNeedsReason = computed(() => {
  const variance = Math.abs(receiptVarianceQuantity.value);
  return variance > 0 && variance > receiptTolerance.value;
});
const receiptSubmitDisabledReason = computed(() => {
  if (!receiptSettlement.value) return '请先加载生产结单状态';
  if (receiptHasOutputLines.value) {
    const invalidIndex = receiptForm.value.outputLines.findIndex((line) => {
      const received = Number(line.receivedQuantity || 0);
      return received <= 0;
    });
    if (invalidIndex >= 0) {
      return `第 ${invalidIndex + 1} 行本次实收必须大于 0`;
    }
    return '';
  }
  if (!receiptReceivedQuantity.value || receiptReceivedQuantity.value <= 0) return '请输入仓库实际收到数量';
  if (receiptExceedsAllowed.value) {
    return overReceiveBlockedMessage(
      receiptReportedQuantity.value, receiptDisplayUnit.value, receiptOverReceiveRule.value,
    );
  }
  if (receiptNeedsReason.value && !receiptForm.value.varianceReason) return '超出容差的差异必须选择原因';
  if (receiptNeedsReason.value && !receiptForm.value.responsibilitySide) return '超出容差的差异必须选择生产侧、仓库侧或称重误差';
  if (receiptNeedsReason.value && receiptForm.value.responsibilitySide === 'PENDING') return '责任侧不能为待核对，请先明确归属';
  if (receiptNeedsReason.value && receiptForm.value.varianceReason === '其他' && !receiptForm.value.otherVarianceReason.trim()) {
    return '请选择“其他”时必须补充说明';
  }
  return '';
});
const receiptCanSubmit = computed(() => receiptSubmitDisabledReason.value === '');
const clearingProductName = computed(() => {
  const r = clearingRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
const clearingPlanNumber = computed(() => {
  const r = clearingRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});
const clearingDisabledReason = computed(() => {
  if (!clearingSettlement.value) return '请先加载中转挂账状态';
  if (!clearingForm.value.clearingReason) return '请选择清账原因';
  if (clearingForm.value.clearingReason === '其他' && !clearingForm.value.otherClearingReason.trim()) {
    return '选择“其他”时必须补充清账原因';
  }
  return '';
});
const clearingCanSubmit = computed(() => clearingDisabledReason.value === '');

function buildReceiptIdempotencyKey(row: TableRow): string {
  // 生成并保存到 receiptIdempotencyKey，确保重试时复用同一 key (防呆 Rule 4)
  const planId = String(row.id || row.planNumber || 'unknown');
  const key = `web-receipt-${planId}-${Date.now()}`;
  receiptIdempotencyKey.value = key;
  return key;
}

async function handleWarehouseReceipt(row: TableRow) {
  if (!factoryId.value || actionLoading.value) return;
  receiptRow.value = row;
  // 幂等 key 在打开弹框时生成一次，submitWarehouseReceipt 直接复用，重试不会产生新 key (防呆 Rule 4)
  buildReceiptIdempotencyKey(row);
  receiptLoading.value = true;
  receiptDialogVisible.value = true;
  try {
    const res = await getProductionSettlement(factoryId.value, String(row.id));
    if (!res.success || !res.data) {
      ElMessage({ message: res.message || '加载结单状态失败', type: 'error', duration: 0, showClose: true });
      receiptDialogVisible.value = false;
      return;
    }
    // ⛔ 没有仓库确认单时不要打开一个空对话框 —— 如实说清为什么, 而不是让用户对着空表单。
    if (isNotSettledPayload(res.data)) {
      ElMessage({
        message: t('production.plans_list.message_1cb23bcb1c53'),
        type: 'info',
        duration: 0,
        showClose: true,
      });
      receiptDialogVisible.value = false;
      return;
    }
    receiptSettlement.value = res.data;
    receiptOverReceiveRule.value = { ...DEFAULT_OVER_RECEIVE_RULE };
    try {
      const ruleRes = await getOverReceiveRule(factoryId.value);
      if (ruleRes.success && ruleRes.data) receiptOverReceiveRule.value = ruleRes.data;
    } catch {
      // 读不到规则 → 按默认硬拦显示; 服务端仍按本厂配置判, 不会因此误放行
    }
    const outputLines = (res.data.outputLines || []).map((line) => ({
      ...line,
      originalReceivedQuantity: Number(line.receivedQuantity ?? 0),
      receivedQuantity: Number(line.reportedQuantity ?? 0) - Number(line.receivedQuantity ?? 0),
    }));
    receiptForm.value = {
      receivedQuantity: outputLines.length ? 0 : Number(res.data.actualFinishedQuantity || 0),
      // API settlement is the quantity contract. Keep canonical payload (box/case/slice)
      // while rendering a localized label in the dialog.
      quantityUnit: outputLines.length
        ? ''
        : canonicalUnitCodeKeepingCount(res.data.quantityUnit || row.unit || row.quantityUnit),
      outputLines,
      varianceReason: '',
      otherVarianceReason: '',
      responsibilitySide: '',
      varianceNote: '',
    };
  } catch (error) {
    handleCatchError(error, '加载结单状态失败');
    receiptDialogVisible.value = false;
  } finally {
    receiptLoading.value = false;
  }
}

async function submitWarehouseReceipt() {
  if (!factoryId.value || !receiptRow.value || !receiptSettlement.value) return;
  if (!receiptCanSubmit.value) {
    ElMessage({ message: receiptSubmitDisabledReason.value, type: 'error', duration: 0, showClose: true });
    return;
  }
  actionLoading.value = true;
  try {
    const varianceReason = receiptForm.value.varianceReason === '其他'
      ? receiptForm.value.otherVarianceReason.trim()
      : receiptForm.value.varianceReason;
    const res = await confirmProductionWarehouseReceipt(factoryId.value, String(receiptRow.value.id), {
      idempotencyKey: receiptIdempotencyKey.value,
      receivedQuantity: receiptHasOutputLines.value ? null : receiptReceivedQuantity.value,
      quantityUnit: receiptHasOutputLines.value ? null : receiptUnit.value,
      outputLines: receiptHasOutputLines.value
        ? receiptForm.value.outputLines.map((line) => ({
            productTypeId: line.productTypeId,
            batchNumber: line.reportedBatchNumber,
            receivedQuantity: Number(line.receivedQuantity),
            quantityUnit: canonicalUnitCodeKeepingCount(line.quantityUnit),
          }))
        : undefined,
      varianceReason: receiptHasOutputLines.value ? null : varianceReason || null,
      responsibilitySide: receiptHasOutputLines.value
        ? null
        : receiptNeedsReason.value ? receiptForm.value.responsibilitySide : null,
      varianceNote: receiptForm.value.varianceNote || null,
    });
    if (res.success) {
      ElMessage.success(res.data?.message || res.message || '仓库已确认入库');
      receiptDialogVisible.value = false;
      await loadData();
    } else {
      ElMessage({ message: res.message || '仓库确认失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    handleCatchError(error, '仓库确认失败');
  } finally {
    actionLoading.value = false;
  }
}

async function handleTransitClearing(row: TableRow) {
  if (!factoryId.value || actionLoading.value) return;
  clearingRow.value = row;
  clearingSettlement.value = getSettlementStatus(row);
  clearingForm.value = {
    clearingReason: '',
    otherClearingReason: '',
    clearingNote: '',
  };
  clearingLoading.value = true;
  clearingDialogVisible.value = true;
  try {
    const res = await getProductionSettlement(factoryId.value, String(row.id));
    if (!res.success || !res.data) {
      ElMessage({ message: res.message || '加载中转挂账状态失败', type: 'error', duration: 0, showClose: true });
      clearingDialogVisible.value = false;
      return;
    }
    // 同上: 没有结单行就没有中转挂账, ⛔ 不要落到「当前状态为 undefined」那句话上。
    if (isNotSettledPayload(res.data)) {
      ElMessage({
        message: t('production.plans_list.message_db8b82a03125'),
        type: 'info',
        duration: 0,
        showClose: true,
      });
      clearingDialogVisible.value = false;
      return;
    }
    clearingSettlement.value = res.data;
    if (res.data.postingStatus !== 'PENDING_CLEARING') {
      ElMessage({
        message: `当前状态为 ${postingStatusText(res.data.postingStatus)}，没有待清账的中转挂账`,
        type: 'warning',
        duration: 0,
        showClose: true,
      });
      clearingDialogVisible.value = false;
    }
  } catch (error) {
    handleCatchError(error, '加载中转挂账状态失败');
    clearingDialogVisible.value = false;
  } finally {
    clearingLoading.value = false;
  }
}

async function submitTransitClearing() {
  if (!factoryId.value || !clearingRow.value || !clearingSettlement.value) return;
  if (!clearingCanSubmit.value) {
    ElMessage({ message: clearingDisabledReason.value, type: 'error', duration: 0, showClose: true });
    return;
  }
  actionLoading.value = true;
  try {
    const clearingReason = clearingForm.value.clearingReason === '其他'
      ? clearingForm.value.otherClearingReason.trim()
      : clearingForm.value.clearingReason;
    const res = await clearProductionTransitLedger(factoryId.value, String(clearingRow.value.id), {
      clearingReason,
      clearingNote: clearingForm.value.clearingNote || null,
    });
    if (res.success) {
      ElMessage.success(res.data?.message || res.message || '中转挂账已清账');
      const rowId = String(clearingRow.value.id);
      if (settlementStatusMap.value[rowId]) {
        settlementStatusMap.value[rowId] = {
          ...settlementStatusMap.value[rowId],
          postingStatus: res.data?.postingStatus || 'POSTED',
          postingMessage: res.data?.message || res.message || '中转挂账已清账',
        };
      }
      clearingDialogVisible.value = false;
      await loadData();
    } else {
      ElMessage({ message: res.message || '中转挂账清账失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    handleCatchError(error, '中转挂账清账失败');
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 取消原因 dialog (#743) ====================
// 快捷下拉 + 自定义补充, 替代纯 textarea
const CANCEL_REASON_OPTIONS = [
  { value: '客户撤单', label: t('production.plans_list.label_ca6c3519dc81') },
  { value: '原料缺货', label: t('production.plans_list.label_cfc2a6ce9529') },
  { value: '质量问题', label: t('production.plans_list.label_0f22786cfc73') },
  { value: '排程冲突', label: t('production.plans_list.label_5dceecd8b1ae') },
  { value: '其他', label: t('production.plans_list.label_a871e12fcc51') },
];
const cancelDialogVisible = ref(false);
const cancelRow = ref<TableRow | null>(null);
const cancelForm = ref({ reasonOption: '', otherReason: '' });
const cancelProductName = computed(() => {
  const r = cancelRow.value;
  if (!r) return '';
  return String(r.productTypeName || r.productName || r.productTypeId || '');
});
// 防呆 Rule 2: 取消 dialog 标题需同时带品名+单号 (与编辑 dialog 一致), 避免多个同品名计划混淆
const cancelPlanNumber = computed(() => {
  const r = cancelRow.value;
  if (!r) return '';
  return String(r.planNumber || r.id || '');
});

function handleCancel(row: TableRow) {
  if (actionLoading.value) return;
  cancelRow.value = row;
  cancelForm.value = { reasonOption: '', otherReason: '' };
  cancelDialogVisible.value = true;
}

async function submitCancel() {
  if (!cancelRow.value) return;
  const opt = cancelForm.value.reasonOption;
  if (!opt) {
    ElMessage.warning(t('production.plans_list.message_59e5542fcb33'));
    return;
  }
  let reason = opt;
  if (opt === '其他') {
    const other = (cancelForm.value.otherReason || '').trim();
    if (!other) {
      ElMessage.warning(t('production.plans_list.message_cce33ca648e3'));
      return;
    }
    reason = `其他: ${other}`;
  }
  actionLoading.value = true;
  try {
    const response = await post(`/${factoryId.value}/production-plans/${cancelRow.value.id}/cancel?reason=${encodeURIComponent(reason)}`);
    if (response.success) {
      ElMessage.success(t('production.plans_list.message_393d64e96681'));
      cancelDialogVisible.value = false;
      loadData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error: any) {
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

// ==================== 编辑生产计划 dialog (更多→编辑 dead-stub 修复) ====================
// 之前「更多→编辑」只弹一个 debug toast (`Action: edit`), 不开任何表单 — 厂长创建计划后
// 无法改计划日期/数量, 是一个完全没接线的死桩。这里用独立的轻量 dialog 而非复用复杂的
// 「新建计划」dialog (那个 dialog 的以销定产多产品批量建单逻辑跟「编辑单张已存在的计划」
// 语义冲突 — 编辑时改 SO/产品行选择不应该走 batch-from-so 去新建计划), 只暴露真正
// 编辑后仍讲得通的字段: 计划日期(核心需求)/计划数量/预计完成日期/预计工人数/指派主管/备注。
//
// 可编辑状态 (EDITABLE_PLAN_STATUSES) 与「不可编辑的短原因」都放在 productionMoreActions.ts,
// 与「更多」菜单里那一项的灰显判据同源 — 否则菜单说可编辑而点击路径又拦下来, 就是两处口径打架。
// 这里的 blockedEditMessage 是点击路径上的长句兜底 (列表行数据可能过期, 拉到详情后还要再挡一次)。

function blockedEditMessage(status: string, isLocked?: boolean, lockReason?: string): string {
  if (isLocked) {
    // 锁定/解锁的后端 API 至今未实装 (#747), 界面上没有任何解锁入口 — 不要指给用户一条走不通的路
    return `计划已锁定${lockReason ? `（${lockReason}）` : ''}，暂不可编辑，请联系管理员解除锁定后再试`;
  }
  switch (status) {
    case 'IN_PROGRESS':
    case 'PAUSED':
      return '生产已开始的计划不可编辑，如需调整数量/日期请先「暂停/停止生产」或联系车间主管';
    case 'COMPLETED':
      return '已完成的计划不可编辑';
    case 'CANCELLED':
      return '已取消的计划不可编辑';
    case 'PENDING_APPROVAL':
      return '该计划正在审批流程中，暂不可编辑';
    default:
      return `当前状态 (${status}) 不可编辑，请刷新列表查看最新状态`;
  }
}

const editDialogVisible = ref(false);
const editDialogLoading = ref(false);
const editingPlanId = ref('');
// 编辑弹窗标题用的上下文 (防呆 Rule 2: 必须显示品名+单号, 不能是空表单)
const editingPlanNumber = ref('');
const editingProductName = ref('');
const editForm = ref({
  plannedDate: '',
  plannedQuantity: 0,
  expectedCompletionDate: '',
  estimatedWorkers: undefined as number | undefined,
  assignedSupervisorId: '' as string | number | undefined,
  notes: '',
});
// 编辑提交时用完整详情 hydrate PUT payload (而不是只传编辑过的几个字段) —
// CreateProductionPlanRequest 里若干字段有 Java 默认值 (sourceType=MANUAL /
// planType=FROM_INVENTORY / priority=5 / isMixedBatch=false ...), 编辑请求如果漏传
// 这些字段, 反序列化会落到默认值, updateEntity 的 null-guard 挡不住"非 null 但是默认值"
// 的情况 —— 会把已有计划的来源类型/计划类型/优先级悄悄冲掉。所以先拉完整详情原样回填,
// 再只覆盖真正编辑过的字段。
let editingPlanDetail: TableRow | null = null;

// 本次编辑的可及范围: full = 全字段 (PENDING/PREPARED); schedule-meta = 只有四个软字段
// (已开工/暂停)。范围由 planEditScope 判定, 与「更多」里那一项的文案同源。
const editScope = ref<PlanEditScope>('full');
const editScopeIsScheduleMeta = computed(() => editScope.value === 'schedule-meta');

async function handleEditPlan(row: TableRow) {
  const status = String(row.status || '');
  const rowLocked = Boolean(row.isLocked);
  // Rule 1 (防呆): 先用列表已有的行数据快速判断, 不等网络往返就能挡掉明显不可编辑的行。
  const scope = planEditScope(row);
  if (scope === null) {
    ElMessageBox.alert(blockedEditMessage(status, rowLocked, row.lockReason ? String(row.lockReason) : undefined), t('production.plans_list.message_6a45521e14f6'), {
      confirmButtonText: t('production.plans_list.confirmbuttontext_348f1cf1243e'),
    }).catch(() => { /* dismiss */ });
    return;
  }

  editDialogLoading.value = true;
  editingPlanId.value = String(row.id);
  try {
    const response = await get(`/${factoryId.value}/production-plans/${row.id}`);
    if (!response.success || !response.data) {
      ElMessage({ message: response.message || '加载生产计划详情失败', type: 'error', duration: 0, showClose: true });
      return;
    }
    const plan = response.data as TableRow;
    // 防御性二次校验: 列表行数据可能已过期 (并发操作/另一个 tab 锁定/开工), 以刚拉取的
    // 详情为准, 而不是只信列表缓存 (Rule 1: 提交前也要挡, 不只是点击那一刻)。
    const freshStatus = String(plan.status || '');
    const freshLocked = Boolean(plan.isLocked);
    const freshScope = planEditScope(plan);
    if (freshScope === null) {
      ElMessageBox.alert(
        blockedEditMessage(freshStatus, freshLocked, plan.lockReason ? String(plan.lockReason) : undefined),
        t('production.plans_list.message_6a45521e14f6'),
        { confirmButtonText: t('production.plans_list.confirmbuttontext_348f1cf1243e') }
      ).catch(() => { /* dismiss */ });
      return;
    }
    // 以详情为准: 点开那一刻是 PENDING, 拉到详情已经开工的话, 范围要跟着收窄成 schedule-meta,
    // 而不是拿列表缓存的 full 去提交一份含计划数量/日期的 payload (后端会 409, 但那是事后报错)。
    editScope.value = freshScope;

    editingPlanDetail = plan;
    editingPlanNumber.value = String(plan.planNumber || plan.id || '');
    editingProductName.value = String(plan.productTypeName || plan.productName || plan.productTypeId || '');
    editForm.value = {
      plannedDate: String(plan.plannedDate || ''),
      plannedQuantity: Number(plan.plannedQuantity) || 0,
      expectedCompletionDate: plan.expectedCompletionDate ? String(plan.expectedCompletionDate) : '',
      estimatedWorkers: plan.estimatedWorkers !== null && plan.estimatedWorkers !== undefined ? Number(plan.estimatedWorkers) : undefined,
      assignedSupervisorId: plan.assignedSupervisorId !== null && plan.assignedSupervisorId !== undefined ? plan.assignedSupervisorId : '',
      notes: String(plan.notes || ''),
    };
    editDialogVisible.value = true;
  } catch (e) {
    handleCatchError(e, '加载生产计划详情失败');
  } finally {
    editDialogLoading.value = false;
  }
}

async function submitEditPlan() {
  if (!editingPlanDetail || !factoryId.value) return;
  // 已开工/暂停: 只提交四个软字段, 走后端更窄的 schedule-meta 契约。计划日期与计划数量
  // 在这条路径上根本不在 payload 里 —— 表单里它们也是灰的, 两处一致。
  if (editScopeIsScheduleMeta.value) {
    editDialogLoading.value = true;
    try {
      const response = await put(
        `/${factoryId.value}/production-plans/${editingPlanId.value}/schedule-meta`,
        {
          expectedCompletionDate: editForm.value.expectedCompletionDate || null,
          estimatedWorkers: editForm.value.estimatedWorkers ?? null,
          assignedSupervisorId: editForm.value.assignedSupervisorId || null,
          notes: editForm.value.notes || null,
        },
      );
      if (response.success) {
        ElMessage.success(t('production.plans_list.message_d6a6a47e0eec'));
        editDialogVisible.value = false;
        loadData();
      } else {
        ElMessage({ message: response.message || '更新失败', type: 'error', duration: 0, showClose: true });
      }
    } catch (e) {
      handleCatchError(e, '更新排产信息失败');
    } finally {
      editDialogLoading.value = false;
    }
    return;
  }
  if (!editForm.value.plannedDate) {
    ElMessage.warning(t('production.plans_list.message_4cd57edb4325'));
    return;
  }
  if (!editForm.value.plannedQuantity && editingPlanDetail.sourceType !== 'SAFETY_STOCK') {
    ElMessage.warning(t('production.plans_list.message_c11365d8764e'));
    return;
  }
  editDialogLoading.value = true;
  try {
    // Hydrate 完整字段(避免 Java DTO 默认值冲掉未编辑的 sourceType/planType/priority 等),
    // 再用表单编辑过的字段覆盖。
    const src = editingPlanDetail;
    const payload = {
      productTypeId: src.productTypeId,
      plannedQuantity: editForm.value.plannedQuantity,
      plannedDate: editForm.value.plannedDate,
      expectedCompletionDate: editForm.value.expectedCompletionDate || undefined,
      customerOrderNumber: src.customerOrderNumber || undefined,
      priority: src.priority,
      estimatedMaterialCost: src.estimatedMaterialCost,
      estimatedLaborCost: src.estimatedLaborCost,
      estimatedEquipmentCost: src.estimatedEquipmentCost,
      estimatedOtherCost: src.estimatedOtherCost,
      notes: editForm.value.notes || undefined,
      planType: src.planType,
      estimatedWorkers: editForm.value.estimatedWorkers,
      assignedSupervisorId: editForm.value.assignedSupervisorId || undefined,
      sourceType: src.sourceType,
      sourceOrderId: src.sourceOrderId || undefined,
      sourceOrderIds: Array.isArray(src.sourceOrderIds) ? src.sourceOrderIds : undefined,
      sourceOrderItemId: src.sourceOrderItemId || undefined,
      sourceCustomerName: src.sourceCustomerName || undefined,
      processName: src.processName || undefined,
      batchDate: src.batchDate || undefined,
      aiConfidence: src.aiConfidence,
      forecastReason: src.forecastReason || undefined,
      isMixedBatch: src.isMixedBatch,
      mixedBatchType: src.mixedBatchType || undefined,
      relatedOrders: Array.isArray(src.relatedOrders) ? src.relatedOrders : undefined,
      skipProcessReporting: src.skipProcessReporting,
    };
    const response = await put(`/${factoryId.value}/production-plans/${editingPlanId.value}`, payload);
    if (response.success) {
      ElMessage.success(t('production.plans_list.message_383f9a32cd4f'));
      editDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: response.message || '更新失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    handleCatchError(e, '更新生产计划失败');
  } finally {
    editDialogLoading.value = false;
  }
}

// #860 follow-up (2026-05-18): 复制生产计划 → 新草稿 (status=PENDING).
// 后端复用产品/数量/日期/成本预估, 不复制实际值/审批/锁定状态.
// 错误由 axios interceptor sticky toast 显示, 不要 try/catch 吞.
async function handleCopyPlan(row: TableRow): Promise<void> {
  const planNumber = String(row.planNumber || row.id || '');
  try {
    await ElMessageBox.confirm(
      `确认复制生产计划 ${planNumber} 为新草稿？复制内容包含产品、数量、日期和成本预估，不复制实际值/审批/锁定状态。`,
      t('production.plans_list.message_401937123a39'),
      { confirmButtonText: t('production.plans_list.confirmbuttontext_63d90d977348'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738'), type: 'info' }
    );
  } catch {
    return; // 用户取消
  }
  const res = await copyProductionPlan(factoryId.value, String(row.id));
  if (res?.success && res.data) {
    ElMessage.success(`已复制为 ${res.data.planNumber}`);
    await loadData();
  }
}

// ==================== Phase 2: BY_STOCK 小结 / 停产 ====================
// 逐行 loading state，防止双击（防呆 Rule 4）
const interimSettleLoadingId = ref<string | null>(null);
const reverseInterimSettleLoadingId = ref<string | null>(null);
const stopProductionLoadingId = ref<string | null>(null);

/**
 * 「更新到当前配方」—— 未开工的计划跟上新配方 (Steve 2026-08-09 拍板)。
 *
 * ⛔ 前端**不判**「有没有开工」: 判据是报工行的三个信号 (rowStatus / submissionStatus /
 * interimSettledAt), 在后端同一个事务里判。前端复制一份必然与后端漂移, 而且列清单到点确认
 * 之间计划可能刚好开工 —— 只信前端那一刻的快照就会把已开工的计划改掉。
 * 已开工时后端返回 409 PRODUCTION_PLAN_ALREADY_STARTED, 直接把它的话显示给用户。
 */
async function handleRepinAuthority(row: TableRow) {
  const planId = String(row.id)
  const planNumberLabel = String(row.planNumber || planId)
  try {
    await ElMessageBox.confirm(
      `把「${planNumberLabel}」更新到当前生效的工艺版本与配方？仅未开工的计划可以更新；已投料/已报工的计划会被后端拒绝并保持原样。`,
      t('production.plans_list.message_d081b003835b'),
      { type: 'warning', confirmButtonText: t('production.plans_list.confirmbuttontext_3055a035f0eb'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') }
    )
  } catch {
    return
  }
  try {
    const res = await repinPlanAuthority(factoryId.value, planId)
    if (res.success) {
      ElMessage.success(res.message || '已更新到当前生效配方')
      await loadData()
    } else {
      ElMessage.error(res.message || '更新失败')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败')
  }
}

async function handleInterimSettle(row: TableRow) {
  const planId = String(row.id);
  if (interimSettleLoadingId.value) return;
  // 防呆 Rule 1/2: 小结是真实入库+扣料操作 (非只读), 之前一键无确认就执行 — 加轻量 confirm
  // 带品名+单号+大白话后果说明, 避免误触
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  let note = '';
  try {
    const promptResult = await ElMessageBox.prompt(
      `小结「${planLabel}」将按当前已录入数量扣减已投入原料并产出半成品/成品入库，计划继续挂起。确认执行？`,
      t('production.plans_list.message_da701d8a08fc'),
      {
        type: 'warning',
        confirmButtonText: t('production.plans_list.confirmbuttontext_da701d8a08fc'),
        cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738'),
        inputPlaceholder: '填写本次小结备注（选填，最多500字）',
        inputValidator: (value: string) => value.trim().length <= 500 || '小结备注不能超过500个字符',
      }
    );
    note = promptResult.value;
  } catch {
    return; // 用户取消
  }
  if (interimSettleLoadingId.value) return;
  interimSettleLoadingId.value = planId;
  try {
    const res = await interimSettle(factoryId.value, planId, note);
    if (res.success) {
      ElMessage.success(res.data?.message as string || res.message || '已小结，计划继续挂起');
      loadData();
    } else {
      ElMessage({ message: res.message || '小结失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '小结失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    interimSettleLoadingId.value = null;
  }
}

/**
 * 撤销小结-申请 (最近一次): 治理 = 申请→审批→执行。此处仅创建撤销申请 (待审批, 零库存副作用)。
 * 防呆 4-位一体: prompt 强制填 reason (带计划名/单号 context) + 后端 message 原样 sticky (含超时/下游 blocking 引用)。
 */
async function handleReverseInterimSettle(row: TableRow) {
  const planId = String(row.id);
  // 防呆 Rule 2 (UX 修复): 之前 planLabel 落回 row.planNumber, 再与后面拼接的 (${row.planNumber})
  // 撞在一起 → 弹窗标题/正文变成「PLAN-XXX」(PLAN-XXX) 计划号显示两次, 没有品名。改用与
  // handleInterimSettle (确认小结) 同款拼法: 品名 (计划号)，没有品名时才退化为纯计划号。
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  let reason = '';
  try {
    const { value } = await ElMessageBox.prompt(
      `申请撤销「${planLabel}」的最近一次小结。撤销需审批，通过后才逆转入库并还回消耗（误结的逐道行恢复可编辑）；仅限小结后24小时内。请填写撤销原因：`,
      t('production.plans_list.message_220f14c4c9d7'),
      {
        type: 'warning',
        confirmButtonText: t('production.plans_list.confirmbuttontext_9cc5e4d81ec2'),
        cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738'),
        inputPlaceholder: '如：产量录错 / 批次选错',
        inputValidator: (v: string) => (v && v.trim() ? true : '请填写撤销原因'),
      }
    );
    reason = (value || '').trim();
  } catch {
    return; // 用户取消
  }
  if (reverseInterimSettleLoadingId.value) return;
  reverseInterimSettleLoadingId.value = planId;
  try {
    const res = await requestReverseInterimSettle(factoryId.value, planId, reason);
    if (res.success) {
      ElMessage.success(res.message || '已提交撤销申请，待审批');
      loadData();
    } else {
      // sticky + 后端原样 message (含超时 WINDOW_EXPIRED / 下游已消耗 blocking 引用)
      ElMessage({ message: res.message || '撤销申请失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '撤销申请失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reverseInterimSettleLoadingId.value = null;
  }
}

// ==================== 撤销小结审批 (STOCKTAKE_APPROVAL_ROLES) ====================
const reversalApprovalVisible = ref(false);
const reversalRequests = ref<InterimSettleReversalRequest[]>([]);
const reversalApprovalLoading = ref(false);
const reversalActingId = ref<string | null>(null);

async function openReversalApproval() {
  reversalApprovalVisible.value = true;
  await loadReversalRequests();
}

async function loadReversalRequests() {
  reversalApprovalLoading.value = true;
  try {
    const res = await listReversalRequests(factoryId.value, { status: 'PENDING_APPROVAL', size: 50 });
    reversalRequests.value = res.success && res.data ? (res.data.content || []) : [];
  } catch {
    reversalRequests.value = [];
  } finally {
    reversalApprovalLoading.value = false;
  }
}

async function handleApproveReversal(reqRow: InterimSettleReversalRequest) {
  if (reversalActingId.value) return;
  reversalActingId.value = reqRow.id;
  try {
    const res = await approveReversalRequest(factoryId.value, reqRow.id);
    if (res.success) {
      ElMessage.success(res.message || '撤销申请已审批，小结已撤销');
      await loadReversalRequests();
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    // 执行侧下游已消耗 / 超时 → sticky 显示后端 blocking message
    const errMsg = (e as any)?.response?.data?.message || '审批失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reversalActingId.value = null;
  }
}

async function handleRejectReversal(reqRow: InterimSettleReversalRequest) {
  let reason = '';
  try {
    const { value } = await ElMessageBox.prompt(t('production.plans_list.message_1fa8eafb4592'), t('production.plans_list.message_f7d7443f0094'), {
      confirmButtonText: t('production.plans_list.confirmbuttontext_089e6ed3f55f'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738'),
      inputValidator: (v: string) => (v && v.trim() ? true : '请填写驳回原因'),
    });
    reason = (value || '').trim();
  } catch {
    return;
  }
  if (reversalActingId.value) return;
  reversalActingId.value = reqRow.id;
  try {
    const res = await rejectReversalRequest(factoryId.value, reqRow.id, reason);
    if (res.success) {
      ElMessage.success(res.message || '撤销申请已驳回');
      await loadReversalRequests();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '驳回失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    reversalActingId.value = null;
  }
}

async function handleStopProduction(row: TableRow) {
  const planId = String(row.id);
  // 防呆 Rule 2: confirm 必须带品名, 不能只靠计划单号 (仓管员按品名认物料, 单号不好记)
  const productLabel = String(row.productTypeName || row.productName || row.productTypeId || '');
  const planNumberLabel = String(row.planNumber || planId);
  const planLabel = productLabel ? `${productLabel} (${planNumberLabel})` : planNumberLabel;
  try {
    await ElMessageBox.confirm(
      `停产「${planLabel}」?停产后计划关闭，不可再小结。`,
      t('production.plans_list.message_d13744a4c074'),
      { type: 'warning', confirmButtonText: t('production.plans_list.confirmbuttontext_37a9ba378c9f'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') }
    );
  } catch {
    return; // 用户取消
  }
  if (stopProductionLoadingId.value) return;
  stopProductionLoadingId.value = planId;
  try {
    const res = await stopProduction(factoryId.value, planId);
    if (res.success) {
      ElMessage.success(res.data?.message as string || res.message || '已停产，计划已关闭');
      loadData();
    } else {
      ElMessage({ message: res.message || '停产失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e: unknown) {
    const errMsg = (e as any)?.response?.data?.message || '停产失败';
    ElMessage({ message: errMsg, type: 'error', duration: 0, showClose: true });
  } finally {
    stopProductionLoadingId.value = null;
  }
}
// ==================== /Phase 2 BY_STOCK ====================

async function handleCreateBatch(row: TableRow) {
  if (actionLoading.value) return;
  try {
    // #748: 加流程决策提示 (基于 May10 六扇门会议确认)
    await ElMessageBox.confirm(
      `确定将计划 "${row.planNumber}" 转为生产批次？\n\n` +
      `这是 APP 逐道报工的可选分支；PC 文员主流程可以直接点"核对结单"，不需要先转批次。\n\n` +
      `流程提示：\n` +
      `• 原料不足只做预警，不阻断转批次或结单。\n` +
      `• 需要仓库备料时可先生成调拨单，但不是开工前置条件。\n` +
      `• 转批次后会自动创建批次和工序任务，现场可在 APP 报工；最终仍由文员核对实际产量、领用和工时后结单。`,
      t('production.plans_list.message_b14b74f16e53'),
      { type: 'warning', confirmButtonText: t('production.plans_list.confirmbuttontext_dc9ae8c72ab6'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') }
    );
    actionLoading.value = true;
    const response = await postCreateBatch(row);
    if (response === null) return; // 缺料未解决且用户放弃强制开工
    if (response.success) {
      const batch = response.data;
      ElMessage.success(`批次创建成功！批次号: ${batch?.batchNumber || ''}`);
      loadData();
    } else {
      ElMessage.error(response.message || '转换失败');
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

async function handleGenerateTransfer(row: TableRow) {
  if (actionLoading.value) return;
  try {
    await ElMessageBox.confirm(
      `确定为计划 "${row.planNumber}" 创建备料调拨草稿？\n\n系统会根据 BOM 配方计算所需原辅料及包材；创建后可在调拨详情调整数量，再提交统一 OA 审批。`,
      t('production.plans_list.message_4712399e10b3'),
      { type: 'info', confirmButtonText: t('production.plans_list.confirmbuttontext_1ad1463fe16f'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') }
    );
    actionLoading.value = true;
    const response = await post(`/${factoryId.value}/production-plans/${row.id}/generate-transfer`);
    if (response.success) {
      const count = response.data?.items?.length || 0;
      ElMessage.success(`备料调拨草稿已就绪，共 ${count} 项物料；请在调拨详情核对数量后提交 OA 审批`);
      if (response.data?.id) {
        // 调拨详情的路由是 /transfer/:id (router/index.ts TransferDetail), 不是
        // /transfer/detail/:id —— 多写一段就落到兜底 404。2026-09-03 客户实拍:
        // 「生成调拨单生跳转失败，但是生成了」——草稿建出来了, 只有跳转死掉。
        // 另 6 处调拨详情跳转都写的 /transfer/${id}, 只有这里漂了。
        // 闸: __tests__/gates/routerPushPathsResolve.source.spec.ts
        router.push(`/transfer/${response.data.id}`);
      }
      loadData();
    } else {
      const msg = response.message || '生成失败';
      // If the error is about missing BOM, offer to navigate to BOM config
      if (msg.includes('BOM') || msg.includes('bom') || msg.includes('配方')) {
        ElMessageBox.confirm(
          `${msg}\n\n是否前往配置该产品的BOM配方？`,
          t('production.plans_list.message_8170af081ac5'),
          { type: 'warning', confirmButtonText: t('production.plans_list.confirmbuttontext_59710d83f9bc'), cancelButtonText: t('production.plans_list.cancelbuttontext_2cd0f3be8738') }
        ).then(() => {
          router.push('/production/bom');
        }).catch(() => { /* user cancelled */ });
      } else {
        ElMessage.error(msg);
      }
    }
  } catch (error: any) {
    // Interceptor already shows specific sticky toast for ApiError (request.ts).
    // Retained catch to prevent uncaught; log for debug.
    if (error !== 'cancel') console.error('[提交失败]', error);
  } finally {
    actionLoading.value = false;
  }
}

function isPendingStatus(status: string) {
  return status === 'PLANNED' || status === 'PENDING';
}

function isUnfinishedStatus(status: string) {
  return ['PENDING', 'IN_PROGRESS'].includes(String(status || '').toUpperCase());
}

function getPlanAdvisory(row: TableRow) {
  return materialAdvisoryMap.value[String(row.id || '')];
}

/**
 * 无预警时的文案**以后端为准**，前端不再自己写一份。
 *
 * 🔴 2026-08-18: 这里原来硬编码 `'原料库存参考: 暂无缺料预警'`，于是后端说什么都不算数 ——
 * 后端把这句话改成带口径的三态（全厂够且生产仓已备料 / 全厂够但没核对生产仓 / 有预警），
 * 这一列照旧显示「暂无缺料预警」。形态 D：同一句话两份，一定会漂；
 * 这次是后端先动，前端那份把它盖掉了。
 *
 * ⚠️ 兜底串只在后端**没给 message** 时用（老版本后端 / 字段缺失），不是默认路径。
 */
function getPlanAdvisorySummary(row: TableRow) {
  const advisory = getPlanAdvisory(row);
  if (!advisory) return '';
  return advisory.message || '原料库存参考: 暂无缺料预警';
}

function advisoryNeedsUnitConfig(advisory: ProductionPlanMaterialAdvisory | null | undefined): boolean {
  if (!advisory?.hasWarning) return false;
  const haystack = [
    advisory.message,
    ...(advisory.warnings || []).map((item) => item.message),
  ].join('\n');
  return /单位.*无法换算|无法换算.*单位|核对单位配置/.test(haystack);
}

function goMaterialUnitConfig(advisory: ProductionPlanMaterialAdvisory | null | undefined) {
  const firstUnitWarning = (advisory?.warnings || []).find((item) => /单位|换算/.test(item.message))
    || advisory?.warnings?.[0];
  router.push({
    path: '/warehouse/material-types',
    query: {
      _returnTo: route.fullPath,
      ...(firstUnitWarning?.materialName ? { keyword: firstUnitWarning.materialName } : {}),
    },
  });
}

// T138 方案A 的开工/生成调拨单 gate (只接受 PENDING) 已并入 productionMoreActions.ts,
// 与「更多」菜单项同源。(isPendingStatus 保留给取消按钮的展示范围, 不收窄.)

function canPrintPlanDocuments(status: string) {
  return ['PENDING', 'CONFIRMED', 'APPROVED', 'IN_PROGRESS', 'COMPLETED'].includes(String(status || '').toUpperCase());
}

/**
 * 这一行现在是不是「等着仓库确认入库」。
 *
 * <p>判据复用 nextStepText 里已有的 postingStatus，不新造一套 —— 那正是
 * 「同一个闸多个承载点」的成因。
 *
 * <p>2026-08-02: 此前模板根本没有这个按钮（handleWarehouseReceipt 是死代码），
 * 计划结单后「下一步」写着「仓库确认入库」，界面上却无处可点。
 *
 * <p><b>2026-08-04 收窄</b>：原来这里连 {@code PENDING_CLEARING} 一起放行，注释写着
 * 「同一个弹窗处理清账」—— <b>代码里不是这样</b>。{@code handleWarehouseReceipt} 没有
 * PENDING_CLEARING 分支，提交调的是 {@code confirmProductionWarehouseReceipt}（入库 API）；
 * 清账有自己的 {@code handleTransitClearing} + {@code clearingDialog} + 清账 API，
 * 而那一整套当时全文件零调用点。放行到这里的结果是：中转挂账的行点开的是入库弹窗，
 * 清账永远做不成。现在这里只管待入库，清账由 {@code canClearTransit} 单独出口。
 */
function needsWarehouseReceipt(row: TableRow): boolean {
  if (String(row.status || '').toUpperCase() !== 'COMPLETED') return false;
  const settlement = getSettlementStatus(row);
  if (!settlement) return false;
  return settlement.postingStatus === 'PENDING_WAREHOUSE_RECEIPT';
}

function nextStepText(row: TableRow) {
  const status = String(row.status || '').toUpperCase();
  if (status === 'COMPLETED') {
    if (isSettlementAbsent(row)) return '无需仓库确认(小结已入库)';
    if (getSettlementStatusError(row)) return '入库状态加载失败';
    const settlement = getSettlementStatus(row);
    if (!settlement) return '加载入库状态';
    if (settlement.postingStatus === 'PENDING_WAREHOUSE_RECEIPT') return '仓库确认入库';
    if (settlement.postingStatus === 'PENDING_CLEARING') return '中转挂账清账';
    if (settlement.postingStatus === 'POSTED_WITH_TOLERANCE') return '已入库(容差)';
    if (settlement.postingStatus === 'POSTED') return '成品库存已入库';
    return postingStatusText(settlement.postingStatus);
  }
  if (row.nextAction) return String(row.nextAction);
  if (status === 'IN_PROGRESS') {
    return row.sourceType === 'SAFETY_STOCK'
      ? '继续逐道录入或生产小结'
      : '继续逐道录入并核对结单';
  }
  if (status === 'PENDING') {
    if (row.sourceType === 'SAFETY_STOCK') {
      return row.skipProcessReporting === false ? '逐道录入后生产小结' : '按实际产出执行生产小结';
    }
    return row.skipProcessReporting === false ? '逐道录入后核对销售订单数量' : '核对销售订单数量后结单';
  }
  if (status === 'PLANNED' || status === 'PREPARED') return '确认后进入未完成';
  if (status === 'CANCELLED') return '已取消';
  return '查看详情';
}

function nextStepTagType(row: TableRow) {
  const status = String(row.status || '').toUpperCase();
  if (status === 'COMPLETED') return postingStatusType(getSettlementStatus(row)?.postingStatus);
  if (status === 'IN_PROGRESS') return 'warning';
  if (status === 'PENDING') return row.skipProcessReporting === false ? 'primary' : 'info';
  if (status === 'CANCELLED') return 'danger';
  return 'info';
}

function cancellationAuditFor(row: TableRow) {
  return cancellationAudit(row);
}

function planRowClassName({ row }: { row: TableRow }): string {
  return planRowClassNameByStatus(String(row.status || ''));
}

function getStatusType(status: string) {
  return getPlanStatusType(status);
}

function getStatusText(status: string) {
  return getPlanStatusText(status);
}

// 防呆 Rule 1: SAFETY_STOCK(库存生产)没有「计划数量」，后端用 0 表示开放数量。
// 列表明确显示“按实际报工”，避免操作员把裸 0 / 破折号误读成异常或漏填。
function formatPlannedQuantity(v: number | null | undefined, unit?: string | null): string {
  if (v == null || v === 0) return '—';
  return unit ? `${v} ${displayUnit(unit)}` : `${v}（单位未配置）`;
}

function formatPlanDisplayQuantity(row: TableRow | null | undefined): string {
  if (!row) return '—';
  if (row.sourceType === 'SAFETY_STOCK') {
    return '按实际报工';
  }
  const sourceQuantity = row.sourceDisplayQuantity as number | null | undefined;
  const sourceUnit = row.sourceDisplayUnit as string | null | undefined;
  if (sourceQuantity != null && sourceQuantity !== 0 && sourceUnit) {
    return formatPlannedQuantity(sourceQuantity, sourceUnit);
  }
  return formatPlannedQuantity(
    row.plannedQuantity as number | null | undefined,
    row.plannedUnit as string | null | undefined,
  );
}

// raw-centric 多SKU (2026-07-13): 计划的 targetFinishedGoodIds → 产品名 (列表/详情共用)。
// 查不到 (产品已删除) 时兜底显示 id + 「已删除」提示, 不静默丢字段。
// 2026-08-28 逐屏走查: 这个函数把**表格列**「生产成品」渲染成了裸 UUID。
//
// 实拍 (f006 真实客户, 20 条计划里 8 条): `eb0aa47b-a5dd-49dc-a…` —— 列宽 240 截断之后
// 连 `（已删除）` 三个字都看不到, 用户在生产计划列表上**看不出这是什么产品**。
//
// 🔴 两个缺陷, 而修法就在 10 行之外:
//
//   ① 回落成 UUID。下面 `resolveOutputProductLabel` 的注释里写着
//      「两个对话框此前直接把 UUID 打给用户 (实拍截图: eb0aa47b-a5dd-49dc-af20-…)」——
//      **同一个 UUID**。那两个对话框修好了, 这个表格列没修。形态 D: 同一件事两份实现,
//      一份修好了另一份没有, 而修好的那份的注释就摆在旁边。
//
//   ② `（已删除）` 是**错误的诊断**。副产是原料, 落在 `raw_material_types` 而不是
//      `product_types` —— 它没被删, 只是不在这张表里。告诉用户「已删除」比不说更糟,
//      它会让人去找一个根本没发生的删除操作。
//
// ⇒ 直接复用 `resolveOutputProductLabel`(两张表都查, 查不到给明确中文), 不再各写一份。
//   ⚠️ 它依赖 `rawMaterialTypesForNames`, 而那个 ref 在 `onMounted` 就加载
//   (与 loadData 同批), 表格渲染时可用 —— 不是「机制在、没接上」。
function targetFinishedGoodNames(ids: unknown): string[] {
  if (!Array.isArray(ids)) return [];
  return ids.map((id) => resolveOutputProductLabel(id));
}

// 「核对结单」终端产出 / 「仓库确认入库」产出行的 productTypeId → 品名。
// 两个对话框此前直接把 UUID 打给用户 (实拍截图: eb0aa47b-a5dd-49dc-af20-bf48ce8e1207)。
// 主产/联产在 product_types, 副产是原料落在 raw_material_types (两张表都要查,
// 同 PR #2839 App 待确认入库屏的坑)。查不到时给明确中文说明, 不回落成 UUID/空串
// (仓库/文员看到 UUID 或空白都无法行动 —— 本仓「禁止降级处理」原则)。
function resolveOutputProductLabel(id: unknown): string {
  const key = id == null ? '' : String(id).trim();
  if (!key) return '未识别 SKU';
  const product = productTypes.value.find((p: TableRow) => String(p.id) === key);
  if (product) return String(product.name);
  const material = rawMaterialTypesForNames.value.find((m) => String(m.id) === key);
  if (material) return String(material.name);
  return '未知产品(产品档案查不到)';
}

// 与上面函数里的字面量必须一致 (那个函数被测试抽出来单独执行, 不能引用模块级常量); 由测试钉住。
const UNKNOWN_OUTPUT_PRODUCT_LABEL = '未知产品(产品档案查不到)';
const PRODUCT_CATALOG_LOADING_LABEL = '产品名加载中…';

// ==================== View Plan ====================
const viewDialogVisible = ref(false);
const viewPlan = ref<TableRow | null>(null);

const viewProcessList = ref<ProductWorkProcessItem[]>([]);
const viewProcessLoading = ref(false);

async function handleViewPlan(row: TableRow) {
  viewPlan.value = row;
  viewDialogVisible.value = true;
  // Fetch 工序+负责人 for the plan's product type
  const ptId = row.productTypeId as string | undefined;
  if (ptId && factoryId.value) {
    viewProcessLoading.value = true;
    viewProcessList.value = [];
    try {
      const res = await getProductWorkProcesses(factoryId.value, ptId);
      if (res.success && Array.isArray(res.data)) {
        viewProcessList.value = res.data as ProductWorkProcessItem[];
      }
    } catch (e) {
      console.warn('[计划详情] 加载工序失败', e);
    } finally {
      viewProcessLoading.value = false;
    }
  } else {
    viewProcessList.value = [];
  }
}

// ==================== Reference Data ====================

async function loadReferenceData() {
  if (!factoryId.value) return;
  try {
    const supsRes = await getSupervisors(factoryId.value);
    if (supsRes?.data) {
      supervisors.value = Array.isArray(supsRes.data) ? supsRes.data : (supsRes.data as TableRow).content || [];
    } else if (supsRes && !supsRes.success) {
      ElMessage.error(supsRes.message || '加载主管数据失败');
    }
  } catch (e: any) {
    console.warn('Failed to load reference data:', e);
    if (!e?.actionHint) ElMessage.error(t('production.plans_list.message_c940365a6808'));
  }
}

// ==================== Import / Export ====================

async function handleDownloadTemplate() {
  if (!factoryId.value) return;
  try {
    const response = await downloadImportTemplate(factoryId.value);
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'production-plan-template.xlsx';
    a.click();
    URL.revokeObjectURL(url);
    ElMessage.success(t('production.plans_list.message_98f984d8f02c'));
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

async function handleImportFile(uploadFile: { raw?: File }) {
  if (!uploadFile?.raw) return;

  const file = uploadFile.raw;
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning(t('production.plans_list.message_c82cc48eb707'));
    return;
  }

  try {
    if (!factoryId.value) return;
    const formData = new FormData();
    formData.append('file', file);

    const res = await importProductionPlans(factoryId.value, formData);
    if (res?.data) {
      const r = res.data;
      const failureInfo = r.failureDetails?.length
        ? '\n\n失败详情:\n' + r.failureDetails.map((f) => `第${f.rowNumber}行: ${f.reason}`).join('\n')
        : '';
      ElMessageBox.alert(
        `总计: ${r.totalCount} 条\n成功: ${r.successCount} 条\n失败: ${r.failureCount} 条` + failureInfo,
        t('production.plans_list.message_e4039a1684cb'),
        { confirmButtonText: t('production.plans_list.confirmbuttontext_fac2a67ad878'), callback: () => loadData() }
      );
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '请检查文件格式';
    if (!(e as { actionHint?: unknown })?.actionHint) ElMessage.error('导入失败: ' + msg);
  }
}

async function handleExport() {
  if (!factoryId.value) return;
  try {
    const params: Record<string, string> = {};
    if (searchForm.value.keyword) params.keyword = searchForm.value.keyword;
    if (searchForm.value.status) params.status = searchForm.value.status;

    const response = await exportProductionPlans(factoryId.value, params);
    const blob = new Blob([response.data], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `生产计划_${toDateString(new Date())}.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
    ElMessage.success(t('production.plans_list.message_5603e0105824'));
  } catch (e: any) {
    // Interceptor shows specific toast; dedupe fallback
    console.error('[失败]', e);
  }
}

// ==================== A2: Dialog dirty-check close ====================

function isPlanFormDirty(): boolean {
  const f = planForm.value;
  return !!(
    f.productTypeId ||
    (f.plannedQuantity && f.plannedQuantity !== 0) ||
    f.notes ||
    f.customerId ||
    f.sourceCustomerName ||
    f.processName ||
    f.sourceOrderId ||
    f.sourceOrderItemId ||
    (f.targetFinishedGoodIds && f.targetFinishedGoodIds.length > 0)
  );
}

async function handleDialogClose() {
  if (isPlanFormDirty()) {
    try {
      await ElMessageBox.confirm(t('production.plans_list.message_e096c0566603'), t('production.plans_list.message_f56c6c82203b'), {
        confirmButtonText: t('production.plans_list.confirmbuttontext_6be15072fb5a'),
        cancelButtonText: t('production.plans_list.cancelbuttontext_fd4b9e3b6c68'),
        type: 'warning',
      });
    } catch {
      // 用户取消 → 不关闭
      return;
    }
  }
  workflowCandidateDialogVisible.value = false;
  preferredWorkflowSelection = null;
  dialogVisible.value = false;
}

// ==================== AI Entry ====================

function handleAiFill(params: TableRow) {
  const name = String(params.productTypeName || '');
  const matched = findUniqueProductByName(name, productTypes.value);

  const today = todayStr();
  planForm.value = {
    productTypeId: matched ? String(matched.id) : '',
    plannedQuantity: Number(params.plannedQuantity || 0),
    aiRequestedUnit: canonicalUnitCodeKeepingCount(params.quantityUnit || params.plannedUnit || params.unit),
    plannedDate: String(params.plannedDate || today),
    notes: String(params.notes || ''),
    estimatedWorkers: undefined,
    assignedSupervisorId: '',
    customerId: '',
    sourceCustomerName: '',
    outputOwnership: 'COMPANY_OWNED' as ProductionPlanOutputOwnership,
    materialSupplyMode: 'FACTORY_SUPPLIED' as 'FACTORY_SUPPLIED' | 'CUSTOMER_SUPPLIED',
    processName: String(params.processName || ''),
    batchDate: String(params.batchDate || today),
    sourceType: 'SAFETY_STOCK' as 'CUSTOMER_ORDER' | 'SAFETY_STOCK',
    sourceOrderId: '',
    sourceOrderItemId: '',
    sourceOrderItemIds: [] as string[],
    extraSourceOrderIds: [] as string[],
    customFields: {} as TableRow,
    skipProcessReporting: true as boolean | null,
    // raw-centric 多SKU (2026-07-13): AI 填单命中的单产品同步进多选成品字段,
    // 保持「生产成品」下拉与 productTypeId 一致 (触发 watch → 解析工序图)。
    targetFinishedGoodIds: matched ? [String(matched.id)] : [],
    resolvedCandidates: [],
    jointSupersets: [],
    selectedCandidateWorkflowId: null,
    resolutionMode: '',
  };
  dialogVisible.value = true;
}

function guardProductionPlanAi(params: Record<string, unknown>) {
  return productionPlanAiGuard(params, productTypes.value);
}
</script>

<template>
  <CanvasAwareWrapper module-code="production_plan">
  <div class="page-wrapper">
    <!-- U-NAV-1 业务流程图导航 (Sprint 2 Track G) -->
    <WorkflowBar
      :nodes="workflowStats?.nodes ?? []"
      :loading="workflowLoading"
      :title="t('production.plans_list.title_7936b39fdfd9')"
      :ai-trigger-enabled="true"
      @node-click="handleWorkflowNodeClick"
      @ai-trigger="aiEntryVisible = true"
    />
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('production.plans_list.text_7f75c8c59ed2') }}</span>
            <span class="data-count">{{ t('production.plans_list.text_76e547a8fa54') }} {{ pagination.total }} {{ t('production.plans_list.text_6016bf63b0ff') }}</span>
          </div>
          <div class="header-right">
            <input
              ref="importInputRef"
              type="file"
              accept=".xlsx,.xls"
              class="native-file-input"
              @change="handleNativeImportChange"
            >
            <el-dropdown trigger="click" @command="handleDataCommand">
              <el-button :icon="Download">
                {{ t('production.plans_list.action_1e875ae1f22f') }}<span class="el-icon--right"><ArrowDown /></span>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="template">{{ t('production.plans_list.text_38c9b17b2e1c') }}</el-dropdown-item>
                  <el-dropdown-item v-if="canWrite" command="import">{{ t('production.plans_list.text_b7fde3468e34') }}</el-dropdown-item>
                  <el-dropdown-item command="export">{{ t('production.plans_list.text_74523ee77314') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-dropdown
              v-if="productionBatchMode && selectedPlans.length > 0"
              trigger="click"
              style="margin-left: 8px;"
              @command="handleBatchCommand"
            >
              <el-button type="info" :icon="Printer">
                {{ t('production.plans_list.action_c16ea1167b2f') }}{{ selectedPlans.length }})<span class="el-icon--right"><ArrowDown /></span>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="print-work-orders">
                    {{ t('production.plans_list.text_938f21ea2113') }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button v-if="canWrite" type="success" :icon="ChatDotRound" @click="aiEntryVisible = true" style="margin-left: 8px;">
              {{ t('production.plans_list.action_8fc27d018366') }}
            </el-button>
            <el-button v-if="canWrite" type="warning" :icon="Plus" @click="handleCreateSecondary" style="margin-left: 8px;">
              {{ t('production.plans_list.action_c863e40ebcc5') }}
            </el-button>
            <UButton v-if="canWrite" color="warning" variant="outline" @click="openReversalApproval" style="margin-left: 8px;"
              :title="t('production.plans_list.title_7968cac02ecd')">
              {{ t('production.plans_list.action_e975f9f0b213') }}
            </UButton>
            <el-button data-testid="production-plan-open-create" v-if="canWrite" type="primary" :icon="Plus" @click="handleCreate" style="margin-left: 8px;">
              {{ t('production.plans_list.action_87bb612bfa59') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <el-input
          v-model="searchForm.keyword"
          :placeholder="t('production.plans_list.placeholder_83692df06975')"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <CompatSelect v-model="searchForm.status" :placeholder="t('production.plans_list.placeholder_0a379c1e7398')" clearable style="width: 150px">
          <!-- Rule 1 (fool-proof-design): "全部" 必须是显式可选项, 不能只靠 clearable 的隐藏 x 图标才能回到不限状态
               —— 否则搜索一个已完成/已取消的计划号, 默认"未完成"筛选下静默 0 结果, 用户以为查不到 -->
          <CompatOption :label="t('production.plans_list.label_5c55a67935af')" value="" />
          <CompatOption :label="t('production.plans_list.label_6707de42c29d')" value="UNFINISHED" />
          <CompatOption :label="t('production.plans_list.label_2a2fd29bd27a')" value="PREPARED" />
          <CompatOption :label="t('production.plans_list.label_637da5612300')" value="PLANNED" />
          <CompatOption :label="t('production.plans_list.label_fdbb297c4fe6')" value="PENDING" />
          <CompatOption :label="t('production.plans_list.label_dc9591e56d50')" value="IN_PROGRESS" />
          <CompatOption :label="t('production.plans_list.label_8d12fc0d4eb2')" value="PAUSED" />
          <CompatOption :label="t('production.plans_list.label_8c09bfd463a1')" value="COMPLETED" />
          <CompatOption :label="t('production.plans_list.label_a37778f17c5f')" value="CANCELLED" />
        </CompatSelect>
        <el-button type="primary" :icon="Search" @click="handleSearch">{{ t('production.plans_list.action_44ce7ae909bb') }}</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">{{ t('production.plans_list.action_cb5d682bac3d') }}</el-button>
        <el-button
          :type="productionBatchMode ? 'primary' : 'default'"
          plain
          :icon="Printer"
          @click="toggleProductionBatchMode"
        >
          {{ productionBatchMode ? t('production.plans_list.text_e4015df34b5b') : t('production.plans_list.text_9fc37904e840') }}
        </el-button>
        <ListColumnSelector
          v-model="productionVisibleColumnKeys"
          :columns="productionColumnOptions"
          :max="productionColumnMax"
          @reset="resetProductionColumns"
        />
      </div>

      <!-- #726 SP12: @selection-change 驱动合并打印 -->
      <el-table
        :key="productionBatchMode ? 'production-batch' : 'production-standard'"
        :data="tableData"
        v-loading="loading"
        :empty-text="emptyText"
        stripe
        border
        table-layout="fixed"
        :scrollbar-always-on="true"
        class="wide-table business-list-table"
        style="width: 100%"
        :row-class-name="planRowClassName"
        @selection-change="handleSelectionChange"
      >
        <el-table-column v-if="productionBatchMode" type="selection" width="45" />
        <el-table-column prop="planNumber" :label="t('production.plans_list.label_c68a7f735afe')" width="160" sortable>
          <template #default="{ row }">
            <UButton
              color="primary"
              variant="link"
              size="sm"
              class="plan-number-link"
              :title="t('production.plans_list.title_95e2d37cc442')"
              @click="handleViewPlan(row)"
            >{{ row.planNumber || row.id }}</UButton>
          </template>
        </el-table-column>
        <el-table-column prop="productTypeName" :label="t('production.plans_list.label_f6469fb8876c')" min-width="240" sortable>
          <template #default="{ row }">
            <div class="plan-product-cell">
              <span class="plan-product-name">{{ planFinishedProductNames(row).join('、') }}</span>
              <el-popover
                placement="right-start"
                trigger="hover"
                :width="780"
                @show="ensureRowWorkflowPreview(row)"
              >
                <template #reference>
                  <UButton
                    class="workflow-preview-trigger"
                    color="primary"
                    variant="link"
                    size="sm"
                    :aria-label="t('production.plans_list.aria_label_1f59eb7105b7')"
                  >{{ t('production.plans_list.action_14d84871122a') }}</UButton>
                </template>
                <div v-loading="rowWorkflowPreviews[String(row.id || '')]?.loading" class="plan-workflow-preview">
                  <WorkflowRoutePreview
                    v-if="rowWorkflowPreviews[String(row.id || '')]?.candidate"
                    :nodes="rowWorkflowPreviews[String(row.id || '')]?.candidate?.previewNodes"
                    :edges="rowWorkflowPreviews[String(row.id || '')]?.candidate?.previewEdges"
                  />
                  <el-empty
                    v-else-if="!rowWorkflowPreviews[String(row.id || '')]?.loading"
                    :description="rowWorkflowPreviews[String(row.id || '')]?.error || t('production.plans_list.description_5f40ac52bb8c')"
                    :image-size="48"
                  />
                </div>
              </el-popover>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('customer')" prop="sourceCustomerName" :label="t('production.plans_list.label_ad4af312dbc2')" min-width="120" show-overflow-tooltip sortable />
        <el-table-column v-if="isProductionColumnVisible('process')" prop="processName" :label="t('production.plans_list.label_648cd106a0b7')" width="120" show-overflow-tooltip sortable />
        <el-table-column v-if="isProductionColumnVisible('batchDate')" prop="batchDate" :label="t('production.plans_list.label_4ab95394a48e')" width="120" sortable />
        <el-table-column
          v-if="isProductionColumnVisible('plannedQuantity')"
          prop="plannedQuantity"
          :label="t('production.plans_list.label_79c31e394ad1')"
          width="110"
          align="right"
          sortable
          :sort-method="(a: TableRow, b: TableRow) => compareNullableNumber(a.plannedQuantity, b.plannedQuantity)"
        >
          <template #default="{ row }">{{ formatPlanDisplayQuantity(row) }}</template>
        </el-table-column>
        <el-table-column :label="t('production.plans_list.label_ffb141f8ff73')" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="productionPlanType(row) === 'ORDER' ? 'primary' : 'info'"
            >{{ productionPlanTypeLabel(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          v-if="isProductionColumnVisible('actualQuantity')"
          prop="actualQuantity"
          :label="t('production.plans_list.label_dbf1981b4fdb')"
          width="120"
          align="right"
          sortable
          :sort-method="(a: TableRow, b: TableRow) => compareNullableNumber(a.actualQuantity, b.actualQuantity)"
        >
          <template #default="{ row }">{{ formatPlanActualQuantity(row) }}</template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('plannedDate')" prop="plannedDate" :label="t('production.plans_list.label_011247215d19')" width="120" sortable />
        <el-table-column
          prop="status"
          :label="t('production.plans_list.label_6320b4a8722a')"
          width="100"
          align="center"
          :filters="productionStatusFilters"
          :filter-method="filterProductionStatus"
        >
          <template #default="{ row }">
            <el-tag
              :type="getStatusType(row.status)"
              size="large"
              effect="dark"
              class="plan-status-tag"
              :class="planStatusClass(row.status)"
            >
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('inventoryStatus')" :label="t('production.plans_list.label_a54c13487c75')" width="125" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="getSettlementStatus(row)"
              :type="postingStatusType(getSettlementStatus(row)?.postingStatus)"
              size="small"
              effect="plain"
            >
              {{ postingStatusText(getSettlementStatus(row)?.postingStatus) }}
            </el-tag>
            <el-tooltip
              v-else-if="isSettlementAbsent(row)"
              content="本计划走存货生产小结，成品已在小结时入库，没有单独的仓库确认单"
              placement="top"
            >
              <UBadge color="info" size="sm" variant="outline">{{ t('production.plans_list.text_4ed501a4eb64') }}</UBadge>
            </el-tooltip>
            <el-tooltip
              v-else-if="getSettlementStatusError(row)"
              :content="getSettlementStatusError(row)"
              placement="top"
            >
              <UBadge color="error" size="sm" variant="outline">{{ t('production.plans_list.text_d1d044826a45') }}</UBadge>
            </el-tooltip>
            <UBadge variant="soft" v-else-if="String(row.status || '').toUpperCase() === 'COMPLETED'" color="info" size="sm">
              {{ t('production.plans_list.text_4ada3d1f26c9') }}
            </UBadge>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('materialReference')" :label="t('production.plans_list.label_407a177f0d11')" width="130" align="center">
          <template #default="{ row }">
            <el-tooltip
              v-if="getPlanAdvisory(row)?.hasWarning"
              :content="getPlanAdvisorySummary(row)"
              placement="top"
            >
              <el-tag type="danger" size="small" :icon="Warning">{{ t('production.plans_list.text_a0323f11cfe5') }}</el-tag>
            </el-tooltip>
            <el-tooltip
              v-else-if="getPlanAdvisory(row)"
              :content="getPlanAdvisorySummary(row)"
              placement="top"
            >
              <UBadge variant="soft" color="success" size="sm">{{ t('production.plans_list.text_7dfba9f30f37') }}</UBadge>
            </el-tooltip>
            <UBadge variant="soft" v-else-if="isUnfinishedStatus(row.status)" color="info" size="sm">{{ t('production.plans_list.text_d04fcbda737f') }}</UBadge>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('nextStep')" :label="t('production.plans_list.label_acfc4e74a650')" min-width="220">
          <template #default="{ row }">
            <div class="next-step-cell">
              <el-tag :type="nextStepTagType(row)" size="small" effect="plain">
                {{ nextStepText(row) }}
              </el-tag>
              <div v-if="row.skipProcessReporting === false" class="next-step-hint">
                {{ t('production.plans_list.text_1d11058f5210') }}
              </div>
              <div v-else-if="isUnfinishedStatus(row.status)" class="next-step-hint">
                {{ t('production.plans_list.text_1e8ba74cf965') }}
              </div>
              <div
                v-else-if="cancellationAuditFor(row)"
                class="cancellation-audit"
                :title="`取消原因：${cancellationAuditFor(row)?.reason}`"
              >
                <div class="cancellation-audit__reason">
                  {{ t('production.plans_list.text_b9fbb8ede4cb') }}{{ cancellationAuditFor(row)?.reason }}
                </div>
                <div>{{ cancellationAuditFor(row)?.actor }} · {{ cancellationAuditFor(row)?.time }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('workers')" prop="estimatedWorkers" :label="t('production.plans_list.label_7dd57ce90957')" width="90" align="center" sortable />
        <el-table-column v-if="isProductionColumnVisible('supervisor')" prop="assignedSupervisorName" :label="t('production.plans_list.label_8f4fdba6fb72')" width="100" show-overflow-tooltip sortable />
        <el-table-column
          v-if="isProductionColumnVisible('source')"
          prop="sourceType"
          :label="t('production.plans_list.label_a488e93d69cc')"
          min-width="170"
          align="center"
          :filters="productionSourceFilters"
          :filter-method="filterProductionSource"
        >
          <template #default="{ row }">
            <!-- SP2: planNumber 前缀 SEC- 标识独立再加工/返工 (planSourceType 字段未暴露在 DTO) -->
            <UBadge variant="soft" v-if="String(row.planNumber || '').startsWith('SEC-')" color="warning" size="sm">{{ t('production.plans_list.text_cb80423476c0') }}</UBadge>
            <UBadge variant="soft" v-else-if="row.sourceType === 'EXCEL_IMPORT'" color="warning" size="sm">{{ t('production.plans_list.text_d4d56720f342') }}</UBadge>
            <UBadge variant="soft" v-else-if="row.sourceType === 'AI_CHAT'" color="success" size="sm">{{ t('production.plans_list.text_a5057dd3ee5b') }}</UBadge>
            <UBadge variant="soft" v-else-if="row.sourceType === 'SAFETY_STOCK'" color="info" size="sm">{{ t('production.plans_list.text_e391b8370ca2') }}</UBadge>
            <UBadge variant="soft" v-else-if="row.sourceType === 'CUSTOMER_ORDER'" color="primary" size="sm">{{ t('production.plans_list.text_68bd4b5a1bf2') }}</UBadge>
            <UBadge variant="soft" v-else-if="row.sourceType === 'AI_FORECAST'" color="success" size="sm">{{ t('production.plans_list.text_5bc21e98c136') }}</UBadge>
            <UBadge variant="soft" v-else size="sm">{{ t('production.plans_list.text_962f41ef825b') }}</UBadge>
            <UButton
              v-if="row.sourceType === 'CUSTOMER_ORDER' && row.sourceOrderId"
              color="primary"
              variant="link"
              size="sm"
              class="source-order-link"
              :title="sourceOrderBusinessNumber(row) ? t('production.plans_list.title_635c9754d592') : t('production.plans_list.title_0aa0beca5c30')"
              @click="openSourceOrder(row)"
            >{{ sourceOrderDisplay(row) }}</UButton>
          </template>
        </el-table-column>
        <!-- Wave2: 报工模式 badge -->
        <el-table-column v-if="isProductionColumnVisible('reportMode')" :label="t('production.plans_list.label_32ac10e7e9ae')" width="100" align="center">
          <template #default="{ row }">
            <UBadge variant="soft"
              v-if="row.skipProcessReporting === false"
              color="warning"
              size="sm"
              :title="t('production.plans_list.title_9c4db651846b')"
            >{{ t('production.plans_list.text_0ccc3e54cd13') }}</UBadge>
            <UBadge variant="soft"
              v-else
              color="info"
              size="sm"
              :title="t('production.plans_list.title_d0836b5890b0')"
            >{{ t('production.plans_list.text_1287b1f994d8') }}</UBadge>
          </template>
        </el-table-column>
        <el-table-column v-if="isProductionColumnVisible('createdAt')" prop="createdAt" :label="t('production.plans_list.label_07ec86e0f1d4')" width="180" :formatter="formatDateTimeCell" sortable />
        <el-table-column
          :label="t('production.plans_list.label_ed31fbb483ee')"
          width="360"
          fixed="right"
          align="center"
          class-name="business-list-table__actions"
        >
          <template #default="{ row }">
            <div class="plan-action-cell business-action-row">
              <UButton
                :data-testid="`production-plan-create-batch-${row.id}`"
                v-if="canShowProductionActions(row)"
                color="warning"
                variant="link"
                size="sm"
                :loading="entryPreparingPlanId === String(row.id)"
                :disabled="entryPreparingPlanId !== null"
                @click="openProcessEntry(row)"
              >{{ processEntryActionLabel(row) }}</UButton>
              <UButton
                :data-testid="`production-settle-${row.id}`"
                v-if="canShowProductionActions(row)"
                color="success"
                variant="link"
                size="sm"
                :loading="row.sourceType === 'SAFETY_STOCK'
                  ? interimSettleLoadingId === String(row.id)
                  : actionLoading"
                @click="handlePrimarySettlementAction(row)"
              >{{ settlementActionLabel(row) }}</UButton>
              <!--
                🔴 2026-08-02: 补回「仓库确认入库」入口。
                handleWarehouseReceipt 与整个 receiptDialog 一直都在, 但**模板从未引用它** ——
                和 handleStart 同一种死代码形状。后果是链条最后一段彻底走不通:
                计划结单后「下一步」明写「仓库确认入库」(见 nextStepText), 而界面上
                没有任何地方能做这件事 → 成品批次永远生不出来 → 销售单永远发不了货。
                2026-08-02 prod 实测: 后端 /warehouse-receipt 完全正常(空 body 返 400 校验而非 404),
                手工调 API 立刻 postingStatus=POSTED 并生成成品批次 —— 缺的只是这个按钮。
                判据复用既有的 postingStatus, 不新造一套。
              -->
              <UButton
                :data-testid="`finished-goods-receive-${row.id}`"
                v-if="needsWarehouseReceipt(row)"
                color="warning"
                variant="link"
                size="sm"
                :loading="receiptLoading && String(receiptRow?.id) === String(row.id)"
                @click="handleWarehouseReceipt(row)"
              >{{ t('production.plans_list.action_c3e464c67c4e') }}</UButton>
              <!--
                🔴 2026-08-04: 补回「中转挂账清账」入口 —— 结算链的最后一段。
                #2176 补回上面那个按钮时, 把 PENDING_CLEARING 也算进了 needsWarehouseReceipt,
                注释写的是「同一个弹窗处理清账」。但代码里不是: handleWarehouseReceipt 没有
                PENDING_CLEARING 分支, 提交走的是 confirmProductionWarehouseReceipt(入库 API);
                真正带清账 API 的 handleTransitClearing 与整个 clearingDialog 全文件**零调用点**
                (同 handleWarehouseReceipt 当初那种死代码形状)。
                后果: 实收短少超容差挂到中转仓后, 列表「下一步」明写「中转挂账清账」
                (见 nextStepText), 用户点到的却是入库弹窗 —— 清账做不了。
                判据复用既有的 canClearTransit(它已含 COMPLETED + PENDING_CLEARING + 写权限)。
              -->
              <UButton
                v-if="canClearTransit(row)"
                color="error"
                variant="link"
                size="sm"
                :loading="clearingLoading && String(clearingRow?.id) === String(row.id)"
                @click="handleTransitClearing(row)"
              >{{ t('production.plans_list.action_615b303181b9') }}</UButton>
              <UButton
                v-if="canRecalculateSettledCost(row)"
                color="primary"
                variant="link"
                size="sm"
                :loading="costRecalculatingPlanId === String(row.id)"
                @click="handleRecalculateSettledCost(row)"
              >{{ t('production.plans_list.action_6a856be3ae75') }}</UButton>
              <UButton
                color="primary"
                variant="link"
                size="sm"
                @click="openArchiveCenter(row)"
              >{{ t('production.plans_list.action_6d219d873e20') }}</UButton>
            <!--
              「更多」的显隐与每一项的显隐都出自 productionMoreCommands(row) 一个判据:
              一项都没有就不渲染按钮 (2026-08-04: 进行中+非存货生产+已有产出的行原先会弹出
              一个空白小方块), 也避免闸和菜单项两处口径各自漂移。
            -->
            <el-dropdown
              v-if="canShowProductionActions(row) && productionMoreCommands(row).length > 0"
              trigger="click"
              @command="(command: ProductionActionCommand) => handleProductionActionCommand(command, row)"
              @visible-change="(visible: boolean) => visible && ensureAssignOperatorCandidates(row)"
            >
              <UButton color="info" variant="link" size="sm">
                {{ t('production.plans_list.action_38844b135cf7') }}<span class="el-icon--right"><ArrowDown /></span>
              </UButton>
              <template #dropdown>
                <el-dropdown-menu>
                  <!-- 防呆 Rule 1: 不可编辑时灰显并写清原因, 不让用户点开才知道 -->
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'edit')"
                    command="edit"
                    :disabled="planEditBlockedReason(row) !== null || actionLoading"
                  >{{ planEditMenuLabel(row) }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'copy')"
                    command="copy"
                    :disabled="actionLoading"
                  >{{ t('production.plans_list.text_d6fc1af05749') }}</el-dropdown-item>
                  <!--
                    缺陷 51: 「分配工序操作员」——生产计划开工后生成的 PB-* 成品批次才挂工序任务,
                    这里下拉打开时惰性解析(ensureAssignOperatorCandidates), 无成品批次时灰显并说清
                    原因, 而不是点了才知道 (与「编辑」同一条防呆规矩)。
                  -->
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'assign-process-operator')"
                    command="assign-process-operator"
                    :disabled="actionLoading || assignOperatorDisabledReason(row) !== ''"
                    :title="assignOperatorDisabledReason(row)"
                  >{{ t('production.plans_list.text_7950b0f4cdef') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'start-secondary')"
                    command="start-secondary"
                    divided
                    :disabled="actionLoading"
                  >{{ t('production.plans_list.text_e798387ba5d6') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'app-reporting')"
                    command="app-reporting"
                    divided
                    :disabled="actionLoading"
                  >{{ t('production.plans_list.text_420ace87350e') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'generate-transfer')"
                    command="generate-transfer"
                    :disabled="actionLoading"
                  >{{ t('production.plans_list.text_4712399e10b3') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'reverse-interim-settle')"
                    command="reverse-interim-settle"
                    divided
                    :disabled="reverseInterimSettleLoadingId === String(row.id)"
                  >{{ t('production.plans_list.text_a44dfe126fc1') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'stop-production')"
                    command="stop-production"
                    :disabled="stopProductionLoadingId === String(row.id)"
                  >{{ t('production.plans_list.text_37a9ba378c9f') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'repin-authority')"
                    command="repin-authority"
                    divided
                    :disabled="actionLoading || row.canRepinAuthority !== true"
                    :title="repinAuthorityBlockedReason(row)"
                  >{{ row.canRepinAuthority === true
                    ? t('production.plans_list.text_d081b003835b')
                    : `更新到当前配方（${repinAuthorityBlockedReason(row)}）` }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'cancel')"
                    command="cancel"
                    divided
                    :disabled="actionLoading"
                  >{{ t('production.plans_list.text_10c3a2e7a980') }}</el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasProductionMoreCommand(row, 'stop-blocked')"
                    disabled
                  >{{ t('production.plans_list.text_541c34bca691') }}{{ row.stopBlockedReason }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <TableFooter
        :stats="footerSummary?.stats ?? []"
        :loading="footerLoading"
        :show-export="false"
        @ai-analyze="() => ElMessage.info({ message: `AI 分析 (待接 SmartBI): 分析当前生产计划${formatSummaryForAI(footerSummary, { filter: { status: searchForm.status, keyword: searchForm.keyword } })}`, duration: 8000, showClose: true })"
      />

      <div class="pagination-wrapper">
        <CompatPagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :page-sizes="[10, 20, 50, 100]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </UCard>

    <el-drawer
      v-model="archiveCenterVisible"
      :title="`档案与核算 — ${archiveCenterPlan?.planNumber || archiveCenterPlan?.id || ''}`"
      size="680px"
      destroy-on-close
    >
      <template v-if="archiveCenterPlan">
        <CompatDescriptions :column="2" border size="small" class="archive-center-summary">
          <CompatDescriptionsItem :label="t('production.plans_list.label_aea82737cc01')">
            {{ archiveCenterPlan.productTypeName || archiveCenterPlan.productName || archiveCenterPlan.productTypeId || '-' }}
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('production.plans_list.label_6320b4a8722a')">
            <el-tag :type="getStatusType(archiveCenterPlan.status as string)" size="small">
              {{ getStatusText(archiveCenterPlan.status as string) }}
            </el-tag>
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('production.plans_list.label_472876096b98')">
            {{ sourceOrderDisplay(archiveCenterPlan) }}
          </CompatDescriptionsItem>
          <CompatDescriptionsItem :label="t('production.plans_list.label_32ac10e7e9ae')">
            {{ archiveCenterPlan.skipProcessReporting === false ? t('production.plans_list.text_79c2f490b43a') : t('production.plans_list.text_c08a8d7acc29') }}
          </CompatDescriptionsItem>
        </CompatDescriptions>

        <el-tabs v-model="archiveCenterTab" class="archive-center-tabs">
          <el-tab-pane :label="t('production.plans_list.label_bf4260a7cbc6')" name="documents">
            <div class="archive-center-grid">
              <button
                type="button"
                class="archive-center-card"
                :disabled="!canPrintPlanDocuments(archiveCenterPlan.status)"
                @click="openArchiveDocument('work-order')"
              >
                <strong>{{ t('production.plans_list.text_ab2f0c85a7aa') }}</strong>
                <span>{{ t('production.plans_list.text_f00a22ad31dc') }}</span>
              </button>
              <button
                type="button"
                class="archive-center-card"
                :disabled="!canPrintPlanDocuments(archiveCenterPlan.status)"
                @click="openArchiveDocument('material-requisition')"
              >
                <strong>{{ t('production.plans_list.text_17b4e90233e2') }}</strong>
                <span>{{ t('production.plans_list.text_9aace1582995') }}</span>
              </button>
              <button
                type="button"
                class="archive-center-card"
                :disabled="!canPrintPlanDocuments(archiveCenterPlan.status)"
                @click="openArchiveDocument('batching-sheet')"
              >
                <strong>{{ t('production.plans_list.text_147ccc80903a') }}</strong>
                <span>{{ t('production.plans_list.text_62408ef4041f') }}</span>
              </button>
              <button
                type="button"
                class="archive-center-card archive-center-card--primary"
                :disabled="!canPrintPlanDocuments(archiveCenterPlan.status)"
                @click="openArchiveDocument('document-pack')"
              >
                <strong>{{ t('production.plans_list.text_d8b5f2ec5998') }}</strong>
                <span>{{ t('production.plans_list.text_f4399bf13193') }}</span>
              </button>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="t('production.plans_list.label_a748f10410ab')" name="trace">
            <el-alert
              :title="t('production.plans_list.title_91ca788502ae')"
              type="info"
              :closable="false"
              show-icon
            />
            <div class="archive-center-actions">
              <UButton color="primary" @click="openArchiveTrace">{{ t('production.plans_list.action_9d51cac8a1b2') }}</UButton>
              <UButton color="neutral" variant="outline"
                v-if="archiveCenterPlan.sourceType === 'CUSTOMER_ORDER' && archiveCenterPlan.sourceOrderId"
                @click="openSourceOrder(archiveCenterPlan)"
              >{{ t('production.plans_list.action_635c9754d592') }}</UButton>
            </div>
          </el-tab-pane>
          <el-tab-pane :label="t('production.plans_list.label_41cc50ef4963')" name="accounting">
            <el-alert
              :title="t('production.plans_list.title_968ae199fec7')"
              type="warning"
              :closable="false"
              show-icon
            />
            <div class="archive-center-grid archive-center-grid--accounting">
              <button type="button" class="archive-center-card" @click="openArchiveSummary">
                <strong>{{ t('production.plans_list.text_c506d4cfc1c6') }}</strong>
                <span>{{ t('production.plans_list.text_cac05f5e3cbf') }}</span>
              </button>
              <button type="button" class="archive-center-card" @click="openArchiveYieldCost">
                <strong>{{ yieldCostActionLabel(archiveCenterPlan) }}</strong>
                <span>
                  {{ String(archiveCenterPlan.status || '').toUpperCase() === 'COMPLETED'
                    ? t('production.plans_list.text_81ade0489dfd')
                    : t('production.plans_list.text_55da86798022') }}
                </span>
              </button>
            </div>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>

    <!--
      缺陷 51: 一张计划可能有多个成品批次(联产/多产品), 「分配工序操作员」不能悄悄取第一个 ——
      多候选时弹这个选择框, 用户自己挑要分配哪个批次的工序。
    -->
    <CompatDialog
      v-model="assignOperatorPickerVisible"
      :title="t('production.plans_list.title_afb2e4aaa4bf')"
      width="440px"
      data-testid="assign-operator-picker"
    >
      <div v-if="assignOperatorPickerRow" class="assign-operator-picker">
        <p class="assign-operator-picker-hint">
          {{ t('production.plans_list.text_7d574932b83d') }}
        </p>
        <div
          v-for="candidate in (assignOperatorState[String(assignOperatorPickerRow.id || '')]?.candidates || [])"
          :key="candidate.documentId"
          class="assign-operator-picker-item"
          data-testid="assign-operator-picker-item"
        >
          <span>{{ candidate.documentNumber || candidate.documentId }}</span>
          <UButton
            color="primary"
            variant="link"
            @click="goToAssignOperatorBatch(candidate)"
          >{{ t('production.plans_list.action_b9d0065fb113') }}</UButton>
        </div>
      </div>
    </CompatDialog>

    <CompatDialog
      v-model="documentPackVisible"
      :title="t('production.plans_list.title_8a30ea5294c4')"
      width="520px"
      destroy-on-close
    >
      <el-alert
        :title="t('production.plans_list.title_58355b3b22da')"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
      />
      <CompatDescriptions v-if="documentPackPlan" :column="1" border size="small" style="margin-bottom: 16px;">
        <CompatDescriptionsItem :label="t('production.plans_list.label_d8393f4287a5')">
          {{ documentPackPlan.planNumber || documentPackPlan.id }}
        </CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('production.plans_list.label_68bd4b5a1bf2')">
          {{ sourceOrderDisplay(documentPackPlan) }}
        </CompatDescriptionsItem>
      </CompatDescriptions>
      <div class="document-pack-chapters">
        <div class="document-pack-label">{{ t('production.plans_list.text_587197506773') }}</div>
        <el-checkbox-group v-model="selectedDocumentChapters">
          <el-checkbox
            v-for="chapter in PRODUCTION_DOCUMENT_CHAPTERS"
            :key="chapter.value"
            :value="chapter.value"
          >{{ chapter.label }}</el-checkbox>
        </el-checkbox-group>
      </div>
      <el-alert
        v-if="selectedDocumentChapters.length === 0"
        :title="t('production.plans_list.title_1878ad272f53')"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 14px;"
      />
      <template #footer>
        <UButton color="neutral" variant="outline" @click="documentPackVisible = false">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="primary"
          :loading="documentPackLoading"
          :disabled="selectedDocumentChapters.length === 0"
          @click="submitProductionDocumentPack"
        >{{ t('production.plans_list.action_8c13c646c7eb') }}</UButton>
      </template>
    </CompatDialog>

    <el-drawer v-model="documentTraceVisible" :title="t('production.plans_list.title_79613a048f62')" size="620px">
      <div v-loading="documentTraceLoading" class="document-trace">
        <template v-if="documentTrace">
          <CompatDescriptions :column="2" border style="margin-bottom: 16px">
            <CompatDescriptionsItem :label="t('production.plans_list.label_d8393f4287a5')">{{ documentTrace.planNumber }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_6320b4a8722a')">{{ documentTrace.planStatus || '-' }}</CompatDescriptionsItem>
          </CompatDescriptions>
          <el-alert
            v-for="missing in documentTrace.missingLinks"
            :key="missing"
            :title="missing"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 10px"
          />
          <UEmpty v-if="documentTrace.documents.length === 0" :description="t('production.plans_list.description_5792b7476775')" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="document in documentTrace.documents"
              :key="`${document.documentType}-${document.documentId}`"
              :timestamp="document.occurredAt || undefined"
              placement="top"
            >
              <el-card
                shadow="never"
                class="trace-document-card"
                :class="{ 'is-selected': traceDocumentKey(document) === expandedTraceKey }"
                data-testid="trace-document-card"
                @click="toggleTraceDocument(document)"
              >
                <div class="trace-document-header">
                  <div>
                    <UBadge size="sm" variant="outline">{{ traceDirectionLabel(document.direction) }}</UBadge>
                    <strong>{{ traceDocumentLabel(document.documentType) }}</strong>
                  </div>
                  <!-- 不跳页, 就地展开 (客户 2026-07-31); 与共用的 DocumentTraceDrawer 同口径 -->
                  <UButton
                    color="primary"
                    variant="link"
                    data-testid="trace-detail-toggle"
                    :aria-expanded="traceDocumentKey(document) === expandedTraceKey"
                    @click.stop="toggleTraceDocument(document)"
                  >{{ traceDocumentKey(document) === expandedTraceKey ? t('production.plans_list.text_afd4b783536b') : t('production.plans_list.text_a748cc074f78') }}</UButton>
                </div>
                <div class="trace-document-number">{{ document.documentNumber || document.documentId }}</div>
                <div class="trace-document-meta">
                  <span>{{ document.relation || '-' }}</span>
                  <!-- 按单据类型选状态表: 同一个码在不同单据含义不同 (REJECTED 收货是「已退回」,
                       FINANCE_APPROVED 销售是「已批准」采购是「已审核」), 全局表必然有一半显示错。
                       客户 2026-07-30 在这里看到的「未知状态（IN_PROGRESS）」= 生产批次的「生产中」。 -->
                  <UBadge variant="soft" v-if="document.status" size="sm" color="info">{{ traceStatusLabel(document.documentType, document.status) }}</UBadge>
                </div>
                <CompatDescriptions
                  v-if="traceDocumentKey(document) === expandedTraceKey"
                  class="trace-document-detail"
                  data-testid="trace-detail-panel"
                  :column="1"
                  border
                  size="small"
                >
                  <CompatDescriptionsItem v-if="document.occurredAt" :label="t('production.plans_list.label_81a54d6edff7')">
                    {{ document.occurredAt }}
                  </CompatDescriptionsItem>
                  <CompatDescriptionsItem
                    v-for="field in document.details || []"
                    :key="field.label"
                    :label="field.label"
                  >{{ field.value }}</CompatDescriptionsItem>
                </CompatDescriptions>
              </el-card>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>

    <!-- 查看计划详情 -->
    <CompatDialog v-model="viewDialogVisible" :title="t('production.plans_list.title_30bf41953b38')" width="680px" destroy-on-close>
      <div v-if="viewPlan" class="plan-detail">
        <!-- 基本信息 -->
        <div class="detail-section">
          <div class="detail-section-title">{{ t('production.plans_list.text_e8df05872569') }}</div>
          <CompatDescriptions :column="2" border size="small">
            <CompatDescriptionsItem :label="t('production.plans_list.label_c68a7f735afe')">{{ viewPlan.planNumber }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_0b3894ed2a38')">
              {{ viewPlan.productTypeName || viewPlan.productName || viewPlan.productTypeId || '-' }}
              <template v-if="Array.isArray(viewPlan?.targetFinishedGoodIds) && viewPlan.targetFinishedGoodIds.length > 0">
                <UBadge variant="soft"
                  v-for="(name, idx) in targetFinishedGoodNames(viewPlan.targetFinishedGoodIds)"
                  :key="idx"
                  size="sm"
                  style="margin-left: 4px;"
                >→ {{ name }}</UBadge>
              </template>
            </CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_ad4af312dbc2')">{{ viewPlan.sourceCustomerName || '-' }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_8f4fdba6fb72')">{{ viewPlan.assignedSupervisorName || '-' }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_79c31e394ad1')">{{ formatPlanDisplayQuantity(viewPlan) }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_dbf1981b4fdb')">{{ formatPlanActualQuantity(viewPlan) }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_6320b4a8722a')">
              <el-tag :type="getStatusType(viewPlan.status as string)" size="small">{{ getStatusText(viewPlan.status as string) }}</el-tag>
            </CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_a488e93d69cc')">
              <!-- SP2 -->
              <UBadge variant="soft" v-if="String(viewPlan.planNumber || '').startsWith('SEC-')" color="warning" size="sm">{{ t('production.plans_list.text_cb80423476c0') }}</UBadge>
              <UBadge variant="soft" v-else-if="viewPlan.sourceType === 'EXCEL_IMPORT'" color="warning" size="sm">{{ t('production.plans_list.text_d4d56720f342') }}</UBadge>
              <UBadge variant="soft" v-else-if="viewPlan.sourceType === 'AI_CHAT'" color="success" size="sm">{{ t('production.plans_list.text_a5057dd3ee5b') }}</UBadge>
              <UBadge variant="soft" v-else-if="viewPlan.sourceType === 'SAFETY_STOCK'" color="info" size="sm">{{ t('production.plans_list.text_e391b8370ca2') }}</UBadge>
              <UBadge variant="soft" v-else-if="viewPlan.sourceType === 'CUSTOMER_ORDER'" color="primary" size="sm">{{ t('production.plans_list.text_68bd4b5a1bf2') }}</UBadge>
              <UBadge variant="soft" v-else-if="viewPlan.sourceType === 'AI_FORECAST'" color="success" size="sm">{{ t('production.plans_list.text_5bc21e98c136') }}</UBadge>
              <UBadge variant="soft" v-else size="sm">{{ t('production.plans_list.text_962f41ef825b') }}</UBadge>
            </CompatDescriptionsItem>
            <CompatDescriptionsItem
              v-if="viewPlan.sourceType === 'CUSTOMER_ORDER'"
              :label="t('production.plans_list.label_68bd4b5a1bf2')"
              :span="2"
            >
              <UButton
                v-if="viewPlan.sourceOrderId"
                color="primary"
                variant="link"
                @click="openSourceOrder(viewPlan)"
              >{{ sourceOrderDisplay(viewPlan) }}</UButton>
              <span v-else>—</span>
            </CompatDescriptionsItem>
            <!-- 多订单计划只展示数量；完整业务单号通过单据追溯读取，避免泄漏内部 UUID。 -->
            <CompatDescriptionsItem
              v-if="Array.isArray((viewPlan as any).sourceOrderIds) && (viewPlan as any).sourceOrderIds.length > 1"
              :label="t('production.plans_list.label_a2d55bbc766f')"
              :span="2"
            >
              {{ t('production.plans_list.text_76e547a8fa54') }} {{ (viewPlan as any).sourceOrderIds.length }} {{ t('production.plans_list.text_b5f3d23178ab') }}
            </CompatDescriptionsItem>
          </CompatDescriptions>
        </div>

        <!-- 时间 -->
        <div class="detail-section">
          <div class="detail-section-title">{{ t('production.plans_list.text_8b6ff498515b') }}</div>
          <CompatDescriptions :column="2" border size="small">
            <CompatDescriptionsItem :label="t('production.plans_list.label_011247215d19')">{{ viewPlan.plannedDate || '-' }}</CompatDescriptionsItem>
            <CompatDescriptionsItem :label="t('production.plans_list.label_4ab95394a48e')">{{ viewPlan.batchDate || '-' }}</CompatDescriptionsItem>
          </CompatDescriptions>
        </div>

        <!-- 工序 + 负责人 -->
        <div class="detail-section">
          <div class="detail-section-title">
            {{ t('production.plans_list.text_648cd106a0b7') }}
            <span v-if="viewProcessLoading" class="detail-section-hint">{{ t('production.plans_list.text_4927a53bcc88') }}</span>
            <span v-else-if="viewProcessList.length > 0" class="detail-section-hint">{{ viewProcessList.length }} {{ t('production.plans_list.text_00a9d5bd97c2') }}</span>
          </div>
          <div v-if="viewProcessLoading" class="process-list-loading">
            <span class="is-loading"><Refresh /></span>
            <span>{{ t('production.plans_list.text_6ff7c948d398') }}</span>
          </div>
          <ol v-else-if="viewProcessList.length > 0" class="process-list">
            <li v-for="(wp, idx) in viewProcessList" :key="wp.id" class="process-item">
              <span class="process-order">{{ idx + 1 }}.</span>
              <span class="process-name">{{ wp.processName }}</span>
              <span class="process-assignee">
                <UBadge v-if="wp.responsibleWorkerName" color="primary" size="sm" variant="outline">{{ wp.responsibleWorkerName }}</UBadge>
                <span v-else class="process-unassigned">{{ t('production.plans_list.text_350c68309b7b') }}</span>
              </span>
            </li>
          </ol>
          <div v-else class="process-list-empty">
            {{ viewPlan.processName || t('production.plans_list.text_e3cbb9c97f92') }}
          </div>
        </div>

        <PlanOutsourceProcesses
          v-if="factoryId && viewPlan.id"
          :key="`outsource:${factoryId}:${viewPlan.id}`"
          :factory-id="factoryId"
          :plan-id="String(viewPlan.id)"
        />

        <PlanConcurrentReadiness
          v-if="factoryId && viewPlan.id"
          :key="`${factoryId}:${viewPlan.id}`"
          :factory-id="factoryId"
          :plan-id="String(viewPlan.id)"
        />

        <!-- 备注 -->
        <div v-if="viewPlan.notes" class="detail-section">
          <div class="detail-section-title">{{ t('production.plans_list.text_daede9881787') }}</div>
          <div class="detail-notes">{{ viewPlan.notes }}</div>
        </div>
      </div>
    </CompatDialog>

    <!-- 新建计划对话框 -->
    <CompatDialog
      v-model="dialogVisible"
      :title="t('production.plans_list.title_8a8f0f5cf51d')"
      width="500px"
      :close-on-click-modal="false"
      :before-close="handleDialogClose"
    >
      <CompatForm :model="planForm" label-width="100px">
        <CompatFormItem :label="t('production.plans_list.label_38e1e77b3ef3')" required>
          <el-radio-group v-model="planForm.sourceType" @change="handleSourceTypeChange">
            <el-radio label="SAFETY_STOCK">{{ t('production.plans_list.text_e391b8370ca2') }}</el-radio>
            <el-radio label="CUSTOMER_ORDER">{{ t('production.plans_list.text_68bd4b5a1bf2') }}</el-radio>
          </el-radio-group>
          <div
            v-if="planForm.sourceType === 'SAFETY_STOCK'"
            style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;"
          >
            {{ t('production.plans_list.text_f6500e6eaec5') }}
          </div>
        </CompatFormItem>
        <CompatFormItem
          v-if="planForm.sourceType === 'CUSTOMER_ORDER'"
          :label="t('production.plans_list.label_68bd4b5a1bf2')"
          required
          :class="{ 'field-error': sourceOrderIdError }"
        >
          <el-select
            data-testid="production-plan-source-order"
            ref="sourceOrderSelectRef"
            v-model="planForm.sourceOrderId"
            :placeholder="t('production.plans_list.placeholder_f0bf980989d4')"
            filterable
            :loading="salesOrdersLoading"
            style="width: 100%"
            @change="handleSalesOrderSelect"
          >
            <el-option
              v-for="so in selectableSalesOrders"
              :key="String(so.id)"
              :label="canViewPrice ? `${so.orderNo} | ${so.customerName || ''} | ¥${so.totalAmount || 0} | ${so.statusLabel || ''}` : `${so.orderNo} | ${so.customerName || ''} | ${so.statusLabel || ''}`"
              :value="String(so.id)"
            />
          </el-select>
          <div v-if="sourceOrderIdError" class="field-error-hint">{{ t('production.plans_list.text_c802f7260f7a') }}</div>
        </CompatFormItem>
        <!-- SP5 多SO合并: 追加额外销售订单 -->
        <CompatFormItem
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"
          :label="t('production.plans_list.label_a2d55bbc766f')"
        >
          <div style="width: 100%">
            <div v-if="planForm.extraSourceOrderIds.length > 0" style="margin-bottom: 6px; display: flex; flex-wrap: wrap; gap: 4px;">
              <el-tag
                v-for="(eid, idx) in planForm.extraSourceOrderIds"
                :key="eid"
                closable
                size="small"
                type="warning"
                @close="planForm.extraSourceOrderIds.splice(idx, 1)"
              >
                {{ selectableSalesOrders.find((o) => String(o.id) === eid)?.orderNo || eid }}
              </el-tag>
            </div>
            <el-select
              :placeholder="t('production.plans_list.placeholder_82f20b12ce7d')"
              filterable
              :loading="salesOrdersLoading"
              style="width: 100%"
              @change="(id: string) => {
                if (id && id !== planForm.sourceOrderId && !planForm.extraSourceOrderIds.includes(id)) {
                  planForm.extraSourceOrderIds.push(id);
                }
              }"
            >
              <el-option
                v-for="so in selectableSalesOrders.filter(
                  (o) => String(o.id) !== planForm.sourceOrderId && !planForm.extraSourceOrderIds.includes(String(o.id))
                )"
                :key="String(so.id)"
                :label="canViewPrice ? `${so.orderNo} | ${so.customerName || ''} | ¥${so.totalAmount || 0} | ${so.statusLabel || ''}` : `${so.orderNo} | ${so.customerName || ''} | ${so.statusLabel || ''}`"
                :value="String(so.id)"
              />
            </el-select>
            <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;">
              {{ t('production.plans_list.text_b766810f54ef') }}
            </div>
          </div>
        </CompatFormItem>
        <CompatFormItem
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId"
          :label="t('production.plans_list.label_c5cdb44b7449')"
          required
        >
          <!-- 以销定产 (2026-06-24): 多选, 选 SO 默认带出全部产品行, 取消不需要的; 每保留行各建一张计划 (产品/数量取自该行) -->
          <CompatSelect
            v-model="planForm.sourceOrderItemIds"
            multiple
            :placeholder="t('production.plans_list.placeholder_2adffe0262e6')"
            filterable
            style="width: 100%"
          >
            <CompatOption
              v-for="it in selectedOrderItems"
              :key="String(it.id)"
              :label="`${it.productName || ''}${it.specification ? ' | ' + it.specification : ''} | 数量 ${it.quantity || 0} | 待发 ${it.remainingQty || 0}`"
              :value="String(it.id)"
            />
          </CompatSelect>
          <div v-if="planForm.sourceOrderItemIds.length > 1" style="font-size:12px;color:var(--cs-muted);margin-top:4px">
            {{ t('production.plans_list.text_7ecf1cde6486') }} {{ planForm.sourceOrderItemIds.length }} {{ t('production.plans_list.text_d32ca0b1bc6c') }}
          </div>
          <UpstreamMissingHint
            v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderId && selectedOrderItems.length === 0"
            :description="t('production.plans_list.description_3ef7547b2642')"
            target-module="sales"
            :require-write="true"
            action-text="去销售订单添加产品行"
            contact-text="请联系销售或管理员为该订单补充产品行后再排产"
            @action="goAddOrderItems(planForm.sourceOrderId || '')"
          />
        </CompatFormItem>
        <CompatFormItem
          v-if="planForm.sourceType === 'CUSTOMER_ORDER' && planForm.sourceOrderItemIds.length > 0"
          :label="t('production.plans_list.label_b4167e2ad6e0')"
        >
          <div class="sales-order-quantity-readonly">
            <div
              v-for="item in selectedOrderItems.filter((candidate) => planForm.sourceOrderItemIds.includes(String(candidate.id)))"
              :key="String(item.id)"
              class="sales-order-quantity-row"
            >
              <span>{{ item.productName || item.productCode || t('production.plans_list.text_8dfb28716fed') }}</span>
              <strong>{{ item.quantity || 0 }} {{ displayUnit(item.unit || item.quantityUnit || '') }}</strong>
            </div>
            <small>{{ t('production.plans_list.text_fbb031464f4d') }}</small>
          </div>
        </CompatFormItem>
        <!-- 以销定产 (2026-06-24): 来源=销售订单时, 产品/数量按所选产品行各自取, 不再手选单产品 → 隐藏 -->
        <!-- 完全匹配优先；无完全匹配时接受覆盖所选成品的最小联产超集。 -->
        <CompatFormItem v-if="planForm.sourceType !== 'CUSTOMER_ORDER'" :label="t('production.plans_list.label_f6469fb8876c')" required>
          <CompatSelect
            v-model="planForm.targetFinishedGoodIds"
            multiple
            filterable
            :placeholder="t('production.plans_list.placeholder_1ae0db7d32a2')"
            style="width: 100%"
          >
            <CompatOption
              v-for="item in finishedGoodProductTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </CompatSelect>
          <div v-if="resolvingWorkflow" style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;">
            {{ t('production.plans_list.text_2846aba140b8') }}
          </div>
          <template v-else-if="planForm.targetFinishedGoodIds.length > 0">
            <div
              v-if="hasResolvedWorkflowCandidate && resolvedWorkflowCandidate"
              class="selected-workflow-route"
            >
              <div class="selected-workflow-route__head">
                <div>
                  <span class="selected-workflow-route__eyebrow">{{ t('production.plans_list.text_fe8edc3ea6b6') }}</span>
                  <strong>{{ workflowCandidateProcessSummary(resolvedWorkflowCandidate) }}</strong>
                </div>
                <el-popover
                  trigger="hover"
                  placement="right-start"
                  :width="780"
                  :show-after="120"
                  :hide-after="120"
                >
                  <WorkflowRoutePreview
                    :nodes="resolvedWorkflowCandidate.previewNodes"
                    :edges="resolvedWorkflowCandidate.previewEdges"
                  />
                  <template #reference>
                    <UButton color="neutral" size="sm" variant="outline" @click.stop>{{ t('production.plans_list.action_42ea5f662997') }}</UButton>
                  </template>
                </el-popover>
              </div>
              <div class="selected-workflow-route__meta">
                <UBadge variant="soft" color="success" size="sm">{{ workflowCandidateTopologyLabel(resolvedWorkflowCandidate) }}</UBadge>
                <span>{{ t('production.plans_list.text_b4b1256c272c') }}{{ resolvedWorkflowCandidate.definitionVersion }}</span>
              </div>
              <div class="selected-workflow-route__outputs">
                <UBadge variant="soft"
                  v-for="t in (resolvedWorkflowCandidate.terminalOutputs || [])"
                  :key="t.productTypeId"
                  size="sm"
                >→ {{ t.productName }}</UBadge>
              </div>
            </div>
            <el-alert
              v-else-if="planForm.resolvedCandidates.length > 0"
              type="warning"
              show-icon
              :closable="false"
              class="workflow-route-decision-alert"
              :title="planForm.resolvedCandidates.length > 1
                ? t('production.plans_list.title_ab0ba302fdbf')
                : t('production.plans_list.title_6bf8f50e55b7')"
            >
              <UButton
                color="warning"
                variant="outline"
                size="sm"
                @click="openWorkflowCandidateDialog"
              >
                {{ t('production.plans_list.action_c3fcde144398') }}
              </UButton>
            </el-alert>
            <!-- 有联产超集: 图是对的, 缺的是「把联产成品一起选上」。⛔ 不要说「未找到」。 -->
            <el-alert
              v-else-if="planForm.resolutionMode === 'NONE' && jointSupersetHint"
              type="warning"
              show-icon
              :closable="false"
              style="margin-top: 8px;"
              data-testid="joint-superset-hint"
            >
              <template #title>
                {{ t('production.plans_list.text_3700417d7264') }}{{ jointSupersetHint.extraNames.join('、') }}
              </template>
              <div style="margin-top: 4px; color: var(--cs-muted); font-size: 12px;">
                {{ t('production.plans_list.text_efca2e1acd6c') }}
              </div>
              <div style="display: flex; gap: 8px; margin-top: 8px;">
                <UButton
                  size="sm"
                  color="primary"
                  data-testid="adopt-joint-outputs"
                  @click="adoptJointSupersetOutputs"
                >{{ t('production.plans_list.action_15ce2b1c14a9') }} {{ jointSupersetHint.allIds.length }} {{ t('production.plans_list.action_b127fa76cb9d') }}</UButton>
                <UButton color="neutral" variant="outline" size="sm" @click="goToWorkflowConfig(planForm.targetFinishedGoodIds)">
                  {{ t('production.plans_list.action_fcd64d3900a0') }}
                </UButton>
              </div>
            </el-alert>
            <el-alert
              v-else-if="planForm.resolutionMode === 'NONE' && planForm.targetFinishedGoodIds.length === 1"
              type="error"
              show-icon
              :closable="false"
              style="margin-top: 8px;"
            >
              <template #title>{{ t('production.plans_list.text_8360d3c955d9') }}</template>
              <div style="display: flex; gap: 8px; margin-top: 8px;">
                <UButton
                  size="sm"
                  color="primary"
                  @click="goToWorkflowConfig(planForm.targetFinishedGoodIds)"
                >{{ t('production.plans_list.action_fbedb04d2c2b') }}</UButton>
              </div>
            </el-alert>
            <el-alert
              v-else-if="planForm.resolutionMode === 'NONE' && planForm.targetFinishedGoodIds.length > 1"
              type="error"
              show-icon
              :closable="false"
              :title="t('production.plans_list.title_9a65c5b389c0')"
              style="margin-top: 8px;"
            />
          </template>
        </CompatFormItem>
        <CompatFormItem v-if="planForm.sourceType === 'SAFETY_STOCK'" :label="t('production.plans_list.label_8b7fc028e18a')">
          <div style="width: 100%;">
            <el-select
              v-model="planForm.customerId"
              :disabled="customerReadStatus !== 'ready'"
              :loading="customerReadStatus === 'loading'"
              clearable
              filterable
              :placeholder="t('production.plans_list.placeholder_341106e892ac')"
              style="width: 100%"
              @change="handlePlanCustomerChange"
              @clear="handlePlanCustomerChange('')"
            >
              <el-option
                v-for="customer in selectablePlanCustomers"
                :key="String(customer.id)"
                :label="productionPlanCustomerLabel(customer)"
                :value="String(customer.id)"
              />
            </el-select>
            <div v-if="customerReadHint" role="status" data-testid="plan-customer-read-status">
              {{ customerReadHint }}
              <UButton v-if="canRetryCustomers" variant="link" color="primary" @click="loadCustomers">{{ t('production.plans_list.action_b8784c8dd563') }}</UButton>
            </div>
            <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;">
              {{ t('production.plans_list.text_08fb4d9972b9') }}
            </div>
          </div>
        </CompatFormItem>
        <CompatFormItem v-if="planForm.sourceType === 'SAFETY_STOCK' && planForm.customerId" :label="t('production.plans_list.label_0a95447d1a67')">
          <div>
            <el-radio-group v-model="planForm.materialSupplyMode" @change="handlePlanCustomerChange(planForm.customerId)">
              <el-radio value="FACTORY_SUPPLIED">{{ t('production.plans_list.text_d0190e5935ec') }}</el-radio>
              <el-radio value="CUSTOMER_SUPPLIED">{{ t('production.plans_list.text_0114e380002a') }}</el-radio>
            </el-radio-group>
            <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;">
              {{ t('production.plans_list.text_005cbc347a67') }}
            </div>
            <el-alert
              v-if="planForm.materialSupplyMode === 'CUSTOMER_SUPPLIED'"
              type="warning"
              show-icon
              :closable="false"
              :title="t('production.plans_list.title_097396074cc1')"
              style="margin-top: 8px;"
            />
          </div>
        </CompatFormItem>
        <!-- Wave2 六扇门: 免工序报工开关 (Rule 1 防呆: 产品无工序时 disabled 锁定) -->
        <CompatFormItem :label="t('production.plans_list.label_32ac10e7e9ae')">
          <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
            <el-switch
              v-model="planForm.skipProcessReporting"
              :disabled="reportModeLocked"
              active-text="免工序报工"
              inactive-text="逐道报工"
              :active-value="true"
              :inactive-value="false"
              style="--el-switch-on-color: var(--el-color-info, var(--cs-muted));"
            />
            <span style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted));">
              <template v-if="planForm.skipProcessReporting">
                {{ t('production.plans_list.text_916cbb503dae') }}
              </template>
              <template v-else>
                {{ t('production.plans_list.text_75c9710fd88e') }}
              </template>
              <template v-if="hasActiveWorkflow">
                {{ t('production.plans_list.text_5896af8ec58e') }}
              </template>
              <template v-else-if="!hasProcesses && planForm.productTypeId">
                {{ t('production.plans_list.text_76e9aa833606') }}
              </template>
            </span>
          </div>
        </CompatFormItem>
        <!-- A3: 工序只读展示；免工序报工时隐藏，产品无工序时改提示 -->
        <CompatFormItem v-if="!planForm.skipProcessReporting" :label="t('production.plans_list.label_648cd106a0b7')">
          <template v-if="!planForm.productTypeId">
            <span style="color: var(--text-color-secondary, var(--cs-muted)); font-size: 13px;">{{ t('production.plans_list.text_51fff14f1ca7') }}</span>
          </template>
          <template v-else-if="hasActiveWorkflow">
            <span style="color: var(--el-color-success, var(--cs-success)); font-size: 13px;">
              {{ t('production.plans_list.text_a80b96e8ef0c') }}
            </span>
          </template>
          <template v-else-if="productWorkProcessList.length === 0">
            <span style="color: var(--el-color-warning, var(--cs-warning)); font-size: 13px;">
              {{ t('production.plans_list.text_4798ea33690c') }}
              <el-link type="primary" underline="hover" @click="router.push('/system/product-processes')" style="font-size: 13px; vertical-align: baseline;">{{ t('production.plans_list.text_c1fc5e1fefb6') }}</el-link>
              {{ t('production.plans_list.text_ebc54f702bda') }}
            </span>
          </template>
          <template v-else>
            <div style="width: 100%;">
              <UBadge variant="soft"
                v-for="(wp, idx) in productWorkProcessList"
                :key="wp.id"
                size="sm"
                style="margin-right: 6px; margin-bottom: 4px;"
              >{{ idx + 1 }}. {{ wp.processName }}<template v-if="wp.responsibleWorkerName"> ({{ wp.responsibleWorkerName }})</template></UBadge>
            </div>
          </template>
          <!-- processName is kept in sync via loadBomProcesses (T135 ITEM #4) -->
        </CompatFormItem>
        <!-- Wave2: 免工序报工模式下的工序提示（产品已配工序但选了免工序） -->
        <CompatFormItem v-else-if="hasProcesses && planForm.productTypeId" :label="t('production.plans_list.label_648cd106a0b7')">
          <span style="color: var(--text-color-secondary, var(--cs-muted)); font-size: 13px;">
            {{ t('production.plans_list.text_afe8ff7bd451') }} {{ productWorkProcessList.length }} {{ t('production.plans_list.text_a7373802071a') }}
          </span>
        </CompatFormItem>
        <!-- A5/E3: batchDate 已被后端消费 (ProductionProgressDashboard 按批次日期过滤)，保留；加说明区分两个日期 -->
        <CompatFormItem :label="t('production.plans_list.label_4ab95394a48e')">
          <div data-testid="production-plan-batch-date" style="width:100%">
          <CompatDatePicker
            v-model="planForm.batchDate"
            type="date"
            :placeholder="t('production.plans_list.placeholder_2cc5aee95aba')"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
          </div>
          <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 2px;">
            {{ t('production.plans_list.text_7b5f02abf33d') }}
          </div>
        </CompatFormItem>
        <!-- 以销定产: 来源=销售订单时数量按各产品行取, 不手填 → 隐藏 -->
        <!-- 存货生产(SAFETY_STOCK): 无计划数量, 按实际小结累计 → 同样隐藏 -->
        <CompatFormItem
          v-if="planForm.sourceType !== 'CUSTOMER_ORDER' && planForm.sourceType !== 'SAFETY_STOCK'"
          :label="hasResolvedWorkflowCandidate
            ? `${planForm.resolutionMode === 'SHARED_MULTI_OUTPUT' ? 'Workflow 计划基准数量' : '计划成品数量'}（${resolvedWorkflowCandidate?.plannedUnit || '单位未配置'}）`
            : t('production.plans_list.label_b4167e2ad6e0')"
          required
        >
          <el-input-number v-model="planForm.plannedQuantity" :min="1" style="width: 100%" />
          <div v-if="planForm.aiRequestedUnit" class="ai-unit-preserved">
            {{ t('production.plans_list.text_8e4a739bc280') }}{{ planForm.aiRequestedUnit }}{{ t('production.plans_list.text_57dbcadf863b') }}{{ resolvedWorkflowCandidate?.plannedUnit || t('production.plans_list.text_fb0f23ddddb5') }}
          </div>
          <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 2px;">
            <template v-if="hasResolvedWorkflowCandidate && planForm.resolutionMode === 'SHARED_MULTI_OUTPUT'">
              {{ t('production.plans_list.text_c966ddc44114') }}
            </template>
            <template v-else-if="hasResolvedWorkflowCandidate">
              {{ t('production.plans_list.text_e35bfdab0d10') }}
            </template>
            <template v-else>
              {{ t('production.plans_list.text_78fe0b7fcec4') }}
            </template>
          </div>
        </CompatFormItem>
        <!-- 存货生产的产量由逐道报工/生产小结形成；此处只接受可选的排产参考数量。 -->
        <CompatFormItem
          v-if="planForm.sourceType === 'SAFETY_STOCK' && (hasActiveWorkflow || hasResolvedWorkflowCandidate)"
          :label="`${planForm.resolutionMode === 'SHARED_MULTI_OUTPUT' ? 'Workflow 计划基准数量' : '计划成品数量'}（选填，${resolvedWorkflowCandidate?.plannedUnit || '单位未配置'}）`"
        >
          <el-input-number
            v-model="planForm.plannedQuantity"
            :min="1"
            clearable
            :placeholder="t('production.plans_list.placeholder_2918dd6ed1b4')"
            style="width: 100%"
          />
          <div style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 2px;">
            <template v-if="planForm.resolutionMode === 'SHARED_MULTI_OUTPUT'">
              {{ t('production.plans_list.text_e96aa2a7e3a8') }}
            </template>
            <template v-else>
              {{ t('production.plans_list.text_d4321bf969de') }}
            </template>
          </div>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_972e6a485642')" required>
          <div data-testid="production-plan-planned-date" style="width:100%">
          <CompatDatePicker
            v-model="planForm.plannedDate"
            type="date"
            :placeholder="t('production.plans_list.placeholder_7c7949a96f66')"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
          </div>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_daede9881787')">
          <el-input data-testid="production-plan-notes" v-model="planForm.notes" type="textarea" :rows="3" />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_ad4181bf4e26')">
          <el-input-number v-model="planForm.estimatedWorkers" :min="1" :max="100" :placeholder="t('production.plans_list.placeholder_537352400c49')" style="width: 100%" />
        </CompatFormItem>
        <!-- E2: 指派主管为可选字段，后端 DTO 无 @NotNull，前端亦无 required 规则 -->
        <CompatFormItem :label="t('production.plans_list.label_d09aca4c2433')">
          <CompatSelect v-model="planForm.assignedSupervisorId" clearable :placeholder="t('production.plans_list.placeholder_9a3640adf716')" style="width: 100%">
            <CompatOption v-for="sup in supervisors" :key="sup.id" :label="sup.fullName || sup.username" :value="sup.id" />
          </CompatSelect>
        </CompatFormItem>
        <CanvasDynamicFields v-model="planForm.customFields" module-code="production_plan" />
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="handleDialogClose">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton data-testid="production-plan-submit" color="primary" :loading="dialogLoading" :disabled="nonSoSubmitBlocked" @click="submitPlan">{{ t('production.plans_list.action_fac2a67ad878') }}</UButton>
      </template>
    </CompatDialog>

    <el-dialog
      v-model="workflowCandidateDialogVisible"
      :title="t('production.plans_list.title_cd4dc131e250')"
      width="880px"
      top="7vh"
      append-to-body
      destroy-on-close
      :close-on-click-modal="false"
    >
      <el-alert
        type="info"
        show-icon
        :closable="false"
        class="workflow-candidate-dialog__hint"
        :title="t('production.plans_list.title_4e88b2058d67')"
      />
      <el-radio-group v-model="pendingCandidateWorkflowId" class="workflow-candidate-list">
        <div
          v-for="candidate in planForm.resolvedCandidates"
          :key="candidate.workflowId"
          :class="[
            'workflow-candidate-card',
            { 'is-selected': pendingCandidateWorkflowId === candidate.workflowId },
          ]"
          @click="pendingCandidateWorkflowId = candidate.workflowId"
        >
          <div class="workflow-candidate-card__top">
            <el-radio :label="candidate.workflowId">
              <span class="workflow-candidate-card__process">
                {{ workflowCandidateProcessSummary(candidate) }}
              </span>
            </el-radio>
            <el-popover
              trigger="hover"
              placement="right-start"
              :width="780"
              :show-after="120"
              :hide-after="120"
            >
              <WorkflowRoutePreview
                :nodes="candidate.previewNodes"
                :edges="candidate.previewEdges"
              />
              <template #reference>
                <UButton
                  class="workflow-preview-trigger"
                  color="primary"
                  variant="outline"
                  size="sm"
                  @click.stop
                >
                  {{ t('production.plans_list.action_7984083228f1') }}
                </UButton>
              </template>
            </el-popover>
          </div>

          <div class="workflow-candidate-card__meta">
            <UBadge variant="soft" size="sm" color="info">{{ workflowCandidateTopologyLabel(candidate) }}</UBadge>
            <span>{{ t('production.plans_list.text_b4b1256c272c') }}{{ candidate.definitionVersion }}</span>
            <span v-if="candidate.ownerProductName">{{ t('production.plans_list.text_1e9b3dc5ae01') }} {{ candidate.ownerProductName }}</span>
          </div>

          <div class="workflow-candidate-card__outputs">
            <span class="workflow-candidate-card__label">{{ t('production.plans_list.text_5361a6a39295') }}</span>
            <el-tag
              v-for="output in (candidate.terminalOutputs || [])"
              :key="output.productTypeId"
              size="small"
              :type="workflowCandidateExtraOutputs(candidate, planForm.targetFinishedGoodIds).includes(output.productTypeId)
                ? 'warning' : 'success'"
            >
              {{ output.productName || resolveOutputProductLabel(output.productTypeId) }}
            </el-tag>
          </div>

          <CompatAlert
            v-if="candidateExtraOutputNames(candidate).length > 0"
            color="warning"
            :close="false"
            :title="`确认后会把额外联产成品加入本计划：${candidateExtraOutputNames(candidate).join('、')}`"
          />
        </div>
      </el-radio-group>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="workflowCandidateDialogVisible = false">{{ t('production.plans_list.action_410641b40d8b') }}</UButton>
        <UButton
          color="primary"
          :loading="confirmingWorkflowCandidate"
          :disabled="pendingCandidateWorkflowId == null"
          @click="confirmWorkflowCandidateSelection"
        >
          {{ t('production.plans_list.action_28ad4974dabd') }}
        </UButton>
      </template>
    </el-dialog>

    <!-- #742 / 6.12 核对结单 dialog -->
    <el-dialog
      v-model="completeDialogVisible"
      :title="completeProductName ? `核对结单 — ${completeProductName}` : t('production.plans_list.title_639203de03d3')"
      width="760px"
      top="4vh"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
      :before-close="handleCompleteDialogBeforeClose"
    >
      <CompatForm data-testid="production-settle-dialog" label-width="118px" class="settlement-form">
        <div v-if="completeAdvisory?.hasWarning" class="settlement-advisory-alert">
          <el-alert
            :title="completeAdvisory.message"
            type="warning"
            show-icon
            :closable="false"
          />
          <UButton
            v-if="advisoryNeedsUnitConfig(completeAdvisory)"
            color="warning"
            variant="outline"
            size="sm"
            @click="goMaterialUnitConfig(completeAdvisory)"
          >
            {{ t('production.plans_list.action_75890d8c752f') }}
          </UButton>
        </div>
        <!-- Phase 2A: 报工自动预填审计 -->
        <el-alert
          v-if="settlementPrefillLoading"
          :title="t('production.plans_list.title_3811e3feb83f')"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="settlementPrefillClean"
          :title="t('production.plans_list.title_3a34cccd4de4')"
          type="success"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert data-testid="settlement-blocker"
          v-else-if="settlementBlockerIssues.length > 0"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <div style="font-weight: 600; margin-bottom: 4px;">
              {{ t('production.plans_list.text_c0c0e008b8b4') }} {{ settlementBlockerIssues.length }} {{ t('production.plans_list.text_c7098fadd405') }}
            </div>
            <ul style="margin: 0; padding-left: 18px;">
              <li v-for="(it, idx) in settlementBlockerIssues" :key="idx" style="line-height: 1.6;">
                {{ it.message }}
              </li>
            </ul>
          </template>
        </el-alert>
        <!-- INFO 级提示 (不阻塞一键确认; 如跨计划半成品确认) -->
        <el-alert
          v-if="settlementPrefillApplied && settlementInfoIssues.length > 0"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <ul style="margin: 0; padding-left: 18px;">
              <li v-for="(it, idx) in settlementInfoIssues" :key="idx" style="line-height: 1.6;">
                {{ it.message }}
              </li>
            </ul>
          </template>
        </el-alert>
        <div class="settlement-context">
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_167b0c9adcd9') }}</div>
            <div class="settlement-context-value">{{ completePlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_7d432cf3863b') }}</div>
            <div class="settlement-context-value">{{ completeProductName || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_19a908ffd1ae') }}</div>
            <div class="settlement-context-value">{{ formatPlannedQuantity(completePlannedQuantity, completePlannedUnit) }}</div>
          </div>
        </div>
        <template v-if="settlementPrefillClean">
          <el-divider content-position="left">{{ t('production.plans_list.text_f47f334ad89d') }}</el-divider>
          <div class="settlement-reconciliation-grid">
            <div class="settlement-reconciliation-card">
              <span>{{ t('production.plans_list.text_624cb7192399') }}</span>
              <strong>{{ completeActualQuantity }} {{ displayUnit(completePlannedUnit) }}</strong>
            </div>
            <div class="settlement-reconciliation-card">
              <span>{{ t('production.plans_list.text_89c47ef5e018') }}</span>
              <strong>{{ completeForm.rawMaterialConsumptions.length }} {{ t('production.plans_list.text_3a116815bc9d') }}</strong>
            </div>
            <div class="settlement-reconciliation-card">
              <span>{{ t('production.plans_list.text_23a85f58ad63') }}</span>
              <strong>{{ completeForm.workMinutes }} {{ t('production.plans_list.text_bd957bc497aa') }}</strong>
            </div>
          </div>
          <div v-if="settlementTerminalOutputs.length > 0" class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">{{ t('production.plans_list.text_3c776f13dc90') }}</div>
            <div
              v-for="(output, index) in settlementTerminalOutputs"
              :key="`terminal-${output.productTypeId}-${output.batchNumber}-${output.unit}-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ resolveOutputProductLabel(output.productTypeId) }} · {{ output.batchNumber || t('production.plans_list.text_4cfbcdf65bdb') }}</span>
              <strong>{{ output.quantity }} {{ displayUnit(output.unit) }}</strong>
            </div>
          </div>
          <div class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">{{ t('production.plans_list.text_46a749285c09') }}</div>
            <div
              v-for="(line, index) in completeForm.rawMaterialConsumptions"
              :key="`recorded-raw-${line.materialBatchId}-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ rawConsumptionDisplayLabel(line) }}</span>
              <strong>{{ line.quantity }} {{ displayUnit(line.unit) }}</strong>
            </div>
          </div>
          <div class="settlement-reconciliation-section">
            <div class="settlement-reconciliation-title">{{ t('production.plans_list.text_569f271f7d1f') }}</div>
            <div
              v-for="(segment, index) in completeForm.laborSegments"
              :key="`recorded-labor-${index}`"
              class="settlement-reconciliation-row"
            >
              <span>{{ segment.workType || `第 ${index + 1} 道工序` }}</span>
              <strong>{{ segment.headcount }} {{ t('production.plans_list.text_dafefee2c6e7') }} {{ segment.minutes }} {{ t('production.plans_list.text_bd957bc497aa') }}</strong>
            </div>
          </div>
          <el-alert
            :title="t('production.plans_list.title_2357d2a3699e')"
            type="info"
            show-icon
            :closable="false"
            style="margin: 12px 0"
          />
          <div class="settlement-line-header">
            <span>{{ t('production.plans_list.text_30df0b90b8a9') }}</span>
            <UButton
              v-if="!settlementManualWipVisible"
              size="sm"
              color="primary"
              variant="outline"
              :loading="wipListLoading"
              @click="openSettlementManualWip"
            >{{ t('production.plans_list.action_18d46856d4be') }}</UButton>
          </div>
          <div v-if="!settlementManualWipVisible" class="settlement-help">
            {{ t('production.plans_list.text_dcfa15fb5d3b') }}
          </div>
          <template v-else>
            <div class="settlement-help">
              {{ t('production.plans_list.text_be0588e7908e') }}
            </div>
            <div v-if="wipList.length === 0 && !wipListLoading" class="settlement-empty">
              {{ t('production.plans_list.text_5e082ae321b8') }}
            </div>
            <div
              v-for="(line, index) in completeForm.semiFinishedConsumptions"
              :key="`clean-wip-${index}`"
              class="settlement-consumption-row"
            >
              <el-select
                v-model="line.semiFinishedInventoryId"
                filterable
                :placeholder="t('production.plans_list.placeholder_c63461102ffe')"
                :loading="wipListLoading"
                class="consumption-select"
              >
                <el-option
                  v-for="wip in wipList"
                  :key="wip.id"
                  :label="`${wip.intermediateBatchNo} | 可用 ${wip.availableQuantity}${displayUnit(wip.unit)}`"
                  :value="wip.id"
                />
              </el-select>
              <el-input-number
                v-model="line.quantity"
                :min="0.01"
                :max="selectedWipForSettlement(line.semiFinishedInventoryId) ? Number(selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity || 0) : undefined"
                :precision="2"
                :step="0.01"
                class="consumption-qty"
              />
              <UInput v-model="line.note" :placeholder="t('production.plans_list.placeholder_70044377f282')" class="consumption-note" />
              <UButton variant="ghost" color="error" @click="removeWipConsumptionLine(index)">{{ t('production.plans_list.action_2f9daa828907') }}</UButton>
              <div v-if="selectedWipForSettlement(line.semiFinishedInventoryId)" class="settlement-wip-boundary">
                {{ t('production.plans_list.text_7badcaa8c639') }} <strong>{{ selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity }} {{ displayUnit(selectedWipForSettlement(line.semiFinishedInventoryId)?.unit) }}</strong>{{ t('production.plans_list.text_af6bfb364dcd') }}
              </div>
              <div v-if="wipLineDisabledReason(line, index)" class="settlement-wip-boundary danger">
                {{ wipLineDisabledReason(line, index) }}
              </div>
            </div>
            <el-button size="small" :icon="Plus" @click="addWipConsumptionLine">{{ t('production.plans_list.action_7d122a5dd5da') }}</el-button>
          </template>
        </template>
        <template v-else>
        <el-divider content-position="left">{{ t('production.plans_list.text_bac4a080fdc9') }}</el-divider>
        <CompatFormItem :label="t('production.plans_list.label_a98c596e050c')" required>
          <el-input-number
            v-model="completeForm.actualQuantity"
            :min="0"
            :precision="2"
            style="width: 100%"
          />
          <!-- 防呆 Rule 1: 有计划数量时软性参考提示; 存货生产等无计划数量 (=0/null) 跳过, 不误导 -->
          <div v-if="completePlannedQuantity > 0" class="settlement-help">
            {{ t('production.plans_list.text_68d4606240cd') }} {{ formatPlannedQuantity(completePlannedQuantity, completePlannedUnit) }}{{ t('production.plans_list.text_36cb3fef184c') }}
          </div>
        </CompatFormItem>
        <el-alert
          v-if="completeIsOverPlan"
          :title="t('production.plans_list.title_faf16c671cfb')"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <CompatFormItem v-if="completeIsOverPlan" :label="t('production.plans_list.label_0c23811a1e04')" required>
          <CompatSelect
            v-model="completeForm.varianceReason"
            :placeholder="t('production.plans_list.placeholder_2303629e133d')"
            style="width: 100%"
          >
            <CompatOption
              v-for="opt in SETTLEMENT_VARIANCE_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem v-if="completeIsOverPlan && completeForm.varianceReason === '其他'" :label="t('production.plans_list.label_59889a398f35')" required>
          <el-input
            v-model="completeForm.otherVarianceReason"
            type="textarea"
            :rows="2"
            :placeholder="t('production.plans_list.placeholder_9d036b414b41')"
          />
        </CompatFormItem>
        <!-- 不良品清点 —— 三态, 不是一个可以留空也可以填 0 的数字。
             2026-08-26 实测: 72 张报工单零填写, 旧代码兜底填 0,
             于是 8 个已完工批次全部声称 100% 良品, 而其中 4 个的质检明确记了不合格。
             「已清点确认零」和「没清点」必须分开, 否则那个 0 又会被当成真实读数。 -->
        <CompatFormItem :label="t('production.plans_list.label_cdfad7941f8e')">
          <el-radio-group v-model="completeForm.defectCountOutcome">
            <el-radio label="COUNTED">{{ t('production.plans_list.text_7e4912250bd2') }}</el-radio>
            <el-radio label="NONE_CONFIRMED">{{ t('production.plans_list.text_21179fc049de') }}</el-radio>
            <el-radio label="NOT_COUNTED">{{ t('production.plans_list.text_ee2db7f3bcce') }}</el-radio>
          </el-radio-group>
          <el-input-number
            v-if="completeForm.defectCountOutcome === 'COUNTED'"
            v-model="completeForm.defectQuantity"
            :min="0"
            :precision="2"
            style="width: 100%; margin-top: 8px"
            :placeholder="t('production.plans_list.placeholder_f216e34e8c99')"
          />
          <div class="settlement-help">
            {{ t('production.plans_list.text_b96b5ea01a75') }}
          </div>
        </CompatFormItem>
        <CompatFormItem v-if="settlementNeedsManualReason" :label="t('production.plans_list.label_d7d0b2d05596')" required>
          <CompatSelect v-model="completeForm.manualOverrideReason" :placeholder="t('production.plans_list.placeholder_da590a8fe3ce')" style="width: 100%">
            <CompatOption v-for="reason in MANUAL_OVERRIDE_REASON_OPTIONS" :key="reason" :label="reason" :value="reason" />
          </CompatSelect>
          <el-input
            v-if="completeForm.manualOverrideReason === '其他'"
            v-model="completeForm.otherManualOverrideReason"
            type="textarea"
            :rows="2"
            maxlength="255"
            :placeholder="t('production.plans_list.placeholder_1394a36d93a0')"
            style="margin-top: 8px"
          />
          <div class="settlement-help">{{ t('production.plans_list.text_96c425eb5472') }}</div>
        </CompatFormItem>
        <el-alert
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        >
          <template #title>
            <div class="settlement-loss-guide">
              <strong>{{ t('production.plans_list.text_4466799410d9') }}</strong>
              {{ t('production.plans_list.text_794b250703be') }}
              <el-link
                type="primary"
                underline="hover"
                @click="router.push({ path: '/warehouse/wastage-reports', query: { reason: 'PRODUCTION_WASTE', source: 'production-settlement' } })"
              >{{ t('production.plans_list.text_28b219276a74') }}</el-link>
              {{ t('production.plans_list.text_e44d6985a1ad') }}
            </div>
          </template>
        </el-alert>

        <el-divider content-position="left">{{ t('production.plans_list.text_b64d65fb5f2c') }}</el-divider>
        <div v-if="completeAdvisory?.hasWarning" class="settlement-advisory-alert">
          <el-alert
            :title="completeAdvisory.message"
            type="error"
            show-icon
            :closable="false"
          />
          <UButton
            v-if="advisoryNeedsUnitConfig(completeAdvisory)"
            color="error"
            variant="outline"
            size="sm"
            @click="goMaterialUnitConfig(completeAdvisory)"
          >
            {{ t('production.plans_list.action_75890d8c752f') }}
          </UButton>
        </div>
        <el-alert
          v-else-if="completeAdvisory"
          :title="completeAdvisory.message"
          type="success"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <div class="settlement-line-header">
          <span>
            {{ t('production.plans_list.text_162fe23e26bc') }}
            <UBadge
              v-if="settlementFieldHasIssue('rawMaterialConsumptions')"
              color="warning"
              size="sm"
              variant="soft"
              style="margin-left: 6px"
            >{{ t('production.plans_list.text_ea1b2f687b8c') }}</UBadge>
          </span>
          <el-button size="small" :icon="Plus" @click="addRawConsumptionLine">{{ t('production.plans_list.action_8729b2a7eae9') }}</el-button>
        </div>
        <div
          v-if="bomFilterBlockedMessage && !materialBatchListLoading"
          class="settlement-empty"
        >
          {{ bomFilterBlockedMessage }}
          <UButton size="sm" color="primary" variant="link" @click="goConfigBomFromSettlement">{{ t('production.plans_list.action_bd87397c8609') }}</UButton>
        </div>
        <div
          v-else-if="bomFilteredMaterialBatchOptions.length === 0 && !materialBatchListLoading"
          class="settlement-empty"
        >
          {{ t('production.plans_list.text_0670810c2dfe') }}
        </div>
        <div
          v-for="(line, index) in completeForm.rawMaterialConsumptions"
          :key="`raw-${index}`"
          class="settlement-consumption-row"
        >
          <el-select
            v-model="line.materialBatchId"
            filterable
            :placeholder="t('production.plans_list.placeholder_09c5406373dc')"
            :loading="materialBatchListLoading"
            class="consumption-select"
            @change="applyPickedRawMaterialBatch(line)"
          >
            <el-option
              v-for="batch in bomFilteredMaterialBatchOptions"
              :key="batch.id"
              :label="materialBatchLabel(batch)"
              :value="batch.id"
            />
          </el-select>
          <el-input-number
            v-model="line.quantity"
            :min="0"
            :max="materialBatchAvailable(selectedMaterialBatch(line.materialBatchId)) || undefined"
            :precision="2"
            class="consumption-qty"
          />
          <UInput
            v-model="line.note"
            :placeholder="t('production.plans_list.placeholder_97af4ef88e1e')"
            class="consumption-note"
          />
          <UButton variant="ghost" color="error" @click="removeRawConsumptionLine(index)">{{ t('production.plans_list.action_2f9daa828907') }}</UButton>
          <div
            v-if="materialWarehouseBreakdown(selectedMaterialBatch(line.materialBatchId))"
            class="settlement-wip-boundary"
          >
            {{ t('production.plans_list.text_e79d9cfb1a15') }}{{ materialWarehouseBreakdown(selectedMaterialBatch(line.materialBatchId)) }}
          </div>
        </div>
        <div class="settlement-line-header">
          <span>{{ t('production.plans_list.text_c306310c10e0') }}</span>
          <el-button size="small" :icon="Plus" @click="addWipConsumptionLine">{{ t('production.plans_list.action_7d122a5dd5da') }}</el-button>
        </div>
        <div class="settlement-help">
          {{ t('production.plans_list.text_04dd4031aa75') }}
        </div>
        <div v-if="wipList.length === 0 && !wipListLoading" class="settlement-empty">
          {{ t('production.plans_list.text_964e828cd2e6') }}
        </div>
        <div
          v-for="(line, index) in completeForm.semiFinishedConsumptions"
          :key="`wip-${index}`"
          class="settlement-consumption-row"
        >
          <el-select
            v-model="line.semiFinishedInventoryId"
            filterable
            :placeholder="t('production.plans_list.placeholder_2fcfee497cfa')"
            :loading="wipListLoading"
            class="consumption-select"
          >
            <el-option
              v-for="wip in wipList"
              :key="wip.id"
              :label="`${wip.intermediateBatchNo} | 可用 ${wip.availableQuantity}${displayUnit(wip.unit)}`"
              :value="wip.id"
            />
          </el-select>
          <el-input-number
            v-model="line.quantity"
            :min="0"
            :max="selectedWipForSettlement(line.semiFinishedInventoryId) ? Number(selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity || 0) : undefined"
            :precision="2"
            class="consumption-qty"
          />
          <UInput
            v-model="line.note"
            :placeholder="t('production.plans_list.placeholder_70044377f282')"
            class="consumption-note"
          />
          <UButton variant="ghost" color="error" @click="removeWipConsumptionLine(index)">{{ t('production.plans_list.action_2f9daa828907') }}</UButton>
          <div
            v-if="selectedWipForSettlement(line.semiFinishedInventoryId)"
            class="settlement-wip-boundary"
          >
            {{ t('production.plans_list.text_7badcaa8c639') }}
            <strong>
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity }}
              {{ displayUnit(selectedWipForSettlement(line.semiFinishedInventoryId)?.unit) }}
            </strong>
            {{ t('production.plans_list.text_0f71b786e217') }}
            <strong>
              {{ selectedWipForSettlement(line.semiFinishedInventoryId)?.availableQuantity }}
              {{ displayUnit(selectedWipForSettlement(line.semiFinishedInventoryId)?.unit) }}
            </strong>
            {{ t('production.plans_list.text_eb0bb1760cd3') }}
          </div>
        </div>

        <el-divider content-position="left">
          {{ t('production.plans_list.text_3a98c074b428') }}
          <UBadge
            v-if="settlementFieldHasIssue('laborSegments')"
            color="warning"
            size="sm"
            variant="soft"
            style="margin-left: 6px"
          >{{ t('production.plans_list.text_ea1b2f687b8c') }}</UBadge>
        </el-divider>
        <el-row :gutter="12">
          <el-col :span="12">
            <CompatFormItem :label="t('production.plans_list.label_389485a87c70')">
              <el-input-number
                v-model="completeForm.workerCount"
                :min="0"
                :precision="0"
                style="width: 100%"
              />
            </CompatFormItem>
          </el-col>
          <el-col :span="12">
            <CompatFormItem :label="t('production.plans_list.label_4391a355f1d7')">
              <el-input-number
                v-model="completeForm.workMinutes"
                :min="0"
                :precision="0"
                style="width: 100%"
              />
            </CompatFormItem>
          </el-col>
        </el-row>
        <div class="settlement-help" style="margin-bottom: 12px;">
          {{ t('production.plans_list.text_0b88c08d38b3') }}
        </div>
        </template>
        <CompatFormItem :label="t('production.plans_list.label_3e073c238b91')" data-testid="settlement-semi-output">
          <el-input-number
            v-model="completeForm.semiFinishedOutputQuantity"
            :min="0"
            :precision="2"
            style="width: 100%"
          />
          <div class="settlement-help">
            {{ t('production.plans_list.text_b1d1cbb2f32e') }}
          </div>
        </CompatFormItem>
        <el-alert
          v-if="!settlementPrefillClean && completeSubmitDisabledReason"
          :title="completeSubmitDisabledReason"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 4px"
        />
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="requestCloseCompleteDialog">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="primary"
          :loading="actionLoading"
          :disabled="!completeCanSubmit"
          data-testid="production-settle-dialog-submit"
          @click="submitComplete"
        >{{ t('production.plans_list.action_544a279f2f80') }}</UButton>
      </template>
    </el-dialog>

    <!-- 6.12: 生产报产与仓库实收必须双方核对；差异进入中转挂账 -->
    <el-dialog
      v-model="receiptDialogVisible"
      :title="receiptProductName ? `仓库确认入库 — ${receiptProductName}` : t('production.plans_list.title_c3e464c67c4e')"
      width="720px"
      top="6vh"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
    >
      <CompatForm data-testid="finished-goods-receive-dialog" v-loading="receiptLoading" label-width="124px" class="settlement-form">
        <div v-if="!receiptHasOutputLines" class="settlement-context">
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_167b0c9adcd9') }}</div>
            <div class="settlement-context-value">{{ receiptPlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_6d781005de23') }}</div>
            <div class="settlement-context-value">
              {{ receiptReportedQuantity }} {{ receiptDisplayUnit }}
            </div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_4249c8e935d2') }}</div>
            <div class="settlement-context-value">
              {{ receiptTolerance }} {{ receiptDisplayUnit }}
            </div>
          </div>
        </div>

        <el-alert
          v-if="receiptHasOutputLines"
          :title="t('production.plans_list.title_44db70874ad3')"
          type="info"
          show-icon
          :closable="false"
          style="margin: 12px 0"
        />
        <div v-if="receiptHasOutputLines" class="receipt-output-lines">
          <div
            v-for="(line, index) in receiptForm.outputLines"
            :key="`${line.productTypeId}:${line.reportedBatchNumber}`"
            class="receipt-output-line"
          >
            <div class="receipt-output-line__identity">
              <span>{{ t('production.plans_list.text_4c97f9db086d') }} {{ index + 1 }}</span>
              <strong>{{ resolveOutputProductLabel(line.productTypeId) }}</strong>
              <small>{{ t('production.plans_list.text_620226469b1a') }} {{ line.reportedBatchNumber }}</small>
            </div>
            <div class="receipt-output-line__reported">
              <span>{{ t('production.plans_list.text_6d781005de23') }}</span>
              <strong>{{ line.reportedQuantity }} {{ displayUnit(line.quantityUnit) }}</strong>
              <small>{{ t('production.plans_list.text_2bfa8a13150e') }} {{ line.originalReceivedQuantity || 0 }}{{ t('production.plans_list.text_58263aa6feb0') }} {{ Number(line.reportedQuantity) - Number(line.originalReceivedQuantity || 0) }}</small>
            </div>
            <CompatFormItem :label="t('production.plans_list.label_28f0c5580f0b')" required class="receipt-output-line__input">
              <el-input-number
                data-testid="native-receipt-quantity" v-model="line.receivedQuantity"
                :min="0"
                :precision="4"
                :step="0.0001"
                style="width: 100%"
              />
              <span class="receipt-output-line__unit">{{ displayUnit(line.quantityUnit) }}</span>
              <small
                v-if="Number(line.receivedQuantity) > Number(line.reportedQuantity) - Number(line.originalReceivedQuantity || 0)"
                class="danger"
              >{{ t('production.plans_list.text_e9f782a13c69') }}</small>
            </CompatFormItem>
          </div>
        </div>

        <el-alert
          v-else
          :title="receiptUsesInterimFinishedGoods
            ? t('production.plans_list.title_e7199944edb3')
            : t('production.plans_list.title_c8ab0fd87aab')"
          type="warning"
          show-icon
          :closable="false"
          style="margin: 12px 0"
        />

        <template v-if="!receiptHasOutputLines">
        <CompatFormItem :label="t('production.plans_list.label_28f0c5580f0b')" required>
          <el-input-number
            data-testid="native-receipt-quantity" v-model="receiptForm.receivedQuantity"
            :min="0"
            :max="receiptMaxQuantity"
            :precision="2"
            style="width: 100%"
          />
          <div class="settlement-help" data-testid="receipt-over-receive-help">
            {{ receiptOverReceiveHelp }}
          </div>
        </CompatFormItem>

        <div class="receipt-diff-panel">
          <div>
            <span>{{ t('production.plans_list.text_6d781005de23') }}</span>
            <strong>{{ receiptReportedQuantity }} {{ receiptDisplayUnit }}</strong>
          </div>
          <div>
            <span>{{ t('production.plans_list.text_28f0c5580f0b') }}</span>
            <strong>{{ receiptReceivedQuantity }} {{ receiptDisplayUnit }}</strong>
          </div>
          <div :class="{ danger: receiptVarianceQuantity > receiptTolerance }">
            <span>{{ t('production.plans_list.text_e7958de61fa2') }}</span>
            <strong>{{ receiptVarianceQuantity }} {{ receiptDisplayUnit }}</strong>
          </div>
        </div>

        <el-alert
          v-if="receiptExceedsAllowed"
          :title="`${receiptSubmitDisabledReason}。系统已禁止提交。`"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="receiptNeedsReason"
          :title="t('production.plans_list.title_30e11c0b8a21')"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />
        <el-alert
          v-else-if="receiptVarianceQuantity > 0"
          :title="t('production.plans_list.title_2c8912269726')"
          type="info"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />

        <CompatFormItem v-if="receiptNeedsReason" :label="t('production.plans_list.label_0c23811a1e04')" required>
          <CompatSelect v-model="receiptForm.varianceReason" :placeholder="t('production.plans_list.placeholder_2303629e133d')" style="width: 100%">
            <CompatOption
              v-for="opt in RECEIPT_VARIANCE_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem v-if="receiptNeedsReason && receiptForm.varianceReason === '其他'" :label="t('production.plans_list.label_59889a398f35')" required>
          <el-input
            v-model="receiptForm.otherVarianceReason"
            type="textarea"
            :rows="2"
            :placeholder="t('production.plans_list.placeholder_6bcf26fe9138')"
          />
        </CompatFormItem>
        <CompatFormItem v-if="receiptNeedsReason" :label="t('production.plans_list.label_7a2abf6405e6')" required>
          <CompatSelect v-model="receiptForm.responsibilitySide" :placeholder="t('production.plans_list.placeholder_2b0d794013e7')" style="width: 100%">
            <CompatOption
              v-for="opt in RECEIPT_RESPONSIBILITY_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        </template>
        <CompatFormItem :label="t('production.plans_list.label_daede9881787')">
          <el-input
            v-model="receiptForm.varianceNote"
            type="textarea"
            :rows="2"
            :placeholder="t('production.plans_list.placeholder_424e06f4eadd')"
          />
        </CompatFormItem>
        <el-alert
          v-if="receiptSubmitDisabledReason"
          :title="receiptSubmitDisabledReason"
          type="warning"
          show-icon
          :closable="false"
          style="margin-bottom: 4px"
        />
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="receiptDialogVisible = false">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="primary"
          :loading="actionLoading"
          data-testid="finished-goods-receive-dialog-submit"
          :disabled="!receiptCanSubmit"
          @click="submitWarehouseReceipt"
        >{{ t('production.plans_list.action_f1f3965f6080') }}</UButton>
      </template>
    </el-dialog>

    <CompatDialog
      v-model="clearingDialogVisible"
      :title="clearingProductName ? `中转挂账清账 — ${clearingProductName}` : t('production.plans_list.title_615b303181b9')"
      width="560px"
      class="settlement-dialog"
      destroy-on-close
      append-to-body
    >
      <CompatForm v-loading="clearingLoading" label-width="112px" class="settlement-form">
        <div class="settlement-context">
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_167b0c9adcd9') }}</div>
            <div class="settlement-context-value">{{ clearingPlanNumber || '-' }}</div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_82b7cf40b5e6') }}</div>
            <div class="settlement-context-value">
              {{ postingStatusText(clearingSettlement?.postingStatus) }}
            </div>
          </div>
          <div>
            <div class="settlement-context-label">{{ t('production.plans_list.text_572cb4a367ba') }}</div>
            <div class="settlement-context-value">
              {{ clearingSettlement?.postingMessage || '-' }}
            </div>
          </div>
        </div>

        <el-alert
          :title="t('production.plans_list.title_9c278d3124b3')"
          type="warning"
          show-icon
          :closable="false"
          style="margin: 12px 0"
        />

        <CompatFormItem :label="t('production.plans_list.label_e10d67a6884b')" required>
          <CompatSelect
            v-model="clearingForm.clearingReason"
            :placeholder="t('production.plans_list.placeholder_1be6f1099773')"
            style="width: 100%"
          >
            <CompatOption
              v-for="opt in CLEARING_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem v-if="clearingForm.clearingReason === '其他'" :label="t('production.plans_list.label_59889a398f35')" required>
          <el-input
            v-model="clearingForm.otherClearingReason"
            type="textarea"
            :rows="2"
            :placeholder="t('production.plans_list.placeholder_37ca9292730a')"
          />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_653abf1dd5e7')">
          <el-input
            v-model="clearingForm.clearingNote"
            type="textarea"
            :rows="3"
            :placeholder="t('production.plans_list.placeholder_ce4ba7d5e7d5')"
          />
        </CompatFormItem>
        <el-alert
          v-if="clearingDisabledReason"
          :title="clearingDisabledReason"
          type="warning"
          show-icon
          :closable="false"
        />
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="clearingDialogVisible = false">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton
          color="error"
          :loading="actionLoading"
          :disabled="!clearingCanSubmit"
          @click="submitTransitClearing"
        >{{ t('production.plans_list.action_6d9468de7b61') }}</UButton>
      </template>
    </CompatDialog>

    <!-- #743 取消原因 dialog (快捷下拉 + 品名) -->
    <CompatDialog
      v-model="cancelDialogVisible"
      :title="cancelProductName ? `取消计划 — ${cancelProductName} (${cancelPlanNumber})` : t('production.plans_list.title_10c3a2e7a980')"
      width="460px"
      destroy-on-close
      append-to-body
    >
      <CompatForm label-width="100px">
        <CompatFormItem :label="t('production.plans_list.label_7d432cf3863b')">
          <span>{{ cancelProductName || '-' }}</span>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_167b0c9adcd9')">
          <span>{{ cancelPlanNumber || '-' }}</span>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_b73005b16d22')" required>
          <CompatSelect
            v-model="cancelForm.reasonOption"
            :placeholder="t('production.plans_list.placeholder_59e5542fcb33')"
            style="width: 100%"
          >
            <CompatOption
              v-for="opt in CANCEL_REASON_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem v-if="cancelForm.reasonOption === '其他'" :label="t('production.plans_list.label_59889a398f35')" required>
          <el-input
            v-model="cancelForm.otherReason"
            type="textarea"
            :rows="3"
            :placeholder="t('production.plans_list.placeholder_3a22f008fcf5')"
          />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="cancelDialogVisible = false">{{ t('production.plans_list.action_3fd47edce45b') }}</UButton>
        <UButton color="error" :loading="actionLoading" @click="submitCancel">{{ t('production.plans_list.action_ed723524fe93') }}</UButton>
      </template>
    </CompatDialog>

    <!-- 编辑生产计划 dialog (更多→编辑 dead-stub 修复; 防呆 Rule 2: 标题带品名+单号) -->
    <CompatDialog
      v-model="editDialogVisible"
      :title="editingProductName ? `编辑生产计划 — ${editingProductName} (${editingPlanNumber})` : t('production.plans_list.title_75dc8eb83cc1')"
      width="520px"
      destroy-on-close
      append-to-body
    >
      <CompatForm v-loading="editDialogLoading" :model="editForm" label-width="120px">
        <!--
          防呆 Rule 1: 已开工的计划能改什么, 打开就看得见 —— 两个硬字段灰显并写明原因,
          而不是让用户改完点保存才被 409 弹回来。
        -->
        <el-alert
          v-if="editScopeIsScheduleMeta"
          type="info"
          :closable="false"
          show-icon
          :title="t('production.plans_list.title_002934444a2d')"
          :description="t('production.plans_list.description_bb301a849d37')"
          style="margin-bottom: 12px;"
        />
        <CompatFormItem :label="t('production.plans_list.label_aea82737cc01')">
          <span>{{ editingProductName || '-' }}</span>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_5c2eb0883fd7')">
          <span>{{ editingPlanNumber || '-' }}</span>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_972e6a485642')" :required="!editScopeIsScheduleMeta">
          <CompatDatePicker
            v-model="editForm.plannedDate"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="t('production.plans_list.placeholder_b3812a61504a')"
            style="width: 100%"
            :disabled="editScopeIsScheduleMeta"
          />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_3248d4a7fefc')">
          <CompatDatePicker
            v-model="editForm.expectedCompletionDate"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="t('production.plans_list.placeholder_db40cd4e7f1a')"
            style="width: 100%"
            clearable
          />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_19a908ffd1ae')" :required="!editScopeIsScheduleMeta">
          <el-input-number
            v-model="editForm.plannedQuantity"
            :min="0"
            :precision="2"
            style="width: 100%"
            :disabled="editScopeIsScheduleMeta"
          />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_ad4181bf4e26')">
          <el-input-number v-model="editForm.estimatedWorkers" :min="1" :max="500" style="width: 100%" :placeholder="t('production.plans_list.placeholder_394680d3cea1')" />
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_03df8922ea8e')">
          <CompatSelect v-model="editForm.assignedSupervisorId" clearable :placeholder="t('production.plans_list.placeholder_9a3640adf716')" style="width: 100%">
            <CompatOption v-for="sup in supervisors" :key="sup.id" :label="sup.fullName || sup.username" :value="sup.id" />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('production.plans_list.label_daede9881787')">
          <el-input v-model="editForm.notes" type="textarea" :rows="2" :placeholder="t('production.plans_list.placeholder_394680d3cea1')" />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="editDialogVisible = false">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="editDialogLoading" @click="submitEditPlan">{{ t('production.plans_list.action_a3030bf8f16d') }}</UButton>
      </template>
    </CompatDialog>

    <!-- SP2 独立再加工/返工计划创建 dialog -->
    <CompatDialog
      v-model="secondaryDialogVisible"
      :title="t('production.plans_list.title_940a89920308')"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
      append-to-body
    >
      <CompatForm :model="secondaryForm" label-width="110px" v-loading="wipListLoading">
        <!-- 防呆 Rule 1: 源 WIP 可用量前置显示，不等提交才报错 -->
        <CompatFormItem :label="t('production.plans_list.label_67dc6c1da241')" required>
          <el-select
            v-model="secondaryForm.wipId"
            :placeholder="t('production.plans_list.placeholder_4c14a9d29bb2')"
            filterable
            style="width: 100%"
            :loading="wipListLoading"
            @change="handleWipSelect"
          >
            <el-option
              v-for="wip in wipList"
              :key="wip.id"
              :value="wip.id"
              :label="`${wip.intermediateBatchNo} | 可用 ${wip.availableQuantity}${displayUnit(wip.unit)}`"
            >
              <span style="float: left; font-weight: 500;">{{ wip.intermediateBatchNo }}</span>
              <span style="float: right; font-size: 12px; color: var(--cs-muted); margin-left: 12px;">
                {{ t('production.plans_list.text_4d99c976beb8') }} {{ wip.availableQuantity }}{{ displayUnit(wip.unit) }}
              </span>
            </el-option>
          </el-select>
          <!-- 防呆 Rule 1: 实时显示所选 WIP 可用量 -->
          <div v-if="selectedWip" style="margin-top: 6px; font-size: 12px; color: var(--el-color-success);">
            {{ t('production.plans_list.text_9291de781e60') }} <strong>{{ selectedWip.availableQuantity }} {{ displayUnit(selectedWip.unit) }}</strong>
            <template v-if="selectedWip.unitCost != null">
              {{ t('production.plans_list.text_33a9eb391e90') }}{{ Number(selectedWip.unitCost).toFixed(4) }}
            </template>
          </div>
          <div v-if="wipList.length === 0 && !wipListLoading" style="margin-top: 6px; font-size: 12px; color: var(--el-color-warning);">
            {{ t('production.plans_list.text_1c3532227187') }}
          </div>
        </CompatFormItem>

        <CompatFormItem :label="t('production.plans_list.label_e04cb061f5ce')" required>
          <!-- 防呆 Rule 1: :max 限制不超过可用量 -->
          <el-input-number
            v-model="secondaryForm.quantity"
            :min="0.01"
            :max="selectedWip ? Number(selectedWip.availableQuantity) : undefined"
            :precision="2"
            style="width: 100%"
          />
          <div v-if="selectedWip" style="font-size: 12px; color: var(--text-color-secondary, var(--cs-muted)); margin-top: 4px;">
            {{ t('production.plans_list.text_27028e07c61b') }} {{ selectedWip.availableQuantity }} {{ displayUnit(selectedWip.unit) }}{{ t('production.plans_list.text_fd7a582f86b2') }}
          </div>
        </CompatFormItem>

        <CompatFormItem :label="t('production.plans_list.label_cd8ec8a28f8a')" required>
          <CompatSelect
            v-model="secondaryForm.productTypeId"
            :placeholder="t('production.plans_list.placeholder_505a93195b39')"
            filterable
            style="width: 100%"
          >
            <CompatOption
              v-for="item in productTypes"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </CompatSelect>
          <div v-if="selectedWip && !secondaryForm.productTypeId" style="margin-top: 4px; font-size: 12px; color: var(--text-color-secondary, var(--cs-muted));">
            {{ t('production.plans_list.text_2c9ec1e56a9a') }}
          </div>
          <!-- 防呆 Rule 2: 来源上下文 -->
          <div v-if="selectedWip && secondaryForm.productTypeId" style="margin-top: 4px; font-size: 12px; color: var(--text-color-secondary, var(--cs-muted));">
            {{ t('production.plans_list.text_71faa8fdd443') }} {{ selectedWip.intermediateBatchNo }}
          </div>
        </CompatFormItem>

        <CompatFormItem :label="t('production.plans_list.label_011247215d19')">
          <CompatDatePicker
            v-model="secondaryForm.plannedDate"
            type="date"
            :placeholder="t('production.plans_list.placeholder_2f8143fa18df')"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="secondaryDialogVisible = false">{{ t('production.plans_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="secondaryDialogLoading" @click="submitSecondaryPlan">{{ t('production.plans_list.action_940a89920308') }}</UButton>
      </template>
    </CompatDialog>

    <!-- AI 对话创建 -->
    <AiEntryDrawer
      v-model="aiEntryVisible"
      :config="PRODUCTION_PLAN_CONFIG"
      :confirm-guard="guardProductionPlanAi"
      @fill-form="handleAiFill"
    />

    <!-- SP-F: 逐工序电子表格抽屉 -->
    <el-drawer
      v-model="entryDrawerVisible"
      :title="`逐工序录入 — ${entryProductLabel}`"
      size="80%"
      :close-on-click-modal="false"
      :destroy-on-close="false"
      :before-close="handleEntryDrawerBeforeClose"
    >
      <ProcessSheet
        v-if="entryRow"
        ref="processSheetRef"
        :factory-id="String(factoryId)"
        :plan-id="String(entryRow?.id || '')"
        :product-type-id="String(entryRow?.productTypeId || '')"
        :product-name="entryProductLabel"
        :planned-quantity="Number(entryRow?.plannedQuantity || 0)"
        :planned-unit="entryRow?.plannedUnit || null"
        @submitted="onEntrySubmitted"
      />
    </el-drawer>

    <!-- 阅读汇总弹窗 -->
    <ProductionSummaryDialog
      v-model="summaryDialogVisible"
      :factory-id="String(factoryId)"
      :plan-id="summaryPlanId"
      :plan-number="summaryPlanNumber"
      :product-name="summaryProductName"
    />

    <!-- 撤销小结审批 (STOCKTAKE_APPROVAL_ROLES: 财务经理/工厂超管) -->
    <CompatDialog v-model="reversalApprovalVisible" :title="t('production.plans_list.title_d1e55c3a4f9b')" width="760px" destroy-on-close>
      <div style="margin-bottom:8px;color:var(--cs-muted);font-size:13px;">
        {{ t('production.plans_list.text_b31c6ccc8368') }}
      </div>
      <CompatTable :data="reversalRequests" v-loading="reversalApprovalLoading" size="small" border>
        <CompatTableColumn prop="productionPlanId" :label="t('production.plans_list.label_0f6b1949b093')" min-width="120" show-overflow-tooltip />
        <CompatTableColumn prop="sessionSeq" :label="t('production.plans_list.label_b3a43d2ab02a')" width="90" align="center" />
        <CompatTableColumn prop="reason" :label="t('production.plans_list.label_258f2558689d')" min-width="150" show-overflow-tooltip />
        <CompatTableColumn prop="requestedAt" :label="t('production.plans_list.label_e9b5d944cc4b')" width="160" />
        <CompatTableColumn :label="t('production.plans_list.label_ed31fbb483ee')" width="150" align="center">
          <template #default="{ row }">
            <UButton color="success" size="sm" variant="link"
              :disabled="reversalActingId === row.id" :loading="reversalActingId === row.id"
              @click="handleApproveReversal(row)">{{ t('production.plans_list.action_642cfa74e9d8') }}</UButton>
            <UButton color="error" size="sm" variant="link"
              :disabled="reversalActingId === row.id"
              @click="handleRejectReversal(row)">{{ t('production.plans_list.action_089e6ed3f55f') }}</UButton>
          </template>
        </CompatTableColumn>
        <template #empty>{{ t('production.plans_list.text_9a300659cf0e') }}</template>
      </CompatTable>
    </CompatDialog>
  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.wide-table :deep(.el-scrollbar__bar.is-horizontal) { opacity: 1; }
// 缺陷 51: 多成品批次时的「分配工序操作员」选择框。
.assign-operator-picker-hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.assign-operator-picker-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-color-lighter, var(--cs-border));
  &:last-child { border-bottom: none; }
}
.ai-unit-preserved { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; }
// 校验失败字段红框锚定 (fool-proof-design Rule: 预先显示边界, 不只弹 toast 让用户自己找字段)。
.field-error {
  :deep(.el-select__wrapper) {
    box-shadow: 0 0 0 1px var(--el-color-danger, var(--cs-danger)) inset !important;
  }
}

.field-error-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger, var(--cs-danger));
}

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
    border-bottom: 1px solid var(--border-color-lighter, var(--cs-border));
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
  flex-wrap: wrap;
  gap: 8px;

  .header-right {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 0;
  }

  .header-left {
    display: flex;
    align-items: baseline;
    gap: 12px;

    .page-title {
      font-size: 16px;
      font-weight: 600;
      color: var(--text-color-primary, var(--cs-text));
    }

    .data-count {
      font-size: 13px;
      color: var(--text-color-secondary, var(--cs-muted));
    }
  }
}

.search-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.el-table {
  flex: 1;
}

:deep(.el-table__body tr.plan-row-pending > td.el-table__cell) {
  background: #fff4cf !important;
}

:deep(.el-table__body tr.plan-row-in-progress > td.el-table__cell) {
  background: #e8f3ff !important;
}

:deep(.el-table__body tr.plan-row-completed > td.el-table__cell) {
  background: #e8f8df !important;
}

:deep(.el-table__body tr.plan-row-cancelled > td.el-table__cell) {
  background: var(--cs-surface-subtle) !important;
  color: var(--text-color-secondary, var(--cs-muted));
}

:deep(.el-table__body tr.plan-row-exception > td.el-table__cell) {
  background: #fde8e8 !important;
}

:deep(.el-table__body tr.plan-row-pending:hover > td.el-table__cell) {
  background: #ffe9a8 !important;
}

:deep(.el-table__body tr.plan-row-in-progress:hover > td.el-table__cell) {
  background: #d8ebff !important;
}

:deep(.el-table__body tr.plan-row-completed:hover > td.el-table__cell) {
  background: #dcf3d0 !important;
}

:deep(.el-table__body tr.plan-row-exception:hover > td.el-table__cell) {
  background: #fad1d1 !important;
}

.plan-status-tag {
  min-width: 76px;
  height: 30px;
  justify-content: center;
  border-width: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0;
}

.plan-status-pending {
  --el-tag-bg-color: var(--cs-warning);
  --el-tag-text-color: #ffffff;
}

.plan-status-in-progress {
  --el-tag-bg-color: var(--cs-primary);
  --el-tag-text-color: #ffffff;
}

.plan-status-completed {
  --el-tag-bg-color: var(--cs-success);
  --el-tag-text-color: #ffffff;
}

.plan-status-cancelled {
  --el-tag-bg-color: var(--cs-muted);
  --el-tag-text-color: #ffffff;
}

.plan-status-exception {
  --el-tag-bg-color: var(--cs-danger);
  --el-tag-text-color: #ffffff;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, var(--cs-border));
  margin-top: 16px;
}

.plan-number-link {
  display: inline-flex;
  max-width: 100%;
  overflow: hidden;

  :deep(span) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.plan-action-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 2px 8px;
  min-height: 30px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

.archive-center-summary {
  margin-bottom: 16px;
}

.archive-center-tabs {
  :deep(.el-tabs__header) {
    margin-bottom: 16px;
  }
}

.archive-center-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.archive-center-grid--accounting {
  margin-top: 16px;
}

.archive-center-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-height: 104px;
  padding: 16px;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 10px;
  background: var(--fill-color-lighter, var(--cs-surface-subtle));
  color: var(--text-color-primary, var(--cs-text));
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;

  strong {
    margin-bottom: 8px;
    font-size: 15px;
  }

  span {
    color: var(--text-color-secondary, var(--cs-muted));
    font-size: 13px;
    line-height: 1.5;
  }

  &:hover:not(:disabled),
  &:focus-visible {
    border-color: var(--el-color-primary, var(--cs-primary));
    background: var(--el-color-primary-light-9, #ecf5ff);
    outline: none;
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.55;
  }
}

.archive-center-card--primary {
  border-color: var(--el-color-primary-light-5, #a0cfff);
}

.archive-center-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;

  :deep(.el-button + .el-button) {
    margin-left: 0;
  }
}

@media (max-width: 768px) {
  .archive-center-grid {
    grid-template-columns: 1fr;
  }
}

// ==================== Plan Detail Dialog ====================
.plan-detail {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-section {
  .detail-section-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-color-regular, var(--cs-muted));
    margin-bottom: 8px;
    display: flex;
    align-items: center;
    gap: 8px;

    .detail-section-hint {
      font-size: 12px;
      font-weight: 400;
      color: var(--text-color-secondary, var(--cs-muted));
    }
  }
}

.process-list-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 12px 0;
  font-size: 13px;
  color: var(--text-color-secondary, var(--cs-muted));
}

.process-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 4px;
  overflow: hidden;

  .process-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    font-size: 13px;
    border-bottom: 1px solid var(--border-color-extra-light, #f2f6fc);

    &:last-child {
      border-bottom: none;
    }

    &:nth-child(even) {
      background: var(--fill-color-light, var(--cs-surface-subtle));
    }

    .process-order {
      color: var(--text-color-secondary, var(--cs-muted));
      min-width: 20px;
      flex-shrink: 0;
    }

    .process-name {
      flex: 1;
      color: var(--text-color-primary, var(--cs-text));
    }

    .process-assignee {
      flex-shrink: 0;
    }

    .process-unassigned {
      font-size: 12px;
      color: var(--text-color-placeholder, #c0c4cc);
    }
  }
}

.process-list-empty {
  font-size: 13px;
  color: var(--text-color-secondary, var(--cs-muted));
  padding: 8px 0;
}

.detail-notes {
  font-size: 13px;
  color: var(--text-color-regular, var(--cs-muted));
  line-height: 1.6;
  padding: 8px 12px;
  background: var(--fill-color-light, var(--cs-surface-subtle));
  border-radius: 4px;
}

.next-step-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.next-step-hint {
  font-size: 12px;
  color: var(--text-color-secondary, var(--cs-muted));
  line-height: 1.3;
}

.cancellation-audit {
  max-width: 100%;
  font-size: 12px;
  color: var(--text-color-secondary, var(--cs-muted));
  line-height: 1.35;
}

.cancellation-audit__reason {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settlement-form {
  .el-divider {
    margin: 16px 0 14px;
  }
}

.settlement-dialog {
  display: flex;
  max-height: 92vh;
  flex-direction: column;

  .el-dialog__body {
    overflow-y: auto;
    padding-bottom: 12px;
  }

  .el-dialog__footer {
    border-top: 1px solid var(--border-color-lighter, var(--cs-border));
    padding-top: 12px;
  }
}

.settlement-context {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 4px;
}

.settlement-context > div {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 8px;
  background: var(--cs-surface-subtle);
}

.settlement-context-label {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-color-secondary, var(--cs-muted));
}

.settlement-context-value {
  overflow: hidden;
  color: var(--text-color-primary, var(--cs-text));
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settlement-reconciliation-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.settlement-reconciliation-card,
.settlement-reconciliation-section {
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 8px;
  background: var(--cs-surface-subtle);
}

.settlement-reconciliation-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px;

  span {
    color: var(--text-color-secondary, var(--cs-muted));
    font-size: 12px;
  }

  strong {
    color: var(--text-color-primary, var(--cs-text));
    font-size: 16px;
  }
}

.settlement-reconciliation-section {
  margin-top: 10px;
  overflow: hidden;
}

.settlement-reconciliation-title {
  padding: 9px 12px;
  border-bottom: 1px solid var(--border-color-lighter, var(--cs-border));
  color: var(--text-color-primary, var(--cs-text));
  font-size: 13px;
  font-weight: 600;
}

.settlement-reconciliation-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 12px;
  border-bottom: 1px solid var(--border-color-extra-light, #f2f6fc);
  color: var(--text-color-regular, var(--cs-muted));
  font-size: 13px;

  &:last-child {
    border-bottom: 0;
  }

  strong {
    color: var(--text-color-primary, var(--cs-text));
    white-space: nowrap;
  }
}

.settlement-help {
  margin-top: 4px;
  color: var(--text-color-secondary, var(--cs-muted));
  font-size: 12px;
  line-height: 1.5;
}

.receipt-output-lines {
  display: grid;
  gap: 12px;
  margin-bottom: 14px;
}

.receipt-output-line {
  display: grid;
  grid-template-columns: minmax(150px, 1.2fr) minmax(120px, 0.8fr) minmax(220px, 1.4fr);
  align-items: center;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--border-color-lighter, #dfe6ef);
  border-radius: 10px;
  background: var(--cs-surface-subtle);
}

.receipt-output-line__identity,
.receipt-output-line__reported {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;

  span,
  small {
    color: var(--text-color-secondary, var(--cs-muted));
    font-size: 12px;
  }

  strong {
    overflow: hidden;
    color: var(--text-color-primary, var(--cs-text));
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.receipt-output-line__input {
  margin-bottom: 0;

  :deep(.el-form-item__content) {
    flex-wrap: nowrap;
    gap: 8px;
  }
}

.receipt-output-line__unit {
  flex: 0 0 auto;
  color: var(--text-color-regular, var(--cs-muted));
}

@media (max-width: 760px) {
  .receipt-output-line {
    grid-template-columns: 1fr;
  }
}

.settlement-loss-guide {
  color: var(--text-color-regular, var(--cs-muted));
  font-size: 13px;
  line-height: 1.6;
}

.settlement-advisory-alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 12px;

  .el-alert {
    flex: 1;
  }

  .el-button {
    flex: 0 0 auto;
  }
}

.settlement-line-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 10px 0 8px;
  color: var(--text-color-primary, var(--cs-text));
  font-size: 14px;
  font-weight: 600;
}

.settlement-empty {
  margin-bottom: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--border-color, var(--cs-border));
  border-radius: 6px;
  color: var(--text-color-secondary, var(--cs-muted));
  font-size: 13px;
  line-height: 1.5;
}

.plan-product-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.plan-product-name {
  overflow: hidden;
  min-width: 0;
  color: var(--text-color-primary, var(--cs-text));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-preview-trigger {
  flex: 0 0 auto;
  padding-inline: 2px;
  opacity: 0.72;
}

.plan-product-cell:hover .workflow-preview-trigger,
.workflow-preview-trigger:focus-visible {
  opacity: 1;
}

.plan-workflow-preview {
  min-height: 88px;
}

.sales-order-quantity-readonly {
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 8px;
  background: var(--fill-color-light, var(--cs-surface-subtle));
}

.sales-order-quantity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color-lighter, var(--cs-border));
}

.sales-order-quantity-row strong {
  flex: 0 0 auto;
  color: var(--text-color-primary, var(--cs-text));
}

.sales-order-quantity-readonly small {
  display: block;
  padding: 7px 12px;
  color: var(--text-color-secondary, var(--cs-muted));
}

.settlement-consumption-row {
  display: grid;
  grid-template-columns: minmax(220px, 1.2fr) minmax(120px, 0.5fr) minmax(150px, 0.8fr) auto;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.settlement-wip-boundary {
  grid-column: 1 / -1;
  margin-top: -2px;
  color: var(--text-color-secondary, var(--cs-muted));
  font-size: 12px;
  line-height: 1.5;
}

.settlement-wip-boundary strong {
  color: var(--text-color-primary, var(--cs-text));
  font-weight: 600;
}

.consumption-select,
.consumption-qty,
.consumption-note {
  width: 100%;
}

.receipt-diff-panel {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0 0 12px 124px;
}

.receipt-diff-panel > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 8px;
  background: #ffffff;
}

.receipt-diff-panel span {
  color: var(--text-color-secondary, var(--cs-muted));
  font-size: 12px;
}

.receipt-diff-panel strong {
  overflow: hidden;
  color: var(--text-color-primary, var(--cs-text));
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.receipt-diff-panel .danger {
  border-color: var(--color-danger-light-5, #fab6b6);
  background: var(--color-danger-light-9, var(--cs-danger-soft));

  strong {
    color: var(--color-danger, var(--cs-danger));
  }
}

.document-trace {
  min-height: 180px;
}

.trace-document-card :deep(.el-card__body) {
  padding: 12px 14px;
}

.trace-document-header,
.trace-document-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trace-document-header > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.trace-document-number {
  margin: 10px 0 6px;
  color: var(--text-color-primary, var(--cs-text));
  font-size: 15px;
  font-weight: 600;
}

.trace-document-meta {
  color: var(--text-color-secondary, var(--cs-muted));
  font-size: 12px;
}

.native-file-input {
  display: none;
}

.source-order-link {
  display: block;
  max-width: 155px;
  margin: 4px auto 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-pack-chapters {
  padding: 14px 16px;
  border: 1px solid var(--border-color-lighter, var(--cs-border));
  border-radius: 8px;
  background: var(--fill-color-lighter, var(--cs-surface-subtle));
}

.document-pack-label {
  margin-bottom: 10px;
  color: var(--text-color-primary, var(--cs-text));
  font-size: 13px;
  font-weight: 600;
}

.document-pack-chapters :deep(.el-checkbox-group) {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.selected-workflow-route {
  width: 100%;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid #b9ddc6;
  border-radius: 10px;
  background: #f5fbf7;
}

.selected-workflow-route__head,
.workflow-candidate-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.selected-workflow-route__head > div:first-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.selected-workflow-route__eyebrow {
  color: #548066;
  font-size: 11px;
}

.selected-workflow-route__head strong,
.workflow-candidate-card__process {
  color: #1d2c3d;
  font-size: 14px;
  line-height: 1.45;
}

.selected-workflow-route__meta,
.selected-workflow-route__outputs,
.workflow-candidate-card__meta,
.workflow-candidate-card__outputs {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.selected-workflow-route__meta {
  margin-top: 9px;
  color: #667085;
  font-size: 12px;
}

.selected-workflow-route__outputs {
  margin-top: 8px;
}

.workflow-route-decision-alert {
  width: 100%;
  margin-top: 8px;
}

.workflow-route-decision-alert :deep(.el-alert__content) {
  width: 100%;
}

.workflow-route-decision-alert .el-button {
  margin-top: 9px;
}

.workflow-candidate-dialog__hint {
  margin-bottom: 14px;
}

.workflow-candidate-list {
  display: flex;
  width: 100%;
  max-height: 58vh;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding: 2px 4px 4px 2px;
}

.workflow-candidate-card {
  padding: 14px 16px;
  border: 1px solid #d9e1ea;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: border-color 150ms ease, box-shadow 150ms ease, background 150ms ease;
}

.workflow-candidate-card:hover {
  border-color: #91b9e9;
  box-shadow: 0 6px 18px rgba(47, 104, 170, 0.09);
}

.workflow-candidate-card.is-selected {
  border-color: var(--el-color-primary, var(--cs-primary));
  background: #f5f9ff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.workflow-candidate-card__top :deep(.el-radio) {
  min-width: 0;
  height: auto;
  flex: 1;
  align-items: flex-start;
  white-space: normal;
}

.workflow-candidate-card__top :deep(.el-radio__label) {
  min-width: 0;
  padding-right: 8px;
  white-space: normal;
}

.workflow-candidate-card__meta {
  margin: 9px 0;
  color: #7a8695;
  font-size: 12px;
}

.workflow-candidate-card__outputs {
  padding-top: 9px;
  border-top: 1px solid #edf0f4;
}

.workflow-candidate-card__label {
  margin-right: 2px;
  color: #667085;
  font-size: 12px;
  font-weight: 600;
}

.workflow-candidate-card :deep(.el-alert) {
  margin-top: 10px;
}

@media (max-width: 760px) {
  .settlement-consumption-row,
  .receipt-diff-panel,
  .settlement-reconciliation-grid {
    grid-template-columns: 1fr;
  }

  .receipt-diff-panel {
    margin-left: 0;
  }

  .selected-workflow-route__head,
  .workflow-candidate-card__top {
    flex-direction: column;
  }
}

</style>
