import type { AgentWireScope } from './pageAgentTransport';

function row(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function sameScope(left: AgentWireScope, right: AgentWireScope): boolean {
  return left.factoryId === right.factoryId && left.actorId === right.actorId && left.role === right.role && left.sandboxId === right.sandboxId;
}

/** Secret credential material stays in this closure, never in agent state,
 * observations, actions, journals, or LLM prompts. The backend prefers Bearer
 * over cookies, so the exact outgoing header binds the verified actor. */
export function createAgentIdentityBinding(currentToken: () => string | null, currentScope: () => AgentWireScope) {
  let proof: { token: string; scope: AgentWireScope } | undefined;
  const assertCurrent = (expected: AgentWireScope) => {
    if (!proof || !sameScope(proof.scope, expected) || !sameScope(currentScope(), expected) || currentToken() !== proof.token) {
      proof = undefined;
      throw new Error('当前登录状态已改变，AI已停止核对与提交');
    }
  };
  return {
    async verify(expected: AgentWireScope, fetchIdentity: () => Promise<unknown>): Promise<void> {
      proof = undefined;
      const token = currentToken();
      if (!token || !sameScope(currentScope(), expected)) throw new Error('登录身份已变化，请重新登录后开始操作');
      const envelope = row(await fetchIdentity());
      if (envelope.success === false) throw new Error('真实登录身份核对失败');
      const data = row(envelope.data ?? envelope), factory = row(data.factoryUser);
      const role = String(data.roleCode ?? factory.role ?? data.role ?? '');
      if (String(factory.factoryId ?? data.factoryId) !== expected.factoryId
          || String(data.id ?? data.userId ?? factory.id) !== expected.actorId
          || !expected.role || role !== expected.role
          || currentToken() !== token || !sameScope(currentScope(), expected)) {
        throw new Error('真实账号、角色或登录凭证已变化，未执行页面操作');
      }
      proof = { token, scope: { ...expected } };
    },
    assertOutgoing(expected: AgentWireScope, authorization?: string | null): void {
      assertCurrent(expected);
      if (authorization !== `Bearer ${proof!.token}`) {
        proof = undefined;
        throw new Error('提交前登录状态发生变化，已阻止请求；请重新核对当前账号');
      }
    },
    assertCurrent,
    clear(): void { proof = undefined; },
  };
}
