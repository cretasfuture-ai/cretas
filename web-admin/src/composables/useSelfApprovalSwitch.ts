import { ref, watch, type Ref } from 'vue';
import { get } from '@/api/request';

/**
 * 本厂是否打开了「允许本人审批」(纪律排查 B1–B3 / B11 / B15)。
 *
 * 读 GET /{factoryId}/settings/features 的 allowSelfApproval。读不到 (网络错 / 老后端没有这个键)
 * 一律按关处理 —— 页面照旧不让提交人自批, 与改动前一致; 后端各审批出口仍自己把关。
 */
export function useSelfApprovalSwitch(factoryId: Ref<string | null | undefined>) {
  const allowSelfApproval = ref(false);

  async function load(id: string | null | undefined) {
    allowSelfApproval.value = false;
    if (!id) return;
    try {
      const res = await get<Record<string, boolean>>(`/${id}/settings/features`, { _silent: true });
      allowSelfApproval.value = !!(res.success && res.data && res.data.allowSelfApproval === true);
    } catch {
      allowSelfApproval.value = false;
    }
  }

  watch(factoryId, (id) => { void load(id); }, { immediate: true });

  return { allowSelfApproval, reload: () => load(factoryId.value) };
}
