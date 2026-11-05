<script setup lang="ts">
import {computed} from 'vue';
import UButton from '@nuxt/ui/components/Button.vue';
import type {OperationsFinding} from '@/views/smart-bi/operationsGuidance';
const props=defineProps<{title:string;summary:string;findings:OperationsFinding[];allowAsk?:boolean}>();
const emit=defineEmits<{(event:'locate',finding:OperationsFinding):void;(event:'ask',finding:OperationsFinding):void}>();
const ordered=computed(()=>[...props.findings].sort((a,b)=>({data:0,gap:1,check:2}[a.kind]-{data:0,gap:1,check:2}[b.kind])));
</script>
<template>
  <section class="operations-review" :aria-label="title">
    <h3>{{ title }}</h3><p>{{ summary }}</p>
    <p v-if="!ordered.length" class="note">本组数字暂未发现对账矛盾；是否满足生产和经营目标，还需结合计划、需求与标准。</p>
    <div v-for="(finding,index) in ordered.slice(0,3)" :key="finding.code" class="finding">
      <h4><span>{{ finding.kind==='data'?'先核资料':finding.kind==='gap'?'需要补齐':'需要核对' }}</span> {{ finding.title }}</h4>
      <p>{{ finding.summary }}</p><p><b>可能原因：</b>{{ finding.possibleCauses }}</p>
      <p><b>找谁处理：</b>{{ finding.owner }}。{{ finding.action }}</p>
      <div class="actions"><UButton v-if="finding.keys.length" color="primary" variant="outline" size="sm" @click="emit('locate',finding)">定位问题明细</UButton><UButton v-if="allowAsk" color="neutral" variant="ghost" size="sm" @click="emit('ask',finding)">追问这项问题</UButton></div>
    </div>
    <details v-if="ordered.length>3"><summary>其他 {{ ordered.length-3 }} 项核对提醒</summary><article v-for="finding in ordered.slice(3)" :key="finding.code" class="finding"><h4>{{ finding.title }}</h4><p>{{ finding.summary }}</p><p>可能原因：{{ finding.possibleCauses }}</p><p>找{{ finding.owner }}：{{ finding.action }}</p><div class="actions"><UButton v-if="finding.keys.length" color="primary" variant="outline" size="sm" @click="emit('locate',finding)">定位问题明细</UButton><UButton v-if="allowAsk" color="neutral" variant="ghost" size="sm" @click="emit('ask',finding)">追问这项问题</UButton></div></article></details>
  </section>
</template>
<style scoped>
.operations-review{padding:18px;border:1px solid var(--c-line,#edf2f7);border-radius:10px;background:var(--c-surface,#fff);color:var(--c-ink,#1a2332);margin:16px 0}.operations-review h3{font-size:16px;margin:0 0 10px}.operations-review p{font-size:13px;line-height:1.8;margin:7px 0}.finding{padding:14px 0;border-top:1px solid var(--c-line,#edf2f7)}.finding h4{margin:0 0 8px;font-size:14px}.finding h4 span{font-size:11px;color:#995e15;margin-right:8px}.actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:10px}.note,summary{font-size:12px;color:var(--c-text,#41536a)}summary{cursor:pointer;color:var(--c-primary,#1b65a8)}
</style>
