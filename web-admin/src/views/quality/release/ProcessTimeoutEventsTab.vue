<script setup lang="ts">
/**
 * 超时处置 (缺口 14): 超时被拦下的料由质检员复检放行或判不合格, 结论必填。
 *
 * 防呆: Rule 1 对话框写清放行/不合格的后果; Rule 2 标题带批次号; Rule 3 常用结论一点即填;
 * 处置结果不设默认值 —— 放行是食品安全决定, 不让人顺手点确认。错误由请求拦截器常驻提示。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn } from '@/types/csTable';
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

const eventColumns: TableColumn<ProcessTimeLimitEvent>[] = [
  { accessorKey: 'batchNumber', header: '批次号' },
  { accessorKey: 'processName', header: '工序' },
  { id: 'limit', header: '时限' },
  { id: 'start', header: '起算' },
  { id: 'deadline', header: '截止' },
  { id: 'overdue', header: '已超时' },
  { id: 'qty', header: '超时数量' },
  { id: 'actions', header: '操作' },
];
const recordColumns: TableColumn<ProcessTimeLimitEvent>[] = [
  { id: 'result', header: '结果' },
  { accessorKey: 'batchNumber', header: '批次号' },
  { accessorKey: 'processName', header: '工序' },
  { id: 'qty', header: '超时数量' },
  { accessorKey: 'conclusion', header: '复检结论' },
  { id: 'decidedBy', header: '处置人' },
  { id: 'decidedAt', header: '处置时间' },
  { id: 'validUntil', header: '放行有效到' },
];

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
    <UAlert v-if="loadError" :title="loadError" color="error" variant="subtle" icon="i-lucide-circle-alert" class="mb" />
    <section class="cs-card mb">
      <UTable :data="events" :columns="eventColumns" :loading="loading" data-testid="timeout-events-table" class="cs-table">
        <template #empty>暂无待处置的超时批次</template>
        <template #limit-cell="{ row }">{{ formatDuration(row.original.limitMinutes) }}</template>
        <template #start-cell="{ row }">{{ startText(row.original) }}</template>
        <template #deadline-cell="{ row }">{{ formatBackendTime(row.original.deadlineAt) }}</template>
        <template #overdue-cell="{ row }">{{ overdueText(row.original) }}</template>
        <template #qty-cell="{ row }"><span class="num">{{ qty(row.original) }}</span></template>
        <template #actions-cell="{ row }">
          <UButton size="xs" :data-testid="`timeout-decide-${row.original.id}`" @click="openDecision(row.original)">处置</UButton>
        </template>
      </UTable>
    </section>

    <h4 class="section-title">处置记录</h4>
    <section class="cs-card">
      <UTable :data="decidedRecords" :columns="recordColumns" data-testid="timeout-records-table" class="cs-table">
        <template #empty>暂无处置记录</template>
        <template #result-cell="{ row }">
          <UBadge :color="row.original.status === 'RELEASED' ? 'success' : 'error'" variant="soft">
            {{ row.original.status === 'RELEASED' ? '复检放行' : '判不合格' }}
          </UBadge>
        </template>
        <template #qty-cell="{ row }"><span class="num">{{ qty(row.original) }}</span></template>
        <template #decidedBy-cell="{ row }">{{ row.original.decidedByName || '—' }}</template>
        <template #decidedAt-cell="{ row }">{{ formatBackendTime(row.original.decidedAt) }}</template>
        <template #validUntil-cell="{ row }">{{ row.original.status === 'RELEASED' ? formatBackendTime(row.original.validUntil) : '—' }}</template>
      </UTable>
    </section>

    <UModal v-model:open="dialogVisible" :title="dialogTitle" description="超时批次复检处置" :dismissible="false" :ui="{ content: 'max-w-[520px]', description: 'sr-only' }">
      <template #body>
        <template v-if="current">
          <dl class="desc mb">
            <dt>批次号</dt><dd>{{ current.batchNumber || '—' }}</dd>
            <dt>工序 / 时限</dt><dd>{{ current.processName || '—' }} · {{ formatDuration(current.limitMinutes) }}</dd>
            <dt>超时</dt><dd>{{ overdueText(current) }} · 数量 {{ qty(current) }}</dd>
          </dl>
          <UFormField label="处置结果" required>
            <div class="radios">
              <label class="radio" data-testid="timeout-decision-released"><input v-model="decision" type="radio" value="RELEASED"> 复检放行</label>
              <label class="radio" data-testid="timeout-decision-rejected"><input v-model="decision" type="radio" value="REJECTED"> 判不合格</label>
            </div>
            <div v-if="decisionTip" class="form-tip">{{ decisionTip }}</div>
          </UFormField>
          <UFormField label="复检结论" required class="mt-3">
            <div class="presets">
              <UButton
                v-for="p in conclusionPresets"
                :key="p"
                size="xs"
                variant="outline"
                :color="decision === 'REJECTED' ? 'error' : 'success'"
                @click="applyPreset(p)"
              >{{ p }}</UButton>
            </div>
            <UTextarea
              v-model="conclusion"
              :rows="3"
              :maxlength="2000"
              placeholder="例如：感官正常，中心温度复测 4℃ 合格"
              class="w-full"
              data-testid="timeout-conclusion"
            />
          </UFormField>
        </template>
      </template>
      <template #footer>
        <span v-if="submitDisabledReason" class="disabled-reason">{{ submitDisabledReason }}</span>
        <UButton color="neutral" variant="outline" @click="dialogVisible = false">取消</UButton>
        <UButton :loading="submitting" :disabled="!!submitDisabledReason" data-testid="timeout-decision-submit" @click="submitDecision">确认处置</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.mb { margin-bottom: var(--cs-space-3); }
.section-title { margin: var(--cs-space-4) 0 var(--cs-space-2); color: var(--cs-text); font-size: var(--cs-type-section); font-weight: 600; }
.desc { display: grid; grid-template-columns: 96px 1fr; margin: 0; border: 1px solid var(--cs-border); border-radius: var(--cs-radius); font-size: var(--cs-font); }
.desc dt, .desc dd { margin: 0; padding: 6px 10px; border-bottom: 1px solid var(--cs-border); }
.desc dt { background: var(--cs-surface-subtle); color: var(--cs-muted); }
.desc dt:nth-last-of-type(1), .desc dd:last-of-type { border-bottom: 0; }
.radios { display: flex; gap: var(--cs-space-5); }
.radio { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; color: var(--cs-text); }
.radio input { accent-color: var(--cs-primary); }
.form-tip { margin-top: 4px; color: var(--cs-muted); font-size: var(--cs-font-small); line-height: 1.5; }
.presets { display: flex; flex-wrap: wrap; gap: var(--cs-space-2); margin-bottom: var(--cs-space-2); }
.disabled-reason { margin-right: auto; color: var(--cs-danger); font-size: var(--cs-font-small); }
</style>
