import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNavigationVisibilityStore } from '@/store/modules/navigationVisibility';
import { digitalEmployeeProps, digitalWorkdeskTitle } from '../digitalEmployeeProps';
const { auth } = vi.hoisted(() => ({ auth: { factoryId: 'WHOLESALE_TEST', currentRole: 'factory_super_admin', factoryType: 'FACTORY', isPlatform: false } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
const { canWrite } = vi.hoisted(() => ({ canWrite: vi.fn(() => false) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite }) }));

describe('tenant scoped digital employee route props', () => {
  beforeEach(() => { setActivePinia(createPinia()); auth.factoryId = 'WHOLESALE_TEST'; auth.currentRole = 'factory_super_admin'; auth.isPlatform = false; canWrite.mockReturnValue(false); });
  const snapshot = () => ({ factoryId: 'WHOLESALE_TEST', role: 'factory_super_admin', factoryType: 'FACTORY', isPlatform: false, menuProfile: 'wholesale' as const, paths: ['/sales/orders'] });
  it('enables after ready configuration and clears during reload or error', () => {
    const nav = useNavigationVisibilityStore();
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    nav.publish(snapshot());
    expect(digitalEmployeeProps()).toEqual({ digitalEmployee: true, employeePaths: ['/sales/orders'], employeeNavigationPending: false, employeeCanWrite: false });
    expect(digitalWorkdeskTitle('/workdesk/purchaser', '原标题')).toBe('采购数字员工工作台');
    nav.beginLoading();
    expect(digitalEmployeeProps()).toEqual({ digitalEmployee: false, employeePaths: [], employeeNavigationPending: true, employeeCanWrite: false });
    nav.markError();
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    expect(digitalEmployeeProps().employeeNavigationPending).toBe(false);
    nav.markUnavailable();
    expect(digitalEmployeeProps().employeeNavigationPending).toBe(false);
  });
  it('rejects other factories, roles, platforms and absent profile', () => {
    const nav = useNavigationVisibilityStore();
    nav.publish(snapshot());
    auth.factoryId = 'F006';
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    expect(digitalEmployeeProps().employeeNavigationPending).toBe(true);
    auth.factoryId = 'WHOLESALE_TEST'; auth.currentRole = 'warehouse_keeper';
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    auth.currentRole = 'factory_super_admin'; auth.isPlatform = true;
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    auth.isPlatform = false;
    nav.publish({ ...snapshot(), menuProfile: undefined });
    expect(digitalEmployeeProps().digitalEmployee).toBe(false);
    expect(digitalWorkdeskTitle('/workdesk/purchaser', '原标题')).toBe('原标题');
  });
  it('passes the current role write permission to the corresponding workdesk', () => {
    useNavigationVisibilityStore().publish(snapshot());
    expect(digitalEmployeeProps({ path: '/workdesk/sales-owner' }).employeeCanWrite).toBe(false);
    canWrite.mockReturnValue(true);
    expect(digitalEmployeeProps({ path: '/workdesk/purchaser' }).employeeCanWrite).toBe(true);
    expect(canWrite).toHaveBeenLastCalledWith('procurement');
    auth.factoryId = 'F006';
    expect(digitalEmployeeProps({ path: '/workdesk/purchaser' }).employeeCanWrite).toBe(false);
  });
});
