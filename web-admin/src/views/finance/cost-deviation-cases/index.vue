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
  <div class="page">
    <h2>成本损耗异常</h2>
    <el-alert v-if="nothingConfigured" type="warning" :closable="false" show-icon
      title="未配置告警阈值, 本厂不会生成成本和损耗异常" />
    <div class="toolbar">
      <span>成本差异阈值: <b data-test="variance-threshold">{{ pct(thresholds.variancePct) }}</b></span>
      <span>物料损耗阈值: <b data-test="balance-threshold">{{ pct(thresholds.balancePct) }}</b></span>
      <el-button v-if="canWrite" size="small" @click="openThresholds">设置阈值</el-button>
      <el-button v-if="canWrite" size="small" type="primary" :loading="scanning" :disabled="nothingConfigured" @click="scan">立即扫描</el-button>
      <el-checkbox v-model="openOnly" @change="loadCases">只看未处理</el-checkbox>
    </div>

    <el-alert v-if="scanResult" type="info" :closable="false" class="scan"
      :title="`扫描 ${scanResult.evaluated} 项: 新增 ${scanResult.created}, 更新 ${scanResult.refreshed}, 阈值内 ${scanResult.within}, 已处理未变 ${scanResult.alreadyResolved}, 算不出 ${scanResult.unknown.length}, 失败 ${scanResult.failed}`">
      <div class="unknown-list">
        <div v-for="(u, i) in scanResult.unknown" :key="i">{{ u.planNumber ?? '未知计划' }}: {{ u.reason }}</div>
      </div>
    </el-alert>

    <el-table v-loading="loading" :data="cases" empty-text="没有异常" @row-click="openCase">
      <el-table-column prop="sourceLabel" label="类型" width="100" />
      <el-table-column prop="planNumber" label="计划" width="180" />
      <el-table-column label="产品" width="160">
        <template #default="{ row }">{{ row.productName ?? '未知' }}</template>
      </el-table-column>
      <el-table-column prop="summary" label="异常" min-width="280" />
      <el-table-column label="差异金额" width="110">
        <template #default="{ row }">{{ amountText(row) }}</template>
      </el-table-column>
      <el-table-column label="负责人" width="100">
        <template #default="{ row }">{{ row.ownerName ?? '未指定' }}</template>
      </el-table-column>
      <el-table-column prop="stageLabel" label="状态" width="90" />
    </el-table>

    <el-dialog v-model="thresholdsOpen" title="告警阈值" width="420px">
      <p class="hint">留空 = 未配置, 该类不告警。</p>
      <el-form label-width="150px">
        <el-form-item label="成本差异率 (%)">
          <el-input-number v-model="draft.variancePct" :min="0.01" :max="100" :value-on-clear="null" />
        </el-form-item>
        <el-form-item label="未解释差额占比 (%)">
          <el-input-number v-model="draft.balancePct" :min="0.01" :max="100" :value-on-clear="null" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="thresholdsOpen = false">取消</el-button>
        <el-button type="primary" @click="saveThresholds">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer" size="720px" title="异常处理">
      <template v-if="detail">
        <h3>异常</h3>
        <p>{{ detail.case.sourceLabel }} · {{ detail.case.planNumber }} · {{ detail.case.productName }} · {{ detail.case.stageLabel }}</p>
        <p>{{ detail.case.summary }}</p>
        <el-alert v-if="detail.case.belowThresholdAt" type="success" :closable="false" title="读数已回到阈值内, 仍需负责人给出结论" />

        <h3>证据</h3>
        <el-alert v-if="detail.evidenceError" type="error" :closable="false" :title="detail.evidenceError" />
        <p>批次: {{ detail.batches.map(b => b.batchNumber).join('、') || '无' }}</p>
        <el-table v-if="detail.varianceEvidence" :data="detail.varianceEvidence.lines" size="small">
          <el-table-column prop="materialName" label="项目" />
          <el-table-column label="价格差"><template #default="{ row }">{{ qty(row.priceVariance) }}</template></el-table-column>
          <el-table-column label="用量差"><template #default="{ row }">{{ qty(row.usageVariance) }}</template></el-table-column>
          <el-table-column label="替代料差"><template #default="{ row }">{{ qty(row.substituteVariance) }}</template></el-table-column>
          <el-table-column label="人工差"><template #default="{ row }">{{ qty(row.laborVariance) }}</template></el-table-column>
          <el-table-column label="说明"><template #default="{ row }">{{ row.reasons.join('; ') }}</template></el-table-column>
        </el-table>
        <el-table v-if="detail.balanceEvidence" :data="detail.balanceEvidence.rows" size="small">
          <el-table-column prop="processName" label="工序" />
          <el-table-column label="投入"><template #default="{ row }">{{ qty(row.input) }}</template></el-table-column>
          <el-table-column label="产出"><template #default="{ row }">{{ qty(row.output) }}</template></el-table-column>
          <el-table-column label="已确认损耗"><template #default="{ row }">{{ qty(row.confirmedLoss) }}</template></el-table-column>
          <el-table-column label="未解释"><template #default="{ row }">{{ qty(row.unexplained) }}</template></el-table-column>
          <el-table-column label="占比%"><template #default="{ row }">{{ qty(row.unexplainedPct) }}</template></el-table-column>
          <el-table-column prop="unit" label="单位" />
        </el-table>

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
          <el-select v-model="ownerId" filterable placeholder="选择负责人" style="width: 220px">
            <el-option v-for="u in users" :key="u.id" :label="u.fullName ?? u.username" :value="u.id" />
          </el-select>
          <el-button :loading="acting" :disabled="ownerId == null" @click="assign">指定</el-button>
          <el-button v-if="!isVerifying" type="primary" :loading="acting" :disabled="detail.case.ownerUserId == null" @click="startVerify">开始核查</el-button>
        </div>

        <h3>处理结果</h3>
        <p v-if="detail.case.resolutionLabel">{{ detail.case.resolutionLabel }}: {{ detail.case.resolutionNote }}</p>
        <template v-else-if="canWrite && isVerifying">
          <el-select v-model="resolution" placeholder="选择结果" style="width: 220px">
            <el-option v-for="r in RESOLUTIONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <el-input v-model="note" type="textarea" :rows="3" placeholder="写清核查发现和处理方式" class="note" />
          <el-button type="primary" :loading="acting" :disabled="!resolution || !note.trim()" @click="resolve">提交并关闭</el-button>
        </template>
        <p v-else>待核查</p>

        <h3>留痕</h3>
        <el-timeline>
          <el-timeline-item v-for="(e, i) in detail.events" :key="i" :timestamp="e.createdAt">{{ e.detail }}</el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page { padding: 16px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; margin: 12px 0; }
.scan { margin-bottom: 12px; }
.unknown-list { max-height: 120px; overflow-y: auto; }
.hint { color: var(--el-text-color-secondary); }
.row { display: flex; gap: 8px; }
.note { margin: 8px 0; }
h3 { margin: 18px 0 6px; }
</style>
