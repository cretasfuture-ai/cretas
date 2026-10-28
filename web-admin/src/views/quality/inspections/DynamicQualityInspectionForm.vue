<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { get, post } from '@/api/request';
import {
  buildDynamicAnswers,
  isDynamicFormForBatch,
  itemIsOutOfRange,
  outOfRangeDynamicItems,
  requiredDynamicItems,
  type DynamicQualityForm,
  type DynamicQualitySubmitResponse,
} from './dynamicQualityInspection';

const props = defineProps<{
  factoryId: string;
  batchId: string;
  batchNumber?: string;
  productName?: string;
  form: DynamicQualityForm;
}>();

const emit = defineEmits<{
  submitted: [response: DynamicQualitySubmitResponse];
  cancel: [];
}>();

const values = ref<Record<string, string>>({});
const currentForm = ref<DynamicQualityForm>(props.form);
const notes = ref('');
const submitting = ref(false);
const error = ref('');
const stale = ref(false);
const revision = ref(props.form.standardRevision);
const submitGuard = ref(false);
const refreshGuard = ref(false);
const requiredItems = computed(() => requiredDynamicItems(currentForm.value, values.value));
const outOfRangeItems = computed(() => outOfRangeDynamicItems(currentForm.value, values.value));

watch(() => props.form, (next) => {
  currentForm.value = next;
  revision.value = next.standardRevision;
}, { deep: true });

function messageFromError(errorValue: unknown, fallback: string): string {
  if (errorValue instanceof Error && errorValue.message) return errorValue.message;
  if (typeof errorValue === 'object' && errorValue !== null) {
    const response = (errorValue as { response?: { data?: { message?: string } } }).response;
    if (response?.data?.message) return response.data.message;
  }
  return fallback;
}

function isStaleError(errorValue: unknown, message: string): boolean {
  if (typeof errorValue === 'object' && errorValue !== null) {
    const candidate = errorValue as {
      code?: unknown;
      status?: unknown;
      response?: { status?: unknown; data?: { code?: unknown; errorCode?: unknown } };
    };
    const errorCode = candidate.code ?? candidate.response?.data?.errorCode;
    if (errorCode === 'QUALITY_STANDARD_STALE') return true;
    const status = candidate.status ?? candidate.response?.status;
    const responseCode = candidate.response?.data?.code;
    if ((status === 409 || responseCode === 409) && message.includes('质检项目已更新')) return true;
  }
  return message.includes('标准已更新') || message.includes('标准版本') || message.includes('质检项目已更新');
}

function setValue(bindingId: string, value: string | number | undefined): void {
  values.value[bindingId] = value == null ? '' : String(value);
}

async function submit(): Promise<void> {
  if (submitGuard.value) return;
  if (!isDynamicFormForBatch(currentForm.value, props.batchId)) {
    error.value = '当前批次的质检标准已失效，请关闭后重新打开';
    return;
  }
  if (requiredItems.value.length > 0) {
    error.value = `请填写必检项目：${requiredItems.value.map((item) => item.label).join('、')}`;
    return;
  }
  submitGuard.value = true;
  submitting.value = true;
  error.value = '';
  stale.value = false;
  try {
    const response = await post<DynamicQualitySubmitResponse>(
      `/${props.factoryId}/processing/batches/${encodeURIComponent(props.batchId)}/quality-inspections`,
      {
        standardRevision: revision.value,
        sampleSize: 1,
        answers: buildDynamicAnswers(currentForm.value, values.value),
        notes: notes.value || undefined,
      },
    );
    if (!response.success || response.data?.inspection?.id == null) {
      throw new Error(response.message || '服务端未返回完整的质检结果，请保留当前填写内容后重试');
    }
    emit('submitted', response.data);
  } catch (errorValue) {
    const message = messageFromError(errorValue, '提交失败，请检查标准版本后重试');
    stale.value = isStaleError(errorValue, message);
    error.value = message;
    ElMessage.error(message);
  } finally {
    submitGuard.value = false;
    submitting.value = false;
  }
}

async function refreshStandard(): Promise<void> {
  if (refreshGuard.value) return;
  refreshGuard.value = true;
  try {
    const response = await get<DynamicQualityForm>(
      `/${props.factoryId}/processing/batches/${encodeURIComponent(props.batchId)}/quality-form`,
    );
    if (!response.success || !response.data || !isDynamicFormForBatch(response.data, props.batchId)) {
      throw new Error(response.message || '当前批次质检标准读取失败，请重试');
    }
    currentForm.value = response.data;
    revision.value = response.data.standardRevision;
    error.value = '';
    stale.value = false;
  } catch (errorValue) {
    const message = messageFromError(errorValue, '刷新标准失败，当前填写内容已保留');
    error.value = message;
    ElMessage.error(message);
  } finally {
    refreshGuard.value = false;
  }
}
</script>

<template>
  <div class="dynamic-quality-form">
    <el-alert
      :title="`动态质检标准 · ${currentForm.standardRevision}`"
      type="info"
      :closable="false"
      show-icon
    >
      <template #default>
        <span>{{ props.productName || currentForm.productName || '当前批次' }} · {{ props.batchNumber || props.batchId }}</span>
        <span class="dynamic-hint">填写实际测量值；超出范围会按真实结果记录为不合格，不会被前端拦截。</span>
      </template>
    </el-alert>
    <div class="sample-size-note">本次记录 1 份样本，按当前动态质检表提交。</div>

    <el-form label-position="top" class="dynamic-fields" @submit.prevent="submit">
      <el-form-item
        v-for="(item, index) in currentForm.items"
        :key="item.bindingId"
        :label="`${index + 1}. ${item.label}${item.effectiveIsRequired ? '（必检）' : ''}`"
        :required="item.effectiveIsRequired === true"
      >
        <div class="dynamic-field-meta">
          <span>{{ item.unit || '无单位' }}</span>
          <span v-if="item.valueType === 'RANGE'">{{ item.effectiveMinValue ?? '无下限' }} ~ {{ item.effectiveMaxValue ?? '无上限' }}</span>
          <span v-if="item.effectiveStandardValue">标准 {{ item.effectiveStandardValue }}</span>
        </div>
        <el-radio-group
          v-if="item.valueType === 'BOOLEAN'"
          :model-value="values[item.bindingId]"
          @update:model-value="setValue(item.bindingId, $event)"
        >
          <el-radio-button label="true">是</el-radio-button>
          <el-radio-button label="false">否</el-radio-button>
        </el-radio-group>
        <el-input
          v-else
          :model-value="values[item.bindingId] || ''"
          :type="item.valueType === 'RANGE' ? 'number' : 'text'"
          :placeholder="item.valueType === 'RANGE' ? '填写实测值' : '填写检查结果'"
          clearable
          @update:model-value="setValue(item.bindingId, $event)"
        />
        <div v-if="itemIsOutOfRange(item, values[item.bindingId])" class="range-warning">
          实测值超出标准范围，提交后将记录为不合格。
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="notes" type="textarea" :rows="2" placeholder="备注（可选）" />
      </el-form-item>
    </el-form>

    <el-alert v-if="requiredItems.length" :title="`还有 ${requiredItems.length} 项必检内容未填写`" type="warning" :closable="false" />
    <el-alert v-if="outOfRangeItems.length" title="存在超出范围的实测值，提交后会记录为不合格" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <div class="dynamic-actions">
      <el-button @click="emit('cancel')">取消</el-button>
      <el-button v-if="stale" type="warning" @click="refreshStandard">刷新标准（保留已填写答案）</el-button>
      <el-button type="primary" :loading="submitting" :disabled="submitting" @click="submit">提交质检结果</el-button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.dynamic-quality-form { padding: 4px 0; }
.dynamic-hint { display: block; margin-top: 6px; color: var(--text-color-secondary, #606266); }
.sample-size-note { margin-top: 12px; padding: 8px 12px; color: var(--text-color-primary, #303133); background: var(--el-color-info-light-9); border-radius: 4px; font-size: 13px; }
.dynamic-fields { margin-top: 16px; }
.dynamic-field-meta { display: flex; gap: 12px; margin-bottom: 6px; color: var(--text-color-secondary, #909399); font-size: 12px; }
.range-warning { margin-top: 6px; color: var(--el-color-danger); font-size: 12px; }
.dynamic-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
