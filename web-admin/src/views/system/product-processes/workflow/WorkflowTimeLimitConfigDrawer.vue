<template>
  <el-drawer
    :model-value="visible"
    :title="`工序时限 · ${processName || '未命名工序'}`"
    direction="rtl"
    size="480px"
    append-to-body
    :before-close="handleBeforeClose"
    data-testid="time-limit-drawer"
    @update:model-value="(value: boolean) => !value && handleBeforeClose(() => emit('update:visible', false))"
  >
    <div class="time-limit-drawer">
      <p class="time-limit-drawer__scope">
        给投入的料定一个「最多放多久」。超时的料不能领用，要质检员复检放行或判不合格。不打开就是不限时。
      </p>
      <section
        v-for="(port, index) in ports"
        :key="port.portId"
        class="time-limit-card"
        :class="{ 'is-enabled': drafts[index]?.enabled }"
        :data-testid="`time-limit-port-${port.portId}`"
      >
        <template v-if="drafts[index]">
          <div class="time-limit-card__head">
            <span class="time-limit-card__name" :title="port.materialName">{{ port.materialName || `投入 ${index + 1}` }}</span>
            <el-switch
              v-model="drafts[index].enabled"
              :disabled="!canWrite"
              inline-prompt
              active-text="限时"
              inactive-text="不限"
              :aria-label="`${port.materialName || `投入 ${index + 1}`} 是否限时`"
              data-testid="time-limit-enabled"
            />
          </div>
          <el-form v-if="drafts[index].enabled" label-width="156px" label-position="left" size="small">
            <el-form-item label="时限(分钟)" required>
              <el-input-number
                v-model="drafts[index].maxMinutes"
                :disabled="!canWrite"
                :min="1"
                :precision="0"
                :controls="false"
                data-testid="time-limit-max"
              />
            </el-form-item>
            <el-form-item label="临近提醒(分钟)">
              <el-input-number
                v-model="drafts[index].warnMinutes"
                :disabled="!canWrite"
                :min="1"
                :precision="0"
                :controls="false"
                placeholder="不提醒就留空"
                data-testid="time-limit-warn"
              />
            </el-form-item>
            <el-form-item label="起算点">
              <el-select v-model="drafts[index].startEvent" :disabled="!canWrite" :teleported="false" data-testid="time-limit-start">
                <el-option v-for="option in startEventOptions(port.raw)" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="允许冷藏暂停">
              <el-switch v-model="drafts[index].pauseAllowed" :disabled="!canWrite" data-testid="time-limit-pause" />
            </el-form-item>
            <el-form-item v-if="drafts[index].pauseAllowed" label="最多暂停(分钟)">
              <el-input-number
                v-model="drafts[index].maxPauseMinutes"
                :disabled="!canWrite"
                :min="1"
                :precision="0"
                :controls="false"
                placeholder="不封顶就留空"
                data-testid="time-limit-max-pause"
              />
            </el-form-item>
            <el-form-item label="混批">
              <el-select v-model="drafts[index].mixRule" :disabled="!canWrite" :teleported="false" data-testid="time-limit-mix">
                <el-option v-for="option in MIX_RULE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="拆批">
              <el-select v-model="drafts[index].splitRule" :disabled="!canWrite" :teleported="false" data-testid="time-limit-split">
                <el-option v-for="option in SPLIT_RULE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="回料">
              <el-select v-model="drafts[index].reworkRule" :disabled="!canWrite" :teleported="false" data-testid="time-limit-rework">
                <el-option v-for="option in REWORK_RULE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="回料时限(分钟)">
              <el-input-number
                v-model="drafts[index].reworkMaxMinutes"
                :disabled="!canWrite"
                :min="1"
                :precision="0"
                :controls="false"
                placeholder="留空同上面的时限"
                data-testid="time-limit-rework-max"
              />
            </el-form-item>
            <el-form-item label="复检放行有效期(分钟)">
              <el-input-number
                v-model="drafts[index].recheckValidMinutes"
                :disabled="!canWrite"
                :min="1"
                :precision="0"
                :controls="false"
                placeholder="留空就是放行后不再限时"
                data-testid="time-limit-recheck"
              />
            </el-form-item>
          </el-form>
          <p v-if="problems[index]" class="time-limit-card__problem" data-testid="time-limit-problem">{{ problems[index] }}</p>
        </template>
      </section>
    </div>

    <template #footer>
      <el-button data-testid="time-limit-cancel" @click="handleBeforeClose(() => emit('update:visible', false))">取消</el-button>
      <el-button
        type="primary"
        :disabled="!canWrite || hasProblem"
        data-testid="time-limit-save"
        @click="save"
      >保存</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import type { WorkflowPortTimeLimit } from '@/api/processTimeLimit';
import {
  MIX_RULE_OPTIONS,
  REWORK_RULE_OPTIONS,
  SPLIT_RULE_OPTIONS,
  draftFromLimit,
  limitFromDraft,
  startEventOptions,
  timeLimitProblem,
  type TimeLimitDraft,
  type TimeLimitPortRow,
} from './workflowTimeLimit';

const props = defineProps<{
  visible: boolean;
  canWrite: boolean;
  processName: string;
  ports: TimeLimitPortRow[];
}>();

const emit = defineEmits<{
  'update:visible': [visible: boolean];
  /** portId → 配置; null = 不限时。 */
  save: [limits: Record<string, WorkflowPortTimeLimit | null>];
}>();

const drafts = ref<TimeLimitDraft[]>([]);
const initialSnapshot = ref('');
const snapshot = () => JSON.stringify(drafts.value);

watch(() => props.visible, (visible) => {
  if (!visible) return;
  drafts.value = props.ports.map((port) => draftFromLimit(port.limit, port.raw));
  initialSnapshot.value = snapshot();
}, { immediate: true });

const problems = computed(() => drafts.value.map((draft, index) => (draft.enabled
  ? timeLimitProblem({
    maxMinutes: draft.maxMinutes,
    warnMinutes: draft.warnMinutes,
    startEvent: draft.startEvent,
    maxPauseMinutes: draft.pauseAllowed ? draft.maxPauseMinutes : null,
    reworkMaxMinutes: draft.reworkMaxMinutes,
    recheckValidMinutes: draft.recheckValidMinutes,
  }, props.ports[index]?.raw ?? false)
  : null)));
const hasProblem = computed(() => problems.value.some((problem) => problem != null));

function save(): void {
  if (hasProblem.value) return;
  emit('save', Object.fromEntries(props.ports.map((port, index) => [port.portId, limitFromDraft(drafts.value[index])])));
  initialSnapshot.value = snapshot();
  emit('update:visible', false);
}

function handleBeforeClose(done: () => void): void {
  if (snapshot() === initialSnapshot.value) { done(); return; }
  ElMessageBox.confirm('工序时限有未保存的修改，确认放弃？', '放弃修改', {
    type: 'warning', confirmButtonText: '放弃', cancelButtonText: '继续编辑',
  }).then(() => done()).catch(() => { /* 继续编辑 */ });
}
</script>

<style scoped>
.time-limit-drawer { display: flex; flex-direction: column; gap: 12px; }
.time-limit-drawer__scope { margin: 0; color: #667085; font-size: 12px; line-height: 1.6; }
.time-limit-card { padding: 8px 10px; border: 1px solid #e6ebf2; border-radius: 8px; background: #fff; }
/* 已开不只靠颜色: 开关本身 + 左侧色条 + 展开的表单。 */
.time-limit-card.is-enabled { border-color: var(--el-color-primary); border-left-width: 4px; }
.time-limit-card__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.time-limit-card__name {
  flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: #1f2d3d; font-size: 13px; font-weight: 700;
}
.time-limit-card :deep(.el-form) { margin-top: 8px; }
.time-limit-card :deep(.el-form-item) { margin-bottom: 8px; }
.time-limit-card :deep(.el-input-number),
.time-limit-card :deep(.el-select) { width: 100%; }
.time-limit-card :deep(.el-input-number .el-input__inner) { text-align: left; }
.time-limit-card__problem { margin: 4px 0 0; color: #c0392b; font-size: 12px; }
</style>
