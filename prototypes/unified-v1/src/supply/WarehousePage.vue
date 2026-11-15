<script setup lang="ts">
// 六膳门 · 仓库档案（规格 26）＋ Cretas 本地库位与平面图（32，W4）。仓库档案来自黑湖只读；区域、库位、底图、摆放只存本机原型。
import {computed,ref} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {T,post,readSupply} from './supply-store'
import type {Layout,Zone,SupplyState} from './supply-store'
import {stockState,fmtTime} from './supply-facts'
import {confirmReason} from '../frame/ai-protocol'
const emit=defineEmits<{openStock:[warehouseId:string]}>()
const inv=computed(()=>T.inv!)
const rows=computed(()=>inv.value.warehouses.map(w=>{const s=inv.value.stock.filter(x=>String(x.warehouseId)===String(w.id));const l=T.supply?.layouts[String(w.id)];return {w,id:String(w.id),positive:s.filter(x=>stockState(x.qtyInWarehouse)==='positive').length,zero:s.filter(x=>stockState(x.qtyInWarehouse)==='zero').length,negative:s.filter(x=>stockState(x.qtyInWarehouse)==='negative').length,zones:l?.zones.length||0,locations:l?.locations.length||0,image:Boolean(l?.image)}}))
// 黑湖仓库状态码含义未确认，原样显示，不猜
const statusText=(v:unknown)=>v==null||v===''?'未记录':'来源码 '+String(v)+'（含义待黑湖确认）'

const editing=ref<string>(),draft=ref<Layout>(),dirty=ref(false),err=ref(''),saving=ref(false),zoneName=ref(''),zonePrefix=ref(''),locCode=ref(''),locName=ref(''),locZone=ref('')
const editingName=computed(()=>String(inv.value.warehouses.find(w=>String(w.id)===editing.value)?.name||''))
function edit(id:string){const l=T.supply?.layouts[id];draft.value=l?JSON.parse(JSON.stringify({zones:l.zones,locations:l.locations,image:l.image})):{zones:[],locations:[],image:null};editing.value=id;dirty.value=false;err.value='';locZone.value=draft.value?.zones[0]?.id||''}
// 第五批 R21：不用浏览器原生 confirm，改用设计系统小弹层；放弃草稿不需要留痕，原因可不填
async function close(){if(dirty.value){const r=await confirmReason({title:'放弃未保存的布局修改',message:'布局有未保存的修改，关闭后这些修改不会保存。',required:false,minLength:0,reasonLabel:'备注（可不填）',okLabel:'放弃修改',danger:true});if(r===null)return}editing.value=undefined;draft.value=undefined}
const uid=()=>Math.random().toString(36).slice(2,10)
function addZone(){err.value='';const n=zoneName.value.trim();if(!n){err.value='区域名称必填';return}if(draft.value!.zones.some(z=>z.name===n)){err.value='区域名称重复';return}const z:Zone={id:uid(),name:n,prefix:zonePrefix.value.trim().slice(0,8),placed:false,x:5,y:5,w:22,h:16};draft.value!.zones.push(z);locZone.value=locZone.value||z.id;zoneName.value='';zonePrefix.value='';dirty.value=true}
function removeZone(z:Zone){if(draft.value!.locations.some(l=>l.zoneId===z.id)){err.value='区域「'+z.name+'」下还有库位，请先删除库位';return}draft.value!.zones=draft.value!.zones.filter(x=>x.id!==z.id);dirty.value=true}
function addLoc(){err.value='';const c=locCode.value.trim();if(!c){err.value='库位编码必填';return}if(!locZone.value){err.value='请先建区域';return}if(draft.value!.locations.some(l=>l.code===c)){err.value='库位编码重复：'+c;return}draft.value!.locations.push({id:uid(),zoneId:locZone.value,code:c,name:locName.value.trim()});locCode.value='';locName.value='';dirty.value=true}
function removeLoc(id:string){draft.value!.locations=draft.value!.locations.filter(l=>l.id!==id);dirty.value=true}
// 底图：浏览器内压缩到长边 1600px JPEG，预览后随布局一起保存
async function pickImage(e:Event){
  const f=(e.target as HTMLInputElement).files?.[0];if(!f)return;err.value=''
  if(!/^image\/(png|jpeg|webp)$/.test(f.type)){err.value='底图只接受 PNG、JPEG、WebP';return}
  if(f.size>20_000_000){err.value='原图超过 20MB';return}
  const img=new Image(),url=URL.createObjectURL(f)
  try{await new Promise((ok,no)=>{img.onload=ok;img.onerror=no;img.src=url})
    const k=Math.min(1,1600/Math.max(img.width,img.height)),c=document.createElement('canvas');c.width=Math.round(img.width*k);c.height=Math.round(img.height*k)
    c.getContext('2d')!.drawImage(img,0,0,c.width,c.height)
    draft.value!.image={dataUrl:c.toDataURL('image/jpeg',0.82),name:f.name,at:new Date().toISOString()};dirty.value=true
  }catch{err.value='图片读取失败'}finally{URL.revokeObjectURL(url)}
}
function removeImage(){draft.value!.image=null;dirty.value=true}
// 拖动摆放：只改草稿位置，点「保存布局」才写入
const canvas=ref<HTMLElement>()
let drag:{z:Zone;sx:number;sy:number;x:number;y:number;resize:boolean}|undefined
function down(e:PointerEvent,z:Zone,resize=false){e.preventDefault();(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);drag={z,sx:e.clientX,sy:e.clientY,x:resize?z.w:z.x,y:resize?z.h:z.y,resize}}
function move(e:PointerEvent){if(!drag||!canvas.value)return;const r=canvas.value.getBoundingClientRect(),dx=(e.clientX-drag.sx)/r.width*100,dy=(e.clientY-drag.sy)/r.height*100;if(drag.resize){drag.z.w=Math.max(4,Math.min(100-drag.z.x,drag.x+dx));drag.z.h=Math.max(4,Math.min(100-drag.z.y,drag.y+dy))}else{drag.z.x=Math.max(0,Math.min(100-drag.z.w,drag.x+dx));drag.z.y=Math.max(0,Math.min(100-drag.z.h,drag.y+dy))}dirty.value=true}
function up(){drag=undefined}
function nudge(e:KeyboardEvent,z:Zone){const d=e.shiftKey?5:1,m:Record<string,[number,number]>={ArrowLeft:[-d,0],ArrowRight:[d,0],ArrowUp:[0,-d],ArrowDown:[0,d]};const v=m[e.key];if(!v)return;e.preventDefault();z.x=Math.max(0,Math.min(100-z.w,z.x+v[0]));z.y=Math.max(0,Math.min(100-z.h,z.y+v[1]));dirty.value=true}
async function save(){
  if(!T.supply||!editing.value||!draft.value)return;saving.value=true;err.value=''
  try{const round=(n:number)=>Math.round(n*100)/100;const layout={...draft.value,zones:draft.value.zones.map(z=>({...z,x:round(z.x),y:round(z.y),w:round(z.w),h:round(z.h)}))}
    await post<SupplyState>('/layout',{warehouseId:editing.value,layout,expectedVersion:T.supply.version});await readSupply()
    const back=T.supply?.layouts[editing.value];if(!back)throw Error('保存后未能回读布局，请刷新核对')
    draft.value=JSON.parse(JSON.stringify({zones:back.zones,locations:back.locations,image:back.image}));dirty.value=false
    T.notice='已保存「'+editingName.value+'」布局：'+back.zones.length+' 个区域、'+back.locations.length+' 个库位'+(back.image?'、底图':'')+'（已重新读取保存区）。'
  }catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{saving.value=false}
}
const zoneOf=(id:string)=>draft.value?.zones.find(z=>z.id===id)
</script>
<template><div>
<template v-if="!editing">
<p v-if="T.supplyError" class="notice">{{T.supplyError}}</p>
<section class="card"><div class="section-head"><h2>仓库档案 · {{rows.length}} 个</h2><small>余额计数来自黑湖快照 {{fmtTime(inv.materialAt)}}</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>仓库编码</th><th>仓库</th><th>状态</th><th class="num">有余额</th><th class="num">零库存</th><th class="num">负库存</th><th>本地布局</th><th>操作</th></tr></thead><tbody>
<tr v-for="r in rows" :key="r.id"><td>{{r.w.code}}</td><td>{{r.w.name}}<span class="src-tag">黑湖</span></td><td>{{statusText(r.w.status)}}</td><td class="num">{{r.positive}}</td><td class="num">{{r.zero}}</td><td class="num"><span v-if="r.negative" class="supply-badge" data-tone="negative">{{r.negative}}</span><template v-else>0</template></td><td>{{r.zones||r.locations||r.image?r.zones+' 区域 · '+r.locations+' 库位'+(r.image?' · 有底图':''):'未配置'}}<span v-if="r.zones||r.image" class="src-tag" data-tone="supply">Cretas 配置</span></td><td class="ops"><button class="op" @click="emit('openStock',r.id)">库存</button><button class="op" :disabled="!T.supply" @click="edit(r.id)">布局</button></td></tr>
</tbody></table></div></section>
</template>
<template v-else-if="draft">
<div class="layout-head"><button class="ui-button" @click="close">← 返回仓库档案</button><h2>{{editingName}} · 区域、库位与平面图</h2><span v-if="dirty" class="supply-badge" data-tone="zero">未保存</span><span class="spacer"/><UButton size="sm" :loading="saving" :disabled="!dirty" @click="save">保存布局</UButton></div>
<p class="supply-note">拖动只改动草稿位置，点“保存布局”才写入本机原型；也可以选中区域后用方向键微调（Shift 加大步长）。不回写黑湖。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div class="layout-grid">
<section class="card layout-side">
<h3>区域</h3>
<form class="filters" @submit.prevent="addZone"><input v-model="zoneName" maxlength="40" aria-label="区域名称" placeholder="区域名称，例：冷藏区"><input v-model="zonePrefix" maxlength="8" aria-label="区域前缀" placeholder="前缀"><button class="ui-button" type="submit">新增区域</button></form>
<ul class="layout-list"><li v-for="z in draft.zones" :key="z.id"><b>{{z.name}}</b><small>{{z.prefix}}</small><span class="spacer"/><button v-if="!z.placed" class="op" :disabled="!draft.image" :title="draft.image?'放到平面图左上角，再拖动摆放':'请先上传底图'" @click="z.placed=true;dirty=true">放到图上</button><button v-else class="op" @click="z.placed=false;dirty=true">移出图</button><button class="op" @click="removeZone(z)">删除</button></li><li v-if="!draft.zones.length" class="muted">还没有区域。</li></ul>
<h3>库位</h3>
<form class="filters" @submit.prevent="addLoc"><select v-model="locZone" aria-label="库位所属区域"><option value="" disabled>选择区域</option><option v-for="z in draft.zones" :key="z.id" :value="z.id">{{z.name}}</option></select><input v-model="locCode" maxlength="32" aria-label="库位编码" placeholder="库位编码"><input v-model="locName" maxlength="40" aria-label="库位名称" placeholder="名称（选填）"><button class="ui-button" type="submit">新增库位</button></form>
<ul class="layout-list"><li v-for="l in draft.locations" :key="l.id"><b>{{l.code}}</b><small>{{zoneOf(l.zoneId)?.name}}{{l.name?' · '+l.name:''}}</small><span class="spacer"/><button class="op" @click="removeLoc(l.id)">删除</button></li><li v-if="!draft.locations.length" class="muted">还没有库位。</li></ul>
</section>
<section class="card layout-map">
<div class="section-head"><h3>平面图</h3><span class="spacer"/><label class="ui-button">上传底图<input type="file" accept="image/png,image/jpeg,image/webp" hidden aria-label="上传底图" @change="pickImage"></label><button v-if="draft.image" class="ui-button" @click="removeImage">移除底图</button></div>
<div v-if="draft.image" ref="canvas" class="layout-canvas" @pointermove="move" @pointerup="up" @pointercancel="up"><img :src="draft.image.dataUrl" :alt="editingName+' 平面图底图'" draggable="false">
<div v-for="z in draft.zones.filter(x=>x.placed)" :key="z.id" class="layout-zone" tabindex="0" :aria-label="'区域 '+z.name+'，方向键移动'" :style="{left:z.x+'%',top:z.y+'%',width:z.w+'%',height:z.h+'%'}" @pointerdown="down($event,z)" @keydown="nudge($event,z)"><b>{{z.name}}</b><small>{{draft.locations.filter(l=>l.zoneId===z.id).length}} 库位</small><i class="layout-resize" aria-hidden="true" @pointerdown.stop="down($event,z,true)"/></div></div>
<p v-else class="empty">还没有底图。上传仓库平面照片或图纸后，把区域拖到对应位置。本轮不做图片识别。</p>
<p v-if="draft.image" class="muted">底图：{{draft.image.name}} · {{fmtTime(draft.image.at)}}</p>
</section>
</div>
</template>
</div></template>
