<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';
import { getProductProductionMetrics, type ProductProductionMetrics, type ProductProductionResult } from '@/api/productProductionMetrics';
import { isValidPeriod, type ActualFlowPeriod } from './actualFlowPeriod';

const props = defineProps<{ factoryId: string; actorKey?: string; period: ActualFlowPeriod | null }>();
const router = useRouter();
const permissionStore = usePermissionStore();
const canRead = computed(() => permissionStore.canAccess('production'));
const loading = ref(false);
const error = ref('');
const notice = ref('');
const products = ref<ProductProductionResult[]>([]);
const expandedRows = ref<string[]>([]);
let sequence = 0;
let alive = true;
const identity = () => JSON.stringify([props.factoryId, props.actorKey, props.period, canRead.value]);
const periodLabel = computed(() => props.period ? `${props.period[0]} 至 ${props.period[1]}` : '请选择统计期间');
const rowKey = (row: ProductProductionResult) => row.productTypeId ?? '__unassigned__';

function validate(result: ProductProductionMetrics, period: ActualFlowPeriod) {
  if (result.periodStart !== period[0] || result.periodEnd !== period[1] || result.sourceVersion !== 'actual-flow-v3') {
    throw new Error('返回结果与所选期间或业务口径不一致');
  }
  if (!Array.isArray(result.products)) throw new Error('成品结果格式无效');
  const ids = new Set<string>(); const batches = new Set<string>();
  for (const row of result.products) {
    if (!row || typeof row.productName !== 'string' || !row.productName.trim()
      || (row.productTypeId != null && (typeof row.productTypeId !== 'string' || !row.productTypeId.trim()))
      || typeof row.costMasked !== 'boolean' || !Array.isArray(row.batchNumbers)
      || !Number.isInteger(row.batchCount) || row.batchCount <= 0 || row.batchCount !== row.batchNumbers.length
      || [row.yieldRate, row.goodOutputRate, row.totalCost, row.unitCost].some(v => v != null && (typeof v !== 'number' || !Number.isFinite(v) || v < 0))) {
      throw new Error('成品结果或批次数量无效');
    }
    const id = rowKey(row);
    if (ids.has(id)) throw new Error('存在重复的成品身份');
    ids.add(id);
    for (const batch of row.batchNumbers) {
      if (typeof batch !== 'string' || !batch.trim() || batches.has(batch)) throw new Error('存在缺失或重复的来源批次');
      batches.add(batch);
    }
  }
}
async function load() {
  const ownSequence = ++sequence; const context = identity();
  const current = () => alive && ownSequence === sequence && context === identity();
  products.value = []; expandedRows.value = []; error.value = ''; notice.value = ''; loading.value = false;
  if (!alive) return;
  if (!canRead.value) { notice.value = '当前账号没有生产查看权限'; return; }
  if (!props.factoryId || !isValidPeriod(props.period)) { notice.value = '请选择完整且有效的统计期间'; return; }
  const period: ActualFlowPeriod = [...props.period];
  const span = (Date.parse(`${period[1]}T00:00:00Z`) - Date.parse(`${period[0]}T00:00:00Z`)) / 86400000;
  if (span > 89) { notice.value = '成品经营结果每次支持不超过90天，请缩小统计期间'; return; }
  loading.value = true;
  try {
    const result = await getProductProductionMetrics(props.factoryId, period);
    if (!current()) return;
    validate(result, period); products.value = result.products;
  } catch (failure) {
    if (current()) error.value = failure instanceof Error ? `成品经营结果读取失败：${failure.message}` : '成品经营结果读取失败，请重试';
  } finally { if (current()) loading.value = false; }
}
function openTrace(batchNumber: string) {
  if (canRead.value && products.value.some(row => row.batchNumbers.includes(batchNumber))) {
    void router.push({ path: '/production/traceability', query: { batchNumber } });
  }
}
function toggleBatches(row: ProductProductionResult) {
  expandedRows.value = expandedRows.value.includes(rowKey(row)) ? [] : [rowKey(row)];
}
const number = (value?: number | null) => value == null ? '待补数据' : value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
watch(identity, () => { void load(); }, { immediate: true });
onDeactivated(() => { alive = false; ++sequence; products.value = []; expandedRows.value = []; });
onActivated(() => { if (!alive) { alive = true; void load(); } });
onBeforeUnmount(() => { alive = false; ++sequence; });
</script>

<template>
  <el-card class="product-production-results" shadow="never" data-testid="product-production-results">
    <template #header><div class="results-header"><span>按成品查看本期结果</span><el-button size="small" :loading="loading" @click="load">刷新成品结果</el-button></div></template>
    <p class="results-period">统计期间：{{ periodLabel }}</p>
    <p class="results-note">按完工日期统计，盒、袋等沿用生产时固定的重量。联产时，本成品产出公斤数÷同计划全部新鲜原料公斤数为贡献出成率，各成品贡献相加才是计划总出成率；共享投入只计一次，成本沿用正式结单分摊。缺少完整报工、质量计数或成本时显示“待补数据”。</p>
    <div v-if="loading" role="status">正在读取成品经营结果…</div>
    <el-alert v-else-if="error" :title="error" type="error" :closable="false"><el-button size="small" @click="load">重试</el-button></el-alert>
    <el-alert v-else-if="notice" :title="notice" type="warning" :closable="false" />
    <el-empty v-else-if="!products.length" description="该期间没有已完工且已结单的成品批次" />
    <el-table v-else :data="products" :row-key="rowKey" :expand-row-keys="expandedRows" size="small" stripe>
      <el-table-column type="expand" width="40"><template #default="{ row }"><div class="batch-links"><span>本行来源批次：</span><el-button v-for="batch in row.batchNumbers" :key="batch" link type="primary" @click="openTrace(batch)">{{ batch }} · 查看追溯</el-button></div></template></el-table-column>
      <el-table-column prop="productName" label="成品" min-width="190" />
      <el-table-column prop="batchCount" label="完工批次" width="90" />
      <el-table-column label="实际出成率" min-width="110"><template #default="{ row }">{{ number(row.yieldRate) }}{{ row.yieldRate == null ? '' : '%' }}</template></el-table-column>
      <el-table-column label="生产良品率" min-width="110"><template #default="{ row }">{{ number(row.goodOutputRate) }}{{ row.goodOutputRate == null ? '' : '%' }}</template></el-table-column>
      <el-table-column label="完整成本（元）" min-width="130"><template #default="{ row }">{{ row.costMasked ? '权限隐藏' : number(row.totalCost) }}</template></el-table-column>
      <el-table-column label="每公斤合格品成本（元）" min-width="170"><template #default="{ row }">{{ row.costMasked ? '权限隐藏' : number(row.unitCost) }}</template></el-table-column>
      <el-table-column label="来源" width="100"><template #default="{ row }"><el-button link type="primary" @click="toggleBatches(row)">查看批次</el-button></template></el-table-column>
    </el-table>
  </el-card>
</template>

<style scoped>
.product-production-results { margin-top: 20px; }
.results-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.results-period { margin: 0 0 8px; font-size: 13px; }
.results-note { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 12px; }
.batch-links { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 12px 20px; }
</style>
