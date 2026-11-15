<script setup lang="ts">
// 结构 2 · 单个数字员工的工作台：左边对话，右边它正在办和已办完的任务（办理中 / 等你确认 / 已办成 / 没办成 / 结果不明）。
// 结构 6 的落点：从现有页面「交给数字 X」带上下文进来（ctxKind/ctxId/ctxLabel/back），办完「回到原页面」。
import {computed,nextTick,reactive,ref,watch} from 'vue'
import {S,navigate} from '../frame/store'
import {C,createInbox,perms,src,itemStatus,type InboxItem} from './crew-store'
import {crewById,crewPerm,CREW,type CrewId} from './crew-config'
import {welcome,reply,user,type Msg,type Opt} from './crew-chat'
import {rushDraft,yieldDraft} from './crew-drafts'
import {pct,qty,bjDate,type YieldSeries} from './crew-facts'
import CrewAvatar from './CrewAvatar.vue'
import CrewPreview from './CrewPreview.vue'
// 对话按员工保存在本页内存里（切页面再回来还在；刷新清空，不进保存区）
const THREADS:Record<string,Msg[]>=reactive({})
// 反馈（ai-chat-standard F4）：只给完成的回答；澄清、写确认卡、失败都不给。原型只记在本页内存里
const FB:Record<string,'up'|'down'>=reactive({})
const canFeedback=(m:Msg)=>m.role==='assistant'&&m.kind==='answer'&&!m.options?.length&&m.card?.type!=='inbox'
const id=computed(()=>(crewById(S.query.get('id')||'')?.id||'planner') as CrewId)
const d=computed(()=>crewById(id.value)!)
const ctx=computed(()=>S.query.get('ctxKind')?{kind:S.query.get('ctxKind')!,id:S.query.get('ctxId')||'',label:S.query.get('ctxLabel')||'',back:S.query.get('back')||''}:null)
const msgs=computed(()=>THREADS[id.value]||[])
const input=ref(''),busy=ref(false),slow=ref(false),thread=ref<HTMLElement>()
function ensure(){if(!THREADS[id.value]){THREADS[id.value]=[welcome(id.value,{src:src(),inv:C.inv,perms:perms(),ctx:ctx.value})];if(ctx.value&&ctx.value.label)input.value=prefill()}}
function prefill(){const c=ctx.value!;if(id.value==='planner')return '围绕「'+c.label+'」：周五要加 2 吨，排得进吗？';if(id.value==='accountant')return c.label+' 的出成率是不是掉了？';return '帮我看看「'+c.label+'」'}
watch([id,()=>S.source,()=>C.state],ensure,{immediate:true})
const items=computed(()=>(C.state?.inbox||[]).filter(x=>x.employee===id.value).slice().reverse())
const open=ref('')
function nearBottom(){const el=thread.value;return !el||el.scrollHeight-el.scrollTop-el.clientHeight<120}
async function scroll(force=false){const was=nearBottom();await nextTick();if(force||was)thread.value?.scrollTo({top:thread.value.scrollHeight})}
async function send(q?:string,label?:string){
  const text=(q??input.value).trim();if(!text||busy.value)return
  if(text.startsWith('__draft_'))return draft(text)
  THREADS[id.value].push(user(label||text));input.value='';busy.value=true;slow.value=false;scroll(true)
  const t=setTimeout(()=>{slow.value=true},3000)
  await new Promise(r=>setTimeout(r,250))
  try{THREADS[id.value].push(...reply(id.value,text,{src:src(),inv:C.inv,perms:perms(),ctx:ctx.value}))}
  catch(e){THREADS[id.value].push({id:'e'+Date.now(),role:'assistant',kind:'error',text:'没算出来：'+(e instanceof Error?e.message:'出错')+'。没有执行任何写操作。'})}
  finally{clearTimeout(t);busy.value=false;scroll()}
}
// 写操作：只生成预览进「等我确认」，对话里显示同一张确认卡
async function draft(kind:string){
  const last=[...msgs.value].reverse().find(m=>m.card&&(m.card.type==='rush'||m.card.type==='yield'));if(!last)return
  const label=kind==='__draft_rush'?'生成安排草案':'生成核对待办'
  THREADS[id.value].push(user(label));scroll(true)
  if(crewPerm(d.value,perms())==='-'){THREADS[id.value].push({id:'p'+Date.now(),role:'assistant',kind:'error',text:'你没有'+d.value.name+'的权限，不能生成待确认事项。没有执行任何写操作。'});return}
  try{
    const c=ctx.value?'从「'+ctx.value.label+'」带入':''
    const dr=last.card!.type==='rush'?rushDraft(last.card!.data as never,c):yieldDraft(src()!,(last.card!.data as {series:YieldSeries}).series,c)
    const it=await createInbox(dr)
    THREADS[id.value].push({id:'i'+Date.now(),role:'assistant',kind:'draft',text:'预览已生成，放进「等我确认」。下面每一行都是确认后要写入的内容；有权限的人确认后才建协同任务。',card:{type:'inbox',data:it.id}})
  }catch(e){const ex=(e as Error).message;THREADS[id.value].push({id:'x'+Date.now(),role:'assistant',kind:'error',text:'没生成：'+ex+'。没有执行任何写操作。'})}
  scroll()
}
const itemOf=(iid:unknown)=>C.state?.inbox.find(x=>x.id===iid) as InboxItem|undefined
function newChat(){if(busy.value)return;delete THREADS[id.value];ensure()}
function back(){const b=ctx.value?.back;if(!b)return;const [p,q]=b.split('?');navigate(p,Object.fromEntries(new URLSearchParams(q||'')))}
function to(p:string){const q:Record<string,string>={};if(C.ws==='ydwm')q.ws='ydwm';navigate(p,q)}
function switchTo(cid:string){const q:Record<string,string>={id:cid};if(C.ws==='ydwm')q.ws='ydwm';navigate('/crew/worker',q)}
const avatarState=computed(()=>busy.value||items.value.some(x=>x.status==='executing')?'working':items.value.some(x=>x.status==='pending')?'waiting':'idle')
function onKey(e:KeyboardEvent){if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();send()}}
function pick(o:Opt){send(o.question,o.label)}
</script>
<template><div class="bench">
<section class="chat" aria-label="对话">
  <div class="chat-head"><CrewAvatar :name="d.name" :state="avatarState" :size="40"/><div class="grow"><h2>{{d.name}}</h2><small>{{d.duty}}</small></div>
    <select aria-label="切换数字员工" :value="id" @change="switchTo(($event.target as HTMLSelectElement).value)"><option v-for="c in CREW" :key="c.id" :value="c.id">{{c.name}}</option></select>
    <button class="btn" type="button" data-testid="ai-chat-new-conversation" :disabled="busy" @click="newChat">新对话</button></div>
  <div v-if="ctx" class="ctx"><span>从「{{ctx.label||ctx.id}}」带来的上下文</span><button v-if="ctx.back" class="btn link" type="button" @click="back">回到原页面</button></div>
  <div ref="thread" class="thread" data-testid="ai-chat-thread"><div class="thread-col">
    <template v-for="m in msgs" :key="m.id">
      <div v-if="m.role==='user'" class="msg-user" data-testid="ai-chat-user-bubble">{{m.text}}</div>
      <div v-else data-testid="ai-chat-assistant-body">
        <div v-if="m.head" class="msg-ai head" data-testid="ai-chat-conclusion">{{m.head}}</div>
        <div v-else class="msg-ai" :class="{error:m.kind==='error'}">{{m.text}}</div>
        <component :is="m.head?'details':'div'" :class="m.head?'basis':undefined" data-testid="ai-chat-basis"><summary v-if="m.head">看依据（核对明细和数据表）</summary>
        <div v-if="m.head" class="msg-ai">{{m.text}}</div>
        <template v-if="m.card?.type==='rush'">
          <p v-if="(m.card.data as any).result.alt" class="muted" style="font-size:12px;margin-top:6px">下面 {{(m.card.data as any).result.lines.length}} 项都是 1:1、损耗 0，像「任选其一」的替代料：每行是单独用这一项时的需要量，不能相加。</p>
          <table class="mini"><thead><tr><th>BOM 投入</th><th class="num">按用量需</th><th class="num">库存快照</th><th>仓库</th></tr></thead>
            <tbody><tr v-for="l in (m.card.data as any).result.lines" :key="l.code"><td>{{l.name}}</td><td class="num">{{qty(l.need,l.unit||'kg')}}</td><td class="num">{{l.stock==null?'未返回':qty(l.stock,l.stockUnit||l.unit)}}</td><td>{{l.warehouses}}</td></tr></tbody></table>
          <table class="mini"><thead><tr><th>近 14 天生产日</th><th class="num">实际产出</th></tr></thead><tbody><tr v-for="x in (m.card.data as any).result.history.days" :key="x[0]" :class="{hl:(m.card.data as any).result.history.maxDay?.date===x[0]}"><td>{{x[0]}}</td><td class="num">{{qty(x[1],'kg')}}</td></tr></tbody></table>
        </template>
        <template v-if="m.card?.type==='yield'">
          <table class="mini"><thead><tr><th>工单</th><th>计划日</th><th class="num">{{(m.card.data as any).series.fromProc}}</th><th class="num">{{(m.card.data as any).series.toProc}}</th><th class="num">出成</th><th>说明</th></tr></thead>
            <tbody><tr v-for="p in (m.card.data as any).series.points" :key="p.code" :class="{hl:p.code===(m.card.data as any).series.drop?.to.code||p.code===(m.card.data as any).series.drop?.from.code}"><td>{{p.code}}</td><td>{{p.date}}</td><td class="num">{{qty(p.from,p.fromUnit||'kg')}}</td><td class="num">{{qty(p.to,p.toUnit||'kg')}}</td><td class="num">{{pct(p.ratio)}}</td><td>{{p.reason}}</td></tr></tbody></table>
        </template>
        </component>
        <CrewPreview v-if="m.card?.type==='inbox'&&itemOf(m.card.data)" :item="itemOf(m.card.data)!" compact/>
        <p v-if="m.card?.type==='link'"><button class="btn link" type="button" @click="to((m.card.data as any).route)">{{(m.card.data as any).label}}</button></p>
        <div v-if="m.options?.length&&(m.kind!=='notice'||msgs.length===1)" class="opts"><button v-for="(o,i) in m.options" :key="i" type="button" :data-testid="'suggested-action-'+i" @click="pick(o)">{{o.label}}<small v-if="o.desc" class="muted">{{o.desc}}</small></button></div>
        <div v-if="canFeedback(m)" class="follow" data-testid="ai-chat-feedback"><button type="button" :aria-pressed="FB[m.id]==='up'" :class="{on:FB[m.id]==='up'}" aria-label="有帮助" @click="FB[m.id]='up'">👍</button><button type="button" :aria-pressed="FB[m.id]==='down'" aria-label="没帮助" @click="FB[m.id]='down'">👎</button><small v-if="FB[m.id]" class="muted">已记下（原型只记在本页）</small></div>
        <div v-if="m.followUps?.length" class="follow"><button v-for="(o,i) in m.followUps" :key="i" type="button" :data-testid="'ai-chat-followup-'+i" @click="send(o.question,o.label)">{{o.label}}</button></div>
      </div>
    </template>
    <div v-if="busy" class="loading" data-testid="ai-chat-loading-skeleton"><i/><i style="width:80%"/><span>{{slow?'正在处理':'正在理解你的话…'}}</span></div>
  </div></div>
  <div class="composer"><textarea v-model="input" data-testid="ai-chat-input-frame" rows="1" :placeholder="'对'+d.name+'说要办的事（Enter 发送，Shift+Enter 换行）'" aria-label="输入" @keydown="onKey"/><button class="btn primary" type="button" :disabled="busy||!input.trim()" @click="send()">发送</button></div>
</section>
<aside class="panel" aria-label="任务">
  <header><h2>{{d.short}}的任务 <small class="muted">{{items.length}} 件</small></h2><button class="btn link" type="button" @click="to('/crew/inbox')">等我确认</button></header>
  <div class="body tasks">
    <p v-if="!items.length" class="muted">还没有任务。对话里让它办事，生成的预览会出现在这里。</p>
    <div v-for="x in items" :key="x.id" class="task"><div class="t"><b>{{x.title}}</b><span class="chip" :class="itemStatus(x).tone">{{itemStatus(x).label}}</span></div>
      <small class="muted">{{bjDate(x.createdAt)}} · {{x.context||'对话'}}<template v-if="x.result?.ref"> · {{x.result.ref.label}}</template></small>
      <button class="btn link" type="button" style="justify-self:start" @click="open=open===x.id?'':x.id">{{open===x.id?'收起':'看预览与结果'}}</button>
      <CrewPreview v-if="open===x.id" :item="x"/></div>
    <p class="muted">状态口径：等你确认 = 预览已出、没人确认；办理中 = 已确认、正在调接口；已交办 = 已交给人办（建了协同任务），不扣额度；已办成 = 那件事办完、回读核实，扣 1 个额度；没办成 = 接口报错或任务取消，没有执行写操作；结果不明 = 接口返回成功但回读对不上。</p>
  </div></aside>
</div></template>
