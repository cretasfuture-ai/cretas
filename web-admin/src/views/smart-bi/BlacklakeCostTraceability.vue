<script setup lang="ts">
import CostDataTable from '@/components/smartbi/CostDataTable.vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import UCard from '@nuxt/ui/components/Card.vue';
import UButton from '@nuxt/ui/components/Button.vue';
import { getBlacklakeCostTraceability, type CostTraceabilityReport, type TraceableWorkOrder } from '@/api/smartbi/blacklake';
import { useAuthStore } from '@/store/modules/auth';
import { formatBlacklakeDateTime } from './blacklakePeriod';

const report = ref<CostTraceabilityReport | null>(null);
const loading = ref(false); const error = ref(''); const search = ref(''); const filter = ref('all');
const selected = ref<TraceableWorkOrder | null>(null); const page = ref(1); let generation = 0;
const auth = useAuthStore();
const identity = computed(() => [auth.factoryId, auth.user?.id ?? '', auth.currentRole].join(':'));
async function load() {
  const current = ++generation; report.value = null; selected.value = null; error.value = ''; loading.value = true;
  try { const next = await getBlacklakeCostTraceability(); if (current === generation) report.value = next; }
  catch (cause) { if (current === generation) error.value = cause instanceof Error ? cause.message : '最新补查暂不可用'; }
  finally { if (current === generation) loading.value = false; }
}
onMounted(():void => {void load();});
watch(identity, () => void load());
onBeforeUnmount(() => { generation += 1; });
watch([search, filter], () => { page.value = 1; selected.value = null; });
const filtered = computed(() => (report.value?.workOrders || []).filter(row => {
  const match = `${row.workOrderCode} ${row.product} ${row.needs.map(n => n.responsibleRole).join(' ')}`.toLowerCase().includes(search.value.toLowerCase());
  return match && (filter.value === 'all' || (filter.value === 'blocked' && !row.directCostAvailable)
    || (filter.value === 'new' && row.directCostAvailable && row.baselineComparison?.directCostAvailable === false)
    || (filter.value === 'regressed' && !row.directCostAvailable && row.baselineComparison?.directCostAvailable === true));
}));
const visible = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10));
function amount(value: number | null) { return value == null ? '暂不可算' : value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
function clueValue(value: string | number) { const n = Number(value); return value !== '' && Number.isFinite(n) ? amount(n) : String(value); }
function status(value: string) {
  const labels: Record<string, string> = { pending_completion: '未完工，待完成后核对', query_incomplete: '接口未查完整',
    source_values_inconsistent: '单价或数量金额符号不一致',
    source_identity_conflict: '源单工单关联冲突', source_zero_or_missing_unverified: '零值或缺值待核',
    zero_or_missing_unverified: '计薪口径待核', batch_relationship_not_supplied: '接口未提供批次分配',
    missing_in_queried_response: '已查接口证据不足' };
  return labels[value] || '待核实';
}
</script>

<template>
  <UCard class="trace-card" data-testid="cost-traceability">
    <template #header><div class="trace-heading"><div><h3>待补资料与工单核对</h3><p>逐单看缺什么、找谁补、补齐后能判断什么。</p></div><UButton color="neutral" variant="outline" :loading="loading" @click="load">刷新补查清单</UButton></div></template>
    <p v-if="loading" role="status">正在读取已保存的只读补查结果…</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <template v-if="report">
      <p class="trace-note">本清单核对原 {{ report.summary.workOrders }} 张工单，不随上方日期筛选改变。</p>
      <details><summary>补查范围与时间</summary><p class="trace-note">补查截至 {{ formatBlacklakeDateTime(report.checkedAt) }}。下表按原{{ report.summary.workOrders }}张工单列示，与上方旧快照的日期筛选及金额分别核对。新增 {{ report.sourceProbe?.newOrdersOutsideOriginalCohort ?? '未提供' }} 张工单单列，未混入。</p></details>
      <el-alert v-if="report.sourceProbe?.allRequiredQueriesComplete === false" type="warning" :closable="false" title="本轮补查尚未查完整，部分结果受查询限制；请先恢复查询，不据此认定工厂缺数据。" />
      <div class="trace-counts"><span>材料与人工可计算 <strong>{{ report.summary.ordersWithDirectCost }} / {{ report.summary.workOrders }}</strong></span><span>本次补齐资料 <strong>{{ report.baselineComparison?.newlyFieldsEligibleOriginallyBlocked ?? '未提供' }}</strong></span><span>本次新增待核对 <strong>{{ report.baselineComparison?.previouslyEligibleNowBlocked ?? '未提供' }}</strong></span></div>
      <details><summary>材料关联核对情况</summary><p class="trace-note">已核实 {{ report.summary.materialOriginLinks }} 条领退料与用料清单关联，其中 {{ report.summary.materialProcessLinks }} 条可定位投料工序。业务关联不是批次证明；不把上游成本再加到已承接价值的半成品领料金额上。</p></details>
      <div class="trace-filters"><el-input v-model="search" aria-label="搜索补查工单或负责人" placeholder="搜索工单、产品或负责人" clearable /><el-select v-model="filter" aria-label="成本清单筛选"><el-option value="all" label="全部原工单" /><el-option value="blocked" label="仍不能算单位成本" /><el-option value="new" label="本次新增字段具备" /><el-option value="regressed" label="本次新增阻断" /></el-select></div>
      <CostDataTable :rows="filtered" :columns="[{key:'workOrderCode',label:'工单',type:'text',minWidth:180},{key:'product',label:'产品',type:'text',minWidth:180},{key:'identifiedUnitCost',label:'单位直接成本',type:'number',minWidth:140},{key:'_status',label:'当前状态',type:'select',value:row=>row.directCostAvailable?'可算，批次待核':'资料待补',minWidth:170},{key:'col4',label:'核对资料',type:'text',width:130,sortable:false,filterable:false}]" paginate   stripe><template #identifiedUnitCost="{row}">{{ amount(row.identifiedUnitCost) }}<span v-if="row.identifiedUnitCost != null"> / {{ row.unit }}</span></template><template #_status="{row}">{{ row.directCostAvailable ? '字段具备，批次仍待核' : '单位成本仍受阻' }}</template><template #col4="{row}"><UButton color="neutral" variant="outline" @click="selected = row">查看待补资料</UButton></template></CostDataTable>

      <el-drawer :model-value="!!selected" title="工单待补资料" size="min(900px,100%)" @close="selected=null"><section v-if="selected" class="trace-detail" aria-label="逐工单补资料详情">
        <h4>{{ selected.workOrderCode }} · {{ selected.product }}</h4>
        <p>这张单据已有 {{ selected.businessLinks.length }} 条领退料与用料清单关联。单位直接成本{{ selected.directCostAvailable ? '字段具备' : '仍受阻' }}，原料到成品的实际批次也尚未证明贯通。</p>
        <ul v-if="selected.providedClues.length"><li v-for="clue in selected.providedClues" :key="clue.label">已有 {{ clue.label }}：{{ clueValue(clue.value) }}。单位、实际/计划属性和适用范围待确认，不替代库存实际计价。</li></ul>
        <article v-for="need in selected.needs" :key="need.id" class="trace-request">
          <span class="trace-state">{{ status(need.sourceStatus) }}</span><p><strong>缺什么：</strong>{{ need.missingData }}</p><p><strong>找谁：</strong>{{ need.responsibleRole }}</p><p><strong>补齐后：</strong>{{ need.afterProvided }}</p><p v-if="need.sourceDocuments?.length" class="trace-note">核对单据：{{ need.sourceDocuments.join('、') }}</p>
        </article>
        <details v-if="selected.businessLinks.length"><summary>查看已经核实的业务关联</summary><p v-for="(link, index) in selected.businessLinks" :key="index">{{ link.sourceDocumentCode || '来源单据待核' }} · {{ link.materialName || '物料名称待核' }}：已核对工单用料行；{{ link.feedProcessName ? `投料工序为${link.feedProcessName}` : '投料工序尚未核实' }}。实际批次仍待核。</p></details>
      </section></el-drawer>
    </template>
  </UCard>
</template>

<style scoped>
.trace-card{margin-bottom:20px}.trace-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}h3{margin:0;font-size:17px}h4{font-size:15px;margin:0 0 12px}p,li{line-height:1.8}.trace-note,.trace-state{color:var(--ui-text-muted,#7a8599);font-size:12px}.trace-counts{display:flex;flex-wrap:wrap;gap:12px 28px;padding:12px 0}.trace-counts strong{font-size:20px;margin-left:8px}.trace-filters{display:flex;gap:12px;margin:16px 0}.trace-filters .el-input{max-width:360px}.trace-filters .el-select{width:240px}.trace-pages{margin-top:16px}.trace-detail{margin-top:20px;border-top:1px solid var(--ui-border,#edf2f7);padding-top:20px}.trace-request{padding:12px 0;border-bottom:1px solid var(--ui-border,#edf2f7)}.trace-request p{margin:6px 0}@media(max-width:700px){.trace-heading,.trace-filters{flex-direction:column}.trace-filters .el-input,.trace-filters .el-select{width:100%;max-width:none}}
</style>
