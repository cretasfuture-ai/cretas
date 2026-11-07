import { describe, expect, it } from 'vitest';
import { createAgentWorkflowGrant, deriveAgentWorkflowContext } from '../pageAgentApproval';
import { DEMO_RUN_KEY } from '@/demo/f006Flow';
import type { AgentWireRequest } from '../pageAgentTransport';

const scope = { factoryId: 'F006', actorId: '1309', role: 'factory_super_admin', sandboxId: 'own-round' };
const runKey = `${DEMO_RUN_KEY}-unique-round`;
const row = { instanceId: 'wf1', moduleCode: 'PURCHASE_ORDER', businessEntityId: 'po1', currentNodeId: 'node1' };
const state = () => ({ active: true, phase: 'acting', scope: { ...scope }, runKey, completed: [{ entityId: 'po1', entityType: 'PURCHASE_ORDER' as const, createdBy: '1309', runKey }] });
const context = () => deriveAgentWorkflowContext(state(), scope, true, row, 'APPROVE');
const wire = (): AgentWireRequest => ({ method: 'POST', path: '/unused', canonicalPath: '/workflow/instances/wf1/demo-actions', kind: 'business', payload: { action: 'APPROVE', expectedNodeId: 'node1', idempotencyKey: 'oa-approve-wf1-node1', notes: runKey } });

describe('original approval page one-use grant', () => {
  it('grants the demo exception only for a verified purchase from the current round', () => {
    expect(context().demoRunKey).toBe(runKey);
    for (const changed of [{ entityId: 'old-po' }, { createdBy: '999' }, { runKey: `${runKey}-old` }]) {
      const current = state(); Object.assign(current.completed[0]!, changed);
      expect(deriveAgentWorkflowContext(current, scope, true, row, 'APPROVE').demoRunKey).toBeUndefined();
    }
    expect(deriveAgentWorkflowContext(state(), scope, true, { ...row, moduleCode: 'SALES_ORDER' }, 'APPROVE').demoRunKey).toBeUndefined();
  });
  it.each(['factoryId', 'actorId', 'role', 'sandboxId'] as const)('rejects changed live %s', key => {
    expect(() => deriveAgentWorkflowContext(state(), { ...scope, [key]: 'changed' }, true, row, 'APPROVE')).toThrow();
  });
  it('requires an active original click, owned sandbox and a known node/module/action', () => {
    expect(() => deriveAgentWorkflowContext({ ...state(), phase: 'paused' }, scope, true, row, 'APPROVE')).toThrow();
    expect(() => deriveAgentWorkflowContext(state(), scope, false, row, 'APPROVE')).toThrow();
    expect(() => deriveAgentWorkflowContext(state(), scope, true, { ...row, currentNodeId: undefined }, 'APPROVE')).toThrow();
    expect(() => deriveAgentWorkflowContext(state(), scope, true, { ...row, moduleCode: 'OTHER' }, 'APPROVE')).toThrow();
    expect(() => deriveAgentWorkflowContext(state(), scope, true, row, 'REJECT')).toThrow();
  });
  it('consumes an exact original request once and clears after the handler', async () => {
    const grant = createAgentWorkflowGrant();
    await grant.run(context(), scope, async () => {
      expect(grant.consume(wire(), scope)).toEqual(context());
      expect(() => grant.consume(wire(), scope)).toThrow();
    });
    expect(() => grant.consume(wire(), scope)).toThrow();
  });
  it.each(['path', 'node', 'notes', 'key', 'scope'])('consumes a mismatched %s grant before rejecting', async mismatch => {
    const grant = createAgentWorkflowGrant(); const request = wire();
    const payload = request.payload as Record<string, string>;
    if (mismatch === 'path') request.canonicalPath = '/workflow/instances/other/demo-actions';
    if (mismatch === 'node') payload.expectedNodeId = 'other';
    if (mismatch === 'notes') payload.notes = 'other';
    if (mismatch === 'key') payload.idempotencyKey = 'other';
    await grant.run(context(), scope, async () => {
      expect(() => grant.consume(request, mismatch === 'scope' ? { ...scope, sandboxId: 'other' } : scope)).toThrow();
      expect(() => grant.consume(wire(), scope)).toThrow();
    });
  });
  it('clears after a thrown handler and explicit cancellation', async () => {
    const grant = createAgentWorkflowGrant();
    await expect(grant.run(context(), scope, async () => { throw new Error('handler failed'); })).rejects.toThrow('handler failed');
    expect(() => grant.consume(wire(), scope)).toThrow();
    await grant.run(context(), scope, async () => { grant.clear(); expect(() => grant.consume(wire(), scope)).toThrow(); });
  });
});
