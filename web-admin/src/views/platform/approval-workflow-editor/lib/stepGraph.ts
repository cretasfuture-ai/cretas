/**
 * 线性步骤列表 ⇄ 引擎图（nodes + edges + SpEL）的双向转换。
 *
 * ## 为什么需要这一层
 *
 * 后端引擎 `WorkflowEngineServiceImpl` 是**沿 edges 走图**的
 * （`walkToFirstHumanNode` / `evaluateOutgoingEdges`，边上带 SpEL condition）。
 * 而审批流在业务上 90% 是线性的，用自由画布表达它导致了 2026-09-01 的生产事故：
 * 画布加载丢边 → 保存把 `edges_json` 写成 `"[]"` → 该审批链再也路由不到节点
 * （新单 422、在途单 409），且产品内没有恢复入口。
 *
 * ⇒ 编辑层改用**线性步骤列表**：顺序由数组下标决定，**不存在"连线"这个概念**，
 *   因此结构上不可能丢线。引擎要的图由本模块**确定性地生成**。
 *
 * ## 这一层必须自己扛住的两条正确性（引擎不会替你兜底）
 *
 * 1. **审批节点的出边必须带 condition。** `evaluateOutgoingEdges` 里
 *    「空 condition = 永真，取第一条命中的」，且**与用户点的是通过还是驳回无关**
 *    —— 少了条件会让「通过」走到 Rejected（2026-09-01 生产实证）。
 *    ⇒ 本模块生成的每条审批出边都带显式条件，并由 {@link assertGeneratedGraphIsSound} 兜底断言。
 * 2. **每个审批节点都要有 REJECT 出路。** 在第 2 步驳回也必须能结束流程，
 *    ⛔ 不能只给最后一步接 rejected。
 *
 * @see docs/audits/liushanmen/2026-09-01-web-admin-procurement-module.md 缺陷 27 / 29
 */
import type {
  ApprovalWorkflowEdge,
  ApprovalWorkflowNode,
} from '@/api/approvalWorkflow'

/** 引擎读的决策变量名。⛔ 改这里必须同步后端 `evaluateOutgoingEdges` 的 ctx.put("decision", ...)。 */
export const DECISION_VAR = '#decision'
export const COND_APPROVE = `${DECISION_VAR} == 'APPROVE'`
export const COND_REJECT = `${DECISION_VAR} == 'REJECT'`

export const START_NODE_ID = 'start'
export const APPROVED_NODE_ID = 'approved'
export const REJECTED_NODE_ID = 'rejected'

/** 审批人来源。角色与具体人员可以并存 —— 引擎侧 approverUserIds 优先于 approverRoles。 */
export interface ApproverSource {
  roles: string[]
  userIds: string[]
}

export type StepType = 'approval' | 'cc'

export interface ApprovalStep {
  id: string
  type: StepType
  /** 面向一线的中文步骤名。⛔ 不要用英文 key（2026-09-01 缺陷 23）。 */
  label: string
  approver: ApproverSource
  /** 或签 = 一人通过即可；会签 = 需所有人通过。仅对 type='approval' 有意义。 */
  mode?: 'or' | 'and'
  /** 审批人为空时：自动通过 / 阻断。 */
  emptyAction?: 'pass' | 'block'
  timeoutMinutes?: number
  /** 超时转派给谁。 */
  delegateUserId?: string
  delegateUserLabel?: string
}

export interface StepFlow {
  steps: ApprovalStep[]
}

export interface GeneratedGraph {
  nodes: ApprovalWorkflowNode[]
  edges: ApprovalWorkflowEdge[]
  startNodeId: string
}

/** 画布坐标只为兼容既有存储结构；线性编辑器本身不用它。 */
function layoutY(index: number): number {
  return 120 + index * 140
}

function approvalNodeConfig(step: ApprovalStep): Record<string, unknown> {
  const requiredApprovers = step.mode === 'and'
    ? Math.max(1, step.approver.userIds.length || 1)
    : 1
  const config: Record<string, unknown> = {
    approverRoles: [...step.approver.roles],
    approverUserIds: [...step.approver.userIds],
    requiredApprovers,
    approvalMode: step.mode ?? 'or',
    emptyAction: step.emptyAction ?? 'pass',
  }
  if (step.timeoutMinutes != null) config.timeoutMinutes = step.timeoutMinutes
  if (step.delegateUserId) config.delegateUserId = step.delegateUserId
  if (step.delegateUserLabel) config.delegateUserLabel = step.delegateUserLabel
  return config
}

/**
 * 步骤列表 → 引擎图。
 *
 * 生成规则：
 * ```
 * start ──(无条件)──▶ 步骤1 ──APPROVE──▶ 步骤2 ──APPROVE──▶ approved
 *                       │                  │
 *                       └──REJECT──▶ rejected ◀──REJECT──┘
 * ```
 * 抄送(cc)步骤不参与决策，只在链上串一条无条件边。
 */
export function stepsToGraph(flow: StepFlow): GeneratedGraph {
  const approvalSteps = flow.steps.filter(s => s.type === 'approval')
  if (approvalSteps.length === 0) {
    throw new Error('审批流至少需要一个审批步骤')
  }

  const nodes: ApprovalWorkflowNode[] = [
    { id: START_NODE_ID, type: 'start', label: '发起', position: { x: 240, y: 40 }, config: {} },
  ]
  const edges: ApprovalWorkflowEdge[] = []

  flow.steps.forEach((step, i) => {
    nodes.push({
      id: step.id,
      type: step.type === 'cc' ? 'notify' : 'approval',
      label: step.label,
      position: { x: 240, y: layoutY(i) },
      config: step.type === 'cc'
        ? { ccRoles: [...step.approver.roles], ccUserIds: [...step.approver.userIds] }
        : approvalNodeConfig(step),
    })
  })

  nodes.push(
    {
      id: APPROVED_NODE_ID,
      type: 'end',
      label: '通过',
      position: { x: 400, y: layoutY(flow.steps.length) },
      config: { outcome: 'APPROVED' },
    },
    {
      id: REJECTED_NODE_ID,
      type: 'end',
      label: '拒绝',
      position: { x: 80, y: layoutY(flow.steps.length) },
      config: { outcome: 'REJECTED' },
    },
  )

  // 入口边：start → 第一个步骤，无条件（引擎沿边找第一个 human node）
  edges.push({
    id: `e_start_${flow.steps[0].id}`,
    source: START_NODE_ID,
    target: flow.steps[0].id,
    priority: 0,
  })

  flow.steps.forEach((step, i) => {
    const next = flow.steps[i + 1]
    const onwardTarget = next ? next.id : APPROVED_NODE_ID

    if (step.type === 'cc') {
      // 抄送不做决策，直接串下去
      edges.push({
        id: `e_${step.id}_next`,
        source: step.id,
        target: onwardTarget,
        priority: 0,
      })
      return
    }

    // 🔴 审批步骤：两条**都带条件**的出边。少一个条件就会退化成
    //    「取第一条永真边」——与用户点的是通过还是驳回无关。
    edges.push({
      id: `e_${step.id}_approve`,
      source: step.id,
      target: onwardTarget,
      label: '通过',
      condition: COND_APPROVE,
      priority: 0,
    })
    edges.push({
      id: `e_${step.id}_reject`,
      source: step.id,
      target: REJECTED_NODE_ID,
      label: '驳回',
      condition: COND_REJECT,
      priority: 1,
    })
  })

  const graph: GeneratedGraph = { nodes, edges, startNodeId: START_NODE_ID }
  assertGeneratedGraphIsSound(graph)
  return graph
}

/**
 * 生成物的自检。⛔ 不是"防御性编程"，是这一层的核心职责 ——
 * 引擎不会告诉你分支边少了条件，它会**静默地把通过路由到拒绝**。
 *
 * 放在 stepsToGraph 出口而不是只放在测试里：测试只覆盖它想到的输入，
 * 这里覆盖所有真实输入。
 */
export function assertGeneratedGraphIsSound(graph: GeneratedGraph): void {
  const byId = new Map(graph.nodes.map(n => [n.id, n]))

  for (const edge of graph.edges) {
    if (!byId.has(edge.source) || !byId.has(edge.target)) {
      throw new Error(`生成的连线端点不存在: ${edge.source} → ${edge.target}`)
    }
  }

  for (const node of graph.nodes) {
    if (node.type !== 'approval') continue
    const outgoing = graph.edges.filter(e => e.source === node.id)
    if (outgoing.length !== 2) {
      throw new Error(
        `审批节点 ${node.id} 应当恰好有 2 条出边(通过/驳回)，实际 ${outgoing.length} 条`,
      )
    }
    const blank = outgoing.filter(e => !e.condition || !e.condition.trim())
    if (blank.length > 0) {
      throw new Error(
        `审批节点 ${node.id} 存在无条件出边 —— 引擎会把它当永真并取第一条，`
        + '导致「通过」可能走到「拒绝」',
      )
    }
    const conditions = outgoing.map(e => e.condition)
    if (!conditions.includes(COND_APPROVE) || !conditions.includes(COND_REJECT)) {
      throw new Error(`审批节点 ${node.id} 的出边未同时覆盖 APPROVE 与 REJECT`)
    }
  }
}

/**
 * 引擎图 → 步骤列表（读回既有定义时用）。
 *
 * ⚠️ 只还原本模块生成得出来的形状。遇到分支/并行/条件节点等线性列表表达不了的图，
 * 返回 `null` —— 调用方应当退回画布模式，⛔ 不要静默丢弃用户已有的配置
 * （2026-09-01 事故就是"读不出来就当没有"造成的）。
 */
export function graphToSteps(
  nodes: ApprovalWorkflowNode[],
  edges: ApprovalWorkflowEdge[],
  startNodeId: string,
): StepFlow | null {
  if (nodes.some(n => n.type === 'condition' || n.type === 'parallel' || n.type === 'join')) {
    return null
  }
  const byId = new Map(nodes.map(n => [n.id, n]))
  const outgoing = (id: string) => edges.filter(e => e.source === id)

  const first = outgoing(startNodeId)
  if (first.length !== 1) return null

  const steps: ApprovalStep[] = []
  const visited = new Set<string>()
  let cursor: string | undefined = first[0].target

  while (cursor && cursor !== APPROVED_NODE_ID && cursor !== REJECTED_NODE_ID) {
    if (visited.has(cursor)) return null // 有环，线性列表表达不了
    visited.add(cursor)
    const node = byId.get(cursor)
    if (!node) return null
    if (node.type !== 'approval' && node.type !== 'notify') return null

    const cfg = (node.config ?? {}) as Record<string, unknown>
    steps.push({
      id: node.id,
      type: node.type === 'notify' ? 'cc' : 'approval',
      label: node.label ?? node.id,
      approver: {
        roles: Array.isArray(cfg.approverRoles) ? (cfg.approverRoles as string[]) : [],
        userIds: Array.isArray(cfg.approverUserIds)
          ? (cfg.approverUserIds as unknown[]).map(String)
          : [],
      },
      mode: cfg.approvalMode === 'and' ? 'and' : 'or',
      emptyAction: cfg.emptyAction === 'block' ? 'block' : 'pass',
      timeoutMinutes: typeof cfg.timeoutMinutes === 'number' ? cfg.timeoutMinutes : undefined,
      delegateUserId: cfg.delegateUserId ? String(cfg.delegateUserId) : undefined,
      delegateUserLabel: cfg.delegateUserLabel ? String(cfg.delegateUserLabel) : undefined,
    })

    const outs = outgoing(cursor)
    const onward = node.type === 'approval'
      ? outs.find(e => e.condition === COND_APPROVE)
      : outs[0]
    if (!onward) return null
    cursor = onward.target
  }

  return steps.length > 0 ? { steps } : null
}
