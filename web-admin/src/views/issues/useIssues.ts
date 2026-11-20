import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
import { createIssuesLoader, type IssuesContext } from './issuesData';

/** 整个应用共用一个读取器：离开再回来不重复读，也共用同一个 inflight。 */
const loader = createIssuesLoader({ get: (url, config) => get(url, config) as never });

export function useIssues() {
  const auth = useAuthStore();
  const perm = usePermissionStore();
  function context(): IssuesContext {
    return {
      factoryId: String(auth.factoryId || ''),
      canRead: (modules) => modules.some((m) => perm.canAccess(m as ModuleName)),
    };
  }
  return { S: loader.S, reload: () => loader.load(context()), applyDisposition: loader.applyDisposition };
}
