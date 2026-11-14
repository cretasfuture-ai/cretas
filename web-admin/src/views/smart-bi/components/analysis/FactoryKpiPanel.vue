<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get } from '@/api/request'
import { useAuthStore } from '@/store/modules/auth'

interface KpiMetricsResponse {
  oee?: number | null; outputCompletionRate?: number | null; avgCycleTime?: number | null; throughput?: number | null
  fpy?: number | null; overallQualityRate?: number | null; scrapRate?: number | null; reworkRate?: number | null
  unitCost?: number | null; materialCostRatio?: number | null; laborCostRatio?: number | null; overheadCostRatio?: number | null
  otif?: number | null; onTimeDeliveryRate?: number | null; inFullDeliveryRate?: number | null; avgLeadTime?: number | null; orderFulfillmentRate?: number | null
}
type Metrics = {
  production: { oee: number | null; yield: number | null; cycleTime: number | null; throughput: number | null }
  quality: { fpy: number | null; defectRate: number | null; reworkRate: number | null; scrapRate: number | null }
  delivery: { onTimeRate: number | null; leadTime: number | null; fillRate: number | null }
  cost: { unitCost: number | null; materialCost: number | null; laborCost: number | null; overheadCost: number | null }
}
const authStore = useAuthStore(); const loading = ref(false); const hasData = ref(false); const loadError = ref('')
const targets = { oee: 85, yield: 95, fpy: 90, onTimeRate: 95 }
function emptyMetrics(): Metrics { return { production: { oee: null, yield: null, cycleTime: null, throughput: null }, quality: { fpy: null, defectRate: null, reworkRate: null, scrapRate: null }, delivery: { onTimeRate: null, leadTime: null, fillRate: null }, cost: { unitCost: null, materialCost: null, laborCost: null, overheadCost: null } } }
const metrics = ref<Metrics>(emptyMetrics())
const pct = (v: number | null | undefined): number | null => v == null ? null : v / 100
const barPct = (v: number | null): number => (v ?? 0) * 100
const formatPercent = (v: number | null | undefined): string => v == null ? '—' : `${(v * 100).toFixed(1)}%`
const formatMeasure = (v: number | null | undefined, unit = ''): string => v == null ? '—' : `${v}${unit}`
function progressStatus(v: number | null, target: number): 'success' | 'warning' | 'exception' | undefined { if (v == null) return undefined; const ratio = v / target; return ratio >= 1 ? 'success' : ratio >= 0.8 ? 'warning' : 'exception' }
async function load() {
  const factoryId = authStore.factoryId
  if (!factoryId) { metrics.value = emptyMetrics(); hasData.value = false; return }
  loading.value = true; loadError.value = ''
  try {
    const response = await get<KpiMetricsResponse>(`/${factoryId}/reports/kpi-metrics`)
    if (!response.success) throw new Error(response.message || '加载 KPI 数据失败')
    const d = response.data
    if (!d) { metrics.value = emptyMetrics(); hasData.value = false; return }
    metrics.value = { production: { oee: pct(d.oee), yield: pct(d.overallQualityRate), cycleTime: d.avgCycleTime ?? null, throughput: d.throughput ?? null }, quality: { fpy: pct(d.fpy), defectRate: pct(d.fpy == null ? null : 100 - d.fpy), reworkRate: pct(d.reworkRate), scrapRate: pct(d.scrapRate) }, delivery: { onTimeRate: pct(d.onTimeDeliveryRate ?? d.otif), leadTime: d.avgLeadTime ?? null, fillRate: pct(d.inFullDeliveryRate ?? d.orderFulfillmentRate) }, cost: { unitCost: d.unitCost ?? null, materialCost: pct(d.materialCostRatio), laborCost: pct(d.laborCostRatio), overheadCost: pct(d.overheadCostRatio) } }
    hasData.value = Object.values(d).some(v => v != null)
  } catch (error) { metrics.value = emptyMetrics(); hasData.value = false; loadError.value = error instanceof Error ? error.message : '加载 KPI 数据失败，请刷新重试'; ElMessage.error(loadError.value) } finally { loading.value = false }
}
const showEmpty = computed(() => !loading.value && !loadError.value && !hasData.value); onMounted(load)
</script>

<template>
  <section class="factory-kpi-panel" aria-label="工厂 KPI 看板">
    <div class="page-header"><h2>工厂 KPI 看板</h2><el-button type="primary" size="small" :loading="loading" @click="load">刷新数据</el-button></div>
    <el-alert v-if="loadError" type="error" :closable="false" show-icon :title="loadError" />
    <el-empty v-if="showEmpty" description="暂无工厂 KPI 数据" />
    <div v-else class="kpi-grid" v-loading="loading">
      <el-card class="kpi-card"><template #header><div class="card-header">生产效率</div></template>
        <div class="kpi-item"><div class="kpi-label">设备综合效率 (OEE)</div><el-progress :percentage="barPct(metrics.production.oee)" :show-text="metrics.production.oee !== null" :status="progressStatus(metrics.production.oee == null ? null : metrics.production.oee * 100, targets.oee)" /><div class="kpi-meta"><span>当前: {{ formatPercent(metrics.production.oee) }}</span><span class="target">目标: {{ targets.oee }}%</span></div></div>
        <div class="kpi-item"><div class="kpi-label">良品率</div><el-progress :percentage="barPct(metrics.production.yield)" :show-text="metrics.production.yield !== null" :status="progressStatus(metrics.production.yield == null ? null : metrics.production.yield * 100, targets.yield)" /><div class="kpi-meta"><span>当前: {{ formatPercent(metrics.production.yield) }}</span><span class="target">目标: {{ targets.yield }}%</span></div></div>
        <div class="kpi-stats"><div class="stat"><div class="stat-value">{{ formatMeasure(metrics.production.throughput) }}</div><div class="stat-label">日产出</div></div><div class="stat"><div class="stat-value">{{ formatMeasure(metrics.production.cycleTime, 'min') }}</div><div class="stat-label">周期时间</div></div></div>
      </el-card>
      <el-card class="kpi-card"><template #header><div class="card-header">质量指标</div></template>
        <div class="kpi-item"><div class="kpi-label">一次合格率 (FPY)</div><el-progress :percentage="barPct(metrics.quality.fpy)" :show-text="metrics.quality.fpy !== null" :status="progressStatus(metrics.quality.fpy == null ? null : metrics.quality.fpy * 100, targets.fpy)" /><div class="kpi-meta"><span>当前: {{ formatPercent(metrics.quality.fpy) }}</span><span class="target">目标: {{ targets.fpy }}%</span></div></div>
        <div class="kpi-stats"><div class="stat danger"><div class="stat-value">{{ formatPercent(metrics.quality.defectRate) }}</div><div class="stat-label">缺陷率</div></div><div class="stat warning"><div class="stat-value">{{ formatPercent(metrics.quality.reworkRate) }}</div><div class="stat-label">返工率</div></div><div class="stat"><div class="stat-value">{{ formatPercent(metrics.quality.scrapRate) }}</div><div class="stat-label">报废率</div></div></div>
      </el-card>
      <el-card class="kpi-card"><template #header><div class="card-header">交付指标</div></template>
        <div class="kpi-item"><div class="kpi-label">准时交付率</div><el-progress :percentage="barPct(metrics.delivery.onTimeRate)" :show-text="metrics.delivery.onTimeRate !== null" :status="progressStatus(metrics.delivery.onTimeRate == null ? null : metrics.delivery.onTimeRate * 100, targets.onTimeRate)" /><div class="kpi-meta"><span>当前: {{ formatPercent(metrics.delivery.onTimeRate) }}</span><span class="target">目标: {{ targets.onTimeRate }}%</span></div></div>
        <div class="kpi-stats"><div class="stat"><div class="stat-value">{{ formatMeasure(metrics.delivery.leadTime, '天') }}</div><div class="stat-label">平均交期</div></div><div class="stat"><div class="stat-value">{{ formatPercent(metrics.delivery.fillRate) }}</div><div class="stat-label">订单满足率</div></div></div>
      </el-card>
      <el-card class="kpi-card"><template #header><div class="card-header">成本结构</div></template>
        <div class="cost-item"><span>单位成本</span><strong>{{ metrics.cost.unitCost == null ? '—' : `¥${metrics.cost.unitCost.toFixed(2)}` }}</strong></div><el-divider />
        <div class="cost-line"><div class="cost-bar"><span :style="{ width: barPct(metrics.cost.materialCost) + '%' }"></span></div><span>原料 {{ formatPercent(metrics.cost.materialCost) }}</span></div><div class="cost-line"><div class="cost-bar"><span :style="{ width: barPct(metrics.cost.laborCost) + '%' }"></span></div><span>人工 {{ formatPercent(metrics.cost.laborCost) }}</span></div><div class="cost-line"><div class="cost-bar"><span :style="{ width: barPct(metrics.cost.overheadCost) + '%' }"></span></div><span>间接 {{ formatPercent(metrics.cost.overheadCost) }}</span></div>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.factory-kpi-panel { padding: 20px; }.page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:20px; }.page-header h2 { margin:0; font-size:20px; }.kpi-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; min-height:180px; }.kpi-card { border-radius:12px; }.card-header { font-weight:600; }.kpi-item { margin-bottom:20px; }.kpi-label { font-size:13px; color:#606266; margin-bottom:8px; }.kpi-meta { display:flex; justify-content:space-between; margin-top:8px; font-size:12px; color:#909399; }.target { color:#67c23a; }.kpi-stats { display:flex; gap:16px; padding-top:16px; border-top:1px solid #ebeef5; }.stat { flex:1; text-align:center; padding:12px; background:#f5f7fa; border-radius:8px; }.stat-value { font-size:20px; font-weight:600; color:#303133; }.stat-label { font-size:12px; color:#909399; margin-top:4px; }.danger .stat-value { color:#f56c6c; }.warning .stat-value { color:#e6a23c; }.cost-item,.cost-line { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; color:#606266; }.cost-item strong { font-size:24px; color:#303133; }.cost-bar { flex:1; height:8px; margin-right:10px; background:#f0f2f5; border-radius:4px; overflow:hidden; }.cost-bar span { display:block; height:100%; background:var(--cs-primary); border-radius:4px; }.cost-line:nth-of-type(4) .cost-bar span { background:#67c23a; }.cost-line:nth-of-type(5) .cost-bar span { background:#e6a23c; }@media (max-width:768px) { .kpi-grid { grid-template-columns:1fr; } }
</style>
