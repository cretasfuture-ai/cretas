<script setup lang="ts">
import { ref, watch } from 'vue';
import { parseOrderDeliveryDate } from './orderDeliveryDate';

const props = defineProps<{ modelValue: string }>();
const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void;
  (event: 'change', value: string): void;
}>();
const text = ref(props.modelValue);
const error = ref('');
watch(() => props.modelValue, value => { text.value = value; error.value = ''; });

function accept(value: string) {
  text.value = value;
  error.value = '';
  if (value !== props.modelValue) {
    emit('update:modelValue', value);
    emit('change', value);
  }
}
function commitText() {
  const parsed = parseOrderDeliveryDate(text.value);
  if (parsed === null) {
    error.value = '日期无效，请输入 YYYYMMDD 或 YYYY-MM-DD';
    return false;
  }
  accept(parsed);
  return true;
}
function chooseDate(value: string | null) { accept(value || ''); }
defineExpose({ validate: commitText });
</script>

<template>
  <div class="order-delivery-date">
    <div class="date-controls">
      <el-input v-model="text" aria-label="交货日期" placeholder="YYYYMMDD 或 YYYY-MM-DD"
        :aria-invalid="Boolean(error)" @change="commitText" @keydown.enter.prevent="commitText" />
      <el-date-picker :model-value="modelValue" type="date" value-format="YYYY-MM-DD"
        :editable="false" :clearable="false" aria-label="从日历选择交货日期"
        class="calendar-picker" @update:model-value="chooseDate" />
    </div>
    <div class="date-feedback"><span v-if="error" class="date-error" role="alert">{{ error }}</span></div>
  </div>
</template>

<style scoped>
.order-delivery-date { width: 100%; }
.date-controls { display: flex; gap: 4px; }
.date-controls > .el-input { flex: 1; min-width: 0; }
.date-controls :deep(.calendar-picker) { flex: 0 0 36px; width: 36px; }
.date-controls :deep(.calendar-picker .el-input__inner) { width: 0; padding: 0; }
.date-controls :deep(.calendar-picker .el-input__prefix-inner > :last-child) { margin-right: 0; }
.date-error { display: block; color: var(--el-color-danger); font-size: 12px; line-height: 20px; }
.date-feedback { min-height: 20px; line-height: 20px; }
</style>
