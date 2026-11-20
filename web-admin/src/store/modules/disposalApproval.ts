import { ref } from 'vue';
import { defineStore } from 'pinia';
import { get } from '@/api/request';

/**
 * 报废审批资格 (disposal:approve) —— 网页菜单 / 路由守卫的唯一依据 (纪律排查 B8 的网页侧)。
 *
 * 后端 `GET /{factoryId}/disposal-records/approval-permission` 与 approve/reject 守卫读同一份判断
 * (DisposalRecordService#canApprove, 工厂可在阈值里改名单), App 早已按它显示按钮。
 * 网页以前自己抄了一份岗位名 ['finance_manager','factory_super_admin','platform_admin'],
 * 工厂把名单改了就会漂 (形态 D)。
 *
 * 读不到 = 不显示 (fail-closed): 宁可晚一点出现, 不出一个点了必 403 的入口。
 * 结论按 (factoryId, userId) 缓存, 换账号 / 换工厂重新问。
 */
export const useDisposalApprovalStore = defineStore('disposalApproval', () => {
  const canApprove = ref(false);
  const loadedKey = ref('');
  let inflight: { key: string; promise: Promise<void> } | null = null;

  async function ensureLoaded(factoryId: string, userKey: string): Promise<void> {
    const key = `${factoryId}:${userKey}`;
    if (!factoryId) {
      canApprove.value = false;
      loadedKey.value = '';
      return;
    }
    if (loadedKey.value === key) return;
    if (inflight?.key === key) return inflight.promise;
    canApprove.value = false;
    const promise = (async () => {
      let result = false;
      try {
        const res = await get<{ canApprove?: boolean }>(
          `/${factoryId}/disposal-records/approval-permission`,
          { _silent: true },
        );
        result = res?.data?.canApprove === true;
      } catch {
        result = false;
      }
      if (inflight?.key === key) {
        canApprove.value = result;
        loadedKey.value = key;
        inflight = null;
      }
    })();
    inflight = { key, promise };
    return promise;
  }

  function reset(): void {
    canApprove.value = false;
    loadedKey.value = '';
    inflight = null;
  }

  return { canApprove, loadedKey, ensureLoaded, reset };
});
