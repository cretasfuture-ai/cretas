import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'

let releaseRunningCount: ((count: number) => void) | undefined
const deferredRunningCount = new Promise<number>((resolve) => { releaseRunningCount = resolve })

const workflow = {
  id: 'awf-f006-po-default', factoryId: 'F006', decisionType: 'PURCHASE_ORDER_APPROVAL',
  name: '采购订单默认审批', version: 1, publishStatus: 'published', enabled: true,
  priority: 0, startNodeId: 'start_1',
  nodesJson: JSON.stringify([
    { id: 'start_1', type: 'start', label: '开始', position: { x: 50, y: 150 }, config: {} },
    { id: 'cond_1', type: 'condition', label: '金额判断', position: { x: 250, y: 150 }, config: {} },
    { id: 'approval_finance', type: 'approval', label: '财务审批', position: { x: 500, y: 80 }, config: {} },
    { id: 'end_approved', type: 'end', label: '通过', position: { x: 750, y: 80 }, config: {} },
    { id: 'end_auto', type: 'end', label: '自动通过', position: { x: 750, y: 220 }, config: {} },
  ]),
  edgesJson: JSON.stringify([
    { id: 'e_start_cond', source: 'start_1', target: 'cond_1', label: null, priority: 0, condition: null },
    { id: 'e_cond_finance', source: 'cond_1', target: 'approval_finance', label: '>30000', priority: 0, condition: '#amount > 30000' },
    { id: 'e_cond_auto', source: 'cond_1', target: 'end_auto', label: 'DEFAULT', priority: 99, condition: null },
    { id: 'e_finance_approved', source: 'approval_finance', target: 'end_approved', label: '通过', priority: 0, condition: null },
  ]),
}

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }))
vi.mock('@/api/approvalWorkflow', () => {
  const ok = (data: unknown) => Promise.resolve({ success: true, data })
  return {
    getDecisionTypesMetadata: () => ok([]), getDecisionTypes: () => ok([]),
    getApprovalDirectory: () => Promise.resolve({ roles: [], users: [] }),
    getWorkflowsByDecisionType: () => ok([workflow]), getWorkflowById: () => ok(workflow),
    getRunningInstanceCount: () => deferredRunningCount.then(data => ({ success: true, data })),
  }
})

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} } as typeof ResizeObserver
}
if (!SVGElement.prototype.getBBox) {
  SVGElement.prototype.getBBox = () => ({ x: 0, y: 0, width: 100, height: 40, top: 0, right: 100, bottom: 40, left: 0 }) as DOMRect
}

describe('approval canvas real editor edge persistence', () => {
  it('mounts production editor and renders the API five-node/four-edge graph', async () => {
    const { default: Editor } = await import('../../index.vue')
    const wrapper = mount(Editor, {
      props: { embedded: true, initialDecisionType: 'PURCHASE_ORDER_APPROVAL', initialWorkflowId: workflow.id, lockDecisionType: true },
      global: { stubs: { StepListEditor: true, ApprovalWorkflowAIComposer: true, WorkflowSimulator: true, ConditionRulesPanel: true } },
      attachTo: document.body,
    })
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 30))
    await nextTick()
    await nextTick()
    expect(wrapper.findAll('.vue-flow__node')).toHaveLength(5)
    expect(wrapper.findAll('.vue-flow__edge')).toHaveLength(4)
    expect(wrapper.text()).toContain('4 条连线')
    expect((wrapper.vm as unknown as { canSave: boolean }).canSave).toBe(false)
    ;(wrapper.vm as unknown as { onConnect: (connection: unknown) => void }).onConnect({
      source: 'start_1', target: 'approval_finance', sourceHandle: undefined,
    })
    expect(wrapper.findAll('.vue-flow__edge')).toHaveLength(4)

    releaseRunningCount?.(0)
    await new Promise(resolve => setTimeout(resolve, 30))
    await nextTick()
    expect(wrapper.findAll('.vue-flow__edge')).toHaveLength(4)
    expect((wrapper.vm as unknown as { canSave: boolean }).canSave).toBe(true)
  }, 15000)
})
