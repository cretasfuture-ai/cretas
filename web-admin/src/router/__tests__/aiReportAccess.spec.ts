import { describe, expect, it } from 'vitest';
import { isPathAllowedForRole } from '../guards';

describe('factory operating report route access', () => {
  it('lets finance open the restored list and details without granting other analytics paths', () => {
    expect(isPathAllowedForRole('finance_manager', '/analytics/ai-reports')).toBe(true);
    expect(isPathAllowedForRole('finance_manager', '/analytics/ai-reports/42')).toBe(true);
    expect(isPathAllowedForRole('finance_manager', '/analytics/kpi')).toBe(false);
  });
  it('preserves root and warehouse role routing for server-projected reports', () => {
    expect(isPathAllowedForRole('factory_super_admin', '/analytics/ai-reports/42')).toBe(true);
    expect(isPathAllowedForRole('warehouse_manager', '/analytics/ai-reports/42')).toBe(true);
  });
});
