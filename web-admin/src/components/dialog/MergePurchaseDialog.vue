<script setup lang="ts">
/**
 * MergePurchaseDialog.vue — 多销售订单合并生成一张采购单的弹窗.
 *
 * 转录行3650: 多个销售订单用"加号"逐个追加合并成一张采购单 —
 * "我可能要把好几个订单的料合在一起一次买"。
 *
 * 🔴 这是「从销售订单生成采购单」的**唯一**入口 (2026-08-19 起)。
 * 2026-07-20 `bb17530017` 从销售订单列表删掉「开始采购」按钮后, 单张 SO 也走这里
 * (sales/orders/list.vue:「单张时等价于开始采购但走合并弹窗」); 并列的
 * StartPurchaseDialog 从那天起无人挂载, 却仍持续收到修复 (7-17/7-18 的单位契约、
 * 7-26 包装规格、7-31 供应商简称) —— 全部落在用户看不见的地方, 已于 2026-08-19 删除。
 * ⛔ 不要再建第二个入口。
 *
 * 本弹窗的职责:
 * - 跨多张 SO 按物料聚合需求 (SO1 需牛腱50 + SO2 需牛腱30 → 合并 80)，库存统一扣一次。
 * - 顶部显示参与合并的 SO 号列表 + 客户列表 (可能多客户)。
 * - 每行标注「合并自哪几张 SO」(sourceSalesOrderNumbers)。
 * - 逐 SO 摘要诚实暴露「某张 SO 无 BOM 未贡献料」，不静默。
 *
 * 防呆 Rule 1: 每行展示「需要量/现有库存/净需求」，采购数量默认 = 净需求，不让用户手填。
 * 防呆 Rule 2: 顶部带 SO 列表 + 客户上下文。
 * 防呆 Rule 5: 全部无 BOM 时给明确空状态 + 引导，不卡死。
 *
 * 后端: POST /api/mobile/{factoryId}/purchase/orders/suggestions/from-so-multi
 *       body: { salesOrderIds: [...] }
 * 确认: POST /api/mobile/{factoryId}/purchase/orders (CreatePurchaseOrderRequest)
 */
import { ref, watch, computed } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/request';
import { convertUnit } from '@/api/unitContract';
import {
  canonicalUnitCode,
  canonicalUnitCodeKeepingCount,
  displayUnit,
  formatPriceUnit,
  mergeCanonicalUnitOptions,
  pricingAmountPreview,
  purchaseOrderPricingPayload,
  resolvePurchaseSuggestionUnits,
  resolveUnitSwitch,
} from '@/utils/unitPricing';

// ── types ──────────────────────────────────────────────────────────────────

interface MultiSuggestionItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  sourceProductName: string;
  sourceSalesOrderNumbers: string[];
  requiredQuantity: number;
  unit: string;
  quantityUnit?: string | null;
  currentStock: number;
  netRequired: number;
  stockSufficient: boolean;
  referenceUnitPrice: number | null;
  /** 后端权威的计价单位分母。⛔ 丢掉它 = 把 20 元/盒 当成 20 元/g。 */
  referencePriceUnit?: string | null;
  /** 版本兼容别名 (旧响应)。 */
  priceUnit?: string | null;
  lineAmount?: number | null;
  convertedPricingQuantity?: number | null;
}

interface SoSummary {
  salesOrderId: string;
  salesOrderNumber: string;
  customerName: string;
  hasBom: boolean;
}

interface MultiSuggestionResponse {
  salesOrderIds: string[];
  salesOrderNumbers: string[];
  customerNames: string[];
  hasBom: boolean;
  items: MultiSuggestionItem[];
  soSummaries: SoSummary[];
}

interface SupplierOption {
  id: string;
  name: string;
  displayName?: string | null;
  shortName?: string | null;
  supplierCode?: string | null;
}

interface MaterialPackagingHierarchy {
  level1Unit?: string | null;
  level1PerLevel2?: number | null;
  level2Unit?: string | null;
  level2PerLevel3?: number | null;
  level3Unit?: string | null;
  packagingSpecs?: Array<{
    id: string;
    name: string;
    packageUnit: string;
    baseUnit: string;
    conversionFactor: number;
    defaultSpec?: boolean;
    active?: boolean;
  }>;
}

interface EditableItem {
  materialTypeId: string;
  materialName: string;
  materialCategory: string;
  quantity: number;
  unit: string;
  quantityUnit: string;
  unitPrice: number | null;
  priceUnit: string;
  lineAmount: number | null;
  convertedPricingQuantity: number | null;
  specification: string;
  boxQuantity: number | null;
  remark: string;
  _requiredQuantity: number;
  _currentStock: number;
  _netRequired: number;
  _stockSufficient: boolean;
  _sourceSalesOrderNumbers: string[];
  /**
   * 🔴 建议三列 (需要量/现有库存/净需求) 的**冻结**单位。
   *
   * ⛔ 不许拿 `quantityUnit` 给那三列当标签: 换采购单位只换 `quantity`,
   * 那三个数是后端按 BOM 口径给的、**不会跟着变**。用可变单位当标签,
   * 「12.50 kg」会被重新标成「12.50 箱」—— 数字没动、单位变了, 而且不报错。
   * (已删除的 StartPurchaseDialog 就是这么坏的; 那正是删它之前要先修的地方。)
   */
  _suggestionUnit: string;
  _activePriceUnit: string;
}

/** 入参: 用户勾选/追加的 SO 列表 (id + number + customer). */
interface SelectedSo {
  id: string;
  number: string;
  customer?: string;
}

// ── props / emits ──────────────────────────────────────────────────────────

const props = defineProps<{
  modelValue: boolean;
  factoryId: string;
  /** 勾选/追加的 SO 列表 (≥1) */
  salesOrders: SelectedSo[];
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  created: [poId: string];
}>();

// ── state ──────────────────────────────────────────────────────────────────

const loading = ref(false);
const submitting = ref(false);
const suggestion = ref<MultiSuggestionResponse | null>(null);
const editableItems = ref<EditableItem[]>([]);
const supplierId = ref('');
const suppliers = ref<SupplierOption[]>([]);
const suppliersLoading = ref(false);
const packagingByMaterial = ref<Record<string, MaterialPackagingHierarchy>>({});
const factoryToday = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
const orderDateStr = ref(factoryToday());
const expectedDeliveryDate = ref('');
const remark = ref('');

/**
 * 下拉标签: 有简称就「简称（全称）」, 否则退回「全称 (编码)」。
 * 客户 (六膳门 / 刘山门) 提简称是因为全称在下拉里太长认不出来。
 */
function supplierOptionLabel(supplier: SupplierOption): string {
  const short = (supplier.shortName ?? '').trim();
  if (short) return `${short}（${supplier.name}）`;
  return supplier.supplierCode ? `${supplier.name} (${supplier.supplierCode})` : supplier.name;
}

async function loadSupplierOptions() {
  suppliersLoading.value = true;
  try {
    const res = await get<{ content?: SupplierOption[] }>(`/${props.factoryId}/suppliers`, {
      params: { page: 1, size: 100 },
    });
    suppliers.value = res.success && Array.isArray(res.data?.content) ? res.data.content : [];
  } catch {
    suppliers.value = [];
    ElMessage.warning('供应商列表加载失败，可稍后在采购草稿中补填');
  } finally {
    suppliersLoading.value = false;
  }
}

async function ensurePackagingLoaded(materialTypeId: string) {
  if (!materialTypeId || packagingByMaterial.value[materialTypeId]) return;
  try {
    const res = await get<MaterialPackagingHierarchy | null>(
      `/${props.factoryId}/material-packaging/by-material/${materialTypeId}`,
    );
    packagingByMaterial.value[materialTypeId] = res.data || {};
  } catch {
    packagingByMaterial.value[materialTypeId] = {};
  }
}

/**
 * 可选数量单位 —— 只负责**列出候选**, 不负责换算。
 * 换算一律由后端 `POST /{factoryId}/units/convert` 回答 (见 onQuantityUnitChange)。
 */
function quantityUnitOptions(item: EditableItem) {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  return mergeCanonicalUnitOptions(
    packaging?.level1Unit,
    packaging?.level2Unit,
    packaging?.level3Unit,
    ...(packaging?.packagingSpecs || []).map((spec) => spec.packageUnit),
    item.quantityUnit,
    item.unit,
  ).map((unit) => ({ value: unit, label: displayUnit(unit) }));
}

function priceUnitOptions(item: EditableItem) {
  return mergeCanonicalUnitOptions(
    quantityUnitOptions(item).map((option) => option.value),
    item.priceUnit,
  ).map((unit) => ({ value: unit, label: formatPriceUnit(unit) }));
}

function packagingSummary(item: EditableItem): string {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  const dynamic = (packaging?.packagingSpecs || [])
    .filter((spec) => spec.active !== false)
    .map((spec) => `1 ${displayUnit(spec.packageUnit)} = ${spec.conversionFactor} ${displayUnit(spec.baseUnit)}`);
  if (dynamic.length > 0) return dynamic.join('；');
  if (!packaging?.level1Unit || !packaging.level2Unit || !packaging.level1PerLevel2) return '';
  const level2 = `1 ${displayUnit(packaging.level2Unit)} = ${packaging.level1PerLevel2} ${displayUnit(packaging.level1Unit)}`;
  if (!packaging.level3Unit || !packaging.level2PerLevel3) return level2;
  return `${level2}；1 ${displayUnit(packaging.level3Unit)} = ${packaging.level2PerLevel3} ${displayUnit(packaging.level2Unit)}`;
}

function recalculateBoxQuantity(item: EditableItem) {
  const packaging = packagingByMaterial.value[item.materialTypeId];
  const quantity = Number(item.quantity);
  const quantityUnit = canonicalUnitCode(item.quantityUnit || item.unit);
  const level1Unit = canonicalUnitCode(packaging?.level1Unit);
  const level2Unit = canonicalUnitCode(packaging?.level2Unit);
  const level1PerLevel2 = Number(packaging?.level1PerLevel2);
  if (!Number.isFinite(quantity) || quantity <= 0 || !level2Unit) {
    item.boxQuantity = null;
  } else if (quantityUnit === level2Unit) {
    item.boxQuantity = quantity;
  } else if (quantityUnit === level1Unit && Number.isFinite(level1PerLevel2) && level1PerLevel2 > 0) {
    item.boxQuantity = Math.round((quantity / level1PerLevel2) * 10000) / 10000;
  } else {
    item.boxQuantity = null;
  }
}

function clearSuggestionAmount(item: EditableItem) {
  // 后端给的预览值只描述**原始建议数量**, 用户一改就作废。
  item.lineAmount = null;
  item.convertedPricingQuantity = null;
}

function amountPreview(item: EditableItem) {
  return pricingAmountPreview(item);
}

function onQuantityChange(item: EditableItem) {
  clearSuggestionAmount(item);
  recalculateBoxQuantity(item);
}

function onPriceUnitChange(item: EditableItem) {
  // 硬约束 8 (2026-09-02, 缺陷 33 的同根缺陷): 这里把 canonicalUnitCode() 的结果直接
  // 写回 item.priceUnit —— 与 list.vue #474 修的 applyRelationPrice 是同一形状
  // (写入单位字段前先归一, 提交时的守卫已经够不着)。改用 canonicalUnitCodeKeepingCount
  // 双重防线: 根治已在 unitPricing.ts 的 UNIT_ALIASES 表本身 (canonicalUnitCode 现在
  // 也不折叠 个/只/件 了), 这里换函数是防线加固, 不是必需, 但与 list.vue 保持同一模式。
  const nextUnit = canonicalUnitCodeKeepingCount(item.priceUnit);
  if (!nextUnit) {
    item.priceUnit = item._activePriceUnit;
    return;
  }
  if (nextUnit === item._activePriceUnit) return;

  // 数字和它的单位不可分割。把「20」从 元/盒 原样搬到 元/g 会让金额差两个数量级,
  // 所以换了计价单位就必须重新输入该单位对应的单价。
  item.priceUnit = nextUnit;
  item._activePriceUnit = nextUnit;
  item.unitPrice = null;
  clearSuggestionAmount(item);
  ElMessage.info('计价单位已变化，请输入该单位对应的单价');
}

/**
 * 换采购数量单位 —— **换算只问后端**。
 *
 * 🔴 仓里已经有两份换算实现了:
 *   - 后端 `UnitContractServiceImpl.convert` (会做 g↔kg 这类科学换算 + 物料包装规格)
 *   - 前端 `StartPurchaseDialog.quantityUnitToLevel1Factor` (已随组件删除; 只认包装层级,
 *     g→kg 返回 null → 弹「未配置包装换算」把用户挡住)
 * 后者正是测试人员 2026-07-20 报的「最基础的 g 与 kg 都无法自动换算」。
 * ⛔ 这里不许再写第三份: 一律调 `POST /{factoryId}/units/convert`,
 *    换不了就 fail closed 并把**后端的原话**给用户, 不猜因子。
 */
async function onQuantityUnitChange(item: EditableItem) {
  const previousUnit = canonicalUnitCode(item.unit);
  const outcome = await resolveUnitSwitch(
    { quantity: item.quantity, fromUnit: previousUnit, toUnit: item.quantityUnit },
    async (quantity, fromUnit, toUnit) => {
      const res = await convertUnit(props.factoryId, {
        quantity,
        // 物料也走这个参数: 后端 convertViaMaterialPackagingHierarchy 用
        // context.productTypeId() 查 material_packaging_specs (与 PurchaseServiceImpl 同调用法)。
        productTypeId: item.materialTypeId,
        fromUnit,
        toUnit,
        scene: 'PROCUREMENT',
      });
      return res.success ? res.data : null;
    },
  );

  if (!outcome.ok) {
    ElMessage.warning(outcome.message);
    item.quantityUnit = outcome.unit;   // fail closed: 回滚, 不留「单位换了数字没换」的行
    return;
  }

  item.quantity = outcome.quantity;
  item.quantityUnit = outcome.unit;
  item.unit = outcome.unit;
  clearSuggestionAmount(item);
  recalculateBoxQuantity(item);
}

// ── computed ───────────────────────────────────────────────────────────────

const dialogTitle = computed(() => `合并采购 — 共 ${props.salesOrders.length} 张销售订单`);

const hasNetRequired = computed(() =>
  editableItems.value.some((it) => !it._stockSufficient)
);

/** 逐 SO 摘要里无 BOM 的那些 (诚实提示). */
const noBomSummaries = computed(
  () => suggestion.value?.soSummaries.filter((s) => !s.hasBom) ?? []
);

const categoryLabel = (cat: string) => {
  const map: Record<string, string> = {
    RAW: '原料',
    AUXILIARY: '辅料',
    PACKAGING: '包材',
  };
  return map[cat] ?? cat;
};

// ── load merged suggestion ───────────────────────────────────────────────────

async function loadSuggestion() {
  if (!props.factoryId || props.salesOrders.length === 0) return;
  loading.value = true;
  suggestion.value = null;
  editableItems.value = [];
  try {
    const res = await post<MultiSuggestionResponse>(
      `/${props.factoryId}/purchase/orders/suggestions/from-so-multi`,
      { salesOrderIds: props.salesOrders.map((s) => s.id) }
    );
    if (res.success && res.data) {
      suggestion.value = res.data;
      editableItems.value = res.data.items.map((it) => {
        // 与 StartPurchaseDialog 走同一份权威解析 —— 参考单价的分母是后端的
        // referencePriceUnit, 不是数量单位。两者可以不同 (BOM 行按「盒」计价、
        // 按「g」计量), 自己再写一份「就按数量单位算」= 把 20 元/盒 记成 20 元/g。
        const units = resolvePurchaseSuggestionUnits(it);
        return {
          materialTypeId: it.materialTypeId,
          materialName: it.materialName,
          materialCategory: it.materialCategory,
          quantity: it.netRequired > 0 ? it.netRequired : it.requiredQuantity,
          unit: units.quantityUnit,
          quantityUnit: units.quantityUnit,
          unitPrice: it.referenceUnitPrice ?? null,
          priceUnit: units.priceUnit,
          lineAmount: it.lineAmount ?? null,
          convertedPricingQuantity: it.convertedPricingQuantity ?? null,
          specification: '',
          // ⚠️ 显式类型不能省: tsconfig 是 noImplicitAny:true 且 strictNullChecks 关着,
          //    这个对象字面量没有上下文类型, 裸 `null` 会被判成隐式 any (TS7018)。
          boxQuantity: null as number | null,
          remark: '',
          _requiredQuantity: it.requiredQuantity,
          _currentStock: it.currentStock,
          _netRequired: it.netRequired,
          _stockSufficient: it.stockSufficient,
          _sourceSalesOrderNumbers: it.sourceSalesOrderNumbers ?? [],
          _suggestionUnit: units.quantityUnit,
          _activePriceUnit: units.priceUnit,
        };
      });
      await Promise.all(editableItems.value.map((item) => ensurePackagingLoaded(item.materialTypeId)));
      editableItems.value.forEach(recalculateBoxQuantity);
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      supplierId.value = '';
      orderDateStr.value = factoryToday();
      expectedDeliveryDate.value = '';
      remark.value = '';
      void loadSupplierOptions();
      void loadSuggestion();
    }
  },
  { immediate: false }
);

// ── create PO ──────────────────────────────────────────────────────────────

async function handleConfirm() {
  if (editableItems.value.length === 0) {
    await ElMessageBox.alert(
      '没有可采购的原料行（所选订单均未配置 BOM 或库存充足），无需创建采购单。',
      '无需采购',
      { type: 'info', confirmButtonText: '确定' }
    );
    return;
  }
  // 防呆 Rule 1: 提交前先把边界说清楚, 不要等后端 4xx 回来才告诉用户。
  const invalidQuantityIndex = editableItems.value.findIndex((item) => !(Number(item.quantity) > 0));
  if (invalidQuantityIndex >= 0) {
    ElMessage.warning(`第 ${invalidQuantityIndex + 1} 行采购数量必须大于 0`);
    return;
  }
  const missingQuantityUnitIndex = editableItems.value.findIndex(
    (item) => !canonicalUnitCode(item.quantityUnit),
  );
  if (missingQuantityUnitIndex >= 0) {
    ElMessage.warning(`第 ${missingQuantityUnitIndex + 1} 行请选择数量单位`);
    return;
  }
  const missingPriceUnitIndex = editableItems.value.findIndex(
    (item) => Number(item.unitPrice) > 0 && !canonicalUnitCode(item.priceUnit),
  );
  if (missingPriceUnitIndex >= 0) {
    ElMessage.warning(`第 ${missingPriceUnitIndex + 1} 行有参考单价，请选择计价单位`);
    return;
  }

  submitting.value = true;
  try {
    const soNumbers = suggestion.value?.salesOrderNumbers ?? props.salesOrders.map((s) => s.number);
    // 关联首张 SO (后端 PO.salesOrderId 单值); 全部 SO 号写进备注供溯源.
    const primarySoId = suggestion.value?.salesOrderIds[0] ?? props.salesOrders[0]?.id ?? null;
    const payload = {
      supplierId: supplierId.value || null,
      purchaseType: 'DIRECT',
      orderDate: orderDateStr.value,
      expectedDeliveryDate: expectedDeliveryDate.value || null,
      salesOrderId: primarySoId,
      remark:
        remark.value || `合并销售订单 ${soNumbers.join(' / ')} 自动生成`,
      // ⛔ 不要退回手写 { unit, unitPrice }: 不传 priceUnit 时后端
      // applyPurchasePriceContract 会把计价单位**兜底成数量单位**
      // (firstNonBlank(priceUnit, canonicalQuantityUnit)), 于是 20 元/盒 被静默
      // 记成 20 元/g, 换算因子还是 1 —— 金额直接差两个数量级, 且不报错。
      items: editableItems.value.map((it) => purchaseOrderPricingPayload({
        materialTypeId: it.materialTypeId,
        materialName: it.materialName,
        quantity: it.quantity,
        unit: it.unit,
        quantityUnit: it.quantityUnit,
        unitPrice: it.unitPrice ?? null,
        priceUnit: it.priceUnit,
        specification: it.specification || null,
        boxQuantity: it.boxQuantity,
        remark: it.remark || '',
        // 收货侧要靠它快照换算因子 (resolveMaterialPackagingSelection);
        // 不传就只能回落到默认规格, 分次收货的件数会对不上。
        materialPackagingSpecId: packagingByMaterial.value[it.materialTypeId]?.packagingSpecs
          ?.find((spec) => canonicalUnitCode(spec.packageUnit) === canonicalUnitCode(it.quantityUnit))?.id,
      })),
    };

    const res = await post<{ id: string }>(
      `/${props.factoryId}/purchase/orders`,
      payload
    );
    if (res.success && res.data?.id) {
      ElMessage.success('合并采购单创建成功');
      emit('update:modelValue', false);
      emit('created', res.data.id);
    }
  } finally {
    submitting.value = false;
  }
}

function handleClose() {
  emit('update:modelValue', false);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="96%"
    top="4vh"
    destroy-on-close
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div v-if="loading" class="mp-loading">
      <el-skeleton :rows="5" animated />
    </div>

    <!-- 全部无 BOM (防呆 Rule 5) -->
    <el-empty
      v-else-if="suggestion && !suggestion.hasBom"
      description="所选销售订单关联的产品均未配置 BOM，无法自动展开原料需求。请先在「工序配置」中配置 BOM，再使用合并采购功能。"
    >
      <template #extra>
        <el-button type="primary" @click="handleClose">知道了</el-button>
      </template>
    </el-empty>

    <template v-else-if="suggestion">
      <!-- Context header (防呆 Rule 2) -->
      <div class="mp-context-header">
        <el-alert
          :title="`合并 ${suggestion.salesOrderNumbers.length} 张订单: ${suggestion.salesOrderNumbers.join(' / ')}`"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          :title="`客户: ${suggestion.customerNames.length ? suggestion.customerNames.join('、') : '—'}  |  合并后共 ${suggestion.items.length} 种原辅包材`"
          type="info"
          :closable="false"
        />
        <!-- 诚实暴露: 某些 SO 无 BOM 未贡献料 -->
        <el-alert
          v-if="noBomSummaries.length"
          :title="`注意: ${noBomSummaries.map((s) => s.salesOrderNumber).join('、')} 未配置 BOM，未贡献原料需求。`"
          type="warning"
          :closable="false"
        />
        <el-alert
          v-if="hasNetRequired"
          title="橙色行: 库存不足需采购（净需求 > 0）；绿色行: 库存充足可跳过。库存已跨订单统一扣减一次。可手动调整数量或删除行。"
          type="warning"
          :closable="false"
          class="mp-hint"
        />
      </div>

      <!-- Merged editable items table -->
      <el-table
        :data="editableItems"
        stripe
        size="small"
        style="width: 100%; margin-top: 12px"
        :row-class-name="(row: { row: EditableItem }) => row.row._stockSufficient ? 'mp-row-sufficient' : 'mp-row-needed'"
      >
        <el-table-column label="分类" width="66">
          <template #default="{ row }">
            <el-tag
              :type="row.materialCategory === 'RAW' ? 'danger' : row.materialCategory === 'AUXILIARY' ? 'warning' : 'info'"
              size="small"
            >{{ categoryLabel(row.materialCategory) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="materialName" label="原料名称" min-width="130" />
        <el-table-column label="合并自" min-width="150">
          <template #default="{ row }">
            <el-tag
              v-for="soNo in row._sourceSalesOrderNumbers"
              :key="soNo"
              size="small"
              type="info"
              effect="plain"
              style="margin: 1px 2px"
            >{{ soNo }}</el-tag>
          </template>
        </el-table-column>
        <!-- 这三列的数字是后端按 BOM 口径给的, 换采购单位不会改动它们
             ⇒ 标签必须用**冻结**的 _suggestionUnit, 不能用可变的 quantityUnit。 -->
        <el-table-column label="需要量" width="96" align="right">
          <template #default="{ row }">
            <span class="mp-qty">{{ row._requiredQuantity?.toFixed(2) }} {{ displayUnit(row._suggestionUnit) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="现有库存" width="96" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'mp-stock-ok' : 'mp-stock-low'">
              {{ row._currentStock?.toFixed(2) }} {{ displayUnit(row._suggestionUnit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="净需求" width="96" align="right">
          <template #default="{ row }">
            <span :class="row._stockSufficient ? 'mp-stock-ok' : 'mp-net-needed'">
              {{ row._netRequired?.toFixed(2) }} {{ displayUnit(row._suggestionUnit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="采购数量 / 单位" width="215">
          <template #default="{ row }">
            <div class="mp-inline-editor">
              <el-input-number
                v-model="row.quantity"
                :min="0"
                :precision="2"
                size="small"
                style="width: 120px"
                controls-position="right"
                @change="onQuantityChange(row)"
              />
              <el-select
                v-model="row.quantityUnit"
                filterable
                size="small"
                style="width: 82px"
                placeholder="单位"
                @change="onQuantityUnitChange(row)"
              >
                <el-option
                  v-for="option in quantityUnitOptions(row)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="包装规格" width="180">
          <template #default="{ row }">
            <el-input v-model="row.specification" size="small" placeholder="选填，如 10kg/箱" clearable />
            <div v-if="packagingSummary(row)" class="mp-packaging-hint">{{ packagingSummary(row) }}</div>
            <div v-if="row.boxQuantity != null" class="mp-packaging-hint">折算箱数：{{ row.boxQuantity }}</div>
          </template>
        </el-table-column>
        <el-table-column label="参考单价 / 计价单位" width="200">
          <template #default="{ row }">
            <div class="mp-inline-editor">
              <el-input-number
                v-model="row.unitPrice"
                :min="0"
                :precision="4"
                size="small"
                style="width: 108px"
                controls-position="right"
                :placeholder="row.unitPrice == null ? '未知' : ''"
              />
              <el-select
                v-model="row.priceUnit"
                size="small"
                style="width: 82px"
                placeholder="计价单位"
                @change="onPriceUnitChange(row)"
              >
                <el-option
                  v-for="option in priceUnitOptions(row)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </div>
            <!-- 计价单位可以不等于数量单位 (按盒计价、按 g 计量)。不写出来,
                 采购员就会把「20」读成 20 元/g 然后照着下单。 -->
            <div class="mp-price-label">{{ formatPriceUnit(row.priceUnit) }}</div>
            <div class="mp-packaging-hint">切换计价单位后需重新输入对应单价</div>
          </template>
        </el-table-column>
        <el-table-column label="金额预览" width="140" align="right">
          <template #default="{ row }">
            <span v-if="amountPreview(row).amount != null">{{ amountPreview(row).amount?.toFixed(2) }} 元</span>
            <span v-else class="mp-pending-amount">{{ amountPreview(row).message }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="62" align="center">
          <template #default="{ $index }">
            <el-button
              type="danger"
              link
              size="small"
              @click="editableItems.splice($index, 1)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mp-meta-row" style="margin-top: 16px; display: flex; gap: 16px; align-items: flex-start">
        <el-form-item label="供应商（选填）" style="flex: 1.4">
          <el-select
            v-model="supplierId"
            :loading="suppliersLoading"
            placeholder="可稍后在采购草稿中补填"
            filterable
            clearable
            size="small"
            style="width: 100%"
          >
            <el-option
              v-for="supplier in suppliers"
              :key="supplier.id"
              :label="supplierOptionLabel(supplier)"
              :value="supplier.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="下单日期" style="flex: 1">
          <el-input v-model="orderDateStr" type="date" size="small" />
        </el-form-item>
        <el-form-item label="期望交货" style="flex: 1">
          <el-input v-model="expectedDeliveryDate" type="date" size="small" />
        </el-form-item>
        <el-form-item label="备注" style="flex: 2">
          <el-input
            v-model="remark"
            type="textarea"
            :rows="2"
            size="small"
            :placeholder="`合并销售订单 ${suggestion.salesOrderNumbers.join(' / ')} 自动生成`"
          />
        </el-form-item>
      </div>
      <el-alert
        type="info"
        :closable="false"
        style="margin-top: 8px"
        title="将先创建采购草稿；供应商可稍后补填，但提交审批前必须选择。全部销售订单号记入备注供溯源。"
      />
    </template>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button
        v-if="suggestion && suggestion.hasBom"
        type="primary"
        :loading="submitting"
        :disabled="editableItems.length === 0"
        @click="handleConfirm"
      >
        确认创建合并采购单 ({{ editableItems.length }} 行)
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mp-loading {
  padding: 20px 0;
}
.mp-context-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mp-hint {
  margin-top: 4px;
}
.mp-qty {
  color: #606266;
}
.mp-stock-ok {
  color: #67c23a;
  font-weight: 500;
}
.mp-stock-low {
  color: #e6a23c;
}
.mp-net-needed {
  color: #f56c6c;
  font-weight: 600;
}
.mp-inline-editor {
  display: flex;
  gap: 6px;
  align-items: center;
}
.mp-price-label {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}
.mp-packaging-hint {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
  line-height: 1.35;
}
.mp-pending-amount {
  color: #909399;
  font-size: 12px;
}
:deep(.mp-row-needed) td {
  background-color: #fff8f0 !important;
}
:deep(.mp-row-sufficient) td {
  color: #909399;
}
</style>
