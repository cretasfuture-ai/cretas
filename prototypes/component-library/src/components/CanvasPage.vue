<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { VueFlow, Handle, Position, useVueFlow } from '@vue-flow/core'
import type { Connection, Edge, Node, NodeMouseEvent, EdgeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import type { Json, SampleData } from '../types'
import { notify, protectEdit, setEditGuard, state } from '../state'
import Icon from './Icon.vue'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
type NodeData = { label: string; category: string; order: number; unit: string; raw: Record<string, Json>; draft?: boolean }
type CanvasNode = Node<NodeData> & { selected?: boolean }
type Graph = { nodes: CanvasNode[]; edges: Edge[] }
const props = defineProps<{ data: SampleData }>()
const initial = props.data.workflows.find(w => w.nodes.length >= 5 && w.nodes.length <= 7) || props.data.workflows[0]
const requestedId = sessionStorage.getItem('cretas-v5-canvas-target'); sessionStorage.removeItem('cretas-v5-canvas-target')
const workflowId = ref(props.data.workflows.some(w => w.id === requestedId) ? requestedId! : initial?.id || ''), nodes = shallowRef<CanvasNode[]>([]), edges = shallowRef<Edge[]>([])
const selectedId = ref(''), selectedEdgeId = ref(''), library = ref(false), full = ref(false), rawOpen = ref(false), localOverride = ref(false)
const processSearch = ref(''), nextTarget = ref(''), tool = ref('select'), snap = ref(true), dragging = ref(false), dropOver = ref(false)
const history = ref<string[]>([]), future = ref<string[]>([]), savedGraph = ref(''), resetOpen = ref(false), helpOpen = ref(false), validationOpen = ref(false)
const flowArea = ref<HTMLElement>(), context = ref<{ x: number; y: number } | null>(null)
const form = reactive({ label: '', category: '', unit: '', order: 1 }), edgeLabel = ref('')
const { fitView, zoomIn, zoomOut, viewport, screenToFlowCoordinate, setCenter, zoomTo } = useVueFlow({ id: 'cretas-process-sample' })
const workflow = computed(() => props.data.workflows.find(w => w.id === workflowId.value))
const selected = computed(() => nodes.value.find(n => n.id === selectedId.value))
const selectedEdge = computed(() => edges.value.find(e => e.id === selectedEdgeId.value))
const selectedNodes = computed(() => nodes.value.filter(n => n.selected || n.id === selectedId.value))
const processList = computed(() => props.data.processes.filter(p => `${p.processName} ${p.category || ''} ${p.processCode || ''}`.toLowerCase().includes(processSearch.value.toLowerCase())))
const choices = computed(() => props.data.workflows.map(w => ({ label: `${w.name} · ${w.nodes.length} 道工序 · #${w.id}`, value: w.id })))
const duration = () => state.motion === 'off' ? 0 : state.motion === 'light' ? 80 : state.duration
function serialize() { return JSON.stringify({ nodes: nodes.value.map(n => ({ id: n.id, type: n.type, position: { ...n.position }, data: n.data })), edges: edges.value.map(e => ({ id: e.id, source: e.source, target: e.target, sourceHandle: e.sourceHandle || null, targetHandle: e.targetHandle || null, type: e.type, label: e.label || '', data: e.data, style: e.data?.localDraft ? { stroke: '#9b7029', strokeDasharray: '5 4', strokeWidth: 2 } : undefined })) }) }
const dirty = computed(() => serialize() !== savedGraph.value)
function remember() { const value = serialize(); if (history.value.at(-1) !== value) history.value.push(value); if (history.value.length > 80) history.value.shift(); future.value = []; localOverride.value = true }
function clearSelection() { selectedId.value = ''; selectedEdgeId.value = ''; nodes.value = nodes.value.map(n => ({ ...n, selected: false })); edges.value = edges.value.map(e => ({ ...e, selected: false })); context.value = null }
async function load() {
  if (!workflow.value) return
  clearSelection(); history.value = []; future.value = []; let saved: Graph | null = null
  try { const text = localStorage.getItem('cretas-v5-canvas-' + workflowId.value); if (text) { const value = JSON.parse(text); if (!Array.isArray(value.nodes) || !Array.isArray(value.edges)) throw Error('Invalid draft'); saved = value } }
  catch { notify('本机画布草稿无法读取，已显示源快照；未覆盖损坏存档。') }
  localOverride.value = !!saved
  nodes.value = saved?.nodes || workflow.value.nodes.map(n => ({ id: n.id, type: 'process', position: { x: n.x, y: n.y }, data: { label: n.label, category: String(n.raw.processCategory || '生产工序'), order: Number(n.raw.processOrder) || 1, unit: String(n.raw.plannedUnit || ''), raw: n.raw } }))
  edges.value = saved?.edges || workflow.value.edges.map(e => ({ ...e, type: 'smoothstep', data: { materialName: e.label, provenance: 'snapshot-derived' } }))
  savedGraph.value = serialize(); validationOpen.value = false
  await nextTick(); setTimeout(() => fitView({ padding: 0.22, duration: duration() }), 80)
}
watch(workflowId, load, { immediate: true })
function chooseWorkflow(id: string) { if (id !== workflowId.value) protectEdit(() => workflowId.value = id) }
watch(selectedId, () => { const d = selected.value?.data; if (d) Object.assign(form, { label: d.label, category: d.category, unit: d.unit, order: d.order }); nextTarget.value = '' })
watch(selectedEdgeId, () => edgeLabel.value = String(selectedEdge.value?.label || ''))
function clickNode(e: NodeMouseEvent) { selectedId.value = e.node.id; selectedEdgeId.value = ''; context.value = null }
function clickEdge(e: EdgeMouseEvent) { selectedEdgeId.value = e.edge.id; selectedId.value = ''; context.value = null }
function applyProperties() {
  if (!selected.value) return
  if (!form.label.trim()) { form.label = selected.value.data!.label; return notify('工序名称不能为空。') }
  const patch = { label: form.label.trim(), category: form.category.trim(), unit: form.unit.trim(), order: Math.max(1, Number(form.order) || 1) }
  if (Object.entries(patch).every(([k,v]) => selected.value!.data![k as keyof NodeData] === v)) return
  remember(); nodes.value = nodes.value.map(n => n.id === selectedId.value ? { ...n, data: { ...n.data!, ...patch, draft: true } } : n)
}
function canConnect(c: Connection) {
  if (!c.source || !c.target || c.source === c.target || edges.value.some(e => e.source === c.source && e.target === c.target)) return false
  const visit = (id: string, seen = new Set<string>()): boolean => { if (id === c.source) return true; if (seen.has(id)) return false; seen.add(id); return edges.value.filter(e => e.source === id).some(e => visit(e.target, seen)) }
  return !visit(c.target)
}
function validateConnection(c: Connection) { return edges.value.some(e => e.source === c.source && e.target === c.target) || canConnect(c) }
function connect(c: Connection) { if (!canConnect(c)) return notify('无法连接：不能连接自身、重复关系或形成循环。'); remember(); edges.value = [...edges.value, { id: 'draft-edge-' + crypto.randomUUID(), source: c.source!, target: c.target!, type: 'smoothstep', label: '草稿连接', data: { localDraft: true, provenance: 'local-draft' }, style: { stroke: '#9b7029', strokeDasharray: '5 4', strokeWidth: 2 } }]; notify('已添加草稿连接，点击连线可编辑名称或删除。') }
function addProcess(id: string, position?: { x: number; y: number }) {
  const p = props.data.processes.find(p => p.id === id); if (!p) return
  remember(); const newId = 'draft-' + crypto.randomUUID(), rect = flowArea.value?.getBoundingClientRect()
  const at = position || (rect ? screenToFlowCoordinate({ x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }) : { x: 150, y: 300 })
  clearSelection(); nodes.value = [...nodes.value, { id: newId, type: 'process', selected: true, position: { x: snap.value ? Math.round(at.x / 20) * 20 : at.x, y: snap.value ? Math.round(at.y / 20) * 20 : at.y }, data: { label: p.processName, category: p.category || '生产工序', order: nodes.value.length + 1, unit: p.unit || '', raw: p.raw, draft: true } }]
  selectedId.value = newId; notify('已加入工序。可拖动定位，或从右侧圆点连接下游。')
}
function startLibraryDrag(e: DragEvent, id: string) { e.dataTransfer?.setData('application/cretas-process', id); if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy' }
function dropProcess(e: DragEvent) { e.preventDefault(); dropOver.value = false; const id = e.dataTransfer?.getData('application/cretas-process'); if (id) addProcess(id, screenToFlowCoordinate({ x: e.clientX, y: e.clientY })) }
function duplicate() { const list = selectedNodes.value; if (!list.length) return; remember(); const ids = new Map<string,string>(); const copied = list.map(n => { const id = 'draft-' + crypto.randomUUID(); ids.set(n.id, id); return { ...n, id, selected: true, position: { x: n.position.x + 40, y: n.position.y + 40 }, data: { ...n.data!, label: n.data!.label + '（副本）', draft: true } } }); const relations = edges.value.filter(e => ids.has(e.source) && ids.has(e.target)).map(e => ({ ...e, id: 'draft-edge-' + crypto.randomUUID(), source: ids.get(e.source)!, target: ids.get(e.target)!, data: { ...e.data, localDraft: true } })); clearSelection(); nodes.value = [...nodes.value, ...copied]; edges.value = [...edges.value, ...relations]; selectedId.value = copied[0].id; notify(`已复制 ${copied.length} 个工序及其内部连接。`) }
function removeSelection() { const ids = new Set(selectedNodes.value.map(n => n.id)); if (!ids.size && !selectedEdge.value) return; remember(); nodes.value = nodes.value.filter(n => !ids.has(n.id)); edges.value = edges.value.filter(e => !ids.has(e.source) && !ids.has(e.target) && e.id !== selectedEdgeId.value); clearSelection(); notify('已从草稿删除，关联连接一并移除；可以撤销。') }
function undo() { const last = history.value.pop(); if (!last) return; future.value.push(serialize()); const value: Graph = JSON.parse(last); nodes.value = value.nodes; edges.value = value.edges; clearSelection() }
function redo() { const next = future.value.pop(); if (!next) return; history.value.push(serialize()); const value: Graph = JSON.parse(next); nodes.value = value.nodes; edges.value = value.edges; clearSelection() }
function save() { applyProperties(); renameEdge(); try { const text = serialize(), key = 'cretas-v5-canvas-' + workflowId.value; localStorage.setItem(key, text); if (localStorage.getItem(key) !== text) throw Error('Readback failed'); savedGraph.value = text; localOverride.value = true; notify('画布草稿已保存并回读确认，刷新后可继续编辑。'); return true } catch { notify('保存失败，请检查本机存储。修改仍保留在当前画布。'); return false } }
async function reset() { try { localStorage.removeItem('cretas-v5-canvas-' + workflowId.value) } catch { return notify('无法清除本机存档，未恢复画布。') }; resetOpen.value = false; await load(); notify('已恢复账号快照，当前草稿已清除。') }
function renameEdge() { const e = selectedEdge.value; if (!e || edgeLabel.value === String(e.label || '')) return; remember(); edges.value = edges.value.map(x => x.id === e.id ? { ...x, label: edgeLabel.value.trim(), data: { ...x.data, localDraft: true } } : x) }
function autoLayout() {
  const indegree = new Map(nodes.value.map(n => [n.id, edges.value.filter(e => e.target === n.id).length])), ranks = new Map(nodes.value.map(n => [n.id, 0])), queue = nodes.value.filter(n => !indegree.get(n.id)).map(n => n.id)
  for (let i = 0; i < queue.length; i++) for (const e of edges.value.filter(e => e.source === queue[i])) { ranks.set(e.target, Math.max(ranks.get(e.target) || 0, (ranks.get(e.source) || 0) + 1)); indegree.set(e.target, (indegree.get(e.target) || 1) - 1); if (!indegree.get(e.target)) queue.push(e.target) }
  if (queue.length !== nodes.value.length) return notify('流程中存在循环，无法自动排列，请先检查连接。')
  remember(); const rows = new Map<number,number>(); nodes.value = nodes.value.map(n => { const rank = ranks.get(n.id) || 0, row = rows.get(rank) || 0; rows.set(rank, row + 1); return { ...n, position: { x: 80 + rank * 320, y: 80 + row * 190 } } }); nextTick(() => fitView({ padding: 0.22, duration: duration() })); notify('已按上下游关系排列；关系保持不变，可撤销。')
}
const issues = computed(() => nodes.value.filter(n => nodes.value.length > 1 && !edges.value.some(e => e.source === n.id || e.target === n.id)).map(n => ({ id: n.id, text: `${n.data?.label}：尚未连接上下游` })))
function focusNode(id: string) { const n = nodes.value.find(n => n.id === id); if (!n) return; selectedId.value = n.id; setCenter(n.position.x + 112, n.position.y + 64, { zoom: 1, duration: duration() }) }
function nodeContext(e: NodeMouseEvent) { e.event.preventDefault(); selectedId.value = e.node.id; selectedEdgeId.value = ''; const rect = flowArea.value?.getBoundingClientRect(), event = e.event as MouseEvent; if (rect) context.value = { x: Math.max(8, Math.min(event.clientX - rect.x, rect.width - 190)), y: Math.max(8, Math.min(event.clientY - rect.y, rect.height - 160)) } }
function keyboard(e: KeyboardEvent) {
  if (state.leaveOpen || state.searchOpen || state.menusOpen || state.settingsOpen || helpOpen.value || resetOpen.value || rawOpen.value || (e.target as HTMLElement)?.closest('input,textarea,select,[contenteditable="true"],[role="combobox"]')) return
  const mod = e.ctrlKey || e.metaKey, key = e.key.toLowerCase()
  if (mod && key === 's') { e.preventDefault(); save() }
  else if (mod && key === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo() }
  else if (mod && key === 'y') { e.preventDefault(); redo() }
  else if (mod && key === 'd') { e.preventDefault(); duplicate() }
  else if (mod && key === 'a') { e.preventDefault(); nodes.value = nodes.value.map(n => ({ ...n, selected: true })) }
  else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); removeSelection() }
  else if (e.key === 'Escape') { clearSelection(); full.value = false }
  else if (key === 'v') tool.value = 'select'
  else if (key === 'h') tool.value = 'pan'
  else if (e.key === '1') fitView({ padding: 0.22, duration: duration() })
}
function beforeUnload(e: BeforeUnloadEvent) { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
onMounted(() => { setEditGuard({ dirty: () => dirty.value, save, discard: () => { savedGraph.value = serialize() } }); document.addEventListener('keydown', keyboard); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { setEditGuard(null); document.removeEventListener('keydown', keyboard); window.removeEventListener('beforeunload', beforeUnload) })
const io = (value: Json | undefined): Record<string, Json>[] => Array.isArray(value) ? value.filter((x): x is Record<string, Json> => x !== null && typeof x === 'object' && !Array.isArray(x)) : []
</script>

<template>
  <div :class="['canvas-page', { 'canvas-full': full }]"><div class="page-head"><div><div class="eyebrow">生产管理 / 工艺设计</div><h1>生产工序规划</h1><p>从投入到产出，编排每一道工序。</p></div><div class="head-actions"><span class="save-state"><i :class="['dot', dirty ? 'amber' : 'green']" />{{ dirty ? '有未保存修改' : localOverride ? '本机草稿已保存' : '源方案已载入' }}</span><UButton color="neutral" variant="outline" @click="validationOpen = !validationOpen"><Icon name="check" :size="16" />检查连接</UButton><UButton @click="save" data-testid="save-canvas" title="Ctrl + S"><Icon name="file" :size="16" />保存草稿</UButton></div></div>
    <div class="canvas-context"><div class="context-product"><span class="small-label">生产方案</span><USelect :model-value="workflowId" @update:model-value="chooseWorkflow(String($event))" :items="choices" class="workflow-select" aria-label="选择工序方案" /></div><span class="source-pill"><Icon name="shield" :size="14" />{{ localOverride ? '本机草稿覆盖 · 源配置可核对' : '源投入产出关系 · 布局重建' }}</span><UButton color="neutral" variant="ghost" size="sm" @click="rawOpen = true"><Icon name="book" :size="15" />数据依据</UButton></div>
    <div :class="['canvas-workspace', { 'has-inspector': !!selected || !!selectedEdge, 'has-library': library, dragging }]">
      <aside v-if="library" class="process-library"><div class="panel-title"><span>工序库 <small>{{ processList.length }}/{{ data.processes.length }}</small></span><button aria-label="关闭工序库" @click="library = false"><Icon name="close" :size="16" /></button></div><UInput v-model="processSearch" placeholder="搜索名称、分类、编码…" aria-label="搜索工序库" /><div class="library-hint"><Icon name="hand" :size="14" />拖入画布，或点击添加</div><button v-for="p in processList" :key="p.id" class="process-library-item" draggable="true" @dragstart="startLibraryDrag($event, p.id)" @click="addProcess(p.id)" :title="p.processName + ' · 拖入画布'"><span class="process-mini-icon"><Icon name="layers" :size="15" /></span><span>{{ p.processName }}<small>{{ p.category || '未分类' }} · {{ p.unit || '单位未设' }}</small></span><Icon name="plus" :size="14" /></button><p v-if="!processList.length" class="library-hint">没有匹配工序，换个关键词试试。</p></aside>
      <div ref="flowArea" :class="['flow-area', { 'drop-over': dropOver, 'pan-tool': tool === 'pan' }]" @dragover.prevent="dropOver = true" @dragleave.self="dropOver = false" @drop="dropProcess">
        <div class="canvas-top-tools"><UButton color="neutral" variant="ghost" size="sm" :aria-pressed="library" @click="library = !library"><Icon name="plus" :size="16" />添加工序</UButton><span class="toolbar-divider" /><button :class="{ active: tool === 'select' }" aria-label="选择工具" title="选择工具 V · Shift 框选" @click="tool = 'select'"><Icon name="cursor" :size="17" /></button><button :class="{ active: tool === 'pan' }" aria-label="平移工具" title="平移工具 H · 空格临时平移" @click="tool = 'pan'"><Icon name="hand" :size="17" /></button><span class="toolbar-divider" /><button :disabled="!history.length" aria-label="撤销画布修改" title="撤销 Ctrl+Z" @click="undo"><Icon name="undo" :size="17" /></button><button :disabled="!future.length" aria-label="重做画布修改" title="重做 Ctrl+Shift+Z" @click="redo"><Icon name="redo" :size="17" /></button><span class="toolbar-divider" /><button aria-label="自动排列工序" title="按上下游自动排列" @click="autoLayout"><Icon name="layout" :size="17" /></button><button :class="{ active: snap }" :aria-pressed="snap" aria-label="网格吸附" title="20 像素网格吸附" @click="snap = !snap"><Icon name="magnet" :size="17" /></button><button aria-label="恢复账号画布" title="恢复源方案" @click="resetOpen = true"><Icon name="clock" :size="17" /></button></div>
        <VueFlow id="cretas-process-sample" v-model:nodes="nodes" v-model:edges="edges" :min-zoom="0.2" :max-zoom="2" :snap-to-grid="snap" :snap-grid="[20, 20]" :pan-on-drag="tool === 'pan' ? true : [1, 2]" :selection-on-drag="tool === 'select'" selection-key-code="Shift" :multi-selection-key-code="['Meta', 'Control']" pan-activation-key-code="Space" :nodes-draggable="tool === 'select'" :is-valid-connection="validateConnection" :default-edge-options="{ type: 'smoothstep', style: { stroke: '#8193aa', strokeWidth: 1.8 }, labelStyle: { fill: '#475569', fontSize: 11 }, labelBgStyle: { fill: '#f5f7fa', fillOpacity: 0.98 } }" :delete-key-code="null" @node-click="clickNode" @edge-click="clickEdge" @node-context-menu="nodeContext" @pane-click="clearSelection" @connect="connect" @node-drag-start="remember(); dragging = true" @node-drag-stop="dragging = false" @selection-drag-start="remember(); dragging = true" @selection-drag-stop="dragging = false" data-testid="process-canvas">
          <Background :gap="20" :size="1.2" pattern-color="#cdd6e2" />
          <template #node-process="{ data: nd, selected: isSelected }"><div :class="['process-node', { selected: isSelected, 'draft-node': nd.draft }]"><Handle type="target" :position="Position.Left" title="上游投入连接点" /><div class="node-heading"><span class="node-number">{{ String(nd.order).padStart(2, '0') }}</span><span>{{ nd.category }}</span><span v-if="nd.draft" class="local-tag">草稿</span><Icon v-else name="layers" :size="14" class="node-check" /></div><h3>{{ nd.label }}</h3><div class="node-info"><span>{{ io(nd.raw.inputs).length }} 投入 <span class="io-arrow">→</span> {{ io(nd.raw.outputs).length }} 产出</span><span>{{ nd.unit || '未设单位' }}</span></div><Handle type="source" :position="Position.Right" title="拖动连接下游工序" /></div></template>
        </VueFlow>
        <div v-if="dropOver" class="canvas-drop-hint">松开鼠标，将工序放在这里</div>
        <div v-if="selectedNodes.length > 1" class="selection-toolbar"><b>已选 {{ selectedNodes.length }} 道工序</b><button @click="duplicate"><Icon name="copy" :size="15" />复制</button><button @click="removeSelection"><Icon name="trash" :size="15" />删除</button></div>
        <div v-if="validationOpen" class="validation-panel"><div class="panel-title">{{ issues.length ? `${issues.length} 项待检查` : '结构检查通过' }}<button aria-label="关闭检查结果" @click="validationOpen = false"><Icon name="close" :size="15" /></button></div><p v-if="!issues.length">{{ nodes.length }} 道工序、{{ edges.length }} 条关系。工艺发布仍需正式业务校验。</p><button v-for="(issue, i) in issues" :key="i" @click="focusNode(issue.id)"><Icon name="info" :size="15" />{{ issue.text }}<Icon name="arrow" :size="14" /></button></div>
        <div v-if="context" class="canvas-context-menu" :style="{ left: context.x + 'px', top: context.y + 'px' }"><button @click="duplicate(); context = null"><Icon name="copy" :size="15" />复制工序<kbd>Ctrl D</kbd></button><button @click="focusNode(selectedId); context = null"><Icon name="fit" :size="15" />定位工序</button><button class="danger-text" @click="removeSelection(); context = null"><Icon name="trash" :size="15" />删除<kbd>Del</kbd></button></div>
        <div class="canvas-bottom-tools"><span>{{ nodes.length }} 个工序 <i />{{ edges.length }} 条关系 <button aria-label="查看画布快捷键" @click="helpOpen = true">快捷键 ?</button></span><div><button aria-label="缩小画布" title="缩小" @click="zoomOut({ duration: duration() })">−</button><button class="zoom-label" aria-label="还原100%缩放" title="还原 100%" @click="zoomTo(1, { duration: duration() })">{{ Math.round(viewport.zoom * 100) }}%</button><button aria-label="放大画布" title="放大" @click="zoomIn({ duration: duration() })">＋</button><span class="toolbar-divider" /><button aria-label="适应画布" title="适应所有工序 · 1" @click="fitView({ padding: 0.22, duration: duration() })"><Icon name="fit" :size="16" /></button><button :aria-label="full ? '退出专注模式' : '专注模式'" title="专注模式 · Esc 退出" @click="full = !full; nextTick(() => fitView({ padding: 0.22, duration: duration() }))"><Icon name="external" :size="16" /></button></div></div>
      </div>
      <aside v-if="selected" class="node-inspector"><div class="panel-title">工序属性<button aria-label="关闭工序属性" @click="clearSelection"><Icon name="close" :size="16" /></button></div><span class="inspector-type"><Icon name="layers" :size="16" />{{ selected.data?.draft ? '本机编辑工序' : '源方案工序' }}</span><h2>{{ selected.data?.label }}</h2><label class="property-field">显示名称<UInput v-model="form.label" aria-label="工序显示名称" @blur="applyProperties" @keydown.enter="applyProperties" /></label><div class="property-grid"><label class="property-field">分类<UInput v-model="form.category" aria-label="工序分类" @blur="applyProperties" /></label><label class="property-field">报工单位<UInput v-model="form.unit" aria-label="工序报工单位" @blur="applyProperties" /></label><label class="property-field">显示顺序<UInput v-model.number="form.order" type="number" min="1" aria-label="工序显示顺序" @blur="applyProperties" /></label></div><h4>投入物料 <small>{{ io(selected.data?.raw.inputs).length }}</small></h4><div v-for="(x, i) in io(selected.data?.raw.inputs)" :key="i" class="material-line"><i class="material-dot input" /><span>{{ x.materialName }}<small>{{ x.materialKind }}</small></span><b>{{ x.unit }}</b></div><p v-if="!io(selected.data?.raw.inputs).length" class="muted">没有收录投入字段</p><h4>产出物料 <small>{{ io(selected.data?.raw.outputs).length }}</small></h4><div v-for="(x, i) in io(selected.data?.raw.outputs)" :key="i" class="material-line"><i class="material-dot output" /><span>{{ x.materialName }}<small>{{ x.byproduct ? '副产出' : x.materialKind }}</small></span><b>{{ x.unit }}</b></div><p class="inspector-note">物料沿用源记录。名称、分类、单位和显示顺序只写入本机草稿。</p><h4>新增下游连接</h4><USelect v-model="nextTarget" :items="nodes.filter(n => n.id !== selectedId).map(n => ({ label: n.data?.label || n.id, value: n.id }))" placeholder="选择下游工序" aria-label="选择下游工序" /><UButton color="neutral" variant="outline" size="sm" :disabled="!nextTarget" @click="connect({ source: selectedId, target: nextTarget, sourceHandle: null, targetHandle: null })"><Icon name="link" :size="14" />连接到下游</UButton><div class="inspector-foot"><UButton color="neutral" variant="outline" size="sm" @click="duplicate"><Icon name="copy" :size="14" />复制工序</UButton><UButton color="error" variant="soft" size="sm" @click="removeSelection"><Icon name="trash" :size="14" />从本机草稿移除</UButton></div></aside>
      <aside v-else-if="selectedEdge" class="node-inspector edge-inspector"><div class="panel-title">连接属性<button aria-label="关闭连接属性" @click="clearSelection"><Icon name="close" :size="16" /></button></div><span class="inspector-type"><Icon name="link" :size="16" />{{ selectedEdge.data?.localDraft ? '本机草稿连接' : '源物料匹配关系' }}</span><h2>工序流转关系</h2><div class="edge-endpoints"><b>{{ nodes.find(n => n.id === selectedEdge?.source)?.data?.label }}</b><Icon name="down" /><b>{{ nodes.find(n => n.id === selectedEdge?.target)?.data?.label }}</b></div><label class="property-field">连接名称<UInput v-model="edgeLabel" aria-label="连接名称" @blur="renameEdge" @keydown.enter="renameEdge" /></label><p class="inspector-note">{{ selectedEdge.data?.materialName ? '源物料：' + selectedEdge.data.materialName : '手动连接仅为本机草稿，尚未建立正式物料规则。' }}</p><UButton color="error" variant="soft" @click="removeSelection"><Icon name="trash" :size="15" />删除连接</UButton></aside>
    </div><div class="canvas-caption"><span><Icon name="cursor" :size="14" />拖动工序 · Shift 框选 · 空格平移 · 拖动圆点连线</span><span>源物料关系保留 · 本机草稿</span></div>
  </div>
  <UModal v-model:open="resetOpen" title="恢复账号源方案？" description="将清除这个方案的本机存档和未保存修改，恢复账号快照中的工序与关系。"><template #footer><UButton color="neutral" variant="outline" @click="resetOpen = false">继续编辑</UButton><UButton color="error" @click="reset">确认恢复源方案</UButton></template></UModal>
  <UModal v-model:open="helpOpen" title="画布操作与快捷键" description="让编排流程更顺手"><template #body><div class="shortcut-grid"><span>选择 / 平移工具</span><kbd>V / H</kbd><span>临时平移</span><kbd>按住空格拖动</kbd><span>多选 / 框选</span><kbd>Ctrl 点击 / Shift 拖动</kbd><span>复制选中工序</span><kbd>Ctrl D</kbd><span>删除节点或连接</span><kbd>Delete</kbd><span>撤销 / 重做</span><kbd>Ctrl Z / Ctrl Shift Z</kbd><span>保存本机草稿</span><kbd>Ctrl S</kbd><span>适应整个画布</span><kbd>1</kbd><span>关闭选择 / 退出专注</span><kbd>Esc</kbd></div></template></UModal>
  <UModal v-model:open="rawOpen" title="工序关系的数据依据" description="真实账号快照与样板布局的边界" :ui="{ content: 'max-w-2xl' }"><template #body><div class="source-note"><Icon name="book" /><div><b>{{ workflow?.name }}</b><small>{{ workflow?.sourceKey }}</small><p>节点对应源 workflowNodeId，原始连接由投入／产出的 materialNodeId 匹配生成。坐标为样板布局重建；手动修改、节点副本和新增连接标为本机草稿，不代表源工艺配置。</p></div></div><pre class="source-json">{{ JSON.stringify(workflow?.raw, null, 2) }}</pre></template></UModal>
</template>
