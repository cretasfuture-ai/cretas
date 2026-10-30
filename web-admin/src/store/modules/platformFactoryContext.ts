import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { FactoryViewContext } from '@/api/platformFactoryMenus';

const STORAGE_KEY = 'cretas_platform_factory_view';

const FACTORY_VIEW_PAGE_PATHS = new Set([
  '/dashboard', '/production/plans', '/warehouse/materials',
  '/procurement/orders', '/sales/orders', '/quality/label-qc',
]);

export function isFactoryViewPageRoute(path: string): boolean {
  return FACTORY_VIEW_PAGE_PATHS.has(path.split(/[?#]/)[0]);
}

interface StoredContext extends FactoryViewContext {
  expiresAt: number;
}

function readStoredContext(): StoredContext | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<StoredContext>;
    if (typeof value.factoryId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value.factoryId)
      || typeof value.accessToken !== 'string' || !value.accessToken
      || typeof value.expiresAt !== 'number' || !Number.isFinite(value.expiresAt)
      || value.readOnly !== true) return null;
    return value as StoredContext;
  } catch {
    return null;
  }
}

function writeStoredContext(value: StoredContext | null): void {
  if (value) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  else sessionStorage.removeItem(STORAGE_KEY);
}

export function getStoredFactoryViewContext(): StoredContext | null {
  return readStoredContext();
}

function hasStoredFactoryViewContext(): boolean {
  return !!sessionStorage.getItem(STORAGE_KEY);
}

export function isFactoryViewManagementRequest(url: string, method = 'GET'): boolean {
  const path = requestPath(url);
  const verb = method.toUpperCase();
  return path === '/api/platform' || path.startsWith('/api/platform/')
    || (verb === 'GET' && path === '/api/admin/role-permissions')
    || (verb === 'POST' && ['/api/mobile/auth/logout', '/api/mobile/auth/refresh',
      '/api/mobile/auth/refresh-token', '/api/mobile/auth/web-credential'].includes(path));
}

function requestPath(url: string): string {
  try { return new URL(url, window.location.origin).pathname; }
  catch { return ''; }
}

// Factory-only query handlers may resolve numeric factory-user identities or
// persist analysis caches. They have not been enabled for platform view tokens.
export function isFactoryViewReadonlyPost(_url: string, _body?: unknown): boolean {
  return false;
}

/** Reactive singleton shared by auth/permission stores and the Pinia store. */
export const factoryViewContext = ref<StoredContext | null>(readStoredContext());

export function resetFactoryViewContext(): void {
  factoryViewContext.value = null;
  writeStoredContext(null);
}

/** Returns the short-lived token for business calls, or an error when it expired. */
export function factoryViewTokenForRequest(url: string, method = 'GET'): string | null {
  const context = factoryViewContext.value || readStoredContext();
  if (isFactoryViewManagementRequest(url, method)) return null;
  if (!context && hasStoredFactoryViewContext()) {
    throw new Error('工厂查看凭证无效，请返回平台后重新进入');
  }
  if (!context) return null;
  if (context.expiresAt <= Date.now()) {
    throw new Error('工厂查看凭证已过期，请返回平台后重新进入');
  }
  return context.accessToken;
}

export function isFactoryViewRequest(url: string, method = 'GET'): boolean {
  const context = factoryViewContext.value || readStoredContext();
  return (!!context || hasStoredFactoryViewContext()) && !isFactoryViewManagementRequest(url, method);
}

export const usePlatformFactoryContextStore = defineStore('platformFactoryContext', () => {
  const context = factoryViewContext;

  const isViewingFactory = computed(() => !!context.value);
  const factoryId = computed(() => context.value?.factoryId || '');
  const factoryName = computed(() => context.value?.factoryName || factoryId.value);
  const readOnly = computed(() => context.value?.readOnly === true);
  const expiresAt = computed(() => context.value?.expiresAt || 0);
  const expiresIn = computed(() => Math.max(0, Math.ceil((expiresAt.value - Date.now()) / 1000)));

  async function enterFactoryView(factoryIdToView: string): Promise<StoredContext> {
    const { createFactoryViewContext } = await import('@/api/platformFactoryMenus');
    const response = await createFactoryViewContext(factoryIdToView);
    if (!response.success || !response.data?.accessToken || response.data.readOnly !== true
      || response.data.factoryId !== factoryIdToView
      || !Number.isFinite(Number(response.data.expiresIn)) || Number(response.data.expiresIn) <= 0
      || Number(response.data.expiresIn) > 900) {
      throw new Error(response.message || '无法进入工厂只读查看');
    }
    const expiresInSeconds = Math.min(900, Math.max(1, Number(response.data.expiresIn)));
    const next: StoredContext = {
      ...response.data,
      expiresIn: expiresInSeconds,
      expiresAt: Date.now() + expiresInSeconds * 1000,
      readOnly: true,
    };
    context.value = next;
    writeStoredContext(next);
    window.dispatchEvent(new CustomEvent('cretas:factory-view-changed'));
    return next;
  }

  function leaveFactoryView(): void {
    context.value = null;
    writeStoredContext(null);
    window.dispatchEvent(new CustomEvent('cretas:factory-view-changed'));
  }

  function markExpired(): void {
    const current = context.value || readStoredContext();
    if (!current) return;
    context.value = { ...current, expiresAt: 0, expiresIn: 0 };
    writeStoredContext(context.value);
    window.dispatchEvent(new CustomEvent('cretas:factory-view-expired'));
  }

  window.addEventListener('cretas:factory-view-expired', () => {
    const current = readStoredContext();
    if (current) {
      context.value = { ...current, expiresAt: 0, expiresIn: 0 };
      writeStoredContext(context.value);
    }
  });

  return {
    context,
    isViewingFactory,
    factoryId,
    factoryName,
    readOnly,
    expiresAt,
    expiresIn,
    enterFactoryView,
    leaveFactoryView,
    markExpired,
  };
});

export type { StoredContext as PlatformFactoryViewState };
