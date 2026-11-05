<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount,watch,computed} from 'vue';
import LiveMonitor from './LiveMonitor.vue';
import SavedAnalysisReport from './SavedAnalysisReport.vue';
import {getBlacklakeLiveMonitor,type LiveMonitorReport} from '@/api/smartbi/blacklake-live';
import {getBlacklakePeriodReport,type BlacklakePeriodJob} from '@/api/smartbi/blacklake';
import {formatBlacklakeDateTime} from '@/views/smart-bi/blacklakePeriod';
import {localizeBlacklakeText} from '@/views/smart-bi/blacklakePresentation';
import {useAuthStore} from '@/store/modules/auth';
const emit=defineEmits<{(event:'data-updated'):void}>();
const state=ref<LiveMonitorReport|null>(null),report=ref<BlacklakePeriodJob|null>(null),error=ref('');
const auth=useAuthStore(),identity=computed(()=>[auth.factoryId,auth.user?.id,auth.currentRole].join(':'));
let mounted=false,timer:number|undefined,offset=0,busy=false;
async function refresh(page=offset){
  if(busy)return;busy=true;const key=identity.value;
  try{const next=await getBlacklakeLiveMonitor(page);if(!mounted||key!==identity.value)return;
    if(state.value?.lastSuccessAt&&state.value.lastSuccessAt!==next.lastSuccessAt)emit('data-updated');
    offset=page;state.value=next;error.value='';
  }catch(cause){if(mounted&&key===identity.value)error.value=cause instanceof Error?cause.message:'采集状态暂不可读';}
  finally{busy=false;}
}
async function poll(){await refresh();if(mounted)timer=window.setTimeout(()=>{void poll();},30000);}
async function open(id:string){const key=identity.value;try{const next=await getBlacklakePeriodReport(id);if(mounted&&key===identity.value)report.value=next;}catch(cause){error.value=cause instanceof Error?cause.message:'报告暂不可读';}}
watch(identity,()=>{state.value=null;report.value=null;offset=0;});
onMounted(()=>{mounted=true;void poll();});
onBeforeUnmount(()=>{mounted=false;if(timer!==undefined)window.clearTimeout(timer);});
</script>
<template>
  <section class="continuous-panel">
    <details><summary>每分钟持续采集 · {{state?.lastSuccessAt?`最近成功 ${formatBlacklakeDateTime(state.lastSuccessAt)}，已成功 ${state.successfulCycles} 轮`:'正在读取状态'}} · 查看一周记录与分析</summary><LiveMonitor :report="state" @refresh="refresh()" @history-page="refresh($event)" @open-report="open" /></details>
    <p v-if="error||state?.error" role="alert">{{error||state?.error}}</p>
    <p class="note">本机需保持开机联网；中断时间会保留记录，恢复后继续拉取，不补造缺失的分钟快照。</p>
    <el-drawer :model-value="!!report" title="问题与处理建议" size="min(100%,960px)" @close="report=null"><SavedAnalysisReport :report="report" /></el-drawer>
  </section>
</template>
<style scoped>.continuous-panel{padding:12px 16px;margin-bottom:16px;border:1px solid var(--c-line,#e5eaf0);border-radius:8px;background:var(--c-surface,#fff);font-size:13px}.continuous-panel summary{cursor:pointer;color:var(--c-primary,#1b65a8)}.continuous-panel .note{font-size:12px;color:var(--c-muted,#738196);margin:6px 0 0}.continuous-panel article{border-top:1px solid var(--c-line,#e5eaf0);padding:12px 0;line-height:1.7}</style>
