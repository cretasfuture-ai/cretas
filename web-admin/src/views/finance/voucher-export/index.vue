<script setup lang="ts">
/**
 * SP11 F3/F4/F5/F6 — 凭证导出 + 科目映射配置 + 导出配置 CRUD
 *
 * 防呆设计 (per fool-proof-design.md):
 * - Rule 1: 日期范围必填, 缺失禁用导出
 * - Rule 2: CRUD dialog 带配置名称/编号作上下文
 * - Rule 3: 删除操作先弹确认 (SalesConfirmModal) 而非即时删除
 * - Rule 4: 导出防重 (loading 态 disabled); 新建配置 409 提示 "已有，请使用更新操作"
 * - Rule 5: SP6 未完成提示 + next action
 * - 4位一体: error toast sticky(duration:0, showClose=true), 后端真实 message
 *
 * Route: /finance/voucher-export
 * RBAC: finance:read_write (导出/CRUD); finance:read (查看)
 *
 * Endpoints:
 *   POST /api/mobile/{factoryId}/finance/voucher-export          — 序时账 xlsx
 *   GET  /api/mobile/{factoryId}/finance/subject-balance/export  — 科目余额 xlsx
 *   GET  /api/mobile/{factoryId}/finance/voucher-import-template — 金蝶导入模板 xlsx
 *                                                                   (?targetSystem=KINGDEE_YXSKY 默认 | KINGDEE_KIS)
 *   GET    /api/mobile/{factoryId}/finance/export-config          — 查列表
 *   POST   /api/mobile/{factoryId}/finance/export-config          — 新建
 *   PUT    /api/mobile/{factoryId}/finance/export-config/{id}     — 更新
 *   DELETE /api/mobile/{factoryId}/finance/export-config/{id}     — 软删除
 *   GET    /api/mobile/{factoryId}/finance/subject-mappings       — 查列表
 *   POST   /api/mobile/{factoryId}/finance/subject-mappings       — upsert
 *   DELETE /api/mobile/{factoryId}/finance/subject-mappings/{id}  — 软删除
 */
import { ref, computed, onMounted } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { ElMessage } from 'element-plus';
import type { CsTableColumn } from '@/types/csTable';
// 通用确认弹窗 (UModal, 取代 ElMessageBox)。
import SalesConfirmModal from '@/views/sales/components/SalesConfirmModal.vue';
import { get, post, put, del } from '@/api/request';
import request from '@/api/request';

// ============================================================
// Types — mirror backend DTOs (camelCase, backend truth)
// ============================================================
type VoucherTargetSystem = 'KINGDEE' | 'YONYOU' | 'CUSTOM' | 'KINGDEE_YXSKY' | 'KINGDEE_KIS';
/** Kingdee 导入模板专用: 只有云星空/KIS-K3 两种列集实现, 见后端 VoucherExportServiceImpl. */
type KingdeeImportTarget = 'KINGDEE_YXSKY' | 'KINGDEE_KIS';
// SP6 SettlementType enum values (PREPAID/CREDIT_FIRST/NO_INVOICE/MONTHLY/CREDIT_PERIOD/IMMEDIATE)
type SettlementType = 'PREPAID' | 'CREDIT_FIRST' | 'NO_INVOICE' | 'MONTHLY' | 'CREDIT_PERIOD' | 'IMMEDIATE';
type LedgerReportType = 'chronological' | 'general' | 'subsidiary' | 'trialBalance' | 'incomeStatement' | 'quantityAmount';

interface VoucherExportConfigDTO {
  id?: string;
  factoryId?: string;
  targetSystem: VoucherTargetSystem;
  colVoucherNo: string;
  colDate: string;
  colSummary: string;
  colSubjectCode: string;
  colSubjectName: string;
  colDebit: string;
  colCredit: string;
  colAuxiliary: string;
  colCurrency: string;
  isActive?: boolean;
}

interface VoucherSubjectMappingDTO {
  id?: string;
  factoryId?: string;
  settlementType: SettlementType;
  businessType: string | null;
  debitSubjectCode: string;
  debitSubjectName: string;
  creditSubjectCode: string;
  creditSubjectName: string;
  remark: string | null;
}

interface LedgerReportDefinition {
  type: LedgerReportType;
  label: string;
  endpoint: string;
  fallbackPrefix: string;
  headers: string[];
}

// ============================================================
// Stores
// ============================================================
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId ?? '');
const canRead = computed(() => permissionStore.canAccess('finance'));
const canWrite = computed(() => permissionStore.canWrite('finance'));

const confirmRef = ref<InstanceType<typeof SalesConfirmModal>>();

// ============================================================
// Export state
// ============================================================
const exportLoading = ref(false);
const balanceExportLoading = ref(false);
const kingdeeTemplateLoading = ref(false);
const ledgerExportLoading = ref<Record<LedgerReportType, boolean>>({
  chronological: false,
  general: false,
  subsidiary: false,
  trialBalance: false,
  incomeStatement: false,
  quantityAmount: false,
});
// H-FP-3 Rule4: 预填当月首末日，财务几乎按月导出，不让用户每次手动选
function currentMonthRange(): [string, string] {
  const now = new Date();
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const lastDay = new Date(y, now.getMonth() + 1, 0).getDate();
  return [`${y}-${m}-01`, `${y}-${m}-${String(lastDay).padStart(2, '0')}`];
}
const exportDateRange = ref<[string, string] | null>(currentMonthRange());
const exportTargetSystem = ref<VoucherTargetSystem>('KINGDEE');
const selectedConfigId = ref('');
// 六膳门用哪个金蝶版本就导哪种格式 (fool-proof Rule 3: 约束选择而非猜测).
// 不同版本原生"引入"接受不同文件格式: 云星空→.xlsx, KIS专业版→legacy .xls, KIS标准版/迷你版/K3→.dbf
type KingdeeExportFormat = 'XLSX' | 'XLS' | 'DBF';
type KingdeeEdition = 'YXSKY_XLSX' | 'KIS_XLS' | 'KIS_DBF';
interface KingdeeEditionDef {
  value: KingdeeEdition;
  label: string;
  targetSystem: KingdeeImportTarget;
  format: KingdeeExportFormat;
  ext: string;
}
const KINGDEE_EDITIONS: KingdeeEditionDef[] = [
  { value: 'YXSKY_XLSX', label: '金蝶云星空 (.xlsx)', targetSystem: 'KINGDEE_YXSKY', format: 'XLSX', ext: 'xlsx' },
  { value: 'KIS_XLS', label: '金蝶KIS专业版 (.xls)', targetSystem: 'KINGDEE_KIS', format: 'XLS', ext: 'xls' },
  { value: 'KIS_DBF', label: '金蝶KIS标准版/迷你版/K3 (.dbf)', targetSystem: 'KINGDEE_KIS', format: 'DBF', ext: 'dbf' },
];
const kingdeeEdition = ref<KingdeeEdition>('YXSKY_XLSX');
const selectedKingdeeEdition = computed<KingdeeEditionDef>(
  () => KINGDEE_EDITIONS.find((e) => e.value === kingdeeEdition.value) ?? KINGDEE_EDITIONS[0]
);

// 日期范围: 两个原生日期输入, 仍落在同一个 exportDateRange 上 (清空任一端 => 不能导出)。
const rangeStart = computed({
  get: () => exportDateRange.value?.[0] ?? '',
  set: (v: string) => { exportDateRange.value = [v ?? '', exportDateRange.value?.[1] ?? '']; },
});
const rangeEnd = computed({
  get: () => exportDateRange.value?.[1] ?? '',
  set: (v: string) => { exportDateRange.value = [exportDateRange.value?.[0] ?? '', v ?? '']; },
});

const canExport = computed(() =>
  !!exportDateRange.value && !!exportDateRange.value[0] && !!exportDateRange.value[1]
);

const ledgerReports: LedgerReportDefinition[] = [
  {
    type: 'chronological',
    label: '序时账 / 日记账',
    endpoint: 'chronological-ledger/export',
    fallbackPrefix: 'chronological_ledger',
    headers: ['日期', '凭证字号', '摘要', '科目编码', '科目名称', '借方金额', '贷方金额', '方向', '余额'],
  },
  {
    type: 'general',
    label: '总账',
    endpoint: 'general-ledger/export',
    fallbackPrefix: 'general_ledger',
    headers: ['科目编码', '科目名称', '期初(借/贷)', '本期借方', '本期贷方', '本年累计', '期末(借/贷)'],
  },
  {
    type: 'subsidiary',
    label: '明细账',
    endpoint: 'subsidiary-ledger/export',
    fallbackPrefix: 'subsidiary_ledger',
    headers: ['科目编码/名称分段', '日期', '凭证字号', '摘要', '借方', '贷方', '方向', '余额'],
  },
  {
    type: 'trialBalance',
    label: '试算平衡表',
    endpoint: 'trial-balance/export',
    fallbackPrefix: 'trial_balance',
    headers: ['科目编码', '科目名称', '期初借方', '期初贷方', '本期借方', '本期贷方', '期末借方', '期末贷方'],
  },
  {
    type: 'incomeStatement',
    label: '利润表',
    endpoint: 'income-statement/export',
    fallbackPrefix: 'income_statement',
    headers: ['项目', '本期金额', '本年累计金额'],
  },
  {
    type: 'quantityAmount',
    label: '数量金额明细账',
    endpoint: 'quantity-amount-ledger/export',
    fallbackPrefix: 'quantity_amount_ledger',
    headers: ['日期', '凭证字号', '摘要', '收入数量', '收入单价', '收入金额', '发出数量', '发出单价', '发出金额', '结存数量', '结存单价', '结存金额'],
  },
];

// ============================================================
// Export Config state
// ============================================================
const configLoading = ref(false);
const configList = ref<VoucherExportConfigDTO[]>([]);
const configDialogVisible = ref(false);
const configDialogMode = ref<'create' | 'edit'>('create');
const configSaveLoading = ref(false);
const editingConfigId = ref('');

const emptyConfig = (): VoucherExportConfigDTO => ({
  targetSystem: 'KINGDEE',
  colVoucherNo: '凭证字号',
  colDate: '日期',
  colSummary: '摘要',
  colSubjectCode: '科目编码',
  colSubjectName: '科目名称',
  colDebit: '借方金额',
  colCredit: '贷方金额',
  colAuxiliary: '辅助核算',
  colCurrency: '币别',
  isActive: true,
});
const configForm = ref<VoucherExportConfigDTO>(emptyConfig());

// ============================================================
// Subject Mapping state
// ============================================================
const mappingLoading = ref(false);
const mappingList = ref<VoucherSubjectMappingDTO[]>([]);
const mappingDialogVisible = ref(false);
const mappingSaveLoading = ref(false);
const editingMappingId = ref('');

const SETTLEMENT_TYPE_LABELS: Record<SettlementType, string> = {
  PREPAID: '预付',
  CREDIT_FIRST: '赊销先入库',
  NO_INVOICE: '未到票（暂估）',
  MONTHLY: '月结',
  CREDIT_PERIOD: '账期',
  IMMEDIATE: '现结',
};

const emptyMapping = (): VoucherSubjectMappingDTO => ({
  settlementType: 'MONTHLY',
  businessType: 'PURCHASE',
  debitSubjectCode: '1405',
  debitSubjectName: '原材料',
  creditSubjectCode: '2202',
  creditSubjectName: '应付账款',
  remark: null,
});
const mappingForm = ref<VoucherSubjectMappingDTO>(emptyMapping());

// ============================================================
// Load data
// ============================================================
async function loadConfigs() {
  if (!factoryId.value) return;
  configLoading.value = true;
  try {
    const res = await get<VoucherExportConfigDTO[]>(
      `/${factoryId.value}/finance/export-config`
    );
    if (res.success && res.data) configList.value = res.data;
  } catch {
    // 拦截器已 toast
  } finally {
    configLoading.value = false;
  }
}

async function loadMappings() {
  if (!factoryId.value) return;
  mappingLoading.value = true;
  try {
    const res = await get<VoucherSubjectMappingDTO[]>(
      `/${factoryId.value}/finance/subject-mappings`
    );
    if (res.success && res.data) mappingList.value = res.data;
  } catch {
    // 拦截器已 toast
  } finally {
    mappingLoading.value = false;
  }
}

function downloadBlob(response: unknown, fallbackName: string) {
  const res = response as { headers?: Record<string, unknown>; data?: BlobPart };
  const rawDisposition = res.headers?.['content-disposition'];
  const contentDisposition = typeof rawDisposition === 'string' ? rawDisposition : String(rawDisposition ?? '');
  let filename = fallbackName;
  const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;]+)/i);
  if (match) filename = decodeURIComponent(match[1].trim());

  const url = URL.createObjectURL(new Blob([res.data ?? '']));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

// ============================================================
// 凭证序时账导出
// ============================================================
async function handleVoucherExport() {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  exportLoading.value = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const body: Record<string, string> = {
      startDate,
      endDate,
      targetSystem: exportTargetSystem.value,
    };
    if (selectedConfigId.value) body.configId = selectedConfigId.value;

    // _keepResponse: true 必须 — 否则拦截器把 blob 解成信封, response.headers 丢失,
    // downloadBlob 永远拿不到服务端 Content-Disposition 文件名 (2026-07-03 sweep, 同 #1172 根因).
    const response = await request.post(
      `/${factoryId.value}/finance/voucher-export`,
      body,
      { responseType: 'blob', _keepResponse: true }
    );

    downloadBlob(response, `voucher_${startDate}_${endDate}.xlsx`);

    ElMessage({ message: '凭证序时账已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    exportLoading.value = false;
  }
}

// ============================================================
// 科目余额表导出
// ============================================================
async function handleBalanceExport() {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  balanceExportLoading.value = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const response = await request.get(
      `/${factoryId.value}/finance/subject-balance/export`,
      {
        params: { startDate, endDate, targetSystem: exportTargetSystem.value },
        responseType: 'blob',
        _keepResponse: true,
      }
    );

    downloadBlob(response, `subject_balance_${startDate}_${endDate}.xlsx`);

    ElMessage({ message: '科目余额表已导出', type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast
  } finally {
    balanceExportLoading.value = false;
  }
}

async function handleKingdeeImportTemplate() {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  const edition = selectedKingdeeEdition.value;
  kingdeeTemplateLoading.value = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const response = await request.get(
      `/${factoryId.value}/finance/voucher-import-template`,
      {
        params: { startDate, endDate, targetSystem: edition.targetSystem, format: edition.format },
        responseType: 'blob',
        _keepResponse: true,
      }
    );

    const filePrefix = edition.targetSystem === 'KINGDEE_KIS'
      ? 'kingdee_kis_voucher'
      : 'kingdee_yxsky_import_template';
    downloadBlob(response, `${filePrefix}_${startDate}_${endDate}.${edition.ext}`);
    ElMessage({
      message: `${edition.label}导入文件已导出`,
      type: 'success',
      duration: 3000,
    });
  } catch {
    // 拦截器已 toast
  } finally {
    kingdeeTemplateLoading.value = false;
  }
}

async function handleLedgerExport(report: LedgerReportDefinition) {
  if (!canExport.value) {
    ElMessage({ message: '请先选择日期范围', type: 'warning', duration: 3000 });
    return;
  }
  if (!factoryId.value) return;

  ledgerExportLoading.value[report.type] = true;
  try {
    const [startDate, endDate] = exportDateRange.value!;
    const response = await request.get(
      `/${factoryId.value}/finance/${report.endpoint}`,
      {
        params: { startDate, endDate, targetSystem: exportTargetSystem.value },
        responseType: 'blob',
        _keepResponse: true,
      }
    );

    downloadBlob(response, `${report.fallbackPrefix}_${startDate}_${endDate}.xlsx`);
    ElMessage({ message: `${report.label}已导出`, type: 'success', duration: 3000 });
  } catch {
    // 拦截器已 toast；试算平衡不平会透出后端 409 message
  } finally {
    ledgerExportLoading.value[report.type] = false;
  }
}

// ============================================================
// Export Config CRUD
// ============================================================
function openConfigCreate() {
  configDialogMode.value = 'create';
  editingConfigId.value = '';
  configForm.value = emptyConfig();
  configDialogVisible.value = true;
}

function openConfigEdit(row: VoucherExportConfigDTO) {
  configDialogMode.value = 'edit';
  editingConfigId.value = row.id ?? '';
  configForm.value = { ...row };
  configDialogVisible.value = true;
}

async function handleConfigSave() {
  if (!factoryId.value) return;
  configSaveLoading.value = true;
  try {
    if (configDialogMode.value === 'create') {
      const res = await post<VoucherExportConfigDTO>(
        `/${factoryId.value}/finance/export-config`,
        configForm.value
      );
      if (res.success) {
        ElMessage({ message: '导出配置已创建', type: 'success', duration: 3000 });
        configDialogVisible.value = false;
        await loadConfigs();
      }
    } else {
      const res = await put<VoucherExportConfigDTO>(
        `/${factoryId.value}/finance/export-config/${editingConfigId.value}`,
        configForm.value
      );
      if (res.success) {
        ElMessage({ message: '导出配置已更新', type: 'success', duration: 3000 });
        configDialogVisible.value = false;
        await loadConfigs();
      }
    }
  } catch {
    // 拦截器已 toast (含 409 Rule 4 幂等)
  } finally {
    configSaveLoading.value = false;
  }
}

async function handleConfigDelete(row: VoucherExportConfigDTO) {
  if (!factoryId.value) return;
  // Rule 3: 删除前 confirm + 上下文
  const ok = await confirmRef.value!.ask({
    title: '删除确认',
    message: `确认删除 "${row.targetSystem}" 导出配置？此操作不可恢复。`,
    confirmText: '删除',
    danger: true,
  });
  if (ok === null) return;
  try {
    const res = await del(`/${factoryId.value}/finance/export-config/${row.id}`);
    if (res.success) {
      ElMessage({ message: '删除成功', type: 'success', duration: 3000 });
      await loadConfigs();
    }
  } catch {
    // 拦截器已 toast
  }
}

// ============================================================
// Subject Mapping CRUD
// ============================================================
function openMappingCreate() {
  editingMappingId.value = '';
  mappingForm.value = emptyMapping();
  mappingDialogVisible.value = true;
}

function openMappingEdit(row: VoucherSubjectMappingDTO) {
  editingMappingId.value = row.id ?? '';
  mappingForm.value = { ...row };
  mappingDialogVisible.value = true;
}

async function handleMappingSave() {
  if (!factoryId.value) return;
  // fool-proof Rule 1: 字段级校验, 不静默丢给后端报晦涩错误.
  if (!mappingForm.value.settlementType) {
    ElMessage.warning('请选择结算方式');
    return;
  }
  if (!mappingForm.value.debitSubjectCode) {
    ElMessage.warning('请填写借方科目代码');
    return;
  }
  if (!mappingForm.value.creditSubjectCode) {
    ElMessage.warning('请填写贷方科目代码');
    return;
  }
  mappingSaveLoading.value = true;
  try {
    const res = await post<VoucherSubjectMappingDTO>(
      `/${factoryId.value}/finance/subject-mappings`,
      mappingForm.value
    );
    if (res.success) {
      ElMessage({ message: '科目映射已保存', type: 'success', duration: 3000 });
      mappingDialogVisible.value = false;
      await loadMappings();
    }
  } catch {
    // 拦截器已 toast
  } finally {
    mappingSaveLoading.value = false;
  }
}

async function handleMappingDelete(row: VoucherSubjectMappingDTO) {
  if (!factoryId.value) return;
  const label = SETTLEMENT_TYPE_LABELS[row.settlementType] ?? row.settlementType;
  const ok = await confirmRef.value!.ask({
    title: '删除确认',
    message: `确认删除结算方式「${label}」的科目映射？`,
    confirmText: '删除',
    danger: true,
  });
  if (ok === null) return;
  try {
    const res = await del(`/${factoryId.value}/finance/subject-mappings/${row.id}`);
    if (res.success) {
      ElMessage({ message: '删除成功', type: 'success', duration: 3000 });
      await loadMappings();
    }
  } catch {
    // 拦截器已 toast
  }
}

function getTargetSystemLabel(ts: VoucherTargetSystem): string {
  const map: Record<VoucherTargetSystem, string> = {
    KINGDEE: '金蝶',
    YONYOU: '用友',
    CUSTOM: '通用',
    KINGDEE_YXSKY: '金蝶云星空',
    KINGDEE_KIS: '金蝶KIS/K3',
  };
  return map[ts] ?? ts;
}

function getSettlementLabel(st: SettlementType): string {
  return SETTLEMENT_TYPE_LABELS[st] ?? st;
}

// ============================================================
// Nuxt UI 选项 / 列定义 (USelect 不接受空字符串值, 用哨兵值映射回原有的 '' / null 语义)
// ============================================================
const TARGET_SYSTEM_ITEMS = [
  { label: '金蝶', value: 'KINGDEE' },
  { label: '用友', value: 'YONYOU' },
  { label: '通用', value: 'CUSTOM' },
];
const CONFIG_TARGET_ITEMS = [
  { label: '金蝶', value: 'KINGDEE' },
  { label: '金蝶云星空', value: 'KINGDEE_YXSKY' },
  { label: '金蝶KIS/K3', value: 'KINGDEE_KIS' },
  { label: '用友', value: 'YONYOU' },
  { label: '通用', value: 'CUSTOM' },
];
const SETTLEMENT_ITEMS = (Object.keys(SETTLEMENT_TYPE_LABELS) as SettlementType[])
  .map((v) => ({ label: SETTLEMENT_TYPE_LABELS[v], value: v }));
const NONE = '__NONE__';
const BUSINESS_TYPE_ITEMS = [
  { label: '通用（不区分）', value: NONE },
  { label: '采购', value: 'PURCHASE' },
  { label: '销售', value: 'SALES' },
];
const BUSINESS_TYPE_LABELS: Record<string, string> = { PURCHASE: '采购', SALES: '销售' };
function businessTypeText(v: string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '通用';
  return BUSINESS_TYPE_LABELS[v] ?? v;
}
const businessTypeModel = computed({
  get: () => mappingForm.value.businessType ?? NONE,
  set: (v: string) => { mappingForm.value.businessType = v === NONE ? null : v; },
});
const remarkModel = computed({
  get: () => mappingForm.value.remark ?? '',
  set: (v: string) => { mappingForm.value.remark = v === '' ? null : v; },
});
const configSelectItems = computed(() => [
  { label: '使用默认配置', value: NONE },
  ...configList.value.map((cfg) => ({
    label: `${getTargetSystemLabel(cfg.targetSystem)} — ${cfg.colVoucherNo}`,
    value: cfg.id ?? '',
  })),
]);
const selectedConfigModel = computed({
  get: () => selectedConfigId.value || NONE,
  set: (v: string) => { selectedConfigId.value = v === NONE ? '' : v; },
});

const configColumns = computed<CsTableColumn<VoucherExportConfigDTO>[]>(() => {
  const cols: CsTableColumn<VoucherExportConfigDTO>[] = [
    { id: 'targetSystem', header: '目标系统' },
    { accessorKey: 'colVoucherNo', header: '凭证字号列名' },
    { accessorKey: 'colDate', header: '日期列名' },
    { accessorKey: 'colSummary', header: '摘要列名' },
    { accessorKey: 'colSubjectCode', header: '科目编码列名' },
    { accessorKey: 'colSubjectName', header: '科目名称列名' },
    { accessorKey: 'colDebit', header: '借方金额列名' },
    { accessorKey: 'colCredit', header: '贷方金额列名' },
    { accessorKey: 'colAuxiliary', header: '辅助核算列名' },
    { accessorKey: 'colCurrency', header: '币别列名' },
    { id: 'isActive', header: '状态' },
  ];
  if (canWrite.value) cols.push({ id: 'actions', header: '操作' });
  return cols;
});

const mappingColumns = computed<CsTableColumn<VoucherSubjectMappingDTO>[]>(() => {
  const cols: CsTableColumn<VoucherSubjectMappingDTO>[] = [
    { id: 'settlementType', header: '结算方式' },
    { id: 'businessType', header: '业务类型' },
    { accessorKey: 'debitSubjectCode', header: '借方科目代码' },
    { accessorKey: 'debitSubjectName', header: '借方科目名称' },
    { accessorKey: 'creditSubjectCode', header: '贷方科目代码' },
    { accessorKey: 'creditSubjectName', header: '贷方科目名称' },
    { id: 'remark', header: '备注' },
  ];
  if (canWrite.value) cols.push({ id: 'actions', header: '操作' });
  return cols;
});

// ============================================================
// Init
// ============================================================
onMounted(async () => {
  await Promise.all([loadConfigs(), loadMappings()]);
});
</script>

<template>
  <div class="page" data-testid="voucher-export-page">
    <!-- === 导出操作区 === -->
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>凭证导出</h2>
        <span class="count">凭证序时账 / 科目余额表 / 常用账簿</span>
      </header>

      <div class="export-body">
        <div class="export-row">
          <UFormField label="期间范围" required>
            <div class="range">
              <UInput v-model="rangeStart" type="date" class="w-40" data-testid="export-start" />
              <span class="range-sep">至</span>
              <UInput v-model="rangeEnd" type="date" class="w-40" data-testid="export-end" />
            </div>
          </UFormField>
          <UFormField label="目标系统">
            <USelect v-model="exportTargetSystem" :items="TARGET_SYSTEM_ITEMS" class="w-32" data-testid="export-target" />
          </UFormField>
          <UFormField label="列名配置">
            <USelect v-model="selectedConfigModel" :items="configSelectItems" class="w-56" data-testid="export-config-select" />
          </UFormField>
          <div class="export-actions">
            <UButton
              icon="i-lucide-download"
              :loading="exportLoading"
              :disabled="!canExport || !canWrite"
              data-testid="export-voucher"
              @click="handleVoucherExport"
            >导出凭证序时账</UButton>
            <UButton
              color="neutral" variant="outline"
              icon="i-lucide-download"
              :loading="balanceExportLoading"
              :disabled="!canExport || !canWrite"
              data-testid="export-balance"
              @click="handleBalanceExport"
            >导出科目余额表</UButton>
          </div>
        </div>

        <div class="export-row">
          <UFormField label="金蝶版本/格式">
            <USelect v-model="kingdeeEdition" :items="KINGDEE_EDITIONS" class="w-80" data-testid="export-kingdee-edition" />
          </UFormField>
          <div class="export-actions">
            <UButton
              color="success"
              icon="i-lucide-download"
              :loading="kingdeeTemplateLoading"
              :disabled="!canExport || !canWrite"
              data-testid="export-kingdee"
              @click="handleKingdeeImportTemplate"
            >导出{{ selectedKingdeeEdition.label }}</UButton>
          </div>
          <span class="form-hint">
            按贵司实际使用的金蝶版本选择: 云星空→.xlsx; KIS专业版→.xls; KIS标准版/迷你版/K3→.dbf
          </span>
        </div>

        <div class="ledger-report-grid">
          <div
            v-for="report in ledgerReports"
            :key="report.type"
            class="ledger-report-panel"
          >
            <div class="ledger-report-top">
              <span class="ledger-report-title">{{ report.label }}</span>
              <UButton
                size="xs" color="neutral" variant="outline"
                icon="i-lucide-download"
                :loading="ledgerExportLoading[report.type]"
                :disabled="!canExport || !canRead"
                :data-testid="`export-ledger-${report.type}`"
                @click="handleLedgerExport(report)"
              >导出</UButton>
            </div>
            <div class="ledger-header-preview">
              <UBadge
                v-for="header in report.headers"
                :key="`${report.type}-${header}`"
                color="neutral" variant="outline" size="sm"
              >{{ header }}</UBadge>
            </div>
          </div>
        </div>

        <UAlert
          v-if="!canRead"
          color="warning" variant="soft" icon="i-lucide-triangle-alert"
          title="导出需要财务读取权限，当前账号无权操作"
        />
        <UAlert
          v-else-if="!canWrite"
          color="warning" variant="soft" icon="i-lucide-triangle-alert"
          title="凭证序时账 / 科目余额表导出需要财务读写权限；常用账簿导出使用财务读取权限"
        />
        <UAlert
          v-if="!canExport && canRead"
          color="info" variant="soft" icon="i-lucide-info"
          title="请先选择期间日期范围再导出"
        />
      </div>
    </section>

    <!-- === 导出配置 CRUD === -->
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>凭证导出配置（列名映射）</h2>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" size="xs" icon="i-lucide-refresh-cw" aria-label="刷新" title="刷新" :loading="configLoading" @click="loadConfigs" />
          <UButton v-if="canWrite" size="xs" icon="i-lucide-plus" data-testid="config-create" @click="openConfigCreate">新建配置</UButton>
        </div>
      </header>

      <UTable :data="configList" :columns="configColumns" :loading="configLoading" class="cs-table" data-testid="config-table">
        <template #empty>暂无配置，点击「新建配置」创建</template>
        <template #targetSystem-cell="{ row }">
          <UBadge :color="row.original.targetSystem === 'KINGDEE' ? 'primary' : 'success'" variant="soft">{{ getTargetSystemLabel(row.original.targetSystem) }}</UBadge>
        </template>
        <template #isActive-cell="{ row }">
          <UBadge :color="row.original.isActive ? 'success' : 'neutral'" variant="soft">{{ row.original.isActive ? '启用' : '停用' }}</UBadge>
        </template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" icon="i-lucide-pencil" data-testid="config-edit" @click="openConfigEdit(row.original)">编辑</UButton>
            <UButton color="error" variant="link" size="xs" icon="i-lucide-trash-2" data-testid="config-delete" @click="handleConfigDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- === 科目映射 CRUD === -->
    <section class="cs-card">
      <header class="cs-card-head">
        <h2 title="配置采购结算属性对应的会计借贷科目，用于自动生成凭证分录">结算属性 → 会计科目映射</h2>
        <div class="head-actions">
          <UButton color="neutral" variant="outline" size="xs" icon="i-lucide-refresh-cw" aria-label="刷新" title="刷新" :loading="mappingLoading" @click="loadMappings" />
          <UButton v-if="canWrite" size="xs" icon="i-lucide-plus" data-testid="mapping-create" @click="openMappingCreate">新增映射</UButton>
        </div>
      </header>

      <UTable :data="mappingList" :columns="mappingColumns" :loading="mappingLoading" class="cs-table" data-testid="mapping-table">
        <template #empty>暂无科目映射配置</template>
        <template #settlementType-cell="{ row }">{{ getSettlementLabel(row.original.settlementType) }}</template>
        <template #businessType-cell="{ row }">{{ businessTypeText(row.original.businessType) }}</template>
        <template #remark-cell="{ row }">{{ row.original.remark ?? '—' }}</template>
        <template #actions-cell="{ row }">
          <div class="row-actions">
            <UButton variant="link" size="xs" icon="i-lucide-pencil" data-testid="mapping-edit" @click="openMappingEdit(row.original)">编辑</UButton>
            <UButton color="error" variant="link" size="xs" icon="i-lucide-trash-2" data-testid="mapping-delete" @click="handleMappingDelete(row.original)">删除</UButton>
          </div>
        </template>
      </UTable>
    </section>

    <!-- Dialog: 导出配置 新建/编辑 -->
    <UModal
      v-model:open="configDialogVisible"
      :title="`${configDialogMode === 'create' ? '新建' : '编辑'} — 凭证导出配置（${getTargetSystemLabel(configForm.targetSystem)}）`"
      description="设置导出文件的列名"
      :ui="{ content: 'max-w-[600px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="dialog-form">
          <UFormField label="目标系统" required hint="同工厂同目标系统只能有一个配置">
            <USelect v-model="configForm.targetSystem" :items="CONFIG_TARGET_ITEMS" :disabled="configDialogMode === 'edit'" class="w-full" data-testid="config-target" />
          </UFormField>
          <UFormField label="凭证字号列名"><UInput v-model="configForm.colVoucherNo" class="w-full" /></UFormField>
          <UFormField label="日期列名"><UInput v-model="configForm.colDate" class="w-full" /></UFormField>
          <UFormField label="摘要列名"><UInput v-model="configForm.colSummary" class="w-full" /></UFormField>
          <UFormField label="科目编码列名"><UInput v-model="configForm.colSubjectCode" class="w-full" /></UFormField>
          <UFormField label="科目名称列名"><UInput v-model="configForm.colSubjectName" class="w-full" /></UFormField>
          <UFormField label="借方金额列名"><UInput v-model="configForm.colDebit" class="w-full" /></UFormField>
          <UFormField label="贷方金额列名"><UInput v-model="configForm.colCredit" class="w-full" /></UFormField>
          <UFormField label="辅助核算列名"><UInput v-model="configForm.colAuxiliary" class="w-full" /></UFormField>
          <UFormField label="币别列名"><UInput v-model="configForm.colCurrency" class="w-full" /></UFormField>
          <UFormField v-if="configDialogMode === 'edit'" label="状态">
            <USwitch v-model="configForm.isActive" :label="configForm.isActive ? '启用' : '停用'" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="configDialogVisible = false">取消</UButton>
        <UButton :loading="configSaveLoading" data-testid="config-save" @click="handleConfigSave">{{ configDialogMode === 'create' ? '创建' : '保存' }}</UButton>
      </template>
    </UModal>

    <!-- Dialog: 科目映射 新增/编辑 -->
    <UModal
      v-model:open="mappingDialogVisible"
      :title="`${editingMappingId ? '编辑' : '新增'} — 科目映射（${getSettlementLabel(mappingForm.settlementType)}）`"
      description="设置结算方式对应的借贷科目"
      :ui="{ content: 'max-w-[500px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <div class="dialog-form">
          <UFormField label="结算方式" required>
            <USelect v-model="mappingForm.settlementType" :items="SETTLEMENT_ITEMS" class="w-full" data-testid="mapping-settlement" />
          </UFormField>
          <UFormField label="业务类型">
            <USelect v-model="businessTypeModel" :items="BUSINESS_TYPE_ITEMS" class="w-full" data-testid="mapping-business" />
          </UFormField>
          <UFormField label="借方科目代码" required><UInput v-model="mappingForm.debitSubjectCode" placeholder="如 1405" class="w-full" data-testid="mapping-debit-code" /></UFormField>
          <UFormField label="借方科目名称"><UInput v-model="mappingForm.debitSubjectName" placeholder="如 原材料" class="w-full" /></UFormField>
          <UFormField label="贷方科目代码" required><UInput v-model="mappingForm.creditSubjectCode" placeholder="如 2202" class="w-full" data-testid="mapping-credit-code" /></UFormField>
          <UFormField label="贷方科目名称"><UInput v-model="mappingForm.creditSubjectName" placeholder="如 应付账款" class="w-full" /></UFormField>
          <UFormField label="备注"><UTextarea v-model="remarkModel" :rows="2" placeholder="可选说明" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="mappingDialogVisible = false">取消</UButton>
        <UButton :loading="mappingSaveLoading" data-testid="mapping-save" @click="handleMappingSave">保存</UButton>
      </template>
    </UModal>

    <SalesConfirmModal ref="confirmRef" />
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); display: grid; gap: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count { color: var(--cs-muted); font-size: var(--cs-font-small); }
.head-actions { margin-left: auto; display: flex; align-items: center; gap: var(--cs-space-2); }
.export-body { padding: var(--cs-space-4) var(--cs-space-5); display: grid; gap: var(--cs-space-3); }
.export-row { display: flex; flex-wrap: wrap; align-items: flex-end; gap: var(--cs-space-3); }
.export-actions { display: flex; gap: var(--cs-space-2); }
.range { display: flex; align-items: center; gap: var(--cs-space-2); }
.range-sep { color: var(--cs-muted); font-size: var(--cs-font-small); }
.ledger-report-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 10px; }
.ledger-report-panel { border: 1px solid var(--cs-border); border-radius: 8px; padding: 12px; background: var(--cs-surface); }
.ledger-report-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.ledger-report-title { font-size: 14px; font-weight: 600; color: var(--cs-text); }
.ledger-header-preview { display: flex; flex-wrap: wrap; gap: 6px; }
.dialog-form { display: grid; gap: var(--cs-space-3); }
.form-hint { font-size: 12px; color: var(--cs-muted); }
.row-actions { display: flex; align-items: center; gap: var(--cs-space-2); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
