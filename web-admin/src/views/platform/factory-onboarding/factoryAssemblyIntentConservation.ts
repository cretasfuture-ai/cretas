import type {
  AlertItem,
  MetricWatchItem,
  ModuleConfigSuggestion,
  SuggestedData,
} from '@/api/factoryOnboardingApi'

export interface ExplicitCatalogSelections {
  alerts: AlertItem[]
  metricWatches: MetricWatchItem[]
}

const MODULE_SWITCH_BY_CONFIG_ID: Readonly<Record<string, string>> = {
  equipment: 'enableEquipmentManagement',
}

function mergeByKey<T>(generated: T[] | undefined, selected: T[], keyOf: (item: T) => string): T[] {
  const merged = new Map<string, T>()
  for (const item of generated ?? []) {
    const key = keyOf(item).trim()
    if (key) merged.set(key, item)
  }
  for (const item of selected) {
    const key = keyOf(item).trim()
    if (key) merged.set(key, item)
  }
  return [...merged.values()]
}

function alignModuleSwitches(
  modules: Record<string, boolean> | undefined,
  moduleConfigs: ModuleConfigSuggestion[] | undefined,
): Record<string, boolean> | undefined {
  const aligned = modules ? { ...modules } : {}
  for (const config of moduleConfigs ?? []) {
    const switchKey = MODULE_SWITCH_BY_CONFIG_ID[config.moduleId.trim().toLowerCase()]
    if (switchKey && config.enabled !== undefined) aligned[switchKey] = config.enabled
  }
  return Object.keys(aligned).length > 0 ? aligned : undefined
}

function alignMaterialCategories(data: SuggestedData): void {
  const available = new Map((data.codeSegments ?? [])
    .map((item) => [item.label.trim().toLowerCase(), item.label.trim()] as const)
    .filter(([key]) => key))
  const raw = available.get('原料')
  const auxiliary = available.get('辅料')
  const packaging = available.get('包材')
  const roles = new Map<string, string>()

  for (const process of data.workProcesses ?? []) {
    for (const item of process.auxMaterials ?? []) {
      if (item.name.trim() && auxiliary) roles.set(item.name.trim(), auxiliary)
    }
    for (const item of process.packMaterials ?? []) {
      if (item.name.trim() && packaging) roles.set(item.name.trim(), packaging)
    }
  }
  for (const material of data.materialTypes) {
    if (!material.category?.trim()) {
      material.category = roles.get(material.name.trim()) ?? raw
    }
  }
}

function alignFinishedProcessUnits(data: SuggestedData): void {
  const productUnits = new Map(data.productTypes
    .filter((product) => product.code.trim() && product.unit.trim())
    .map((product) => [product.code.trim(), product.unit.trim()] as const))
  const unitsByFinalProcess = new Map<string, Set<string>>()

  for (const route of data.productProcesses ?? []) {
    const finalProcess = route.processNames.at(-1)?.trim()
    const unit = productUnits.get(route.productCode.trim())
    if (!finalProcess || !unit) continue
    const units = unitsByFinalProcess.get(finalProcess) ?? new Set<string>()
    units.add(unit)
    unitsByFinalProcess.set(finalProcess, units)
  }
  for (const process of data.workProcesses ?? []) {
    const units = unitsByFinalProcess.get(process.name.trim())
    if (units?.size === 1) process.outputUnit = [...units][0]
  }
}

/**
 * 只合并用户明确选择和生成器明确产出的配置；defaults/current 仅供展示，不能进入写计划。
 */
export function conserveFactoryAssemblyIntent(
  suggested: SuggestedData,
  selections: ExplicitCatalogSelections,
): SuggestedData {
  const data: SuggestedData = {
    ...suggested,
    productTypes: suggested.productTypes.map((product) => ({
      ...product,
      productCategory: product.productCategory?.trim() || 'FINISHED_PRODUCT',
    })),
    materialTypes: suggested.materialTypes.map((material) => ({ ...material })),
    workProcesses: suggested.workProcesses?.map((process) => ({
      ...process,
      auxMaterials: process.auxMaterials?.map((item) => ({ ...item })),
      packMaterials: process.packMaterials?.map((item) => ({ ...item })),
    })),
    productProcesses: suggested.productProcesses?.map((route) => ({
      ...route,
      processNames: [...route.processNames],
      routeNodes: route.routeNodes?.map((node) => ({ ...node })),
      routeEdges: route.routeEdges?.map((edge) => ({ ...edge })),
    })),
    alerts: mergeByKey(suggested.alerts, selections.alerts, (item) => item.alertType),
    metricWatches: mergeByKey(
      suggested.metricWatches,
      selections.metricWatches,
      (item) => item.metricName,
    ),
    thresholds: [...(suggested.thresholds ?? [])],
    modules: alignModuleSwitches(suggested.modules, suggested.moduleConfigs),
    conversionRates: suggested.conversionRates.map((conversion) => ({
      ...conversion,
      provenance: conversion.provenance ?? 'AI_INFERRED',
    })),
  }

  alignMaterialCategories(data)
  alignFinishedProcessUnits(data)
  return data
}
