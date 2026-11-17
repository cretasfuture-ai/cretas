<script setup lang="ts">
/**
 * 销售模块的确认 / 带输入的确认弹窗 (取代 ElMessageBox.confirm / prompt)。
 * 用法: 模板放一个 <SalesConfirmModal ref="confirmRef" />, 代码里
 *   const value = await confirmRef.value!.ask({ title, message, input?: {...} });
 *   if (value === null) return;   // 取消 / 点遮罩 / Esc
 * 确认返回输入值 (无输入框时返回空串)。组件全部是 Nuxt UI, 弹层里不放 Element 控件。
 */
import { ref } from 'vue';

export interface SalesAskInput {
  label: string;
  placeholder?: string;
  initial?: string;
  required?: boolean;
  pattern?: RegExp;
  errorText?: string;
  type?: 'text' | 'textarea';
}
export interface SalesAskOptions {
  title: string;
  message: string;
  confirmText?: string;
  danger?: boolean;
  input?: SalesAskInput;
}

const open = ref(false);
const opts = ref<SalesAskOptions>({ title: '', message: '' });
const value = ref('');
const error = ref('');
let resolver: ((v: string | null) => void) | null = null;

function settle(v: string | null) {
  const r = resolver;
  resolver = null;
  open.value = false;
  r?.(v);
}

function ask(o: SalesAskOptions): Promise<string | null> {
  if (resolver) settle(null);
  opts.value = o;
  value.value = o.input?.initial ?? '';
  error.value = '';
  open.value = true;
  return new Promise((resolve) => { resolver = resolve; });
}

function onConfirm() {
  const input = opts.value.input;
  if (input) {
    const v = value.value.trim();
    if (input.required && !v) { error.value = input.errorText || `${input.label}必填`; return; }
    if (input.pattern && !input.pattern.test(v)) { error.value = input.errorText || `${input.label}格式不正确`; return; }
    settle(v);
    return;
  }
  settle('');
}

function onOpenChange(next: boolean) {
  if (!next && resolver) settle(null);
  else open.value = next;
}

defineExpose({ ask });
</script>

<template>
  <UModal
    :open="open"
    :title="opts.title"
    :description="opts.message"
    :ui="{ content: 'max-w-[460px]', description: 'sr-only', footer: 'justify-end' }"
    @update:open="onOpenChange"
  >
    <template #body>
      <p class="msg" data-testid="sales-confirm-message">{{ opts.message }}</p>
      <UFormField v-if="opts.input" :label="opts.input.label" :required="opts.input.required" :error="error || undefined" class="mt-3">
        <UTextarea v-if="opts.input.type === 'textarea'" v-model="value" :rows="3" :placeholder="opts.input.placeholder" class="w-full" data-testid="sales-confirm-input" />
        <UInput v-else v-model="value" :placeholder="opts.input.placeholder" class="w-full" data-testid="sales-confirm-input" @keyup.enter="onConfirm" />
      </UFormField>
    </template>
    <template #footer>
      <UButton color="neutral" variant="outline" data-testid="sales-confirm-cancel" @click="settle(null)">取消</UButton>
      <UButton :color="opts.danger ? 'error' : 'primary'" data-testid="sales-confirm-ok" @click="onConfirm">{{ opts.confirmText || '确定' }}</UButton>
    </template>
  </UModal>
</template>

<style scoped>
.msg { margin: 0; color: var(--cs-text); font-size: 13px; line-height: 1.6; }
</style>
