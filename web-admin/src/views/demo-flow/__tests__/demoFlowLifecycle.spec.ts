import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const actions=vi.hoisted(()=>({enable:vi.fn(),replace:vi.fn()}))
vi.mock('vue-router',()=>({useRouter:()=>({replace:actions.replace})}))
vi.mock('@/demo/f006NativeRuntime',()=>({enableNativeDemo:actions.enable}))
import Entry from '../index.vue'
describe('native demonstration entry',()=>{
 beforeEach(()=>{actions.enable.mockReset();actions.replace.mockReset()})
 it('opens the original procurement page without rendering another business form',async()=>{
  const wrapper=mount(Entry);await flushPromises()
  expect(actions.enable).toHaveBeenCalledTimes(1);expect(actions.replace).toHaveBeenCalledWith('/procurement/orders')
  expect(wrapper.find('form,input,[data-testid="demo-guide"]').exists()).toBe(false)
  wrapper.unmount()
 })
 it('does not navigate when the identity fence refuses activation',async()=>{
  actions.enable.mockImplementationOnce(()=>{throw Error('wrong account')})
  const wrapper=mount(Entry);await flushPromises();expect(actions.replace).not.toHaveBeenCalled();expect(wrapper.html()).toContain('wrong account');wrapper.unmount()
 })
})
