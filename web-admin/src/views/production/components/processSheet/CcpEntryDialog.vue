<script setup lang="ts">
/**
 * 缺口 13: 正式报工前录入本工序的关键控制点 (CCP) 实测值。
 *
 * 防呆:
 *   - Rule 1 边界前置: 每项先显示「合格范围 + 单位」再让填; 填的过程中超出范围立刻红字提示后果
 *   - Rule 2 身份: 标题带工序名 + 批次/行
 *   - 全部填完才能确认 (按钮旁写明还差哪几项)
 *
 * 用法: `const readings = await dialogRef.value.collect(checkpoints, '卤煮 — 第 2 行')`;
 *       返回 null = 用户取消 (调用方不提交报工)。
 */
import { computed, ref } from 'vue';
import { formatLimit, isOutOfLimit, type CcpReading, type CcpStepCheckpoint } from '@/api/ccp';

const visible = ref(false);
const title = ref('');
const checkpoints = ref<CcpStepCheckpoint[]>([]);
const values = ref<Record<number, number | null>>({});
const notes = ref('');
let resolver: ((r: CcpReading[] | null) => void) | null = null;

const missing = computed(() => checkpoints.value.filter((c) => values.value[c.checkpointId] == null));
const outOfLimit = computed(() => checkpoints.value.filter((c) =>
  isOutOfLimit(values.value[c.checkpointId], c.criticalLimitMin, c.criticalLimitMax)));
const confirmDisabledReason = computed(() => (missing.value.length
  ? `还差：${missing.value.map((c) => c.name).join('、')}`
  : null));

function collect(cps: CcpStepCheckpoint[], dialogTitle: string): Promise<CcpReading[] | null> {
  checkpoints.value = cps;
  values.value = Object.fromEntries(cps.map((c): [number, number | null] => [c.checkpointId, null]));
  notes.value = '';
  title.value = dialogTitle;
  visible.value = true;
  return new Promise((resolve) => { resolver = resolve; });
}

function finish(result: CcpReading[] | null) {
  visible.value = false;
  const r = resolver;
  resolver = null;
  r?.(result);
}

function confirm() {
  if (confirmDisabledReason.value) return;
  finish(checkpoints.value.map((c) => ({
    checkpointId: c.checkpointId,
    measuredValue: Number(values.value[c.checkpointId]),
    notes: notes.value.trim() || null,
  })));
}

defineExpose({ collect });
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`录入关键控制点（CCP）— ${title}`"
    width="560px"
    :close-on-click-modal="false"
    @close="resolver && finish(null)"
  >
    <p class="lead">本工序是关键控制点，正式报工前请按规定测量并填写实测值。</p>
    <el-form label-position="top">
      <el-form-item v-for="cp in checkpoints" :key="cp.checkpointId" :label="`${cp.name}（${cp.checkpointCode}）`" required>
        <div class="limit">合格范围：<b>{{ formatLimit(cp) }}</b></div>
        <div v-if="cp.monitoringProcedure" class="procedure">测量方法：{{ cp.monitoringProcedure }}</div>
        <el-input-number
          v-model="values[cp.checkpointId]"
          :precision="2"
          :controls="false"
          :placeholder="`实测值（${cp.unit}）`"
          style="width: 220px"
          :data-testid="`ccp-input-${cp.checkpointCode}`"
        />
        <span class="unit">{{ cp.unit }}</span>
        <div
          v-if="isOutOfLimit(values[cp.checkpointId], cp.criticalLimitMin, cp.criticalLimitMax)"
          class="out-of-limit"
          :data-testid="`ccp-out-${cp.checkpointCode}`"
        >
          超出合格范围：提交后会记录偏离，本批次转为「待检」，需质检员处理偏离后才能放行。
          <template v-if="cp.correctiveAction">纠偏参考：{{ cp.correctiveAction }}</template>
        </div>
      </el-form-item>
      <el-form-item label="备注（可选）">
        <el-input v-model="notes" maxlength="200" show-word-limit placeholder="例如：测温计编号、测量位置" />
      </el-form-item>
    </el-form>
    <template #footer>
      <span v-if="confirmDisabledReason" class="disabled-reason">{{ confirmDisabledReason }}</span>
      <span v-else-if="outOfLimit.length" class="warn">有 {{ outOfLimit.length }} 项超出范围</span>
      <el-button @click="finish(null)">返回修改</el-button>
      <el-button
        type="primary"
        :disabled="!!confirmDisabledReason"
        data-testid="ccp-confirm"
        @click="confirm"
      >
        确认并正式报工
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.lead {
  margin: 0 0 12px;
  color: #606266;
  font-size: 13px;
}
.limit {
  font-size: 13px;
  color: #303133;
  width: 100%;
}
.procedure {
  font-size: 12px;
  color: #909399;
  width: 100%;
  margin-bottom: 4px;
}
.unit {
  margin-left: 8px;
  color: #606266;
}
.out-of-limit {
  width: 100%;
  margin-top: 4px;
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.5;
}
.disabled-reason {
  color: #f56c6c;
  font-size: 12px;
  margin-right: 12px;
}
.warn {
  color: #e6a23c;
  font-size: 12px;
  margin-right: 12px;
}
</style>
