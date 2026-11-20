<script setup lang="ts">
// 六膳门 · 生产领退料（规格 27，归 A「计划执行」菜单位置，D2）：已发 / 退回 / 净领用；需求、实耗黑湖未提供。来源只读。
import {computed} from 'vue'
import type {Row} from '../frame/types'
import {T,orderLink} from './supply-store'
import {flatten,flowSummary,fmtQty,fmtTime,localDate,PICK_TYPES,RETURN_TYPES,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {navigate} from '../frame/store'
const props=defineProps<{projects:Row[]}>(),emit=defineEmits<{inspect:[kind:string,row:Row]}>()
const prefs=usePrefs('unified-lsm-supply-flow-v1',{view:'summary',type:'',q:'',from:'',to:''})
const lines=computed(()=>flatten(T.inv!).filter(l=>[...PICK_TYPES,...RETURN_TYPES].includes(l.bizType)))
const inRange=(v:string)=>{const d=localDate(v);return (!prefs.value.from||d>=prefs.value.from)&&(!prefs.value.to||d<=prefs.value.to)}
const filtered=computed(()=>lines.value.filter(l=>(!prefs.value.type||l.bizType===prefs.value.type)&&inRange(l.bizAt)&&(!prefs.value.q||(l.orderNo+l.productName+l.productCode+l.related).toLowerCase().includes(prefs.value.q.toLowerCase()))).sort((a,b)=>b.bizAt.localeCompare(a.bizAt)))
const summary=computed(()=>flowSummary(filtered.value))
const sumPager=usePager(summary),linePager=usePager(filtered)
const orderCount=(t:string)=>new Set(lines.value.filter(l=>l.bizType===t).map(l=>l.orderNo)).size
const noRelated=computed(()=>filtered.value.filter(l=>!l.related).length)
const projectOf=(code:string)=>code?props.projects.find(p=>String(p.projectCode)===code):undefined
function exportCurrent(){downloadCsv('六膳门生产领退料.csv',['产品编码','产品','单位','已发','退回','净领用','需求','实耗','行数','无关联单号行'],summary.value.map(s=>[s.productCode,s.productName,s.unit,s.issued,s.returned,s.net,'黑湖未提供','黑湖未提供',s.lines,s.noRelated]))}
defineExpose({exportCurrent})
</script>
<template><div>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索单号、产品或工单" placeholder="单号、产品、关联工单">
<select v-model="prefs.type" aria-label="筛选类型"><option value="">领料与退料</option><option value="生产领料出库">领料出库（{{orderCount('生产领料出库')}} 张）</option><option value="生产退料入库">退料入库（{{orderCount('生产退料入库')}} 张）</option></select>
<label>业务时间 <input v-model="prefs.from" type="date" aria-label="开始日期"></label><label>至 <input v-model="prefs.to" type="date" aria-label="结束日期"></label>
<button type="button" class="ui-button" @click="prefs.q='';prefs.type='';prefs.from='';prefs.to=''">重置</button>
<span class="seg" role="group" aria-label="视图"><button type="button" :aria-pressed="prefs.view==='summary'" @click="prefs.view='summary'">按产品汇总</button><button type="button" :aria-pressed="prefs.view==='lines'" @click="prefs.view='lines'">按明细行</button></span>
</form>
<template v-if="prefs.view==='summary'">
<div class="section-head"><h2>按产品汇总 · {{summary.length}} 行</h2><small>同产品同单位才合计；无关联单号的行 {{noRelated}}</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>产品</th><th>单位</th><th class="num">已发</th><th class="num">退回</th><th class="num">净领用</th><th>需求</th><th>实耗</th><th class="num">行数</th></tr></thead><tbody>
<tr v-for="s in sumPager.rows.value" :key="s.productCode+s.unit"><td>{{s.productName}}<small>{{s.productCode}}</small></td><td>{{s.unit}}</td><td class="num">{{fmtQty(s.issued)}}</td><td class="num">{{fmtQty(s.returned)}}</td><td class="num" :data-warn="s.net<0||undefined">{{fmtQty(s.net)}}<span v-if="s.net<0" class="supply-badge" data-tone="negative">退回多于领用</span></td><td class="muted">黑湖未提供</td><td class="muted">黑湖未提供</td><td class="num">{{s.lines}}</td></tr>
<tr v-if="!summary.length"><td colspan="8" class="empty">当前筛选没有领退料记录。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{sumPager.page.value}} / {{sumPager.pages.value}} 页</span><button class="ui-button" :disabled="sumPager.page.value<=1" @click="sumPager.page.value--">上一页</button><button class="ui-button" :disabled="sumPager.page.value>=sumPager.pages.value" @click="sumPager.page.value++">下一页</button></div>
</template>
<template v-else>
<div class="section-head"><h2>明细行 · {{filtered.length}} 行</h2><small>关联单号能对上六膳门工单时可直接打开工单</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>业务时间</th><th>单号</th><th>类型</th><th>产品</th><th class="num">数量</th><th>单位</th><th>仓库</th><th>关联单号</th><th>操作</th></tr></thead><tbody>
<tr v-for="(l,i) in linePager.rows.value" :key="l.lineId+'-'+i"><td>{{fmtTime(l.bizAt)}}</td><td>{{l.orderNo}}</td><td>{{l.bizType==='生产领料出库'?'领料':'退料'}}</td><td>{{l.productName}}<small>{{l.productCode}}</small></td><td class="num">{{fmtQty(l.rawQty)}}</td><td>{{l.unit}}</td><td>{{l.warehouseName}}</td><td><button v-if="orderLink(l.related)" class="op" @click="navigate(orderLink(l.related)!.path,{no:l.related})">{{l.related}}</button><template v-else>{{l.related||'无关联'}}</template></td><td class="ops"><template v-if="projectOf(l.related)"><button class="op" @click="emit('inspect','project',projectOf(l.related)!)">工单</button><button class="op" title="打开该工单的成本分析（B 线）" @click="navigate('/lsm/cost',{order:String(projectOf(l.related)!.id)})">成本</button></template></td></tr>
<tr v-if="!filtered.length"><td colspan="9" class="empty">当前筛选没有领退料记录。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{linePager.page.value}} / {{linePager.pages.value}} 页</span><button class="ui-button" :disabled="linePager.page.value<=1" @click="linePager.page.value--">上一页</button><button class="ui-button" :disabled="linePager.page.value>=linePager.pages.value" @click="linePager.page.value++">下一页</button></div>
</template>
</section>
</div></template>
