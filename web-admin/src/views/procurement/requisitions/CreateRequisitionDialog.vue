<script setup lang="ts">
import CsDateField from '@/components/cs/CsDateField.vue';
/**
 * 新建请购单 dialog — Sprint 6 W2-A.
 *
 * MVP scope (per Sprint 5 PR #56 backend contract):
 *   - 单选 suggestedSupplierId (整张请购单走同一供应商; UI 限制, backend convertToPO 同假设)
 *   - 多行明细: 物料 + 数量 + 单位 + 行内 remark
 *   - 期望交货日 + 申请原因 + 备注
 *
 * Fool-proof design (per .claude/rules/fool-proof-design.md):
 *   - Rule 2 (context): 行标题显示已选物料名 + 数量 单位
 *   - Rule 3 (constrained inputs): 单位走原料带出的选项 (allow-create 兜底)
 *   - 提交按 :disabled 控制 (必填校验失败时 disable)
 */
import { ref, reactive, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import { usePinyinFilter } from '@/utils/pinyinSearch';
import {
  createRequisition,
  type CreatePurchaseRequisitionRequest,
  type RequisitionItem,
} from '@/api/purchaseRequisition';
import type { TableRow } from '@/types/api';

interface Supplier {
  id: string;
  name: string;
}
interface Material {
  id: string;
  name: string;
  unit?: string | null;
  category?: string | null;
}

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'created'): void;
}>();

const authStore = useAuthStore();
const factoryId = computed(() => authStore.factoryId);
const isRestaurant = computed(() => authStore.factoryType === 'RESTAURANT');
const dialogTitle = computed(() => (isRestaurant.value ? '新建报货单' : '新建请购单'));
const supplierLabelText = computed(() => (isRestaurant.value ? '建议供应商' : '建议供应商'));
const supplierPlaceholder = computed(() =>
  isRestaurant.value
    ? '可选: 厨师长不确定供应商时留空, 采购转单时再选'
    : '选择供应商 (整张请购单走同一供应商)',
);
const expectedDateLabel = computed(() => (isRestaurant.value ? '希望到货日' : '期望交货日'));
const reasonLabel = computed(() => (isRestaurant.value ? '报货原因' : '申请原因'));
const reasonPlaceholder = computed(() =>
  isRestaurant.value
    ? '可选: 明日备货 / 厨房急用 / 库存不够 / 临时加菜'
    : '可选: 简要说明请购理由 (生产急用 / 库存告缺 / 客户专单)',
);
const remarkPlaceholder = computed(() => (isRestaurant.value ? '可选: 档口、厨师长、验收备注等' : '可选: 补充说明'));
const itemDividerText = computed(() => (isRestaurant.value ? '报货明细' : '请购明细'));
const addLineText = computed(() => (isRestaurant.value ? '添加报货明细' : '添加明细行'));
const createReadyText = computed(() => (isRestaurant.value ? '创建报货草稿' : '创建草稿'));
const createdMessagePrefix = computed(() => (isRestaurant.value ? '已创建报货单' : '已创建请购单'));

const visible = computed<boolean>({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
});

interface FormItemRow {
  materialTypeId: string;
  materialName: string;
  quantity: number | null;
  unit: string;
  remark: string;
}

function newItemRow(): FormItemRow {
  return {
    materialTypeId: '',
    materialName: '',
    quantity: null,
    unit: '',
    remark: '',
  };
}

const form = reactive<{
  suggestedSupplierId: string;
  expectedDate: string;
  reason: string;
  remark: string;
  items: FormItemRow[];
}>({
  suggestedSupplierId: '',
  expectedDate: '',
  reason: '',
  remark: '',
  items: [newItemRow()],
});

const suppliers = ref<Supplier[]>([]);
const materials = ref<Material[]>([]);

// ─────────────────────────────────────────────────────────────────────────
// 缺陷 13 (2026-09-01, LIUSHANMEN 生产走查): owner 在物料框输入 `ht` (海天的首字母)
// → 下拉显示「无匹配数据」。
//
// ⚠️ **这不是「搜索坏了」** —— 输「海天」或「老抽」能搜到。是**匹配面不够**,
//    两个因素叠加才让它难用:
//      1. Element Plus 默认 `filterable` 只对 label 做中文子串匹配, 不认拼音/首字母;
//         而中文 ERP 里一线用拼音比切输入法打汉字快。
//      2. 物料名**以规格开头** (生产实测 `13.9kg海天草菇老抽`) ⇒ 用户凭直觉去输
//         最自然的品名首字母, 一定不中。
//
// 复用 `@/utils/pinyinSearch` (pinyin-pro): 原文 / 全拼 / 首字母 三种任一命中即匹配,
// 所以 `海天` `haitian` `ht` 都能找到 `13.9kg海天草菇老抽`。
// ⛔ 不在这里再抄一份匹配逻辑 —— 那个文件头部已经把「两份会漂」登记在案, 并且
//    明写了物料下拉是它预期要推广到的场景之一。
// ⚠️ 用了 `:filter-method` 之后 el-select 会**关掉内建的字面过滤**,
//    过滤结果必须自己维护并喂给 v-for (见 usePinyinFilter 内部注释) ⇒ 下面用 filteredMaterials。
const materialFilter = usePinyinFilter(
  () => materials.value,
  (m) => [m.name],
);
const handleMaterialFilter = materialFilter.handleFilter;
const handleMaterialVisibleChange = materialFilter.handleVisibleChange;
// USelectMenu: 搜索词双向绑定到同一个 query，过滤仍走 usePinyinFilter（拼音/首字母），ignore-filter 关掉组件自带的字面过滤
const materialSearch = materialFilter.query;
const filteredMaterials = materialFilter.filtered;

// 🔴 空态文案要挂 `no-data-text`, ⛔ 不能只挂 `no-match-text`。
// 读过 element-plus 2.13.0 的 useSelect.mjs `emptyText`:
//     filterable && inputValue && states.options.size > 0 && filteredCount === 0 → noMatchText
//     states.options.size === 0                                                  → noDataText
// `states.options` 数的是**真正渲染出来的 <el-option> 组件**。我们用 filter-method
// 自己过滤 v-for 列表 ⇒ 没命中时**一个 el-option 都不渲染** ⇒ options.size === 0
// ⇒ 走的是 **noDataText** 那一支, noMatchText 永远到不了。
// (只挂 noMatchText 就是「机制在、没接上」—— 文案写了但用户永远看不到。)
// ⇒ 两个都挂同一份文案, 由 query 是否为空区分「搜不到」和「本来就没有」。
const materialEmptyText = computed(() =>
  materialFilter.query.value.trim()
    ? '没找到这个物料 —— 试试品名关键字或拼音首字母, 例如 老抽 / laochou / lc'
    : '暂无可选物料 —— 请先在「原料档案」里维护',
);
const submitting = ref(false);

async function loadSuppliers() {
  if (!factoryId.value) return;
  try {
    const res = await get<{ content?: Supplier[] }>(
      `/${factoryId.value}/suppliers`,
      { params: { page: 1, size: 200 } },
    );
    suppliers.value = res?.data?.content ?? [];
  } catch {
    // request interceptor already showed sticky toast
  }
}

async function loadMaterials() {
  if (!factoryId.value) return;
  try {
    const res = await get<Material[] | { content?: Material[] }>(
      `/${factoryId.value}/raw-material-types/active`,
    );
    const data = res?.data;
    materials.value = Array.isArray(data) ? data : data?.content ?? [];
  } catch {
    // interceptor handles
  }
}

watch(visible, async (v) => {
  if (v) {
    // 重置表单
    form.suggestedSupplierId = '';
    form.expectedDate = '';
    form.reason = '';
    form.remark = '';
    form.items = [newItemRow()];
    await Promise.all([loadSuppliers(), loadMaterials()]);
  }
});

function onMaterialChange(row: FormItemRow) {
  const mat = materials.value.find((m) => m.id === row.materialTypeId);
  if (mat) {
    row.materialName = String(mat.name || '');
    if (!row.unit && mat.unit) row.unit = String(mat.unit);
  }
}

// 单位允许手输新值（原 el-select allow-create）：USelectMenu create-item 触发后直接写回该行
function onUnitCreate(row: FormItemRow, name: string) {
  row.unit = name;
}

function addItem() {
  form.items.push(newItemRow());
}

function removeItem(idx: number) {
  if (form.items.length > 1) form.items.splice(idx, 1);
}

const canSubmit = computed(() => {
  if (!isRestaurant.value && !form.suggestedSupplierId) return false;
  if (!form.items.length) return false;
  return form.items.every(
    (it) =>
      it.materialTypeId &&
      it.unit &&
      typeof it.quantity === 'number' &&
      it.quantity > 0,
  );
});

async function handleCreate() {
  if (!canSubmit.value || submitting.value || !factoryId.value) return;
  submitting.value = true;
  try {
    const requestedItems: RequisitionItem[] = form.items.map((it) => ({
      materialTypeId: it.materialTypeId,
      materialName: it.materialName || null,
      quantity: Number(it.quantity),
      unit: it.unit,
      suggestedSupplierId: form.suggestedSupplierId || null,
      remark: it.remark || null,
    }));
    const payload: CreatePurchaseRequisitionRequest = {
      requestedItems,
      expectedDate: form.expectedDate || null,
      reason: form.reason || null,
      remark: form.remark || null,
    };
    const res = await createRequisition(factoryId.value, payload);
    if (res?.success) {
      const created = res.data;
      ElMessage.success(`${createdMessagePrefix.value} ${created?.requisitionNumber || ''}`);
      visible.value = false;
      emit('created');
    } else {
      ElMessage.error(res?.message || '创建失败');
    }
  } catch (e) {
    // Interceptor shows toast; log for dev
    console.error('[请购单创建失败]', e);
  } finally {
    submitting.value = false;
  }
}

// Fool-proof Rule 2: dialog 标题显示已选供应商 (一旦选了)
const supplierLabel = computed(() => {
  const s = suppliers.value.find((x) => x.id === form.suggestedSupplierId);
  return s ? s.name : '';
});

function getMaterialUnits(row: FormItemRow): string[] {
  const mat = materials.value.find((m) => m.id === row.materialTypeId);
  const set = new Set<string>();
  if (mat?.unit) set.add(String(mat.unit));
  if (row.unit) set.add(row.unit);
  return Array.from(set);
}

function _avoidUnusedWarning(_r: TableRow) {
  /* preserve TableRow import for future audit */
}
void _avoidUnusedWarning;
</script>

<template>
  <UModal
    v-model:open="visible"
    :title="dialogTitle"
    description="创建请购单草稿"
    :dismissible="false"
    :ui="{ footer: 'justify-end', content: 'max-w-[900px]', description: 'sr-only' }"
  >
    <template #body>
      <div class="form">
        <UFormField :label="supplierLabelText" :required="!isRestaurant">
          <USelectMenu
            v-model="form.suggestedSupplierId"
            :items="suppliers"
            value-key="id"
            label-key="name"
            :placeholder="supplierPlaceholder"
            clear
            class="w-full"
          />
          <div v-if="supplierLabel" class="hint">
            已选: <strong>{{ supplierLabel }}</strong>
          </div>
        </UFormField>
        <UFormField :label="expectedDateLabel">
          <CsDateField v-model="form.expectedDate" clearable class="w-56" />
        </UFormField>
        <UFormField :label="reasonLabel">
          <UTextarea v-model="form.reason" :rows="2" :maxlength="5000" :placeholder="reasonPlaceholder" class="w-full" />
        </UFormField>
        <UFormField label="备注">
          <UTextarea v-model="form.remark" :rows="2" :maxlength="5000" :placeholder="remarkPlaceholder" class="w-full" />
        </UFormField>

        <USeparator :label="itemDividerText" />

        <div class="item-row item-header">
          <span class="c-material"><span class="req-star">*</span>物料</span>
          <span class="c-qty"><span class="req-star">*</span>数量</span>
          <span class="c-unit"><span class="req-star">*</span>单位</span>
          <span class="c-remark">行备注</span>
          <span class="c-act">操作</span>
        </div>
        <div v-for="(item, idx) in form.items" :key="idx" class="item-row">
          <!-- 缺陷 13: 拼音/首字母搜索 + 能指路的空态。ignore-filter 关掉组件自带过滤 ⇒ items 走 filteredMaterials。 -->
          <USelectMenu
            v-model="item.materialTypeId"
            v-model:search-term="materialSearch"
            :items="filteredMaterials"
            value-key="id"
            label-key="name"
            ignore-filter
            placeholder="选择物料 (支持拼音, 如 ht)"
            class="c-material"
            @update:model-value="onMaterialChange(item)"
            @update:open="(o: boolean) => handleMaterialVisibleChange(o)"
          >
            <template #empty>{{ materialEmptyText }}</template>
          </USelectMenu>
          <UInputNumber
            v-model="item.quantity"
            :min="0.01"
            :step-snapping="false"
            :format-options="{ minimumFractionDigits: 2, maximumFractionDigits: 2 }"
            :increment="false"
            :decrement="false"
            placeholder="数量"
            class="c-qty"
          />
          <USelectMenu
            v-model="item.unit"
            :items="getMaterialUnits(item)"
            :disabled="!item.materialTypeId"
            create-item
            placeholder="单位"
            class="c-unit"
            @create="(name: string) => onUnitCreate(item, name)"
          />
          <UInput v-model="item.remark" placeholder="可选 — 备注" :maxlength="200" class="c-remark" />
          <UButton
            variant="link"
            color="error"
            icon="i-lucide-trash-2"
            class="c-act"
            aria-label="删除明细行"
            :disabled="form.items.length <= 1"
            @click="removeItem(idx)"
          />
        </div>
        <UButton color="primary" variant="outline" block icon="i-lucide-plus" @click="addItem">{{ addLineText }}</UButton>
      </div>
    </template>
    <template #footer>
      <UButton color="neutral" variant="outline" @click="visible = false">取消</UButton>
      <UButton :loading="submitting" :disabled="!canSubmit" @click="handleCreate">
        {{ canSubmit ? createReadyText : '请填写必填项' }}
      </UButton>
    </template>
  </UModal>
</template>

<style scoped>
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.item-row { display: flex; gap: var(--cs-space-2); align-items: center; }
.item-header { font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); }
.c-material { width: 240px; flex: none; }
.c-qty { width: 130px; flex: none; }
.c-unit { width: 120px; flex: none; }
.c-remark { flex: 1; min-width: 160px; }
.c-act { width: 60px; flex: none; }
.hint { font-size: var(--cs-font-small); color: var(--cs-muted); margin-top: var(--cs-space-1); }
.req-star { color: var(--cs-danger); margin-right: var(--cs-space-1); }
</style>
