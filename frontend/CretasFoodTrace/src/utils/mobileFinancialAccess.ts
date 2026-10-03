import { getFactoryId, getUserRole, type User } from '../types/auth';
import { hasModulePermission } from './permissionHelper';
import { isMobileBusinessObserver } from './mobileRoleBoundaries';
import { canViewPrice } from './priceViewAccess';

export type FinancialScreenKind = 'finance' | 'price';
export interface MobileFinancialScope {
  key: string;
  factoryId: string;
  canWrite: boolean;
}

/** A screen/form belongs to one actor and tenant, including its access level. */
export function getMobileFinancialScope(
  user: User | null | undefined, kind: FinancialScreenKind
): MobileFinancialScope | null {
  const factoryId = getFactoryId(user);
  if (!user || !factoryId) return null;
  const canRead = kind === 'price' ? canViewPrice(user) : hasModulePermission(user, 'finance', 'read');
  if (!canRead) return null;
  const canWrite = kind === 'finance' && hasModulePermission(user, 'finance', 'write')
    && !isMobileBusinessObserver(user);
  return { factoryId, canWrite, key: JSON.stringify([kind, user.id, factoryId, getUserRole(user), canWrite]) };
}
