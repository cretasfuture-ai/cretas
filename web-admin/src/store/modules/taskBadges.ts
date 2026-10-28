import { computed, reactive, ref } from 'vue';
import { defineStore } from 'pinia';
import { get } from '@/api/request';
import { getPendingWarehouseReceivingTasks } from '@/api/purchaseReceive';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';

export type TaskBadgeKey = 'salesOrders' | 'warehouseReceiving' | 'unorderedInbound';

/**
 * 每个角标要读哪些模块 —— 没有任一模块的读权限就【别发那个请求】。
 *
 * ⚠️ 2026-08-17: 角标之前对所有人一视同仁地发三个请求。报工审批页的文员
 * 没有 sales 读权限, 于是 `/sales/orders` 每轮都 403。
 * 请求带 `_silent: true` 不弹 toast, 但 403 照样进 console, 而且服务端照样被打。
 *
 * ⇒ 权限不足时【不发请求】, 而不是「发了再把错误吞掉」。
 * 结果对用户是一样的(角标本来就 fail-closed 地隐藏), 少的只是噪声和无效请求。
 *
 * 🔴 2026-08-20 (M-13): 上面那次修对了做法, 但 `warehouseReceiving` 这一条
 * **映射错了模块** —— 写的是 `procurement`, 而
 * `GET /warehouse/receiving/tasks` 的后端注解是
 * `@RequirePermission({"warehouse:read_write", "warehouse:read", "inventory:write"})`。
 *
 * 于是财务主管(有 procurement 读权限、无 warehouse)被守卫放行 → 发出请求 → 接口 403。
 * prod 实测: 财务打开**任何**页面(经营驾驶舱/智能数据分析都算)都在刷这条 403,
 * 而且前端还会弹「操作无法完成」——**让财务以为是自己的审批操作失败了**。
 * 那条采购单他因此打不开、审不了(台账 M-13, 本轮第一条阻塞)。
 *
 * ▎**守卫量的模块 ≠ 接口要求的模块** —— 两份清单各写各的, 一定会漂(形态 D)。
 *
 * ⇒ 改成**模块列表**, 与后端注解逐条对应; 任一命中即放行(与后端 requireAll=false 同义)。
 * ⛔ 不要退回单模块 —— 后端好几个接口就是「A 或 B」, 单模块要么过严要么漏。
 */
const BADGE_MODULES: Record<TaskBadgeKey, ModuleName[]> = {
  // GET /sales/orders
  salesOrders: ['sales'],
  // GET /warehouse/receiving/tasks — warehouse:read_write / warehouse:read / inventory:write
  warehouseReceiving: ['warehouse', 'inventory'],
  // GET /customer-material-arrivals
  unorderedInbound: ['warehouse'],
};

interface BadgeState {
  count: number;
  available: boolean;
  /**
   * 请求过、但这次没算出来(接口失败 / 服务端说 UNKNOWN)。⇒ 角标显示「?」, 不是 0、也不是隐藏。
   * 没权限(根本没发请求)与还没加载都不算 unknown。
   */
  unknown: boolean;
}

/** 服务端 `GET /task-badges/*` 的返回: state=KNOWN 时 count 是授权范围内的真实总数。 */
interface ServerBadgeCount {
  state: 'KNOWN' | 'UNKNOWN';
  count: number | null;
  reason?: string | null;
}

/** 数字在服务端算(真正的 COUNT), 前端不再取第一页再数 —— 超过一页就会数错。 */
async function loadServerCount(currentFactoryId: string, path: string, label: string): Promise<number> {
  const response = await get<ServerBadgeCount>(`/${currentFactoryId}/task-badges/${path}`, {
    _silent: true,
  } as never);
  if (!response.success) throw new Error(response.message || `${label} task badge unavailable`);
  const data = response.data;
  if (!data || data.state !== 'KNOWN' || typeof data.count !== 'number' || !Number.isFinite(data.count)) {
    throw new Error(data?.reason || `${label} task badge count is unknown`);
  }
  return data.count;
}

export const useTaskBadgeStore = defineStore('taskBadges', () => {
  const REFRESH_INTERVAL_MS = 5 * 60_000;
  const factoryId = ref('');
  const loading = ref(false);
  let nextRefreshAt = 0;
  let loadingFactory = '';
  let refreshQueued = false;
  const states = reactive<Record<TaskBadgeKey, BadgeState>>({
    salesOrders: { count: 0, available: false, unknown: false },
    warehouseReceiving: { count: 0, available: false, unknown: false },
    unorderedInbound: { count: 0, available: false, unknown: false },
  });
  let loadToken = 0;

  const totalActionable = computed(() => Object.values(states)
    .filter((state) => state.available)
    .reduce((sum, state) => sum + state.count, 0));

  function setFactory(nextFactoryId: string): void {
    const normalized = String(nextFactoryId || '').trim();
    if (factoryId.value === normalized) return;
    factoryId.value = normalized;
    nextRefreshAt = 0;
    refreshQueued = false;
    loading.value = false;
    loadingFactory = '';
    loadToken++;
    for (const state of Object.values(states)) {
      state.count = 0;
      state.available = false;
      state.unknown = false;
    }
  }

  function loadSales(currentFactoryId: string): Promise<number> {
    return loadServerCount(currentFactoryId, 'sales-orders', 'Sales order');
  }

  async function loadReceiving(currentFactoryId: string): Promise<number> {
    const response = await getPendingWarehouseReceivingTasks(currentFactoryId);
    if (!response.success) throw new Error(response.message || 'Warehouse receiving task badge unavailable');
    return Array.isArray(response.data) ? response.data.length : 0;
  }

  function loadUnorderedInbound(currentFactoryId: string): Promise<number> {
    return loadServerCount(currentFactoryId, 'unordered-inbound', 'Unordered inbound');
  }

  async function loadAll(force = false): Promise<void> {
    const currentFactoryId = factoryId.value;
    if (!currentFactoryId) return;
    if (!force && Date.now() < nextRefreshAt) return;
    if (loading.value && loadingFactory === currentFactoryId) {
      if (force) refreshQueued = true;
      return;
    }
    const token = ++loadToken;
    loading.value = true;
    loadingFactory = currentFactoryId;

    const permission = usePermissionStore();
    const runners: Record<TaskBadgeKey, (fid: string) => Promise<number>> = {
      salesOrders: loadSales,
      warehouseReceiving: loadReceiving,
      unorderedInbound: loadUnorderedInbound,
    };

    const loaders: Array<[TaskBadgeKey, Promise<number>]> = [];
    (Object.keys(runners) as TaskBadgeKey[]).forEach((key) => {
      // 任一模块有读权限即放行 —— 与后端 @RequirePermission(requireAll=false) 同义。
      if (!BADGE_MODULES[key].some((module) => permission.canAccess(module))) {
        // 权限不足 ⇒ 【根本不发这个请求】。角标同样隐藏(与请求失败时一致),
        // 区别只在于不再制造一条 403。
        states[key].available = false;
        states[key].unknown = false;
        states[key].count = 0;
        return;
      }
      loaders.push([key, runners[key](currentFactoryId)]);
    });

    const results = await Promise.allSettled(loaders.map(([, promise]) => promise));
    if (token !== loadToken || currentFactoryId !== factoryId.value) return;
    results.forEach((result, index) => {
      const key = loaders[index][0];
      if (result.status === 'fulfilled') {
        states[key].count = Math.max(0, Number(result.value) || 0);
        states[key].available = true;
        states[key].unknown = false;
      } else {
        // 算不出来就明说「未知」: 既不显示 0, 也不悄悄隐藏(隐藏会让人以为没有待办)。
        states[key].available = false;
        states[key].unknown = true;
      }
    });
    nextRefreshAt = loaders.length === 0 ? 0 : Date.now()
      + (results.every((result) => result.status === 'fulfilled') ? REFRESH_INTERVAL_MS : 60_000);
    loading.value = false;
    loadingFactory = '';
    if (refreshQueued) {
      refreshQueued = false;
      void loadAll(true);
    }
  }

  function badge(key?: TaskBadgeKey): number | null {
    if (!key || !states[key].available || states[key].count <= 0) return null;
    return states[key].count;
  }

  /** 这个角标的数字这次没能算出来(区别于「没权限」「没有待办」)。 */
  function isUnknown(key?: TaskBadgeKey): boolean {
    return !!key && states[key].unknown;
  }

  return { states, loading, totalActionable, setFactory, loadAll, badge, isUnknown };
});
