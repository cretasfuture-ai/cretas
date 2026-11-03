<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue';
import type { CostAlertReport, CostDataAlert, EligibleCostReview } from '@/api/smartbi/blacklake';
const props = defineProps<{ alerts: CostAlertReport; review: EligibleCostReview }>();
const family = ref(''); const search = ref(''); const page = ref(1); const selectedUnit = ref('KG');
const families: Record<string, string> = { data_quality: '数据质量', business_signal: '成本差异信号', readiness: '核算准备条件' };
const units = computed(() => [...new Set(props.review.projects.map(p => p.unit).filter((u): u is string => typeof u === 'string' && !!u))]);
watchEffect(() => { if (!units.value.includes(selectedUnit.value)) selectedUnit.value = units.value[0] || ''; });
const ranked = computed(() => props.review.rankings.filter(p => p.unit === selectedUnit.value));
const filtered = computed(() => props.alerts.items.filter(a => (!family.value || a.family === family.value) && `${a.title} ${a.message} ${a.entityRefs.map(p => p.projectCode).join(' ')}`.includes(search.value)));
const visible = computed(() => filtered.value.slice((page.value - 1) * 15, page.value * 15));
const fmt = (n: number | null | undefined) => n == null ? '未提供' : n.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
const aiAdvice = (a: CostDataAlert) => props.alerts.ai.reviews?.find(r => r.alertId === a.id);
const unitRows = computed(() => props.review.projects.filter(p => p.unit === selectedUnit.value).sort((a, b) => (a.productCode ?? '').localeCompare(b.productCode ?? '') || a.projectCode.localeCompare(b.projectCode)));
</script>
<template>
  <section class="cost-alerts" data-testid="cost-data-alerts">
    <el-card shadow="never">
      <h3>成本与数据异常提醒</h3><p>缺价、缺产量和缺关联本身就是分析发现。规则独立运行，AI 按同一证据给出核对建议。</p>
      <div class="metrics"><div><small>字段可核算 / 全部工单</small><strong>{{ review.eligibleProjects }} / {{ alerts.summary.projects }}</strong></div><div><small>暂不能核算</small><strong>{{ alerts.summary.costBlocked }} 张</strong></div><div><small>成本差异信号</small><strong>{{ alerts.summary.byFamily.business_signal || 0 }} 条</strong></div></div>
      <el-alert type="warning" :closable="false" title="字段能计算不等于实际成本已归集完整。提醒可重叠；未完工属于准备条件，零值与缺失分开核对。" />
      <p class="ai-state" data-testid="local-ai-state">{{ alerts.ai.status === 'complete' ? `本地 AI · ${alerts.ai.host} / ${alerts.ai.model}` : '规则分析' }}：{{ alerts.ai.message }}</p>
      <div class="filters"><el-select v-model="family" aria-label="异常类别" @change="page = 1"><el-option label="全部类别" value="" /><el-option v-for="(label, key) in families" :key="key" :label="label" :value="key" /></el-select><el-input v-model="search" placeholder="搜索问题或工单编码" clearable @input="page = 1" /></div>
      <el-table :data="visible" stripe row-key="id" data-testid="cost-alert-table">
        <el-table-column type="expand"><template #default="{ row }: { row: CostDataAlert }"><div class="details"><p><b>涉及工单：</b>{{ row.entityRefs.map(p => p.projectCode).join('、') || '整个数据集' }}</p><p><b>影响字段：</b>{{ row.fields.join('、') }}</p><p><b>证据：</b><span v-for="(value, key) in row.evidence" :key="key">{{ key }}：{{ typeof value === 'object' ? JSON.stringify(value) : value }}； </span></p><p>来源归档 {{ row.runId }} · 规则 {{ row.ruleVersion }} · 待核实</p></div></template></el-table-column>
        <el-table-column label="类别 / 影响" min-width="150"><template #default="{ row }"><el-tag :type="row.blocksUnitCost ? 'danger' : 'warning'">{{ families[row.family] }}</el-tag><p>{{ row.blocksUnitCost ? '阻断单位成本' : '需要核对，未证实原因' }}</p></template></el-table-column>
        <el-table-column label="发现的问题" min-width="330"><template #default="{ row }"><b>{{ row.title }}</b><p>{{ row.message }}</p></template></el-table-column>
        <el-table-column label="核对动作 / 责任角色" min-width="300"><template #default="{ row }"><p>{{ row.action }}</p><small>{{ row.owner }}</small><p v-if="aiAdvice(row)" class="ai-advice">本地 AI 建议：{{ aiAdvice(row)?.suggestion }}（{{ aiAdvice(row)?.priority === 'high' ? '优先核对' : '常规核对' }}，待人工确认）</p></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="15" :total="filtered.length" layout="prev, pager, next, total" /><el-empty v-if="!filtered.length" description="当前筛选下没有提醒" />
    </el-card>
    <el-card shadow="never" class="section">
      <div class="heading"><h3>{{ review.eligibleProjects }} 张工单 · 原料到成品成本复核</h3><el-select v-model="selectedUnit" aria-label="成本复核成品单位"><el-option v-for="u in units" :key="u" :label="u" :value="u" /></el-select></div>
      <p>按成品单位分别查看。金额为来源币种；成本高低不等于经营异常。同产品也需核对规格和工艺阶段。</p>
      <el-table :data="ranked" stripe data-testid="eligible-product-ranking"><el-table-column prop="product" label="产品" min-width="210" /><el-table-column prop="projects" label="可核算工单" /><el-table-column label="加权单位直接成本"><template #default="{ row }">{{ fmt(row.weightedUnitCost) }} / {{ row.unit }}</template></el-table-column><el-table-column label="工单最低 / 最高"><template #default="{ row }">{{ fmt(row.minimum) }} / {{ fmt(row.maximum) }}</template></el-table-column></el-table>
      <h4>逐单材料、人工与成品数量</h4><el-table :data="unitRows" stripe data-testid="eligible-cost-review">
        <el-table-column type="expand"><template #default="{ row }"><div class="details"><h4>{{ row.projectCode }} · 材料贡献明细</h4><el-table :data="row.components"><el-table-column prop="material" label="材料/半成品" min-width="180" /><el-table-column prop="orderCode" label="领退料单" min-width="180" /><el-table-column label="单位成品耗用"><template #default="{ row: line }">{{ fmt(line.usagePerFinished) }} {{ line.unit }}/{{ row.unit }}</template></el-table-column><el-table-column label="单位成品成本贡献"><template #default="{ row: line }">{{ fmt(line.costPerFinished) }}</template></el-table-column></el-table><p>如来源领退料含半成品，仅采用该来源估值，不另叠加上游工单成本。批次链路完整性仍待核实。</p></div></template></el-table-column>
        <el-table-column prop="projectCode" label="工单编码" min-width="190" /><el-table-column prop="product" label="产品" min-width="180" /><el-table-column label="成品良品量"><template #default="{ row }">{{ fmt(row.finishedGood) }} {{ row.unit }}</template></el-table-column><el-table-column label="单位材料"><template #default="{ row }">{{ fmt(row.materialPerFinished) }}</template></el-table-column><el-table-column label="单位人工"><template #default="{ row }">{{ fmt(row.laborPerFinished) }}</template></el-table-column><el-table-column label="单位直接成本"><template #default="{ row }">{{ fmt(row.unitIdentifiedDirectCost) }}</template></el-table-column>
      </el-table><p v-for="text in review.limitations" :key="text" class="note">{{ text }}</p>
    </el-card>
  </section>
</template>
<style scoped>
h3{font-size:17px;margin:0 0 12px}p{line-height:1.8}.el-card{border-radius:10px}.section{margin-top:20px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:20px 0}.metrics div{padding:16px;background:#f4f6f9;border-radius:10px}.metrics strong,.metrics small{display:block}.metrics strong{font-size:24px;margin-top:8px}.note,small{color:#596579}.filters,.heading{display:flex;gap:16px;align-items:center;justify-content:space-between;margin:20px 0}.filters .el-select,.heading .el-select{width:240px}.filters .el-input{max-width:400px}.details{padding:16px;overflow-wrap:anywhere}.ai-advice{color:#1B65A8}.el-pagination{margin-top:16px;overflow-x:auto}.ai-state{background:#f4f6f9;padding:12px}@media(max-width:700px){.metrics{grid-template-columns:1fr}.filters,.heading{flex-wrap:wrap}.filters .el-input,.filters .el-select{width:100%;max-width:none}}
</style>
