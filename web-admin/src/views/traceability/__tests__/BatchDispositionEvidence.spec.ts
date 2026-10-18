import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';
const state = vi.hoisted(() => ({ history: vi.fn(), push: vi.fn() }));
const auth = reactive({ factoryId: 'NEW-FACTORY', user: { id: 2 } });
const access = reactive({ quality: true });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canAccess: () => access.quality }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }));
vi.mock('@/api/quality-disposition', () => ({ getDispositionHistory: (...args: unknown[]) => state.history(...args) }));
import Evidence from '../BatchDispositionEvidence.vue';
const approved = { id: 'APP-17', batchId: 17, inspectionId: 'QC-17', action: 'CONDITIONAL_RELEASE',
  requiresApproval: true, approvalStatus: 'APPROVED', approverName: '独立复核员', approvedAt: '2026-10-16T23:20:00' };
const open = (factoryId = 'NEW-FACTORY') => mount(Evidence, { props: { factoryId, batchId: 17 }, global: { plugins: [ElementPlus] } });
beforeEach(() => { vi.clearAllMocks(); auth.factoryId = 'NEW-FACTORY'; auth.user.id = 2; access.quality = true; state.history.mockResolvedValue([]); });
describe('复用质检复核历史', () => {
  it('独立展示条件放行、审批人和时间，不把原检验改为合格', async () => {
    state.history.mockResolvedValue([approved]); const wrapper = open(); await flushPromises();
    expect(state.history).toHaveBeenCalledWith('NEW-FACTORY', 17);
    expect(wrapper.text()).toContain('条件放行'); expect(wrapper.text()).toContain('已批准');
    expect(wrapper.text()).toContain('独立复核员'); expect(wrapper.text()).toContain('2026-10-16');
    expect(wrapper.text()).toContain('原始检验结论与复核决定分别保留');
    await wrapper.find('button').trigger('click');
    expect(state.push).toHaveBeenCalledWith({ path: '/quality/disposition', query: { batchId: '17' } });
  });
  it('待审批与已拒绝不能显示成已批准', async () => {
    state.history.mockResolvedValue([{ ...approved, approvalStatus: 'PENDING', approvedAt: null }, { ...approved, id: 'APP-18', approvalStatus: 'REJECTED' }]);
    const wrapper = open(); await flushPromises(); expect(wrapper.text()).toContain('待审批');
    expect(wrapper.text()).toContain('已拒绝'); expect(wrapper.text()).not.toContain('已批准');
  });
  it('读取失败明确报错，不显示暂无记录', async () => {
    state.history.mockRejectedValue(new Error('历史服务不可用')); const wrapper = open(); await flushPromises();
    expect(wrapper.text()).toContain('历史服务不可用'); expect(wrapper.text()).not.toContain('暂无复核申请记录');
  });
  it('返回其他批次的记录不展示', async () => {
    state.history.mockResolvedValue([{ ...approved, batchId: 99 }]); const wrapper = open(); await flushPromises();
    expect(wrapper.text()).toContain('当前批次不一致'); expect(wrapper.text()).not.toContain('独立复核员');
  });
  it('无权限或不同工厂时不读取复核历史', async () => {
    const foreign = open('OTHER-FACTORY'); await flushPromises(); expect(foreign.text()).toBe('');
    access.quality = false; const denied = open(); await flushPromises(); expect(denied.text()).toBe(''); expect(state.history).not.toHaveBeenCalled();
  });
  it('切换账号后旧请求不能回填审批人', async () => {
    let finish: (value: unknown) => void = () => {};
    state.history.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = open(); await flushPromises(); auth.user.id = 3; state.history.mockResolvedValue([]); await flushPromises();
    finish([approved]); await flushPromises(); expect(wrapper.text()).not.toContain('独立复核员');
    expect(wrapper.text()).toContain('暂无复核申请记录');
  });
});
