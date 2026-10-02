import path from 'path';
import type { FactoryRole, FactoryType, FactoryUser } from '../../types/auth';
import { canAccessOperatingReports, operatingReportReaderKey } from '../../utils/operatingReportAccess';
import { NAV_DIR, parseRoleDispatch, reachableScreensFor, relative, screensIn } from './lib/roleReachability';

function user(role: FactoryRole, factoryType: FactoryType | undefined = 'FACTORY', factoryId = 'TEST_REPORT'): FactoryUser {
  return { id: 71, username: 'synthetic', email: '', isActive: true, createdAt: '', updatedAt: '',
    userType: 'factory', factoryUser: { role, factoryType, factoryId, permissions: [] } };
}
const roles: FactoryRole[] = ['factory_super_admin', 'finance_manager', 'warehouse_manager'];
const dispatch = parseRoleDispatch();

describe('normal factory operating report entry', () => {
  it.each(roles)('%s gets both real report screens through its normal role navigator', (role) => {
    expect(canAccessOperatingReports(user(role))).toBe(true);
    const screens = [...reachableScreensFor(role, dispatch).keys()].map(relative);
    expect(screens).toContain('screens/factory-admin/ai-analysis/AIReportScreen.tsx');
    expect(screens).toContain('screens/factory-admin/ai-analysis/AIReportDetailScreen.tsx');
  });
  it('list and its detail destination are registered together in the shared stack', () => {
    const routes = screensIn({ file: path.join(NAV_DIR, 'OperatingReportStackNavigator.tsx'), scope: 'OperatingReportStackNavigator' });
    expect(routes.map((r) => r.screenName)).toEqual(['AIReport', 'AIReportDetail']);
  });
  it.each(['RESTAURANT', 'HEADQUARTERS', 'BRANCH', 'CENTRAL_KITCHEN'] as FactoryType[])(
    'does not add a factory report entry to %s', (type) => {
      for (const role of roles) expect(canAccessOperatingReports(user(role, type))).toBe(false);
    });
  it('fails closed for unknown tenant, no factory, anonymous, cashier and warehouse worker', () => {
    const unknown = user('finance_manager');
    expect(canAccessOperatingReports({ ...unknown, factoryUser: { ...unknown.factoryUser, factoryType: undefined } })).toBe(false);
    expect(canAccessOperatingReports(user('finance_manager', 'FACTORY', ''))).toBe(false);
    expect(canAccessOperatingReports(null)).toBe(false);
    expect(canAccessOperatingReports(user('cashier'))).toBe(false);
    expect(canAccessOperatingReports(user('warehouse_worker'))).toBe(false);
  });
  it('reader identity changes for role, factory and account, without using credentials', () => {
    const finance = user('finance_manager');
    const key = operatingReportReaderKey(finance);
    expect(operatingReportReaderKey(user('warehouse_manager'))).not.toBe(key);
    expect(operatingReportReaderKey(user('finance_manager', 'FACTORY', 'OTHER_TEST'))).not.toBe(key);
    expect(operatingReportReaderKey({ ...finance, id: 72 })).not.toBe(key);
    expect(operatingReportReaderKey(null)).not.toBe(key);
  });
});
