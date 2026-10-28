<script setup lang="ts">
/**
 * S3-16 锅次面板 (生产批次详情): 本批次每一锅的下锅/出锅、出锅量、工艺参数、用料与去向,
 * 投入/产出分摊进度, 以及录入 / 更正 / 确认 / 作废。
 *
 * 锅次时间是设备/锅次事件, 与人工工时分开 (人工工时在报工里); 摄像候选时间只算候选, 主管确认后才算时长。
 */
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';
import {
  confirmPot, correctPot, isPotPending, listBatchPots, recordPot, voidPot, TIME_SOURCE_LABEL,
  type BatchPotsView, type PotAllocationLine, type PotLink, type PotTimeSource, type PotView,
} from '@/api/productionPot';

const props = defineProps<{
  factoryId: string;
  productionBatchId: number;
  batchNumber: string;
  canWrite: boolean;
}>();

const view = ref<BatchPotsView | null>(null);
const loading = ref(false);
const loadError = ref('');

async function load() {
  if (!props.factoryId || !props.productionBatchId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await listBatchPots(props.factoryId, props.productionBatchId);
    view.value = res.success ? res.data : null;
    if (!res.success) loadError.value = res.message || '锅次加载失败';
  } catch (e) {
    view.value = null;
    loadError.value = '锅次加载失败';
    handleCatchError(e, '锅次加载失败');
  } finally {
    loading.value = false;
  }
}
watch(() => [props.factoryId, props.productionBatchId], load, { immediate: true });

const pots = computed(() => view.value?.pots ?? []);
const activePots = computed(() => pots.value.filter(p => p.status !== 'VOIDED'));

function nodeLabel(l: { batchNumber: string | null; nodeId: string }) {
  return l.batchNumber || l.nodeId;
}
function qty(v: number | null | undefined, unit?: string | null) {
  return v == null ? '-' : `${Number(v)}${unit ? ' ' + unit : ''}`;
}
function params(p: PotView) {
  if (p.processRedacted) return '工艺保密，需授权查看';
  const entries = Object.entries(p.processParams || {});
  return entries.length ? entries.map(([k, v]) => `${PARAM_LABEL[k] ?? k} ${v}`).join('，') : '-';
}
const PARAM_LABEL: Record<string, string> = { temperatureC: '温度℃', durationMin: '时长min', pressureKpa: '压力kPa' };

/** 某节点还能分到锅上的量 (Rule 1: 先显示边界)。 */
function remaining(line: PotAllocationLine, excludePot?: PotView, side?: 'in' | 'out'): number | null {
  if (line.batchQuantity == null) return null;
  let own = 0;
  if (excludePot) {
    const list = side === 'out' ? excludePot.outputs : excludePot.inputs;
    own = list.filter(l => l.nodeId === line.nodeId).reduce((s, l) => s + Number(l.quantity), 0);
  }
  return Math.max(0, Number(line.batchQuantity) - Number(line.allocatedQuantity) + own);
}

// ==================== 录入 / 更正 ====================
interface LinkDraft { nodeType: string; nodeId: string; label: string; unit: string | null; max: number | null; quantity: number | null }
interface PotForm {
  potNumber: number | null; equipmentLabel: string; startedAt: string | null; endedAt: string | null;
  timeSource: PotTimeSource; outputQuantity: number | null; outputUnit: string;
  temperatureC: number | null; durationMin: number | null; remark: string;
  inputs: LinkDraft[]; outputs: LinkDraft[];
}
const dialogVisible = ref(false);
const editing = ref<PotView | null>(null);
const saving = ref(false);
const form = ref<PotForm>(emptyForm());
const REASONS = ['录入笔误', '复秤更正', '补录用料/去向', '时间核对后更正', '其他'];
const reasonPick = ref('');
const reasonOther = ref('');
const reasonText = computed(() => (reasonPick.value === '其他' ? reasonOther.value.trim() : reasonPick.value));

function emptyForm(): PotForm {
  return { potNumber: null, equipmentLabel: '', startedAt: null, endedAt: null, timeSource: 'MANUAL',
    outputQuantity: null, outputUnit: '', temperatureC: null, durationMin: null, remark: '', inputs: [], outputs: [] };
}
function drafts(lines: PotAllocationLine[], pot: PotView | null, side: 'in' | 'out'): LinkDraft[] {
  return lines.map(l => {
    const mine = pot ? (side === 'in' ? pot.inputs : pot.outputs).find(x => x.nodeId === l.nodeId) : undefined;
    return { nodeType: l.nodeType, nodeId: l.nodeId, label: nodeLabel(l), unit: l.unit,
      max: remaining(l, pot ?? undefined, side), quantity: mine ? Number(mine.quantity) : null };
  });
}
function openCreate() {
  editing.value = null;
  const next = activePots.value.reduce((m, p) => Math.max(m, p.potNumber), 0) + 1;
  form.value = { ...emptyForm(), potNumber: next,
    inputs: drafts(view.value?.inputAllocation ?? [], null, 'in'),
    outputs: drafts(view.value?.outputAllocation ?? [], null, 'out') };
  dialogVisible.value = true;
}
function openEdit(p: PotView) {
  editing.value = p;
  reasonPick.value = '';
  reasonOther.value = '';
  form.value = {
    potNumber: p.potNumber, equipmentLabel: p.equipmentLabel ?? '', startedAt: p.startedAt, endedAt: p.endedAt,
    timeSource: p.timeSource, outputQuantity: p.outputQuantity == null ? null : Number(p.outputQuantity),
    outputUnit: p.outputUnit ?? '',
    temperatureC: typeof p.processParams?.temperatureC === 'number' ? (p.processParams.temperatureC as number) : null,
    durationMin: typeof p.processParams?.durationMin === 'number' ? (p.processParams.durationMin as number) : null,
    remark: p.remark ?? '',
    inputs: drafts(view.value?.inputAllocation ?? [], p, 'in'),
    outputs: drafts(view.value?.outputAllocation ?? [], p, 'out'),
  };
  dialogVisible.value = true;
}
const overLimit = computed(() => [...form.value.inputs, ...form.value.outputs]
  .some(d => d.quantity != null && d.max != null && d.quantity > d.max));
const timeInvalid = computed(() => Boolean(form.value.startedAt && form.value.endedAt
  && new Date(form.value.endedAt) < new Date(form.value.startedAt)));
const canSubmit = computed(() => Boolean(form.value.potNumber && form.value.potNumber > 0)
  && !overLimit.value && !timeInvalid.value && (!editing.value || Boolean(reasonText.value)));

function links(list: LinkDraft[]): PotLink[] {
  return list.filter(d => d.quantity != null && d.quantity > 0)
    .map(d => ({ nodeType: d.nodeType as PotLink['nodeType'], nodeId: d.nodeId, quantity: Number(d.quantity), unit: d.unit }));
}
function processParams(): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  if (form.value.temperatureC != null) out.temperatureC = form.value.temperatureC;
  if (form.value.durationMin != null) out.durationMin = form.value.durationMin;
  return out;
}
async function submit() {
  if (!canSubmit.value) return;
  saving.value = true;
  try {
    const f = form.value;
    if (editing.value) {
      await correctPot(props.factoryId, editing.value.id, {
        potNumber: f.potNumber ?? undefined, equipmentLabel: f.equipmentLabel, startedAt: f.startedAt, endedAt: f.endedAt,
        timeSource: f.timeSource, outputQuantity: f.outputQuantity, outputUnit: f.outputUnit,
        // 工艺保密时看不到原值: 不回传 (否则空值会把看不见的工艺参数 / 备注覆盖掉)
        remark: editing.value.processRedacted ? undefined : f.remark,
        processParams: editing.value.processRedacted ? undefined : processParams(),
        inputs: links(f.inputs), outputs: links(f.outputs), reason: reasonText.value,
      });
      ElMessage.success(`第 ${f.potNumber} 锅已更正，原值已留痕`);
    } else {
      await recordPot(props.factoryId, {
        productionBatchId: props.productionBatchId, potNumber: f.potNumber!, equipmentLabel: f.equipmentLabel || null,
        startedAt: f.startedAt, endedAt: f.endedAt, timeSource: f.timeSource, outputQuantity: f.outputQuantity,
        outputUnit: f.outputUnit || null, processParams: processParams(), inputs: links(f.inputs),
        outputs: links(f.outputs), remark: f.remark || null,
      });
      ElMessage.success(`第 ${f.potNumber} 锅已记录`);
    }
    dialogVisible.value = false;
    await load();
  } catch (e) {
    handleCatchError(e, editing.value ? '锅次更正失败' : '锅次记录失败');
  } finally {
    saving.value = false;
  }
}

// ==================== 确认 / 作废 ====================
const actionVisible = ref(false);
const actionKind = ref<'confirm' | 'void'>('confirm');
const actionPot = ref<PotView | null>(null);
const actionStarted = ref<string | null>(null);
const actionEnded = ref<string | null>(null);
const VOID_REASONS = ['录错批次', '重复录入', '这一锅没有实际生产', '其他'];
const CONFIRM_REASONS = ['已核对监控与锅边记录', '已核对设备记录', '操作员当面确认', '其他'];
function openAction(kind: 'confirm' | 'void', p: PotView) {
  actionKind.value = kind;
  actionPot.value = p;
  actionStarted.value = p.startedAt;
  actionEnded.value = p.endedAt;
  reasonPick.value = '';
  reasonOther.value = '';
  actionVisible.value = true;
}
const confirmTimesMissing = computed(() => actionKind.value === 'confirm' && !(actionStarted.value && actionEnded.value)
  && actionPot.value?.bindingStatus !== 'PENDING_CONFIRMATION');
async function submitAction() {
  if (!actionPot.value || !reasonText.value || confirmTimesMissing.value) return;
  saving.value = true;
  try {
    if (actionKind.value === 'void') {
      await voidPot(props.factoryId, actionPot.value.id, reasonText.value);
      ElMessage.success(`第 ${actionPot.value.potNumber} 锅已作废（记录保留）`);
    } else {
      await confirmPot(props.factoryId, actionPot.value.id,
        { startedAt: actionStarted.value, endedAt: actionEnded.value, reason: reasonText.value });
      ElMessage.success(`第 ${actionPot.value.potNumber} 锅已确认`);
    }
    actionVisible.value = false;
    await load();
  } catch (e) {
    handleCatchError(e, actionKind.value === 'void' ? '作废失败' : '确认失败');
  } finally {
    saving.value = false;
  }
}

defineExpose({ reload: load });
</script>

<template>
  <el-card shadow="never" class="detail-card" data-testid="production-pots-panel">
    <template #header>
      <div class="pot-header">
        <span class="section-title">锅次</span>
        <span v-if="view" class="pot-sub">
          共 {{ view.potCount }} 锅<template v-if="view.pendingCount > 0">，<el-tag type="warning" size="small">{{ view.pendingCount }} 锅待确认</el-tag></template>
        </span>
        <el-button v-if="canWrite && view" type="primary" size="small" data-testid="pot-add" @click="openCreate">录入一锅</el-button>
      </div>
    </template>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />
    <div v-else-if="loading" class="pot-empty">加载中…</div>
    <template v-else-if="view">
      <p class="pot-note">锅次时间是下锅 / 出锅事件，人工工时仍以报工为准；摄像候选时间须主管确认后才计时长。</p>
      <el-empty v-if="pots.length === 0" description="本批次还没有锅次记录：召回时按整批计算" :image-size="60">
        <el-button v-if="canWrite" type="primary" size="small" @click="openCreate">录入第一锅</el-button>
      </el-empty>
      <el-table v-else :data="pots" size="small" border data-testid="pot-table" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="pot-expand">
              <div><b>用料：</b>
                <span v-if="row.inputs.length === 0" class="pot-warn">未分摊（召回时按整批算）</span>
                <span v-for="i in row.inputs" :key="i.nodeId" class="pot-chip">{{ nodeLabel(i) }} {{ qty(i.quantity, i.unit) }}</span>
              </div>
              <div><b>去向：</b>
                <span v-if="row.outputs.length === 0" class="pot-warn">未分摊</span>
                <span v-for="o in row.outputs" :key="o.nodeId" class="pot-chip">{{ nodeLabel(o) }} {{ qty(o.quantity, o.unit) }}</span>
              </div>
              <div v-if="row.corrections.length > 0" data-testid="pot-corrections">
                <b>更正记录：</b>
                <div v-for="(c, i) in row.corrections" :key="i" class="pot-correction">
                  {{ formatDateTime(c.correctedAt) }} {{ c.correctedByName || '' }}
                  {{ c.action === 'VOID' ? '作废' : c.action === 'CONFIRM' ? '确认' : '更正' }}
                  {{ c.fieldName }}：{{ c.oldValue ?? '空' }} → {{ c.newValue ?? '空' }}（{{ c.reason }}）
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="锅号" width="70">
          <template #default="{ row }">第 {{ row.potNumber }} 锅</template>
        </el-table-column>
        <el-table-column label="工序 / 锅" min-width="120">
          <template #default="{ row }">{{ row.processName || row.processCode || '-' }}<br><span class="pot-sub">{{ row.equipmentLabel || '' }}</span></template>
        </el-table-column>
        <el-table-column label="下锅" min-width="130">
          <template #default="{ row }">{{ row.startedAt ? formatDateTime(row.startedAt) : '未知' }}</template>
        </el-table-column>
        <el-table-column label="出锅" min-width="130">
          <template #default="{ row }">{{ row.endedAt ? formatDateTime(row.endedAt) : '未知' }}</template>
        </el-table-column>
        <el-table-column label="锅次时长" width="110">
          <template #default="{ row }">
            <span v-if="row.durationMinutes != null">{{ row.durationMinutes }} 分钟</span>
            <el-tag v-else type="warning" size="small">待确认</el-tag>
            <div class="pot-sub">{{ TIME_SOURCE_LABEL[row.timeSource as PotTimeSource] }}</div>
          </template>
        </el-table-column>
        <el-table-column label="出锅量" width="100">
          <template #default="{ row }">{{ qty(row.outputQuantity, row.outputUnit) }}</template>
        </el-table-column>
        <el-table-column label="工艺参数" min-width="130">
          <template #default="{ row }">{{ params(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'VOIDED'" type="info" size="small">已作废</el-tag>
            <el-tag v-else-if="row.bindingStatus === 'PENDING_CONFIRMATION'" type="warning" size="small">关联待确认</el-tag>
            <el-tag v-else-if="isPotPending(row)" type="warning" size="small">时间待确认</el-tag>
            <el-tag v-else type="success" size="small">已确认</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'VOIDED'">
              <el-button link type="primary" size="small" @click="openEdit(row)">更正</el-button>
              <el-button v-if="isPotPending(row)" link type="warning" size="small" @click="openAction('confirm', row)">确认</el-button>
              <el-button link type="danger" size="small" @click="openAction('void', row)">作废</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="pots.length > 0" class="pot-alloc" data-testid="pot-allocation">
        <div v-for="line in view.inputAllocation" :key="'in' + line.nodeId">
          用料 {{ nodeLabel(line) }}：已分到锅上 {{ qty(line.allocatedQuantity, line.unit) }} / 批次 {{ qty(line.batchQuantity, line.unit) }}
          <el-tag :type="line.complete ? 'success' : 'warning'" size="small">{{ line.complete ? '已分完，可按锅召回' : '未分完，召回按整批' }}</el-tag>
        </div>
        <div v-for="line in view.outputAllocation" :key="'out' + line.nodeId">
          产出 {{ nodeLabel(line) }}：已分到锅上 {{ qty(line.allocatedQuantity, line.unit) }} / 批次 {{ qty(line.batchQuantity, line.unit) }}
        </div>
      </div>
    </template>

    <!-- 录入 / 更正 -->
    <el-dialog v-model="dialogVisible" :title="`${editing ? '更正' : '录入'}锅次 — 第 ${form.potNumber ?? '?'} 锅 (${batchNumber})`" width="640px" append-to-body>
      <el-form label-width="96px" size="small">
        <el-form-item label="锅号" required>
          <el-input-number v-model="form.potNumber" :min="1" :step="1" step-strictly />
        </el-form-item>
        <el-form-item label="锅 / 设备"><el-input v-model="form.equipmentLabel" placeholder="如 2 号夹层锅" /></el-form-item>
        <el-form-item label="时间来源">
          <el-radio-group v-model="form.timeSource">
            <el-radio-button value="MANUAL">人工录入</el-radio-button>
            <el-radio-button value="DEVICE">设备</el-radio-button>
            <el-radio-button value="CAMERA_CANDIDATE">摄像候选</el-radio-button>
            <el-radio-button value="UNKNOWN">未知</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.timeSource !== 'UNKNOWN'">
          <el-form-item label="下锅时间"><el-date-picker v-model="form.startedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
          <el-form-item label="出锅时间" :error="timeInvalid ? '出锅时间不能早于下锅时间' : ''">
            <el-date-picker v-model="form.endedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
          </el-form-item>
        </template>
        <p v-if="form.timeSource === 'CAMERA_CANDIDATE'" class="pot-note">摄像候选时间只是候选，保存后需主管确认才计时长。</p>
        <el-form-item label="出锅量">
          <el-input-number v-model="form.outputQuantity" :min="0" :precision="3" :controls="false" />
          <el-input v-model="form.outputUnit" placeholder="单位" style="width: 80px; margin-left: 8px" />
        </el-form-item>
        <template v-if="!editing?.processRedacted">
          <el-form-item label="温度 ℃"><el-input-number v-model="form.temperatureC" :controls="false" /></el-form-item>
          <el-form-item label="时长 min"><el-input-number v-model="form.durationMin" :min="0" :controls="false" /></el-form-item>
        </template>
        <p v-else class="pot-note">该产品工艺保密：工艺参数和备注不显示、也不能在这里修改。</p>
        <el-form-item label="这一锅用料">
          <div class="pot-links">
            <p v-if="form.inputs.length === 0" class="pot-sub">本批次还没有用料记录</p>
            <div v-for="d in form.inputs" :key="d.nodeId" class="pot-link-row">
              <span class="pot-link-label">{{ d.label }}</span>
              <el-input-number v-model="d.quantity" :min="0" :max="d.max ?? undefined" :precision="3" :controls="false" />
              <span class="pot-sub">{{ d.unit || '' }}，还可分 {{ d.max == null ? '（批次量未知）' : d.max }}</span>
            </div>
            <p class="pot-sub">不知道这一锅用了哪批料就留空：召回时这一批按整批算，不会猜。</p>
          </div>
        </el-form-item>
        <el-form-item label="这一锅去向">
          <div class="pot-links">
            <p v-if="form.outputs.length === 0" class="pot-sub">本批次还没有产出批次</p>
            <div v-for="d in form.outputs" :key="d.nodeId" class="pot-link-row">
              <span class="pot-link-label">{{ d.label }}</span>
              <el-input-number v-model="d.quantity" :min="0" :max="d.max ?? undefined" :precision="3" :controls="false" />
              <span class="pot-sub">{{ d.unit || '' }}，还可分 {{ d.max == null ? '（批次量未知）' : d.max }}</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="!editing?.processRedacted" label="备注"><el-input v-model="form.remark" /></el-form-item>
        <el-form-item v-if="editing" label="更正原因" required>
          <el-select v-model="reasonPick" placeholder="请选择" style="width: 200px">
            <el-option v-for="r in REASONS" :key="r" :label="r" :value="r" />
          </el-select>
          <el-input v-if="reasonPick === '其他'" v-model="reasonOther" placeholder="请写明原因" style="margin-top: 6px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!canSubmit" :loading="saving" data-testid="pot-submit" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 确认 / 作废 -->
    <el-dialog v-model="actionVisible" :title="`${actionKind === 'void' ? '作废' : '确认'}锅次 — 第 ${actionPot?.potNumber ?? ''} 锅 (${batchNumber})`" width="480px" append-to-body>
      <el-form label-width="90px" size="small">
        <template v-if="actionKind === 'confirm'">
          <el-form-item label="下锅时间"><el-date-picker v-model="actionStarted" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
          <el-form-item label="出锅时间"><el-date-picker v-model="actionEnded" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
          <p v-if="confirmTimesMissing" class="pot-warn">下锅和出锅时间都要填，才能确认时间。</p>
        </template>
        <p v-else class="pot-note">作废后记录保留、不再计入召回和分摊，锅号可以重新使用。</p>
        <el-form-item label="原因" required>
          <el-select v-model="reasonPick" placeholder="请选择" style="width: 220px">
            <el-option v-for="r in (actionKind === 'void' ? VOID_REASONS : CONFIRM_REASONS)" :key="r" :label="r" :value="r" />
          </el-select>
          <el-input v-if="reasonPick === '其他'" v-model="reasonOther" placeholder="请写明原因" style="margin-top: 6px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button :type="actionKind === 'void' ? 'danger' : 'primary'" :disabled="!reasonText || confirmTimesMissing" :loading="saving" @click="submitAction">
          {{ actionKind === 'void' ? '作废' : '确认' }}
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.pot-header { display: flex; align-items: center; gap: 12px; }
.pot-header .el-button { margin-left: auto; }
.pot-sub { color: var(--el-text-color-secondary); font-size: 12px; }
.pot-note { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 8px; }
.pot-warn { color: var(--el-color-warning); }
.pot-chip { display: inline-block; margin-right: 12px; }
.pot-expand { padding: 4px 16px; line-height: 1.9; }
.pot-correction { font-size: 12px; color: var(--el-text-color-regular); }
.pot-alloc { margin-top: 10px; font-size: 13px; line-height: 2; }
.pot-links { width: 100%; }
.pot-link-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.pot-link-label { min-width: 140px; }
</style>
