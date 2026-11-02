import { describe, expect, it } from 'vitest';
import { isPlatformWorkspaceRoute } from '../guards';
import { isFactoryViewPageRoute } from '@/store/modules/platformFactoryContext';
import { platformMenuConfig } from '@/components/layout/menuConfig';

describe('platform workspace route boundary', () => {
  it('keeps platform tools and factory console in the platform workspace', () => {
    expect(isPlatformWorkspaceRoute('/platform/factories')).toBe(true);
    expect(isPlatformWorkspaceRoute('/system/llm-usage')).toBe(true);
  });

  it('keeps every platform-only tool reachable, including conversational factory building', () => {
    for (const path of ['/factory-onboarding', '/factory-review', '/system/ai-intents',
      '/system/encoding-rules', '/system/smartbi-config', '/system/label-qc-platform-diagnostics',
      '/system/data-quality-queue', '/smart-bi/calibration']) {
      expect(isPlatformWorkspaceRoute(path)).toBe(true);
    }
  });

  it('derives the platform sidebar from menuConfig platform-only entries, without factory menus', () => {
    const tools = platformMenuConfig.flatMap((group) => group.children ?? []);
    const paths = tools.map((item) => item.path);
    expect(paths).toContain('/factory-onboarding');
    expect(paths).toContain('/platform/factories');
    expect(new Set(paths).size).toBe(paths.length);
    for (const item of tools) {
      expect(item.roles).toEqual(['platform_admin']);
    }
    // 工厂自己的就绪页、工厂超级管理员也能用的页面不进平台侧栏
    expect(paths).not.toContain('/factory-readiness');
    expect(paths).not.toContain('/ops/agent-ops');
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
