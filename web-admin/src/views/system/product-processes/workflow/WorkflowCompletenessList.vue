<template>
  <div class="completeness-list">
    <section v-for="group in groups" :key="group.id" class="completeness-list__group">
      <h4>{{ group.label }}</h4>
      <ul>
        <li v-for="(issue, index) in group.issues" :key="index">
          <button
            v-if="locatable && issue.nodeId"
            type="button"
            class="completeness-list__item completeness-list__link"
            @click="$emit('locate', issue)"
          >
            <span>{{ issue.message }}</span>
            <small>{{ issue.action }}</small>
          </button>
          <div v-else class="completeness-list__item">
            <span>{{ issue.message }}</span>
            <small>{{ issue.action }}</small>
          </div>
        </li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import type { WorkflowCompletenessGroup, WorkflowCompletenessIssue } from './types';

withDefaults(defineProps<{ groups: WorkflowCompletenessGroup[]; locatable?: boolean }>(), {
  locatable: false,
});
defineEmits<{ locate: [issue: WorkflowCompletenessIssue] }>();
</script>

<style scoped>
.completeness-list { display: grid; gap: 12px; }
.completeness-list__group h4 {
  margin: 0 0 4px;
  padding: 4px 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 650;
}
.completeness-list__group ul { margin: 0; padding: 0; list-style: none; }
.completeness-list__item {
  display: grid;
  gap: 2px;
  width: 100%;
  padding: 6px 8px;
  overflow-wrap: anywhere;
  text-align: left;
  line-height: 1.45;
  color: var(--el-text-color-primary);
  font-size: 13px;
}
.completeness-list__item small { color: var(--el-text-color-regular); font-size: 12px; }
.completeness-list__link {
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}
.completeness-list__link:hover { background: var(--el-color-primary-light-9); }
.completeness-list__link:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
</style>
