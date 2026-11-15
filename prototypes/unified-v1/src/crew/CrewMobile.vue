<script setup lang="ts">
// 结构 8 · 一线手机端（375 宽外壳）：操作员、仓管、质检员不在五位里选，按登录人岗位只给一个入口，自动对应到他那位数字员工。
// UX Flow Analysis 见 unified-spec/F-crew-mobile-uxflow.md。语音优先（H1，原型不录音）、主按钮唯一、数字大字、写操作预览 → 确认（H2）、确认卡每行都显示（H3）。
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {C,setRole,src,createInbox,today,realToday,itemStatus,perms} from './crew-store'
import {crewById,DEMO_ROLES,type CrewId} from './crew-config'
import {workOrders,rushCheck,qty,type WorkOrder} from './crew-facts'
import {reply,user,type Msg} from './crew-chat'
import CrewAvatar from './CrewAvatar.vue'
import CrewPreview from './CrewPreview.vue'
const ROLES=['operator','keeper','inspector'] as const
const role=computed(()=>(ROLES as readonly string[]).includes(C.role)?C.role as typeof ROLES[number]:'operator')
// 手机端身份只在这一页生效：离开时还原进入前的身份
const prevRole=C.role
if(!(ROLES as readonly string[]).includes(C.role))setRole('operator',false)
onBeforeUnmount(()=>setRole(prevRole,false))
const crewId=computed(()=>DEMO_ROLES[role.value].mobile as CrewId),d=computed(()=>crewById(crewId.value)!)
// F-6：本厂停用了这位数字员工时，手机端不再给入口
const enabledHere=computed(()=>C.state?C.state.config.enabled[crewId.value]!==false:true)
// 称重类工序（kg）叫「称重报工」，按盒等计数的叫「报工」
const actName=computed(()=>role.value==='inspector'?'记不合格':unit.value==='kg'?'称重报工':'报工')
const stage=ref<'home'|'form'|'preview'|'chat'>('home'),wo=ref<WorkOrder|null>(null),proc=ref(''),good=ref(''),input=ref(''),msgs=ref<Msg[]>([]),itemId=ref(''),err=ref('')
watch(role,()=>{stage.value='home';itemId.value='';msgs.value=[];err.value=''})
// 今天要办的（真实数据）：操作员 = 今天计划开工的黑湖工单；仓管 = 在生产但原料库存为 0 的产品；质检员 = 今天的工单（记不合格）
const todos=computed(()=>{const s=src();if(!s)return [];const t=today()
  if(role.value==='keeper'){const out:{title:string;sub:string;wo:WorkOrder}[]=[],seen=new Set<string>();for(const w of workOrders(s).filter(w=>w.planDate>=new Date(Date.parse(t)-6*864e5).toISOString().slice(0,10))){if(seen.has(w.productCode))continue;seen.add(w.productCode);const r=rushCheck(s,C.inv,{productCode:w.productCode,qty:0,date:t,today:t});if(r.lines.length&&r.lines.every(l=>l.stock===0))out.push({title:'核实原料库存 · '+w.productName,sub:r.lines.length+' 项原料库存快照为 0，近 14 天生产 '+r.history.days.length+' 天',wo:w})}return out.slice(0,5)}
  return workOrders(s).filter(w=>w.planDate===t).slice(0,6).map(w=>({title:w.productName,sub:w.code+' · 计划 '+qty(w.planAmount,w.unit==='KG'?'kg':w.unit)+' · 已报 '+(w.procs.map(p=>p.name).join('、')||'无'),wo:w}))})
// 「今天要办的」最多列 6 张（N-3）：快照日的工单总数另算，页面写明
const todoTotal=computed(()=>{const s=src();if(!s||role.value==='keeper')return 0;const t=today();return workOrders(s).filter(w=>w.planDate===t).length})
const procs=computed(()=>wo.value?.procs.map(p=>p.name)||[])
// 重量 / 数量的单位跟着所选工序在黑湖报工里的单位走（KG → kg；盒 → 盒）
const unit=computed(()=>{const u=wo.value?.procs.find(p=>p.name===proc.value)?.unit||'KG';return u==='KG'?'kg':u})
function start(w:WorkOrder){wo.value=w;err.value='';if(role.value==='keeper'){makeKeeper(w);return}proc.value=(w.procs.find(p=>p.unit==='KG')||w.procs[0])?.name||'出料';good.value='';stage.value='form'}
const goodErr=computed(()=>good.value===''?'':!/^\d{1,6}(\.\d{1,2})?$/.test(good.value)?'只填数字，最多两位小数，例如 130.5':'')
async function makeOperator(){const w=wo.value!;try{const it=await createInbox({employee:'planner',kind:'mobile-weigh',module:'work_report',title:(actName.value+' · '+w.code+' '+proc.value).slice(0,80),sourceKey:'weigh:'+w.code+':'+proc.value+':'+good.value,context:'一线手机端',
  preview:{rows:[{label:'工单',value:w.code+' · '+w.productName},{label:'工序',value:proc.value},{label:'良品',value:qty(Number(good.value),unit.value)},{label:'时间',value:'确认时取当前时间'},{label:'确认后执行',value:'原型演示：只记「原型演示，未执行」，不写报工，不扣额度'}],impact:['这道工序多一笔称重'],basis:['黑湖工单快照'],notDone:['称重报工写入还没接上，本次只演示预览']},
  action:{type:'demo',reason:'称重报工写入还没接上，只演示预览'}});itemId.value=it.id;stage.value='preview'}catch(e){err.value=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}}
async function makeKeeper(w:WorkOrder){try{const s=src()!,r=rushCheck(s,C.inv,{productCode:w.productCode,qty:0,date:today(),today:today()})
  const it=await createInbox({employee:'keeper',kind:'mobile-stockcheck',module:'warehouse',title:('核实原料库存 · '+w.productName).slice(0,80),sourceKey:'stockcheck:'+w.productCode+':'+today(),context:'一线手机端',
    preview:{rows:[{label:'产品',value:w.productName+'（'+w.productCode+'）'},{label:'原料',value:r.lines.map(l=>l.name).join('、')},{label:'库存快照',value:'全部为 0（'+r.snapshot.inventory+'）'},{label:'近 14 天生产',value:r.history.days.length+' 天'},{label:'确认后执行',value:'建协同任务交「仓库」核实，截止 '+realToday()+'；记「已交办」，仓库办完才算已办成'}],impact:['只建核实待办，不改库存'],basis:['黑湖库存快照、黑湖工单'],notDone:['不补库存数（来源原则：补充由人填）']},
    action:{type:'e-task',payload:{type:'shortage',fields:{material:r.lines[0]?.name||w.productName},title:('库存核实 · '+w.productName).slice(0,60),description:'数字仓管：'+w.productName+' 的 '+r.lines.length+' 项原料在黑湖库存快照里都是 0，近 14 天生产 '+r.history.days.length+' 天。请到库核实实际库存，必要时在库存页「补充」。',assignee:{kind:'role',role:'仓库'},due:realToday(),priority:'中',related:[{type:'product',code:w.productCode}],origin:{kind:'draft',ref:'数字仓管 · 手机端',draftBy:'数字仓管'}}}})
  itemId.value=it.id;stage.value='preview'}catch(e){err.value=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}}
async function makeInspector(){const w=wo.value!;try{const it=await createInbox({employee:'inspector',kind:'mobile-ng',module:'quality',title:('不合格 · '+w.code+' '+proc.value).slice(0,80),sourceKey:'ng:'+w.code+':'+proc.value+':'+good.value,context:'一线手机端',
  preview:{rows:[{label:'工单',value:w.code+' · '+w.productName},{label:'工序',value:proc.value},{label:'不合格数量',value:qty(Number(good.value),unit.value)},{label:'确认后执行',value:'建「质量异常」协同任务交质量经理；记「已交办」，办完才算已办成'}],impact:['不改报工、不判批次'],basis:['黑湖工单快照'],notDone:['检验单写入本原型手机端还没接']},
  action:{type:'e-task',payload:{type:'quality',fields:{failItems:proc.value+' 现场不合格',qty:Number(good.value),unit:unit.value},title:('质量异常 · '+w.code+' '+proc.value).slice(0,60),description:'一线质检员在手机端报：'+w.productName+' '+w.code+' '+proc.value+' 不合格 '+good.value+' '+unit.value+'。',assignee:{kind:'role',role:'质量经理'},due:realToday(),priority:'高',related:[{type:'project',code:w.code}],origin:{kind:'draft',ref:'数字质检员 · 手机端',draftBy:'数字质检员'}}}})
  itemId.value=it.id;stage.value='preview'}catch(e){err.value=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}}
const item=computed(()=>C.state?.inbox.find(x=>x.id===itemId.value))
function ask(){const t=input.value.trim();if(!t)return;msgs.value.push(user(t),...reply(crewId.value,t,{src:src(),inv:C.inv,perms:perms()}));input.value='';stage.value='chat'}
</script>
<template><div class="phone-wrap">
<div class="phone" role="region" aria-label="一线手机端（375 宽）">
  <div class="bar"><CrewAvatar :name="d.name" :state="item?.status==='pending'?'waiting':'idle'" :size="40"/><div><b>{{d.name}}</b><div class="muted">{{DEMO_ROLES[role].label.replace('（演示）','')}}的入口</div></div></div>
  <div class="scroll">
    <template v-if="!enabledHere"><p class="todo" role="status"><b>本厂没有启用{{d.name}}</b><span>这个岗位暂时没有数字员工入口。需要的话请找管理员在「数字班组 · 班组首页」底部启用。</span></p></template>
    <template v-else-if="stage==='home'">
      <button class="mic" type="button" data-testid="voice-first-container" title="原型不录音：按住说话在正式 App 里接语音识别">按住说话</button>
      <p class="muted" style="text-align:center;margin-bottom:12px">也可以点下面的事直接办（今天 = 快照日 {{today()}}）</p>
      <p v-if="role!=='keeper'&&todoTotal>todos.length" class="muted">快照日共 {{todoTotal}} 张工单，这里只列前 {{todos.length}} 张；其余在「生产工单」里看</p>
      <p v-if="!todos.length" class="muted">{{C.ws==='ydwm'?'味美工作区没有演示数据，一线入口的演示在六膳门工作区。':src()?'今天没有要办的。':'正在读取数据…'}}</p>
      <button v-for="t in todos" :key="t.title+t.sub" class="todo big" type="button" style="text-align:left" @click="start(t.wo)"><b>{{t.title}}</b><span>{{t.sub}}</span></button>
    </template>
    <template v-else-if="stage==='form'&&wo">
      <p class="muted">{{actName}} — {{wo.productName}}（{{wo.code}}）</p>
      <label style="display:grid;gap:6px;margin:12px 0"><span>工序</span><select v-model="proc" style="height:48px;font-size:16px"><option v-for="p in procs" :key="p">{{p}}</option></select></label>
      <label style="display:grid;gap:6px;margin:12px 0"><span>{{role==='inspector'?'不合格':'良品'}}{{unit==='kg'?'重量':'数量'}}（{{unit}}）</span><input v-model="good" class="num-big" inputmode="decimal" style="height:56px" aria-label="重量"></label>
      <p v-if="goodErr" class="no">{{goodErr}}</p>
      <button class="btn primary big" type="button" :disabled="!good||!!goodErr" @click="role==='inspector'?makeInspector():makeOperator()">下一步：看一眼再确认</button>
      <button class="btn link" type="button" style="margin-top:12px" @click="stage='home'">返回</button>
    </template>
    <template v-else-if="stage==='preview'&&item">
      <CrewPreview :item="item"/>
      <p v-if="item.status!=='pending'" style="margin-top:12px;font-size:16px"><span class="chip" :class="itemStatus(item).tone">{{itemStatus(item).label}}</span> {{item.result?.ref?.label||item.result?.note}}</p>
      <button class="btn big" type="button" style="margin-top:12px" @click="stage='home';itemId=''">回到今天要办的</button>
    </template>
    <template v-else-if="stage==='chat'">
      <template v-for="m in msgs" :key="m.id"><div v-if="m.role==='user'" class="msg-user" style="margin-left:auto">{{m.text}}</div><div v-else class="msg-ai">{{m.text}}</div></template>
      <button class="btn big" type="button" @click="stage='home'">回到今天要办的</button>
    </template>
    <p v-if="err" class="error" role="alert">{{err}}</p>
  </div>
  <div v-if="enabledHere" class="dock"><div class="row"><input v-model="input" placeholder="打字说要办的事" aria-label="打字输入" @keydown.enter="ask"><button class="btn primary" type="button" :disabled="!input.trim()" @click="ask">发送</button></div></div>
</div>
<div class="ux-notes">
  <h2>登录人岗位（演示）</h2>
  <p style="display:flex;gap:8px;flex-wrap:wrap;margin:8px 0"><button v-for="r in ROLES" :key="r" class="btn" :class="{primary:role===r}" type="button" @click="setRole(r,false)">{{DEMO_ROLES[r].label}}</button></p>
  <p class="muted">岗位 → 唯一入口：操作员 → 数字计划员；仓管 → 数字仓管；质检员 → 数字质检员（按权限配置映射，见功能归属页）。演示岗位的权限是原型假设，不是真实账号权限。</p>
  <h2 style="margin-top:16px">这一屏的设计回应（摘要）</h2>
  <ul style="padding-left:18px"><li>不让一线在五位里选：只露一个入口（F1）</li><li>进屏默认语音大按钮，键盘是次要入口（H1）</li><li>「今天要办的」直接列真实工单 / 待核实项，点一下进去，不用找菜单（F2）</li><li>每屏一个主按钮；重量大字输入，格式错在字段旁说怎么改，保留已填（F3、F4）</li><li>先看一眼再确认：确认卡每行都显示；称重写入未接时明说「原型演示，未执行」（F5）</li></ul>
</div>
</div></template>
