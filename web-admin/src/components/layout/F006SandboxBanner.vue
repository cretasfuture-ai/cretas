<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import request from '@/api/request'
import { clearEndedSandbox, endSandbox, refreshSandbox, sandboxSession } from '@/demo/f006Sandbox'

const busy = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let mounted = true
const label = computed(() => ({ PREPARING: '正在准备隔离演示', VERIFYING: '正在恢复演示会话', READY: '隔离演示中', ENDING: '正在清空本轮演示数据', ENDED: '本轮演示已清空', FAILED: '演示已暂停，隔离仍有效' }[sandboxSession.value?.status || 'READY']))
function finish(): void {
  clearEndedSandbox()
  window.location.replace('/demo-flow')
}
async function check(): Promise<void> {
  if (!mounted || !sandboxSession.value || busy.value) return
  try {
    const session = await refreshSandbox(request)
    error.value = ''
    if (session.status === 'ENDED') { finish(); return }
  } catch { error.value = '暂时无法核对沙盒状态，业务仍保持隔离，请重试' }
  finally {
    if (mounted && sandboxSession.value) timer = setTimeout(() => void check(), sandboxSession.value.status === 'READY' ? 15000 : 2000)
  }
}
async function end(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  if (timer) clearTimeout(timer)
  try {
    const session = await endSandbox(request)
    if (session.status === 'ENDED') { finish(); return }
  } catch { error.value = '清理尚未完成，请重试；本轮不会切回正式业务' }
  finally { busy.value = false; if (mounted && sandboxSession.value) timer = setTimeout(() => void check(), 2000) }
}
onMounted((): void => { void check() })
onBeforeUnmount(() => { mounted = false; if (timer) clearTimeout(timer) })
</script>

<template>
  <section v-if="sandboxSession" class="sandbox-banner" role="status" aria-live="polite">
    <div><strong>{{ label }}</strong><span>{{ error || sandboxSession.message || '本轮单据和库存独立保存；可先查看溯源、报工和成本，结束后再清空。' }}</span></div>
    <el-button type="warning" :loading="busy || sandboxSession.status === 'ENDING'" :disabled="busy || sandboxSession.status === 'ENDING'" @click="end">{{ sandboxSession.status === 'FAILED' ? '重试结束并清空' : '结束演示并清空' }}</el-button>
  </section>
</template>

<style scoped>
.sandbox-banner{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 20px;background:#fff7e8;border-bottom:1px solid #f3d19e;color:#7a4d00}.sandbox-banner strong{display:block;font-size:14px}.sandbox-banner span{display:block;margin-top:4px;font-size:12px}.sandbox-banner :deep(.el-button){flex-shrink:0}@media(max-width:700px){.sandbox-banner{align-items:flex-start;flex-direction:column}}
</style>
