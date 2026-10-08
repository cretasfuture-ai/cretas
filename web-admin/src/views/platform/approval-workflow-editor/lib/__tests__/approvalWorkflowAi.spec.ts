import { describe, expect, it } from 'vitest'
import type { ApprovalDirectory, ApprovalWorkflowNode, ApprovalWorkflowEdge } from '@/api/approvalWorkflow'
import { compileApprovalWorkflowAiDraft } from '../approvalWorkflowAi'

const directory: ApprovalDirectory = {
  roles: [
    { name: 'procurement_manager', displayName: '采购主管' },
    { name: 'factory_super_admin', displayName: '工厂总管理员' },
  ],
  users: [
    { id: 42, username: 'liushanmen_admin', fullName: '六扇门管理员', isActive: true },
  ],
}

function compile(spec: unknown) {
  return compileApprovalWorkflowAiDraft({
    spec,
    currentName: '采购订单审批',
    currentNodes: [],
    currentEdges: [],
    decisionType: 'PURCHASE_ORDER_APPROVAL',
    directory,
  })
}

describe('approval workflow AI draft compiler', () => {
  function renameFixture(mode: unknown) {
    const nodes: ApprovalWorkflowNode[] = [
      { id: 'start', type: 'start', label: '开始', position: { x: 0, y: 0 }, config: {} },
      { id: 'quality', type: 'approval', label: '质量经理审核', position: { x: 0, y: 100 },
        config: { mode, approverRoles: ['factory_super_admin'], approverRoleLabels: ['工厂总管理员'],
          approverUserIds: ['42'], approverUserLabels: ['六扇门管理员（liushanmen_admin）'], requiredApprovers: 1 } },
      { id: 'end', type: 'end', label: '结束', position: { x: 0, y: 200 }, config: { outcome: 'APPROVED' } },
    ]
    const edges: ApprovalWorkflowEdge[] = [
      { id: 'e1', source: 'start', target: 'quality', priority: 0 },
      { id: 'e2', source: 'quality', target: 'end', priority: 0 },
    ]
    const spec = { startNodeId: 'start', nodes: nodes.map(n => {
      const config = { ...n.config }
      delete config.approverRoleLabels
      delete config.approverUserLabels
      return { ...n, config, label: n.id === 'quality' ? '质量经理验收' : n.label }
    }), edges }
    return { nodes, edges, spec }
  }

  it.each(['ANY', 'ALL'])('renames an existing %s approval without changing config, nodes or edges', mode => {
    const { nodes, edges, spec } = renameFixture(mode)
    const before = structuredClone(nodes)
    const draft = compileApprovalWorkflowAiDraft({ spec, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })
    expect(draft.nodes).toEqual(nodes.map(n => ({ ...n, label: n.id === 'quality' ? '质量经理验收' : n.label })))
    expect(draft.edges).toEqual(edges)
    expect(draft.nodes[1].config).toEqual(nodes[1].config)
    expect(nodes).toEqual(before)
  })

  it.each(['INVALID', 'N_OF_M', 1, null])('rejects illegal approval mode %s', mode => {
    const { nodes, edges, spec } = renameFixture(mode)
    expect(() => compileApprovalWorkflowAiDraft({ spec, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })).toThrow()
  })

  it('still rejects unknown config keys while allowing a paired legal mode', () => {
    const { nodes, edges, spec } = renameFixture('ANY')
    spec.nodes[1].config = { ...spec.nodes[1].config, bypassApprovals: true }
    expect(() => compileApprovalWorkflowAiDraft({ spec, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })).toThrow('不支持的配置')
  })

  it.each(['ALL', 'ANY', 'N_OF_M'])('keeps the existing join mode %s contract', mode => {
    const spec = { startNodeId: 'start', nodes: [
      { id: 'start', type: 'start', config: {} },
      { id: 'join', type: 'join', config: { mode, n: 2 } },
      { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
    ], edges: [{ id: 'e1', source: 'start', target: 'join' }, { id: 'e2', source: 'join', target: 'end' }] }
    expect(compile(spec).nodes[1].config).toEqual({ mode, n: 2 })
    spec.nodes[1].config.mode = 'INVALID'
    expect(() => compile(spec)).toThrow('汇聚模式无效')
  })

  it('maps only real factory roles and users into a reversible local draft', () => {
    const draft = compile({
      name: '采购订单审批',
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', label: '开始', config: {} },
        {
          id: 'approval_admin',
          type: 'approval',
          label: '管理员审批',
          config: {
            approverRoles: ['factory_super_admin'],
            approverUserIds: ['42'],
            requiredApprovers: 1,
            timeoutMinutes: 720,
          },
        },
        { id: 'end', type: 'end', label: '审批通过', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval_admin', priority: 0 },
        { id: 'edge_2', source: 'approval_admin', target: 'end', priority: 0 },
      ],
    })

    expect(draft.nodes[1].config).toMatchObject({
      approverRoles: ['factory_super_admin'],
      approverRoleLabels: ['工厂总管理员'],
      approverUserIds: ['42'],
      approverUserLabels: ['六扇门管理员（liushanmen_admin）'],
      timeoutMinutes: 720,
    })
  })

  it('rejects an approver that is not in the current factory directory', () => {
    expect(() => compile({
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', config: {} },
        {
          id: 'approval',
          type: 'approval',
          config: { approverUserIds: ['999'] },
        },
        { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval' },
        { id: 'edge_2', source: 'approval', target: 'end' },
      ],
    })).toThrow('审批人不在当前工厂可选目录中')
  })

  it('rejects duplicate, self or cyclic connections before touching the draft', () => {
    expect(() => compile({
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', config: {} },
        { id: 'approval_a', type: 'approval', config: {} },
        { id: 'approval_b', type: 'approval', config: {} },
        { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'edge_1', source: 'start', target: 'approval_a' },
        { id: 'edge_2', source: 'approval_a', target: 'approval_b' },
        { id: 'edge_3', source: 'approval_b', target: 'approval_a' },
        { id: 'edge_4', source: 'approval_b', target: 'end' },
      ],
    })).toThrow('审批画布不能形成循环')
  })
})
