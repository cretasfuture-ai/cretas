<script setup lang="ts">
import { CompatDatePicker, CompatDescriptions, CompatDescriptionsItem, CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import ListEmptyExplain from '@/components/common/ListEmptyExplain.vue';
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { displayUnit } from '@/utils/unitPricing';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put } from '@/api/request';
import { listManufacturers, type ManufacturerRegistry } from '@/api/manufacturer';

import CanvasAwareWrapper from '@/components/canvas/CanvasAwareWrapper.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import { Search, Refresh } from '@element-plus/icons-vue';
import { formatDateTimeCell, fmtQty, formatAmount } from '@/utils/tableFormatters';
import { batchStatusDisplay, formatDateTimeMinute, isDepletedAvailableBatch } from './batchDisplay';
import { enumLabel, MATERIAL_BATCH_STATUS_LABELS } from '@/utils/enumDisplay';
import type { FormInstance } from 'element-plus';
// The synchronous stock-price notice is covered by the existing scheduler
// re-entry regression. Keep that call path for the compatibility pilot.
import { ElMessage } from 'element-plus';
import type { TableRow } from '@/types/api';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import PendingPurchaseReceivingPanel from './PendingPurchaseReceivingPanel.vue';
import type { ReceivingLifecycleCounts, ReceivingTaskFilter } from './warehouseInboundLifecycle';
import { autoTotalWeightKg, autoWeightSkippedHint } from './autoWeight';
import { toDateString } from '@/utils/dateFormat';
import { groupBatchesBySourceDocument, type DocumentBatchGroup } from './groupBySourceDocument';
import { getSourceIndividual } from '@/api/sourceIndividual';
import { UNLINK_REASON_MAX_LENGTH, unlinkSourceIndividualPath, validateUnlinkReason } from './sourceIndividualUnlink';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 10, total: 0 });
const searchKeyword = ref('');

type InboundWorkspaceTab = ReceivingTaskFilter | 'BATCHES';
const activeInboundTab = ref<InboundWorkspaceTab>('ALL');
const receivingCounts = ref<ReceivingLifecycleCounts>({
  ALL: 0,
  WAITING_RECEIVE: 0,
  RECEIVING: 0,
  PARTIAL: 0,
});
const receivingFilter = computed<ReceivingTaskFilter>(() =>
  activeInboundTab.value === 'BATCHES' ? 'ALL' : activeInboundTab.value);
const currentRecordCount = computed(() => activeInboundTab.value === 'BATCHES'
  ? `${pagination.value.total} 个批次`
  : `${receivingCounts.value[receivingFilter.value]} 项任务`);
// 默认落点：没有待处理任务、但已有入库批次时，直接打开「已入库批次」，避免用户一进来看到空表。
// 只在用户没有点过标签、URL 没指定 view 时自动切换一次。
const inboundTabTouched = ref(false);
const receivingCountsLoaded = ref(false);
function handleReceivingCounts(counts: ReceivingLifecycleCounts): void {
  receivingCounts.value = counts;
  receivingCountsLoaded.value = true;
}
function handleInboundTabChange(): void {
  inboundTabTouched.value = true;
}
watch(
  [() => receivingCounts.value.ALL, () => pagination.value.total, receivingCountsLoaded],
  ([taskTotal, batchTotal, loaded]) => {
    if (inboundTabTouched.value || !loaded) return;
    if (activeInboundTab.value === 'ALL' && taskTotal === 0 && batchTotal > 0) {
      activeInboundTab.value = 'BATCHES';
      inboundTabTouched.value = true;
    }
  },
);

watch(
  () => route.query.view,
  (view) => {
    const normalized = String(view || '').toUpperCase();
    if (normalized) inboundTabTouched.value = true;
    if (normalized === 'BATCHES' || normalized === 'INVENTORY') activeInboundTab.value = 'BATCHES';
    else if (normalized === 'RECEIVING' || normalized === 'TASKS') activeInboundTab.value = 'ALL';
  },
  { immediate: true },
);

// ==================== 按物料汇总 / 按批次明细 切换 ====================
// F006 六膳门客户原话痛点: 同一原料入库多次 → 一堆散批次看着重复。
// 汇总模式按 materialTypeId 把当前搜索条件下的全部批次聚合成一行/物料 (合计量 + 批次数),
// 点开可展开看具体批次。纯前端聚合 (不新增后端端点) — 复用现有分页列表接口, 汇总时改用大 size
// 一次拉全量 (而非当前页 10 条), 避免"只汇总当前页"造成误导性小计。
interface MaterialSummaryGroup {
  materialTypeId: string;
  materialName: string;
  unit: string;
  totalQuantity: number;
  totalValue: number | null;
  batchCount: number;
  batches: TableRow[];
}

const viewMode = ref<'detail' | 'summary' | 'byDocument'>('detail');
const summaryLoading = ref(false);
const summaryRows = ref<TableRow[]>([]);
const summaryTruncated = ref(false);
// 汇总一次拉取的上限 — F006 规模下批次总数远小于此; 若真的超过, summaryTruncated 会提示用户缩小搜索范围
// 而不是静默漏算 (防呆: 不返回看似完整实则不全的假汇总)。
const SUMMARY_FETCH_SIZE = 2000;

async function loadSummaryData() {
  if (!factoryId.value) return;
  summaryLoading.value = true;
  summaryTruncated.value = false;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: {
        page: 1,
        size: SUMMARY_FETCH_SIZE,
        keyword: searchKeyword.value || undefined,
      },
    });
    if (response.success && response.data) {
      summaryRows.value = response.data.content || [];
      const total = Number(response.data.totalElements) || 0;
      summaryTruncated.value = total > summaryRows.value.length;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载按物料汇总数据失败');
      summaryRows.value = [];
    }
  } catch (error) {
    console.error('加载汇总失败:', error);
    ElMessage.error(t('warehouse.materials_list.message_4fc332c7bd23'));
    summaryRows.value = [];
  } finally {
    summaryLoading.value = false;
  }
}

const groupedSummary = computed<MaterialSummaryGroup[]>(() => {
  const map = new Map<string, MaterialSummaryGroup>();
  for (const row of summaryRows.value) {
    const key = String(row.materialTypeId || row.materialName || row.materialTypeName || 'unknown');
    const qty = Number(row.currentQuantity ?? row.quantity ?? row.receiptQuantity ?? 0) || 0;
    const unit = String(row.quantityUnit || row.unit || '-');
    const name = String(row.materialName || row.materialTypeName || '未命名物料');
    let group = map.get(key);
    if (!group) {
      group = {
        materialTypeId: key,
        materialName: name,
        unit,
        totalQuantity: 0,
        totalValue: canViewPrice.value ? 0 : null,
        batchCount: 0,
        batches: [],
      };
      map.set(key, group);
    }
    group.totalQuantity += qty;
    if (group.totalValue !== null) {
      group.totalValue += Number(row.totalValue) || 0;
    }
    group.batchCount += 1;
    group.batches.push(row);
  }
  return Array.from(map.values()).sort((a, b) => a.materialName.localeCompare(b.materialName, 'zh-CN'));
});

// ==================== 按收货单分组 (2026-09-01, owner 原话: "一次一个单入库…应该放在一个组里边") ====================
// 与「按物料汇总」共用同一份全量拉取(summaryRows) — 分组键 source_doc_id 早就存在于
// material_batches, 这里只是纯前端聚合, 不新增请求。详见 ./groupBySourceDocument.ts。
const groupedByDocument = computed<DocumentBatchGroup[]>(() => groupBatchesBySourceDocument(summaryRows.value));

/** 「按物料汇总」和「按收货单分组」都需要全量(不分页)批次数据, 与「按批次明细」的分页数据分开维护。 */
function isAggregateView(mode: 'detail' | 'summary' | 'byDocument' = viewMode.value): boolean {
  return mode === 'summary' || mode === 'byDocument';
}

function handleViewModeChange(mode: string | number | boolean) {
  viewMode.value = mode as 'detail' | 'summary' | 'byDocument';
  if (isAggregateView()) loadSummaryData();
}

const materialTypes = ref<TableRow[]>([]);
const suppliers = ref<TableRow[]>([]);
const manufacturers = ref<ManufacturerRegistry[]>([]);

onMounted(() => {
  loadData();
  loadMaterialTypes();
  loadSuppliers();
  loadManufacturers();
});

async function loadMaterialTypes() {
  if (!factoryId.value) return;
  try {
    // Bug B2 fix: use raw-material-types/active (same table the backend validates against)
    const res = await get(`/${factoryId.value}/raw-material-types/active`);
    if (res.success && res.data) materialTypes.value = Array.isArray(res.data) ? res.data : (res.data.content || []);
  } catch { /* silent */ }
}

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get(`/${factoryId.value}/suppliers`, { params: { size: 200 } });
    if (res.success && res.data) suppliers.value = res.data.content || res.data || [];
  } catch { /* silent */ }
}

async function loadManufacturers() {
  if (!factoryId.value) return;
  try {
    const res = await listManufacturers(factoryId.value, true);
    if (res.success && Array.isArray(res.data)) manufacturers.value = res.data;
  } catch { /* silent */ }
}

async function loadData() {
  if (!factoryId.value) return;

  loading.value = true;
  try {
    const response = await get(`/${factoryId.value}/material-batches`, {
      params: {
        page: pagination.value.page,
        size: pagination.value.size,
        keyword: searchKeyword.value || undefined
      }
    });
    if (response.success && response.data) {
      tableData.value = response.data.content || [];
      pagination.value.total = response.data.totalElements || 0;
    } else if (response.success === false) {
      ElMessage.error(response.message || '加载原材料批次失败');
    }
  } catch (error) {
    // Interceptor already shows specific sticky toast for ApiError.
    console.error('加载失败:', error);
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.value.page = 1;
  if (isAggregateView()) {
    loadSummaryData();
  } else {
    loadData();
  }
}

function handleRefresh() {
  searchKeyword.value = '';
  pagination.value.page = 1;
  if (isAggregateView()) {
    loadSummaryData();
  } else {
    loadData();
  }
}

function handleReceivingRefresh() {
  loadData();
  if (isAggregateView()) loadSummaryData();
}

function handlePageChange(page: number) {
  pagination.value.page = page;
  loadData();
}

function handleManufacturerChange(code: string) {
  const manufacturer = manufacturers.value.find((item) => item.code === code);
  if (manufacturer?.originPlace) {
    formData.originPlace = manufacturer.originPlace;
  }
}

function handleSizeChange(size: number) {
  pagination.value.size = size;
  pagination.value.page = 1;
  loadData();
}

// 标签配色 (纯展示). 中文不在这里 —— 见下面的 getStatusText。
function getStatusType(status: string) {
  const map: Record<string, string> = {
    AVAILABLE: 'success',
    FRESH: 'success',
    IN_STOCK: 'success',
    FROZEN: 'primary',
    INSPECTING: 'warning',
    RESERVED: 'warning',
    PRODUCING_RESERVED: 'warning',
    DEPLETED: 'info',
    USED_UP: 'info',
    EXPIRED: 'danger',
    SCRAPPED: 'danger',
    DEFECTIVE: 'danger',
  };
  return map[status?.toUpperCase()] || 'info';
}

/**
 * 2026-08-20: 本函数原来自带一份只有 4 个码的私有中文表, `MaterialBatchStatus` 的
 * 另外 8 个取值 (INSPECTING/FRESH/FROZEN/USED_UP/SCRAPPED/PRODUCING_RESERVED/
 * DEFECTIVE/IN_STOCK) 全部 `|| status` 原样吐英文 —— 客户看到的就是 `INSPECTING`。
 *
 * ⛔ 不要再在本文件里写死中文: 中文的唯一出处是 enumDisplay.ts 的
 * MATERIAL_BATCH_STATUS_LABELS, 由 enumDisplayCoverage.spec.ts 对着 Java 枚举源钉住,
 * 后端加第 13 个取值时那条测试会红。
 */
function getStatusText(status: string) {
  return enumLabel(status?.toUpperCase(), MATERIAL_BATCH_STATUS_LABELS);
}

function batchTagType(row: TableRow | null): string {
  return isDepletedAvailableBatch(row) ? 'info' : getStatusType(String(row?.status ?? ''));
}

// ==================== View Dialog ====================
const viewDialogVisible = ref(false);
const viewRecord = ref<TableRow | null>(null);

function handleView(row: TableRow) {
  viewRecord.value = row;
  viewDialogVisible.value = true;
}

// ==================== Create / Edit Dialog ====================
const formDialogVisible = ref(false);
const formDialogTitle = ref('入库登记');
const formRef = ref<FormInstance>();
const formSaving = ref(false);
const editingId = ref<string | null>(null);
/** 正在编辑的批次挂着的来源个体 (#1844 L-d); null = 没挂。 */
const editingSourceIndividualId = ref<string | null>(null);
/** 该个体的档案编号; 读不到时为 null, 界面如实说读不到, ⛔ 不拿 id 冒充编号。 */
const editingSourceIndividualCode = ref<string | null>(null);
const unlinkingSourceIndividual = ref(false);

const formData = reactive({
  batchNumber: '',
  materialTypeId: '',
  supplierId: '',
  receiptDate: toDateString(new Date()),
  receiptQuantity: null as number | null,
  quantityUnit: 'kg',
  boxCount: null as number | null,
  totalWeight: null as number | null,
  totalValue: null as number | null,
  unitPrice: null as number | null,
  expireDate: '',
  notes: '',
  factoryNumber: '',
  originPlace: '',
});

// ==================== 扫码查询 ====================
const scanCode = ref('');
const scanLoading = ref(false);
const scanResult = ref<Record<string, unknown> | null>(null);
const scanResultVisible = computed({
  get: () => scanResult.value !== null,
  set: (v: boolean) => { if (!v) scanResult.value = null; },
});

async function handleScanQuery() {
  const code = scanCode.value.trim();
  if (!code) {
    ElMessage.warning(t('warehouse.materials_list.message_bdf6a2c9cc90'));
    return;
  }
  scanLoading.value = true;
  scanResult.value = null;
  try {
    const res = await get(`/${factoryId.value}/labels/scan/${encodeURIComponent(code)}`);
    if (res.success && res.data) {
      scanResult.value = res.data as Record<string, unknown>;
    } else {
      ElMessage({ message: res.message || '未找到对应批次', type: 'warning', duration: 0, showClose: true });
    }
  } catch {
    ElMessage({ message: t('warehouse.materials_list.message_fbc1dd005b6b'), type: 'error', duration: 0, showClose: true });
  } finally {
    scanLoading.value = false;
  }
}

// Track the selected material's canonical unit for the UI hint
const selectedMaterialUnit = ref<string | null>(null);

const formRules = {
  // 一物一码: 批次号由系统自动生成, 非手填, 故无 required 校验 (转录 6.9 行68/105 防手敲重码)
  materialTypeId: [{ required: true, message: t('warehouse.materials_list.message_c23b611e58ad'), trigger: 'change' }],
  supplierId: [{ required: true, message: t('warehouse.materials_list.message_ade9f5ce860c'), trigger: 'change' }],
  receiptQuantity: [{ required: true, message: t('warehouse.materials_list.message_ec6ac20e4abe'), trigger: 'blur' }],
  receiptDate: [{ required: true, message: t('warehouse.materials_list.message_a16b0d9f77a8'), trigger: 'change' }],
  totalWeight: [{ required: true, message: t('warehouse.materials_list.message_d7ba056c7f22'), trigger: 'blur' }],
  totalValue: [{ required: true, message: t('warehouse.materials_list.message_8fae187bda19'), trigger: 'blur' }],
};

// Bug C5: auto-calculate totalWeight and totalValue from selected material's base info
// W-02 fix (Round 7): when material has no unit price, hint the user so they don't
// stare at an empty required field wondering why auto-calc skipped it.
// T158 fix: auto-sync quantityUnit to the material's master unit when user selects/changes
// a material in add mode, preventing kg/个 mismatch (防呆 Rule 2 — unit anchored to master).
let w02HintShown = false;
/** 「单位不是重量单位所以没自动填」的提示，每次开弹窗只说一遍。 */
let weightHintShown = false;
function autoCalcWeightAndValue() {
  // 缺陷 A/C (2026-09-01, LIUSHANMEN 批次 MT-20260901-3312 实测):
  //
  // 编辑既有批次时, 数量/原料类型/总重量/总价值全部是账实红线锁定字段——
  // EDITABLE_FIELDS_ON_UPDATE 不含它们, 对应控件也全部 `:disabled="!!editingId"`。
  // 用户在编辑态下**不可能**改动这些字段, 这两个 watch 之所以在编辑态触发,
  // 纯粹是 handleEdit() 用 Object.assign 一次性灌入既有批次数据的副作用,
  // 不是真实的用户输入。继续跑这段自动填充/提示逻辑会:
  //
  //   (a) 拿"原料主数据当前单价"覆盖批次自己在收货时锁定的总价值——
  //       批次自己的 unitPrice(本例 8.85, 随 PO 收货写入 formData.unitPrice)
  //       与主数据的当前单价是两回事, 覆盖显示值与账实红线本身的精神矛盾
  //       (即便不会真的提交, 见 buildUpdatePayload 白名单);
  //   (b) 弹出"原料「X」未配置单价，请手动输入总价值"——而编辑态下这句指令
  //       **在任何情况下都不可能被执行**: 总价值控件要么因 canViewPrice=false
  //       整个不渲染, 要么 :disabled="!!editingId" 被锁定, 唯一的正路是
  //       "更正批次估值"(openCostCorrection, 走带新旧值审计的独立接口)。
  //       这正是 fool-proof-design Rule 5(死路必须变成可导航)与四位一体(d)
  //       (指令必须指向真实存在的下一步)要守住的东西。
  //
  // ⇒ 只在新增模式(editingId === null)跑这段自动填充/提示, 编辑态整段跳过。
  //   这同时消掉了「总重量」那条姊妹提示(weightHintShown)在编辑态的同一问题——
  //   它守的 formData.totalWeight 同样被锁, 同一条判据同样适用, 见下面 PR 说明。
  if (editingId.value) return;

  const qty = formData.receiptQuantity;
  const mat = materialTypes.value.find((m: TableRow) => m.id === formData.materialTypeId) as TableRow | undefined;

  // T158: sync unit on every material-selection change, even before qty is entered.
  // 此处只在新增模式跑得到(上面已 return), editingId === null 恒成立, 保留判断只是防御式写法。
  if (mat) {
    selectedMaterialUnit.value = (mat.unit as string) || null;
    if (editingId.value === null && mat.unit) {
      formData.quantityUnit = String(mat.unit);
    }
  } else {
    selectedMaterialUnit.value = null;
  }

  if (!formData.materialTypeId || qty == null || qty <= 0) return;
  if (!mat) return;
  // 🔴 2026-08-27: 这里原来是 `formData.totalWeight = Number(qty.toFixed(3))` ——
  //    **不看单位**，注释写着「unit is typically kg」。而单位是跟着所选原料走的
  //    （上面几行的 `formData.quantityUnit = String(mat.unit)`），
  //    后端 totalWeight 的契约又写死是公斤(@Schema "入库总重量(kg)")。
  //    ⇒ 选「箱」录 3 → 总重量被填成 3 kg；选「g」录 500 → 填成 500 kg（1000×）。
  //    生产实测 484 个原料类型里 102 个不是 kg（含 3 个 g）。详见 ./autoWeight.ts。
  const autoWeight = autoTotalWeightKg(qty, formData.quantityUnit);
  if (autoWeight !== null) {
    formData.totalWeight = autoWeight;
  } else {
    // ⛔ 不回退成 qty —— 那正是被修的那个毛病。留空 + 说明为什么，让用户自己录。
    formData.totalWeight = null;
    if (!weightHintShown) {
      // 缺陷 C (2026-09-01 实测): 守卫必须在 ElMessage.info() **之前**置位。
      // 实测 ElMessage.info() 会在同一个 Vue watcher flush 周期内重入调度器,
      // 让"仍在等待执行"的另一个 watch(本文件 materialTypeId / receiptQuantity
      // 两个 watch 在同一次 Object.assign 后同时挂起)在守卫真正落地之前
      // 就跑到这一行, 读到的仍是旧值——同一句提示因此弹两次。
      // 先置位再弹, 第二个 watch 进来时守卫已经是 true, 重入也不会再穿透。
      weightHintShown = true;
      ElMessage.info({ message: autoWeightSkippedHint(formData.quantityUnit), duration: 5000 });
    }
  }
  // totalValue = quantity * unitPrice
  const unitPrice = Number(mat.unitPrice || mat.movingAvgPrice || 0);
  if (unitPrice > 0) {
    formData.totalValue = Number((qty * unitPrice).toFixed(2));
    w02HintShown = false;
  } else if (!w02HintShown) {
    // Show hint once per dialog session so user knows why totalValue wasn't auto-filled
    // 同上: 守卫先置位, 再弹提示, 堵住 ElMessage.info() 的重入窗口(缺陷 C)。
    w02HintShown = true;
    ElMessage.info({ message: `原料「${mat.name || '该原料'}」未配置单价，请手动输入总价值`, duration: 4000 });
  }
}

watch(() => formData.materialTypeId, () => { autoCalcWeightAndValue(); });
watch(() => formData.receiptQuantity, () => { autoCalcWeightAndValue(); });

function handleCreate() {
  editingId.value = null;
  editingSourceIndividualId.value = null;
  editingSourceIndividualCode.value = null;
  formDialogTitle.value = '入库登记';
  w02HintShown = false;
  weightHintShown = false;
  selectedMaterialUnit.value = null;
  Object.assign(formData, { batchNumber: '', materialTypeId: '', supplierId: '', receiptDate: toDateString(new Date()), receiptQuantity: null, quantityUnit: 'kg', boxCount: null, totalWeight: null, totalValue: null, unitPrice: null, expireDate: '', notes: '', factoryNumber: '', originPlace: '' });
  formDialogVisible.value = true;
}

function handleEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  formDialogTitle.value = '编辑批次';
  w02HintShown = false;
  weightHintShown = false;
  selectedMaterialUnit.value = null;
  Object.assign(formData, {
    batchNumber: row.batchNumber || '',
    materialTypeId: row.materialTypeId || '',
    supplierId: row.supplierId || '',
    receiptDate: row.receiptDate || row.inboundDate || toDateString(new Date()),
    receiptQuantity: row.receiptQuantity ?? row.quantity ?? row.currentQuantity ?? null,
    quantityUnit: row.quantityUnit || row.unit || 'kg',
    boxCount: row.boxCount ?? null,
    totalWeight: row.totalWeight ?? null,
    totalValue: row.totalValue ?? null,
    unitPrice: row.unitPrice ?? null,
    expireDate: row.expireDate || row.expiryDate || '',
    notes: row.notes || '',
    factoryNumber: row.factoryNumber || '',
    originPlace: row.originPlace || '',
  });
  editingSourceIndividualId.value = row.sourceIndividualId ? String(row.sourceIndividualId) : null;
  editingSourceIndividualCode.value = null;
  if (editingSourceIndividualId.value) {
    const linkedId = editingSourceIndividualId.value;
    getSourceIndividual(factoryId.value, linkedId)
      .then((res) => {
        if (res.success && editingSourceIndividualId.value === linkedId) {
          editingSourceIndividualCode.value = res.data?.individualCode ?? null;
        }
      })
      .catch(() => { /* 编号读不到就显示「读不到」, 不影响解除 */ });
  }
  formDialogVisible.value = true;
}

/**
 * 解除批次的来源个体挂载 (#1844 L-d)。原因必填, 后端写操作日志;
 * ⛔ 不走编辑提交体 (那里 sourceIndividualId 空白会 400, null 是「不改」)。
 */
async function handleUnlinkSourceIndividual() {
  const batchId = editingId.value;
  if (!batchId || !editingSourceIndividualId.value) return;
  let reason: string;
  try {
    const result = await ElMessageBox.prompt(
      t('warehouse.materials_list.message_83584b26616a'),
      t('warehouse.materials_list.message_c77b8d95ba13'),
      {
        confirmButtonText: t('warehouse.materials_list.confirmbuttontext_a21a394ffd2c'),
        cancelButtonText: t('warehouse.materials_list.cancelbuttontext_2cd0f3be8738'),
        inputType: 'textarea',
        inputPlaceholder: `例如：挂错了个体（最多 ${UNLINK_REASON_MAX_LENGTH} 字）`,
        inputValidator: validateUnlinkReason,
      },
    );
    reason = String(result.value ?? '').trim();
  } catch {
    return; // 用户取消
  }
  unlinkingSourceIndividual.value = true;
  try {
    const res = await post(unlinkSourceIndividualPath(factoryId.value, batchId), { reason });
    if (res.success) {
      ElMessage.success(t('warehouse.materials_list.message_54ae0ab603e4'));
      editingSourceIndividualId.value = null;
      editingSourceIndividualCode.value = null;
      loadData();
    } else {
      // ⛔ 不吞后端文案: 409「没挂个体」/ 403「无权」各有含义。
      ElMessage({ message: res.message || '解除失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    console.error('解除来源个体挂载失败:', error);
    ElMessage({ message: t('warehouse.materials_list.message_25e72cc5e030'), type: 'error', duration: 0, showClose: true });
  } finally {
    unlinkingSourceIndividual.value = false;
  }
}

/**
 * 编辑既有批次时**允许提交**的字段 —— 白名单 (fail-closed)。
 *
 * owner 2026-08-20 原话: "我点击编辑是可以去修改所有的信息的, 这个是完全不能允许仓储去做的事情
 * …包括所有其他权限、财务、工厂总监, 都应该是到盘点与期初库存去盘点的时候才能改。"
 *
 * 判据是「改了它, 库存账/成本账/溯源链会不会被直接改写」:
 *   ⛔ 不在白名单 (账实数据): receiptQuantity 数量 / quantityUnit 单位 / totalWeight 总重量 /
 *      totalValue 总价值 / materialTypeId 原料类型 / supplierId 供应商 / receiptDate 入库日期。
 *      —— 数量与重量是库存台账本身; 总价值是成本; 单位换了等于同一个数字改写账实(箱→kg);
 *         原料类型改了等于把库存从 A 品类搬到 B 品类, 两边账同时错;
 *         供应商是应付归属与溯源链源头, 在溯源系统里属于批次身份而非描述;
 *         入库日期是先进先出的排序键(改一天就换了哪一批先被消耗)与期间归属, 不是描述。
 *   ✅ 在白名单 (描述性信息, 仓管随手补录是合理的): 箱数(DTO 自己写着"不参与库存计算")、
 *      过期日期、备注、厂号、产地。
 *
 * ⚠️ 2026-08-20 第二轮把 receiptDate 从白名单里摘出来。上一轮不锁它的理由是
 * 「盘点改不了日期, 锁了是 dead-end」—— 那条理由成立, 所以本轮是**锁 + 修**一起做:
 * 正路是下面的「更正入库日期」弹窗 (原因必填, 后端写带新旧值的审计台账)。
 * ⛔ 每一个被锁的字段都必须有一条能走的路, 否则就是把用户堵死。
 *
 * ⚠️ 这份白名单**不是**唯一的闸 —— 后端 MaterialBatchServiceImpl 里有一道同语义的守卫,
 * 对所有角色生效。只改前端等于没改: 接口还在, 绕过界面照样能改。两处必须同时在。
 */
const EDITABLE_FIELDS_ON_UPDATE = [
  'boxCount',
  'expireDate',
  'notes',
  'factoryNumber',
  'originPlace',
] as const;

/** 编辑提交体: 只挑白名单字段, 账实字段一个都不发。 */
function buildUpdatePayload(): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const key of EDITABLE_FIELDS_ON_UPDATE) {
    payload[key] = formData[key];
  }
  return payload;
}

function goStocktake() {
  formDialogVisible.value = false;
  router.push('/warehouse/stocktakes');
}

// ==================== 更正入库日期 (账实字段里唯一在本页有正路的那个) ====================
/**
 * 入库日期被锁之后的**正路**。
 *
 * ⛔ 刻意不指向盘点: 盘点只改数量, 它**改不了日期**。把入库日期的出口也指过去,
 * 用户会照着走一趟盘点、发现改不了、然后认定系统坏了 —— 那是把一个死路换成一条更长的死路。
 *
 * 权限与盘点审批同级 (finance_manager / factory_super_admin / platform_admin):
 * 入库日期决定先进先出的消耗顺序与期间归属, 单步立即生效、没有审批环节,
 * 所以门槛放在「让它生效」那一档, ⛔ 不是「发起盘点」那一档(那一档任何仓管都能进)。
 * 后端 MaterialBatchServiceImpl.RECEIPT_DATE_CORRECTION_ROLES 是唯一 enforce 处;
 * 这里同一份角色码只用于**展示**: 没资格的人看到的是"该找谁", 而不是一个点下去必 403 的按钮。
 */
const RECEIPT_DATE_CORRECTION_ROLES = ['finance_manager', 'factory_super_admin', 'platform_admin'];
const canCorrectReceiptDate = computed(() => authStore.hasRole(RECEIPT_DATE_CORRECTION_ROLES));

/** 与后端 CorrectReceiptDateRequest.reason 的 @Size(max=255) 一致 —— 超长后端会 400。 */
const CORRECTION_REASON_MAX_LENGTH = 255;

const correctDateDialogVisible = ref(false);
const correctDateSaving = ref(false);
const correctDateBatchId = ref<string | null>(null);
const correctDateBatchNumber = ref('');
const correctDateOriginal = ref('');
const correctDateForm = reactive({ receiptDate: '', reason: '' });
const todayIso = toDateString(new Date());
/** 货不可能在将来收到 —— 与后端那道 400 同判据, 在用户填之前就把边界摆出来 (防呆 Rule 1)。 */
const disableFutureDate = (d: Date) => d.getTime() > Date.now();

function openCorrectReceiptDate() {
  correctDateBatchId.value = editingId.value;
  correctDateBatchNumber.value = formData.batchNumber;
  correctDateOriginal.value = String(formData.receiptDate || '');
  correctDateForm.receiptDate = correctDateOriginal.value;
  correctDateForm.reason = '';
  correctDateDialogVisible.value = true;
}

// ==================== 更正批次估值（成本事实，必须带新旧值留痕） ====================
const costCorrectionDialogVisible = ref(false);
const costCorrectionSaving = ref(false);
const costCorrectionBatchId = ref<string | null>(null);
const costCorrectionBatchNumber = ref('');
const costCorrectionOriginal = ref<number | null>(null);
const costCorrectionForm = reactive({ unitPrice: null as number | null, reason: '' });

function openCostCorrection() {
  costCorrectionBatchId.value = editingId.value;
  costCorrectionBatchNumber.value = formData.batchNumber;
  costCorrectionOriginal.value = formData.unitPrice == null ? null : Number(formData.unitPrice);
  costCorrectionForm.unitPrice = costCorrectionOriginal.value;
  costCorrectionForm.reason = '';
  costCorrectionDialogVisible.value = true;
}

async function submitCostCorrection() {
  const batchId = costCorrectionBatchId.value;
  const price = Number(costCorrectionForm.unitPrice);
  const reason = costCorrectionForm.reason.trim();
  if (!batchId) return;
  if (!Number.isFinite(price) || price <= 0) { ElMessage.warning(t('warehouse.materials_list.message_f3355c533db1')); return; }
  if (costCorrectionOriginal.value != null && Math.abs(price - costCorrectionOriginal.value) < 0.000001) {
    ElMessage.warning(t('warehouse.materials_list.message_47a14c95e281')); return;
  }
  if (!reason) { ElMessage.warning(t('warehouse.materials_list.message_1fa994cae9b0')); return; }
  if (reason.length > CORRECTION_REASON_MAX_LENGTH) { ElMessage.warning(t('warehouse.materials_list.message_6b0e81ecf61c')); return; }
  costCorrectionSaving.value = true;
  try {
    const res = await post(`/${factoryId.value}/material-batches/${batchId}/cost-correction`, {
      unitPrice: price,
      reason,
    });
    if (!res.success) {
      ElMessage({ message: res.message || '估值更正失败', type: 'error', duration: 0, showClose: true });
      return;
    }
    ElMessage.success(t('warehouse.materials_list.message_911788e724b3'));
    costCorrectionDialogVisible.value = false;
    if (formData.receiptQuantity != null) {
      formData.totalValue = Number((formData.receiptQuantity * price).toFixed(2));
    }
    formData.unitPrice = price;
    await loadData();
    if (isAggregateView()) await loadSummaryData();
  } catch (error) {
    console.error('更正批次估值失败:', error);
    ElMessage({ message: t('warehouse.materials_list.message_507bb61d5934'), type: 'error', duration: 0, showClose: true });
  } finally {
    costCorrectionSaving.value = false;
  }
}

async function submitCorrectReceiptDate() {
  const batchId = correctDateBatchId.value;
  if (!batchId) return;
  const newDate = String(correctDateForm.receiptDate || '');
  const reason = correctDateForm.reason.trim();
  // 三条前置判据与后端逐条对应。在这里拦是为了少一次往返, ⛔ 不是"前端拦了后端就可以不拦"。
  if (!newDate) { ElMessage.warning(t('warehouse.materials_list.message_5e44ec7956e4')); return; }
  if (newDate === correctDateOriginal.value) { ElMessage.warning(t('warehouse.materials_list.message_361b35746274')); return; }
  if (newDate > todayIso) { ElMessage.warning(t('warehouse.materials_list.message_c4d210173c74')); return; }
  if (!reason) { ElMessage.warning(t('warehouse.materials_list.message_af8329e9763d')); return; }
  if (reason.length > CORRECTION_REASON_MAX_LENGTH) {
    ElMessage.warning(`更正原因不能超过 ${CORRECTION_REASON_MAX_LENGTH} 字`);
    return;
  }

  correctDateSaving.value = true;
  try {
    const res = await post(
      `/${factoryId.value}/material-batches/${batchId}/receipt-date-correction`,
      { receiptDate: newDate, reason },
    );
    if (res.success) {
      ElMessage.success(t('warehouse.materials_list.message_6bd96efb5c08'));
      correctDateDialogVisible.value = false;
      formData.receiptDate = newDate;
      correctDateOriginal.value = newDate;
      loadData();
      if (isAggregateView()) loadSummaryData();
    } else {
      // ⛔ 不吞后端文案: 403 的时候它说的是"该找谁", 比一句通用"操作失败"有用得多。
      ElMessage({ message: res.message || '更正失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (error) {
    console.error('更正入库日期失败:', error);
    ElMessage({ message: t('warehouse.materials_list.message_53e76e3b3eaf'), type: 'error', duration: 0, showClose: true });
  } finally {
    correctDateSaving.value = false;
  }
}

async function handleFormSubmit() {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  formSaving.value = true;
  try {
    // W-05 fix (Round 8): factoryId was being spread into the body even though
    // the URL path already carries it — redundant noise in server logs. Backend
    // reads the path variable and ignores any body factoryId.
    let response;
    if (editingId.value) {
      // ⛔ 刻意不是 { ...formData }: 那样会把数量/总重量/总价值/原料类型/供应商一起发上去,
      // 正是本次要堵的洞。新增(POST)不受影响 —— 建批次时这些字段本来就该填。
      response = await put(`/${factoryId.value}/material-batches/${editingId.value}`, buildUpdatePayload());
    } else {
      const payload = { ...formData };
      response = await post(`/${factoryId.value}/material-batches`, payload);
    }
    if (response.success) {
      ElMessage.success(editingId.value ? '更新成功' : '入库登记成功');
      formDialogVisible.value = false;
      loadData();
      if (isAggregateView()) loadSummaryData();
    } else {
      ElMessage.error(response.message || '操作失败');
    }
  } catch (error) {
    console.error('保存失败:', error);
    ElMessage.error(t('warehouse.materials_list.message_f42a1238b44a'));
  } finally {
    formSaving.value = false;
  }
}

// ==================== A5: 生成标签 ====================
const labelGenerating = ref<string | null>(null); // batchId 正在生成中

async function handleGenerateLabel(row: TableRow) {
  const batchId = String(row.id || '');
  if (!batchId) { ElMessage.warning(t('warehouse.materials_list.message_1b204332830f')); return; }
  try {
    await ElMessageBox.confirm(
      `确认为批次「${row.batchNumber || batchId}」生成物料标签？\n\n标签已存在则会提示先作废旧标签。`,
      t('warehouse.materials_list.message_04a517d29df3'),
      { confirmButtonText: t('warehouse.materials_list.confirmbuttontext_1ad1463fe16f'), cancelButtonText: t('warehouse.materials_list.cancelbuttontext_2cd0f3be8738'), type: 'info' }
    );
  } catch { return; } // user cancelled

  labelGenerating.value = batchId;
  try {
    const res = await post(`/${factoryId.value}/labels/material-batch/${batchId}`, {});
    if (res.success && res.data) {
      const code = (res.data as { labelCode?: string }).labelCode || '—';
      ElMessage({ message: `标签已生成，编号：${code}`, type: 'success', duration: 5000, showClose: true });
    } else {
      ElMessage({ message: res.message || '生成失败', type: 'error', duration: 0, showClose: true });
    }
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
    ElMessage({ message: msg || '生成标签失败，请重试', type: 'error', duration: 0, showClose: true });
  } finally {
    labelGenerating.value = null;
  }
}

</script>

<template>
  <CanvasAwareWrapper module-code="material_batch">
  <div class="page-wrapper">
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('warehouse.materials_list.text_963b21a278e0') }}</span>
            <span class="data-count">{{ t('warehouse.materials_list.text_cb62ebd689ee') }} {{ currentRecordCount }}</span>
          </div>
          <div class="header-right">
            <el-input
              v-model="scanCode"
              :placeholder="t('warehouse.materials_list.placeholder_f6917c970079')"
              clearable
              style="width: 200px"
              @keyup.enter="handleScanQuery"
            />
            <UButton color="neutral" variant="outline" :loading="scanLoading" @click="handleScanQuery">{{ t('warehouse.materials_list.action_c6ba7dcc62a5') }}</UButton>
            <UButton color="neutral" variant="outline" v-if="canWrite" @click="router.push('/warehouse/material-types')">
              {{ t('warehouse.materials_list.action_c00f17d23ce9') }}
            </UButton>
            <UButton color="neutral" variant="outline" v-if="canWrite" @click="router.push('/warehouse/manufacturers')">
              {{ t('warehouse.materials_list.action_9a29a90329d1') }}
            </UButton>
          </div>
        </div>
      </template>

      <el-tabs v-model="activeInboundTab" class="inbound-status-tabs" @tab-change="handleInboundTabChange">
        <el-tab-pane :label="`全部任务 ${receivingCounts.ALL}`" name="ALL" />
        <el-tab-pane :label="`待收货 ${receivingCounts.WAITING_RECEIVE}`" name="WAITING_RECEIVE" />
        <el-tab-pane :label="`收货中 ${receivingCounts.RECEIVING}`" name="RECEIVING" />
        <el-tab-pane :label="`部分入库 ${receivingCounts.PARTIAL}`" name="PARTIAL" />
        <el-tab-pane :label="`已入库批次 ${pagination.total}`" name="BATCHES" />
      </el-tabs>

      <PendingPurchaseReceivingPanel
        v-if="factoryId && activeInboundTab !== 'BATCHES'"
        :factory-id="factoryId"
        :can-write="canWrite"
        :status-filter="receivingFilter"
        :show-heading="false"
        @counts-changed="handleReceivingCounts"
        @refreshed="handleReceivingRefresh"
      />

      <template v-else>
      <div class="search-bar batch-toolbar">
        <el-radio-group v-model="viewMode" @change="handleViewModeChange">
          <el-radio-button label="detail">{{ t('warehouse.materials_list.text_9b87b7ef3027') }}</el-radio-button>
          <el-radio-button label="summary">{{ t('warehouse.materials_list.text_ca5759abe4c8') }}</el-radio-button>
          <el-radio-button label="byDocument">{{ t('warehouse.materials_list.text_8f363d265e16') }}</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="searchKeyword"
          :placeholder="t('warehouse.materials_list.placeholder_e776c01c6e12')"
          :prefix-icon="Search"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button type="primary" :icon="Search" @click="handleSearch">{{ t('warehouse.materials_list.action_44ce7ae909bb') }}</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">{{ t('warehouse.materials_list.action_cb5d682bac3d') }}</el-button>
      </div>

      <!-- 按物料汇总视图 (F006 客户原话痛点: 同一原料入库多次, 看着一堆散批次) -->
      <template v-if="viewMode === 'summary'">
        <div class="summary-hint">
          <span>{{ t('warehouse.materials_list.text_76e547a8fa54') }} <strong>{{ groupedSummary.length }}</strong> {{ t('warehouse.materials_list.text_814a7c60903b') }}</span>
          <span v-if="summaryTruncated" class="summary-warning">
            {{ t('warehouse.materials_list.text_13ff7222eb45') }} {{ summaryRows.length }} {{ t('warehouse.materials_list.text_5d427fe2324e') }}
          </span>
        </div>
        <el-table
          :data="groupedSummary"
          v-loading="summaryLoading"
          :empty-text="t('warehouse.materials_list.empty_text_b6185aa71e19')"
          stripe
          border
          row-key="materialTypeId"
          style="width: 100%"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <CompatTable :data="row.batches" size="small" border stripe class="nested-batch-table">
                <CompatTableColumn prop="batchNumber" :label="t('warehouse.materials_list.label_cb5386f1a98a')" width="160" />
                <CompatTableColumn prop="supplierName" :label="t('warehouse.materials_list.label_033ed5945254')" min-width="140" show-overflow-tooltip />
                <CompatTableColumn :label="t('warehouse.materials_list.label_5e29d121dda0')" width="120" align="right">
                  <template #default="{ row: b }">
                    {{ b.quantity ?? b.currentQuantity ?? b.receiptQuantity ?? '-' }} {{ displayUnit(b.quantityUnit || b.unit) }}
                  </template>
                </CompatTableColumn>
                <CompatTableColumn :label="t('warehouse.materials_list.label_6320b4a8722a')" width="100" align="center">
                  <template #default="{ row: b }">
                    <el-tag :type="batchTagType(b)" size="small">{{ batchStatusDisplay(b, getStatusText).text }}</el-tag>
                  </template>
                </CompatTableColumn>
                <CompatTableColumn prop="expiryDate" :label="t('warehouse.materials_list.label_a75622e03fcd')" width="120" />
                <CompatTableColumn prop="createdAt" :label="t('warehouse.materials_list.label_fd184c2ce046')" width="180" :formatter="formatDateTimeCell" />
                <CompatTableColumn :label="t('warehouse.materials_list.label_ed31fbb483ee')" width="180" fixed="right" align="center">
                  <template #default="{ row: b }">
                    <UButton color="primary" variant="link" size="sm" @click="handleView(b)">{{ t('warehouse.materials_list.action_db8db0530432') }}</UButton>
                    <UButton v-if="canWrite" color="primary" variant="link" size="sm" @click="handleEdit(b)">{{ t('warehouse.materials_list.action_051836569928') }}</UButton>
                    <UButton
                      v-if="canWrite"
                      color="success"
                      variant="link"
                      size="sm"
                      :loading="labelGenerating === String(b.id)"
                      @click="handleGenerateLabel(b)"
                    >{{ t('warehouse.materials_list.action_9d9cf609f6ea') }}</UButton>
                  </template>
                </CompatTableColumn>
              </CompatTable>
            </template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_a27f31ef6fa4')" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.materialName }}</template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_6ecebeaf912f')" width="150" align="right">
            <template #default="{ row }"><strong>{{ fmtQty(row.totalQuantity) }}</strong></template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_80b19d68b149')" width="90" align="center">
            <template #default="{ row }">{{ row.unit }}</template>
          </el-table-column>
          <el-table-column v-if="canViewPrice" :label="t('warehouse.materials_list.label_e6764062e67f')" width="140" align="right">
            <template #default="{ row }">{{ formatAmount(row.totalValue) }}</template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_2f69582f9147')" width="120" align="right">
            <template #default="{ row }">{{ row.batchCount }} {{ t('warehouse.materials_list.text_adc46b52038f') }}</template>
          </el-table-column>
        </el-table>
      </template>

      <!--
        按收货单分组视图 (2026-09-01, owner 原话: "3251厂牛前 跟 941厂牛后…一次一个单入库,
        它应该是放在一个组里边"). 分组键是 material_batches.source_doc_id (数据早就存在,
        见 groupBySourceDocument.ts 头部说明), 与「按物料汇总」共用同一份全量批次拉取。
        ⛔ 这是第三个维度, 不替代上面两个视图 —— 都问不出"这一次收货都到了哪几个批次号"。
      -->
      <template v-if="viewMode === 'byDocument'">
        <div class="summary-hint">
          <span>{{ t('warehouse.materials_list.text_76e547a8fa54') }} <strong>{{ groupedByDocument.length }}</strong> {{ t('warehouse.materials_list.text_57bd16e398b1') }}</span>
          <span v-if="summaryTruncated" class="summary-warning">
            {{ t('warehouse.materials_list.text_f5ca2d7a6acf') }} {{ summaryRows.length }} {{ t('warehouse.materials_list.text_1c8991e83864') }}
          </span>
        </div>
        <el-table
          :data="groupedByDocument"
          v-loading="summaryLoading"
          :empty-text="t('warehouse.materials_list.empty_text_b6185aa71e19')"
          stripe
          border
          row-key="key"
          style="width: 100%"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <CompatTable :data="row.batches" size="small" border stripe class="nested-batch-table">
                <CompatTableColumn prop="batchNumber" :label="t('warehouse.materials_list.label_cb5386f1a98a')" width="160" />
                <CompatTableColumn :label="t('warehouse.materials_list.label_1eab99853e63')" min-width="140" show-overflow-tooltip>
                  <template #default="{ row: b }">{{ b.materialTypeName || b.materialName || '-' }}</template>
                </CompatTableColumn>
                <CompatTableColumn prop="supplierName" :label="t('warehouse.materials_list.label_033ed5945254')" min-width="140" show-overflow-tooltip />
                <CompatTableColumn :label="t('warehouse.materials_list.label_5e29d121dda0')" width="120" align="right">
                  <template #default="{ row: b }">
                    {{ b.receiptQuantity ?? b.inboundQuantity ?? b.currentQuantity ?? '-' }} {{ displayUnit(b.quantityUnit || b.unit) }}
                  </template>
                </CompatTableColumn>
                <CompatTableColumn :label="t('warehouse.materials_list.label_6320b4a8722a')" width="100" align="center">
                  <template #default="{ row: b }">
                    <el-tag :type="batchTagType(b)" size="small">{{ batchStatusDisplay(b, getStatusText).text }}</el-tag>
                  </template>
                </CompatTableColumn>
                <CompatTableColumn prop="createdAt" :label="t('warehouse.materials_list.label_fd184c2ce046')" width="180" :formatter="formatDateTimeCell" />
                <CompatTableColumn :label="t('warehouse.materials_list.label_ed31fbb483ee')" width="180" fixed="right" align="center">
                  <template #default="{ row: b }">
                    <UButton color="primary" variant="link" size="sm" @click="handleView(b)">{{ t('warehouse.materials_list.action_db8db0530432') }}</UButton>
                    <UButton v-if="canWrite" color="primary" variant="link" size="sm" @click="handleEdit(b)">{{ t('warehouse.materials_list.action_051836569928') }}</UButton>
                    <UButton
                      v-if="canWrite"
                      color="success"
                      variant="link"
                      size="sm"
                      :loading="labelGenerating === String(b.id)"
                      @click="handleGenerateLabel(b)"
                    >{{ t('warehouse.materials_list.action_9d9cf609f6ea') }}</UButton>
                  </template>
                </CompatTableColumn>
              </CompatTable>
            </template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_bfe9311ee679')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.docLabel }}</span>
              <UBadge variant="soft" v-if="row.isUnlinked" color="info" size="sm" class="doc-group-unlinked-tag">
                {{ t('warehouse.materials_list.text_2651a679b819') }}
              </UBadge>
            </template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_41e78d51282b')" width="130" align="center">
            <template #default="{ row }">{{ row.receivedAt || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_2f69582f9147')" width="110" align="right">
            <template #default="{ row }">{{ row.batchCount }} {{ t('warehouse.materials_list.text_adc46b52038f') }}</template>
          </el-table-column>
          <el-table-column :label="t('warehouse.materials_list.label_b9babe8735b9')" min-width="220">
            <template #default="{ row }">
              <!--
                ⛔ 一次收货可能混不同单位/物料, 绝不把不同单位的数量直接相加成一个假总数
                (owner 明确点名的第 6 个缺陷形状) —— 按单位逐条展示。数量算不出来的批次
                单独计数标出来, 不悄悄按 0 处理。
              -->
              <div v-for="t in (row.totalsByUnit as any[])" :key="t.unit" class="doc-group-unit-line">
                {{ fmtQty(t.total) }} {{ displayUnit(t.unit) }}
                <span v-if="t.unknownCount > 0" class="text-secondary">
                  {{ t('warehouse.materials_list.text_696d559002b1') }} {{ t.unknownCount }} {{ t('warehouse.materials_list.text_710f3cdd8f86') }}
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <CompatTable v-if="viewMode === 'detail'" :data="tableData" v-loading="loading" stripe border style="width: 100%">
        <template #empty><ListEmptyExplain empty-title="还没有入库批次" empty-hint="采购到货验收或生产完工入库后，批次会出现在这里；也可点「入库」手动登记。" /></template>
        <CompatTableColumn prop="batchNumber" :label="t('warehouse.materials_list.label_cb5386f1a98a')" width="160" />
        <CompatTableColumn :label="t('warehouse.materials_list.label_1eab99853e63')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.materialTypeName || row.materialName || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="supplierName" :label="t('warehouse.materials_list.label_033ed5945254')" min-width="150" show-overflow-tooltip />
        <CompatTableColumn :label="t('warehouse.materials_list.label_5e29d121dda0')" width="100" align="right">
          <template #default="{ row }">{{ row.quantity ?? row.currentQuantity ?? row.receiptQuantity ?? '-' }}</template>
        </CompatTableColumn>
        <!-- T159-A foldable #1: 显示批次实际称重/入库单位 (quantityUnit), 非主数据 unit
             Steve反馈: "改批次单位列表不变" — row.unit=主数据unit(箱/kg等), row.quantityUnit=实际入库单位 -->
        <CompatTableColumn :label="t('warehouse.materials_list.label_80b19d68b149')" width="80" align="center">
          <template #default="{ row }">{{ displayUnit(row.quantityUnit || row.unit) || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.materials_list.label_e9722902ed67')" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.boxCount != null">{{ t('warehouse.materials_list.text_5890c084b931') }} {{ row.boxCount }} {{ t('warehouse.materials_list.text_702dff992262') }}</span>
            <span v-else class="text-secondary">-</span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="status" :label="t('warehouse.materials_list.label_6320b4a8722a')" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="batchTagType(row)" size="small">
              {{ batchStatusDisplay(row, getStatusText).text }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="expiryDate" :label="t('warehouse.materials_list.label_a75622e03fcd')" width="120" />
        <CompatTableColumn prop="createdAt" :label="t('warehouse.materials_list.label_fd184c2ce046')" width="180" :formatter="formatDateTimeCell" />
        <!--
          ⛔ 2026-08-06: 合同号/供应商批次号是 V20261029_61 加的, 收货时能填了,
          但列表和详情**一个都没渲染** —— 写进去没人看得见, 等于没写。
          客户按合同号追溯来料正是这个功能存在的理由。
        -->
        <CompatTableColumn prop="contractNumber" :label="t('warehouse.materials_list.label_a9d0edf1196c')" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.contractNumber || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="supplierBatchNumber" :label="t('warehouse.materials_list.label_07ece109f912')" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierBatchNumber || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="factoryNumber" :label="t('warehouse.materials_list.label_3b1242372913')" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.factoryNumber || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="originPlace" :label="t('warehouse.materials_list.label_f4807f3dc756')" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.originPlace || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.materials_list.label_ed31fbb483ee')" width="180" fixed="right" align="center">
          <template #default="{ row }">
            <UButton color="primary" variant="link" size="sm" @click="handleView(row)">{{ t('warehouse.materials_list.action_db8db0530432') }}</UButton>
            <UButton v-if="canWrite" color="primary" variant="link" size="sm" @click="handleEdit(row)">{{ t('warehouse.materials_list.action_051836569928') }}</UButton>
            <!-- A5: 生成物料批次标签 (SP4) -->
            <UButton
              v-if="canWrite"
              color="success"
              variant="link"
              size="sm"
              :loading="labelGenerating === String(row.id)"
              @click="handleGenerateLabel(row)"
            >{{ t('warehouse.materials_list.action_9d9cf609f6ea') }}</UButton>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <div v-if="viewMode === 'detail'" class="pagination-wrapper">
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
      </template>
    </UCard>

    <!-- View Dialog -->
    <CompatDialog v-model="viewDialogVisible" :title="t('warehouse.materials_list.title_525d4710b5e4')" width="500px" destroy-on-close>
      <CompatDescriptions v-if="viewRecord" :column="1" border>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_cb5386f1a98a')">{{ viewRecord.batchNumber || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_1eab99853e63')">{{ viewRecord.materialTypeName || viewRecord.materialName || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_033ed5945254')">{{ viewRecord.supplierName || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_5e29d121dda0')">{{ viewRecord.quantity ?? viewRecord.currentQuantity ?? '-' }} {{ displayUnit(viewRecord.quantityUnit || viewRecord.unit) || '' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_e9722902ed67')">
          <span v-if="viewRecord.boxCount != null">{{ t('warehouse.materials_list.text_5890c084b931') }} {{ viewRecord.boxCount }} {{ t('warehouse.materials_list.text_702dff992262') }} <span class="text-secondary">{{ t('warehouse.materials_list.text_d32ffb8ab72e') }}</span></span>
          <span v-else>-</span>
        </CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_6320b4a8722a')">
          <el-tag :type="batchTagType(viewRecord)">{{ batchStatusDisplay(viewRecord, getStatusText).text }}</el-tag>
        </CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_a75622e03fcd')">{{ viewRecord.expiryDate || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_fd184c2ce046')">{{ formatDateTimeMinute(viewRecord.createdAt) }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_daede9881787')">{{ viewRecord.notes || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_a9d0edf1196c')">{{ viewRecord.contractNumber || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_07ece109f912')">{{ viewRecord.supplierBatchNumber || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_3b1242372913')">{{ viewRecord.factoryNumber || '-' }}</CompatDescriptionsItem>
        <CompatDescriptionsItem :label="t('warehouse.materials_list.label_f4807f3dc756')">{{ viewRecord.originPlace || '-' }}</CompatDescriptionsItem>
      </CompatDescriptions>
    </CompatDialog>

    <!-- 扫码查询结果 Dialog -->
    <CompatDialog v-model="scanResultVisible" :title="t('warehouse.materials_list.title_31327964e1fd')" width="480px" destroy-on-close>
      <CompatDescriptions v-if="scanResult" :column="1" border>
        <CompatDescriptionsItem v-for="(val, key) in scanResult" :key="String(key)" :label="String(key)">
          {{ val ?? '-' }}
        </CompatDescriptionsItem>
      </CompatDescriptions>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="scanResult = null">{{ t('warehouse.materials_list.action_3fd47edce45b') }}</UButton>
      </template>
    </CompatDialog>

    <!-- Create / Edit Dialog -->
    <CompatDialog v-model="formDialogVisible" :title="formDialogTitle" width="500px" destroy-on-close>
      <CompatForm ref="formRef" :model="formData" :rules="editingId ? undefined : formRules" label-width="80px">
        <CompatFormItem :label="t('warehouse.materials_list.label_cb5386f1a98a')" prop="batchNumber">
          <el-tooltip
            :content="editingId ? '批次号作为追溯标识, 创建后不可修改' : '批次号由系统自动生成 (一物一码), 防手敲重码, 无需填写'"
            placement="top-start"
          >
            <UInput
              v-model="formData.batchNumber"
              :placeholder="editingId ? '' : t('warehouse.materials_list.placeholder_6f51aa18270e')"
              :disabled="true"
            />
          </el-tooltip>
        </CompatFormItem>
        <!--
          账实红线 (owner 2026-08-20): 编辑既有批次时, 账实字段一律只读。
          ⛔ 不做成"只 disable 不说去哪" —— 那是 dead-end。下面那块 locked-hint 给出唯一正路。
        -->
        <div v-if="editingId" class="locked-hint">
          <div class="locked-hint__title">{{ t('warehouse.materials_list.text_d261a293c237') }}</div>
          <div class="locked-hint__body">
            {{ t('warehouse.materials_list.text_c2e91ea6da87') }}
          </div>
          <UButton color="primary" variant="link" size="sm" @click="goStocktake">{{ t('warehouse.materials_list.action_95cdfb20f8e8') }}</UButton>
          <!--
            ⛔ 入库日期**不能**混在上面那句里指向盘点 —— 盘点只改数量, 改不了日期。
            指错去处比不指更糟: 用户会走一趟、发现改不了、然后认定系统坏了。
          -->
          <div class="locked-hint__body locked-hint__aside">
            {{ t('warehouse.materials_list.text_1b1f308c547f') }}
          </div>
        </div>
        <CompatFormItem :label="t('warehouse.materials_list.label_1eab99853e63')" prop="materialTypeId">
          <CompatSelect v-model="formData.materialTypeId" :placeholder="t('warehouse.materials_list.placeholder_656b207c73ef')" filterable :disabled="!!editingId" style="width: 100%">
            <CompatOption v-for="mt in materialTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
          </CompatSelect>
          <UpstreamMissingHint v-if="materialTypes.length === 0 && !editingId" :description="t('warehouse.materials_list.description_f799fa762fed')" target-module="warehouse" require-write action-text="去创建物料类型" contact-text="请联系仓库管理员先创建物料类型" @action="goCreate('/warehouse/material-types')" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_033ed5945254')" prop="supplierId">
          <CompatSelect v-model="formData.supplierId" :placeholder="t('warehouse.materials_list.placeholder_a0106ac4aa3d')" filterable :disabled="!!editingId" style="width: 100%">
            <CompatOption v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </CompatSelect>
          <UpstreamMissingHint v-if="suppliers.length === 0 && !editingId" :description="t('warehouse.materials_list.description_e9c8318df7aa')" target-module="procurement" require-write action-text="去创建供应商" contact-text="请联系采购或管理员先创建供应商" @action="goCreate('/procurement/suppliers')" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_5e29d121dda0')" prop="receiptQuantity">
          <!--
            缺陷 B (2026-09-01, LIUSHANMEN 批次 MT-20260901-3312 实测): 这里原来是
            `:precision="1"`。el-input-number 的 displayValue 用 `currentValue.toFixed(precision)`
            —— (0.02).toFixed(1) = "0.0", 与列表/详情/DB 的 receipt_quantity=0.020000 对不上,
            看着像"数量丢了"(0.02 kg 是极小但合法的收货量, min=0.01 与 PendingPurchaseReceivingPanel
            的收货入口一致)。

            ⚠️ 底层 formData.receiptQuantity 本身没被这个精度污染——el-input-number 只在用户
            blur/change(实际输入)时才把截断值写回 v-model; 初始挂载走的是
            `watch(modelValue, ..., {immediate:true})` 分支, 只更新内部 currentValue(纯显示),
            不 emit UPDATE_MODEL_EVENT。也因此 buildUpdatePayload() 从不含 receiptQuantity,
            这条显示 bug 从未污染过提交体, 见 handleFormSubmit()。

            修法: 不传 :precision, 让组件按 `getPrecision(modelValue)` 动态取值——
            与列表/详情"原样打印数字"完全一致(0.02 → "0.02", 12.5 → "12.5", 5 → "5"),
            不再用一个与本字段无关的固定小数位截断真实收货量。
          -->
          <el-input-number v-model="formData.receiptQuantity" :min="0" :disabled="!!editingId" style="width: 100%" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_80b19d68b149')">
          <CompatSelect v-model="formData.quantityUnit" :disabled="!!editingId" style="width: 100%">
            <CompatOption label="kg" value="kg" />
            <CompatOption label="g" value="g" />
            <CompatOption label="L" value="L" />
            <CompatOption :label="t('warehouse.materials_list.label_3a116815bc9d')" value="个" />
            <CompatOption :label="t('warehouse.materials_list.label_702dff992262')" value="箱" />
            <!-- T158: include master unit as an option if it's not in the standard list above -->
            <CompatOption
              v-if="selectedMaterialUnit && !['kg','g','L','个','箱'].includes(selectedMaterialUnit)"
              :label="selectedMaterialUnit"
              :value="selectedMaterialUnit"
            />
          </CompatSelect>
          <div v-if="selectedMaterialUnit && editingId === null" class="field-hint">
            {{ t('warehouse.materials_list.text_3dd17eac2c88') }}{{ selectedMaterialUnit }}{{ t('warehouse.materials_list.text_59acdc369f8b') }}
          </div>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_e9722902ed67')">
          <el-input-number v-model="formData.boxCount" :min="0" :precision="0" :controls="true" :placeholder="t('warehouse.materials_list.placeholder_537352400c49')" style="width: 100%" />
          <div class="field-hint">{{ t('warehouse.materials_list.text_506ffbf8ccf2') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_12adbd42e7c9')" prop="receiptDate">
          <CompatDatePicker v-model="formData.receiptDate" type="date" value-format="YYYY-MM-DD" :placeholder="t('warehouse.materials_list.placeholder_a031d31dacf3')" :disabled="!!editingId" style="width: 100%" />
          <!--
            只读 + 正路入口必须同屏。⛔ 没资格的人看到的是"该找谁", 不是一个点下去必 403 的按钮
            (后端 RECEIPT_DATE_CORRECTION_ROLES 才是唯一 enforce 处, 这里只管展示)。
          -->
          <template v-if="editingId">
            <UButton v-if="canCorrectReceiptDate" color="primary" variant="link" size="sm" @click="openCorrectReceiptDate">
              {{ t('warehouse.materials_list.action_1db1f7b724b0') }}
            </UButton>
            <div v-else class="field-hint">
              {{ t('warehouse.materials_list.text_09b09ff58a64') }}
            </div>
          </template>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_084681bd2276')" prop="totalWeight">
          <el-input-number v-model="formData.totalWeight" :min="0" :precision="3" :disabled="!!editingId" style="width: 100%" />
        </CompatFormItem>
        <CompatFormItem v-if="canViewPrice" :label="t('warehouse.materials_list.label_cb3aaa7607be')" prop="totalValue">
          <el-input-number v-model="formData.totalValue" :min="0" :precision="2" :disabled="!!editingId" style="width: 100%" />
          <template v-if="editingId">
            <UButton v-if="canCorrectReceiptDate" color="primary" variant="link" size="sm" @click="openCostCorrection">
              {{ t('warehouse.materials_list.action_c232a6bccac1') }}
            </UButton>
            <div v-else class="field-hint">{{ t('warehouse.materials_list.text_d3665c421a6b') }}</div>
          </template>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_a75622e03fcd')">
          <CompatDatePicker v-model="formData.expireDate" type="date" value-format="YYYY-MM-DD" :placeholder="t('warehouse.materials_list.placeholder_6f5e8f0ed63a')" style="width: 100%" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_daede9881787')">
          <el-input v-model="formData.notes" type="textarea" :rows="2" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_3b1242372913')">
          <CompatSelect
            v-model="formData.factoryNumber"
            :placeholder="t('warehouse.materials_list.placeholder_aa8c31eb7340')"
            filterable
            clearable
            style="width: 100%"
            @change="handleManufacturerChange"
          >
            <CompatOption
              v-for="manufacturer in manufacturers"
              :key="manufacturer.id"
              :label="`${manufacturer.code} · ${manufacturer.name}`"
              :value="manufacturer.code"
            />
          </CompatSelect>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_f4807f3dc756')">
          <UInput v-model="formData.originPlace" :placeholder="t('warehouse.materials_list.placeholder_9d5414736191')" />
        </CompatFormItem>
        <!--
          #1844 L-d: 挂错了个体要有一条能走的路。解除走独立端点(原因必填、写操作日志),
          ⛔ 不放进编辑提交体 —— 那里 sourceIndividualId 空白会 400, null 是「不改」。
        -->
        <CompatFormItem v-if="editingId && editingSourceIndividualId" :label="t('warehouse.materials_list.label_13bb29369dab')">
          <div class="source-individual-link" data-testid="batch-source-individual">
            <span v-if="editingSourceIndividualCode">{{ t('warehouse.materials_list.text_b03d2fbde903') }} {{ editingSourceIndividualCode }}</span>
            <span v-else class="text-secondary">{{ t('warehouse.materials_list.text_1c60e8b479fc') }}</span>
            <UButton
              v-if="canWrite"
              color="error"
              variant="link"
              size="sm"
              :loading="unlinkingSourceIndividual"
              data-testid="batch-source-individual-unlink"
              @click="handleUnlinkSourceIndividual"
            >
              {{ t('warehouse.materials_list.action_a21a394ffd2c') }}
            </UButton>
          </div>
          <div class="field-hint">{{ t('warehouse.materials_list.text_a41dce9c8879') }}</div>
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="formDialogVisible = false">{{ t('warehouse.materials_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="formSaving" @click="handleFormSubmit">{{ t('warehouse.materials_list.action_fac2a67ad878') }}</UButton>
      </template>
    </CompatDialog>

    <CompatDialog v-model="costCorrectionDialogVisible" :title="t('warehouse.materials_list.title_04610ccd2512')" width="460px" destroy-on-close append-to-body>
      <div class="locked-hint">
        <div class="locked-hint__title">{{ t('warehouse.materials_list.text_3e8a667cc31e') }}</div>
        <div class="locked-hint__body">{{ t('warehouse.materials_list.text_6995924ac8b0') }}</div>
      </div>
      <CompatForm label-width="110px">
        <CompatFormItem :label="t('warehouse.materials_list.label_cb5386f1a98a')"><el-input :model-value="costCorrectionBatchNumber" disabled /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_f7615229414c')"><el-input :model-value="costCorrectionOriginal ?? '未估值'" disabled /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_eaf7eed714e3')" required>
          <el-input-number v-model="costCorrectionForm.unitPrice" :min="0.000001" :precision="6" style="width: 100%" />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_2e5a23e8f191')" required>
          <el-input v-model="costCorrectionForm.reason" type="textarea" :rows="3" :maxlength="CORRECTION_REASON_MAX_LENGTH" show-word-limit :placeholder="t('warehouse.materials_list.placeholder_341f740046ee')" />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="costCorrectionDialogVisible = false">{{ t('warehouse.materials_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="costCorrectionSaving" @click="submitCostCorrection">{{ t('warehouse.materials_list.action_b5258814d894') }}</UButton>
      </template>
    </CompatDialog>

    <!--
      更正入库日期 —— 入库日期被锁之后唯一的正路。
      与「编辑批次」刻意分开: 编辑是随手改描述, 这里是一次有原因、要留痕的更正。
    -->
    <CompatDialog v-model="correctDateDialogVisible" :title="t('warehouse.materials_list.title_7b70f0c11c34')" width="460px" destroy-on-close append-to-body>
      <div class="locked-hint">
        <div class="locked-hint__title">{{ t('warehouse.materials_list.text_f7a90973a4ab') }}</div>
        <div class="locked-hint__body">
          {{ t('warehouse.materials_list.text_25ade7482200') }}
        </div>
      </div>
      <CompatForm label-width="110px">
        <CompatFormItem :label="t('warehouse.materials_list.label_cb5386f1a98a')">
          <el-input :model-value="correctDateBatchNumber" disabled />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_487e34b8b62c')">
          <el-input :model-value="correctDateOriginal || '—'" disabled />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_eaf7eed714e3')" required>
          <el-date-picker
            v-model="correctDateForm.receiptDate"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="t('warehouse.materials_list.placeholder_b73ad2297cde')"
            :disabled-date="disableFutureDate"
            style="width: 100%"
          />
          <div class="field-hint">{{ t('warehouse.materials_list.text_67bd5a636cad') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.materials_list.label_e1a5e211ea1c')" required>
          <el-input
            v-model="correctDateForm.reason"
            type="textarea"
            :rows="3"
            :maxlength="CORRECTION_REASON_MAX_LENGTH"
            show-word-limit
            :placeholder="t('warehouse.materials_list.placeholder_85b7c69cc725')"
          />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="correctDateDialogVisible = false">{{ t('warehouse.materials_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="correctDateSaving" @click="submitCorrectReceiptDate">
          {{ t('warehouse.materials_list.action_b5258814d894') }}
        </UButton>
      </template>
    </CompatDialog>

  </div>
  </CanvasAwareWrapper>
</template>

<style lang="scss" scoped>
.page-wrapper {
  height: 100%;
  width: 100%;
  display: flex;
  flex-direction: column;
}

.field-hint {
  font-size: 12px;
  color: var(--text-color-secondary, var(--cs-muted));
  line-height: 1.4;
  margin-top: 4px;
}

.text-secondary {
  font-size: 12px;
  color: var(--text-color-secondary, var(--cs-muted));
}

/* 账实字段只读时的说明块 —— 必须同时说清"为什么不能改"和"那去哪改" (防呆: 无 dead-end)。 */
.locked-hint {
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 4px;
  background: var(--el-color-warning-light-9, var(--cs-warning-soft));
  border: 1px solid var(--el-color-warning-light-7, #f5dab1);

  .locked-hint__title {
    font-size: 13px;
    font-weight: 600;
    color: var(--el-color-warning, var(--cs-warning));
    margin-bottom: 4px;
  }

  .locked-hint__body {
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-color-secondary, var(--cs-muted));
  }

  /* 入库日期那条例外说明: 与上面"去盘点"那条出口分开, 免得被读成同一条路。 */
  .locked-hint__aside {
    margin-top: 6px;
    padding-top: 6px;
    border-top: 1px dashed var(--el-color-warning-light-7, #f5dab1);
  }
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
  align-items: center;
}

.inbound-status-tabs { margin-top: 0; }
.batch-toolbar { margin-top: 4px; }

.summary-hint {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--text-color-secondary, var(--cs-muted));
}

.summary-warning {
  color: var(--el-color-warning, var(--cs-warning));
}

.nested-batch-table {
  width: calc(100% - 48px);
  margin-left: 48px;
}

.doc-group-unlinked-tag {
  margin-left: 8px;
}

.doc-group-unit-line {
  line-height: 1.6;
}

.el-table {
  flex: 1;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  border-top: 1px solid var(--border-color-lighter, var(--cs-border));
  margin-top: 16px;
}
</style>
