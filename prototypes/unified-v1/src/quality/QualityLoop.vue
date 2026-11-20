<script setup lang="ts">
// 第八批（R37 / R41）：质量闭环页。黑湖没有 CCP、退回、废弃、召回、标签拍检这类数据：
// 这里只存 Cretas 补充记录（核心 5 页：CCP 监控记录、质检退回单、废弃处理、批次召回、包装标签拍检），
// 其余 3 页（工艺参数偏离、冷链超温批次、视觉质检训练）只写缺口，不造数据。
// 写入走 /__unified/e/api/records/*：预览 → 确认 → 独立回读；记录不能改，只能关闭（写处理结果）或作废（写原因）。
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {E,get,write,fmtTime,tag,logHTML,fmt2} from '../collab/e-store'
interface Rec{id:string;no:string;type:string;status:'RECORDED'|'OPEN'|'CLOSED'|'VOID';source:string;fields:Record<string,any>;createdAt:string;createdBy:string;updatedAt:string;closeResult?:string;closedAt?:string;closedBy?:string;voidReason?:string;log:{at:string;by:string;action:string;detail?:string}[]}
const path=ref('')
const syncPath=()=>{path.value=location.hash.replace(/^#/,'').split('?')[0]}
const SLUG:Record<string,string>={'/quality/ccp':'ccp','/quality/returns':'return','/quality/disposals':'disposal','/quality/recalls':'recall','/quality/label-qc':'label'}
const GAPS:Record<string,{title:string;formal:string;ydwm:string;need:string;owner:string}>={
  '/quality/param-deviation':{title:'工艺参数偏离',formal:'/quality/param-deviation',ydwm:'味美编译页打开返回 404，没有可直达的页面',need:'需要工序的工艺设定值（温度、时间、转速等）和设备或人工采集的实测值，偏离后扣留本批及下游。黑湖报工只有数量和重量，没有这类参数；Cretas 也还没有采集入口。',owner:'数据来源要先定：是接设备采集，还是由 CCP 监控记录之外另开参数记录（E 线）'},
  '/quality/cold-chain':{title:'冷链超温批次',formal:'/quality/cold-chain',ydwm:'味美编译页打开返回 404，没有可直达的页面',need:'需要冷库、冷藏车的温度采集（逐时读数）和批次出入库时间，才能判断哪些批次超温。黑湖没有温度数据；入库批次（C）只有仓库与数量。',owner:'温度采集来源待定（设备或手工）；批次对应关系找 C'},
  '/quality/vision-training':{title:'视觉质检训练',formal:'/quality/vision-training',ydwm:'味美编译页打开返回 403（无权限），没有可直达的页面',need:'属于 AI 识别能力的配置（训练选样、标注、模型版本），不是质量业务单据。按 G-gap-v2 下沉到「设置」，只写明缺口，不在原型里做训练。',owner:'由 F / D 在设置里的 AI 能力配置承接'}
}
const slug=computed(()=>SLUG[path.value]||'')
const gap=computed(()=>GAPS[path.value])
const TITLE:Record<string,{h:string;hint:string;direct:string}>={
  ccp:{h:'CCP 监控记录',hint:'记录工序关键控制点的实测值；是否偏离由代码按关键限值计算。偏离只提示「建议扣留本批及下游」，不自动扣留。',direct:'味美编译页可直达（/quality/ccp）'},
  return:{h:'质检退回单',hint:'检验不合格后把货退回工序或供应商：写明对象、数量、原因、退回给谁；处理完写结果关闭。',direct:'味美编译页可直达（/quality/returns）'},
  disposal:{h:'废弃处理',hint:'记录报废、销毁、退供应商、回炉返工的对象、数量和原因；可挂 C 的入库批次。',direct:'味美编译页可直达（/quality/disposals）'},
  recall:{h:'批次召回',hint:'选 C 的入库批次发起召回，进行中的召回会让该批次进入「待处置」扣留，C 的先到期先出建议会跳过它。',direct:'味美编译页打开返回 403，不可直达'},
  label:{h:'包装标签拍检',hint:'按七项标签内容逐项记合格、不合格或不适用；有一项不合格即判不合格。',direct:'味美沿用原编译页'}
}
const FIELDS:Record<string,{k:string;l:string;t:'text'|'num'|'select'|'time'|'process'|'batch'|'insp';req?:boolean;opts?:string[];wide?:boolean;ph?:string}[]>={
  ccp:[{k:'processCode',l:'工序',t:'process',req:true},{k:'point',l:'监控点',t:'text',req:true,ph:'如 中心温度'},{k:'lower',l:'关键限值下限',t:'num'},{k:'upper',l:'关键限值上限',t:'num'},{k:'value',l:'实测值',t:'num',req:true},{k:'unit',l:'单位',t:'text',ph:'如 ℃'},{k:'projectCode',l:'工单号（选填）',t:'text'},{k:'monitoredAt',l:'监控时间',t:'time',req:true},{k:'monitor',l:'监控人',t:'text',req:true},{k:'note',l:'备注',t:'text',wide:true}],
  return:[{k:'object',l:'退回对象',t:'text',req:true,ph:'工单、批次或物料编号'},{k:'inspectionNo',l:'来源检验单（选填）',t:'insp'},{k:'qty',l:'退回数量',t:'num',req:true},{k:'unit',l:'单位',t:'text',req:true,ph:'如 kg'},{k:'returnTo',l:'退回给',t:'text',req:true,ph:'工序或供应商'},{k:'reason',l:'退回原因',t:'text',req:true,wide:true}],
  disposal:[{k:'object',l:'废弃对象',t:'text',req:true},{k:'batchNo',l:'入库批次（选填）',t:'batch'},{k:'inspectionNo',l:'来源检验单（选填）',t:'insp'},{k:'qty',l:'废弃数量',t:'num',req:true},{k:'unit',l:'单位',t:'text',req:true},{k:'method',l:'废弃方式',t:'select',req:true,opts:[]},{k:'reason',l:'废弃原因',t:'text',req:true,wide:true}],
  recall:[{k:'batchNo',l:'入库批次',t:'batch',req:true},{k:'level',l:'召回等级',t:'select',req:true,opts:[]},{k:'reason',l:'召回原因',t:'text',req:true,wide:true},{k:'scope',l:'影响范围说明',t:'text',wide:true}],
  label:[{k:'product',l:'产品',t:'text',req:true},{k:'batchNo',l:'入库批次（选填）',t:'batch'},{k:'inspector',l:'检查人',t:'text',req:true},{k:'checkedAt',l:'检查时间',t:'time',req:true},{k:'photoNote',l:'照片说明（原型不存照片）',t:'text',wide:true}]
}
const meta=computed(()=>E.state?.meta as any)
const recs=computed<Rec[]>(()=>((E.state as any)?.records||[]).filter((r:Rec)=>r.type===slug.value).slice().sort((a:Rec,b:Rec)=>b.createdAt.localeCompare(a.createdAt)))
const showVoid=ref(false)
const shown=computed(()=>recs.value.filter(r=>showVoid.value||r.status!=='VOID'))
const procs=ref<{code:string;name:string}[]>([]),batches=ref<{batchNo:string;productName:string;qty:number|null;unit:string;expiryDate:string}[]>([]),batchNote=ref('')
const insps=computed(()=>(E.state?.inspections||[]).filter(q=>q.status!=='VOID'))
const nowBj=()=>new Date(Date.now()+8*3600e3).toISOString().slice(0,16)
const open=ref(false),cur=ref<Rec>(),phase=ref<'edit'|'preview'|'close'|'void'>('edit'),err=ref(''),ok=ref(''),saving=ref(false)
const f=reactive<Record<string,any>>({}),checks=reactive<Record<string,string>>({}),reason=ref('')
function fieldsOf(){const fs=FIELDS[slug.value]||[];return fs.map(x=>x.k==='method'?{...x,opts:meta.value?.disposalMethods||[]}:x.k==='level'?{...x,opts:meta.value?.recallLevels||[]}:x)}
function openNew(){cur.value=undefined;for(const k of Object.keys(f))delete f[k];for(const x of fieldsOf())f[x.k]=x.t==='time'?nowBj():'';f.actor=E.actor;for(const k of meta.value?.labelItems||[])checks[k]='';reason.value='';phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function openOne(r:Rec){cur.value=r;reason.value='';phase.value='edit';err.value='';ok.value='';open.value=true;E.drawerOpen=true}
function close(){open.value=false;E.drawerOpen=false}
const missing=computed(()=>{if(cur.value)return '';for(const x of fieldsOf())if(x.req&&!String(f[x.k]??'').trim())return '请填写'+x.l.replace('（选填）','');
  for(const x of fieldsOf())if(x.t==='num'){const t=String(f[x.k]??'').trim();if(t&&!/^-?\d+(\.\d{1,2})?$/.test(t))return '「'+x.l+'」必须是数字，最多两位小数';if(t&&(x.k==='qty')&&Number(t)<=0)return '「'+x.l+'」必须大于 0'}
  if(slug.value==='disposal'&&f.batchNo){const b=batches.value.find(b=>b.batchNo===f.batchNo);const u=String(f.unit??'').trim();if(b&&b.unit&&u&&u.toLowerCase()!==b.unit.toLowerCase())return '单位要和批次单位一致：批次单位是 '+b.unit}if(slug.value==='label')for(const k of meta.value?.labelItems||[])if(!checks[k])return '请选择「'+k+'」的结果';return ''})
function body(){const o:Record<string,any>={};for(const x of fieldsOf())o[x.k]=f[x.k];if(slug.value==='ccp'){const p=procs.value.find(p=>p.code===f.processCode);if(p)o.processName=p.name}if(slug.value==='label')o.checks={...checks};return o}
const val=(r:Rec,k:string)=>{const v=r.fields[k];return v==null||v===''?'—':v}
const resultText=(r:Rec)=>r.type==='ccp'?(r.fields.result==='PASS'?'在限值内':'偏离'):r.type==='label'?(r.fields.result==='PASS'?'合格':'不合格：'+(r.fields.failItems||[]).join('、')):''
const STATUS:Record<string,string>={RECORDED:'已记录',OPEN:'进行中',CLOSED:'已关闭',VOID:'已作废'}
const limitText=(f:Record<string,any>)=>(f.lower!=null?fmt2(f.lower):'—')+' ~ '+(f.upper!=null?fmt2(f.upper):'—')+(f.unit?' '+f.unit:'')
function previewRows(){const rows:[string,string][]=[];for(const x of fieldsOf()){let v=String(f[x.k]??'').trim();if(x.t==='process')v=procs.value.find(p=>p.code===v)?.name||v;rows.push([x.l.replace('（选填）',''),v||'未填写'])}if(slug.value==='label')for(const k of meta.value?.labelItems||[])rows.push([k,checks[k]||'未选']);return rows}
async function save(){err.value='';ok.value='';saving.value=true;try{const r=await write('records/save',{type:slug.value,fields:body()});const back=(E.state as any).records.find((x:Rec)=>x.id===r.id);if(!back)throw Error('已提交，回读不一致');openOne(back);ok.value='已保存 · 回读一致：'+back.no+(back.type==='ccp'||back.type==='label'?'（'+resultText(back)+'）':'')}catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{saving.value=false}}
async function closeRec(){err.value='';if(!reason.value.trim()){err.value='关闭必须填写处理结果';return}saving.value=true;try{const r=await write('records/close',{id:cur.value!.id,result:reason.value});const back=(E.state as any).records.find((x:Rec)=>x.id===r.id);openOne(back);ok.value='已关闭 · 回读一致：'+back.closeResult}catch(e){err.value=e instanceof Error?e.message:'关闭失败'}finally{saving.value=false}}
async function voidRec(){err.value='';if(!reason.value.trim()){err.value='作废必须填写原因';return}saving.value=true;try{const r=await write('records/void',{id:cur.value!.id,reason:reason.value});const back=(E.state as any).records.find((x:Rec)=>x.id===r.id);openOne(back);ok.value='已作废 · 回读一致：'+back.voidReason}catch(e){err.value=e instanceof Error?e.message:'作废失败'}finally{saving.value=false}}
async function loadAux(){try{procs.value=((await get<{list:{code:string;name:string}[]|null}>('processes')).list)||[]}catch{procs.value=[]}
  if(E.ws==='lsm'){try{const b=await get<{available:boolean;reason?:string;rows:any[]}>('batches');batches.value=b.rows;batchNote.value=b.available?'':(b.reason||'C 入库批次接口未就绪')}catch(e){batchNote.value=e instanceof Error?e.message:'C 入库批次读取失败'}}}
function onHash(){syncPath();open.value=false;E.drawerOpen=false}
// 废弃处理选了入库批次：带出物料名（对象为空时）和批次单位；单位与批次不一致由 missing 提示、服务端也拦
watch(()=>f.batchNo,v=>{if(slug.value!=='disposal'||cur.value)return;const b=batches.value.find(b=>b.batchNo===v);if(!b)return;if(!String(f.object??'').trim()||batches.value.some(x=>x.productName===f.object))f.object=b.productName;if(b.unit)f.unit=b.unit})
onMounted(()=>{syncPath();window.addEventListener('hashchange',onHash);loadAux()})
onBeforeUnmount(()=>{window.removeEventListener('hashchange',onHash);E.drawerOpen=false})
</script>
<template>
<template v-if="gap">
  <section class="card">
    <div class="section-head"><h2>{{gap.title}}</h2><span class="state muted">只写缺口，未做</span></div>
    <div class="e-empty" style="text-align:left">
      <p><b>{{E.ws==='lsm'?'黑湖没有此类数据。':'味美当前不可直达。'}}</b>{{E.ws==='lsm'?'':gap.ydwm+'。'}}原型不造数据，也不拿别的数据顶替；这一页按 R37 只记缺口，没有补充入口。</p>
      <dl style="margin:8px 0 0"><dt class="muted">缺什么</dt><dd style="margin:0 0 8px">{{gap.need}}</dd><dt class="muted">正式端路由</dt><dd style="margin:0 0 8px">{{gap.formal}}（味美：{{gap.ydwm}}）</dd><dt class="muted">谁来补</dt><dd style="margin:0">{{gap.owner}}</dd></dl>
    </div>
  </section>
</template>
<section v-else-if="E.ws==='ydwm'" class="card">
  <div class="section-head"><h2>{{TITLE[slug]?.h}}</h2></div>
  <div class="e-empty" style="text-align:left"><p>{{slug==='recall'?'味美工作区没有可用的编译页：':'味美工作区这一页由味美编译页承担：'}}{{TITLE[slug]?.direct}}。统一入口不再另做一份记录，也不写味美账号。</p></div>
</section>
<section v-else class="card">
  <div class="section-head"><h2>{{TITLE[slug]?.h}} <i class="help" tabindex="0" :title="TITLE[slug]?.hint">?</i></h2><div class="actions"><span v-html="tag('cretas')"/><UButton size="sm" @click="openNew()">新建{{TITLE[slug]?.h}}</UButton></div></div>
  <p class="source-gap" style="margin:0 16px 8px">黑湖没有此类数据；这里只有 Cretas 补充记录，不回写黑湖。{{TITLE[slug]?.hint}}</p>
  <div class="filters"><label class="e-switch"><input v-model="showVoid" type="checkbox">显示已作废</label></div>
  <div v-if="!shown.length" class="e-empty"><p>还没有{{TITLE[slug]?.h}}。</p><UButton size="sm" @click="openNew()">新建{{TITLE[slug]?.h}}</UButton></div>
  <div v-else class="table-scroll"><table>
    <thead v-if="slug==='ccp'"><tr><th>单号</th><th>工序</th><th>监控点</th><th>实测值</th><th>关键限值</th><th>判定</th><th>监控时间</th><th>状态</th></tr></thead>
    <thead v-else-if="slug==='return'"><tr><th>单号</th><th>退回对象</th><th>数量</th><th>退回给</th><th>原因</th><th>创建时间</th><th>状态</th></tr></thead>
    <thead v-else-if="slug==='disposal'"><tr><th>单号</th><th>废弃对象</th><th>批次</th><th>数量</th><th>方式</th><th>原因</th><th>创建时间</th><th>状态</th></tr></thead>
    <thead v-else-if="slug==='recall'"><tr><th>单号</th><th>批次</th><th>产品</th><th>等级</th><th>原因</th><th>创建时间</th><th>状态</th></tr></thead>
    <thead v-else><tr><th>单号</th><th>产品</th><th>批次</th><th>判定</th><th>检查人</th><th>检查时间</th><th>状态</th></tr></thead>
    <tbody><tr v-for="r in shown" :key="r.id" :class="{muted:r.status==='VOID'}" style="cursor:pointer" tabindex="0" @click="openOne(r)" @keydown.enter="openOne(r)">
      <template v-if="slug==='ccp'"><td>{{r.no}}</td><td>{{val(r,'processName')}}</td><td>{{val(r,'point')}}</td><td :class="{'e-over':r.fields.result==='DEVIATION'}">{{fmt2(r.fields.value)}} {{r.fields.unit}}</td><td>{{limitText(r.fields)}}</td><td :class="{'e-over':r.fields.result==='DEVIATION'}">{{resultText(r)}}</td><td>{{r.fields.monitoredAt.replace('T',' ')}}</td></template>
      <template v-else-if="slug==='return'"><td>{{r.no}}</td><td>{{val(r,'object')}}</td><td>{{fmt2(r.fields.qty)}} {{r.fields.unit}}</td><td>{{val(r,'returnTo')}}</td><td>{{val(r,'reason')}}</td><td>{{fmtTime(r.createdAt)}}</td></template>
      <template v-else-if="slug==='disposal'"><td>{{r.no}}</td><td>{{val(r,'object')}}</td><td>{{val(r,'batchNo')}}</td><td>{{fmt2(r.fields.qty)}} {{r.fields.unit}}</td><td>{{val(r,'method')}}</td><td>{{val(r,'reason')}}</td><td>{{fmtTime(r.createdAt)}}</td></template>
      <template v-else-if="slug==='recall'"><td>{{r.no}}</td><td>{{val(r,'batchNo')}}</td><td>{{r.fields.batch?.productName||'—'}}</td><td>{{val(r,'level')}}</td><td>{{val(r,'reason')}}</td><td>{{fmtTime(r.createdAt)}}</td></template>
      <template v-else><td>{{r.no}}</td><td>{{val(r,'product')}}</td><td>{{val(r,'batchNo')}}</td><td :class="{'e-over':r.fields.result==='FAIL'}">{{resultText(r)}}</td><td>{{val(r,'inspector')}}</td><td>{{r.fields.checkedAt.replace('T',' ')}}</td></template>
      <td v-if="slug==='ccp'||slug==='label'"><span class="state" :class="r.status==='VOID'?'muted':'success'">{{STATUS[r.status]}}</span></td><td v-else><span class="state" :class="r.status==='OPEN'?'warning':r.status==='VOID'?'muted':'success'">{{STATUS[r.status]}}</span></td>
    </tr></tbody></table></div>
  <div class="pagination"><span>共 {{shown.length}} 条 · Cretas 补充</span><span class="muted">{{TITLE[slug]?.direct}}</span></div>
</section>

<aside v-if="open&&slug" class="e-drawer" role="dialog" aria-modal="false" :aria-label="cur?cur.no:'新建'+TITLE[slug].h" @keydown.esc="close()">
  <header><div><h2>{{cur?cur.no:'新建'+TITLE[slug].h}} <span v-if="cur" class="state" :class="cur.status==='OPEN'?'warning':cur.status==='VOID'?'muted':'success'">{{STATUS[cur.status]}}</span></h2><p>{{TITLE[slug].h}} · <span v-html="tag('cretas')"/></p></div><UButton aria-label="关闭" size="xs" color="neutral" variant="ghost" @click="close()">×</UButton></header>
  <div class="e-body">
    <template v-if="!cur">
      <div class="e-sec"><div class="e-grid">
        <label v-for="x in fieldsOf()" :key="x.k" :class="{wide:x.wide}"><span :class="{'e-req':x.req}">{{x.l}}</span>
          <select v-if="x.t==='process'" v-model="f[x.k]"><option value="">请选择工序</option><option v-for="p in procs" :key="p.code" :value="p.code">{{p.name}}</option></select>
          <select v-else-if="x.t==='batch'" v-model="f[x.k]"><option value="">{{x.req?'请选择批次':'不挂批次'}}</option><option v-for="b in batches" :key="b.batchNo" :value="b.batchNo">{{b.batchNo}} · {{b.productName}}</option></select>
          <select v-else-if="x.t==='insp'" v-model="f[x.k]"><option value="">不关联</option><option v-for="q in insps" :key="q.id" :value="q.no">{{q.no}}{{q.status==='SUBMITTED'&&q.verdict==='FAIL'?'（不合格）':''}}</option></select>
          <select v-else-if="x.t==='select'" v-model="f[x.k]"><option value="">请选择</option><option v-for="o in x.opts" :key="o">{{o}}</option></select>
          <input v-else-if="x.t==='time'" v-model="f[x.k]" type="datetime-local" :max="nowBj()">
          <input v-else v-model="f[x.k]" :inputmode="x.t==='num'?'decimal':undefined" :placeholder="x.ph" maxlength="200">
        </label>
        <div v-if="slug==='label'" class="e-field wide"><span class="e-req">七项检查</span><div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px"><label v-for="k in meta?.labelItems" :key="k" style="display:flex;gap:6px;align-items:center;margin:0"><span style="min-width:4em">{{k}}</span><select v-model="checks[k]" :aria-label="k"><option value="">请选择</option><option>合格</option><option>不合格</option><option>不适用</option></select></label></div></div>
      </div></div>
      <p v-if="batchNote&&(slug==='recall'||slug==='disposal'||slug==='label')" class="source-gap">{{batchNote}}。选批次依赖 C 的入库批次接口。</p>
      <p v-if="missing&&phase==='edit'" class="muted" role="note">{{missing}}</p>
      <div v-if="phase==='preview'" class="e-preview"><h4>保存预览</h4><div class="diff"><template v-for="r in previewRows()" :key="r[0]"><span>{{r[0]}}</span><span></span><ins>{{r[1]}}</ins></template></div><p class="muted" style="margin-top:6px">只在 Cretas 保存一条补充记录，不回写黑湖、不改其他单据。{{slug==='recall'?'召回进行中，该批次会进入「待处置」扣留。':''}}判定由代码按填写内容计算。</p></div>
    </template>
    <template v-else>
      <div class="e-sec"><div class="e-grid">
        <template v-for="x in fieldsOf()" :key="x.k"><div :class="{wide:x.wide}" class="e-field"><span class="muted">{{x.l.replace('（选填）','')}}</span><b>{{x.k==='processCode'?val(cur,'processName'):x.k==='monitoredAt'||x.k==='checkedAt'?String(val(cur,x.k)).replace('T',' '):x.t==='num'&&cur.fields[x.k]!=null?fmt2(cur.fields[x.k]):val(cur,x.k)}}</b></div></template>
        <div v-if="slug==='ccp'" class="e-field"><span class="muted">关键限值 / 判定</span><b :class="{'e-over':cur.fields.result==='DEVIATION'}">{{limitText(cur.fields)}} · 实测 {{fmt2(cur.fields.value)}} · {{resultText(cur)}}</b></div>
        <div v-if="slug==='ccp'&&cur.fields.result==='DEVIATION'" class="e-field wide"><span class="e-over">偏离：建议扣留本批及下游并通知品控；原型不自动扣留，请人工处理（可去「质量检验」新建检验或「协同任务」派人）。</span></div>
        <div v-if="slug==='label'" class="e-field wide"><span class="muted">七项检查</span><b>{{Object.entries(cur.fields.checks||{}).map(([k,v])=>k+'：'+v).join('　')}}</b><br><b :class="{'e-over':cur.fields.result==='FAIL'}">判定：{{resultText(cur)}}</b></div>
        <div v-if="slug==='recall'" class="e-field wide"><span class="muted">批次信息（取自 C）</span><b>{{cur.fields.batch?.productName}} · 生产 {{cur.fields.batch?.productionDate||'—'}} · 到期 {{cur.fields.batch?.expiryDate||'—'}} · {{fmt2(cur.fields.batch?.qty)}} {{cur.fields.batch?.unit}}</b></div>
        <div v-if="cur.closeResult" class="e-field wide"><span class="muted">处理结果</span><b>{{cur.closeResult}}（{{cur.closedBy}} · {{fmtTime(cur.closedAt)}}）</b></div>
        <div v-if="cur.voidReason" class="e-field wide"><span class="muted">作废原因</span><b>{{cur.voidReason}}</b></div>
      </div></div>
      <div v-if="phase==='close'||phase==='void'" class="e-preview"><h4>{{phase==='close'?'关闭':'作废'}} {{cur.no}}</h4><label class="e-field"><span class="e-req">{{phase==='close'?'处理结果':'作废原因'}}</span><input v-model="reason" maxlength="200" :aria-label="phase==='close'?'处理结果':'作废原因'"></label><p class="muted">{{phase==='void'?'作废后记录保留但不再生效'+(cur.type==='recall'&&cur.status==='OPEN'?'，批次扣留随之解除':'')+'。':cur.type==='recall'?'关闭后批次不再因召回扣留。':'关闭后记录保留，结果写入。'}}</p></div>
      <details class="e-log e-sec"><summary>记录 {{cur.log.length}} 条</summary><div v-html="logHTML(cur.log,'记录')"/></details>
    </template>
    <p v-if="err" role="alert" class="e-err">{{err}}</p><p v-if="ok" role="status" class="e-ok">{{ok}}</p>
  </div>
  <footer><span class="muted">{{E.session?'写入练习会话 '+E.session:'写入本机原型保存区'}}</span>
    <template v-if="!cur"><template v-if="phase==='preview'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">返回修改</UButton><UButton size="sm" :loading="saving" @click="save()">确认保存</UButton></template><UButton v-else size="sm" :disabled="!!missing" @click="phase='preview'">保存</UButton></template>
    <template v-else-if="phase==='close'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="sm" :loading="saving" @click="closeRec()">确认关闭</UButton></template>
    <template v-else-if="phase==='void'"><UButton size="sm" color="neutral" variant="outline" @click="phase='edit'">取消</UButton><UButton size="sm" color="error" :loading="saving" @click="voidRec()">确认作废</UButton></template>
    <template v-else-if="cur.status!=='VOID'"><UButton v-if="cur.status==='OPEN'" size="sm" @click="phase='close';reason=''">关闭</UButton><UButton size="sm" color="neutral" variant="ghost" @click="phase='void';reason=''">作废</UButton></template>
  </footer>
</aside>
</template>
