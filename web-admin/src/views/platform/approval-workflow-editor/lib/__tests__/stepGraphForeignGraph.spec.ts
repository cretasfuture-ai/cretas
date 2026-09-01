import { describe, expect, it } from 'vitest'
import type { ApprovalWorkflowEdge, ApprovalWorkflowNode } from '@/api/approvalWorkflow'
import {
  COND_APPROVE,
  COND_REJECT,
  explainStepListFallback,
  graphToSteps,
  stepsToGraph,
} from '../stepGraph'

/**
 * 「**外来图**」用例 —— 即**不是本模块生成的**图。
 *
 * <h3>为什么必须单独有这一类</h3>
 * 既有的 `stepListRoundTrip.spec.ts` 是 `stepsToGraph → graphToSteps` 往返，
 * **两端都是自己生成的**。那种测试证明的是「我和我自己自洽」，
 * ⛔ 证明不了「我读得懂世界上已经存在的图」。
 *
 * 2026-09-01 的缺陷正好落在这个盲区里：`graphToSteps` 的终止判据写死了
 * `'approved'`/`'rejected'` 两个 id，而它们是 `stepsToGraph` 自己产出的。
 * 于是**任何历史/画布手绘的图都读不进来**，而要产出「本模块形状的图」
 * 又必须先进步骤列表 ⇒ **自举死锁**。
 *
 * 实测（F006 测试环境全量 14 条审批流）：能进步骤列表的 **0 条**；
 * 其中 **9 条**完全线性、无分支，仅因终端 id 不叫 `approved`/`rejected` 被拒。
 *
 * ⚠️ 本文件的每个用例都**刻意不使用** `stepsToGraph` 造数据 —— 一旦用它造，
 * 就又变成自洽测试，这个缺陷会再次隐形。
 */

/** 真实取自 F006 测试环境 `INVENTORY_TRANSFER_APPROVAL` / 库存调拨审批（财务复核单级）。 */
function realPersistedLinearGraph(): {
  nodes: ApprovalWorkflowNode[]
  edges: ApprovalWorkflowEdge[]
  startNodeId: string
} {
  return {
    startNodeId: 'start_1',
    nodes: [
      { id: 'start_1', type: 'start', label: '提交', config: {}, position: { x: 0, y: 0 } },
      {
        id: 'approval_finance',
        type: 'approval',
        label: '财务经理复核',
        config: { approverRoles: ['finance_manager'], timeoutMinutes: 1440 },
        position: { x: 1, y: 0 },
      },
      { id: 'end_approved', type: 'end', label: '通过', config: { outcome: 'APPROVED' }, position: { x: 2, y: 0 } },
      { id: 'end_rejected', type: 'end', label: '驳回', config: { outcome: 'REJECTED' }, position: { x: 3, y: 0 } },
    ] as ApprovalWorkflowNode[],
    edges: [
      { id: 'e1', source: 'start_1', target: 'approval_finance', condition: null, label: null, priority: 0 },
      { id: 'e2', source: 'approval_finance', target: 'end_approved', condition: COND_APPROVE, label: '通过', priority: 0 },
      { id: 'e3', source: 'approval_finance', target: 'end_rejected', condition: COND_REJECT, label: '驳回', priority: 0 },
    ] as ApprovalWorkflowEdge[],
  }
}

describe('graphToSteps 必须读得懂外来图（非本模块生成）', () => {
  it('🔴 回归：终端节点 id 不是 approved/rejected 的线性图，照样要读得出来', () => {
    const g = realPersistedLinearGraph()

    const flow = graphToSteps(g.nodes, g.edges, g.startNodeId)

    expect(
      flow,
      '真实持久化的线性图读不出来 —— 步骤列表只认自己生成的图, 自举死锁又回来了',
    ).not.toBeNull()
    expect(flow!.steps.map(s => s.id)).toEqual(['approval_finance'])
    expect(flow!.steps[0].label).toBe('财务经理复核')
    expect(flow!.steps[0].approver.roles).toEqual(['finance_manager'])
    expect(explainStepListFallback(g.nodes, g.edges, g.startNodeId)).toBeNull()
  })

  it('判别变量是终端 id 而不是图的复杂度：只改 id，读数必须不变', () => {
    const g = realPersistedLinearGraph()
    // 只把两个终端 id 换成本模块自己会产出的那两个，其余完全不动。
    const rename = (id: string) =>
      id === 'end_approved' ? 'approved' : id === 'end_rejected' ? 'rejected' : id
    const nodes = g.nodes.map(n => ({ ...n, id: rename(n.id) })) as ApprovalWorkflowNode[]
    const edges = g.edges.map(e => ({ ...e, target: rename(e.target) })) as ApprovalWorkflowEdge[]

    const foreign = graphToSteps(g.nodes, g.edges, g.startNodeId)
    const native = graphToSteps(nodes, edges, g.startNodeId)

    expect(
      JSON.stringify(foreign),
      '换个终端 id 就读出不同结果 —— 说明判定仍然挂在 id 上, 而不是图的性质上',
    ).toBe(JSON.stringify(native))
  })

  it('阳性对照：真有分支的外来图仍然必须退回，且原因说的是分支', () => {
    const g = realPersistedLinearGraph()
    const nodes = [
      ...g.nodes,
      { id: 'cond_1', type: 'condition', label: '金额判断', config: {}, position: { x: 9, y: 9 } },
    ] as ApprovalWorkflowNode[]

    expect(graphToSteps(nodes, g.edges, g.startNodeId)).toBeNull()
    expect(explainStepListFallback(nodes, g.edges, g.startNodeId)).toContain('条件分支')
  })

  it('⛔ 退回原因必须由实际判定算出来，不许对所有情况都说「有分支」', () => {
    const g = realPersistedLinearGraph()
    // 无分支，但审批节点缺「通过」出边 —— 读不了，可原因不是分支。
    const edges = g.edges.filter(e => e.condition !== COND_APPROVE)

    const reason = explainStepListFallback(g.nodes, edges, g.startNodeId)

    expect(reason, '这张图读不了, 必须给出原因').not.toBeNull()
    expect(reason).toContain('通过')
    expect(
      reason,
      '把「缺通过出边」说成「有分支」是假话 —— 用户会去找一个不存在的分支',
    ).not.toContain('条件分支')
  })

  it('自洽性不受影响：本模块生成的图仍然读得回来', () => {
    const generated = stepsToGraph({
      steps: [
        { id: 's1', type: 'approval', label: '一级审批', approver: { roles: ['finance_manager'], userIds: [] } },
      ],
    })

    const flow = graphToSteps(generated.nodes, generated.edges, generated.startNodeId)

    expect(flow).not.toBeNull()
    expect(flow!.steps.map(s => s.id)).toEqual(['s1'])
  })
})
