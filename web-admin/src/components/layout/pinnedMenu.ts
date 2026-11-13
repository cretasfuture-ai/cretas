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
export const PINNED_MENU_LIMIT = 12;

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
    return [...new Set(parsed.filter((p): p is string => typeof p === 'string' && p.startsWith('/')))].slice(0, PINNED_MENU_LIMIT);
  } catch {
    return [];
  }
}

export function writePinnedPaths(key: string | null, paths: readonly string[]): boolean {
  if (!key) return false;
  try {
    const s = storage();
    if (!s) return false;
    s.setItem(key, JSON.stringify(paths.slice(0, PINNED_MENU_LIMIT)));
    return true;
  } catch {
    return false;
  }
}

// 侧栏与顶栏共用一份响应式状态
const currentKey = ref<string | null>(null);
const pinned: Ref<string[]> = ref([]);

export function usePinnedMenu() {
  function bind(key: string | null): void {
    if (key === currentKey.value) return;
    currentKey.value = key;
    pinned.value = readPinnedPaths(key);
  }
  function isPinned(path: string): boolean {
    return pinned.value.includes(path);
  }
  function toggle(path: string): void {
    if (!currentKey.value) return;
    const next = isPinned(path)
      ? pinned.value.filter((p) => p !== path)
      : [...pinned.value, path].slice(-PINNED_MENU_LIMIT);
    pinned.value = next;
    writePinnedPaths(currentKey.value, next);
  }
  return { pinned, currentKey, bind, isPinned, toggle };
}
