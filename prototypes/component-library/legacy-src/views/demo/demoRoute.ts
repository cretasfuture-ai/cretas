export type DemoTenant = 'factory' | 'rest' | 'logistics';

function firstQueryValue(value: unknown): string | undefined {
  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : undefined;
  }
  return typeof value === 'string' ? value : undefined;
}

export function resolveDemoTenant(query: Record<string, unknown>): DemoTenant | null {
  const raw = firstQueryValue(query.tenant ?? query.type)?.toLowerCase();
  if (raw === 'rest' || raw === 'restaurant' || raw === 'catering') return 'rest';
  if (raw === 'factory' || raw === 'food') return 'factory';
  if (raw === 'logistics' || raw === 'logistic' || raw === 'transport') return 'logistics';
  return null;
}

export function resolveDemoRedirect(query: Record<string, unknown>, tenant?: DemoTenant | null): string {
  const redirect = firstQueryValue(query.redirect);
  if (redirect && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect;
  }
  if (tenant === 'logistics') return '/scheduling/logistics/workbench';
  return '/dashboard';
}

/**
 * 演示租户判定 —— 全仓**唯一**定义。
 *
 * 演示账号由 `POST /api/mobile/auth/demo-login` 发放, 其 factoryId 一律是
 * `DEMO_*` (DEMO_REST / DEMO_FACTORY2 / DEMO_LOGISTICS)。路由守卫拿它决定
 * 「要不要给演示页放宽模块闸」, 所以它不能有第二份实现 —— 两份一定会漂。
 */
export function isDemoFactoryId(factoryId?: string | null): boolean {
  return /^DEMO_/.test(factoryId || '');
}

export function resolveLogisticsDemoRoute(path: string, factoryType?: string, factoryId?: string): string | null {
  if (path === '/dashboard' && factoryType === 'LOGISTICS' && isDemoFactoryId(factoryId)) {
    return '/scheduling/logistics/workbench';
  }
  return null;
}
