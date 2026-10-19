import { describe, expect, it, vi } from 'vitest';
import { defineComponent, nextTick, reactive, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { productionPlanCustomerReadAccess, useProductionPlanCustomerReadAccess } from '../productionPlanCustomerReadAccess';

function permissions(sales = '-', finance = '-') {
  return reactive({ isDbLoaded: true, dbLoadError: null as string | null, currentUserId: 'user-1',
    sales, finance, getPermissionLevel(module: 'sales' | 'finance') { return this[module]; } });
}

describe('customer roster permission source', () => {
  it.each([['r', '-', 'allowed'], ['rw', '-', 'allowed'], ['-', 'r', 'allowed'],
    ['-', 'rw', 'allowed'], ['w', '-', 'denied'], ['-', 'w', 'denied'], ['-', '-', 'denied']])(
    'sales=%s finance=%s resolves %s', (sales, finance, expected) => {
      expect(productionPlanCustomerReadAccess(permissions(sales, finance))).toBe(expected);
    });
  it('does not trust fallback permissions before the authoritative snapshot loads', () => {
    const p = permissions('rw'); p.isDbLoaded = false;
    expect(productionPlanCustomerReadAccess(p)).toBe('pending');
    p.dbLoadError = 'unavailable';
    expect(productionPlanCustomerReadAccess(p)).toBe('permission-error');
  });
});

describe('mounted customer loader', () => {
  function setup(p = permissions()) {
    const factory = ref<string | undefined>('F006');
    const fetcher = vi.fn<(id: string) => Promise<{ id: string; name: string }[]>>()
      .mockResolvedValue([{ id: 'c1', name: 'Allowed customer' }]);
    let state!: ReturnType<typeof useProductionPlanCustomerReadAccess>;
    const wrapper = mount(defineComponent({ setup() {
      state = useProductionPlanCustomerReadAccess(factory, p, fetcher);
      return state;
    }, template: '<div>{{ customerReadStatus }}:{{ customerReadHint }}<span v-for="c in customers" :key="String(c.id)">{{ c.name }}</span></div>' }));
    return { wrapper, state, p, factory, fetcher };
  }
  it('skips denied requests and loads after an effective grant without checking role names', async () => {
    const s = setup(); await flushPromises();
    expect(s.fetcher).not.toHaveBeenCalled(); expect(s.wrapper.text()).toContain('无客户名单读取权限');
    s.p.sales = 'r'; await flushPromises();
    expect(s.fetcher).toHaveBeenCalledWith('F006'); expect(s.wrapper.text()).toContain('Allowed customer');
  });
  it('keeps authorized failure distinct from a successful empty response, and retries', async () => {
    const s = setup(); s.fetcher.mockRejectedValueOnce(new Error('upstream unavailable'));
    s.p.finance = 'r'; await flushPromises();
    expect(s.state.customerReadStatus.value).toBe('error'); expect(s.wrapper.text()).toContain('upstream unavailable');
    expect(s.state.canRetryCustomers.value).toBe(true);
    expect(s.wrapper.text()).not.toContain('暂无可关联客户');
    s.fetcher.mockResolvedValueOnce([]); await s.state.loadCustomers();
    expect(s.state.customerReadStatus.value).toBe('ready'); expect(s.wrapper.text()).toContain('暂无可关联客户');
  });
  it('invalidates an in-flight response when permission is revoked', async () => {
    const s = setup(); let resolve!: (rows: { id: string; name: string }[]) => void;
    s.fetcher.mockImplementationOnce(() => new Promise(done => { resolve = done; }));
    s.p.sales = 'r'; await nextTick(); s.p.sales = '-';
    resolve([{ id: 'secret', name: 'stale roster' }]); await flushPromises();
    expect(s.state.customerReadStatus.value).toBe('denied'); expect(s.state.customers.value).toEqual([]);
  });
  it('waits for the snapshot and exposes permission lookup failure without sending a request', async () => {
    const p = permissions('rw'); p.isDbLoaded = false;
    const s = setup(p); await flushPromises(); expect(s.fetcher).not.toHaveBeenCalled();
    p.dbLoadError = 'permission endpoint down'; await flushPromises();
    expect(s.state.customerReadStatus.value).toBe('error'); expect(s.fetcher).not.toHaveBeenCalled();
    expect(s.state.canRetryCustomers.value).toBe(false);
  });
  it('waits for the new snapshot across the store identity-before-invalidation sequence', async () => {
    const s = setup(permissions('r')); await flushPromises();
    s.fetcher.mockClear();
    // Mirrors permissionStore.setRole: identity is assigned before isDbLoaded resets.
    s.factory.value = 'NEW_FACTORY'; s.p.currentUserId = 'user-2'; s.p.isDbLoaded = false;
    await flushPromises();
    expect(s.fetcher).not.toHaveBeenCalled(); expect(s.state.customerReadStatus.value).toBe('pending');
    s.p.sales = '-'; s.p.isDbLoaded = true; await flushPromises();
    expect(s.fetcher).not.toHaveBeenCalled(); expect(s.state.customerReadStatus.value).toBe('denied');
    s.p.sales = 'r'; await flushPromises();
    expect(s.fetcher).toHaveBeenCalledWith('NEW_FACTORY');
  });
});
