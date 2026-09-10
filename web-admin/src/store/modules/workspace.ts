import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export interface WorkspaceRouteSnapshot {
  fullPath: string;
  path: string;
  name?: string | symbol | null;
  title?: string;
  query?: Record<string, unknown>;
  /**
   * 路由的 `meta.module` —— 业务域的**权威**分类, router 里已有 19 种取值
   * (warehouse / finance / procurement / sales / dashboard / ...)。
   * ⛔ 不要在别处另建一份「路径 → 业务域」的映射表: 那就是本仓说的
   * 「同一个东西有两份, 它一定会漂」, 而漂的后果是某个页面**静默地**不再被失效。
   */
  module?: string;
}

export interface WorkspaceTab {
  key: string;
  fullPath: string;
  path: string;
  title: string;
  dirty: boolean;
  openedAt: number;
  /** 见 WorkspaceRouteSnapshot.module。可选: sessionStorage 里可能存着本次改动之前落盘的旧标签。 */
  module?: string;
}

const STORAGE_PREFIX = 'cretas:web-workspace:v1:';
const MAX_TABS = 10;

/**
 * 兜底失效阈值: 切回一个「上次取数距今超过这么久」的标签时重新取数。
 *
 * ⚠️ 这是**兜底**, 不是主力。主力是 {@link useWorkspaceStore} 的 `invalidateModules`
 * (写完立刻准); 这一条负责我们没预料到的写入口 —— 别人在另一台机器上改了数据、
 * 或者某个写操作还没接线。
 *
 * <p>3 分钟是权衡出来的: 再短就退化成「每次切标签都打一轮接口」(用户开着 10 个标签
 * 来回切, 后端和体验一起垮 —— 这正是不能无脑全量重取的原因); 再长客户就会继续待在
 * 「刷新一下网页就好了」的体验里。
 */
export const WORKSPACE_STALE_AFTER_MS = 3 * 60 * 1000;

function text(value: unknown): string {
  return String(value ?? '').trim();
}

export function workspaceRouteKey(route: Pick<WorkspaceRouteSnapshot, 'path' | 'query'>): string {
  const explicitTask = text(route.query?._task);
  return explicitTask ? `${route.path}?_task=${explicitTask}` : route.path;
}

export function workspaceRouteTitle(route: WorkspaceRouteSnapshot): string {
  const explicit = text(route.query?._taskLabel);
  if (explicit) return explicit;
  if (route.title) return text(route.title);
  const last = route.path.split('/').filter(Boolean).pop();
  return last || '工作台';
}

function isDirtyRoute(route: WorkspaceRouteSnapshot): boolean {
  const title = workspaceRouteTitle(route);
  return route.path.endsWith('/create')
    || ['1', 'true'].includes(text(route.query?.create).toLowerCase())
    || ['1', 'true'].includes(text(route.query?.edit).toLowerCase())
    || /新建|编辑/.test(title);
}

function safeParseTabs(raw: string | null): WorkspaceTab[] {
  if (!raw) return [];
  try {
    const value = JSON.parse(raw) as unknown;
    if (!Array.isArray(value)) return [];
    return value
      .filter((item): item is WorkspaceTab => Boolean(
        item && typeof item === 'object'
        && typeof (item as WorkspaceTab).key === 'string'
        && typeof (item as WorkspaceTab).fullPath === 'string'
        && typeof (item as WorkspaceTab).path === 'string'
        && typeof (item as WorkspaceTab).title === 'string',
      ))
      .slice(-MAX_TABS);
  } catch {
    return [];
  }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const scope = ref('anonymous');
  const tabs = ref<WorkspaceTab[]>([]);
  const activeKey = ref('');
  const draggingKey = ref('');
  const referenceKey = ref('');
  /**
   * 每个标签「被关过几次」。只用来生成 <keep-alive> 的缓存键, ⛔ 不持久化 ——
   * 它描述的是本次会话里缓存实例的身份, 刷新浏览器后缓存本来就没了。
   */
  const closeEpochs = ref<Record<string, number>>({});
  /**
   * 每个**缓存实例**第一次被显示的时刻。键是 {@link cacheKeyFor} 的产物(含 `#epoch`),
   * ⛔ 不是标签键 —— 换了 epoch 就是一个全新的组件实例, 它会重新 onMounted 取数,
   * 计时理应从那一刻重新开始。⛔ 同样不持久化。
   */
  const freshAt = ref<Record<string, number>>({});

  const activeTab = computed(() => tabs.value.find((tab) => tab.key === activeKey.value) || null);
  const referenceTab = computed(() => tabs.value.find((tab) => tab.key === referenceKey.value) || null);

  function storageKey(): string {
    return `${STORAGE_PREFIX}${scope.value}`;
  }

  function persist(): void {
    if (typeof sessionStorage === 'undefined') return;
    sessionStorage.setItem(storageKey(), JSON.stringify(tabs.value));
  }

  function setScope(nextScope: string): void {
    const normalized = text(nextScope) || 'anonymous';
    if (scope.value === normalized && tabs.value.length) return;
    scope.value = normalized;
    tabs.value = typeof sessionStorage === 'undefined'
      ? []
      : safeParseTabs(sessionStorage.getItem(storageKey()));
    activeKey.value = '';
    draggingKey.value = '';
    referenceKey.value = '';
    // 换工厂/换角色等于换一套标签, 旧的缓存代次跟着作废, 否则会给新标签发出带 #N 的键。
    closeEpochs.value = {};
    freshAt.value = {};
  }

  function openRoute(route: WorkspaceRouteSnapshot): void {
    if (text(route.query?._workspaceReference) === '1') return;
    const key = workspaceRouteKey(route);
    /*
     * 只有「切到另一个标签」才检查新鲜度。
     * ⚠️ 同一个标签内翻页 / 改筛选 / router.replace 摘参数也会走到这里 ——
     *    那时换缓存键等于把用户刚设好的筛选条件当场抹掉, 与 2026-08-19 客户实报第 (4) 条
     *    (「切换标签导致未保存的信息丢失」) 是同一种伤害。⛔ 不能一视同仁。
     */
    const switchingIn = activeKey.value !== key;
    const existing = tabs.value.find((tab) => tab.key === key);
    if (existing) {
      existing.fullPath = route.fullPath;
      existing.title = workspaceRouteTitle(route);
      // 旧版本落盘的标签没有 module, 在这里补上; 取不到就保持原值, ⛔ 不要覆盖成空。
      existing.module = text(route.module) || existing.module;
      existing.dirty ||= isDirtyRoute(route);
    } else {
      tabs.value.push({
        key,
        fullPath: route.fullPath,
        path: route.path,
        title: workspaceRouteTitle(route),
        dirty: isDirtyRoute(route),
        openedAt: Date.now(),
        module: text(route.module) || undefined,
      });
      if (tabs.value.length > MAX_TABS) {
        // 🔴 2026-08-19 客户实报: 「切换标签会导致未保存的信息丢失」。
        //    原来这里挑「第一个不是参考页、也不是刚打开的那个」就删, ⛔ 没看 dirty ——
        //    正在填的新建/编辑页会被<b>静默</b>删掉 (标签直接从栏里消失, 组件随之卸载,
        //    输入框内容一起没)。全仓 0 个 onBeforeRouteLeave 守卫, 所以没有任何东西会拦一下:
        //    5 处 beforeunload 只在关浏览器/刷新时触发, 应用内切标签一次都不响。
        //    改成优先挑<b>干净</b>的标签下手; 全都脏就让标签数暂时超过软上限 ——
        //    多留一个标签, 好过悄无声息吞掉用户刚打的字。
        const removable = tabs.value.find((tab) => tab.key !== referenceKey.value
          && tab.key !== key && !tab.dirty);
        if (removable) {
          tabs.value.splice(tabs.value.indexOf(removable), 1);
          invalidateCache(removable.key);
        }
      }
    }
    if (switchingIn) refreshIfStale(key);
    activeKey.value = key;
    persist();
  }

  /**
   * <keep-alive> 用的缓存键。
   *
   * <h3>🔴 2026-08-19 客户实报: 「关闭标签无法彻底刷新, 只能刷新浏览器」</h3>
   * AppLayout 的 {@code <keep-alive :max="10">} 用 {@link workspaceRouteKey} (= route.path) 当键,
   * 而 {@link closeTab} 只从 tabs 数组里删一行 —— <b>缓存里那个组件实例原封不动留着</b>。
   * 于是「关掉标签再打开」拿回的是同一个实例: keep-alive 的语义是 deactivate 而不是 unmount,
   * <b>onMounted 不会二次执行</b>, 而全仓 353 个会取数的页面里有 351 个只在 onMounted 取数
   * (只有 production/approval 一页写了 onActivated 重新取数)。
   * 结果就是用户看到的旧状态/旧数据, 除了 F5 没有别的出路 —— 产品里唯一的「刷新」按钮
   * (AppHeader.handleRefresh) 干的也正是 {@code router.go(0)} 整页重载。
   *
   * <p>修法: 把缓存身份从「路径」改成「这一次打开的这个标签」。关一次就 +1,
   * 下次再打开是<b>新键</b> → keep-alive 认不出来 → 真正重新 mount → onMounted 重新取数。
   *
   * <p>⚠️ 没关过的标签 epoch=0, 键与改动前<b>逐字相同</b> —— 标签之间来回切换仍然命中缓存
   * (那正是「切标签不该丢输入」需要的), 所以这个改动只影响「关闭后重开」这一条路径。
   */
  function cacheKeyFor(key: string): string {
    const epoch = closeEpochs.value[key] || 0;
    return epoch ? `${key}#${epoch}` : key;
  }

  /** 标签消失时作废它的缓存实例 —— 关闭与被挤掉都要走这里, 否则缓存与标签又脱钩。 */
  function invalidateCache(key: string): void {
    delete freshAt.value[cacheKeyFor(key)];   // 旧实例的计时跟着旧键一起作废
    closeEpochs.value[key] = (closeEpochs.value[key] || 0) + 1;
  }

  /**
   * 【方案 B: 时间兜底】切回一个已打开的标签时, 距上次取数太久就换缓存键让它重新 mount。
   *
   * ⛔ 正在编辑的脏标签永远不动 —— 重挂载会把用户填了一半的表单清空, 那正是
   * 2026-08-19 客户实报第 (4) 条的形态。宁可让它显示旧数据, 也不能吞掉用户打的字。
   */
  function refreshIfStale(key: string, now = Date.now()): void {
    const cacheKey = cacheKeyFor(key);
    const last = freshAt.value[cacheKey];
    if (last === undefined) {
      // 这个实例还没被显示过 —— 马上就要 mount, 从现在开始计时。
      freshAt.value[cacheKey] = now;
      return;
    }
    if (tabs.value.find((item) => item.key === key)?.dirty) return;
    if (now - last <= WORKSPACE_STALE_AFTER_MS) return;
    invalidateCache(key);
    freshAt.value[cacheKeyFor(key)] = now;
  }

  /**
   * 【方案 A: 写操作驱动失效】把受影响业务域里**其它**标签的缓存作废,
   * 它们下次被切回来时会重新 mount → onMounted 重新取数。
   *
   * <h3>🔴 2026-08-20 owner 实测</h3>
   * 「我在个人 OA 里点击通过以后, 再点到财务管理里面, <b>还是待审批</b>; 到仓储管理的
   * 入库任务与批次, 点进去<b>没有任何东西</b>。但我刷新了一下网页, 它就更新好了。」
   * 根因: 那些标签的组件实例停在 {@code <keep-alive>} 里, 切回去只触发 activated,
   * ⛔ 不会二次 onMounted —— 而全仓 353 个取数页面里 351 个只在 onMounted 取数。
   *
   * <p>参数是路由的 {@code meta.module}(权威分类), ⛔ 不是另造的一套域名。
   *
   * <p>三条刻意的例外:
   * <ul>
   *   <li><b>当前标签不动</b>: 发起写操作的页面自己知道该怎么刷(如 OA 列表批完就
   *       {@code loadPending()}); 在这里换键会把用户正看着的页面当场重挂载。</li>
   *   <li><b>脏标签不动</b>: 同 {@link refreshIfStale}。</li>
   *   <li><b>没记到 module 的标签不动</b>: 宁可让它走 {@link WORKSPACE_STALE_AFTER_MS}
   *       的时间兜底, 也不要顺手把不相干的标签清一遍。</li>
   * </ul>
   *
   * <p>⚠️ 作废是<b>惰性</b>的 —— 这里只改键, 不发任何请求。一次写操作最多换来
   * 「用户真的切回去看的那几个标签各取一次数」, ⛔ 不是「10 个标签立刻打 10 轮接口」。
   *
   * <p>⚠️ 已知代价: 被作废的旧实例仍留在 keep-alive 的 LRU 里占一个位置, 直到被挤掉。
   * 这与 {@link closeTab} 走的是同一条路(既有行为), max=10 兜住上限; 且脏标签不会
   * 产生这种孤儿, 所以「未提交的表单被挤掉」这条风险不因本改动而增加。
   */
  function invalidateModules(modules: readonly string[]): void {
    const targets = new Set(modules.map((item) => text(item)).filter(Boolean));
    if (!targets.size) return;
    tabs.value.forEach((tab) => {
      if (tab.key === activeKey.value) return;
      if (tab.dirty) return;
      if (!tab.module || !targets.has(tab.module)) return;
      invalidateCache(tab.key);
    });
  }

  function closeTab(key: string): WorkspaceTab | null {
    if (tabs.value.length <= 1) return null;
    const index = tabs.value.findIndex((tab) => tab.key === key);
    if (index < 0) return null;
    tabs.value.splice(index, 1);
    invalidateCache(key);
    if (referenceKey.value === key) referenceKey.value = '';
    const fallback = tabs.value[Math.min(index, tabs.value.length - 1)] || null;
    if (activeKey.value === key) activeKey.value = fallback?.key || '';
    persist();
    return fallback;
  }

  function markDirty(key: string, dirty: boolean): void {
    const tab = tabs.value.find((item) => item.key === key);
    if (!tab) return;
    tab.dirty = dirty;
    persist();
  }

  function beginDrag(key: string): void {
    draggingKey.value = key;
  }

  function endDrag(): void {
    draggingKey.value = '';
  }

  function moveTab(key: string, beforeKey: string): void {
    const from = tabs.value.findIndex((tab) => tab.key === key);
    if (from < 0 || key === beforeKey || !tabs.value.some((tab) => tab.key === beforeKey)) return;
    const [tab] = tabs.value.splice(from, 1);
    tabs.value.splice(tabs.value.findIndex((item) => item.key === beforeKey), 0, tab!);
    persist();
  }

  function pinReference(key = draggingKey.value): void {
    if (!tabs.value.some((tab) => tab.key === key)) return;
    referenceKey.value = key;
    draggingKey.value = '';
  }

  function closeReference(): void {
    referenceKey.value = '';
  }

  function duplicateRoute(): { path: string; query: Record<string, string> } | null {
    const tab = activeTab.value;
    if (!tab) return null;
    const taskId = Date.now().toString(36);
    const url = new URL(tab.fullPath, 'https://workspace.local');
    const query: Record<string, string> = {};
    url.searchParams.forEach((value, key) => {
      if (!key.startsWith('_task')) query[key] = value;
    });
    query._task = taskId;
    query._taskLabel = `${tab.title} ${tabs.value.filter((item) => item.path === tab.path).length + 1}`;
    return { path: tab.path, query };
  }

  return {
    tabs,
    activeKey,
    activeTab,
    draggingKey,
    referenceKey,
    referenceTab,
    setScope,
    openRoute,
    closeTab,
    cacheKeyFor,
    invalidateModules,
    refreshIfStale,
    markDirty,
    beginDrag,
    endDrag,
    moveTab,
    pinReference,
    closeReference,
    duplicateRoute,
  };
});
