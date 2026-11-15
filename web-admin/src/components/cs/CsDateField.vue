<script setup lang="ts">
/**
 * 日期选择（Nuxt UI）：UInput 触发 + UPopover + UCalendar，绑定值是 `YYYY-MM-DD` 字符串（与原 el-date-picker value-format 一致）。
 * 取代浏览器原生 `<input type="date">`（长相和其它控件不一致）。
 */
import { computed } from 'vue';
import { CalendarDate } from '@internationalized/date';

const props = defineProps<{ modelValue?: string | null; placeholder?: string; clearable?: boolean }>();
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>();

function parse(v?: string | null): CalendarDate | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v ?? '');
  return m ? new CalendarDate(Number(m[1]), Number(m[2]), Number(m[3])) : undefined;
}
const calendarValue = computed({
  get: () => parse(props.modelValue),
  set: (d: CalendarDate | undefined) => {
    emit('update:modelValue', d ? `${String(d.year).padStart(4, '0')}-${String(d.month).padStart(2, '0')}-${String(d.day).padStart(2, '0')}` : '');
  },
});
</script>

<template>
  <UPopover :content="{ align: 'start' }">
    <UInput
      :model-value="modelValue ?? ''"
      readonly
      icon="i-lucide-calendar"
      :placeholder="placeholder ?? '选择日期'"
      class="w-full cursor-pointer"
    >
      <template v-if="clearable && modelValue" #trailing>
        <UButton color="neutral" variant="link" size="xs" icon="i-lucide-x" aria-label="清除日期" @click.stop="emit('update:modelValue', '')" />
      </template>
    </UInput>
    <template #content>
      <UCalendar v-model="calendarValue" class="p-2" />
    </template>
  </UPopover>
</template>
