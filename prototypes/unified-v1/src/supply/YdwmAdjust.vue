<script setup lang="ts">
// 味美 · 库存调整单（N5）：单条细微调整，原因必选 → 预览 → 确认 → 回读。
// 写入走味美既有接口 POST sales/finished-goods/{id}/adjust（只在 C 线隔离会话 test-c-20261111）。
// 原料批次味美没有单条调整接口，只能走盘点（页面如实说明，不另造接口）。
import {computed,onMounted,ref} from 'vue'
import type {Row} from '../frame/types'
import {yget,ypost,num,YD_SESSION_LABEL,withSession} from './ydwm'
import {fmtQty,add} from './supply-facts'
const goods=ref<Row[]>([]),busy=ref(false),err=ref(''),done=ref<{batch:string;before:number;after:number;unit:string;reason:string;at:string}[]>([])
const pick=ref(''),after=ref(''),reason=ref(''),note=ref(''),stage=ref<'edit'|'preview'>('edit'),saving=ref(false)
const REASONS=[{v:'计量误差',t:'OTHER'},{v:'破损',t:'SCRAP'},{v:'过期报废',t:'SCRAP'},{v:'找到遗漏',t:'OTHER'},{v:'期初更正',t:'OPENING_CORRECTION'},{v:'其他',t:'OTHER'}]
async function read(){busy.value=true;err.value='';try{goods.value=await yget<Row[]>('sales/finished-goods?page=1&size=500')}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const batch=computed(()=>goods.value.find(g=>String(g.id)===pick.value))
const before=computed(()=>num(batch.value?.producedQuantity))
const shippedQ=computed(()=>num(batch.value?.shippedQuantity)??0)
const delta=computed(()=>{const a=num(after.value.trim());return a===undefined||before.value===undefined?undefined:add(a,-before.value)})
const big=computed(()=>delta.value!==undefined&&before.value?Math.abs(delta.value)/before.value>0.5:false)
function check(){
  if(!batch.value)return '请选择成品批次'
  const a=num(after.value.trim());if(a===undefined||a<0)return '调整后数量必须是非负数字'
  if(delta.value===0)return '调整后数量与当前相同，不需要调整'
  if(a<shippedQ.value)return '调整后数量不能低于已发货数量 '+fmtQty(shippedQ.value)
  if(!reason.value)return '请选择原因'
  if(reason.value==='其他'&&!note.value.trim())return '原因选“其他”时必须写说明'
  return ''
}
function preview(){err.value=check();if(!err.value)stage.value='preview'}
async function confirm(){
  const b=batch.value;if(!b||delta.value===undefined)return;saving.value=true;err.value=''
  try{
    const r=REASONS.find(x=>x.v===reason.value)!,was=before.value!
    await ypost('sales/finished-goods/'+encodeURIComponent(String(b.id))+'/adjust',{adjustmentQuantity:delta.value,reason:reason.value+(note.value.trim()?'：'+note.value.trim():''),referenceType:r.t})
    await read()
    const back=goods.value.find(g=>String(g.id)===String(b.id)),got=num(back?.producedQuantity)
    if(got!==num(after.value))throw Error('已提交，但回读数量（'+fmtQty(got)+'）与预期不一致，请刷新核对')
    done.value.unshift({batch:String(b.batchNumber),before:was,after:got!,unit:String(b.unit),reason:reason.value,at:new Date().toLocaleString('zh-CN',{hour12:false})})
    stage.value='edit';after.value='';reason.value='';note.value=''
  }catch(e){err.value=e instanceof Error?e.message:'调整失败';stage.value='edit'}finally{saving.value=false}
}
defineExpose({exportCurrent:()=>{}})
</script>
<template><div>
<p class="supply-note">味美工作区 · {{YD_SESSION_LABEL()}}。单条成品批次的小幅调整（不必整仓盘点）；调整写入本机练习会话，不影响账号工作区。原料批次味美没有单条调整接口，请走盘点。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div class="layout-grid">
<section class="card"><div class="section-head"><h2>新建调整</h2></div>
<form class="filters adjust-form" @submit.prevent="stage==='edit'?preview():confirm()">
<label>成品批次<select v-model="pick" :disabled="stage==='preview'" aria-label="选择成品批次"><option value="" disabled>选择批次</option><option v-for="g in goods" :key="String(g.id)" :value="String(g.id)">{{g.productName}} · {{g.batchNumber}} · {{fmtQty(g.producedQuantity)}} {{g.unit}}</option></select></label>
<label>调整后数量<input v-model="after" :disabled="stage==='preview'" inputmode="decimal" aria-label="调整后数量"> <small>{{batch?.unit}}</small></label>
<label>原因<select v-model="reason" :disabled="stage==='preview'" aria-label="调整原因"><option value="" disabled>选择原因</option><option v-for="r in REASONS" :key="r.v" :value="r.v">{{r.v}}</option></select></label>
<label>说明<input v-model="note" :disabled="stage==='preview'" maxlength="100" aria-label="调整说明" placeholder="选填；原因为“其他”时必填"></label>
<template v-if="stage==='preview'&&batch"><dl class="kv"><dt>批次</dt><dd>{{batch.productName}} · {{batch.batchNumber}}</dd><dt>调整前</dt><dd>{{fmtQty(before)}} {{batch.unit}}（已发 {{fmtQty(shippedQ)}}）</dd><dt>调整后</dt><dd>{{fmtQty(after)}} {{batch.unit}}</dd><dt>差额</dt><dd>{{delta!>0?'+':''}}{{fmtQty(delta)}} {{batch.unit}}</dd><dt>原因</dt><dd>{{reason}}{{note?'：'+note:''}}</dd></dl><p v-if="big" class="notice">较大调整（超过当前数量的 50%），建议改走盘点。</p></template>
<div class="supply-actions"><button v-if="stage==='preview'" type="button" class="ui-button" @click="stage='edit'">返回修改</button><button type="submit" class="ui-button" data-variant="primary" :disabled="saving||busy">{{stage==='edit'?'预览':'确认调整'}}</button></div>
</form></section>
<section class="card"><div class="section-head"><h2>成品批次 · {{goods.length}}</h2><button class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>产品</th><th>批次</th><th class="num">生产</th><th class="num">已发</th><th class="num">可用</th><th>单位</th></tr></thead><tbody>
<tr v-for="g in goods" :key="String(g.id)"><td>{{g.productName}}</td><td>{{g.batchNumber}}</td><td class="num">{{fmtQty(g.producedQuantity)}}</td><td class="num">{{fmtQty(g.shippedQuantity)}}</td><td class="num">{{fmtQty(g.availableQuantity)}}</td><td>{{g.unit}}</td></tr>
<tr v-if="!goods.length"><td colspan="6" class="empty">{{busy?'正在读取':'没有成品批次。'}}</td></tr></tbody></table></div>
<h3>本次调整（已回读）</h3><p class="muted" v-if="!done.length">味美调整记录列表接口未返回；本页只列出本次会话中确认并回读成功的调整。</p>
<ul class="layout-list"><li v-for="(d,i) in done" :key="i"><b>{{d.batch}}</b><small>{{fmtQty(d.before)}} → {{fmtQty(d.after)}} {{d.unit}} · {{d.reason}} · {{d.at}}</small></li></ul>
</section></div>
</div></template>
