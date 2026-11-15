<script setup lang="ts">
// E3 检验标准（按阶段）。标准值由人填写（Cretas 补充），不由 AI 生成；保存生成新版本，旧版本保留。
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {E,STAGE_ORDER,TYPE_LABEL,get,write,fmtTime,refText,range,stdVersion,routeQuery,setQuery,tag,refTag,logHTML,verifiedRef} from '../collab/e-store'
import type {Standard,StageKey,StdItem} from '../collab/e-store'
const st=computed(()=>E.state!),meta=computed(()=>st.value.meta)
const stage=ref<StageKey>((routeQuery().get('stage') as StageKey)||'incoming')
watch(stage,v=>setQuery({stage:v}))
const showOff=ref(false)
const rows=computed(()=>st.value.standards.filter(s=>s.stage===stage.value&&(showOff.value||s.active)))
const count=(k:StageKey)=>st.value.standards.filter(s=>s.stage===k&&s.active).length

type Row={id:string;name:string;method:string;tool:string;kind:'numeric'|'judge'|'option';lower:string|number;upper:string|number;target:string|number;unit:string;options:string;passOptions:string[];required:boolean}
const open=ref(false),editing=ref<Standard|undefined>(),phase=ref<'edit'|'preview'|'toggle'>('edit'),err=ref(''),ok=ref(''),saving=ref(false)
const f=reactive({name:'',scope:'object' as 'all'|'object',objType:'material',objCode:'',items:[] as Row[],reason:'',toggleReason:'',fcOn:false,fcProcs:[] as string[]})
// 首检要求（BL-borrow 第 17 条）：生产阶段标准的属性；工序取本工作区工序目录（六膳门 = 黑湖）
const procs=ref<{id:string;code:string;name:string}[]|null>(null)
onMounted(async()=>{try{procs.value=(await get<{list:{id:string;code:string;name:string}[]|null}>('processes')).list}catch{procs.value=null}})
const procName=(c:string)=>procs.value?.find(p=>p.code===c)?.name||c
const optList=(it:Row)=>Array.from(new Set(it.options.split(/[、,，\n]/).map(x=>x.trim()).filter(Boolean)))
const objTypes=computed(()=>meta.value.stages[stage.value].objectTypes.filter(t=>t!=='other'))
const objList=computed(()=>E.objects[f.objType]||[])
const blank=():Row=>({id:'',name:'',method:'',tool:'',kind:'numeric',lower:'',upper:'',target:'',unit:'',options:'',passOptions:[],required:true})
function openNew(){editing.value=undefined;Object.assign(f,{name:'',scope:'object',objType:objTypes.value[0]||'material',objCode:'',items:[blank()],reason:'',toggleReason:'',fcOn:false,fcProcs:[]});phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function openOne(s:Standard){editing.value=s;const v=stdVersion(s)!;Object.assign(f,{name:v.name,scope:v.scope,objType:v.objectRef?.type||objTypes.value[0],objCode:v.objectRef?.code||'',items:v.items.map(i=>({id:i.id,name:i.name,method:i.method,tool:i.tool||'',kind:i.kind,lower:i.lower??'',upper:i.upper??'',target:i.target??'',unit:i.unit||'',options:(i.options||[]).join('、'),passOptions:[...(i.passOptions||[])],required:i.required})),reason:'',toggleReason:'',fcOn:!!v.firstCheck,fcProcs:[...(v.firstCheck?.processes||[])]});phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function close(){open.value=false;E.drawerOpen=false}
// 本地校验（与服务端同规则；服务端为准）
const invalid=computed(()=>{
  if(!f.name.trim())return '标准名称不能为空'
  if(f.scope==='object'&&!f.objCode.trim())return '请选择适用对象，或选「本阶段全部对象」'
  if(!f.items.length)return '至少需要一个检验项'
  for(const [i,it] of f.items.entries()){
    if(!it.name.trim())return '第 '+(i+1)+' 个检验项缺少名称'
    if(it.kind==='numeric'){const lo=it.lower===''?null:Number(it.lower),hi=it.upper===''?null:Number(it.upper);if(lo==null&&hi==null)return '「'+it.name+'」至少填写下限或上限';if(lo!=null&&hi!=null&&lo>hi)return '「'+it.name+'」下限不能大于上限';if(!it.unit.trim())return '「'+it.name+'」缺少单位';if(it.target!==''){const tg=Number(it.target);if((lo!=null&&tg<lo)||(hi!=null&&tg>hi))return '「'+it.name+'」标准值必须在上下限之间'}}
    if(it.kind==='option'){const o=optList(it);if(o.length<2)return '「'+it.name+'」至少要两个选项';const pass=it.passOptions.filter(x=>o.includes(x));if(!pass.length)return '「'+it.name+'」至少勾选一个合格选项';if(pass.length===o.length)return '「'+it.name+'」所有选项都合格，判定没有意义'}
  }
  if(stage.value==='production'&&f.fcOn&&!f.fcProcs.length)return '用作首检时至少选一道工序'
  if(editing.value&&!f.reason.trim())return '修改已有标准必须填写修改原因'
  return ''
})
const objRef=()=>{if(f.scope==='all')return null;const hit=objList.value.find(o=>o.code===f.objCode);return hit?verifiedRef(hit):{type:f.objType,id:'',code:f.objCode.trim(),name:''}}
const desc=(items:{name:string;kind:string;lower?:unknown;upper?:unknown;unit?:string;required:boolean;passOptions?:string[]}[])=>items.map(i=>i.name+'['+range({kind:i.kind,lower:i.lower===''||i.lower==null?null:Number(i.lower),upper:i.upper===''||i.upper==null?null:Number(i.upper),unit:i.unit,passOptions:i.passOptions} as StdItem)+']'+(i.required?'':'(选检)')).join('、')
const fcText=(on:boolean,ps:string[])=>on&&ps.length?ps.map(procName).join('、'):'否'
const diff=computed(()=>{const v=editing.value?stdVersion(editing.value):undefined;const after={名称:f.name,适用对象:f.scope==='all'?'本阶段全部对象':f.objCode,检验项:desc(f.items),首检工序:fcText(f.fcOn,f.fcProcs)};if(!v)return Object.entries(after).map(([k,a])=>[k,'（新建）',a]);const before={名称:v.name,适用对象:v.scope==='all'?'本阶段全部对象':v.objectRef?.code||'',检验项:desc(v.items),首检工序:fcText(!!v.firstCheck,v.firstCheck?.processes||[])};return Object.keys(after).map(k=>[k,before[k as keyof typeof before],after[k as keyof typeof after]]).filter(([,b,a])=>b!==a)})
async function save(){err.value='';ok.value='';saving.value=true;try{const r=await write('standards/save',{id:editing.value?.id,stage:stage.value,name:f.name,scope:f.scope,objectRef:objRef(),items:f.items.map(it=>({...it,options:optList(it),passOptions:it.passOptions.filter(x=>optList(it).includes(x))})),firstCheck:stage.value==='production'?{enabled:f.fcOn,processes:f.fcProcs}:null,reason:f.reason});const back=st.value.standards.find(s=>s.id===r.id);if(!back)throw Error('已提交，回读不一致');openOne(back);const v=stdVersion(back)!;ok.value='已保存 · 回读一致：'+v.name+' v'+v.v+'（'+v.items.length+' 项）'}catch(e){err.value=e instanceof Error?e.message:'保存失败';phase.value='edit'}finally{saving.value=false}}
async function toggle(){err.value='';if(!f.toggleReason.trim()){err.value=(editing.value!.active?'停用':'启用')+'必须填写原因';return}saving.value=true;try{const r=await write('standards/toggle',{id:editing.value!.id,active:!editing.value!.active,reason:f.toggleReason});const back=st.value.standards.find(s=>s.id===r.id)!;openOne(back);ok.value='已'+(back.active?'启用':'停用')+' · 回读一致'}catch(e){err.value=e instanceof Error?e.message:'操作失败'}finally{saving.value=false}}
const usage=(s:Standard)=>st.value.inspections.filter(q=>q.standardId===s.id).length
onBeforeUnmount(()=>{E.drawerOpen=false})
</script>
<template>
<section class="card">
  <div class="section-head"><h2>检验标准 <small>按阶段配置；标准值由质量人员填写，来源「Cretas 补充」</small></h2><div class="actions"><a class="e-link" :href="'#/quality/inspections?stage='+stage+(E.ws==='ydwm'?'&ws=ydwm':'')">返回质量检验</a><UButton size="sm" @click="openNew()">新建标准</UButton></div></div>
  <div class="e-tabs" role="tablist" aria-label="检验阶段"><button v-for="k in STAGE_ORDER" :key="k" role="tab" :aria-selected="stage===k" @click="stage=k">{{meta.stages[k].label}}<b>{{count(k)}}</b></button></div>
  <p class="e-stage-hint">{{meta.stages[stage].hint}} 对象范围：{{meta.stages[stage].objectLabel}}。</p>
  <p v-if="E.lake?.available" class="e-stage-hint">黑湖检验规范、检验项当前都是 0 条，所以这里的检验标准都是 Cretas 补充；以后黑湖返回规范，以黑湖为准。</p>
  <div class="filters"><label class="e-switch"><input v-model="showOff" type="checkbox">显示已停用</label></div>
  <div v-if="!rows.length" class="e-empty"><p>{{meta.stages[stage].label}}阶段还没有{{showOff?'':'启用的'}}检验标准。</p><UButton size="sm" @click="openNew()">新建标准</UButton></div>
  <div v-else class="table-scroll"><table><thead><tr><th>标准名称</th><th>适用对象</th><th>检验项</th><th>版本</th><th>状态</th><th>已用于</th><th>来源</th><th>最后修改</th><th class="op">操作</th></tr></thead>
  <tbody><tr v-for="s in rows" :key="s.id"><td>{{stdVersion(s)!.name}} <span v-if="stdVersion(s)!.firstCheck" class="state active" :title="'首检工序：'+stdVersion(s)!.firstCheck!.processes.map(procName).join('、')">首检 {{stdVersion(s)!.firstCheck!.processes.length}} 道工序</span></td><td :title="refText(stdVersion(s)!.objectRef)">{{stdVersion(s)!.scope==='all'?'本阶段全部'+meta.stages[s.stage].objectLabel:refText(stdVersion(s)!.objectRef)}} <span v-if="stdVersion(s)!.objectRef" v-html="refTag(stdVersion(s)!.objectRef)"/></td>
    <td :title="desc(stdVersion(s)!.items)">{{stdVersion(s)!.items.length}} 项</td><td>v{{stdVersion(s)!.v}}</td><td><span class="state" :class="s.active?'success':'muted'">{{s.active?'启用':'已停用'}}</span></td><td>{{usage(s)}} 张检验单</td><td><span v-html="tag('cretas')"/></td><td>{{stdVersion(s)!.by}} · {{fmtTime(stdVersion(s)!.at)}}</td>
    <td class="op"><UButton size="xs" color="neutral" variant="outline" @click="openOne(s)">编辑</UButton></td></tr></tbody></table></div>
</section>
<aside v-if="open" class="e-drawer" role="dialog" aria-modal="false" :aria-label="editing?'编辑标准':'新建标准'" @keydown.esc="close()">
  <header><div><h2>{{editing?stdVersion(editing)!.name+' v'+stdVersion(editing)!.v:'新建'+meta.stages[stage].label+'标准'}} <span v-if="editing" class="state" :class="editing.active?'success':'muted'">{{editing.active?'启用':'已停用'}}</span></h2><p>{{meta.stages[stage].label}}阶段 · 对象范围 {{meta.stages[stage].objectLabel}} · <span v-html="tag('cretas')"/></p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="close()">×</UButton></header>
  <div class="e-body">
    <div class="e-sec"><div class="e-grid">
      <label class="wide"><span class="e-req">标准名称</span><input v-model="f.name" maxlength="60" placeholder="如：冷冻原料到货验收"></label>
      <label><span class="e-req">适用范围</span><select v-model="f.scope"><option value="object">指定对象</option><option value="all">本阶段全部{{meta.stages[stage].objectLabel}}</option></select></label>
      <label v-if="f.scope==='object'&&objTypes.length>1"><span>对象类型</span><select v-model="f.objType"><option v-for="t in objTypes" :key="t" :value="t">{{TYPE_LABEL[t]}}</option></select></label>
      <label v-if="f.scope==='object'" class="wide"><span class="e-req">适用对象（{{TYPE_LABEL[f.objType]}}）</span><input v-model="f.objCode" list="e-std-obj" :placeholder="objList.length?'输入编码或从列表选择':'本工作区没有可选对象，可手填编号（标未核实）'" aria-label="适用对象编码"><datalist id="e-std-obj"><option v-for="o in objList" :key="o.code" :value="o.code">{{o.name}}</option></datalist><small v-if="f.objCode&&objList.find(o=>o.code===f.objCode)">{{objList.find(o=>o.code===f.objCode)!.name}}</small><small v-else-if="f.objCode" class="e-over">不在本工作区对象列表中，保存为未核实引用</small></label>
    </div></div>
    <div class="e-sec"><h3>检验项 <i class="help" tabindex="0" title="判定由代码给出：数值项按上下限，选项项按勾选的合格选项；判定项由检验人选合格/不合格。标准值只作参考，不参与判定。">?</i></h3>
      <div v-for="(it,i) in f.items" :key="i" class="e-item-card">
        <div class="e-grid e-grid3"><label><span class="e-req">名称</span><input v-model="it.name" :aria-label="'检验项 '+(i+1)+' 名称'"></label>
          <label><span>类型</span><select v-model="it.kind" :aria-label="'检验项 '+(i+1)+' 类型'"><option value="numeric">数值（按上下限判定）</option><option value="option">选项（按合格选项判定）</option><option value="judge">判定（人工选合格/不合格）</option></select></label>
          <label class="e-inline"><input v-model="it.required" type="checkbox" :aria-label="'检验项 '+(i+1)+' 必检'">必检<button class="e-link" style="margin-left:auto" :aria-label="'删除检验项 '+(i+1)" @click="f.items.splice(i,1)">删除</button></label></div>
        <div v-if="it.kind==='numeric'" class="e-grid e-grid4"><label><span>下限</span><input v-model="it.lower" type="number" step="0.01" :aria-label="'检验项 '+(i+1)+' 下限'"></label><label><span>上限</span><input v-model="it.upper" type="number" step="0.01" :aria-label="'检验项 '+(i+1)+' 上限'"></label><label><span>标准值（可选）</span><input v-model="it.target" type="number" step="0.01" :aria-label="'检验项 '+(i+1)+' 标准值'"></label><label><span class="e-req">单位</span><input v-model="it.unit" :aria-label="'检验项 '+(i+1)+' 单位'"></label></div>
        <div v-else-if="it.kind==='option'" class="e-grid"><label class="wide"><span class="e-req">选项（用顿号分隔）</span><input v-model="it.options" :aria-label="'检验项 '+(i+1)+' 选项'" placeholder="如：干净、少量残留、明显残留"></label>
          <div class="e-field wide"><span>合格选项</span><div class="e-procs"><label v-for="o in optList(it)" :key="o"><input v-model="it.passOptions" type="checkbox" :value="o" :aria-label="'合格选项 '+o">{{o}}</label><em v-if="!optList(it).length" class="muted">先填选项</em></div></div></div>
        <div class="e-grid"><label><span>方法</span><input v-model="it.method" placeholder="可选"></label><label><span>检验工具</span><input v-model="it.tool" placeholder="可选，如 探针温度计"></label></div>
      </div>
      <UButton size="xs" color="neutral" variant="outline" style="margin-top:6px" @click="f.items.push(blank())">添加检验项</UButton>
    </div>
    <div v-if="stage==='production'" class="e-sec"><h3>首检 <i class="help" tabindex="0" title="勾选后，这些工序的工序任务需要先做首检（生产阶段检验，选这条标准）。首检要求是 Cretas 补充，黑湖没有这项配置；未通过首检只标红提醒，不阻断报工。">?</i></h3>
      <label class="e-inline"><input v-model="f.fcOn" type="checkbox" :disabled="procs===null">用作首检标准</label>
      <p v-if="procs===null" class="muted">本工作区没有可读的工序目录，首检只在六膳门工作区可用。</p>
      <div v-else-if="f.fcOn" class="e-procs" style="margin-top:6px"><label v-for="p in procs" :key="p.code"><input v-model="f.fcProcs" type="checkbox" :value="p.code">{{p.name}}</label></div></div>
    <label v-if="editing" class="e-field e-sec"><span class="e-req">修改原因</span><input v-model="f.reason" placeholder="如：按供应商新规格调整上限"></label>
    <p v-if="invalid&&phase==='edit'" class="muted" role="note">{{invalid}}</p>
    <div v-if="phase==='preview'" class="e-preview"><h4>保存预览 · {{editing?'生成 v'+(stdVersion(editing)!.v+1):'新建 v1'}}</h4><div class="diff"><span class="h">字段</span><span class="h">改前</span><span class="h">改后</span><template v-for="[k,b,a] in diff" :key="k"><span>{{k}}</span><del>{{b||'—'}}</del><ins>{{a}}</ins></template></div><p class="muted" style="margin-top:6px">旧版本保留；已提交的检验单继续按其锁定版本显示。不回写黑湖。</p></div>
    <div v-if="phase==='toggle'" class="e-preview"><h4>{{editing!.active?'停用':'启用'}}标准</h4><label class="e-field"><span class="e-req">原因</span><input v-model="f.toggleReason" aria-label="停用或启用原因"></label><p class="muted">{{editing!.active?'停用后新建检验不可再选；历史检验不受影响。':'启用后可在新建检验时选择。'}}</p></div>
    <p v-if="err" role="alert" class="e-err">{{err}}</p><p v-if="ok" role="status" class="e-ok">{{ok}}</p>
    <details v-if="editing" class="e-log e-sec"><summary>修改记录 {{editing.log.length}} 条 · 历史版本 {{editing.versions.length}} 个</summary><div v-html="logHTML(editing.log,'标准')"/></details>
  </div>
  <footer><span class="muted">{{E.session?'写入练习会话 '+E.session:'写入本机原型保存区'}}</span>
    <template v-if="phase==='preview'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">返回修改</UButton><UButton size="sm" :loading="saving" @click="save()">确认保存</UButton></template>
    <template v-else-if="phase==='toggle'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="sm" :color="editing!.active?'error':'primary'" :loading="saving" @click="toggle()">确认{{editing!.active?'停用':'启用'}}</UButton></template>
    <template v-else><UButton v-if="editing" size="sm" color="neutral" variant="ghost" @click="phase='toggle'">{{editing.active?'停用':'启用'}}</UButton><UButton size="sm" :disabled="!!invalid" @click="phase='preview'">保存</UButton></template>
  </footer>
</aside>
</template>
