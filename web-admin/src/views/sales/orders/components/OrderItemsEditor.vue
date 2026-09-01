<!-- web-admin/src/views/sales/orders/components/OrderItemsEditor.vue -->
<script setup lang="ts">
/**
 * OrderItemsEditor — 销售订单产品行受控编辑器（新建弹窗 + 详情页 DRAFT 编辑共用）。
 * 字段对齐后端 SalesOrderItemDTO。选产品时 emit product-change(index) 供父级跑价格逻辑。
 *
 * 🔒 2026-09-02 Owner 裁定: 「销售订单行不再让人填单价(未税)」——
 * 一般含税报价才是操作员知道的数, 未税报价永远算不出来。含税单价 (taxIncludedUnitPrice)
 * 是录入项, 未税单价 (unitPrice) 由它与税率实时反算, 只作展示; 后端存储口径不变
 * (unitPrice 仍是未税, 见 SalesOrderItem.getLineAmount())。taxIncludedUnitPrice
 * 仅供界面录入, 提交前必须由父组件剥掉 (不进后端 DTO)。
 */
import { computed } from 'vue';
import { untaxedUnitPrice } from '../salesOrderNewLineContract';

export interface OrderItemRow {
  productTypeId: string;
  productName?: string;
  quantity?: number;
  unit?: string;
  unitPrice?: number;
  /** 仅供界面录入含税单价; 提交仍是后端既有的未税 unitPrice, 由此值与 taxRate 实时反算。 */
  taxIncludedUnitPrice?: number;
  taxRate?: number;
  specification?: string;
  [k: string]: unknown;
}
interface ProductOption {
  id: string;
  name: string;
  unit?: string;
  code?: string;
  specification?: string;
  packageSpec?: string;
  productCategory?: string;
  category?: string;
  [k: string]: unknown;
}

const props = defineProps<{ items: OrderItemRow[]; products: ProductOption[] }>();
const emit = defineEmits<{
  (e: 'update:items', v: OrderItemRow[]): void;
  (e: 'product-change', index: number): void;
}>();

const rows = computed(() => props.items);
function emitRows(next: OrderItemRow[]) { emit('update:items', next); }

function emptyRow(): OrderItemRow { return { productTypeId: '', quantity: 0, unit: '份', unitPrice: 0, taxIncludedUnitPrice: 0, taxRate: 13 }; }
function addRow() { emitRows([...rows.value, emptyRow()]); }
function removeRow(idx: number) {
  const next = rows.value.slice();
  next.splice(idx, 1);
  emitRows(next.length ? next : [emptyRow()]);
}
function onProductChange(idx: number, productTypeId: string) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r } : r));
  next[idx].productTypeId = productTypeId;
  const p = props.products.find((x) => x.id === productTypeId);
  if (p) { next[idx].productName = p.name; if (!next[idx].unit) next[idx].unit = p.unit ?? '份'; }
  emitRows(next);
  emit('product-change', idx);
}
function patch(idx: number, key: keyof OrderItemRow, val: unknown) {
  const next = rows.value.map((r, i) => (i === idx ? { ...r, [key]: val } : r));
  emitRows(next);
}

/** 税率必须是 0-100 的有效数字才能反算未税单价; 清空/非法税率一律判定为「未知」而不是 0。 */
function validTaxRate(rate: unknown): number | null {
  if (rate === null || rate === undefined || rate === '') return null;
  const n = Number(rate);
  return Number.isFinite(n) && n >= 0 && n <= 100 ? n : null;
}

/**
 * 含税单价 + 税率 → 未税单价 (仅供展示; 与提交给后端的 unitPrice 用同一条公式,
 * 保证「展示的数」和「实际存的数」永远一致)。税率未知时返回 null —— 调用方必须
 * 把它当成「算不出来」处理, 禁止落到 0 或原样回显含税价。
 */
function derivedUntaxedUnitPrice(row: OrderItemRow): number | null {
  const rate = validTaxRate(row.taxRate);
  if (rate === null) return null;
  return untaxedUnitPrice(Number(row.taxIncludedUnitPrice) || 0, rate);
}

function patchTaxIncludedPrice(idx: number, val: number) {
  const next = rows.value.map((r, i) => {
    if (i !== idx) return r;
    const row = { ...r, taxIncludedUnitPrice: val };
    const untaxed = derivedUntaxedUnitPrice(row);
    return { ...row, unitPrice: untaxed === null ? undefined : untaxed };
  });
  emitRows(next);
}

function patchTaxRate(idx: number, val: number) {
  const next = rows.value.map((r, i) => {
    if (i !== idx) return r;
    const row = { ...r, taxRate: val };
    const untaxed = derivedUntaxedUnitPrice(row);
    return { ...row, unitPrice: untaxed === null ? undefined : untaxed };
  });
  emitRows(next);
}

const hasProducts = computed(() => props.products.length > 0);

const categoryLabels: Record<string, string> = {
  FINISHED_PRODUCT: '成品',
  RAW_MATERIAL: '原料',
  PACKAGING: '包材',
  SEASONING: '调味料',
  CUSTOMER_MATERIAL: '客供料',
  CONTRACT_MANUFACTURING: '代工',
};

function textPart(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function productCategoryLabel(product: ProductOption): string {
  const code = textPart(product.productCategory) || textPart(product.category);
  return categoryLabels[code] || code;
}

function productOptionLabel(product: ProductOption): string {
  return [
    textPart(product.code),
    textPart(product.name),
    textPart(product.specification) || textPart(product.packageSpec),
    productCategoryLabel(product),
  ].filter(Boolean).join(' / ');
}

defineExpose({ productOptionLabel, derivedUntaxedUnitPrice, patchTaxIncludedPrice, patchTaxRate });
</script>

<template>
  <div class="order-items-editor">
    <slot v-if="!hasProducts" name="empty-products" />
    <el-table v-else :data="rows" border size="small">
      <el-table-column label="产品" min-width="160">
        <template #default="{ row, $index }">
          <el-select v-if="row" :model-value="row.productTypeId" placeholder="选择产品"
            filterable
            clearable
            @update:model-value="(v: string) => onProductChange($index, v)">
            <el-option v-for="p in products" :key="p.id" :label="productOptionLabel(p)" :value="p.id" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="数量" width="120">
        <template #default="{ row, $index }">
          <el-input-number v-if="row" :model-value="row.quantity" :min="0" controls-position="right"
            @update:model-value="(v: number) => patch($index, 'quantity', v)" />
        </template>
      </el-table-column>
      <el-table-column label="单位" width="90">
        <template #default="{ row, $index }">
          <el-input v-if="row" :model-value="row.unit" @update:model-value="(v: string) => patch($index, 'unit', v)" />
        </template>
      </el-table-column>
      <el-table-column label="含税单价" width="150">
        <template #default="{ row, $index }">
          <template v-if="row">
            <el-input-number :model-value="row.taxIncludedUnitPrice" :min="0" :precision="2" controls-position="right"
              @update:model-value="(v: number) => patchTaxIncludedPrice($index, v)" />
            <div v-if="derivedUntaxedUnitPrice(row) !== null" class="derived-untaxed-price">
              未税 {{ derivedUntaxedUnitPrice(row) }}
            </div>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="税率%" width="100">
        <template #default="{ row, $index }">
          <el-input-number v-if="row" :model-value="row.taxRate" :min="0" :max="100" controls-position="right"
            @update:model-value="(v: number) => patchTaxRate($index, v)" />
        </template>
      </el-table-column>
      <el-table-column label="规格" min-width="120">
        <template #default="{ row, $index }">
          <el-input v-if="row" :model-value="row.specification" @update:model-value="(v: string) => patch($index, 'specification', v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button v-if="$index != null" type="danger" link size="small" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="hasProducts" size="small" style="margin-top: 8px;" @click="addRow">+ 添加一行</el-button>
  </div>
</template>

<style scoped>
.derived-untaxed-price {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
  line-height: 1.2;
}
</style>
