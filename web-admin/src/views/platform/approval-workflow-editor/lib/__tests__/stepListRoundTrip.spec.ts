import { describe, expect, it } from 'vitest'
import {
  COND_APPROVE,
  COND_REJECT,
  STEP_LIST_FALLBACK_TEXT,
  explainStepListFallback,
  graphToSteps,
  stepsToGraph,
  type ApprovalStep,
} from '../stepGraph'

/**
 * 步骤列表 UI 的**行为**闸 —— 守的是「用它配出来的审批流不可能长成事故那个样子」。
 *
 * 这里不测 UI 渲染，测的是 UI 唯一会走的那条数据通路：
 *   后端定义 --graphToSteps--> 步骤列表 --(用户编辑)--> stepsToGraph --> 写回后端
 *
 * ⚠️ 与 stepGraph.spec.ts 的分工：那份测转换层自己的契约，
 *    这份测**这条通路作为整体**能不能产出 2026-09-01 那个坏图。
 */

function approvalStep(id: string, label: string, roles: string[] = ['finance_manager']): ApprovalStep {
  return { id, type: 'approval', label, approver: { roles, userIds: [] }, mode: 'or' }
}

/** LIUSHANMEN PURCHASE_ORDER_APPROVAL 修好之后的真实形状（2026-09-01 从接口读回）。 */
function liushanmenFixedGraph() {
  return {
    nodes: [
      { id: 'start', type: 'start' as const, label: 'Submit' },
      { id: 'admin_approval', type: 'approval' as const, label: '采购订单财务审批',
        config: { approverRoles: ['finance_manager'], approverUserIds: [], approvalMode: 'or' } },
      { id: 'approved', type: 'end' as const, label: 'Approved' },
      { id: 'rejected', type: 'end' as const, label: 'Rejected' },
    ],
    edges: [
      { id: 'e1', source: 'admin_approval', target: 'approved', condition: COND_APPROVE, priority: 0 },
      { id: 'e2', source: 'start', target: 'admin_approval', priority: 0 },
      { id: 'e3', source: 'admin_approval', target: 'rejected', condition: COND_REJECT, priority: 0 },
    ],
    startNodeId: 'start',
  }
}

describe('步骤列表这条通路', () => {
  it('读得回生产上那条修好的链，并认出它的审批人配置', () => {
    const g = liushanmenFixedGraph()
    const flow = graphToSteps(g.nodes, g.edges, g.startNodeId)

    expect(flow, '生产上这条链必须能用步骤列表表达，否则业务入口会退回画布').not.toBeNull()
    expect(flow!.steps).toHaveLength(1)
    expect(flow!.steps[0].label).toBe('采购订单财务审批')
    expect(flow!.steps[0].approver.roles).toEqual(['finance_manager'])
  })

  it('🔴 用户在步骤列表里做的任何编辑，都产不出「无条件分支边」', () => {
    const g = liushanmenFixedGraph()
    const flow = graphToSteps(g.nodes, g.edges, g.startNodeId)!

    // 模拟用户的全部可用动作：改名、加一级、换审批人、调顺序、加抄送
    const edited: ApprovalStep[] = [
      { ...flow.steps[0], label: '财务初审', approver: { roles: ['finance_manager'], userIds: ['1647'] } },
      approvalStep('s2', '总监复核', ['factory_super_admin']),
      { id: 'c1', type: 'cc', label: '抄送采购', approver: { roles: ['procurement_manager'], userIds: [] } },
    ]

    const out = stepsToGraph({ steps: edited })
    const approvalIds = new Set(out.nodes.filter(n => n.type === 'approval').map(n => n.id))
    const blankBranch = out.edges.filter(e => approvalIds.has(e.source) && !e.condition?.trim())

    expect(blankBranch, '审批节点出现了无条件出边 —— 这正是「点通过走拒绝」的成因').toEqual([])
  })

  it('每一级审批都有驳回出路，⛔ 不是只有最后一级', () => {
    const out = stepsToGraph({
      steps: [approvalStep('s1', '一级'), approvalStep('s2', '二级'), approvalStep('s3', '三级')],
    })
    for (const id of ['s1', 's2', 's3']) {
      const reject = out.edges.find(e => e.source === id && e.condition === COND_REJECT)
      expect(reject, `${id} 在第 ${id.slice(1)} 级驳回时无路可走`).toBeDefined()
      expect(reject!.target).toBe('rejected')
    }
  })

  it('往返稳定：读出来不改再写回去，审批语义不变', () => {
    const g = liushanmenFixedGraph()
    const flow = graphToSteps(g.nodes, g.edges, g.startNodeId)!
    const out = stepsToGraph(flow)
    const back = graphToSteps(out.nodes, out.edges, out.startNodeId)

    expect(back).not.toBeNull()
    expect(back!.steps.map(s => s.label)).toEqual(flow.steps.map(s => s.label))
    expect(back!.steps.map(s => s.approver.roles)).toEqual(flow.steps.map(s => s.approver.roles))
  })

  describe('阳性对照 —— 这些必须被拦住，否则本闸在守空气', () => {
    it('删光审批步骤必须抛，⛔ 不产出一个不可路由的空图', () => {
      expect(() => stepsToGraph({ steps: [] })).toThrow(/至少需要一个审批步骤/)
    })

    it('只剩抄送步骤（没有审批）同样必须抛', () => {
      const ccOnly: ApprovalStep[] = [
        { id: 'c1', type: 'cc', label: '抄送', approver: { roles: [], userIds: [] } },
      ]
      expect(() => stepsToGraph({ steps: ccOnly })).toThrow(/至少需要一个审批步骤/)
    })
  })

  describe('退回画布的边界 —— 表达不了就必须返回 null，⛔ 不许猜', () => {
    it('生产事故那张图（三条边全无条件）读不出来', () => {
      const broken = {
        nodes: liushanmenFixedGraph().nodes,
        edges: [
          { id: 'e1', source: 'start', target: 'admin_approval', priority: 0 },
          { id: 'e2', source: 'admin_approval', target: 'rejected', priority: 0 },
          { id: 'e3', source: 'admin_approval', target: 'approved', priority: 0 },
        ],
      }
      expect(graphToSteps(broken.nodes, broken.edges, 'start')).toBeNull()
    })

    it('含条件节点的图读不出来（业务入口应退回画布而不是丢配置）', () => {
      const nodes = [
        { id: 'start', type: 'start' as const },
        { id: 'c1', type: 'condition' as const },
      ]
      expect(graphToSteps(nodes, [{ id: 'e', source: 'start', target: 'c1' }], 'start')).toBeNull()
    })

    it('阴性对照：完好的线性图**不该**被判成表达不了', () => {
      const g = liushanmenFixedGraph()
      expect(graphToSteps(g.nodes, g.edges, g.startNodeId)).not.toBeNull()
    })

    /**
     * 前置 B：审查发现 3 明确要的负控——「悬空连线」：审批节点的通过边条件完好，
     * 只是**目标节点不存在**（比如那个节点在一次编辑里被删掉，边却留了下来）。
     * 用的是同一份 LIUSHANMEN 真实修好形状，只改一处，其余原样 —— 与 stepGraph.spec.ts
     * 里"端点悬空必须抛"那条不同：那条测的是**写**方向(assertGeneratedGraphIsSound
     * 拦一个人为构造出的坏生成物)，这条测的是**读**方向——喂一张外部产生的、
     * 边指向不存在节点的图，业务入口必须把它识别出来并说明原因，
     * ⛔ 不能静默丢弃这条连线、猜一个不完整的步骤列表。
     */
    it('🔴 悬空连线（通过边指向不存在的节点）必须被识别并报告，⛔ 不静默丢配置', () => {
      const g = liushanmenFixedGraph()
      const dangling = {
        nodes: g.nodes,
        // 只改 e1 的 target —— 其余(e2/e3)原样，与"完好的线性图"只差这一处。
        edges: g.edges.map((e) => (
          e.id === 'e1' ? { ...e, target: 'approved_but_this_node_was_deleted' } : e
        )),
      }

      expect(
        graphToSteps(dangling.nodes, dangling.edges, g.startNodeId),
        '悬空连线的图被读成了一个步骤列表 —— 它应该是 null, 不是猜一个不完整的结果',
      ).toBeNull()

      expect(
        explainStepListFallback(dangling.nodes, dangling.edges, g.startNodeId),
        '读不了必须给出具体原因, ⛔ 不能返回 null 让调用方(以及最终用户)自己猜',
      ).toBe(STEP_LIST_FALLBACK_TEXT['unknown-node'])
    })
  })
})
