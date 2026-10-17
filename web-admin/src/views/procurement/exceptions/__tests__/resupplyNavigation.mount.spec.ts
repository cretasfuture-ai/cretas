import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defineComponent, h } from 'vue'

const io = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), confirm: vi.fn(), message: vi.fn(), warehouse: true }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: io.push }) }))
vi.mock('@/api/request', () => ({ get: io.get, post: io.post }))
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'TEST', user: { id: 1 }, currentRole: 'factory_super_admin' }) }))
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canAccess: () => io.warehouse }) }))
vi.mock('element-plus', async (original) => ({ ...await original<typeof import('element-plus')>(), ElMessage: io.message, ElMessageBox: { confirm: io.confirm } }))
import Exceptions from '../list.vue'

const row = { id: 'exception-1', exceptionNumber: 'EX-1', purchaseOrderId: 'po-original', supplierId: 'supplier-1', materialName: '面粉', exceptionType: 'UNDER_RECEIVE', exceptionQty: 2, unit: '箱', status: 'RESOLVED', decision: 'REQUEST_RESUPPLY', ownerUserId: 1, returnOrderId: null }
function open(data = row) {
  io.get.mockResolvedValue({ success: true, data: { content: [data], totalElements: 1 } })
  const dialog = defineComponent({ props: { modelValue: Boolean }, setup: (props, { slots }) => () => props.modelValue ? h('section', [slots.default?.(), slots.footer?.()]) : null })
  return mount(Exceptions, { global: { plugins: [ElementPlus], stubs: { ElDialog: dialog } } })
}
async function click(wrapper: ReturnType<typeof open>, label: string) {
  const button = wrapper.findAll('button,a').find(b => b.text().trim() === label)
  expect(button, `rendered action: ${label}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
beforeEach(() => { vi.clearAllMocks(); io.warehouse = true; io.confirm.mockResolvedValue('confirm') })

describe('补货决定继续原采购单收货', () => {
  it('历史详情按原采购单精确导航，打开和导航不创建单据', async () => {
    const wrapper = open(); await flushPromises()
    await click(wrapper, '详情')
    await click(wrapper, '前往原采购单待收任务')
    expect(io.push).toHaveBeenCalledExactlyOnceWith({ path: '/warehouse/materials', query: { purchaseOrderId: 'po-original' } })
    expect(io.post).not.toHaveBeenCalled()
  })

  it('没有仓储访问权限时给出交接说明并且不暴露跳转', async () => {
    io.warehouse = false
    const wrapper = open(); await flushPromises(); await click(wrapper, '详情')
    expect(wrapper.text()).toContain('请联系仓储按原采购单办理补发收货')
    expect(wrapper.text()).not.toContain('前往原采购单待收任务')
    expect(io.push).not.toHaveBeenCalled(); expect(io.post).not.toHaveBeenCalled()
  })

  it('其他决定不显示补发入口', async () => {
    const wrapper = open({ ...row, decision: 'ACCEPT_SHORT' }); await flushPromises(); await click(wrapper, '详情')
    expect(wrapper.text()).not.toContain('前往原采购单待收任务')
    expect(io.post).not.toHaveBeenCalled()
  })

  it('成功决定只提交一次，确认后导航至响应中的原采购单', async () => {
    io.post.mockResolvedValue({ success: true, data: row })
    const wrapper = open({ ...row, status: 'PENDING', decision: '' }); await flushPromises()
    await click(wrapper, '处理')
    wrapper.findAllComponents({ name: 'ElSelect' }).at(-1)!.vm.$emit('update:modelValue', 'REQUEST_RESUPPLY')
    await flushPromises(); await click(wrapper, '确认处理'); await flushPromises()
    expect(io.post).toHaveBeenCalledExactlyOnceWith('/TEST/purchase-exceptions/exception-1/decide', { decision: 'REQUEST_RESUPPLY', notes: null })
    expect(io.confirm).toHaveBeenCalledOnce()
    expect(io.push).toHaveBeenCalledExactlyOnceWith({ path: '/warehouse/materials', query: { purchaseOrderId: 'po-original' } })
  })

  it('决定失败时保留表单，不提示成功或导航', async () => {
    io.post.mockRejectedValue({ response: { data: { message: '异常单已处理' } } })
    const wrapper = open({ ...row, status: 'PENDING', decision: '' }); await flushPromises(); await click(wrapper, '处理')
    wrapper.findAllComponents({ name: 'ElSelect' }).at(-1)!.vm.$emit('update:modelValue', 'REQUEST_RESUPPLY')
    await flushPromises(); await click(wrapper, '确认处理'); await flushPromises()
    expect(io.post).toHaveBeenCalledOnce()
    expect(io.push).not.toHaveBeenCalled(); expect(io.confirm).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确认处理')
    expect(io.message).toHaveBeenCalledWith(expect.objectContaining({ message: '异常单已处理', type: 'error' }))
  })
})
