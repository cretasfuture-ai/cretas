import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from '@/store/modules/permission';
function setAccess(granted: boolean) {
  usePermissionStore().$patch({ dbPermissions: { production: granted ? 'r' : '-', analytics: 'rw' } });
}
import Evidence from '../FinishedBatchEvidence.vue';
import { get } from '@/api/request';

vi.mock('@/api/request', () => ({ get: vi.fn() }));
const push = vi.fn();
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }));
const rows = [
  { batchNumber: 'B-KG', productName: '散装酱', actualQty: 10, unit: 'kg', completedAt: '2026-10-16T08:00:00' },
  { batchNumber: 'B-BOX', productName: '盒装酱', actualQty: 2, unit: '盒', completedAt: '2026-10-15T08:00:00' },
];
const wrappers: VueWrapper[] = [];
function render(period: [string, string] | null = ['2026-10-15', '2026-10-16'], canRead = true) {
  setAccess(canRead);
  const wrapper = mount(Evidence, { props: { factoryId: 'F006', actorKey: 'actor-1', period }, global: { plugins: [ElementPlus] } });
  wrappers.push(wrapper); return wrapper;
}
beforeEach(() => {
  setActivePinia(createPinia());
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-16T12:00:00'));
  vi.clearAllMocks(); vi.mocked(get).mockResolvedValue({ success: true, data: rows });
});
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers(); });

describe('finished batch facts through the real mounted table', () => {
  it('shows both SKUs in their own units and navigates to the exact batch', async () => {
    const wrapper = render(); await flushPromises();
    expect(get).toHaveBeenCalledWith('/F006/production/batches/finished', { params: { days: 90 } });
    expect(wrapper.text()).toContain('10 kg'); expect(wrapper.text()).toContain('2 盒');
    expect(wrapper.text()).toContain('不同单位的数量不混加');
    const button = wrapper.findAll('button').find(button => button.text() === '查看追溯')!;
    await button.trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/production/traceability', query: { batchNumber: 'B-KG' } });
  });
  it('filters the real completion date and product name without merging quantities', async () => {
    const wrapper = render(); await flushPromises();
    await wrapper.get('input[aria-label="按成品名称筛选"]').setValue('盒装');
    expect(wrapper.text()).toContain('B-BOX'); expect(wrapper.text()).not.toContain('B-KG');
    await wrapper.setProps({ period: ['2026-10-16', '2026-10-16'] }); await flushPromises();
    expect(wrapper.text()).toContain('B-KG'); expect(wrapper.text()).not.toContain('B-BOX');
  });
  it('does not load or retain rows when production permission is absent or revoked', async () => {
    const wrapper = render(undefined, false); await flushPromises();
    expect(get).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('没有生产查看权限');
    setAccess(true); await flushPromises();
    expect(wrapper.text()).toContain('B-KG');
    setAccess(false); await flushPromises();
    expect(wrapper.text()).not.toContain('B-KG'); expect(get).toHaveBeenCalledTimes(1);
  });
  it.each([null, ['2026-02-31', '2026-10-16'], ['2026-10-16', '2026-10-15'], ['2026-10-17', '2026-10-17']])('does not query an incomplete or invalid period %s', async period => {
    const wrapper = render(period as [string, string] | null); await flushPromises();
    expect(get).not.toHaveBeenCalled(); expect(wrapper.find('table').exists()).toBe(false);
    expect(wrapper.find('.el-alert').exists()).toBe(true);
  });
  it('accepts the first fully covered day and rejects the partial 90th-day boundary', async () => {
    const boundary = new Date(2026, 9, 16 - 89);
    const format = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    const wrapper = render([format(boundary), '2026-10-16']); await flushPromises();
    expect(get).toHaveBeenCalledTimes(1);
    boundary.setDate(boundary.getDate() - 1);
    await wrapper.setProps({ period: [format(boundary), '2026-10-16'] }); await flushPromises();
    expect(get).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('不能据此判断没有业务');
  });
  it.each([{ success: false, message: '资料读取失败' }, { success: true, data: { unexpected: true } }])('keeps API failures visible instead of claiming no batches', async response => {
    vi.mocked(get).mockResolvedValueOnce(response);
    const wrapper = render(); await flushPromises();
    expect(wrapper.text()).toContain('加载失败'); expect(wrapper.text()).not.toContain('该期间没有可显示');
  });
  it.each(['actorKey', 'factoryId'])('ignores an old response after %s changes', async field => {
    let resolveOld!: (value: { success: boolean; data: typeof rows }) => void;
    vi.mocked(get).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = render();
    await wrapper.setProps({ [field]: field === 'actorKey' ? 'actor-2' : 'F007' }); await flushPromises();
    resolveOld({ success: true, data: [{ ...rows[0]!, batchNumber: 'OLD-BATCH' }] }); await flushPromises();
    expect(wrapper.text()).toContain('B-KG'); expect(wrapper.text()).not.toContain('OLD-BATCH');
  });
  it('clears loading and ignores a pending result when dates are removed', async () => {
    let resolveOld!: (value: { success: boolean; data: typeof rows }) => void;
    vi.mocked(get).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = render(); await wrapper.setProps({ period: null });
    resolveOld({ success: true, data: rows }); await flushPromises();
    expect(wrapper.text()).not.toContain('正在读取'); expect(wrapper.text()).not.toContain('B-KG');
    expect(wrapper.text()).toContain('请选择完整');
  });
  it('reports missing completion evidence and removes all ambiguous duplicate identities', async () => {
    vi.mocked(get).mockResolvedValueOnce({ success: true, data: [...rows, rows[0], { batchNumber: 'NO-DATE' }] });
    const wrapper = render(); await flushPromises();
    expect(wrapper.text()).toContain('身份重复'); expect(wrapper.text()).toContain('缺少有效完工时间');
    expect(wrapper.text()).toContain('B-BOX');
    expect(wrapper.findAll('button').filter(button => button.text() === '查看追溯')).toHaveLength(1);
  });
  it.each([{ completedAt: null }, { actualQty: -1 }, { batchNumber: ' B-KG ', completedAt: null }])('does not offer a trace link when a malformed duplicate shares a valid identity %s', async malformed => {
    vi.mocked(get).mockResolvedValueOnce({ success: true, data: [...rows, { ...rows[0], ...malformed }] });
    const wrapper = render(); await flushPromises();
    expect(wrapper.text()).toContain('身份重复');
    const links = wrapper.findAll('button').filter(button => button.text() === '查看追溯');
    expect(links).toHaveLength(1);
    await links[0]!.trigger('click');
    expect(push).toHaveBeenCalledWith({ path: '/production/traceability', query: { batchNumber: 'B-BOX' } });
  });
});

it('rejects a late response after authoritative production access is revoked', async () => {
  let resolve!: (value: unknown) => void;
  vi.mocked(get).mockReturnValueOnce(new Promise(done => { resolve = done; }));
  const wrapper = render(); await flushPromises();
  expect(get).toHaveBeenCalledTimes(1);
  setAccess(false); await flushPromises();
  resolve({ success: true, data: rows }); await flushPromises();
  expect(wrapper.text()).toContain('没有生产查看权限');
  expect(wrapper.text()).not.toContain('B-KG');
  expect(get).toHaveBeenCalledTimes(1);
});
