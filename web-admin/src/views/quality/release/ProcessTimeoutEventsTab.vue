<script setup lang="ts">
/**
 * 超时处置 (缺口 14): 超时被拦下的料由质检员复检放行或判不合格, 结论必填。
 *
 * 防呆: Rule 1 对话框写清放行/不合格的后果; Rule 2 标题带批次号; Rule 3 常用结论一点即填;
 * 处置结果不设默认值 —— 放行是食品安全决定, 不让人顺手点确认。错误由请求拦截器常驻提示。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import {
  PROCESS_TIME_START_LABELS,
  decideProcessTimeEvent,
  listProcessTimeEventRecords,
  listProcessTimeEvents,
  type ProcessTimeLimitDecision,
  type ProcessTimeLimitEvent,
} from '@/api/processTimeLimit';
import { handleCatchError } from '@/utils/errorToast';
import { displayProcessUnit } from '@/utils/processSheetUnits';
import { formatBackendTime, formatDuration, parseBackendTime } from '@/utils/processTimeLimit';

const props = defineProps<{ factoryId: string }>();
const emit = defineEmits<{ count: [value: number] }>();

const loading = ref(false);
const loadError = ref<string | null>(null);
const events = ref<ProcessTimeLimitEvent[]>([]);
const records = ref<ProcessTimeLimitEvent[]>([]);
const loadedAt = ref(Date.now());

const dialogVisible = ref(false);
const submitting = ref(false);
const current = ref<ProcessTimeLimitEvent | null>(null);
const decision = ref<ProcessTimeLimitDecision | null>(null);
const conclusion = ref('');

const RELEASE_PRESETS = ['复检感官合格', '中心温度复测合格', '微生物复检合格'];
const REJECT_PRESETS = ['感官异常（异味/变色）', '中心温度不达标', '微生物超标', '超时过久，不再使用'];
const conclusionPresets = computed(() => (decision.value === 'REJECTED' ? REJECT_PRESETS : RELEASE_PRESETS));

const decidedRecords = computed(() => records.value.filter((item) => item.status !== 'OPEN'));

const dialogTitle = computed(() => (current.value
  ? `超时处置 — ${current.value.processName ?? '工序'} (${current.value.batchNumber ?? '批次号缺失'})`
  : '超时处置'));

const decisionTip = computed(() => {
  const item = current.value;
  if (!item || !decision.value) return '';
  if (decision.value === 'RELEASED') {
    return item.recheckValidMinutes == null
      ? '放行后这批料不再限时'
      : `放行后 ${formatDuration(item.recheckValidMinutes)} 内可以领用，过了还要再处置`;
  }
  return item.targetType === 'SEMI_FINISHED'
    ? `判不合格后，超时的 ${qty(item)} 从库存转出，不能再领用`
    : '判不合格后，整批标为不合格，不能再领用';
});

const submitDisabledReason = computed(() => {
  if (!decision.value) return '请选择处置结果';
  if (!conclusion.value.trim()) return '请填写复检结论';
  return null;
});

function qty(item: ProcessTimeLimitEvent): string {
  return item.expiredQuantity == null
    ? '—'
    : `${Number(item.expiredQuantity)} ${displayProcessUnit(item.unit)}`.trim();
}

function overdueText(item: ProcessTimeLimitEvent): string {
  const deadline = parseBackendTime(item.deadlineAt);
  if (deadline == null) return '—';
  return formatDuration(Math.max(0, Math.ceil((loadedAt.value - deadline) / 60000)));
}

function startText(item: ProcessTimeLimitEvent): string {
  return `${formatBackendTime(item.startAt)}（${PROCESS_TIME_START_LABELS[item.startEvent] ?? item.startEvent}）`;
}

async function reload(): Promise<void> {
  if (!props.factoryId) return;
  loading.value = true;
  loadError.value = null;
  try {
    const [open, recent] = await Promise.all([
      listProcessTimeEvents(props.factoryId),
      listProcessTimeEventRecords(props.factoryId),
    ]);
    if (!open.success || !recent.success) {
      loadError.value = open.message || recent.message || '超时清单加载失败';
      return;
    }
    events.value = Array.isArray(open.data) ? open.data : [];
    records.value = Array.isArray(recent.data) ? recent.data : [];
    loadedAt.value = Date.now();
    emit('count', events.value.length);
  } catch (e) {
    loadError.value = '超时清单加载失败，请点「刷新」重试';
    handleCatchError(e, '超时清单加载失败');
  } finally {
    loading.value = false;
  }
}

function openDecision(item: ProcessTimeLimitEvent): void {
  current.value = item;
  decision.value = null;
  conclusion.value = '';
  dialogVisible.value = true;
}

function applyPreset(text: string): void {
  const cur = conclusion.value.trim();
  if (cur.includes(text)) return;
  conclusion.value = cur ? `${cur}；${text}` : text;
}

async function submitDecision(): Promise<void> {
  const item = current.value;
  if (!item || !decision.value || submitDisabledReason.value) return;
  submitting.value = true;
  try {
    const res = await decideProcessTimeEvent(props.factoryId, item.id, {
      decision: decision.value,
      conclusion: conclusion.value.trim(),
    });
    if (res.success) {
      ElMessage.success(`${res.message || '已处置'} ${item.batchNumber ?? ''}`.trim());
      dialogVisible.value = false;
      await reload();
    }
  } catch (e) {
    handleCatchError(e, '提交失败，请重试');
    await reload();
    // 别人已处置过: 这条不在清单里了, 对话框留着只能再失败一次
    if (!events.value.some((event) => event.id === item.id)) dialogVisible.value = false;
  } finally {
    submitting.value = false;
  }
}

onMounted(() => { void reload(); });

defineExpose({ reload });
</script>

<template>
  <div data-testid="timeout-events-tab">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="mb" />
    <el-table v-loading="loading" :data="events" stripe empty-text="暂无待处置的超时批次" data-testid="timeout-events-table" class="mb">
      <el-table-column prop="batchNumber" label="批次号" min-width="190" show-overflow-tooltip />
      <el-table-column prop="processName" label="工序" min-width="110" show-overflow-tooltip />
      <el-table-column label="时限" width="110">
        <template #default="{ row }">{{ formatDuration(row.limitMinutes) }}</template>
      </el-table-column>
      <el-table-column label="起算" min-width="210">
        <template #default="{ row }">{{ startText(row) }}</template>
      </el-table-column>
      <el-table-column label="截止" width="150">
        <template #default="{ row }">{{ formatBackendTime(row.deadlineAt) }}</template>
      </el-table-column>
      <el-table-column label="已超时" width="120">
        <template #default="{ row }">{{ overdueText(row) }}</template>
      </el-table-column>
      <el-table-column label="超时数量" width="120" align="right">
        <template #default="{ row }">{{ qty(row) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" size="small" :data-testid="`timeout-decide-${row.id}`" @click="openDecision(row)">
            处置
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 class="section-title">处置记录</h4>
    <el-table :data="decidedRecords" stripe empty-text="暂无处置记录" data-testid="timeout-records-table">
      <el-table-column label="结果" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'RELEASED' ? 'success' : 'danger'">
            {{ row.status === 'RELEASED' ? '复检放行' : '判不合格' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="batchNumber" label="批次号" min-width="190" show-overflow-tooltip />
      <el-table-column prop="processName" label="工序" min-width="110" show-overflow-tooltip />
      <el-table-column label="超时数量" width="120" align="right">
        <template #default="{ row }">{{ qty(row) }}</template>
      </el-table-column>
      <el-table-column prop="conclusion" label="复检结论" min-width="220" show-overflow-tooltip />
      <el-table-column label="处置人" width="110">
        <template #default="{ row }">{{ row.decidedByName || '—' }}</template>
      </el-table-column>
      <el-table-column label="处置时间" width="150">
        <template #default="{ row }">{{ formatBackendTime(row.decidedAt) }}</template>
      </el-table-column>
      <el-table-column label="放行有效到" width="150">
        <template #default="{ row }">{{ row.status === 'RELEASED' ? formatBackendTime(row.validUntil) : '—' }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" :close-on-click-modal="false">
      <template v-if="current">
        <el-descriptions :column="1" border size="small" class="mb">
          <el-descriptions-item label="批次号">{{ current.batchNumber || '—' }}</el-descriptions-item>
          <el-descriptions-item label="工序 / 时限">{{ current.processName || '—' }} · {{ formatDuration(current.limitMinutes) }}</el-descriptions-item>
          <el-descriptions-item label="超时">{{ overdueText(current) }} · 数量 {{ qty(current) }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-position="top">
          <el-form-item label="处置结果" required>
            <el-radio-group v-model="decision">
              <el-radio value="RELEASED" data-testid="timeout-decision-released">复检放行</el-radio>
              <el-radio value="REJECTED" data-testid="timeout-decision-rejected">判不合格</el-radio>
            </el-radio-group>
            <div v-if="decisionTip" class="form-tip">{{ decisionTip }}</div>
          </el-form-item>
          <el-form-item label="复检结论" required>
            <div class="presets">
              <el-button
                v-for="p in conclusionPresets"
                :key="p"
                class="preset"
                size="small"
                plain
                :type="decision === 'REJECTED' ? 'danger' : 'success'"
                @click="applyPreset(p)"
              >{{ p }}</el-button>
            </div>
            <el-input
              v-model="conclusion"
              type="textarea"
              :rows="3"
              maxlength="2000"
              show-word-limit
              placeholder="例如：感官正常，中心温度复测 4℃ 合格"
              data-testid="timeout-conclusion"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <span v-if="submitDisabledReason" class="disabled-reason">{{ submitDisabledReason }}</span>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!!submitDisabledReason"
          data-testid="timeout-decision-submit"
          @click="submitDecision"
        >确认处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.section-title { margin: 16px 0 8px; color: #303133; font-size: 14px; font-weight: 600; }
.form-tip { width: 100%; margin-top: 4px; color: #909399; font-size: 12px; line-height: 1.5; }
.presets { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.preset { margin-left: 0; }
.disabled-reason { margin-right: 12px; color: #f56c6c; font-size: 12px; }
</style>
