import { get } from './request'
import type { ApiResponse } from '@/types/api'

export interface PlanConcurrentReadinessMaterial {
  materialTypeId: string
  materialName: string
  unit: string
  required: number | null
  consumed: number | null
  ownHeldReserved: number | null
  otherUnoccupied: number | null
  stockFree: number | null
  lowerPercent: number | null
  upperPercent: number | null
}

export type PlanConcurrentReadinessStatus = 'VERIFIED' | 'UNVERIFIED'

export interface PlanConcurrentReadiness {
  planId: string
  planNumber: string
  planStatus: string
  demandThroughDate: string | null
  observedAt: string
  cohortBasis: string
  consideredPlanCount: number
  unverifiedPlanCount: number
  notes: string[]
  material: {
    status: PlanConcurrentReadinessStatus
    reason: string
    lowerPercent: number | null
    upperPercent: number | null
    items: PlanConcurrentReadinessMaterial[]
  }
  progress: {
    status: PlanConcurrentReadinessStatus
    basis: 'FINISHED_GOODS_RECEIVED'
    plannedQuantity: number | null
    receivedQuantity: number | null
    remainingQuantity: number | null
    completionPercent: number | null
    unit: string | null
    reason: string | null
  }
  advisoryOnly: true
}

type UnknownRecord = Record<string, unknown>

function record(value: unknown): UnknownRecord | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as UnknownRecord
    : null
}

function boundedText(value: unknown, label: string, allowNull = false, maxLength = 191): string | null {
  if (allowNull && value === null) return null
  if (typeof value !== 'string' || value.trim().length === 0 || value.length > maxLength) {
    throw new Error(`同期可用性响应字段无效：${label}`)
  }
  return value
}

function nullableText(value: unknown, label: string): string | null {
  if (value === undefined) throw new Error(`同期可用性响应字段缺失：${label}`)
  return value === null ? null : boundedText(value, label, false)
}

function readinessStatus(value: unknown, label: string): PlanConcurrentReadinessStatus {
  if (value !== 'VERIFIED' && value !== 'UNVERIFIED') {
    throw new Error(`同期可用性状态无效：${label}`)
  }
  return value
}

function quantity(value: unknown, label: string): number | null {
  if (value === undefined) throw new Error(`同期可用性响应字段缺失：${label}`)
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`同期可用性响应字段无效：${label}`)
  }
  return value
}

function percent(value: unknown, label: string): number | null {
  if (value === undefined) throw new Error(`同期可用性响应字段缺失：${label}`)
  if (value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 100) {
    throw new Error(`同期可用性百分比无效：${label}`)
  }
  return value
}

function nonNegativeInteger(value: unknown, label: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
    throw new Error(`同期可用性计数无效：${label}`)
  }
  return value
}

function isoDateOrNull(value: unknown, label: string): string | null {
  if (value === undefined) throw new Error(`同期可用性响应字段缺失：${label}`)
  if (value === null) return null
  const text = boundedText(value, label, false) as string
  if (Number.isNaN(Date.parse(text))) throw new Error(`同期可用性日期无效：${label}`)
  return text
}

function material(value: unknown, index: number): PlanConcurrentReadinessMaterial {
  const row = record(value)
  if (!row) throw new Error(`同期可用性物料行无效：${index}`)
  return {
    materialTypeId: boundedText(row.materialTypeId, `material[${index}].materialTypeId`) as string,
    materialName: boundedText(row.materialName, `material[${index}].materialName`, false, 512) as string,
    unit: boundedText(row.unit, `material[${index}].unit`) as string,
    required: quantity(row.required, `material[${index}].required`),
    consumed: quantity(row.consumed, `material[${index}].consumed`),
    ownHeldReserved: quantity(row.ownHeldReserved, `material[${index}].ownHeldReserved`),
    otherUnoccupied: quantity(row.otherUnoccupied, `material[${index}].otherUnoccupied`),
    stockFree: quantity(row.stockFree, `material[${index}].stockFree`),
    lowerPercent: percent(row.lowerPercent, `material[${index}].lowerPercent`),
    upperPercent: percent(row.upperPercent, `material[${index}].upperPercent`),
  }
}

export function parsePlanConcurrentReadiness(value: unknown): PlanConcurrentReadiness {
  const root = record(value)
  if (!root) throw new Error('同期可用性响应不是对象')
  const materialValue = record(root.material)
  const progressValue = record(root.progress)
  const notes = root.notes
  if (!materialValue || !progressValue || !Array.isArray(notes) || notes.some(note => typeof note !== 'string' || note.length > 500)) {
    throw new Error('同期可用性响应结构不完整')
  }
  const basis = boundedText(progressValue.basis, 'progress.basis')
  if (basis !== 'FINISHED_GOODS_RECEIVED') {
    throw new Error('成品入库进度口径无效')
  }
  if (root.advisoryOnly !== true) throw new Error('同期可用性结果不是只读建议')
  const items = root.material && Array.isArray(materialValue.items)
    ? materialValue.items.map(material)
    : null
  if (!items) throw new Error('同期可用性物料明细缺失')
  const materialStatus = readinessStatus(materialValue.status, 'material.status')
  const materialReason = boundedText(materialValue.reason, 'material.reason') as string
  const materialLower = percent(materialValue.lowerPercent, 'material.lowerPercent')
  const materialUpper = percent(materialValue.upperPercent, 'material.upperPercent')
  if (materialStatus === 'VERIFIED') {
    if (materialReason !== 'OK' || materialLower === null || materialUpper === null || materialLower > materialUpper) {
      throw new Error('已核算物料响应边界无效')
    }
  } else if (materialLower !== null || materialUpper !== null) {
    throw new Error('待核对物料不得提供确定百分比')
  }
  const progressStatus = readinessStatus(progressValue.status, 'progress.status')
  const progressUnit = nullableText(progressValue.unit, 'progress.unit')
  const plannedQuantity = quantity(progressValue.plannedQuantity, 'progress.plannedQuantity')
  const receivedQuantity = quantity(progressValue.receivedQuantity, 'progress.receivedQuantity')
  const remainingQuantity = quantity(progressValue.remainingQuantity, 'progress.remainingQuantity')
  const completion = quantity(progressValue.completionPercent, 'progress.completionPercent')
  if (progressStatus === 'VERIFIED') {
    if (!progressUnit || plannedQuantity === null || plannedQuantity <= 0
      || receivedQuantity === null || remainingQuantity === null || completion === null) {
      throw new Error('已核算成品入库进度字段无效')
    }
  } else if (receivedQuantity !== null || remainingQuantity !== null || completion !== null) {
    throw new Error('待核对成品入库进度不得提供确定计算值')
  }
  return {
    planId: boundedText(root.planId, 'planId') as string,
    planNumber: boundedText(root.planNumber, 'planNumber') as string,
    planStatus: boundedText(root.planStatus, 'planStatus') as string,
    demandThroughDate: isoDateOrNull(root.demandThroughDate, 'demandThroughDate'),
    observedAt: (() => {
      const value = boundedText(root.observedAt, 'observedAt') as string
      if (Number.isNaN(Date.parse(value))) throw new Error('同期可用性日期无效：observedAt')
      return value
    })(),
    cohortBasis: boundedText(root.cohortBasis, 'cohortBasis') as string,
    consideredPlanCount: nonNegativeInteger(root.consideredPlanCount, 'consideredPlanCount'),
    unverifiedPlanCount: nonNegativeInteger(root.unverifiedPlanCount, 'unverifiedPlanCount'),
    notes: notes.map(note => note as string),
    material: {
      status: materialStatus,
      reason: materialReason,
      lowerPercent: materialLower,
      upperPercent: materialUpper,
      items,
    },
    progress: {
      status: progressStatus,
      basis,
      plannedQuantity,
      receivedQuantity,
      remainingQuantity,
      completionPercent: completion,
      unit: progressUnit,
      reason: nullableText(progressValue.reason, 'progress.reason'),
    },
    advisoryOnly: true,
  }
}

export function getPlanConcurrentReadiness(factoryId: string, planId: string): Promise<ApiResponse<PlanConcurrentReadiness>> {
  if (!factoryId.trim() || !planId.trim()) {
    return Promise.reject(new Error('工厂和生产计划不能为空'))
  }
  return get<PlanConcurrentReadiness>(
    `/${encodeURIComponent(factoryId)}/production-plans/${encodeURIComponent(planId)}/concurrent-readiness`,
  )
}
