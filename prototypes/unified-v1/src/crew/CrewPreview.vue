<script setup lang="ts">
// 写确认卡：预览的每一行都显示（ai-chat-standard H3），确认才执行（H2）；没权限的人看得到但按钮不可点，并说明缺什么权限。
import {computed} from 'vue'
import {C,confirmAndRun,cancelInbox,mayConfirm,itemStatus,type InboxItem} from './crew-store'
import {crewById,modCn} from './crew-config'
import {confirmReason} from '../frame/ai-protocol'
import {bjDate} from './crew-facts'
const p=defineProps<{item:InboxItem;compact?:boolean}>()
const emit=defineEmits<{(e:'done',x:InboxItem):void}>()
const st=computed(()=>itemStatus(p.item)),can=computed(()=>mayConfirm(p.item)),who=computed(()=>crewById(p.item.employee)?.name||'')
async function ok(){try{const x=await confirmAndRun(p.item);C.notice=x.result?(x.result.label+'：'+(x.result.ref?.label||x.result.note||x.title)):'';emit('done',x)}catch(e){C.error=(e instanceof Error?e.message:'失败')+'。没有执行任何写操作。'}}
async function no(){const r=await confirmReason({title:'不办这件事',object:p.item.title,message:'不办只记在班组记录里，不扣额度，也不改任何业务数据',okLabel:'确认不办'});if(r===null)return;try{await cancelInbox(p.item.id,r)}catch(e){C.error=e instanceof Error?e.message:'失败'}}
</script>
<template><section class="preview" data-testid="ai-confirm-card">
<h3><span>{{who}} · {{item.title}}</span><span class="chip" :class="st.tone">{{st.label}}</span></h3>
<dl data-testid="ai-confirm-rows"><template v-for="r in item.preview.rows" :key="r.label"><dt>{{r.label}}</dt><dd>{{r.value}}</dd></template></dl>
<div class="notes" v-if="!compact||item.status==='pending'">
  <p v-if="item.preview.impact.length"><b>影响：</b>{{item.preview.impact.join('；')}}</p>
  <p v-if="item.preview.notDone.length"><b>这次不做：</b>{{item.preview.notDone.join('；')}}</p>
  <p v-if="item.preview.basis.length"><b>依据：</b>{{item.preview.basis.join('；')}}</p>
  <p v-if="item.action.type==='demo'"><b>原型演示：</b>{{item.action.reason}}（确认后只记「原型演示，未执行」，不扣额度）</p>
  <p v-if="item.result"><b>结果：</b>{{item.result.label}}{{item.result.ref?' · '+item.result.ref.label:''}}{{item.result.note?' · '+item.result.note:''}}</p>
  <p v-if="item.confirmedBy">确认人：{{item.confirmedBy}} · {{bjDate(item.confirmedAt)}}</p>
</div>
<footer v-if="item.status==='pending'">
  <span class="why" v-if="!can">你没有「{{modCn(item.module)}}」模块的办理权限，只能看，确认要交给有权限的人</span>
  <span class="why" v-else-if="!C.state?.writable">没带验收会话，不能确认（地址加 ?acceptanceSession=test-f-20261112）</span>
  <button class="btn" type="button" @click="no">不办</button>
  <button class="btn primary" type="button" data-testid="crew-confirm" :disabled="!can||!C.state?.writable||C.busyId===item.id" @click="ok">{{C.busyId===item.id?'办理中…':'确认并办理'}}</button>
</footer></section></template>
