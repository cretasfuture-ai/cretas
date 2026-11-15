<script setup lang="ts">
// 六膳门工作区内容根：只负责路由分发、来源状态条、提示和两个共享浮层（记录详情、AI 悬浮窗）。
// 侧栏 / 顶栏 / 菜单搜索 / 工作区切换由统一外壳（public/shell.js）提供，这里不再画第二套。
import {computed,defineAsyncComponent,onMounted,ref} from 'vue'
import UApp from '@nuxt/ui/components/App.vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {S,load,syncRoute,fmtDate,inspect,openMenu,api} from './store'
import type {Source,Workspace} from './types'
import {routeInfo} from './routes'
import type {MenuEntry} from './types'
import AiFloat from './AiFloat.vue'
import PendingPage from './PendingPage.vue'
import ProductionList from '../production/ProductionList.vue'
import RecordDetail from '../production/RecordDetail.vue'
const FactoryCanvas=defineAsyncComponent(()=>import('../production/FactoryCanvas.vue'))
const LsmCost=defineAsyncComponent(()=>import('../cost/LsmCost.vue'))
const SupplyRecords=defineAsyncComponent(()=>import('../supply/SupplyRecords.vue'))
const HomeOverview=defineAsyncComponent(()=>import('../home/HomeOverview.vue'))
const CustomReports=defineAsyncComponent(()=>import('../home/CustomReports.vue'))
const FinanceLsm=defineAsyncComponent(()=>import('../home/FinanceLsm.vue'))
const AiReports=defineAsyncComponent(()=>import('../home/AiReports.vue'))
const OaCenter=defineAsyncComponent(()=>import('../home/OaCenter.vue'))
const ReconcileHub=defineAsyncComponent(()=>import('../home/ReconcileHub.vue'))
// F 线数字班组：CrewModule.vue 由 F 新建；文件还没有时显示待整理页，不让构建失败
const crewMods=import.meta.glob('../crew/CrewModule.vue')
// G 线骨架页：同样按需加载，文件不在时显示待整理页
const gMods=import.meta.glob('../frame-g/GModule.vue')
const GModule=defineAsyncComponent(()=>(gMods['../frame-g/GModule.vue']?gMods['../frame-g/GModule.vue']():Promise.resolve(PendingPage)) as Promise<never>)
const CrewModule=defineAsyncComponent(()=>(crewMods['../crew/CrewModule.vue']?crewMods['../crew/CrewModule.vue']():Promise.resolve(PendingPage)) as Promise<never>)
const EModule=defineAsyncComponent(()=>import('../collab/EModule.vue'))
const ws=computed(()=>S.query.get('ws')==='ydwm'?'ydwm':'lsm')
const info=computed(()=>routeInfo(S.path,S.menu.find(m=>m.path===S.path)?.title,ws.value))
const menuEntry=computed<MenuEntry>(()=>({path:S.path,title:S.menu.find(m=>m.path===S.path)?.title||info.value.title,group:'六膳门'}))
async function refreshSource(){S.busy=true;S.error='';try{const b=await api<{source:Source;workspace:Workspace}>('/api/refresh',{});S.source=b.source;S.workspace=b.workspace;S.notice='已读取现有OpenAPI采集器最新成功快照；原始业务未修改。'}catch(e){S.error=e instanceof Error?e.message:'刷新失败'}finally{S.busy=false}}
// A 线：「重新读取 / 刷新来源」合成一个「更新来源快照」，两种方式收进工具菜单
function sourceMenu(ev:MouseEvent){const C=(window as unknown as {CretasUI?:{menu:(o:{anchor:HTMLElement;items:{label:string;description?:string;onSelect:()=>void}[]})=>void}}).CretasUI;if(!C)return;C.menu({anchor:ev.currentTarget as HTMLElement,items:[{label:'重新读取本机快照',description:'读本机已保存的黑湖只读快照，不访问黑湖',onSelect:()=>{load()}},{label:'从黑湖采集器取最新',description:'调用六膳门既有 OpenAPI 采集器取最新成功快照（只读）；失败保留原快照',onSelect:()=>{refreshSource()}}]})}
// 第七批 · 第二次构建（制造旧分块）（A 报：同一页切到对账汇总空白，整页刷新才正常）：页面开着时别处重新构建，旧页面引用的分块文件名已换，懒加载失败会留下空白。
// 捕获到「动态加载模块失败」就整页重载一次（60 秒内只重载一次，避免循环）。
function reloadOnStaleChunk(){try{const k='uni-chunk-reload',last=Number(sessionStorage.getItem(k)||0);if(Date.now()-last<60000)return;sessionStorage.setItem(k,String(Date.now()))}catch{/* 存储不可用时直接重载一次 */}location.reload()}
function staleChunkRejected(ev:PromiseRejectionEvent){const m=String((ev.reason&&ev.reason.message)||ev.reason||'');if(/dynamically imported module|Importing a module script failed|Unable to preload/i.test(m)){ev.preventDefault();reloadOnStaleChunk()}}
onMounted(async()=>{window.addEventListener('vite:preloadError',ev=>{ev.preventDefault();reloadOnStaleChunk()});window.addEventListener('unhandledrejection',staleChunkRejected);window.addEventListener('hashchange',syncRoute);syncRoute();if(S.path==='/__ai')return;try{const m=await (await fetch('/__unified/menu.json')).json();S.menu=m.groups.flatMap((g:{label:string;entries:{label:string;members:{route:string;label:string;reason?:string}[]}[]})=>g.entries.flatMap(e=>e.members.map(x=>({path:x.route,title:x.label,group:g.label+' / '+e.label,reason:x.reason}))))}catch{/* 菜单标题缺失时使用路由默认标题 */}await load()})
</script>
<template><UApp><AiFloat v-if="S.path==='/__ai'" embedded/><template v-else><main class="lsm-main" :data-view="info.view">
<EModule v-if="info.view==='e'"/>
<CrewModule v-else-if="info.view==='crew'"/>
<GModule v-else-if="info.view==='g'"/>
<template v-if="ws==='lsm'&&!['home','supply','reports','aireport','oa','e','cost','finance','reconcile','crew','g'].includes(info.view)"><div class="page-heading"><div><p>六膳门 · {{info.sub}}</p><h1>{{info.title}}</h1></div><div class="actions"><UButton color="neutral" variant="outline" size="sm" :loading="S.busy" @click="sourceMenu">更新来源快照</UButton><span class="help" tabindex="0" title="重新读取本机快照：读本机已保存的黑湖只读快照，不访问黑湖。从黑湖采集器取最新：调用六膳门既有 OpenAPI 采集器取最新成功快照（只读，不修改黑湖业务）；失败时保留原快照。两者都不读取味美数据。">?</span></div></div>
<div class="source-strip"><span class="live-dot"/>黑湖 OpenAPI · 已核验来源 <span>记录 {{fmtDate(S.source?.checkedAt)}}</span><span>工艺 / BOM {{fmtDate(S.source?.referenceAt)}}</span><span class="revision" :title="S.source?.revision">{{S.source?.revision.slice(0,12)}}</span></div>
<details v-if="S.source?.sourceGaps?.length" class="source-gap"><summary>来源缺口 {{S.source.sourceGaps.length}} 条 · 保留未返回记录</summary><p>此前可见的工单本次未返回，不等于已删除、已取消或数量为 0；未并入当前工单统计。</p><p v-for="g in S.source.sourceGaps" :key="String(g.id)">工单 {{g.projectCode}} · 来源ID {{g.id}} · 最后可见 {{fmtDate(g.lastSeenAt)}}</p></details></template>

<p v-if="S.error" role="alert" class="error">{{S.error}}</p><p v-if="S.notice" role="status" class="notice">{{S.notice}} <button aria-label="关闭提示" @click="S.notice=''">×</button></p>
<template v-if="S.source&&!['e','crew','g'].includes(info.view)">
<HomeOverview v-if="info.view==='home'" :source="S.source" :entry="menuEntry" @open="openMenu" @inspect="inspect"/>
<ProductionList v-else-if="info.view==='production'&&info.list" :kind="info.list"/>
<FactoryCanvas v-else-if="info.view==='canvas'" :source="S.source" :initial-order="S.query.get('project')||undefined" @inspect="inspect"/>
<LsmCost v-else-if="info.view==='cost'"/>
<CustomReports v-else-if="info.view==='reports'"/>
<ReconcileHub v-else-if="info.view==='reconcile'"/>
<AiReports v-else-if="info.view==='aireport'"/>
<OaCenter v-else-if="info.view==='oa'"/>
<template v-else-if="info.view==='finance'"><div class="page-heading"><div><p>六膳门 · 数据与分析</p><h1>经营分析</h1></div></div><FinanceLsm/></template>
<SupplyRecords v-else-if="info.view==='supply'" :source="S.source" :entry="menuEntry" @open="openMenu" @inspect="inspect"/>
<PendingPage v-else/>
</template>
<p v-else-if="S.busy&&!['e','crew','g'].includes(info.view)" class="empty">正在读取六膳门来源…</p>
<template v-if="!['e','crew','g'].includes(info.view)"><p v-if="ws==='ydwm'" class="footnote">味美工作区 · 本页由统一框架打开；保存写入独立验收会话或统一入口自己的保存区，不写账号工作区。</p>
<p v-else class="footnote">六膳门工作区 · 黑湖只读来源；工单、任务和报工数量分层核对；BOM 标准数量不代表实际领料。保存只写本机原型（Cretas 主来源或补充），不回写黑湖，不读写味美数据。</p></template>
</main><RecordDetail/><AiFloat v-if="info.view!=='crew'"/></template></UApp></template>
