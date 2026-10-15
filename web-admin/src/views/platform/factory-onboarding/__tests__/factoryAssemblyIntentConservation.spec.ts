import { describe, expect, it } from 'vitest'
import type { SuggestedData } from '@/api/factoryOnboardingApi'
import { conserveFactoryAssemblyIntent } from '../factoryAssemblyIntentConservation'

function base(): SuggestedData {
  return {
    productTypes: [
      { code: 'DUCK_NECK', name: '香辣鸭脖', unit: '袋', level1Unit: '箱', boxConversionCoefficient: 20 },
      { code: 'CHICKEN_FEET', name: '五香鸡爪', unit: '袋', level1Unit: '箱', boxConversionCoefficient: 12 },
    ],
    materialTypes: [
      { code: 'FROZEN_DUCK', name: '冷冻鸭副', unit: 'kg' },
      { code: 'MARINADE', name: '腌料', unit: 'kg' },
      { code: 'VACUUM_BAG', name: '真空袋', unit: '个' },
    ],
    conversionRates: [
      { fromCode: 'FROZEN_DUCK', toCode: 'DUCK_NECK', rate: 0.3, note: 'Agent 推演' },
    ],
    codeSegments: [
      { code: '001', label: '原料' },
      { code: '002', label: '包材' },
      { code: '003', label: '辅料' },
    ],
    workProcesses: [{
      name: '真空包装', unit: 'kg', outputUnit: '盒', outputKind: 'FINISHED_GOOD',
      auxMaterials: [{ name: '腌料' }], packMaterials: [{ name: '真空袋' }],
    }],
    productProcesses: [
      {
        productCode: 'DUCK_NECK', processNames: ['真空包装'],
        routeNodes: [{ id: 'vacuum-pack', label: '真空包装' }],
        routeEdges: [],
      },
      { productCode: 'CHICKEN_FEET', processNames: ['真空包装'] },
    ],
    alerts: [
      { alertType: 'INVENTORY_LOW', enabled: true },
      { alertType: 'INVENTORY_EXPIRING', enabled: true },
      { alertType: 'QUALITY_ANOMALY', enabled: true },
    ],
    metricWatches: [
      { metricName: 'YIELD_RATE', enabled: true, threshold: 95 },
      { metricName: 'UNIT_COST', enabled: true, threshold: 30 },
    ],
    moduleConfigs: [{ moduleId: 'equipment', moduleName: '设备', enabled: false }],
    modules: { enableEquipmentManagement: true },
  }
}

describe('factory assembly intent conservation', () => {
  it('preserves exactly the explicit three alerts and two metrics without injecting defaults', () => {
    const result = conserveFactoryAssemblyIntent(base(), { alerts: [], metricWatches: [] })

    expect(result.alerts?.map((item) => item.alertType)).toEqual([
      'INVENTORY_LOW', 'INVENTORY_EXPIRING', 'QUALITY_ANOMALY',
    ])
    expect(result.metricWatches?.map((item) => [item.metricName, item.threshold])).toEqual([
      ['YIELD_RATE', 95], ['UNIT_COST', 30],
    ])
    expect(result.thresholds).toEqual([])
  })

  it('makes the equipment switch agree with the explicit module close decision', () => {
    const result = conserveFactoryAssemblyIntent(base(), { alerts: [], metricWatches: [] })
    expect(result.modules?.enableEquipmentManagement).toBe(false)
  })

  it('resolves same-plan material roles and the final process unit from the product graph', () => {
    const result = conserveFactoryAssemblyIntent(base(), { alerts: [], metricWatches: [] })
    expect(result.materialTypes.map((item) => [item.name, item.category])).toEqual([
      ['冷冻鸭副', '原料'], ['腌料', '辅料'], ['真空袋', '包材'],
    ])
    expect(result.workProcesses?.[0].outputUnit).toBe('袋')
    expect(result.productTypes.map((item) => [item.unit, item.level1Unit, item.boxConversionCoefficient]))
      .toEqual([['袋', '箱', 20], ['袋', '箱', 12]])
  })

  it('keeps inferred conversion visible without leaking interview facts into apply data', () => {
    const result = conserveFactoryAssemblyIntent(base(), {
      alerts: [], metricWatches: [],
    })
    expect(result.conversionRates[0].provenance).toBe('AI_INFERRED')
    expect(result).not.toHaveProperty('customerSupplied')
  })

  it('deep-copies the explicit route contract so editing cannot mutate the generated source', () => {
    const source = base()
    const result = conserveFactoryAssemblyIntent(source, { alerts: [], metricWatches: [] })

    result.productProcesses![0].routeNodes![0].label = '气调包装'

    expect(source.productProcesses?.[0].routeNodes?.[0].label).toBe('真空包装')
    expect(result.productProcesses?.[0].routeNodes?.[0].label).toBe('气调包装')
  })
})
