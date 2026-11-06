import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export type NavigationVisibilityIdentity = {
  factoryId: string;
  role: string;
  factoryType: string;
  isPlatform: boolean;
};

export type NavigationVisibilitySnapshot = NavigationVisibilityIdentity & {
  paths: readonly string[];
};

export type NavigationVisibilityStatus = 'unavailable' | 'loading' | 'ready' | 'error';

function sameIdentity(a: NavigationVisibilityIdentity, b: NavigationVisibilityIdentity): boolean {
  return a.factoryId === b.factoryId
    && a.role === b.role
    && a.factoryType === b.factoryType
    && a.isPlatform === b.isPlatform;
}

export const useNavigationVisibilityStore = defineStore('navigationVisibility', () => {
  const current = ref<NavigationVisibilitySnapshot | null>(null);
  const status = ref<NavigationVisibilityStatus>('unavailable');
  let loadGeneration = 0;

  function publish(snapshot: NavigationVisibilitySnapshot): void {
    current.value = {
      ...snapshot,
      paths: [...new Set(snapshot.paths)],
    };
    status.value = 'ready';
  }

  function invalidate(): void {
    current.value = null;
    status.value = 'loading';
  }

  function beginLoading(): number {
    const token = ++loadGeneration;
    invalidate();
    return token;
  }

  function isCurrentLoad(token: number): boolean {
    return token === loadGeneration;
  }

  function markReadyIfCurrent(token: number): boolean {
    if (!isCurrentLoad(token)) return false;
    markReady();
    return true;
  }

  function markUnavailableIfCurrent(token: number): boolean {
    if (!isCurrentLoad(token)) return false;
    markUnavailable();
    return true;
  }

  function markErrorIfCurrent(token: number): boolean {
    if (!isCurrentLoad(token)) return false;
    markError();
    return true;
  }

  function markReady(): void {
    status.value = 'ready';
  }

  function markUnavailable(): void {
    current.value = null;
    status.value = 'unavailable';
  }

  function markError(): void {
    current.value = null;
    status.value = 'error';
  }

  function clear(): void {
    current.value = null;
    status.value = 'unavailable';
  }

  function isPathVisible(path: string, identity: NavigationVisibilityIdentity): boolean {
    const snapshot = current.value;
    return status.value === 'ready'
      && snapshot !== null
      && sameIdentity(snapshot, identity)
      && snapshot.paths.includes(path);
  }

  return {
    snapshot: computed(() => current.value),
    status: computed(() => status.value),
    publish,
    invalidate,
    beginLoading,
    isCurrentLoad,
    markReadyIfCurrent,
    markUnavailableIfCurrent,
    markErrorIfCurrent,
    markReady,
    markUnavailable,
    markError,
    clear,
    isPathVisible,
  };
});
