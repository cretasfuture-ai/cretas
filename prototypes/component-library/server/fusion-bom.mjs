import crypto from 'node:crypto'
import path from 'node:path'
import { fusionWorkspace } from './fusion-data.mjs'
import * as fusionWorkflow from './fusion-workflow.mjs'

const FACTORY = 'F006'
const RECIPE_BASE = `/api/mobile/${FACTORY}/bom/recipes`
const WORKSPACE_SUFFIX = '/seasoning/workspace'

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value))
}

function bodyJson(body) {
  if (body == null || body === '') return {}
  if (typeof body === 'object' && !Buffer.isBuffer(body)) return clone(body)
  try { return JSON.parse(Buffer.from(body).toString('utf8')) } catch { return null }
}

function now() { return new Date().toISOString() }

function ok(send, res, data, message = '已保存到本机工作区并回读确认。') {
  send(res, 200, { success: true, data, message, prototypeLocalOnly: true })
  return true
}

function fail(send, res, status, code, message) {
  send(res, status, { success: false, data: null, code, message, prototypeLocalOnly: true })
  return true
}

function isFactory(role, target) {
  return role === 'factory' && String(target || '').startsWith(`/api/mobile/${FACTORY}/`)
}

function workspaceParent(parent, root) {
  const value = parent || root
  if (!value) return value
  return path.basename(value).toLowerCase() === 'samples-v5' ? path.dirname(value) : value
}

function recipeModel(w) {
  return w.models.get(RECIPE_BASE)
}

function recipeRows(w) {
  const model = recipeModel(w)
  return model ? w.rows(model).filter(row => row && row.factoryId === FACTORY && !row.deleted && !row.deletedAt) : []
}

function recipe(w, id) {
  return recipeRows(w).find(row => String(row.id) === String(id)) || null
}

function sourceRecord(w, pathname) {
  const result = w.read('GET', pathname, null)
  return result?.status === 200 && result.body?.data != null ? clone(result.body.data) : null
}

function nextNumber(values) {
  return values.reduce((max, value) => Math.max(max, Number(value) || 0), 0) + 1
}

function maxItemId(w) {
  return nextNumber(recipeRows(w).flatMap(row => Array.isArray(row.items) ? row.items.map(item => item.id) : []))
}

function maxSeasoningId(w) {
  return nextNumber(recipeRows(w).flatMap(row => Array.isArray(row.seasoningItems) ? row.seasoningItems.map(item => item.id) : []))
}

function modelFor(w, base) {
  return w.models.get(base) || w.ensureModel(base)
}

function saveRow(w, model, before, next, type = 'update', pathName = RECIPE_BASE) {
  w.set(model, String(next.id), next)
  w.log({ method: type === 'create' ? 'POST' : 'PUT', path: pathName, type, id: next.id, before: clone(before), after: clone(next), at: now() })
}

function tenantGuard(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return '请求体格式错误。'
  for (const field of ['factoryId', 'tenantId', 'factory']) {
    if (input[field] != null && String(input[field]) !== FACTORY) return '仅允许 F006 工厂工作区。'
  }
  return null
}

function recipeRevisionGuard(input, current) {
  const expected = input?.expectedVersion ?? input?.expectedRevision ?? input?.lockVersion
  if (expected != null && Number(expected) !== Number(current.version)) return '配方已被其他本机操作更新，请重新载入。'
  return null
}

function editableRecipe(send, res, current) {
  if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在于当前 F006 本机快照。')
  if (current.factoryId !== FACTORY) return fail(send, res, 403, 'TENANT_MISMATCH', '配方不属于 F006，已拒绝跨工厂操作。')
  if (current.status !== 'DRAFT') return fail(send, res, 409, 'RECIPE_NOT_EDITABLE', '只有 DRAFT 配方可以在本机编辑。')
  return null
}

function materialName(w, materialTypeId) {
  if (!materialTypeId) return null
  for (const base of ['/api/mobile/F006/material-types', '/api/mobile/F006/raw-material-types']) {
    const model = w.models.get(base)
    const row = model && w.row(model, materialTypeId)
    if (row) return row.name || row.materialName || null
  }
  return null
}

function productRecord(w, productTypeId) {
  return w.row(w.models.get(`/api/mobile/${FACTORY}/product-types`), productTypeId)
    || w.row(w.models.get(`/api/mobile/${FACTORY}/raw-material-types`), productTypeId)
}

function normalizeItem(w, recipeId, input, old = null, id = null) {
  const value = { ...(old || {}), ...(input || {}) }
  if (value.factoryId != null && String(value.factoryId) !== FACTORY) throw new Error('仅允许 F006 工厂工作区。')
  const materialTypeId = value.materialTypeId == null || value.materialTypeId === '' ? null : String(value.materialTypeId)
  const semiFinishedRefCode = value.semiFinishedRefCode == null || value.semiFinishedRefCode === '' ? null : String(value.semiFinishedRefCode)
  if ((materialTypeId == null) === (semiFinishedRefCode == null)) throw new Error('物料行必须且只能提供 materialTypeId 或 semiFinishedRefCode。')
  if (value.unit == null || String(value.unit).trim() === '') throw new Error('物料行 unit 不能为空。')
  if (value.standardQuantity != null && (!Number.isFinite(Number(value.standardQuantity)) || Number(value.standardQuantity) < 0)) throw new Error('standardQuantity 必须是非负数。')
  if (String(value.materialCategory || '').toUpperCase() === 'PACKAGING' && value.standardQuantity == null) throw new Error('PACKAGING 物料必须提供 standardQuantity。')
  const at = now()
  const next = {
    ...value,
    id: old?.id ?? id ?? maxItemId(w),
    recipeId,
    factoryId: FACTORY,
    materialTypeId,
    semiFinishedRefCode,
    unit: String(value.unit),
    materialName: value.materialName || materialName(w, materialTypeId),
    createdAt: old?.createdAt || at,
    updatedAt: at,
    deleted: false,
  }
  if (!Object.hasOwn(next, 'substitutes') && old?.substituteDetails) next.substitutes = clone(old.substituteDetails)
  return next
}

function normalizeRecipeItems(w, recipeId, items) {
  if (!Array.isArray(items)) throw new Error('items 必须是数组。')
  let nextId = maxItemId(w)
  return items.map(item => normalizeItem(w, recipeId, item, item?.id != null ? item : null, item?.id == null ? nextId++ : null))
}

function recipeSummary(row) {
  return clone(row)
}

function workspaceTemplate(w, recipeId) {
  const direct = sourceRecord(w, `${RECIPE_BASE}/${recipeId}${WORKSPACE_SUFFIX}`)
  if (direct) return direct
  const row = recipe(w, recipeId)
  if (!row) return null
  const sameProduct = recipeRows(w).filter(value => String(value.productTypeId) === String(row.productTypeId))
  for (const candidate of sameProduct) {
    const template = sourceRecord(w, `${RECIPE_BASE}/${candidate.id}${WORKSPACE_SUFFIX}`)
    if (template) return template
  }
  return null
}

async function workflowRevision(root, session, revisionId, productTypeId) {
  const getter = fusionWorkflow.getFusionWorkflowRevision
  if (typeof getter !== 'function') return null
  const result = await getter(root, session, revisionId, productTypeId)
  return result?.definition || result || null
}

function finite(value) { return Number.isFinite(Number(value)) ? Number(value) : null }

function revisionProcessNodes(definition) {
  return (definition?.nodes || []).filter(node => node?.kind === 'PROCESS' && node?.id && node?.data)
}

function revisionProcess(definition, node) {
  const data = node.data || {}, ports = Array.isArray(data.ports) ? data.ports : [], inputs = ports.filter(port => port?.direction === 'INPUT')
  const basisQuantity = finite(data.standardBasisQuantity ?? data.basisQuantity ?? data.inputQuantity)
  const basisUnit = data.standardBasisUnit || data.basisUnit || data.inputUnit || inputs.find(port => port?.unit)?.unit || null
  const inputUnits = inputs.map(port => String(port?.unit || '').trim().toLowerCase()).filter(Boolean)
  const standardUsageSupported = inputUnits.some(unit => unit === 'kg' || unit === 'g') || (basisQuantity != null && Boolean(basisUnit))
  return {
    workflowProcessNodeId: String(node.id),
    workProcessId: data.workProcessId || String(node.id),
    processOrder: finite(data.processOrder ?? data.order) ?? null,
    processName: data.processName || data.name || String(node.id),
    processCategory: data.processCategory ?? null,
    standardBasisQuantity: basisQuantity,
    standardBasisUnit: basisUnit,
    standardBasisMaterialKind: inputs[0]?.materialKind || null,
    basisQuantity,
    basisUnit,
    standardUsageSupported,
    costScope: data.costScope ?? null,
    costTargetProductTypeIds: Array.isArray(data.costTargetProductTypeIds) ? clone(data.costTargetProductTypeIds) : [],
    editable: true,
    bindings: [],
  }
}

function revisionEdge(definition, processNodeId, port) {
  const edge = (definition?.edges || []).find(value => value?.source === processNodeId && value?.sourceHandle === port?.id)
    || (definition?.edges || []).find(value => value?.target === processNodeId && value?.targetHandle === port?.id)
  return edge?.id || null
}

function revisionWorkspace(row, definition) {
  const nodes = Array.isArray(definition?.nodes) ? definition.nodes : [], processes = revisionProcessNodes(definition)
    .map(node => revisionProcess(definition, node))
  const items = Array.isArray(row.seasoningItems) ? row.seasoningItems.filter(item => !item.deleted && !item.deletedAt) : []
  for (const process of processes) {
    const sameWorkProcess = processes.filter(value => String(value.workProcessId) === String(process.workProcessId))
    process.bindings = items.filter(item => item.workflowProcessNodeId
      ? String(item.workflowProcessNodeId) === String(process.workflowProcessNodeId)
      : sameWorkProcess.length === 1 && String(item.workProcessId) === String(process.workProcessId)).map(item => bindingFor(item, process))
  }
  const workspace = {
    recipeId: String(row.id), productTypeId: row.productTypeId, productName: row.productName, status: row.status,
    editable: row.status === 'DRAFT', seasoningRevision: Number(row.seasoningRevision || 0),
    workflowRevisionId: definition.revisionId ?? null, workflowId: definition.id ?? null,
    workflowOwnerProductTypeId: definition.productTypeId ?? null, workflowDefinitionVersion: definition.version ?? null,
    workflowRevisionHash: definition.revisionHash ?? null, workflowRevisionStatus: definition.status ?? null,
    workflowRevisionSavedAt: definition.updatedAt ?? definition.savedAt ?? null,
    workflowRootCount: nodes.filter(node => node.kind === 'RAW_MATERIAL' && !node.data?.isByproduct).length,
    workflowProcessCount: processes.length,
    workflowTargetCount: nodes.filter(node => node.kind === 'FINISHED_GOOD' && !node.data?.isByproduct).length,
    workflowTargetProductTypeId: nodes.find(node => node.kind === 'FINISHED_GOOD' && String(node.data?.skuId) === String(row.productTypeId))?.data?.skuId ?? null,
    workflowUpgradeAvailable: false, workflowUpgradeRevisionId: null, workflowUpgradeDefinitionVersion: null,
    bomFamilyId: row.bomFamilyId ?? null, sharedRecipeId: row.sharedRecipeId ?? null, sharedRulesOwner: row.sharedRulesOwner ?? true,
    outputRole: row.outputRole ?? 'MAIN', costAllocationRatio: row.costAllocationRatio ?? null,
    processes, materialSummaries: buildMaterialSummaries(items, processes), anomalies: [],
  }
  return workspace
}

function processForWorkspace(workspace, workProcessId) {
  return workspace?.processes?.find(process => String(process.workProcessId) === String(workProcessId)) || null
}

function bindingFor(item, process) {
  return {
    ...clone(item),
    workflowProcessNodeId: item.workflowProcessNodeId || process?.workflowProcessNodeId,
    workProcessId: item.workProcessId,
    materialName: item.materialName || item.name,
    unit: item.unit || null,
  }
}

function buildWorkspace(w, row) {
  if (row.workflowDefinition && Array.isArray(row.workflowDefinition.nodes)) return revisionWorkspace(row, row.workflowDefinition)
  const template = workspaceTemplate(w, row.id)
  if (!template) return null
  const workspace = clone(template)
  workspace.recipeId = String(row.id)
  workspace.productTypeId = row.productTypeId
  workspace.productName = row.productName
  workspace.status = row.status
  workspace.editable = row.status === 'DRAFT'
  workspace.seasoningRevision = Number(row.seasoningRevision || 0)
  const items = Array.isArray(row.seasoningItems) ? row.seasoningItems.filter(item => !item.deleted && !item.deletedAt) : []
  workspace.processes = (workspace.processes || []).map(process => ({
    ...process,
    editable: row.status === 'DRAFT' && process.editable !== false,
    // A shared backend workProcessId may occur on several Workflow nodes. The
    // captured seasoning item carries workflowProcessNodeId, so bind by that
    // stable node identity first and only use workProcessId for legacy rows.
    bindings: items.filter(item => item.workflowProcessNodeId
      ? String(item.workflowProcessNodeId) === String(process.workflowProcessNodeId)
      : String(item.workProcessId) === String(process.workProcessId)).map(item => bindingFor(item, process)),
  }))
  workspace.materialSummaries = buildMaterialSummaries(items, workspace.processes)
  workspace.anomalies = Array.isArray(workspace.anomalies) ? workspace.anomalies : []
  return workspace
}

function buildMaterialSummaries(items, processes) {
  const byMaterial = new Map()
  for (const item of items) {
    if (!item.materialTypeId) continue
    const key = String(item.materialTypeId)
    const process = processes.find(value => item.workflowProcessNodeId && String(value.workflowProcessNodeId) === String(item.workflowProcessNodeId))
      || processes.find(value => String(value.workProcessId) === String(item.workProcessId))
    const usage = {
      bindingId: item.id,
      workflowProcessNodeId: process?.workflowProcessNodeId || item.workflowProcessNodeId,
      workProcessId: item.workProcessId,
      processOrder: process?.processOrder ?? 0,
      processName: process?.processName || '',
      basisQuantity: process?.standardBasisQuantity ?? null,
      basisUnit: process?.standardBasisUnit ?? null,
      dosagePerKgG: item.dosagePerKgG,
      subsequentPotRatio: item.subsequentPotRatio ?? null,
      countInSeasoning: item.countInSeasoning !== false,
    }
    const current = byMaterial.get(key) || { materialTypeId: key, materialName: item.name || item.materialName || key, unit: item.unit || null, usages: [] }
    current.usages.push(usage)
    current.processUsages = current.usages
    byMaterial.set(key, current)
  }
  return [...byMaterial.values()]
}

function seasoningsFor(row) {
  return (Array.isArray(row.seasoningItems) ? row.seasoningItems : []).filter(item => !item.deleted && !item.deletedAt).map(clone)
}

function sourceSeasoningItems(w, source) {
  const detailItems = seasoningsFor(source)
  const template = workspaceTemplate(w, source.id)
  const workspaceItems = template?.processes?.flatMap(process => process.bindings || [])
    .filter(item => item && item.materialTypeId && item.workProcessId)
    .map(item => ({ ...clone(item), workflowProcessNodeId: item.workflowProcessNodeId || null })) || []
  // The workspace is the authoritative process binding projection when it
  // contains more captured rows than the recipe detail. Do not invent rows
  // when both snapshots are empty or when the detail is already complete.
  return workspaceItems.length > detailItems.length ? workspaceItems : detailItems
}

function revisionBindings(definition) {
  const nodes = Array.isArray(definition?.nodes) ? definition.nodes : []
  const processNodes = nodes.filter(node => node?.kind === 'PROCESS')
  const processIds = new Set(processNodes.map(node => String(node.id)))
  const materialNodes = new Map(nodes.filter(node => node?.id).map(node => [String(node.id), node]))
  const validSkus = new Set(nodes.map(node => node?.data?.skuId).filter(Boolean).map(String))
  const packaging = []
  for (const node of nodes.filter(value => value?.kind === 'FINISHED_GOOD')) for (const binding of node.data?.packagingBindings || []) {
    if (binding?.materialTypeId) packaging.push({ ...clone(binding), workflowMaterialNodeId: node.id, materialCategory: 'PACKAGING', packagingRole: binding.packagingRole || 'PRIMARY_CONTAINER' })
  }
  return { nodes, processNodes, processIds, materialNodes, validSkus, packaging }
}

function revisionItemFromPort(definition, node, port, index) {
  const kind = String(port.materialKind || '').toUpperCase()
  const materialTypeId = kind === 'SEMI_FINISHED' ? null : (port.skuId || null)
  const semiFinishedRefCode = kind === 'SEMI_FINISHED' ? (port.skuId || null) : null
  return {
    id: null, recipeId: null, factoryId: FACTORY, materialTypeId, semiFinishedRefCode,
    materialName: port.materialName || null, standardQuantity: null, yieldRate: null,
    actualQuantity: null, unit: port.unit || null, unitPrice: null, priceUnit: port.unit || null,
    quantityToPriceFactor: 1, taxRate: null, itemCost: null, materialCategory: kind === 'RAW_MATERIAL' ? 'RAW' : 'AUXILIARY',
    sortOrder: index, isOptional: false, substituteGroup: null, packagingSpecId: null, packagingRole: null,
    naturalQuantity: null, naturalUnit: null, workflowMaterialNodeId: port.materialNodeId || node.id,
    workflowInputPortId: port.id || null, workflowEdgeId: revisionEdge(definition, node.id, port), costScope: null,
    costScopeKey: null, remark: null, perPortion: false, subProductTypeId: null, deleted: false,
  }
}

function revisionItems(definition, existingItems, recipeId, dropObsoleteInputs) {
  const graph = revisionBindings(definition), validNodeIds = new Set(graph.nodes.map(node => String(node.id)))
  const obsoleteItems = (existingItems || []).filter(item => item.workflowMaterialNodeId && !validNodeIds.has(String(item.workflowMaterialNodeId)))
  if (obsoleteItems.length && !dropObsoleteInputs) return { conflict: obsoleteItems.map(item => ({ id: item.id, materialTypeId: item.materialTypeId, workflowMaterialNodeId: item.workflowMaterialNodeId })) }
  const retained = (existingItems || []).filter(item => !obsoleteItems.some(value => String(value.id) === String(item.id)))
  const next = retained.map(clone)
  const inputPorts = graph.processNodes.flatMap(node => (node.data?.ports || []).filter(port => port?.direction === 'INPUT').map(port => ({ node, port })))
  for (const { node, port } of inputPorts) {
    const found = next.find(item => String(item.workflowInputPortId || '') === String(port.id || '') || String(item.workflowMaterialNodeId || '') === String(port.materialNodeId || '') && String(item.materialTypeId || item.semiFinishedRefCode || '') === String(port.skuId || ''))
    if (!found) next.push(revisionItemFromPort(definition, node, port, next.length))
  }
  for (const binding of graph.packaging) {
    const found = next.find(item => String(item.materialTypeId) === String(binding.materialTypeId) && item.materialCategory === 'PACKAGING')
    if (!found) next.push({ ...clone(binding), id: null, recipeId, factoryId: FACTORY, unit: binding.unit || null, standardQuantity: binding.standardQuantity, naturalQuantity: binding.standardQuantity, naturalUnit: binding.unit || null, packagingSpecId: binding.packagingSpecId || null, packagingRole: binding.packagingRole || null, materialName: binding.materialName || null, sortOrder: next.length, deleted: false })
  }
  return { items: next.map((item, index) => ({ ...item, id: item.id || null, recipeId, factoryId: FACTORY, sortOrder: item.sortOrder ?? index, updatedAt: now(), prototypeLocalOnly: true })), obsoleteIds: obsoleteItems.map(item => item.id).filter(Boolean) }
}

function revisionSeasonings(definition, existingItems, recipeId, dropObsoleteInputs) {
  const graph = revisionBindings(definition), obsolete = (existingItems || []).filter(item => item.workflowProcessNodeId && !graph.processIds.has(String(item.workflowProcessNodeId)))
  if (obsolete.length && !dropObsoleteInputs) return { conflict: obsolete.map(item => ({ id: item.id, materialTypeId: item.materialTypeId, workflowProcessNodeId: item.workflowProcessNodeId })) }
  const items = (existingItems || []).filter(item => !obsolete.some(value => String(value.id) === String(item.id))).map(item => ({ ...clone(item), recipeId, factoryId: FACTORY, updatedAt: now() }))
  for (const node of graph.processNodes) for (const [index, binding] of (node.data?.materialBindings || []).entries()) {
    if (!binding?.materialTypeId || !Number.isFinite(Number(binding.dosagePerKgG))) continue
    const exists = items.some(item => String(item.workflowProcessNodeId || '') === String(node.id) && String(item.materialTypeId) === String(binding.materialTypeId))
    if (exists) continue
    items.push({ id: null, recipeId, factoryId: FACTORY, workflowProcessNodeId: node.id, workProcessId: node.data.workProcessId || node.id, materialTypeId: binding.materialTypeId, section: 'COOKING', seq: index, name: binding.materialName || String(binding.materialTypeId), dosagePerKgG: Number(binding.dosagePerKgG), subsequentPotRatio: binding.subsequentPotRatio ?? null, priceSource1: binding.priceSource1 ?? null, priceSource2: binding.priceSource2 ?? null, countInSeasoning: binding.countInSeasoning !== false, remark: binding.remark ?? null, deleted: false, updatedAt: now() })
  }
  return { items, obsoleteIds: obsolete.map(item => item.id).filter(Boolean) }
}

function revisionMetadata(row, definition) {
  return {
    ...row, workflowRevisionId: definition.revisionId ?? null, workflowId: definition.id ?? null,
    workflowDefinitionVersion: definition.version ?? null, workflowRevisionHash: definition.revisionHash ?? null,
    workflowRevisionStatus: definition.status ?? null, workflowDefinition: clone(definition),
    workflowNodesSnapshotJson: JSON.stringify(definition.nodes || []), workflowEdgesSnapshotJson: JSON.stringify(definition.edges || []),
  }
}

function assignMissingRowIds(w, row) {
  let itemId = maxItemId(w), seasoningId = maxSeasoningId(w)
  return {
    ...row,
    items: (row.items || []).map(item => ({ ...item, id: item.id == null ? itemId++ : item.id })),
    seasoningItems: (row.seasoningItems || []).map(item => ({ ...item, id: item.id == null ? seasoningId++ : item.id })),
  }
}

function removeSubstitutesFor(w, recipeId, parentIds) {
  if (!parentIds.length) return
  const model = w.models.get(`${RECIPE_BASE}/${recipeId}/substitutes`)
  if (!model) return
  const ids = new Set(parentIds.map(String))
  for (const value of w.rows(model)) if (ids.has(String(value.parentRecipeItemId ?? value.parentSeasoningItemId))) w.set(model, value.id, value, true)
}

function applyRevisionToDraft(w, row, definition, dropObsoleteInputs) {
  const itemResult = revisionItems(definition, row.items || [], row.id, dropObsoleteInputs)
  const seasoningResult = revisionSeasonings(definition, row.seasoningItems || [], row.id, dropObsoleteInputs)
  if (itemResult.conflict || seasoningResult.conflict) return { conflict: [...(itemResult.conflict || []), ...(seasoningResult.conflict || [])] }
  if (dropObsoleteInputs) removeSubstitutesFor(w, row.id, [...(itemResult.obsoleteIds || []), ...(seasoningResult.obsoleteIds || [])])
  return { row: assignMissingRowIds(w, { ...revisionMetadata(row, definition), items: itemResult.items, seasoningItems: seasoningResult.items, updatedAt: now(), prototypeLocalOnly: true }) }
}

function seasoningResponse(w, row) {
  if (!buildWorkspace(w, row)) return null
  return {
    bomRecipeId: row.id,
    productTypeId: row.productTypeId,
    productName: row.productName,
    status: row.status,
    seasoningItems: seasoningsFor(row),
    injectionConfigs: clone(row.injectionConfigs || []),
  }
}

function substituteRows(w, row) {
  const base = `${RECIPE_BASE}/${row.id}/substitutes`
  const model = w.models.get(base)
  if (model) return w.rows(model).filter(item => !item.deleted && !item.deletedAt).map(clone)
  const rows = []
  for (const item of [...(row.items || []), ...(row.seasoningItems || [])]) {
    for (const value of item.substitutes || item.substituteDetails || []) rows.push({ ...clone(value), recipeId: row.id, factoryId: FACTORY })
  }
  return rows
}

function saveSubstitutes(w, row, item, substitutes) {
  const base = `${RECIPE_BASE}/${row.id}/substitutes`
  const model = modelFor(w, base)
  const current = substituteRows(w, row)
  const parentKind = row.seasoningItems?.some(value => String(value.id) === String(item.id)) ? 'SEASONING_ITEM' : 'RECIPE_ITEM'
  const next = current.filter(value => !(String(value.parentRecipeItemId ?? value.parentSeasoningItemId) === String(item.id)))
  for (const input of substitutes || []) {
    const materialTypeId = input.substituteMaterialTypeId || input.materialTypeId
    if (!materialTypeId) throw new Error('替代物料必须提供 substituteMaterialTypeId。')
    next.push({ ...clone(input), id: input.id || crypto.randomUUID(), recipeId: row.id, factoryId: FACTORY, parentKind, ...(parentKind === 'SEASONING_ITEM' ? { parentSeasoningItemId: item.id } : { parentRecipeItemId: item.id }), parentMaterialTypeId: item.materialTypeId, substituteMaterialTypeId: String(materialTypeId), conversionFactor: input.conversionFactor == null ? 1 : Number(input.conversionFactor), updatedAt: now() })
  }
  for (const previous of current) if (!next.some(value => String(value.id) === String(previous.id))) w.set(model, previous.id, previous, true)
  for (const value of next) w.set(model, value.id, value)
  return next
}

function findItem(w, itemId) {
  for (const row of recipeRows(w)) {
    const item = (row.items || []).find(value => String(value.id) === String(itemId))
    if (item) return { row, kind: 'item', item }
    const seasoning = (row.seasoningItems || []).find(value => String(value.id) === String(itemId))
    if (seasoning) return { row, kind: 'seasoning', item: seasoning }
  }
  return null
}

function saveRecipeItem(w, row, input, old, send, res) {
  const list = [...(row.items || [])]
  const index = old ? list.findIndex(value => String(value.id) === String(old.id)) : -1
  let value
  try { value = normalizeItem(w, row.id, input, old, old ? old.id : maxItemId(w)) } catch (error) { return fail(send, res, 422, 'ITEM_SCHEMA_INVALID', error.message) }
  if (index < 0) list.push(value); else list[index] = value
  const next = { ...row, items: list, updatedAt: now(), prototypeLocalOnly: true }
  saveSubstitutes(w, next, value, input.substitutes)
  saveRow(w, recipeModel(w), row, next, index < 0 ? 'create' : 'update', `${RECIPE_BASE}/${row.id}/items`)
  return ok(send, res, value)
}

function findSeasoning(row, id) {
  return (row.seasoningItems || []).find(item => String(item.id) === String(id)) || null
}

function revisionGuard(input, row, send, res) {
  const expected = input?.expectedRevision
  if (expected == null || Number(expected) !== Number(row.seasoningRevision || 0)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '调料配置已更新，请重新载入 workspace 后重试。')
  return null
}

export async function handleFusionBom({ parent, root, role, session, method, target, body, send, res }) {
  const rawTarget = String(target || '')
  if (role === 'factory' && /^\/api\/mobile\/[^/]+\/bom\/recipes(?:\/|$)/.test(rawTarget) && !isFactory(role, rawTarget)) return fail(send, res, 403, 'TENANT_MISMATCH', 'BOM 本机原型仅允许 F006 工厂工作区。')
  if (!isFactory(role, rawTarget)) return false
  const parsed = new URL(String(target), 'http://prototype.local')
  const pathname = parsed.pathname
  if(!pathname.startsWith(RECIPE_BASE)&&!/^\/api\/mobile\/F006\/product-types\/[^/]+\/packaging-specs$/.test(pathname))return false
  const input = bodyJson(body)
  if (!['GET', 'HEAD'].includes(method) && input == null) return fail(send, res, 400, 'SCHEMA', '请求体不是有效 JSON。')
  const w = fusionWorkspace(workspaceParent(parent, root), role, session)
  // fusionWorkspace builds source models first; sync state changes again so
  // session-only auxiliary models (for example /substitutes) are readable.
  w.syncShared()

  // Packaging specs live beside BOM recipes, not below the recipe prefix.
  const packagingSpecs = pathname.match(new RegExp(`^/api/mobile/${FACTORY}/product-types/([^/]+)/packaging-specs$`))
  if (packagingSpecs) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '包装规格仅支持 GET。')
    const captured = w.read('GET', pathname, null)
    if (!captured || captured.status !== 200) return fail(send, res, 404, 'PACKAGING_SPECS_NOT_CAPTURED', '当前账号快照未收录该产品包装规格，未编造空配置。')
    return ok(send, res, captured.body?.data ?? captured.body, '已读取 F006 本机包装规格。')
  }
  if (!pathname.startsWith(RECIPE_BASE)) return false
  const suffix = pathname.slice(RECIPE_BASE.length)
  const parts = suffix.split('/').filter(Boolean)
  const model = recipeModel(w)
  if (!model) return fail(send, res, 404, 'BOM_NOT_CAPTURED', '当前账号快照未收录 BOM 目录，未访问生产。')

  if (parts.length === 1 && parts[0] === 'ensure-draft') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'ensure-draft 仅支持 POST。')
    const tenantError = tenantGuard(input); if (tenantError) return fail(send, res, 422, 'TENANT_MISMATCH', tenantError)
    if (!input.productTypeId) return fail(send, res, 422, 'PRODUCT_TYPE_REQUIRED', 'productTypeId 不能为空。')
    const workflowRoot = root || path.join(workspaceParent(parent, root), 'samples-v5')
    const definition = input.workflowRevisionId == null ? null : await workflowRevision(workflowRoot, session, input.workflowRevisionId, input.productTypeId)
    if (input.workflowRevisionId != null && !definition) return fail(send, res, 404, 'WORKFLOW_REVISION_NOT_FOUND', '指定的本机 Workflow revision 不存在，未回落到其他版本。')
    const existing = recipeRows(w).find(row => String(row.productTypeId) === String(input.productTypeId) && row.status === 'DRAFT')
    if (existing && definition && (!existing.workflowDefinition || String(existing.workflowRevisionId || '') !== String(definition.revisionId || '') || String(existing.workflowRevisionHash || '') !== String(definition.revisionHash || ''))) {
      const rebased = applyRevisionToDraft(w, existing, definition, input.dropObsoleteInputs === true)
      if (rebased.conflict?.length) return fail(send, res, 409, 'BOM_REVISION_OBSOLETE_INPUTS', `新 Workflow revision 不再包含已有投入或调料绑定；请确认 dropObsoleteInputs 后重试。(${rebased.conflict.map(item => item.id).join(', ')})`)
      saveRow(w, model, existing, rebased.row, 'update', `${RECIPE_BASE}/ensure-draft`)
      return ok(send, res, recipeSummary(rebased.row), '已按指定 Workflow revision 重建本机 BOM 草稿。')
    }
    if (existing) return ok(send, res, recipeSummary(existing), '已回读现有本机 BOM 草稿。')
    const source = recipeRows(w).filter(row => String(row.productTypeId) === String(input.productTypeId)).sort((a, b) => Number(b.version || 0) - Number(a.version || 0))[0]
    if (!source) {
      const product = productRecord(w, input.productTypeId)
      if (!product) return fail(send, res, 404, 'PRODUCT_NOT_FOUND', '产品不存在于当前工厂。')
      const id = crypto.randomUUID(), at = now()
      let items = [], seasoningItems = []
      if (definition) {
        const projected = applyRevisionToDraft(w, { id, productTypeId: product.id, productName: product.name, items: [], seasoningItems: [], status: 'DRAFT', seasoningRevision: 0 }, definition, true)
        items = projected.row.items; seasoningItems = projected.row.seasoningItems
      }
      const next = revisionMetadata({ id, factoryId: FACTORY, productTypeId: product.id, productName: product.name, recipeCode: `BOM-LOCAL-${id.slice(0, 8).toUpperCase()}`, version: 1, status: 'DRAFT', isCurrent: false, seasoningRevision: 0, outputUnit: product.unit ?? null, outputQuantityPerUnit: null, overallYieldRate: null, totalCost: null, items, seasoningItems, createdAt: at, updatedAt: at, prototypeLocalOnly: true }, definition || {})
      saveRow(w, model, null, next, 'create', `${RECIPE_BASE}/ensure-draft`)
      return ok(send, res, recipeSummary(next), definition ? '已按指定 Workflow revision 建立本机 BOM 草稿。' : '已为当前产品建立本机空白配方草稿。')
    }
    const id = crypto.randomUUID(), at = now(), sourceSeasonings = sourceSeasoningItems(w, source)
    let next = { ...clone(source), id, recipeCode: `BOM-LOCAL-${id.slice(0, 8).toUpperCase()}`, version: Number(source.version || 0) + 1, status: 'DRAFT', isCurrent: false, activatedAt: null, activatedBy: null, createdAt: at, updatedAt: at, prototypeLocalOnly: true, items: (source.items || []).map((item, index) => ({ ...clone(item), id: maxItemId(w) + index, recipeId: id })), seasoningItems: sourceSeasonings.map((item, index) => ({ ...clone(item), id: maxSeasoningId(w) + index, recipeId: id })) }
    if (definition) {
      const rebased = applyRevisionToDraft(w, next, definition, input.dropObsoleteInputs === true)
      if (rebased.conflict?.length) return fail(send, res, 409, 'BOM_REVISION_OBSOLETE_INPUTS', `新 Workflow revision 不再包含已有投入或调料绑定；请确认 dropObsoleteInputs 后重试。(${rebased.conflict.map(item => item.id).join(', ')})`)
      next = rebased.row
      next.items = next.items.map((item, index) => ({ ...item, id: maxItemId(w) + index, recipeId: id }))
      next.seasoningItems = next.seasoningItems.map((item, index) => ({ ...item, id: maxSeasoningId(w) + index, recipeId: id }))
    }
    saveRow(w, model, null, next, 'create', `${RECIPE_BASE}/ensure-draft`)
    return ok(send, res, recipeSummary(next), '已从本机 BOM 快照创建可编辑草稿。')
  }

  // /recipes and /recipes/{id} lifecycle
  if (parts.length === 0) {
    if (method === 'GET') {
      const statuses = parsed.searchParams.get('status')?.split(',').filter(Boolean)
      const rows = recipeRows(w).filter(row => !statuses?.length || statuses.includes(row.status))
      const page = Math.max(0, Number(parsed.searchParams.get('page') || 0)), size = Math.max(0, Number(parsed.searchParams.get('size') || 0))
      const content = size ? rows.slice(page * size, page * size + size) : rows
      return ok(send, res, { content, totalElements: rows.length, totalPages: size ? Math.ceil(rows.length / size) : 1 }, '已读取当前 F006 本机 BOM 目录。')
    }
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'BOM 目录仅支持 GET/POST。')
    const tenantError = tenantGuard(input); if (tenantError) return fail(send, res, 422, 'TENANT_MISMATCH', tenantError)
    if (!input.productTypeId) return fail(send, res, 422, 'PRODUCT_TYPE_REQUIRED', 'productTypeId 不能为空。')
    let items = []
    try { items = input.items == null ? [] : normalizeRecipeItems(w, 'pending', input.items) } catch (error) { return fail(send, res, 422, 'ITEM_SCHEMA_INVALID', error.message) }
    const id = crypto.randomUUID(), at = now(), product = w.row(w.models.get('/api/mobile/F006/product-types'), input.productTypeId)
    const next = { id, factoryId: FACTORY, recipeCode: `BOM-LOCAL-${id.slice(0, 8).toUpperCase()}`, productTypeId: String(input.productTypeId), productName: input.productName || product?.name || null, version: nextNumber(recipeRows(w).filter(row => String(row.productTypeId) === String(input.productTypeId)).map(row => row.version)), isCurrent: false, status: 'DRAFT', sourceType: input.sourceType || 'MANUAL', sourceSampleId: input.sourceSampleId || null, overallYieldRate: input.overallYieldRate ?? null, outputQuantityPerUnit: input.outputQuantityPerUnit ?? null, outputUnit: input.outputUnit ?? null, notes: input.notes ?? null, items: items.map(item => ({ ...item, recipeId: id })), seasoningItems: [], seasoningRevision: 0, createdAt: at, updatedAt: at, deleted: false, prototypeLocalOnly: true }
    saveRow(w, model, null, next, 'create')
    return ok(send, res, recipeSummary(next), '已创建 F006 本机 BOM 草稿。')
  }

  const recipeId = parts[0]
  if (!/^[A-Za-z0-9:_-]+$/.test(recipeId)) return false
  const current = recipe(w, recipeId)
  if (parts.length === 1) {
    if (method === 'GET') return current ? ok(send, res, recipeSummary(current), '已读取 F006 本机 BOM 配方。') : fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在于当前 F006 本机快照。')
    if (method === 'PUT') {
      const guard = editableRecipe(send, res, current); if (guard) return guard
      const tenantError = tenantGuard(input); if (tenantError) return fail(send, res, 422, 'TENANT_MISMATCH', tenantError)
      const conflict = recipeRevisionGuard(input, current); if (conflict) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', conflict)
      const next = { ...current, ...Object.fromEntries(['productName', 'overallYieldRate', 'outputQuantityPerUnit', 'outputUnit', 'notes'].filter(key => Object.hasOwn(input, key)).map(key => [key, input[key]])), updatedAt: now(), prototypeLocalOnly: true }
      if (input.items != null) { try { next.items = normalizeRecipeItems(w, current.id, input.items) } catch (error) { return fail(send, res, 422, 'ITEM_SCHEMA_INVALID', error.message) } }
      saveRow(w, model, current, next)
      return ok(send, res, recipeSummary(next))
    }
    if (method === 'DELETE') {
      if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在于当前 F006 本机快照。')
      if (current.status === 'ACTIVE') return fail(send, res, 409, 'ACTIVE_RECIPE_DELETE_FORBIDDEN', '当前 ACTIVE 配方不能删除。')
      w.set(model, recipeId, current, true); w.log({ method: 'DELETE', path: `${RECIPE_BASE}/${recipeId}`, type: 'delete', id: recipeId, before: clone(current), after: null, at: now() })
      return ok(send, res, null, '已删除本机草稿。')
    }
    return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '当前 BOM 配方仅支持 GET/PUT/DELETE。')
  }

  if(parts.length===3&&parts[0]==='by-product'&&parts[2]==='seasoning'&&method==='GET'){
    const rows=recipeRows(w).filter(row=>String(row.productTypeId)===String(parts[1])).sort((a,b)=>Number(b.version)-Number(a.version)),selected=rows.find(row=>row.status==='ACTIVE'&&row.isCurrent)||rows.find(row=>row.status==='DRAFT');if(!selected)return fail(send,res,404,'PRODUCT_BOM_NOT_CONFIGURED','该产品尚未建立可用 BOM 配方。');return ok(send,res,{bomRecipeId:selected.id,productTypeId:selected.productTypeId,productName:selected.productName,status:selected.status,seasoningItems:seasoningsFor(selected),injectionConfigs:clone(selected.injectionConfigs||[])},'已按实际配方读取调料配置。')
  }
  // Product directory and versions.
  if (parts[0] === 'by-product' && parts[2] === 'current' || parts[0] === 'by-product' && parts[2] === 'versions') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '按产品读取仅支持 GET。')
    const rows = recipeRows(w).filter(row => String(row.productTypeId) === String(parts[1])).sort((a, b) => Number(b.version || 0) - Number(a.version || 0))
    if (!rows.length) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '该产品没有收录的 BOM 配方。')
    return ok(send, res, parts[2] === 'versions' ? rows : rows.find(row => row.status === 'ACTIVE' && row.isCurrent) || null, '已读取按产品的本机 BOM 版本。')
  }


  if (parts.length === 2 && parts[1] === 'clone') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'clone 仅支持 POST。')
    if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '待克隆配方不存在。')
    const id = crypto.randomUUID(), at = now(), sameProduct = recipeRows(w).filter(row => String(row.productTypeId) === String(current.productTypeId)), sourceSeasonings = sourceSeasoningItems(w, current), next = { ...clone(current), id, recipeCode: `BOM-LOCAL-${id.slice(0, 8).toUpperCase()}`, version: nextNumber(sameProduct.map(row => row.version)), status: 'DRAFT', isCurrent: false, activatedAt: null, activatedBy: null, createdAt: at, updatedAt: at, prototypeLocalOnly: true, items: (current.items || []).map(item => ({ ...clone(item), id: maxItemId(w) + Number(item.id) - Number(current.items?.[0]?.id || item.id), recipeId: id })), seasoningItems: sourceSeasonings.map((item, index) => ({ ...clone(item), id: maxSeasoningId(w) + index, recipeId: id })) }
    saveRow(w, model, null, next, 'create', `${RECIPE_BASE}/${recipeId}/clone`)
    return ok(send, res, recipeSummary(next), '已克隆本机 BOM 草稿。')
  }

  if (parts.length === 2 && parts[1] === 'activate') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'activate 仅支持 POST。')
    const guard = editableRecipe(send, res, current); if (guard) return guard
    if(!(current.items||[]).length&&!(current.seasoningItems||[]).length)return fail(send,res,422,'EMPTY_RECIPE','请先配置实际用料，再启用配方。')
    const at = now(), rows = recipeRows(w).filter(row => String(row.productTypeId) === String(current.productTypeId))
    for (const row of rows) if (String(row.id) !== String(current.id) && (row.status === 'ACTIVE' || row.isCurrent)) saveRow(w, model, row, { ...row, status: 'ARCHIVED', isCurrent: false, updatedAt: at, prototypeLocalOnly: true })
    const next = { ...current, status: 'ACTIVE', isCurrent: true, activatedAt: at, activatedBy: input?.operatorId ?? null, updatedAt: at, prototypeLocalOnly: true }
    saveRow(w, model, current, next, 'activate', `${RECIPE_BASE}/${recipeId}/activate`)
    return ok(send, res, recipeSummary(next), '本机 BOM 草稿已激活；未执行生产写入。')
  }

  // Recipe item CRUD.
  if (parts.length === 2 && parts[1] === 'items') {
    const guard = editableRecipe(send, res, current); if (guard) return guard
    if (method === 'POST') return saveRecipeItem(w, current, input, null, send, res)
    return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '物料行创建仅支持 POST。')
  }
  if (parts[0] === 'items' && parts.length === 2) {
    const located = findItem(w, parts[1])
    if (!located || located.kind !== 'item') return fail(send, res, 404, 'ITEM_NOT_FOUND', '物料行不存在于当前 F006 本机快照。')
    const guard = editableRecipe(send, res, located.row); if (guard) return guard
    if (method === 'PUT') return saveRecipeItem(w, located.row, input, located.item, send, res)
    if (method === 'DELETE') {
      const next = { ...located.row, items: (located.row.items || []).filter(item => String(item.id) !== String(parts[1])), updatedAt: now(), prototypeLocalOnly: true }
      saveRow(w, model, located.row, next, 'delete', `${RECIPE_BASE}/items/${parts[1]}`)
      return ok(send, res, null, '物料行已从本机草稿删除。')
    }
    return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '物料行仅支持 PUT/DELETE。')
  }

  // Seasoning read and full replace.
  if (parts.at(-1) === 'workspace' && parts.length === 3) {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'seasoning workspace 仅支持 GET。')
    if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在。')
    const workspace = buildWorkspace(w, current)
    return workspace ? ok(send, res, workspace, '已读取 F006 本机调料工作区。') : fail(send, res, 404, 'WORKSPACE_NOT_CAPTURED', '当前配方没有收录的调料 workspace，未编造空配置。')
  }
  if (parts.at(-1) === 'seasoning' && parts.length === 2) {
    if (method === 'GET') {
      if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在。')
      const data = seasoningResponse(w, current)
      return data ? ok(send, res, data, '已读取 F006 本机调料配置。') : fail(send, res, 404, 'SEASONING_NOT_CAPTURED', '当前配方没有收录调料配置，未编造空配置。')
    }
    const guard = editableRecipe(send, res, current); if (guard) return guard
    if (method !== 'PUT') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'seasoning 仅支持 GET/PUT。')
    const tenantError = tenantGuard(input); if (tenantError) return fail(send, res, 422, 'TENANT_MISMATCH', tenantError)
    if (!Array.isArray(input.seasoningItems) || !Array.isArray(input.injectionConfigs)) return fail(send, res, 422, 'SEASONING_SCHEMA_INVALID', 'seasoningItems 和 injectionConfigs 必须是数组。')
    const revision = input.expectedRevision == null ? null : Number(input.expectedRevision)
    if (revision != null && revision !== Number(current.seasoningRevision || 0)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '调料配置已更新，请重新载入 workspace 后重试。')
    const at = now(), nextItems = []
    try {
      for (const value of input.seasoningItems) {
        const old = findSeasoning(current, value.id)
        if (!value.workProcessId || !value.materialTypeId || !value.section || !Number.isFinite(Number(value.seq)) || value.name == null || value.dosagePerKgG == null || !Number.isFinite(Number(value.dosagePerKgG))) throw new Error('调料行必须提供 workProcessId、materialTypeId、section、seq、name 和 dosagePerKgG。')
        nextItems.push({ ...clone(old || {}), ...clone(value), id: old?.id || value.id || maxSeasoningId(w) + nextItems.length, recipeId: current.id, factoryId: FACTORY, createdAt: old?.createdAt || at, updatedAt: at, deleted: false })
      }
    } catch (error) { return fail(send, res, 422, 'SEASONING_SCHEMA_INVALID', error.message) }
    const next = { ...current, seasoningItems: nextItems, injectionConfigs: clone(input.injectionConfigs), seasoningRevision: Number(current.seasoningRevision || 0) + 1, updatedAt: at, prototypeLocalOnly: true }
    saveRow(w, model, current, next, 'update', `${RECIPE_BASE}/${recipeId}/seasoning`)
    return ok(send, res, seasoningResponse(w, next))
  }

  // Seasoning binding CRUD with expectedRevision optimistic lock.
  if (parts.length === 5 && parts[1] === 'seasoning' && parts[2] === 'processes' && parts[4] === 'bindings') {
    if (method !== 'POST') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', 'binding 创建仅支持 POST。')
    const guard = editableRecipe(send, res, current); if (guard) return guard
    const conflict = revisionGuard(input, current, send, res); if (conflict) return conflict
    const template = buildWorkspace(w, current), process = processForWorkspace(template, parts[3])
    if (!process) return fail(send, res, 404, 'WORKFLOW_PROCESS_NOT_FOUND', '工序不在当前 BOM workspace 中。')
    if (!input.materialTypeId || !Number.isFinite(Number(input.dosagePerKgG))) return fail(send, res, 422, 'BINDING_SCHEMA_INVALID', '绑定必须提供 materialTypeId 和 dosagePerKgG。')
    const at = now(), binding = { ...clone(input), id: maxSeasoningId(w), recipeId: current.id, factoryId: FACTORY, workProcessId: parts[3], workflowProcessNodeId: input.workflowProcessNodeId || process.workflowProcessNodeId, section: input.section || 'COOKING', seq: Number(input.seq ?? (current.seasoningItems || []).filter(item => String(item.workProcessId) === String(parts[3])).length), name: input.name || materialName(w, input.materialTypeId) || String(input.materialTypeId), dosagePerKgG: Number(input.dosagePerKgG), countInSeasoning: input.countInSeasoning !== false, subsequentPotRatio: input.subsequentPotRatio ?? null, createdAt: at, updatedAt: at, deleted: false }
    const next = { ...current, seasoningItems: [...(current.seasoningItems || []), binding], seasoningRevision: Number(current.seasoningRevision || 0) + 1, updatedAt: at, prototypeLocalOnly: true }
    if (input.substitutes) { try { saveSubstitutes(w, next, binding, input.substitutes) } catch (error) { return fail(send, res, 422, 'SUBSTITUTE_SCHEMA_INVALID', error.message) } }
    saveRow(w, model, current, next, 'create', `${RECIPE_BASE}/${recipeId}/seasoning/processes/${parts[3]}/bindings`)
    return ok(send, res, { seasoningRevision: next.seasoningRevision, binding })
  }
  if (parts.length === 4 && parts[1] === 'seasoning' && parts[2] === 'bindings') {
    const located = current && findSeasoning(current, parts[3]) ? { row: current, item: findSeasoning(current, parts[3]) } : findItem(w, parts[3])
    if (!located || located.kind === 'item') return fail(send, res, 404, 'BINDING_NOT_FOUND', '调料绑定不存在于当前本机快照。')
    const guard = editableRecipe(send, res, located.row); if (guard) return guard
    if (method === 'PUT') {
      const conflict = revisionGuard(input, located.row, send, res); if (conflict) return conflict
      const at = now(), nextBinding = { ...clone(located.item), ...clone(input), id: located.item.id, recipeId: located.row.id, factoryId: FACTORY, updatedAt: at, deleted: false }
      delete nextBinding.expectedRevision
      const next = { ...located.row, seasoningItems: (located.row.seasoningItems || []).map(item => String(item.id) === String(located.item.id) ? nextBinding : item), seasoningRevision: Number(located.row.seasoningRevision || 0) + 1, updatedAt: at, prototypeLocalOnly: true }
      if (input.substitutes) { try { saveSubstitutes(w, next, nextBinding, input.substitutes) } catch (error) { return fail(send, res, 422, 'SUBSTITUTE_SCHEMA_INVALID', error.message) } }
      saveRow(w, model, located.row, next, 'update', `${RECIPE_BASE}/${recipeId}/seasoning/bindings/${parts[3]}`)
      return ok(send, res, { seasoningRevision: next.seasoningRevision, binding: nextBinding })
    }
    if (method === 'DELETE') {
      const expected = parsed.searchParams.get('expectedRevision') ?? input?.expectedRevision
      if (expected == null || Number(expected) !== Number(located.row.seasoningRevision || 0)) return fail(send, res, 409, 'OPTIMISTIC_LOCK_CONFLICT', '调料配置已更新，请重新载入 workspace 后重试。')
      const next = { ...located.row, seasoningItems: (located.row.seasoningItems || []).filter(item => String(item.id) !== String(located.item.id)), seasoningRevision: Number(located.row.seasoningRevision || 0) + 1, updatedAt: now(), prototypeLocalOnly: true }
      saveSubstitutes(w, next, located.item, [])
      saveRow(w, model, located.row, next, 'delete', `${RECIPE_BASE}/${recipeId}/seasoning/bindings/${parts[3]}`)
      return ok(send, res, { seasoningRevision: next.seasoningRevision, binding: located.item })
    }
    return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '调料绑定仅支持 PUT/DELETE。')
  }

  // Substitutes are read-only unless carried by a typed item/binding mutation.
  if (parts.length === 2 && parts[1] === 'substitutes') {
    if (method !== 'GET') return fail(send, res, 405, 'METHOD_NOT_ALLOWED', '替代关系只通过物料行/调料绑定保存。')
    if (!current) return fail(send, res, 404, 'RECIPE_NOT_FOUND', '配方不存在。')
    return ok(send, res, substituteRows(w, current), '已读取 F006 本机替代关系。')
  }

  return false
}

export default handleFusionBom
