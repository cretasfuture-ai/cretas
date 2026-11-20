<template>
  <div class="completeness-badge" @keydown.esc.stop="close" @pointerdown.stop @wheel.stop>
    <button
      ref="trigger"
      type="button"
      class="completeness-badge__trigger"
      :class="{ 'is-warning': issues.length > 0, 'is-pass': state === 'ready' && issues.length === 0 }"
      :aria-expanded="open"
      :aria-controls="open ? 'workflow-completeness-panel' : undefined"
      data-testid="workflow-completeness-badge"
      @click="open = !open"
    >
      <span aria-live="polite">{{ label }}</span>
      <span class="completeness-badge__chevron" aria-hidden="true" />
    </button>
    <section
      v-if="open"
      id="workflow-completeness-panel"
      class="completeness-badge__panel"
      aria-label="工艺检查"
      data-testid="workflow-completeness-panel"
    >
      <h3>工艺检查</h3>
      <WorkflowCompletenessList v-if="issues.length" :groups="groups" locatable @locate="locate" />
      <p v-else>{{ label }}</p>
      <button v-if="state === 'failed'" type="button" class="completeness-badge__retry" @click="$emit('retry')">
        重新检查
      </button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import WorkflowCompletenessList from './WorkflowCompletenessList.vue';
import type { WorkflowCompletenessGroup, WorkflowCompletenessIssue } from './types';

const props = defineProps<{
  issues: WorkflowCompletenessIssue[];
  groups: WorkflowCompletenessGroup[];
  state: 'checking' | 'ready' | 'failed';
}>();
const emit = defineEmits<{ locate: [issue: WorkflowCompletenessIssue]; retry: [] }>();
const open = defineModel<boolean>('open', { required: true });
const trigger = ref<HTMLButtonElement>();
const label = computed(() => {
  if (props.state === 'failed') return '检查未完成';
  if (props.issues.length) return `${props.issues.length} 个遗漏`;
  return props.state === 'ready' ? '检查通过' : '检查中…';
});

function close() {
  if (!open.value) return;
  open.value = false;
  trigger.value?.focus();
}

function locate(issue: WorkflowCompletenessIssue) {
  close();
  emit('locate', issue);
}
</script>

<style scoped>
.completeness-badge { position: absolute; z-index: 25; top: 12px; left: 12px; max-width: calc(100% - 24px); }
.completeness-badge__trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 4px 11px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  color: var(--el-text-color-regular);
  background: var(--el-bg-color-overlay);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
}
.completeness-badge__trigger.is-warning { color: #874200; background: #fff0d8; border-color: #eeae55; }
.completeness-badge__trigger.is-pass { color: #17634b; background: #e9f7ef; border-color: #a4d4b6; }
.completeness-badge__trigger:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.completeness-badge__chevron {
  width: 5px;
  height: 5px;
  border: solid currentColor;
  border-width: 0 1.5px 1.5px 0;
  transform: rotate(45deg) translateY(-2px);
}
.completeness-badge__trigger[aria-expanded=true] .completeness-badge__chevron {
  transform: rotate(225deg) translateY(-2px);
}
.completeness-badge__panel {
  width: 348px;
  max-width: 100%;
  max-height: min(360px, 46vh);
  overflow: auto;
  overscroll-behavior: contain;
  margin-top: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color-overlay);
  box-shadow: var(--el-box-shadow-light);
}
.completeness-badge__panel h3 { margin: 0 0 8px; color: var(--el-text-color-primary); font-size: 14px; }
.completeness-badge__panel p { margin: 4px 0; color: var(--el-text-color-regular); font-size: 13px; }
.completeness-badge__retry {
  border: 0;
  padding: 4px 0;
  color: var(--el-color-primary);
  background: transparent;
  font: inherit;
  cursor: pointer;
}
</style>
