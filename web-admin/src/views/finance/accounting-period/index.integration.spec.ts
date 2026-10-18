import { flushPromises, mount } from '@vue/test-utils';
import { nextTick, reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus, { ElMessageBox } from 'element-plus';
import PeriodPage from './index.vue';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn() }));
const route = reactive({ query: {} as Record<string, unknown> });
const identity = reactive({ factoryId: 'F001', user: { id: 1 } });
const permissions = reactive({ canWrite: true });

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(resolver => { resolve = resolver; });
  return { promise, resolve };
}

function period(status: 'OPEN' | 'PENDING_CLOSE' | 'CLOSED' = 'OPEN', factory = 'F001', year = 2026, month = 1) {
  return {
    id: `${factory}-${year}-${String(month).padStart(2, '0')}`, factoryId: factory, year, month, status,
    adjustDeadline: '2026-02-01T00:00:00Z',
  };
}

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: api.push }),
}));
vi.mock('@/api/request', () => ({ get: api.get, post: api.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => permissions.canWrite }),
}));

beforeEach(() => {
  vi.clearAllMocks();
  route.query = {};
  identity.factoryId = 'F001';
  identity.user.id = 1;
  permissions.canWrite = true;
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(true);
  api.get.mockImplementation((url: string) => {
    if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period()] });
    if (url.includes('/finance/vouchers')) return Promise.resolve({ success: true, data: { totalElements: 4 } });
    return Promise.resolve({ success: true, data: [] });
  });
  api.post.mockResolvedValue({ success: true, data: period('CLOSED') });
});

function render() {
  return mount(PeriodPage, { global: { plugins: [ElementPlus] } });
}

describe('期间结账恢复入口真实挂载行为', () => {
  it('shows pending periods and discards a late response after actor switch', async () => {
    const oldList = deferred<unknown>();
    const newList = deferred<unknown>();
    let listCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (!url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: { totalElements: 4 } });
      return listCalls++ === 0 ? oldList.promise : newList.promise;
    });
    const wrapper = render();
    identity.user.id = 2;
    await flushPromises();
    oldList.resolve({ success: true, data: [{ ...period('PENDING_CLOSE', 'F001', 2025, 12), id: 'OLD', factoryId: 'F001' }] });
    await flushPromises();
    expect(wrapper.text()).not.toContain('2025-12');
    newList.resolve({ success: true, data: [{ ...period('PENDING_CLOSE', 'F001', 2026, 2), id: 'NEW', factoryId: 'F001' }] });
    await flushPromises();
    expect(wrapper.text()).toContain('2026-02');
    wrapper.unmount();
  });

  it('clears stale period rows and shows the read error after refresh failure', async () => {
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('2026-01');
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.reject(new Error('期间服务不可用'));
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    await wrapper.findAll('button').find(button => button.text() === '刷新')!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('2026-01');
    expect(wrapper.text()).toContain('期间服务不可用');
    wrapper.unmount();
  });

  it('closes the open-period modal on factory and actor switch before confirmation', async () => {
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '打开期间')!.trigger('click');
    await nextTick();
    expect((wrapper.vm as unknown as { openDialogVisible: boolean }).openDialogVisible).toBe(true);
    api.post.mockClear();

    identity.factoryId = 'F002';
    identity.user.id = 2;
    await nextTick();
    await flushPromises();

    expect((wrapper.vm as unknown as { openDialogVisible: boolean }).openDialogVisible).toBe(false);
    const staleConfirm = wrapper.findAll('button').find(button => button.text() === '确认');
    expect(staleConfirm?.isVisible() ?? false).toBe(false);
    expect(api.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('does not request an unsupported voucher count before request-close', async () => {
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '发起结账')!.trigger('click');
    await flushPromises();
    expect(api.get.mock.calls.some(([url]) => String(url).includes('/finance/vouchers'))).toBe(false);
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    expect(ElMessageBox.confirm.mock.calls[0][0]).toContain('确认前请在会计凭证中核对本期单据');
    expect(ElMessageBox.confirm.mock.calls[0][0]).not.toContain('笔凭证');
    expect(api.post).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('prevents repeated request-close while the confirmation is open', async () => {
    const confirmation = deferred<boolean>();
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(confirmation.promise);
    const wrapper = render();
    await flushPromises();
    const requestButton = wrapper.findAll('button').find(button => button.text() === '发起结账')!;
    await Promise.all([requestButton.trigger('click'), requestButton.trigger('click')]);
    await flushPromises();
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1);
    confirmation.resolve(true);
    await flushPromises();
    expect(api.post).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('prevents repeated reopen submissions while the mutation is pending', async () => {
    const reopen = deferred<unknown>();
    api.post.mockReturnValueOnce(reopen.promise);
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period('CLOSED')] });
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '反结账')!.trigger('click');
    const vm = wrapper.vm as unknown as { reopenForm: { reasonType: string } };
    vm.reopenForm.reasonType = '财务发现少入一笔凭证';
    const submit = wrapper.findAll('button').find(button => button.text() === '确认反结账')!;
    await Promise.all([submit.trigger('click'), submit.trigger('click')]);
    expect(api.post).toHaveBeenCalledTimes(1);
    reopen.resolve({ success: true, data: period('OPEN') });
    await flushPromises();
    wrapper.unmount();
  });

  it('opens a valid recovery deep-link and returns only after explicit reopen', async () => {
    route.query = {
      year: '2026', month: '1', _periodRecovery: '1', sourceFactoryId: 'F001', sourceActorId: '1',
      _returnTo: '/finance/return-refunds/return-a?refundRequestId=RF-1&ledgerId=LEDGER-1',
    };
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period('CLOSED')] });
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('反结账 2026-01 期间');
    const vm = wrapper.vm as unknown as { reopenForm: { reasonType: string } };
    vm.reopenForm.reasonType = '财务发现少入一笔凭证';
    await wrapper.findAll('button').find(button => button.text() === '确认反结账')!.trigger('click');
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F001/finance/accounting-periods/reopen', {
      year: 2026, month: 1, reason: '财务发现少入一笔凭证',
    });
    expect(api.push).toHaveBeenCalledWith('/finance/return-refunds/return-a?refundRequestId=RF-1&ledgerId=LEDGER-1');
    wrapper.unmount();
  });

  it.each([
    { label: 'array marker', marker: ['1'] },
    { label: 'duplicate marker', marker: ['1', '1'] },
  ])('rejects a recovery deep-link with a $label without opening or posting', async ({ marker }) => {
    route.query = {
      year: '2026', month: '1', _periodRecovery: marker, sourceFactoryId: 'F001', sourceActorId: '1',
      _returnTo: '/finance/return-refunds/return-a?refundRequestId=RF-1&ledgerId=LEDGER-1',
    };
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period('CLOSED')] });
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    const wrapper = render();
    await flushPromises();
    expect((wrapper.vm as unknown as { reopenDialogVisible: boolean }).reopenDialogVisible).toBe(false);
    expect(wrapper.text()).toContain('期间恢复链接已失效');
    expect(api.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('closes the recovery dialog and ignores a late reopen response after identity switch', async () => {
    const reopen = deferred<unknown>();
    api.post.mockReturnValueOnce(reopen.promise);
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period('CLOSED')] });
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '反结账')!.trigger('click');
    const vm = wrapper.vm as unknown as { reopenForm: { reasonType: string } };
    vm.reopenForm.reasonType = '财务发现少入一笔凭证';
    await wrapper.findAll('button').find(button => button.text() === '确认反结账')!.trigger('click');
    identity.factoryId = 'F002';
    await nextTick();
    await flushPromises();
    reopen.resolve({ success: true, data: period('OPEN', 'F001') });
    await flushPromises();
    expect(api.push).not.toHaveBeenCalled();
    expect((wrapper.vm as unknown as { reopenDialogVisible: boolean }).reopenDialogVisible).toBe(false);
    wrapper.unmount();
  });

  it('keeps a read-only account from opening a deep-linked reopen dialog', async () => {
    permissions.canWrite = false;
    route.query = { year: '2026', month: '1' };
    api.get.mockImplementation((url: string) => {
      if (url.includes('/accounting-periods')) return Promise.resolve({ success: true, data: [period('CLOSED')] });
      return Promise.resolve({ success: true, data: { totalElements: 4 } });
    });
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('当前账号只有只读权限，不能反结账');
    expect(wrapper.text()).not.toContain('反结账 2026-01 期间');
    expect(wrapper.findAll('button').find(button => button.text() === '反结账')!.attributes('disabled')).toBeDefined();
    wrapper.unmount();
  });
});
