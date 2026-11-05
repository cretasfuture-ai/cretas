<script setup lang="ts">
const publicPreview = import.meta.env.MODE === 'blacklake-public';
import CostDataTable from '@/components/smartbi/CostDataTable.vue';
import { computed, ref, watch, watchEffect } from 'vue';
import UButton from '@nuxt/ui/components/Button.vue';
import UBadge from '@nuxt/ui/components/Badge.vue';
import { useAuthStore } from '@/store/modules/auth';
import { saveBlacklakeAlertFollowup, type AlertFollowup, type BlacklakePeriodQuery, type CostAlertReport, type CostDataAlert, type EligibleCostReview } from '@/api/smartbi/blacklake';
import { formatBlacklakeNumber, localizeBlacklakeField, localizeBlacklakeText, readableEvidenceEntries } from './blacklakePresentation';
const props = defineProps<{ alerts: CostAlertReport; review: EligibleCostReview; followups?: Record<string,AlertFollowup>; period?: BlacklakePeriodQuery | null }>();
const emit=defineEmits<{ask:[alert:CostDataAlert];updated:[followup:AlertFollowup];'locate-order':[projectId:number]}>();
const auth=useAuthStore();
const progress=ref<Record<string,AlertFollowup>>({});
const statusFilter=ref('');
const editing=ref<CostDataAlert|null>(null),note=ref(''),saveError=ref(''),saving=ref(false);
const status=ref<AlertFollowup['status']>('open');
const statusNames={open:'未处理',awaiting_data:'待补资料',verified:'已核对'};
let generation=0;
watch(()=>[props.followups,props.alerts,auth.factoryId,auth.user?.id,auth.currentRole],()=>{
  generation++;progress.value={...props.followups};editing.value=null;saveError.value='';saving.value=false;
},{immediate:true});
function edit(alert:CostDataAlert){editing.value=alert;note.value=progress.value[alert.id]?.note||'';status.value=progress.value[alert.id]?.status||'open';saveError.value='';}
async function save(){
  const alert=editing.value;if(!alert||saving.value)return;
  if(!note.value.trim()){saveError.value='请填写本次跟进说明';return;}
  const stamp=generation;saving.value=true;saveError.value='';
  try{
    const result=await saveBlacklakeAlertFollowup(alert.id,{run_id:alert.runId,status:status.value,note:note.value,period:props.period || undefined,
      expected_revision:progress.value[alert.id]?.revision||0});
    if(stamp!==generation)return;
    progress.value={...progress.value,[alert.id]:result};editing.value=null;emit('updated',result);
  }catch(e){if(stamp===generation)saveError.value=e instanceof Error?e.message:'跟进状态未能保存';}
  finally{if(stamp===generation)saving.value=false;}
}
const family = ref(''); const search = ref(''); const page = ref(1); const selectedUnit = ref('KG');
const families: Record<string, string> = { data_quality: '数据质量', business_signal: '成本差异信号', readiness: '核算准备条件' };
const units = computed(() => [...new Set(props.review.projects.map(p => p.unit).filter((u): u is string => typeof u === 'string' && !!u))]);
watchEffect(() => { if (!units.value.includes(selectedUnit.value)) selectedUnit.value = units.value[0] || ''; });
const ranked = computed(() => props.review.rankings.filter(p => p.unit === selectedUnit.value));
const filtered = computed(() => props.alerts.items.filter(a => (!family.value || a.family === family.value) &&
  (!statusFilter.value || (progress.value[a.id]?.status || 'open')===statusFilter.value) &&
  `${a.title} ${a.message} ${a.entityRefs.map(p => p.projectCode).join(' ')}`.includes(search.value)));
const visible = computed(() => filtered.value.slice((page.value - 1) * 15, page.value * 15));
const fmt = (n: number | null | undefined) => formatBlacklakeNumber(n);
const aiAdvice = (a: CostDataAlert) => props.alerts.ai.reviews?.find(r => r.alertId === a.id);
const evidenceEntries = (a: CostDataAlert) => readableEvidenceEntries(a.evidence);
const unitRows = computed(() => props.review.projects.filter(p => p.unit === selectedUnit.value).sort((a, b) => (a.productCode ?? '').localeCompare(b.productCode ?? '') || a.projectCode.localeCompare(b.projectCode)));
</script>
<template>
  <section class="cost-alerts" data-testid="cost-data-alerts">
    <el-card shadow="never">
      <h3>成本与数据异常提醒</h3><p>先看哪些工单需要核对、找谁处理，再记录跟进进度。</p>
      <div class="metrics"><div><small>字段可核算 / 全部工单</small><strong>{{ review.eligibleProjects }} / {{ alerts.summary.projects }}</strong></div><div><small>暂不能核算</small><strong>{{ alerts.summary.costBlocked }} 张</strong></div><div><small>成本差异信号</small><strong>{{ alerts.summary.byFamily.business_signal || 0 }} 条</strong></div></div>
      <el-alert type="warning" :closable="false" title="字段能计算不等于实际成本已归集完整。提醒可重叠；未完工属于准备条件，零值与缺失分开核对。" />
      <details><summary>分析说明</summary><p class="ai-state" data-testid="local-ai-state">{{ alerts.ai.status === 'complete' ? `本地 AI · ${alerts.ai.host} / ${alerts.ai.model}` : '规则分析' }}：{{ localizeBlacklakeText(alerts.ai.message) }}</p></details>
      <div class="filters"><el-select v-model="family" aria-label="异常类别" @change="page = 1"><el-option label="全部类别" value="" /><el-option v-for="(label, key) in families" :key="key" :label="label" :value="key" /></el-select><el-select v-model="statusFilter" aria-label="跟进状态" @change="page=1"><el-option label="全部状态" value="" /><el-option v-for="(label,key) in statusNames" :key="key" :label="label" :value="key" /></el-select><el-input v-model="search" placeholder="搜索问题或工单编码" clearable @input="page = 1" /></div>
      <p class="note">跟进状态是 Cretas 的人工记录；标记“已核对”不会修改来源数据，也不会解除单位成本的阻断条件。</p>
      <CostDataTable :rows="filtered" :columns="[{key:'family',label:'类别 / 影响',type:'select',value:row=>families[row.family],minWidth:150},{key:'title',label:'发现的问题',type:'text',minWidth:330},{key:'owner',label:'核对动作 / 责任角色',type:'select',minWidth:300},{key:'_progress',label:'跟进',type:'select',value:row=>statusNames[progress[row.id]?.status||'open'],minWidth:210}]" paginate   stripe row-key="id" data-testid="cost-alert-table"><template #expand="{row}"><div class="details"><p><b>需要核对的工单：</b></p><div v-if="row.entityRefs.length" class="order-links"><UButton v-for="entity in row.entityRefs" :key="entity.projectId" size="sm" color="primary" variant="outline" type="button" @click="emit('locate-order', entity.projectId)">核对工单 {{ entity.projectCode }}</UButton></div><p v-else>这条提醒影响当前数据集，暂无单个工单可定位。</p><p><b>影响字段：</b>{{ row.fields.map(localizeBlacklakeField).join('、') || '来源系统未提供可读字段' }}</p><div v-if="evidenceEntries(row).length" class="evidence-list"><p v-for="entry in evidenceEntries(row)" :key="`${entry.label}-${entry.value}`"><b>{{ entry.label }}：</b>{{ entry.value }}</p></div><p v-else>暂无可读证据细节。</p><p class="details-note">这条提醒需要人工核对，不代表已确认原因。</p></div></template><template #family="{ row }"><el-tag :type="row.blocksUnitCost ? 'danger' : 'warning'">{{ families[row.family] || '待人工确认' }}</el-tag><p>{{ row.blocksUnitCost ? '会阻断单位成本展示' : '需要核对，原因尚未证实' }}</p></template><template #title="{ row }"><b>{{ localizeBlacklakeText(row.title) }}</b><p>{{ localizeBlacklakeText(row.message) }}</p></template><template #owner="{ row }"><p>{{ localizeBlacklakeText(row.action) }}</p><small>{{ localizeBlacklakeText(row.owner) }}</small><p v-if="aiAdvice(row)" class="ai-advice">本地 AI 建议：{{ localizeBlacklakeText(aiAdvice(row)?.suggestion) }}（{{ aiAdvice(row)?.priority === 'high' ? '优先核对' : '常规核对' }}，待人工确认）</p></template><template #_progress="{ row }"><div class="followup-actions" :data-alert-id="row.id"><UBadge :color="progress[row.id]?.status==='verified'?'success':progress[row.id]?.status==='awaiting_data'?'warning':'neutral'" variant="soft">{{ statusNames[progress[row.id]?.status || 'open'] }}</UBadge><small v-if="progress[row.id]">{{ progress[row.id].note }}</small><UButton size="sm" variant="soft" @click="emit('ask',row)">追问此异常</UButton><UButton size="sm" color="neutral" variant="ghost" :disabled="publicPreview" :title="publicPreview?'公开预览不保存跟进记录':undefined" @click="edit(row)">记录跟进</UButton></div></template></CostDataTable>
      <el-empty v-if="!filtered.length" description="当前筛选下没有提醒" />
    </el-card>
    <details class="section"><summary>展开可核算工单与产品明细</summary><el-card shadow="never">
      <div class="heading"><h3>{{ review.eligibleProjects }} 张工单 · 原料到成品成本复核</h3><el-select v-model="selectedUnit" aria-label="成本复核成品单位"><el-option v-for="u in units" :key="u" :label="u" :value="u" /></el-select></div>
      <p>按成品单位分别查看。金额为来源币种；成本高低不等于经营异常。同产品也需核对规格和工艺阶段。</p>
      <CostDataTable :rows="ranked" :columns="[{key:'product',label:'产品',type:'text',minWidth:210},{key:'projects',label:'可核算工单',type:'number'},{key:'weightedUnitCost',label:'加权单位直接成本',type:'number'},{key:'minimum',label:'工单最低 / 最高',type:'text'}]" paginate   stripe data-testid="eligible-product-ranking"><template #weightedUnitCost="{ row }">{{ fmt(row.weightedUnitCost) }} / {{ row.unit }}</template><template #minimum="{ row }">{{ fmt(row.minimum) }} / {{ fmt(row.maximum) }}</template></CostDataTable>
      <h4>逐单材料、人工与成品数量</h4><CostDataTable :rows="unitRows" :columns="[{key:'projectCode',label:'工单编码',type:'text',minWidth:190},{key:'product',label:'产品',type:'text',minWidth:180},{key:'finishedGood',label:'成品良品量',type:'number'},{key:'materialPerFinished',label:'单位材料',type:'number'},{key:'laborPerFinished',label:'单位人工',type:'number'},{key:'unitIdentifiedDirectCost',label:'单位直接成本',type:'number'}]" paginate   stripe data-testid="eligible-cost-review"><template #expand="{row}"><div class="details"><h4>{{ row.projectCode }} · 材料贡献明细</h4><CostDataTable :rows="row.components" :columns="[{key:'material',label:'材料/半成品',type:'text',minWidth:180},{key:'orderCode',label:'领退料单',type:'text',minWidth:180},{key:'usagePerFinished',label:'单位成品耗用',type:'number'},{key:'costPerFinished',label:'单位成品成本贡献',type:'number'}]" paginate  ><template #usagePerFinished="{ row: line }">{{ fmt(line.usagePerFinished) }} {{ line.unit }}/{{ row.unit }}</template><template #costPerFinished="{ row: line }">{{ fmt(line.costPerFinished) }}</template></CostDataTable><p>如来源领退料含半成品，仅采用该来源估值，不另叠加上游工单成本。批次链路完整性仍待核实。</p></div></template><template #finishedGood="{ row }">{{ fmt(row.finishedGood) }} {{ row.unit }}</template><template #materialPerFinished="{ row }">{{ fmt(row.materialPerFinished) }}</template><template #laborPerFinished="{ row }">{{ fmt(row.laborPerFinished) }}</template><template #unitIdentifiedDirectCost="{ row }">{{ fmt(row.unitIdentifiedDirectCost) }}</template></CostDataTable><p v-for="text in review.limitations" :key="text" class="note">{{ text }}</p>
    </el-card></details>
    <el-dialog :model-value="!!editing" title="记录异常跟进" width="min(92vw, 520px)" @close="editing=null" :close-on-click-modal="false">
      <template v-if="editing"><p>{{ localizeBlacklakeText(editing.title) }}</p><p class="note">责任角色：{{ localizeBlacklakeText(editing.owner) }}</p>
      <el-form label-position="top" @submit.prevent="save"><el-form-item label="跟进状态"><el-select v-model="status" aria-label="本次跟进状态"><el-option v-for="(label,key) in statusNames" :key="key" :label="label" :value="key" /></el-select></el-form-item>
      <el-form-item label="本次核对 / 待补资料说明"><el-input v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="例如：已联系仓库核实领退料关联，等待原单回执" /></el-form-item>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" /><p class="note">仅保存测试环境的跟进记录，不改成本金额和黑湖数据。</p>
      <UButton type="submit" :loading="saving" @click.prevent="save">保存跟进</UButton></el-form></template>
    </el-dialog>
  </section>
</template>
<style scoped>
h3{font-size:17px;margin:0 0 12px}p{line-height:1.8}.el-card{border-radius:10px}.section{margin-top:20px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin:20px 0}.metrics div{padding:16px;background:#f4f6f9;border-radius:10px}.metrics strong,.metrics small{display:block}.metrics strong{font-size:24px;margin-top:8px}.note,small{color:#596579}.filters,.heading{display:flex;gap:16px;align-items:center;justify-content:space-between;margin:20px 0}.filters .el-select,.heading .el-select{width:240px}.filters .el-input{max-width:400px}.details{padding:16px;overflow-wrap:anywhere}.order-links{display:flex;flex-wrap:wrap;gap:8px;margin:-4px 0 12px}.evidence-list{display:grid;gap:4px}.details-note{color:#596579;font-size:12px}.ai-advice{color:#1B65A8}.el-pagination{margin-top:16px;overflow-x:auto}.ai-state{background:#f4f6f9;padding:12px}@media(max-width:700px){.metrics{grid-template-columns:1fr}.filters,.heading{flex-wrap:wrap}.filters .el-input,.filters .el-select{width:100%;max-width:none}}
</style>
<style scoped>.followup-actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center}.followup-actions small{display:block;flex-basis:100%;overflow-wrap:anywhere}details>summary{padding:16px;border:1px solid var(--c-line,#e5eaf1);border-radius:10px;color:var(--c-primary,#1b65a8);cursor:pointer;background:white}.filters{flex-wrap:wrap}</style>
