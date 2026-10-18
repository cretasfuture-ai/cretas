<template>
  <el-card class="indicator-applicability" shadow="never">
    <template #header>
      <div class="applicability-header"><span>指标适用性</span><el-tag v-if="report" :type="overallTag">{{ overallLabel }}</el-tag></div>
    </template>
    <el-skeleton v-if="loading" :rows="3" animated />
    <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
    <template v-else-if="report">
      <p class="basis">{{ report.basis === 'CONFIGURATION_ONLY' ? '基于当前配置，仅说明口径可用性，不代表已有运行数据。' : '基于拟配置资料的预评估。' }}</p>
      <el-table :data="report.metrics" size="small" empty-text="暂无产品配置">
        <el-table-column prop="name" label="指标" min-width="190" />
        <el-table-column label="配置状态" width="130"><template #default="scope"><el-tag :type="tagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="reason" label="说明" min-width="300" />
      </el-table>
      <div v-if="report.products.length" class="product-notes">
        <div v-for="product in report.products" :key="product.productId" class="product-note">
          <span>{{ product.productName || product.productId }}</span>
          <span v-for="metric in product.metrics" :key="metric.code" class="metric-note">
            {{ metric.name }}：{{ statusLabel(metric.status) }}（{{ metric.reason }}）
            <template v-if="metric.requiredFacts?.length">；需补充：{{ metric.requiredFacts.join('、') }}</template>
          </span>
        </div>
      </div>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { getIndicatorApplicability, type IndicatorApplicabilityReport, type ApplicabilityStatus } from '@/api/indicator';

const props = defineProps<{ factoryId: string; providedReport?: IndicatorApplicabilityReport | null; actorKey?: string }>();
const report = ref<IndicatorApplicabilityReport | null>(props.providedReport?.factoryId === props.factoryId ? props.providedReport : null);
const loading = ref(false);
const error = ref<string | null>(null);
let sequence = 0;
const usableProvided = () => props.providedReport?.factoryId === props.factoryId ? props.providedReport : null;
const overallLabel = computed(() => report.value?.overallStatus === 'COMPLETE' ? '配置完整' : report.value?.overallStatus === 'BLOCKED' ? '存在阻断' : '部分待确认');
const overallTag = computed(() => report.value?.overallStatus === 'COMPLETE' ? 'success' : report.value?.overallStatus === 'BLOCKED' ? 'danger' : 'warning');
function statusLabel(status: ApplicabilityStatus) { return ({ APPLICABLE: '可适用', BLOCKED: '已阻断', NOT_APPLICABLE: '不适用', NEEDS_CONFIRMATION: '待确认' } as Record<string, string>)[status] || status; }
function tagType(status: ApplicabilityStatus) { return status === 'APPLICABLE' ? 'success' : status === 'BLOCKED' ? 'danger' : status === 'NOT_APPLICABLE' ? 'info' : 'warning'; }
async function load() {
  const id = ++sequence; report.value = usableProvided(); error.value = null; loading.value = false;
  if (usableProvided() || !props.factoryId) return;
  loading.value = true;
  try {
    const next = await getIndicatorApplicability(props.factoryId);
    if (id !== sequence) return;
    if (next.factoryId !== props.factoryId || !Array.isArray(next.products) || !Array.isArray(next.metrics)) {
      throw new Error('指标适用性返回的工厂或资料格式不一致，请刷新后重试');
    }
    report.value = next;
  }
  catch (e) { if (id === sequence) { report.value = null; error.value = e instanceof Error ? e.message : '指标适用性读取失败'; } }
  finally { if (id === sequence) loading.value = false; }
}
watch(() => [props.factoryId, props.actorKey, props.providedReport], load, { deep: true });
onMounted(load);
onBeforeUnmount(() => { sequence += 1; });
</script>

<style scoped>
.applicability-header { display:flex; align-items:center; justify-content:space-between; font-weight:600; }
.basis { margin:0 0 12px; color:var(--el-text-color-secondary); font-size:13px; }
.product-notes { margin-top:12px; display:flex; flex-direction:column; gap:6px; }
.product-note { display:flex; flex-wrap:wrap; gap:8px 14px; font-size:12px; color:var(--el-text-color-secondary); }
.metric-note { color:var(--el-text-color-regular); }
</style>
