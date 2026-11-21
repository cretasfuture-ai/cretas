<script setup lang="ts">
import { computed, ref, watch } from 'vue';

type Value = Date | string | number;
type Model = Value | Value[] | null | undefined;
const model = defineModel<Model>();
const props = withDefaults(defineProps<{
  type?: 'date' | 'datetime' | 'daterange' | 'datetimerange' | 'month' | 'monthrange' | 'year';
  valueFormat?: string; format?: string; disabled?: boolean; clearable?: boolean;
  placeholder?: string; startPlaceholder?: string; endPlaceholder?: string; rangeSeparator?: string;
  name?: string | string[]; id?: string | string[]; size?: 'small' | 'default' | 'large';
}>(), { type: 'date', clearable: true, rangeSeparator: '至' });
const emit = defineEmits<{ change: [value: Model]; clear: [] }>();
const range = computed(() => props.type.endsWith('range'));
const inputType = computed(() => props.type.startsWith('datetime') ? 'datetime-local' : props.type.startsWith('month') ? 'month' : props.type === 'year' ? 'number' : 'date');
const nativeFormat = computed(() => inputType.value === 'datetime-local' ? 'YYYY-MM-DDTHH:mm:ss' : inputType.value === 'month' ? 'YYYY-MM' : inputType.value === 'number' ? 'YYYY' : 'YYYY-MM-DD');
const size = computed(() => props.size === 'small' ? 'sm' : props.size === 'large' ? 'lg' : 'md');
const fields = ref<string[]>(['', '']);
const tokens = /YYYY|MM|DD|HH|mm|ss/g;
function formatDate(date: Date, pattern: string) {
  const parts: Record<string, number> = { YYYY: date.getFullYear(), MM: date.getMonth() + 1, DD: date.getDate(),
    HH: date.getHours(), mm: date.getMinutes(), ss: date.getSeconds() };
  return pattern.replace(tokens, token => String(parts[token]).padStart(token === 'YYYY' ? 4 : 2, '0'));
}
function parseDate(input: string, pattern: string): Date | undefined {
  const parts: Record<string, number> = { YYYY: 1970, MM: 1, DD: 1, HH: 0, mm: 0, ss: 0 };
  let position = 0;
  const expression = [...pattern.matchAll(tokens)];
  for (const token of expression) {
    const width = token[0].length;
    const digits = input.slice(token.index, token.index + width);
    if (/^\d+$/.test(digits)) parts[token[0]] = Number(digits);
    else if (token.index < input.length || !['ss', 'mm'].includes(token[0])) return;
    position = token.index + width;
  }
  if (position === 0) return;
  const date = new Date(parts.YYYY, parts.MM - 1, parts.DD, parts.HH, parts.mm, parts.ss);
  if (date.getDate() === parts.DD && date.getMonth() + 1 === parts.MM) return date;
}
function display(value: Value | undefined) {
  if (value === undefined || value === '') return '';
  const date = value instanceof Date ? value : (typeof value === 'number' || props.valueFormat === 'x') ? new Date(Number(value))
    : parseDate(value, props.valueFormat ?? nativeFormat.value);
  return date && Number.isFinite(date.getTime()) ? formatDate(date, nativeFormat.value) : '';
}
watch([model, nativeFormat, () => props.valueFormat], () => {
  const current = Array.isArray(model.value) ? model.value : [model.value ?? undefined];
  fields.value = [display(current[0]), display(current[1])];
}, { immediate: true });

function update(index: number, raw: string | number | null | undefined) {
  const value = String(raw ?? '');
  fields.value[index] = value;
  const active = fields.value.slice(0, range.value ? 2 : 1);
  if (active.every(item => !item)) {
    clear();
    return;
  }
  if (active.some(item => !item)) return;
  const dates = active.map(item => parseDate(item, nativeFormat.value));
  if (dates.some(date => !date)) return;
  const result = (dates as Date[]).sort((a, b) => a.getTime() - b.getTime()).map(date => {
    if (!props.valueFormat) return date;
    if (props.valueFormat === 'x') return date.getTime();
    return formatDate(date, props.valueFormat);
  });
  model.value = range.value ? result : result[0];
  emit('change', model.value);
}
function clear() {
  fields.value = ['', ''];
  model.value = null;
  emit('change', null);
  emit('clear');
}
function at(value: string | string[] | undefined, index: number) { return Array.isArray(value) ? value[index] : index === 0 ? value : undefined; }
</script>

<template>
  <div class="inline-flex max-w-full flex-wrap items-center gap-2">
    <UInput :id="at(id, 0)" :name="at(name, 0)" :size="size" :type="inputType" :disabled="disabled"
      :model-value="fields[0]" :placeholder="range ? startPlaceholder : placeholder"
      :aria-label="(range ? startPlaceholder : placeholder) || (range ? '开始日期' : '日期')"
      :step="inputType === 'datetime-local' ? 1 : undefined" :max="range ? fields[1] || undefined : undefined"
      @update:model-value="(value: string | number | null | undefined) => update(0, value)" />
    <template v-if="range">
      <span>{{ rangeSeparator }}</span>
      <UInput :id="at(id, 1)" :name="at(name, 1)" :size="size" :type="inputType" :disabled="disabled"
        :model-value="fields[1]" :placeholder="endPlaceholder" :aria-label="endPlaceholder || '结束日期'"
        :step="inputType === 'datetime-local' ? 1 : undefined" :min="fields[0] || undefined"
        @update:model-value="(value: string | number | null | undefined) => update(1, value)" />
    </template>
    <UButton v-if="clearable && fields.some(Boolean)" type="button" aria-label="清除日期" size="xs"
      icon="i-lucide-x" color="neutral" variant="ghost" :disabled="disabled" @click="clear" />
  </div>
</template>
