import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ApprovalPermissionMatrix from '../ApprovalPermissionMatrix.vue';

const mocks = vi.hoisted(() => ({ get: vi.fn(), setGrant: vi.fn(), confirm: vi.fn() }));

vi.mock('@/api/approvalPermissions', () => ({
  getApprovalPermissions: mocks.get,
  setApprovalGrant: mocks.setGrant,
}));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: mocks.confirm } };
});

const view = {
  approvals: [
    { code: 'approval_report_reversal', label: '报工撤回审批' },
    { code: 'approval_refund', label: '退款审批' },
  ],
  roles: [
    { code: 'factory_super_admin', label: '工厂超级管理员' },
    { code: 'production_manager', label: '生产经理' },
    { code: 'operator', label: '操作员' },
  ],
  grants: {
    approval_report_reversal: {
      factory_super_admin: { granted: true, source: 'ALWAYS' },
      production_manager: { granted: true, source: 'DEFAULT' },
      operator: { granted: false, source: 'DEFAULT' },
    },
    approval_refund: {
      factory_super_admin: { granted: true, source: 'ALWAYS' },
      production_manager: { granted: false, source: 'FACTORY' },
      operator: { granted: false, source: 'DEFAULT' },
    },
  },
};

type Vm = { change: (a: string, al: string, r: string, rl: string, v: string) => Promise<void>; selectValue: (a: string, r: string) => string };

describe('谁能审批 (纪律排查: 审批人按权限)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.get.mockResolvedValue(view);
    mocks.setGrant.mockResolvedValue(undefined);
    mocks.confirm.mockResolvedValue(true);
  });

  it('按岗位 × 审批类型显示; 超管恒有不可改; 本厂改过的显示为授给 / 不授给', async () => {
    const wrapper = mount(ApprovalPermissionMatrix, { props: { factoryId: 'F006' }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).toContain('报工撤回审批');
    expect(wrapper.text()).toContain('超管恒有');
    expect(wrapper.find('[data-testid="approval-grant-approval_report_reversal-factory_super_admin"]').exists()).toBe(false);
    const vm = wrapper.vm as unknown as Vm;
    expect(vm.selectValue('approval_report_reversal', 'production_manager')).toBe('default');
    expect(vm.selectValue('approval_refund', 'production_manager')).toBe('deny');
    wrapper.unmount();
  });

  it('授给 / 不授给 / 恢复默认 写的是本厂覆盖; 授给一线岗位先确认, 取消就不写', async () => {
    const wrapper = mount(ApprovalPermissionMatrix, { props: { factoryId: 'F006' }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.change('approval_refund', '退款审批', 'production_manager', '生产经理', 'grant');
    expect(mocks.setGrant).toHaveBeenLastCalledWith('F006', 'production_manager', 'approval_refund', true);
    await vm.change('approval_refund', '退款审批', 'production_manager', '生产经理', 'default');
    expect(mocks.setGrant).toHaveBeenLastCalledWith('F006', 'production_manager', 'approval_refund', null);
    expect(mocks.confirm).not.toHaveBeenCalled();

    mocks.confirm.mockRejectedValueOnce('cancel');
    mocks.setGrant.mockClear();
    await vm.change('approval_report_reversal', '报工撤回审批', 'operator', '操作员', 'grant');
    expect(mocks.confirm).toHaveBeenCalledWith(expect.stringContaining('一线岗位'), expect.any(String), expect.any(Object));
    expect(mocks.setGrant).not.toHaveBeenCalled();

    await vm.change('approval_report_reversal', '报工撤回审批', 'operator', '操作员', 'grant');
    expect(mocks.setGrant).toHaveBeenLastCalledWith('F006', 'operator', 'approval_report_reversal', true);
    wrapper.unmount();
  });
});
