import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  factoryViewContext,
  type PlatformFactoryViewState,
} from '@/store/modules/platformFactoryContext';
import { installPlatformFactoryViewNetworkGuard } from '../platformFactoryViewNetwork';

const VIEW_KEY = 'cretas_platform_factory_view';

class FakeXHR {
  static instances: FakeXHR[] = [];
  method = '';
  url = '';
  async = true;
  status = 200;
  sent = false;
  deferResponse = false;
  headers = new Map<string, string>();
  listeners = new Map<string, Array<() => void>>();

  constructor() {
    FakeXHR.instances.push(this);
  }

  open(method: string, url: string, async = true): void {
    this.method = method;
    this.url = url;
    this.async = async;
    this.headers.clear();
    this.sent = false;
  }

  setRequestHeader(name: string, value: string): void {
    const existing = this.headers.get(name.toLowerCase());
    this.headers.set(name.toLowerCase(), existing ? `${existing}, ${value}` : value);
  }

  addEventListener(name: string, listener: () => void): void {
    this.listeners.set(name, [...(this.listeners.get(name) || []), listener]);
  }

  send(_body?: unknown): void {
    this.sent = true;
    if (this.deferResponse) return;
    for (const listener of this.listeners.get('loadend') || []) listener();
  }
}

function viewContext(overrides: Partial<PlatformFactoryViewState> = {}): PlatformFactoryViewState {
  return {
    factoryId: 'F006',
    factoryName: '六膳门',
    factoryType: 'FOOD_PROCESSING',
    accessToken: 'view-token',
    expiresIn: 300,
    expiresAt: Date.now() + 300_000,
    readOnly: true,
    ...overrides,
  };
}

function activateView(overrides: Partial<PlatformFactoryViewState> = {}): void {
  const value = viewContext(overrides);
  sessionStorage.setItem(VIEW_KEY, JSON.stringify(value));
  factoryViewContext.value = value;
}

describe('platform factory native network boundary', () => {
  let nativeFetch: ReturnType<typeof vi.fn>;
  let uninstall: (() => void) | undefined;

  beforeEach(() => {
    sessionStorage.clear();
    factoryViewContext.value = null;
    FakeXHR.instances = [];
    nativeFetch = vi.fn().mockResolvedValue({ status: 200, ok: true });
    vi.stubGlobal('fetch', nativeFetch);
    vi.stubGlobal('XMLHttpRequest', FakeXHR);
  });

  afterEach(() => {
    uninstall?.();
    uninstall = undefined;
    vi.unstubAllGlobals();
    sessionStorage.clear();
    factoryViewContext.value = null;
  });

  it('attaches the view token to same-origin business fetch and overrides caller casing', async () => {
    activateView();
    const request = new Request(`${window.location.origin}/api/mobile/F006/dashboard`, {
      headers: { Authorization: 'Bearer original' },
    });
    uninstall = installPlatformFactoryViewNetworkGuard();

    await fetch(request, { headers: { aUtHoRiZaTiOn: 'Bearer caller', 'X-Client-Type': 'web' } });

    expect(nativeFetch).toHaveBeenCalledTimes(1);
    const options = nativeFetch.mock.calls[0][1] as RequestInit;
    const headers = new Headers(options.headers);
    expect(headers.get('authorization')).toBe('Bearer view-token');
    expect(headers.get('x-factory-view-token')).toBe('true');
    expect(headers.get('x-client-type')).toBe('platform-factory-view');
    expect(nativeFetch.mock.calls[0][0]).toBe(request);
  });

  it('does not mistake /api/platformevil for a management endpoint', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    await fetch('/api/platformevil/status');

    const headers = new Headers(nativeFetch.mock.calls[0][1].headers);
    expect(headers.get('authorization')).toBe('Bearer view-token');
  });

  it('trusts only configured absolute API prefixes for cross-origin view requests', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test/api/mobile');
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    await fetch('https://api.example.test/api/mobile/F006/dashboard');
    await fetch('https://api.example.test/other/dashboard', {
      headers: { Authorization: 'Bearer original-platform-token' },
      credentials: 'include',
    });

    const trustedHeaders = new Headers(nativeFetch.mock.calls[0][1].headers);
    const arbitraryHeaders = new Headers(nativeFetch.mock.calls[1][1]?.headers);
    expect(trustedHeaders.get('authorization')).toBe('Bearer view-token');
    expect(arbitraryHeaders.get('authorization')).toBeNull();
    expect(nativeFetch.mock.calls[1][1]?.credentials).toBe('omit');
  });

  it('preserves exact platform management requests with the original caller headers', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    await fetch('/api/platform/factories', { headers: { Authorization: 'Bearer platform-token' } });

    const headers = new Headers(nativeFetch.mock.calls[0][1].headers);
    expect(headers.get('authorization')).toBe('Bearer platform-token');
    expect(headers.get('x-factory-view-token')).toBeNull();
    await fetch('/api/admin/role-permissions', { headers: { Authorization: 'Bearer platform-token' } });
    expect(new Headers(nativeFetch.mock.calls[1][1].headers).get('authorization')).toBe('Bearer platform-token');
    await expect(fetch('/api/admin/cache/purge', { method: 'POST' })).rejects.toThrow('业务写操作');
    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.open('POST', '/api/admin/cache/purge');
    expect(() => xhr.send()).toThrow('业务写操作');
    expect(xhr.sent).toBe(false);
  });

  it('fails closed for malformed and expired contexts without falling back to the platform fetch', async () => {
    sessionStorage.setItem(VIEW_KEY, '{malformed');
    uninstall = installPlatformFactoryViewNetworkGuard();
    await expect(fetch('/api/mobile/F006/dashboard')).rejects.toThrow('凭证无效');
    expect(nativeFetch).not.toHaveBeenCalled();

    sessionStorage.setItem(VIEW_KEY, JSON.stringify(viewContext({ expiresAt: Date.now() - 1 })));
    factoryViewContext.value = null;
    await expect(fetch('/api/mobile/F006/dashboard')).rejects.toThrow('凭证已过期');
    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it('denies external presigned writes and body-dependent stream POSTs', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    await expect(fetch('https://uploads.example.test/presigned', { method: 'PUT', body: 'bytes' }))
      .rejects.toThrow('外部上传');
    const stream = new ReadableStream<Uint8Array>({ start() {} });
    await expect(fetch('/api/mobile/F006/ai-intents/execute', {
      method: 'POST',
      body: stream as unknown as BodyInit,
    })).rejects.toThrow('业务写操作');
    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it('denies factory-user query POSTs that are not enabled for platform view', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    await expect(fetch('/api/mobile/F006/list-summary/inventory', {
      method: 'POST',
      body: JSON.stringify({ page: 1 }),
    })).rejects.toThrow('业务写操作');
    await expect(fetch('/api/mobile/F006/ai-intents/execute', {
      method: 'POST',
      body: JSON.stringify({
        intentCode: 'PURCHASER_WEEKLY_PLAN',
        userInput: '下周采购什么?',
      }),
    })).rejects.toThrow('业务写操作');

    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it('marks the view expired on native fetch 401', async () => {
    activateView();
    nativeFetch.mockResolvedValue({ status: 401, ok: false });
    const expired = vi.fn();
    window.addEventListener('cretas:factory-view-expired', expired);
    uninstall = installPlatformFactoryViewNetworkGuard();

    await fetch('/api/mobile/F006/dashboard');

    expect(expired).toHaveBeenCalledTimes(1);
    window.removeEventListener('cretas:factory-view-expired', expired);
  });

  it('guards XHR headers, supports synchronous calls and resets state on reuse', () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();

    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.open('GET', '/api/mobile/F006/dashboard', false);
    xhr.setRequestHeader('authorization', 'Bearer original');
    xhr.setRequestHeader('X-Client-Type', 'web');
    xhr.send();
    expect(xhr.sent).toBe(true);
    expect(xhr.headers.get('authorization')).toBe('Bearer view-token');
    expect(xhr.headers.get('x-client-type')).toBe('platform-factory-view');

    xhr.open('GET', '/api/platform/factories', true);
    xhr.setRequestHeader('Authorization', 'Bearer platform-token');
    xhr.send();
    expect(xhr.headers.get('authorization')).toBe('Bearer platform-token');

    xhr.open('GET', '/api/mobile/F006/dashboard', true);
    xhr.setRequestHeader('Authorization', 'Bearer old-again');
    xhr.send();
    expect(xhr.headers.get('authorization')).toBe('Bearer view-token');
  });

  it('does not send denied XHR writes and dispatches 401 for allowed view calls', () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();
    const expired = vi.fn();
    window.addEventListener('cretas:factory-view-expired', expired);

    const denied = new XMLHttpRequest() as unknown as FakeXHR;
    denied.open('POST', '/api/mobile/F006/orders');
    expect(() => denied.send('{}')).toThrow('业务写操作');
    expect(denied.sent).toBe(false);

    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.status = 401;
    xhr.open('GET', '/api/mobile/F006/dashboard', false);
    xhr.send();
    expect(expired).toHaveBeenCalledTimes(1);
    window.removeEventListener('cretas:factory-view-expired', expired);
  });

  it('restores native globals on uninstall', () => {
    const originalFetch = globalThis.fetch;
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalHeader = XMLHttpRequest.prototype.setRequestHeader;
    const originalSend = XMLHttpRequest.prototype.send;
    uninstall = installPlatformFactoryViewNetworkGuard();
    uninstall();
    uninstall = undefined;

    expect(globalThis.fetch).toBe(originalFetch);
    expect(XMLHttpRequest.prototype.open).toBe(originalOpen);
    expect(XMLHttpRequest.prototype.setRequestHeader).toBe(originalHeader);
    expect(XMLHttpRequest.prototype.send).toBe(originalSend);
  });

  it('keeps unsupported Python requests local instead of expiring a valid Java view', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();
    await expect(fetch('/smartbi-api/api/smartbi/gold/finance-summary')).rejects.toThrow('暂不提供数据分析服务');
    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.open('GET', '/smartbi-api/api/smartbi/gold/finance-summary');
    expect(() => xhr.send()).toThrow('暂不提供数据分析服务');
    expect(xhr.sent).toBe(false);
    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it('blocks same-origin non-API writes and nonstandard mutation methods before send', async () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();
    await expect(fetch('/upload', { method: 'POST', body: 'file' })).rejects.toThrow('业务写操作');
    await expect(fetch('/files', { method: 'PROPFIND' })).rejects.toThrow('业务写操作');
    for (const method of ['POST', 'PROPFIND']) {
      const xhr = new XMLHttpRequest() as unknown as FakeXHR;
      xhr.open(method, '/upload');
      expect(() => xhr.send('file')).toThrow('业务写操作');
      expect(xhr.sent).toBe(false);
    }
    expect(nativeFetch).not.toHaveBeenCalled();
  });

  it('handles a later asynchronous XHR 401 without removing its listener at send time', () => {
    activateView();
    uninstall = installPlatformFactoryViewNetworkGuard();
    const expired = vi.fn();
    window.addEventListener('cretas:factory-view-expired', expired);
    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.deferResponse = true;
    xhr.status = 0;
    xhr.open('GET', '/api/mobile/F006/dashboard', true);
    xhr.send();
    expect(expired).not.toHaveBeenCalled();
    xhr.status = 401;
    for (const listener of xhr.listeners.get('loadend') || []) listener();
    expect(expired).toHaveBeenCalledTimes(1);
    window.removeEventListener('cretas:factory-view-expired', expired);
  });

  it('preserves native external XHR credentials outside factory view', () => {
    uninstall = installPlatformFactoryViewNetworkGuard();
    const xhr = new XMLHttpRequest() as unknown as FakeXHR;
    xhr.open('GET', 'https://external.example/data');
    xhr.setRequestHeader('Authorization', 'Bearer external-credential');
    xhr.send();
    expect(xhr.headers.get('authorization')).toBe('Bearer external-credential');
  });
});

