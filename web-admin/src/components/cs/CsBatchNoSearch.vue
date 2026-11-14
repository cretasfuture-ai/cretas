<script setup lang="ts">
// 批量单号查询: 对齐原型 Cretas System v1 的 cs-batch-* (按钮 + 浮层 + 摘要)。
// 本组件只整理输入; 「找到 N 条 / 未找到 M 个」由调用方的 onSearch 返回, 匹配与过滤在后端。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { parseBatchNos, MAX_BATCH_NOS } from '@/utils/listFilters';

export interface BatchNoSearchResult {
  found: number;
  missing: string[];
}

const props = defineProps<{
  /** 单号叫什么, 如「批次号」 */
  label?: string;
  /** 执行查询, 返回找到几条与未找到的单号; 抛错则浮层显示错误原文 */
  onSearch: (nos: string[]) => Promise<BatchNoSearchResult>;
}>();
const emit = defineEmits<{ clear: [] }>();

const open = ref(false);
const text = ref('');
const busy = ref(false);
const error = ref('');
const result = ref<BatchNoSearchResult | null>(null);
const appliedCount = ref(0);
const root = ref<HTMLElement | null>(null);

const parsed = computed(() => parseBatchNos(text.value));
const noun = computed(() => props.label || '单号');

async function run(): Promise<void> {
  if (parsed.value.nos.length === 0 || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    result.value = await props.onSearch(parsed.value.nos);
    appliedCount.value = parsed.value.nos.length;
  } catch (e) {
    result.value = null;
    error.value = e instanceof Error && e.message ? e.message : '查询失败，请重试';
  } finally {
    busy.value = false;
  }
}

function clearAll(): void {
  const hadApplied = appliedCount.value > 0;
  text.value = '';
  result.value = null;
  error.value = '';
  appliedCount.value = 0;
  if (hadApplied) emit('clear');
}

function onDocClick(ev: MouseEvent): void {
  if (open.value && root.value && !root.value.contains(ev.target as Node)) open.value = false;
}
onMounted(() => document.addEventListener('click', onDocClick));
onBeforeUnmount(() => document.removeEventListener('click', onDocClick));

defineExpose({ clearAll });
</script>

<template>
  <span ref="root" class="cs-batch-no">
    <button type="button" class="cs-batch-trigger" :aria-expanded="open" data-testid="batch-no-open" @click="open = !open">
      批量{{ noun }}
    </button>
    <span v-if="appliedCount > 0 && result" class="cs-batch-summary" data-testid="batch-no-summary">
      {{ appliedCount }} 个{{ noun }}：找到 {{ result.found }} 条<template v-if="result.missing.length">，<b>未找到 {{ result.missing.length }} 个</b></template>
      <button type="button" data-testid="batch-no-clear-chip" @click="clearAll">清除</button>
    </span>
    <div v-if="open" class="cs-batch-pop" role="dialog" :aria-label="`批量查询${noun}`">
      <label>
        <span>批量查询{{ noun }}</span>
        <textarea
          v-model="text"
          rows="6"
          :placeholder="`每行一个${noun}，也可用逗号、顿号、空格分隔；重复的自动去掉，最多 ${MAX_BATCH_NOS} 个`"
          data-testid="batch-no-text"
        />
      </label>
      <p class="cs-batch-count">已识别 {{ parsed.nos.length }} 个{{ parsed.overLimit ? `，超出 ${MAX_BATCH_NOS} 个的部分未计入，请分批查询` : '' }}</p>
      <p v-if="error" class="cs-batch-error" role="alert" data-testid="batch-no-error">{{ error }}</p>
      <div v-if="result" class="cs-batch-count" data-testid="batch-no-result">
        找到 {{ result.found }} 条；未找到 {{ result.missing.length }} 个
        <p v-if="result.missing.length" class="cs-batch-missing" data-testid="batch-no-missing">{{ result.missing.join('、') }}</p>
      </div>
      <div class="cs-batch-actions">
        <button type="button" class="cs-btn" @click="clearAll">清除条件</button>
        <button type="button" class="cs-btn" @click="open = false">关闭</button>
        <button
          type="button"
          class="cs-btn cs-btn-primary"
          :disabled="parsed.nos.length === 0 || busy"
          data-testid="batch-no-run"
          @click="run"
        >{{ busy ? '查询中…' : '查询' }}</button>
      </div>
    </div>
  </span>
</template>

<style scoped>
.cs-batch-no { position: relative; display: inline-flex; align-items: center; }
.cs-batch-trigger,
.cs-btn {
  height: var(--cs-control-height, 36px);
  padding: 0 var(--cs-space-3, 12px);
  border: 1px solid var(--cs-border, #dce3ed);
  border-radius: var(--cs-radius-sm, 6px);
  background: var(--cs-surface, #fff);
  color: var(--cs-text, #1a2332);
  font-size: var(--cs-font, 14px);
  cursor: pointer;
}
.cs-btn { height: 30px; font-size: var(--cs-font-small, 12px); }
.cs-btn-primary { border-color: var(--cs-primary, #1b65a8); background: var(--cs-primary, #1b65a8); color: #fff; }
.cs-btn-primary:disabled { opacity: .5; cursor: not-allowed; }
.cs-batch-trigger:hover,
.cs-btn:hover:not(:disabled) { border-color: var(--cs-primary, #1b65a8); }
.cs-batch-pop {
  position: absolute; left: 0; top: calc(100% + 6px); z-index: 3000; width: 320px; padding: 10px 12px;
  background: var(--cs-surface, #fff); border: 1px solid var(--cs-border, #dce3ed); border-radius: var(--cs-radius-lg, 12px);
  box-shadow: 0 12px 32px rgba(23, 43, 64, .18); font-size: .8125rem; display: grid; gap: 6px;
}
.cs-batch-pop label { display: grid; gap: 4px; }
.cs-batch-pop textarea {
  width: 100%; font: inherit; font-variant-numeric: tabular-nums; box-sizing: border-box;
  border: 1px solid var(--cs-border, #dce3ed); border-radius: var(--cs-radius-sm, 6px); padding: 6px;
}
.cs-batch-count { margin: 0; color: var(--cs-muted, #526278); font-size: var(--cs-font-small, 12px); }
.cs-batch-error { margin: 0; color: var(--cs-danger, #b42318); font-size: var(--cs-font-small, 12px); }
.cs-batch-actions { display: flex; justify-content: flex-end; gap: 6px; }
.cs-batch-missing {
  margin: 2px 0 0; color: var(--cs-danger, #b42318); font-size: var(--cs-font-small, 12px);
  max-height: 96px; overflow: auto; overflow-wrap: anywhere;
}
.cs-batch-summary { margin-left: 8px; font-size: var(--cs-font-small, 12px); color: var(--cs-muted, #526278); }
.cs-batch-summary b { color: var(--cs-warning, #885910); font-weight: 600; }
.cs-batch-summary button { border: 0; background: none; color: var(--cs-primary, #1b65a8); cursor: pointer; padding: 0 0 0 6px; font: inherit; }
</style>
