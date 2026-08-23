import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getMock = vi.hoisted(() => vi.fn())
const postMock = vi.hoisted(() => vi.fn())
const putMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/request', () => ({ get: getMock, post: postMock, put: putMock }))
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', hasRole: () => true }),
}))
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}))

import InvoiceList from './list.vue'

const rows = [
  { id: 'I1', purchaseOrderId: 'PO-1', purchaseOrderNumber: 'PO-1', supplierId: 'S1', supplierName: '甲', invoiceNumber: 'INV-1', amount: 10, reconcileStatus: 'PENDING' },
  { id: 'I2', purchaseOrderId: 'PO-2', purchaseOrderNumber: 'PO-2', supplierId: 'S2', supplierName: '乙', invoiceNumber: 'INV-2', amount: 20, reconcileStatus: 'MATCHED' },
]

function mountList() {
  return shallowMount(InvoiceList, {
    global: {
      stubs: {
        'el-table': { template: '<div />' },
        'el-table-column': { template: '<span />' },
      },
    },
  })
}

describe('procurement invoice list array contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMock.mockResolvedValue({ success: true, data: rows })
  })

  it('sends poId through Axios query params and paginates the returned array locally', async () => {
    const wrapper = mountList()
    await flushPromises()
    vi.clearAllMocks()
    getMock.mockResolvedValue({ success: true, data: rows })

    wrapper.vm.filterPoId = 'PO-1'
    wrapper.vm.page = 2
    wrapper.vm.pageSize = 1
    await wrapper.vm.fetchData()

    expect(getMock).toHaveBeenCalledWith('/F006/invoices/views', { params: { poId: 'PO-1' } })
    expect(wrapper.vm.total).toBe(2)
    expect(wrapper.vm.tableData).toEqual([rows[1]])
  })

  it('filters array responses by reconcile status before setting total', async () => {
    const wrapper = mountList()
    await flushPromises()
    vi.clearAllMocks()

    wrapper.vm.filterStatus = 'MATCHED'
    await wrapper.vm.fetchData()

    expect(getMock).toHaveBeenCalledWith('/F006/invoices/views', { params: {} })
    expect(wrapper.vm.total).toBe(1)
    expect(wrapper.vm.tableData).toEqual([rows[1]])
  })
})
