/**
 * P2「常用」固定区（统一原型 D10）。
 *
 * 本批存浏览器本地（organizer 11-13 决定：后端没有用户偏好接口，新表留到以后）：
 * - 按「账号 id + 工厂 id」分键，同一台电脑上换账号/换工厂互不串；
 * - 读写一律 try/catch：隐私模式、存储被禁、内容损坏 ⇒ 当作空，不报错；
 * - ⛔ 界面上不写「已同步」之类的承诺（换电脑/换浏览器就没有，这是本批的已知限制）。
 * 只存 path；显示时只列当前侧栏真的看得见的项，权限仍由侧栏判。
 */
import { ref, type Ref } from 'vue';

export const PINNED_MENU_STORAGE_PREFIX = 'cretas:pinned-menu:v1';
/** 侧栏「常用」最多显示几条（只数当前看得见的）。 */
export const PINNED_MENU_LIMIT = 12;
/** 存储里最多留几条（含暂时看不见的，例如权限被收回又给回来）；超出丢最早的看不见的。 */
export const PINNED_MENU_STORE_CAP = 50;

export function pinnedMenuStorageKey(userId: string | number | null | undefined, factoryId: string | null | undefined): string | null {
  const user = userId === null || userId === undefined ? '' : String(userId).trim();
  if (!user) return null;
  return `${PINNED_MENU_STORAGE_PREFIX}:${user}:${(factoryId ?? '').trim() || '-'}`;
}

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null;
  } catch {
    return null;
  }
}

export function readPinnedPaths(key: string | null): string[] {
  if (!key) return [];
  try {
    const raw = storage()?.getItem(key);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return [...new Set(parsed.filter((p): p is string => typeof p === 'string' && p.startsWith('/')))].slice(-PINNED_MENU_STORE_CAP);
  } catch {
    return [];
  }
}

export function writePinnedPaths(key: string | null, paths: readonly string[]): boolean {
  if (!key) return false;
  try {
    const s = storage();
    if (!s) return false;
    s.setItem(key, JSON.stringify(paths.slice(-PINNED_MENU_STORE_CAP)));
    return true;
  } catch {
    return false;
  }
}

// 侧栏与顶栏共用一份响应式状态
const currentKey = ref<string | null>(null);
const pinned: Ref<string[]> = ref([]);
/** 侧栏过滤后真正看得见的叶子 path（由 AppSidebar 写入）；能不能钉、占不占名额都按它判。 */
const visibleLeafPaths: Ref<string[]> = ref([]);

/**
 * 加入一条：看得见的最多 PINNED_MENU_LIMIT 条（满了丢最早那条看得见的）；
 * 看不见的不占名额但也不删（权限回来还在），总数超过 PINNED_MENU_STORE_CAP 丢最早的看不见的。
 */
export function addPinnedPath(current: readonly string[], path: string, visible: ReadonlySet<string>): string[] {
  let next = [...current.filter((p) => p !== path), path];
  const visibleOnes = next.filter((p) => visible.has(p));
  if (visibleOnes.length > PINNED_MENU_LIMIT) {
    const drop = new Set(visibleOnes.slice(0, visibleOnes.length - PINNED_MENU_LIMIT));
    next = next.filter((p) => !drop.has(p));
  }
  while (next.length > PINNED_MENU_STORE_CAP) {
    const idx = next.findIndex((p) => !visible.has(p));
    next.splice(idx >= 0 ? idx : 0, 1);
  }
  return next;
}

export function usePinnedMenu() {
  function bind(key: string | null): void {
    if (key === currentKey.value) return;
    currentKey.value = key;
    pinned.value = readPinnedPaths(key);
  }
  function isPinned(path: string): boolean {
    return pinned.value.includes(path);
  }
  function setVisibleLeafPaths(paths: readonly string[]): void {
    visibleLeafPaths.value = [...paths];
  }
  /** 只有侧栏里看得见的叶子才能钉。 */
  function canPin(path: string): boolean {
    return visibleLeafPaths.value.includes(path);
  }
  function toggle(path: string): void {
    if (!currentKey.value) return;
    if (!isPinned(path) && !canPin(path)) return;
    const next = isPinned(path)
      ? pinned.value.filter((p) => p !== path)
      : addPinnedPath(pinned.value, path, new Set(visibleLeafPaths.value));
    pinned.value = next;
    writePinnedPaths(currentKey.value, next);
  }
  return { pinned, currentKey, visibleLeafPaths, bind, isPinned, canPin, setVisibleLeafPaths, toggle };
}
