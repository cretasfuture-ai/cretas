<script setup lang="ts">
// 第四批① BL13 味美：采购精简视图「新建草稿（带价目表参考价）」。经味美原接口 POST purchase/orders 保存为草稿（status=DRAFT），
// 保存后重新读取列表回读本单。单价可改；每行记来源：价目表 / 手改 / 手填（无有效价目）。提交、审批仍在原页面。
import {computed,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {yget,ypost,today,YD_SESSION_LABEL} from './ydwm'
import {matchPurchasePrice,priceSource} from './ydwm-price'
import {fmtQty} from './supply-facts'
const emit=defineEmits<{close:[];saved:[id:string]}>()
const suppliers=ref<Row[]>([]),materials=ref<Row[]>([]),lists=ref<Row[]>([]),busy=ref(false),err=ref(''),saved=ref<Row>()
const f=ref<{supplierId:string;materialId:string;qty:string|number;unit:string;price:string|number;tax:string;notes:string}>({supplierId:'',materialId:'',qty:'',unit:'',price:'',tax:'',notes:''})
onMounted(async()=>{busy.value=true;try{const [s,m,l]=await Promise.all([yget<Row[]>('suppliers?page=1&size=500'),yget<Row[]>('raw-material-types/active'),yget<Row[]>('price-lists?page=1&size=500')]);suppliers.value=(s||[]).filter(x=>x.isActive!==false);materials.value=m||[];lists.value=l||[]}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}})
const mat=computed(()=>materials.value.find(m=>String(m.id)===f.value.materialId))
const match=computed(()=>mat.value?matchPurchasePrice(lists.value,String(mat.value.name??''),f.value.unit,today()):null)
// 选物料或单位变化时带出参考价；用户改过单价后不再覆盖
const touched=ref(false)
// 审计退回 1：按原页面草稿的字段补齐物料名称与税率。税率取物料档案（taxTreatment / taxRate）；档案没有时由用户选，不默认 0
const taxOf=(m:Row)=>m.taxTreatment==='EXEMPT'?'0':m.taxRate==='TAX_13'?'13':m.taxRate==='TAX_9'?'9':typeof m.taxRate==='number'?String(m.taxRate):''
watch(mat,m=>{if(m){f.value.unit=String(m.unit??'');f.value.tax=taxOf(m)}})
watch(match,m=>{if(!touched.value)f.value.price=m?.ref?String(m.ref.price):''})
// type=number 的输入框 v-model 会给出数字，统一按字符串判断空值
const priceNum=computed(()=>{const t=String(f.value.price??'').trim();return t===''?null:Number(t)})
const src=computed(()=>priceSource(priceNum.value,match.value?.ref||null))
const valid=computed(()=>!!f.value.supplierId&&!!mat.value&&f.value.tax!==''&&Number(f.value.qty)>0&&priceNum.value!=null&&Number.isFinite(priceNum.value)&&priceNum.value>=0)
async function save(){if(!valid.value||!mat.value)return;busy.value=true;err.value=''
  try{const ref=match.value?.ref||null
    const body={supplierId:f.value.supplierId,status:'DRAFT',notes:f.value.notes||'精简视图新建草稿（单价来源见明细）',items:[{materialTypeId:String(mat.value.id),materialName:String(mat.value.name??''),taxRate:Number(f.value.tax),quantity:Number(f.value.qty),unit:f.value.unit,unitPrice:priceNum.value,
      priceSource:src.value.label,referencePrice:ref?.price??null,referencePriceListId:ref?.listId??null,referencePriceListName:ref?.listName??null,priceDeviationPct:src.value.deviation==null?null:Number(src.value.deviation.toFixed(4))}]}
    const r=await ypost<Row>('purchase/orders',body)
    // 回读：重新读列表找本单，不用保存返回值代替
    const all=await yget<Row[]>('purchase/orders?page=1&size=500');saved.value=all.find(o=>String(o.id)===String(r?.id))
    if(!saved.value)throw Error('保存返回成功，但回读列表里没有找到这张草稿')
    emit('saved',String(saved.value.id))}
  catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{busy.value=false}}
const line=computed(()=>((saved.value?.items as Row[])||[])[0])
</script>
<template><section class="card detail-card" aria-label="新建采购草稿">
<div class="section-head"><h2>新建采购草稿（带价目表参考价）</h2><button class="ui-button" @click="emit('close')">关闭</button></div>
<p class="supply-note">经味美原接口保存为草稿 · {{YD_SESSION_LABEL()}}。参考价只来自「生效中」的采购价目表，按物料名称＋单位精确匹配（味美价目表没有供应商字段）；单价可改，改了记为「手改」并算偏离。提交与审批在原采购订单页面。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<form v-if="!saved" class="draft-form" @submit.prevent="save">
<label>供应商<select v-model="f.supplierId" required><option value="">请选择</option><option v-for="s in suppliers" :key="String(s.id)" :value="String(s.id)">{{s.name}}{{s.phone?' · '+s.phone:''}}</option></select></label>
<label>物料<select v-model="f.materialId" required><option value="">请选择</option><option v-for="m in materials" :key="String(m.id)" :value="String(m.id)">{{m.name}}（{{m.unit}}）</option></select></label>
<label>数量<input v-model="f.qty" type="number" min="0" step="any" required></label>
<label>单位<input v-model="f.unit" required></label>
<label>单价<input v-model="f.price" type="number" min="0" step="any" required @input="touched=true"></label>
<label>税率<select v-model="f.tax" required><option value="">请选择</option><option value="0">0%（免税）</option><option value="9">9%</option><option value="13">13%</option></select><small v-if="mat&&!taxOf(mat)" class="muted">物料档案没有税率，请选择</small></label>
<label>备注<input v-model="f.notes" placeholder="可不填"></label>
<div v-if="mat" class="draft-ref">
  <template v-if="match?.ref">参考价 <b>{{fmtQty(match.ref.price)}}</b> / {{f.unit}} · 来源 <span class="src-tag">价目表</span>「{{match.ref.listName}}」，生效 {{match.ref.from||'未填'}} 至 {{match.ref.to||'长期'}}<template v-if="match.ref.others">（另有 {{match.ref.others}} 个有效价目，取生效日最新）</template></template>
  <template v-else><span class="supply-badge" data-tone="zero">无有效价目</span> 按「{{mat.name}}＋{{f.unit}}」没有生效中的采购价目</template>
  <span v-for="x in match?.inactive||[]" :key="x.listName" class="muted"> ·「{{x.listName}}」有 {{fmtQty(x.price)}}，但{{x.reason}}，不作参考</span>
  <div>单价来源：<b>{{src.label}}</b><template v-if="src.deviation!=null&&src.label==='手改'"> · 偏离 {{src.deviation>0?'+':''}}{{src.deviation.toFixed(2)}}%</template></div>
</div>
<div class="draft-actions"><button type="submit" class="ui-button primary" :disabled="!valid||busy">{{busy?'正在保存…':'保存为草稿'}}</button><button type="button" class="ui-button" @click="emit('close')">取消</button></div>
</form>
<div v-else class="draft-ref">
  <p><b>已保存并回读</b>：{{saved.orderNumber}} · 状态 {{saved.status}} · 供应商 {{saved.supplierName}}</p>
  <p v-if="line">{{line.materialName||'物料名称未返回'}} {{fmtQty(line.quantity)}} {{line.unit}} · 单价 {{fmtQty(line.unitPrice)}} · 税率 {{line.taxRate==null?'未返回':line.taxRate+'%'}} · 来源 <b>{{line.priceSource??'未记录'}}</b><template v-if="line.referencePrice!=null"> · 参考价 {{fmtQty(line.referencePrice)}}（{{line.referencePriceListName}}）</template><template v-if="line.priceDeviationPct!=null&&line.priceSource==='手改'"> · 偏离 {{Number(line.priceDeviationPct).toFixed(2)}}%</template></p>
</div>
</section></template>
<style scoped>
.draft-form{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px 16px;align-items:end}
.draft-form label{display:flex;flex-direction:column;gap:4px;font-size:13px}
.draft-ref{grid-column:1/-1;border:1px solid var(--cs-border);border-radius:6px;padding:8px 12px;font-size:13px}
.draft-actions{grid-column:1/-1;display:flex;gap:8px}
</style>
