<script setup lang="ts">
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import UApp from '@nuxt/ui/components/App.vue'
import UBadge from '@nuxt/ui/components/Badge.vue'
import UButton from '@nuxt/ui/components/Button.vue'
import UCard from '@nuxt/ui/components/Card.vue'
import UInput from '@nuxt/ui/components/Input.vue'
import UModal from '@nuxt/ui/components/Modal.vue'
import USelect from '@nuxt/ui/components/Select.vue'
import USlideover from '@nuxt/ui/components/Slideover.vue'
import UTextarea from '@nuxt/ui/components/Textarea.vue'
import UTooltip from '@nuxt/ui/components/Tooltip.vue'
import { zh_cn as zhCN } from '@nuxt/ui/locale'
import { fallbackSnapshot, type NuxtSnapshot, type PanelTab } from './types'
import SupplyRegions from './SupplyRegions.vue'
const ProductCostWorkspace=defineAsyncComponent(()=>import('./ProductCostWorkspace.vue'))
const costHost=ref<HTMLElement|null>(null)

const snapshot = ref<NuxtSnapshot>(fallbackSnapshot())
const bridge = computed(() => window.CretasNuxtBridge)
const contentRef = ref<HTMLElement | null>(null)
const galleryHost = ref<HTMLElement | null>(null)
const composer = ref('')
const dragging = ref(false)
const resizing = ref(false)
const dragOrigin = ref({ x: 0, y: 0, left: 0, top: 0, width: 420 })
const gallerySaved = ref(false)
const galleryName = ref('标准冻虾仁')
const galleryQty = ref(12)
const galleryUnit = ref('kg')
const galleryError = ref('')
const canvasMessage = ref('')
const galleryLoading = ref(false)
const showPreview = ref(false)
const showCancelConfirm = ref(false)
const galleryDirty = ref(false)
const galleryQuestion = ref('核对标准用量与实际耗用的区别')
const galleryAnswer = ref('')
const galleryAIError = ref('')
const galleryAIBusy = ref(false)
const overlayRef = ref<HTMLElement | null>(null)
const toolbarTarget = ref<HTMLElement | null>(null)
let hostObserver: MutationObserver | undefined
const planFilter = ref('全部')
const planAnalysed = ref(false)
const planExported = ref(false)
const canvasNodes = ref(['原料', '清洗', '包装'])
const canvasHistory = ref<string[][]>([])
const canvasFuture = ref<string[][]>([])
const canvasSettings = ref('默认布局')
const canvasSettingsOpen = ref(false)
const composition = ref(false)

const isGallery = computed(() => snapshot.value.page === 'gallery')
const panelStyle = computed(() => ({
  width: `${Math.min(Math.max(snapshot.value.panel.width || 420, 340), Math.max(340, window.innerWidth - 24))}px`,
  left: snapshot.value.panel.left == null ? undefined : `${snapshot.value.panel.left}px`,
  right: snapshot.value.panel.left == null ? '16px' : 'auto',
  top: snapshot.value.panel.top == null ? undefined : `${snapshot.value.panel.top}px`,
  bottom: snapshot.value.panel.top == null ? '16px' : 'auto',
  maxHeight: `calc(100vh - ${snapshot.value.panel.top == null ? 32 : snapshot.value.panel.top + 8}px)`,
}))
const toolbarActions = computed(() => snapshot.value.actions)

function sync(next: NuxtSnapshot): void {
  snapshot.value = next
  if (!composer.value && next.panel.question) composer.value = next.panel.question
  void nextTick(() => {
    if (contentRef.value) bridge.value?.bindContent(contentRef.value)
    if (snapshot.value.overlay && overlayRef.value) bridge.value?.attachOverlay(overlayRef.value)
  })
}

function onState(event: Event): void {
  const detail = (event as CustomEvent<NuxtSnapshot>).detail
  if (detail) sync(detail)
}

async function invoke(id: string): Promise<void> {
  await bridge.value?.invoke(id)
}
async function invokeSupply(action: { id: string }): Promise<void> { await bridge.value?.invoke(action.id) }

async function ask(): Promise<void> {
  const question = composer.value.trim()
  if (!question || snapshot.value.panel.busy) return
  bridge.value?.panelQuestion?.(question)
  composer.value = ''
  await bridge.value?.ask(question)
  if(composer.value===question){composer.value='';bridge.value?.panelQuestion?.('')}
}

function onComposerKey(event: KeyboardEvent): void {
  if (!composition.value && (event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    void ask()
  }
}

function setTab(tab: PanelTab): void { bridge.value?.panelTab(tab) }
function panelClose(): void { bridge.value?.panelClose() }

function startDrag(event: PointerEvent): void {
  if ((event.target as HTMLElement).closest('button')) return
  const panel = (event.currentTarget as HTMLElement).closest('.nuxt-ai-panel') as HTMLElement | null
  if (!panel) return
  const rect = panel.getBoundingClientRect()
  dragging.value = true
  dragOrigin.value = { x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, width: rect.width }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function moveDrag(event: PointerEvent): void {
  if (!dragging.value && !resizing.value) return
  if (dragging.value) bridge.value?.panelLayout({ left: Math.max(8, dragOrigin.value.left + event.clientX - dragOrigin.value.x), top: Math.max(8, dragOrigin.value.top + event.clientY - dragOrigin.value.y) })
  if (resizing.value) bridge.value?.panelLayout({ width: Math.min(680, Math.max(340, dragOrigin.value.width - (event.clientX - dragOrigin.value.x))) })
}

function endPointer(): void { dragging.value = false; resizing.value = false }
function startResize(event: PointerEvent): void {
  const panel = (event.currentTarget as HTMLElement).parentElement
  if (!panel) return
  const rect = panel.getBoundingClientRect()
  resizing.value = true
  dragOrigin.value = { x: event.clientX, y: event.clientY, left: rect.left, top: rect.top, width: rect.width }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}
function keyboardDrag(event: KeyboardEvent): void {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  const step = event.shiftKey ? 24 : 8
  const rect=(event.currentTarget as HTMLElement).closest('.nuxt-ai-panel')?.getBoundingClientRect()
  const delta = { left: snapshot.value.panel.left ?? rect?.left ?? 16, top: snapshot.value.panel.top ?? rect?.top ?? 16 }
  if (event.key === 'ArrowLeft') delta.left -= step
  if (event.key === 'ArrowRight') delta.left += step
  if (event.key === 'ArrowUp') delta.top -= step
  if (event.key === 'ArrowDown') delta.top += step
  bridge.value?.panelLayout({ left: Math.max(8, delta.left), top: Math.max(8, delta.top) })
}
function keyboardResize(event: KeyboardEvent): void {
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    bridge.value?.panelLayout({ width: Math.min(680, Math.max(340, snapshot.value.panel.width + (event.key === 'ArrowRight' ? 16 : -16))) })
  }
}

function saveGallery(): void {
  if (!validGallery()) return
  galleryError.value = ''
  galleryLoading.value = true
  window.setTimeout(() => {
    try {localStorage.setItem('cretas-nuxt-gallery-v1', JSON.stringify({ name: galleryName.value, quantity: galleryQty.value, unit: galleryUnit.value }));gallerySaved.value = true; galleryDirty.value = false}
    catch {galleryError.value='保存失败，当前输入已保留，请检查浏览器存储空间。';gallerySaved.value=false}
    finally {galleryLoading.value = false}
  }, 280)
}
function reloadGallery(): void {
  try {const raw = localStorage.getItem('cretas-nuxt-gallery-v1');if (!raw) return;const value=JSON.parse(raw);if(!value||typeof value.name!=='string'||!value.name.trim()||!Number.isFinite(value.quantity)||value.quantity<=0||!['kg','箱','袋'].includes(value.unit))throw Error('shape');galleryName.value=value.name;galleryQty.value=value.quantity;galleryUnit.value=value.unit;gallerySaved.value=true;galleryDirty.value=false;galleryError.value=''}catch{galleryError.value='沙盒保存内容无法读取，未覆盖当前输入。'}
}
function validGallery():boolean {const valid=!!galleryName.value.trim()&&Number.isFinite(Number(galleryQty.value))&&Number(galleryQty.value)>0&&['kg','箱','袋'].includes(galleryUnit.value);galleryError.value=valid?'':'请输入名称，并填写大于 0 的标准数量';return valid}
function requestPreview(): void {if(validGallery())showPreview.value=true}
function runGalleryAI():void {galleryAIError.value='';if(!galleryQuestion.value.trim()){galleryAIError.value='请先填写需要核对的问题。';return}if(!validGallery()){galleryAIError.value='先补齐示例BOM配置，再核对依据。';return}galleryAIBusy.value=true;setTimeout(()=>{galleryAnswer.value=`示例依据：${galleryName.value}，标准数量 ${galleryQty.value} ${galleryUnit.value}。标准用量不代表实际耗用；此沙盒没有报工记录，因此实际耗用未提供。修改配置需预览并人工确认，未自动保存。`;galleryAIBusy.value=false},280)}
function requestCancelPreview(): void { if (galleryDirty.value) showCancelConfirm.value = true; else showPreview.value = false }
function discardPreview(): void { showCancelConfirm.value = false; showPreview.value = false; reloadGallery() }
function analysePlan(): void { planAnalysed.value = true }
function exportPlanDemo(): void {
  const blob = new Blob([JSON.stringify({ filter: planFilter.value, analysed: planAnalysed.value, demo: true }, null, 2)], { type: 'application/json' })
  const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = 'cretas-plan-demo.json'; link.click(); URL.revokeObjectURL(link.href); planExported.value = true
}
function mutateCanvas(mutator: (nodes: string[]) => string[]): void { canvasHistory.value.push([...canvasNodes.value]); canvasNodes.value = mutator([...canvasNodes.value]); canvasFuture.value = [] }
function undoCanvas(): void { const prior = canvasHistory.value.pop(); if (!prior) return; canvasFuture.value.push([...canvasNodes.value]); canvasNodes.value = prior }
function redoCanvas(): void { const next = canvasFuture.value.pop(); if (!next) return; canvasHistory.value.push([...canvasNodes.value]); canvasNodes.value = next }
function saveCanvas(): void {try{localStorage.setItem('cretas-nuxt-canvas-v1', JSON.stringify({ nodes: canvasNodes.value, settings: canvasSettings.value }));canvasMessage.value='画布沙盒已保存，刷新可恢复。'}catch{canvasMessage.value='保存失败，当前节点已保留，请检查浏览器存储空间。'}}
function reloadCanvas(): void {try{const raw=localStorage.getItem('cretas-nuxt-canvas-v1');if(!raw)return;const value=JSON.parse(raw);if(!value||!Array.isArray(value.nodes)||value.nodes.length>200||!value.nodes.every((n:unknown)=>typeof n==='string'&&n.length<=100)||typeof value.settings!=='string'||value.settings.length>100)throw Error('shape');canvasNodes.value=value.nodes;canvasSettings.value=value.settings;canvasHistory.value=[];canvasFuture.value=[];canvasMessage.value='已读取保存的画布沙盒。'}catch{canvasMessage.value='保存内容无法读取，当前画布未被覆盖。'}}
function rescanHosts(): void { costHost.value=document.querySelector<HTMLElement>('#cretas-nuxt-cost-host'); toolbarTarget.value = document.querySelector<HTMLElement>('#cretas-nuxt-toolbar'); galleryHost.value = document.querySelector<HTMLElement>('#cretas-nuxt-gallery') }
function onOverlayUpdate(open: boolean): void { if (!open) bridge.value?.overlayClose() }
function attachOverlay(): void { if (overlayRef.value) bridge.value?.attachOverlay(overlayRef.value) }

onMounted(() => {
  toolbarTarget.value = document.querySelector<HTMLElement>('#cretas-nuxt-toolbar')
  galleryHost.value = document.querySelector<HTMLElement>('#cretas-nuxt-gallery')
  costHost.value=document.querySelector<HTMLElement>('#cretas-nuxt-cost-host')
  sync(window.CretasNuxtBridge?.snapshot() ?? fallbackSnapshot())
  document.addEventListener('cretas:nuxt-state', onState)
  document.addEventListener('cretas:nuxt-hosts', rescanHosts)
  hostObserver = new MutationObserver(rescanHosts)
  hostObserver.observe(document.body, { childList: true, subtree: true })
  window.addEventListener('pointerup', endPointer)
  window.addEventListener('pointercancel', endPointer)
  window.dispatchEvent(new CustomEvent('cretas:nuxt-ready'))
  reloadGallery()
  reloadCanvas()
  void nextTick(attachOverlay)
})
onBeforeUnmount(() => { document.removeEventListener('cretas:nuxt-state', onState); document.removeEventListener('cretas:nuxt-hosts', rescanHosts); window.removeEventListener('pointerup', endPointer); window.removeEventListener('pointercancel', endPointer); hostObserver?.disconnect() })
</script>

<template>
  <UApp :locale="zhCN">
    <Teleport v-if="snapshot.cost && costHost" to="#cretas-nuxt-cost-host"><ProductCostWorkspace/></Teleport>
    <Teleport v-if="toolbarTarget" to="#cretas-nuxt-toolbar">
      <div class="nuxt-toolbar" data-nuxt-region="toolbar">
        <UTooltip :ui="{content:'cretas-nuxt-surface'}" v-for="action in toolbarActions" :key="action.id" :text="action.help">
          <UButton :label="action.label" :disabled="action.disabled" :color="action.primary ? 'primary' : 'neutral'" :variant="action.primary ? 'solid' : 'outline'" size="sm" @click="invoke(action.id)" />
        </UTooltip>
      </div>
    </Teleport>

    <SupplyRegions v-if="snapshot.page === 'supply' && snapshot.regions?.length" :regions="snapshot.regions" @invoke="invokeSupply" />

    <Teleport v-if="snapshot.panel.open && !snapshot.overlay" to="body">
      <UCard class="nuxt-ai-panel" :style="panelStyle" data-nuxt-region="ai-panel">
        <template #header>
          <div class="nuxt-panel-head" role="button" tabindex="0" aria-label="拖动解读面板" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="endPointer" @pointercancel="endPointer" @keydown="keyboardDrag">
            <div><strong>{{ snapshot.panel.title || '配置解读' }}</strong><span class="nuxt-panel-subtitle">当前对象 · 只读解读</span></div>
            <UButton icon="i-lucide-x" aria-label="关闭解读面板" color="neutral" variant="ghost" size="sm" @click="panelClose" />
          </div>
        </template>
        <div v-if="snapshot.panel.contextHtml" class="nuxt-panel-context" v-html="snapshot.panel.contextHtml" />
        <div class="nuxt-panel-tabs" role="tablist">
          <UButton v-for="tab in (['answer', 'evidence', 'history'] as PanelTab[])" :key="tab" :label="tab === 'answer' ? '解答' : tab === 'evidence' ? '依据' : `历史 ${snapshot.panel.historyCount || ''}`" :variant="snapshot.panel.tab === tab ? 'soft' : 'ghost'" color="primary" size="xs" @click="setTab(tab)" />
        </div>
        <div ref="contentRef" class="nuxt-panel-content" v-html="snapshot.panel.bodyHtml" />
        <div class="nuxt-composer"><UTextarea v-model="composer" :rows="2" :maxlength="2000" aria-label="向当前对象提问" autoresize placeholder="问当前对象，例如：哪些事实需要复核？" @compositionstart="composition = true" @compositionend="composition = false" @update:model-value="bridge?.panelQuestion?.($event)" @keydown="onComposerKey" /><div class="nuxt-composer-actions"><span>Ctrl / ⌘ + Enter 发送</span><div class="nuxt-demo-row"><UButton label="刷新依据" icon="i-lucide-refresh-cw" color="neutral" variant="ghost" size="xs" @click="bridge?.refresh()" /><UButton label="发送" size="xs" :loading="snapshot.panel.busy" :disabled="!composer.trim()" @click="ask" /></div></div></div>
        <button class="nuxt-resize-handle" role="separator" aria-orientation="vertical" aria-label="调整面板宽度" tabindex="0" @pointerdown="startResize" @pointermove="moveDrag" @pointerup="endPointer" @pointercancel="endPointer" @keydown="keyboardResize" />
      </UCard>
    </Teleport>

    <Teleport v-if="isGallery && galleryHost" to="#cretas-nuxt-gallery">
      <section class="nuxt-gallery" data-nuxt-region="gallery">
        <header class="nuxt-gallery-header"><div><UBadge color="primary" variant="subtle">Nuxt UI</UBadge><h2>Cretas UI · Vue 组件</h2><p>实际组件交互样板，业务页面仍由原版模块负责。</p></div><UButton label="刷新沙盒" icon="i-lucide-refresh-cw" color="neutral" variant="outline" size="sm" @click="reloadGallery" /></header>
        <div class="nuxt-gallery-grid">
          <UCard><template #header><div class="nuxt-card-title"><span>计划工具栏</span><UBadge color="success" variant="subtle">可操作</UBadge></div></template><p class="nuxt-muted">计划 #YD-2026-081 · 标准冻虾仁</p><div class="nuxt-demo-row"><USelect :ui="{content:'cretas-nuxt-surface'}" v-model="planFilter" :items="['全部', '待处理', '已完成']" size="sm" /><UButton label="执行分析" size="sm" @click="analysePlan" /><UButton label="导出 JSON" color="neutral" variant="outline" size="sm" @click="exportPlanDemo" /><UButton label="提交" color="primary" variant="soft" size="sm" disabled /></div><p v-if="planAnalysed" class="nuxt-success">分析完成：当前筛选为「{{ planFilter }}」，仅为演示结果。</p><p v-if="planExported" class="nuxt-success">已下载 demo JSON，不写入业务数据。</p></UCard>
          <UCard><template #header><div class="nuxt-card-title"><span>画布工具</span><UBadge color="neutral" variant="subtle">状态示例</UBadge></div></template><div class="nuxt-canvas-demo"><span>{{ canvasNodes.join(' → ') }}</span><div class="nuxt-demo-row"><UButton label="新增节点" size="xs" @click="mutateCanvas(nodes => [...nodes, '质检'])" /><UButton label="撤销" size="xs" color="neutral" variant="outline" :disabled="!canvasHistory.length" @click="undoCanvas" /><UButton label="重做" size="xs" color="neutral" variant="outline" :disabled="!canvasFuture.length" @click="redoCanvas" /><UButton label="设置" size="xs" color="primary" variant="soft" @click="canvasSettingsOpen = !canvasSettingsOpen" /><UButton label="保存" size="xs" color="neutral" variant="outline" @click="saveCanvas" /><UButton label="刷新" size="xs" color="neutral" variant="ghost" @click="reloadCanvas" /></div><div v-if="canvasSettingsOpen" class="nuxt-demo-row"><UInput v-model="canvasSettings" aria-label="画布设置名称" size="xs" /><UBadge color="primary" variant="subtle">{{ canvasSettings }}</UBadge></div><p v-if="canvasMessage" role="status" class="nuxt-muted">{{canvasMessage}}</p></div></UCard>
          <UCard><template #header><div class="nuxt-card-title"><span>BOM 标准数量</span><UBadge color="warning" variant="subtle">校验</UBadge></div></template><div class="nuxt-form-grid"><label>原料名称<UInput v-model="galleryName" size="sm" @update:model-value="galleryDirty = true" /></label><label>标准数量<UInput v-model.number="galleryQty" type="number" size="sm" @update:model-value="galleryDirty = true" /></label><label>单位<USelect :ui="{content:'cretas-nuxt-surface'}" v-model="galleryUnit" :items="['kg', '箱', '袋']" size="sm" @update:model-value="galleryDirty = true" /></label></div><p v-if="galleryError" class="nuxt-error">{{ galleryError }}</p><div class="nuxt-demo-row"><UButton label="保存沙盒" :loading="galleryLoading" size="sm" @click="saveGallery" /><UButton label="预览并确认" color="neutral" variant="outline" size="sm" @click="requestPreview" /></div><p v-if="gallerySaved" class="nuxt-success">已保存到本地沙盒，可刷新后复核。</p></UCard>
          <UCard><template #header><div class="nuxt-card-title"><span>状态与权限</span><UBadge color="neutral" variant="subtle">测试控件</UBadge></div></template><div class="nuxt-demo-row"><UBadge color="success">正常</UBadge><UBadge color="warning">加载中</UBadge><UBadge color="error">错误</UBadge><UBadge color="neutral">只读</UBadge></div><div class="nuxt-demo-row"><UInput placeholder="只读示例" size="sm" disabled /><UButton label="加载中" size="sm" loading /><UButton label="无权限" size="sm" disabled /></div></UCard>
          <UCard><template #header><div class="nuxt-card-title"><span>解读到操作</span><UBadge color="neutral" variant="subtle">沙盒</UBadge></div></template><p class="nuxt-muted">使用左侧示例BOM核对，不调用模型，不读取账号记录。</p><UTextarea v-model="galleryQuestion" aria-label="沙盒核对问题" :rows="2" placeholder="描述需要核对的对象" /><div class="nuxt-demo-row"><UButton label="核对示例数据" size="sm" :loading="galleryAIBusy" @click="runGalleryAI" /><UButton label="预览配置" color="neutral" variant="outline" size="sm" :disabled="!galleryAnswer" @click="requestPreview" /></div><p v-if="galleryAIError" role="alert" class="nuxt-error">{{galleryAIError}}</p><p v-if="galleryAnswer" role="status" class="nuxt-muted">{{galleryAnswer}}</p></UCard>
        </div>
        <UModal :ui="{overlay:'cretas-nuxt-surface',content:'cretas-nuxt-surface'}" v-model:open="showPreview" title="保存前预览" description="请确认沙盒中的变更内容。" :dismissible="false"><template #body><p>将保存：{{ galleryName }} · {{ galleryQty }} {{ galleryUnit }}</p><p class="nuxt-muted">仅写入 cretas-nuxt-gallery-v1，不影响业务数据。</p></template><template #footer><UButton label="取消" color="neutral" variant="outline" @click="requestCancelPreview" /><UButton label="确认保存" @click="showPreview = false; saveGallery()" /></template></UModal>
        <UModal :ui="{overlay:'cretas-nuxt-surface',content:'cretas-nuxt-surface'}" v-model:open="showCancelConfirm" title="放弃未保存变更？" description="当前表单内容尚未保存。" :dismissible="false"><template #body><p>放弃后将恢复最近一次沙盒保存的内容。</p></template><template #footer><UButton label="继续编辑" color="neutral" variant="outline" @click="showCancelConfirm = false" /><UButton label="放弃变更" color="error" @click="discardPreview" /></template></UModal>
      </section>
    </Teleport>

    <Teleport v-if="snapshot.overlay" to="body">
      <UModal :ui="{overlay:'cretas-nuxt-surface',content:'cretas-nuxt-surface'}" v-if="snapshot.overlay.kind === 'modal'" class="nuxt-business-overlay nuxt-business-modal" :open="true" :title="snapshot.overlay.title" :dismissible="false" @update:open="onOverlayUpdate" @after:enter="attachOverlay">
        <template #body><div ref="overlayRef" class="nuxt-overlay-content" data-nuxt-overlay-content="modal" /></template>
        <template #footer><UButton v-for="action in snapshot.overlay.actions" :key="action.id" :label="action.label" :disabled="action.disabled" :color="action.primary ? 'primary' : 'neutral'" :variant="action.primary ? 'solid' : 'outline'" @click="bridge?.overlayAction(action.id)" /></template>
      </UModal>
      <USlideover :ui="{overlay:'cretas-nuxt-surface',content:'cretas-nuxt-surface'}" v-else class="nuxt-business-overlay nuxt-business-drawer" :open="true" :title="snapshot.overlay.title" :dismissible="false" @update:open="onOverlayUpdate" @after:enter="attachOverlay">
        <template #body><div ref="overlayRef" class="nuxt-overlay-content" data-nuxt-overlay-content="drawer" /></template>
        <template #footer><UButton v-for="action in snapshot.overlay.actions" :key="action.id" :label="action.label" :disabled="action.disabled" :color="action.primary ? 'primary' : 'neutral'" :variant="action.primary ? 'solid' : 'outline'" @click="bridge?.overlayAction(action.id)" /></template>
      </USlideover>
    </Teleport>
  </UApp>
</template>
