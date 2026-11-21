<script setup lang="ts">
import { CompatDialog, CompatForm, CompatFormItem, CompatOption, CompatPagination, CompatSelect, CompatTable, CompatTableColumn } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage, ElMessageBox } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

/**
 * 原料类型字典管理 (raw_material_types)
 *
 * 张权 Apr 29 2026 反馈: "没有 新建的入口哦 这个入口是入库登记".
 * 仓储 → 原料 / 物料 (采购入库) 页面只有"入库登记"(material_batches CRUD),
 * 但缺一个"原料类型字典"管理页, 用户无法在 UI 里创建新原料类型 (冻猪蹄/吸塑盒).
 * 后端 /raw-material-types 全 CRUD 早齐, 只缺前端页面 — 本页补上.
 *
 * May 7 2026 用户需求 (PR #114/#116/#120 后端落地):
 * 1. 按基本类型建议简短料号，创建前允许用户修改
 * 2. 分段字典只作为可选分类树，不参与料号生成
 * 3. 单位下拉 + 智能默认 (suggest-unit 按相似名称+类别取最近原料的 unit)
 * 4. 去掉单价 (按采购价浮动, 在采购订单里录)
 * 5. 包装层级: 一级 (kg, 必填=unit) + 二/三级 (10kg/箱, 12箱/柜)
 *
 * T159-A-form (2026-06-08): 防呆复刻 SKU 表单模式
 *   1. 料号建议 (create mode): GET .../raw-material-types/preview-code?category=
 *   2. 全字段智能匹配 cascade: GET .../raw-material-types/suggest?name=&category=
 *      → 填 unit/category/storageType/shelfLifeDays/level1PerLevel2/level2Unit
 *      + *ManuallyEdited flags + cascadeWriting guard (exact SKU pattern)
 *      + clearCascadeFields when name cleared
 *      null 字段 → 不覆盖; 端点 404/error → graceful degrade to suggest-unit fallback
 *   3. 包装层级内联换算行 (SKU-style):
 *      「1 [二级单位] = [换算数] [一级单位]」+ live preview tag
 *      一级单位 = 只读 echo 主单位 (single source of truth)
 *   4. 单位下拉统一使用 UnitSelect，支持搜索、查重和现场创建
 *   foldable #1: 批次列表单位列 → 修 materials/list.vue 显示 quantityUnit 而非 unit
 */
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get, post, put, del } from '@/api/request';
import { listSystemUnits, type SystemUnit } from '@/api/systemUnits';

import { Plus, Edit, Delete as DeleteIcon, Search, Refresh, Lock, View } from '@element-plus/icons-vue';
import { formatAmount } from '@/utils/tableFormatters';
import { bigCategoryOf, MATERIAL_CATEGORY_ENUM_VALUES, MATERIAL_STORAGE_TYPE_ENUM_VALUES } from '@/utils/materialCategory';
import { getWarehouseLayout } from '@/api/warehouseLayout';
import { displayUnit, sameUnit } from '@/utils/unitPricing';
import { purchaseUnitSelectionReason, resolvePurchaseUnits } from '@/utils/purchaseUnitContract';
import { handleCatchError } from '@/utils/errorToast';
import ConceptDisambiguationAlert from '@/components/common/ConceptDisambiguationAlert.vue';
import UpstreamMissingHint from '@/components/common/UpstreamMissingHint.vue';
import UnitSelect from '@/components/common/UnitSelect.vue';
import MaterialAliasDialog from './MaterialAliasDialog.vue';
import SupplyImpactDialog from '@/components/common/SupplyImpactDialog.vue';
import MaterialMergeDialog from './MaterialMergeDialog.vue';
import type { RawMaterialTypeRow } from '@/api/materialAliasMerge';
import { packagingProblemSummary, splitPackagingRules } from './packagingRuleDraft';
import { useCreateAndReturn } from '@/composables/useCreateAndReturn';
import type { TableRow } from '@/types/api';
import {
  listActiveSuppliers,
  listMaterialSuppliers,
  createSupplierMaterial,
  updateSupplierMaterial,
  deleteSupplierMaterial,
  type SupplierMaterialPayload,
  type SupplierMaterialRelation,
  type SupplierRecord,
} from '@/api/supplierManagement';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const route = useRoute();
const factoryId = computed(() => authStore.factoryId);
// 🔴 缺陷 46 (2026-09-02, owner 拍板改页面写权限): 菜单条目
//   (`/warehouse/material-types`, menuConfig.ts) module 是 warehouse, 但这里绑的是
//   production —— 仓库角色进得了页面(菜单放行), 新增/编辑/删除按钮却因 `v-if="canWrite"`
//   全部隐藏, 只剩「供应商」一栏能操作。原料类型字典是包材/原料主数据, 属于仓储岗位职责。
// 2026-08-22 那次"绑到 production"是因为后端 RawMaterialTypeController 的
//   create/update/delete 与 MaterialPackagingHierarchyController 的 by-material PUT/DELETE
//   当时只认 production:read_write —— 已同 commit 改成 {production, warehouse}:read_write
//   任一即可(仓库角色不再 403); markByproduct / batch-status 两个端点本页不调用, 未动。
// ⚠️ 同文件的 canManageClassification (system) / canManageSupplierRelations (procurement)
//   是另外两组端点的门禁, ⛔ 别把三者合并。
// ⚠️ canWrite 本身保持「纯模块闸」不变 —— writeGateMatchesMenuModule.source.spec.ts
//   按字面正则从这一行抠出 canWrite('warehouse') 的实参, 与 canManageMaterialTypeDictionary
//   分开声明才不会破坏那道闸的被测对象。
const canWrite = computed(() => permissionStore.canWrite('warehouse'));
const canManageSupplierRelations = computed(() => permissionStore.canWrite('procurement'));
const canManageClassification = computed(() => permissionStore.canWrite('system'));
// 缺陷 46 follow-up (2026-09-04, owner 拍板): 「只让仓储主管改」——
//   canWrite('warehouse') 是模块闸 (仓管/仓储主管对 warehouse 模块的 platform_role_
//   permissions 都是 'rw', 见 V20261024_22__warehouse_worker_read_warehouse_inventory.sql),
//   区分不了「仓管」和「仓储主管」。新建/编辑/删除按钮真正的可点性 = 模块闸 ∧ 角色白名单。
// ⚠️ 角色白名单写成**本文件内的字面量 Set**, 不 import 外部模块 ——
//   分支可达性静态分析器 (src/__tests__/gates/branchReachability.spec.ts) 的 fileEnv 只解析
//   同一个 .vue 文件 <script> 里的 VariableDeclaration, 不跨文件解析 import; 调用外部函数
//   canManageMaterialTypes(role) 对它是不可判定的 unknown, 而 core() 会把 and/or 链里的
//   unknown 操作数直接丢掉 —— `canWrite && canManageMaterialTypes(role)` 被它读成只剩
//   `canWrite`, 取反后与原式拼成 `canWrite && !canWrite`, 被误判成一条恒假的空转分支
//   (棘轮预算 18→19, 2026-09-04 CI 实测)。改用本地 Set 字面量 + `.has()`
//   (与工厂供货验收列表的 SUPPLIER_DELIVERY_WRITE_ROLES 同一形状, 分析器认得出),
//   与 materialTypeWriteRoles.ts 的 MATERIAL_TYPE_WRITE_ROLES 是否一致由
//   materialTypeWriteRolesMatchBackend.spec.ts 从两处源码分别解析后钉住。
const MATERIAL_TYPE_WRITE_ROLE_SET = new Set([
  'warehouse_manager', 'factory_super_admin',
  'super_admin', 'platform_admin', 'developer', 'platform_super_admin',
]);
const canManageMaterialTypeDictionary = computed(
  () => canWrite.value && MATERIAL_TYPE_WRITE_ROLE_SET.has(permissionStore.currentRole),
);
// worker 能看到页面 (canWrite=true, 菜单 module 放行) 但点不动写按钮时, 给出显式一行提示 ——
// ⛔ 不能只是把按钮悄悄藏起来 (那正是缺陷 46 本体「看得见点不开」的静默版本)。
const showMaterialTypeWriteRoleHint = computed(
  () => canWrite.value && !canManageMaterialTypeDictionary.value,
);
// T2-5b (issue #534): expose movingAvgPrice — gate by canViewPrice RBAC
const canViewPrice = computed(() => permissionStore.canViewPrice);
const { goCreate } = useCreateAndReturn();

const loading = ref(false);
// 客户张权反馈 (2026-07-02): "搜不出来 filter 不了" — 搜索框绑了 searchKeyword 但后端
// GET /{factoryId}/raw-material-types 列表接口 (list.vue 实际调用的接口) 从不接收 keyword 参数
// (只有独立的 /raw-material-types/search 子路由支持 keyword) — 输入框敲字对结果毫无影响,
// 关键字和分类节点统一下推后端分页，避免只取前 2000 条后在客户端假筛选。
const tableData = ref<TableRow[]>([]);
const pagination = ref({ page: 1, size: 20, total: 0 });
const searchKeyword = ref('');
// 可选分类筛选：分类节点只用系统生成 ID，不再存在编码前缀。
const filterSegmentL1 = ref<number | null>(null);
const filterSegmentL2 = ref<number | null>(null);
const filterSegmentL3 = ref<number | null>(null);
const selectedClassificationId = computed(() =>
  filterSegmentL3.value || filterSegmentL2.value || filterSegmentL1.value,
);

// 储存类型读取系统字典；单位由 UnitSelect 统一加载；基本类型来自平台枚举及存量值。
interface DictItem { enumCode: string; enumLabel: string; sortOrder: number }
const storageTypeOptions = ref<DictItem[]>([]);

onMounted(async () => {
  const keyword = route.query.keyword;
  if (typeof keyword === 'string' && keyword.trim()) {
    searchKeyword.value = keyword.trim();
  }
  await Promise.all([loadDictionaries(), loadSegmentTree()]);
  await loadData();
});

async function loadDictionaries() {
  if (!factoryId.value) return;
  try {
    const storageRes = await get<DictItem[]>(`/${factoryId.value}/system-config/enums/MATERIAL_STORAGE_TYPE`);
    storageTypeOptions.value = (storageRes.data || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
  } catch (e) {
    // ⛔ 不再静默吞掉: 字典挂了和字典为空以前长得一模一样(都是空下拉),
    // 2026-08-28 就是这么让「必填字段没有可选项」在生产上活了下来。
    console.warn('储存类型字典加载失败, 回落到平台内置值 + 本厂储存区域', e);
  }
  try {
    const layout = await getWarehouseLayout(factoryId.value);
    storageZoneNames.value = (layout.data?.zones || [])
      .map((z) => (z?.name || '').trim())
      .filter((n) => n !== '');
  } catch (e) {
    console.warn('仓库储存区域加载失败, 储存类型下拉将不含本厂区域', e);
  }
}

/**
 * 储存类型下拉 = 平台内置值 ∪ 系统字典 ∪ **本厂储存区域** ∪ 存量值。
 *
 * 🔴 2026-08-28 客户反馈:「储存区域/储存类型都建好了, 原料类型里却看不到」。
 * 他们能配「储存类型」的地方**只有**仓储位置管理里的储存区域(冷冻库/冷藏库/急冻库/
 * 常温库/缓化库/研发库), 而这个下拉当时只读 `system_enums` —— 两个来源从来没接上,
 * 加上那张表在 prod 上是空的, 下拉显示「无数据」。
 *
 * ⚠️ `raw_material_types.storage_type` 是 varchar(20) 自由文本, 后端没有任何按具体值
 * 分支的逻辑(只有一个按值查询的接口), 所以并入区域名是安全的; 超长的区域名会被跳过。
 */
const STORAGE_TYPE_MAX_LEN = 20;
const storageZoneNames = ref<string[]>([]);

function storageTypeChoices(current?: string): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  const seen = new Set<string>();
  const push = (name: string, suffix = '') => {
    const v = (name || '').trim();
    if (!v || seen.has(v) || v.length > STORAGE_TYPE_MAX_LEN) return;
    seen.add(v);
    out.push({ value: v, label: suffix ? `${v} ${suffix}` : v });
  };
  storageTypeOptions.value.forEach((c) => push(c.enumLabel));
  MATERIAL_STORAGE_TYPE_ENUM_VALUES.forEach((v) => push(v));
  storageZoneNames.value.forEach((n) => push(n, '(本厂储存区域)'));
  seenStorageTypes.value.forEach((v) => push(v));
  const cur = (current || '').trim();
  if (cur && !seen.has(cur)) out.unshift({ value: cur, label: `${cur} (历史)` });
  return out;
}

// 兼容旧调用名 —— 模板与既有源码闸都引用它。
function mergeHistoricStorage(current?: string): { value: string; label: string }[] {
  return storageTypeChoices(current);
}

async function loadData() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await get<{ content: TableRow[]; totalElements: number }>(
      `/${factoryId.value}/raw-material-types`,
      {
        params: {
          page: pagination.value.page,
          size: pagination.value.size,
          classificationId: selectedClassificationId.value || undefined,
          keyword: searchKeyword.value.trim() || undefined,
        },
      },
    );
    if (res.success && res.data) {
      tableData.value = res.data.content || [];
      pagination.value.total = res.data.totalElements || 0;
      // 无字典模式下的类别下拉要并上存量在用的取值, 见 materialFamilyOptions 注释
      rememberCategories(tableData.value);
    }
  } catch (e) { console.error(e); }
  finally { loading.value = false; }
}

// ==================== Create / Edit Dialog ====================
const dialogVisible = ref(false);
const editingId = ref<string | null>(null);
const form = ref({
  code: '',
  name: '',
  category: '',
  // 副产标记: 与 category 正交 —— 副产仍保留材质分类, 因此能被别的 workflow 当投入投料
  isByproduct: false,
  // 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」): 冰水/水/蒸汽/现场自制高汤等
  // 现场取用、不走采购入库的物料。挂在物料类型本身, 领料单/报工据此跳过库存查找与拦截。
  selfSupplied: false,
  unit: 'kg',
  storageType: '',
  shelfLifeDays: null as number | null,
  notes: '',
  // SP4: 税率 + 含税单价
  taxTreatment: 'TAXABLE' as 'TAXABLE' | 'EXEMPT',
  taxRate: 'TAX_13' as string,
  taxExemptionReason: '',
  taxIncludedUnitPrice: null as number | null,
  // P8: 包材关联固定客户 (选填, 非 PACKAGING 类留空)
  associatedCustomerId: null as string | null,
  // #759: 包材每产品单位用量 (选填, 仅 PACKAGING 有意义)
  packQtyPerProduct: null as number | null,
});

function isPackagingCategory(category: string | null | undefined): boolean {
  return bigCategoryOf(String(category || '')) === '包材';
}

const isPackagingMaterial = computed(() => isPackagingCategory(form.value.category));

// P8: 客户列表 (用于 associatedCustomerId 下拉)
//
// 客户张权反馈 (2026-07-02): "关联固定客户" 下拉显示"没有数据"。根因: 当时调的
// GET /{factoryId}/customers 是 CRM 客户列表页用的接口, 服务端加了 @DataScope("created_by")
// (Sprint 6 W2-B RBAC 第2维数据权限) — SELF/SELF_AND_BELOW/DEPT_AND_BELOW 数据域的角色只能看到
// "自己创建过的客户", 仓库管理员几乎从没建过客户记录, 所以对他们合法返回 0 条。
// 拿它给"关联固定客户"这种**引用型下拉**用就是错的接口。
//
// 🔴 2026-09-06 第二次改这一行 (SEC-CUSTOMER-ROSTER-REMAINING-PATHS)。7-02 那次改成了
//    GET /customers/active, 当时写下的理由原话是「无 DataScope 限制…两种角色都稳定返回全部
//    17 条」—— 也就是说, 它是**因为那个端点没有任何权限门**才被选中的。
//    owner 2026-09-06 裁定「客户名单只有 销售/财务/工厂管理员/运营 能看」之后, /active 按裁定
//    关到 sales|finance 四个码, 而 warehouse_manager / warehouse_worker 在 L1 上
//    sales='-' finance='-' ⇒ 这个下拉会 403。
//    ⛔ 解法**不是**把 /active 继续开着 (那等于让一个仓储下拉决定全厂客户名单的可见性),
//    而是给这个下拉一个刚好够用的口子:
//
//      GET /{factoryId}/customers/warehouse-options
//        · 只返回 { id, name } —— ⛔ 没有电话/邮箱/税号/信用额度/余额/累计销售额
//          (那些字段今天经 /active 是**全部**吐给仓库员的);
//        · @RequirePermission({"warehouse:read_write","warehouse:read"}) —— 与本页自己的
//          路由模块闸 (meta.module='warehouse' ⇒ permissionStore.canAccess('warehouse'))
//          **完全同宽**: 打得开本页的角色恰好放行, 打不开的一个都不放行;
//        · 无 @DataScope —— 引用型下拉必须给全集, 这一点与 7-02 的判断一致。
//
//    ⚠️ 下一个人想给这个下拉加字段 (比如显示联系人/电话) 之前请先读那个端点的注释:
//    它现在的宽度是「一个仓储录入字段所需的最小值」, 不是一个通用客户下拉。
interface CustomerItem { id: string; name: string }
const customerOptions = ref<CustomerItem[]>([]);
const customerOptionsLoaded = ref(false);
async function loadCustomers() {
  if (!factoryId.value || customerOptions.value.length > 0) return;
  try {
    const res = await get<CustomerItem[] | { content?: CustomerItem[] }>(
      `/${factoryId.value}/customers/warehouse-options`,
    );
    const data = res.data;
    customerOptions.value = Array.isArray(data) ? data : (data as { content?: CustomerItem[] })?.content ?? [];
  } catch {
    customerOptions.value = [];
  } finally {
    customerOptionsLoaded.value = true;
  }
}
interface MaterialPackagingRule {
  id?: string;
  name: string;
  packageUnit: string;
  baseUnit: string;
  conversionFactor?: number;
  defaultSpec: boolean;
  active: boolean;
  sortOrder: number;
  version?: number;
}

function blankPackagingRule(index = 0): MaterialPackagingRule {
  return {
    name: index === 0 ? '默认包装' : `包装规格 ${index + 1}`,
    packageUnit: '',
    baseUnit: form.value.unit?.trim() || '',
    conversionFactor: undefined,
    defaultSpec: index === 0,
    active: true,
    sortOrder: index,
  };
}

const packagingRules = ref<MaterialPackagingRule[]>([blankPackagingRule()]);
/** 保存时发现的不完整行: 下标 → 原因, 显示在那一行下面 (D-S2-12) */
const packagingRuleErrors = ref<Record<number, string>>({});

function addPackagingRule(): void {
  packagingRules.value.push(blankPackagingRule(packagingRules.value.length));
}

function removePackagingRule(index: number): void {
  // ⛔ 这里原本 `if (index === 0) return` —— 第一条永远删不掉, 配上「至少一条」的
  // 强制校验, 用户就没有任何办法表达「这个原料没有固定包装」。现在允许删光。
  packagingRules.value.splice(index, 1);
  packagingRules.value.forEach((rule, ruleIndex) => {
    rule.name = ruleIndex === 0 ? '默认包装' : `包装规格 ${ruleIndex + 1}`;
    rule.defaultSpec = ruleIndex === 0;
    rule.sortOrder = ruleIndex;
  });
}

function packagingRuleText(rule: MaterialPackagingRule): string {
  const factor = Number(rule.conversionFactor);
  if (!rule.packageUnit || !form.value.unit || !Number.isFinite(factor) || factor <= 0) return '';
  return `1 ${displayUnit(rule.packageUnit)} = ${factor} ${displayUnit(form.value.unit)}`;
}
const dialogTitle = computed(() => (editingId.value ? '编辑原料类型' : '新建原料类型'));

// SP4: 含税单价 → 未税联动
const preTaxUnitPrice = computed(() => {
  const price = form.value.taxIncludedUnitPrice;
  const rate = form.value.taxRate;
  if (price == null) return null;
  if (form.value.taxTreatment === 'EXEMPT') return Number(price);
  if (!rate) return null;
  const rateNum = rate === 'TAX_9' ? 0.09 : rate === 'TAX_13' ? 0.13 : null;
  if (rateNum == null) return null;
  return Math.round((price / (1 + rateNum)) * 100) / 100;
});

// 可选分类级联下拉 (MaterialCodeSegmentController)
interface SegmentNode {
  id: number;
  segmentLabel: string;
  level: number;
  parentId: number | null;
  isActive: boolean;
  children?: SegmentNode[];
}

function formatSegmentOptionLabel(option: { segmentLabel?: string; label?: string }): string {
  const label = String(option.segmentLabel || option.label || '').trim();
  return label || '未命名分类';
}

function materialDisplayCode(row: TableRow): string {
  return String(row.code || '-').trim() || '-';
}
const segmentTree = ref<SegmentNode[]>([]);
const segmentL1 = ref<number | null>(null);
const segmentL2 = ref<number | null>(null);
const segmentL3 = ref<number | string | null>(null);
const segmentLoading = ref(false);
const codeContractHint = ref('');
const editingDisplayCode = ref('');
const codeSuggestionLoading = ref(false);
const codeSuggestionError = ref('');
const codeManuallyEdited = ref(false);
const QUICK_CREATE_L3 = '__quick_create_l3__';
const createL3DialogVisible = ref(false);
const createL3Submitting = ref(false);
const createL3Form = ref({ label: '' });
const l3MatchHint = ref('');
const l3ManuallyEdited = ref(false);
const unitSuggestionHint = ref('');
interface TaxonomyCandidate {
  l1Id: number;
  l1Label: string;
  l2Id: number;
  l2Label: string;
  l3Id: number;
  l3Label: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  reason: string;
}
const taxonomyCandidates = ref<TaxonomyCandidate[]>([]);
let segmentTreeRequestVersion = 0;

async function loadSegmentTree() {
  if (!factoryId.value) return;
  const requestFactoryId = factoryId.value;
  const requestVersion = ++segmentTreeRequestVersion;
  segmentLoading.value = true;
  try {
    const res = await get<SegmentNode[]>(`/${requestFactoryId}/material-segments/tree`);
    if (requestVersion !== segmentTreeRequestVersion || requestFactoryId !== factoryId.value) return;
    if (res.success && Array.isArray(res.data)) {
      segmentTree.value = res.data;
    } else {
      segmentTree.value = [];
    }
  } catch {
    if (requestVersion === segmentTreeRequestVersion) segmentTree.value = [];
  } finally {
    if (requestVersion === segmentTreeRequestVersion) segmentLoading.value = false;
  }
}

// L1 options = top-level nodes
const segmentL1Options = computed(() =>
  segmentTree.value.filter((n) => n.level === 1 && n.isActive),
);
// 新建类别与列表大类共用此组选项，避免旧 MATERIAL_CATEGORY 枚举与编码类族漂移。
/** 是否存在可选物料分类树；它不会切换料号生成模式。 */
const hasSegmentDictionary = computed(() => segmentL1Options.value.length > 0);

/**
 * 无字典模式下**存量在用**的类别取值。
 *
 * 🔴 光有平台枚举不够: 枚举是 主材/辅材/调味料/包材/添加剂, 而字典模式下写进去的
 * category 是 L1 类族的名字(六膳门那 14 个换过料号的物料就是 `原料`)。工厂一旦从
 * 「有字典」切成「无字典」, 这些历史取值在下拉里一个都对不上 —— 编辑时类别框显示空白,
 * 列表也按它筛不出来, 而用户什么都没做错。
 *
 * 所以选项 = 平台枚举 ∪ 已加载数据里真实出现过的类别。跨页累积(只增不减), 免得翻页
 * 把上一页见过的取值又弄丢。fool-proof-design Rule 5: 宁缺勿藏。
 */
const seenCategories = ref<string[]>([]);
/** 同理: 储存类型的存量取值也要留住(六膳门 316 条物料里全是「新鲜」这种历史值)。 */
const seenStorageTypes = ref<string[]>([]);
function rememberCategories(rows: { category?: string | null; storageType?: string | null }[]) {
  for (const row of rows) {
    const c = (row.category || '').trim();
    if (c && !seenCategories.value.includes(c)) seenCategories.value.push(c);
    const s = (row.storageType || '').trim();
    if (s && !seenStorageTypes.value.includes(s)) seenStorageTypes.value.push(s);
  }
}

/**
 * 基本类型始终来自平台枚举、存量值与分类树 L1 的并集。
 * 是否配置分类树不再改变新建路径。
 */
const materialFamilyOptions = computed(() => {
  const values = [...MATERIAL_CATEGORY_ENUM_VALUES] as string[];
  for (const c of seenCategories.value) {
    if (!values.includes(c)) values.push(c);
  }
  for (const node of segmentL1Options.value) {
    if (!values.includes(node.segmentLabel)) values.push(node.segmentLabel);
  }
  const current = (form.value?.category || '').trim();
  if (current && !values.includes(current)) values.push(current);
  return values.map((label) => ({
    value: label,
    label,
    classificationId: segmentL1Options.value.find((node) => node.segmentLabel === label)?.id || null,
  }));
});
const filterSegmentL2Options = computed(() => {
  if (!filterSegmentL1.value) return [];
  const l1Node = segmentTree.value.find((node) => node.id === filterSegmentL1.value);
  return l1Node?.children?.filter((node) => node.level === 2 && node.isActive) ?? [];
});
const filterSegmentL3Options = computed(() => {
  if (!filterSegmentL2.value) return [];
  for (const l1Node of segmentTree.value) {
    const l2Node = l1Node.children?.find((node) => node.id === filterSegmentL2.value);
    if (l2Node) return l2Node.children?.filter((node) => node.level === 3 && node.isActive) ?? [];
  }
  return [];
});

watch(filterSegmentL1, () => {
  filterSegmentL2.value = null;
  filterSegmentL3.value = null;
  pagination.value.page = 1;
  loadData();
});
watch(filterSegmentL2, () => {
  filterSegmentL3.value = null;
  pagination.value.page = 1;
  loadData();
});
watch(filterSegmentL3, () => {
  pagination.value.page = 1;
  loadData();
});

function resolveMaterialFamily(category: string | null | undefined): string | null {
  const raw = String(category || '').trim();
  if (!raw) return null;
  const exact = materialFamilyOptions.value.find((option) => option.value === raw);
  if (exact) return exact.value;

  // 历史枚举归并到当前三类 L1：主材→原料，辅材/添加剂/调味料→辅料，PACKAGING→包材。
  const bucket = bigCategoryOf(raw);
  const canonical = bucket === '调料' ? '辅料' : bucket;
  return materialFamilyOptions.value.find((option) => option.value === canonical)?.value ?? null;
}

function isMaterialFamily(category: string | null | undefined): boolean {
  return materialFamilyOptions.value.some((option) => option.value === category);
}

function syncMaterialFamilyFromSegment(classificationId: number | null) {
  if (editingId.value || !classificationId) return;
  const option = materialFamilyOptions.value.find((item) => item.classificationId === classificationId);
  if (!option || form.value.category === option.value) return;
  cascadeWriting.value = true;
  try {
    form.value.category = option.value;
  } finally {
    cascadeWriting.value = false;
  }
}
// L2 options = children of selected L1
const segmentL2Options = computed(() => {
  if (!segmentL1.value) return [];
  const l1Node = segmentTree.value.find((n) => n.id === segmentL1.value);
  return l1Node?.children?.filter((c) => c.level === 2 && c.isActive) ?? [];
});
// L3 options = children of selected L2
const segmentL3Options = computed(() => {
  if (!segmentL2.value) return [];
  for (const l1 of segmentTree.value) {
    const l2Node = l1.children?.find((c) => c.id === segmentL2.value);
    if (l2Node) return l2Node.children?.filter((c) => c.level === 3 && c.isActive) ?? [];
  }
  return [];
});
const selectedL2Label = computed(() =>
  segmentL2Options.value.find((node) => node.id === segmentL2.value)?.segmentLabel || '',
);

// When L1 changes, reset L2/L3
watch(segmentL1, (classificationId) => {
  segmentL2.value = null;
  segmentL3.value = null;
  resetCodeContractPreview();
  syncMaterialFamilyFromSegment(classificationId);
});
watch(segmentL2, () => {
  segmentL3.value = null;
  resetCodeContractPreview();
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
});

watch(segmentL3, (value) => {
  resetCodeContractPreview();
  if (dialogVisible.value && !editingId.value && typeof value === 'number' && form.value.category) {
    void refreshCodeSuggestion(false);
  }
});

function handleL3Change(value: number | string | null): void {
  if (value === QUICK_CREATE_L3) {
    segmentL3.value = null;
    // L3 is shared taxonomy, not the current purchasable MaterialType.
    // Never copy the material name into a new shared classification.
    createL3Form.value = { label: '' };
    createL3DialogVisible.value = true;
    return;
  }
  l3ManuallyEdited.value = true;
  l3MatchHint.value = '';
  taxonomyCandidates.value = [];
}

async function applyTaxonomyCandidate(candidate: TaxonomyCandidate, manual: boolean): Promise<void> {
  cascadeWriting.value = true;
  try {
    segmentL1.value = candidate.l1Id;
    await nextTick();
    segmentL2.value = candidate.l2Id;
    await nextTick();
    segmentL3.value = candidate.l3Id;
    l3ManuallyEdited.value = manual;
    taxonomyCandidates.value = [];
    l3MatchHint.value = `${manual ? '已确认分类' : '已根据名称智能匹配'}：${candidate.l1Label} > ${candidate.l2Label} > ${candidate.l3Label}；${candidate.reason}`;
  } finally {
    cascadeWriting.value = false;
  }
}

function rematchTaxonomy(): void {
  l3ManuallyEdited.value = false;
  segmentL3.value = null;
  l3MatchHint.value = '已允许重新匹配，请继续编辑名称或稍候系统重新推荐';
  suggestionRefreshToken.value += 1;
}

async function handleCreateL3(): Promise<void> {
  if (!canManageClassification.value) {
    ElMessage.error(t('warehouse.material_types_list.message_7e516c582c13'));
    createL3DialogVisible.value = false;
    return;
  }
  const label = createL3Form.value.label.trim();
  if (!segmentL2.value) { ElMessage.warning(t('warehouse.material_types_list.message_a28c9868147d')); return; }
  if (!label) { ElMessage.warning(t('warehouse.material_types_list.message_4458a60da3e0')); return; }
  if (!factoryId.value) return;

  const normalizedLabel = label.toLocaleLowerCase();
  const existing = segmentL3Options.value.find(
    (node) => node.segmentLabel.trim().toLocaleLowerCase() === normalizedLabel,
  );
  if (existing) {
    segmentL3.value = existing.id;
    l3ManuallyEdited.value = true;
    createL3DialogVisible.value = false;
    ElMessage.success(`“${existing.segmentLabel}”已存在，已直接选中`);
    return;
  }

  createL3Submitting.value = true;
  try {
    const response = await post<SegmentNode>(`/${factoryId.value}/material-segments`, {
      level: 3,
      segmentLabel: label,
      parentId: segmentL2.value,
      sortOrder: segmentL3Options.value.length,
      isActive: true,
    });
    if (!response.success || !response.data) {
      throw new Error(response.message || '创建三级分类失败');
    }
    await loadSegmentTree();
    segmentL3.value = response.data.id;
    l3ManuallyEdited.value = true;
    createL3DialogVisible.value = false;
    ElMessage.success(`已创建新品类「${response.data.segmentLabel}」`);
  } catch (error) {
    handleCatchError(error, '创建三级分类失败');
  } finally {
    createL3Submitting.value = false;
  }
}

let l3MatchTimer: ReturnType<typeof setTimeout> | undefined;
let l3MatchRequestVersion = 0;
watch(
  () => [form.value.name, segmentL1.value, segmentL2.value, dialogVisible.value, editingId.value] as const,
  ([name, _l1, l2, visible, currentEditingId]) => {
    const requestVersion = ++l3MatchRequestVersion;
    if (l3MatchTimer) clearTimeout(l3MatchTimer);
    l3MatchHint.value = '';
    const normalizedName = String(name || '').trim();
    if (!visible || currentEditingId || !l2 || normalizedName.length < 2 || l3ManuallyEdited.value) return;
    l3MatchTimer = setTimeout(async () => {
      try {
        const response = await get<{ content: TableRow[] }>(`/${factoryId.value}/raw-material-types`, {
          params: { page: 1, size: 20, classificationId: l2, keyword: normalizedName },
        });
        if (requestVersion !== l3MatchRequestVersion) return;
        const rows = response.success && Array.isArray(response.data?.content) ? response.data.content : [];
        const query = normalizedName.toLocaleLowerCase();
        // Only an exact same-factory historic identity may be auto-selected.
        // Fuzzy results are exposed by the explainable candidate UI instead.
        const matched = rows.find((row) => String(row.name || '').trim().toLocaleLowerCase() === query);
        const matchedL3 = Number(matched?.classificationId || 0);
        const option = segmentL3Options.value.find((node) => node.id === matchedL3);
        if (option && !l3ManuallyEdited.value) {
          segmentL3.value = option.id;
          l3MatchHint.value = `已按历史物料「${String(matched?.name || '')}」匹配 ${option.segmentLabel}`;
        }
      } catch {
        // 历史匹配失败不影响手工选择，也不虚构 L3。
      }
    }, 400);
  },
);

interface MaterialCodePreview {
  code: string;
  classificationId?: number | null;
  selectable: boolean;
  guidance: string;
}

let codeContractRequestVersion = 0;

function resetCodeContractPreview(): void {
  codeContractHint.value = '';
  codeSuggestionError.value = '';
}

async function refreshCodeSuggestion(notifyError = true): Promise<boolean> {
  if (!form.value.category) {
    codeSuggestionError.value = '请先选择基本类型，系统才能计算下一个可用料号';
    return false;
  }
  const hasPartialClassification = Boolean(segmentL1.value || segmentL2.value || segmentL3.value);
  if (hasPartialClassification && (!segmentL1.value || !segmentL2.value || !segmentL3.value)) {
    codeSuggestionError.value = '分类如需填写，必须完整选择一级、二级、三级；也可以全部留空';
    if (notifyError) ElMessage.warning(codeSuggestionError.value);
    return false;
  }
  if (!factoryId.value) return false;
  const requestVersion = ++codeContractRequestVersion;
  const requestedCategory = form.value.category;
  codeSuggestionLoading.value = true;
  codeSuggestionError.value = '';
  try {
    const res = await get<MaterialCodePreview>(
      `/${factoryId.value}/raw-material-types/preview-code`,
      {
        params: {
          category: requestedCategory,
          classificationId: typeof segmentL3.value === 'number' ? segmentL3.value : undefined,
        },
        _silent: !notifyError,
      },
    );
    if (requestVersion !== codeContractRequestVersion || requestedCategory !== form.value.category) {
      return false;
    }
    if (res.success && res.data?.code && res.data.selectable) {
      codeContractHint.value = res.data.guidance;
      if (!codeManuallyEdited.value) form.value.code = res.data.code;
      return true;
    } else {
      resetCodeContractPreview();
      codeSuggestionError.value = res.message || '系统无法计算下一个可用料号';
      if (notifyError) ElMessage.error(codeSuggestionError.value);
      return false;
    }
  } catch (error) {
    if (requestVersion !== codeContractRequestVersion || requestedCategory !== form.value.category) {
      return false;
    }
    resetCodeContractPreview();
    codeSuggestionError.value = '系统暂时无法计算下一个可用料号，请稍后重试';
    if (notifyError) handleCatchError(error, codeSuggestionError.value);
    return false;
  } finally {
    if (requestVersion === codeContractRequestVersion) {
      codeSuggestionLoading.value = false;
    }
  }
}

// ==================== T159-A: *ManuallyEdited flags + cascadeWriting guard ====================
// Exact mirror of SKU's pattern:
//   - cascadeWriting=true while cascade writes → watchers skip marking as manual
//   - User direct edits → *ManuallyEdited=true → cascade will NOT overwrite that field
const cascadeWriting = ref(false);
const unitManuallyEdited = ref(false);
const categoryManuallyEdited = ref(false);
const storageTypeManuallyEdited = ref(false);
const shelfLifeManuallyEdited = ref(false);
const packagingManuallyEdited = ref(false);

function handleCodeInput(value: string): void {
  codeManuallyEdited.value = true;
  form.value.code = value.toUpperCase();
}

// Unit manual state is driven by UnitSelect's user-only change event. Watching
// v-model cannot distinguish form initialization from a real user action.
function handleUnitUserChange(value: string): void {
  if (cascadeWriting.value) return;
  unitManuallyEdited.value = true;
  unitSuggestionHint.value = '';
  form.value.unit = value;
}
watch(() => form.value.category, (category) => {
  if (!cascadeWriting.value) categoryManuallyEdited.value = true;
  if (dialogVisible.value && !editingId.value) {
    codeManuallyEdited.value = false;
    form.value.code = '';
    resetCodeContractPreview();
    if (category) void refreshCodeSuggestion(false);
  }
  if (!isPackagingCategory(category)) {
    form.value.associatedCustomerId = null;
    form.value.packQtyPerProduct = null;
  }
});
watch(() => form.value.storageType, () => {
  if (!cascadeWriting.value) storageTypeManuallyEdited.value = true;
});
watch(() => form.value.shelfLifeDays, () => {
  if (!cascadeWriting.value) shelfLifeManuallyEdited.value = true;
});
watch(packagingRules, () => {
  if (!cascadeWriting.value) packagingManuallyEdited.value = true;
  // 用户一改就清掉上次保存的行内报错, 下次保存重新判
  if (Object.keys(packagingRuleErrors.value).length) packagingRuleErrors.value = {};
}, { deep: true, flush: 'sync' });

function resetManuallyEditedFlags() {
  codeManuallyEdited.value = false;
  unitManuallyEdited.value = false;
  categoryManuallyEdited.value = false;
  storageTypeManuallyEdited.value = false;
  shelfLifeManuallyEdited.value = false;
  packagingManuallyEdited.value = false;
  unitSuggestionHint.value = '';
  taxonomyCandidates.value = [];
}

// clearCascadeFields: when name is cleared entirely, reset cascade-filled fields
// ONLY for fields the user hasn't manually edited
function clearCascadeFields() {
  cascadeWriting.value = true;
  try {
    if (!unitManuallyEdited.value) form.value.unit = 'kg';
    if (!categoryManuallyEdited.value) form.value.category = '';
    if (!storageTypeManuallyEdited.value) form.value.storageType = storageTypeChoices()[0]?.value || '';
    if (!shelfLifeManuallyEdited.value) form.value.shelfLifeDays = null;
    if (!packagingManuallyEdited.value) {
      packagingRules.value = [blankPackagingRule()];
    }
  } finally {
    cascadeWriting.value = false;
  }
}

// ==================== T159-A: Full smart-match cascade ====================
// Debounced watch: name (or name+category) change → call suggest endpoint → fill fields.
// null fields from suggest → leave as-is (don't overwrite with null).
// Endpoint 404/error → fallback to suggest-unit only (backward compat).
let suggestTimer: number | undefined;
let suggestRequestVersion = 0;
const suggestionRefreshToken = ref(0);
watch(
  () => [form.value.name, form.value.category, dialogVisible.value, editingId.value, suggestionRefreshToken.value] as const,
  ([name, _cat, visible, eid]) => {
    const requestVersion = ++suggestRequestVersion;
    if (!visible || eid) return; // edit mode: no cascade
    if (suggestTimer) clearTimeout(suggestTimer);
    const trimmedName = String(name || '').trim();

    // Name cleared → reset cascade fields (graceful)
    if (trimmedName.length === 0) {
      clearCascadeFields();
      return;
    }
    if (trimmedName.length < 2) return;

    suggestTimer = window.setTimeout(async () => {
      try {
        const params: Record<string, string> = { name: trimmedName };
        const cat = String(form.value.category || '').trim();
        if (cat) params.category = cat;

        // Try full suggest endpoint (T159-B-codegen provides this)
        const res = await get<{
          unit?: string | null;
          matchedMaterialName?: string | null;
          unitConfidence?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
          category?: string | null;
          storageType?: string | null;
          shelfLifeDays?: number | null;
          level1PerLevel2?: number | null;
          level2Unit?: string | null;
          classificationL1Id?: number | null;
          classificationL2Id?: number | null;
          classificationL3Id?: number | null;
          classificationConfidence?: 'HIGH' | 'MEDIUM' | 'LOW' | null;
          classificationReason?: string | null;
          classificationCandidates?: TaxonomyCandidate[] | null;
        }>(`/${factoryId.value}/raw-material-types/suggest`, { params });
        if (requestVersion !== suggestRequestVersion) return;

        if (!res.success) {
          // Fallback: old suggest-unit endpoint (original behavior)
          const unitRes = await get<string>(
            `/${factoryId.value}/raw-material-types/suggest-unit`,
            { params },
          );
          if (unitRes.success && unitRes.data && !unitManuallyEdited.value) {
            cascadeWriting.value = true;
            try {
              form.value.unit = unitRes.data;
              unitSuggestionHint.value = `已根据同工厂相似原料智能匹配为「${displayUnit(unitRes.data)}」，可手动修改`;
            } finally { cascadeWriting.value = false; }
          }
          return;
        }

        const d = res.data;
        if (!d) return;

        const candidates = (d.classificationCandidates || []).slice(0, 3);
        if (l3ManuallyEdited.value) {
          taxonomyCandidates.value = [];
          l3MatchHint.value = '名称已变化，但分类已手动修改；如需采用新名称建议，请点击“重新匹配分类”';
        } else if (d.classificationConfidence === 'HIGH' && candidates[0]) {
          await applyTaxonomyCandidate(candidates[0], false);
        } else if (d.classificationConfidence === 'MEDIUM') {
          taxonomyCandidates.value = candidates;
          l3MatchHint.value = '找到可能的现有分类，请确认后再保存；系统不会自动创建分类';
        } else {
          taxonomyCandidates.value = [];
        }

        // Apply cascade — only fields user hasn't manually edited, skip null values
        cascadeWriting.value = true;
        try {
          if (d.unit != null && !unitManuallyEdited.value && d.unitConfidence !== 'LOW') {
            form.value.unit = d.unit;
            const source = d.matchedMaterialName ? `，参考相似原料「${d.matchedMaterialName}」` : '';
            unitSuggestionHint.value = `已根据同工厂相似原料智能匹配为「${displayUnit(d.unit)}」${source}，可手动修改`;
          }
          if (d.category != null && !categoryManuallyEdited.value) {
            const family = resolveMaterialFamily(d.category);
            if (family) form.value.category = family;
          }
          if (d.storageType != null && !storageTypeManuallyEdited.value) form.value.storageType = d.storageType;
          if (d.shelfLifeDays != null && !shelfLifeManuallyEdited.value) form.value.shelfLifeDays = d.shelfLifeDays;
          if (!packagingManuallyEdited.value) {
            // ⛔ 智能填充**不能填出一条必被拒绝的规则**: 若历史同类原料的包装单位
            // 归一后就等于基本单位(两边都按 kg 收), 填进去立刻触发
            // 「包装单位不能与库存基本单位相同」——用户什么都没做就看到红字, 且
            // 不知道那是自动填的。这种情况直接不填, 让它保持「无固定包装」。
            const suggestedSameAsBase = d.level2Unit != null
              && sameUnit(d.level2Unit, form.value.unit);
            if (!suggestedSameAsBase) {
              const firstRule = packagingRules.value[0] || blankPackagingRule();
              if (!packagingRules.value[0]) packagingRules.value = [firstRule];
              if (d.level1PerLevel2 != null) firstRule.conversionFactor = d.level1PerLevel2;
              if (d.level2Unit != null) firstRule.packageUnit = d.level2Unit;
              firstRule.baseUnit = form.value.unit;
            }
          }
        } finally {
          cascadeWriting.value = false;
        }
      } catch {
        // silent — endpoint may not be deployed yet
      }
    }, 400);
  },
);

/**
 * 🔴 2026-08-07 客户事故(张权:「牛肉入仓都是抄码的 他没有固定重量一箱」)的**残留**:
 * 2026-08-06 已经把「至少一条包装规则」的强制去掉、第一条也能删了, 但**新建时仍然默认
 * 插一条空行**, 而保存校验会拦「请完整填写每条包装规则」。
 * 于是提示写着「不定重的原料请留空」, 用户照做把格子留空 → 被拦;
 * 真正能过的走法是点「删除」把整行去掉。**说的和做的相反。**
 *
 * 改成默认**一条都不给**: 什么都不动就是「无固定包装」(下方空状态会明说),
 * 真有固定包装的人点「添加多包装换算规则」。
 */
function resetPackaging() {
  packagingRules.value = [];
  packagingRuleErrors.value = {};
}

function openCreate() {
  editingId.value = null;
  form.value = {
    code: '',
    name: '',
    category: '',
    isByproduct: false,
    selfSupplied: false,
    unit: 'kg',
    storageType: storageTypeChoices()[0]?.value || '',
    shelfLifeDays: null,
    notes: '',
    taxTreatment: 'TAXABLE',
    taxRate: 'TAX_13',
    taxExemptionReason: '',
    taxIncludedUnitPrice: null,
    associatedCustomerId: null,
    packQtyPerProduct: null,
  };
  resetPackaging();
  resetManuallyEditedFlags();
  // Reset optional classification.
  segmentL1.value = null;
  segmentL2.value = null;
  segmentL3.value = null;
  resetCodeContractPreview();
  editingDisplayCode.value = '';
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
  if (segmentTree.value.length === 0) loadSegmentTree();
  loadCustomers();
  dialogVisible.value = true;
}

/**
 * 把物料已有的三级分类 id 反解成 L1/L2/L3 并回填选择器。
 *
 * 后端 `RawMaterialTypeDTO.classificationId` 给的是 **L3** 节点 id, 而界面是三级级联,
 * 所以要沿 `segmentTree` 往上找它的 L2/L1。找不到(分类被停用/删了/树还没加载)就保持
 * 三个都为空 —— 空表示「本次不改分类」, 提交时不带 classificationId, 后端不动原值。
 */
function applyExistingClassification(classificationId: number | string | null | undefined): void {
  const target = Number(classificationId ?? NaN);
  if (!Number.isFinite(target)) return;
  for (const l1 of segmentTree.value) {
    for (const l2 of l1.children ?? []) {
      const l3 = (l2.children ?? []).find((node) => Number(node.id) === target);
      if (l3) {
        segmentL1.value = l1.id;
        segmentL2.value = l2.id;
        segmentL3.value = l3.id;
        return;
      }
    }
  }
}

async function openEdit(row: TableRow) {
  editingId.value = String(row.id || '');
  form.value = {
    code: String(row.code || ''),
    name: String(row.name || ''),
    category: String(row.category || ''),
    // 编辑时保留既有副产标记 —— 漏了这行会让编辑一次就把标记悄悄抹掉
    isByproduct: row.isByproduct === true || row.isByproduct === 'true',
    // 缺陷 47: 与副产同一条纪律 —— 漏了这行, 编辑一次就把「自备料」标记悄悄抹掉
    selfSupplied: row.selfSupplied === true || row.selfSupplied === 'true',
    unit: String(row.unit || 'kg'),
    storageType: String(row.storageType || ''),
    shelfLifeDays: row.shelfLifeDays as number | null ?? null,
    notes: String(row.notes || ''),
    taxTreatment: row.taxTreatment === 'EXEMPT' ? 'EXEMPT' : 'TAXABLE',
    taxRate: String(row.taxRate || ''),
    taxExemptionReason: String(row.taxExemptionReason || ''),
    taxIncludedUnitPrice: (row.taxIncludedUnitPrice as number | null) ?? null,
    associatedCustomerId: (row.associatedCustomerId as string | null) ?? null,
    packQtyPerProduct: row.packQtyPerProduct != null ? Number(row.packQtyPerProduct) : null,
  };
  editingDisplayCode.value = materialDisplayCode(row);
  loadCustomers();
  resetPackaging();
  resetManuallyEditedFlags();
  segmentL1.value = null;
  segmentL2.value = null;
  segmentL3.value = null;
  resetCodeContractPreview();
  l3MatchHint.value = '';
  l3ManuallyEdited.value = false;
  if (segmentTree.value.length === 0) await loadSegmentTree();
  // 🔴 回填已有分类。不回填的话三级选择器一打开就是空的, 用户随手一保存
  // 就会把原来的分类改成别的(或者以为自己没选就不会动它, 其实选了才会动)。
  applyExistingClassification(row.classificationId as number | string | null | undefined);
  // 原料、辅料和包材共用计量/包装层级；库存基本单位始终来自 form.unit。
  try {
    const res = await get<{
      level1PerLevel2: number | null;
      level2Unit: string | null;
      level2PerLevel3: number | null;
      level3Unit: string | null;
      packagingSpecs?: MaterialPackagingRule[] | null;
    }>(
      `/${factoryId.value}/material-packaging/by-material/${editingId.value}`,
    );
    if (res.success && res.data) {
      if (res.data.packagingSpecs?.length) {
        packagingRules.value = res.data.packagingSpecs.map((rule, index) => ({
          ...rule,
          baseUnit: form.value.unit,
          defaultSpec: index === 0,
          active: rule.active !== false,
          sortOrder: index,
        }));
      } else {
        const legacyRules: MaterialPackagingRule[] = [];
        const firstFactor = Number(res.data.level1PerLevel2);
        if (res.data.level2Unit && firstFactor > 0) {
          legacyRules.push({
            ...blankPackagingRule(0),
            packageUnit: res.data.level2Unit,
            conversionFactor: firstFactor,
          });
        }
        const secondFactor = Number(res.data.level2PerLevel3);
        if (res.data.level3Unit && firstFactor > 0 && secondFactor > 0) {
          legacyRules.push({
            ...blankPackagingRule(1),
            packageUnit: res.data.level3Unit,
            conversionFactor: firstFactor * secondFactor,
          });
        }
        // 没有历史规则 = 这个物料本来就没有固定包装, 别塞空行(见 resetPackaging 注释)
        packagingRules.value = legacyRules;
      }
    }
  } catch { /* 未配置换算时正常空 */ }
  dialogVisible.value = true;
}

const submitting = ref(false);
/**
 * 🔴 2026-08-13: 原本是 `&& !editingId.value` —— 分类段只在【新建】时能选,
 * 编辑既有物料时这一段整个不显示, 提交时也不带 `classificationId`。
 * 后果是**存量物料的分类永远补不上**: 生产实测 479 个启用物料只有 10 个挂了分类
 * (包材 63 个里 0 个)。而画布上「替代包材」正是按分类判作用域的, 于是那栏
 * 对所有人恒灰, 提示让人去维护档案 —— 档案里却没有这个控件。
 *
 * 后端两条路径本来就都支持(createMaterialType / updateMaterialType 都会
 * requireValidSegmentChain + ensureCategoryMatchesSegment), 缺的只是界面。
 */
const showSegmentEditor = computed(() => hasSegmentDictionary.value);
async function handleSave() {
  if (!form.value.name) return ElMessage.warning(t('warehouse.material_types_list.message_ec004968c37b'));
  if (!form.value.category) return ElMessage.warning(t('warehouse.material_types_list.message_64a754862807'));
  if (!form.value.unit) return ElMessage.warning(t('warehouse.material_types_list.message_164cb209d751'));
  if (!isPackagingMaterial.value && !form.value.storageType) return ElMessage.warning(t('warehouse.material_types_list.message_38115dfa60c6'));
  if (canViewPrice.value) {
    if (form.value.taxTreatment === 'TAXABLE' && !form.value.taxRate) return ElMessage.warning(t('warehouse.material_types_list.message_dda4ec5efe8b'));
    if (form.value.taxTreatment === 'EXEMPT' && !form.value.taxExemptionReason.trim()) {
      return ElMessage.warning(t('warehouse.material_types_list.message_6fd577f5e809'));
    }
    if (form.value.taxIncludedUnitPrice != null && Number(form.value.taxIncludedUnitPrice) <= 0) {
      return ElMessage.warning(isPackagingMaterial.value
        ? '采购参考价必须大于 0'
        : '采购参考价如填写，必须大于 0；未知价格请留空');
    }
    // 🔴 2026-08-13 真机: 包材的采购参考价在后端是【必填】——
    // RawMaterialTypeServiceImpl 的 create/update 两条路径都有
    // `if (packaging) validateRequiredPricing(...)`, 缺价直接 400「含税单价必须大于0」。
    // 而界面上这个字段一直写着「选填；未知价格请留空」, 于是留空 → 保存 → 后端拒绝。
    // LIUSHANMEN 25 个启用包材里 24 个价格是空的, 等于它们**保存任何修改都会被拦**
    // (实测: 不带价格的 PUT 与带价格的 PUT 只差这一项, 前者 400 后者 200)。
    // 这里先拦一道并说清原因, 免得用户按界面说的做了却存不进去。
    if (isPackagingMaterial.value
      && (form.value.taxIncludedUnitPrice == null || Number(form.value.taxIncludedUnitPrice) <= 0)) {
      return ElMessage.warning(t('warehouse.material_types_list.message_c13f5aa57ea9'));
    }
  }
  const hasPartialClassification = Boolean(segmentL1.value || segmentL2.value || segmentL3.value);
  if (hasPartialClassification && (!segmentL1.value || !segmentL2.value || !segmentL3.value)) {
    return ElMessage.error(t('warehouse.material_types_list.message_6889d00b46e6'));
  }
  if (!editingId.value && !String(form.value.code || '').trim()) {
    if (!codeSuggestionError.value) await refreshCodeSuggestion(true);
    if (!String(form.value.code || '').trim()) return;
  }

  // 每条包装规则都直接换算到唯一库存基本单位。
  //
  // ⛔ 2026-08-06 客户事故: 这里原本强制「至少一条」, 而另一条校验又禁止
  // 「包装单位 == 基本单位」, 且第一条规则**没有删除按钮** —— 三条合起来,
  // 「抄码牛肉、没有固定重量一箱」这个客观事实在系统里没有任何合法表达方式。
  // 六膳门于是编了个假的 `1 斤 = 1 kg`(物理上 1 斤 = 0.5kg), 收货界面从此把
  // kg 显示成「斤」。
  //
  // 后端本来就允许空列表(ProductPackagingSpecServiceImpl#replace 接受 []),
  // 收货侧也会优雅回落到「采购单规格」(PendingPurchaseReceivingPanel 的
  // orderOption), 所以「不配包装换算」是完全支持的 —— 只是前端不让填而已。
  //
  // D-S2-12 (2026-10-29 验收): 默认那条空白行留空就点保存, 以前整张表单被一条 3 秒就消失的
  // toast 拦住, 验收读到的是「没有任何提示也没有请求」。现在: 全空的新行忽略; 填一半的行
  // 拦下, 在那一行下面写清缺什么, 再弹一条不自动消失的提示 —— ⛔ 不静默失败。
  const packagingSplit = splitPackagingRules(packagingRules.value);
  packagingRuleErrors.value = packagingSplit.problems;
  if (Object.keys(packagingSplit.problems).length) {
    ElMessage({
      type: 'warning',
      message: packagingProblemSummary(packagingSplit.problems),
      duration: 0,
      showClose: true,
    });
    return;
  }

  const submittedPackagingRules = packagingSplit.complete
    .map((rule, index) => ({
      id: rule.id,
      name: index === 0 ? '默认包装' : `包装规格 ${index + 1}`,
      packageUnit: rule.packageUnit.trim(),
      baseUnit: form.value.unit,
      conversionFactor: Number(rule.conversionFactor),
      defaultSpec: index === 0,
      active: true,
      sortOrder: index,
      version: rule.version,
    }));
  const repeatedUnits = submittedPackagingRules.some((rule, index, all) =>
    all.findIndex(candidate =>
      candidate.packageUnit.trim().toLowerCase() === rule.packageUnit.trim().toLowerCase()) !== index);
  if (repeatedUnits) return ElMessage.warning(t('warehouse.material_types_list.message_3ba07ac3ad28'));
  // 用 sameUnit 而不是字面比较: `kg` 与 `公斤`、`pcs` 与 `件` 是同一个单位的两种写法,
  // 字面比较放得过去, 存进去就是一条恒等换算(1公斤=1kg), 与不填没区别却更容易误导。
  if (submittedPackagingRules.some(rule => sameUnit(rule.packageUnit, form.value.unit))) {
    return ElMessage.warning(t('warehouse.material_types_list.message_dbd26d87cc24'));
  }

  submitting.value = true;
  try {
    let materialId: string;
    // 关弹窗会重置 editingId, 而下面的提示文案要在关窗之后才拼 —— 先把它记下来。
    const editingWas = Boolean(editingId.value);
    const materialPayload: Record<string, unknown> = { ...form.value };
    // 编辑态料号不可修改；新建态必须把建议值或用户修改值原样提交。
    if (editingId.value) {
      delete materialPayload.code;
    }
    if (!canViewPrice.value) {
      delete materialPayload.taxTreatment;
      delete materialPayload.taxRate;
      delete materialPayload.taxExemptionReason;
      delete materialPayload.taxIncludedUnitPrice;
    }
    if (isPackagingMaterial.value) {
      delete materialPayload.storageType;
    } else {
      delete materialPayload.associatedCustomerId;
      delete materialPayload.packQtyPerProduct;
    }
    if (editingId.value) {
      const res = await put(`/${factoryId.value}/raw-material-types/${editingId.value}`, {
        ...materialPayload,
        // 与新建同源: 不带这个字段, 存量物料的分类就永远补不上(见 showSegmentEditor 注释)。
        // 后端 updateMaterialType 只在非 null 时才动它, 所以没选分类时保持原值。
        classificationId: typeof segmentL3.value === 'number' ? segmentL3.value : undefined,
      });
      if (!res.success) throw new Error(res.message || '更新失败');
      materialId = editingId.value;
      ElMessage.success(t('warehouse.material_types_list.message_7c0d2664869c'));
    } else {
      const payload = {
        ...materialPayload,
        classificationId: typeof segmentL3.value === 'number' ? segmentL3.value : undefined,
      };
      const res = await post<{ id: string }>(`/${factoryId.value}/raw-material-types`, payload);
      if (!res.success) throw new Error(res.message || '创建失败');
      materialId = res.data?.id || '';
      ElMessage.success(t('warehouse.material_types_list.message_1ab62884f4ee'));
    }

    // 动态包装规则 upsert / delete；后端同时投影旧层级字段，兼容历史调用方。
    //
    // 🔴 2026-08-19: 这里原来直接 `await put(...)` 裸挂在外层 try 上。物料主记录在上面
    //    已经【独立事务提交】了(RawMaterialTypeServiceImpl.createMaterialType 无后置副作用),
    //    而包装规则是【另一个接口、另一个事务】(MaterialPackagingHierarchyController
    //    PUT /material-packaging/by-material/{id}), 它的 400/404 回滚不掉已建好的物料。
    //    于是包装校验一失败就跳到外层 catch, 弹「原料类型保存失败」, 同时
    //    `dialogVisible=false` / `loadData()` 都不执行 —— 用户看到失败提示、列表里也看不到
    //    新记录, 但库里那一行**已经在了**。测试同学报的「新建辅料SKU副产提示失败, 但新建成功」
    //    就是这条路径 (副产开关与包装规则同在这个表单里)。
    //
    //    ⇒ 包装规则单独 try/catch: 主记录成败与包装成败是两件事, 必须分开说。
    let packagingError: unknown = null;
    if (submittedPackagingRules.length) {
      try {
        await put(`/${factoryId.value}/material-packaging/by-material/${materialId}`, {
          level1Unit: form.value.unit,
          packagingSpecs: submittedPackagingRules,
        });
      } catch (e) {
        packagingError = e;
      }
    } else if (editingId.value) {
      // 编辑模式下用户清空全部规则 → 删除现有配置
      try { await del(`/${factoryId.value}/material-packaging/by-material/${materialId}`); }
      catch { /* 不存在也 OK */ }
    }

    // 主记录已落库 —— 无论包装规则成败都要关弹窗 + 刷新列表, 否则用户看不到那条已建好的记录,
    // 会照着「失败」提示再建一次, 撞重复料号。
    dialogVisible.value = false;
    loadData();

    if (packagingError) {
      // 4 位一体: 说清「哪一半成了、哪一半没成」+ 下一步动作, 不要再谎报整体失败。
      handleCatchError(
        packagingError,
        `${editingWas ? '物料已保存' : '物料已创建'}，但包装规格未保存。请在列表中编辑该物料重新填写包装规格`,
      );
    }
  } catch (e) {
    console.error(e);
    handleCatchError(e, '原料类型保存失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

// ==================== SupplierMaterial 双向关系 ====================
// 原料侧与供应商详情共用同一 SupplierMaterial 关系实体/API；采购历史不是关系配置真值。
const suppliersDialogVisible = ref(false);
const suppliersForMaterial = ref<SupplierMaterialRelation[]>([]);
const suppliersLoading = ref(false);
const suppliersDialogMaterialName = ref('');
const suppliersDialogMaterial = ref<{ id: string; name: string; code?: string; unit?: string } | null>(null);
const activeSupplierOptions = ref<SupplierRecord[]>([]);
const relationAllUnitOptions = ref<SystemUnit[]>([]);
const relationPurchaseUnitOptions = ref<SystemUnit[]>([]);
const relationDialogVisible = ref(false);
const relationEditing = ref<SupplierMaterialRelation | null>(null);
const relationSaving = ref(false);
const relationForm = reactive({
  supplierId: '', supplierMaterialCode: '', purchaseUnit: '', defaultPurchasePrice: null as number | null,
  currency: 'CNY', minOrderQuantity: null as number | null, leadTimeDays: null as number | null,
  preferred: false, active: true,
});

const availableSupplierOptions = computed(() => {
  const linked = new Set(suppliersForMaterial.value.map((row) => String(row.supplierId || '')));
  return activeSupplierOptions.value.filter((supplier) => !linked.has(String(supplier.id)));
});
const materialRelationUnitResolution = computed(() => resolvePurchaseUnits(
  suppliersDialogMaterial.value?.unit,
  relationAllUnitOptions.value,
  relationPurchaseUnitOptions.value,
));
const compatibleMaterialPurchaseUnits = computed(() => materialRelationUnitResolution.value.compatibleUnits);
const materialRelationUnitReason = computed(() => (suppliersDialogMaterial.value
  ? purchaseUnitSelectionReason(relationForm.purchaseUnit, materialRelationUnitResolution.value)
  : ''));

async function reloadMaterialSuppliers(): Promise<void> {
  const material = suppliersDialogMaterial.value;
  if (!material) return;
  suppliersForMaterial.value = await listMaterialSuppliers(factoryId.value, material.id);
}

async function openSuppliersForMaterial(row: TableRow) {
  const materialId = String(row.id || '').trim();
  const materialName = String(row.name || '').trim();
  if (!materialId || !materialName) {
    ElMessage.warning(t('warehouse.material_types_list.message_39fe42c318e9'));
    return;
  }
  suppliersDialogMaterialName.value = materialName;
  suppliersDialogMaterial.value = {
    id: materialId,
    name: materialName,
    code: String(row.code || ''),
    unit: String(row.unit || ''),
  };
  suppliersForMaterial.value = [];
  suppliersDialogVisible.value = true;
  suppliersLoading.value = true;
  try {
    await reloadMaterialSuppliers();
  } catch {
    /* interceptor */
  } finally {
    suppliersLoading.value = false;
  }
}

async function ensureActiveSupplierOptions(): Promise<void> {
  activeSupplierOptions.value = await listActiveSuppliers(factoryId.value);
}

async function ensureRelationUnitOptions(): Promise<void> {
  const [allUnits, purchaseUnits] = await Promise.all([
    relationAllUnitOptions.value.length ? Promise.resolve(null) : listSystemUnits(factoryId.value),
    relationPurchaseUnitOptions.value.length
      ? Promise.resolve(null)
      : listSystemUnits(factoryId.value, 'PURCHASE_QUANTITY'),
  ]);
  if (allUnits) relationAllUnitOptions.value = allUnits.data ?? [];
  if (purchaseUnits) relationPurchaseUnitOptions.value = purchaseUnits.data ?? [];
}

async function openMaterialRelationCreate(): Promise<void> {
  if (!canManageSupplierRelations.value) return;
  await Promise.all([ensureActiveSupplierOptions(), ensureRelationUnitOptions()]);
  relationEditing.value = null;
  const resolution = resolvePurchaseUnits(
    suppliersDialogMaterial.value?.unit,
    relationAllUnitOptions.value,
    relationPurchaseUnitOptions.value,
  );
  Object.assign(relationForm, {
    supplierId: '', supplierMaterialCode: '', purchaseUnit: resolution.defaultUnit,
    defaultPurchasePrice: null, currency: 'CNY', minOrderQuantity: null, leadTimeDays: null,
    preferred: false, active: true,
  });
  relationDialogVisible.value = true;
}

async function openMaterialRelationEdit(row: SupplierMaterialRelation): Promise<void> {
  if (!canManageSupplierRelations.value) return;
  await Promise.all([ensureActiveSupplierOptions(), ensureRelationUnitOptions()]);
  relationEditing.value = row;
  Object.assign(relationForm, {
    supplierId: String(row.supplierId || ''),
    supplierMaterialCode: row.supplierMaterialCode || '',
    purchaseUnit: row.purchaseUnit || row.baseUnit || suppliersDialogMaterial.value?.unit || '',
    defaultPurchasePrice: row.defaultPurchasePrice ?? null,
    currency: row.currency || 'CNY',
    minOrderQuantity: row.minOrderQuantity ?? null,
    leadTimeDays: row.leadTimeDays ?? null,
    preferred: Boolean(row.preferred),
    active: row.active !== false,
  });
  relationDialogVisible.value = true;
}

async function saveMaterialRelation(): Promise<void> {
  const material = suppliersDialogMaterial.value;
  if (!material) return;
  if (!relationForm.supplierId) return void ElMessage.warning(t('warehouse.material_types_list.message_ade9f5ce860c'));
  if (materialRelationUnitReason.value) return void ElMessage.warning(materialRelationUnitReason.value);
  if (!relationForm.currency.trim()) return void ElMessage.warning(t('warehouse.material_types_list.message_3ea9ca010e1e'));
  const payload: SupplierMaterialPayload = {
    materialTypeId: material.id,
    supplierMaterialCode: relationForm.supplierMaterialCode.trim() || undefined,
    purchaseUnit: relationForm.purchaseUnit,
    defaultPurchasePrice: relationForm.defaultPurchasePrice,
    currency: relationForm.currency.trim().toUpperCase(),
    minOrderQuantity: relationForm.minOrderQuantity,
    leadTimeDays: relationForm.leadTimeDays,
    preferred: relationForm.preferred,
    active: relationForm.active,
    version: relationEditing.value?.version ?? undefined,
  };
  relationSaving.value = true;
  try {
    if (relationEditing.value) {
      await updateSupplierMaterial(factoryId.value, relationForm.supplierId, relationEditing.value.id, payload);
    } else {
      await createSupplierMaterial(factoryId.value, relationForm.supplierId, payload);
    }
    await reloadMaterialSuppliers();
    relationDialogVisible.value = false;
    ElMessage.success(t('warehouse.material_types_list.message_3814643a77f1'));
  } finally {
    relationSaving.value = false;
  }
}

async function deactivateMaterialRelation(row: SupplierMaterialRelation): Promise<void> {
  const supplierId = String(row.supplierId || '');
  if (!supplierId) return void ElMessage.warning(t('warehouse.material_types_list.message_319c64d9de4b'));
  await ElMessageBox.confirm(
    `确定停用「${row.supplierName || supplierId}」与当前物料的供应关系吗？历史采购记录不会被删除。`,
    t('warehouse.material_types_list.message_e1d90e216041'),
    { type: 'warning', confirmButtonText: t('warehouse.material_types_list.confirmbuttontext_4e6fd0e28c55'), cancelButtonText: t('warehouse.material_types_list.cancelbuttontext_2cd0f3be8738') },
  );
  await deleteSupplierMaterial(factoryId.value, supplierId, row.id, row.version);
  await reloadMaterialSuppliers();
  ElMessage.success(t('warehouse.material_types_list.message_1db08ede06a3'));
}

// ==================== 别名 / 合并重复物料 ====================
const aliasDialogVisible = ref(false);
const aliasTarget = ref<RawMaterialTypeRow | null>(null);
const mergeDialogVisible = ref(false);
const mergeTarget = ref<RawMaterialTypeRow | null>(null);

const impactVisible = ref(false);
const impactTarget = ref<RawMaterialTypeRow | null>(null);

function openImpact(row: TableRow): void {
  impactTarget.value = row as RawMaterialTypeRow;
  impactVisible.value = true;
}

function openAliasDialog(row: TableRow): void {
  aliasTarget.value = row as RawMaterialTypeRow;
  aliasDialogVisible.value = true;
}

function openMergeDialog(row: TableRow): void {
  mergeTarget.value = row as RawMaterialTypeRow;
  mergeDialogVisible.value = true;
}

async function handleDelete(row: TableRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除原料类型「${row.name}」? 该原料关联的批次仍保留, 但无法新建新批次.`,
      t('warehouse.material_types_list.message_fe43bc43c294'),
      { type: 'warning' },
    );
    const res = await del(`/${factoryId.value}/raw-material-types/${row.id}`);
    if (res.success) {
      ElMessage.success(t('warehouse.material_types_list.message_5223f91b9670'));
      loadData();
    }
  } catch { /* user cancelled or interceptor toasted */ }
}

// 关键字过滤是纯客户端 (filteredData computed 已实时响应 searchKeyword), 敲字/清空即生效,
// 不需要等 Enter/点搜索按钮才刷新 — 这里只需把页码归 1 (换关键字后从第一页开始看结果)。
watch(searchKeyword, () => {
  pagination.value.page = 1;
});

function handleSearch() {
  pagination.value.page = 1;
  loadData();
}
function handleRefresh() {
  searchKeyword.value = '';
  filterSegmentL1.value = null;
  filterSegmentL2.value = null;
  filterSegmentL3.value = null;
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
</script>

<template>
  <div class="page-wrapper">
    <ConceptDisambiguationAlert
      here-name="原料类型字典"
      here="原料的「分类抽象」（如「冻猪蹄」「吸塑盒2014-3.5」），定义后才能在采购订单 / 入库登记 / BOM 里被选择"
      other-name="仓储管理 → 原料 / 物料 (采购入库)"
      other="原料的「具体批次」，记录某次入库的数量、价格、保质期"
      other-path="/warehouse/materials"
      consequence="先在这里建原料类型, 再去入库登记里给它建批次"
    />
    <UCard class="page-card" >
      <template #header>
        <div class="card-header">
          <div class="header-left">
            <span class="page-title">{{ t('warehouse.material_types_list.text_03134f6f725a') }}</span>
            <span class="data-count">{{ t('warehouse.material_types_list.text_76e547a8fa54') }} {{ pagination.total }} {{ t('warehouse.material_types_list.text_6016bf63b0ff') }}</span>
          </div>
          <div class="header-right">
            <span v-if="showMaterialTypeWriteRoleHint" class="write-role-hint">
              {{ t('warehouse.material_types_list.text_677a751e1794') }}
            </span>
            <el-tooltip
              v-if="canWrite && !canManageMaterialTypeDictionary"
              content="物料字典由仓储主管维护，当前角色仅可查看"
              placement="top"
            >
              <el-button type="primary" :icon="Plus" disabled>{{ t('warehouse.material_types_list.action_1285895aeb88') }}</el-button>
            </el-tooltip>
            <el-button v-else-if="canWrite" type="primary" :icon="Plus" @click="openCreate">
              {{ t('warehouse.material_types_list.action_1285895aeb88') }}
            </el-button>
          </div>
        </div>
      </template>

      <div class="search-bar">
        <!-- 分类层级筛选：用户只看分类名称，节点 ID 由系统维护。 -->
        <CompatSelect
          v-model="filterSegmentL1"
          :placeholder="t('warehouse.material_types_list.placeholder_80dab2e19fb1')"
          clearable
          style="width: 170px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in segmentL1Options"
            :key="opt.id"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.id"
          />
        </CompatSelect>
        <CompatSelect
          v-model="filterSegmentL2"
          :placeholder="t('warehouse.material_types_list.placeholder_913a953dee42')"
          clearable
          filterable
          :disabled="!filterSegmentL1"
          style="width: 190px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in filterSegmentL2Options"
            :key="opt.id"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.id"
          />
        </CompatSelect>
        <CompatSelect
          v-model="filterSegmentL3"
          :placeholder="t('warehouse.material_types_list.placeholder_6e8e4b775fb5')"
          clearable
          filterable
          :disabled="!filterSegmentL2"
          style="width: 220px"
          @change="handleSearch"
        >
          <CompatOption
            v-for="opt in filterSegmentL3Options"
            :key="opt.id"
            :label="formatSegmentOptionLabel(opt)"
            :value="opt.id"
          />
        </CompatSelect>
        <el-input
          v-model="searchKeyword"
          :placeholder="t('warehouse.material_types_list.placeholder_26776cea5174')"
          clearable
          style="width: 280px"
          @keyup.enter="handleSearch"
        />
        <el-button :icon="Search" @click="handleSearch">{{ t('warehouse.material_types_list.action_44ce7ae909bb') }}</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">{{ t('warehouse.material_types_list.action_cb5d682bac3d') }}</el-button>
      </div>

      <CompatTable v-loading="loading" :data="tableData" stripe>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_7033b8a7afc4')" min-width="180">
          <template #default="{ row }">
            <div class="material-code-cell">
              <span class="material-code-value">{{ materialDisplayCode(row) }}</span>
            </div>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_a27f31ef6fa4')" min-width="200">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div v-if="Array.isArray(row.aliases) && row.aliases.length" class="alias-line" data-test="alias-line">
              {{ t('warehouse.material_types_list.text_cf95352aaf25') }}{{ row.aliases.join('、') }}
            </div>
            <UBadge variant="soft" v-if="row.mergedIntoId" size="sm" color="info" data-test="merged-tag">
              {{ t('warehouse.material_types_list.text_ecccf8ea7a90') }}{{ row.mergedIntoName || row.mergedIntoId }}」
            </UBadge>
          </template>
        </CompatTableColumn>
        <CompatTableColumn prop="category" :label="t('warehouse.material_types_list.label_0d12cbd6562b')" width="120" />
        <CompatTableColumn prop="unit" :label="t('warehouse.material_types_list.label_80b19d68b149')" width="80">
          <template #default="{ row }">{{ displayUnit(row.unit) }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="storageType" :label="t('warehouse.material_types_list.label_b2f0fcd336bd')" width="100" />
        <CompatTableColumn prop="shelfLifeDays" :label="t('warehouse.material_types_list.label_4d1d935843cd')" width="120">
          <template #default="{ row }">{{ row.shelfLifeDays ?? '-' }}</template>
        </CompatTableColumn>
        <!-- T2-5b (issue #534): F006 客户反馈 — expose 移动均价 (RawMaterialTypeDTO.movingAvgPrice)
             gated by canViewPrice RBAC (per PR #443/#467 price-field policy) -->
        <CompatTableColumn v-if="canViewPrice" prop="movingAvgPrice" :label="t('warehouse.material_types_list.label_bd201c58efe5')" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.movingAvgPrice != null">{{ formatAmount(row.movingAvgPrice) }}</span>
            <span v-else style="color: #c0c4cc">-</span>
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_ed31fbb483ee')" width="360" fixed="right">
          <template #default="{ row }">
            <!-- Issue #779: 反查供应商入口 -->
            <el-button link type="primary" :icon="View" @click="openSuppliersForMaterial(row)">{{ t('warehouse.material_types_list.action_033ed5945254') }}</el-button>
            <UButton variant="link" color="primary" data-test="row-supply-impact" @click="openImpact(row)">{{ t('warehouse.material_types_list.action_b2a3fc7add19') }}</UButton>
            <!-- 别名 / 合并: 与编辑同一道写门禁; 已合并的行不再允许编辑 / 合并 / 加别名 -->
            <UButton
              v-if="canWrite && !row.mergedIntoId"
              variant="link"
              color="primary"
              :disabled="!canManageMaterialTypeDictionary"
              data-test="row-alias"
              @click="openAliasDialog(row)"
            >{{ t('warehouse.material_types_list.action_55c4cb3d7464') }}</UButton>
            <UButton
              v-if="canWrite && !row.mergedIntoId"
              variant="link"
              color="warning"
              :disabled="!canManageMaterialTypeDictionary"
              data-test="row-merge"
              @click="openMergeDialog(row)"
            >{{ t('warehouse.material_types_list.action_3ad7e3b86a2b') }}</UButton>
            <el-button
              v-if="canWrite && !row.mergedIntoId"
              link
              type="primary"
              :icon="Edit"
              :disabled="!canManageMaterialTypeDictionary"
              @click="openEdit(row)"
            >{{ t('warehouse.material_types_list.action_051836569928') }}</el-button>
            <el-button
              v-if="canWrite"
              link
              type="danger"
              :icon="DeleteIcon"
              :disabled="!canManageMaterialTypeDictionary"
              @click="handleDelete(row)"
            >{{ t('warehouse.material_types_list.action_2f9daa828907') }}</el-button>
          </template>
        </CompatTableColumn>
      </CompatTable>

      <CompatPagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </UCard>

    <SupplyImpactDialog
      v-if="impactVisible"
      v-model="impactVisible"
      :factory-id="factoryId"
      :target="{ materialTypeId: impactTarget?.id }"
      :target-name="impactTarget?.name ?? ''"
    />
    <MaterialAliasDialog
      v-model="aliasDialogVisible"
      :factory-id="factoryId"
      :material="aliasTarget"
      @changed="loadData"
    />
    <MaterialMergeDialog
      v-model="mergeDialogVisible"
      :factory-id="factoryId"
      :material="mergeTarget"
      @merged="loadData"
    />

    <!-- ==================== T159-A: Create / Edit Dialog ==================== -->
    <CompatDialog v-model="dialogVisible" :title="dialogTitle" width="660px" destroy-on-close>
      <CompatForm :model="form" label-width="120px">

        <CompatFormItem v-if="editingId" :label="t('warehouse.material_types_list.label_7033b8a7afc4')">
          <el-input :model-value="editingDisplayCode" disabled :prefix-icon="Lock" />
          <div class="field-hint">{{ t('warehouse.material_types_list.text_41c942cb4793') }}</div>
        </CompatFormItem>
        <CompatFormItem v-else :label="t('warehouse.material_types_list.label_7033b8a7afc4')" required>
          <el-input
            :model-value="form.code"
            maxlength="32"
            :placeholder="t('warehouse.material_types_list.placeholder_689aedef99b5')"
            :disabled="!form.category || codeSuggestionLoading || Boolean(codeSuggestionError)"
            @input="handleCodeInput"
          />
          <UButton color="neutral" variant="outline"
            size="sm"
            :disabled="!form.category"
            :loading="codeSuggestionLoading"
            @click="refreshCodeSuggestion(true)"
          >{{ t('warehouse.material_types_list.action_f53ff005bb08') }}</UButton>
          <div v-if="!form.category" class="field-hint">{{ t('warehouse.material_types_list.text_d1af61935c7b') }}</div>
          <div v-else-if="codeSuggestionError" class="field-hint field-hint--error">{{ codeSuggestionError }}</div>
          <div v-else-if="codeContractHint" class="field-hint field-hint--matched">{{ codeContractHint }}</div>
          <div v-else class="field-hint">{{ t('warehouse.material_types_list.text_b438f58443e9') }}</div>
        </CompatFormItem>

        <CompatFormItem :label="t('warehouse.material_types_list.label_a27f31ef6fa4')" required>
          <UInput v-model="form.name" :placeholder="t('warehouse.material_types_list.placeholder_3a4523c1f38e')" />
          <div class="field-hint">{{ t('warehouse.material_types_list.text_3710c459c054') }}</div>
        </CompatFormItem>

        <CompatFormItem :label="t('warehouse.material_types_list.label_0d12cbd6562b')" required>
          <CompatSelect v-model="form.category" :placeholder="t('warehouse.material_types_list.placeholder_64a754862807')" style="width: 100%" filterable>
            <CompatOption
              v-if="editingId && form.category && !isMaterialFamily(form.category)"
              :label="`${form.category} (历史)`"
              :value="form.category"
              disabled
            />
            <CompatOption
              v-for="opt in materialFamilyOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
          <div class="field-hint">{{ t('warehouse.material_types_list.text_b696e9d438aa') }}</div>
        </CompatFormItem>

        <!-- 副产是「来历」不是「材质」: 打了标记的物料仍保留其类别(如原料), 因此既能被排除出
             采购/补货建议, 又能被别的 workflow 当原料投入 —— 后者正是副产进原料字典的初衷。 -->
        <CompatFormItem :label="t('warehouse.material_types_list.label_45102696658e')">
          <el-switch v-model="form.isByproduct" active-text="这是副产（生产产出，无采购来源）" />
          <div class="field-hint">
            {{ t('warehouse.material_types_list.text_67d1e1e63c18') }}
          </div>
        </CompatFormItem>

        <!-- 缺陷 47 (owner 2026-09-02 裁定「它没有所谓的库存一说」): 冰水/水/蒸汽/现场自制
             高汤等现场取用、不走采购入库的物料。挂在物料类型本身 —— 领料单仍会把它列成
             配方提醒行(不消失), 但不强制填数量、不查库存、不因库存不足拦截。 -->
        <CompatFormItem :label="t('warehouse.material_types_list.label_7b1294164a24')">
          <el-switch v-model="form.selfSupplied" active-text="自备料（现场取用，不计库存）" />
          <div class="field-hint">
            {{ t('warehouse.material_types_list.text_131051f198e5') }}
          </div>
        </CompatFormItem>

        <!-- 单位统一入口：搜索不到时可现场创建，重复时直接选择已有单位 -->
        <CompatFormItem :label="t('warehouse.material_types_list.label_47908dee54bd')" required>
          <UnitSelect
            v-model="form.unit"
            :factory-id="factoryId"
            usage-scope="INVENTORY_QUANTITY"
            :show-symbol="false"
            :placeholder="t('warehouse.material_types_list.placeholder_e31adb8a5466')"
            :clearable="false"
            @change="handleUnitUserChange"
          />
          <div class="field-hint">{{ t('warehouse.material_types_list.text_aaee3b64369a') }}</div>
          <div v-if="unitSuggestionHint && !unitManuallyEdited && !editingId" class="field-hint field-hint--matched">
            {{ unitSuggestionHint }}
          </div>
          <div v-if="unitManuallyEdited && !editingId" class="field-hint field-hint--manual">
            {{ t('warehouse.material_types_list.text_93f3cc1f06e5') }}
          </div>
        </CompatFormItem>

        <!--
          采购包装直接换算到唯一库存基本单位。**不是必填** ——
          抄码/不定重原料(如按实际过磅收的牛肉)本来就没有固定包装, 强制填只会逼出
          假换算(六膳门就填过 `1 斤 = 1 kg`)。见 handleSave 里的说明。
        -->
        <CompatFormItem v-if="form.unit" :label="t('warehouse.material_types_list.label_3c3fab794f5d')">
          <div class="material-packaging-spec-list">
            <div class="packaging-rule-note">
              {{ t('warehouse.material_types_list.text_68e9c3d5e56a') }}<strong>{{ t('warehouse.material_types_list.text_f9d2eb12de77') }}</strong>{{ t('warehouse.material_types_list.text_4ca5fd4a5782') }}{{ displayUnit(form.unit) }}{{ t('warehouse.material_types_list.text_ad830c699136') }}
            </div>
            <div v-if="!packagingRules.length" class="packaging-rule-empty">
              {{ t('warehouse.material_types_list.text_dc444bb21cea') }} {{ displayUnit(form.unit) }} {{ t('warehouse.material_types_list.text_7c91db51d562') }}
            </div>
            <div
              v-for="(rule, index) in packagingRules"
              :key="rule.id || `new-${index}`"
              class="material-packaging-spec-item"
            >
              <div class="spec-conversion-row">
                <UBadge variant="soft" v-if="index === 0" color="success" size="sm">{{ t('warehouse.material_types_list.text_844b8cc8dff7') }}</UBadge>
                <span v-else class="packaging-rule-index">{{ t('warehouse.material_types_list.text_1123245d1bed') }} {{ index + 1 }}</span>
                <span class="conversion-label">1</span>
                <UnitSelect
                  v-model="rule.packageUnit"
                  :factory-id="factoryId"
                  usage-scope="PURCHASE_QUANTITY"
                  :placeholder="t('warehouse.material_types_list.placeholder_4ca0a07cb6f2')"
                  class="spec-unit-select"
                />
                <span class="conversion-equals">=</span>
                <el-input-number
                  v-model="rule.conversionFactor"
                  :min="0.00000001"
                  :precision="8"
                  :controls="false"
                  :placeholder="t('warehouse.material_types_list.placeholder_33d0c85dd078')"
                  class="spec-factor-input"
                />
                <el-input
                  :model-value="displayUnit(form.unit)"
                  disabled
                  class="spec-base-unit"
                />
                <!-- 第一条也可以删 —— 否则「没有固定包装」就无从表达 -->
                <UButton color="error" variant="link" @click="removePackagingRule(index)">
                  {{ t('warehouse.material_types_list.action_2f9daa828907') }}
                </UButton>
              </div>
              <div v-if="packagingRuleErrors[index]" class="spec-rule-error" role="alert">
                {{ packagingRuleErrors[index] }}
              </div>
              <div
                v-if="rule.packageUnit && sameUnit(rule.packageUnit, form.unit)"
                class="spec-same-warn"
              >
                {{ t('warehouse.material_types_list.text_fc493ed4024a') }}
              </div>
              <div v-else-if="packagingRuleText(rule)" class="spec-echo">
                {{ packagingRuleText(rule) }}
              </div>
            </div>
            <UButton color="neutral" variant="outline" class="add-packaging-rule-button" @click="addPackagingRule">
              {{ t('warehouse.material_types_list.action_b648eb24c34a') }}
            </UButton>
          </div>
        </CompatFormItem>

        <CompatFormItem v-if="!isPackagingMaterial" :label="t('warehouse.material_types_list.label_b2f0fcd336bd')" required>
          <CompatSelect v-model="form.storageType" :placeholder="t('warehouse.material_types_list.placeholder_38115dfa60c6')" style="width: 100%">
            <CompatOption
              v-for="opt in mergeHistoricStorage(form.storageType)"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </CompatSelect>
        </CompatFormItem>

        <CompatFormItem :label="t('warehouse.material_types_list.label_4d1d935843cd')">
          <el-input-number v-model="form.shelfLifeDays" :min="0" style="width: 100%" />
        </CompatFormItem>

        <CompatFormItem :label="t('warehouse.material_types_list.label_daede9881787')">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
        </CompatFormItem>

        <!-- SP4: 税率 + 含税/未税单价 (canViewPrice 门控) -->
        <template v-if="canViewPrice">
          <CompatFormItem :label="t('warehouse.material_types_list.label_ac9d83674cd7')" required>
            <el-radio-group v-model="form.taxTreatment">
              <el-radio value="TAXABLE">{{ t('warehouse.material_types_list.text_3b3bcc26dc0d') }}</el-radio>
              <el-radio value="EXEMPT">{{ t('warehouse.material_types_list.text_77b9b4aad320') }}</el-radio>
            </el-radio-group>
          </CompatFormItem>
          <CompatFormItem v-if="form.taxTreatment === 'TAXABLE'" :label="t('warehouse.material_types_list.label_2eabd408188b')" required>
            <CompatSelect v-model="form.taxRate" :placeholder="t('warehouse.material_types_list.placeholder_dda4ec5efe8b')" style="width: 100%">
              <CompatOption :label="t('warehouse.material_types_list.label_bdcd82a48285')" value="TAX_9" />
              <CompatOption :label="t('warehouse.material_types_list.label_e2d2f1fab6c8')" value="TAX_13" />
            </CompatSelect>
          </CompatFormItem>
          <CompatFormItem v-else :label="t('warehouse.material_types_list.label_a0f44fa35a90')" required>
            <UInput v-model="form.taxExemptionReason" :placeholder="t('warehouse.material_types_list.placeholder_7448b4f64400')" maxlength="255" />
          </CompatFormItem>
          <!--
            🔴 包材是【必填】: 后端 create/update 两条路径都有
            `if (packaging) validateRequiredPricing(...)`, 缺价直接 400。
            这里原本一律标「选填；未知价格请留空」—— 界面说的和闸判的不是一回事,
            用户照界面留空就存不进去(实测 LIUSHANMEN 24/25 个包材因此改不动)。
          -->
          <CompatFormItem
            :required="isPackagingMaterial"
            :label="form.taxTreatment === 'EXEMPT' ? `免税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）` : `含税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`"
          >
            <el-input-number
              v-model="form.taxIncludedUnitPrice"
              :min="0.0001"
              :precision="4"
              :controls="false"
              :placeholder="isPackagingMaterial ? t('warehouse.material_types_list.placeholder_384b78a7d8f0') : t('warehouse.material_types_list.placeholder_5cf15a799c51')"
              style="width: 100%"
            />
            <div class="field-hint">
              {{ isPackagingMaterial
                ? t('warehouse.material_types_list.text_fa27376902fe')
                : t('warehouse.material_types_list.text_92721bde9cab') }}
            </div>
          </CompatFormItem>
          <CompatFormItem v-if="form.taxIncludedUnitPrice != null && (form.taxRate || form.taxTreatment === 'EXEMPT')" :label="`未税采购参考价（元/${displayUnit(form.unit) || '库存主单位'}）`">
            <el-input
              :model-value="preTaxUnitPrice != null ? preTaxUnitPrice.toFixed(4) : '—'"
              disabled
              style="width: 100%"
            />
            <div class="field-hint">{{ form.taxTreatment === 'EXEMPT' ? t('warehouse.material_types_list.text_54806c8b65ca') : t('warehouse.material_types_list.text_4910e25b0e72') }}</div>
          </CompatFormItem>
        </template>

        <!-- 包材专属字段只在包材建档时显示和提交。 -->
        <template v-if="isPackagingMaterial">
          <USeparator>
            <span class="divider-title">{{ t('warehouse.material_types_list.text_14d6291dd481') }}</span>
          </USeparator>
          <CompatFormItem :label="t('warehouse.material_types_list.label_2cf6fc9a9661')">
          <el-select
            v-model="form.associatedCustomerId"
            :placeholder="t('warehouse.material_types_list.placeholder_446918e5befb')"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="c in customerOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
            />
            <!-- fool-proof-design Rule 5: 拉不到客户不能只显空白"无数据", 给明确下一步 -->
            <template #empty>
              <div style="padding: 8px 12px">
                <UpstreamMissingHint
                  v-if="customerOptionsLoaded"
                  :description="t('warehouse.material_types_list.description_6d312ae85dd5')"
                  target-module="sales"
                  action-text="去添加客户"
                  contact-text="请联系销售或管理员先添加客户"
                  @action="goCreate('/sales/customers')"
                />
                <span v-else style="color: var(--cs-muted); font-size: 12px">{{ t('warehouse.material_types_list.text_4927a53bcc88') }}</span>
              </div>
            </template>
          </el-select>
          <div class="field-hint">{{ t('warehouse.material_types_list.text_8a65dd23db51') }}</div>
          </CompatFormItem>
        <!-- #759: 每成品单位用量 (仅 PACKAGING 类型显示，后端自动推 BOM standardQuantity) -->
          <CompatFormItem :label="t('warehouse.material_types_list.label_e28e0f53da52')">
          <el-input-number
            v-model="form.packQtyPerProduct"
            :min="0"
            :precision="6"
            :step="0.1"
            style="width: 100%"
            :placeholder="t('warehouse.material_types_list.placeholder_79ebad06f27b')"
          />
          <div class="field-hint">
            {{ t('warehouse.material_types_list.text_c15f5eba2224') }}
          </div>
          </CompatFormItem>
        </template>

        <!-- 分类字典只承担可选归类，不参与料号生成。 -->
        <USeparator v-if="showSegmentEditor">
          <span class="divider-title">{{ t('warehouse.material_types_list.text_a3c2c7c43337') }}</span>
        </USeparator>
        <template v-if="showSegmentEditor">
          <template v-if="segmentL1Options.length > 0 || segmentLoading">
            <CompatFormItem :label="t('warehouse.material_types_list.label_07c167eb4e7d')">
              <el-select
                v-model="segmentL1"
                :placeholder="t('warehouse.material_types_list.placeholder_d0060b5e9e75')"
                clearable
                filterable
                style="width: 100%"
                :loading="segmentLoading"
              >
                <el-option
                  v-for="opt in segmentL1Options"
                  :key="opt.id"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.id"
                />
              </el-select>
            </CompatFormItem>
            <CompatFormItem :label="t('warehouse.material_types_list.label_a84a0cd165d4')">
              <CompatSelect
                v-model="segmentL2"
                :placeholder="segmentL1 ? t('warehouse.material_types_list.placeholder_3f7885098127') : t('warehouse.material_types_list.placeholder_87eb418e0c7e')"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL1"
              >
                <CompatOption
                  v-for="opt in segmentL2Options"
                  :key="opt.id"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.id"
                />
              </CompatSelect>
            </CompatFormItem>
            <CompatFormItem :label="t('warehouse.material_types_list.label_2f1c8a0aad07')">
              <CompatSelect
                v-model="segmentL3"
                :placeholder="t('warehouse.material_types_list.placeholder_a28c9868147d')"
                clearable
                filterable
                style="width: 100%"
                :disabled="!segmentL2"
                @change="handleL3Change"
              >
                <CompatOption
                  v-if="canManageClassification"
                  :key="QUICK_CREATE_L3"
                  :label="t('warehouse.material_types_list.label_70ecfb9cf8ed')"
                  :value="QUICK_CREATE_L3"
                />
                <CompatOption
                  v-for="opt in segmentL3Options"
                  :key="opt.id"
                  :label="formatSegmentOptionLabel(opt)"
                  :value="opt.id"
                />
              </CompatSelect>
              <div class="field-hint">{{ t('warehouse.material_types_list.text_6919f3917621') }}</div>
              <div v-if="!canManageClassification" class="field-hint">{{ t('warehouse.material_types_list.text_dbe1fcdbcb23') }}</div>
              <div v-if="l3MatchHint" class="field-hint field-hint--matched">{{ l3MatchHint }}</div>
              <div v-if="taxonomyCandidates.length" class="taxonomy-candidates" :aria-label="t('warehouse.material_types_list.aria_label_c786aba92396')">
                <UButton color="neutral"
                  v-for="candidate in taxonomyCandidates"
                  :key="candidate.l3Id"
                  size="sm"
                  variant="outline"
                  @click="applyTaxonomyCandidate(candidate, true)"
                >
                  {{ candidate.l1Label }} &gt; {{ candidate.l2Label }} &gt; {{ candidate.l3Label }}
                </UButton>
              </div>
              <UButton
                v-if="l3ManuallyEdited && l3MatchHint.includes('名称已变化')"
                variant="link"
                color="primary"
                @click="rematchTaxonomy"
              >{{ t('warehouse.material_types_list.action_c8fa9bd1e628') }}</UButton>
            </CompatFormItem>
          </template>
        </template>

      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">{{ t('warehouse.material_types_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="submitting" @click="handleSave">{{ t('warehouse.material_types_list.action_a3030bf8f16d') }}</UButton>
      </template>
    </CompatDialog>

    <CompatDialog
      v-model="createL3DialogVisible"
      :title="t('warehouse.material_types_list.title_cf5430518c8d')"
      width="460px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-alert
        :title="t('warehouse.material_types_list.title_68df4a180fb3')"
        :description="t('warehouse.material_types_list.description_55e87956f7b4')"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
      />
      <CompatForm label-width="110px">
        <CompatFormItem :label="t('warehouse.material_types_list.label_ba8408a26df0')">
          <el-input :model-value="selectedL2Label" disabled />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_62feb6fd940d')" required>
          <UInput v-model="createL3Form.label" maxlength="100" :placeholder="t('warehouse.material_types_list.placeholder_86f484f8765e')" />
        </CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="createL3DialogVisible = false">{{ t('warehouse.material_types_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="createL3Submitting" @click="handleCreateL3">{{ t('warehouse.material_types_list.action_eee33291914b') }}</UButton>
      </template>
    </CompatDialog>

    <!-- SupplierMaterial 双向关系：原料侧维护入口 -->
    <CompatDialog
      v-model="suppliersDialogVisible"
      :title="`${suppliersDialogMaterialName} — 供应商关系`"
      width="980px"
      destroy-on-close
    >
      <div class="supplier-relation-toolbar">
        <span>{{ t('warehouse.material_types_list.text_0a2140646a81') }}</span>
        <el-button
          v-if="canManageSupplierRelations"
          type="primary"
          :icon="Plus"
          @click="openMaterialRelationCreate"
        >{{ t('warehouse.material_types_list.action_e5e52edd6e06') }}</el-button>
      </div>
      <CompatTable
        v-loading="suppliersLoading"
        :data="suppliersForMaterial"
        :empty-text="t('warehouse.material_types_list.empty_text_37527c505ba8')"
        stripe
        size="small"
      >
        <CompatTableColumn prop="supplierName" :label="t('warehouse.material_types_list.label_033ed5945254')" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.supplierName || row.supplierId }}</template>
        </CompatTableColumn>
        <CompatTableColumn prop="supplierMaterialCode" :label="t('warehouse.material_types_list.label_a742cba4444b')" width="130">
          <template #default="{ row }">{{ row.supplierMaterialCode || '-' }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_7023dd8f558a')" width="100">
          <template #default="{ row }">{{ displayUnit(row.purchaseUnit || row.baseUnit) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_21d8c93ca5cf')" width="155" align="right">
          <template #default="{ row }">
            {{ row.defaultPurchasePrice == null ? t('warehouse.material_types_list.text_80a57e03f071') : `${row.currency || 'CNY'} ${Number(row.defaultPurchasePrice).toFixed(2)}/${displayUnit(row.purchaseUnit || row.baseUnit)}` }}
          </template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_659bd4ea4ba4')" width="120" align="right">
          <template #default="{ row }">{{ row.minOrderQuantity ?? '-' }} {{ displayUnit(row.purchaseUnit || row.baseUnit) }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_7d0e25d161e5')" width="90">
          <template #default="{ row }">{{ row.leadTimeDays == null ? '-' : `${row.leadTimeDays}天` }}</template>
        </CompatTableColumn>
        <CompatTableColumn :label="t('warehouse.material_types_list.label_6320b4a8722a')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active === false ? 'info' : 'success'" size="small">
              {{ row.active === false ? t('warehouse.material_types_list.text_a8c3698b5b8c') : row.preferred ? t('warehouse.material_types_list.text_2b11c99bfed1') : t('warehouse.material_types_list.text_f4f0ead1116b') }}
            </el-tag>
          </template>
        </CompatTableColumn>
        <CompatTableColumn v-if="canManageSupplierRelations" :label="t('warehouse.material_types_list.label_ed31fbb483ee')" width="125" fixed="right">
          <template #default="{ row }">
            <UButton color="primary" variant="link" @click="openMaterialRelationEdit(row)">{{ t('warehouse.material_types_list.action_051836569928') }}</UButton>
            <UButton v-if="row.active !== false" color="warning" variant="link" @click="deactivateMaterialRelation(row)">{{ t('warehouse.material_types_list.action_4e6fd0e28c55') }}</UButton>
          </template>
        </CompatTableColumn>
      </CompatTable>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="suppliersDialogVisible = false">{{ t('warehouse.material_types_list.action_3fd47edce45b') }}</UButton>
      </template>
    </CompatDialog>

    <CompatDialog
      v-model="relationDialogVisible"
      :title="relationEditing ? t('warehouse.material_types_list.title_4c69f78c9e6f') : t('warehouse.material_types_list.title_e5e52edd6e06')"
      width="620px"
      append-to-body
      :close-on-click-modal="false"
    >
      <CompatForm label-width="145px">
        <CompatFormItem :label="t('warehouse.material_types_list.label_033ed5945254')" required>
          <CompatSelect v-model="relationForm.supplierId" filterable :disabled="Boolean(relationEditing)" style="width: 100%" :placeholder="t('warehouse.material_types_list.placeholder_1d80c032fb3d')">
            <CompatOption
              v-if="relationEditing"
              :label="relationEditing.supplierName || relationEditing.supplierId"
              :value="relationEditing.supplierId"
            />
            <CompatOption
              v-for="supplier in availableSupplierOptions"
              :key="supplier.id"
              :label="`${supplier.name}${supplier.supplierCode || supplier.code ? ` (${supplier.supplierCode || supplier.code})` : ''}`"
              :value="supplier.id"
            />
          </CompatSelect>
          <div class="field-hint">{{ t('warehouse.material_types_list.text_f412df366173') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_6446454e8e36')">
          <el-input :model-value="`${suppliersDialogMaterial?.name || '-'} (${suppliersDialogMaterial?.code || suppliersDialogMaterial?.id || '-'})`" disabled />
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_a742cba4444b')"><UInput v-model="relationForm.supplierMaterialCode" maxlength="100" /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_7023dd8f558a')" required>
          <UnitSelect
            v-model="relationForm.purchaseUnit"
            :factory-id="factoryId"
            :options="compatibleMaterialPurchaseUnits"
            usage-scope="PURCHASE_QUANTITY"
            :placeholder="t('warehouse.material_types_list.placeholder_b3ca76761983')"
          />
          <el-alert
            v-if="materialRelationUnitReason"
            type="error"
            :closable="false"
            show-icon
            :title="materialRelationUnitReason"
          />
          <div class="field-hint">{{ t('warehouse.material_types_list.text_273c68fd6d22') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="`默认采购价（元/${displayUnit(relationForm.purchaseUnit) || '采购单位'}）`">
          <el-input-number v-model="relationForm.defaultPurchasePrice" :min="0" :precision="4" controls-position="right" style="width: 100%" />
          <div class="field-hint">{{ t('warehouse.material_types_list.text_fb16b58124c2') }}</div>
        </CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_9677f386bdcd')" required><UInput v-model="relationForm.currency" maxlength="3" /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_659bd4ea4ba4')"><el-input-number v-model="relationForm.minOrderQuantity" :min="0.0001" :precision="4" style="width: 100%" /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_f0ea6923da21')"><el-input-number v-model="relationForm.leadTimeDays" :min="0" :precision="0" style="width: 100%" /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_8a394d2ba9cf')"><USwitch v-model="relationForm.preferred" /></CompatFormItem>
        <CompatFormItem :label="t('warehouse.material_types_list.label_262c018aca17')"><USwitch v-model="relationForm.active" /></CompatFormItem>
      </CompatForm>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="relationDialogVisible = false">{{ t('warehouse.material_types_list.action_2cd0f3be8738') }}</UButton>
        <UButton color="primary" :loading="relationSaving" @click="saveMaterialRelation">{{ t('warehouse.material_types_list.action_a3030bf8f16d') }}</UButton>
      </template>
    </CompatDialog>
  </div>
</template>

<style scoped>
.page-wrapper { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.header-left { display: flex; align-items: baseline; gap: 12px; }
.page-title { font-size: 18px; font-weight: 600; }
.data-count { font-size: 13px; color: var(--cs-muted); }
.write-role-hint { font-size: 13px; color: var(--cs-muted); margin-right: 8px; }
.alias-line { font-size: 12px; color: var(--cs-muted); }
.search-bar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.supplier-relation-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; color: var(--cs-muted); font-size: 13px; }
.divider-title { font-size: 14px; color: var(--cs-muted); font-weight: 500; }

/* T159-A: Code preview row */
.code-preview-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}
.code-preview-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}
.code-preview-label {
  min-width: 132px;
  color: var(--cs-muted);
  font-size: 12px;
}
.code-preview-tag {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
}
.code-preview-tag.code-preview-loading {
  opacity: 0.6;
}
.code-preview-hint {
  font-size: 12px;
  color: var(--cs-muted);
}

.material-code-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.material-code-value {
  color: var(--cs-text);
  font-weight: 600;
  letter-spacing: 0.4px;
  overflow-wrap: anywhere;
}

/* T159-A: Field hints */
.field-hint {
  font-size: 12px;
  color: var(--cs-muted);
  margin-top: 4px;
  line-height: 1.4;
}
.field-hint--manual {
  color: var(--cs-warning);
}

.field-hint--error {
  color: var(--cs-warning);
}

.field-hint--matched {
  color: var(--cs-success);
}

.field-hint--error {
  color: var(--cs-danger);
}

/* 与 SKU 新建保持相同的动态包装规则编辑体验 */
.material-packaging-spec-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.packaging-rule-empty {
  padding: 10px 12px;
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--cs-success);
  background: var(--cs-success-soft);
  border: 1px solid #e1f3d8;
  border-radius: 4px;
}

.packaging-rule-note {
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--cs-muted);
  background: var(--cs-surface-subtle);
  border: 1px solid var(--cs-border);
  border-radius: 8px;
}
.material-packaging-spec-item {
  padding: 10px 12px;
  background: #fafcff;
  border: 1px solid var(--cs-border);
  border-radius: 8px;
}
.spec-conversion-row {
  display: grid;
  grid-template-columns: auto auto minmax(150px, 1fr) auto minmax(110px, 0.75fr) minmax(92px, 0.55fr) auto;
  align-items: center;
  gap: 8px;
}
.packaging-rule-index {
  min-width: 42px;
  color: var(--cs-muted);
  font-size: 12px;
}
.spec-unit-select,
.spec-factor-input,
.spec-base-unit {
  width: 100%;
}
.conversion-label {
  font-size: 15px;
  font-weight: 600;
  color: var(--cs-text);
  min-width: 10px;
}
.conversion-equals {
  font-size: 16px;
  color: var(--cs-muted);
  padding: 0 2px;
}
.spec-echo,
.spec-same-warn,
.spec-rule-error {
  margin-top: 6px;
  padding-left: 54px;
  font-size: 12px;
}
.spec-echo {
  color: var(--cs-success);
}
.spec-same-warn {
  color: var(--cs-warning);
}
.spec-rule-error {
  color: var(--el-color-danger);
}
.add-packaging-rule-button {
  width: 100%;
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}
@media (max-width: 760px) {
  .spec-conversion-row {
    grid-template-columns: auto auto minmax(0, 1fr) auto;
  }
  .spec-factor-input,
  .spec-base-unit {
    grid-column: span 2;
  }
  .spec-echo,
  .spec-same-warn,
  .spec-rule-error {
    padding-left: 0;
  }
}
</style>
