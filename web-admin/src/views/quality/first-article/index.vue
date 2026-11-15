<script setup lang="ts">
/**
 * 工序首检 (原型统一 E 线 BL17; 设计 docs/specs/process-first-article-inspection-design.md)。
 * 三个页签: 工序首检 (要首检的工序任务 + 首检状态) / 首检单 / 首检标准; 顶部是本厂首检开关 (默认关)。
 * 写操作一律「预览 → 确认」: 存标准、判定先调服务端 preview, 送检 / 作废 / 改开关先在确认层里写明会发生什么。
 * 按钮只是方便; 谁能判定以服务端 @RequirePermission 为准 (质量写权限), 被拒时把服务端原话显示出来。
 * 组件全部 Nuxt UI + --cs-*; 错误提示用 ElMessage (命令式服务)。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { CsTableColumn as TableColumn, CsTabItem as TabsItem } from '@/types/csTable';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { getActiveWorkProcesses, type WorkProcessItem } from '@/api/processProduction';
import {
  deactivateStandard, getInspection, getPolicy, judgeInspection, listInspections, listStandards, listTasks,
  MODE_HINT, MODE_LABEL, previewJudge, previewStandard, requestInspection, saveStandard, STATUS_COLOR,
  STATUS_LABEL, TASK_FA_COLOR, updatePolicy, voidInspection,
  type FirstArticleMode, type FirstArticleStatus, type InspectionView, type JudgePreview, type PolicyView,
  type StandardPreview, type StandardRequest, type StandardView, type TaskRow, type Verdict,
} from '@/api/firstArticle';
import { buildResults, draftsToItems, emptyDraft, itemToDraft, rowActions, type ItemDraft } from './firstArticleForm';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId || '');
const canJudge = computed(() => permissionStore.canWrite('quality'));
const canRequest = computed(() => permissionStore.canWrite('production') || permissionStore.canWrite('quality'));
const canEditPolicy = computed(() => ['factory_super_admin', 'permission_admin'].includes(String(authStore.currentRole || '')));

function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback;
}
function time(v: string | null | undefined) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—';
}

// ==================== 开关 ====================
const policy = ref<PolicyView | null>(null);
const policyFailed = ref(false);
const modeDraft = ref<FirstArticleMode>('OFF');
const policyConfirm = ref(false);
const MODE_ITEMS = (['OFF', 'WARN', 'BLOCK'] as FirstArticleMode[]).map((m) => ({ label: MODE_LABEL[m], value: m }));

async function loadPolicy() {
  policyFailed.value = false;
  try {
    const res = await getPolicy(factoryId.value);
    if (res.success && res.data) { policy.value = res.data; modeDraft.value = res.data.mode; }
    else policyFailed.value = true;
  } catch { policyFailed.value = true; }
}
async function confirmPolicy() {
  try {
    const res = await updatePolicy(factoryId.value, modeDraft.value);
    if (!res.success) { ElMessage.error(res.message || '保存失败'); return; }
    policy.value = res.data;
    policyConfirm.value = false;
    ElMessage.success(`首检开关已改为「${MODE_LABEL[modeDraft.value]}」`);
  } catch (e) { ElMessage.error(errorText(e, '保存失败')); }
}

// ==================== 页签 ====================
const activeTab = ref<'tasks' | 'inspections' | 'standards'>('tasks');
const TABS: TabsItem[] = [
  { label: '工序首检', value: 'tasks', slot: 'tasks' },
  { label: '首检单', value: 'inspections', slot: 'inspections' },
  { label: '首检标准', value: 'standards', slot: 'standards' },
];

// ==================== 工序首检表 ====================
const onlyInProgress = ref(true);
const taskRows = ref<TaskRow[]>([]);
const taskTotal = ref(0);
const taskPage = ref(1);
const taskLoading = ref(false);
const taskFailed = ref(false);
const taskColumns: TableColumn<TaskRow>[] = [
  { id: 'batch', header: '批次' },
  { id: 'process', header: '工序' },
  { accessorKey: 'productTypeId', header: '产品' },
  { id: 'taskStatus', header: '任务状态' },
  { id: 'started', header: '开工时间' },
  { accessorKey: 'standardName', header: '首检标准' },
  { id: 'fa', header: '首检状态' },
  { id: 'actions', header: '操作' },
];
async function loadTasks() {
  taskLoading.value = true;
  taskFailed.value = false;
  try {
    const res = await listTasks(factoryId.value, { onlyInProgress: onlyInProgress.value, page: taskPage.value, size: 20 });
    if (res.success && res.data) { taskRows.value = res.data.content ?? []; taskTotal.value = res.data.totalElements ?? 0; }
    else { taskFailed.value = true; ElMessage.error(res.message || '工序首检表加载失败'); }
  } catch (e) { taskFailed.value = true; ElMessage.error(errorText(e, '工序首检表加载失败')); }
  finally { taskLoading.value = false; }
}
function faColor(row: TaskRow) {
  return TASK_FA_COLOR[row.firstArticleStatus];
}
function inspectionColor(i: InspectionView) {
  return STATUS_COLOR[i.status];
}
function itemNames(s: StandardView) {
  return s.items.map((i) => i.name).join('、');
}
function actionsOf(row: TaskRow) {
  return rowActions(row, { canRequest: canRequest.value, canJudge: canJudge.value });
}

// ==================== 送检 (确认层) ====================
const requestTarget = ref<TaskRow | null>(null);
const requestForm = ref({ quantity: '', unit: '', notes: '' });
const requestSubmitting = ref(false);
function openRequest(row: TaskRow) {
  requestTarget.value = row;
  requestForm.value = { quantity: '', unit: '', notes: '' };
}
async function confirmRequest() {
  if (!requestTarget.value) return;
  const q = requestForm.value.quantity.trim();
  const quantity = q ? Number(q) : null;
  if (quantity !== null && !Number.isFinite(quantity)) { ElMessage.error('送检数量不是数字'); return; }
  requestSubmitting.value = true;
  try {
    const res = await requestInspection(factoryId.value, {
      taskId: requestTarget.value.taskId, sampleQuantity: quantity,
      sampleUnit: requestForm.value.unit.trim() || null, notes: requestForm.value.notes.trim() || null,
    });
    if (!res.success) { ElMessage.error(res.message || '送检失败'); return; }
    ElMessage.success('已送检，等质检判定');
    requestTarget.value = null;
    await Promise.all([loadTasks(), loadInspections()]);
  } catch (e) { ElMessage.error(errorText(e, '送检失败')); }
  finally { requestSubmitting.value = false; }
}

// ==================== 判定 (预览 → 确认) ====================
const judgeTarget = ref<InspectionView | null>(null);
const judgeValues = ref<Record<string, string>>({});
const judgeVerdict = ref<Verdict | null>(null);
const judgeNotes = ref('');
const judgePreviewData = ref<JudgePreview | null>(null);
const judgeBusy = ref(false);
const VERDICT_ITEMS = [{ label: '首检合格', value: 'PASSED' }, { label: '首检不合格', value: 'FAILED' }];
async function openJudge(inspectionId: number) {
  try {
    const res = await getInspection(factoryId.value, inspectionId);
    if (!res.success || !res.data) { ElMessage.error(res.message || '首检单读取失败'); return; }
    judgeTarget.value = res.data;
    judgeValues.value = {};
    judgeVerdict.value = null;
    judgeNotes.value = '';
    judgePreviewData.value = null;
  } catch (e) { ElMessage.error(errorText(e, '首检单读取失败')); }
}
function judgeBody() {
  const built = buildResults(judgeTarget.value?.standardItems ?? [], judgeValues.value);
  if (built.error) { ElMessage.error(built.error); return null; }
  return { results: built.results, verdict: judgeVerdict.value, notes: judgeNotes.value.trim() || null };
}
async function runJudgePreview() {
  if (!judgeTarget.value) return;
  const body = judgeBody();
  if (!body) return;
  judgeBusy.value = true;
  try {
    const res = await previewJudge(factoryId.value, judgeTarget.value.id, body);
    if (!res.success || !res.data) { judgePreviewData.value = null; ElMessage.error(res.message || '预览失败'); return; }
    judgePreviewData.value = res.data;
    if (!judgeVerdict.value && res.data.autoVerdict) judgeVerdict.value = res.data.autoVerdict;
  } catch (e) { judgePreviewData.value = null; ElMessage.error(errorText(e, '预览失败')); }
  finally { judgeBusy.value = false; }
}
function onJudgeInputChanged() {
  judgePreviewData.value = null; // 改了输入, 旧预览作废, 必须重新预览才能确认
}
async function confirmJudge() {
  if (!judgeTarget.value) return;
  const body = judgeBody();
  if (!body) return;
  judgeBusy.value = true;
  try {
    const check = await previewJudge(factoryId.value, judgeTarget.value.id, body);
    if (!check.success || !check.data) { ElMessage.error(check.message || '预览失败'); return; }
    judgePreviewData.value = check.data;
    if (!check.data.canSubmit) { ElMessage.warning(check.data.message); return; }
    const res = await judgeInspection(factoryId.value, judgeTarget.value.id, body);
    if (!res.success) { ElMessage.error(res.message || '判定失败'); return; }
    ElMessage.success(`已判定：${res.data ? STATUS_LABEL[res.data.status] : ''}`);
    judgeTarget.value = null;
    await Promise.all([loadTasks(), loadInspections()]);
  } catch (e) { ElMessage.error(errorText(e, '判定失败')); }
  finally { judgeBusy.value = false; }
}

// ==================== 首检单 ====================
const inspectionRows = ref<InspectionView[]>([]);
const inspectionTotal = ref(0);
const inspectionPage = ref(1);
const inspectionStatus = ref<FirstArticleStatus | 'ALL'>('ALL');
const inspectionLoading = ref(false);
const inspectionFailed = ref(false);
const INSPECTION_STATUS_ITEMS = [
  { label: '全部状态', value: 'ALL' },
  ...(['PENDING', 'PASSED', 'FAILED', 'VOID'] as FirstArticleStatus[]).map((s) => ({ label: STATUS_LABEL[s], value: s })),
];
const inspectionColumns: TableColumn<InspectionView>[] = [
  { accessorKey: 'id', header: '单号' },
  { id: 'process', header: '工序' },
  { accessorKey: 'taskId', header: '工序任务' },
  { id: 'standard', header: '标准' },
  { id: 'status', header: '状态' },
  { id: 'sample', header: '送检数量' },
  { id: 'requested', header: '送检时间' },
  { id: 'judged', header: '判定时间' },
  { id: 'notes', header: '说明' },
  { id: 'actions', header: '操作' },
];
async function loadInspections() {
  inspectionLoading.value = true;
  inspectionFailed.value = false;
  try {
    const res = await listInspections(factoryId.value, {
      status: inspectionStatus.value === 'ALL' ? null : inspectionStatus.value, page: inspectionPage.value, size: 20,
    });
    if (res.success && res.data) { inspectionRows.value = res.data.content ?? []; inspectionTotal.value = res.data.totalElements ?? 0; }
    else { inspectionFailed.value = true; ElMessage.error(res.message || '首检单加载失败'); }
  } catch (e) { inspectionFailed.value = true; ElMessage.error(errorText(e, '首检单加载失败')); }
  finally { inspectionLoading.value = false; }
}
function inspectionNote(i: InspectionView) {
  if (i.status === 'VOID') return `作废：${i.voidReason ?? ''}`;
  const failed = (i.results ?? []).filter((r) => r.pass === false).map((r) => r.name);
  const parts = [failed.length ? `不合格项：${failed.join('、')}` : '', i.judgeNotes ?? '', i.requestNotes ?? ''];
  return parts.filter(Boolean).join('；') || '—';
}

// ==================== 作废 (确认层, 写原因) ====================
const voidTarget = ref<InspectionView | null>(null);
const voidReason = ref('');
async function confirmVoid() {
  if (!voidTarget.value) return;
  if (!voidReason.value.trim()) { ElMessage.warning('请写明作废原因'); return; }
  try {
    const res = await voidInspection(factoryId.value, voidTarget.value.id, voidReason.value.trim());
    if (!res.success) { ElMessage.error(res.message || '作废失败'); return; }
    ElMessage.success('已作废，可以重新送检');
    voidTarget.value = null;
    voidReason.value = '';
    await Promise.all([loadTasks(), loadInspections()]);
  } catch (e) { ElMessage.error(errorText(e, '作废失败')); }
}

// ==================== 首检标准 ====================
const standards = ref<StandardView[]>([]);
const includeInactive = ref(false);
const standardsLoading = ref(false);
const standardsFailed = ref(false);
const processes = ref<WorkProcessItem[]>([]);
const standardColumns: TableColumn<StandardView>[] = [
  { accessorKey: 'name', header: '标准' },
  { id: 'process', header: '工序' },
  { id: 'items', header: '检验项' },
  { id: 'state', header: '状态' },
  { id: 'updated', header: '更新时间' },
  { id: 'actions', header: '操作' },
];
async function loadStandards() {
  standardsLoading.value = true;
  standardsFailed.value = false;
  try {
    const res = await listStandards(factoryId.value, includeInactive.value);
    if (res.success && res.data) standards.value = res.data;
    else { standardsFailed.value = true; ElMessage.error(res.message || '首检标准加载失败'); }
  } catch (e) { standardsFailed.value = true; ElMessage.error(errorText(e, '首检标准加载失败')); }
  finally { standardsLoading.value = false; }
}
async function loadProcesses() {
  try {
    const res = await getActiveWorkProcesses(factoryId.value);
    if (res.success && res.data) processes.value = res.data;
  } catch { /* 工序下拉读不到时, 保存预览会报「工序不存在」; 不造假选项 */ }
}
const processItems = computed(() => processes.value.map((p) => ({ label: p.processName, value: p.id })));
const TYPE_ITEMS = [{ label: '数值', value: 'NUMERIC' }, { label: '选项', value: 'OPTION' }];

const editOpen = ref(false);
const editId = ref<number | null>(null);
const editProcessId = ref<string | undefined>(undefined);
const editName = ref('');
const editDrafts = ref<ItemDraft[]>([]);
const editPreview = ref<StandardPreview | null>(null);
const editBusy = ref(false);
function openCreate() {
  editId.value = null;
  editProcessId.value = undefined;
  editName.value = '';
  editDrafts.value = [emptyDraft('NUMERIC')];
  editPreview.value = null;
  editOpen.value = true;
}
function openEdit(s: StandardView) {
  editId.value = s.id;
  editProcessId.value = s.workProcessId;
  editName.value = s.name;
  editDrafts.value = s.items.map(itemToDraft);
  editPreview.value = null;
  editOpen.value = true;
}
function standardBody(): StandardRequest | null {
  const { items, error } = draftsToItems(editDrafts.value);
  if (error) { ElMessage.error(error); return null; }
  return { id: editId.value, workProcessId: editProcessId.value ?? null, name: editName.value, items };
}
function onEditChanged() { editPreview.value = null; }
async function runStandardPreview() {
  const body = standardBody();
  if (!body) return;
  editBusy.value = true;
  try {
    const res = await previewStandard(factoryId.value, body);
    if (!res.success || !res.data) { editPreview.value = null; ElMessage.error(res.message || '预览失败'); return; }
    editPreview.value = res.data;
  } catch (e) { editPreview.value = null; ElMessage.error(errorText(e, '预览失败')); }
  finally { editBusy.value = false; }
}
async function confirmStandard() {
  const body = standardBody();
  if (!body || !editPreview.value) return;
  editBusy.value = true;
  try {
    const res = await saveStandard(factoryId.value, body);
    if (!res.success || !res.data) { ElMessage.error(res.message || '保存失败'); return; }
    ElMessage.success(`已保存：${res.data.name} 第 ${res.data.revision} 版（${res.data.items.length} 项）`);
    editOpen.value = false;
    await Promise.all([loadStandards(), loadTasks()]);
  } catch (e) { ElMessage.error(errorText(e, '保存失败')); }
  finally { editBusy.value = false; }
}

const deactivateTarget = ref<StandardView | null>(null);
const deactivateReason = ref('');
async function confirmDeactivate() {
  if (!deactivateTarget.value) return;
  if (!deactivateReason.value.trim()) { ElMessage.warning('请写明停用原因'); return; }
  try {
    const res = await deactivateStandard(factoryId.value, deactivateTarget.value.id, deactivateReason.value.trim());
    if (!res.success) { ElMessage.error(res.message || '停用失败'); return; }
    ElMessage.success('已停用，这道工序不再要求首检');
    deactivateTarget.value = null;
    deactivateReason.value = '';
    await Promise.all([loadStandards(), loadTasks()]);
  } catch (e) { ElMessage.error(errorText(e, '停用失败')); }
}

onMounted(async () => {
  if (!factoryId.value) return;
  await Promise.all([loadPolicy(), loadTasks(), loadInspections(), loadStandards(), loadProcesses()]);
});
</script>

<template>
  <div class="page" data-testid="first-article-page">
    <section class="cs-card">
      <header class="cs-card-head">
        <h2>工序首检</h2>
        <span class="count">工序开工后先做首件检验；要不要拦报完工按本厂开关</span>
      </header>
      <div class="policy" data-testid="first-article-policy">
        <span class="label">首检开关</span>
        <template v-if="policyFailed"><span class="error">读取失败，不代表开关是关的</span></template>
        <template v-else-if="policy">
          <UBadge :color="policy.mode === 'OFF' ? 'neutral' : policy.mode === 'WARN' ? 'warning' : 'error'" variant="soft">{{ MODE_LABEL[policy.mode] }}</UBadge>
          <span class="muted">{{ MODE_HINT[policy.mode] }}<template v-if="!policy.configured">（本厂还没配置，按关处理）</template></span>
          <template v-if="canEditPolicy">
            <USelect v-model="modeDraft" :items="MODE_ITEMS" class="w-28" data-testid="first-article-mode" />
            <UButton size="sm" color="neutral" variant="outline" :disabled="modeDraft === policy.mode" @click="policyConfirm = true">保存开关</UButton>
          </template>
        </template>
      </div>
    </section>

    <UTabs v-model="activeTab" :items="TABS" variant="link" class="mt-3" :ui="{ content: 'pt-3' }">
      <template #tasks>
        <section class="cs-card">
          <div class="filters">
            <UCheckbox v-model="onlyInProgress" label="只看进行中" @update:model-value="() => { taskPage = 1; loadTasks(); }" />
            <span class="muted">共 {{ taskTotal }} 个要首检的工序任务</span>
          </div>
          <UAlert v-if="taskFailed" color="error" variant="subtle" title="加载失败，下面的列表不代表没有要首检的工序" class="mx-5 mb-3" />
          <UTable :data="taskRows" :columns="taskColumns" :loading="taskLoading" class="cs-table" data-testid="first-article-tasks">
            <template #empty>没有要首检的工序任务（先在「首检标准」里给工序配标准）</template>
            <template #batch-cell="{ row }">{{ row.original.batchNumber ?? `批次 ${row.original.productionBatchId}` }}</template>
            <template #process-cell="{ row }">{{ row.original.processName ?? row.original.workProcessId }}</template>
            <template #taskStatus-cell="{ row }">{{ row.original.taskStatusLabel }}</template>
            <template #started-cell="{ row }">{{ time(row.original.actualStartAt) }}</template>
            <template #fa-cell="{ row }">
              <UBadge :color="faColor(row.original)" variant="soft">{{ row.original.firstArticleStatusLabel }}</UBadge>
            </template>
            <template #actions-cell="{ row }">
              <div class="ops">
                <UButton v-if="actionsOf(row.original).request" size="xs" data-testid="fa-request" @click="openRequest(row.original)">送检</UButton>
                <UButton v-if="actionsOf(row.original).judge" size="xs" color="primary" variant="soft" data-testid="fa-judge" @click="openJudge(row.original.latestInspectionId!)">判定</UButton>
                <UButton v-if="row.original.latestInspectionId && !actionsOf(row.original).judge" size="xs" color="neutral" variant="link" @click="openJudge(row.original.latestInspectionId!)">查看</UButton>
              </div>
            </template>
          </UTable>
          <div class="pager">
            <UPagination v-model:page="taskPage" :items-per-page="20" :total="taskTotal" show-edges :sibling-count="1" @update:page="loadTasks" />
          </div>
        </section>
      </template>

      <template #inspections>
        <section class="cs-card">
          <div class="filters">
            <USelect v-model="inspectionStatus" :items="INSPECTION_STATUS_ITEMS" class="w-36" @update:model-value="() => { inspectionPage = 1; loadInspections(); }" />
            <span class="muted">共 {{ inspectionTotal }} 张</span>
          </div>
          <UAlert v-if="inspectionFailed" color="error" variant="subtle" title="加载失败，下面的列表不代表没有首检单" class="mx-5 mb-3" />
          <UTable :data="inspectionRows" :columns="inspectionColumns" :loading="inspectionLoading" class="cs-table" data-testid="first-article-inspections">
            <template #empty>还没有首检单</template>
            <template #process-cell="{ row }">{{ row.original.processName ?? row.original.workProcessId }}</template>
            <template #standard-cell="{ row }">{{ row.original.standardName }} · 第 {{ row.original.standardRevision }} 版</template>
            <template #status-cell="{ row }"><UBadge :color="inspectionColor(row.original)" variant="soft">{{ row.original.statusLabel }}</UBadge></template>
            <template #sample-cell="{ row }">{{ row.original.sampleQuantity != null ? `${row.original.sampleQuantity} ${row.original.sampleUnit ?? ''}` : '—' }}</template>
            <template #requested-cell="{ row }">{{ time(row.original.requestedAt) }}</template>
            <template #judged-cell="{ row }">{{ time(row.original.judgedAt) }}</template>
            <template #notes-cell="{ row }"><span class="note">{{ inspectionNote(row.original) }}</span></template>
            <template #actions-cell="{ row }">
              <div class="ops">
                <UButton v-if="row.original.status === 'PENDING' && canJudge" size="xs" color="primary" variant="soft" @click="openJudge(row.original.id)">判定</UButton>
                <UButton v-else size="xs" color="neutral" variant="link" @click="openJudge(row.original.id)">查看</UButton>
                <UButton v-if="row.original.status !== 'VOID' && canJudge" size="xs" color="error" variant="link" @click="voidTarget = row.original; voidReason = ''">作废</UButton>
              </div>
            </template>
          </UTable>
          <div class="pager">
            <UPagination v-model:page="inspectionPage" :items-per-page="20" :total="inspectionTotal" show-edges :sibling-count="1" @update:page="loadInspections" />
          </div>
        </section>
      </template>

      <template #standards>
        <section class="cs-card">
          <div class="filters">
            <UButton v-if="canJudge" icon="i-lucide-plus" data-testid="fa-standard-new" @click="openCreate">新建首检标准</UButton>
            <UCheckbox v-model="includeInactive" label="含已停用" @update:model-value="loadStandards" />
          </div>
          <UAlert v-if="standardsFailed" color="error" variant="subtle" title="加载失败，下面的列表不代表没有首检标准" class="mx-5 mb-3" />
          <UTable :data="standards" :columns="standardColumns" :loading="standardsLoading" class="cs-table" data-testid="first-article-standards">
            <template #empty>还没有首检标准；没有标准的工序不要求首检</template>
            <template #process-cell="{ row }">{{ row.original.processName ?? row.original.workProcessId }}</template>
            <template #items-cell="{ row }"><span class="note">{{ itemNames(row.original) }}</span></template>
            <template #state-cell="{ row }">
              <UBadge v-if="row.original.active" color="success" variant="soft">启用 · 第 {{ row.original.revision }} 版</UBadge>
              <span v-else class="muted">已停用：{{ row.original.deactivatedReason }}</span>
            </template>
            <template #updated-cell="{ row }">{{ time(row.original.updatedAt) }}</template>
            <template #actions-cell="{ row }">
              <div v-if="row.original.active && canJudge" class="ops">
                <UButton size="xs" color="neutral" variant="link" @click="openEdit(row.original)">修改</UButton>
                <UButton size="xs" color="error" variant="link" @click="deactivateTarget = row.original; deactivateReason = ''">停用</UButton>
              </div>
            </template>
          </UTable>
        </section>
      </template>
    </UTabs>

    <!-- 改开关: 确认层 -->
    <UModal v-model:open="policyConfirm" title="确认修改首检开关" description="首检开关确认" :ui="{ footer: 'justify-end', description: 'sr-only' }">
      <template #body>
        <p data-testid="fa-policy-confirm">从「{{ policy ? MODE_LABEL[policy.mode] : '' }}」改为「{{ MODE_LABEL[modeDraft] }}」：{{ MODE_HINT[modeDraft] }}</p>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="policyConfirm = false">取消</UButton>
        <UButton @click="confirmPolicy">确认修改</UButton>
      </template>
    </UModal>

    <!-- 送检: 确认层 -->
    <UModal :open="!!requestTarget" title="送首检" description="送首检确认" :ui="{ footer: 'justify-end', description: 'sr-only' }" @update:open="(v: boolean) => { if (!v) requestTarget = null; }">
      <template #body>
        <div v-if="requestTarget" class="form">
          <p>{{ requestTarget.batchNumber ?? `批次 ${requestTarget.productionBatchId}` }} · {{ requestTarget.processName }}，按标准「{{ requestTarget.standardName }}」送检。送检后由质检判定。</p>
          <div class="row2">
            <UFormField label="送检数量"><UInput v-model="requestForm.quantity" placeholder="选填，如 3" class="w-full" /></UFormField>
            <UFormField label="单位"><UInput v-model="requestForm.unit" placeholder="填了数量就要填，如 盒" class="w-full" /></UFormField>
          </div>
          <UFormField label="说明"><UTextarea v-model="requestForm.notes" :rows="2" :maxlength="500" placeholder="选填，如 换品种首件" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="requestTarget = null">取消</UButton>
        <UButton :loading="requestSubmitting" data-testid="fa-request-confirm" @click="confirmRequest">确认送检</UButton>
      </template>
    </UModal>

    <!-- 判定: 填实测值 → 服务端预览 → 确认 -->
    <UModal :open="!!judgeTarget" :title="judgeTarget ? `首检单 ${judgeTarget.id} · ${judgeTarget.processName ?? ''}` : '首检单'" description="首检判定" :dismissible="false" :ui="{ footer: 'justify-end', content: 'max-w-[760px]', description: 'sr-only' }" @update:open="(v: boolean) => { if (!v) judgeTarget = null; }">
      <template #body>
        <div v-if="judgeTarget" class="form" data-testid="fa-judge-dialog">
          <p class="muted">标准「{{ judgeTarget.standardName }}」第 {{ judgeTarget.standardRevision }} 版 · 状态 {{ judgeTarget.statusLabel }}<template v-if="judgeTarget.sampleQuantity != null"> · 送检 {{ judgeTarget.sampleQuantity }} {{ judgeTarget.sampleUnit }}</template></p>
          <template v-if="judgeTarget.status === 'PENDING' && canJudge">
            <div v-for="it in judgeTarget.standardItems" :key="it.key ?? it.name" class="item-line">
              <span class="item-name">{{ it.name }}<span v-if="it.required !== false" class="req">*</span></span>
              <span class="muted rule">{{ it.type === 'NUMERIC' ? `${it.lower ?? ''} ~ ${it.upper ?? ''} ${it.unit ?? ''}` : `合格：${(it.passOptions ?? []).join(' / ')}` }}</span>
              <UInput v-if="it.type === 'NUMERIC'" v-model="judgeValues[it.key ?? '']" placeholder="实测值" class="w-36" @update:model-value="onJudgeInputChanged" />
              <USelect v-else v-model="judgeValues[it.key ?? '']" :items="(it.options ?? []).map((o) => ({ label: o, value: o }))" placeholder="选择" class="w-36" @update:model-value="onJudgeInputChanged" />
              <span v-if="judgePreviewData" class="pass" :class="{ bad: judgePreviewData.results.find((r) => r.key === it.key)?.pass === false }">
                {{ (() => { const r = judgePreviewData.results.find((x) => x.key === it.key); return r == null || r.pass == null ? '未填' : r.pass ? '合格' : '不合格'; })() }}
              </span>
            </div>
            <UFormField label="判定结果"><URadioGroup v-model="judgeVerdict" orientation="horizontal" :items="VERDICT_ITEMS" @update:model-value="onJudgeInputChanged" /></UFormField>
            <UFormField label="说明"><UTextarea v-model="judgeNotes" :rows="2" :maxlength="500" placeholder="各项合格却判不合格时必填" class="w-full" @update:model-value="onJudgeInputChanged" /></UFormField>
            <UAlert v-if="judgePreviewData" :color="judgePreviewData.canSubmit ? 'info' : 'warning'" variant="subtle" :title="`系统按标准算出：${judgePreviewData.autoVerdict === 'PASSED' ? '首检合格' : '首检不合格'}`" :description="judgePreviewData.message" data-testid="fa-judge-preview" />
          </template>
          <template v-else>
            <div v-for="r in judgeTarget.results ?? []" :key="r.key" class="item-line">
              <span class="item-name">{{ r.name }}</span>
              <span class="muted rule">{{ r.rule }}</span>
              <span>{{ r.numericValue ?? r.optionValue ?? '未填' }}</span>
              <span class="pass" :class="{ bad: r.pass === false }">{{ r.pass == null ? '未填' : r.pass ? '合格' : '不合格' }}</span>
            </div>
            <p v-if="!judgeTarget.results" class="muted">{{ judgeTarget.status === 'PENDING' ? '等质检判定（你没有质量写权限，只能查看）' : '没有逐项结果' }}</p>
            <p v-if="judgeTarget.judgeNotes">判定说明：{{ judgeTarget.judgeNotes }}</p>
            <p v-if="judgeTarget.voidReason">作废原因：{{ judgeTarget.voidReason }}</p>
          </template>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="judgeTarget = null">关闭</UButton>
        <template v-if="judgeTarget?.status === 'PENDING' && canJudge">
          <UButton color="neutral" variant="soft" :loading="judgeBusy" data-testid="fa-judge-preview-btn" @click="runJudgePreview">预览判定</UButton>
          <UButton :disabled="!judgePreviewData || !judgePreviewData.canSubmit" :loading="judgeBusy" data-testid="fa-judge-confirm" @click="confirmJudge">确认判定</UButton>
        </template>
      </template>
    </UModal>

    <!-- 作废: 确认层 -->
    <UModal :open="!!voidTarget" title="作废首检单" description="作废首检单确认" :ui="{ footer: 'justify-end', description: 'sr-only' }" @update:open="(v: boolean) => { if (!v) voidTarget = null; }">
      <template #body>
        <div v-if="voidTarget" class="form">
          <p>作废首检单 {{ voidTarget.id }}（{{ voidTarget.statusLabel }}）。作废后这道工序回到上一张单的状态，可以重新送检。</p>
          <UFormField label="作废原因" required><UTextarea v-model="voidReason" :rows="2" :maxlength="500" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="voidTarget = null">取消</UButton>
        <UButton color="error" @click="confirmVoid">确认作废</UButton>
      </template>
    </UModal>

    <!-- 停用标准: 确认层 -->
    <UModal :open="!!deactivateTarget" title="停用首检标准" description="停用首检标准确认" :ui="{ footer: 'justify-end', description: 'sr-only' }" @update:open="(v: boolean) => { if (!v) deactivateTarget = null; }">
      <template #body>
        <div v-if="deactivateTarget" class="form">
          <p>停用「{{ deactivateTarget.name }}」后，工序「{{ deactivateTarget.processName }}」不再要求首检；已有首检单保留。</p>
          <UFormField label="停用原因" required><UTextarea v-model="deactivateReason" :rows="2" :maxlength="500" class="w-full" /></UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="deactivateTarget = null">取消</UButton>
        <UButton color="error" @click="confirmDeactivate">确认停用</UButton>
      </template>
    </UModal>

    <!-- 新建 / 修改标准: 编辑 → 服务端预览 → 确认保存 -->
    <UModal v-model:open="editOpen" :title="editId ? '修改首检标准' : '新建首检标准'" description="首检标准编辑" :dismissible="false" :ui="{ footer: 'justify-end', content: 'max-w-[900px]', description: 'sr-only' }">
      <template #body>
        <div class="form" data-testid="fa-standard-dialog">
          <div class="row2">
            <UFormField label="工序" required>
              <USelectMenu v-model="editProcessId" value-key="value" :items="processItems" :disabled="!!editId" placeholder="选择要首检的工序" class="w-full" @update:model-value="onEditChanged" />
            </UFormField>
            <UFormField label="标准名称" required><UInput v-model="editName" placeholder="如 修油首检" class="w-full" @update:model-value="onEditChanged" /></UFormField>
          </div>
          <div v-for="(d, idx) in editDrafts" :key="idx" class="draft">
            <USelect v-model="d.type" :items="TYPE_ITEMS" class="w-24" @update:model-value="onEditChanged" />
            <UInput v-model="d.name" placeholder="检验项名称" class="w-36" @update:model-value="onEditChanged" />
            <template v-if="d.type === 'NUMERIC'">
              <UInput v-model="d.lower" placeholder="下限" class="w-20" @update:model-value="onEditChanged" />
              <UInput v-model="d.upper" placeholder="上限" class="w-20" @update:model-value="onEditChanged" />
              <UInput v-model="d.target" placeholder="标准值" class="w-20" @update:model-value="onEditChanged" />
              <UInput v-model="d.unit" placeholder="单位" class="w-16" @update:model-value="onEditChanged" />
            </template>
            <template v-else>
              <UInput v-model="d.options" placeholder="选项，用顿号分隔" class="w-48" @update:model-value="onEditChanged" />
              <UInput v-model="d.passOptions" placeholder="合格选项" class="w-36" @update:model-value="onEditChanged" />
            </template>
            <UCheckbox v-model="d.required" label="必检" @update:model-value="onEditChanged" />
            <UButton size="xs" color="neutral" variant="link" icon="i-lucide-x" aria-label="删除这一项" :disabled="editDrafts.length <= 1" @click="editDrafts.splice(idx, 1); onEditChanged()" />
          </div>
          <div class="ops">
            <UButton size="xs" color="neutral" variant="outline" icon="i-lucide-plus" @click="editDrafts.push(emptyDraft('NUMERIC')); onEditChanged()">加数值项</UButton>
            <UButton size="xs" color="neutral" variant="outline" icon="i-lucide-plus" @click="editDrafts.push(emptyDraft('OPTION')); onEditChanged()">加选项项</UButton>
          </div>
          <UAlert v-if="editPreview" color="info" variant="subtle" data-testid="fa-standard-preview"
            :title="`${editPreview.action === 'CREATE' ? '新建' : '修订为第 ' + editPreview.nextRevision + ' 版'}：${editPreview.processName} · ${editPreview.name}`"
            :description="editPreview.items.map((it, i) => `${it.name}（${editPreview!.ruleTexts[i]}${it.required === false ? '，选检' : ''}）`).join('；')" />
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="editOpen = false">取消</UButton>
        <UButton color="neutral" variant="soft" :loading="editBusy" data-testid="fa-standard-preview-btn" @click="runStandardPreview">预览</UButton>
        <UButton :disabled="!editPreview" :loading="editBusy" data-testid="fa-standard-confirm" @click="confirmStandard">确认保存</UButton>
      </template>
    </UModal>
  </div>
</template>

<style scoped>
.page { padding: var(--cs-space-4); }
.cs-card { background: var(--cs-surface); border: 1px solid var(--cs-border); border-radius: var(--cs-radius); min-width: 0; }
.cs-card-head { display: flex; align-items: baseline; gap: var(--cs-space-3); padding: var(--cs-space-4) var(--cs-space-5); border-bottom: 1px solid var(--cs-border); }
.cs-card-head h2 { margin: 0; font-size: var(--cs-type-title); font-weight: 650; color: var(--cs-text); }
.count, .muted { color: var(--cs-muted); font-size: var(--cs-font-small); }
.policy { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-3) var(--cs-space-5); }
.policy .label { font-weight: 600; color: var(--cs-text); }
.error { color: var(--cs-danger); }
.filters { display: flex; gap: var(--cs-space-3); align-items: center; flex-wrap: wrap; padding: var(--cs-space-4) var(--cs-space-5); }
.pager { display: flex; justify-content: flex-end; padding: var(--cs-space-4) var(--cs-space-5); }
.ops { display: flex; gap: var(--cs-space-2); align-items: center; flex-wrap: wrap; }
.note { white-space: normal; }
.form { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.row2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--cs-space-3); }
.draft { display: flex; gap: var(--cs-space-2); align-items: center; flex-wrap: wrap; }
.item-line { display: grid; grid-template-columns: 120px 1fr 160px 60px; gap: var(--cs-space-2); align-items: center; }
.item-name { color: var(--cs-text); }
.req { color: var(--cs-danger); margin-left: 2px; }
.rule { font-variant-numeric: tabular-nums; }
.pass { color: var(--cs-success); font-weight: 600; }
.pass.bad { color: var(--cs-danger); }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; padding-block: 0; height: var(--cs-row-height); color: var(--cs-text); border-color: var(--cs-border); }
</style>
