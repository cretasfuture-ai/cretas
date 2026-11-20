<script setup lang="ts">
// 批量单号查询: 对齐原型 Cretas System v1 的 cs-batch-* (按钮 + 浮层 + 摘要)。
// Nuxt UI: UPopover + UTextarea + UButton + --cs-* (点外部/Esc 关闭由 UPopover 负责)。
// 本组件只整理输入; 「找到 N 条 / 未找到 M 个」由调用方的 onSearch 返回, 匹配与过滤在后端。
import { computed, ref } from 'vue';
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

defineExpose({ clearAll });
</script>

<template>
  <span class="cs-batch-no">
    <UPopover v-model:open="open" :content="{ align: 'start', side: 'bottom' }">
      <UButton color="neutral" variant="outline" :aria-expanded="open" data-testid="batch-no-open">批量{{ noun }}</UButton>
      <template #content>
        <div class="cs-batch-pop" role="dialog" :aria-label="`批量查询${noun}`">
          <UFormField :label="`批量查询${noun}`">
            <UTextarea
              v-model="text"
              :rows="6"
              class="w-full"
              :placeholder="`每行一个${noun}，也可用逗号、顿号、空格分隔；重复的自动去掉，最多 ${MAX_BATCH_NOS} 个`"
              data-testid="batch-no-text"
            />
          </UFormField>
          <p class="cs-batch-count">已识别 {{ parsed.nos.length }} 个{{ parsed.overLimit ? `，超出 ${MAX_BATCH_NOS} 个的部分未计入，请分批查询` : '' }}</p>
          <p v-if="error" class="cs-batch-error" role="alert" data-testid="batch-no-error">{{ error }}</p>
          <div v-if="result" class="cs-batch-count" data-testid="batch-no-result">
            找到 {{ result.found }} 条；未找到 {{ result.missing.length }} 个
            <p v-if="result.missing.length" class="cs-batch-missing" data-testid="batch-no-missing">{{ result.missing.join('、') }}</p>
          </div>
          <div class="cs-batch-actions">
            <UButton color="neutral" variant="outline" size="sm" @click="clearAll">清除条件</UButton>
            <UButton color="neutral" variant="outline" size="sm" @click="open = false">关闭</UButton>
            <UButton size="sm" :disabled="parsed.nos.length === 0 || busy" data-testid="batch-no-run" @click="run">{{ busy ? '查询中…' : '查询' }}</UButton>
          </div>
        </div>
      </template>
    </UPopover>
    <span v-if="appliedCount > 0 && result" class="cs-batch-summary" data-testid="batch-no-summary">
      {{ appliedCount }} 个{{ noun }}：找到 {{ result.found }} 条<template v-if="result.missing.length">，<b>未找到 {{ result.missing.length }} 个</b></template>
      <UButton variant="link" size="xs" data-testid="batch-no-clear-chip" @click="clearAll">清除</UButton>
    </span>
  </span>
</template>

<style scoped>
.cs-batch-no { display: inline-flex; align-items: center; }
.cs-batch-pop { width: 320px; padding: 12px; display: grid; gap: 8px; font-size: .8125rem; }
.cs-batch-count { margin: 0; color: var(--cs-muted); font-size: var(--cs-font-small); }
.cs-batch-error { margin: 0; color: var(--cs-danger); font-size: var(--cs-font-small); }
.cs-batch-actions { display: flex; justify-content: flex-end; gap: 6px; }
.cs-batch-missing { margin: 2px 0 0; color: var(--cs-danger); font-size: var(--cs-font-small); max-height: 96px; overflow: auto; overflow-wrap: anywhere; }
.cs-batch-summary { margin-left: 8px; font-size: var(--cs-font-small); color: var(--cs-muted); }
.cs-batch-summary b { color: var(--cs-warning); font-weight: 600; }
</style>
