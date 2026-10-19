import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, AxiosHeaders, type AxiosAdapter } from 'axios';
import {
  installClientErrorReporter,
  configureClientErrorReporter,
  reportClientError,
  reportRequestFailure,
  resetClientErrorReporterForTests,
} from '../clientErrorReporter';
import request from '../../api/request';

describe('client error telemetry', () => {
  const fetchMock = vi.fn<typeof fetch>();
  const readonlyWindow = window as Window & { __CRETAS_READONLY_HARNESS__?: unknown };

  beforeEach(() => {
    delete readonlyWindow.__CRETAS_READONLY_HARNESS__;
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockClear();
    fetchMock.mockResolvedValue(new Response('{}', { status: 202 }));
    configureClientErrorReporter(() => null);
    resetClientErrorReporterForTests();
  });

  it('sends only bounded metadata for an authenticated factory', async () => {
    await reportClientError({
      category: 'request_failure',
      route: '/orders/list?secret=form-value#private',
      method: 'get',
      status: 500,
      errorCode: 'SERVER_ERROR',
      parentCorrelationId: 'corr-123',
      source: 'web-admin',
    });

    // Direct reporter tests configure a trusted auth getter just as main.ts does.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('uses the auth getter, strips query values, and does not recurse on collector failure', async () => {
    const app = { config: {} } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true, accessToken: 'token-value' }));
    fetchMock.mockRejectedValue(new Error('collector failed'));

    await reportClientError({
      category: 'request_failure',
      route: '/orders/list?password=private',
      method: 'GET',
      status: 502,
      errorCode: 'UPSTREAM_DOWN',
      parentCorrelationId: 'corr-123',
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/mobile/F006/client-errors');
    const body = JSON.parse(String((init as RequestInit).body));
    expect(body.route).toBe('/orders/list');
    expect(JSON.stringify(body)).not.toContain('private');
    expect(JSON.stringify(body)).not.toContain('token-value');
    expect(body).not.toHaveProperty('message');
    expect(body).not.toHaveProperty('payload');
    expect(body).not.toHaveProperty('headers');
    expect(body.eventId).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/);

    await reportClientError({ category: 'request_failure', route: '/orders/list', method: 'GET', status: 502, errorCode: 'UPSTREAM_DOWN', parentCorrelationId: 'corr-123' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('hooks Vue and browser failures without exposing raw error text', async () => {
    const app = { config: {} } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true }));
    const error = new Error('password=secret');
    error.stack = 'Error: password=secret\n at fn (C:\\private\\app.js:12:8)';
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    app.config.errorHandler?.(error, null, 'render function');
    window.dispatchEvent(new ErrorEvent('error', { error, filename: 'https://host/app.js?token=secret', lineno: 12, colno: 8 }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    consoleError.mockRestore();

    expect(fetchMock).toHaveBeenCalled();
    const requests = fetchMock.mock.calls.map(([, init]) => JSON.parse(String((init as RequestInit).body)));
    expect(requests.some((body) => body.category === 'vue')).toBe(true);
    expect(requests.some((body) => body.category === 'window_error')).toBe(true);
    expect(requests.every((body) => !JSON.stringify(body).includes('password=secret'))).toBe(true);
    expect(requests.some((body) => body.file === 'app.js' && body.line === 12 && body.column === 8)).toBe(true);
  });

  it('parses only real script frames and never treats an error message as a file', async () => {
    const app = { config: {} } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true }));
    const messageOnly = new Error('private-customer:123:4');
    messageOnly.stack = 'Error: private-customer:123:4';
    app.config.errorHandler?.(messageOnly, null, 'render function');

    const withFrame = new Error('private-customer:123:4');
    withFrame.stack = 'Error: private-customer:123:4\n    at render (https://host/app.js:12:8)';
    app.config.errorHandler?.(withFrame, null, 'render function');

    const firefox = new Error('private-customer:123:4');
    firefox.stack = 'render@https://host/component.ts:9:2';
    window.dispatchEvent(new ErrorEvent('error', { error: firefox }));
    await new Promise((resolve) => setTimeout(resolve, 0));

    const requests = fetchMock.mock.calls.map(([, init]) => JSON.parse(String((init as RequestInit).body)));
    const vueBodies = requests.filter((body) => body.category === 'vue');
    expect(vueBodies.some((body) => body.file === undefined && body.line === undefined)).toBe(true);
    expect(vueBodies.some((body) => body.file === 'app.js' && body.line === 12 && body.column === 8)).toBe(true);
    expect(requests.some((body) => body.category === 'window_error'
      && body.file === 'component.ts' && body.line === 9 && body.column === 2)).toBe(true);
    expect(requests.every((body) => !JSON.stringify(body).includes('private-customer'))).toBe(true);
  });

  it('deduplicates an Axios failure and its unhandled ApiError follow-up', async () => {
    const app = { config: {} } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true }));
    reportRequestFailure({
      config: { url: '/F006/orders', method: 'GET', headers: { 'X-Correlation-ID': 'corr-1' } },
      response: { status: 503, data: { errorCode: 'UPSTREAM_DOWN' }, headers: { 'X-Correlation-ID': 'corr-response' } },
    });
    await reportClientError({ category: 'request_failure', route: '/F006/orders', method: 'GET', status: 503, errorCode: 'UPSTREAM_DOWN', parentCorrelationId: 'corr-different' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const body = JSON.parse(String((fetchMock.mock.calls[0][1] as RequestInit).body));
    expect(body.parentCorrelationId).toBe('corr-response');
  });

  it('adds a RequestAuditSupport-compatible correlation ID to shared Axios requests', async () => {
    const seen: { value?: unknown } = {};
    const adapter: AxiosAdapter = async (config) => {
      seen.value = config.headers?.get?.('X-Correlation-ID') || config.headers?.['X-Correlation-ID'];
      return {
        data: { success: true, data: {} },
        status: 200,
        statusText: 'OK',
        headers: new AxiosHeaders(),
        config,
      };
    };
    await request.get('/F006/health', { adapter });
    expect(seen.value).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/);
  });

  it('does not duplicate an interceptor-created ApiError through unhandledrejection', async () => {
    const app = { config: {} } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true }));
    const adapter: AxiosAdapter = async (config) => ({
      data: { success: false, message: 'private form value', code: 'UPSTREAM_DOWN' },
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      config,
    });
    const reason = await request.get('/F006/orders', { adapter }).catch((error: unknown) => error);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const event = new Event('unhandledrejection');
    Object.defineProperty(event, 'reason', { value: reason });
    window.dispatchEvent(event);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not send while the auth store is logged out', async () => {
    configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: false }));
    await reportClientError({ category: 'window_error', route: '/orders/list' });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('suppresses pending microtask reports after the strict read-only marker activates', async () => {
    configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: true }));
    const pending = Promise.resolve().then(() => reportClientError({
      category: 'window_error',
      route: '/orders/list',
    }));
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = true;
    await pending;
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('suppresses installed hooks in readonly mode while retaining the existing Vue handler', async () => {
    const previous = vi.fn();
    const app = { config: { errorHandler: previous } } as Parameters<typeof installClientErrorReporter>[0];
    installClientErrorReporter(app, () => ({ factoryId: 'F006', isAuthenticated: true }));
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = true;
    const error = new Error('local diagnostic');
    app.config.errorHandler?.(error, null, 'render function');
    window.dispatchEvent(new ErrorEvent('error', { error }));
    reportRequestFailure({ config: { url: '/F006/customers', method: 'get' }, response: { status: 403 } });
    await Promise.resolve();
    expect(previous).toHaveBeenCalledWith(error, null, 'render function');
    expect(fetchMock).not.toHaveBeenCalled();
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = false;
    await reportClientError({ category: 'request_failure', route: '/F006/customers', method: 'GET', status: 403 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('rechecks the marker immediately before sending if authentication activates readonly mode', async () => {
    configureClientErrorReporter(() => {
      readonlyWindow.__CRETAS_READONLY_HARNESS__ = true;
      return { factoryId: 'F006', isAuthenticated: true };
    });
    await reportClientError({ category: 'window_error', route: '/production/plans' });
    expect(fetchMock).not.toHaveBeenCalled();
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = false;
    configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: true }));
    await reportClientError({ category: 'window_error', route: '/production/plans' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each([
    ['undefined', undefined],
    ['false', false],
    ['string', 'true'],
  ])('keeps normal reporting enabled when the read-only marker is %s', async (_label, marker) => {
    if (marker === undefined) {
      delete readonlyWindow.__CRETAS_READONLY_HARNESS__;
    } else {
      readonlyWindow.__CRETAS_READONLY_HARNESS__ = marker;
    }
    configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: true }));
    await reportClientError({ category: 'window_error', route: `/orders/${String(marker)}` });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps cancelled Axios requests out of telemetry', async () => {
    configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: true }));
    const adapter: AxiosAdapter = async (config) => {
      throw new AxiosError('canceled', AxiosError.ERR_CANCELED, config);
    };
    await expect(request.get('/F006/orders', { adapter })).rejects.toMatchObject({ code: AxiosError.ERR_CANCELED });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('times out a stalled collector without surfacing or recursing', async () => {
    vi.useFakeTimers();
    try {
      configureClientErrorReporter(() => ({ factoryId: 'F006', isAuthenticated: true }));
      fetchMock.mockImplementation((_input, init) => new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      }));
      const pending = reportClientError({ category: 'window_error', route: '/orders/list' });
      await vi.advanceTimersByTimeAsync(3_000);
      await pending;
      expect(fetchMock).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
