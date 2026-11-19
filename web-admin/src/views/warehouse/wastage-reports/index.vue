<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post } from '@/api/request';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { badgeColor, type ElementTagType } from '@/utils/csBadge';
import type { TableRow } from '@/types/api';
// Rule 5 (fool-proof-design): 报损照片证据改真实拍照/文件上传, 不再要求仓管员手打 URL 粘贴到文本框
// (低技术素养用户根本无法生成一个图片 URL). WastageReport 在后端要求创建时 photoUrls 已非空
// (WastageReportServiceImpl#validatePhotos), 此时报损单实体还不存在, 无法用需要 entityId 的
// AttachmentUploadButton/uploadAndRegister (会注册 Attachment 记录挂在一个还不存在的 entityId 上)。
// 改用底层 getUploadUrl + 直传 OSS (与 uploadAndRegister 内部一致, 跳过 register 步骤), 拿到
// fileUrl 后推入 createForm.photoUrls — 提交时序不变 (仍是 photoUrls: string[] JSON.stringify)。
import { getUploadUrl } from '@/api/attachment';
import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { loadErrorText } from '@/utils/listEmptyState';

/* 组件全部是 Nuxt UI (UTable / UTabs / UModal / UFormField / USelectMenu / UInput / UTextarea / UBadge / UPagination) + --cs-*；弹窗里不放 Element 控件，照片缩略图用原生 img + 链接（取代 el-image 预览）。提示仍用 ElMessage。 */

// ────────────────────────────────────────────────────────────────────────────
// Auth / permissions
// ────────────────────────────────────────────────────────────────────────────
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));

// ────────────────────────────────────────────────────────────────────────────
// Tab: WAREHOUSE / FACTORY
// ────────────────────────────────────────────────────────────────────────────
const activeTab = ref<'WAREHOUSE' | 'FACTORY'>('WAREHOUSE');
const tabItems: TabsItem[] = [
  { label: '仓库报损 → 财务审批', value: 'WAREHOUSE' },
  { label: '生产报损 → 厂长审批', value: 'FACTORY' },
];
const tabModel = computed({
  get: () => activeTab.value,
  set: (v: string) => { activeTab.value = v as 'WAREHOUSE' | 'FACTORY'; handleTabChange(); },
});
// 新建弹窗里的「报损类型」只切类型，不重新加载列表（与原 el-radio-group 绑 activeTab 行为一致）
function setTrack(v: 'WAREHOUSE' | 'FACTORY') { activeTab.value = v; }

// ────────────────────────────────────────────────────────────────────────────
// Status / reason maps
// ────────────────────────────────────────────────────────────────────────────
const statusMap: Record<string, { label: string; type: string }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING_APPROVAL: { label: '待审批', type: 'warning' },
  APPROVED: { label: '已审批', type: 'success' },
  REJECTED: { label: '已驳回', type: 'danger' },
  APPLIED: { label: '已应用', type: 'success' },
};

const reasonOptions = [
  { value: 'EXPIRED', label: '过期/变质' },
  { value: 'DAMAGED', label: '物理损坏' },
  { value: 'CONTAMINATED', label: '污染/不合格' },
  { value: 'THEFT', label: '丢失/盗失' },
  { value: 'PRODUCTION_WASTE', label: '生产损耗' },
  { value: 'OTHER', label: '其他' },
];

function reasonLabel(val: string) {
  return reasonOptions.find((o) => o.value === val)?.label || val;
}

// ────────────────────────────────────────────────────────────────────────────
// List state
// ────────────────────────────────────────────────────────────────────────────
const loading = ref(false);
const loadError = ref('');
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const statusFilter = ref('');
const UNSETTLED_BATCH_QUERY_SIZE = 50;

const R = { class: { th: 'text-right', td: 'text-right' } };
const columns: TableColumn<TableRow>[] = [
  { accessorKey: 'reportNo', header: '报损单号' },
  { id: 'warehouse', header: '仓库' },
  { id: 'batch', header: '批次' },
  { id: 'wastageQty', header: '报损数量', meta: R },
  { id: 'reason', header: '报损原因' },
  { id: 'status', header: '状态' },
  { id: 'submitter', header: '提交人' },
  { id: 'createdAt', header: '创建时间' },
  { id: 'actions', header: '操作', meta: R },
];
// 状态筛选：Nuxt UI 选项值不能是空串，用 ALL 表示「全部状态」，回写 statusFilter 仍是 ''
const statusItems = [{ label: '全部状态', value: 'ALL' }, ...Object.entries(statusMap).map(([k, v]) => ({ label: v.label, value: k }))];
const statusModel = computed({
  get: () => statusFilter.value || 'ALL',
  set: (v: string) => { statusFilter.value = v === 'ALL' ? '' : v; handleFilterChange(); },
});
const pageModel = computed({
  get: () => pagination.value.page,
  set: (v: number) => handlePageChange(v),
});
const photoInput = ref<HTMLInputElement | null>(null);

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  loadError.value = '';
  try {
    const params: Record<string, unknown> = {
      page: pagination.value.page - 1,  // backend is 0-based; pagination.page is 1-based
      size: pagination.value.size,
      trackType: activeTab.value,
    };
    if (statusFilter.value) params.status = statusFilter.value;
    const res = await get(`/${factoryId.value}/wastage-reports`, { params });
    if (res.success && res.data) {
      const d = res.data;
      tableData.value = d.content || d || [];
      pagination.value.total = d.totalElements ?? tableData.value.length;
    } else {
      tableData.value = [];
      loadError.value = res.message || '加载报损记录失败';
    }
  } catch (e) {
    tableData.value = [];
    loadError.value = loadErrorText(e, '加载报损记录失败');
    ElMessage({ message: String((e as Error).message || '加载失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    loading.value = false;
  }
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleTabChange() {
  pagination.value.page = 1;
  statusFilter.value = '';
  loadData();
}

function clearStatusFilter() {
  statusFilter.value = '';
  handleFilterChange();
}

function handleFilterChange() {
  pagination.value.page = 1;
  loadData();
}

// ────────────────────────────────────────────────────────────────────────────
// Warehouses dropdown
// ────────────────────────────────────────────────────────────────────────────
const warehouses = ref<TableRow[]>([]);
const warehouseItems = computed(() => warehouses.value.map((w) => ({ label: String(w.name), value: String(w.id) })));

async function loadWarehouses() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/factory/warehouses`);
    if (res.success && Array.isArray(res.data)) warehouses.value = res.data;
  } catch { /* silent */ }
}

// P2-7 fix: 生产报损(FACTORY trackType)行没有仓库(报损发生在生产环节, 非仓库),
// row.warehouseId 是 null/undefined。之前 template 用 String(row.warehouseId) 预先
// 转成字面量字符串 "null" 再传入本函数, find 找不到匹配后原样返回 "null" 字符串,
// 表格仓库列显示刺眼的字面 "null" 而非空态占位。改为接受 nullable 输入, 空值/无匹配
// 一律显示 "—"（不显示原始 ID 或 "null"/"undefined" 字面量）。
function warehouseName(id: string | number | null | undefined): string {
  if (id == null || id === '') return '—';
  const w = warehouses.value.find((x) => String(x.id) === String(id));
  if (w) return String(w.name || id);
  return String(id);
}

// ────────────────────────────────────────────────────────────────────────────
// 批次下拉 (防呆: 选批次 → 报损数量绑定该批次可用量, 与报废处置一致)。
//
// ⚠️ 批次为必选, 不是选填: 后端 CreateWastageReportRequest.materialBatchId 是
// @NotBlank, DB 列 wastage_reports.material_batch_id 也是 NOT NULL (V20261010_24)。
// 报损服务 (WastageReportServiceImpl) 全程按单一批次扣减 (applyWastageToInventory),
// 没有跨批次 FEFO 物料级扣减路径。之前 UI 写 "留空=物料级报损" 但后端从未支持过 —
// 仓管员照着提示留空批次 100% 命中 400 "批次 ID 不能为空"。
// 修复: UI 诚实化为必选, 不再宣传后端不支持的路径 (fool-proof-design.md Rule 1/2)。
// 若未来要支持真物料级报损 (跨批次 FEFO 自动扣减), 需要: ①Flyway 迁移放开
// material_batch_id NOT NULL ②前端加物料类型选择器(当前无) ③后端仿
// BatchConsumptionServiceImpl.adjustConsumption 写跨批次 FEFO 扣减 —
// 属于新功能设计, 不在本次缺陷修复范围内。
// ────────────────────────────────────────────────────────────────────────────
const batches = ref<TableRow[]>([]);

// fool-proof-design Rule 1 (预先显示边界, 2026-07-05): batchId → 未小结报工消耗量 (原生单位)。
// 半成品/原料报工写「待小结」消耗但账面 usedQuantity 要到小结才扣 — 期间 availableQuantity 是
// stale-high (账面未扣, 货已投产)。此 map 让仓管员在报损前就看到真实「货架实物」而非账面数。
// honest-null: 加载失败/物料无待扣 → map 缺该 key → 前端按 0 处理 (退化为原有 availableQuantity 显示,
// 不阻断/不改变任何已存在的 selectedWastageMax gate)。
const unsettledByBatch = ref<Record<string, number>>({});

async function loadBatches() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/material-batches`, {
      params: { status: 'AVAILABLE', size: 200 },
    });
    if (res.success && res.data) batches.value = res.data.content || res.data || [];
    await loadUnsettledConsumption();
  } catch { /* silent — 加载失败时批次下拉为空, 提交仍会被 submitCreate 的必选校验挡住 */ }
}

// 🟢 PURE DISPLAY: 批量查已加载批次的未小结报工消耗量 (一次性 bulk 查询, 非 N+1)。
// 只读提示, 不改变 selectedWastageMax 的 gate 逻辑 (Steve 已确认现有 409 保护正确,
// 本次只加「货架实物」提示, 不动任何校验/gate)。
async function loadUnsettledConsumption() {
  if (!factoryId.value || batches.value.length === 0) return;
  try {
    const batchIds = batches.value.map((b) => String(b.id)).filter(Boolean);
    if (batchIds.length === 0) return;
    const merged: Record<string, number> = {};
    for (let i = 0; i < batchIds.length; i += UNSETTLED_BATCH_QUERY_SIZE) {
      const chunk = batchIds.slice(i, i + UNSETTLED_BATCH_QUERY_SIZE);
      const res = await get(`/${factoryId.value}/material-batches/unsettled-consumption`, {
        params: { batchIds: chunk.join(',') },
      });
      if (res.success && res.data) Object.assign(merged, res.data as Record<string, number>);
    }
    unsettledByBatch.value = merged;
  } catch { /* silent — 加载失败时货架实物提示不显示, 仍显示原 availableQuantity, 不阻断报损 */ }
}

// 按所选仓库过滤批次 (仓库未选则显全部)
const batchItems = computed(() => warehouseBatches.value.map((b) => ({
  label: `${b.batchNumber} - ${b.materialName || b.materialTypeName || '物料'} (可用 ${b.currentQuantity ?? b.remainingQuantity ?? 0})`,
  value: String(b.id),
})));
const warehouseBatches = computed<TableRow[]>(() => {
  const wid = createForm.value.warehouseId;
  if (!wid) return batches.value;
  return batches.value.filter((b) => String(b.warehouseId) === String(wid));
});

// Bug 3 fix: 列表"批次"列之前直接渲染 row.materialBatchId (裸 GUID)。
// 复用 create dialog 已加载的 batches (AVAILABLE 批次) 解析显示名 "批次号 - 物料名"。
// 注意: 已耗尽/非 AVAILABLE 批次可能不在 batches 里 (loadBatches 只拉 AVAILABLE) —
// 找不到时诚实回退显示原始 ID, 而不是静默隐藏。
function batchLabel(id: unknown): string {
  const idStr = id == null ? '' : String(id);
  if (!idStr) return '—';
  const b = batches.value.find((x) => String(x.id) === idStr);
  if (!b) return idStr;
  const name = b.materialName || b.materialTypeName || '物料';
  return `${b.batchNumber || idStr} - ${name}`;
}

// 选中批次的可用量 (currentQuantity 优先); 批次必选, 未选时 null 仅用于禁止提交前的兜底态
const selectedWastageMax = computed<number | null>(() => {
  const b = batches.value.find((x) => String(x.id) === String(createForm.value.materialBatchId));
  if (!b) return null;
  const avail = (b.currentQuantity ?? b.remainingQuantity ?? b.receiptQuantity) as number | undefined;
  return avail != null ? Number(avail) : null;
});

// fool-proof-design Rule 1: 选中批次的「货架实物」= 可用量 − 未小结报工消耗 (clamp ≥ 0)。
// 与 selectedWastageMax 一致的选批次口径, 但恒 ≤ selectedWastageMax (纯提示, 不改 :max gate)。
// null = 未选批次 或 无未小结消耗记录 (与可用量相等, 无需额外提示)。
const selectedUnsettled = computed<number>(() => {
  const id = String(createForm.value.materialBatchId || '');
  if (!id) return 0;
  const v = unsettledByBatch.value[id];
  return v != null ? Number(v) : 0;
});
const selectedPhysicalAvailable = computed<number | null>(() => {
  if (selectedWastageMax.value == null) return null;
  const shelf = selectedWastageMax.value - selectedUnsettled.value;
  return shelf < 0 ? 0 : shelf;
});

// ────────────────────────────────────────────────────────────────────────────
// Create wastage report dialog
// ────────────────────────────────────────────────────────────────────────────
const createDialogVisible = ref(false);
const createLoading = ref(false);
const createForm = ref({
  warehouseId: '',
  materialBatchId: '',
  rawMaterialTypeId: '',
  wastageQty: null as number | null,
  wastageReason: '',
  reasonDetail: '',
  photoUrls: [] as string[],
  notes: '',
});

// 报损照片 — 真实拍照/文件上传 (Rule 5), 替代原 URL 粘贴文本框.
const photoUploading = ref(false);

function beforePhotoUpload(file: File): boolean {
  const maxSize = 20 * 1024 * 1024;
  if (file.size > maxSize) {
    ElMessage.error('照片超出上限 20MB');
    return false;
  }
  return true;
}

async function handlePhotoUpload(opts: { file: File }): Promise<void> {
  photoUploading.value = true;
  try {
    const urlResp = await getUploadUrl(opts.file.name, opts.file.type, factoryId.value);
    const { uploadUrl, fileUrl } = urlResp.data;
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': opts.file.type },
      body: opts.file,
    });
    if (!putRes.ok) throw new Error(`上传失败 HTTP ${putRes.status}`);
    createForm.value.photoUrls.push(fileUrl);
    ElMessage.success('照片上传成功');
  } catch (e) {
    ElMessage.error(String((e as Error).message || '照片上传失败'));
  } finally {
    photoUploading.value = false;
  }
}

async function onPhotoChosen(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file || !beforePhotoUpload(file)) return;
  await handlePhotoUpload({ file });
}

function removePhoto(idx: number) {
  createForm.value.photoUrls.splice(idx, 1);
}

function openCreateDialog() {
  createForm.value = {
    warehouseId: '',
    materialBatchId: '',
    rawMaterialTypeId: '',
    wastageQty: null,
    wastageReason: '',
    reasonDetail: '',
    photoUrls: [],
    notes: '',
  };
  createDialogVisible.value = true;
}

async function submitCreate() {
  const form = createForm.value;
  if (!form.warehouseId) {
    ElMessage({ message: '请选择仓库', type: 'warning', duration: 3000 });
    return;
  }
  // 批次必选 (后端 @NotBlank + DB NOT NULL, 服务不支持跨批次物料级扣减 — 见上方批次下拉注释)
  if (!form.materialBatchId) {
    ElMessage({ message: '请选择批次（报损须精确到批次，用于扣减对应库存与核算成本）', type: 'warning', duration: 0, showClose: true });
    return;
  }
  if (!form.wastageQty || form.wastageQty <= 0) {
    ElMessage({ message: '请填写报损数量', type: 'warning', duration: 3000 });
    return;
  }
  // 防呆: 报损数量不可超过所选批次可用量 (与报废处置一致)
  if (selectedWastageMax.value != null && form.wastageQty > selectedWastageMax.value) {
    ElMessage({ message: `报损数量不能超过批次可用量 ${selectedWastageMax.value}`, type: 'warning', duration: 0, showClose: true });
    return;
  }
  if (!form.wastageReason) {
    ElMessage({ message: '请选择报损原因', type: 'warning', duration: 3000 });
    return;
  }
  if (form.photoUrls.length === 0) {
    ElMessage({ message: '至少需要拍照/上传1张照片证据', type: 'warning', duration: 3000 });
    return;
  }
  createLoading.value = true;
  try {
    const payload = {
      trackType: activeTab.value,
      warehouseId: form.warehouseId,
      materialBatchId: form.materialBatchId || undefined,
      rawMaterialTypeId: form.rawMaterialTypeId || undefined,
      wastageQty: form.wastageQty,
      wastageReason: form.wastageReason,
      reasonDetail: form.wastageReason === 'OTHER' ? form.reasonDetail : undefined,
      // P0 fix: backend CreateWastageReportRequest.photoUrls is a `String` field
      // (JSON array string, e.g. '["url1","url2"]' — see WastageReport entity /
      // WastageReportServiceImpl#validatePhotos which re-parses it as JSON text).
      // Sending a raw JS array here fails Jackson deserialization with
      // "字段类型不正确，期望 String" and the create ALWAYS 400s — 报损 was 100%
      // unusable via web-admin. Must JSON.stringify before sending.
      photoUrls: JSON.stringify(form.photoUrls),
      notes: form.notes || undefined,
    };
    const res = await post(`/${factoryId.value}/wastage-reports`, payload);
    if (res.success) {
      ElMessage({ message: '报损记录已创建（草稿）', type: 'success', duration: 3000 });
      createDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '创建失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '创建失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    createLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Submit for approval
// ────────────────────────────────────────────────────────────────────────────
const submitLoading = ref(false);

async function submitForApproval(row: TableRow) {
  submitLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${row.id}/submit`, {});
    if (res.success) {
      ElMessage({ message: '已提交审批', type: 'success', duration: 3000 });
      loadData();
    } else {
      ElMessage({ message: res.message || '提交失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '提交失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    submitLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Approve dialog
// ────────────────────────────────────────────────────────────────────────────
const approveDialogVisible = ref(false);
const approveLoading = ref(false);
const approveRow = ref<TableRow | null>(null);
const approveNote = ref('');

function openApproveDialog(row: TableRow) {
  approveRow.value = row;
  approveNote.value = '';
  approveDialogVisible.value = true;
}

async function submitApprove() {
  if (!approveRow.value) return;
  approveLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${approveRow.value.id}/approve`, { notes: approveNote.value });
    if (res.success) {
      ElMessage({ message: '审批通过', type: 'success', duration: 3000 });
      approveDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '审批失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '审批失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    approveLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Reject dialog
// ────────────────────────────────────────────────────────────────────────────
const rejectDialogVisible = ref(false);
const rejectLoading = ref(false);
const rejectRow = ref<TableRow | null>(null);
const rejectReasonSelect = ref('');
const rejectReasonOther = ref('');

const rejectReasonOptions = [
  { value: 'PHOTO_MISSING', label: '照片证据不足' },
  { value: 'QTY_MISMATCH', label: '数量与实物不符' },
  { value: 'REASON_INVALID', label: '报损原因不充分' },
  { value: 'WRONG_BATCH', label: '批次信息有误' },
  { value: 'OTHER', label: '其他' },
];

function openRejectDialog(row: TableRow) {
  rejectRow.value = row;
  rejectReasonSelect.value = '';
  rejectReasonOther.value = '';
  rejectDialogVisible.value = true;
}

async function submitReject() {
  if (!rejectRow.value) return;
  const reason = rejectReasonSelect.value === 'OTHER'
    ? rejectReasonOther.value.trim()
    : rejectReasonOptions.find((o) => o.value === rejectReasonSelect.value)?.label || '';
  if (!reason) {
    ElMessage({ message: '请选择驳回原因', type: 'warning', duration: 3000 });
    return;
  }
  rejectLoading.value = true;
  try {
    const res = await post(`/${factoryId.value}/wastage-reports/${rejectRow.value.id}/reject`, { reason });
    if (res.success) {
      ElMessage({ message: '已驳回', type: 'success', duration: 3000 });
      rejectDialogVisible.value = false;
      loadData();
    } else {
      ElMessage({ message: res.message || '驳回失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (e) {
    ElMessage({ message: String((e as Error).message || '驳回失败'), type: 'error', duration: 0, showClose: true });
  } finally {
    rejectLoading.value = false;
  }
}

// ────────────────────────────────────────────────────────────────────────────
// Photo viewer dialog
// ────────────────────────────────────────────────────────────────────────────
const photoViewerVisible = ref(false);
const viewingPhotos = ref<string[]>([]);

/**
 * WastageReportDTO.photoUrls is serialized as a JSON string by the backend
 * (e.g., '["url1","url2"]'). Normalize to string[] regardless of whether the
 * value arrives as a parsed array (unlikely) or a raw JSON string.
 */
function parsePhotoUrlsFromRow(raw: unknown): string[] {
  if (Array.isArray(raw)) return (raw as unknown[]).map(String);
  if (typeof raw === 'string' && raw.trim().startsWith('[')) {
    try { return JSON.parse(raw) as string[]; } catch { /* fall through */ }
  }
  if (typeof raw === 'string' && raw.length > 0) return [raw];
  return [];
}

function openPhotoViewer(row: TableRow) {
  const urls = parsePhotoUrlsFromRow(row.photoUrls);
  if (urls.length === 0) {
    ElMessage({ message: '该记录暂无照片', type: 'info', duration: 3000 });
    return;
  }
  viewingPhotos.value = urls;
  photoViewerVisible.value = true;
}

// ────────────────────────────────────────────────────────────────────────────
// Init
// ────────────────────────────────────────────────────────────────────────────
onMounted(async () => {
  await loadWarehouses();
  await loadBatches();
  await loadData();
});
</script>

<template>
  <div class="page-container" data-testid="wastage-reports">
    <div class="cs-page-head">
      <h2>报损管理</h2>
      <div class="header-actions">
        <USelectMenu
          v-model="statusModel"
          value-key="value"
          :items="statusItems"
          :search-input="false"
          class="w-36"
          data-testid="wastage-status-filter"
        />
        <UButton icon="i-lucide-refresh-cw" color="neutral" variant="outline" :loading="loading" @click="loadData">刷新</UButton>
        <UButton v-if="canWrite" icon="i-lucide-plus" data-testid="wastage-create" @click="openCreateDialog">新建报损</UButton>
      </div>
    </div>

    <UAlert class="mb-3" color="info" variant="subtle" icon="i-lucide-info" title="报损出库将计入损耗成本，审批通过并应用后自动扣减库存。" />

    <!-- Dual-track tabs -->
    <section class="cs-card">
      <UTabs v-model="tabModel" :items="tabItems" :content="false" variant="link" class="px-4" data-testid="wastage-tabs" />

      <UTable :data="tableData" :columns="columns" :loading="loading" class="cs-table" data-testid="wastage-table">
        <template #empty>
          <ListEmptyExplain
            :load-error="loadError"
            :filtered="!!statusFilter"
            :empty-title="activeTab === 'WAREHOUSE' ? '还没有仓库报损记录' : '还没有生产报损记录'"
            empty-hint="库存破损、过期或生产损耗需要核销时，点右上角「新建报损」登记；审批通过并应用后才会扣减库存、计入损耗成本。"
            @retry="loadData"
            @clear-filter="clearStatusFilter"
          />
        </template>
        <template #warehouse-cell="{ row }">{{ warehouseName(row.original.warehouseId) }}</template>
        <template #batch-cell="{ row }">{{ batchLabel(row.original.materialBatchId) }}</template>
        <template #wastageQty-cell="{ row }"><span class="num">{{ row.original.wastageQty }}</span></template>
        <template #reason-cell="{ row }">{{ reasonLabel(String(row.original.wastageReason)) }}</template>
        <template #status-cell="{ row }">
          <UBadge :color="badgeColor(statusMap[String(row.original.status)]?.type as ElementTagType)" variant="soft">
            {{ statusMap[String(row.original.status)]?.label || row.original.status }}
          </UBadge>
        </template>
        <!-- fool-proof-design Rule 2: 后端已批量解析 submittedByName (userId→姓名), 不再显裸 userId. -->
        <template #submitter-cell="{ row }">{{ row.original.submittedByName || row.original.submittedBy || '—' }}</template>
        <template #createdAt-cell="{ row }">
          {{ row.original.createdAt ? String(row.original.createdAt).replace('T', ' ').slice(0, 16) : '—' }}
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton size="xs" variant="link" color="neutral" icon="i-lucide-image" @click="openPhotoViewer(row.original)">照片</UButton>
            <UButton
              v-if="canWrite && row.original.status === 'DRAFT'"
              size="xs"
              variant="link"
              color="warning"
              :loading="submitLoading"
              @click="submitForApproval(row.original)"
            >提交审批</UButton>
            <UButton
              v-if="canWrite && row.original.status === 'PENDING_APPROVAL'"
              size="xs"
              variant="link"
              color="success"
              icon="i-lucide-check"
              @click="openApproveDialog(row.original)"
            >审批</UButton>
            <UButton
              v-if="canWrite && row.original.status === 'PENDING_APPROVAL'"
              size="xs"
              variant="link"
              color="error"
              icon="i-lucide-x"
              @click="openRejectDialog(row.original)"
            >驳回</UButton>
          </div>
        </template>
      </UTable>

      <div class="pager">
        <span class="count">共 {{ pagination.total }} 条</span>
        <UPagination v-model:page="pageModel" :items-per-page="pagination.size" :total="pagination.total" show-edges :sibling-count="1" />
      </div>
    </section>

    <!-- ──────────────── Create Dialog ──────────────── -->
    <UModal v-model:open="createDialogVisible" title="新建报损记录" description="报损出库计入损耗成本" :ui="{ content: 'max-w-[560px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <UAlert color="warning" variant="subtle" icon="i-lucide-triangle-alert" title="报损出库计入损耗成本。至少上传1张照片证据。" class="mb-3" />
        <div class="form">
          <UFormField label="报损类型">
            <div class="seg">
              <UButton value="WAREHOUSE" size="sm" :color="activeTab === 'WAREHOUSE' ? 'primary' : 'neutral'" :variant="activeTab === 'WAREHOUSE' ? 'solid' : 'outline'" @click="setTrack('WAREHOUSE')">仓库报损</UButton>
              <UButton value="FACTORY" size="sm" :color="activeTab === 'FACTORY' ? 'primary' : 'neutral'" :variant="activeTab === 'FACTORY' ? 'solid' : 'outline'" @click="setTrack('FACTORY')">生产报损</UButton>
              <span class="hint">{{ activeTab === 'WAREHOUSE' ? '财务审批' : '厂长审批' }}</span>
            </div>
          </UFormField>
          <UFormField label="仓库" required>
            <USelectMenu v-model="createForm.warehouseId" value-key="value" :items="warehouseItems" placeholder="选择仓库" class="w-full" data-testid="wastage-warehouse" @update:model-value="createForm.materialBatchId = ''" />
          </UFormField>
          <UFormField label="批次" required>
            <USelectMenu v-model="createForm.materialBatchId" value-key="value" :items="batchItems" placeholder="请选择批次（报损须精确到批次）" class="w-full" data-testid="wastage-batch" />
          </UFormField>
          <UFormField label="报损数量" required>
            <UInput
              v-model.number="createForm.wastageQty"
              type="number"
              :min="0.001"
              :max="selectedWastageMax ?? undefined"
              step="0.001"
              placeholder="数量"
              class="w-48"
              data-testid="wastage-qty"
            />
            <div v-if="selectedWastageMax != null" class="hint-line">
              该批次可用量 {{ selectedWastageMax }}，报损数量不可超过此值
            </div>
            <!-- fool-proof-design Rule 1 (预先显示边界, 2026-07-05): 纯提示, 不改上面的 :max gate。
                 只在有未小结生产占用 (货架实物 < 可用量) 时才显示, 避免无意义噪音。 -->
            <div v-if="selectedPhysicalAvailable != null && selectedUnsettled > 0" class="hint-line warn">
              可用(货架实物) {{ selectedPhysicalAvailable }}：可用库存 {{ selectedWastageMax }} − 生产已占用（未小结）{{ selectedUnsettled }}
            </div>
          </UFormField>
          <UFormField label="报损原因" required>
            <USelectMenu v-model="createForm.wastageReason" value-key="value" :items="reasonOptions" :search-input="false" placeholder="选择原因" class="w-full" data-testid="wastage-reason" />
          </UFormField>
          <UFormField v-if="createForm.wastageReason === 'OTHER'" label="补充说明" required>
            <UTextarea v-model="createForm.reasonDetail" :rows="2" placeholder="请填写具体原因" class="w-full" />
          </UFormField>
          <UFormField label="照片证据" required>
            <div class="w-full">
              <input ref="photoInput" type="file" accept="image/*" hidden data-testid="wastage-photo-input" @change="onPhotoChosen">
              <UButton icon="i-lucide-paperclip" :loading="photoUploading" :disabled="photoUploading" @click="photoInput?.click()">拍照 / 上传照片</UButton>
              <div v-if="createForm.photoUrls.length" class="thumbs">
                <div v-for="(url, i) in createForm.photoUrls" :key="url + i" class="thumb">
                  <a :href="url" target="_blank" rel="noopener"><img :src="url" alt="报损照片"></a>
                  <button type="button" class="thumb-x" aria-label="删除照片" @click="removePhoto(i)">
                    <UIcon name="i-lucide-circle-x" />
                  </button>
                </div>
              </div>
              <div class="hint-line">已上传 {{ createForm.photoUrls.length }} 张（至少1张）</div>
            </div>
          </UFormField>
          <UFormField label="备注">
            <UTextarea v-model="createForm.notes" :rows="2" placeholder="可选" class="w-full" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createDialogVisible = false">取消</UButton>
        <UButton :loading="createLoading" data-testid="wastage-create-submit" @click="submitCreate">保存草稿</UButton>
      </template>
    </UModal>

    <!-- ──────────────── Approve Dialog ──────────────── -->
    <UModal v-model:open="approveDialogVisible" title="审批报损申请" description="核对报损信息后审批" :ui="{ content: 'max-w-[520px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <template v-if="approveRow">
          <dl class="summary">
            <div><dt>报损单号</dt><dd>{{ approveRow.reportNo }}</dd></div>
            <div><dt>审批路径</dt><dd><UBadge color="neutral" variant="soft">{{ activeTab === 'WAREHOUSE' ? '仓库报损 → 财务审批' : '生产报损 → 厂长审批' }}</UBadge></dd></div>
            <div><dt>仓库</dt><dd>{{ warehouseName(approveRow?.warehouseId) }}</dd></div>
            <div><dt>报损数量</dt><dd class="num">{{ approveRow.wastageQty }}</dd></div>
            <div><dt>报损原因</dt><dd>{{ reasonLabel(String(approveRow.wastageReason)) }}</dd></div>
            <div><dt>提交人</dt><dd>{{ approveRow.submittedByName || approveRow.submittedBy || '—' }}</dd></div>
          </dl>

          <!-- Photo thumbnails: photoUrls is a JSON string from the backend DTO -->
          <div v-if="parsePhotoUrlsFromRow(approveRow.photoUrls).length > 0" class="mt-3">
            <div class="hint-line">照片证据：</div>
            <div class="thumbs">
              <a v-for="(url, i) in parsePhotoUrlsFromRow(approveRow.photoUrls)" :key="i" :href="url" target="_blank" rel="noopener" class="thumb big">
                <img :src="url" alt="报损照片">
              </a>
            </div>
          </div>

          <UAlert color="warning" variant="subtle" icon="i-lucide-triangle-alert" title="审批通过后，报损数量将计入损耗成本并扣减对应库存。" class="my-3" />

          <UFormField label="审批备注">
            <UTextarea v-model="approveNote" :rows="2" placeholder="可选" class="w-full" />
          </UFormField>
        </template>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="approveDialogVisible = false">取消</UButton>
        <UButton color="success" :loading="approveLoading" @click="submitApprove">确认审批通过</UButton>
      </template>
    </UModal>

    <!-- ──────────────── Reject Dialog ──────────────── -->
    <UModal v-model:open="rejectDialogVisible" title="驳回报损申请" description="选择驳回原因" :ui="{ content: 'max-w-[480px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <template v-if="rejectRow">
          <dl class="summary">
            <div><dt>报损单号</dt><dd>{{ rejectRow.reportNo }}</dd></div>
            <div><dt>仓库</dt><dd>{{ warehouseName(rejectRow?.warehouseId) }}</dd></div>
            <div><dt>报损数量</dt><dd class="num">{{ rejectRow.wastageQty }}</dd></div>
            <div><dt>原因</dt><dd>{{ reasonLabel(String(rejectRow.wastageReason)) }}</dd></div>
          </dl>
          <div class="form mt-3">
            <UFormField label="驳回原因" required>
              <USelectMenu v-model="rejectReasonSelect" value-key="value" :items="rejectReasonOptions" :search-input="false" placeholder="选择驳回原因" class="w-full" data-testid="wastage-reject-reason" />
            </UFormField>
            <UFormField v-if="rejectReasonSelect === 'OTHER'" label="补充说明" required>
              <UTextarea v-model="rejectReasonOther" :rows="2" placeholder="请填写具体原因" class="w-full" />
            </UFormField>
          </div>
        </template>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="rejectDialogVisible = false">取消</UButton>
        <UButton color="error" :loading="rejectLoading" @click="submitReject">确认驳回</UButton>
      </template>
    </UModal>

    <!-- ──────────────── Photo Viewer Dialog ──────────────── -->
    <UModal v-model:open="photoViewerVisible" title="照片证据查看" description="点击图片在新窗口查看原图" :ui="{ content: 'max-w-[640px]', description: 'sr-only', footer: 'justify-end' }">
      <template #body>
        <div class="thumbs center">
          <a v-for="(url, i) in viewingPhotos" :key="i" :href="url" target="_blank" rel="noopener" class="thumb viewer">
            <img :src="url" alt="报损照片">
          </a>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="photoViewerVisible = false">关闭</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page-container { padding: var(--cs-space-4); min-height: 100%; }
.header-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
.row-actions { display: flex; justify-content: flex-end; gap: var(--cs-space-1); }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.seg { display: flex; gap: var(--cs-space-2); align-items: center; }
.hint { color: var(--cs-muted); font-size: var(--cs-font-small); }
.hint-line { font-size: var(--cs-font-small); color: var(--cs-muted); margin-top: 4px; }
.hint-line.warn { color: var(--cs-warning); }
.thumbs { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); margin-top: var(--cs-space-2); }
.thumbs.center { justify-content: center; gap: var(--cs-space-3); }
.thumb { position: relative; display: block; width: 72px; height: 72px; }
.thumb.big { width: 80px; height: 80px; }
.thumb.viewer { width: 180px; height: 180px; }
.thumb img { width: 100%; height: 100%; object-fit: cover; border-radius: var(--cs-radius-sm); border: 1px solid var(--cs-border); }
.thumb-x { position: absolute; top: -6px; right: -6px; display: flex; background: var(--cs-surface); border-radius: 50%; color: var(--cs-danger); font-size: 18px; }
.summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--cs-space-3); margin: 0; padding: var(--cs-space-3) var(--cs-space-4); background: var(--cs-surface-subtle); border: 1px solid var(--cs-border); border-radius: var(--cs-radius-sm); }
.summary dt { font-size: var(--cs-font-small); color: var(--cs-muted); }
.summary dd { margin: 2px 0 0; color: var(--cs-text); }
</style>
