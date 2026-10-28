<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, ref, watch } from 'vue'
import FactoryReadiness from '@/views/platform/factory-review/components/FactoryReadiness.vue'
import { getMyFactoryReadiness, loadMyFactoryReadiness, type FactorySelfReadiness } from '@/api/factorySelfReadiness'
import { useAuthStore } from '@/store/modules/auth'
import { ApiError } from '@/types/api'

// A factory's own, read-only view of its build readiness. The factory is always the signed-in
// account's: a factoryId in the URL never chooses the data, and nothing here applies, publishes or
// writes. The platform review page and its write actions stay platform-only.
const authStore = useAuthStore()
const factoryId = computed(() => authStore.factoryId)
const current = ref<FactorySelfReadiness | null>(null)
const loading = ref(false)
const error = ref('')
const unavailable = ref(false)
const refreshKey = ref(0)
let sequence = 0
let alive = true

async function load() {
  const own = ++sequence
  const forFactory = factoryId.value
  current.value = null; error.value = ''; unavailable.value = false; loading.value = false
  if (!alive) return
  if (!forFactory) { error.value = '当前账号没有所属工厂，无法查看建厂就绪'; return }
  // An answer that arrives after the factory, the page or a newer request moved on is dropped.
  const stillCurrent = () => alive && own === sequence && forFactory === factoryId.value
  loading.value = true
  try {
    const next = await getMyFactoryReadiness(forFactory)
    if (!stillCurrent()) return
    current.value = next
    refreshKey.value += 1
  } catch (failure) {
    if (!stillCurrent()) return
    // The endpoint exists only while the backend's factory-assembly switch is on.
    if (failure instanceof ApiError && failure.status === 404) unavailable.value = true
    else error.value = failure instanceof Error ? failure.message : '建厂就绪状态读取失败'
  } finally {
    if (stillCurrent()) loading.value = false
  }
}

watch(factoryId, load, { immediate: true })
onDeactivated(() => { alive = false; ++sequence; current.value = null; loading.value = false })
onActivated(() => { if (!alive) { alive = true; void load() } })
onBeforeUnmount(() => { alive = false; ++sequence })
</script>

<template>
  <div class="factory-self-readiness" data-testid="factory-self-readiness">
    <div class="head">
      <div>
        <h2>建厂就绪</h2>
        <p class="hint">本页只读取本厂的建厂进度和日常业务准备情况，不会应用配置、发布工艺或修改单据。</p>
      </div>
      <div class="head-actions">
        <router-link to="/factory-readiness/facts" data-testid="self-readiness-facts-link">已确认资料与版本历史</router-link>
        <el-button :loading="loading" data-testid="self-readiness-refresh" @click="load">刷新</el-button>
      </div>
    </div>
    <el-skeleton v-if="loading" :rows="3" animated />
    <el-alert v-else-if="unavailable" data-testid="self-readiness-unavailable" type="info" :closable="false"
      title="建厂功能当前未开放" description="平台尚未为系统开启建厂功能，开启后这里会显示本厂的就绪情况。" />
    <el-alert v-else-if="error" data-testid="self-readiness-error" type="error" :closable="false" :title="error">
      <el-button @click="load">重试</el-button>
    </el-alert>
    <el-alert v-else-if="current?.state === 'NO_CANONICAL_BLUEPRINT'" data-testid="self-readiness-no-blueprint"
      type="warning" :closable="false" title="缺正式资料：本厂还没有正式建厂蓝图"
      description="就绪报告需要在平台为本厂确认正式建厂蓝图后才能生成。本页不会自动创建蓝图，也不会推算阶段或完成情况。" />
    <FactoryReadiness v-else-if="current?.state === 'READY_TO_READ'" :factory-id="factoryId"
      :blueprint-id="current.blueprint.id" :row-version="current.blueprint.rowVersion" :refresh-key="refreshKey"
      :loader="loadMyFactoryReadiness" />
  </div>
</template>

<style scoped>
.factory-self-readiness { display: flex; flex-direction: column; gap: 16px; padding: 16px; }
.head { align-items: flex-start; display: flex; gap: 12px; justify-content: space-between; }
.head h2 { font-size: 20px; margin: 0 0 4px; }
.head-actions { align-items: center; display: flex; gap: 12px; }
.hint { color: var(--el-text-color-secondary); margin: 0; }
@media (max-width: 560px) {
  .head { flex-direction: column; align-items: stretch; }
}
</style>
