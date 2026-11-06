import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

const fetchDisruption = vi.hoisted(() => vi.fn())
const fetchQuality = vi.hoisted(() => vi.fn())
const fetchPrice = vi.hoisted(() => vi.fn())
vi.mock('@/api/supplyImpact', async () => {
  const actual = await vi.importActual<typeof import('@/api/supplyImpact')>('@/api/supplyImpact')
  return { ...actual, fetchDisruption, fetchQuality, fetchPrice }
})

import SupplyImpactDialog from '../SupplyImpactDialog.vue'

const quality = {
  subject: { kind: 'MATERIAL', id: 'material-1', name: '原料' }, from: null, to: null,
  sourceBatches: [], productionBatches: [], finishedBatches: [], shipments: [], customers: [], unshippedOrders: [], gaps: [],
  boxExposure: {
    scope: 'DISCOVERED_LINEAGE', inventoryBasis: 'PHYSICAL_ON_HAND_INCLUDING_RESERVED', quantityMeaning: 'PACKAGING_SNAPSHOT_EQUIVALENT',
    stock: { complete: true, totalBoxQuantity: '1', knownBoxSubtotal: '1', knownSourceCount: 1, unknownSourceCount: 0 },
    shipped: { complete: true, totalBoxQuantity: '1', knownBoxSubtotal: '1', knownSourceCount: 1, unknownSourceCount: 0 },
    stockSources: [{ sourceId: 'stock-1', finishedGoodsBatchId: 'fg-1', batchNumber: 'FG-1', sourceQuantity: '1', sourceUnit: '箱', packagingUnit: '箱', packagingBaseUnit: '箱', packagingFactor: '1', boxQuantity: '1', reservedQuantity: '0', deliveryRecordId: null, deliveryItemId: null, customerId: null, customerName: null, gaps: [] }],
    shipmentSources: [], customers: [], gaps: [],
  },
}
const disruption = {
  subject: { kind: 'MATERIAL', id: 'material-1', name: '原料' }, horizonDays: 14,
  materials: [], recipes: [], plans: [], stock: [], orders: [], gaps: [],
}

const global = {
  stubs: {
    'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' },
    'el-radio-group': { emits: ['update:modelValue', 'change'], template: '<div><button data-testid="choose-quality" @click="$emit(\'update:modelValue\', \'quality\'); $emit(\'change\', \'quality\')">quality</button><slot /></div>' },
    'el-radio-button': { template: '<span><slot /></span>' },
    'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
    'el-alert': { template: '<div><slot /></div>' },
    'el-table': { template: '<div><slot /></div>' },
    'el-table-column': { template: '<span />' },
    'el-input-number': { template: '<span />' },
    'el-date-picker': { template: '<span />' },
    'el-text': { template: '<span><slot /></span>' },
  },
}

describe('SupplyImpactDialog request context', () => {
  beforeEach(() => { fetchDisruption.mockReset(); fetchQuality.mockReset(); fetchPrice.mockReset() })

  it('renders a current quality response after the real radio v-model/change sequence', async () => {
    fetchDisruption.mockResolvedValue(disruption)
    let resolveQuality!: (value: unknown) => void
    fetchQuality.mockReturnValue(new Promise(resolve => { resolveQuality = resolve }))
    const wrapper = mount(SupplyImpactDialog, { props: { modelValue: true, factoryId: 'F006', target: { materialTypeId: 'material-1' }, targetName: '原料' }, global })
    await flushPromises()
    await wrapper.get('[data-testid="choose-quality"]').trigger('click')
    resolveQuality(quality); await flushPromises()
    expect(wrapper.find('[data-testid="impact-box-exposure"]').exists()).toBe(true)
  })

  it('drops an old quality response when target context changes while a new request is pending', async () => {
    fetchDisruption.mockResolvedValue(disruption)
    let resolveOld!: (value: unknown) => void
    let resolveNew!: (value: unknown) => void
    fetchQuality
      .mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise(resolve => { resolveNew = resolve }))
    const wrapper = mount(SupplyImpactDialog, { props: { modelValue: true, factoryId: 'F006', target: { materialTypeId: 'material-1' }, targetName: '原料' }, global })
    await flushPromises()
    await wrapper.get('[data-testid="choose-quality"]').trigger('click')
    await wrapper.setProps({ target: { materialTypeId: 'material-2' } })
    resolveOld(quality); await flushPromises()
    expect(wrapper.find('[data-testid="impact-box-exposure"]').exists()).toBe(false)
    resolveNew(quality); await flushPromises()
    expect(wrapper.find('[data-testid="impact-box-exposure"]').exists()).toBe(true)
  })

  it('drops a pending quality response across close and reopen', async () => {
    fetchDisruption.mockResolvedValue(disruption)
    let resolveQuality!: (value: unknown) => void
    fetchQuality.mockReturnValue(new Promise(resolve => { resolveQuality = resolve }))
    const wrapper = mount(SupplyImpactDialog, { props: { modelValue: true, factoryId: 'F006', target: { materialTypeId: 'material-1' }, targetName: '原料' }, global })
    await flushPromises()
    await wrapper.get('[data-testid="choose-quality"]').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await wrapper.setProps({ modelValue: true })
    resolveQuality(quality); await flushPromises()
    expect(wrapper.find('[data-testid="impact-box-exposure"]').exists()).toBe(false)
  })
})
