<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { deriveByShare } from '@/views/system/product-processes/workflow/workflowInputShare';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { showSingletonNotification } from '@/utils/singletonNotification';
import { Plus, Refresh, View, Tickets, Printer } from '@element-plus/icons-vue';
import request, { get, post, put } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  RAW_WORKFLOW_OWNER_ANCHOR_LABEL,
  isRawWorkflowOwnerAnchorName,
} from '@/constants/rawWorkflowOwnerAnchor';
import { handleCatchError } from '@/utils/errorToast';
import { listWarehouses } from '@/api/factoryWarehouse';
import { bomSeasoningApi } from '@/api/bom';
import { deriveAuxiliaryQty, rawPickedTotalKg, splitZeroQtyRows } from './confirmPickingAuxDerive';

interface RequisitionItem {
  id: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCategory?: string | null;
  requiredQty?: number | string | null;
  /** 主原料份额(%), 建单时抄自计划钉住的工艺修订端口; null=按实际领用。 */
  inputShareRatio?: number | null;
  pickedQty?: number | string | null;
  issuedQty?: number | string | null;
  consumedQty?: number | string | null;
  wastageQty?: number | string | null;
  returnedQty?: number | string | null;
  unit?: string | null;
  // 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」): 生成需求单那一刻
  // RawMaterialType.selfSupplied 的快照。true = 冰水/水/蒸汽/现场自制高汤等配方
  // 提醒行 —— 不强制填数量、不查库存、不因库存不足拦截。
  selfSupplied?: boolean | null;
}

interface Requisition {
  id: string;
  requisitionNo: string;
  status: string;
  productionPlanId?: string | null;
  productionPlanNumber?: string | null;
  productName?: string | null;
  sourceWarehouseId?: string | null;
  targetWarehouseId?: string | null;
  requiredDate?: string | null;
  requestedByName?: string | null;
  createdAt?: string | null;
  remarks?: string | null;
  items?: RequisitionItem[];
}

interface ClosePreviewRow extends RequisitionItem {
  wastageInput: number;
  previewReturnQty: number;
}

/** 生产计划下拉选项 (仅未完结计划: 待执行/进行中/待处理/暂停) — Rule 1 防呆: 不让仓管手填 UUID。 */
interface PlanOption {
  id: string;
  planNumber?: string | null;
  productName?: string | null;
  plannedDate?: string | null;
  statusDisplayName?: string | null;
}

const router = useRouter();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('production'));

const loading = ref(false);
const submitting = ref(false);
const tableData = ref<Requisition[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const statusFilter = ref('');

const createDialogVisible = ref(false);
// plannedRawInputKg: 可选。留空 = 沿用旧行为(辅料/包材需求量留空「按实际领用」)。
// 早上定产量 → 人据此决定这批领多少原料 → 这里就是那个数字的落点(owner 2026-09-01 拍板),
// 不是能反推的主数据, ⛔ 不预填、不猜。
const createForm = ref<{ productionPlanId: string; plannedRawInputKg: number | null }>({
  productionPlanId: '',
  plannedRawInputKg: null,
});
const planOptions = ref<PlanOption[]>([]);
const planOptionsLoading = ref(false);

// 生产计划 id → { planNumber, productName, plannedDate } 解析缓存, 供表格/详情把裸 GUID 转人话展示。
const planCache = ref<Record<string, { planNumber?: string | null; productName?: string | null; plannedDate?: string | null }>>({});
// 仓库 id → 仓库真名 (来自 factory/warehouses, DB 名权威, 非硬编码)。
const warehouseNameMap = ref<Record<string, string>>({});

const detailDialogVisible = ref(false);
const detailData = ref<Requisition | null>(null);

const closeDialogVisible = ref(false);
const closeTarget = ref<Requisition | null>(null);
const closeRows = ref<ClosePreviewRow[]>([]);

interface ConfirmPickRow extends RequisitionItem {
  // null = 「用量待定」行留空待仓管称重录入 (见 prepareConfirmDialog); 不是 0。
  pickedInput: number | null;
  // true = 辅料行命中了 BOM 调料配方的 dosagePerKgG 比例, 由 RAW 合计自动推算
  // (owner 2026-09-02 拍板)。false/undefined = 没有比例数据, 维持人工填 (老行为)。
  autoLocked?: boolean;
  // true = 用户手改过这一行的自动计算值 —— 之后 recalc 不再覆盖它, 直到点
  // 「恢复自动计算」重置 (owner 卡点②: 现场多放了盐是真实的, 不能被静默吃掉)。
  // 主料份额推算 (a) 复用同一个标记: RAW 行手填过就不再被份额推算覆盖 (⛔ 不留第二套标记)。
  manuallyTouched?: boolean;
}

/**
 * owner 2026-09-02: 「填入第一个原料 30 吨, 第二个它会自动跳出 70 吨」。
 * 只在**原料**行之间推; 自备料行不参与 (它没有领用量, 既不被推也不当基准);
 * 手填过 (manuallyTouched) 的行不覆盖。份额来自 item.inputShareRatio (工艺端口冻结值)。
 * 调用顺序固定为 RAW 份额 → recalcAuxRows: 一次填数, 其余主料和辅料全部跳出来。
 */
function autoFillPickedByShare(changed: ConfirmPickRow): void {
  const raws = confirmRows.value.filter((r) => (r.materialCategory ?? 'RAW') === 'RAW' && !isSelfSupplied(r));
  const derived = deriveByShare(raws.map((r) => ({ id: r.id, share: r.inputShareRatio ?? null })), changed.id, changed.pickedInput);
  if (derived.size === 0) return;
  for (const r of raws) {
    if (r.id === changed.id || !derived.has(r.id)) continue;
    if (r.pickedInput != null && r.manuallyTouched) continue;
    r.pickedInput = derived.get(r.id) ?? null;
    r.manuallyTouched = false;
  }
}
const confirmDialogVisible = ref(false);
const confirmTarget = ref<Requisition | null>(null);
const confirmRows = ref<ConfirmPickRow[]>([]);
// 物料id(materialTypeId) → dosagePerKgG(g/kg投入) —— 来自该计划产品当前 BOM 的调料配方
// (bomSeasoningApi.getByProduct), 供辅料行实时推算。见 loadAuxRatios / recalcAuxRows。
const auxRatioMap = ref<Record<string, number>>({});

// 取消原因: 标准选项 (Rule 3 防呆 — 自由文本改约束选择), 选"其他"才显文本框。
const CANCEL_REASON_OPTIONS = ['计划取消', '需求变更', '重复单据', '数量错误', '其他'] as const;
const cancelDialogVisible = ref(false);
const cancelTarget = ref<Requisition | null>(null);
const cancelForm = ref({ reason: '', otherReason: '' });

const statusMap: Record<string, { text: string; type: 'info' | 'warning' | 'success' | 'danger' | '' }> = {
  PENDING: { text: '待备料', type: 'warning' },
  PICKING: { text: '备料中', type: 'warning' },
  TRANSFERRED: { text: '已调拨', type: '' },
  ISSUED: { text: '已签收', type: 'success' },
  IN_USE: { text: '生产中', type: 'success' },
  CLOSED: { text: '已关单', type: 'info' },
  CANCELLED: { text: '已取消', type: 'danger' },
};

// Fool-proof-design display fix: 详情弹窗类别列之前直显后端原始枚举 (RAW/AUXILIARY/PACKAGING/
// SEMI_FINISHED, 对应 FactoryMaterialRequisitionItem.MaterialCategory), 仓管员看不懂英文缩写。
const MATERIAL_CATEGORY_LABELS: Record<string, string> = {
  RAW: '原料',
  AUXILIARY: '辅料',
  PACKAGING: '包装',
  SEMI_FINISHED: '半成品',
};
function materialCategoryLabel(category?: string | null): string {
  if (!category) return '-';
  return MATERIAL_CATEGORY_LABELS[category] || category;
}

onMounted(() => {
  loadData();
  loadWarehouseNames();
});

// 仓库真名解析 (Rule 2 防呆: 上下文带身份信息, 不显裸 ID)。
async function loadWarehouseNames() {
  if (!factoryId.value) return;
  try {
    const res = await listWarehouses(factoryId.value);
    if (res.success) {
      const map: Record<string, string> = {};
      (res.data || []).forEach((w) => {
        map[w.id] = w.name;
      });
      warehouseNameMap.value = map;
    }
  } catch {
    // best-effort: 展示回退到裸 ID, 不阻塞主流程
  }
}

function warehouseName(id?: string | null): string {
  if (!id) return '-';
  return warehouseNameMap.value[id] || id;
}

// 生产计划下拉 (Rule 1 防呆: 不让仓管手填 UUID) — 只取未完结计划。
async function loadPlanOptions() {
  if (!factoryId.value) return;
  planOptionsLoading.value = true;
  try {
    const openStatuses = ['PLANNED', 'PENDING', 'IN_PROGRESS', 'PAUSED'];
    const results = await Promise.all(
      openStatuses.map((status) =>
        get<PlanOption[]>(`/${factoryId.value}/production-plans/status/${status}`)
          .catch(() => ({ success: false, data: [] as PlanOption[], message: '' })),
      ),
    );
    const merged: PlanOption[] = [];
    for (const res of results) {
      if (res.success && Array.isArray(res.data)) merged.push(...(res.data as PlanOption[]));
    }
    const seen = new Set<string>();
    const deduped = merged.filter((p) => {
      if (!p.id || seen.has(p.id)) return false;
      seen.add(p.id);
      return true;
    });
    deduped.sort((a, b) => String(b.plannedDate || '').localeCompare(String(a.plannedDate || '')));
    planOptions.value = deduped;
    // 顺手回填缓存, 表格/详情不用再单独请求
    deduped.forEach((p) => {
      planCache.value[p.id] = { planNumber: p.planNumber, productName: p.productName, plannedDate: p.plannedDate };
    });
  } catch (e) {
    handleCatchError(e, '加载生产计划列表失败');
  } finally {
    planOptionsLoading.value = false;
  }
}

function planLabel(planId?: string | null): string {
  if (!planId) return '-';
  const cached = planCache.value[planId];
  if (cached) {
    // 2026-08-28 实拍(f006 真实客户): 这一列曾显示成
    //   PLAN-1787108510261-C307CE6E · __RAW_WORKFLOW_OWNER__RMT_b25ab8b6-…
    // 那不是产品名, 是后端为「多产出计划」建的 inactive 内部锚点(见常量文件的说明)。
    // 后端那段代码的注释写着「前端产品目录会过滤 RAW_MATERIAL」—— 产品目录确实过滤了,
    // 这一处没有。⇒ 机制在, 这个消费端没接上。
    const productLabel = isRawWorkflowOwnerAnchorName(cached.productName)
      ? RAW_WORKFLOW_OWNER_ANCHOR_LABEL
      : cached.productName;
    const parts = [cached.planNumber, productLabel].filter(Boolean);
    if (parts.length > 0) return parts.join(' · ');
  }
  return '原计划已删除/归档';
}

function toNumber(value: number | string | null | undefined): number {
  if (value === null || value === undefined || value === '') return 0;
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function qty(value: number | string | null | undefined): string {
  return toNumber(value).toFixed(3).replace(/\.?0+$/, '');
}

/**
 * 「用量待定」= BOM 里只登记了配方资格、没有参考用量 (原料/辅料在 BOM 中本来就没有数量输入框)。
 * 后端 generateFromPlan 对这类行留 requiredQty=null 而不是拦单; 前端必须把 null 和 0 区分开 ——
 * toNumber(null) 是 0, 直接走 qty() 会印成「0」, 车间照单领 0 就是短料。
 */
function isPendingQty(item: { requiredQty?: number | string | null }): boolean {
  return item.requiredQty === null || item.requiredQty === undefined || item.requiredQty === '';
}

function requiredQtyLabel(item: { requiredQty?: number | string | null; inputShareRatio?: number | null }): string {
  if (!isPendingQty(item)) return qty(item.requiredQty);
  // 主料带份额: 不再只是一句「按实际领用」—— 告诉仓管填任一原料, 其余会按这个比例跳出来。
  return item.inputShareRatio != null ? `按配比 ${Number(item.inputShareRatio)}%` : '按实际领用';
}

/**
 * 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」): 自备料
 * (冰水/水/蒸汽/现场自制高汤等) —— 配方提醒行, 不强制填数量、不查库存、
 * 不因缺库存被拦截。判据只认后端快照 selfSupplied, ⛔ 不猜。
 */
function isSelfSupplied(item: { selfSupplied?: boolean | null }): boolean {
  return item.selfSupplied === true;
}

function calcReturn(row: ClosePreviewRow): number {
  return toNumber(row.issuedQty) - toNumber(row.consumedQty) - toNumber(row.wastageInput);
}

function recalcRow(row: ClosePreviewRow) {
  row.previewReturnQty = calcReturn(row);
}

function formatDateTime(value?: string | null): string {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-';
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page - 1,
      size: pagination.value.size,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/material-requisitions`, { params });
    if (res.success) {
      const data = res.data || {};
      tableData.value = data.content || [];
      pagination.value.total = data.totalElements || 0;
      tableData.value.forEach((row) => {
        if (row.productionPlanId) {
          planCache.value[row.productionPlanId] = {
            planNumber: row.productionPlanNumber || '原计划已删除/归档',
            productName: row.productName,
          };
        }
      });
    }
  } catch (e) {
    handleCatchError(e, '加载领料单失败');
  } finally {
    loading.value = false;
  }
}

function handleStatusChange() {
  pagination.value.page = 1;
  loadData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function openCreateDialog() {
  createForm.value = { productionPlanId: '', plannedRawInputKg: null };
  createDialogVisible.value = true;
  void loadPlanOptions();
}

async function handleCreate() {
  if (submitting.value) return;
  if (!createForm.value.productionPlanId) {
    ElMessage.warning('请选择生产计划');
    return;
  }
  if (createForm.value.plannedRawInputKg !== null && createForm.value.plannedRawInputKg <= 0) {
    ElMessage.warning('计划投入原料量必须大于 0，留空则按旧行为生成');
    return;
  }
  submitting.value = true;
  try {
    const payload = {
      productionPlanId: createForm.value.productionPlanId,
      // 留空不传字段, 而不是传 null —— 与后端「未传 = 沿用旧行为」的判定对齐 (Map.get 缺键 vs 传 null 语义一致, 但显式不传更省一层歧义)。
      ...(createForm.value.plannedRawInputKg !== null ? { plannedRawInputKg: createForm.value.plannedRawInputKg } : {}),
    };
    const res = await post(`/${factoryId.value}/material-requisitions/generate`, payload);
    if (res.success) {
      createDialogVisible.value = false;
      ElMessage.success('领料单已生成');
      loadData();
    }
  } finally {
    submitting.value = false;
  }
}

async function fetchDetail(row: Requisition): Promise<Requisition | null> {
  const res = await get(`/${factoryId.value}/material-requisitions/${row.id}`);
  return res.success ? res.data as Requisition : null;
}

async function openDetail(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (data) {
      detailData.value = data;
      detailDialogVisible.value = true;
      if (data.productionPlanId) {
        planCache.value[data.productionPlanId] = {
          planNumber: data.productionPlanNumber || '原计划已删除/归档',
          productName: data.productName,
        };
      }
    }
  } catch (e) {
    handleCatchError(e, '加载领料单详情失败');
  }
}

// 领料单打印 (owner ask, 2026-09-01) — 仓管纸质凭据。后端 PrintController:231
// GET /{factoryId}/print/material-requisition/{id} 已验证生产可用 (200,
// application/pdf, ReportLab PDF)，唯独前端没有入口 (形态 B: built, never wired)。
//
// 硬约束 8 台账: 全仓 `responseType: 'blob'` 调用点普查 (grep, 排除纯注释行/测试文件) =
// 28 处 (BEFORE) → 29 处 (AFTER, 本行)。本次镜像
// procurement/orders/list.vue 的 handleDownloadPdf (采购订单「打印」逐行 action,
// key=print-pdf) —— 那是单据级 blob 下载，与本处单张领料单打印语义一致。
// 未发现字面意义上的「批量打印」按钮挂在采购订单列表；production/plans/list.vue
// 才有 productionBatchMode 批量打印，是另一个多选批处理形状，不适配这里的单行动作，
// 未采用。consolidated-material-requisition / batching-sheet 两个后端端点未验证，
// 依 brief 要求不在本次范围内，登记为 follow-up，不构建。
const printingIds = ref<Set<string>>(new Set());
async function handlePrint(row: Requisition) {
  if (!factoryId.value || !row.id) return;
  const id = row.id;
  if (printingIds.value.has(id)) return;
  printingIds.value.add(id);
  try {
    const response = await request.get(
      `/${factoryId.value}/print/material-requisition/${id}`,
      { responseType: 'blob' },
    );
    const blob = new Blob([response.data], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `领料单_${row.requisitionNo || id}.pdf`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success('打印文件已生成');
  } catch (e) {
    console.error('[领料单打印失败]', e);
    ElMessage.error('打印失败，请稍后重试');
  } finally {
    printingIds.value.delete(id);
  }
}

async function handleAction(row: Requisition, action: string, confirmMsg: string, successMsg: string) {
  if (submitting.value) return;
  try {
    await ElMessageBox.confirm(confirmMsg, '确认', { type: 'warning' });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/${action}`, {});
    if (res.success) {
      ElMessage.success(successMsg);
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

const handleStartPicking = (row: Requisition) =>
  handleAction(row, 'start-picking', `开始备料 ${row.requisitionNo}?`, '已进入备料状态');

// 物料行摘要 HTML (供调拨/签收富确认弹窗使用) — Rule 2 防呆: 上下文带品名+数量+仓库, 不只给个单号。
//
// 缺陷43 (owner 2026-09-02 演示走查, 同一屏): 11 行里 8 行「× 0kg」占了 73% 篇幅,
// 真正要看的 3 行反而要滚动。⛔ 不删零值行 —— 0 是明确的事实 (#469: 0=这次不领,
// 审计要留痕), 只是不该占主视野。非零行照常逐行列出、排最前; 零值行折进一条可展开
// 摘要。这段 HTML 走 ElMessageBox 的 dangerouslyUseHTMLString, 没有 Vue 事件绑定,
// 折叠交互只能用原生 <details>/<summary> (浏览器原生行为, 不需要 JS)。
function buildItemsSummaryHtml(items: RequisitionItem[], qtyField: 'pickedQty' | 'issuedQty'): string {
  if (items.length === 0) return '<div style="color:#909399">（无物料行）</div>';
  const rowHtml = (it: RequisitionItem) => {
    const name = it.materialName || it.materialTypeId || '-';
    const amount = qty(it[qtyField] ?? it.pickedQty);
    const unit = it.unit || '';
    return `<div>${name} × ${amount}${unit}</div>`;
  };
  const { nonZero, zero } = splitZeroQtyRows(items, (it) => toNumber(it[qtyField] ?? it.pickedQty));
  const nonZeroHtml = nonZero.map(rowHtml).join('');
  const zeroHtml = zero.length > 0
    ? `<details style="margin-top:4px;color:#909399"><summary style="cursor:pointer">另有 ${zero.length} 项本次不领</summary>${zero.map(rowHtml).join('')}</details>`
    : '';
  return `<div style="margin-top:8px;max-height:200px;overflow-y:auto">${nonZeroHtml}${zeroHtml}</div>`;
}

function hasPickedQuantity(items: RequisitionItem[]): boolean {
  return items.some((item) => toNumber(item.pickedQty) > 0);
}

// 辅料自动计算 (owner 2026-09-02 拍板, 逐字引用见 confirmPickingAuxDerive.ts 头部):
// 「原料放最前面填, 辅料基本不用填, 自动计算; 改原料它就重新算。」
//
// productionPlanId → productTypeId 只能现查 (Requisition 实体没有 productTypeId 字段,
// 只有 productionPlanId/productName 快照), 再用 productTypeId 查该产品当前 BOM 的调料配方
// (bomSeasoningApi.getByProduct, 只读, 与「生成」弹窗用的 plannedRawInputKg 后端口径同源:
// FactoryMaterialRequisitionServiceImpl#seasoningRequiredQty 的 dosagePerKgG × 投入kg ÷ 1000)。
async function resolveProductTypeId(planId: string): Promise<string | null> {
  try {
    const res = await get<{ productTypeId?: string | null }>(`/${factoryId.value}/production-plans/${planId}`);
    if (!res.success) return null;
    return (res.data as { productTypeId?: string | null } | undefined)?.productTypeId ?? null;
  } catch {
    return null;
  }
}

async function loadAuxRatios(productionPlanId?: string | null) {
  auxRatioMap.value = {};
  if (!factoryId.value || !productionPlanId) return;
  const productTypeId = await resolveProductTypeId(productionPlanId);
  if (!productTypeId) return;
  try {
    const res = await bomSeasoningApi.getByProduct(factoryId.value, productTypeId);
    if (!res.success || !res.data) return;
    const map: Record<string, number> = {};
    (res.data.seasoningItems || []).forEach((s) => {
      if (!s.materialTypeId) return;
      // 镜像后端 seasoningRequiredQty 口径: COOKING 段且不计入调料成本的行(如老汤)不参与自动计算
      if (s.section === 'COOKING' && s.countInSeasoning === false) return;
      const dosage = Number(s.dosagePerKgG);
      if (Number.isFinite(dosage) && dosage > 0) map[s.materialTypeId] = dosage;
    });
    auxRatioMap.value = map;
  } catch {
    // best-effort: 比例拿不到就退回人工填 (老行为), 不阻塞确认领料主流程
  }
}

// 用 RAW 行的「实际拣货」合计, 实时重算命中比例的辅料行 —— 写路径①(见 PR 说明「AUX 写路径普查」)。
// ⛔ 只接管 materialCategory==='AUXILIARY' 且命中 auxRatioMap 的行: 没有比例数据的辅料 (未在
// BOM 调料表登记) 和 PACKAGING (走的是另一条与 RAW 无关的口径, 没有 dosagePerKgG 这类比例数据,
// 见本文件顶部 import 处的 PR 说明) 维持人工填, 不接管 —— 不编一个没有数据支撑的公式。
function recalcAuxRows() {
  const totalKg = rawPickedTotalKg(confirmRows.value);
  confirmRows.value.forEach((row) => {
    if (row.materialCategory !== 'AUXILIARY') return;
    const ratio = row.materialTypeId ? auxRatioMap.value[row.materialTypeId] : undefined;
    if (ratio === undefined) return;
    row.autoLocked = true;
    // 手改保护 (owner 卡点②): 用户已经动过这一行, recalc 不再覆盖, 直到「恢复自动计算」重置。
    if (row.manuallyTouched) return;
    row.pickedInput = deriveAuxiliaryQty(ratio, totalKg, row.unit);
  });
}

// 输入框 @change —— 写路径②(RAW 行变化触发重算) / 写路径③(用户手改自动计算行, 打上手改标记)。
function handlePickedInputChange(row: ConfirmPickRow) {
  if (row.materialCategory === 'RAW') {
    // 顺序固定: 主料份额先推其余主料 → 再按新的 RAW 合计推辅料 (organizer 2026-09-02)。
    row.manuallyTouched = true;
    autoFillPickedByShare(row);
    recalcAuxRows();
    return;
  }
  if (row.autoLocked) {
    row.manuallyTouched = true;
  }
}

// 「恢复自动计算」—— 写路径④: 清掉手改标记, 立即用当前 RAW 合计重算这一行。
function resetAuxToAuto(row: ConfirmPickRow) {
  row.manuallyTouched = false;
  recalcAuxRows();
}

async function prepareConfirmDialog(data: Requisition) {
  confirmTarget.value = data;
  confirmRows.value = (data.items || []).map((item) => {
    const picked = toNumber(item.pickedQty);
    // Prefill: reuse a saved picked quantity; otherwise default to required quantity for quick operator confirmation.
    // 「用量待定」行 (BOM 只登记配方资格、没有参考用量) 没有可预填的数字 —— 留空并强制仓管称重录入,
    // 预填 0 会让人一路点确认, 结果那味料一点也没调拨过去 (客户表格第 45 行的下游)。
    return {
      ...item,
      pickedInput: picked > 0 ? picked : (isPendingQty(item) ? null : toNumber(item.requiredQty)),
      autoLocked: false,
      manuallyTouched: false,
    };
  });
  confirmDialogVisible.value = true;
  if (data.productionPlanId) {
    planCache.value[data.productionPlanId] = {
      planNumber: data.productionPlanNumber || '原计划已删除/归档',
      productName: data.productName,
    };
  }
  // 比例是异步查回来的; 先把对话框和 RAW 行原样呈现, 比例到了再补算辅料行 (不阻塞打开对话框)。
  await loadAuxRatios(data.productionPlanId);
  recalcAuxRows();
}

// 调拨: 备料仓 → 生产仓, 富确认带品名+数量+仓库路由 (对齐「确认领料」上下文标准, 不是裸单号弹窗)。
async function handleTransfer(row: Requisition) {
  if (submitting.value) return;
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    const items = data.items || [];
    if (!hasPickedQuantity(items)) {
      ElMessage.warning('请先确认领料数量，再调拨到生产仓');
      void prepareConfirmDialog(data);
      return;
    }
    const from = warehouseName(data.sourceWarehouseId);
    const to = warehouseName(data.targetWarehouseId);
    await ElMessageBox.confirm(
      `<div>调拨单号：<b>${data.requisitionNo}</b></div>` +
        `<div style="margin-top:4px">${from} → ${to}</div>` +
        buildItemsSummaryHtml(items, 'pickedQty'),
      '确认调拨到生产仓',
      { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认调拨', cancelButtonText: '取消' },
    );
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/transfer`, {});
    if (res.success) {
      ElMessage.success('已调拨到生产仓');
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

// 签收: 生产仓实收确认, 富确认带品名+数量+仓库路由。
async function handleReceive(row: Requisition) {
  if (submitting.value) return;
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    const items = data.items || [];
    const from = warehouseName(data.sourceWarehouseId);
    const to = warehouseName(data.targetWarehouseId);
    await ElMessageBox.confirm(
      `<div>签收单号：<b>${data.requisitionNo}</b></div>` +
        `<div style="margin-top:4px">${from} → ${to}</div>` +
        buildItemsSummaryHtml(items, 'issuedQty'),
      '确认签收',
      { type: 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '确认签收', cancelButtonText: '取消' },
    );
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${row.id}/receive`, {});
    if (res.success) {
      ElMessage.success('已签收');
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

// 仓管确认领料 (confirm-picking): 逐物料录入实际拣货数量。防呆: 预填需求量 = "要收多少",
// 仓管只需核对确认 (Rule 1 预先显示边界 + Rule 2 上下文带单号/品名)。
async function openConfirmDialog(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    void prepareConfirmDialog(data);
  } catch (e) {
    handleCatchError(e, '加载领料明细失败');
  }
}

async function submitConfirm() {
  if (!confirmTarget.value || submitting.value) return;
  const invalid = confirmRows.value.filter((row) => Number(row.pickedInput) < 0);
  if (invalid.length > 0) {
    ElMessage.warning('拣货数量不能为负数');
    return;
  }
  // 「用量待定」行没有参考数字可对照 —— 留空提交后 issuedQty=null, 调拨时那一味料会被整个跳过
  // (料没搬却推到 TRANSFERRED)。后端 transferToFactory 也拦, 这里先在录入现场说清是哪几味。
  //
  // 🔴 2026-09-02 owner 卡点: 原判据 `!(Number(row.pickedInput) > 0)` 把「显式填 0」
  // 和「没填(null/留空)」当成同一件事拦下来 —— 而这两者语义相反: 0 是「这次一个都不领」
  // 的明确事实, null 才是「我不知道」(正是这道闸原本要防的)。LIUSHANMEN 现场 7 味辅料
  // + L1 纸箱库存全是 0, 用户如实填 0 却被当成"没填"拒绝提交, 领料确认整段卡死。
  // ⇒ 只拦 null/undefined/空串 (真正"没填"), 显式 0 放行。
  //
  // 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」) 追加: 自备料
  // (冰水/水/蒸汽/现场自制高汤) 留空是常态, 不是漏填 —— ⛔ 不扩大这道闸的口径去覆盖它
  // (硬约束: 只在这一处排除自备料, 不弱化对其它行的保护), 而是把它排除在判据之外。
  const missingPending = confirmRows.value.filter(
    (row) => !isSelfSupplied(row) && isPendingQty(row)
      && (row.pickedInput === null || row.pickedInput === undefined
      || (row.pickedInput as unknown) === ''),
  );
  if (missingPending.length > 0) {
    // 🔴 2026-09-02 owner 并发同一 PR 报出的独立缺陷: 用户反复点「确认领料」被这道闸拦回来时,
    // 同一条 sticky (duration:0) 提示不去重地一层层堆, 11 条一起盖住操作区,
    // 用户看不清自己在改什么。⛔ 不改成非 sticky —— 4-位一体规定 error/警示类必须
    // duration:0 + showClose 由用户手动关; 该修的是「堆多层」, 不是「不常驻」。
    // grouping:true 是 Element Plus 内置去重: 相同文案合并成一条并带次数徽标, 不再重复入栈。
    ElMessage({
      message: `原料「${missingPending.map((row) => row.materialName).join('」「')}」按实际领用，`
        + '请称重后录入实际领用数量（这些原料在 BOM 中只登记配方资格、没有参考用量）',
      type: 'warning',
      duration: 0,
      showClose: true,
      grouping: true,
    });
    return;
  }
  try {
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${confirmTarget.value.id}/confirm-picking`, {
      items: confirmRows.value.map((row) => ({ itemId: row.id, pickedQty: row.pickedInput ?? 0 })),
    });
    if (res.success) {
      ElMessage.success('已确认领料，可调拨到生产仓');
      confirmDialogVisible.value = false;
      loadData();
    }
  } catch {
    // interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

async function openCloseDialog(row: Requisition) {
  try {
    const data = await fetchDetail(row);
    if (!data) return;
    closeTarget.value = data;
    closeRows.value = (data.items || []).map((item) => {
      const wastageInput = toNumber(item.wastageQty);
      return {
        ...item,
        wastageInput,
        previewReturnQty: toNumber(item.issuedQty) - toNumber(item.consumedQty) - wastageInput,
      };
    });
    closeDialogVisible.value = true;
  } catch (e) {
    handleCatchError(e, '加载退料预览失败');
  }
}

async function submitClose() {
  if (!closeTarget.value || submitting.value) return;
  const invalidRows = closeRows.value.filter((row) => row.previewReturnQty < 0);
  if (invalidRows.length > 0) {
    showSingletonNotification({
      title: '退料数据异常',
      message: `${invalidRows.map((r) => r.materialName || r.materialTypeId || r.id).join('、')} 的实用量+损耗大于发出量。请修正损耗后再关单。`,
      type: 'error',
      duration: 0,
      showClose: true,
    });
    return;
  }
  try {
    await ElMessageBox.confirm(`确认关单 ${closeTarget.value.requisitionNo} 并执行退料回库?`, '确认关单', { type: 'warning' });
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${closeTarget.value.id}/close`, {
      items: closeRows.value.map((row) => ({ itemId: row.id, wastageQty: row.wastageInput || '0' })),
    });
    if (res.success) {
      ElMessage.success('已关单并执行退料回库');
      closeDialogVisible.value = false;
      loadData();
    }
  } catch {
    // user cancelled or interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}

// 取消物料需求单 — Rule 2 防呆: 弹窗标题带单号; Rule 3: 原因改约束选择, 选"其他"才显文本框。
function openCancelDialog(row: Requisition) {
  cancelTarget.value = row;
  cancelForm.value = { reason: '', otherReason: '' };
  cancelDialogVisible.value = true;
}

async function submitCancel() {
  if (!cancelTarget.value || submitting.value) return;
  if (!cancelForm.value.reason) {
    ElMessage.warning('请选择取消原因');
    return;
  }
  if (cancelForm.value.reason === '其他' && !cancelForm.value.otherReason.trim()) {
    ElMessage.warning('请填写具体原因');
    return;
  }
  const reason = cancelForm.value.reason === '其他' ? cancelForm.value.otherReason.trim() : cancelForm.value.reason;
  try {
    submitting.value = true;
    const res = await put(`/${factoryId.value}/material-requisitions/${cancelTarget.value.id}/cancel`, { reason });
    if (res.success) {
      ElMessage.success('已取消');
      cancelDialogVisible.value = false;
      loadData();
    }
  } catch {
    // interceptor displayed backend error
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>领料单</h2>
        <span class="page-subtitle">领料、调拨、签收、关单退料</span>
      </div>
      <div class="header-actions">
        <el-select v-model="statusFilter" placeholder="状态" clearable class="status-filter" @change="handleStatusChange">
          <el-option label="待备料" value="PENDING" />
          <el-option label="备料中" value="PICKING" />
          <el-option label="已调拨" value="TRANSFERRED" />
          <el-option label="已签收" value="ISSUED" />
          <el-option label="生产中" value="IN_USE" />
          <el-option label="已关单" value="CLOSED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button :icon="Tickets" @click="router.push('/production/material-returns')">退料记录</el-button>
        <el-button :icon="Refresh" @click="loadData">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreateDialog">按计划生成</el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="requisitionNo" label="单号" min-width="150" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type || 'info'" size="small">
              {{ statusMap[row.status]?.text || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生产计划" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ planLabel(row.productionPlanId) }}</template>
        </el-table-column>
        <el-table-column label="物料行" width="90" align="center">
          <template #default="{ row }">{{ (row.items || []).length }}</template>
        </el-table-column>
        <el-table-column prop="requiredDate" label="需料日期" width="120" />
        <el-table-column prop="requestedByName" label="申请人" width="110">
          <template #default="{ row }">{{ row.requestedByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :icon="View" @click="openDetail(row)">详情</el-button>
            <el-button type="info" link size="small" :icon="Printer" :loading="printingIds.has(row.id)" @click="handlePrint(row)">打印</el-button>
            <el-button v-if="row.status === 'PENDING' && canWrite" type="primary" link size="small" @click="handleStartPicking(row)">备料</el-button>
            <el-button v-if="row.status === 'PICKING' && canWrite" type="primary" link size="small" @click="openConfirmDialog(row)">确认领料</el-button>
            <el-button v-if="row.status === 'PICKING' && canWrite" type="primary" link size="small" @click="handleTransfer(row)">调拨</el-button>
            <el-button v-if="row.status === 'TRANSFERRED' && canWrite" type="success" link size="small" @click="handleReceive(row)">签收</el-button>
            <el-button v-if="['ISSUED','IN_USE'].includes(row.status) && canWrite" type="warning" link size="small" @click="openCloseDialog(row)">关单</el-button>
            <el-button v-if="['PENDING','PICKING'].includes(row.status) && canWrite" type="danger" link size="small" @click="openCancelDialog(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.page"
          :page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="按生产计划生成领料单" width="560px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="生产计划" required>
          <el-select
            v-model="createForm.productionPlanId"
            placeholder="搜索产品名 / 计划单号"
            filterable
            :loading="planOptionsLoading"
            style="width: 100%"
          >
            <el-option
              v-for="plan in planOptions"
              :key="plan.id"
              :label="`${plan.productName || '-'} · ${plan.plannedDate || '未定日期'} · ${plan.planNumber || plan.id}`"
              :value="plan.id"
            />
          </el-select>
          <div v-if="!planOptionsLoading && planOptions.length === 0" style="font-size: 12px; color: #f56c6c; margin-top: 6px">
            暂无待生成领料单的生产计划(待执行/进行中)，请先
            <el-button type="primary" link size="small" @click="createDialogVisible = false; router.push('/production/plans')">去创建生产计划</el-button>
          </div>
        </el-form-item>
        <el-form-item label="计划投入原料量">
          <el-input-number
            v-model="createForm.plannedRawInputKg"
            :min="0.001"
            :precision="3"
            :step="1"
            controls-position="right"
            placeholder="选填，单位 kg"
            style="width: 220px"
          />
          <span style="margin-left: 8px; font-size: 12px; color: #909399">kg，选填</span>
          <div style="font-size: 12px; color: #909399; margin-top: 6px">
            这批计划实际领多少原料，由你现场决定；填了才能按配方比例算出辅料/包材需求量，
            留空则辅料/包材需求量显示「按实际领用」。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!createForm.productionPlanId" @click="handleCreate">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailDialogVisible" :title="`领料单 ${detailData?.requisitionNo || ''}`" width="900px" destroy-on-close>
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detailData.requisitionNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusMap[String(detailData.status)]?.type || 'info'" size="small">
              {{ statusMap[String(detailData.status)]?.text || detailData.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="生产计划">{{ planLabel(detailData.productionPlanId) }}</el-descriptions-item>
          <el-descriptions-item label="需料日期">{{ detailData.requiredDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库路由">{{ warehouseName(detailData.sourceWarehouseId) }} → {{ warehouseName(detailData.targetWarehouseId) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detailData.remarks || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="detailData.items || []" border size="small" class="detail-table" max-height="360">
          <el-table-column label="物料" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.materialName }}
              <el-tag v-if="isSelfSupplied(row)" type="info" size="small" effect="plain" style="margin-left: 4px">自备料</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类别" width="90" align="center">
            <template #default="{ row }">{{ materialCategoryLabel(row.materialCategory) }}</template>
          </el-table-column>
          <el-table-column label="需求" width="110" align="right">
            <template #default="{ row }">{{ requiredQtyLabel(row) }}</template>
          </el-table-column>
          <el-table-column prop="issuedQty" label="发出" width="90" align="right" />
          <el-table-column prop="consumedQty" label="实用" width="90" align="right" />
          <el-table-column prop="wastageQty" label="损耗" width="90" align="right" />
          <el-table-column prop="returnedQty" label="退回" width="90" align="right" />
          <el-table-column prop="unit" label="单位" width="70" align="center" />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="confirmDialogVisible" :title="`确认领料 ${confirmTarget?.requisitionNo || ''}`" width="820px" destroy-on-close>
      <el-alert
        title="原料请先称重录入实际拣货数量；辅料按原料合计自动实时计算，无需手填。改了原料，辅料会重新算；要人工改辅料也可以直接改，改后不再被覆盖（可点「恢复自动计算」重置）。确认后可调拨到生产仓。"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-descriptions v-if="confirmTarget" :column="2" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="单号">{{ confirmTarget.requisitionNo }}</el-descriptions-item>
        <el-descriptions-item label="生产计划">{{ planLabel(confirmTarget.productionPlanId) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="confirmRows" border size="small">
        <el-table-column label="物料" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.materialName }}
            <el-tag v-if="isSelfSupplied(row)" type="info" size="small" effect="plain" style="margin-left: 4px">自备料</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需求量" width="130" align="right">
          <template #default="{ row }">{{ requiredQtyLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="实际拣货" width="220" align="right">
          <template #default="{ row }">
            <!-- 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」): 自备料不查库存、
                 不需要称重录入 —— 用一句说明替掉数量输入框, 不让人误以为漏填了。 -->
            <span v-if="isSelfSupplied(row)" class="field-hint">现场取用，无需称重录入</span>
            <template v-else>
              <el-input-number
                v-model="row.pickedInput"
                :min="0"
                :precision="row.materialCategory === 'AUXILIARY' ? 4 : 3"
                :step="0.001"
                controls-position="right"
                class="qty-input"
                data-testid="picked-input"
                :placeholder="row.autoLocked && !row.manuallyTouched ? '按原料自动计算' : undefined"
                @change="handlePickedInputChange(row)"
              />
              <div v-if="row.autoLocked" class="aux-auto-hint">
                <el-tag v-if="!row.manuallyTouched" type="success" size="small" effect="plain">自动计算</el-tag>
                <template v-else>
                  <el-tag type="warning" size="small" effect="plain">手改</el-tag>
                  <el-button link type="primary" size="small" @click="resetAuxToAuto(row)">恢复自动计算</el-button>
                </template>
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
      </el-table>
      <template #footer>
        <el-button @click="confirmDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitConfirm">确认领料</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="closeDialogVisible" :title="`关单退料 ${closeTarget?.requisitionNo || ''}`" width="980px" destroy-on-close>
      <el-table :data="closeRows" border size="small">
        <el-table-column prop="materialName" label="物料" min-width="170" show-overflow-tooltip />
        <el-table-column label="发出" width="100" align="right">
          <template #default="{ row }">{{ qty(row.issuedQty) }}</template>
        </el-table-column>
        <el-table-column label="实用" width="100" align="right">
          <template #default="{ row }">{{ qty(row.consumedQty) }}</template>
        </el-table-column>
        <el-table-column label="损耗" width="160" align="right">
          <template #default="{ row }">
            <el-input-number
              v-model="row.wastageInput"
              :min="0"
              :precision="3"
              :step="0.001"
              controls-position="right"
              class="qty-input"
              @change="recalcRow(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="退回" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ danger: row.previewReturnQty < 0 }">{{ qty(row.previewReturnQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="80" align="center" />
      </el-table>
      <template #footer>
        <el-button @click="closeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitClose">确认关单</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="cancelDialogVisible" :title="`取消领料单 ${cancelTarget?.requisitionNo || ''}`" width="480px" destroy-on-close>
      <el-alert
        title="取消后该需求单不可恢复；已备料/已调拨的物料请另行走退料流程。"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="取消原因" required>
          <el-select v-model="cancelForm.reason" placeholder="请选择取消原因" style="width: 100%">
            <el-option v-for="opt in CANCEL_REASON_OPTIONS" :key="opt" :label="opt" :value="opt" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="cancelForm.reason === '其他'" label="具体原因" required>
          <el-input v-model="cancelForm.otherReason" type="textarea" :rows="3" placeholder="请填写具体取消原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelDialogVisible = false">返回</el-button>
        <el-button type="danger" :loading="submitting" @click="submitCancel">确认取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    color: #1a2332;
    font-size: 20px;
    font-weight: 600;
  }
}

.page-subtitle {
  display: block;
  margin-top: 4px;
  color: #7a8599;
  font-size: 13px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-filter {
  width: 140px;
}

.content-card {
  border-radius: 10px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.detail-table {
  margin-top: 16px;
}

.qty-input {
  width: 132px;
}

.aux-auto-hint {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 4px;
  font-size: 12px;
}

.danger {
  color: #f56c6c;
  font-weight: 600;
}
</style>
