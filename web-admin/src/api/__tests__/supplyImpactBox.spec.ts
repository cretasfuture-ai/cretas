import { describe, expect, it } from 'vitest'
import { isSupplyImpactBoxExposure } from '../supplyImpact'

const totals = { complete: true, totalBoxQuantity: '12.50', knownBoxSubtotal: '12.50', knownSourceCount: 1, unknownSourceCount: 0 }
const source = {
  sourceId: 'fg-1', finishedGoodsBatchId: 'fg-1', batchNumber: 'FG-1', sourceQuantity: '100', sourceUnit: 'kg',
  packagingUnit: '箱', packagingBaseUnit: 'kg', packagingFactor: '10', boxQuantity: '10', reservedQuantity: '1',
  deliveryRecordId: null, deliveryItemId: null, customerId: null, customerName: null, gaps: [],
}
const exposure = {
  scope: 'DISCOVERED_LINEAGE', inventoryBasis: 'PHYSICAL_ON_HAND_INCLUDING_RESERVED', quantityMeaning: 'PACKAGING_SNAPSHOT_EQUIVALENT',
  stock: totals, shipped: { complete: false, totalBoxQuantity: null, knownBoxSubtotal: null, knownSourceCount: 0, unknownSourceCount: 1 }, stockSources: [source], shipmentSources: [], customers: [], gaps: [],
}

describe('supply impact box exposure contract', () => {
  it('accepts the additive frozen shape while keeping decimal strings', () => {
    expect(isSupplyImpactBoxExposure(exposure)).toBe(true)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, totalBoxQuantity: 12.5 } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, complete: true, totalBoxQuantity: null } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, complete: true, unknownSourceCount: 1 } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, knownBoxSubtotal: '12.51' } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, complete: false } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stockSources: [] })).toBe(false)
  })

  it('rejects extra raw fields, missing source gaps, and negative/invalid decimal facts', () => {
    expect(isSupplyImpactBoxExposure({ ...exposure, rawReplayData: '{}' })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stockSources: [{ ...source, boxQuantity: null, gaps: [] }] })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, knownBoxSubtotal: '-1' } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stock: { ...totals, knownBoxSubtotal: '1e2' } })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, gaps: [{ code: 'GAP', message: 'gap', secret: 'raw' }] })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, gaps: [{ code: '', message: 'gap' }] })).toBe(false)
    expect(isSupplyImpactBoxExposure({ ...exposure, stockSources: [source, { ...source, sourceId: 'fg-2', finishedGoodsBatchId: 'fg-1' }], stock: { ...totals, knownSourceCount: 2 } })).toBe(false)
    const shipment = { ...source, sourceId: 'edge-1', customerId: 'customer-1', customerName: '客户', deliveryRecordId: 'delivery-1', deliveryItemId: 'item-1' }
    const withCustomer = { ...exposure, shipped: totals, shipmentSources: [shipment], customers: [{ customerId: 'customer-1', customerName: '客户', totals }] }
    expect(isSupplyImpactBoxExposure(withCustomer)).toBe(true)
    expect(isSupplyImpactBoxExposure({ ...withCustomer, customers: [{ ...withCustomer.customers[0], customerId: 'other' }] })).toBe(false)
  })
})
