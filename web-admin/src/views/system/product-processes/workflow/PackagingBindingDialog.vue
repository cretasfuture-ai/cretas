<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  bomRecipeApi,
  type BomItemSubstituteView,
  type BomRecipeItemPayload,
  type BomRecipeItemView,
  type ProductPackagingSpecView,
} from '@/api/bom';
import { canonicalUnitCode, canonicalUnitCodeKeepingCount, displayUnit } from '@/utils/unitPricing';

/**
 * 画布上的包材编辑弹窗 —— Task 1 (2026-08-05 bom-canvas-phase3-2)。
 *
 * 走 `bomRecipeApi.addItem` / `bomRecipeApi.updateItem`(见 task-1-report.md Step 1 表),
 * 不新增任何 API 函数/端点。这两个函数原本是旧 BOM 页(`views/production/bom/index.vue`)
 * 的包材新增/编辑路径在用的; 该页 2026-08-07 阶段 5 已删, 本组件成为唯一调用方。
 *
 * 保存口径是「包装层级自然用量 → 成品基本单位标准用量」。例如 SKU 基本单位为盒，
 * 包装规格为 1 箱 = 8 盒，外箱自然用量填 1 个/箱，标准折算即 0.125 个/盒。
 * 报工只报实际成品产出，后端按标准折算自动计算包材需求。
 */

export interface PackagingMaterialOption {
  id: string;
  name: string;
  code?: string | null;
  primaryCode?: string | null;
  category?: string | null;
  materialCategory?: string | null;
  unit?: string | null;
  quantityUnit?: string | null;
  priceUnit?: string | null;
  movingAvgPrice?: number | null;
  /** RawMaterialTypeDTO 的未税采购参考价键；响应不会使用 unitPrice。 */
  materialReferencePrice?: number | null;
  taxRate?: string | null;
  /**
   * 三级物料分类节点 id(后端 RawMaterialTypeDTO.classificationId ←
   * raw_material_types.classification_segment_id)。替代包材的作用域判据就是它。
   */
  classificationId?: number | string | null;
}

/** 与落库行同形 —— 直接复用 API 的响应类型, 避免维护一份平行、容易漂移的 DTO。 */
export type PackagingRowPayload = BomRecipeItemView;

const props = withDefaults(defineProps<{
  modelValue: boolean;
  factoryId: string;
  recipeId: string;
  outputName: string;
  /** 产出 SKU，用于读取该产品自己的盒/箱/袋包装规格。 */
  productTypeId: string;
  /** 产出 SKU 的基本单位 —— 用量分母的权威来源, 禁止硬编码。 */
  baseUnit: string;
  row?: PackagingRowPayload | null;
  materials: PackagingMaterialOption[];
  /** 由调用方按 `parentKind === 'RECIPE_ITEM' && parentRecipeItemId === row.id` 预先过滤好传入
   *  (与 SeasoningBindingDialog 的 substituteRelations 契约一致)。 */
  substituteRelations?: BomItemSubstituteView[];
  canWrite?: boolean;
}>(), {
  row: null,
  substituteRelations: () => [],
  canWrite: true,
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
  conflict: [];
}>();

const form = reactive({
  materialTypeId: '',
  packagingSpecId: '',
  packagingRole: 'PRIMARY_CONTAINER',
  naturalQuantity: null as number | null,
  isOptional: false,
  remark: '',
  substituteMaterialTypeIds: [] as string[],
  substituteFactors: {} as Record<string, number | null>,
});
const saving = reactive({ value: false });
const formRef = ref<{ validate: () => Promise<boolean> } | null>(null);
let operationGeneration = 0;
const packagingSpecs = ref<ProductPackagingSpecView[]>([]);
const packagingSpecsLoading = ref(false);
const packagingSpecsError = ref('');

const PACKAGING_ROLE_OPTIONS = [
  { value: 'PRIMARY_CONTAINER', label: '成品直接容器（彩盒/托盘）' },
  { value: 'INNER_WRAP', label: '内包装（内袋/白套）' },
  { value: 'SEAL', label: '封装（膜/封口材料）' },
  { value: 'LABEL', label: '标签/标识' },
  { value: 'OUTER_CASE', label: '外包装（纸箱/周转箱）' },
  { value: 'ADHESIVE', label: '包装辅助耗材（胶等）' },
  { value: 'OTHER', label: '其他包材' },
] as const;

const selectedMaterial = computed(() => props.materials.find((item) => item.id === form.materialTypeId));
const positivePrice = (value?: number | null): value is number => (
  value != null && Number.isFinite(Number(value)) && Number(value) > 0
);
const effectivePrice = computed(() => {
  const movingAvgPrice = selectedMaterial.value?.movingAvgPrice;
  if (positivePrice(movingAvgPrice)) {
    return { value: Number(movingAvgPrice), source: '移动平均库存成本' as const };
  }
  const referencePrice = selectedMaterial.value?.materialReferencePrice;
  if (positivePrice(referencePrice)) {
    return { value: Number(referencePrice), source: '未税采购参考价' as const };
  }
  return null;
});
const missingEffectivePrice = computed(() => selectedMaterial.value != null && effectivePrice.value == null);
const automaticPriceLabel = computed(() => effectivePrice.value
  ? `¥${effectivePrice.value.value.toFixed(4)}`
  : '保存前需先配置有效价格');

/** 与 index.vue 的 recipeUnitForMaterial(category='PACKAGING') 同规则: 缺档案单位兜底 pcs。 */
const quantityUnit = computed(() => (
  canonicalUnitCodeKeepingCount(selectedMaterial.value?.quantityUnit || selectedMaterial.value?.unit) || 'pcs'
));
const quantityUnitLabel = computed(() => displayUnit(quantityUnit.value));
const baseUnitLabel = computed(() => displayUnit(props.baseUnit));
const selectedPackagingSpec = computed(() => packagingSpecs.value
  .find((item) => item.id === form.packagingSpecId));
const packagingBasisLabel = computed(() => {
  const spec = selectedPackagingSpec.value;
  if (!spec) return `每 1 ${baseUnitLabel.value}成品`;
  return `每 1 ${displayUnit(spec.packageUnit)}（=${spec.conversionFactor} ${displayUnit(spec.baseUnit)}）`;
});
const standardQuantityPreview = computed(() => {
  if (form.naturalQuantity == null
      || !Number.isFinite(form.naturalQuantity)
      || form.naturalQuantity <= 0) {
    return null;
  }
  const factor = Number(selectedPackagingSpec.value?.conversionFactor ?? 1);
  if (!Number.isFinite(factor) || factor <= 0) return null;
  return form.naturalQuantity / factor;
});
const standardQuantityPreviewText = computed(() => standardQuantityPreview.value == null
  ? '—'
  : `${Number(standardQuantityPreview.value.toFixed(8))} ${quantityUnitLabel.value}/${baseUnitLabel.value}`);

/**
 * 替代包材的作用域判据 —— 只有同一个三级分类节点的包材才允许互替。
 *
 * <h3>🔴 2026-08-13 真机实测: 旧判据在生产上恒为空</h3>
 * 旧写法是「编码是 10 位以上纯数字 → 取前 10 位; 否则 category 不是『包材』→ 用 category」。
 * 两条分支在生产上**都走不到**:
 *
 * - 10 位数字码: 全库 552 个启用物料, 命中 **0 个**(本仓编码是 BC001/WL0xx 这种短码,
 *   「不再固定 16 位」之后再没有纯数字长码)。
 * - category 分支: 能进包材选择器的 category 只有 `包材/packaging/PACKAGING`
 *   (见 utils/materialCategory.ts#PACKAGING_CATEGORY_VALUES), 而这条分支正好把它们排除 ——
 *   **构造上就死的**, 不是当前数据碰巧走不到。
 *
 * 于是「替代包材」这一栏对所有工厂、所有包材 100% 禁用, 而提示说「主包材缺少可验证的
 * 分类」, 指向一个**没有任何可达数据状态能满足**的条件: 用户以为是自己档案没维护好,
 * 实际上怎么维护都没用。闸的意图是对的(不能让「标签」被「真空袋」替代),
 * 坏的是没人有路遵守它。
 *
 * <p>改用 `classificationId` —— 系统本来就为「分类/包装作用域」设计的载体
 * (raw_material_types.classification_segment_id, 三级分类节点)。它可空;
 * 空的时候仍然禁用替代, 但提示改成一个**做得到**的动作(去物料档案挂分类)。
 */
function packagingClassificationKey(material: PackagingMaterialOption | undefined): string {
  if (!material) return '';
  const segmentId = String(material.classificationId ?? '').trim();
  return segmentId ? `seg:${segmentId}` : '';
}

const substituteCandidates = computed<PackagingMaterialOption[]>(() => {
  const selectedKey = packagingClassificationKey(selectedMaterial.value);
  if (!selectedKey) return [];
  return props.materials.filter((item) => (
    item.id !== form.materialTypeId && packagingClassificationKey(item) === selectedKey
  ));
});

function substituteUnit(materialTypeId: string): string {
  const material = props.materials.find((item) => item.id === materialTypeId);
  return canonicalUnitCodeKeepingCount(material?.quantityUnit || material?.unit);
}

function substituteNeedsExplicitFactor(materialTypeId: string): boolean {
  return substituteUnit(materialTypeId) !== quantityUnit.value;
}

let packagingSpecLoadSeq = 0;
async function loadPackagingSpecs(): Promise<void> {
  if (!props.modelValue || !props.productTypeId) return;
  const seq = ++packagingSpecLoadSeq;
  packagingSpecsLoading.value = true;
  packagingSpecsError.value = '';
  try {
    const response = await bomRecipeApi.getProductPackagingSpecs(props.factoryId, props.productTypeId);
    if (seq !== packagingSpecLoadSeq) return;
    packagingSpecs.value = (response.data || []).filter((item) => item.active !== false);
    if (form.packagingSpecId
        && !packagingSpecs.value.some((item) => item.id === form.packagingSpecId)) {
      packagingSpecsError.value = '该行引用的包装规格已停用或不存在，请重新选择后保存。';
    }
  } catch {
    if (seq !== packagingSpecLoadSeq) return;
    packagingSpecs.value = [];
    packagingSpecsError.value = '包装规格读取失败，当前不能保存包材关系。';
  } finally {
    if (seq === packagingSpecLoadSeq) packagingSpecsLoading.value = false;
  }
}

watch(() => [props.modelValue, props.row, props.productTypeId] as const, () => {
  if (!props.modelValue) return;
  form.materialTypeId = props.row?.materialTypeId || '';
  form.packagingSpecId = props.row?.packagingSpecId || '';
  form.packagingRole = props.row?.packagingRole || 'PRIMARY_CONTAINER';
  const factor = Number(props.row?.packagingConversionFactorSnapshot ?? 1);
  form.naturalQuantity = props.row?.naturalQuantity != null
    ? Number(props.row.naturalQuantity)
    : props.row?.standardQuantity != null
      ? Number(props.row.standardQuantity) * (Number.isFinite(factor) && factor > 0 ? factor : 1)
      : null;
  form.isOptional = Boolean(props.row?.isOptional);
  form.remark = props.row?.remark || '';
  form.substituteMaterialTypeIds = props.substituteRelations.map((relation) => relation.substituteMaterialTypeId);
  form.substituteFactors = Object.fromEntries(props.substituteRelations.map((relation) => [
    relation.substituteMaterialTypeId,
    relation.conversionFactor == null ? null : Number(relation.conversionFactor),
  ]));
  void loadPackagingSpecs();
}, { immediate: true });

function invalidatePendingOperation(): void {
  operationGeneration += 1;
}

function isCurrentOperation(generation: number): boolean {
  return props.canWrite && props.modelValue && generation === operationGeneration;
}

watch(() => props.canWrite, (canWrite) => {
  if (!canWrite) invalidatePendingOperation();
}, { flush: 'sync' });
watch(() => props.modelValue, (modelValue) => {
  if (!modelValue) invalidatePendingOperation();
}, { flush: 'sync' });
onBeforeUnmount(invalidatePendingOperation);

function isRevisionConflict(error: unknown): boolean {
  const candidate = error as { response?: { status?: number }; status?: number };
  return candidate?.response?.status === 409 || candidate?.status === 409;
}

async function submit() {
  if (!props.canWrite || !props.modelValue) return;
  const generation = operationGeneration;
  if (!form.materialTypeId) return ElMessage.warning('请选择包材');
  if (!form.packagingRole) return ElMessage.warning('请选择包材业务角色');
  if (packagingSpecsLoading.value || packagingSpecsError.value) {
    return ElMessage.warning(packagingSpecsError.value || '包装规格仍在读取，请稍后再保存');
  }
  // 禁止降级处理: 自然用量和折算量任一不可计算都拒绝保存，不能默默落库为 0。
  if (form.naturalQuantity == null
      || !Number.isFinite(form.naturalQuantity)
      || form.naturalQuantity <= 0
      || standardQuantityPreview.value == null) {
    return ElMessage.warning('请填写大于 0 的包装层级自然用量');
  }
  if (!effectivePrice.value) {
    return ElMessage.warning('该包材缺少有效移动平均库存成本或未税采购参考价，暂不能保存');
  }
  const missingFactor = form.substituteMaterialTypeIds.find((materialTypeId) => {
    if (!substituteNeedsExplicitFactor(materialTypeId)) return false;
    const factor = Number(form.substituteFactors[materialTypeId]);
    return !Number.isFinite(factor) || factor <= 0;
  });
  if (missingFactor) return ElMessage.warning('不同单位的替代包材必须填写大于0的明确等价换算系数');

  try {
    if (formRef.value && !(await formRef.value.validate())) return;
  } catch {
    ElMessage.warning('请检查表单填写内容');
    return;
  }
  if (!isCurrentOperation(generation)) return;

  saving.value = true;
  try {
    const payload: BomRecipeItemPayload = {
      materialTypeId: form.materialTypeId,
      workflowMaterialNodeId: props.row?.workflowMaterialNodeId ?? null,
      workflowInputPortId: props.row?.workflowInputPortId ?? null,
      workflowEdgeId: props.row?.workflowEdgeId ?? null,
      costScope: 'OUTPUT_EXCLUSIVE',
      standardQuantity: standardQuantityPreview.value,
      yieldRate: 100,
      unit: quantityUnit.value,
      unitPrice: effectivePrice.value.value,
      taxRate: selectedMaterial.value?.taxRate === 'TAX_9' ? 9 : selectedMaterial.value?.taxRate === 'TAX_13' ? 13 : 0,
      materialCategory: 'PACKAGING',
      sortOrder: props.row?.sortOrder ?? 0,
      isOptional: form.isOptional,
      substituteGroup: null,
      packagingSpecId: form.packagingSpecId || null,
      packagingRole: form.packagingRole,
      naturalQuantity: form.naturalQuantity,
      substitutes: form.substituteMaterialTypeIds.map((materialTypeId) => ({
        materialTypeId,
        conversionFactor: substituteNeedsExplicitFactor(materialTypeId)
          ? form.substituteFactors[materialTypeId] ?? null
          : null,
      })),
      remark: form.remark.trim() || null,
      perPortion: false,
      semiFinishedRefCode: null,
      subProductTypeId: null,
    };
    if (props.row?.id) {
      await bomRecipeApi.updateItem(props.factoryId, Number(props.row.id), payload);
    } else {
      await bomRecipeApi.addItem(props.factoryId, props.recipeId, payload);
    }
    if (!isCurrentOperation(generation)) return;
    emit('update:modelValue', false);
    emit('saved');
  } catch (error: unknown) {
    if (isRevisionConflict(error)) {
      ElMessage.warning('配方已被其他人修改，请重新加载后再操作');
      emit('conflict');
    } else {
      // 禁止降级处理: 原样显示后端 message, 不用 generic「操作失败」吞掉具体原因。
      ElMessage.error((error as { message?: string })?.message || '保存包材失败');
    }
  } finally {
    saving.value = false;
  }
}

function close(): void {
  invalidatePendingOperation();
  emit('update:modelValue', false);
}
</script>

<template>
  <!--
    🔴 2026-09-01 owner 指定: 「包材跟辅料按照之前我们原型图的样式的话, 应该就是侧边栏」。
    已确认原型里这两个编辑器是**停靠式侧栏**(evidence/auxiliary-recipe-docked-editor.png、
    packaging-recipe-docked-editor.png), 不是居中模态框。

    为什么侧栏比模态框对: 配料/包材要**边看画布边填** —— 居中模态框把画布整个盖住,
    用户看不到自己正在给哪道工序配料。侧栏靠边, 画布还在。

    ⛔ 只换外壳: 里面的表单、校验、保存逻辑一个字都没动(它们是已经在跑的东西)。
  -->
  <el-drawer
    :model-value="modelValue"
    :title="row ? '编辑包材' : '添加包材'"
    size="640px"
    direction="rtl"
    append-to-body
    @close="close"
  >
    <el-form ref="formRef" :model="form" label-width="130px" data-testid="packaging-binding-dialog">
      <el-form-item label="投入产出">
        <el-input data-testid="locked-output-context" :model-value="outputName" disabled />
        <div class="form-tip">产出由入口锁定，保存只影响该产出的包材。</div>
      </el-form-item>
      <el-form-item label="选择包材" required>
        <el-select v-model="form.materialTypeId" data-testid="packaging-material-select" filterable placeholder="从包材档案选择…" style="width: 100%">
          <el-option v-for="material in materials" :key="material.id" :label="material.name" :value="material.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="包材角色" required>
        <el-select v-model="form.packagingRole" data-testid="packaging-role-select" style="width: 100%">
          <el-option
            v-for="option in PACKAGING_ROLE_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <div class="form-tip">角色用于区分内袋、封膜、标签、彩盒和外箱；替代包材只能在同角色内生效。</div>
      </el-form-item>
      <el-form-item label="用量基准" required>
        <el-select
          v-model="form.packagingSpecId"
          data-testid="packaging-spec-select"
          :loading="packagingSpecsLoading"
          style="width: 100%"
        >
          <el-option :label="`基本规格：每 1 ${baseUnitLabel}`" value="" />
          <el-option
            v-for="spec in packagingSpecs"
            :key="spec.id"
            :label="`${spec.name}：1 ${displayUnit(spec.packageUnit)} = ${spec.conversionFactor} ${displayUnit(spec.baseUnit)}`"
            :value="spec.id"
          />
        </el-select>
        <div class="form-tip">从成品 SKU 的包装规格读取，不在 BOM 中另造“每箱几盒”。</div>
        <el-alert
          v-if="packagingSpecsError"
          type="warning"
          :closable="false"
          show-icon
          :title="packagingSpecsError"
        />
      </el-form-item>
      <el-form-item label="自然用量" required>
        <div style="display: flex; align-items: center; gap: 8px; width: 100%;">
          <el-input-number
            v-model="form.naturalQuantity"
            data-testid="packaging-natural-quantity"
            :precision="4"
            :step="0.01"
            :controls="false"
            placeholder="请输入用量"
            style="flex: 1;"
          />
          <span class="unit-suffix" data-testid="packaging-quantity-unit">{{ quantityUnitLabel }}</span>
        </div>
        <div class="form-tip">
          {{ packagingBasisLabel }}使用多少该包材。单位固定来自包材档案，不在此处修改。
        </div>
      </el-form-item>
      <el-form-item label="标准折算">
        <el-input data-testid="packaging-standard-preview" :model-value="standardQuantityPreviewText" disabled />
        <div class="form-tip">系统按成品基本单位保存并自动计算报工需求；报工员不再填写包材数量。</div>
      </el-form-item>
      <el-form-item label="档案单位">
        <el-input :model-value="quantityUnitLabel" disabled />
        <div class="form-tip">单位从物料档案自动继承且只读；如不正确，请先维护物料档案。</div>
      </el-form-item>
      <el-form-item label="自动单价">
        <div class="automatic-price" data-testid="packaging-automatic-price">
          <el-input :model-value="automaticPriceLabel" disabled />
          <el-tag v-if="effectivePrice" :type="effectivePrice.source === '移动平均库存成本' ? 'success' : 'warning'">
            {{ effectivePrice.source }}
          </el-tag>
        </div>
      </el-form-item>
      <el-alert
        v-if="missingEffectivePrice"
        type="warning"
        :closable="false"
        show-icon
        title="该包材缺少有效移动平均库存成本或未税采购参考价，暂不能保存"
      />
      <el-form-item label="替代包材（可选）">
        <el-select
          v-model="form.substituteMaterialTypeIds"
          multiple
          filterable
          collapse-tags
          collapse-tags-tooltip
          placeholder="选择可替代当前主项的包材…"
          style="width: 100%"
          data-testid="packaging-substitute-select"
        >
          <el-option
            v-for="item in substituteCandidates"
            :key="item.id"
            :value="item.id"
            :label="`${item.name} · ${displayUnit(item.quantityUnit || item.unit)}`"
          />
        </el-select>
        <div class="form-tip">替代包材必须与主包材属于同一分类/包装作用域；不会作为额外需求重复计算。</div>
        <el-alert
          v-if="form.materialTypeId && !packagingClassificationKey(selectedMaterial)"
          type="warning"
          :closable="false"
          show-icon
          title="该包材还没挂物料分类，暂不能配置替代包材"
          description="替代范围按三级物料分类判定。请到「仓储管理 → 原料类型字典」编辑这个包材、选好分类后再回来配置。"
        />
        <div v-if="form.substituteMaterialTypeIds.length" class="substitute-factors">
          <div v-for="materialTypeId in form.substituteMaterialTypeIds" :key="materialTypeId" class="substitute-factor-row">
            <span>{{ materials.find((item) => item.id === materialTypeId)?.name || materialTypeId }}</span>
            <template v-if="substituteNeedsExplicitFactor(materialTypeId)">
              <el-input-number
                v-model="form.substituteFactors[materialTypeId]"
                :data-testid="`packaging-substitute-factor-${materialTypeId}`"
                :min="0.000001"
                :precision="6"
                :controls="false"
                placeholder="等价换算系数"
              />
              <small>跨单位必须明确换算</small>
            </template>
            <el-tag v-else size="small" type="info">同单位默认 1:1</el-tag>
          </div>
        </div>
      </el-form-item>
      <el-form-item label="需求性质">
        <el-switch
          v-model="form.isOptional"
          :active-value="false"
          :inactive-value="true"
          active-text="必需"
          inactive-text="可省略"
        />
        <div class="form-tip">默认必需；标记可省略后，生产时允许不投入该包材。</div>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" />
      </el-form-item>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="正式报工只填写实际成品产出；系统按本配置自动分摊并扣减包材。损耗和额外领用不计入本标准用量。"
      />
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving.value" :disabled="!canWrite" @click="submit">保存包材</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.form-tip { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.unit-suffix { min-width: 40px; color: var(--el-text-color-regular); font-weight: 600; }
.automatic-price { display: flex; align-items: center; gap: 8px; width: 100%; }
.automatic-price :deep(.el-input) { flex: 1; }
.substitute-factors { display: grid; gap: 6px; width: 100%; margin-top: 8px; }
.substitute-factor-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.substitute-factor-row span { overflow: hidden; color: var(--el-text-color-regular); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.substitute-factor-row :deep(.el-input-number) { width: 150px; }
</style>
