<script setup lang="ts">
/**
 * 缺口 06 配比核对: 多主料汇合时, 边填边显示「配方份额 vs 实际份额」, 超出 ±2 个百分点标红并要求选原因。
 * 只负责展示与收集原因; 判定在 ./blendRatio.ts (与后端 BlendRatioCheck 同口径), 提交拦截在父组件。
 */
import { computed } from 'vue';
import { BLEND_REASONS, BLEND_REASON_OTHER, formatPercent, formatPp, type BlendEvaluation } from './blendRatio';

const props = defineProps<{
  evaluation: BlendEvaluation;
  reason: string;
  note: string;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:reason', value: string): void;
  (e: 'update:note', value: string): void;
}>();

const checked = computed(() => (props.evaluation.status === 'WITHIN' || props.evaluation.status === 'OVER')
  ? props.evaluation
  : null);
const isOver = computed(() => props.evaluation.status === 'OVER');
const isSecret = computed(() => props.evaluation.status === 'SECRET');
</script>

<template>
  <section
    v-if="evaluation.status !== 'NOT_APPLICABLE'"
    class="blend-panel"
    :class="{ 'blend-panel--over': isOver }"
    data-testid="blend-ratio-panel"
    aria-label="配比核对"
  >
    <div class="blend-title">
      <strong>配比核对</strong>
      <span v-if="checked" class="blend-sub">按工艺配方份额，允许偏差 ±{{ checked.tolerancePp }} 个百分点</span>
    </div>
    <p v-if="evaluation.status === 'SKIPPED'" class="blend-skip" data-testid="blend-ratio-skipped">{{ evaluation.reason }}</p>
    <p v-if="isSecret" class="blend-skip" data-testid="blend-ratio-secret">
      该产品工艺已保密，本页不显示配方份额、不能预先核对；提交时如提示「配比偏差」，请在下面选择原因后再提交。
    </p>
    <table v-if="checked" class="blend-table">
      <thead>
        <tr><th>主料</th><th>配方</th><th>实际</th><th>偏差</th></tr>
      </thead>
      <tbody>
        <tr
          v-for="line in checked.lines"
          :key="line.id"
          data-testid="blend-ratio-line"
          :class="{ 'blend-line--over': line.over }"
        >
          <td>{{ line.name }}</td>
          <td>{{ formatPercent(line.recipePercent) }}</td>
          <td>{{ formatPercent(line.actualPercent) }}</td>
          <td :data-over="line.over ? 'true' : 'false'">{{ formatPp(line.deviationPp) }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="isOver || isSecret" class="blend-reason" data-testid="blend-ratio-reason-area">
      <span v-if="isOver" class="blend-reason-label"><i class="sp-required">*</i>配比超出允许范围，请选择原因</span>
      <span v-else class="blend-sub">配比偏差原因（提交提示超差时必选）</span>
      <el-select
        :model-value="reason"
        placeholder="选择偏差原因"
        size="small"
        style="width:180px"
        :disabled="disabled"
        data-testid="blend-ratio-reason"
        aria-label="配比偏差原因"
        @update:model-value="(v: string) => emit('update:reason', v)"
      >
        <el-option v-for="r in BLEND_REASONS" :key="r" :label="r" :value="r" />
      </el-select>
      <el-input
        v-if="reason === BLEND_REASON_OTHER"
        :model-value="note"
        placeholder="请写明原因"
        size="small"
        maxlength="200"
        style="width:260px"
        :disabled="disabled"
        data-testid="blend-ratio-note"
        aria-label="配比偏差说明"
        @update:model-value="(v: string) => emit('update:note', v)"
      />
    </div>
  </section>
</template>

<style scoped>
.blend-panel {
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  border-radius: 6px;
  padding: 8px 12px;
  background: var(--el-fill-color-blank, #fff);
  font-size: 13px;
}
.blend-panel--over {
  border-color: var(--el-color-danger-light-5, #fab6b6);
  background: var(--el-color-danger-light-9, #fef0f0);
}
.blend-title { display: flex; gap: 8px; align-items: baseline; margin-bottom: 4px; }
.blend-sub { color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.blend-skip { margin: 0; color: var(--el-text-color-secondary, #909399); }
.blend-table { border-collapse: collapse; }
.blend-table th, .blend-table td { padding: 2px 12px 2px 0; text-align: left; font-variant-numeric: tabular-nums; }
.blend-table th { color: var(--el-text-color-secondary, #909399); font-weight: normal; }
.blend-line--over td { color: var(--el-color-danger, #f56c6c); font-weight: 600; }
.blend-reason { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-top: 6px; }
.blend-reason-label { color: var(--el-color-danger, #f56c6c); }
.sp-required { color: var(--el-color-danger, #f56c6c); font-style: normal; margin-right: 2px; }
</style>
