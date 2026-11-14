<script setup lang="ts">
/**
 * 缺口 03 回料比例上限: 正式报工被后端以 REWORK_RATIO_REASON_REQUIRED 拦下时, 让用户选超限原因再重提。
 *
 * 为什么是「被拦后再问」而不是边填边算: 回料占比里有按配比自动扣减的部分 (ReworkPolicyAutoDeduction),
 * 只有后端提交时才知道真实占比, 前端预算会和后端不一致; 后端 message 已写明「占比 X%，超过上限 Y%」。
 *
 * 用法: `const r = await dialogRef.value.collect(message, '卤煮 — 第 2 行')`;
 *       返回 null = 用户取消 (不提交, 表单数据原样保留)。
 */
import { computed, ref } from 'vue';
import { REWORK_REASON_OTHER, REWORK_REASONS, type ReworkRatioInput, reworkReasonBlockReason } from './reworkRatio';

const visible = ref(false);
const title = ref('');
const message = ref('');
const reason = ref('');
const note = ref('');
let resolver: ((r: ReworkRatioInput | null) => void) | null = null;

const blockReason = computed(() => reworkReasonBlockReason(reason.value, note.value));

function collect(backendMessage: string, dialogTitle: string, previous?: ReworkRatioInput): Promise<ReworkRatioInput | null> {
  message.value = backendMessage;
  title.value = dialogTitle;
  reason.value = previous?.reason ?? '';
  note.value = previous?.note ?? '';
  visible.value = true;
  return new Promise((resolve) => { resolver = resolve; });
}

function finish(result: ReworkRatioInput | null) {
  visible.value = false;
  const r = resolver;
  resolver = null;
  r?.(result);
}

function confirm() {
  if (blockReason.value) return;
  finish({ reason: reason.value, note: note.value.trim() || undefined });
}

defineExpose({ collect });
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`回料超过上限 — ${title}`"
    width="520px"
    :close-on-click-modal="false"
    @close="resolver && finish(null)"
  >
    <p class="lead" data-testid="rework-ratio-message">{{ message }}</p>
    <p class="hint">请先核对回料投入量。确实要这样投的，选一个原因，原因会随这次报工记录下来。</p>
    <el-form label-position="top">
      <el-form-item label="超限原因" required>
        <el-select
          v-model="reason"
          placeholder="选择原因"
          style="width: 260px"
          data-testid="rework-ratio-reason"
        >
          <el-option v-for="r in REWORK_REASONS" :key="r" :label="r" :value="r" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="reason === REWORK_REASON_OTHER" label="请写明原因" required>
        <el-input
          v-model="note"
          maxlength="200"
          show-word-limit
          placeholder="为什么回料占比超过上限"
          data-testid="rework-ratio-note"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <span v-if="blockReason" class="disabled-reason">{{ blockReason }}</span>
      <el-button @click="finish(null)">返回修改</el-button>
      <el-button type="primary" :disabled="!!blockReason" data-testid="rework-ratio-confirm" @click="confirm">
        按此原因继续报工
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lead { margin: 0 0 8px; color: var(--cs-danger); font-size: 14px; font-weight: 600; line-height: 1.5; }
.hint { margin: 0 0 12px; color: var(--cs-muted); font-size: 13px; }
.disabled-reason { color: var(--cs-danger); font-size: 12px; margin-right: 12px; }
</style>
