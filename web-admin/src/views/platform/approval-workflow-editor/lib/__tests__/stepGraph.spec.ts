import { describe, expect, it } from 'vitest'
import {
  APPROVED_NODE_ID,
  COND_APPROVE,
  COND_REJECT,
  REJECTED_NODE_ID,
  START_NODE_ID,
  assertGeneratedGraphIsSound,
  graphToSteps,
  stepsToGraph,
  type ApprovalStep,
  type GeneratedGraph,
} from '../stepGraph'

function approvalStep(id: string, label: string, roles: string[] = ['finance_manager']): ApprovalStep {
  return { id, type: 'approval', label, approver: { roles, userIds: [] }, mode: 'or' }
}

describe('stepGraph — 线性步骤列表 ⇄ 引擎图', () => {
  it('单级审批生成 start→审批→(通过/驳回) 的完整图', () => {
    const g = stepsToGraph({ steps: [approvalStep('s1', '财务审批')] })

    expect(g.startNodeId).toBe(START_NODE_ID)
    expect(g.nodes.map(n => n.id)).toEqual([START_NODE_ID, 's1', APPROVED_NODE_ID, REJECTED_NODE_ID])

    const out = g.edges.filter(e => e.source === 's1')
    expect(out).toHaveLength(2)
    expect(out.find(e => e.condition === COND_APPROVE)?.target).toBe(APPROVED_NODE_ID)
    expect(out.find(e => e.condition === COND_REJECT)?.target).toBe(REJECTED_NODE_ID)
  })

  it('多级审批：每一级驳回都能结束流程，⛔ 不是只有最后一级接 rejected', () => {
    const g = stepsToGraph({
      steps: [approvalStep('s1', '一级'), approvalStep('s2', '二级'), approvalStep('s3', '三级')],
    })

    for (const id of ['s1', 's2', 's3']) {
      const reject = g.edges.find(e => e.source === id && e.condition === COND_REJECT)
      expect(reject, `${id} 缺少驳回出路`).toBeDefined()
      expect(reject!.target).toBe(REJECTED_NODE_ID)
    }
    expect(g.edges.find(e => e.source === 's1' && e.condition === COND_APPROVE)!.target).toBe('s2')
    expect(g.edges.find(e => e.source === 's2' && e.condition === COND_APPROVE)!.target).toBe('s3')
    expect(g.edges.find(e => e.source === 's3' && e.condition === COND_APPROVE)!.target).toBe(APPROVED_NODE_ID)
  })

  it('🔴 生成物里不存在无条件的审批出边（这正是 2026-09-01「通过走拒绝」的成因）', () => {
    const g = stepsToGraph({ steps: [approvalStep('s1', 'A'), approvalStep('s2', 'B')] })
    const approvalIds = new Set(g.nodes.filter(n => n.type === 'approval').map(n => n.id))
    const blank = g.edges.filter(e => approvalIds.has(e.source) && !e.condition?.trim())
    expect(blank).toEqual([])
  })

  it('抄送步骤不做决策，只串一条无条件边', () => {
    const cc: ApprovalStep = {
      id: 'c1', type: 'cc', label: '抄送财务', approver: { roles: [], userIds: ['1638'] },
    }
    const g = stepsToGraph({ steps: [approvalStep('s1', 'A'), cc] })
    const ccOut = g.edges.filter(e => e.source === 'c1')
    expect(ccOut).toHaveLength(1)
    expect(ccOut[0].condition).toBeUndefined()
    expect(ccOut[0].target).toBe(APPROVED_NODE_ID)
  })

  it('会签把 requiredApprovers 提到人数，或签固定为 1', () => {
    const and: ApprovalStep = {
      id: 's1', type: 'approval', label: '会签', mode: 'and',
      approver: { roles: [], userIds: ['1', '2', '3'] },
    }
    const g1 = stepsToGraph({ steps: [and] })
    expect(g1.nodes.find(n => n.id === 's1')!.config!.requiredApprovers).toBe(3)

    const g2 = stepsToGraph({ steps: [{ ...and, mode: 'or' }] })
    expect(g2.nodes.find(n => n.id === 's1')!.config!.requiredApprovers).toBe(1)
  })

  it('没有审批步骤时拒绝生成，⛔ 不产出一个不可路由的空图', () => {
    expect(() => stepsToGraph({ steps: [] })).toThrow(/至少需要一个审批步骤/)
  })

  describe('assertGeneratedGraphIsSound —— 出口自检必须真的会拦', () => {
    function soundGraph(): GeneratedGraph {
      return stepsToGraph({ steps: [approvalStep('s1', 'A')] })
    }

    it('把条件抹掉就必须抛（阳性对照：闸能红）', () => {
      const g = soundGraph()
      g.edges.find(e => e.condition === COND_APPROVE)!.condition = ''
      expect(() => assertGeneratedGraphIsSound(g)).toThrow(/无条件出边/)
    })

    it('删掉驳回出边就必须抛', () => {
      const g = soundGraph()
      g.edges = g.edges.filter(e => e.condition !== COND_REJECT)
      expect(() => assertGeneratedGraphIsSound(g)).toThrow(/恰好有 2 条出边/)
    })

    it('端点悬空必须抛（2026-09-01 丢边事故的形状）', () => {
      const g = soundGraph()
      g.edges.push({ id: 'x', source: 's1', target: 'nope' })
      expect(() => assertGeneratedGraphIsSound(g)).toThrow(/端点不存在/)
    })

    it('完好的图不抛（阴性对照：闸不是恒红）', () => {
      expect(() => assertGeneratedGraphIsSound(soundGraph())).not.toThrow()
    })
  })

  describe('graphToSteps —— 读回既有定义', () => {
    it('本模块生成的图能原样读回（往返稳定）', () => {
      const steps = [approvalStep('s1', '一级'), approvalStep('s2', '二级')]
      const g = stepsToGraph({ steps })
      const back = graphToSteps(g.nodes, g.edges, g.startNodeId)

      expect(back).not.toBeNull()
      expect(back!.steps.map(s => s.id)).toEqual(['s1', 's2'])
      expect(back!.steps.map(s => s.label)).toEqual(['一级', '二级'])
      expect(back!.steps[0].approver.roles).toEqual(['finance_manager'])
    })

    it('🔴 生产上那张坏掉的图（分支边没有条件）读不出来，返回 null 而不是猜一个顺序', () => {
      // LIUSHANMEN PURCHASE_ORDER_APPROVAL 2026-09-01 的真实形状：
      // 三条边全都没有 condition，rejected 排在 approved 之前。
      const nodes = [
        { id: 'start', type: 'start' as const },
        { id: 'admin_approval', type: 'approval' as const, label: '采购订单财务审批' },
        { id: 'approved', type: 'end' as const },
        { id: 'rejected', type: 'end' as const },
      ]
      const edges = [
        { id: 'e1', source: 'start', target: 'admin_approval', priority: 0 },
        { id: 'e2', source: 'admin_approval', target: 'rejected', priority: 0 },
        { id: 'e3', source: 'admin_approval', target: 'approved', priority: 0 },
      ]
      expect(graphToSteps(nodes, edges, 'start')).toBeNull()
    })

    it('含条件/并行节点的图读不出来，返回 null（调用方应退回画布，⛔ 不得静默丢配置）', () => {
      const nodes = [
        { id: 'start', type: 'start' as const },
        { id: 'c1', type: 'condition' as const },
      ]
      expect(graphToSteps(nodes, [{ id: 'e', source: 'start', target: 'c1' }], 'start')).toBeNull()
    })

    it('有环的图读不出来，返回 null', () => {
      const nodes = [
        { id: 'start', type: 'start' as const },
        { id: 'a', type: 'approval' as const },
        { id: 'b', type: 'approval' as const },
      ]
      const edges = [
        { id: 'e0', source: 'start', target: 'a' },
        { id: 'e1', source: 'a', target: 'b', condition: COND_APPROVE },
        { id: 'e2', source: 'b', target: 'a', condition: COND_APPROVE },
      ]
      expect(graphToSteps(nodes, edges, 'start')).toBeNull()
    })
  })
})
