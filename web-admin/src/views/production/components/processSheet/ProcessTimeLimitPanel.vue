<script setup lang="ts">
/**
 * 缺口 14 工序时限: 这道工序可选来源还能放多久, 以及登记解冻 / 冷藏。
 * 这里只显示和登记; 能不能投料由后端在保存时判定 (409 原话由请求拦截器常驻提示)。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessageBox } from 'element-plus';
import {
  endProcessTimeMark,
  getPlanTimeLimits,
  startProcessTimeMark,
  type ProcessTimeLimitStatus,
} from '@/api/processTimeLimit';
import type { WorkflowPortDescriptor } from '@/api/processSheet';
import { handleCatchError } from '@/utils/errorToast';
import { displayProcessUnit } from '@/utils/processSheetUnits';
import {
  TIME_LIMIT_STATE_LABELS,
  TIME_LIMIT_STATE_TAGS,
  crossedDeadline,
  hasPartialExpiry,
  remainingMinutesAt,
  remainingText,
} from '@/utils/processTimeLimit';

const props = defineProps<{
  factoryId: string;
  planId: string;
  processOrder: number;
  /** 本工序的投入端口: 冷藏按钮看端口配置里有没有 pauseCodes。 */
  inputPorts: WorkflowPortDescriptor[];
}>();

const rows = ref<ProcessTimeLimitStatus[]>([]);
const loadFailed = ref(false);
const nowMs = ref(Date.now());
const busyKey = ref<string | null>(null);
/** 已为哪些截止时间重新问过后端: 手机/电脑时钟不准时不至于每分钟都问一遍。 */
const recheckedDeadlines = new Set<string>();
let loadSeq = 0;
let timer: ReturnType<typeof setInterval> | null = null;

const pausablePortIds = computed(() => new Set(props.inputPorts
  .filter((port) => port.timeLimit?.pauseCodes?.includes('COLD_STORAGE'))
  .map((port) => port.workflowPortId)));

const targetKey = (row: ProcessTimeLimitStatus) => `${row.targetType}:${row.targetId}`;
const rowKey = (row: ProcessTimeLimitStatus) => `${row.workflowPortId ?? ''}:${targetKey(row)}`;
const timeText = (row: ProcessTimeLimitStatus) => remainingText(remainingMinutesAt(row, nowMs.value));
const qty = (value: number | null, row: ProcessTimeLimitStatus) =>
  `${Number(value ?? 0)} ${displayProcessUnit(row.unit)}`.trim();

function canPause(row: ProcessTimeLimitStatus): boolean {
  return pausablePortIds.value.has(row.workflowPortId ?? '') && !row.paused
    && (row.state === 'OK' || row.state === 'WARNING');
}

async function reload(): Promise<void> {
  const seq = ++loadSeq;
  try {
    const res = await getPlanTimeLimits(props.factoryId, props.planId, props.processOrder);
    if (seq !== loadSeq) return;
    if (res.success && Array.isArray(res.data)) {
      rows.value = res.data;
      loadFailed.value = false;
      nowMs.value = Date.now();
    } else {
      loadFailed.value = true;
    }
  } catch {
    if (seq === loadSeq) loadFailed.value = true;
  }
}

async function runMark(row: ProcessTimeLimitStatus, action: () => Promise<void>): Promise<void> {
  busyKey.value = rowKey(row);
  try {
    await action();
  } catch (e) {
    handleCatchError(e, '登记失败，请重试');
  } finally {
    busyKey.value = null;
    await reload();
  }
}

async function startThaw(row: ProcessTimeLimitStatus): Promise<void> {
  const batch = row.batchNumber ?? '这批料';
  try {
    await ElMessageBox.confirm(
      `从现在开始算解冻时间，登记后不能改。`,
      `开始解冻 — ${batch}`,
      { type: 'warning', confirmButtonText: '开始解冻', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  await runMark(row, async () => {
    await startProcessTimeMark(props.factoryId, {
      targetType: row.targetType, targetId: row.targetId, kind: 'START', code: 'THAW',
    });
  });
}

// 纪律排查 A13: 解冻开始可补登实际时间 (要原因并留痕; 最多往前补多久由本厂配置, 超出时服务端说明上限)
const backfillRow = ref<ProcessTimeLimitStatus | null>(null);
const backfillAt = ref<string>('');
const backfillReason = ref('');
/** 防呆 Rule 3: 常用原因可选, 也允许直接输入。 */
const BACKFILL_REASONS = ['开始解冻时忘了当场登记', '交接班漏登', '设备或网络故障当时登记不了'];
const backfillVisible = computed({
  get: () => backfillRow.value !== null,
  set: (open: boolean) => { if (!open) backfillRow.value = null; },
});
const backfillDisabledReason = computed(() => {
  if (!backfillAt.value) return '请选择实际开始解冻的时间';
  if (new Date(backfillAt.value).getTime() > Date.now()) return '开始时间不能晚于现在';
  if (!backfillReason.value.trim()) return '请填写补登原因';
  return '';
});

function openBackfill(row: ProcessTimeLimitStatus): void {
  backfillRow.value = row;
  backfillAt.value = '';
  backfillReason.value = '';
}

async function submitBackfill(): Promise<void> {
  const row = backfillRow.value;
  if (!row || backfillDisabledReason.value) return;
  const startedAt = backfillAt.value;
  const reason = backfillReason.value.trim();
  backfillRow.value = null;
  await runMark(row, async () => {
    await startProcessTimeMark(props.factoryId, {
      targetType: row.targetType, targetId: row.targetId, kind: 'START', code: 'THAW',
      startedAt, backfillReason: reason,
    });
  });
}

function startCold(row: ProcessTimeLimitStatus): Promise<void> {
  return runMark(row, async () => {
    await startProcessTimeMark(props.factoryId, {
      targetType: row.targetType, targetId: row.targetId, kind: 'PAUSE', code: 'COLD_STORAGE',
    });
  });
}

function endCold(row: ProcessTimeLimitStatus): Promise<void> {
  const markId = row.pauseMarkId;
  if (markId == null) return Promise.resolve();
  return runMark(row, async () => {
    await endProcessTimeMark(props.factoryId, markId);
  });
}

function tick(): void {
  nowMs.value = Date.now();
  const due = rows.value.filter((row) => crossedDeadline(row, nowMs.value)
    && !recheckedDeadlines.has(`${rowKey(row)}:${row.deadlineAt}`));
  if (due.length === 0) return;
  due.forEach((row) => recheckedDeadlines.add(`${rowKey(row)}:${row.deadlineAt}`));
  void reload();
}

watch(() => [props.factoryId, props.planId, props.processOrder], () => { void reload(); });

onMounted(() => {
  void reload();
  timer = setInterval(tick, 60_000);
});
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

defineExpose({ reload });
</script>

<template>
  <section
    v-if="rows.length > 0 || loadFailed"
    class="time-limit-panel"
    data-testid="process-time-limit-panel"
    aria-label="工序时限"
  >
    <div class="time-limit-title">
      <strong>工序时限</strong>
      <span class="time-limit-sub">倒计时仅供参考，能不能投料以保存时系统判定为准</span>
    </div>
    <p v-if="loadFailed" class="time-limit-failed" data-testid="time-limit-load-failed">
      时限状态没读到，保存时系统仍会判定。
      <el-button link type="primary" size="small" @click="reload">重新读取</el-button>
    </p>
    <table v-if="rows.length > 0" class="time-limit-table">
      <thead>
        <tr><th>批次</th><th>状态</th><th>时间</th><th>说明</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="rowKey(row)" data-testid="time-limit-row">
          <td>{{ row.batchNumber || '—' }}</td>
          <td>
            <el-tag :type="TIME_LIMIT_STATE_TAGS[row.state]" size="small" data-testid="time-limit-state">
              {{ TIME_LIMIT_STATE_LABELS[row.state] }}
            </el-tag>
          </td>
          <td data-testid="time-limit-remaining">{{ timeText(row) ?? '—' }}</td>
          <td>
            <div v-if="row.reason">{{ row.reason }}</div>
            <div v-if="hasPartialExpiry(row)" data-testid="time-limit-partial">
              可领 {{ qty(row.drawableQuantity, row) }}，已超时 {{ qty(row.expiredQuantity, row) }}
            </div>
          </td>
          <td class="time-limit-actions">
            <el-button
              v-if="row.state === 'START_MISSING'"
              size="small"
              type="primary"
              :loading="busyKey === rowKey(row)"
              data-testid="time-limit-start-thaw"
              @click="startThaw(row)"
            >开始解冻</el-button>
            <el-button
              v-if="row.state === 'START_MISSING'"
              size="small"
              link
              type="primary"
              :disabled="busyKey === rowKey(row)"
              data-testid="time-limit-backfill-thaw"
              @click="openBackfill(row)"
            >补登实际开始时间</el-button>
            <el-button
              v-if="canPause(row)"
              size="small"
              :loading="busyKey === rowKey(row)"
              data-testid="time-limit-start-cold"
              @click="startCold(row)"
            >开始冷藏</el-button>
            <el-button
              v-if="row.paused && row.pauseMarkId != null"
              size="small"
              type="primary"
              :loading="busyKey === rowKey(row)"
              data-testid="time-limit-end-cold"
              @click="endCold(row)"
            >结束冷藏</el-button>
          </td>
        </tr>
      </tbody>
    </table>
      <el-dialog
      v-model="backfillVisible"
      :title="`补登开始解冻 — ${backfillRow?.batchNumber ?? '这批料'}`"
      width="420px"
      append-to-body
      data-testid="time-limit-backfill-dialog"
    >
      <p class="time-limit-backfill-tip">
        早于现在开始解冻却没当场登记时用。计时从你填的时间算（更早 = 更快到期），登记后不能改；补登原因会留痕。
      </p>
      <el-form label-position="top">
        <el-form-item label="实际开始解冻时间" required>
          <el-date-picker
            v-model="backfillAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            format="MM-DD HH:mm"
            placeholder="选择时间"
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
            style="width: 100%"
            data-testid="time-limit-backfill-at"
          />
        </el-form-item>
        <el-form-item label="补登原因" required>
          <el-select
            v-model="backfillReason"
            filterable
            allow-create
            default-first-option
            placeholder="选择常用原因，或直接输入"
            style="width: 100%"
            data-testid="time-limit-backfill-reason"
          >
            <el-option v-for="r in BACKFILL_REASONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <span v-if="backfillDisabledReason" class="time-limit-backfill-tip">{{ backfillDisabledReason }}</span>
        <el-button @click="backfillVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!!backfillDisabledReason"
          data-testid="time-limit-backfill-submit"
          @click="submitBackfill"
        >登记</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.time-limit-backfill-tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-right: 8px;
}
.time-limit-panel {
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter, #e4e7ed);
  border-radius: 6px;
  padding: 8px 12px;
  background: var(--el-fill-color-blank, #fff);
  font-size: 13px;
}
.time-limit-title { display: flex; gap: 8px; align-items: baseline; margin-bottom: 4px; }
.time-limit-sub { color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.time-limit-failed { margin: 0; color: var(--el-color-danger, #f56c6c); }
.time-limit-table { width: 100%; border-collapse: collapse; }
.time-limit-table th, .time-limit-table td {
  padding: 3px 12px 3px 0; text-align: left; vertical-align: top; font-variant-numeric: tabular-nums;
}
.time-limit-table th { color: var(--el-text-color-secondary, #909399); font-weight: normal; }
.time-limit-actions { white-space: nowrap; }
</style>
