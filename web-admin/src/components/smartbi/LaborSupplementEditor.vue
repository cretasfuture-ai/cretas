<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue';
import {getLaborSupplements,saveLaborSupplements,type LaborSupplementStatus} from '@/api/smartbi/laborSupplement';
import {downloadLaborTemplate,importLaborRows,type LaborTaskEntry,type LaborTaskStatus} from '@/utils/laborSupplementImport';
const props=defineProps<{orderIds:number[];sourceRunId?:string}>();
const emit=defineEmits<{updated:[value:LaborSupplementStatus]}>();
const state=ref<LaborSupplementStatus|null>(null),error=ref(''),success=ref(''),busy=ref(false),open=ref(false),tab=ref('manual');
const taskId=ref<number>(),reference=ref(''),confirmed=ref(false),zero=ref(false);
const segments=ref([{start:'',end:'',people:'',breakMinutes:0,shutdownMinutes:0}]);
const imported=ref<LaborTaskEntry[]>([]),importErrors=ref<string[]>([]),fileInput=ref<HTMLInputElement>();
const editRevision=ref(0),editSource=ref(''),importSource=ref('');
let generation=0,timer:ReturnType<typeof setTimeout>|undefined;
const ready=computed(()=>state.value?.tasks.filter(t=>t.status==='ready').length||0);
const selected=computed(()=>state.value?.tasks.find(t=>t.taskId===taskId.value));
const fmt=(n:number|null)=>n===null?'待补':n.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
async function load(current=generation){try{const value=await getLaborSupplements(props.orderIds);if(current!==generation)return;state.value=value;emit('updated',value);error.value='';}
  catch(e){if(current===generation)error.value=e instanceof Error?e.message:'补充资料暂未连接，稍后自动重试';}
  finally{if(current===generation)timer=setTimeout(()=>void load(current),15000);}}
watch(()=>props.orderIds.join(','),()=>{generation++;if(timer)clearTimeout(timer);state.value=null;open.value=false;void load();},{immediate:true});
watch(()=>props.sourceRunId,()=>{generation++;if(timer)clearTimeout(timer);void load();});
watch(open,value=>{if(value){taskId.value=undefined;imported.value=[];importErrors.value=[];error.value='';}});
onBeforeUnmount(()=>{generation++;if(timer)clearTimeout(timer);});
watch(taskId,()=>{editRevision.value=selected.value?.revision??0;editSource.value=state.value?.sourceRunId??'';reference.value='';confirmed.value=false;zero.value=false;segments.value=[{start:'',end:'',people:'',breakMinutes:0,shutdownMinutes:0}];});
async function save(entries:LaborTaskEntry[],sourceRunId=state.value?.sourceRunId){if(!state.value?.editable||busy.value||!sourceRunId)return;busy.value=true;error.value='';success.value='';let committed=false;try{
  await saveLaborSupplements(sourceRunId,entries);committed=true;const value=await getLaborSupplements(props.orderIds);state.value=value;emit('updated',value);success.value='已保存，人工指标和异常提醒已重算；文字分析会自动排队更新。';imported.value=[];open.value=false;
}catch(e){if(committed){success.value='资料已保存，汇总会自动重新读取，请勿重复提交。';open.value=false;imported.value=[];}else error.value=e instanceof Error?e.message:'保存未完成，请核对后重试';}finally{busy.value=false;}}
function submit(){const task=selected.value;if(!task||!confirmed.value){error.value='请选择工序，并确认这些是实际参与人员和作业记录';return;}
  const rows=zero.value?[]:segments.value.map(s=>({start:s.start?`${s.start}:00+08:00`:'',end:s.end?`${s.end}:00+08:00`:'',workers:s.people.split(/[,，;；\s]+/).filter(Boolean),breakMinutes:s.breakMinutes,shutdownMinutes:s.shutdownMinutes}));
  if(!reference.value.trim()||!zero.value&&rows.some(s=>!s.start||!s.end||!s.workers.length)){error.value='请补齐时段、人员代码和核对依据';return;}
  void save([{projectId:task.projectId,taskId:task.taskId,segments:rows,confirmed:true,expectedRevision:editRevision.value,source:'manual',reference:reference.value.trim(),zeroConfirmed:zero.value}],editSource.value);}
async function fileSelected(event:Event){const file=(event.target as HTMLInputElement).files?.[0];imported.value=[];importErrors.value=[];if(!file||!state.value)return;
  try{const result=importLaborRows(await file.arrayBuffer(),state.value.tasks);importSource.value=state.value.sourceRunId;imported.value=result.entries;importErrors.value=result.errors;}catch(e){importErrors.value=[e instanceof Error?e.message:'文件无法读取'];}
  (event.target as HTMLInputElement).value='';}
const sources=[
  {name:'工序人数与作业时段',where:'Cretas 生产报工、结单',status:'已有采集入口',note:'取已审核的实际记录；预计人数、计划工时不代替实际。'},
  {name:'批次参与人员',where:'Cretas 批次人员签到 / 签出',status:'已有采集入口',note:'可核对批次名单；还需确定每人参与的工序和时段。'},
  {name:'上班、下班及休息',where:'Cretas 考勤',status:'已有采集入口',note:'用于辅助核对，整天出勤不能直接摊给某张工单。'},
  {name:'每段具体人员与停机扣除',where:'本页补充 / Excel',status:'现有记录不完整',note:'原生报工多段记录以时段和人数为主，未完整保留逐人名单、工序停机。'},
  {name:'黑湖工单 ↔ Cretas 批次 / 任务',where:'工单、批次和任务编号核对',status:'尚未绑定',note:'当前隔离租户未绑定原生业务工厂，不能按姓名或日期自动拼接。可先按模板核对导入。'},
];
function taskLabel(t:LaborTaskStatus){return `${t.projectCode} · ${t.process} · 任务 ${t.taskId}`;}
</script>
<template>
  <section class="supplement" aria-label="人工资料补充">
    <div class="bar"><div><b>人工资料补充</b><p>{{ready}} / {{state?.tasks.length??'—'}} 道工序已补齐<span v-if="state?.tasks.some(t=>t.status==='stale')"> · 有来源数据变化，请重新核对</span></p></div><el-button type="primary" :disabled="!state" @click="open=true">补充 / 导入资料</el-button></div>
    <p v-if="!state?.editable&&state" class="muted">公开页只展示汇总结果；请在本机管理入口补充人员资料。</p>
    <p v-if="error" role="alert" class="error">{{error}}</p><p v-if="success" role="status" class="ok">{{success}}</p>
    <el-dialog v-model="open" title="补充实际人员与工时" width="min(1000px, 94vw)" :close-on-click-modal="false" destroy-on-close>
      <p class="muted">先选工单和工序。补充只用于分析，不改黑湖工资；改动已有资料时需重新填写完整记录。</p>
      <p v-if="error" role="alert" class="error">{{error}}</p>
      <el-tabs v-model="tab">
        <el-tab-pane label="逐项填写" name="manual">
          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="工单与工序"><el-select v-model="taskId" filterable placeholder="搜索工单号或工序" aria-label="补充工单与工序" style="width:100%"><el-option v-for="t in state?.tasks||[]" :key="t.taskId" :value="t.taskId" :label="taskLabel(t)"/></el-select></el-form-item>
            <p v-if="selected" class="muted">已记人工费 {{fmt(selected.laborCost)}} · {{selected.status==='ready'?'已补齐，可重新提交更正':selected.status==='stale'?'源数据已变化，需重新确认':'待补资料'}}<span v-if="selected.updatedAt"> · 上次补充 {{selected.updatedAt.slice(5,16).replace('T',' ')}}</span></p>
            <el-checkbox v-model="zero">确认本任务不记录人时</el-checkbox>
            <div v-if="!zero" class="segments"><article v-for="(s,i) in segments" :key="i" class="segment"><div class="segment-title"><b>时段 {{i+1}}</b><el-button v-if="segments.length>1" text @click="segments.splice(i,1)">移除此段</el-button></div>
              <div class="form-grid"><el-form-item label="开始时间（北京时间）"><input v-model="s.start" type="datetime-local" :aria-label="`时段${i+1}开始时间`"/></el-form-item><el-form-item label="结束时间（北京时间）"><input v-model="s.end" type="datetime-local" :aria-label="`时段${i+1}结束时间`"/></el-form-item>
              <el-form-item label="参与人员代码"><el-input v-model="s.people" :aria-label="`时段${i+1}参与人员代码`" placeholder="如 E001,E002；同一人始终用同一代码"/></el-form-item><el-form-item label="休息 / 停机（分钟）"><div class="deductions"><el-input-number v-model="s.breakMinutes" :min="0" :max="1440" aria-label="休息分钟"/><el-input-number v-model="s.shutdownMinutes" :min="0" :max="1440" aria-label="停机分钟"/></div></el-form-item></div>
            </article><el-button @click="segments.push({start:'',end:'',people:'',breakMinutes:0,shutdownMinutes:0})">增加作业时段</el-button></div>
            <p v-else class="muted">零人时不会变成人员名单，真实人均仍需人员依据；已有工资也不会清零。</p>
            <el-form-item label="核对依据"><el-input v-model="reference" maxlength="120" aria-label="人工补充核对依据" placeholder="如班组作业记录或报工单编号，不填个人联系方式"/></el-form-item>
            <el-checkbox v-model="confirmed">已按班组记录核对，填写的是实际参与情况</el-checkbox>
            <div class="actions"><el-button type="primary" :disabled="!state?.editable||!confirmed||!selected" :loading="busy" @click="submit">保存并重算</el-button></div>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="Excel 导入" name="excel">
          <p>下载所选产品的工单工序模板，每行填一个作业时段。Cretas 已有记录也可整理为同一模板。</p>
          <div class="actions"><el-button @click="downloadLaborTemplate(state?.tasks||[])">下载对应工单模板</el-button><el-button @click="fileInput?.click()">选择 Excel 文件</el-button><input ref="fileInput" type="file" accept=".xlsx,.xls" hidden @change="fileSelected"/></div>
          <p v-if="importErrors.length" class="error" role="alert">共 {{importErrors.length}} 处需修改，当前未保存任何数据。</p><ul v-if="importErrors.length" class="import-errors"><li v-for="e in importErrors.slice(0,30)" :key="e">{{e}}</li></ul>
          <template v-if="imported.length"><p>待确认：{{imported.length}} 道工序，{{imported.reduce((sum,r)=>sum+r.segments.length,0)}} 个时段。</p><el-table :data="imported" max-height="280"><el-table-column label="工单 / 工序"><template #default="{row}">{{state?.tasks.find(t=>t.taskId===row.taskId)?.projectCode}} / {{state?.tasks.find(t=>t.taskId===row.taskId)?.process}}</template></el-table-column><el-table-column label="时段数"><template #default="{row}">{{row.segments.length}}</template></el-table-column><el-table-column prop="reference" label="核对依据"/></el-table><el-button type="primary" :disabled="!state?.editable||!!importErrors.length" :loading="busy" @click="save(imported,importSource)">确认整批保存并重算</el-button></template>
        </el-tab-pane>
        <el-tab-pane label="系统已有与待采集" name="sources"><el-table :data="sources"><el-table-column prop="name" label="资料" min-width="150"/><el-table-column prop="where" label="来源" min-width="150"/><el-table-column prop="status" label="现状" min-width="120"/><el-table-column prop="note" label="如何使用 / 还缺什么" min-width="250"/></el-table></el-tab-pane>
      </el-tabs>
      <p v-if="!state?.editable" class="muted">此链接为公开查看页，保存入口仅向本机管理员开放。</p>
    </el-dialog>
  </section>
</template>
<style scoped>
.supplement{margin:20px 0;padding:16px;border:1px solid #edf2f7;border-radius:10px;background:#f7f9fb}.bar,.segment-title,.actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.bar p,.muted{font-size:12px;line-height:1.7;color:#7a8599}.bar p{margin:6px 0 0}.error{color:#b34b3d;font-size:13px;line-height:1.7}.ok{color:#267655;font-size:13px}.segment{border:1px solid #edf2f7;padding:16px;border-radius:8px;margin:12px 0}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}.form-grid input{height:32px;border:1px solid #dcdfe6;border-radius:4px;padding:0 8px;width:100%;box-sizing:border-box}.deductions{display:flex;gap:8px}.actions{justify-content:flex-start;margin:16px 0}.import-errors{max-height:180px;overflow:auto;font-size:13px;color:#b34b3d}@media(max-width:700px){.form-grid{grid-template-columns:1fr}.bar{align-items:flex-start}.deductions{flex-wrap:wrap}}
</style>
