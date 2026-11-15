import { useAuthStore } from '@/store/modules/auth';
import { useNavigationVisibilityStore } from '@/store/modules/navigationVisibility';
import { wholesaleWorkdeskTitles } from '@/components/layout/menuConfig';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';

/** Evaluate inside RouterView, so late configuration and account changes stay reactive. */
export function digitalEmployeeProps(route?: { path: string }) {
  const auth = useAuthStore();
  const navigation = useNavigationVisibilityStore();
  const snapshot = navigation.snapshot;
  const identityMatches = snapshot?.factoryId === auth.factoryId && snapshot?.role === auth.currentRole
    && snapshot?.factoryType === auth.factoryType && snapshot?.isPlatform === auth.isPlatform;
  const enabled = navigation.status === 'ready' && identityMatches && snapshot?.menuProfile === 'wholesale' && !auth.isPlatform;
  const module: ModuleName | undefined = route?.path === '/workdesk/sales-owner' ? 'sales'
    : route?.path === '/workdesk/purchaser' ? 'procurement' : undefined;
  return {
    digitalEmployee: !!enabled, employeePaths: enabled ? snapshot.paths : [],
    employeeNavigationPending: navigation.status === 'loading' || (navigation.status === 'ready' && !identityMatches),
    employeeCanWrite: !!enabled && !!module && usePermissionStore().canWrite(module),
  };
}

export function digitalWorkdeskTitle(path: string, fallback: string): string {
  if (!wholesaleWorkdeskTitles[path]) return fallback;
  return digitalEmployeeProps().digitalEmployee ? wholesaleWorkdeskTitles[path] || fallback : fallback;
}
