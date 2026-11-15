<script setup lang="ts">
// 第八批 · BOM 调价建议（G-gap-v2：/production/bom/price-adjustments）。
// 原料价变动 → 哪些产品的成本受影响、影响多少 → 调价建议。只作建议：本页没有任何保存、批准、改价按钮，不回写黑湖、不改价目表或配方行单价。
// 六膳门：接口 /__lsm/product-cost/bom-price（只读），采购价、客户价目表、工单领料价来自黑湖，每个数字标来源；时间一律北京时间。
// 味美：编译页里没有 BOM 调价建议页，这里只给精简视图（指向味美已有的物料均价趋势、价格表、成本分析），不读六膳门数据。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import UBadge from '@nuxt/ui/components/Badge.vue'
import {bjTime,fmtCost,fmtPct} from './cost-review'
const props=defineProps<{ws:'lsm'|'ydwm'}>()
interface Chg{state:string;base?:number;latest?:number;delta?:number;pct?:number;latestDay?:string;baseFrom?:string;baseTo?:string;days?:number;series?:{day:string;price:number;qty:number;n:number}[]}
interface Mat{key:string;code:string;name:string;unit:string|null;kind:string;purchase:Chg;issue:Chg;basis:string|null;state:string;base:number|null;latest:number|null;delta:number|null;pct:number|null;latestDay:string|null;vendors:string[];zeroPurchase:number}
interface Imp{materialKey:string;code:string;name:string;unit:string|null;usagePerUnit:number;orders:number;orderCodes:string[];state:string;kind:string;delta:number|null;pct:number|null;impact:number|null;chain:number|null;note:string|null}
interface Sug{customer:string;price:number|null;priceAt?:string|null;keepMargin?:number;keepMarginPct?:number;keepRatio?:number|null;keepRatioPct?:number|null;ratioNow?:number|null;ratioAfter?:number|null;costChange?:number;basis?:string|null;unit?:string;reason:string|null}
interface Prod{key:string;productCode:string;product:string;unit:string;orders:number;unitCost:number|null;unitCostSource:string|null;impacts:Imp[];changedCount:number;chainCount:number;chainImpact:number|null;chainImpactPct:number|null;outlierCount:number;totalImpact:number|null;totalImpactPct:number|null;suggestions:Sug[];priceNote:string|null}
interface Sale{customer:string;price:number|null;priceAt:string|null;unit:string|null;ok:boolean;reason:string|null}
interface Data{revision:string;checkedAt:string;sourceCheckedAt:string;sources:Record<string,string>;rule:string;summary:Record<string,number>;materials:Mat[];products:Prod[];usage:Record<string,{productKey:string;productCode:string;product:string;unit:string;usagePerUnit:number;orders:number;unitCost:number|null}[]>;salePrices:Record<string,Sale[]>;gaps:{code:string;text:string}[]}
type Tab='advice'|'materials'|'products'|'whatif'|'gaps'
const tab=ref<Tab>('advice'),data=ref<Data|null>(null),busy=ref(false),error=ref(''),open=ref(''),whatKey=ref(''),whatPrice=ref('')
function session(){try{const own=new URL(location.href).searchParams.get('acceptanceSession');if(own)return own;return window.top&&window.top!==window?new URL(window.top.location.href).searchParams.get('acceptanceSession'):null}catch{return null}}
async function load(){if(props.ws!=='lsm')return;busy.value=true;error.value='';try{const s=session(),r=await fetch('/__lsm/product-cost/bom-price',{headers:s?{'x-acceptance-session':s}:{}}),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'读取未完成');data.value=b.data}catch(e){error.value=e instanceof Error?e.message:'读取失败';data.value=null}finally{busy.value=false}}
onMounted(()=>{try{const t=new URL(location.href).hash.match(/[?&]tab=(\w+)/)?.[1] as Tab|undefined;if(t&&['advice','materials','products','whatif','gaps'].includes(t))tab.value=t}catch{/* 无 */}void load()})
watch(()=>props.ws,()=>{data.value=null;void load()})
const f=(n:number|null|undefined,d=2)=>n==null?'—':n.toLocaleString('zh-CN',{minimumFractionDigits:d,maximumFractionDigits:d})
const sgn=(n:number|null|undefined,d=2)=>n==null?'—':(n>0?'+':'')+f(n,d)
const KIND:Record<string,string>={purchased:'外购（有采购订单）',semi:'内部半成品',raw:'原料 / 包材'}
const STATE:Record<string,{t:string;c:'warning'|'error'|'neutral'|'info'|'success'}>={changed:{t:'价格有变动',c:'warning'},flat:{t:'价格没变',c:'neutral'},single:{t:'只有 1 个价格点',c:'info'},outlier:{t:'价格不稳定，先核价',c:'error'},internal:{t:'上游半成品成本传导',c:'info'},'usage-odd':{t:'用量口径异常',c:'error'},none:{t:'没有价格',c:'neutral'}}
const BASIS:Record<string,string>={purchase:'黑湖采购订单',issue:'黑湖领退料成本单价'}
const sum=computed(()=>data.value?.summary||{})
const advice=computed(()=>(data.value?.products||[]).filter(p=>p.suggestions.some(s=>s.keepMargin!=null)))
const noAdvice=computed(()=>(data.value?.products||[]).filter(p=>(p.changedCount>0||p.chainCount>0)&&!p.suggestions.some(s=>s.keepMargin!=null)))
const affected=computed(()=>(data.value?.products||[]).filter(p=>p.changedCount>0||p.chainCount>0||p.outlierCount>0))
const basisText=(b?:string|null)=>b==='direct'?'原料 / 外购价变动':b==='chain'?'上游半成品成本传导（先核上游工单成本）':b==='both'?'原料价变动＋上游半成品传导':'—'
const whatMat=computed(()=>data.value?.materials.find(m=>m.key===whatKey.value))
watch(data,d=>{if(d&&!whatKey.value)whatKey.value=d.materials.find(m=>m.state==='changed')?.key||d.materials[0]?.key||''})
watch(whatKey,()=>{const m=whatMat.value;whatPrice.value=m?.latest!=null?String(m.latest):''})
const whatRows=computed(()=>{const d=data.value,m=whatMat.value,np=Number(whatPrice.value);if(!d||!m||!whatPrice.value.trim()||!Number.isFinite(np)||np<0)return []
 const baseline=m.latest??m.base;if(baseline==null)return []
 return (d.usage[m.key]||[]).map(u=>{const imp=u.usagePerUnit*(np-baseline),sales=(d.salePrices[u.productCode]||[]).filter(s=>s.ok);return {...u,imp,pct:u.unitCost&&u.unitCost>0?imp/u.unitCost*100:null,sales,baseline}}).sort((a,b)=>Math.abs(b.pct??0)-Math.abs(a.pct??0))})
const whatBase=computed(()=>whatMat.value?.latest??whatMat.value?.base??null)
const ydLinks=[
 {to:'/warehouse/material-price-trend',t:'物料均价趋势',d:'原料价变动：选物料和时间，看均价走势；空数据与单位限制如实显示（味美已有页面）。'},
 {to:'/procurement/price-lists',t:'价格表（精简视图）',d:'采购价格表；原页面可从精简视图头部进入办理。'},
 {to:'/production/bom/ecns',t:'工程变更通知',d:'BOM、工艺改动的申请与影响范围（味美已有页面）。'},
 {to:'/analytics/overview',t:'分析概览（味美编译页）',d:'味美的经营分析概览；页上的成本一栏写明「尚未接入成本数据源」，这里只是入口，没有产品成本数。'}]
// 味美工作区：编译页用顶层的真实路径直接打开（带验收会话），不能用统一框架内的 hash 路由，否则会落到「待逐页整理」占位页
function ydHref(path:string){const s=session();return path+(s?'?acceptanceSession='+encodeURIComponent(s):'')}
function goYd(e:Event,path:string){e.preventDefault();const u=ydHref(path);try{window.top!.location.href=u}catch{location.href=u}}
</script>
<template>
 <section class="nuxt-cost-workspace bom-price" aria-label="BOM调价建议">
  <nav class="nuxt-cost-breadcrumb" aria-label="当前位置"><span>生产管理</span><span>/</span><span>BOM</span><span>/</span><strong>BOM调价建议</strong></nav>
  <header class="nuxt-cost-heading"><div><h1>BOM调价建议</h1>
   <p class="cost-object"><UBadge color="primary" variant="subtle">{{ws==='lsm'?'六膳门':'味美'}}</UBadge><span v-if="data">黑湖成本快照 {{String(data.revision).slice(0,8)}} · 采集于 {{bjTime(data.checkedAt)}}</span><span class="nuxt-muted">原料价变动 → 哪些产品成本受影响、影响多少 → 调价建议</span></p></div>
   <div v-if="ws==='lsm'" class="nuxt-demo-row"><UButton label="刷新数据" :loading="busy" @click="load()"/></div></header>
  <p class="bom-banner" role="note"><strong>只作建议。</strong>本页没有批准、保存或改价按钮：<template v-if="ws==='lsm'">不回写黑湖，不改客户价目表，不改配方行单价。采纳与否由人在黑湖或价目表里自己办理。</template><template v-else>不改价目表，不改配方行单价。</template></p>
  <p v-if="error" role="alert" class="nuxt-error">{{error}}</p>

  <template v-if="ws==='ydwm'">
   <div class="nuxt-cost-card bom-card"><h2>味美 · 精简视图</h2>
    <p>味美编译页里<strong>没有</strong>「BOM调价建议」这一页，也没有可以直接读取「原料价变动 × 产品成本」的接口，本工作区不在这里造数。下面是味美已有、能直接打开的相关页面，价格变动与产品成本影响请在下面这些页面里分别核对：</p>
    <ul class="bom-links"><li v-for="l in ydLinks" :key="l.to"><a :href="ydHref(l.to)" target="_top" @click="goYd($event,l.to)">{{l.t}}</a><span class="nuxt-muted">{{l.d}}</span></li></ul>
    <p class="nuxt-muted">六膳门工作区才有「价格变动 → 产品影响 → 售价建议」的完整计算（数据来自黑湖采购订单、客户价目表和工单领料）。味美要接这一页，需要补采购收货价与配方用量的只读接口，目前没有。</p></div>
  </template>

  <template v-else>
   <p v-if="busy&&!data" class="nuxt-muted">正在读取黑湖成本快照…</p>
   <template v-if="data">
    <div class="bom-metrics" role="group" aria-label="概览">
     <div><b>{{sum.changed}}</b><span>种原料价格有变动</span><small>{{sum.purchasedN}} 种外购 · {{sum.rawN}} 种原料/包材 · 共 {{sum.materialsInUse}} 种在用</small></div>
     <div><b>{{sum.affectedProducts}}</b><span>个产品受原料价影响</span><small>另有 {{sum.chainProducts}} 个产品受上游半成品成本传导</small></div>
     <div><b>{{sum.outlier}}</b><span>种价格不稳定</span><small>先核价，不出建议</small></div>
     <div><b>{{sum.single}}</b><span>种只有 1 个价格点</span><small>无法判断是否变动</small></div>
    </div>
    <nav class="nuxt-demo-row cost-views" aria-label="视图">
     <UButton v-for="t in ([['advice','调价建议'],['materials','原料价变动'],['products','受影响产品'],['whatif','假设试算'],['gaps','缺什么']] as [Tab,string][])" :key="t[0]" :label="t[1]+(t[0]==='gaps'?' '+data.gaps.length:'')" :color="tab===t[0]?'primary':'neutral'" :variant="tab===t[0]?'soft':'ghost'" size="sm" @click="tab=t[0]"/></nav>

    <div v-if="tab==='advice'" class="nuxt-cost-card bom-card">
     <h2>调价建议</h2>
     <p class="nuxt-muted">建议价 ＝ 客户价目表现价 ＋ 每单位成本变化（保持每单位毛利额不变）；另列保持「直接成本占售价比」不变的算法。成本是已识别直接成本（材料＋人工），不含制造费用。成本下降时同一公式给出的是降价空间，不是要求降价。</p>
     <div v-if="advice.length" class="nuxt-cost-scroll"><table class="nuxt-cost-table"><thead><tr><th>产品</th><th>客户<small>{{data.sources.customer}}</small></th><th class="num">现价<small>{{data.sources.customer}}</small></th><th class="num">每单位成本变化<small>黑湖领退料成本单价 × 领料量</small></th><th class="num">建议价<small>保持毛利额</small></th><th class="num">建议价<small>保持成本占比</small></th><th class="num">成本占售价<small>现 → 后</small></th><th>依据</th></tr></thead><tbody>
      <template v-for="p in advice" :key="p.key"><tr v-for="(s,i) in p.suggestions" :key="p.key+i">
       <td v-if="i===0" :rowspan="p.suggestions.length"><strong>{{p.product}}</strong><small class="bom-sub">{{p.productCode}} · 单位 {{p.unit}} · 单位成本 {{f(p.unitCost)}}（{{p.unitCostSource||'来源待核'}}）</small></td>
       <td>{{s.customer}}<small v-if="s.priceAt" class="bom-sub">价目更新 {{String(s.priceAt).slice(0,16)}}（北京时间）</small></td>
       <template v-if="s.keepMargin!=null"><td class="num">{{f(s.price)}} / {{s.unit}}</td><td class="num" :class="(s.costChange||0)>0?'bom-up':'bom-down'">{{sgn(s.costChange,2)}}<small class="bom-sub">{{sgn(s.keepRatioPct,2)}}%</small></td><td class="num"><strong>{{f(s.keepMargin)}}</strong><small class="bom-sub">{{sgn(s.keepMarginPct,2)}}%</small></td><td class="num">{{f(s.keepRatio)}}</td><td class="num">{{s.ratioNow!=null?fmtPct(s.ratioNow)+' → '+fmtPct(s.ratioAfter??null):'—'}}</td><td>{{basisText(s.basis)}}</td></template>
       <td v-else colspan="6" class="nuxt-muted">现价 {{s.price==null?'—':f(s.price)}}：{{s.reason}}</td></tr></template></tbody></table></div>
     <p v-else class="nuxt-muted">当前没有可给出售价建议的产品：{{noAdvice.length?'成本受影响的产品里，没有客户价目表价格或售价单位无法核实（见下）。':'没有产品的成本因原料价变动而受影响。'}}</p>
     <template v-if="noAdvice.length"><h3>成本受影响，但给不出售价建议</h3>
      <ul class="bom-list"><li v-for="p in noAdvice" :key="p.key"><strong>{{p.product}}</strong>（{{p.unit}}）：每 {{p.unit}} 成本 {{sgn((p.totalImpact||0)+(p.chainImpact||0))}}<template v-if="p.unitCost">，约 {{sgn(((p.totalImpact||0)+(p.chainImpact||0))/p.unitCost*100)}}%</template>；<span class="nuxt-muted">{{p.suggestions.find(s=>s.reason)?.reason||p.priceNote||'客户价目表没有该产品的价格行（半成品通常不单独定价）'}}</span></li></ul></template>
     <p class="nuxt-muted bom-foot">数据来源：{{data.sources.purchase}}、{{data.sources.issue}}、{{data.sources.customer}}、{{data.sources.saleUnit}}（只取售价单位）；单位成本为{{data.sources.unitCost}}。时间为北京时间。</p>
    </div>

    <div v-else-if="tab==='materials'" class="nuxt-cost-card bom-card">
     <h2>原料价变动</h2>
     <p class="nuxt-muted">{{data.rule}}</p>
     <div class="nuxt-cost-scroll"><table class="nuxt-cost-table"><thead><tr><th>原料</th><th>类别</th><th class="num">此前均价</th><th class="num">最新价</th><th class="num">变动</th><th>价格依据<small>来源</small></th><th>状态</th><th>明细</th></tr></thead><tbody>
      <template v-for="m in data.materials" :key="m.key"><tr>
       <td><strong>{{m.name}}</strong><small class="bom-sub">{{m.code}} · {{m.unit||'单位未填'}}</small></td><td>{{KIND[m.kind]}}</td>
       <td class="num">{{f(m.base,4)}}</td><td class="num">{{f(m.latest,4)}}<small v-if="m.latestDay" class="bom-sub">{{m.latestDay}}</small></td>
       <td class="num" :class="(m.delta||0)>0?'bom-up':(m.delta||0)<0?'bom-down':''">{{m.delta==null?'—':sgn(m.delta,4)}}<small v-if="m.pct!=null" class="bom-sub">{{sgn(m.pct,2)}}%</small></td>
       <td>{{m.basis?BASIS[m.basis]:'—'}}<small v-if="m.vendors.length" class="bom-sub">{{m.vendors.join('、')}}</small></td>
       <td><UBadge :color="STATE[m.state]?.c||'neutral'" variant="subtle">{{STATE[m.state]?.t||m.state}}</UBadge></td>
       <td><UButton size="xs" color="neutral" variant="ghost" :label="open===m.key?'收起':'价格点'" :aria-expanded="open===m.key" @click="open=open===m.key?'':m.key"/></td></tr>
       <tr v-if="open===m.key" class="bom-detail"><td colspan="8">
        <div class="bom-two"><div><strong>采购订单价（{{data.sources.purchase}}）</strong><p v-if="!m.purchase.series?.length" class="nuxt-muted">{{m.zeroPurchase?'采购订单里该料价格为 0（'+m.zeroPurchase+' 行），不采用。':'没有采购订单。'}}</p><p v-for="s in m.purchase.series||[]" :key="s.day">{{s.day}}　{{f(s.price,4)}}　数量 {{f(s.qty)}}</p></div>
         <div><strong>领料成本单价（{{data.sources.issue}}）</strong><p class="nuxt-muted">库存加权成本，不等于采购价。</p><p v-if="!m.issue.series?.length" class="nuxt-muted">没有价格大于 0 的领料行。</p><p v-for="s in m.issue.series||[]" :key="s.day">{{s.day}}　{{f(s.price,4)}}　领料 {{f(s.qty)}}</p></div></div>
        <p v-if="m.kind==='semi'" class="nuxt-muted">内部半成品的领料价是上游工单成本的传导，不是原料价；只提示、不出调价建议。</p></td></tr></template></tbody></table></div>
    </div>

    <div v-else-if="tab==='products'" class="nuxt-cost-card bom-card">
     <h2>受影响产品</h2>
     <p class="nuxt-muted">用量＝已完工工单的实际领料量 ÷ 工单良品（不是 BOM 标准用量）。影响额＝用量 × 原料价变化。同单位下每单位产出领料超过 3 的，视为口径未对齐，不计入。</p>
     <div v-if="!affected.length" class="nuxt-muted">没有受影响产品。</div>
     <div v-for="p in affected" :key="p.key" class="bom-prod"><div class="nuxt-cost-heading"><strong>{{p.product}}</strong><span class="nuxt-muted">{{p.productCode}} · 单位 {{p.unit}} · 已完工工单 {{p.orders}} 张 · 单位成本 {{f(p.unitCost)}}（{{p.unitCostSource||'来源待核'}}）</span></div>
      <p>每 {{p.unit}} 成本变化：原料价 <b :class="(p.totalImpact||0)>0?'bom-up':'bom-down'">{{p.totalImpact==null?'—':sgn(p.totalImpact)}}</b><template v-if="p.totalImpactPct!=null">（{{sgn(p.totalImpactPct)}}%）</template>；上游半成品传导 <b>{{p.chainImpact==null?'—':sgn(p.chainImpact)}}</b><template v-if="p.chainImpactPct!=null">（{{sgn(p.chainImpactPct)}}%）</template></p>
      <div class="nuxt-cost-scroll"><table class="nuxt-cost-table"><thead><tr><th>原料</th><th class="num">每 {{p.unit}} 用量<small>实际领料</small></th><th class="num">价格变化<small>来源见「原料价变动」</small></th><th class="num">影响额</th><th>状态</th></tr></thead><tbody>
       <tr v-for="r in p.impacts.filter(r=>r.impact!==null||r.chain!==null||r.state==='outlier'||r.state==='usage-odd')" :key="r.materialKey"><td>{{r.name}}<small class="bom-sub">{{r.code}} · {{KIND[r.kind]}}</small></td><td class="num">{{f(r.usagePerUnit,4)}} {{r.unit}}<small class="bom-sub">{{r.orders}} 张工单</small></td><td class="num">{{r.delta==null?'—':sgn(r.delta,4)}}<small v-if="r.pct!=null" class="bom-sub">{{sgn(r.pct)}}%</small></td><td class="num">{{r.impact!=null?sgn(r.impact,4):r.chain!=null?sgn(r.chain,4):'—'}}</td><td><UBadge :color="STATE[r.state]?.c||'neutral'" variant="subtle">{{STATE[r.state]?.t||r.state}}</UBadge><small v-if="r.note" class="bom-sub">{{r.note}}</small></td></tr></tbody></table></div></div>
    </div>

    <div v-else-if="tab==='whatif'" class="nuxt-cost-card bom-card">
     <h2>假设试算</h2>
     <p class="nuxt-muted">选一种原料，填一个假设的新价，看每个产品的成本影响和对应客户价的建议。<strong>只在本页内计算，不保存、不写入任何价格</strong>；刷新页面后清空。</p>
     <div class="bom-form"><label>原料<select v-model="whatKey" aria-label="选择原料"><option v-for="m in data.materials.filter(m=>data!.usage[m.key]?.length)" :key="m.key" :value="m.key">{{m.name}}（{{m.code}}，{{m.unit||'单位未填'}}）</option></select></label>
      <label>假设新价（元 / {{whatMat?.unit||'单位'}}）<input v-model="whatPrice" inputmode="decimal" aria-label="假设新价"/></label>
      <p class="nuxt-muted">对比基准：最新价 {{f(whatBase,4)}}（{{whatMat?.basis?BASIS[whatMat.basis]:'没有价格数据'}}）。</p></div>
     <p v-if="whatBase==null" class="nuxt-muted">这种原料没有价格数据，不能试算。</p>
     <p v-else-if="whatPrice.trim()&&!Number.isFinite(Number(whatPrice))" class="nuxt-error" role="alert">请填写数字。</p>
     <div v-else-if="whatRows.length" class="nuxt-cost-scroll"><table class="nuxt-cost-table"><thead><tr><th>产品</th><th class="num">每单位用量<small>实际领料</small></th><th class="num">每单位成本变化</th><th class="num">占单位成本</th><th>客户价目表建议价（保持毛利额）<small>{{data.sources.customer}}</small></th></tr></thead><tbody>
      <tr v-for="r in whatRows" :key="r.productKey"><td>{{r.product}}<small class="bom-sub">{{r.productCode}} · {{r.unit}}</small></td><td class="num">{{f(r.usagePerUnit,4)}}</td><td class="num" :class="r.imp>0?'bom-up':'bom-down'">{{sgn(r.imp,4)}}</td><td class="num">{{r.pct==null?'单位成本缺':sgn(r.pct)+'%'}}</td>
       <td><span v-if="!r.sales.length" class="nuxt-muted">没有可用的客户价</span><span v-for="s in r.sales" :key="s.customer" class="bom-chip">{{s.customer}}：{{f(s.price)}} → <b>{{f((s.price||0)+r.imp)}}</b></span></td></tr></tbody></table></div>
     <p v-else class="nuxt-muted">没有产品使用这种原料。</p>
    </div>

    <div v-else class="nuxt-cost-card bom-card">
     <h2>缺什么</h2>
     <p class="nuxt-muted">不猜、不造数：下面每一条都会让结论更弱，列明缺的东西和补法。</p>
     <ul class="bom-list"><li v-for="g in data.gaps" :key="g.code">{{g.text}}</li></ul>
     <p class="nuxt-muted bom-foot">需要补的主要有：① 黑湖供应商价目表（现行采购价）；② BOM 标准用量；③ 更长时间窗的采购订单。补充入口按 R37 只给核心页做，本页暂未设补充入口。</p>
    </div>
   </template>
  </template>
 </section>
</template>
<style scoped>
.bom-banner{margin:10px 0;padding:8px 12px;border:1px solid var(--cs-border,#dce4ec);border-left:3px solid var(--cs-primary,#1b65a8);background:var(--cs-surface-subtle,#f8fafc);border-radius:6px}
.bom-metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px;margin:10px 0}
.bom-metrics>div{padding:10px 14px;border:1px solid var(--cs-border,#dce4ec);border-radius:8px;background:var(--cs-surface-subtle,#f8fafc);display:flex;flex-direction:column}
.bom-metrics b{font-size:22px;font-weight:600}.bom-metrics small{color:#6b7b8b}
.bom-card{margin-top:12px;padding:14px;border:1px solid var(--cs-border,#dce4ec);border-radius:8px}
.bom-card h2{font-size:16px;margin:0 0 6px}.bom-card h3{font-size:14px;margin:14px 0 6px}
.bom-sub{display:block;color:#6b7b8b;font-size:12px;font-weight:400}
.num{text-align:right;font-variant-numeric:tabular-nums}
.bom-up{color:#b3261e}.bom-down{color:#1b7a43}
.bom-two{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.bom-two p{margin:2px 0}
.bom-form{display:flex;flex-wrap:wrap;gap:14px;align-items:flex-end;margin:10px 0}.bom-form label{display:flex;flex-direction:column;gap:4px;font-size:13px}
.bom-form select,.bom-form input{min-height:32px;min-width:220px;border:1px solid var(--cs-border,#dce4ec);border-radius:6px;padding:4px 8px}
.bom-list{margin:6px 0;padding-left:20px}.bom-list li{margin:4px 0}
.bom-links{list-style:none;padding:0;margin:8px 0}.bom-links li{display:flex;flex-direction:column;padding:8px 0;border-bottom:1px solid var(--cs-border,#dce4ec)}
.bom-chip{display:inline-block;margin:0 10px 2px 0}.bom-foot{margin-top:10px}.bom-prod{margin-top:14px;padding-top:10px;border-top:1px solid var(--cs-border,#dce4ec)}
.bom-detail td{background:var(--cs-surface-subtle,#f8fafc)}
</style>
