<script setup lang="ts">
/**
 * 成本 / 损耗异常: 异常 → 证据 → 影响 → 核查路径 → 负责人 → 处理结果。
 * 未配置阈值的那一类不告警, 页面明说「未配置」; 金额 / 用量算不出写「未知」, ⛔ 不在前端补 0。
 */
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import type { CsTableColumn } from '@/types/csTable';
import {
  assignCostDeviationOwner,
  getCostDeviationCase,
  getCostDeviationThresholds,
  listCostDeviationCases,
  resolveCostDeviation,
  saveCostDeviationThresholds,
  scanCostDeviations,
  startCostDeviationVerify,
  type CostDeviationCase,
  type CostDeviationDetail,
  type CostDeviationResolution,
  type CostDeviationScanResult,
  type CostDeviationThresholds,
} from '@/api/costDeviationCases';

const UNKNOWN = '未知';
const RESOLUTIONS: { value: CostDeviationResolution; label: string }[] = [
  { value: 'REASONABLE', label: '差异合理' },
  { value: 'CORRECTED', label: '已纠正' },
  { value: 'STANDARD_UPDATE', label: '标准需调整' },
  { value: 'DATA_ERROR', label: '录入有误' },
];

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('finance') || permissionStore.canWrite('production'));

const thresholds = ref<CostDeviationThresholds>({ variancePct: null, balancePct: null });
const draft = ref<CostDeviationThresholds>({ variancePct: null, balancePct: null });
const thresholdsOpen = ref(false);
const openOnly = ref(true);
const loading = ref(false);
const scanning = ref(false);
const cases = ref<CostDeviationCase[]>([]);
const scanResult = ref<CostDeviationScanResult | null>(null);

const drawer = ref(false);
const detail = ref<CostDeviationDetail | null>(null);
const users = ref<{ id: number; fullName: string | null; username: string }[]>([]);
const ownerId = ref<number | null>(null);
const resolution = ref<CostDeviationResolution | null>(null);
const note = ref('');
const acting = ref(false);

const caseColumns: CsTableColumn<CostDeviationCase>[] = [
  { accessorKey: 'sourceLabel', header: '类型' },
  { accessorKey: 'planNumber', header: '计划' },
  { id: 'product', header: '产品' },
  { accessorKey: 'summary', header: '异常' },
  { id: 'amount', header: '差异金额' },
  { id: 'owner', header: '负责人' },
  { accessorKey: 'stageLabel', header: '状态' },
  { id: 'actions', header: '操作' },
];
const varianceColumns: CsTableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'materialName', header: '项目' },
  { id: 'priceVariance', header: '价格差' },
  { id: 'usageVariance', header: '用量差' },
  { id: 'substituteVariance', header: '替代料差' },
  { id: 'laborVariance', header: '人工差' },
  { id: 'reasons', header: '说明' },
];
const balanceColumns: CsTableColumn<Record<string, unknown>>[] = [
  { accessorKey: 'processName', header: '工序' },
  { id: 'input', header: '投入' },
  { id: 'output', header: '产出' },
  { id: 'confirmedLoss', header: '已确认损耗' },
  { id: 'unexplained', header: '未解释' },
  { id: 'unexplainedPct', header: '占比%' },
  { accessorKey: 'unit', header: '单位' },
];
const ownerItems = computed(() => users.value.map(u => ({ label: u.fullName ?? u.username, value: u.id })));

const nothingConfigured = computed(() => thresholds.value.variancePct == null && thresholds.value.balancePct == null);
const isOpen = computed(() => detail.value != null && detail.value.case.stage !== 'CLOSED');
const isVerifying = computed(() => detail.value?.case.stage === 'EXECUTING');

function fail(e: unknown, fallback: string) {
  ElMessage({ message: e instanceof Error && e.message ? e.message : fallback, type: 'error', duration: 0, showClose: true });
}
function pct(v: number | null): string {
  return v == null ? '未配置' : `${v}%`;
}
function qty(v: number | null | undefined): string {
  return v == null ? UNKNOWN : String(v);
}
function amountText(c: CostDeviationCase): string {
  if (c.priceMasked) return '无权查看';
  return c.amountVariance == null ? UNKNOWN : `¥${c.amountVariance}`;
}

async function loadThresholds() {
  if (!factoryId.value) return;
  try {
    const res = await getCostDeviationThresholds(factoryId.value);
    if (res.success) thresholds.value = res.data;
  } catch (e: unknown) {
    fail(e, '阈值加载失败');
  }
}
async function loadCases() {
  if (!factoryId.value) return;
  loading.value = true;
  try {
    const res = await listCostDeviationCases(factoryId.value, openOnly.value);
    cases.value = res.success ? res.data : [];
  } catch (e: unknown) {
    cases.value = [];
    fail(e, '异常列表加载失败');
  } finally {
    loading.value = false;
  }
}
function openThresholds() {
  draft.value = { ...thresholds.value };
  thresholdsOpen.value = true;
}
async function saveThresholds() {
  if (!factoryId.value) return;
  try {
    const res = await saveCostDeviationThresholds(factoryId.value, draft.value);
    if (res.success) {
      thresholds.value = res.data;
      thresholdsOpen.value = false;
      ElMessage.success('阈值已保存');
    }
  } catch (e: unknown) {
    fail(e, '阈值保存失败');
  }
}
async function scan() {
  if (!factoryId.value) return;
  scanning.value = true;
  try {
    const res = await scanCostDeviations(factoryId.value);
    if (res.success) {
      scanResult.value = res.data;
      await loadCases();
    }
  } catch (e: unknown) {
    fail(e, '扫描失败');
  } finally {
    scanning.value = false;
  }
}
async function reloadDetail(id: string) {
  if (!factoryId.value) return;
  detail.value = null;
  try {
    const res = await getCostDeviationCase(factoryId.value, id);
    if (res.success) {
      detail.value = res.data;
      ownerId.value = res.data.case.ownerUserId;
      resolution.value = null;
      note.value = '';
    }
  } catch (e: unknown) {
    fail(e, '异常详情加载失败');
  }
}
async function openCase(row: CostDeviationCase) {
  if (!factoryId.value) return;
  drawer.value = true;
  await reloadDetail(row.id);
  if (users.value.length === 0) {
    try {
      const res = await get<{ id: number; fullName: string | null; username: string }[]>(`/${factoryId.value}/users/directory`);
      if (res.success) users.value = res.data;
    } catch (e: unknown) {
      fail(e, '员工名录加载失败');
    }
  }
}
async function act(run: (factory: string, id: string) => Promise<unknown>, done: string) {
  if (!factoryId.value || !detail.value) return;
  const id = detail.value.case.id;
  acting.value = true;
  try {
    await run(factoryId.value, id);
    ElMessage.success(done);
    await Promise.all([reloadDetail(id), loadCases()]);
  } catch (e: unknown) {
    fail(e, '操作失败');
  } finally {
    acting.value = false;
  }
}
function assign() {
  const owner = ownerId.value;
  if (owner != null) void act((f, id) => assignCostDeviationOwner(f, id, owner), '负责人已指定');
}
function startVerify() {
  void act(startCostDeviationVerify, '已开始核查');
}
function resolve() {
  const code = resolution.value;
  if (code) void act((f, id) => resolveCostDeviation(f, id, code, note.value), '已关闭');
}

onMounted(() => {
  void loadThresholds();
  void loadCases();
});
</script>

<template>
  <div class="page" data-testid="cost-deviation-page">
    <h2>成本损耗异常</h2>
    <UAlert v-if="nothingConfigured" color="warning" variant="soft" icon="i-lucide-triangle-alert" class="mb-3"
      title="未配置告警阈值, 本厂不会生成成本和损耗异常" />
    <div class="toolbar">
      <span>成本差异阈值: <b data-test="variance-threshold">{{ pct(thresholds.variancePct) }}</b></span>
      <span>物料损耗阈值: <b data-test="balance-threshold">{{ pct(thresholds.balancePct) }}</b></span>
      <UButton v-if="canWrite" size="sm" color="neutral" variant="outline" data-testid="deviation-thresholds" @click="openThresholds">设置阈值</UButton>
      <UButton v-if="canWrite" size="sm" :loading="scanning" :disabled="nothingConfigured" data-testid="deviation-scan" @click="scan">立即扫描</UButton>
      <UCheckbox v-model="openOnly" label="只看未处理" @update:model-value="loadCases" />
    </div>

    <UAlert v-if="scanResult" color="info" variant="soft" icon="i-lucide-info" class="scan"
      :title="`扫描 ${scanResult.evaluated} 项: 新增 ${scanResult.created}, 更新 ${scanResult.refreshed}, 阈值内 ${scanResult.within}, 已处理未变 ${scanResult.alreadyResolved}, 算不出 ${scanResult.unknown.length}, 失败 ${scanResult.failed}`">
      <template #description>
        <div class="unknown-list">
          <div v-for="(u, i) in scanResult.unknown" :key="i">{{ u.planNumber ?? '未知计划' }}: {{ u.reason }}</div>
        </div>
      </template>
    </UAlert>

    <UTable :data="cases" :columns="caseColumns" :loading="loading" class="cs-table" data-testid="deviation-table">
      <template #empty>没有异常</template>
      <template #product-cell="{ row }">{{ row.original.productName ?? '未知' }}</template>
      <template #amount-cell="{ row }">{{ amountText(row.original) }}</template>
      <template #owner-cell="{ row }">{{ row.original.ownerName ?? '未指定' }}</template>
      <template #actions-cell="{ row }">
        <UButton variant="link" size="xs" :data-testid="`deviation-open-${row.original.id}`" @click="openCase(row.original)">处理</UButton>
      </template>
    </UTable>

    <UModal
      v-model:open="thresholdsOpen"
      title="告警阈值"
      description="设置成本差异与物料损耗的告警阈值"
      :ui="{ content: 'max-w-[460px]', description: 'sr-only', footer: 'justify-end' }"
    >
      <template #body>
        <p class="hint">留空 = 未配置, 该类不告警。</p>
        <div class="form">
          <UFormField label="成本差异率 (%)">
            <UInputNumber v-model="draft.variancePct" :min="0.01" :max="100" :step-snapping="false" :increment="false" :decrement="false" class="w-full" data-testid="deviation-variance-pct" />
          </UFormField>
          <UFormField label="未解释差额占比 (%)">
            <UInputNumber v-model="draft.balancePct" :min="0.01" :max="100" :step-snapping="false" :increment="false" :decrement="false" class="w-full" data-testid="deviation-balance-pct" />
          </UFormField>
        </div>
      </template>
      <template #footer>
        <UButton color="neutral" variant="outline" @click="thresholdsOpen = false">取消</UButton>
        <UButton data-testid="deviation-threshold-save" @click="saveThresholds">保存</UButton>
      </template>
    </UModal>

    <!-- 非模态: 模态 USlideover 会让 body 变 pointer-events:none, 抽屉里的下拉点不动。 -->
    <USlideover
      v-model:open="drawer"
      :modal="false"
      :overlay="false"
      side="right"
      title="异常处理"
      description="成本损耗异常处理详情"
      :ui="{ content: 'max-w-[720px] w-full', description: 'sr-only' }"
    >
      <template #body>
        <div v-if="detail" data-testid="deviation-detail">
          <h3>异常</h3>
          <p>{{ detail.case.sourceLabel }} · {{ detail.case.planNumber }} · {{ detail.case.productName }} · {{ detail.case.stageLabel }}</p>
          <p>{{ detail.case.summary }}</p>
          <UAlert v-if="detail.case.belowThresholdAt" color="success" variant="soft" icon="i-lucide-circle-check" title="读数已回到阈值内, 仍需负责人给出结论" />

          <h3>证据</h3>
          <UAlert v-if="detail.evidenceError" color="error" variant="soft" icon="i-lucide-circle-alert" :title="detail.evidenceError" />
          <p>批次: {{ detail.batches.map(b => b.batchNumber).join('、') || '无' }}</p>
          <UTable v-if="detail.varianceEvidence" :data="detail.varianceEvidence.lines" :columns="varianceColumns" class="cs-table">
            <template #priceVariance-cell="{ row }">{{ qty(row.original.priceVariance as number | null) }}</template>
            <template #usageVariance-cell="{ row }">{{ qty(row.original.usageVariance as number | null) }}</template>
            <template #substituteVariance-cell="{ row }">{{ qty(row.original.substituteVariance as number | null) }}</template>
            <template #laborVariance-cell="{ row }">{{ qty(row.original.laborVariance as number | null) }}</template>
            <template #reasons-cell="{ row }">{{ (row.original.reasons as string[]).join('; ') }}</template>
          </UTable>
          <UTable v-if="detail.balanceEvidence" :data="detail.balanceEvidence.rows" :columns="balanceColumns" class="cs-table">
            <template #input-cell="{ row }">{{ qty(row.original.input as number | null) }}</template>
            <template #output-cell="{ row }">{{ qty(row.original.output as number | null) }}</template>
            <template #confirmedLoss-cell="{ row }">{{ qty(row.original.confirmedLoss as number | null) }}</template>
            <template #unexplained-cell="{ row }">{{ qty(row.original.unexplained as number | null) }}</template>
            <template #unexplainedPct-cell="{ row }">{{ qty(row.original.unexplainedPct as number | null) }}</template>
          </UTable>

          <h3>影响</h3>
          <p>涉及批次 {{ detail.impact.batchCount }} 个; 差异金额: {{ detail.impact.amount == null ? (detail.impact.amountNote ?? UNKNOWN) : `¥${detail.impact.amount}` }}</p>
          <p v-if="detail.impact.unitCostImpact != null">单位成本影响: ¥{{ detail.impact.unitCostImpact }} / {{ detail.impact.outputUnit }}</p>
          <p v-if="detail.case.sourceType === 'MATERIAL_BALANCE'">未解释数量合计: {{ qty(detail.impact.unexplainedQty) }} {{ detail.impact.unexplainedUnit ?? '' }}</p>
          <p v-if="detail.case.unknownItemCount > 0">另有 {{ detail.case.unknownItemCount }} 项算不出, 实际影响可能更大或更小</p>

          <h3>核查路径</h3>
          <ol><li v-for="s in detail.verifyPath" :key="s">{{ s }}</li></ol>

          <h3>负责人</h3>
          <p>当前: {{ detail.case.ownerName ?? '未指定' }}</p>
          <div v-if="canWrite && isOpen" class="row">
            <USelectMenu v-model="ownerId" value-key="value" :items="ownerItems" placeholder="选择负责人" class="w-44 shrink-0" data-testid="deviation-owner" />
            <UButton class="shrink-0 whitespace-nowrap" :loading="acting" :disabled="ownerId == null" color="neutral" variant="outline" data-testid="deviation-assign" @click="assign">指定</UButton>
            <UButton class="shrink-0 whitespace-nowrap" v-if="!isVerifying" :loading="acting" :disabled="detail.case.ownerUserId == null" data-testid="deviation-start-verify" @click="startVerify">开始核查</UButton>
          </div>

          <h3>处理结果</h3>
          <p v-if="detail.case.resolutionLabel">{{ detail.case.resolutionLabel }}: {{ detail.case.resolutionNote }}</p>
          <template v-else-if="canWrite && isVerifying">
            <USelect v-model="resolution" :items="RESOLUTIONS" placeholder="选择结果" class="w-56" data-testid="deviation-resolution" />
            <UTextarea v-model="note" :rows="3" placeholder="写清核查发现和处理方式" class="note w-full" data-testid="deviation-note" />
            <UButton :loading="acting" :disabled="!resolution || !note.trim()" data-testid="deviation-resolve" @click="resolve">提交并关闭</UButton>
          </template>
          <p v-else>待核查</p>

          <h3>留痕</h3>
          <ul class="timeline">
            <li v-for="(e, i) in detail.events" :key="i"><span class="ts">{{ e.createdAt }}</span> {{ e.detail }}</li>
          </ul>
        </div>
      </template>
    </USlideover>
  </div>
</template>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; margin: 12px 0; }
.scan { margin-bottom: 12px; }
.unknown-list { max-height: 120px; overflow-y: auto; }
.hint { color: var(--cs-muted); margin: 0 0 8px; }
.form { display: grid; gap: 12px; }
.row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.note { margin: 8px 0; }
h3 { margin: 18px 0 6px; font-weight: 650; }
.timeline { list-style: none; padding: 0; margin: 0; display: grid; gap: 6px; }
.ts { color: var(--cs-muted); font-size: 12px; margin-right: 8px; }
:deep(.cs-table th) { height: 32px; padding-block: 4px; font-size: var(--cs-font-small); font-weight: 500; color: var(--cs-muted); background: var(--cs-surface-subtle); white-space: nowrap; }
:deep(.cs-table td) { font-size: 13px; color: var(--cs-text); border-color: var(--cs-border); }
</style>
