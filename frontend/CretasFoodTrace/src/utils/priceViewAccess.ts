import { getUserRole, type User } from '../types/auth';

/** Mirrors PermissionServiceImpl's special procurement:price:view scope. */
export const PRICE_VIEW_ROLES: ReadonlySet<string> = new Set([
  'factory_super_admin', 'platform_admin', 'procurement_manager', 'finance_manager',
  'sales_manager', 'dispatcher', 'production_manager', 'restaurant_manager',
  'restaurant_owner', 'restaurant_purchaser', 'permission_admin', 'department_admin',
]);

export function canViewPriceForRole(role: string | null | undefined): boolean {
  return role != null && PRICE_VIEW_ROLES.has(role);
}

export function canViewPrice(user: User | null | undefined): boolean {
  return !!user && canViewPriceForRole(getUserRole(user));
}
