import { effectScope, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { isWorkdeskScopeChanged, useWorkdeskRequest } from '../useWorkdeskRequest';

describe('native workdesk request identity boundary', () => {
  it('rejects old responses after switching away and back to the same factory', async () => {
    const factoryId = ref('A'); const actorId = ref('1');
    const scope = effectScope();
    const api = scope.run(() => useWorkdeskRequest(() => ({ factoryId: factoryId.value, actorId: actorId.value })))!;
    let complete!: (value: string) => void;
    const pending = api.run(() => new Promise<string>((resolve) => { complete = resolve; })).catch(error => error);
    factoryId.value = 'B'; factoryId.value = 'A';
    complete('old tenant report');
    expect(isWorkdeskScopeChanged(await pending)).toBe(true);
    expect(await api.run(async () => 'new report')).toBe('new report');
    scope.stop();
  });
  it('does not send a request without a real actor and preserves native payload semantics', async () => {
    const actorId = ref(''); const scope = effectScope();
    const api = scope.run(() => useWorkdeskRequest(() => ({ factoryId: 'A', actorId: actorId.value })))!;
    const operation = vi.fn(async () => ({ preview: true, intentCode: 'RECEIVE_CONFIRM_CREATE' }));
    await expect(api.run(operation)).rejects.toThrow('操作人');
    expect(operation).not.toHaveBeenCalled();
    actorId.value = '2';
    expect(await api.run(operation)).toEqual({ preview: true, intentCode: 'RECEIVE_CONFIRM_CREATE' });
    scope.stop();
  });
});
