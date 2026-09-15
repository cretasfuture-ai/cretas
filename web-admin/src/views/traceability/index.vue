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
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search } from '@element-plus/icons-vue';
import { useAuthStore } from '@/store/modules/auth';
import { getFullTrace, isTraceNotFound, type FullTraceResponse } from '@/api/traceability';
import { bannerText, summarizeSourceIndividuals } from './lineageBanner';
import {
  depthLabel,
  emptySectionsLine,
  isBlank,
  qualityStatusLabel,
  qualityStatusTag,
  shipmentStatusLabel,
  shipmentStatusTag,
  type EmptySectionKey,
} from './traceDisplay';
import LineageNodeRow from './LineageNodeRow.vue';
import IndividualDrawer from './IndividualDrawer.vue';

const authStore = useAuthStore();
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

onMounted(() => {
  // `?batchNumber=` 直接带参打开 —— 投屏前把地址敲好, 上台只按回车。
  const q = route?.query?.batchNumber;
  const initial = typeof q === 'string' ? q.trim() : '';
  if (initial) {
    batchInput.value = initial;
    void runQuery(initial);
  }
});

async function handleSearch() {
  const batchNumber = batchInput.value.trim();
  if (!batchNumber) return;
  // 地址栏同步, 让这一条链路可以被复制/收藏。replace 而不是 push: 连查三次不该留三条历史。
  if (route?.query?.batchNumber !== batchNumber) {
    void router.replace({ query: { ...route.query, batchNumber } });
  }
  await runQuery(batchNumber);
}

async function runQuery(batchNumber: string) {
  if (!factoryId.value) {
    status.value = 'error';
    errorMessage.value = '当前账号没有工厂信息，无法查询溯源';
    return;
  }
  status.value = 'loading';
  queriedBatch.value = batchNumber;
  trace.value = null;
  errorMessage.value = '';
  try {
    const res = await getFullTrace(factoryId.value, batchNumber);
    if (res.success && res.data) {
      trace.value = res.data;
      status.value = 'ready';
    } else {
      // 拦截器对 success:false 一律 reject, 这个分支正常到不了; 到了就是拦截器变了。
      status.value = 'error';
      errorMessage.value = res.message || '溯源查询失败';
    }
  } catch (error) {
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
    { label: '产量', value: p.quantity != null ? `${p.quantity} ${p.unit || ''}`.trim() : '' },
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
const emptyLine = computed(() => {
  const empties: EmptySectionKey[] = [];
  if (processingStages.value.length === 0) empties.push('processingStages');
  if (qualityInspections.value.length === 0) empties.push('qualityInspections');
  if (shipments.value.length === 0) empties.push('shipments');
  return emptySectionsLine(empties);
});

function percent(rate?: number | null): string {
  return rate == null ? '' : `${(rate * 100).toFixed(1)}%`;
}
function dateOnly(value?: string | null): string {
  return value ? value.split('T')[0] : '';
}
function timeRange(start?: string, end?: string): string {
  if (!start) return '';
  const s = new Date(start).toLocaleString();
  return end ? `${s} ~ ${new Date(end).toLocaleTimeString()}` : s;
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
            <template #header><span class="card-title">生产信息</span></template>
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

      <!-- 「本批来自 N 头牛」横幅 -->
      <div v-if="banner" class="source-banner" data-testid="source-individual-banner">
        {{ banner }}
      </div>

      <!-- 来源链 -->
      <el-card shadow="never" class="content-card" data-testid="lineage-card">
        <template #header>
          <span class="card-title">来源链</span>
          <span v-if="trace.lineage && trace.lineage.nodeCount > 0" class="card-title__trailing">
            {{ trace.lineage.nodeCount }} 个上游节点
          </span>
        </template>

        <!-- ① 本次没拿到 —— 与「没有上游」是两句话 -->
        <p v-if="lineageUnavailable" class="muted" data-testid="lineage-unavailable">
          来源链暂时取不到，请稍后重试
        </p>
        <!-- ② 确实没有上游 -->
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
          <el-table-column label="消耗量" width="120">
            <template #default="{ row }">{{ row.quantity != null ? `${row.quantity} ${row.unit || ''}` : '' }}</template>
          </el-table-column>
          <el-table-column prop="receiptDate" label="入库日期" width="120" />
        </el-table>
      </el-card>

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
              {{ row.inputWeight != null && row.outputWeight != null ? `${row.inputWeight}kg → ${row.outputWeight}kg` : '' }}
            </template>
          </el-table-column>
          <el-table-column label="损耗率" width="100">
            <template #default="{ row }">{{ percent(row.lossRate) }}</template>
          </el-table-column>
          <el-table-column label="合格率" width="100">
            <template #default="{ row }">{{ percent(row.passRate) }}</template>
          </el-table-column>
          <el-table-column label="时间" min-width="200">
            <template #default="{ row }">{{ timeRange(row.startTime, row.endTime) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 质检 -->
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
          <el-table-column prop="inspectorName" label="检验员" width="120" />
          <el-table-column label="结果" width="120">
            <template #default="{ row }">
              <el-tag :type="qualityStatusTag(row.result)" size="small">{{ qualityStatusLabel(row.result) || '待定' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="合格率" width="100">
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
          <el-table-column prop="shipmentDate" label="出货日期" width="120" />
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
      <p class="muted footer-line">
        查询时间: {{ trace.queryTime ? new Date(trace.queryTime).toLocaleString() : '-' }}
      </p>
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

.query-bar {
  display: grid;
  grid-template-columns: minmax(260px, 480px) auto;
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

.trace-row .content-card {
  height: calc(100% - 16px);
}

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
  margin-bottom: 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background-color: var(--el-color-primary-light-9);
  color: var(--el-color-primary-dark-2);
  font-size: var(--font-size-md);
  font-weight: 600;
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
