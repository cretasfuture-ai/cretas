import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fusionWorkspace } from './fusion-data.mjs'

const FACTORY = 'FOOD_0000_001'
const PREFIX = '/api/mobile/FOOD_0000_001/product-process-workflows/'

const clone = (value) => value == null ? value : JSON.parse(JSON.stringify(value))
const readJson = (file, fallback) => {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) } catch { return fallback }
}
const safeSession = (session) => session == null ? 'account' : /^[A-Za-z0-9._-]{1,120}$/.test(String(session || '')) ? String(session) : 'anonymous'
const stateFile = (root, session) => path.join(root, 'private', 'fusion-state', `${safeSession(session)}.json`)
const sourceFile = (root) => path.join(root, 'private', 'fusion-workflows.json')
const responseFile = (root) => path.join(root, 'private', 'fusion-responses.json')

function stable(value) {
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${stable(value[k])}`).join(',')}}`
  return JSON.stringify(value)
}

function graphHash(definition) {
  return crypto.createHash('sha256').update(stable({
    nodes: definition.nodes || [], edges: definition.edges || [], viewport: definition.viewport || null,
  })).digest('hex')
}

function sourceDefinitions(root) {
  const raw = readJson(sourceFile(root), [])
  const entries = Array.isArray(raw) ? raw : []
  const out = new Map()
  for (const entry of entries) {
    const definition = entry?.definition || entry
    if (!definition || definition.factoryId !== FACTORY || !definition.productTypeId) continue
    out.set(String(entry.id || definition.productTypeId), clone(definition))
  }
  return out
}

function captured(root) {
  const raw = readJson(responseFile(root), {})
  const versions = new Map(), activations = new Map()
  for (const [key, value] of Object.entries(raw || {})) {
    const vm = key.match(/^GET \/api\/mobile\/FOOD_0000_001\/product-process-workflows\/([^/]+)\/versions\/(\d+)$/)
    if (vm && value?.body?.data) versions.set(`${vm[1]}:${vm[2]}`, clone(value.body.data))
    const am = key.match(/^GET \/api\/mobile\/FOOD_0000_001\/product-process-workflows\/([^/]+)\/activation$/)
    if (am && value?.status >= 200 && value.status < 300) activations.set(am[1], clone(value.body?.data ?? null))
  }
  return { versions, activations }
}

function capturedCatalog(root, session) {
  const raw = readJson(responseFile(root), {}), items = new Map()
  for (const [key, value] of Object.entries(raw || {})) {
    if (!/^GET \/api\/mobile\/FOOD_0000_001\/(?:product-types|raw-material-types)\?/.test(key)) continue
    for (const item of value?.body?.data?.content || []) if (item?.id) items.set(String(item.id), clone(item))
  }
  const w=fusionWorkspace(root,'factory',session);for(const suffix of ['product-types','raw-material-types']){const model=w.models.get('/api/mobile/FOOD_0000_001/'+suffix);if(model)for(const row of w.rows(model))items.set(String(row.id),clone(row));}
  return items
}

function sourceHistory(root, owner, current) {
  const { versions } = captured(root)
  const history = []
  const summary = readJson(responseFile(root), {})[`GET /api/mobile/FOOD_0000_001/product-process-workflows/${owner}/versions`]?.body?.data
  for (const row of Array.isArray(summary) ? summary : []) {
    const version = Number(row.definitionVersion ?? row.version)
    const definition = versions.get(`${owner}:${version}`)
    if (definition) history.push({ definition, source: true })
  }
  if (!history.some(row => Number(row.definition.version) === Number(current.version))) history.push({ definition: current, source: true })
  const seen = new Set()
  return history.filter(row => {
    const v = Number(row.definition.version)
    if (!Number.isInteger(v) || seen.has(v)) return false
    seen.add(v); return true
  }).sort((a, b) => Number(a.definition.version) - Number(b.definition.version))
}

function loadState(root, session) {
  const value = readJson(stateFile(root, session), {})
  if (!value || typeof value !== 'object' || !value.owners || typeof value.owners !== 'object') return { owners: {} }
  return value
}

function persistState(root, session, state) {
  const dir = path.dirname(stateFile(root, session))
  fs.mkdirSync(dir, { recursive: true })
  const file = stateFile(root, session), tmp = `${file}.tmp-${process.pid}-${Date.now()}`
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2), 'utf8')
  fs.renameSync(tmp, file)
}

function ownerState(root, state, owner) {
  const source = sourceDefinitions(root).get(owner)
  if (!state.owners[owner] && source) {
    state.owners[owner] = {
      versions: sourceHistory(root, owner, source),
      currentVersion: Number(source.version),
      activation: undefined,
      idempotency: {},
    }
  }
  return { owner: state.owners[owner], source }
}

function currentRow(owner) {
  const rows = owner?.versions || []
  const version = Number(owner.currentVersion)
  return rows.find(row => !row.source && Number(row.definition.version) === version) || rows.find(row => Number(row.definition.version) === version) || null
}

function sourceRow(owner, version) {
  return (owner?.versions || []).find(row => row.source && Number(row.definition.version) === Number(version)) || null
}

function response(send, res, status, body) { send(res, status, { ...body, prototypeLocalOnly: true }); return true }
function ok(send, res, data, message = '本机工艺数据') { return response(send, res, 200, { success: true, data, message }) }
function fail(send, res, status, code, message) { return response(send, res, status, { success: false, code, message }) }

function parseBody(body) {
  try { return JSON.parse(body?.toString() || '{}') } catch { return null }
}

function validation(definition, { bindings = false } = {}) {
  const errors = [], nodes = Array.isArray(definition?.nodes) ? definition.nodes : [], edges = Array.isArray(definition?.edges) ? definition.edges : []
  if (definition?.schemaVersion !== 1 || !Array.isArray(definition?.nodes) || !Array.isArray(definition?.edges)) errors.push({ code: 'SCHEMA', message: '画布 schema、节点或连线结构无效。' })
  const ids = new Set()
  for (const node of nodes) {
    if (!node?.id || ids.has(node.id)) errors.push({ code: ids.has(node?.id) ? 'DUPLICATE_NODE' : 'SCHEMA', message: '画布包含空节点或重复节点。', nodeId: node?.id })
    ids.add(node?.id)
  }
  const edgeIds = new Set(), outgoing = new Map()
  for (const edge of edges) {
    if (!edge?.id || edgeIds.has(edge.id)) errors.push({ code: 'DUPLICATE_EDGE', message: '画布包含空连线或重复连线。', edgeId: edge?.id })
    edgeIds.add(edge?.id)
    if (!ids.has(edge?.source) || !ids.has(edge?.target)) errors.push({ code: 'NODE_REFERENCE', message: '连线引用了不存在的节点。', edgeId: edge?.id })
    if (edge?.source === edge?.target) errors.push({ code: 'CYCLE', message: '工艺图不能包含循环。', edgeId: edge?.id })
    if (ids.has(edge?.source) && ids.has(edge?.target)) {
      if (!outgoing.has(edge.source)) outgoing.set(edge.source, [])
      outgoing.get(edge.source).push(edge.target)
    }
  }
  const visiting = new Set(), visited = new Set()
  const visit = (id) => {
    if (visiting.has(id)) return true
    if (visited.has(id)) return false
    visiting.add(id)
    for (const target of outgoing.get(id) || []) if (visit(target)) return true
    visiting.delete(id); visited.add(id); return false
  }
  for (const node of nodes) if (visit(node.id)) { errors.push({ code: 'CYCLE', message: '工艺图不能包含循环。' }); break }
  const materialKinds = new Set(['RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOOD', 'BYPRODUCT', 'REWORK'])
  for (const node of nodes) {
    const data = node?.data || {}
    if (materialKinds.has(node?.kind)) {
      if (bindings && (data.bound === false || !String(data.skuId || '').trim() || !String(data.baseUnit || '').trim())) errors.push({ code: 'SKU_REQUIRED', message: '物料节点必须绑定 SKU 和基本单位。', nodeId: node.id })
    }
    if (node?.kind === 'PROCESS') {
      if (!Array.isArray(data.ports) || data.ports.length === 0) errors.push({ code: 'PORT_REQUIRED', message: '工序必须至少有一个端口。', nodeId: node.id })
      const portIds = new Set()
      for (const port of Array.isArray(data.ports) ? data.ports : []) {
        if (!port?.id || portIds.has(port.id) || !ids.has(port.materialNodeId)) errors.push({ code: 'PORT_REQUIRED', message: '工序端口必须绑定有效物料节点。', nodeId: node.id })
        portIds.add(port?.id)
      }
      if (bindings) for (const item of data.materialBindings || []) if (!item?.materialTypeId || !(Number(item.dosagePerKgG) > 0)) errors.push({ code: 'BINDING_INVALID', message: '辅料绑定必须包含物料和正数用量。', nodeId: node.id })
      if(bindings){if(!(data.ports||[]).some(p=>p.direction==='INPUT')||!(data.ports||[]).some(p=>p.direction==='OUTPUT'))errors.push({code:'PROCESS_IO_REQUIRED',message:'每道工序必须有实际投入和产出接口。',nodeId:node.id});for(const port of data.ports||[]){const material=nodes.find(n=>n.id===port.materialNodeId),edge=edges.find(e=>port.direction==='INPUT'?e.source===port.materialNodeId&&e.target===node.id:e.source===node.id&&e.target===port.materialNodeId);if(!material||!edge||material.kind==='PROCESS'||String(material.data?.skuId)!==String(port.skuId))errors.push({code:'PORT_EDGE_MISMATCH',message:'工序接口的 SKU、物料和连线不一致。',nodeId:node.id})}for(const source of data.reworkPolicy?.sources||[]){const material=nodes.find(n=>n.id===source.reworkNodeId);if(!material?.data?.isRework||!Number.isFinite(Number(source.inputRatioPercent))||Number(source.inputRatioPercent)<0||Number(source.inputRatioPercent)>100)errors.push({code:'REWORK_POLICY_INVALID',message:'回料关系引用或比例无效。',nodeId:node.id})}}
    }
    if (bindings && node?.kind === 'FINISHED_GOOD') for (const item of data.packagingBindings || []) if (!item?.materialTypeId || !(Number(item.standardQuantity) > 0)) errors.push({ code: 'BINDING_INVALID', message: '包材绑定必须包含物料和正数用量。', nodeId: node.id })
  }
  return { errors, nodes, edges }
}

function preflight(definition, activation) {
  const structural = validation(definition)
  const missingItems = [], automaticMappings = [], conflicts = []
  for (const error of structural.errors) conflicts.push({ code: error.code, materialTypeId: null, materialName: null, processNodeId: error.nodeId || null, field: null, message: error.message, action: '修正画布后重试' })
  for (const node of definition?.nodes || []) {
    const data = node.data || {}
    if (['RAW_MATERIAL', 'SEMI_FINISHED', 'FINISHED_GOOD'].includes(node.kind) && (data.bound === false || !data.skuId || !data.baseUnit)) missingItems.push({ code: 'SKU_REQUIRED', materialTypeId: data.skuId || null, materialName: data.name || null, processNodeId: null, field: 'skuId', message: '物料尚未绑定 SKU/基本单位。', action: '完成物料绑定' })
    if (node.kind === 'PROCESS') for (const port of data.ports || []) if (port.materialNodeId && port.skuId) automaticMappings.push({ materialTypeId: port.skuId, materialName: port.materialName || null, fromNodeId: port.materialNodeId, toNodeId: node.id, toProcessNodeId: node.id, toInputPortId: port.id, toEdgeId: null, ownerRecipeId: null, costScope: null, costScopeKey: null })
  }
  const classification = conflicts.length ? 'CONFLICT' : missingItems.length ? 'USER_INPUT_REQUIRED' : 'READY'
  return {
    classification, activeBomVersion: null, syncDraftVersion: definition?.version ?? null,
    activeBomWorkflowRevisionId: activation?.activeDefinitionVersion === definition?.version ? definition?.revisionId ?? null : null,
    targetWorkflowRevisionId: definition?.revisionId ?? null,
    preservedItems: automaticMappings.map(item => item.materialTypeId).filter(Boolean), automaticMappings, missingItems, conflicts,
    canCompleteAutomatically: classification === 'READY', prototypeLocalOnly: true,
  }
}

let lastLocalRevisionId=0
function nextRevision(owner) {
  lastLocalRevisionId=Math.max(Date.now(),lastLocalRevisionId+1,(owner?.versions || []).reduce((max,row)=>Math.max(max,Number(row.definition.revisionId)||0),0)+1);return lastLocalRevisionId
}

function nextId(owner, source) {
  const max = (owner?.versions || []).reduce((n, row) => Math.max(n, Number(row.definition.id) || 0), Number(source?.id) || 0)
  return max + 1
}

function sameGraph(a, b) { return graphHash(a) === graphHash(b) }

function definitionFromSave(incoming, current, owner, source, root) {
  const unchanged = sameGraph(incoming, current)
  const version = Number(current.version)
  const derive = current.status === 'PUBLISHED' || (current.status === 'SNAPSHOT')
  const nextVersion = derive ? version + 1 : version
  const changed = !unchanged
  const definition = {
    ...clone(current), ...clone(incoming), id: current.id ?? source?.id ?? nextId(owner, source), factoryId: FACTORY,
    productTypeId: current.productTypeId || source?.productTypeId, schemaVersion: 1, status: 'DRAFT', version: nextVersion,
    lockVersion: Number(current.lockVersion || 0) + 1, nodes: clone(incoming.nodes), edges: clone(incoming.edges), viewport: clone(incoming.viewport),
    prototypeLocalOnly: true, updatedAt: new Date().toISOString(),
  }
  if (changed) {
    definition.revisionId = nextRevision(owner)
    definition.revisionHash = graphHash(definition)
  } else {
    definition.revisionId = current.revisionId ?? null
    definition.revisionHash = current.revisionHash ?? graphHash(definition)
  }
  if (derive) {
    definition.autoForked = true; definition.forkedFromWorkflowId = current.id ?? null; definition.forkedFromVersion = version
  } else {
    definition.autoForked = false; definition.forkedFromWorkflowId = current.forkedFromWorkflowId ?? null; definition.forkedFromVersion = current.forkedFromVersion ?? null
  }
  return definition
}

function summaries(owner) {
  const byVersion = new Map()
  for (const row of owner?.versions || []) {
    const version = Number(row.definition.version)
    const old = byVersion.get(version)
    if (!old || (!row.source && old.source)) byVersion.set(version, row)
  }
  const activeVersion = Number(owner?.activation?.activeDefinitionVersion)
  return [...byVersion.values()].sort((a, b) => Number(a.definition.version) - Number(b.definition.version)).map(row => ({
    definitionVersion: Number(row.definition.version), status: row.definition.status, updatedAt: row.definition.updatedAt || null,
    active: Boolean(owner?.activation?.enabled && activeVersion === Number(row.definition.version)), productionPlans: [], prototypeLocalOnly: true,
  }))
}

function findOwnerByWorkflowId(root, workflowId, state) {
  const sources = sourceDefinitions(root)
  for (const [owner, definition] of sources) if (String(definition.id) === String(workflowId)) return owner
  for (const [owner, value] of Object.entries(state.owners || {})) if ((value.versions || []).some(row => String(row.definition.id) === String(workflowId))) return owner
  return null
}

function activationFor(root, ownerId, value, source) {
  if (value?.activation !== undefined) return clone(value.activation)
  return captured(root).activations.get(ownerId) ?? null
}

function makeActivation(root, ownerId, owner, workflow, previous, enabled = true) {
  const capturedActivation = captured(root).activations.get(ownerId)
  return {
    id: previous?.id ?? capturedActivation?.id ?? 100000 + Number(workflow?.id || 0), factoryId: FACTORY, productTypeId: ownerId,
    activeWorkflowId: workflow.id, activeDefinitionVersion: Number(workflow.version), enabled,
    workflowType: previous?.workflowType ?? capturedActivation?.workflowType ?? null,
    rootInputProductTypeIds: previous?.rootInputProductTypeIds ?? capturedActivation?.rootInputProductTypeIds ?? null,
    terminalOutputProductTypeIds: previous?.terminalOutputProductTypeIds ?? capturedActivation?.terminalOutputProductTypeIds ?? null,
    activatedBy: previous?.activatedBy ?? null, activatedAt: enabled ? new Date().toISOString() : previous?.activatedAt ?? null,
    lockVersion: Number(previous?.lockVersion ?? capturedActivation?.lockVersion ?? 0) + 1, prototypeLocalOnly: true,
  }
}

function topology(definition) {
  const nodes = Array.isArray(definition?.nodes) ? definition.nodes : [], edges = Array.isArray(definition?.edges) ? definition.edges : []
  const byId = new Map(nodes.filter(node => node?.id).map(node => [node.id, node]))
  const incoming = new Set(), outgoing = new Set(), outgoingMap = new Map()
  for (const edge of edges) {
    if (!byId.has(edge?.source) || !byId.has(edge?.target)) continue
    incoming.add(edge.target); outgoing.add(edge.source)
    if (!outgoingMap.has(edge.source)) outgoingMap.set(edge.source, [])
    outgoingMap.get(edge.source).push(edge.target)
  }
  const terminalNodes = nodes.filter(node => node?.kind === 'FINISHED_GOOD' && !outgoing.has(node.id) && !node?.data?.isByproduct && String(node?.data?.skuId || '').trim())
  const rootNodes = nodes.filter(node => node?.kind === 'RAW_MATERIAL' && !incoming.has(node.id) && String(node?.data?.skuId || '').trim())
  const terminals = [...new Set(terminalNodes.map(node => String(node.data.skuId)))].sort()
  const roots = [...new Set(rootNodes.map(node => String(node.data.skuId)))].sort()
  const parent = new Map(rootNodes.map(node => [node.id, node.id]))
  const find = (id) => { let current = parent.get(id) || id; while (current !== (parent.get(current) || current)) current = parent.get(current); parent.set(id, current); return current }
  for (const node of rootNodes) {
    const replacement = node?.data?.substituteOfNodeId
    if (replacement && parent.has(replacement)) parent.set(find(node.id), find(replacement))
  }
  const logicalRootInputCount = new Set(rootNodes.map(node => find(node.id))).size
  let workflowType = 'INVALID'
  if (terminals.length === 1) workflowType = 'SINGLE_OUTPUT_PRODUCT'
  else if (terminals.length > 1 && logicalRootInputCount === 1) workflowType = 'RAW_MATERIAL_SPLIT'
  else if (terminals.length > 1 && logicalRootInputCount > 1) workflowType = 'JOINT_PRODUCTION'
  return { nodes, edges, byId, incoming, outgoing, outgoingMap, terminalNodes, rootNodes, terminals, roots, logicalRootInputCount, workflowType }
}

function activeGraphs(root, state) {
  const sources = sourceDefinitions(root), capturedData = captured(root), result = []
  const owners = new Set([...sources.keys(), ...Object.keys(state.owners || {})])
  for (const ownerId of owners) {
    const source = sources.get(ownerId), value = state.owners?.[ownerId]
    const activation = value?.activation !== undefined ? value.activation : capturedData.activations.get(ownerId)
    if (!activation?.enabled) continue
    const version = Number(activation.activeDefinitionVersion), workflowId = String(activation.activeWorkflowId)
    let definition = null
    if (value) definition = (value.versions || []).find(row => !row.source && Number(row.definition.version) === version && String(row.definition.id) === workflowId)?.definition || null
    if (!definition && source) definition = sourceHistory(root, ownerId, source).find(row => Number(row.definition.version) === version && String(row.definition.id) === workflowId)?.definition || null
    if (!definition || definition.status !== 'PUBLISHED' || Number(definition.version) !== version || String(definition.id) !== workflowId) continue
    const graph = topology(definition)
    if (graph.workflowType === 'INVALID' || validation(definition, { bindings: true }).errors.length) continue
    result.push({ ownerId, definition, graph, activation })
  }
  return result
}
export function getFusionActiveWorkflow(root,session,productTypeId,workflowId){
  const active=activeGraphs(root,loadState(root,session)).filter(row=>(row.ownerId===productTypeId||row.graph.terminals.includes(productTypeId))&&(workflowId==null||String(row.definition.id)===String(workflowId))).sort((a,b)=>Number(b.ownerId===productTypeId)-Number(a.ownerId===productTypeId))[0];return active?{ownerId:active.ownerId,definition:clone(active.definition),activation:clone(active.activation),candidate:buildResolutionCandidate(root,active,capturedCatalog(root,session),[productTypeId])}:null
}
export function getFusionWorkflowRevision(root,session,revisionId,productTypeId){
  const state=loadState(root,session),sources=sourceDefinitions(root),owners=new Set([...sources.keys(),...Object.keys(state.owners||{})]);for(const ownerId of owners){const source=sources.get(ownerId),rows=[...(state.owners[ownerId]?.versions||[]),...(source?sourceHistory(root,ownerId,source):[])];for(const row of rows){const definition=row.definition;if(Number(definition.revisionId)!==Number(revisionId))continue;if(productTypeId&&ownerId!==productTypeId&&!topology(definition).terminals.includes(productTypeId))continue;return clone(definition)}}return null
}

function catalogValue(catalog, id, field) { return catalog.get(String(id))?.[field] ?? null }

function terminalUnit(graph, skuId) {
  const units = []
  for (const node of graph.nodes) if (node.kind === 'PROCESS') for (const port of node.data?.ports || []) {
    if (port.direction === 'OUTPUT' && String(port.skuId || '') === String(skuId) && port.unit != null) units.push(String(port.unit))
  }
  return [...new Set(units)].length === 1 ? units[0] : null
}

function processPreview(graph) {
  const incomingCount = new Map(graph.nodes.map(node => [node.id, 0])), depth = new Map(graph.nodes.map(node => [node.id, 0]))
  for (const edge of graph.edges) if (incomingCount.has(edge?.target) && graph.byId.has(edge?.source)) incomingCount.set(edge.target, incomingCount.get(edge.target) + 1)
  const queue = graph.nodes.filter(node => incomingCount.get(node.id) === 0).sort(nodeOrder)
  const seen = new Set()
  while (queue.length) {
    const node = queue.shift(); if (seen.has(node.id)) continue; seen.add(node.id)
    for (const target of graph.outgoingMap.get(node.id) || []) {
      depth.set(target, Math.max(depth.get(target) || 0, (depth.get(node.id) || 0) + 1))
      incomingCount.set(target, incomingCount.get(target) - 1); if (incomingCount.get(target) === 0) queue.push(graph.byId.get(target))
    }
    queue.sort(nodeOrder)
  }
  const ordered = [...graph.nodes].sort((a, b) => (depth.get(a.id) || 0) - (depth.get(b.id) || 0) || nodeOrder(a, b))
  const label = node => String(node.kind === 'PROCESS' ? node.data?.processName : node.data?.name || node.data?.skuId || node.id)
  const unit = node => node.kind === 'PROCESS' ? `${node.data?.inputUnit || ''}->${node.data?.outputUnit || ''}` : node.data?.baseUnit || null
  return {
    processSteps: ordered.filter(node => node.kind === 'PROCESS').map(label),
    previewNodes: ordered.map(node => ({ id: node.id, kind: node.kind, label: label(node), unit: unit(node) })),
    previewEdges: graph.edges.filter(edge => graph.byId.has(edge?.source) && graph.byId.has(edge?.target)).map(edge => ({ id: edge.id, source: edge.source, target: edge.target })),
  }
}

function nodeOrder(a, b) {
  const x = node => node?.position?.x == null ? Number.MAX_VALUE : Number(node.position.x)
  const y = node => node?.position?.y == null ? Number.MAX_VALUE : Number(node.position.y)
  return x(a) - x(b) || y(a) - y(b)
    || String(a?.id).localeCompare(String(b?.id))
}

function buildResolutionCandidate(root, active, catalog, requested) {
  const { ownerId, definition, graph } = active, ownerNode = graph.rootNodes.find(node => String(node.data?.skuId) === ownerId)
  const ownerItem = catalog.get(ownerId)
  const terminalOutputs = graph.terminals.map(skuId => {
    const terminalNode = graph.terminalNodes.find(node => String(node.data?.skuId) === skuId)
    return { productTypeId: skuId, productName: catalogValue(catalog, skuId, 'name') || terminalNode?.data?.name || skuId, unit: terminalUnit(graph, skuId) || terminalNode?.data?.baseUnit || null }
  })
  const preview = processPreview(graph), ownerUnit = ownerNode?.data?.baseUnit || ownerItem?.unit || null
  let plannedUnit = null
  if (ownerNode) plannedUnit = ownerUnit
  else {
    const units = terminalOutputs.filter(output => output.productTypeId === ownerId).map(output => output.unit).filter(Boolean)
    if (new Set(units).size === 1) plannedUnit = units[0]
  }
  return {
    workflowId: Number.isFinite(Number(definition.id)) ? Number(definition.id) : null, definitionVersion: Number(definition.version), ownerProductTypeId: ownerId,
    ownerProductName: ownerItem?.name || ownerNode?.data?.name || ownerId, ownerProductCategory: ownerItem?.productCategory || (ownerNode ? 'RAW_MATERIAL' : null), ownerUnit,
    plannedUnit, terminalOutputs, exactMatch: graph.terminals.length === requested.length && requested.every(id => graph.terminals.includes(id)), workflowType: graph.workflowType,
    rootInputProductTypeIds: graph.roots, logicalRootInputCount: graph.logicalRootInputCount, ...preview,
  }
}

function buildDirectoryEntry(root, active, catalog) {
  const candidate = buildResolutionCandidate(root, active, catalog, active.graph.terminals)
  return { workflowId: candidate.workflowId, definitionVersion: candidate.definitionVersion, ownerProductTypeId: candidate.ownerProductTypeId, ownerProductName: candidate.ownerProductName, workflowType: candidate.workflowType, terminalOutputs: candidate.terminalOutputs.map(output => ({ productTypeId: output.productTypeId, productName: output.productName })), anchorIsTerminalOutput: active.graph.terminals.includes(active.ownerId) }
}

function requestFingerprint(request) { return stable({ lockVersion: request.lockVersion, revisionId: request.revisionId, revisionHash: request.revisionHash, definitionVersion: request.definitionVersion }) }

async function applyPublicationEffects(root,session,ownerId,definition){
 const w=fusionWorkspace(root,'factory',session),beforeState=clone(w.state),beforeShared=clone(w.sharedSnapshot),files=[w.file,w.sharedFile],backups=files.map(file=>fs.existsSync(file)?fs.readFileSync(file):null),planBase=`/api/mobile/${FACTORY}/production-plans`,recipeBase=`/api/mobile/${FACTORY}/bom/recipes`,oldRecipes=w.rows(w.models.get(recipeBase)),products=topology(definition).terminals,newRecipes=new Map();
 const summary={reboundPlanCount:0,preservedStartedPlanCount:0,reboundPlanNumbers:[],preservedPlanNumbers:[]};
 try{
  const {handleFusionBom}=await import('./fusion-bom.mjs');const call=async(method,target,input)=>{let response;await handleFusionBom({parent:root,root,role:'factory',session,method,target,body:Buffer.from(JSON.stringify(input||{})),res:{},send:(_,status,body)=>response={status,body}});if(!response?.body.success)throw Error(response?.body.message||'BOM 同步未完成');return response.body.data};
  for(const productTypeId of products){const draft=await call('POST',recipeBase+'/ensure-draft',{productTypeId,workflowRevisionId:definition.revisionId});let row=w.row(w.models.get(recipeBase),draft.id);if(row?.items?.length||row?.seasoningItems?.length)row=await call('POST',recipeBase+'/'+draft.id+'/activate',{});newRecipes.set(productTypeId,row||draft)}
  const model=w.models.get(planBase);for(const plan of w.rows(model)){if(String(plan.selectedWorkflowId)!==String(definition.id)||['COMPLETED','CANCELLED','CLOSED'].includes(plan.status))continue;const reports=w.state.configs[planBase+'/'+plan.id+'/process-sheet/rows']||[],started=reports.some(row=>row.submissionStatus==='SUBMITTED'||row.materialized||row.interimSettledAt),product=plan.targetFinishedGoodIds?.[0]||plan.productTypeId;if(started){summary.preservedStartedPlanCount++;summary.preservedPlanNumbers.push(plan.planNumber);if(!plan.selectedBomRecipeId){const old=oldRecipes.find(row=>row.productTypeId===product&&(row.isCurrent||row.status==='ACTIVE'));if(old)w.set(model,plan.id,{...plan,selectedBomRecipeId:old.id,selectedBomVersion:old.version})}continue}const recipe=newRecipes.get(product);w.set(model,plan.id,{...plan,selectedWorkflowRevisionId:definition.revisionId,selectedWorkflowRevisionHash:definition.revisionHash,selectedWorkflowVersion:definition.version,selectedBomRecipeId:recipe?.id||null,selectedBomVersion:recipe?.version||null,selectedBomRecipesByProduct:Object.fromEntries([...newRecipes].map(([id,r])=>[id,{id:r.id,version:r.version}])),updatedAt:new Date().toISOString(),prototypeLocalOnly:true});delete w.state.configs[planBase+'/'+plan.id+'/process-sheet/workflow-config'];summary.reboundPlanCount++;summary.reboundPlanNumbers.push(plan.planNumber)}w.persist();return {summary,recipes:[...newRecipes.values()],rollback:()=>{for(const [i,file]of files.entries()){if(backups[i])fs.writeFileSync(file,backups[i]);else fs.rmSync(file,{force:true})}w.state=beforeState;w.sharedSnapshot=beforeShared;w.syncShared()}};
 }catch(error){for(const [i,file]of files.entries()){if(backups[i])fs.writeFileSync(file,backups[i]);else fs.rmSync(file,{force:true})}w.state=beforeState;w.sharedSnapshot=beforeShared;w.syncShared();throw error}
}

async function publishAndActivate({ root, session, state, ownerId, value, source, request, send, res }) {
  const current = currentRow(value)?.definition
  if (!current) return fail(send, res, 404, 'WORKFLOW_NOT_FOUND', '工艺定义不存在。')
  const key = typeof request?.idempotencyKey === 'string' ? request.idempotencyKey.trim() : ''
  if (!key) return fail(send, res, 422, 'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_REQUIRED', '发布必须提供幂等键。')
  const fingerprint = requestFingerprint(request)
  const replay = value.idempotency?.[key]
  if (replay) {
    if (replay.fingerprint !== fingerprint) return fail(send, res, 409, 'WORKFLOW_PUBLISH_IDEMPOTENCY_KEY_MISMATCH', '幂等键已用于另一组发布参数。')
    return ok(send, res, { ...clone(replay.response), replayed: true }, '幂等重放：未新增版本。')
  }
  if (current.status !== 'DRAFT') return fail(send, res, 409, 'PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING', '发布前请先保存本机草稿。')
  if (Number(request.lockVersion) !== Number(current.lockVersion)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '本机草稿已更新，请重新载入。')
  if (Number(request.definitionVersion) !== Number(current.version) || Number(request.revisionId) !== Number(current.revisionId) || String(request.revisionHash) !== String(current.revisionHash)) return fail(send, res, 409, 'WORKFLOW_PUBLISH_REVISION_IDENTITY_CONFLICT', '发布请求不是当前草稿的 revision。')
  const checked = validation(current, { bindings: true })
  if (checked.errors.length) return fail(send, res, 422, checked.errors[0].code, checked.errors[0].message)
  const pre = preflight(current, activationFor(root, ownerId, value, source))
  if (pre.classification !== 'READY') return fail(send, res, 422, `WORKFLOW_BOM_SYNC_${pre.classification}`, 'BOM/绑定配置尚未满足发布条件。')
  let effects;try{effects=await applyPublicationEffects(root,session,ownerId,current)}catch(error){return fail(send,res,422,'BOM_SYNC_NOT_APPLIED',error.message)}
  const published = { ...clone(current), status: 'PUBLISHED', updatedAt: new Date().toISOString(), publishedAt: new Date().toISOString(), prototypeLocalOnly: true }
  value.versions = (value.versions || []).filter(row => !(Number(row.definition.version) === Number(published.version) && !row.source))
  value.versions.push({ definition: published, source: false })
  value.currentVersion = Number(published.version)
  value.activation = makeActivation(root, ownerId, value, published, activationFor(root, ownerId, value, source), true)
  const planRebind = effects.summary
  const payload = { workflow: clone(published), activation: clone(value.activation), bomSync: { ...pre, syncDraftVersion: published.version, targetWorkflowRevisionId: published.revisionId,recipeIds:effects.recipes.map(row=>row.id) }, planRebind, idempotencyKey: key, replayed: false }
  if (!value.idempotency) value.idempotency = {}
  value.idempotency[key] = { fingerprint, response: clone(payload) }
  try{persistState(root, session, state)}catch(error){effects.rollback();throw error}
  return ok(send, res, payload, '已同步本机 BOM、发布工艺并更新未报工计划；已有报工的计划保留原配置。')
}

export async function handleFusionWorkflow({ root, role, session, method, target, body, send, res }) {
  if (role !== 'factory') return false
  const rawTarget = String(target || '')
  const parsedTarget = new URL(rawTarget, 'http://prototype.local')
  const pathname = parsedTarget.pathname
  if (!pathname.startsWith(PREFIX) && pathname !== '/api/mobile/FOOD_0000_001/product-process-workflows/activation') return false
  const suffix = pathname.slice(PREFIX.length)
  const parts = suffix.split('/').filter(Boolean)
  const state = loadState(root, session)
  if (pathname === `${PREFIX}resolve-by-outputs`) {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'resolve-by-outputs 仅支持 POST。')
    const request = parseBody(body), requested = [...new Set((Array.isArray(request?.productTypeIds) ? request.productTypeIds : []).map(value => String(value || '').trim()).filter(Boolean))]
    if (!requested.length) return fail(send, res, 400, 'WORKFLOW_RESOLUTION_EMPTY_SELECTION', '请至少选择一个成品。')
    const catalog = capturedCatalog(root,session), missing = requested.filter(id => !catalog.has(id) || catalog.get(id)?.productCategory !== 'FINISHED_PRODUCT')
    if (missing.length) return fail(send, res, 404, 'WORKFLOW_RESOLUTION_PRODUCT_NOT_FOUND', `以下成品不存在于当前工厂: ${missing.join(', ')}`)
    const candidates = activeGraphs(root, state)
      .filter(active => requested.every(id => active.graph.terminals.includes(id)))
      .map(active => ({ active, candidate: buildResolutionCandidate(root, active, catalog, requested) }))
    const exact = candidates.filter(item => item.candidate.exactMatch)
    const selected = exact.length ? exact : candidates.length ? candidates.filter(item => item.active.graph.terminals.length === Math.min(...candidates.map(candidate => candidate.active.graph.terminals.length))) : []
    const data = {
      requestedProductTypeIds: requested, resolutionMode: selected.length ? requested.length === 1 ? 'SINGLE_OUTPUT' : 'MULTI_OUTPUT' : 'NONE',
      message: selected.length ? exact.length ? '已匹配启用的工序 Workflow' : '匹配到包含额外联产成品的 Workflow，请确认完整产出集合' : '未找到覆盖所选成品的已启用 Workflow。',
      candidates: selected.map(item => item.candidate), prototypeLocalOnly: true,
    }
    return ok(send, res, data, '已按启用版本解析本机工艺候选。')
  }
  if (pathname === `${PREFIX}producing`) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'producing 仅支持 GET。')
    const requested = String(parsedTarget.searchParams.get('finishedGoodProductTypeId') || '').trim()
    if (!requested) return fail(send, res, 400, 'PRODUCT_TYPE_REQUIRED', 'finishedGoodProductTypeId 不能为空。')
    const catalog = capturedCatalog(root,session), workflows = activeGraphs(root, state)
      .filter(active => active.graph.terminals.includes(requested))
      .sort((a, b) => a.graph.terminals.length - b.graph.terminals.length || Number(a.definition.id) - Number(b.definition.id))
      .map(active => buildDirectoryEntry(root, active, catalog))
    return ok(send, res, { finishedGoodProductTypeId: requested, workflows, prototypeLocalOnly: true }, '已读取所有启用版本的产出目录。')
  }
  if (pathname === '/api/mobile/FOOD_0000_001/product-process-workflows/activation') {
    const ownerId = parsedTarget.searchParams.get('productTypeId')
    if (!ownerId) return fail(send, res, 400, 'PRODUCT_TYPE_REQUIRED', '停用必须提供 productTypeId。')
    const located = ownerState(root, state, ownerId)
    if (!located.owner) return fail(send, res, 404, 'WORKFLOW_NOT_FOUND', '工艺定义不存在。')
    if (method !== 'DELETE') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '此 activation 路径仅支持 DELETE。')
    const currentActivation = activationFor(root, ownerId, located.owner, located.source)
    if (parsedTarget.searchParams.has('lockVersion') && Number(parsedTarget.searchParams.get('lockVersion')) !== Number(currentActivation?.lockVersion ?? 0)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '启用状态已更新，请重新载入。')
    located.owner.activation = makeActivation(root, ownerId, located.owner, currentRow(located.owner)?.definition || located.source, currentActivation, false)
    persistState(root, session, state)
    return ok(send, res, located.owner.activation, '本机启用已停用。')
  }
  const ownerId = parts[0]
  if (!/^[A-Za-z0-9:_-]+$/.test(ownerId)) return false
  let located = ownerState(root, state, ownerId)
  if (!located.owner && !located.source){
    if(!capturedCatalog(root,session).has(ownerId)){const w=fusionWorkspace(path.dirname(root),'factory',session),known=['/api/mobile/FOOD_0000_001/product-types','/api/mobile/FOOD_0000_001/raw-material-types'].some(base=>w.row(w.models.get(base),ownerId));if(!known)return fail(send,res,404,'WORKFLOW_NOT_FOUND','未找到本工厂的产品或物料。')}
    if(method==='GET'&&parts.length===1)return ok(send,res,null,'该产品尚未创建工艺。')
    if(method==='GET'&&parts[1]==='activation')return ok(send,res,null,'该产品尚未启用工艺。')
    if(method==='GET'&&parts[1]==='versions')return parts.length===2?ok(send,res,[],'该产品尚无已保存的版本。'):fail(send,res,404,'WORKFLOW_VERSION_NOT_FOUND','该产品尚无已保存的版本。')
    if(method!=='PUT'||parts[1]!=='draft')return fail(send,res,409,'WORKFLOW_DRAFT_REQUIRED','请先保存工艺草稿。')
    const initial={id:Date.now(),factoryId:FACTORY,productTypeId:ownerId,status:'DRAFT',version:1,lockVersion:0,revisionId:0,nodes:[],edges:[],viewport:{x:0,y:0,zoom:1}};initial.revisionHash=graphHash(initial);state.owners[ownerId]={versions:[{definition:initial,source:false}],currentVersion:1,activation:null,idempotency:{}};located={owner:state.owners[ownerId],source:undefined}
  }
  const owner = located.owner
  const source = located.source
  if (parts.length === 1) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '当前工艺路径仅支持 GET。')
    return ok(send, res, currentRow(owner)?.definition || source, '已读取本机工艺定义。')
  }
  const action = parts[1], version = parts[2] == null ? null : Number(parts[2])
  if (action === 'draft') {
    if (method !== 'PUT') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'draft 仅支持 PUT。')
    const incoming = parseBody(body)
    if (!incoming) return fail(send, res, 400, 'SCHEMA', '画布内容格式错误。')
    const current = currentRow(owner)?.definition || source
    if (incoming.factoryId && incoming.factoryId !== FACTORY || incoming.productTypeId && incoming.productTypeId !== ownerId) return fail(send, res, 422, 'TENANT_MISMATCH', '工厂或产品租户不一致。')
    if (Number(incoming.lockVersion ?? current.lockVersion ?? 0) !== Number(current.lockVersion ?? 0)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '本机草稿已被更新，请重新载入。')
    const checked = validation(incoming)
    if (checked.errors.length) return fail(send, res, 422, checked.errors[0].code, checked.errors[0].message)
    const definition = definitionFromSave(incoming, current, owner, source, root)
    owner.versions = (owner.versions || []).filter(row => !(Number(row.definition.version) === Number(definition.version) && !row.source))
    owner.versions.push({ definition, source: false }); owner.currentVersion = Number(definition.version)
    persistState(root, session, state)
    return ok(send, res, definition, '画布已保存到独立本机状态并回读确认。')
  }
  if (action === 'versions') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'versions 仅支持 GET。')
    if (version == null) return ok(send, res, summaries(owner), '已读取本机及只读源版本历史。')
    const row = (owner.versions || []).find(item => Number(item.definition.version) === version && !item.source) || sourceRow(owner, version)
    if (!row) return fail(send, res, 404, 'WORKFLOW_VERSION_NOT_FOUND', '该工艺版本不存在。')
    return ok(send, res, row.definition, '已读取工艺历史版本。')
  }
  if (action === 'activation') {
    if (method === 'GET') return ok(send, res, activationFor(root, ownerId, owner, source), '已读取本机启用状态。')
    if (method !== 'PUT' && method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'activation 仅支持 GET/PUT/POST。')
    const current = currentRow(owner)?.definition || source
    if (current.status !== 'PUBLISHED') return fail(send, res, 409, 'WORKFLOW_NOT_PUBLISHED', '只有已发布版本才能启用。')
    owner.activation = makeActivation(root, ownerId, owner, current, activationFor(root, ownerId, owner, source), true)
    persistState(root, session, state)
    return ok(send, res, owner.activation, '本机工艺已启用。')
  }
  if (action === 'bom-sync-preflight') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'bom-sync-preflight 仅支持 GET。')
    const current = currentRow(owner)?.definition || source
    return ok(send, res, preflight(current, activationFor(root, ownerId, owner, source)), '已完成本机结构与绑定核查；未声称正式后端门禁。')
  }
  if (action === 'publish-and-activate') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'publish-and-activate 仅支持 POST。')
    const request = parseBody(body)
    if (!request) return fail(send, res, 400, 'SCHEMA', '发布请求格式错误。')
    return publishAndActivate({ root, session, state, ownerId, value: owner, source, request, send, res })
  }
  if (action === 'publish') return fail(send, res, 409, 'PUBLISH_AND_ACTIVATE_REQUIRED', '本机演示请使用 publish-and-activate，并提供 revision 与幂等键。')
  return false
}

export default handleFusionWorkflow
