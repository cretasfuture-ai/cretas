import { DEMO_RUN_KEY } from '@/demo/f006Flow';
import type { AgentWireRequest, AgentWireScope, AgentWorkflowContext } from './pageAgentTransport';

export interface VerifiedAgentOrder {
  entityId?: string;
  entityType?: 'PURCHASE_ORDER' | 'SALES_ORDER';
  createdBy?: string;
  runKey?: string;
}
export interface WorkflowPageRow {
  instanceId: string;
  moduleCode: string;
  businessEntityId: string;
  currentNodeId?: string;
}
interface ApprovalState {
  active: boolean;
  phase: string;
  scope: AgentWireScope | null;
  runKey: string;
  completed: readonly VerifiedAgentOrder[];
}
function sameScope(a: AgentWireScope, b: AgentWireScope): boolean {
  return a.factoryId === b.factoryId && a.actorId === b.actorId && a.role === b.role && a.sandboxId === b.sandboxId;
}
function record(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function validId(value: unknown): value is string { return typeof value === 'string' && /^[A-Za-z0-9_:-]{1,160}$/.test(value); }

/** Metadata comes from the real pending row, never from model output. A
 * purchase demo exception additionally requires the verified current-run PO. */
export function deriveAgentWorkflowContext(
  state: ApprovalState, live: AgentWireScope, sandboxReadyAndOwned: boolean,
  row: WorkflowPageRow, action: string,
): AgentWorkflowContext {
  if (!state.active || state.phase !== 'acting' || !state.scope || !sameScope(state.scope, live)
      || live.factoryId !== 'F006' || live.actorId !== '1309' || live.role !== 'factory_super_admin'
      || !live.sandboxId || !sandboxReadyAndOwned) throw new Error('当前演示审批的账号、工厂或轮次不一致');
  if (action !== 'APPROVE' || !validId(row.instanceId) || !validId(row.businessEntityId) || !validId(row.currentNodeId)
      || !['PURCHASE_ORDER', 'SALES_ORDER'].includes(row.moduleCode)) throw new Error('此审批尚未接入可核对的AI操作，请手动处理');
  const context: AgentWorkflowContext = { instanceId: row.instanceId, moduleCode: row.moduleCode as AgentWorkflowContext['moduleCode'], businessEntityId: row.businessEntityId, expectedNodeId: row.currentNodeId, action: 'APPROVE' };
  const created = state.completed.find(item => item.entityId === row.businessEntityId && item.entityType === 'PURCHASE_ORDER' && item.createdBy === live.actorId && item.runKey === state.runKey);
  if (context.moduleCode === 'PURCHASE_ORDER' && created && state.runKey.startsWith(`${DEMO_RUN_KEY}-`)) context.demoRunKey = state.runKey;
  return context;
}

/** An original page handler may grant exactly one matching request. The grant
 * is consumed even on mismatch/throw; clearing a slot cannot revive it. */
export function createAgentWorkflowGrant() {
  let owner: symbol | null = null;
  let pending: { context: AgentWorkflowContext; scope: AgentWireScope } | null = null;
  return {
    async run<T>(context: AgentWorkflowContext, scope: AgentWireScope, callback: () => Promise<T>): Promise<T> {
      if (owner) throw new Error('上一笔审批操作尚未结束');
      const token = Symbol('original-workflow-handler'); owner = token;
      pending = { context: { ...context }, scope: { ...scope } };
      try { return await callback(); }
      finally { if (owner === token) { pending = null; owner = null; } }
    },
    consume(wire: AgentWireRequest, current: AgentWireScope): AgentWorkflowContext {
      const grant = pending; pending = null;
      if (!grant || !sameScope(grant.scope, current)) throw new Error('没有绑定到本轮原审批页面的一次性操作');
      const context = grant.context;
      const suffix = context.demoRunKey ? 'demo-actions' : 'actions';
      const payload = record(wire.payload);
      if (wire.method !== 'POST' || wire.canonicalPath !== `/workflow/instances/${context.instanceId}/${suffix}`
          || payload.action !== context.action || payload.expectedNodeId !== context.expectedNodeId
          || payload.idempotencyKey !== `oa-approve-${context.instanceId}-${context.expectedNodeId}`
          || context.demoRunKey && payload.notes !== context.demoRunKey) throw new Error('原审批单号、节点或本轮标识不匹配，已阻止提交');
      return { ...context };
    },
    clear(): void { pending = null; owner = null; },
  };
}
