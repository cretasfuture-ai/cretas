<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/modules/auth'
import request from '@/api/request'
import AiEntryDrawer from '@/components/ai-entry/AiEntryDrawer.vue'
import { PURCHASE_ORDER_CONFIG, SALES_ORDER_CONFIG } from '@/components/ai-entry/types'
import { DEMO_STEPS, assertF006Session, createDemoState, executeStep, initializeDemo, prepareStep, type DemoField, type DemoState } from '@/demo/f006Flow'

const router = useRouter()
const authStore = useAuthStore()
const state = ref<DemoState>(createDemoState())
const currentIndex = ref(0)
const form = ref<Record<string, unknown>>({})
const logs = ref<Array<{ time: string; text: string; kind: 'info' | 'success' | 'error' }>>([])
const running = ref(false)
const autoplay = ref(false)
const paused = ref(false)
const active = ref(true)
const lastResult = ref<Record<string, unknown> | null>(null)
const optionMap = ref<Record<string, Array<{ id: string; name: string }>>>({})
const demoInitialized = ref(false)
const aiDrawerVisible = ref(false)
const aiFilling = ref(false)
let autoplayTimer: ReturnType<typeof setTimeout> | undefined
const DEMO_SKU_ID = '2a6f37c4-b0e1-55ae-b31a-ddc6815829cd'
const SESSION_KEY = 'cretas:f006-demo-flow:20261105'
// The authenticated route owns the role restriction; the page checks the tenant.
const isF006Admin = computed(() => authStore.factoryId === 'F006')
const currentStep = computed(() => DEMO_STEPS[currentIndex.value])
const currentFields = computed<DemoField[]>(() => currentStep.value ? prepareStep(state.value, currentStep.value.id).fields : [])
const progress = computed(() => Math.round(((currentIndex.value + 1) / DEMO_STEPS.length) * 100))
const phases = [
  { label: '采购 + 销售需求', steps: ['purchase-create', 'sales-order-create'] }, { label: '审批收货', steps: ['purchase-submit', 'purchase-approve', 'purchase-receive', 'purchase-receive-confirm'] },
  { label: '销售审批', steps: ['sales-order-submit', 'sales-order-approve'] }, { label: '排产领料', steps: ['production-plan-create', 'production-batch-create', 'material-requisition'] },
  { label: '六道工序', steps: ['process-report-1', 'process-report-2', 'process-report-3', 'process-report-4', 'process-report-5', 'process-report-6'] }, { label: '结算入库', steps: ['production-settle', 'finished-goods-receive'] }, { label: '发运确认', steps: ['sales-delivery-create', 'sales-shipment-create', 'sales-batch-allocate', 'sales-warehouse-confirm'] }, { label: '反向溯源', steps: ['traceability-read'] },
]
const phaseIndex = computed(() => phases.findIndex(phase => phase.steps.includes(currentStep.value?.id || '')))
const currentPhase = computed(() => phases[phaseIndex.value] || phases[0])
const isApprovalStep = computed(() => currentStep.value?.id === 'purchase-approve' || currentStep.value?.id === 'sales-order-approve')
const isComplete = computed(() => state.value.currentStep >= DEMO_STEPS.length)
const aiConfig = computed(() => currentStep.value?.id === 'sales-order-create' ? SALES_ORDER_CONFIG : PURCHASE_ORDER_CONFIG)
const entityLabels: Record<string, string> = { purchaseOrder: '采购订单', purchaseReceipt: '收货单', salesOrder: '销售订单', productionPlan: '生产计划', productionBatch: '生产批次', processReport: '工序报工', productionSettlement: '成本结算', productionWarehouseReceipt: '成品入库', salesDelivery: '销售出库', salesShipment: '发运单', salesBatchAllocation: '批次分配', traceability: '追溯结果' }
const destinationLabels: Record<string, string> = { 'purchase-create': '采购单创建后，将进入采购审批队列', 'purchase-submit': '审批通过后，仓库待收货列表会出现本单', 'purchase-receive': '收货单写入仓库，等待确认入库', 'purchase-receive-confirm': '原料库存增加，可用于后续生产', 'sales-order-create': '销售需求建立，作为本次排产来源', 'sales-order-submit': '销售单进入审批流', 'sales-order-approve': '审批完成，需求可进入生产计划', 'production-plan-create': '生产计划生成，关联销售需求', 'production-batch-create': '生产批次建立，准备工序报工', 'process-report': '工序产出写入，更新在制品数量', 'production-settle': '完工成本结算，形成成本结果', 'finished-goods-receive': '成品批次入库，可用于销售出库', 'sales-delivery-create': '销售出库单创建，等待发运', 'sales-shipment-create': '发运信息写入，形成物流轨迹', 'sales-batch-allocate': '成品批次分配完成，锁定出库来源', 'sales-warehouse-confirm': '仓库确认后，销售库存正式扣减', 'traceability-read': '展示从成品批次反向追溯到原料的链路' }
const resultFields: Record<string, string> = { orderNumber: '业务单号', orderNo: '业务单号', code: '单据编号', number: '单据编号', status: '状态', quantity: '数量', receivedQuantity: '入库数量', outputQuantity: '产出量', totalAmount: '总金额', totalCost: '总成本', unitPrice: '单价', unitCost: '单位成本', unit: '单位', knownCostSubtotal: '已归集成本', calculationStatus: '成本归集状态', salesOrderNumber: '销售单号', purchaseOrderNumber: '采购单号', finishedGoodsBatchNumber: '成品批次', createdAt: '创建时间', completedAt: '完成时间', batchNumber: '批次号' }
const safeResult = computed(() => Object.entries(lastResult.value || {}).filter(([key, value]) => resultFields[key] && value !== null && value !== undefined).map(([key, value]) => ({ label: resultFields[key], value })))
const detailRoute = computed(() => {
  const entity = currentStep.value?.entity || ''
  if (entity === 'traceability') { const batch = lastResult.value?.batchNumber || lastResult.value?.finishedGoodsBatchNumber; return batch ? `/production/traceability?batchNumber=${encodeURIComponent(String(batch))}` : '' }
  return ({ purchaseOrder: '/procurement/orders', purchaseReceipt: '/warehouse/materials?view=receiving', salesOrder: '/sales/orders', productionPlan: '/production/plans', productionBatch: '/production/batches', salesDelivery: '/sales/deliveries' }[entity] || '')
})

function addLog(text: string, kind: 'info' | 'success' | 'error' = 'info') { logs.value.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text, kind }) }
function fillStep() { form.value = Object.fromEntries(currentFields.value.map(field => [field.name, field.value ?? ''])) }
function stopPlayback() { autoplay.value = false; paused.value = false; if (autoplayTimer) { clearTimeout(autoplayTimer); autoplayTimer = undefined } }
function pausePlayback() { paused.value = true; if (autoplayTimer) clearTimeout(autoplayTimer) }
function scheduleAutoplay() { if (active.value && autoplay.value && !paused.value) autoplayTimer = setTimeout(() => void executeCurrent(), 1400) }
function persistState() { try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(state.value)) } catch { /* sessionStorage is optional */ } }
function restoreState() { try { const raw = sessionStorage.getItem(SESSION_KEY); if (!raw) return; const saved = JSON.parse(raw) as DemoState; if (saved.schemaVersion === 2 && saved.factoryId === 'F006' && saved.runKey?.startsWith('DEMO-F006-FLOW-20261105')) { state.value = saved; currentIndex.value = Math.min(saved.currentStep, DEMO_STEPS.length - 1) } else sessionStorage.removeItem(SESSION_KEY) } catch { sessionStorage.removeItem(SESSION_KEY) } }
function uniqueOptionId(field: string, name: unknown): string | undefined { const value = String(name || '').trim(); if (!value) return undefined; const options = optionMap.value[field] || []; const hits = options.filter(option => option.name === value || option.name.includes(value) || value.includes(option.name)); return hits.length === 1 ? hits[0].id : undefined }
async function handleAiFill(params: Record<string, unknown>) {
  if (aiFilling.value || !active.value) return
  const items = Array.isArray(params.items) ? params.items as Array<Record<string, unknown>> : []
  if (items.length > 1) { ElMessage.warning('演示只接受一条明细，请在 AI 对话中保留一条 DEMO 明细'); return }
  const item = items[0] || {}
  const updates: Record<string, unknown> = {}
  if (currentStep.value?.id === 'purchase-create') {
    const supplierId = uniqueOptionId('supplierId', params.supplierName); const materialId = uniqueOptionId('materialTypeId', item.materialName)
    if (params.supplierName && !supplierId) { ElMessage.warning('AI 供应商未唯一匹配 F006 候选，未填入'); return }
    if (item.materialName && !materialId) { ElMessage.warning('AI 原料未唯一匹配 F006 候选，未填入'); return }
    if (supplierId) updates.supplierId = supplierId; if (materialId) updates.materialTypeId = materialId
    if (item.quantity !== undefined) updates.quantity = item.quantity; if (item.unit !== undefined) updates.unit = item.unit; if (item.unitPrice !== undefined) updates.unitPrice = item.unitPrice
  } else if (currentStep.value?.id === 'sales-order-create') {
    const customerId = uniqueOptionId('customerId', params.customerName); if (params.customerName && !customerId) { ElMessage.warning('AI 客户未唯一匹配 F006 候选，未填入'); return }
    if (customerId) updates.customerId = customerId; updates.productTypeId = '2a6f37c4-b0e1-55ae-b31a-ddc6815829cd'
    if (item.quantity !== undefined) updates.quantity = item.quantity; if (item.unit !== undefined) updates.unit = item.unit; if (item.unitPrice !== undefined) updates.unitPrice = item.unitPrice
  }
  aiDrawerVisible.value = false; aiFilling.value = true
  try { for (const [key, value] of Object.entries(updates)) { if (!active.value) return; form.value[key] = value; await new Promise(resolve => setTimeout(resolve, 140)) } addLog('AI 已逐字段预填当前表单，请确认后再执行') } finally { aiFilling.value = false }
}
async function initializeDemoDefaults() { state.value = await initializeDemo(state.value); currentIndex.value = Math.min(state.value.currentStep, DEMO_STEPS.length - 1); demoInitialized.value = true }
async function startNewRound() { if (running.value) return; demoInitialized.value = false; lastResult.value = null; currentIndex.value = 0; state.value = createDemoState(); await initializeDemoDefaults(); fillStep(); addLog('已准备新一轮 F006 演示') }

function unwrapList(value: unknown): Array<Record<string, unknown>> {
  const outer = (value as { data?: unknown } | null)?.data ?? value
  const inner = (outer as { content?: unknown } | null)?.content ?? outer
  return Array.isArray(inner) ? inner as Array<Record<string, unknown>> : []
}
function optionRows(value: unknown, kind: 'supplier' | 'material' | 'customer' | 'product' | 'warehouse') {
  return unwrapList(value).map(row => ({
    id: String(row.id || row.supplierId || row.materialTypeId || row.customerId || row.productTypeId || row.warehouseId),
    name: String(row.name || row.supplierName || row.materialName || row.customerName || row.productName || row.warehouseName || row.code || '未命名'),
  })).filter(item => item.id && (kind !== 'product' || item.id === DEMO_SKU_ID || item.name.includes('演示')))
}
async function loadBootstrapOptions() {
  if (authStore.factoryId !== 'F006') return
  try {
    const [suppliers, materials, warehouses, customers, products] = await Promise.allSettled([
      request.get(`/${authStore.factoryId}/suppliers/active`),
      request.get(`/${authStore.factoryId}/raw-material-types?keyword=DEMO-20261105&page=1&size=100`),
      request.get(`/${authStore.factoryId}/factory/warehouses`),
      request.get(`/${authStore.factoryId}/customers/active`),
      request.get(`/${authStore.factoryId}/product-types/active`),
    ])
    const responseValue = (result: PromiseSettledResult<unknown>) => result.status === 'fulfilled' ? result.value : []
    const productOptions = optionRows(responseValue(products), 'product')
    optionMap.value = {
      supplierId: optionRows(responseValue(suppliers), 'supplier'), materialTypeId: optionRows(responseValue(materials), 'material'), warehouseId: optionRows(responseValue(warehouses), 'warehouse'),
      customerId: optionRows(responseValue(customers), 'customer'), productTypeId: [{ id: DEMO_SKU_ID, name: productOptions.find(item => item.id === DEMO_SKU_ID)?.name || '演示成品' }, ...productOptions.filter(item => item.id !== DEMO_SKU_ID)],
    }
    fillStep()
    addLog('已加载 F006 演示资料，采购与销售字段已预填')
  } catch (error) { addLog(`基础资料加载失败：${error instanceof Error ? error.message : '未知错误'}`, 'error') }
}

async function executeCurrent() {
  if (!active.value || !demoInitialized.value || !currentStep.value || running.value || state.value.currentStep !== currentIndex.value) return
  running.value = true; paused.value = false; addLog(`执行：${currentStep.value.label}`)
  try {
    assertF006Session({ factoryId: authStore.factoryId })
    const nextState = await executeStep(state.value, currentStep.value.id, form.value)
    if (!active.value) return
    state.value = nextState
    persistState()
    lastResult.value = state.value.readbacks[currentStep.value.entity] || state.value.entities[currentStep.value.entity] || null
    addLog(`完成：${currentStep.value.label}；已回读真实单据/库存`, 'success')
    if (autoplay.value && currentIndex.value < DEMO_STEPS.length - 1) { currentIndex.value += 1; fillStep(); addLog('下一步字段已预填，1.4 秒后继续；可立即暂停'); scheduleAutoplay() }
  } catch (error) {
    stopPlayback(); const message = error instanceof Error ? error.message : '执行失败'; addLog(message, 'error'); ElMessage.error(message)
  } finally { running.value = false }
}
function nextStep() {
  if (currentIndex.value >= DEMO_STEPS.length - 1 || state.value.currentStep !== currentIndex.value + 1) return
  currentIndex.value += 1; fillStep(); lastResult.value = null; addLog(`已切换到：${currentStep.value.label}`)
}
function startAutoplay() { autoplay.value = true; paused.value = false; void executeCurrent() }
function continueAutoplay() { paused.value = false; void executeCurrent() }
onMounted(() => { active.value = true; restoreState(); void initializeDemoDefaults().then(() => { fillStep(); void loadBootstrapOptions() }).catch(error => { addLog(error instanceof Error ? error.message : '演示资料加载失败', 'error'); ElMessage.error('演示资料加载失败，已停止') }); addLog(`已加载 ${DEMO_STEPS.length} 个真实业务步骤`) })
onActivated(() => { active.value = true; autoplay.value = false; paused.value = false; if (autoplayTimer) { clearTimeout(autoplayTimer); autoplayTimer = undefined }; restoreState(); currentIndex.value = Math.min(state.value.currentStep, DEMO_STEPS.length - 1); fillStep(); lastResult.value = currentStep.value ? state.value.readbacks[currentStep.value.entity] || state.value.entities[currentStep.value.entity] || null : null })
onDeactivated(() => { active.value = false; stopPlayback() })
onBeforeUnmount(() => { active.value = false; stopPlayback(); aiDrawerVisible.value = false })
defineExpose({ handleAiFill, form, executeCurrent })
</script>

<template>
  <main v-if="isF006Admin" class="demo-flow-page">
    <section class="hero"><div><div class="eyebrow"><span class="live-dot" /> F006 · 现场演示控制台</div><h1>从采购到反向追溯</h1><p>演示单据仅在 F006 工厂内创建，接口失败会立即停止连续演示。</p></div><el-tag type="warning" effect="dark">演示标识 · 真实写入</el-tag></section>
    <section class="stepper-card"><div class="phase-stepper"><div v-for="(phase, index) in phases" :key="phase.label" class="phase-item" :class="{ active: index === phaseIndex, done: index < phaseIndex }"><span>{{ index + 1 }}</span><strong>{{ phase.label }}</strong></div></div><el-progress :percentage="progress" :show-text="false" status="success" /><div class="progress-caption">第 {{ currentIndex + 1 }} / {{ DEMO_STEPS.length }} 步 · 当前阶段：{{ currentPhase.label }} · {{ currentStep.label }}</div></section>
    <section v-if="currentStep" class="work-grid">
      <el-card shadow="never" class="form-card">
        <template #header><div class="card-heading"><span>当前步骤：{{ currentStep.label }}</span><el-tag size="small">{{ currentIndex + 1 }}/{{ DEMO_STEPS.length }}</el-tag></div></template>
        <div class="action-note"><strong>当前动作</strong><span>{{ currentStep.label }}</span><strong>联动去向</strong><span>{{ destinationLabels[currentStep.id] }}</span></div>
        <div v-if="currentStep.id === 'purchase-create' && state.context?.packaging" class="packaging-line"><strong>同时采购包材</strong><span>{{ state.context.packaging.name }} · {{ state.context.finishedQuantity }}{{ state.context.packaging.unit }} · 单价 {{ state.context.packaging.unitPrice }}</span></div>
        <div v-if="currentStep.id === 'purchase-receive'" class="receipt-proof"><span class="proof-icon">▣</span><div><strong>收货凭证</strong><small>本步骤将生成 DEMO 示意凭证并关联收货单，执行后显示缩略图。</small></div></div>
         <el-form label-position="top" class="demo-form"><el-form-item v-for="field in currentFields" :key="field.name" :label="field.label"><el-select v-if="optionMap[field.name]" v-model="form[field.name]" :disabled="running" class="field-control"><el-option v-for="option in optionMap[field.name]" :key="option.id" :label="option.name" :value="option.id" /></el-select><el-input v-else-if="/Id$|Ids$/.test(field.name) && form[field.name]" :model-value="'系统自动带入'" disabled class="field-control" /><el-input v-else v-model="form[field.name]" :disabled="running" :placeholder="field.required ? '必填' : '请选择或填写'" class="field-control" /></el-form-item></el-form>
         <div class="controls"><el-button v-if="currentStep.id === 'purchase-create' || currentStep.id === 'sales-order-create'" plain type="info" :disabled="running || aiFilling" @click="aiDrawerVisible = true">AI 帮我填写</el-button><el-button type="primary" :loading="running" :disabled="!demoInitialized || isComplete" @click="executeCurrent">{{ isApprovalStep ? '执行演示审批' : '执行当前步骤' }}</el-button><el-button :disabled="running || currentIndex >= DEMO_STEPS.length - 1 || state.currentStep !== currentIndex + 1" @click="nextStep">下一步</el-button><el-button v-if="!autoplay" type="success" plain :disabled="running || isComplete" @click="startAutoplay">连续演示</el-button><el-button v-else type="warning" plain :disabled="false" @click="paused ? continueAutoplay() : pausePlayback()">{{ paused ? '继续演示' : '暂停演示' }}</el-button><el-button v-if="isComplete" type="primary" plain :disabled="running" @click="startNewRound">开始新一轮</el-button></div>
        <div v-if="autoplay && paused" class="pause-hint">已暂停：字段已预填，可先向观众解释，再继续执行。</div>
      </el-card>
      <el-card shadow="never" class="result-card"><template #header><div class="card-heading"><span>真实结果回读</span><el-tag v-if="lastResult" type="success">已成功</el-tag></div></template><div v-if="safeResult.length" class="result-grid"><div v-for="item in safeResult" :key="item.label"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></div></div><el-empty v-else description="执行后显示真实单号、数量、成本与库存回读" :image-size="80" /><el-button v-if="lastResult && detailRoute" text type="primary" @click="router.push(detailRoute)">打开对应业务页 →</el-button></el-card>
    </section>
    <section class="quick-links"><div class="quick-title">演示延伸</div><el-button plain @click="router.push('/quality/label-qc')">图片质检</el-button><el-button plain @click="router.push('/smart-bi/dashboard')">SmartBI 分析</el-button><el-button plain @click="router.push('/production/traceability?batchNumber=DEMO-20261105-PB-022')">历史全链追溯</el-button></section>
    <AiEntryDrawer :key="currentStep.id" v-model="aiDrawerVisible" :config="aiConfig" @fill-form="handleAiFill" />
    <section class="log-card"><div class="log-title">演示日志 <span>只显示业务动作与结果</span></div><div v-for="log in logs" :key="`${log.time}-${log.text}`" class="log-row" :class="`is-${log.kind}`"><time>{{ log.time }}</time><span>{{ log.text }}</span></div></section>
  </main>
  <el-result v-else icon="warning" title="仅限 F006 工厂管理员" sub-title="请切换到 F006 工厂管理员账号后访问此页面" />
</template>

<style scoped lang="scss">
.packaging-line{display:flex;gap:10px;align-items:center;padding:10px 12px;margin:-6px 0 14px;border:1px solid #dceaf6;border-radius:8px;background:#f7fbff;color:#536176;font-size:13px}.packaging-line strong{color:#1b65a8}
.approver-card{display:flex;align-items:center;gap:10px;padding:14px 18px;margin:16px 0;border:1px solid #edf2f7;border-radius:10px;background:#fff;box-shadow:0 2px 12px rgba(27,101,168,.06)}.approver-card p{margin:4px 18px 0 0;color:#7a8599;font-size:12px}.approver-user{width:160px}.approver-password{width:190px}
.demo-flow-page{min-height:100%;padding:24px;background:#f4f6f9;color:#1a2332}.hero{display:flex;align-items:flex-start;justify-content:space-between;margin-bottom:18px}.eyebrow{color:#1b65a8;font-size:13px;font-weight:700;letter-spacing:.04em}.live-dot{display:inline-block;width:8px;height:8px;margin-right:6px;border-radius:50%;background:#19a66a}h1{margin:8px 0;font-size:28px}.hero p{margin:0;color:#7a8599}.stepper-card,.form-card,.result-card,.log-card{border:1px solid #edf2f7;border-radius:10px;background:#fff;box-shadow:0 2px 12px rgba(27,101,168,.06)}.stepper-card{padding:18px 24px 10px;margin:16px 0}.phase-stepper{display:grid;grid-template-columns:repeat(8,minmax(90px,1fr));gap:8px}.phase-item{display:flex;align-items:center;gap:6px;color:#7a8599;font-size:13px;white-space:nowrap}.phase-item span{display:grid;place-items:center;width:24px;height:24px;border:1px solid #d9e2ec;border-radius:50%;font-size:12px}.phase-item.active{color:#1b65a8;font-weight:700}.phase-item.active span{border-color:#1b65a8;background:#1b65a8;color:#fff}.phase-item.done{color:#198754}.phase-item.done span{border-color:#198754;color:#198754}.stepper-card :deep(.el-progress){margin-top:16px}.progress-caption{margin-top:8px;color:#536176;font-size:12px;text-align:center}.work-grid{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(300px,.7fr);gap:16px;margin-top:16px}.card-heading{display:flex;align-items:center;justify-content:space-between;font-weight:700}.action-note{display:grid;grid-template-columns:76px 1fr;gap:8px 12px;padding:12px;margin-bottom:18px;border-left:3px solid #1b65a8;background:#f6faff;font-size:13px}.action-note strong{color:#1b65a8}.action-note span{color:#536176}.receipt-proof{display:flex;gap:10px;align-items:center;padding:10px;margin-bottom:14px;border:1px dashed #b8cbe0;border-radius:8px;background:#fbfdff}.proof-icon{display:grid;place-items:center;width:36px;height:36px;color:#1b65a8;background:#eaf3fb;border-radius:6px}.receipt-proof small{display:block;margin-top:3px;color:#7a8599}.demo-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:16px}.field-control{width:100%}.demo-form :deep(.el-form-item){margin-bottom:12px}.controls,.quick-links{display:flex;flex-wrap:wrap;gap:8px;margin-top:8px}.pause-hint{margin-top:12px;color:#946200;font-size:12px}.result-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.result-grid div{padding:10px;background:#f7fafc;border-radius:6px}.result-grid small{display:block;color:#7a8599}.result-grid strong{display:block;margin-top:4px;word-break:break-word}.quick-links{align-items:center;padding:14px 0}.quick-title{margin-right:4px;color:#536176;font-size:13px;font-weight:700}.log-card{padding:16px 20px;margin-top:16px}.log-title{margin-bottom:10px;font-weight:700}.log-title span{margin-left:8px;color:#7a8599;font-size:12px;font-weight:400}.log-row{display:flex;gap:12px;padding:7px 0;border-bottom:1px solid #f1f3f6;font-size:13px}.log-row time{width:72px;color:#9aa5b5}.log-row.is-success span{color:#198754}.log-row.is-error span{color:#c0392b}@media(max-width:1100px){.phase-stepper{grid-template-columns:repeat(4,minmax(140px,1fr));row-gap:12px}}@media(max-width:800px){.demo-flow-page{padding:16px}.hero,.work-grid{display:block}.hero .el-tag{margin-top:12px}.result-card{margin-top:16px}.phase-stepper{grid-template-columns:repeat(2,minmax(130px,1fr))}}
</style>
