<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { BlacklakePeriodBasis, BlacklakePeriodKind, BlacklakePeriodQuery } from '@/api/smartbi/blacklake';
import { periodForKind, periodLabel, yesterdayInBeijing } from './blacklakePeriod';

const props = withDefaults(defineProps<{ modelValue: BlacklakePeriodQuery | null; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ (event: 'update:modelValue', value: BlacklakePeriodQuery | null): void; (event: 'change', value: BlacklakePeriodQuery | null): void }>();
const kinds: { value: BlacklakePeriodKind; label: string }[] = [
  { value: 'day', label: '日' }, { value: 'week', label: '周' }, { value: 'month', label: '月' },
  { value: 'quarter', label: '季' }, { value: 'year', label: '年' }, { value: 'custom', label: '自定义' },
];
const kind = ref<BlacklakePeriodKind>(props.modelValue?.kind || 'day');
const basis = ref<BlacklakePeriodBasis>(props.modelValue?.basis || 'completed');
const startDate = ref(props.modelValue?.start_date || yesterdayInBeijing());
const endDate = ref(props.modelValue?.end_date || yesterdayInBeijing());
const dateError = ref('');
const allHistory = computed(() => props.modelValue === null);

watch(() => props.modelValue, value => {
  if (value === null) return;
  kind.value = value.kind; basis.value = value.basis; startDate.value = value.start_date; endDate.value = value.end_date; dateError.value = '';
});

function publish(value: BlacklakePeriodQuery | null) {
  emit('update:modelValue', value);
  emit('change', value);
}

function selectKind(value: BlacklakePeriodKind) {
  kind.value = value;
  if (value === 'custom') {
    if (!validateDateRange()) return;
    publish({ start_date: startDate.value, end_date: endDate.value, basis: basis.value, kind: value });
    return;
  }
  const next = periodForKind(value, endDate.value || yesterdayInBeijing());
  next.basis = basis.value;
  startDate.value = next.start_date; endDate.value = next.end_date;
  publish(next);
}

function validateDateRange(): boolean {
  if (!startDate.value || !endDate.value) {
    dateError.value = '请填写开始日期和结束日期。';
    return false;
  }
  const validDate = (value: string) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(`${value}T12:00:00Z`);
    return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
  };
  if (!validDate(startDate.value) || !validDate(endDate.value)) {
    dateError.value = '日期格式无效，请重新选择。';
    return false;
  }
  if (startDate.value > endDate.value) {
    dateError.value = '开始日期不能晚于结束日期。';
    return false;
  }
  dateError.value = '';
  return true;
}

function updateRange() {
  if (!validateDateRange()) return;
  if (kind.value !== 'custom') {
    const natural = periodForKind(kind.value, endDate.value);
    if (natural.start_date !== startDate.value || natural.end_date !== endDate.value) kind.value = 'custom';
  }
  publish({ start_date: startDate.value, end_date: endDate.value, basis: basis.value, kind: kind.value });
}

function selectBasis(value: BlacklakePeriodBasis) {
  basis.value = value;
  if (!allHistory.value) publish({ start_date: startDate.value, end_date: endDate.value, basis: value, kind: kind.value });
}

function selectAllHistory() {
  dateError.value = '';
  publish(null);
}
</script>

<template>
  <section class="period-controls" aria-label="分析周期">
    <div class="period-controls__top"><div><span class="period-kicker">分析周期</span><strong>{{ periodLabel(modelValue) }}</strong></div><button type="button" class="all-history" :class="{ active: allHistory }" :disabled="disabled" @click="selectAllHistory">全部已采集</button></div>
    <div class="period-controls__row"><div class="period-kind" role="tablist" aria-label="周期类型"><button v-for="item in kinds" :key="item.value" type="button" :class="{ active: !allHistory && kind === item.value }" :disabled="disabled" @click="selectKind(item.value)">{{ item.label }}</button></div><label>工单日期<select :value="basis" :disabled="disabled || allHistory" @change="selectBasis(($event.target as HTMLSelectElement).value as BlacklakePeriodBasis)"><option value="completed">实际完工日期</option><option value="created">工单创建日期</option></select></label></div>
    <div v-if="!allHistory" class="period-controls__dates"><label>开始日期<input v-model="startDate" type="date" :disabled="disabled" @change="updateRange"></label><span>至</span><label>结束日期<input v-model="endDate" type="date" :disabled="disabled" @change="updateRange"></label></div>
    <p v-if="dateError" class="period-controls__error" role="alert">{{ dateError }}</p>
  </section>
</template>

<style scoped>
.period-controls{padding:16px 18px;border:1px solid #e5eaf1;border-radius:10px;background:#fff;box-shadow:0 5px 24px rgb(27 47 78 / 4%)}.period-controls__top,.period-controls__row,.period-controls__dates{display:flex;align-items:center;justify-content:space-between;gap:14px}.period-controls__top strong,.period-controls__top small{display:block}.period-controls__top strong{margin:3px 0;color:#1a293d;font-size:17px}.period-controls__top small,.period-kicker,.period-controls__dates small{color:#8491a3;font-size:11px}.period-kicker{display:block;letter-spacing:1px}.all-history,.period-kind button{border:1px solid #d4deea;border-radius:6px;background:#fff;color:#41536a;cursor:pointer;font:inherit}.all-history{padding:8px 12px;font-size:12px}.all-history.active,.period-kind button.active{border-color:#1b65a8;background:#eaf2fa;color:#1b65a8;font-weight:600}.period-controls__row{justify-content:flex-start;margin-top:15px}.period-kind{display:flex;gap:4px}.period-kind button{min-width:38px;padding:6px 9px;font-size:12px}.period-controls label{display:flex;align-items:center;gap:7px;color:#8491a3;font-size:11px}.period-controls select,.period-controls input{padding:7px 8px;border:1px solid #d4deea;border-radius:6px;color:#41536a;background:#fff;font:inherit;font-size:12px}.period-controls__dates{justify-content:flex-start;margin-top:13px}.period-controls__dates span{color:#8491a3}.period-controls__dates small{margin-left:4px}.period-controls__error{margin:8px 0 0;color:#b42318;font-size:12px}@media(max-width:700px){.period-controls__top,.period-controls__row,.period-controls__dates{align-items:stretch;flex-direction:column}.period-controls__row{align-items:flex-start}.period-controls__dates{align-items:flex-start}.period-controls__dates label{width:100%;justify-content:space-between}.period-controls__dates input{width:170px}.all-history{align-self:flex-start}}
</style>
