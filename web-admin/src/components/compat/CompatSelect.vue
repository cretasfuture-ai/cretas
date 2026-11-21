<script setup lang="ts" generic="T extends string | number | boolean | (string | number | boolean)[] | null | undefined">
import { computed, useSlots } from 'vue';
import { slotNodes } from './vnode';
import CompatOption from './CompatOption';

type Value = string | number | boolean;
type Model = Value | Value[] | null | undefined;
const model = defineModel<T>();
const props = defineProps<{
  multiple?: boolean; disabled?: boolean; clearable?: boolean; filterable?: boolean;
  placeholder?: string; size?: 'small' | 'default' | 'large'; name?: string; id?: string;
}>();
const emit = defineEmits<{ change: [value: NonNullable<T>]; clear: [] }>();
const slots = useSlots();
const size = computed(() => props.size === 'small' ? 'sm' : props.size === 'large' ? 'lg' : 'md');
function items() {
  return slotNodes(slots.default?.() ?? []).filter(node => node.type === CompatOption).map(node => ({
    value: node.props?.value as Value, label: String(node.props?.label ?? node.props?.value ?? ''),
    disabled: 'disabled' in (node.props ?? {}) && node.props?.disabled !== false,
  }));
}
function update(value: Model) { model.value = value as T; emit('change', value as NonNullable<T>); }
function clear() { update(props.multiple ? [] : undefined); emit('clear'); }
</script>

<template>
  <div class="inline-flex min-w-0 items-center gap-1">
    <USelectMenu :id="id" :name="name" :size="size" :disabled="disabled" :search-input="Boolean(filterable)" :multiple="multiple"
      :items="items()" :model-value="model ?? undefined" value-key="value" :placeholder="placeholder"
      @update:model-value="update" />
    <UButton v-if="clearable && (Array.isArray(model) ? model.length > 0 : model != null && model !== '')"
      aria-label="清除选择" type="button" size="xs" color="neutral" variant="ghost" icon="i-lucide-x" :disabled="disabled" @click="clear" />
  </div>
</template>
