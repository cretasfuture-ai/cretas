import type { User } from '../types/auth';
import { getFactoryId, getUserRole, isFactoryUser } from '../types/auth';

/** Navigation visibility only; the server projects every report using actual price permission. */
export function canAccessOperatingReports(user: User | null): boolean {
  if (!isFactoryUser(user) || !getFactoryId(user)
      || user.factoryUser.factoryType?.toUpperCase() !== 'FACTORY') return false;
  const role = getUserRole(user);
  return role === 'factory_super_admin' || role === 'finance_manager' || role === 'warehouse_manager';
}

export function operatingReportReaderKey(user: User | null): string {
  return JSON.stringify([getFactoryId(user), getUserRole(user), user?.id ?? null]);
}
