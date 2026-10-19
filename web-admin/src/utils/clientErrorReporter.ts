import type { App } from 'vue';

export type ClientErrorCategory = 'vue' | 'window_error' | 'unhandled_rejection' | 'request_failure';

export interface ClientErrorAuthContext {
  factoryId: string;
  isAuthenticated: boolean;
  accessToken?: string | null;
}

export interface ClientErrorMetadata {
  category: ClientErrorCategory;
  route?: string;
  method?: string;
  status?: number;
  errorCode?: string;
  parentCorrelationId?: string;
  source?: string;
  file?: string;
  line?: number;
  column?: number;
}

export type ClientErrorAuthGetter = () => ClientErrorAuthContext | null | undefined;

const COLLECTOR_SUFFIX = '/client-errors';
const CORRELATION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/;
const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/;
const MAX_DEDUP_KEYS = 200;
const DEDUP_MS = 5_000;
const GLOBAL_WINDOW_MS = 60_000;
const GLOBAL_MAX = 20;
const FACTORY_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

let authGetter: ClientErrorAuthGetter | null = null;
let installed = false;
let windowStartedAt = 0;
let windowCount = 0;
const seen = new Map<string, number>();
let reportedErrors = new WeakSet<object>();

function isReadonlyHarness(): boolean {
  return (window as Window & { __CRETAS_READONLY_HARNESS__?: unknown }).__CRETAS_READONLY_HARNESS__ === true;
}

export function configureClientErrorReporter(getAuth: ClientErrorAuthGetter): void {
  authGetter = getAuth;
}

export function installClientErrorReporter(
  app: App,
  getAuth: ClientErrorAuthGetter,
): void {
  configureClientErrorReporter(getAuth);
  const previous = app.config.errorHandler;
  app.config.errorHandler = (error, instance, info) => {
    void reportClientError({
      category: 'vue',
      source: 'web-admin',
      route: currentRoute(),
      ...sourceLocation(error),
    });
    if (previous) {
      previous(error, instance, info);
    } else {
      // Keep Vue's normal local diagnostics when no application handler was installed.
      // The reporter body remains metadata-only and never includes this error object.
      console.error('[cretas] Vue error:', error);
    }
  };

  if (installed) return;
  installed = true;

  window.addEventListener('error', (event: ErrorEvent) => {
    void reportClientError({
      category: 'window_error',
      source: 'web-admin',
      route: currentRoute(),
      ...sourceLocation(event.error, event.filename, event.lineno, event.colno),
    });
  });
  window.addEventListener('unhandledrejection', (event: PromiseRejectionEvent) => {
    // Axios failures are reported at the interceptor boundary. ApiError is the normalized
    // rejection that callers may leave unhandled; reporting it again adds no evidence.
    if (wasClientErrorReported(event.reason)) return;
    void reportClientError({
      category: 'unhandled_rejection',
      source: 'web-admin',
      route: currentRoute(),
      ...sourceLocation(event.reason),
    });
  });
}

/** Report only safe technical metadata; error objects and messages never enter the body. */
export async function reportClientError(metadata: ClientErrorMetadata): Promise<void> {
  if (isReadonlyHarness()) return;

  const auth = readAuth();
  if (isReadonlyHarness()) return;
  if (!auth || !auth.factoryId || typeof fetch !== 'function') return;

  const normalized = normalizeMetadata(metadata);
  const dedupKey = [normalized.category, normalized.route, normalized.method,
    normalized.status, normalized.errorCode,
    normalized.file, normalized.line, normalized.column].join('|');
  const now = Date.now();
  pruneSeen(now);
  if ((seen.get(dedupKey) ?? 0) + DEDUP_MS > now) return;
  if (now - windowStartedAt >= GLOBAL_WINDOW_MS) {
    windowStartedAt = now;
    windowCount = 0;
  }
  if (windowCount >= GLOBAL_MAX) return;

  const eventId = newEventId();
  const correlationId = isSafeCorrelation(normalized.parentCorrelationId)
    ? normalized.parentCorrelationId as string
    : eventId;
  const body = {
    eventId,
    category: normalized.category,
    route: normalized.route || currentRoute(),
    method: normalized.method,
    status: normalized.status,
    errorCode: normalized.errorCode,
    parentCorrelationId: normalized.parentCorrelationId,
    source: normalized.source || 'web-admin',
    file: normalized.file,
    line: normalized.line,
    column: normalized.column,
  };

  if (isReadonlyHarness()) return;
  seen.set(dedupKey, now);
  windowCount += 1;

  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 3_000);
  try {
    await fetch(`${collectorBase(auth.factoryId)}${COLLECTOR_SUFFIX}`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'X-Client-Type': 'web',
        'X-Correlation-ID': correlationId,
        ...(auth.accessToken ? { Authorization: `Bearer ${auth.accessToken}` } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
  } catch {
    // Diagnostics must never recurse into the request pipeline or affect the UI.
  } finally {
    window.clearTimeout(timeout);
  }
}

export function reportRequestFailure(error: unknown): void {
  const candidate = error as {
    config?: { url?: unknown; method?: unknown; headers?: unknown };
    response?: { status?: unknown; data?: unknown; headers?: unknown };
  };
  const data = candidate.response?.data as Record<string, unknown> | undefined;
  const code = typeof data?.errorCode === 'string' ? data.errorCode
    : typeof data?.code === 'string' ? data.code : undefined;
  const responseParent = safeCorrelation(headerValue(candidate.response?.headers, 'X-Correlation-ID'))
    || safeCorrelation(headerValue(candidate.response?.headers, 'x-correlation-id'));
  const requestParent = safeCorrelation(headerValue(candidate.config?.headers, 'X-Correlation-ID'))
    || safeCorrelation(headerValue(candidate.config?.headers, 'x-correlation-id'));
  markClientErrorReported(error);
  void reportClientError({
    category: 'request_failure',
    route: safePath(candidate.config?.url),
    method: safeMethod(candidate.config?.method),
    status: safeStatus(candidate.response?.status),
    errorCode: safeCode(code),
    parentCorrelationId: responseParent || requestParent,
    source: 'web-admin',
  });
}

function readAuth(): ClientErrorAuthContext | null {
  try {
    const value = authGetter?.();
    if (value?.isAuthenticated === true && FACTORY_PATTERN.test(value.factoryId)) return value;
  } catch {
    return null;
  }
  return null;
}

function collectorBase(factoryId: string): string {
  const encodedFactory = encodeURIComponent(factoryId);
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/mobile').replace(/\/$/, '');
  return `${base}/${encodedFactory}`;
}

function normalizeMetadata(metadata: ClientErrorMetadata): ClientErrorMetadata {
  return {
    category: metadata.category,
    route: safePath(metadata.route) || currentRoute(),
    method: safeMethod(metadata.method),
    status: safeStatus(metadata.status),
    errorCode: safeCode(metadata.errorCode),
    parentCorrelationId: safeCorrelation(metadata.parentCorrelationId),
    source: safeCode(metadata.source) || 'web-admin',
    file: safeFile(metadata.file),
    line: safePosition(metadata.line),
    column: safePosition(metadata.column),
  };
}

function sourceLocation(error: unknown, filename?: unknown, line?: unknown, column?: unknown): Partial<ClientErrorMetadata> {
  const stack = error instanceof Error ? error.stack : undefined;
  const match = typeof filename === 'string' && filename
    ? { file: filename, line, column }
    : parseStackLocation(stack);
  return {
    file: safeFile(match?.file),
    line: safePosition(match?.line),
    column: safePosition(match?.column),
  };
}

function parseStackLocation(stack?: string): { file?: string; line?: number; column?: number } {
  if (!stack) return {};
  // Error stacks can start with a Firefox `function@url:line:column` frame, so inspect
  // every line. Error name/message lines are explicitly excluded; only actual V8/Firefox
  // frame syntax can provide a source location.
  for (const line of stack.split(/\r?\n/)) {
    if (/^\s*[A-Za-z]*(?:Error)?\s*:/.test(line)) continue;
    const v8 = line.match(/^\s*at\s+(?:.+?\s+\()?(.+?):(\d+):(\d+)\)?\s*$/);
    const firefox = line.match(/^\s*[^@\s][^@]*@(.+?):(\d+):(\d+)\s*$/);
    const match = v8 || firefox;
    if (!match || !isScriptFile(match[1])) continue;
    return { file: match[1], line: Number(match[2]), column: Number(match[3]) };
  }
  return {};
}

function isScriptFile(value: string): boolean {
  const path = value.split(/[?#]/, 1)[0];
  return /\.(?:js|mjs|ts|vue)$/i.test(path);
}

function safePath(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const raw = value.trim();
  if (!raw) return undefined;
  try {
    const url = new URL(raw, window.location.origin);
    return (url.pathname || '/').slice(0, 256);
  } catch {
    const path = raw.split(/[?#]/, 1)[0];
    return (path.startsWith('/') ? path : `/${path}`).slice(0, 256);
  }
}

function currentRoute(): string {
  return safePath(window.location.pathname) || '/';
}

function safeFile(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const basename = value.split(/[\\/]/).pop()?.split(/[?#]/, 1)[0] || '';
  const clean = basename.replace(/[^A-Za-z0-9_.-]/g, '_');
  return clean ? clean.slice(0, 128) : undefined;
}

function safeCode(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const clean = value.trim().slice(0, 64);
  return CODE_PATTERN.test(clean) ? clean : undefined;
}

function safeCorrelation(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  return isSafeCorrelation(value) ? value : undefined;
}

function isSafeCorrelation(value: unknown): value is string {
  return typeof value === 'string' && CORRELATION_PATTERN.test(value);
}

function safeMethod(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const method = value.toUpperCase();
  return /^(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)$/.test(method) ? method : undefined;
}

function safeStatus(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 599 ? value : undefined;
}

function safePosition(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100000 ? value : undefined;
}

function headerValue(headers: unknown, name: string): unknown {
  if (!headers || typeof headers !== 'object') return undefined;
  const candidate = headers as { get?: (key: string) => unknown; [key: string]: unknown };
  return typeof candidate.get === 'function' ? candidate.get(name) : candidate[name] ?? candidate[name.toLowerCase()];
}

function newEventId(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  if (uuid && isSafeCorrelation(uuid)) return uuid;
  return `evt-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`.slice(0, 64);
}

function pruneSeen(now: number): void {
  for (const [key, timestamp] of seen) {
    if (now - timestamp >= DEDUP_MS) seen.delete(key);
  }
  if (seen.size >= MAX_DEDUP_KEYS) {
    const oldest = seen.keys().next().value;
    if (oldest) seen.delete(oldest);
  }
}

/** Test-only reset keeps functional reporter tests independent without exposing state. */
export function resetClientErrorReporterForTests(): void {
  seen.clear();
  windowStartedAt = 0;
  windowCount = 0;
  reportedErrors = new WeakSet<object>();
}

/** Marks normalized errors created after an Axios failure so the runtime hook skips them. */
export function markClientErrorReported(error: unknown): void {
  if ((typeof error === 'object' && error !== null) || typeof error === 'function') {
    reportedErrors.add(error as object);
  }
}

function wasClientErrorReported(error: unknown): boolean {
  return ((typeof error === 'object' && error !== null) || typeof error === 'function')
    && reportedErrors.has(error as object);
}
