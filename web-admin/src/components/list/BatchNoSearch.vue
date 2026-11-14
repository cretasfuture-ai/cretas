<script setup lang="ts">
// 批量单号查询 (原型 BL21): 搜索框旁的「批量」按钮, 弹层粘贴多个单号。
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
  /** 执行查询, 返回找到几条与未找到的单号; 抛错则弹层显示错误 */
  onSearch: (nos: string[]) => Promise<BatchNoSearchResult>;
}>();
const emit = defineEmits<{ clear: [] }>();

const visible = ref(false);
const text = ref('');
const busy = ref(false);
const error = ref('');
const result = ref<BatchNoSearchResult | null>(null);
const active = ref(false);

const parsed = computed(() => parseBatchNos(text.value));
const noun = computed(() => props.label || '单号');

async function run(): Promise<void> {
  if (parsed.value.nos.length === 0 || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    result.value = await props.onSearch(parsed.value.nos);
    active.value = true;
  } catch (e) {
    result.value = null;
    error.value = e instanceof Error && e.message ? e.message : '查询失败，请重试';
  } finally {
    busy.value = false;
  }
}

function clearAll(): void {
  text.value = '';
  result.value = null;
  error.value = '';
  active.value = false;
  emit('clear');
}

defineExpose({ clearAll });
</script>

<template>
  <span class="batch-no-search">
    <el-button :type="active ? 'primary' : 'default'" data-testid="batch-no-open" @click="visible = true">
      {{ active ? `批量${noun}（${parsed.nos.length}）` : `批量${noun}` }}
    </el-button>
    <el-button v-if="active" link type="primary" data-testid="batch-no-clear-chip" @click="clearAll">清除</el-button>
    <el-dialog v-model="visible" :title="`批量查询${noun}`" width="480px" append-to-body>
      <el-input
        v-model="text"
        type="textarea"
        :rows="8"
        :placeholder="`每行一个${noun}，也可以用逗号、顿号、空格分隔；重复的自动去掉，最多 ${MAX_BATCH_NOS} 个`"
        data-testid="batch-no-text"
      />
      <div class="hint">已识别 {{ parsed.nos.length }} 个{{ parsed.overLimit ? `，超出 ${MAX_BATCH_NOS} 个的部分未计入，请分批查询` : '' }}</div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <div v-if="result" class="result" data-testid="batch-no-result">
        找到 {{ result.found }} 条；未找到 {{ result.missing.length }} 个
        <div v-if="result.missing.length" class="missing" data-testid="batch-no-missing">{{ result.missing.join('、') }}</div>
      </div>
      <template #footer>
        <el-button @click="clearAll">清除条件</el-button>
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" :loading="busy" :disabled="parsed.nos.length === 0" data-testid="batch-no-run" @click="run">查询</el-button>
      </template>
    </el-dialog>
  </span>
</template>

<style scoped>
.hint { margin-top: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.result { margin-top: 10px; font-size: 13px; }
.missing { margin-top: 4px; color: var(--el-color-danger); word-break: break-all; }
</style>
