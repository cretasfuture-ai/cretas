<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { usePermissionStore } from '@/store/modules/permission';
import { get } from '@/api/request';
import { dateOnly, isCalendarDay, isValidPeriod, type ActualFlowPeriod } from './actualFlowPeriod';

interface Batch {
  batchNumber: string;
  productName?: string | null;
  actualQty?: number | null;
  unit?: string | null;
  completedAt: string;
}
const props = defineProps<{ factoryId: string; actorKey?: string; period: ActualFlowPeriod | null }>();
const router = useRouter();
const permissionStore = usePermissionStore();
const canRead = computed(() => permissionStore.canAccess('production'));
const loading = ref(false);
const error = ref('');
const productFilter = ref('');
const batches = ref<Batch[]>([]);
const coverageWarning = ref('');
const anomalies = ref<string[]>([]);
let requestSequence = 0;
let alive = true;
const filteredBatches = computed(() => batches.value.filter(batch => !productFilter.value.trim()
  || (batch.productName || '').toLowerCase().includes(productFilter.value.trim().toLowerCase())));
const identity = () => JSON.stringify([props.factoryId, props.actorKey, props.period, canRead.value]);

async function load() {
  const sequence = ++requestSequence;
  const context = identity();
  const isCurrent = () => alive && sequence === requestSequence && context === identity();
  batches.value = []; anomalies.value = []; error.value = ''; loading.value = false; coverageWarning.value = '';
  if (!alive) return;
  if (!canRead.value) { coverageWarning.value = '当前账号没有生产查看权限'; return; }
  if (!props.factoryId || !isValidPeriod(props.period)) {
    coverageWarning.value = '请选择完整且有效的日期范围，未查询完工批次'; return;
  }
  const [start, end] = props.period;
  const today = new Date();
  const firstFullDay = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 89);
  if (start < dateOnly(firstFullDay) || end > dateOnly(today)) {
    coverageWarning.value = '完工批次仅支持最近90天的完整日期；当前范围未查询，不能据此判断没有业务'; return;
  }
  loading.value = true;
  try {
    const res = await get<unknown>(`/${props.factoryId}/production/batches/finished`, { params: { days: 90 } });
    if (!isCurrent()) return;
    if (!res.success || !Array.isArray(res.data)) throw new Error(res.message || '完工批次接口返回结构无效');
    const counts = new Map<string, number>();
    // Identity must be unique in the raw response, including malformed/out-of-period rows.
    for (const value of res.data as unknown[]) {
      if (value && typeof value === 'object') {
        const number = (value as Record<string, unknown>).batchNumber;
        if (typeof number === 'string' && number.trim()) counts.set(number.trim(), (counts.get(number.trim()) || 0) + 1);
      }
    }
    const valid: Batch[] = [];
    const issues: string[] = [];
    for (const value of res.data as unknown[]) {
      if (!value || typeof value !== 'object') throw new Error('完工批次接口返回结构无效');
      const row = value as Record<string, unknown>;
      if (typeof row.batchNumber !== 'string' || !row.batchNumber.trim()) { issues.push('存在缺少批次号的记录'); continue; }
      if (typeof row.completedAt !== 'string' || !isCalendarDay(row.completedAt.slice(0, 10))) {
        issues.push(`批次 ${row.batchNumber} 缺少有效完工时间`); continue;
      }
      const day = row.completedAt.slice(0, 10);
      if (day < start || day > end) continue;
      if (row.productName != null && typeof row.productName !== 'string') throw new Error('完工批次成品名称格式无效');
      if (row.unit != null && typeof row.unit !== 'string') throw new Error('完工批次数量单位格式无效');
      if (row.actualQty != null && (typeof row.actualQty !== 'number' || !Number.isFinite(row.actualQty) || row.actualQty < 0)) {
        issues.push(`批次 ${row.batchNumber} 登记产量无效`); continue;
      }
      valid.push({ ...row, batchNumber: row.batchNumber.trim() } as unknown as Batch);
    }
    const duplicates = [...counts].filter(([, count]) => count > 1).map(([number]) => number);
    issues.push(...duplicates.map(number => `批次 ${number} 身份重复，暂不展示`));
    batches.value = valid.filter(row => counts.get(row.batchNumber) === 1);
    anomalies.value = issues;
  } catch (failure) {
    if (isCurrent()) error.value = failure instanceof Error ? `完工批次加载失败：${failure.message}` : '完工批次加载失败，请重试';
  } finally { if (isCurrent()) loading.value = false; }
}
function openTrace(batchNumber: string) {
  if (canRead.value && batches.value.some(row => row.batchNumber === batchNumber)) {
    void router.push({ path: '/production/traceability', query: { batchNumber } });
  }
}
watch(identity, () => { productFilter.value = ''; void load(); }, { immediate: true });
onDeactivated(() => { alive = false; ++requestSequence; batches.value = []; });
onActivated(() => { if (!alive) { alive = true; void load(); } });
onBeforeUnmount(() => { alive = false; ++requestSequence; });
</script>

<template>
  <el-card class="finished-batch-evidence" shadow="never">
    <template #header>
      <div class="evidence-header"><span>本期完工批次</span><el-button size="small" :loading="loading" @click="load">刷新批次</el-button></div>
    </template>
    <p class="evidence-note">完工批次不等于已满足指标纳入口径。可逐批核对投料、质量和成本完整性；不同单位的数量不混加。</p>
    <el-input v-model="productFilter" clearable placeholder="按成品名称筛选" aria-label="按成品名称筛选" size="small" class="product-filter" />
    <div v-if="loading" role="status">正在读取完工批次…</div>
    <el-alert v-else-if="error" type="error" :closable="false" :title="error"><el-button size="small" @click="load">重试</el-button></el-alert>
    <el-alert v-else-if="coverageWarning" type="warning" :closable="false" :title="coverageWarning" />
    <el-empty v-else-if="filteredBatches.length === 0" :description="productFilter ? '没有匹配该成品的完工批次' : '该期间没有可显示的完工批次'" />
    <el-table v-else :data="filteredBatches" size="small" stripe>
      <el-table-column prop="productName" label="成品" min-width="160" />
      <el-table-column prop="batchNumber" label="批次" min-width="230" />
      <el-table-column label="登记产量" min-width="120"><template #default="{ row }">{{ row.actualQty ?? '未记录' }} {{ row.unit || '（单位未记录）' }}</template></el-table-column>
      <el-table-column label="完工日期" min-width="110"><template #default="{ row }">{{ row.completedAt.slice(0, 10) }}</template></el-table-column>
      <el-table-column label="数据来源" width="110"><template #default="{ row }"><el-button link type="primary" @click="openTrace(row.batchNumber)">查看追溯</el-button></template></el-table-column>
    </el-table>
    <p v-if="anomalies.length" class="anomaly">数据待核对：{{ anomalies.join('；') }}</p>
  </el-card>
</template>

<style scoped>
.evidence-header { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.evidence-note { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 12px; }
.product-filter { max-width: 280px; margin-bottom: 12px; }
.anomaly { color: var(--el-color-warning); font-size: 12px; margin-bottom: 0; }
</style>
