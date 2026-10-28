<script setup lang="ts">
/**
 * 溯源查询 —— 一条批次从源头个体到出货的完整链路, 投屏讲「一条链路」时不用举手机。
 *
 * 信息架构照手机端 `screens/traceability/TraceabilityDetailScreen.tsx`, ⛔ 不多不少:
 *   溯源码卡 → 生产信息 → 「本批来自 N 头牛」横幅 → 来源链(按 depth 分组的时间线)
 *   → 加工环节 / 质检 / 出货 三张表(空数组不渲染, 页尾一行灰字合并说)
 * 来源链上挂了个体且后端给了个体主键的行可点 → 个体抽屉 (`IndividualDrawer.vue`)。
 *
 * 四态分开: 未查询 / 加载中 / 未找到批次 X / 出错(原话) / 有数据。
 * ⛔ 批次不存在**不**显示空表格装作没数据 —— 「没找到」和「找到了但是空的」是两件事。
 * ⛔ `traceCode` 为 null 说「未打印标签」, 不显示一个扫不回来的码, 也不拿批次号顶上。
 * ⛔ 「本批来自 N 头牛」的 N 由后端算 (lineage.sourceIndividualCount), 前端只显示。
 */
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Connection, Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import { getFullTrace, isTraceNotFound, type FullTraceResponse, type ProcessingStageInfo } from '@/api/traceability';
import { displayProcessUnit } from '@/utils/processSheetUnits';
import { formatDateTime } from '@/utils/dateFormat';
import { bannerText, summarizeSourceIndividuals } from './lineageBanner';
import {
  depthLabel,
  emptySectionsLine,
  formatQueryTime,
  formatTimeRange,
  isBlank,
  qualityStatusLabel,
  qualityStatusTag,
  shipmentStatusLabel,
  shipmentStatusTag,
  type EmptySectionKey,
} from './traceDisplay';
import LineageNodeRow from './LineageNodeRow.vue';
import IndividualDrawer from './IndividualDrawer.vue';
import BatchDispositionEvidence from './BatchDispositionEvidence.vue';
import PotTracePanel from '@/components/pot/PotTracePanel.vue';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const canViewQuality = computed(() => permissionStore.canAccess('quality'));
const route = useRoute();
const router = useRouter();
const factoryId = computed(() => authStore.factoryId);

type Status = 'idle' | 'loading' | 'ready' | 'not-found' | 'error';

const batchInput = ref('');
const status = ref<Status>('idle');
/** 最近一次真正查过的批次号 —— 「未找到批次 X」里的 X 是它, 不是输入框里正在改的那个。 */
const queriedBatch = ref('');
const trace = ref<FullTraceResponse | null>(null);
const errorMessage = ref('');

const drawerVisible = ref(false);
const drawerIndividualId = ref<string | null>(null);
let requestSequence = 0;
let pageIsActive = true;

function clearTrace() {
  ++requestSequence;
  status.value = 'idle';
  trace.value = null;
  errorMessage.value = '';
  queriedBatch.value = '';
  drawerVisible.value = false;
  drawerIndividualId.value = null;
}

watch(() => [factoryId.value, authStore.user?.id, route.query.batchNumber], () => {
  clearTrace();
  if (!pageIsActive || (route.name && route.name !== 'ProductionTraceability')) return;
  const q = route.query.batchNumber;
  batchInput.value = typeof q === 'string' ? q.trim() : '';
  if (batchInput.value) void runQuery(batchInput.value);
}, { immediate: true });

// Daily workspace keeps tabs alive: read back new QC records on return.
onActivated(() => {
  pageIsActive = true;
  const batchNumber = route.query.batchNumber;
  if (typeof batchNumber === 'string' && batchNumber.trim() && status.value !== 'loading') {
    void runQuery(batchNumber.trim());
  }
});
onDeactivated(() => { pageIsActive = false; clearTrace(); });
onBeforeUnmount(() => { pageIsActive = false; clearTrace(); });

async function handleSearch() {
  const batchNumber = batchInput.value.trim();
  if (!batchNumber) return;
  // 地址栏同步, 让这一条链路可以被复制/收藏。replace 而不是 push: 连查三次不该留三条历史。
  if (route?.query?.batchNumber !== batchNumber) {
    await router.replace({ query: { ...route.query, batchNumber } });
    // The route watcher owns a changed deep link. Explicit refresh of the same batch still queries.
    if (queriedBatch.value === batchNumber && status.value !== 'idle') return;
  }
  await runQuery(batchNumber);
}

async function runQuery(batchNumber: string) {
  if (!factoryId.value) {
    status.value = 'error';
    errorMessage.value = '当前账号没有工厂信息，无法查询溯源';
    return;
  }
  const request = ++requestSequence;
  const requestedFactory = factoryId.value;
  const requestedActor = authStore.user?.id;
  const isCurrent = () => pageIsActive && request === requestSequence
    && requestedFactory === factoryId.value && requestedActor === authStore.user?.id;
  status.value = 'loading';
  queriedBatch.value = batchNumber;
  trace.value = null;
  errorMessage.value = '';
  try {
    const res = await getFullTrace(requestedFactory, batchNumber);
    if (!isCurrent()) return;
    if (res.success && res.data) {
      if (res.data.production.factoryId !== requestedFactory) {
        status.value = 'error';
        errorMessage.value = '返回批次与当前工厂不一致，请重新查询';
        return;
      }
      trace.value = res.data;
      status.value = 'ready';
    } else {
      // 拦截器对 success:false 一律 reject, 这个分支正常到不了; 到了就是拦截器变了。
      status.value = 'error';
      errorMessage.value = res.message || '溯源查询失败';
    }
  } catch (error) {
    if (!isCurrent()) return;
    if (isTraceNotFound(error)) {
      status.value = 'not-found';
    } else {
      status.value = 'error';
      errorMessage.value = String((error as Error)?.message || '溯源查询失败');
    }
  }
}

// ── 生产信息: 空值行不渲染 ──────────────────────────────────────────────────
const productionRows = computed(() => {
  const p = trace.value?.production;
  if (!p) return [];
  const rows: Array<{ label: string; value: string }> = [
    { label: '批次号', value: p.batchNumber },
    { label: '产品名称', value: p.productName },
    { label: '生产工厂', value: p.factoryName },
    { label: '生产日期', value: p.productionDate },
    { label: '负责人', value: p.supervisorName ?? '' },
    { label: '设备', value: p.equipmentName ?? '' },
    { label: '总产量（含不良）', value: p.quantity != null ? `${p.quantity} ${p.unit || ''}`.trim() : '' },
    { label: '合格产出', value: p.goodQuantity != null ? `${p.goodQuantity} ${p.unit || ''}`.trim() : '' },
    { label: '不良产出', value: p.defectQuantity != null ? `${p.defectQuantity} ${p.unit || ''}`.trim() : '' },
  ];
  return rows.filter((r) => !isBlank(r.value));
});
const qualityLabel = computed(() => qualityStatusLabel(trace.value?.production.qualityStatus));

// ── 横幅 + 来源链 ───────────────────────────────────────────────────────────
const banner = computed(() => {
  const summary = summarizeSourceIndividuals(trace.value?.lineage);
  return summary.show ? bannerText(summary) : null;
});
/** 按 depth 从远到近排 —— 最远的来源排最上面, 读起来就是「从哪来 → 到这里」。 */
const lineageGroups = computed(() =>
  [...(trace.value?.lineage?.ancestors ?? [])].sort((a, b) => (b.depth ?? 0) - (a.depth ?? 0))
);
const ancestorCount = computed(() => lineageGroups.value.reduce((total, group) => total + group.nodes.length, 0));
const lineageUnavailable = computed(() => status.value === 'ready' && trace.value?.lineage == null);
/**
 * 多跳来源链已经包含一跳 (本批次的原料就是 depth=1 那一层), 有祖先时再列一遍「原材料」
 * 就是同一批牛出现两次。只有血缘没回填 / 引擎没拿到时才退回一跳列表。
 */
const showOneHopMaterials = computed(() => lineageGroups.value.length === 0);

// ── 三张表 + 页尾 ───────────────────────────────────────────────────────────
const processingStages = computed(() =>
  [...(trace.value?.processingStages ?? [])].sort((a, b) => (a.stageOrder ?? 0) - (b.stageOrder ?? 0))
);
const qualityInspections = computed(() => trace.value?.qualityInspections ?? []);
const shipments = computed(() => trace.value?.shipments ?? []);
const canOpenBatchEvidence = computed(() => trace.value?.production.factoryId === factoryId.value
  && Number.isSafeInteger(trace.value?.production.batchId) && Number(trace.value?.production.batchId) > 0);
function openBatchEvidence() {
  if (!canOpenBatchEvidence.value) return;
  void router.push({ path: `/production/batches/${trace.value!.production.batchId}` });
}
function openBatchQuality() {
  const p = trace.value?.production;
  if (p?.batchId == null) return;
  void router.push({ path: '/quality/inspections', query: { batchId: String(p.batchId), batchNumber: p.batchNumber } });
}
function openBatchRecall() {
  const p = trace.value?.production;
  if (p?.batchId == null) return;
  void router.push({ path: '/quality/recalls', query: { batchId: String(p.batchId) } });
}
const emptyLine = computed(() => {
  const empties: EmptySectionKey[] = [];
  if (processingStages.value.length === 0) empties.push('processingStages');
  if (qualityInspections.value.length === 0) empties.push('qualityInspections');
  if (shipments.value.length === 0) empties.push('shipments');
  return emptySectionsLine(empties);
});

/** 页尾「查询时间」。空串 = 后端没给 / 给的解析不了 —— 那一行整行不渲染, ⛔ 不显示 `-`。 */
const queryTimeText = computed(() => formatQueryTime(trace.value?.queryTime));

function percent(rate?: number | null): string {
  return rate == null ? '' : `${(rate * 100).toFixed(1)}%`;
}
function processQuantities(stage: ProcessingStageInfo): string {
  if (stage.inputWeight == null || stage.outputWeight == null) return '';
  // Legacy stage weights were defined in kg; process-sheet quantities retain their native units.
  const legacyUnit = stage.stageType === 'PROCESS_SHEET' ? '' : 'kg';
  const inputUnit = stage.inputUnit || legacyUnit;
  const outputUnit = stage.outputUnit || legacyUnit;
  return `${stage.inputWeight} ${inputUnit ? displayProcessUnit(inputUnit) : '（单位未记录）'} → ${stage.outputWeight} ${outputUnit ? displayProcessUnit(outputUnit) : '（单位未记录）'}`;
}
function dateOnly(value?: string | null): string {
  return value ? value.split('T')[0] : '';
}

function openIndividual(id: string) {
  drawerIndividualId.value = id;
  drawerVisible.value = true;
}
</script>

<template>
  <div class="page-container trace-page">
    <div class="page-header">
      <div>
        <h2>溯源查询</h2>
        <p>输入批次号，查看从源头个体到出货的完整链路</p>
      </div>
    </div>

    <el-card shadow="never" class="content-card query-card">
      <form class="query-bar" @submit.prevent="handleSearch">
        <el-input
          v-model="batchInput"
          :prefix-icon="Search"
          clearable
          placeholder="输入批次号，例如 PB-2026-001"
          data-testid="batch-input"
          @keyup.enter="handleSearch"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="status === 'loading'"
          :disabled="!batchInput.trim()"
          data-testid="search-button"
        >
          查询
        </el-button>
      </form>
    </el-card>

    <!-- 未查询 -->
    <el-empty
      v-if="status === 'idle'"
      description="输入批次号开始查询"
      :image-size="80"
      data-testid="trace-idle"
    />

    <!-- 加载中 -->
    <el-card v-else-if="status === 'loading'" shadow="never" class="content-card" data-testid="trace-loading">
      <el-skeleton :rows="6" animated />
    </el-card>

    <!-- 未找到: ⛔ 不显示空表格装作没数据 -->
    <el-result
      v-else-if="status === 'not-found'"
      icon="warning"
      :title="`未找到批次 ${queriedBatch}`"
      sub-title="请核对批次号是否正确，或该批次是否属于当前工厂"
      data-testid="trace-not-found"
    />

    <!-- 出错: 原话显示 -->
    <el-alert
      v-else-if="status === 'error'"
      type="error"
      :title="`溯源查询失败：${errorMessage}`"
      :closable="false"
      show-icon
      data-testid="trace-error"
    />

    <!-- 有数据 -->
    <template v-else-if="status === 'ready' && trace">
      <el-row :gutter="16" class="trace-row">
        <!-- 溯源码卡 -->
        <el-col :xs="24" :md="8">
          <el-card shadow="never" class="content-card trace-code-card" data-testid="trace-code-card">
            <div class="trace-code-card__label">溯源码</div>
            <div v-if="trace.traceCode" class="trace-code-card__code" data-testid="trace-code">
              {{ trace.traceCode }}
            </div>
            <div v-else class="trace-code-card__missing" data-testid="trace-code-missing">未打印标签</div>
            <div class="trace-code-card__batch" data-testid="trace-code-batch">
              批次号 <span class="mono">{{ trace.production.batchNumber }}</span>
            </div>
          </el-card>
        </el-col>

        <!-- 生产信息 -->
        <el-col :xs="24" :md="16">
          <el-card shadow="never" class="content-card" data-testid="production-card">
            <template #header>
              <span class="card-title">生产信息</span>
              <el-button v-if="canOpenBatchEvidence" link type="primary" data-testid="batch-evidence-link" @click="openBatchEvidence">查看投入产出与成本</el-button>
            </template>
            <el-descriptions :column="2" size="default">
              <el-descriptions-item v-for="row in productionRows" :key="row.label" :label="row.label">
                <span :class="{ mono: row.label === '批次号' }">{{ row.value }}</span>
              </el-descriptions-item>
              <el-descriptions-item v-if="qualityLabel" label="质量状态">
                <el-tag :type="qualityStatusTag(trace.production.qualityStatus)" size="small" data-testid="quality-status">
                  {{ qualityLabel }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>
      </el-row>

      <!-- 「本批来自 N 头牛」横幅 —— 前面一个图标让投屏时一眼抓到, 文案本身不变 -->
      <div v-if="banner" class="source-banner" data-testid="source-individual-banner">
        <el-icon class="source-banner__icon" data-testid="source-individual-banner-icon"><Connection /></el-icon>
        <span>{{ banner }}</span>
      </div>

      <!-- 来源链 -->
      <el-card shadow="never" class="content-card" data-testid="lineage-card">
        <template #header>
          <span class="card-title">来源链</span>
          <span v-if="ancestorCount > 0" class="card-title__trailing">
            {{ ancestorCount }} 个上游节点
          </span>
        </template>

        <el-alert v-if="trace.sharedInputSourceBatchNumber" type="info" :closable="false" show-icon
          title="本批次与主产品共用原料" data-testid="shared-input-notice">
          <p>以下原料及工序投入量为同组生产共用投入，不代表本产品单独耗用，也不重复计算成本。</p>
          <el-button link type="primary" data-testid="shared-input-source-link"
            @click="router.push({ path: '/production/traceability', query: { batchNumber: trace.sharedInputSourceBatchNumber } })">
            查看主批次 {{ trace.sharedInputSourceBatchNumber }} 的来源链
          </el-button>
        </el-alert>

        <!-- ① 本次没拿到 —— 与「没有上游」是两句话 -->
        <p v-if="lineageUnavailable" class="muted" data-testid="lineage-unavailable">
          来源链暂时取不到，请稍后重试
        </p>
        <!-- ② 确实没有上游 -->
        <p v-else-if="lineageGroups.length === 0 && trace.sharedInputSourceBatchNumber" class="muted" data-testid="lineage-shared">
          原料消费及上游血缘保留在同组主批次，可通过上方入口查看。
        </p>
        <p v-else-if="lineageGroups.length === 0" class="muted" data-testid="lineage-empty">
          无上游血缘记录
        </p>
        <!-- ③ 有链 -->
        <el-timeline v-else class="lineage-timeline" data-testid="lineage-timeline">
          <el-timeline-item
            v-for="group in lineageGroups"
            :key="group.depth"
            type="primary"
            :hollow="group.depth !== 1"
            placement="top"
            :timestamp="depthLabel(group.depth)"
            :data-depth="group.depth"
          >
            <div class="lineage-group">
              <LineageNodeRow
                v-for="node in group.nodes"
                :key="`${node.nodeType}#${node.nodeId || node.batchNumber || 'unnamed'}`"
                :node="node"
                show-supplier
                @open-individual="openIndividual"
              />
            </div>
          </el-timeline-item>
        </el-timeline>

        <!-- 一跳原料: 只在没有多跳祖先时退回显示 (有祖先时来源链已经画过 depth=1 那层) -->
        <el-table
          v-if="showOneHopMaterials && trace.materials.length > 0"
          :data="trace.materials"
          size="small"
          class="one-hop-table"
          data-testid="materials-table"
        >
          <el-table-column prop="batchNumber" label="原料批次" width="180" />
          <el-table-column prop="materialName" label="原料" min-width="140" />
          <el-table-column :label="trace.sharedInputSourceBatchNumber ? '同组投入量' : '消耗量'" width="120">
            <template #default="{ row }">{{ row.quantity != null ? `${row.quantity} ${row.unit || ''}` : '' }}</template>
          </el-table-column>
          <el-table-column prop="receiptDate" label="入库日期" width="120" />
        </el-table>
      </el-card>

      <!-- S3-16 按锅追溯 -->
      <PotTracePanel
        v-if="factoryId && trace.production.factoryId === factoryId"
        :factory-id="factoryId"
        :root-production-batch-id="trace.production.batchId ?? null"
        :nodes="lineageGroups.flatMap(g => g.nodes)"
      />

      <!-- 加工环节 -->
      <el-card v-if="processingStages.length > 0" shadow="never" class="content-card" data-testid="processing-card">
        <template #header>
          <span class="card-title">加工环节</span>
          <span class="card-title__trailing">{{ processingStages.length }} 道工序</span>
        </template>
        <el-table :data="processingStages" size="default" data-testid="processing-table">
          <el-table-column label="工序" min-width="160">
            <template #default="{ row }">第{{ row.stageOrder }}道 · {{ row.stageName || row.stageType }}</template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作员" width="120" />
          <el-table-column prop="equipmentName" label="设备" width="140" />
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ row.durationMinutes != null ? `${row.durationMinutes} 分钟` : '' }}</template>
          </el-table-column>
          <el-table-column label="投入 / 产出" width="160">
            <template #default="{ row }">
              {{ processQuantities(row) }}
            </template>
          </el-table-column>
          <el-table-column label="损耗率" width="100">
            <template #default="{ row }">{{ percent(row.lossRate) }}</template>
          </el-table-column>
          <el-table-column label="合格率" width="100">
            <template #default="{ row }">{{ percent(row.passRate) }}</template>
          </el-table-column>
          <el-table-column label="时间" min-width="200">
            <template #default="{ row }">{{ formatTimeRange(row.startTime, row.endTime) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 质检 -->
      <el-card v-if="trace.production.batchId != null" shadow="never" class="content-card" data-testid="quality-entry">
        <div class="card-title">本批次质检</div>
        <p v-if="qualityInspections.length === 0">尚未记录抽检结果。已出货不代表已经质检放行，请核对放行依据。</p>
        <p v-else>抽检合格率仅反映被抽取的样本，不代替生产良品率或正式放行结论。</p>
        <el-button v-if="canViewQuality" @click="openBatchQuality">查看或登记本批次质检</el-button>
        <el-button v-if="canViewQuality" @click="openBatchRecall">查看退货与召回</el-button>
        <BatchDispositionEvidence v-if="canViewQuality && qualityInspections.length > 0"
          :factory-id="trace.production.factoryId" :batch-id="trace.production.batchId" />
      </el-card>
      <el-card v-if="qualityInspections.length > 0" shadow="never" class="content-card" data-testid="quality-card">
        <template #header>
          <span class="card-title">质检记录</span>
          <span class="card-title__trailing">{{ qualityInspections.length }} 次</span>
        </template>
        <el-table :data="qualityInspections" size="default" data-testid="quality-table">
          <el-table-column label="检验编号" width="140">
            <template #default="{ row }">质检 #{{ row.inspectionId?.slice(-8) || '-' }}</template>
          </el-table-column>
          <el-table-column label="检验日期" width="120">
            <template #default="{ row }">{{ dateOnly(row.inspectionDate) }}</template>
          </el-table-column>
          <el-table-column label="检验员" width="120">
            <template #default="{ row }">{{ row.inspectorName || (row.inspectorId != null ? `编号 #${row.inspectorId}` : '') }}</template>
          </el-table-column>
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag :type="qualityStatusTag(row.result)" size="small">{{ qualityStatusLabel(row.result) || '待定' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sampleSize" label="抽样数" width="90" />
          <el-table-column prop="passCount" label="抽检合格数" width="110" />
          <el-table-column prop="failCount" label="抽检不合格数" width="120" />
          <el-table-column label="抽检合格率" width="110">
            <template #default="{ row }">{{ row.passRate != null ? `${row.passRate}%` : '' }}</template>
          </el-table-column>
          <el-table-column prop="remarks" label="备注" min-width="160" show-overflow-tooltip />
        </el-table>
      </el-card>

      <!-- 出货 -->
      <el-card v-if="shipments.length > 0" shadow="never" class="content-card" data-testid="shipment-card">
        <template #header>
          <span class="card-title">出货记录</span>
          <span class="card-title__trailing">{{ shipments.length }} 单</span>
        </template>
        <el-table :data="shipments" size="default" data-testid="shipment-table">
          <el-table-column prop="shipmentNumber" label="出货单号" width="180" />
          <el-table-column label="实际出货时间" width="200">
            <template #default="{ row }">
              <span v-if="row.shipmentTimeSource === 'LINEAGE_EVENT' && row.shippedAt" data-testid="actual-shipment-time">{{ formatDateTime(row.shippedAt) }}</span>
              <span v-else-if="row.shipmentTimeSource === 'LEGACY_DATE_ONLY' && row.shipmentDate">{{ dateOnly(row.shipmentDate) }}（仅日期）</span>
              <span v-else>未留存实际时间</span>
            </template>
          </el-table-column>
          <el-table-column label="计划交付日期" width="140">
            <template #default="{ row }">{{ dateOnly(row.plannedDeliveryDate) || '未记录' }}</template>
          </el-table-column>
          <el-table-column prop="customerName" label="客户" min-width="160" />
          <el-table-column label="数量" width="120">
            <template #default="{ row }">{{ row.quantity != null ? `${row.quantity} ${row.unit || ''}` : '' }}</template>
          </el-table-column>
          <el-table-column prop="logisticsCompany" label="物流公司" width="140" />
          <el-table-column prop="trackingNumber" label="物流单号" width="160" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="shipmentStatusTag(row.status)" size="small">{{ shipmentStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 三段里真正为空的那些, 合成一行灰字; 一段都不空时这一行不出现。 -->
      <p v-if="emptyLine" class="muted footer-line" data-testid="empty-sections-line">{{ emptyLine }}</p>
      <p v-if="queryTimeText" class="muted footer-line" data-testid="query-time">查询时间: {{ queryTimeText }}</p>
    </template>

    <IndividualDrawer
      v-model="drawerVisible"
      :individual-id="drawerIndividualId"
      :factory-id="factoryId"
    />
  </div>
</template>

<style scoped lang="scss">
.page-container {
  padding: 20px;
}

// 右下角客服浮球 (AppLayout 的 InlineCustomerService: 48px, 距底 24px) 是 fixed 定位,
// 滚到底时会盖住来源链最后一行的 chevron。底部多留 48+24+24, 让最后一行永远在它上面。
.trace-page {
  padding-bottom: 96px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: var(--font-size-lg);
    color: var(--el-text-color-primary);
  }

  p {
    margin: 4px 0 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}

.content-card {
  border-radius: 10px;
  margin-bottom: 16px;
}

// 按钮轨道定宽: grid 的 `auto` 轨道会被拉伸吃掉整行剩余宽度, 实测「查询」按钮被拉到 ~600px。
.query-bar {
  display: grid;
  grid-template-columns: minmax(260px, 480px) 120px;
  gap: 12px;
  align-items: center;
}

.card-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.card-title__trailing {
  margin-left: 12px;
  font-size: var(--font-size-sm);
  color: var(--el-text-color-secondary);
}

.mono {
  font-family: var(--font-family-mono);
}

.muted {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.footer-line {
  text-align: center;
  font-size: var(--font-size-sm);
  margin-bottom: 8px;
}

// ⛔ 不把溯源码卡拉到与右侧生产信息卡等高 —— 它只有三行, 强制等高就是下半张空白。

.trace-code-card {
  &__label {
    font-size: var(--font-size-sm);
    color: var(--el-text-color-secondary);
  }

  &__code {
    margin-top: 4px;
    font-family: var(--font-family-mono);
    font-size: var(--font-size-lg);
    font-weight: 600;
    color: var(--el-text-color-primary);
    word-break: break-all;
  }

  &__missing {
    margin-top: 4px;
    font-size: var(--font-size-md);
    color: var(--el-text-color-secondary);
  }

  &__batch {
    margin-top: 12px;
    color: var(--el-text-color-regular);
  }
}

// 与首页「现场作业」瓷砖同一族: 一条「这批货从活体来」的信息横幅, 不是警告, ⛔ 不用棕黄。
.source-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background-color: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
  font-size: var(--font-size-md);
  font-weight: 600;

  &__icon {
    flex: none;
    font-size: 20px;
  }
}

.lineage-timeline {
  padding-left: 4px;
}

.lineage-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.one-hop-table {
  margin-top: 8px;
}
</style>
