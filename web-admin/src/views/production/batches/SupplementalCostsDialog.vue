<template>
  <el-dialog
    v-model="visible"
    title="补录设备和其他费用"
    width="480px"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    :before-close="closeWhenIdle"
    destroy-on-close
  >
    <div class="cost-context">
      <strong>当前批次：{{ batchNumber || productionPlanId }}</strong><br>
      只补录这两项；原料、人工、包材沿用已记录实际数据。留空保持原值，确认无费用请填 0。
    </div>

    <el-alert v-if="savedParamsKey !== null && !saving" type="warning" :closable="false" show-icon
      :title="costsSaved ? '费用已保存，核算尚未完成。直接重试即可，无需重复保存。' : '金额已修改，请保存新金额并重新核算。'"
      class="dialog-error" />

    <el-alert
      v-if="errorMessage"
      :title="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="dialog-error"
    />

    <el-form label-width="110px" @submit.prevent="submit">
      <el-form-item label="设备成本">
        <el-input
          v-model="equipmentInput"
          :disabled="saving"
          type="number"
          min="0"
          step="0.01"
          inputmode="decimal"
          placeholder="留空保持原值"
        >
          <template #append>元</template>
        </el-input>
      </el-form-item>
      <el-form-item label="其他成本">
        <el-input
          v-model="otherInput"
          :disabled="saving"
          type="number"
          min="0"
          step="0.01"
          inputmode="decimal"
          placeholder="留空保持原值"
        >
          <template #append>元</template>
        </el-input>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="saving" @click="visible = false">{{ savedParamsKey !== null ? '稍后核算' : '取消' }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">
        {{ costsSaved ? '重试重新核算' : '保存并重新核算' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { put } from '@/api/request';
import { recalculateSettledPlanCosts } from '@/api/productionPlan';

interface Props {
  modelValue: boolean;
  factoryId: string;
  productionPlanId: string;
  batchNumber?: string;
  contextKey?: string;
  equipmentCost?: number | null;
  otherCost?: number | null;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'saved'): void;
  (event: 'costs-updated'): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => {
    // A failed recalculation does not undo the separate, successful save.
    if (!value && savedParamsKey.value !== null) emit('costs-updated');
    emit('update:modelValue', value);
  },
});

const equipmentInput = ref('');
const otherInput = ref('');
const saving = ref(false);
const errorMessage = ref('');
const savedParamsKey = ref<string | null>(null);
const costsSaved = computed(() => savedParamsKey.value !== null && savedParamsKey.value === paramsKey());
const identity = () => JSON.stringify([props.factoryId, props.productionPlanId, props.contextKey]);
let generation = 0;
let disposed = false;

function inputValue(value: number | null | undefined): string {
  return value == null ? '' : String(value);
}

function resetForm(): void {
  equipmentInput.value = inputValue(props.equipmentCost);
  otherInput.value = inputValue(props.otherCost);
  errorMessage.value = '';
  savedParamsKey.value = null;
}

watch(() => [props.modelValue, identity()] as const, ([opened, current], previous) => {
  ++generation;
  if (previous && current !== previous[1] && opened) emit('update:modelValue', false);
  if (opened) resetForm();
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; ++generation; });

function closeWhenIdle(done: () => void): void {
  if (!saving.value) done();
}

function parseAmount(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const amount = Number(trimmed);
  return Number.isFinite(amount) && amount >= 0 ? amount : NaN;
}

function paramsKey(): string {
  return JSON.stringify([parseAmount(equipmentInput.value), parseAmount(otherInput.value)]);
}

async function submit(): Promise<void> {
  if (saving.value || disposed || !props.modelValue || !props.factoryId || !props.productionPlanId) return;
  const requestGeneration = generation;
  const requestIdentity = identity();
  const { factoryId, productionPlanId } = props;
  const isCurrent = () => !disposed && props.modelValue && requestGeneration === generation && requestIdentity === identity();
  errorMessage.value = '';
  saving.value = true;
  let stage: 'save' | 'recalculate' = 'save';
  try {
    const equipment = parseAmount(equipmentInput.value);
    const other = parseAmount(otherInput.value);
    if (Number.isNaN(equipment) || Number.isNaN(other)) {
      errorMessage.value = '费用必须是大于等于 0 的有效金额。';
      return;
    }
    if (equipment == null && other == null) {
      errorMessage.value = '请至少填写设备成本或其他成本；确认无费用请填 0。';
      return;
    }
    const params: Record<string, number> = {};
    if (equipment != null) params.equipmentCost = equipment;
    if (other != null) params.otherCost = other;
    if (!costsSaved.value) {
      const submittedKey = paramsKey();
      const response = await put<{ settledCostsRecalculated?: boolean }>(
        `/${factoryId}/production-plans/${encodeURIComponent(productionPlanId)}/costs`,
        undefined,
        { params },
      );
      if (!isCurrent()) return;
      if (!response.success) {
        errorMessage.value = response.message || '成本补录失败';
        return;
      }
      if (response.data?.settledCostsRecalculated === true) {
        savedParamsKey.value = null;
        emit('saved');
        visible.value = false;
        return;
      }
      savedParamsKey.value = submittedKey;
    }

    stage = 'recalculate';
    const recalculation = await recalculateSettledPlanCosts(factoryId, productionPlanId);
    if (!isCurrent()) return;
    if (!recalculation.success || !recalculation.data) {
      errorMessage.value = `费用已保存，重新核算失败：${recalculation.message || '服务未返回结果'}`;
      return;
    }

    savedParamsKey.value = null;
    emit('saved');
    visible.value = false;
  } catch (error: unknown) {
    if (!isCurrent()) return;
    const failure = error as {
      message?: string;
      response?: { data?: { message?: string } };
    };
    const message = failure.response?.data?.message || failure.message || '服务暂时不可用';
    errorMessage.value = stage === 'recalculate'
      ? `费用已保存，重新核算失败：${message}`
      : `${savedParamsKey.value !== null ? '新金额保存失败，原费用仍待核算' : '成本补录失败'}：${message}`;
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.cost-context {
  margin-bottom: 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.dialog-error {
  margin-bottom: 16px;
}
</style>
