import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNavigationVisibilityStore } from '@/store/modules/navigationVisibility';
import { digitalEmployeeProps, digitalWorkdeskTitle } from '../digitalEmployeeProps';
const { auth, canWrite } = vi.hoisted(() => ({
  auth: { user: { id: 43 }, factoryId: 'F003', currentRole: 'factory_super_admin', factoryType: 'FACTORY', isPlatform: false },
  canWrite: vi.fn(() => false),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite }) }));

describe('unified digital specialist route presentation with scoped capabilities', () => {
  const snapshot = () => ({ actorId: String(auth.user.id), factoryId: auth.factoryId, role: auth.currentRole, factoryType: auth.factoryType,
    isPlatform: auth.isPlatform, paths: ['/sales/orders'] });
  beforeEach(() => {
    setActivePinia(createPinia()); auth.factoryId = 'F003'; auth.currentRole = 'factory_super_admin';
    auth.factoryType = 'FACTORY'; auth.isPlatform = false; auth.user.id = 43; canWrite.mockReturnValue(false);
  });
  it('uses the shared presentation for ordinary factories and wholesale without opening unready business entries', () => {
    const nav = useNavigationVisibilityStore();
    expect(digitalEmployeeProps()).toEqual({ digitalEmployee: true, employeePaths: [], employeeNavigationPending: true, employeeCanWrite: false, wholesaleMode: false });
    nav.publish(snapshot());
    expect(digitalEmployeeProps()).toEqual({ digitalEmployee: true, employeePaths: ['/sales/orders'], employeeNavigationPending: false, employeeCanWrite: false, wholesaleMode: false });
    expect(digitalWorkdeskTitle('/workdesk/purchaser', '原标题')).toBe('采购数字专员工作台');
    nav.publish({ ...snapshot(), menuProfile: 'wholesale' });
    expect(digitalEmployeeProps().wholesaleMode).toBe(true);
    nav.beginLoading();
    expect(digitalEmployeeProps().employeePaths).toEqual([]);
    expect(digitalEmployeeProps().employeeNavigationPending).toBe(true);
    nav.markError();
    expect(digitalEmployeeProps().employeeCanWrite).toBe(false);
  });
  it('keeps mismatched factories, roles and unscoped platform users from inheriting capabilities', () => {
    const nav = useNavigationVisibilityStore(); nav.publish(snapshot());
    auth.factoryId = 'OTHER';
    expect(digitalEmployeeProps().employeePaths).toEqual([]);
    expect(digitalEmployeeProps().employeeNavigationPending).toBe(true);
    auth.factoryId = 'F003'; auth.currentRole = 'warehouse_keeper';
    expect(digitalEmployeeProps().employeePaths).toEqual([]);
    auth.currentRole = 'factory_super_admin'; auth.isPlatform = true;
    nav.publish(snapshot());
    canWrite.mockReturnValue(true);
    expect(digitalEmployeeProps().employeePaths).toEqual([]);
    expect(digitalEmployeeProps({ path: '/workdesk/purchaser' }).employeeCanWrite).toBe(false);
  });
  it('rejects a same-factory same-role navigation snapshot from the previous actor', () => {
    const nav = useNavigationVisibilityStore(); nav.publish(snapshot()); canWrite.mockReturnValue(true);
    auth.user.id = 44;
    expect(digitalEmployeeProps({ path: '/workdesk/purchaser' })).toMatchObject({ employeeNavigationPending: true, employeePaths: [], employeeCanWrite: false });
    nav.publish(snapshot());
    expect(digitalEmployeeProps({ path: '/workdesk/purchaser' }).employeeCanWrite).toBe(true);
  });
  it('keeps a genuine LOGISTICS navigation identity ready without granting write access', () => {
    auth.factoryId = 'LOGISTICS_TEST'; auth.factoryType = 'LOGISTICS';
    useNavigationVisibilityStore().publish({ ...snapshot(), paths: ['/scheduling/logistics/workbench'] });
    expect(digitalEmployeeProps({ path: '/scheduling/logistics/workbench' })).toEqual({ digitalEmployee: true, employeePaths: ['/scheduling/logistics/workbench'], employeeNavigationPending: false, employeeCanWrite: false, wholesaleMode: false });
    auth.factoryType = 'RESTAURANT';
    expect(digitalEmployeeProps({ path: '/scheduling/logistics/workbench' }).employeeNavigationPending).toBe(true);
  });
  it.each([
    ['/workdesk/sales-owner', 'sales'], ['/workdesk/purchaser', 'procurement'],
    ['/workdesk/warehouse-keeper', 'warehouse'], ['/workdesk/finance-manager', 'finance'],
    ['/workdesk/quality-manager', 'quality'], ['/workdesk/quality-chief', 'quality'],
    ['/workdesk/production-manager', 'production'], ['/scheduling/logistics/workbench', 'scheduling'],
  ])('derives %s write capability from its existing module %s', (path, module) => {
    useNavigationVisibilityStore().publish(snapshot());
    expect(digitalEmployeeProps({ path }).employeeCanWrite).toBe(false);
    canWrite.mockReturnValue(true);
    expect(digitalEmployeeProps({ path }).employeeCanWrite).toBe(true);
    expect(canWrite).toHaveBeenLastCalledWith(module);
  });
});
