import { get } from '@/api/request';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
import { useDisposalApprovalStore } from '@/store/modules/disposalApproval';
import { createTodayLoader, type TodayContext } from './todayData';

/** 「今天」「待办」共用一个读取器：两页之间切换不重复读，也共用同一个 inflight。 */
const loader = createTodayLoader({ get: (url, config) => get(url, config) as never });

export function useToday() {
  const auth = useAuthStore();
  const perm = usePermissionStore();
  const disposal = useDisposalApprovalStore();

  function context(): TodayContext {
    const factoryId = String(auth.factoryId || '');
    const userId = String(auth.user?.id ?? '');
    return {
      factoryId,
      userId,
      canRead: (modules) => modules.some((m) => perm.canAccess(m as ModuleName)),
      // 与侧栏同一判据：没有 finance 模块的人不问；资格只看 disposalApproval store
      disposalCanApprove: async () => {
        if (!perm.canAccess('finance')) return false;
        await disposal.ensureLoaded(factoryId, userId);
        return disposal.canApprove;
      },
    };
  }
  return { T: loader.T, reload: () => loader.load(context()), loader };
}
