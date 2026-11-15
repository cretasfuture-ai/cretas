import crypto from 'node:crypto'
import path from 'node:path'
import { fusionWorkspace } from './fusion-data.mjs'
import { getFusionWorkflowRevision } from './fusion-workflow.mjs'
import {bomTotals,upstreamChanges,autoReworkChanges} from './fusion-report-stock.mjs'
import {unitCode,convertQuantity,reportHash,validateInputs,reportOutputs,reportYield} from './fusion-report-contract.mjs'
import {allocateStock,belongsToPlan} from './fusion-stock-allocation.mjs'

const FACTORY = 'FOOD_0000_001'
const PLAN_BASE = `/api/mobile/${FACTORY}/production-plans`
const SHEET_SUFFIX = '/process-sheet'

const clone = value => value == null ? value : JSON.parse(JSON.stringify(value))
const now = () => new Date().toISOString()

function bodyJson(body) {
  if (body == null || body === '' || Buffer.isBuffer(body)&&body.length===0) return {}
  if (typeof body === 'object' && !Buffer.isBuffer(body)) return clone(body)
  try { return JSON.parse(Buffer.from(body).toString('utf8')) } catch { return null }
}

function workspaceParent(parent, root) {
  const value = parent || root
  return value && path.basename(value).toLowerCase() === 'samples-v5' ? path.dirname(value) : value
}

function ok(send, res, data, message = '已读取 FOOD_0000_001 本机工序报工。') {
  send(res, 200, { success: true, data, message, prototypeLocalOnly: true })
  return true
}

function fail(send, res, status, code, message, data = null) {
  send(res, status, { success: false, data, code, message, prototypeLocalOnly: true })
  return true
}

function isFactoryTarget(role, target) {
  return role === 'factory' && String(target || '').startsWith(`/api/mobile/${FACTORY}/`)
}

function planModel(w) { return w.models.get(PLAN_BASE) }
function planFor(w, planId) {
  const model = planModel(w)
  return model && w.row(model, planId)
}

function modelFor(w, planId) {
  return w.models.get(`${PLAN_BASE}/${planId}${SHEET_SUFFIX}/rows`) || w.ensureModel(`${PLAN_BASE}/${planId}${SHEET_SUFFIX}/rows`)
}

function rowsPath(planId) { return `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/rows` }
function configPath(planId, leaf = 'rows') { return `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/${leaf}` }

function rowKey(row, index = 0) {
  if (row?.clientRowId != null && String(row.clientRowId).trim()) return String(row.clientRowId)
  return `source:${row?.processCode || row?.processName || row?.processOrder || 'unknown'}:${row?.batchNumber || row?.productTypeId || index}`
}

function stateRows(w, planId) {
  const stored = w.state.configs[configPath(planId, 'rows')]
  if (Array.isArray(stored)) return clone(stored)
  // Workspace may have a source model for captured process-sheet rows. Keep
  // source rows separate from this session's local overlay so a new session
  // cannot mistake captured facts for local submissions.
  return []
}

function stateHistory(w, planId) {
  const stored = w.state.configs[configPath(planId, 'history')]
  return Array.isArray(stored) ? clone(stored) : []
}

function stateTombstones(w, planId) {
  const stored = w.state.configs[configPath(planId, 'row-tombstones')]
  return Array.isArray(stored) ? clone(stored) : []
}

function sourceRows(w, planId, search = '') {
  const value = sourceResponse(w, 'GET', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/rows${search}`)
  return Array.isArray(value) ? value.map((row, index) => ({ ...clone(row), clientRowId: rowKey(row, index) })) : []
}

function mergedRows(source, local, tombstones) {
  const deleted = new Set((tombstones || []).map(String))
  const localByKey = new Map((local || []).map((row, index) => [rowKey(row, index), row]))
  const merged = []
  for (const [index, row] of (source || []).entries()) {
    const key = rowKey(row, index)
    if (deleted.has(key)) continue
    merged.push(clone(localByKey.get(key) || row))
    localByKey.delete(key)
  }
  for (const row of localByKey.values()) if (!deleted.has(rowKey(row))) merged.push(clone(row))
  return merged
}

function saveState(w, planId, rows, history, method, pathname, before, after, tombstones = stateTombstones(w, planId)) {
  const model = modelFor(w, planId)
  const previousIds = new Set((before || []).map(row => String(row.clientRowId)))
  const nextIds = new Set((after || []).map(row => String(row.clientRowId)))
  for (const old of before || []) if (!nextIds.has(String(old.clientRowId))) w.set(model, old.clientRowId, old, true)
  for (const row of after || []) w.set(model, row.clientRowId, row)
  w.state.configs[configPath(planId, 'rows')] = clone(rows)
  w.state.configs[configPath(planId, 'history')] = clone(history)
  w.state.configs[configPath(planId, 'row-tombstones')] = clone(tombstones)
  w.log({ method, path: pathname, type: method === 'DELETE' ? 'delete' : previousIds.size ? 'update' : 'create', id: after?.[0]?.clientRowId || before?.[0]?.clientRowId || null, before: clone(before), after: clone(after), at: now() })
}

function sourceResponse(w, method, pathname) {
  const exact = w.read(method, pathname, null)
  if (exact?.status >= 200 && exact?.status < 300 && exact.body?.data != null) return clone(exact.body.data)
  const prefix = `${method} ${pathname.split('?')[0]}`
  const candidates = Object.entries(w.baseline.responses || {}).filter(([key, value]) => key.startsWith(prefix) && value?.status >= 200 && value.status < 300)
  const candidate = candidates.sort(([a], [b]) => a.length - b.length)[0]?.[1]
  return candidate?.body?.data == null ? null : clone(candidate.body.data)
}

function sourcePlanPrefill(w, planId) {
  return sourceResponse(w, 'GET', `${PLAN_BASE}/${planId}/settlement-prefill`)
}

function workflowDefinition(w, productTypeId, plan) {
  if(plan?.selectedWorkflowRevisionId)return getFusionWorkflowRevision(w.root,w.session,plan.selectedWorkflowRevisionId,productTypeId)
  const captured = sourceResponse(w, 'GET', `/api/mobile/${FACTORY}/product-process-workflows/${productTypeId}`)
  if (captured && Array.isArray(captured.nodes)) return captured
  const source = w.read('GET', `/api/mobile/${FACTORY}/product-process-workflows/${productTypeId}`, null)
  return source?.status === 200 && source.body?.data?.nodes ? clone(source.body.data) : null
}

function workProcessMaster(w, workProcessId) {
  const model = w.models.get(`/api/mobile/${FACTORY}/work-processes`)
  return model && w.row(model, workProcessId)
}

function portDescriptor(port, direction) {
  const materialKind = port?.materialKind || null
  const skuId = port?.skuId ?? null
  const materialName = port?.materialName ?? null
  const unit = port?.unit ?? null
  return {
    workflowPortId: port?.id ?? null,
    materialNodeId: port?.materialNodeId ?? null,
    materialKind,
    skuId,
    allowedSkuIds: Array.isArray(port?.allowedSkuIds) ? clone(port.allowedSkuIds) : (skuId ? [skuId] : []),
    materialName,
    unit,
    gramsPerUnit: port?.gramsPerUnit ?? null,
    required: port?.required === true,
    selectionGroupId: port?.selectionGroupId ?? null,
    selectionGroupLabel: port?.selectionGroupLabel ?? null,
    selectionGroupMode: port?.selectionGroupMode ?? null,
    selectionGroupMinSelections: port?.selectionGroupMinSelections ?? null,
    selectionGroupMaxSelections: port?.selectionGroupMaxSelections ?? null,
    skuResolved: Boolean(skuId && materialName != null && unit != null),
    finished: direction === 'OUTPUT' && materialKind === 'FINISHED_GOOD',
    byproduct: port?.byproduct ?? null,
    rework: port?.rework ?? null,
    defective: port?.defective ?? null,
    inputShareRatio: port?.inputShareRatio ?? null,
  }
}

function workflowBatchId(w, plan) {
  const explicit = plan?.batchId ?? plan?.workflowBatchId ?? plan?.productionBatchId
  if (explicit != null && Number.isFinite(Number(explicit))) return Number(explicit)
  const model = w.models.get(`/api/mobile/${FACTORY}/processing/batches`)
  const rows = model ? w.rows(model) : []
  const match = rows.find(row => String(row.productionPlanId ?? row.sourcePlanId ?? '') === String(plan?.id))
  return match?.id == null ? null : Number(match.id)
}

function processConfigFromWorkflow(definition, plan, w) {
  if (!definition || !Array.isArray(definition.nodes)) return null
  const processes = definition.nodes.filter(node => node?.kind === 'PROCESS').map((node, index) => {
    const data = node.data || {}
    const master = workProcessMaster(w, data.workProcessId)
    const ports = Array.isArray(data.ports) ? data.ports : []
    const inputs = ports.filter(port => String(port.direction).toUpperCase() === 'INPUT').map(port => portDescriptor(port, 'INPUT'))
    const outputs = ports.filter(port => String(port.direction).toUpperCase() === 'OUTPUT').map(port => portDescriptor(port, 'OUTPUT'))
    return {
      workflowNodeId: node.id,
      workProcessId: data.workProcessId || null,
      processName: data.processName || node.id,
      processCode: data.processCode || data.workProcessId || node.id,
      processCategory: data.processCategory ?? master?.processCategory ?? null,
      defaultCostCategory: data.defaultCostCategory ?? master?.defaultCostCategory ?? null,
      processOrder: Number(data.processOrder ?? index + 1),
      plannedUnit: data.plannedUnit ?? null,
      inputUnit: data.inputUnit ?? inputs[0]?.unit ?? null,
      outputUnit: data.outputUnit ?? outputs[0]?.unit ?? null,
      inputs,
      output: outputs[0] || null,
      outputs,
      reportingRequired: data.reportingRequired !== false,
      allowMultipleUpstreamSources: data.allowMultipleUpstreamSources === true,
      allowFinishedGoodsSource: data.allowFinishedGoodsSource === true,
      customFieldSchema: clone(data.customFieldSchema || null),
      materialBindings: clone(data.materialBindings || []),
      reworkSources:(data.reworkPolicy?.sources||[]).map(source=>{const n=definition.nodes.find(n=>n.id===source.reworkNodeId&&n.data?.isRework===true);return n?{materialNodeId:n.id,productTypeId:n.data.skuId,inputRatioPercent:Number(source.inputRatioPercent)}:null}).filter(Boolean),
    }
  }).sort((a, b) => a.processOrder - b.processOrder)
  if (!processes.length) return null
  return {
    workflowBatchId: workflowBatchId(w, plan),
    workflowInstanceId: definition.id ?? null,
    workflowId: definition.id ?? null,
    workflowDefinitionVersion: Number(definition.version) || null,
    workflowRevisionId: definition.revisionId ?? null,
    workflowRevisionHash: definition.revisionHash ?? null,
    planId: plan?.id ?? null,
    planNumber: plan?.planNumber ?? null,
    productTypeId: plan?.productTypeId ?? definition.productTypeId ?? null,
    productName: plan?.productName ?? null,
    processes,
    source: 'captured-workflow-definition',
    prototypeLocalOnly: true,
  }
}

function capturedWorkflowConfig(w, planId) {
  const prefix = `GET ${PLAN_BASE}/${planId}/process-sheet/workflow-config`
  const entries = Object.entries(w.baseline.responses || {}).filter(([key, value]) => key.startsWith(prefix) && value?.status >= 200 && value?.status < 300)
  const value = entries.sort(([a], [b]) => a.length - b.length)[0]?.[1]?.body?.data
  return value ? clone(value) : null
}

function getConfig(w, planId, plan) {
  const local = w.state.configs[configPath(planId, 'workflow-config')]
  if (local) return clone(local)
  return capturedWorkflowConfig(w, planId) || processConfigFromWorkflow(workflowDefinition(w, plan?.productTypeId,plan), plan, w)
}

function sourceInventory(w, planId, process, processOrder, yieldCard = false) {
  const suffix = yieldCard ? '/inventory/yield-card' : `/inventory?process=${encodeURIComponent(process)}${processOrder == null ? '' : `&processOrder=${encodeURIComponent(processOrder)}`}`
  return sourceResponse(w, 'GET', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}${suffix}`)
}

function localInventory(rows, process, processOrder,w) {
  return rows.filter(row => row.submissionStatus === 'SUBMITTED' && (process == null || String(row.processCode) === String(process)) && (processOrder == null || Number(row.processOrder) === Number(processOrder))).flatMap(row => {
    const quantity = Number(row.outputQuantity)
    if (!(quantity > 0) || !row.batchNumber) return []
    const batch=w.rows(w.models.get('/api/mobile/'+FACTORY+'/processing/batches')).find(item=>item.batchNumber===row.batchNumber),remaining=Number(batch?.availableQuantity??quantity);
    return [{ batchNumber: row.batchNumber, productTypeId: row.productTypeId, produced: quantity, used: quantity-remaining, remaining, status: 'ACTIVE', unitPrice: null, productTypeName: row.productName || null, productionDate: row.processDate || null, rowTotalCost: null, inputQuantity: Number(row.inputQuantity) || null, sourceBatchNumber: row.upstreamSources?.[0]?.sourceBatchNumber || null, feedQuantity: row.upstreamSources?.reduce((sum, x) => sum + (Number(x.feedQuantityKg) || 0), 0) || null, sourceProducedQuantity: null, sourceConsumedRatio: null, inheritedRawEquivalentQuantity: null, inheritedCost: null, addedCost: null, sourceBreakdowns: [], processDate: row.processDate || null, processOrder: Number(row.processOrder), processName: row.processName || null, unit: row.outputUnit || row.unit || null, stepYieldRate:reportYield(row), cumulativeYieldRate: null, costMissingReasons: ['UNIT_PRICE_UNKNOWN'] }]
  })
}

function availableMaterialBatches(w) {
  const model = w.models.get(`/api/mobile/${FACTORY}/material-batches`)
  return model ? w.rows(model).filter(row => row.factoryId === FACTORY && !row.deleted && !row.deletedAt) : []
}

function previousConsumption(rows, batchNumber) {
  return rows.filter(row => row.submissionStatus === 'SUBMITTED'&&!row.inventoryConsumed&&!row.interimSettledAt).reduce((sum, row) => sum + (row.inputAllocations || []).filter(x => String(x.batchNumber || '') === String(batchNumber)).reduce((n, x) => n + (Number(x.quantity) || 0), 0), 0)
}

function validateTenant(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return '请求体必须是对象。'
  for (const field of ['factoryId', 'tenantId', 'factory']) if (input[field] != null && String(input[field]) !== FACTORY) return '仅允许 FOOD_0000_001 工厂工作区。'
  return null
}

function validateRow(input, plan, config) {
  const errors = []
  if (!input || typeof input !== 'object' || Array.isArray(input)) return ['请求体必须是对象。']
  if (!String(input.clientRowId || '').trim()) errors.push('clientRowId 不能为空。')
  if (!String(input.processCode || '').trim()) errors.push('processCode 不能为空。')
  if (!Number.isInteger(Number(input.processOrder)) || Number(input.processOrder) < 1) errors.push('processOrder 必须是正整数。')
  if (!String(input.productTypeId || '').trim()) errors.push('productTypeId 不能为空。')
  if (input.outputQuantity == null || !Number.isFinite(Number(input.outputQuantity)) || Number(input.outputQuantity) < 0) errors.push('outputQuantity 必须是非负有限数，零值会保留为草稿。')
  for (const field of ['inputQuantity', 'potCount', 'sampleRetainQuantity']) if (input[field] != null && (!Number.isFinite(Number(input[field])) || Number(input[field]) < 0)) errors.push(`${field} 必须是非负有限数。`)
  if (plan?.factoryId && String(plan.factoryId) !== FACTORY) errors.push('计划不属于 FOOD_0000_001 工厂。')
  const process = config?.processes?.find(value => Number(value.processOrder) === Number(input.processOrder))
  const outputPort=process?.outputs?.find(row=>String(row.skuId)===String(input.productTypeId)&&(!input.workflowPortId||row.workflowPortId===input.workflowPortId)),expectedUnit=outputPort?.unit||process?.outputUnit;if(expectedUnit&&input.outputUnit!=null&&unitCode(input.outputUnit)!==unitCode(expectedUnit))errors.push(`产出单位必须匹配工艺定义的 ${expectedUnit}。`)
  const targets=process?.outputs?.map(output=>output.skuId).filter(Boolean)||[];if(targets.length&&!targets.map(String).includes(String(input.productTypeId)))errors.push('报工产出必须来自当前工艺的固定产出接口。');
  if(!process)errors.push('当前工序不在计划固定的工艺版本中。');
  if(process&&!outputPort)errors.push('产出接口与产品不匹配。');
  if(outputPort&&input.finished!==outputPort.finished)errors.push('成品标记必须与工艺产出接口一致。');
  return errors
}

function stockCheck(w,rows,input){return allocateStock(w,input,now())}

function submitResult(row, allocations, submittedAt) {
  const output = Number(row.outputQuantity)
  return { clientRowId: row.clientRowId, batchId: row.batchId||null, batchNumber: row.batchNumber || null, yieldRate:reportYield(row), rowTotalCost: null, unitPrice: null, updated: false, materialized: output > 0, warnings: ['TOTAL_COST_INCOMPLETE',...(row.inputAllocations||[]).some(a=>a.substitution)?['已按固定 BOM 的替代物料及换算系数扣减，详见实际扣料明细。']:[]], reworkAutoDeductions:row.reworkAutoDeductions||[],submissionStatus: 'SUBMITTED', inputAllocations: clone(allocations), outputs: output > 0 ? [{ clientRowId: row.clientRowId, workflowPortId: row.workflowPortId || null, materialNodeId: row.materialNodeId || null, productTypeId: row.productTypeId, batchId: row.batchId||null, batchNumber: row.batchNumber || null, quantity: output, unit: row.outputUnit || row.unit || null, rowTotalCost: null }] : [] }
}

function aggregateQuantity(rows) {
  if (!rows.length) return null
  const units = new Set(rows.map(row => row.outputUnit || row.unit || null))
  if (units.size !== 1 || units.has(null)) return null
  return rows.reduce((sum, row) => sum + Number(row.outputQuantity || 0), 0)
}

function derivedPrefill(source, rows, plan, config,w) {
  const submitted = rows.filter(row => row.submissionStatus === 'SUBMITTED' && !row.interimSettledAt)
  const finished = submitted.filter(row => row.finished)
  const inventory=w?w.rows(w.models.get(`/api/mobile/${FACTORY}/processing/batches`)):[];
  const semi = submitted.filter(row => !row.finished).map(row=>{const batch=inventory.find(b=>b.batchNumber===row.batchNumber);return {...row,outputQuantity:batch?Number(batch.availableQuantity):Number(row.outputQuantity)}}).filter(row=>row.outputQuantity>0)
  const base = source && typeof source === 'object' ? clone(source) : { prefill: {}, audit: { clean: true, issues: [] } }
  const prefill = { ...(base.prefill || {}) }
  prefill.actualFinishedQuantity = aggregateQuantity(finished)
  prefill.actualSemiFinishedQuantity = aggregateQuantity(semi)
  prefill.quantityUnit = finished.length === 1 ? (finished[0].outputUnit || finished[0].unit || null) : (new Set(finished.map(row => row.outputUnit || row.unit)).size === 1 ? finished[0]?.outputUnit || finished[0]?.unit || null : null)
  prefill.terminalOutputs = finished.map(row => ({ clientRowId: row.clientRowId, batchNumber: row.batchNumber, productTypeId: row.productTypeId, quantity: Number(row.outputQuantity), finished: true, unit: row.outputUnit || row.unit || null }))
  prefill.semiFinishedOutputs = semi.map(row => ({ clientRowId: row.clientRowId, batchNumber: row.batchNumber, productTypeId: row.productTypeId, quantity: Number(row.outputQuantity), finished: false, unit: row.outputUnit || row.unit || null }))
  const pendingConsumptions = submitted.flatMap(row => (row.inputAllocations || []).map(allocation => ({ ...clone(allocation), consumed: row.inventoryConsumed === true, consumptionStatus: row.inventoryConsumed === true ? 'CONSUMED' : 'PENDING_SETTLEMENT', processSheetRowId: row.clientRowId })))
  prefill.rawMaterialConsumptions = pendingConsumptions
  prefill.inventoryMutation = pendingConsumptions.some(item => item.consumed) ? 'PARTIAL_OR_ALREADY_CONSUMED' : 'DEFERRED_UNTIL_SETTLEMENT'
  const audit = base.audit || { clean: true, issues: [] }
  if (!Array.isArray(audit.issues)) audit.issues = []
  if (submitted.length) audit.issues = audit.issues.filter(issue => issue?.code !== 'NO_YIELD_REPORTS' && !/NO_REPORTS|没有.*报工/.test(String(issue?.message || '')))
  audit.clean = audit.clean === true && audit.issues.length === 0
  return { ...base, prefill, audit, prototypeLocalOnly: true }
}

export function getFusionSettlementPrefill(w, planId) {
  const plan = planFor(w, planId)
  if (!plan) return null
  const rows = stateRows(w, planId)
  const source = sourcePlanPrefill(w, planId)
  if (!rows.some(row => row.submissionStatus === 'SUBMITTED')) return source || derivedPrefill(null, [], plan, getConfig(w, planId, plan),w)
  return derivedPrefill(source, rows, plan, getConfig(w, planId, plan),w)
}

export async function handleFusionProcessSheet({ parent, root, role, session, method, target, body, headers, send, res }) {
  const rawTarget = String(target || '')
  if (role !== 'factory') return false
  if (/^\/api\/mobile\/[^/]+\/production-plans(?:\/|$)/.test(rawTarget) && !isFactoryTarget(role, rawTarget)) return fail(send, res, 403, 'TENANT_MISMATCH', '工序报工本机原型仅允许 FOOD_0000_001 工厂工作区。')
  if (!isFactoryTarget(role, rawTarget)) return false
  const parsed = new URL(rawTarget, 'http://prototype.local')
  const pathname = parsed.pathname
  const match = pathname.match(new RegExp(`^${PLAN_BASE}/([^/]+)${SHEET_SUFFIX}(?:/(.*))?$`))
  const prefillMatch = pathname.match(new RegExp(`^${PLAN_BASE}/([^/]+)/settlement-prefill$`))
  if (!match && !prefillMatch) return false
  const planId = match?.[1] || prefillMatch[1], tail = match?.[2] || '', input = bodyJson(body)
  if (!['GET', 'HEAD'].includes(method) && input == null) return fail(send, res, 400, 'SCHEMA', '请求体不是有效 JSON。')
  if (!['GET','HEAD'].includes(method)&&input?.productionPlanId!=null&&String(input.productionPlanId)!==String(planId))return fail(send,res,422,'PLAN_CONTEXT_MISMATCH','请求中的计划与当前报工计划不一致，未保存或扣料。')
  const w = fusionWorkspace(workspaceParent(parent, root), role, session)
  if(!w.batchDepth)w.syncShared()
  const plan = planFor(w, planId)
  if (!plan) return fail(send, res, 404, 'PLAN_NOT_FOUND', '计划不存在于当前 FOOD_0000_001 本机快照。')
  if (plan.factoryId && String(plan.factoryId) !== FACTORY) return fail(send, res, 403, 'TENANT_MISMATCH', '计划不属于 FOOD_0000_001 工厂。')
  if(method!=='GET'&&method!=='HEAD'&&tail!=='input-availability'&&(plan.isLocked||['COMPLETED','CANCELLED','CLOSED'].includes(plan.status)))return fail(send,res,409,'PLAN_NOT_EDITABLE','计划已锁定或结束，不能新增或修改报工。');
  const rows = stateRows(w, planId), history = stateHistory(w, planId)
  const config = getConfig(w, planId, plan)
  const baselineRows = sourceRows(w, planId)
  const visibleRows = mergedRows(baselineRows, rows, stateTombstones(w, planId))

  if (prefillMatch) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'settlement-prefill 仅支持 GET。')
    const hasSubmitted = rows.some(row => row.submissionStatus === 'SUBMITTED')
    return ok(send, res, getFusionSettlementPrefill(w, planId), hasSubmitted ? '已按本机正式报工行派生结单预填，并保留源审计。' : '已原样回读源 settlement-prefill。')
  }

  if (!tail) return fail(send, res, 404, 'PROCESS_SHEET_ROUTE_REQUIRED', '请指定 process-sheet 子路径。')
  if (tail === 'workflow-config') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'workflow-config 仅支持 GET。')
    if (!config) return fail(send, res, 404, 'WORKFLOW_CONFIG_NOT_CAPTURED', '当前计划没有已捕获的工艺定义，未编造工序配置。')
    if (!w.state.configs[configPath(planId, 'workflow-config')]) { w.state.configs[configPath(planId, 'workflow-config')] = clone(config); w.persist() }
    return ok(send, res, config, '已读取当前计划对应的 FOOD_0000_001 工艺配置。')
  }
  if (tail === 'rows') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'rows 仅支持 GET。')
    const process = parsed.searchParams.get('process'), processOrder = parsed.searchParams.get('processOrder')
    const filtered = rows.filter(row => !process || String(row.processCode) === String(process)).filter(row => processOrder == null || Number(row.processOrder) === Number(processOrder))
    const merged = mergedRows(sourceRows(w, planId, parsed.search ? parsed.search : ''), filtered, stateTombstones(w, planId))
    return ok(send, res, merged.map(row=>({...row,payload:row.payload||clone(row),rowStatus:row.materialized?'MATERIALIZED':'DRAFT'})), '已回读本机工序报工行。')
  }
  if (tail.startsWith('inventory')) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'inventory 仅支持 GET。')
    const yieldCard = tail === 'inventory/yield-card'
    const process = parsed.searchParams.get('process'), processOrder = parsed.searchParams.get('processOrder')
    const external=[];if(plan.planSourceType==='SECONDARY'&&plan.secondarySourceWipId){for(const [key,items]of Object.entries(w.state.configs))if(key!==rowsPath(plan.id)&&key.endsWith('/process-sheet/rows')&&Array.isArray(items))external.push(...items.filter(r=>String(r.batchId)===String(plan.secondarySourceWipId)&&r.submissionStatus==='SUBMITTED'));}const local = localInventory([...external,...rows], process, processOrder,w)
    const source = sourceInventory(w, planId, process, processOrder, yieldCard)
    return ok(send, res, local.length ? local : source || [], yieldCard ? '已读取计划级出成率库存卡。' : '已读取当前工序可用库存。')
  }
  if (tail === 'input-availability') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'input-availability 仅支持 POST。')
    const ports = Array.isArray(input) ? input : Array.isArray(input?.ports) ? input.ports : null
    if (!ports) return fail(send, res, 422, 'INPUT_AVAILABILITY_SCHEMA_INVALID', '请求必须是端口数组或包含 ports 数组。')
    const batches = availableMaterialBatches(w).filter(batch=>belongsToPlan(batch,planId))
    const data = ports.map(port => { const materialId=port.materialTypeId||port.skuId;const warehouses=w.rows(w.models.get(`/api/mobile/${FACTORY}/warehouses`)),workshopIds=new Set(warehouses.filter(row=>row.type==='WORKSHOP').map(row=>String(row.id)));const matches = batches.filter(row => (!materialId || String(row.materialTypeId) === String(materialId))&&(!port.unit||(row.unit||row.quantityUnit)===port.unit));const stock=row=>Math.max(0,Number(row.remainingQuantity??row.currentQuantity??0)-previousConsumption(rows,row.batchNumber)),eligible=matches.filter(row=>!row.expiryDate||String(row.expiryDate).slice(0,10)>=now().slice(0,10)),local=eligible.filter(row=>workshopIds.has(String(row.warehouseId))),available=local.reduce((sum,row)=>sum+stock(row),0),expired=matches.filter(row=>row.expiryDate&&String(row.expiryDate).slice(0,10)<now().slice(0,10)).reduce((sum,row)=>sum+stock(row),0);return { ...clone(port), available,availableQuantity:available,expired,elsewhere:eligible.filter(row=>!workshopIds.has(String(row.warehouseId))).map(row=>({warehouseId:row.warehouseId,warehouseName:warehouses.find(wh=>wh.id===row.warehouseId)?.name||row.warehouseId,quantity:stock(row)})),supplyScopeHint:'本机生产仓，按同物料、同单位、有效批次核对',sufficient:port.quantity==null?null:available>=Number(port.quantity),batches:local.map(row=>({materialBatchId:row.id,batchNumber:row.batchNumber,quantity:stock(row),unit:row.unit||row.quantityUnit||null,unitPrice:row.unitPrice??null}))} })
    return ok(send, res, data, '已按 FOOD_0000_001 本机库存回读投料可用量。')
  }
  if (tail === 'settlement-prefill') return fail(send, res, 404, 'PROCESS_SHEET_ROUTE_REQUIRED', '结单预填位于 production-plans/{id}/settlement-prefill。')

  const rowMatch = tail.match(/^row(?:\/([^/]+))?(?:\/(draft|submit|history))?$/)
  if (!rowMatch) return false
  const special = tail === 'row/draft' || tail === 'row/submit'
  const clientRowId = special ? null : rowMatch[1] ? decodeURIComponent(rowMatch[1]) : null
  const action = special ? tail.slice('row/'.length) : rowMatch[2]
  if (action === 'history' && clientRowId) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'history 仅支持 GET。')
    const source = sourceResponse(w, 'GET', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/row/${encodeURIComponent(clientRowId)}/history${parsed.search ? parsed.search : ''}`)
    const matches=history.filter(item=>String(item.clientRowId)===String(clientRowId)||String(item.clientRowId).startsWith(clientRowId+'#'));return ok(send,res,matches.length?matches:source||[],'已读取工序报工变更历史。')
  }
  if (method === 'GET' && clientRowId) {
    const current = visibleRows.find(row => String(row.clientRowId) === String(clientRowId)||row.multiOutputBaseRowId===clientRowId)
    return current ? ok(send, res, current, '已读取工序报工行。') : fail(send, res, 404, 'ROW_NOT_FOUND', '工序报工行不存在于当前本机工作区。')
  }
  if (method === 'DELETE' && clientRowId) {
    const group=visibleRows.filter(r=>r.multiOutputBaseRowId===clientRowId);if(group.length){if(group.some(r=>r.submissionStatus==='SUBMITTED'))return fail(send,res,409,'SUBMITTED_ROW_DELETE_FORBIDDEN','已正式提交的联产报工不能删除。');const keys=new Set(group.map(r=>r.clientRowId)),nextRows=rows.filter(r=>!keys.has(r.clientRowId)),nextHistory=[...history,...group.map(r=>({id:crypto.randomUUID(),clientRowId:r.clientRowId,operation:'DELETE',beforeValue:clone(r),afterValue:null,diffSummary:'删除本机联产草稿',createdAt:now()}))];saveState(w,planId,nextRows,nextHistory,'DELETE',pathname,group,nextRows);return ok(send,res,null,'已删除整组本机联产草稿。')}
    const current = visibleRows.find(row => String(row.clientRowId) === String(clientRowId))
    if (!current) return fail(send, res, 404, 'ROW_NOT_FOUND', '工序报工行不存在于当前本机工作区。')
    if (current.submissionStatus === 'SUBMITTED' && current.batchNumber) return fail(send, res, 409, 'SUBMITTED_ROW_DELETE_FORBIDDEN', '已正式提交的报工行不能删除；请走受控冲销流程。')
    const nextRows = rows.filter(row => String(row.clientRowId) !== String(clientRowId)), nextHistory = [...history, { id: crypto.randomUUID(), clientRowId, operation: 'DELETE', beforeValue: clone(current), afterValue: null, diffSummary: '删除本机草稿', operatorId: null, createdAt: now() }]
    const sourceDelete = baselineRows.some(row => String(rowKey(row)) === String(clientRowId)) && !rows.some(row => String(row.clientRowId) === String(clientRowId))
    const tombstones = sourceDelete ? [...new Set([...stateTombstones(w, planId), clientRowId])] : stateTombstones(w, planId).filter(key => String(key) !== String(clientRowId))
    saveState(w, planId, nextRows, nextHistory, 'DELETE', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/row/${encodeURIComponent(clientRowId)}`, [current], nextRows, tombstones)
    return ok(send, res, null, '已删除本机工序草稿。')
  }
  if (!['POST'].includes(method) || action === 'history' || clientRowId) {
    if (clientRowId && method === 'POST' && !action) return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '带 clientRowId 的写入请使用 row/draft 或 row/submit。')
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '报工行仅支持 POST/DELETE。')
  }
  if (tail !== 'row' && tail !== 'row/draft' && tail !== 'row/submit') return false
  const expandedOutputs=input?.multiOutputMember?null:reportOutputs(input,config);
  if(expandedOutputs?.length){
    const rootId=String(input.clientRowId||''),hash=reportHash(input),members=rows.filter(r=>r.multiOutputBaseRowId===rootId),outputs=expandedOutputs,process=config.processes.find(p=>Number(p.processOrder)===Number(input.processOrder));
    if(!rootId||!process||new Set(outputs.map(o=>o.workflowPortId)).size!==outputs.length)return fail(send,res,422,'MULTI_OUTPUT_SCHEMA','联产必须填写唯一且有效的产出接口。');
    if(members.some(r=>r.submissionStatus==='SUBMITTED')){if(members[0].groupRequestHash!==hash)return fail(send,res,409,'REPORT_PAYLOAD_CONFLICT','该联产行已正式报工，内容已变化，请刷新核对。');const result=submitResult(members[0],members[0].inputAllocations||[],members[0].submittedAt);result.clientRowId=rootId;result.outputs=members.flatMap(r=>submitResult(r,[],r.submittedAt).outputs);return ok(send,res,result,'已回读整组联产报工。')}
    const before=clone(w.state),shared=clone(w.sharedSnapshot),results=[];w.beginBatch();
    try{for(const [index,output]of outputs.entries()){
      const child={...input,...output,outputs:undefined,clientRowId:rootId+'#'+index,outputQuantity:output.quantity,outputUnit:output.unit,unit:output.unit,multiOutputMember:true,multiOutputBaseRowId:rootId,groupRequestHash:hash};let reply;
      await handleFusionProcessSheet({parent,root,role,session,method,target,body:Buffer.from(JSON.stringify(child)),headers,res:{},send:(_,status,body)=>reply={status,body}});
      if(!reply?.body.success){const error=new Error(reply?.body.message||'联产报工未完成。');error.reply=reply;throw error}results.push(reply.body.data);
    }w.endBatch();const result={...results[0],clientRowId:rootId,outputs:results.flatMap(r=>r.outputs||[]),inputAllocations:results.flatMap(r=>r.inputAllocations||[])};return ok(send,res,result,action==='submit'?'联产整组已保存、扣料并生成各自产出批次。':'联产整组草稿已保存。')
    }catch(e){w.state=before;w.sharedSnapshot=shared;w.pendingPersist=false;w.endBatch();const r=e.reply;return fail(send,res,r?.status||409,r?.body.code||'MULTI_OUTPUT_FAILED',e.message,r?.body.data)}
  }
  const tenantError = validateTenant(input); if (tenantError) return fail(send, res, 422, 'TENANT_MISMATCH', tenantError)
  const errors = validateRow(input, plan, config); if (errors.length) return fail(send, res, 422, 'ROW_SCHEMA_INVALID', errors.join(' '))
  const existingLocal = rows.find(row => String(row.clientRowId) === String(input.clientRowId))
  const existingSource = baselineRows.find(row => String(rowKey(row)) === String(input.clientRowId))
  const existing = existingLocal || existingSource
  if (action === 'submit' && existing?.submissionStatus === 'SUBMITTED') {if(existing.requestFingerprint&&existing.requestFingerprint!==reportHash(input))return fail(send,res,409,'REPORT_PAYLOAD_CONFLICT','该报工行已提交且内容已变化，请刷新核对。');return ok(send, res, submitResult(existing, existing.inputAllocations || [], existing.submittedAt), '该报工行已提交，返回幂等结果。')}
  if(existing?.submissionStatus==='SUBMITTED')return fail(send,res,409,'SUBMITTED_ROW_IMMUTABLE','已正式报工的行不能改回草稿，请走受控冲销流程。');
  if (action === 'submit') {
    if(!(Number(input.outputQuantity)>0))return fail(send,res,422,'ZERO_OUTPUT_DRAFT_ONLY','零产出请保留为草稿，不能生成正式库存记录。');
    try{validateInputs(w,plan,input,config)}catch(e){return fail(send,res,422,'REPORT_INPUT_CONTRACT',e.message)}
    const group=input.multiOutputMember&&input.multiOutputBaseRowId?rows.find(row=>row.submissionStatus==='SUBMITTED'&&row.multiOutputBaseRowId===input.multiOutputBaseRowId):null;
    let automatic,upstream,rework;try{automatic=bomTotals(w,plan,input,config);upstream=group?[]:upstreamChanges(w,plan,input);rework=group?{changes:[],deductions:[],weightKg:0}:autoReworkChanges(w,plan,input,config)}catch(error){return fail(send,res,409,'REPORT_STOCK_VALIDATION',error.message)}
    const stock = stockCheck(w, rows, {...input,productionPlanId:planId,...(group?{rawMaterialInputs:[],materialInputTotals:automatic.filter(row=>row.requirementKind==='包材')}:{materialInputTotals:[...(input.materialInputTotals||[]),...automatic]})})
    if (stock.error) return fail(send, res, 422, 'INPUT_SCHEMA_INVALID', stock.error)
    if (stock.shortage) return fail(send, res, 409, 'PRODUCTION_STOCK_SHORTAGE', '正式报工库存不足，未修改本机状态。', stock.shortage)
    const at = now(), batchNumber = Number(input.outputQuantity) > 0 ? (input.batchNumber || `CLK-W-${planId.slice(0, 8)}-${Number(input.processOrder)}-${crypto.createHash('sha256').update(String(input.clientRowId)).digest('hex').slice(0,16)}`) : null
    if(batchNumber&&w.rows(w.models.get(`/api/mobile/${FACTORY}/processing/batches`)).some(b=>b.batchNumber===batchNumber))return fail(send,res,409,'OUTPUT_BATCH_CONFLICT','产出批次号已被其他报工使用。');
    const materialModel=w.models.get(`/api/mobile/${FACTORY}/material-batches`),totals=new Map();for(const allocation of stock.consumed)totals.set(String(allocation.materialBatchId),(totals.get(String(allocation.materialBatchId))||0)+allocation.quantity);
    for(const [id,quantity]of totals){const old=w.row(materialModel,id),available=Number(old.remainingQuantity??old.currentQuantity??0);if(available<quantity)return fail(send,res,409,'PRODUCTION_STOCK_SHORTAGE','正式报工库存不足，未修改本机状态。');}
    for(const [id,quantity]of totals){const old=w.row(materialModel,id),available=Number(old.remainingQuantity??old.currentQuantity??0)-quantity;w.set(materialModel,id,{...old,remainingQuantity:available,currentQuantity:available,availableQuantity:available,updatedAt:at,prototypeLocalOnly:true})}
    if(!group)for(const change of upstream)w.set(change.model,change.before.id,change.after);
    for(const change of rework.changes)w.set(change.model,change.before.id,change.after);
    const batchModel=w.ensureModel(`/api/mobile/${FACTORY}/processing/batches`),batchId=Number(input.outputQuantity)>0?Math.max(Date.now(),...w.rows(batchModel).map(row=>(Number(row.id)||0)+1)):null;
    if(batchId)w.set(batchModel,batchId,{id:batchId,factoryId:FACTORY,batchNumber,productionPlanId:planId,productTypeId:input.productTypeId,productName:plan.productName,processName:input.processName,processOrder:input.processOrder,sourceDocType:'PRODUCTION_BATCH',sourceType:'PROCESS_REPORT',status:'COMPLETED',quantity:Number(input.outputQuantity),actualQuantity:Number(input.outputQuantity),availableQuantity:Number(input.outputQuantity),remainingQuantity:Number(input.outputQuantity),unit:input.outputUnit,materialCost:stock.consumed.every(row=>row.unitPrice!=null)?stock.consumed.reduce((n,row)=>n+row.quantity*Number(row.unitPrice),0):null,laborCost:null,totalCost:null,costIncomplete:true,createdAt:at,prototypeLocalOnly:true});
    const allocations=stock.consumed.map(row=>({...row,consumed:true,consumptionStatus:'CONSUMED'}));
    const port=config.processes.find(p=>Number(p.processOrder)===Number(input.processOrder))?.outputs.find(o=>o.skuId===input.productTypeId&&(!input.workflowPortId||o.workflowPortId===input.workflowPortId)),productWeight=input.productWeight??(port?.gramsPerUnit>0?Number(input.outputQuantity)*Number(port.gramsPerUnit)/1000:null);
    const next = { ...clone(existing || {}), ...clone(input), productName:port?.materialName||null,productWeight,requestFingerprint:reportHash(input),reworkAutoDeductions:rework.deductions,actualInputWeightKg:unitCode(input.inputUnit)==='kg'||unitCode(input.inputUnit)==='g'?convertQuantity(input.inputQuantity,input.inputUnit,'kg')+rework.weightKg:null,clientRowId: String(input.clientRowId), factoryId: FACTORY, planId, submissionStatus: 'SUBMITTED', submittedAt: at,batchId,batchNumber, materialized: Number(input.outputQuantity) > 0,inventoryConsumed:true, inputAllocations: clone(allocations), rowTotalCost: null, unitPrice: null, prototypeLocalOnly: true }
    const nextRows = [...rows.filter(row => String(row.clientRowId) !== String(next.clientRowId)), next], nextHistory = [...history, { id: crypto.randomUUID(), clientRowId: next.clientRowId, operation: existing ? 'UPDATE' : 'CREATE', beforeValue: clone(existing), afterValue: clone(next), diffSummary: existing ? '正式提交更新' : '正式提交', operatorId: null, createdAt: at }]
    const finishedRows=nextRows.filter(row=>row.submissionStatus==='SUBMITTED'&&row.finished),outputUnits=new Set(finishedRows.map(row=>row.outputUnit));w.set(planModel(w),plan.id,{...plan,actualQuantity:outputUnits.size===1?finishedRows.reduce((sum,row)=>sum+Number(row.outputQuantity),0):null,updatedAt:at,prototypeLocalOnly:true});
    saveState(w, planId, nextRows, nextHistory, 'POST', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/row/submit`, existing ? [existing] : [], nextRows)
    return ok(send, res, submitResult(next, allocations, at), '已正式报工：扣减本机生产仓库存并生成产出批次；缺少完整成本依据时保持为空。')
  }
  const at = now(), next = { ...clone(existing || {}), ...clone(input), clientRowId: String(input.clientRowId), factoryId: FACTORY, planId, submissionStatus: 'DRAFT', submittedAt: null, batchNumber: existing?.batchNumber || input.batchNumber || null, materialized: false, rowTotalCost: null, unitPrice: null, prototypeLocalOnly: true, updatedAt: at }
  const nextRows = [...rows.filter(row => String(row.clientRowId) !== String(next.clientRowId)), next], nextHistory = [...history, { id: crypto.randomUUID(), clientRowId: next.clientRowId, operation: existing ? 'UPDATE' : 'CREATE', beforeValue: clone(existing), afterValue: clone(next), diffSummary: existing ? '保存草稿更新' : '保存草稿', operatorId: null, createdAt: at }]
  saveState(w, planId, nextRows, nextHistory, 'POST', `${PLAN_BASE}/${planId}${SHEET_SUFFIX}/row/draft`, existing ? [existing] : [], nextRows)
  return ok(send, res, { clientRowId: next.clientRowId, batchId: null, batchNumber: null, yieldRate: null, rowTotalCost: null, unitPrice: null, updated: Boolean(existing), materialized: false, warnings: [], submissionStatus: 'DRAFT' }, '草稿已保存；未扣料且未生成产出批次。')
}

export default handleFusionProcessSheet

export function getFusionProcessConfig(w,plan){return getConfig(w,plan.id,plan)}
