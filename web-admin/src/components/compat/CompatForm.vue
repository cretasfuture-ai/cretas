<script setup lang="ts">
import { computed, provide, reactive, toRaw, watch } from 'vue';
import Schema, { type Rules, type RuleItem } from 'async-validator';
import { formKey, type FieldPath, type FormCallback, type FormErrors, type FormItem } from './formContext';
import { fieldValue } from './vnode';

type TriggerRule = RuleItem & { trigger?: string | string[] };
const props = withDefaults(defineProps<{
  model?: object; rules?: object; labelWidth?: string | number;
  labelPosition?: 'left' | 'right' | 'top'; inline?: boolean; disabled?: boolean;
  size?: string; validateOnRuleChange?: boolean;
}>(), { validateOnRuleChange: true });
const items = reactive(new Set<FormItem>());
const errors = reactive<Record<string, string>>({});
const state = computed(() => (props.model ?? {}) as Record<string, unknown>);
const initial = new Map<string, unknown>();
const revision = new Map<string, number>();
const orientation = computed(() => props.labelPosition === 'top' ? 'vertical' as const : 'horizontal' as const);
const list = (value: unknown): TriggerRule[] => value ? (Array.isArray(value) ? value : [value]) as TriggerRule[] : [];
const clone = (value: unknown): unknown => {
  if (value instanceof Date) return new Date(value.getTime());
  if (Array.isArray(value)) return value.map(clone);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(toRaw(value)).map(([key, entry]) => [key, clone(entry)]));
  return value;
};
const normalize = (path: FieldPath | undefined) => Array.isArray(path) ? path.join('.') : path;
const selected = (paths?: FieldPath) => {
  const names = Array.isArray(paths) ? new Set([...paths, paths.join('.')]) : paths ? new Set([paths]) : null;
  return [...items].filter(item => item.name() && (!names || names.has(item.name())));
};

watch(() => [...items].map(item => item.name()), names => {
  for (const name of names) {
    if (name && !initial.has(name)) initial.set(name, clone(fieldValue(state.value, name)));
  }
}, { flush: 'sync' });

async function validateField(paths?: FieldPath, callback?: FormCallback, trigger?: string): Promise<boolean> {
  const failures: FormErrors = {};
  await Promise.all(selected(paths).map(async item => {
    const name = item.name();
    const stamp = (revision.get(name) ?? 0) + 1;
    revision.set(name, stamp);
    const formRules = fieldValue(props.rules, name) ?? (props.rules as Record<string, unknown> | undefined)?.[name];
    const rules = [...list(formRules), ...list(item.rules())].filter(rule =>
      !trigger || !rule.trigger || (Array.isArray(rule.trigger) ? rule.trigger.includes(trigger) : rule.trigger === trigger))
      .map(({ trigger: _trigger, ...rule }) => rule);
    if (item.required()) rules.push({ required: true });
    if (!rules.length && trigger) return;
    const schema = new Schema({ [name]: rules } as Rules);
    try {
      await schema.validate({ [name]: fieldValue(state.value, name) }, { firstFields: true });
      if (revision.get(name) === stamp) delete errors[name];
    } catch (error) {
      const fields = (error as { fields?: FormErrors }).fields;
      if (!fields?.[name]?.length) throw error;
      failures[name] = fields[name];
      if (revision.get(name) === stamp) errors[name] = fields[name][0]?.message ?? '输入不符合要求';
    }
  }));
  const valid = Object.keys(failures).length === 0;
  if (callback) await callback(valid, valid ? undefined : failures);
  else if (!valid && !trigger) throw failures;
  return valid;
}

function clearValidate(paths?: FieldPath) {
  for (const { name } of selected(paths)) {
    revision.set(name(), (revision.get(name()) ?? 0) + 1);
    delete errors[name()];
  }
}

function resetFields(paths?: FieldPath) {
  for (const item of selected(paths)) {
    const path = item.name().replace(/\[(\d+)\]/g, '.$1').split('.');
    const key = path.pop()!;
    const container = path.length ? fieldValue(state.value, path.join('.')) : state.value;
    if (container && typeof container === 'object') {
      (container as Record<string, unknown>)[key] = clone(initial.get(item.name()));
    }
  }
  clearValidate(paths);
}

provide(formKey, { items, errors, labelWidth: () => props.labelWidth, orientation: () => orientation.value,
  validateField: (path, callback, trigger) => validateField(normalize(path), callback, trigger) });
watch(() => props.rules, () => {
  if (props.validateOnRuleChange) void validateField(undefined, () => {});
}, { deep: true });
function scrollToField(path: FieldPath, options?: ScrollIntoViewOptions | boolean) {
  selected(normalize(path))[0]?.element()?.scrollIntoView(options);
}
defineExpose({ validate: (callback?: FormCallback) => validateField(undefined, callback), validateField, resetFields, clearValidate, scrollToField });
</script>

<template>
  <UForm :state="state" :disabled="disabled" :class="inline ? 'flex flex-wrap items-start gap-x-3' : 'space-y-3'">
    <slot />
  </UForm>
</template>
