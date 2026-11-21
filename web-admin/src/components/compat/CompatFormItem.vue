<script setup lang="ts">
import { computed, inject, onScopeDispose } from 'vue';
import { formKey, type FieldPath } from './formContext';
import { length } from './vnode';

const props = defineProps<{
  prop?: FieldPath; label?: string; required?: boolean; rules?: unknown;
  error?: string; labelWidth?: string | number;
}>();
const form = inject(formKey, undefined);
const name = computed(() => Array.isArray(props.prop) ? props.prop.join('.') : props.prop ?? '');
const item = { name: () => name.value, rules: () => props.rules, required: () => Boolean(props.required) };
form?.items.add(item);
onScopeDispose(() => { form?.items.delete(item); });
const fieldError = computed(() => props.error || form?.errors[name.value]);
function validate(trigger: string) {
  if (form && name.value) void form.validateField(name.value, undefined, trigger);
}
</script>

<template>
  <UFormField :name="name || undefined" :label="label" :error="fieldError" :required="required"
    :orientation="form?.orientation() ?? 'vertical'" :style="{ '--compat-label-width': length(labelWidth ?? form?.labelWidth()) }"
    :ui="{ labelWrapper: 'min-w-[var(--compat-label-width,auto)]', container: 'min-w-0 flex-1' }"
    @focusout="validate('blur')" @change="validate('change')">
    <template v-if="$slots.label" #label><slot name="label" /></template>
    <slot />
  </UFormField>
</template>
