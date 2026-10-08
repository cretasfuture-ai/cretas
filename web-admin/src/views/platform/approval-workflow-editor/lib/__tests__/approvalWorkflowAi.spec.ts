import { describe, expect, it } from 'vitest'
import type { ApprovalDirectory, ApprovalWorkflowNode, ApprovalWorkflowEdge } from '@/api/approvalWorkflow'
import {
  compileApprovalWorkflowAiDraft,
  describeApprovalWorkflowAiChanges,
  type ApprovalWorkflowGraph,
} from '../approvalWorkflowAi'

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

  it('ignores spoofed derived labels and recomputes them from real directory values', () => {
    const { nodes, edges, spec } = renameFixture('ANY')
    spec.nodes[1].config.approverRoleLabels = ['伪造角色']
    spec.nodes[1].config.approverUserLabels = ['伪造审批人']
    const draft = compileApprovalWorkflowAiDraft({ spec, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })
    expect(draft.nodes[1].config).toEqual(nodes[1].config)
    expect(spec.nodes[1].config.approverRoleLabels).toEqual(['伪造角色'])
  })

  it('ignores delegate and notification display fields without changing configuration', () => {
    const spec = { startNodeId: 'start', nodes: [
      { id: 'start', type: 'start', config: {} },
      { id: 'approval', type: 'approval', config: { delegateUserId: '42', delegateUserLabel: '伪造' } },
      { id: 'notify', type: 'notify', config: { notifyRoles: ['factory_super_admin'], notifyRoleLabels: ['伪造'] } },
      { id: 'end', type: 'end', config: { outcome: 'APPROVED' } },
    ], edges: [{ id: 'e1', source: 'start', target: 'approval' },
      { id: 'e2', source: 'approval', target: 'notify' }, { id: 'e3', source: 'notify', target: 'end' }] }
    const draft = compile(spec)
    expect(draft.nodes[1].config).toEqual({ delegateUserId: '42', delegateUserLabel: '六扇门管理员（liushanmen_admin）' })
    expect(draft.nodes[2].config).toEqual({ notifyRoles: ['factory_super_admin'], notifyRoleLabels: ['工厂总管理员'] })
  })

  it.each(['bypassApprovals', 'approverRoleLabel', 'unknownDisplayLabels'])('still rejects unknown configuration %s with derived labels present', key => {
    const { nodes, edges, spec } = renameFixture('ANY')
    spec.nodes[1].config = { ...spec.nodes[1].config, [key]: true }
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

  it.each(['', ' ', null])('accepts empty condition metadata without changing existing edges: %s', condition => {
    const { nodes, edges, spec } = renameFixture('ANY')
    edges[0].condition = '#amount > 100'
    const echoedSpec = { ...spec, edges: edges.map(edge => ({ ...edge, condition })) }
    const draft = compileApprovalWorkflowAiDraft({ spec: echoedSpec, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })
    expect(draft.nodes[1].label).toBe('质量经理验收')
    expect(draft.edges).toEqual(edges)
  })

  it.each(['#amount > 0', '#bypass == true', 0])('rejects AI expression injection: %s', condition => {
    const { nodes, edges, spec } = renameFixture('ANY')
    const injected = { ...spec, edges: edges.map(edge => ({ ...edge, condition })) }
    expect(() => compileApprovalWorkflowAiDraft({ spec: injected, currentName: '质检放行', currentNodes: nodes,
      currentEdges: edges, decisionType: 'QUALITY_RELEASE', directory })).toThrow('AI 不能写入表达式')
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

/**
 * 965-3: 「应用配置」之前给用户看的逐项改动。判据: 每一处真实差异都成一行 (一条不藏),
 * 没差异的不成行 (阴性对照), 不认识的配置项也照样列出。
 */
describe('describeApprovalWorkflowAiChanges (应用前逐项改动)', () => {
  const before: ApprovalWorkflowGraph = {
    name: '质检放行审批',
    nodes: [
      { id: 'start', type: 'start', label: '开始', config: {} },
      {
        id: 'node1',
        type: 'approval',
        label: '质量经理审核',
        config: { approverRoles: ['quality_manager'], approverRoleLabels: ['质量经理'], timeoutMinutes: 60 },
      },
      { id: 'notify', type: 'notify', label: '结果通知', config: { channels: ['wechat'] } },
      { id: 'end', type: 'end', label: '结束', config: { outcome: 'APPROVED' } },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'node1', priority: 0 },
      { id: 'e2', source: 'node1', target: 'notify', priority: 0 },
      { id: 'e3', source: 'notify', target: 'end', priority: 0 },
    ],
  }

  it('阴性对照: 与当前草稿完全相同 -> 0 行 (位置不同不算改动)', () => {
    const same: ApprovalWorkflowGraph = JSON.parse(JSON.stringify(before))
    same.nodes.forEach((node, i) => { node.position = { x: 999, y: i } })
    expect(describeApprovalWorkflowAiChanges(before, same)).toEqual([])
  })

  it('只改节点名: 恰好 1 行, 中文前后对照', () => {
    const after: ApprovalWorkflowGraph = JSON.parse(JSON.stringify(before))
    after.nodes[1].label = '质量经理验收'
    expect(describeApprovalWorkflowAiChanges(before, after)).toEqual([
      { kind: 'change', text: '节点「质量经理审核」改名为「质量经理验收」' },
    ])
  })

  it('一条不藏: 名称 / 增删节点 / 每个配置项 / 未知配置项 / 增删连线 / 条件 / 优先级各成一行', () => {
    const after: ApprovalWorkflowGraph = {
      name: '质检放行审批（新）',
      nodes: [
        { id: 'start', type: 'start', label: '开始', config: {} },
        {
          id: 'node1',
          type: 'approval',
          label: '质量经理审核',
          config: {
            approverRoles: ['factory_super_admin'],
            approverRoleLabels: ['工厂总管理员'],
            timeoutMinutes: 120,
            mysteryKey: 'x',
          },
        },
        {
          id: 'node2',
          type: 'approval',
          label: '厂长复核',
          config: { requiredApprovers: 2 },
        },
        { id: 'end', type: 'end', label: '结束', config: { outcome: 'APPROVED' } },
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'node1', priority: 5 },
        { id: 'e4', source: 'node1', target: 'node2', label: '金额大于 50000 元', condition: '#amount > 50000' },
        { id: 'e5', source: 'node2', target: 'end', label: 'DEFAULT' },
      ],
    }
    expect(describeApprovalWorkflowAiChanges(before, after)).toEqual([
      { kind: 'change', text: '审批名称：质检放行审批 → 质检放行审批（新）' },
      { kind: 'change', text: '「质量经理审核」审批角色：质量经理 → 工厂总管理员' },
      { kind: 'change', text: '「质量经理审核」审批时限（分钟）：60 → 120' },
      { kind: 'change', text: '「质量经理审核」mysteryKey：暂无 → x' },
      { kind: 'add', text: '新增审批节点「厂长复核」' },
      { kind: 'add', text: '「厂长复核」审批人数：2' },
      { kind: 'remove', text: '删除结果通知节点「结果通知」' },
      { kind: 'change', text: '连线 「开始」→「质量经理审核」 优先级：0 → 5' },
      { kind: 'add', text: '新增连线 「质量经理审核」→「厂长复核」，条件：金额大于 50000 元' },
      { kind: 'add', text: '新增连线 「厂长复核」→「结束」，条件：默认分支' },
      { kind: 'remove', text: '删除连线 「质量经理审核」→「结果通知」' },
      { kind: 'remove', text: '删除连线 「结果通知」→「结束」' },
    ])
  })

  it('标签相同而条件表达式变了: 照实列出表达式, 不因标签一样藏掉', () => {
    const b: ApprovalWorkflowGraph = JSON.parse(JSON.stringify(before))
    b.edges[1] = { ...b.edges[1], label: '大额', condition: '#amount > 1000' }
    const a: ApprovalWorkflowGraph = JSON.parse(JSON.stringify(b))
    a.edges[1].condition = '#amount > 2000'
    expect(describeApprovalWorkflowAiChanges(b, a)).toEqual([
      { kind: 'change', text: '连线 「质量经理审核」→「结果通知」 条件表达式：#amount > 1000 → #amount > 2000' },
    ])
  })

  it('值翻成中文: 通知渠道 / 汇聚方式 / 结束结果', () => {
    const b: ApprovalWorkflowGraph = {
      name: 'x',
      nodes: [
        { id: 'n', type: 'notify', label: '通知', config: { channels: ['wechat'] } },
        { id: 'j', type: 'join', label: '汇聚', config: { mode: 'ALL' } },
        { id: 'end', type: 'end', label: '结束', config: { outcome: 'APPROVED' } },
      ],
      edges: [],
    }
    const a: ApprovalWorkflowGraph = JSON.parse(JSON.stringify(b))
    a.nodes[0].config = { channels: ['dingtalk', 'email'] }
    a.nodes[1].config = { mode: 'ANY' }
    a.nodes[2].config = { outcome: 'REJECTED' }
    expect(describeApprovalWorkflowAiChanges(b, a).map((row) => row.text)).toEqual([
      '「通知」通知渠道：微信 → 钉钉、邮件',
      '「汇聚」汇聚方式：全部通过 → 任一通过',
      '「结束」结束结果：通过 → 驳回',
    ])
  })
})
