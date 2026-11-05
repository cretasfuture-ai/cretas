<script setup lang="ts">
import { computed } from 'vue';
import type { AnalysisFactProgress } from '@/api/smartbi/blacklake';
import { formatBlacklakeDateTime } from '@/views/smart-bi/blacklakePeriod';
const props=defineProps<{progress?:AnalysisFactProgress;status?:string;stage?:{phase:string;message:string}}>();
const reviewed=computed(()=>props.progress?.reviewed??props.progress?.additionalReviewed??0);
const full=computed(()=>props.progress?.mode==='all_facts');
const phase=computed(()=>props.progress?.phase||props.stage?.phase||props.status);
const phaseText=computed(()=>({generating:'正在逐项分析',correcting:'正在纠正说明',retrying:'等待自动重试',waiting_model:'等待本地模型恢复',waiting_source:'等待补齐分析事实',queued:'排队继续分析',running:'正在生成概览',ready:'全部分析事实已核对'}[phase.value||'']||'正在准备分析'));
const names=computed(()=>(props.progress?.blocker?.facts||props.progress?.currentFacts||[]).map(f=>f.label));
</script>
<template>
  <section v-if="progress||status==='queued'||status==='running'" class="analysis-progress" aria-label="分析完成进度" role="status">
    <div class="line"><strong>{{progress?(full?`已核对 ${reviewed} / ${progress.total} 项`:`已补充核对 ${reviewed} 项`):phaseText}}</strong><span v-if="progress">{{progress.remaining?`还剩 ${progress.remaining} 项`: '已完成'}}</span></div>
    <progress v-if="progress&&full" :value="reviewed" :max="Math.max(progress.total,1)" :aria-label="`已核对 ${reviewed}，共 ${progress.total} 项`" />
    <p v-if="progress?.blocker">{{progress.blocker.message}}<span v-if="progress.retryAt"> 下次尝试 {{formatBlacklakeDateTime(progress.retryAt)}}。</span></p>
    <p v-else-if="progress?.remaining">{{phaseText}}，已完成的内容可以先看。</p>
    <p v-else-if="stage">{{stage.message}}</p>
    <details v-if="names.length"><summary>查看当前核对项（{{names.length}} 项）</summary><ul><li v-for="(name,index) in names" :key="index">{{name}}</li></ul></details>
  </section>
</template>
<style scoped>
.analysis-progress{grid-column:1/-1;padding:12px 14px;border:1px solid var(--c-line,#e5eaf1);border-radius:8px;background:var(--c-surface,#fff);color:var(--c-ink,#26384d);font-size:13px}.line{display:flex;justify-content:space-between;gap:16px}.line span,p{color:var(--c-muted,#738196)}progress{display:block;width:100%;height:7px;margin-top:10px;accent-color:var(--c-primary,#1b65a8)}p{margin:8px 0 0;line-height:1.7}details{margin-top:8px}summary{color:var(--c-primary,#1b65a8);cursor:pointer}li{margin:6px 0;line-height:1.5}
</style>
