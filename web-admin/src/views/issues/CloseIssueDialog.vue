<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ISSUE_CONCLUSIONS, CLOSE_REASON_MAX, validateClose, type IssueConclusion } from '@/api/issues';

/**
 * 关闭一条问题：先选结论（原型清单：已处理 / 误报 / 接受偏差），再写一句。
 * 两样都有了「关闭」才可点；提交失败的原因写在对话框里（后端同样校验）。
 */
const props = defineProps<{ modelValue: boolean; title: string; submitting: boolean; error: string }>();
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void;
  (e: 'submit', v: { conclusion: IssueConclusion; reason: string }): void;
}>();

const conclusion = ref<IssueConclusion | ''>('');
const reason = ref('');
const tried = ref(false);
watch(() => props.modelValue, (open) => { if (open) { conclusion.value = ''; reason.value = ''; tried.value = false; } });

const problem = computed(() => validateClose(conclusion.value, reason.value));
function submit() {
  tried.value = true;
  if (problem.value) return;
  emit('submit', { conclusion: conclusion.value as IssueConclusion, reason: reason.value.trim() });
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="关闭问题" width="520px" data-testid="close-issue-dialog"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <p class="obj">{{ title }}</p>
    <p class="hint">关闭后不再算进「发现的问题」；来源单据不变，可以恢复。</p>
    <div class="field">
      <div class="label">结论（必选）</div>
      <el-radio-group v-model="conclusion" class="conclusions" data-testid="close-conclusion">
        <el-radio v-for="c in ISSUE_CONCLUSIONS" :key="c.code" :value="c.code" border
          :data-testid="`conclusion-${c.code}`">
          <b>{{ c.label }}</b><span class="desc">{{ c.description }}</span>
        </el-radio>
      </el-radio-group>
    </div>
    <div class="field">
      <div class="label">说明（写一句）</div>
      <el-input v-model="reason" type="textarea" :rows="3" :maxlength="CLOSE_REASON_MAX" show-word-limit
        placeholder="例如：已和客户确认改到周五交" data-testid="close-reason" />
    </div>
    <p v-if="tried && problem" class="bad" data-testid="close-problem">{{ problem }}</p>
    <p v-if="error" class="bad" data-testid="close-error">没有关闭：{{ error }}</p>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!!problem" data-testid="close-submit" @click="submit">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.obj { margin: 0; font-weight: 600; color: var(--cs-text); }
.hint { margin: 4px 0 12px; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.field { margin-bottom: 12px; }
.label { margin-bottom: 6px; color: var(--cs-text); font-size: var(--cs-type-body); }
.conclusions { display: grid; gap: 6px; width: 100%; }
.conclusions :deep(.el-radio) { margin: 0; height: auto; padding: 8px 12px; }
.desc { margin-left: 8px; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.bad { margin: 4px 0 0; color: var(--cs-danger); font-size: var(--cs-type-caption); }
</style>
