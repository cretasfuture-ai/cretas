<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { getDatasetAlerts, reviewDatasetAlerts, type DatasetAlertReport, type DataAlert } from '@/api/smartbi/data-alerts';
const props = withDefaults(defineProps<{ uploadId?: number; report?: DatasetAlertReport; active?: boolean; canReview?: boolean }>(), { active: true, canReview: false });
const auth = useAuthStore();
const current = ref<DatasetAlertReport | null>(null); const loading = ref(false); const reviewing = ref(false); const error = ref('');
const family = ref(''); const search = ref(''); const page = ref(1); let generation = 0;
const labels: Record<string, string> = { data_quality: '数据质量', business_signal: '经营核对信号', readiness: '分析准备条件' };
const findings = computed(() => (current.value?.items || []).filter(a => (!family.value || a.family === family.value) &&
  `${a.title} ${a.message} ${a.fields.join(' ')} ${a.entityRefs?.map(p => p.projectCode).join(' ') || ''}`.includes(search.value)));
const rows = computed(() => findings.value.slice((page.value - 1) * 10, page.value * 10));
const location = (a: DataAlert) => a.sourceRefs?.length ? `记录序号 ${a.sourceRefs.slice(0, 8).map(r => r.rowNumber).join('、')}${a.sourceRefs.length > 8 ? '等' : ''}` : a.entityRefs?.map(p => p.projectCode).join('、') || '数据集级核对';
const advice = (a: DataAlert) => current.value?.ai.reviews?.find(r => r.alertId === a.id);
const matchesFactory = (r: DatasetAlertReport) => typeof r.scope !== 'object' || r.scope.factoryId === auth.factoryId;
async function load(ai = false) {
  if (!props.active || !props.uploadId) return;
  const stamp = ++generation; const id = props.uploadId;
  loading.value = !ai; reviewing.value = ai; error.value = '';
  try {
    const result = await (ai ? reviewDatasetAlerts(id) : getDatasetAlerts(id));
    if (stamp === generation && id === props.uploadId) {
      if (!matchesFactory(result)) throw new Error('报告工厂与当前会话不一致，请重新登录');
      current.value = result;
    }
  } catch (e) { if (stamp === generation) error.value = e instanceof Error ? e.message : '异常检查未完成'; }
  finally { if (stamp === generation) { loading.value = false; reviewing.value = false; } }
}
watch(() => [props.uploadId, props.report, props.active, auth.factoryId, auth.user?.id, auth.currentRole] as const, (now, previous) => {
  generation++; current.value = null; error.value = ''; page.value = 1; loading.value = false; reviewing.value = false;
  const identityChanged = previous && now.slice(3).some((v, i) => v !== previous[i + 3]);
  if (props.report && matchesFactory(props.report) && (!identityChanged || props.report !== previous?.[1])) current.value = props.report;
  else if (props.report) error.value = '会话或数据来源已变化，请重新分析';
  if (props.active && props.uploadId && !props.report) void load();
}, { immediate: true });
</script>
<template>
  <el-card class="data-alert-panel" shadow="never" data-testid="shared-data-alerts" v-loading="loading">
    <div class="heading"><div><h3>数据异常与核对提醒</h3><p>Excel、接口和已保存数据使用同一套规则；缺失、零值和不可适用分别说明。</p></div><div class="actions"><el-button v-if="uploadId" @click="load()" :disabled="loading || reviewing">刷新规则</el-button><el-button v-if="uploadId && canReview" type="primary" @click="load(true)" :loading="reviewing" :disabled="loading">{{ reviewing ? '本地 AI 核对中' : '本地 AI 核对' }}</el-button></div></div>
    <el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <template v-if="current">
      <p class="summary">已检查 {{ current.summary.scannedRows ?? current.summary.projects ?? 0 }} 条记录，可展示 {{ current.summary.alerts }} 条提醒（可重叠）。</p>
      <el-alert v-if="current.summary.truncated" type="warning" :closable="false" title="本次只检查了部分行或列，不能代表全量数据；请查看扫描范围提醒。" />
      <p v-if="current.capabilities?.cost.reason" class="note" data-testid="cost-applicability" :data-cost-status="current.capabilities.cost.status">成本分析：{{ current.capabilities.cost.reason }}</p>
      <p class="ai-state" data-testid="shared-local-ai-state">{{ current.ai.status === 'complete' ? `本地 AI · ${current.ai.host} / ${current.ai.model}` : '规则分析' }}：{{ current.ai.message || '规则已完成；本地 AI 可按需运行' }}</p>
      <details v-for="section in current.ai.analysisSections || []" :key="section.sectionId" class="details" data-testid="shared-business-interpretation"><summary>{{ section.title }} · 本地模型解读</summary><p v-for="fact in section.statistics" :key="fact.id">统计依据：{{ fact.text }}</p><p>怎么看：{{ section.whatItMeans }}</p><p>为什么值得关注：{{ section.whyItMatters }}</p><p v-for="reason in section.hypotheses" :key="reason">{{ reason }}</p><p>下一步：{{ section.nextStep }}（{{ section.owner }}）</p><p>暂不能判断：{{ section.cannotConclude }}</p><p>可以继续问：{{ section.followupQuestion }}</p></details>
      <div class="filters"><el-select v-model="family" placeholder="全部类别" aria-label="通用异常类别" @change="page = 1"><el-option label="全部类别" value="" /><el-option v-for="(name, key) in labels" :key="key" :label="name" :value="key" /></el-select><el-input v-model="search" placeholder="搜索字段或问题" clearable @input="page = 1" /></div>
      <el-table :data="rows" stripe row-key="id" data-testid="shared-alert-table">
        <el-table-column type="expand"><template #default="{ row }: { row: DataAlert }"><div class="details"><p>影响字段：{{ row.fields.join('、') || '当前角色不展示字段明细' }}</p><p v-for="(value, key) in row.evidence" :key="key">{{ key }}：{{ typeof value === 'object' ? JSON.stringify(value) : value }}</p><p>报告版本：{{ current.runId }}；源记录序号对应保存的数据顺序。</p></div></template></el-table-column>
        <el-table-column label="类别 / 位置" min-width="180"><template #default="{ row }"><el-tag :type="row.family === 'readiness' ? 'info' : row.severity === 'blocking' ? 'danger' : 'warning'">{{ labels[row.family] || row.family }}</el-tag><p>{{ location(row) }}</p></template></el-table-column>
        <el-table-column label="发现的问题" min-width="320"><template #default="{ row }"><strong>{{ row.title }}</strong><p>{{ row.message }}</p></template></el-table-column>
        <el-table-column label="下一步 / 责任角色" min-width="260"><template #default="{ row }"><p>{{ row.action }}</p><small>{{ row.owner }}</small><p v-if="advice(row)" class="ai-advice">本地 AI：{{ advice(row)?.suggestion }}（{{ advice(row)?.priority === 'high' ? '优先核对' : '常规核对' }}，待人工确认）</p></template></el-table-column>
      </el-table><el-pagination v-model:current-page="page" :total="findings.length" :page-size="10" layout="prev, pager, next, total" />
      <el-empty v-if="!findings.length" :description="current.analysisStatus === 'restricted' ? '授权未确认，暂不展示分析结论' : '本次扫描未发现匹配的提醒；不代表数据已通过完整业务验收'" />
    </template><el-empty v-else-if="!loading && !error" description="数据保存完成后可检查异常" />
  </el-card>
</template>
<style scoped>
.data-alert-panel{margin:16px 0;border-radius:10px;min-width:0}.heading,.actions,.filters{display:flex;gap:12px;align-items:center;justify-content:space-between}.heading{align-items:flex-start;flex-wrap:wrap}h3{margin:0 0 8px;font-size:17px}p{line-height:1.8}.filters{margin:16px 0}.filters .el-select{width:220px}.filters .el-input{max-width:360px}.details{padding:16px;overflow-wrap:anywhere}.note,small{color:#596579}.ai-state{padding:12px;background:var(--el-fill-color-light)}.ai-advice{color:var(--el-color-primary)}.el-pagination{margin-top:16px;overflow-x:auto}@media(max-width:700px){.filters,.actions{flex-wrap:wrap}.filters .el-select,.filters .el-input{width:100%;max-width:none}}
</style>
