import { useAuthStore } from './authStore';
import { canViewPrice } from '../utils/priceViewAccess';
export { PRICE_VIEW_ROLES, canViewPriceForRole } from '../utils/priceViewAccess';

/**
 * Uses the shared mirror of backend PermissionServiceImpl.PRICE_VIEW_ROLES.
 * Backend strips price values to null for any role outside this list; frontend uses
 * it to hide price-related summary stats (otherwise仓管 sees 0/null misleading).
 *
 * Sprint 1 Track C shipped this Set web-admin only — Sprint 2 Track I (U-FOOTER-1)
 * brings parity to RN, since StickyFooterSummary needs the same gate for 金额 stats.
 */

/**
 * Returns true if current logged-in user's role can view price fields.
 *
 * Usage:
 *   const canViewPrice = useCanViewPrice();
 *   const visibleStats = stats.filter(s => !s.canViewPrice || canViewPrice);
 *
 * Reactive: subscribes to useAuthStore so role change triggers re-render.
 */
export function useCanViewPrice(): boolean {
  const user = useAuthStore((state) => state.user);
  return canViewPrice(user);
}
