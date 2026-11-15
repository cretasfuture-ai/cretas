<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { PageId, Role } from '../types'
import Icon from './Icon.vue'
import { notify, protectEdit, setEditGuard } from '../state'
const props = defineProps<{ page: PageId; role: Role; path: string; workflowCount: number }>()
const emit = defineEmits<{ design: [] }>()
const frame = ref<HTMLIFrameElement>(), ready = ref(false), failed = ref(false), reloadKey = ref(0)
const names = { plans: '生产计划', canvas: '产品工艺与工序画布', analytics: '经营驾驶舱', ai: '数据分析与 AI', platform: '平台工作区' }
const url = computed(() => { const u = new URL(props.path, location.origin); u.searchParams.set('v5business', props.role); u.searchParams.set('prototype','original'); return u.pathname+u.search })
let changedForm = false
const documentOf = () => frame.value?.contentDocument
function hasForm() { return [...(documentOf()?.querySelectorAll<HTMLElement>('.el-dialog,.el-drawer') || [])].some(el => el.getClientRects().length > 0) }
function canvasDirty() { return [...(documentOf()?.querySelectorAll('.dirty-status') || [])].some(el => /有未保存/.test(el.textContent || '')) }
function loaded() { ready.value = true; changedForm = false; try { const doc = documentOf(); failed.value = !doc?.querySelector('#app'); doc?.addEventListener('input', e => { if ((e.target as HTMLElement)?.closest('.el-dialog,.el-drawer')) changedForm = true }) } catch { failed.value = true } }
async function saveBeforeLeave() {
  if (changedForm && hasForm()) { notify('请先回到当前表单完成保存，或取消编辑，再切换页面。'); return false }
  if (!canvasDirty()) return true
  const save = [...(documentOf()?.querySelectorAll<HTMLButtonElement>('button') || [])].find(b => b.textContent?.trim() === '保存当前配置')
  if (!save || save.disabled) { notify('当前画布尚不能保存，请继续编辑并检查提示。'); return false }
  save.click()
  for (let i = 0; i < 40; i++) { await new Promise(resolve => setTimeout(resolve, 100)); if (!canvasDirty()) return true }
  notify('画布尚未确认保存，请继续编辑并检查原版校验提示。'); return false
}
onMounted(() => setEditGuard({ dirty: () => canvasDirty() || changedForm && hasForm(), save: saveBeforeLeave }))
onBeforeUnmount(() => setEditGuard(null))
function reload() { protectEdit(() => { ready.value = false; failed.value = false; reloadKey.value++ }) }
</script>
<template>
  <div class="native-business"><div class="native-business-heading page-head"><div><div class="eyebrow">原版业务结构 · 统一视觉</div><h1>{{ names[page] }}</h1><p>保留现有页面的功能、配置和操作路径，编辑在独立本机业务草稿中进行。</p></div><div class="head-actions"><span class="source-pill"><Icon name="shield" :size="14" />{{ role === 'factory' ? 'F006 工厂超级管理员' : '平台管理员' }}</span><UButton color="neutral" variant="outline" @click="reload"><Icon name="clock" :size="15" />重新载入</UButton></div></div><div class="native-business-notice"><Icon name="info" :size="15" /><span>沿用 2026-10-28 的原版 Vue 页面与账号快照。源快照不变；未收录的数据明确报错。AI 为本地演示。</span></div><div v-if="page === 'canvas'" class="native-source-availability"><span>源快照中当前选中对象没有保存画布；另有 {{ workflowCount }} 套生产计划工序可供查看与编辑探索。</span><UButton size="sm" color="neutral" variant="outline" @click="emit('design')">查看已收录工序方案<Icon name="arrow" :size="14" /></UButton></div><div class="native-frame-container"><div v-if="!ready" class="native-loading" role="status">正在载入原版业务页面…</div><div v-if="failed" class="native-loading"><p>业务页面暂未载入，请确认本机原版服务已启动。</p><UButton @click="reload">重新加载</UButton></div><iframe :key="page + role + reloadKey" ref="frame" :src="url" :title="names[page] + '完整业务优化视图'" @load="loaded" referrerpolicy="same-origin" /></div></div>
</template>
