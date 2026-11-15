<script setup lang="ts">
/**
 * 采购单位下拉（Nuxt UI）：供应商详情抽屉的关系/规格弹窗是 UModal，里面不能放 Element 的 UnitSelect
 * （Element 下拉放进 Nuxt UI 弹层点不了）。
 *
 * 只做「从已给定的采购单位里选」：不带「新增单位」入口 —— 抽屉里原来传的就是 usage-scope=PURCHASE_QUANTITY，
 * 该口径下 UnitSelect 本来就不出现新增入口（canCreate 要求 usageScope 为空）。
 * 选值口径与 UnitSelect 一致：按别名查重、回写 canonical 单位码。
 */
import { computed, ref } from 'vue';
import {
  canonicalSystemUnitCode,
  findDuplicateUnit,
  normalizeUnitIdentity,
  unitAliases,
  type SystemUnit,
} from '@/api/systemUnits';

const props = withDefaults(defineProps<{
  modelValue?: string | null;
  options: SystemUnit[];
  placeholder?: string;
  disabled?: boolean;
}>(), { modelValue: '', placeholder: '请选择单位', disabled: false });

const emit = defineEmits<{ 'update:modelValue': [value: string]; change: [value: string] }>();

const query = ref('');
const activeUnits = computed(() => props.options.filter((unit) => unit.isActive !== false && unit.unitName?.trim()));
const items = computed(() => {
  const needle = normalizeUnitIdentity(query.value);
  const list = needle
    ? activeUnits.value.filter((unit) => unitAliases(unit).some((alias) => normalizeUnitIdentity(alias).includes(needle)))
    : activeUnits.value;
  return list.map((unit) => ({
    label: unit.unitSymbol && unit.unitSymbol !== unit.unitName ? `${unit.unitName}（${unit.unitSymbol}）` : unit.unitName,
    value: unit.unitCode,
  }));
});
const selected = computed(() => {
  const current = findDuplicateUnit(activeUnits.value, [props.modelValue]);
  return canonicalSystemUnitCode(current?.unitCode || props.modelValue);
});

function pick(value: string | undefined): void {
  const canonical = canonicalSystemUnitCode(findDuplicateUnit(activeUnits.value, [value])?.unitCode || value);
  emit('update:modelValue', canonical);
  emit('change', canonical);
  query.value = '';
}
</script>

<template>
  <USelectMenu
    :model-value="selected || undefined"
    v-model:search-term="query"
    :items="items"
    value-key="value"
    :placeholder="placeholder"
    :disabled="disabled"
    ignore-filter
    :search-input="{ placeholder: '搜索…' }"
    class="w-full"
    @update:model-value="pick"
  />
</template>
