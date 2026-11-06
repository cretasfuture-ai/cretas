<script setup lang="ts">
import { computed, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/api/request'
import { serializeSandboxLifecycle } from '@/demo/f006SandboxLifecycle'
import { refreshSandbox, sandboxSession } from '@/demo/f006Sandbox'
import { endNativeDemo, finishNativeCleanup, installNativeDemoRuntime, nativePlayback, nativeProgram, nextNativeModule, pauseNativeDemo, startNativeDemo } from '@/demo/f006NativeRuntime'

const dispose=installNativeDemoRuntime(useRouter())
const props=withDefaults(defineProps<{bootstrapReady?:boolean}>(),{bootstrapReady:false})
let timer:ReturnType<typeof setTimeout>|undefined
let live=true, checking=false
const active=computed(()=>nativePlayback.enabled)
const startLabel=computed(()=>nativePlayback.running&&nativePlayback.paused?'继续':'开始')
watch(active,value=>document.body.classList.toggle('f006-native-demo-active',value),{immediate:true})
async function check(){
  if(!live||!active.value||!props.bootstrapReady||!sandboxSession.value||checking||nativePlayback.ending)return
  checking=true
  try{const sid=sandboxSession.value.sessionId;await serializeSandboxLifecycle(async()=>{if(!live||nativePlayback.ending||sandboxSession.value?.sessionId!==sid)return;const value=await refreshSandbox(request);if(value.status==='ENDED'&&!nativePlayback.ending)await finishNativeCleanup()})}
  catch{if(active.value)nativePlayback.error='暂时无法核对隔离状态，请暂停后重试'}
  finally{checking=false;if(live&&active.value&&sandboxSession.value)timer=setTimeout(()=>void check(),sandboxSession.value.status==='READY'?15000:2000)}
}
watch(()=>[active.value,props.bootstrapReady,sandboxSession.value?.sessionId],()=>{if(timer)clearTimeout(timer);void check()},{immediate:true})
async function end(){try{await endNativeDemo()}catch(error){nativePlayback.error=error instanceof Error?error.message:'清理尚未完成，请重试'}}
onBeforeUnmount(()=>{live=false;if(timer)clearTimeout(timer);dispose();document.body.classList.remove('f006-native-demo-active')})
</script>

<template>
  <Teleport to="body">
    <section v-if="active" class="f006-native-toolbar" data-native-demo-toolbar data-testid="native-demo-toolbar" :data-demo-step="nativePlayback.index" :data-demo-running="nativePlayback.running" :data-demo-paused="nativePlayback.paused" :data-demo-awaiting-next="nativePlayback.awaitingNext" :data-demo-complete="nativePlayback.complete" :data-demo-error="nativePlayback.error || undefined" aria-label="原生页面演示控制">
      <strong>正在演示</strong>
      <span class="native-status" :title="nativePlayback.error || nativePlayback.stage" aria-live="polite">{{ nativePlayback.error || (nativePlayback.preparing ? '正在准备隔离演示，请稍候' : `${nativePlayback.index+1}/${nativeProgram.length} · ${nativePlayback.label} · ${nativePlayback.stage}`) }}<b v-if="nativePlayback.remaining && !nativePlayback.paused"> · {{ nativePlayback.remaining }}秒</b></span>
      <el-button size="small" type="primary" :loading="nativePlayback.preparing" :disabled="!props.bootstrapReady || nativePlayback.ending || nativePlayback.preparing || nativePlayback.atomicRequests>0 || nativePlayback.awaitingNext || nativePlayback.complete || (nativePlayback.running&&!nativePlayback.paused)" @click="startNativeDemo">{{ startLabel }}</el-button>
      <el-button size="small" :disabled="!nativePlayback.running || nativePlayback.paused" @click="pauseNativeDemo()">暂停</el-button>
      <el-button size="small" type="success" :disabled="!nativePlayback.awaitingNext || nativePlayback.running" @click="nextNativeModule">下一模块</el-button>
      <el-button size="small" type="warning" plain :loading="nativePlayback.ending" :disabled="nativePlayback.ending||nativePlayback.preparing||nativePlayback.atomicRequests>0" @click="end">结束并清空</el-button>
    </section>
  </Teleport>
</template>

<style>
.f006-native-toolbar{position:fixed;top:0;left:0;right:0;height:48px;z-index:10000;display:flex;align-items:center;gap:10px;padding:0 16px;box-sizing:border-box;background:#fff7e8;border-bottom:1px solid #f3d19e;color:#7a4d00;font-size:13px}
.f006-native-toolbar>strong{white-space:nowrap}.f006-native-toolbar .native-status{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.f006-native-toolbar .el-button{margin-left:0;flex-shrink:0}
body.f006-native-demo-active{padding-top:48px}body.f006-native-demo-active .app-sidebar{top:48px;height:calc(100vh - 48px)}body.f006-native-demo-active .app-header{top:48px}
body.f006-native-demo-active .el-dialog.is-fullscreen{margin-top:48px;height:calc(100vh - 48px)}body.f006-native-demo-active .el-drawer{top:48px;height:calc(100vh - 48px)}
.f006-native-current-field{outline:2px solid #1b65a8!important;outline-offset:3px}
</style>
