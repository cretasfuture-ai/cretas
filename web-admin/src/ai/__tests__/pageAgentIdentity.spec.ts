import { describe, expect, it, vi } from 'vitest';
import { createAgentIdentityBinding } from '../pageAgentIdentity';

const expected = { factoryId: 'F006', actorId: '1309', role: 'factory_super_admin', sandboxId: 'own' };
const me = { success: true, data: { id: 1309, factoryId: 'F006', roleCode: 'factory_super_admin' } };
function fixture() {
  let token: string | null = 'test-credential-a'; let scope = { ...expected };
  return { binding: createAgentIdentityBinding(() => token, () => scope), token(value: string | null) { token = value; }, scope(value: typeof expected) { scope = value; } };
}
describe('verified actor and outgoing credential epoch', () => {
  it('requires the exact header that was accepted by the live identity endpoint', async () => {
    const f = fixture(); await f.binding.verify(expected, async () => me);
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-a')).not.toThrow();
    expect(() => f.binding.assertOutgoing(expected, 'Bearer another-test-credential')).toThrow('登录状态');
  });
  it('rejects a multi-tab token switch between verification and the actual network send', async () => {
    const f = fixture(); await f.binding.verify(expected, async () => me);
    f.token('test-credential-b');
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-b')).toThrow();
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-a')).toThrow();
  });
  it('does not verify a token that changes while the identity request is in flight', async () => {
    const f = fixture();
    await expect(f.binding.verify(expected, async () => { f.token('test-credential-b'); return me; })).rejects.toThrow();
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-b')).toThrow();
  });
  it.each([
    { id: 7, factoryId: 'F006', roleCode: 'factory_super_admin' },
    { id: 1309, factoryId: 'OTHER', roleCode: 'factory_super_admin' },
    { id: 1309, factoryId: 'F006', roleCode: 'warehouse_manager' },
  ])('rejects a different real server actor/factory/role', async actor => {
    const f = fixture();
    await expect(f.binding.verify(expected, async () => ({ success: true, data: actor }))).rejects.toThrow();
  });
  it('does not silently use cookie-only authentication or skip verification', async () => {
    const f = fixture(); const fetchIdentity = vi.fn(async () => me);
    f.token(null); await expect(f.binding.verify(expected, fetchIdentity)).rejects.toThrow();
    expect(fetchIdentity).not.toHaveBeenCalled();
    expect(() => f.binding.assertOutgoing(expected, null)).toThrow();
  });
  it('invalidates its proof when the data slot changes or the controller is disposed', async () => {
    const f = fixture(); await f.binding.verify(expected, async () => me);
    f.scope({ ...expected, sandboxId: 'another' });
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-a')).toThrow();
    f.scope({ ...expected }); await f.binding.verify(expected, async () => me); f.binding.clear();
    expect(() => f.binding.assertOutgoing(expected, 'Bearer test-credential-a')).toThrow();
  });
});
