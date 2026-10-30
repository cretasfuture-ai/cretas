import { describe, expect, it } from 'vitest';
import { isPlatformWorkspaceRoute } from '../guards';
import { isFactoryViewPageRoute } from '@/store/modules/platformFactoryContext';

describe('platform workspace route boundary', () => {
  it('keeps platform tools and factory console in the platform workspace', () => {
    expect(isPlatformWorkspaceRoute('/platform/factories')).toBe(true);
    expect(isPlatformWorkspaceRoute('/system/llm-usage')).toBe(true);
  });

  it('does not treat factory daily routes as platform routes', () => {
    expect(isPlatformWorkspaceRoute('/dashboard')).toBe(false);
    expect(isPlatformWorkspaceRoute('/production/batches')).toBe(false);
    expect(isPlatformWorkspaceRoute('/warehouse/inventory-query')).toBe(false);
  });

  it('opens only the reviewed list pages in factory view', () => {
    for (const path of ['/dashboard', '/production/plans', '/warehouse/materials',
      '/procurement/orders', '/sales/orders', '/quality/label-qc']) {
      expect(isFactoryViewPageRoute(path)).toBe(true);
    }
    for (const path of ['/system/features', '/finance', '/hr/employees', '/smart-bi/analysis',
      '/production/plans/1', '/production/plans-evil']) {
      expect(isFactoryViewPageRoute(path)).toBe(false);
    }
  });
});

