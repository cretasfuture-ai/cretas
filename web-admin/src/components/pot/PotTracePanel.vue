<script setup lang="ts">
/**
 * S3-16 按锅追溯 (溯源页): 这条链上各道工序的锅次, 以及「某个原料批进了哪几锅、出到了哪些成品」。
 *
 * 只在能证明时按锅收窄 (原料在该批次的量已全部分到锅上); 分不完就按整批, 并说明原因。
 * 批次级召回影响面不受这里影响。
 */
import { computed, ref, watch } from 'vue';
import { handleCatchError } from '@/utils/errorToast';
import { formatDateTime } from '@/utils/dateFormat';
import {
  isPotPending, listPotsForBatches, tracePotConsumers,
  type MaterialPotTrace, type PotView,
} from '@/api/productionPot';

interface ChainNode { nodeType: string; nodeId?: string | null; batchNumber?: string | null; name?: string | null }

const props = defineProps<{
  factoryId: string;
  /** 本批次 + 来源链上的节点 (含原料批 / 生产批)。 */
  rootProductionBatchId: number | null;
  nodes: ChainNode[];
}>();

const productionBatchIds = computed(() => {
  const ids = new Set<number>();
  if (props.rootProductionBatchId != null) ids.add(props.rootProductionBatchId);
  for (const n of props.nodes) {
    if (n.nodeType === 'PRODUCTION_BATCH' && n.nodeId && /^\d+$/.test(n.nodeId)) ids.add(Number(n.nodeId));
  }
  return [...ids];
});
const materialNodes = computed(() => props.nodes.filter(n => n.nodeType === 'MATERIAL_BATCH' && n.nodeId));

const pots = ref<PotView[]>([]);
const loading = ref(false);
const loadError = ref('');
async function load() {
  if (!props.factoryId || productionBatchIds.value.length === 0) {
    pots.value = [];
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const res = await listPotsForBatches(props.factoryId, productionBatchIds.value);
    pots.value = res.success && Array.isArray(res.data) ? res.data.filter(p => p.status !== 'VOIDED') : [];
    if (!res.success) loadError.value = res.message || '锅次加载失败';
  } catch (e) {
    loadError.value = '锅次加载失败';
    handleCatchError(e, '锅次加载失败');
  } finally {
    loading.value = false;
  }
}
watch(() => [props.factoryId, productionBatchIds.value.join(',')], load, { immediate: true });

const pickedMaterial = ref('');
const materialTrace = ref<MaterialPotTrace | null>(null);
const tracing = ref(false);
async function traceMaterial() {
  if (!pickedMaterial.value) return;
  tracing.value = true;
  materialTrace.value = null;
  try {
    const res = await tracePotConsumers(props.factoryId, 'MATERIAL_BATCH', pickedMaterial.value);
    materialTrace.value = res.success ? res.data : null;
  } catch (e) {
    handleCatchError(e, '按锅追溯失败');
  } finally {
    tracing.value = false;
  }
}
function label(n: { batchNumber?: string | null; nodeId?: string | null }) {
  return n.batchNumber || n.nodeId || '-';
}
</script>

<template>
  <el-card shadow="never" class="content-card" data-testid="pot-trace-card">
    <template #header>
      <span class="card-title">按锅追溯</span>
      <span v-if="pots.length > 0" class="card-title__trailing">{{ pots.length }} 锅</span>
    </template>
    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />
    <p v-else-if="loading" class="muted">加载中…</p>
    <p v-else-if="pots.length === 0" class="muted" data-testid="pot-trace-empty">这条链上没有锅次记录：召回按整批计算</p>
    <el-table v-else :data="pots" size="small" data-testid="pot-trace-table">
      <el-table-column label="锅" width="90"><template #default="{ row }">第 {{ row.potNumber }} 锅</template></el-table-column>
      <el-table-column label="工序" min-width="100"><template #default="{ row }">{{ row.processName || row.processCode || '-' }}</template></el-table-column>
      <el-table-column label="下锅 → 出锅" min-width="200">
        <template #default="{ row }">
          {{ row.startedAt ? formatDateTime(row.startedAt) : '未知' }} → {{ row.endedAt ? formatDateTime(row.endedAt) : '未知' }}
          <el-tag v-if="isPotPending(row)" type="warning" size="small">待确认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="用料" min-width="160">
        <template #default="{ row }">
          <span v-if="row.inputs.length === 0" class="muted">未分摊</span>
          <div v-for="i in row.inputs" :key="i.nodeId">{{ label(i) }} {{ Number(i.quantity) }} {{ i.unit || '' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="去向" min-width="160">
        <template #default="{ row }">
          <span v-if="row.outputs.length === 0" class="muted">未分摊</span>
          <div v-for="o in row.outputs" :key="o.nodeId">{{ label(o) }} {{ Number(o.quantity) }} {{ o.unit || '' }}</div>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="materialNodes.length > 0" class="pot-trace-query">
      <span>原料批次进了哪几锅：</span>
      <el-select v-model="pickedMaterial" placeholder="选择链上的原料批次" size="small" style="width: 240px" data-testid="pot-trace-material">
        <el-option v-for="n in materialNodes" :key="n.nodeId!" :label="`${label(n)}${n.name ? ' ' + n.name : ''}`" :value="n.nodeId!" />
      </el-select>
      <el-button size="small" type="primary" :disabled="!pickedMaterial" :loading="tracing" @click="traceMaterial">查询</el-button>
    </div>
    <div v-if="materialTrace" class="pot-trace-result" data-testid="pot-trace-result">
      <p v-if="materialTrace.batches.length === 0" class="muted">没有查到用过这批原料的生产批次</p>
      <div v-for="s in materialTrace.batches" :key="s.productionBatchId" class="pot-trace-scope">
        <div>
          生产批次 <b>{{ s.productionBatchNumber || s.productionBatchId }}</b>：
          <el-tag :type="s.narrowed ? 'success' : 'warning'" size="small">{{ s.narrowed ? '已按锅收窄' : '按整批' }}</el-tag>
          <span class="muted">{{ s.reason }}</span>
        </div>
        <div v-if="s.affectedPots.length > 0">受影响的锅：
          <span v-for="p in s.affectedPots" :key="p.potId" class="pot-chip">
            第 {{ p.potNumber }} 锅<template v-if="p.inputQuantity != null">（{{ Number(p.inputQuantity) }} {{ p.inputUnit || '' }}）</template>
            <el-tag v-if="p.pending" type="warning" size="small">待确认</el-tag>
          </span>
        </div>
        <div>受影响的产出：<span v-for="o in s.affectedOutputs" :key="o.nodeId" class="pot-chip">{{ label(o) }}</span>
          <span v-if="s.affectedOutputs.length === 0" class="muted">无</span></div>
        <div v-if="s.excludedOutputs.length > 0">可排除的产出：<span v-for="o in s.excludedOutputs" :key="o.nodeId" class="pot-chip">{{ label(o) }}</span></div>
        <div>下游成品：<span v-for="o in s.downstreamFinishedGoods" :key="o.nodeId" class="pot-chip">{{ label(o) }}</span>
          <span v-if="s.downstreamFinishedGoods.length === 0" class="muted">尚无</span></div>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.pot-trace-query { margin-top: 12px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pot-trace-result { margin-top: 10px; line-height: 1.9; }
.pot-trace-scope { padding: 6px 0; border-top: 1px dashed var(--el-border-color); }
.pot-chip { display: inline-block; margin-right: 12px; }
.muted { color: var(--el-text-color-secondary); }
</style>
