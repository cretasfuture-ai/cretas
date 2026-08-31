<template>
  <el-dialog
    :model-value="modelValue"
    title="配置回料来源矩阵"
    width="min(1040px, 92vw)"
    append-to-body
    destroy-on-close
    data-testid="rework-matrix-dialog"
    @close="emit('update:modelValue', false)"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="行是回料来源产品，列是本次生产的目标产品。勾选表示该来源可投入该目标；比例按目标分支单独限制。"
    />

    <div v-if="sources.length === 0" class="matrix-empty">
      <el-empty description="画布里还没有能识别来源产品的回料 Cell">
        <span>请先在各产品质检/金检工序下增加“回料”产出，并绑定独立回料半成品 SKU。</span>
      </el-empty>
    </div>
    <div v-else class="matrix-scroll">
      <table class="rework-matrix">
        <thead>
          <tr>
            <th class="source-column">回料来源</th>
            <th
              v-for="target in targets"
              :key="target.productTypeId"
              :class="{ focused: target.productTypeId === focusTargetProductTypeId }"
            >
              <span class="product-dot" :style="{ backgroundColor: target.color }" />
              <strong>{{ target.productName }}</strong>
              <small>{{ target.processName || '尚未匹配同名分支工序' }}</small>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="source in sources" :key="source.productTypeId">
            <th class="source-product">
              <span class="product-dot" :style="{ backgroundColor: source.color }" />
              <span><strong>{{ source.productName }}</strong><small>{{ source.productCode }}</small></span>
            </th>
            <td
              v-for="target in targets"
              :key="`${source.productTypeId}:${target.productTypeId}`"
              :class="{ focused: target.productTypeId === focusTargetProductTypeId }"
            >
              <el-checkbox
                :model-value="isAllowed(source.productTypeId, target.productTypeId)"
                :disabled="!target.processNodeId || target.sharedProcess"
                :aria-label="`${source.productName} 可投入 ${target.productName}`"
                @change="setAllowedValue(source.productTypeId, target.productTypeId, $event)"
              />
            </td>
          </tr>
          <tr class="ratio-row">
            <th>回料最多占本分支投入</th>
            <td
              v-for="target in targets"
              :key="`ratio:${target.productTypeId}`"
              :class="{ focused: target.productTypeId === focusTargetProductTypeId }"
            >
              <el-input-number
                :model-value="ruleForTarget(target.productTypeId).maxInputRatioPercent"
                :min="0"
                :max="100"
                :precision="1"
                :step="1"
                :disabled="!target.processNodeId || target.sharedProcess"
                controls-position="right"
                @update:model-value="setMaxRatioValue(target.productTypeId, $event)"
              />
              <span>%</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-alert
      v-if="unmatchedTargets.length > 0"
      class="matrix-warning"
      type="warning"
      :closable="false"
      show-icon
      :title="`有 ${unmatchedTargets.length} 个目标分支未匹配到当前同名工序；请先把共用工序拆成各成品独立工序，再配置对应列。`"
    />
    <div class="matrix-note">
      此处只配置可选来源和最大比例，不配置本批固定投入量；实际用量在生产执行时按可用回料和批次需求选择。
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :disabled="sources.length === 0 || editableTargetCount === 0"
        data-testid="save-rework-matrix"
        @click="save"
      >应用到 Workflow 草稿</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  ReworkMatrixRule,
  ReworkProductOption,
  ReworkTargetOption,
} from './workflowReworkMatrix';

const props = defineProps<{
  modelValue: boolean;
  sources: ReworkProductOption[];
  targets: ReworkTargetOption[];
  rules: ReworkMatrixRule[];
  focusTargetProductTypeId?: string | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  save: [rules: ReworkMatrixRule[]];
}>();

const draftRules = ref<Record<string, ReworkMatrixRule>>({});

watch(() => [props.modelValue, props.rules, props.targets] as const, () => {
  if (!props.modelValue) return;
  draftRules.value = Object.fromEntries(props.targets.flatMap((target) => {
    if (!target.processNodeId) return [];
    const existing = props.rules.find((rule) => rule.processNodeId === target.processNodeId);
    return [[target.productTypeId, {
      processNodeId: target.processNodeId,
      targetProductTypeId: target.productTypeId,
      targetProductName: target.productName,
      allowedSourceProductTypeIds: [...(existing?.allowedSourceProductTypeIds ?? [])],
      maxInputRatioPercent: existing?.maxInputRatioPercent ?? 0,
    } satisfies ReworkMatrixRule]];
  }));
}, { immediate: true, deep: true });

const unmatchedTargets = computed(() => props.targets.filter((target) => !target.processNodeId || target.sharedProcess));
const editableTargetCount = computed(() => props.targets.length - unmatchedTargets.value.length);

function ruleForTarget(targetProductTypeId: string): ReworkMatrixRule {
  return draftRules.value[targetProductTypeId] ?? {
    processNodeId: '',
    targetProductTypeId,
    targetProductName: null,
    allowedSourceProductTypeIds: [],
    maxInputRatioPercent: 0,
  };
}

function isAllowed(sourceProductTypeId: string, targetProductTypeId: string): boolean {
  return ruleForTarget(targetProductTypeId).allowedSourceProductTypeIds.includes(sourceProductTypeId);
}

function setAllowed(sourceProductTypeId: string, targetProductTypeId: string, allowed: boolean): void {
  const rule = ruleForTarget(targetProductTypeId);
  const next = new Set(rule.allowedSourceProductTypeIds);
  if (allowed) next.add(sourceProductTypeId); else next.delete(sourceProductTypeId);
  draftRules.value = {
    ...draftRules.value,
    [targetProductTypeId]: { ...rule, allowedSourceProductTypeIds: [...next] },
  };
}

function setAllowedValue(sourceProductTypeId: string, targetProductTypeId: string, value: unknown): void {
  setAllowed(sourceProductTypeId, targetProductTypeId, Boolean(value));
}

function setMaxRatio(targetProductTypeId: string, value: number): void {
  const rule = ruleForTarget(targetProductTypeId);
  draftRules.value = {
    ...draftRules.value,
    [targetProductTypeId]: {
      ...rule,
      maxInputRatioPercent: Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0)),
    },
  };
}

function setMaxRatioValue(targetProductTypeId: string, value: unknown): void {
  setMaxRatio(targetProductTypeId, Number(value ?? 0));
}

function save(): void {
  emit('save', Object.values(draftRules.value).filter((rule) => Boolean(rule.processNodeId)));
  emit('update:modelValue', false);
}
</script>

<style scoped>
.matrix-scroll { margin-top: 16px; overflow-x: auto; border: 1px solid #edf2f7; border-radius: 10px; }
.rework-matrix { width: 100%; min-width: 760px; border-collapse: separate; border-spacing: 0; }
.rework-matrix th, .rework-matrix td { padding: 12px; border-right: 1px solid #edf2f7; border-bottom: 1px solid #edf2f7; text-align: center; }
.rework-matrix tr:last-child th, .rework-matrix tr:last-child td { border-bottom: 0; }
.rework-matrix th:last-child, .rework-matrix td:last-child { border-right: 0; }
.rework-matrix thead th { min-width: 160px; background: #f7f9fc; color: #344054; }
.rework-matrix thead th strong, .source-product strong { display: block; font-size: 13px; }
.rework-matrix small { display: block; margin-top: 3px; color: #7a8599; font-size: 11px; font-weight: 400; }
.source-column { width: 190px; text-align: left !important; }
.source-product { display: table-cell; text-align: left !important; background: #fbfcfe; white-space: nowrap; }
.source-product > span:last-child { display: inline-block; vertical-align: middle; }
.product-dot { display: inline-block; width: 10px; height: 10px; margin-right: 7px; border-radius: 3px; vertical-align: middle; }
.focused { background: #eef6ff !important; box-shadow: inset 0 0 0 1px rgba(27, 101, 168, 0.18); }
.ratio-row th { color: #344054; text-align: left; background: #fffaf0; }
.ratio-row td { white-space: nowrap; background: #fffdf8; }
.ratio-row :deep(.el-input-number) { width: 118px; margin-right: 4px; }
.matrix-empty { padding: 20px 0 4px; }
.matrix-empty span { color: #667085; font-size: 12px; }
.matrix-warning { margin-top: 14px; }
.matrix-note { margin-top: 12px; padding: 10px 12px; border-radius: 8px; background: #f7f9fc; color: #667085; font-size: 12px; line-height: 1.6; }
</style>
