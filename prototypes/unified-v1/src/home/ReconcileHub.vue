<script setup lang="ts">
// 第六批（R23）D②：数据与分析「对账（Cretas ↔ 黑湖）」汇总页。只读：从 A（称重报工）、C（入库批次）各自的对账接口取两边记录，
// 用共享 reconcileView 显示四类、找谁、黑湖数据时点；本页不改任何一边，不回写黑湖。
// 各线对账接口约定返回 { asOf, fields:[{key,label}], rows:[{key,label,owner,cretas:{…}|null,blacklake:{…}|null}] }（A 已按此实现）。
import {nextTick,onMounted,ref} from 'vue'
import {reconcile,reconcileView} from '../frame/ai-protocol'
interface Src{line:string;title:string;url:string;owner:string;keyLabel:string;fileName:string}
interface Resp{mismatch?:unknown[];keyLabel?:string;asOf?:string|null;fields?:{key:string;label:string}[];rows?:{key:string;label?:string;owner?:string;cretas:Record<string,unknown>|null;blacklake:Record<string,unknown>|null}[];available?:boolean;reason?:string}
const SOURCES:Src[]=[
  {line:'A',title:'称重报工 ↔ 黑湖报工',url:'/__unified/a/api/weighin-reconcile?ws=lsm',owner:'车间',keyLabel:'工序任务',fileName:'称重报工对账'},
  {line:'C',title:'入库批次 ↔ 黑湖入库单',url:'/__lsm/supply/batch-reconcile',owner:'仓库',keyLabel:'黑湖入库单行',fileName:'入库批次对账'}
]
const state=ref<Record<string,{status:'loading'|'ok'|'na'|'error';msg?:string;counts?:Record<string,number>;asOf?:string|null;mismatch?:number}>>({})
const hosts=ref<Record<string,HTMLElement|null>>({})
const session=()=>{const q=new URLSearchParams(location.hash.split('?')[1]||'');let s=q.get('acceptanceSession')||'';try{if(!s&&window.top)s=new URLSearchParams(window.top.location.search).get('acceptanceSession')||new URLSearchParams((window.top.location.hash.split('?')[1])||'').get('acceptanceSession')||''}catch{/* 读不到顶层地址 */}return /^test-[a-z0-9-]{8,64}$/.test(s)?s:''}
const unitOf=(label:string)=>(label.match(/（([^）]+)）$/)||[])[1]||''
async function load(src:Src){state.value[src.line]={status:'loading'};const s=session();
  try{const r=await fetch(src.url+(s?(src.url.includes('?')?'&':'?')+'acceptanceSession='+encodeURIComponent(s):''),{headers:s?{'x-acceptance-session':s}:{}});const b=await r.json().catch(()=>({}));
    if(r.status===404||r.status===503||(b&&b.message==='未找到')||(b&&b.message==='未知接口')){state.value[src.line]={status:'na',msg:src.line+' 线的对账接口还没接上（'+src.url.split('?')[0]+'），本页不推算。'};return}
    if(!r.ok||!b.success)throw Error(b.message||'读取失败');const d=b.data as Resp;
    if(d.available===false){state.value[src.line]={status:'na',msg:d.reason||'当前工作区没有这项对账'};return}
    const rows=d.rows||[],fields=(d.fields||[]).map(f=>({key:f.key,label:f.label.replace(/（[^）]+）$/,''),unit:unitOf(f.label),digits:f.key==='count'?0:2,cretas:(x:Record<string,unknown>)=>x[f.key],blacklake:(x:Record<string,unknown>)=>x[f.key]}))
    const opts={title:src.title,keyLabel:d.keyLabel||src.keyLabel,fileName:src.fileName,asOf:d.asOf||null,
      cretas:rows.filter(x=>x.cretas).map(x=>({...x.cretas,__k:x.key,__l:x.label||''})),blacklake:rows.filter(x=>x.blacklake).map(x=>({...x.blacklake,__k:x.key,__l:x.label||''})),
      key:(x:Record<string,unknown>)=>x.__k,label:(x:Record<string,unknown>|null)=>String(x?.__l||''),fields,
      owner:(row:{key:string;state:string})=>{const o=rows.find(x=>x.key===row.key)?.owner||src.owner;return row.state==='blacklakeOnly'?o+'（Cretas 没有记录）':row.state==='cretasOnly'?o+'（黑湖没有记录）':o}}
    const counts=reconcile(opts as never).counts;state.value[src.line]={status:'ok',counts,asOf:d.asOf||null,mismatch:Array.isArray(d.mismatch)?d.mismatch.length:0};await nextTick();const el=hosts.value[src.line];if(el){el.innerHTML='';reconcileView(el,opts as never)}}
  catch(e){state.value[src.line]={status:'error',msg:e instanceof Error?e.message:'读取失败'}}}
function reloadAll(){for(const s of SOURCES)load(s)}
onMounted(reloadAll)
const C=(k:string)=>({diff:'有差额',cretasOnly:'只在 Cretas',blacklakeOnly:'只在黑湖',match:'一致'} as Record<string,string>)[k]||k
</script>
<template><div class="d-recon-hub">
  <div class="page-heading"><div><p>六膳门 · 数据与分析</p><h1>对账（Cretas ↔ 黑湖）</h1></div><div class="actions"><button class="d-btn" @click="reloadAll">重新读取</button></div></div>
  <p class="d-note">称重报工、入库批次以 Cretas 为主来源（R23），黑湖值只作对照。这里汇总两类对账：有差额、只在一边的记录写明找谁核对；本页不改任何一边，不回写黑湖。黑湖数据每天 08:00 采集一次，以各表上写的时点为准。</p>
  <div class="d-recon-sum"><div v-for="s in SOURCES" :key="s.line" class="d-recon-card"><strong>{{s.title}}</strong>
    <template v-if="state[s.line]?.status==='ok'"><span v-for="k in ['diff','cretasOnly','blacklakeOnly','match']" :key="k" :class="'k-'+k">{{C(k)}} <b>{{state[s.line]?.counts?.[k]??0}}</b></span><small v-if="state[s.line]?.mismatch" class="muted">另有单位不同 {{state[s.line]?.mismatch}} 条（{{s.line}} 线单独列出核对，不进上面四类）</small></template>
    <span v-else-if="state[s.line]?.status==='loading'" class="muted">正在读取…</span><span v-else class="muted">{{state[s.line]?.msg}}</span></div></div>
  <section v-for="s in SOURCES" :key="s.line" class="d-recon-sec"><div v-if="state[s.line]?.status==='ok'" :ref="el=>hosts[s.line]=el as HTMLElement"/><p v-else-if="state[s.line]?.status==='error'" class="d-err">{{s.title}}：读取失败（{{state[s.line]?.msg}}），本页不推算。</p></section>
</div></template>
<style scoped>
.d-recon-hub .d-note{font-size:12px;color:#5b6475;margin:0 0 12px}.d-btn{background:#fff;border:1px solid #d9e2eb;border-radius:6px;padding:6px 12px;font-size:12px;cursor:pointer}
.d-recon-sum{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:10px;margin-bottom:14px}.d-recon-card{background:#fff;border:1px solid #e0e6ee;border-radius:9px;padding:12px 14px;display:flex;flex-wrap:wrap;gap:6px 12px;align-items:baseline;font-size:12px}.d-recon-card strong{flex-basis:100%;font-size:14px}
.d-recon-card b{font-size:16px;margin-left:2px}.k-diff b{color:#885910}.k-cretasOnly b{color:#205c90}.k-blacklakeOnly b{color:#5b6475}.k-match b{color:#24724f}.muted{color:#7b8c9f}
.d-recon-sec{margin-bottom:14px}.d-err{font-size:12px;color:#b3261e}
</style>
