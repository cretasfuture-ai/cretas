import { useAuthStore } from '@/store/modules/auth';
import { useNavigationVisibilityStore } from '@/store/modules/navigationVisibility';
import { digitalSpecialistTitles } from '@/components/workdesk/digitalSpecialistRoles';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';

/** Evaluate inside RouterView, so late configuration and account changes stay reactive. */
export function digitalEmployeeProps(route?: { path: string }) {
  const auth = useAuthStore();
  const navigation = useNavigationVisibilityStore();
  const snapshot = navigation.snapshot;
  const identityMatches = !!auth.user?.id && snapshot?.actorId === String(auth.user.id)
    && snapshot?.factoryId === auth.factoryId && snapshot?.role === auth.currentRole
    && snapshot?.factoryType === auth.factoryType && snapshot?.isPlatform === auth.isPlatform;
  const ready = navigation.status === 'ready' && identityMatches && !auth.isPlatform;
  const module: ModuleName | undefined = route?.path === '/workdesk/sales-owner' ? 'sales'
    : route?.path === '/workdesk/purchaser' ? 'procurement'
      : route?.path === '/workdesk/warehouse-keeper' ? 'warehouse'
        : route?.path === '/workdesk/finance-manager' ? 'finance'
          : ['/workdesk/quality-manager', '/workdesk/quality-chief'].includes(route?.path || '') ? 'quality'
            : route?.path === '/workdesk/production-manager' ? 'production'
              : route?.path === '/scheduling/logistics/workbench' ? 'scheduling' : undefined;
  return {
    digitalEmployee: true, employeePaths: ready ? snapshot.paths : [],
    employeeNavigationPending: !ready,
    employeeCanWrite: !!ready && !!module && usePermissionStore().canWrite(module),
    wholesaleMode: !!ready && snapshot?.menuProfile === 'wholesale',
  };
}

export function digitalWorkdeskTitle(path: string, fallback: string): string {
  return digitalSpecialistTitles[path] || fallback;
}
