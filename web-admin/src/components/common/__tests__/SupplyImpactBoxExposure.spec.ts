import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SupplyImpactBoxExposure from '../SupplyImpactBoxExposure.vue'

const source = {
  sourceId: 'fg-1', finishedGoodsBatchId: 'fg-1', batchNumber: 'FG-001', sourceQuantity: '123456789012345678.123456', sourceUnit: 'kg',
  packagingUnit: '箱', packagingBaseUnit: 'kg', packagingFactor: '12.5', boxQuantity: '9876543210.123456', reservedQuantity: '2',
  deliveryRecordId: null, deliveryItemId: null, customerId: null, customerName: null, gaps: [],
}
const shippedSource = {
  ...source, sourceId: 'edge-1', finishedGoodsBatchId: 'fg-1', sourceQuantity: '0.125', sourceUnit: 'kg',
  packagingFactor: '0.5', boxQuantity: '0.25', deliveryRecordId: 'delivery-1', deliveryItemId: 'item-1',
  customerId: 'customer-1', customerName: 'QA客户',
}
const completeExposure = {
  scope: 'DISCOVERED_LINEAGE', inventoryBasis: 'PHYSICAL_ON_HAND_INCLUDING_RESERVED', quantityMeaning: 'PACKAGING_SNAPSHOT_EQUIVALENT',
  stock: { complete: true, totalBoxQuantity: '9876543210.123456', knownBoxSubtotal: '9876543210.123456', knownSourceCount: 1, unknownSourceCount: 0 },
  shipped: { complete: true, totalBoxQuantity: '0.25', knownBoxSubtotal: '0.25', knownSourceCount: 1, unknownSourceCount: 0 },
  stockSources: [source], shipmentSources: [shippedSource],
  customers: [{ customerId: 'customer-1', customerName: 'QA客户', totals: { complete: true, totalBoxQuantity: '0.25', knownBoxSubtotal: '0.25', knownSourceCount: 1, unknownSourceCount: 0 } }],
  gaps: [],
}

describe('SupplyImpactBoxExposure', () => {
  it('renders exact server decimals, source snapshots, and customer attribution without calculating', () => {
    const wrapper = mount(SupplyImpactBoxExposure, { props: { exposure: completeExposure } })
    expect(wrapper.get('[data-testid="box-stock-total"]').text()).toBe('9876543210.123456')
    expect(wrapper.get('[data-testid="box-shipped-total"]').text()).toBe('0.25')
    expect(wrapper.get('[data-testid="box-known-subtotal"]').text()).toBe('9876543210.123456')
    expect(wrapper.get('[data-testid="box-stock-sources"]').text()).toContain('12.5')
    expect(wrapper.get('[data-testid="box-shipment-sources"]').text()).toContain('0.25')
    expect(wrapper.get('[data-testid="box-shipment-sources"]').text()).toContain('箱 / kg × 0.5')
    expect(wrapper.get('[data-testid="box-customers"]').text()).toContain('QA客户')
    expect(wrapper.text()).toContain('不是现场封箱清点')
  })

  it('keeps partial and all-unknown totals separate from known subtotals', () => {
    const partial = {
      ...completeExposure,
      stock: { complete: false, totalBoxQuantity: null, knownBoxSubtotal: '1.5', knownSourceCount: 1, unknownSourceCount: 1 },
      stockSources: [source, { ...source, sourceId: 'fg-unknown', finishedGoodsBatchId: 'fg-unknown', boxQuantity: null, gaps: ['包装快照缺失'] }],
      gaps: [{ code: 'MISSING_SNAPSHOT', message: '一个来源缺少不可变包装快照' }],
    }
    const wrapper = mount(SupplyImpactBoxExposure, { props: { exposure: partial } })
    expect(wrapper.get('[data-testid="box-stock-total"]').text()).toBe('待核对')
    expect(wrapper.get('[data-testid="box-known-subtotal"]').text()).toBe('1.5')
    expect(wrapper.get('[data-testid="box-exposure-gaps"]').text()).toContain('缺少不可变包装快照')

    const allUnknown = {
      ...completeExposure,
      stock: { complete: false, totalBoxQuantity: null, knownBoxSubtotal: null, knownSourceCount: 0, unknownSourceCount: 1 },
      stockSources: [{ ...source, boxQuantity: null, gaps: ['数量无法核验'] }],
    }
    const unknownWrapper = mount(SupplyImpactBoxExposure, { props: { exposure: allUnknown } })
    expect(unknownWrapper.get('[data-testid="box-stock-total"]').text()).toBe('待核对')
    expect(unknownWrapper.get('[data-testid="box-known-subtotal"]').text()).toBe('—')
    expect(unknownWrapper.text()).not.toContain('= 0')

    const partialCustomer = mount(SupplyImpactBoxExposure, { props: { exposure: {
      ...completeExposure,
      shipped: { ...completeExposure.shipped, complete: false, totalBoxQuantity: null, unknownSourceCount: 1 },
      customers: [{ ...completeExposure.customers[0], totals: {
        ...completeExposure.customers[0]!.totals, complete: false, totalBoxQuantity: null, unknownSourceCount: 1,
      } }],
    } } })
    const customerCells = partialCustomer.get('[data-testid="box-customers"] tbody tr').findAll('td')
    expect(customerCells.map(cell => cell.text())).toEqual(['QA客户', '待核对', '0.25', '1'])
  })

  it('does not turn legacy, contradictory, or malformed exposure into zero', () => {
    const missing = mount(SupplyImpactBoxExposure, { props: { exposure: undefined } })
    expect(missing.text()).toContain('暂待核对')
    expect(missing.text()).not.toContain('0')

    const contradictory = mount(SupplyImpactBoxExposure, { props: {
      exposure: { ...completeExposure, stock: { ...completeExposure.stock, complete: true, totalBoxQuantity: null } },
    } })
    expect(contradictory.text()).toContain('无法核验')
    expect(contradictory.text()).not.toContain('9876543210.123456')
  })
})
