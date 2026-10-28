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

  it('loads PO line status and uploads only lines the clerk filled (S5-05/07)', async () => {
    const wrapper = mountList()
    await flushPromises()
    vi.clearAllMocks()
    const status = {
      purchaseOrderId: 'PO-1', purchaseOrderNumber: 'CG-1', fullyMatched: false,
      lines: [
        { purchaseOrderItemId: 11, materialTypeId: 'MAT-A', materialName: '鸡腿', unit: 'kg', orderedQuantity: 100,
          receivedQuantity: 100, returnedQuantity: 10, invoiceableQuantity: 90, invoicedQuantity: 50, matchedQuantity: 0,
          remainingQuantity: 40, expectedUnitAmount: 11.3, status: 'PARTIAL', blocker: null,
          receipts: [{ receiveItemId: 7, receiveRecordId: 'R1', receiveNumber: 'SH-1', receivedQuantity: 60, invoicedQuantity: 50, remainingQuantity: 10 }] },
        { purchaseOrderItemId: 12, materialTypeId: 'MAT-B', materialName: '鸡翅', unit: 'kg', orderedQuantity: 50,
          receivedQuantity: 0, returnedQuantity: 0, invoiceableQuantity: 0, invoicedQuantity: 0, matchedQuantity: 0,
          remainingQuantity: 0, expectedUnitAmount: 20, status: 'NOT_RECEIVED', blocker: null, receipts: [] },
      ],
    }
    getMock.mockResolvedValue({ success: true, data: status })
    wrapper.vm.uploadForm.purchaseOrderId = 'PO-1'
    await wrapper.vm.loadUploadLines()

    expect(getMock).toHaveBeenCalledWith('/F006/invoices/match-status', { params: { poId: 'PO-1' } })
    expect(wrapper.vm.uploadDrafts).toHaveLength(2)
    const [a] = wrapper.vm.uploadDrafts
    // 一行都没填 → 不能只登记表头
    expect(wrapper.vm.uploadLinesInvalid).toBe(true)
    // 防呆 Rule 1: 采购单行还剩 40，选了只剩 10 的收货行后上限变成 10
    expect(wrapper.vm.lineMax(a)).toBe(40)
    a.receiveItemId = 7
    expect(wrapper.vm.lineMax(a)).toBe(10)
    a.quantity = 15
    a.amount = 169.5
    expect(wrapper.vm.uploadLinesInvalid).toBe(true)
    a.quantity = 10
    a.amount = 113
    expect(wrapper.vm.uploadLinesInvalid).toBe(false)

    wrapper.vm.uploadFormRef = { validate: (cb: (valid: boolean) => unknown) => cb(true), resetFields: () => {} }
    Object.assign(wrapper.vm.uploadForm, { supplierId: 'S1', invoiceNumber: 'FP-9', amount: 113 })
    postMock.mockResolvedValue({ success: true, data: { reconcileStatus: 'MATCHED' } })
    getMock.mockResolvedValue({ success: true, data: [] })
    await wrapper.vm.doUpload()

    expect(postMock).toHaveBeenCalledWith('/F006/invoices/upload', expect.objectContaining({
      purchaseOrderId: 'PO-1',
      amount: 113,
      lines: [{ purchaseOrderItemId: 11, receiveItemId: 7, materialTypeId: 'MAT-A', quantity: 10, amount: 113 }],
    }))
  })

  it('counts line differences so a wrong-material invoice cannot be marked matched (S5-06)', async () => {
    const wrapper = mountList()
    await flushPromises()
    const row = {
      ...rows[0],
      lines: [
        { id: 1, purchaseOrderItemId: 11, receiveItemId: null, materialTypeId: 'MAT-B', quantity: 100, unit: 'kg',
          unitPrice: null, amount: 1130, expectedAmount: 1130, matchStatus: 'MATERIAL_MISMATCH', matchNote: '发票物料 MAT-B ≠ 采购单行物料 MAT-A' },
        { id: 2, purchaseOrderItemId: 12, receiveItemId: null, materialTypeId: null, quantity: 1, unit: 'kg',
          unitPrice: null, amount: 1, expectedAmount: null, matchStatus: 'PRICE_UNKNOWN', matchNote: null },
      ],
    }
    expect(wrapper.vm.lineDifferenceCount(row)).toBe(1)
    expect(wrapper.vm.lineDifferenceCount(rows[0])).toBe(0)
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
