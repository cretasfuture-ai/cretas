import { watch } from 'vue';

export class WorkdeskScopeChangedError extends Error {
  constructor() { super('账号或企业已切换，已忽略原请求结果'); }
}
export const isWorkdeskScopeChanged = (error: unknown) => error instanceof WorkdeskScopeChangedError;

/** Fence native workdesk requests without changing their READ/preview/write contract. */
export function useWorkdeskRequest(identity: () => { factoryId: string; actorId: string; contextKey?: string }) {
  let generation = 0;
  const key = () => `${identity().factoryId}:${identity().actorId}:${identity().contextKey || ''}`;
  watch(key, () => { generation += 1; }, { flush: 'sync' });
  function capture() {
    const scope = key();
    const version = generation;
    return () => key() === scope && version === generation;
  }
  async function run<T>(operation: () => Promise<T>): Promise<T> {
    const current = identity();
    if (!current.factoryId || !current.actorId) throw new Error('账号未绑定企业或操作人，请重新登录');
    const isCurrent = capture();
    try {
      const response = await operation();
      if (!isCurrent()) throw new WorkdeskScopeChangedError();
      return response;
    } catch (error) {
      if (!isCurrent()) throw new WorkdeskScopeChangedError();
      throw error;
    }
  }
  return { run, capture };
}
