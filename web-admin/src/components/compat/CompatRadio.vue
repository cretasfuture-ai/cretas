<script setup lang="ts">
type Value = string | number | boolean;
const model = defineModel<Value>();
const props = defineProps<{ label?: Value; value?: Value; disabled?: boolean; name?: string }>();
const emit = defineEmits<{ change: [value: Value] }>();
function update(value: Value | undefined) {
  if (value === undefined || value === model.value) return;
  model.value = value;
  emit('change', value);
}
</script>

<template>
  <URadioGroup :name="name" :disabled="disabled" :model-value="model"
    :items="[{ value: props.value ?? props.label ?? '', label: String(props.label ?? props.value ?? '') }]"
    @update:model-value="update">
    <template v-if="$slots.default" #label><slot /></template>
  </URadioGroup>
</template>
