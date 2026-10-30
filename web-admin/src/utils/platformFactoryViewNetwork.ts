import {
  factoryViewContext,
  getStoredFactoryViewContext,
  isFactoryViewReadonlyPost,
  isFactoryViewManagementRequest,
} from '@/store/modules/platformFactoryContext';

const VIEW_STORAGE_KEY = 'cretas_platform_factory_view';
const VIEW_EXPIRED_EVENT = 'cretas:factory-view-expired';
const AUTH_HEADER_NAMES = new Set(['authorization', 'x-factory-view-token', 'x-client-type']);
const READ_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

type RequestInput = string | URL | Request;
type FetchInit = RequestInit;

interface ViewState {
  kind: 'none' | 'active' | 'expired' | 'invalid';
  token?: string;
}

interface RequestDetails {
  url: URL;
  method: string;
  body: unknown;
  hasBodyOverride: boolean;
}

interface DeferredHeader {
  name: string;
  value: string;
}

interface XhrState {
  method: string;
  url: URL;
  deferredAuthHeaders: DeferredHeader[];
  sent: boolean;
  viewRequest: boolean;
  expiredDispatched: boolean;
  responseListener?: () => void;
}

function hasPathBoundary(pathname: string, prefix: string): boolean {
  const normalized = prefix.length > 1 ? prefix.replace(/\/$/, '') : prefix;
  if (normalized === '/') return pathname.startsWith('/');
  return pathname === normalized || pathname.startsWith(`${normalized}/`);
}

function getLocationOrigin(): string {
  return typeof window !== 'undefined' && window.location?.origin
    ? window.location.origin
    : 'http://localhost';
}

function canonicalUrl(input: RequestInput | string): URL | null {
  try {
    const raw = typeof input === 'string' || input instanceof URL
      ? String(input)
      : input.url;
    return new URL(raw, getLocationOrigin());
  } catch {
    return null;
  }
}

function viewStoragePresent(): boolean {
  try {
    return sessionStorage.getItem(VIEW_STORAGE_KEY) !== null;
  } catch {
    return true;
  }
}

function validContext(value: unknown): value is {
  factoryId: string;
  accessToken: string;
  expiresAt: number;
  readOnly: true;
} {
  if (!value || typeof value !== 'object') return false;
  const context = value as Record<string, unknown>;
  return typeof context.factoryId === 'string' && context.factoryId.trim().length > 0
    && typeof context.accessToken === 'string' && context.accessToken.length > 0
    && typeof context.expiresAt === 'number' && Number.isFinite(context.expiresAt)
    && context.readOnly === true;
}

function readViewState(): ViewState {
  const live = factoryViewContext.value;
  const stored = live || getStoredFactoryViewContext();
  if (!live && !viewStoragePresent()) return { kind: 'none' };
  if (!validContext(stored)) return { kind: 'invalid' };
  if (stored.expiresAt <= Date.now()) return { kind: 'expired' };
  return { kind: 'active', token: stored.accessToken };
}

function isSameOrigin(url: URL): boolean {
  return url.origin === getLocationOrigin();
}

function configuredAbsolutePrefix(raw: unknown): { origin: string; pathname: string } | null {
  if (typeof raw !== 'string' || !/^https?:\/\//i.test(raw)) return null;
  try {
    const base = new URL(raw);
    return { origin: base.origin, pathname: base.pathname || '/' };
  } catch {
    return null;
  }
}

function isTrustedConfiguredApi(url: URL): boolean {
  const prefixes = [
    configuredAbsolutePrefix(import.meta.env.VITE_API_BASE_URL),
    configuredAbsolutePrefix(import.meta.env.VITE_SMARTBI_URL),
  ].filter((value): value is { origin: string; pathname: string } => !!value);
  return prefixes.some((prefix) => url.origin === prefix.origin && hasPathBoundary(url.pathname, prefix.pathname));
}

function isPythonApi(url: URL): boolean {
  if (isSameOrigin(url) && hasPathBoundary(url.pathname, '/smartbi-api')) return true;
  const configured = configuredAbsolutePrefix(import.meta.env.VITE_SMARTBI_URL);
  return !!configured && url.origin === configured.origin
    && !(configured.pathname === '/' && isSameOrigin(url))
    && hasPathBoundary(url.pathname, configured.pathname);
}

function isBusinessApi(url: URL): boolean {
  if (isSameOrigin(url)) {
    return url.pathname === '/api'
      || url.pathname.startsWith('/api/')
      || url.pathname === '/smartbi-api'
      || url.pathname.startsWith('/smartbi-api/');
  }
  return isTrustedConfiguredApi(url);
}

function isPlatformManagementRequest(url: URL, method = 'GET'): boolean {
  return isFactoryViewManagementRequest(url.toString(), method)
    && (isSameOrigin(url) || isTrustedConfiguredApi(url));
}

function isWrite(method: string): boolean {
  return !READ_METHODS.has(method.toUpperCase());
}

function guardError(state: ViewState): Error {
  return new Error(state.kind === 'expired'
    ? '工厂查看凭证已过期，请返回平台后重新进入'
    : '工厂查看凭证无效，请返回平台后重新进入');
}

function dispatchViewExpired(): void {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent(VIEW_EXPIRED_EVENT));
}

function isHeaderName(name: string): boolean {
  return AUTH_HEADER_NAMES.has(name.trim().toLowerCase());
}

function mergeHeaders(input: RequestInput, init?: FetchInit): Headers {
  const headers = typeof Request !== 'undefined' && input instanceof Request
    ? new Headers(input.headers)
    : new Headers();
  if (init?.headers) {
    new Headers(init.headers).forEach((value, name) => headers.set(name, value));
  }
  return headers;
}

function withViewHeaders(headers: Headers, token: string): Headers {
  headers.delete('authorization');
  headers.delete('x-factory-view-token');
  headers.set('Authorization', `Bearer ${token}`);
  headers.set('X-Factory-View-Token', 'true');
  headers.set('X-Client-Type', 'platform-factory-view');
  return headers;
}

function withoutExternalCredentials(input: RequestInput, init?: FetchInit): RequestInit {
  const headers = mergeHeaders(input, init);
  headers.delete('authorization');
  headers.delete('x-factory-view-token');
  return { ...init, headers, credentials: 'omit' };
}

function requestDetails(input: RequestInput, init?: FetchInit): RequestDetails | null {
  const url = canonicalUrl(input);
  if (!url) return null;
  const request = typeof Request !== 'undefined' && input instanceof Request ? input : null;
  const method = String(init?.method ?? request?.method ?? 'GET').toUpperCase();
  const hasBodyOverride = !!init && Object.prototype.hasOwnProperty.call(init, 'body');
  return {
    url,
    method,
    body: hasBodyOverride ? init?.body : undefined,
    hasBodyOverride,
  };
}

function readonlyPostAllowed(url: URL, body: unknown, bodyWasSupplied: boolean): boolean {
  // A stream-backed Request cannot be inspected. Fixed, server-confirmed query
  // routes are safe without reading it; body-dependent intent routes are not.
  if (!bodyWasSupplied) return isFactoryViewReadonlyPost(url.toString());
  if (typeof ReadableStream !== 'undefined' && body instanceof ReadableStream) {
    return isFactoryViewReadonlyPost(url.toString());
  }
  return isFactoryViewReadonlyPost(url.toString(), body);
}

function validateViewRequest(details: RequestDetails, state: ViewState): void {
  const management = isPlatformManagementRequest(details.url, details.method);
  const business = isBusinessApi(details.url);
  if (management) return;
  if (!business) {
    if (isWrite(details.method)) throw new Error(isSameOrigin(details.url)
      ? '工厂只读查看不允许业务写操作'
      : '工厂只读查看不允许外部上传或业务写操作');
    return;
  }
  if (state.kind !== 'active') throw guardError(state);
  if (isPythonApi(details.url)) throw new Error('平台只读查看暂不提供数据分析服务，请使用工厂账号');
  if (isWrite(details.method)
    && !(details.method === 'POST' && readonlyPostAllowed(details.url, details.body, details.hasBodyOverride))) {
    throw new Error('工厂只读查看不允许业务写操作');
  }
}

function fetchWithGuard(
  originalFetch: typeof fetch,
): typeof fetch {
  return function guardedFetch(this: unknown, input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const details = requestDetails(input as RequestInput, init);
    if (!details) return Promise.reject(new Error('请求地址无效，工厂查看已拒绝'));
    const state = readViewState();
    if (state.kind === 'none') return originalFetch.call(this, input, init);

    try {
      validateViewRequest(details, state);
    } catch (error) {
      return Promise.reject(error);
    }

    const management = isPlatformManagementRequest(details.url, details.method);
    const business = isBusinessApi(details.url);
    if (!business && !isSameOrigin(details.url)) {
      return originalFetch.call(this, input, withoutExternalCredentials(input as RequestInput, init));
    }
    if (!business || management || state.kind !== 'active') {
      return originalFetch.call(this, input, init);
    }

    const headers = withViewHeaders(mergeHeaders(input as RequestInput, init), state.token!);
    const nextInit: RequestInit = { ...init, headers };
    const response = originalFetch.call(this, input, nextInit);
    return Promise.resolve(response).then((result) => {
      if (result.status === 401) dispatchViewExpired();
      return result;
    });
  } as typeof fetch;
}

function xhrDetails(state: XhrState, body: unknown): RequestDetails {
  return {
    url: state.url,
    method: state.method,
    body,
    hasBodyOverride: body !== undefined,
  };
}

/**
 * Install the native-network boundary used by pages that still call fetch/XHR
 * directly. It is intentionally reversible for tests and hot-reload recovery.
 */
export function installPlatformFactoryViewNetworkGuard(): () => void {
  const originalFetch = globalThis.fetch;
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
  const originalSend = XMLHttpRequest.prototype.send;
  const states = new WeakMap<XMLHttpRequest, XhrState>();

  const guardedFetch = fetchWithGuard(originalFetch);
  globalThis.fetch = guardedFetch;

  XMLHttpRequest.prototype.open = (function guardedOpen(
    this: XMLHttpRequest,
    method: string,
    url: string | URL,
    ...rest: unknown[]
  ): void {
    const previous = states.get(this);
    if (previous?.responseListener && typeof this.removeEventListener === 'function') {
      this.removeEventListener('loadend', previous.responseListener);
    }
    const parsed = canonicalUrl(String(url));
    if (parsed) {
      states.set(this, {
        method: String(method || 'GET').toUpperCase(),
        url: parsed,
        deferredAuthHeaders: [],
        sent: false,
        viewRequest: false,
        expiredDispatched: false,
      });
    }
    (originalOpen as (...args: unknown[]) => void).apply(this, [method, url, ...rest]);
  }) as typeof originalOpen;

  XMLHttpRequest.prototype.setRequestHeader = (function guardedSetRequestHeader(
    this: XMLHttpRequest,
    name: string,
    value: string,
  ): void {
    const state = states.get(this);
    if (state && !state.sent && isHeaderName(name)) {
      state.deferredAuthHeaders.push({ name, value });
      return;
    }
    originalSetRequestHeader.call(this, name, value);
  }) as typeof originalSetRequestHeader;

  XMLHttpRequest.prototype.send = (function guardedSend(this: XMLHttpRequest, body?: Document | XMLHttpRequestBodyInit | null): void {
    const state = states.get(this);
    if (!state) {
      originalSend.call(this, body);
      return;
    }
    const viewState = readViewState();
    const details = xhrDetails(state, body);
    if (viewState.kind !== 'none') validateViewRequest(details, viewState);

    const management = isPlatformManagementRequest(state.url, state.method);
    const business = isBusinessApi(state.url);
    if (business && !management && viewState.kind === 'active') {
      state.viewRequest = true;
      originalSetRequestHeader.call(this, 'Authorization', `Bearer ${viewState.token!}`);
      originalSetRequestHeader.call(this, 'X-Factory-View-Token', 'true');
      originalSetRequestHeader.call(this, 'X-Client-Type', 'platform-factory-view');
    } else if (viewState.kind !== 'none' && !business && !isSameOrigin(state.url)) {
      // Never carry a platform bearer/cookie to an arbitrary external origin.
      if ('withCredentials' in this) this.withCredentials = false;
    } else {
      for (const header of state.deferredAuthHeaders) {
        originalSetRequestHeader.call(this, header.name, header.value);
      }
    }

    state.sent = true;
    const check401 = () => {
      if (!state.viewRequest) return;
      try {
        if (this.status === 401 && !state.expiredDispatched) {
          state.expiredDispatched = true;
          dispatchViewExpired();
        }
      } catch {
        // The status is not readable until the response has started.
      } finally {
        if (typeof this.removeEventListener === 'function') {
          this.removeEventListener('loadend', check401);
        }
      }
    };
    state.responseListener = check401;
    if (state.viewRequest && typeof this.addEventListener === 'function') {
      this.addEventListener('loadend', check401, { once: true });
    }
    const result = originalSend.call(this, body);
    return result;
  }) as typeof originalSend;

  let uninstalled = false;
  return () => {
    if (uninstalled) return;
    uninstalled = true;
    if (globalThis.fetch === guardedFetch) globalThis.fetch = originalFetch;
    if (XMLHttpRequest.prototype.open !== originalOpen) XMLHttpRequest.prototype.open = originalOpen;
    if (XMLHttpRequest.prototype.setRequestHeader !== originalSetRequestHeader) {
      XMLHttpRequest.prototype.setRequestHeader = originalSetRequestHeader;
    }
    if (XMLHttpRequest.prototype.send !== originalSend) XMLHttpRequest.prototype.send = originalSend;
  };
}

