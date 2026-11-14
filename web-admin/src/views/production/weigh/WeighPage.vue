<script setup lang="ts">
/**
 * 迁移 P5「称重报工」/production/weigh（原型 R23 A①、R25、R34、R36）。
 * 选生产批次 → 选工序任务 → 逐笔称重（投入 / 良品毛重 / 皮重 / 不良 / 起止时间）。
 * 一项任务可以报多笔，每笔只累加，不把任务置完工。写入复用已有逐道报工接口，服务端同口径兜底校验。
 * 数据疑点（出成率 ≥100% / 起止相同 / 单位明显不符）只标「数据疑点 · 找谁核」，不改原值，不进异常。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { getBatchWorkProcessTasks, type WorkProcessTaskItem } from '@/api/processProduction';
import { listTaskWeighings, submitWeighing, type WeighingEntry } from '@/api/weighing';
import { ApiError } from '@/types/api';
import { beijingNow, buildPayload, checkWeigh, type WeighDraft, type WeighField } from './weighForm';

interface BatchOption { id: number; batchNumber: string; productName?: string; status?: string }

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => auth.factoryId || '');

const batches = ref<BatchOption[]>([]);
const batchErr = ref('');
const batchId = ref<number | null>(route.query.batchId ? Number(route.query.batchId) : null);
const tasks = ref<WorkProcessTaskItem[]>([]);
const taskErr = ref('');
const taskId = ref<number | null>(route.query.taskId ? Number(route.query.taskId) : null);
const entries = ref<WeighingEntry[]>([]);
const entriesState = ref<'idle' | 'loading' | 'ok' | 'error'>('idle');
const entriesErr = ref('');

const task = computed(() => tasks.value.find((t) => t.id === taskId.value) ?? null);
const batch = computed(() => batches.value.find((b) => b.id === batchId.value) ?? null);

function freshDraft(): WeighDraft {
  const n = beijingNow(new Date());
  return { inputKg: '', goodGrossKg: '', tareKg: '', defectKg: '', date: n.date, start: n.time, end: n.time };
}
const draft = reactive<WeighDraft>(freshDraft());
const touched = ref(false);
const nowTick = ref(Date.now());
const check = computed(() => { void nowTick.value; return checkWeigh(draft, new Date()); });
const serverErr = reactive<Partial<Record<WeighField | 'form', string>>>({});
const fieldErr = (f: WeighField) => serverErr[f] || (touched.value ? check.value.errors[f] : '') || '';
const fieldWarn = (f: WeighField) => (!fieldErr(f) ? check.value.warnings[f] ?? '' : '');
const saving = ref(false);
const savedMsg = ref('');
let requestId = '';

const errOf = (e: unknown) => (e instanceof Error && e.message ? e.message : '读取失败');

async function loadBatches() {
  if (!factoryId.value) return;
  batchErr.value = '';
  try {
    const res = await get<{ content?: Array<Record<string, unknown>> }>(`/${factoryId.value}/processing/batches-with-clerk-wip`, {
      params: { page: 1, size: 100, status: 'IN_PROGRESS' }, _silent: true,
    });
    batches.value = (res.data?.content ?? []).map((b) => ({
      id: Number(b.id), batchNumber: String(b.batchNumber ?? b.id), productName: b.productName ? String(b.productName) : undefined,
      status: b.status ? String(b.status) : undefined,
    }));
  } catch (e) { batchErr.value = `生产批次读取失败：${errOf(e)}`; }
}

async function loadTasks() {
  tasks.value = [];
  taskErr.value = '';
  if (!factoryId.value || !batchId.value) return;
  try {
    const res = await getBatchWorkProcessTasks(factoryId.value, batchId.value);
    tasks.value = (res.data ?? []).slice().sort((a, b) => a.processOrder - b.processOrder);
  } catch (e) { taskErr.value = `工序任务读取失败：${errOf(e)}`; }
}

async function loadEntries() {
  entries.value = [];
  if (!factoryId.value || !taskId.value) { entriesState.value = 'idle'; return; }
  entriesState.value = 'loading';
  try {
    const res = await listTaskWeighings(factoryId.value, taskId.value);
    entries.value = res.data ?? [];
    entriesState.value = 'ok';
  } catch (e) { entriesState.value = 'error'; entriesErr.value = `已报称重读取失败：${errOf(e)}`; }
}

watch(batchId, (v) => {
  void router.replace({ query: v ? { batchId: String(v) } : {} });
  if (task.value && task.value.productionBatchId !== v) taskId.value = null;
  void loadTasks();
});
watch(taskId, (v) => {
  if (v) void router.replace({ query: { batchId: String(batchId.value), taskId: String(v) } });
  void loadEntries();
});
onMounted(async () => { await loadBatches(); await loadTasks(); await loadEntries(); });

const n2 = (v: number | null | undefined) => (v === null || v === undefined ? '—' : Number(v).toFixed(2));
const pct = (v: number | null) => (v === null ? '没法算' : `${(v * 100).toFixed(2)}%`);
const entryYield = (e: WeighingEntry) =>
  e.inputQuantity && e.inputQuantity > 0 && e.outputQuantity !== null ? e.outputQuantity / e.inputQuantity : null;
const entryTime = (e: WeighingEntry) => {
  const s = e.laborSegments?.[0];
  return s?.startTime || s?.endTime ? `${s?.startTime ?? '?'} – ${s?.endTime ?? '?'}` : '没录时间';
};
const totals = computed(() => {
  const withInput = entries.value.filter((e) => (e.inputQuantity ?? 0) > 0 && e.outputQuantity !== null);
  const input = withInput.reduce((a, e) => a + Number(e.inputQuantity), 0);
  const good = withInput.reduce((a, e) => a + Number(e.outputQuantity), 0);
  return { count: entries.value.length, input, good, rate: input > 0 ? good / input : null,
    suspects: entries.value.filter((e) => e.suspects.length).length };
});
function clearServerErr() { for (const k of Object.keys(serverErr)) delete serverErr[k as WeighField]; }
watch(draft, () => { clearServerErr(); savedMsg.value = ''; }, { deep: true });

function fieldOfError(e: ApiError): WeighField | 'form' {
  if (e.code === 'WEIGHING_TIME_IN_FUTURE') return e.message.startsWith('开始') ? 'start' : 'end';
  if (e.code === 'WEIGHING_TARE_NEGATIVE' || e.code === 'WEIGHING_TARE_EXCEEDS_GROSS') return 'tareKg';
  return 'form';
}

async function save() {
  touched.value = true;
  nowTick.value = Date.now();
  clearServerErr();
  if (!task.value || !batchId.value) { serverErr.form = '请先选生产批次和工序任务'; return; }
  if (!check.value.ok || check.value.netKg === null) return;
  if (!requestId) requestId = `weigh-${task.value.id}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
  saving.value = true;
  try {
    await submitWeighing(factoryId.value, batchId.value, buildPayload(draft, task.value.id, requestId, check.value.netKg));
    savedMsg.value = `已保存这一笔：投入 ${n2(Number(draft.inputKg))} kg，良品净重 ${n2(check.value.netKg)} kg。可以接着报下一笔。`;
    requestId = '';
    const keepDate = draft.date;
    Object.assign(draft, freshDraft(), { date: keepDate });
    touched.value = false;
    await loadEntries();
  } catch (e) {
    const where = e instanceof ApiError ? fieldOfError(e) : 'form';
    const hint = e instanceof ApiError && e.actionHint ? `（${e.actionHint}）` : '';
    serverErr[where] = `没有保存：${errOf(e)}${hint}`;
    // 被拦的请求没有落库，下一次点保存用新的请求号；网络超时等不明结果才需要沿用同一个号重试
    if (e instanceof ApiError && e.status && e.status < 500) requestId = '';
  } finally { saving.value = false; }
}
</script>

<template>
  <div class="weigh" data-testid="weigh-page">
    <header class="head">
      <h1>称重报工</h1>
      <p class="why">一道工序可以报多笔。每笔填投入、良品毛重和皮重，系统算良品净重和出成率。录错的记录不会被改，只会标「数据疑点 · 找谁核」。</p>
    </header>

    <el-card shadow="never" class="card">
      <div class="pick">
        <label>生产批次
          <el-select v-model="batchId" filterable placeholder="选进行中的批次" data-testid="weigh-batch" style="width: 280px">
            <el-option v-for="b in batches" :key="b.id" :value="b.id" :label="`${b.batchNumber}${b.productName ? ' · ' + b.productName : ''}`" />
          </el-select>
        </label>
        <label>工序任务
          <el-select v-model="taskId" :disabled="!batchId" placeholder="选工序" data-testid="weigh-task" style="width: 240px">
            <el-option v-for="t in tasks" :key="t.id" :value="t.id" :label="`第 ${t.processOrder} 道 · ${t.processName || '工序'}`" />
          </el-select>
        </label>
      </div>
      <p v-if="batchErr" class="bad">{{ batchErr }}</p>
      <p v-if="taskErr" class="bad">{{ taskErr }}</p>
      <p v-if="!batchErr && batches.length === 0" class="muted">现在没有进行中的生产批次。批次开工后才能称重报工。</p>
    </el-card>

    <template v-if="task">
      <el-card shadow="never" class="card">
        <h2>报一笔 · {{ batch?.batchNumber }} · 第 {{ task.processOrder }} 道 {{ task.processName }}</h2>
        <el-form label-position="top" class="form" @submit.prevent="save">
          <div class="grid">
            <el-form-item label="投入重量 (kg)" :error="fieldErr('inputKg')">
              <el-input v-model="draft.inputKg" inputmode="decimal" data-testid="weigh-input" />
            </el-form-item>
            <el-form-item label="良品毛重 (kg，含容器)" :error="fieldErr('goodGrossKg')">
              <el-input v-model="draft.goodGrossKg" inputmode="decimal" data-testid="weigh-gross" />
              <div v-if="fieldWarn('goodGrossKg')" class="warn" data-testid="weigh-warn-gross">{{ fieldWarn('goodGrossKg') }}</div>
            </el-form-item>
            <el-form-item label="皮重 (kg，容器 / 托盘)" :error="fieldErr('tareKg')">
              <el-input v-model="draft.tareKg" inputmode="decimal" placeholder="没有容器就不填" data-testid="weigh-tare" />
            </el-form-item>
            <el-form-item label="不良重量 (kg)" :error="fieldErr('defectKg')">
              <el-input v-model="draft.defectKg" inputmode="decimal" placeholder="没有不良填 0" data-testid="weigh-defect" />
            </el-form-item>
            <el-form-item label="报工日期" :error="fieldErr('date')">
              <el-date-picker v-model="draft.date" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="开始时间" :error="fieldErr('start')">
              <el-time-select v-model="draft.start" start="00:00" end="23:55" step="00:05" :clearable="false" data-testid="weigh-start" style="width: 100%" />
            </el-form-item>
            <el-form-item label="结束时间" :error="fieldErr('end')">
              <el-time-select v-model="draft.end" start="00:00" end="23:55" step="00:05" :clearable="false" data-testid="weigh-end" style="width: 100%" />
              <div v-if="fieldWarn('end')" class="warn" data-testid="weigh-warn-end">{{ fieldWarn('end') }}</div>
            </el-form-item>
          </div>
          <p class="calc" data-testid="weigh-calc">
            良品净重 <b>{{ n2(check.netKg) }}</b> kg · 本笔出成率 <b>{{ pct(check.yieldRate) }}</b>
          </p>
          <p v-if="serverErr.form" class="bad" data-testid="weigh-server-error">{{ serverErr.form }}</p>
          <p v-if="savedMsg" class="ok" data-testid="weigh-saved">{{ savedMsg }}</p>
          <el-button type="primary" :loading="saving" :disabled="touched && !check.ok" data-testid="weigh-save" @click="save">保存这一笔</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="card">
        <h2>已报的称重</h2>
        <p v-if="entriesState === 'error'" class="bad">{{ entriesErr }}</p>
        <p v-else-if="entriesState === 'ok'" class="muted" data-testid="weigh-totals">
          共 {{ totals.count }} 笔 · 投入合计 {{ n2(totals.input) }} kg · 良品净重合计 {{ n2(totals.good) }} kg · 工序出成率 {{ pct(totals.rate) }}
          <template v-if="totals.suspects"> · <span class="suspect-n">{{ totals.suspects }} 笔数据疑点</span></template>
        </p>
        <el-table v-if="entries.length" :data="entries" size="small" data-testid="weigh-entries">
          <el-table-column label="时间" min-width="120">
            <template #default="{ row }">{{ row.reportDate }} {{ entryTime(row) }}</template>
          </el-table-column>
          <el-table-column label="投入" width="90"><template #default="{ row }">{{ n2(row.inputQuantity) }}</template></el-table-column>
          <el-table-column label="良品净重" width="90"><template #default="{ row }">{{ n2(row.outputQuantity) }}</template></el-table-column>
          <el-table-column label="皮重" width="80"><template #default="{ row }">{{ row.tareWeight === null ? '没录' : n2(row.tareWeight) }}</template></el-table-column>
          <el-table-column label="不良" width="80"><template #default="{ row }">{{ row.defectQuantity === null ? '没清点' : n2(row.defectQuantity) }}</template></el-table-column>
          <el-table-column label="出成率" width="90"><template #default="{ row }">{{ pct(entryYield(row)) }}</template></el-table-column>
          <el-table-column label="数据疑点" min-width="220">
            <template #default="{ row }">
              <div v-if="row.suspects.length" class="suspect" :data-testid="`weigh-suspect-${row.reportId}`">
                <span class="tag">数据疑点 · 找谁核：{{ row.whoToCheck }}</span>
                <div class="reasons">{{ row.suspects.join('；') }}</div>
              </div>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <p v-else-if="entriesState === 'ok'" class="muted">这道工序还没有称重记录。</p>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.weigh { padding: 16px 0; max-width: 1040px; }
.head h1 { margin: 0; font-size: var(--cs-type-title); color: var(--cs-text); }
.why { margin: 4px 0 12px; color: var(--cs-muted); font-size: var(--cs-type-body); max-width: 760px; }
.card { margin-bottom: 12px; }
h2 { margin: 0 0 10px; font-size: var(--cs-type-body); color: var(--cs-text); }
.pick { display: flex; gap: 16px; flex-wrap: wrap; }
.pick label { display: grid; gap: 4px; font-size: var(--cs-type-caption); color: var(--cs-muted); }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 0 16px; }
.calc { margin: 0 0 10px; color: var(--cs-text); font-size: var(--cs-type-body); }
.warn { margin-top: 4px; line-height: 1.4; color: var(--cs-warning); font-size: var(--cs-type-caption); }
.bad { margin: 4px 0 8px; color: var(--cs-danger); font-size: var(--cs-type-caption); }
.ok { margin: 4px 0 8px; color: var(--cs-success); font-size: var(--cs-type-caption); }
.muted { margin: 4px 0; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.suspect .tag { display: inline-block; padding: 1px 8px; border-radius: 10px; border: 1px solid var(--cs-warning); color: var(--cs-warning); font-size: var(--cs-type-caption); }
.suspect .reasons { margin-top: 2px; color: var(--cs-muted); font-size: var(--cs-type-caption); }
.suspect-n { color: var(--cs-warning); }
@media (max-width: 640px) { .pick :deep(.el-select) { width: 100% !important; } }
</style>
