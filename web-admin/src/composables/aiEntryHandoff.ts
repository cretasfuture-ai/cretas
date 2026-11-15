export interface AiEntryIdentity { factoryId: string; actorId: string }
export type AiOrderPath = '/sales/orders' | '/procurement/orders';
type Entry = AiEntryIdentity & { path: AiOrderPath; input: string; expiresAt: number };
const pending = new Map<string, Entry>();

/** Keep business text in memory, never in a URL or persisted cross-account storage. */
export function queueAiEntry(identity: AiEntryIdentity, path: AiOrderPath, input: string) {
  if (!identity.factoryId || !identity.actorId) throw new Error('请先登录已绑定企业的账号');
  if (!input.trim() || input.length > 4000) throw new Error('请填写不超过4000字的办理要求');
  for (const [key, entry] of pending) if (entry.expiresAt < Date.now()) pending.delete(key);
  if (pending.size >= 5) pending.delete(pending.keys().next().value!);
  const key = crypto.randomUUID();
  pending.set(key, { ...identity, path, input, expiresAt: Date.now() + 300_000 });
  return { path, query: { assistant: 'order-entry', handoff: key } };
}

export function takeAiEntry(key: string, identity: AiEntryIdentity | null, path: string): string {
  const entry = pending.get(key);
  pending.delete(key);
  return entry && identity && entry.factoryId === identity.factoryId && entry.actorId === identity.actorId
    && entry.path === path && entry.expiresAt >= Date.now() ? entry.input : '';
}
