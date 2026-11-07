/** Observes the original page's network requests. This module never creates a
 * business request: only a real page control may initiate one. */
export interface AgentWireScope {
  factoryId: string;
  actorId: string;
  role?: string;
  sandboxId?: string;
}
export interface AgentWireRequest {
  method: string;
  path: string;
  canonicalPath: string;
  kind: 'read' | 'planning' | 'activity' | 'control' | 'business';
  payload?: unknown;
  query?: Readonly<Record<string, string>>;
  workflow?: AgentWorkflowContext;
}
export interface AgentWorkflowContext {
  instanceId: string;
  moduleCode: 'PURCHASE_ORDER' | 'SALES_ORDER';
  businessEntityId: string;
  expectedNodeId: string;
  action: 'APPROVE';
  demoRunKey?: string;
}
export interface AgentWireResult extends AgentWireRequest {
  status: number;
  ok: boolean;
  body: unknown;
}
export interface AgentTransportHooks {
  scope: () => AgentWireScope | null;
  allowControl: (method: string, path: string) => boolean;
  beforeBusiness: (request: AgentWireRequest, authorization?: string | null) => void;
  beforePlanning?: (authorization?: string | null) => void;
  completeBusiness: (result: AgentWireResult) => void;
  blocked: (reason: string) => void;
  readPending: (delta: number) => void;
  resolveUrl?: (raw: string, method: string) => string;
}

const MUTATIONS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const businessPath = /^\/(?:purchase|sales|sales-deliveries|warehouse|inventory|materials|material-batches|material-requisitions|processing|production-plans|production-batches|production-requisitions|finished-goods|workflow)(?:\/|$)/;
const forbiddenPath = /\/(?:payments?|refunds?|security|permissions?|delete|remove|void|cancel)(?:\/|$)/i;
function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function parsedBody(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch { return value; }
}
function assertBodyScope(value: unknown, factoryId: string, depth = 0): void {
  if (depth > 12) throw new Error('请求内容层次过深，已停止操作');
  if (value instanceof FormData) {
    for (const name of ['factoryId', 'factory_id', 'tenantId', 'tenant_id']) {
      const tenant = value.get(name);
      if (tenant !== null && tenant !== factoryId) throw new Error('请求中的工厂与当前账号不一致');
    }
  } else if (Array.isArray(value)) {
    for (const item of value) assertBodyScope(item, factoryId, depth + 1);
  } else if (object(value)) {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      if (/^(?:factory_?id|tenant_?id)$/i.test(key) && item != null && String(item) !== factoryId) throw new Error('请求中的工厂与当前账号不一致');
      if (item !== null && typeof item === 'object') assertBodyScope(item, factoryId, depth + 1);
    }
  }
}

/** Fail closed on writes outside the signed-in factory and the owned data slot. */
export function classifyAgentRequest(
  scope: AgentWireScope, method: string, rawUrl: string, rawBody: unknown,
  origin: string, allowControl: AgentTransportHooks['allowControl'],
): AgentWireRequest {
  method = method.toUpperCase();
  const url = new URL(rawUrl, origin);
  const path = url.pathname;
  const base = `/api/mobile/${encodeURIComponent(scope.factoryId)}`;
  const result = (kind: AgentWireRequest['kind'], canonicalPath = path): AgentWireRequest => ({ method, path, canonicalPath, kind });
  if (!MUTATIONS.has(method)) return result('read');
  if (url.origin !== origin) throw new Error('AI 页面操作禁止向外部地址提交');
  if (/%(?:2f|5c|2e|00)|\\/i.test(path)) throw new Error('请求路径包含不允许的编码，已停止操作');
  for (const key of ['factoryId', 'factory_id', 'tenantId', 'tenant_id']) {
    if (url.searchParams.getAll(key).length > 1 || url.searchParams.has(key) && url.searchParams.get(key) !== scope.factoryId) throw new Error('请求参数中的工厂与当前账号不一致');
  }
  const body = parsedBody(rawBody);
  assertBodyScope(body, scope.factoryId);
  if (/^\/api\/mobile\/auth\/(?:refresh|login|unified-login|logout)$/.test(path) && method === 'POST') return result('activity');
  if (path === `${base}/form-assistant/parse` && method === 'POST' && object(body)?.entityType === 'UI_PAGE_ACTION') return result('planning');
  if (path === `${base}/activity-events` && method === 'POST') return result('activity');
  if (allowControl(method, path)) return result('control');
  let canonical: string;
  if (scope.sandboxId) {
    const prefix = `${base}/demo-sandbox/sessions/${scope.sandboxId}/proxy${base}`;
    if (!path.startsWith(`${prefix}/`)) throw new Error('隔离操作禁止向正式业务库或其他演示轮次提交');
    canonical = path.slice(prefix.length);
  } else {
    if (!path.startsWith(`${base}/`) || path.startsWith(`${base}/demo-sandbox/`)) throw new Error('AI 页面操作的工厂或数据区域不一致');
    canonical = path.slice(base.length);
  }
  if (method === 'POST' && (
    /^\/list-summary\/[A-Za-z]+$/.test(canonical) || canonical === '/attachments/batch-3chip-counts'
    || /^\/production-plans\/[^/]+\/process-sheet\/input-availability$/.test(canonical)
  )) return result('read', canonical);
  if (/\/demo-actions(?:\/|$)/.test(canonical) && !(scope.factoryId === 'F006' && scope.actorId === '1309' && scope.sandboxId)) throw new Error('演示审批只允许本轮隔离沙盒，禁止正式业务请求');
  if (method === 'DELETE' || forbiddenPath.test(canonical) || !businessPath.test(canonical)) throw new Error('此操作不在当前 AI 页面操作范围，请到原页面手动处理');
  for (const key of url.searchParams.keys()) if (url.searchParams.getAll(key).length > 1) throw new Error('业务请求含重复参数，请核对原页面');
  return { ...result('business', canonical), payload: body, query: Object.fromEntries(url.searchParams) };
}

function wireResult(request: AgentWireRequest, status: number, body: unknown): AgentWireResult {
  return { ...request, status, body, ok: status >= 200 && status < 300 && object(body)?.success !== false };
}

/** One installation per app layout. Covers Axios/XHR and native fetch equally. */
export function installAgentTransport(hooks: AgentTransportHooks): () => void {
  const originalOpen = XMLHttpRequest.prototype.open;
  const originalSend = XMLHttpRequest.prototype.send;
  const originalSetHeader = XMLHttpRequest.prototype.setRequestHeader;
  const originalFetch = window.fetch;
  const opened = new WeakMap<XMLHttpRequest, { method: string; url: string; authorization?: string }>();
  const resolvedUrl = (raw: string, method: string) => {
    if (!hooks.scope()) return raw;
    try { return hooks.resolveUrl?.(raw, method) ?? raw; }
    catch (error) { hooks.blocked(error instanceof Error ? error.message : '数据隔离状态未能核对'); throw error; }
  };
  const classify = (method: string, url: string, body: unknown, authorization: string | null): AgentWireRequest | null => {
    const scope = hooks.scope();
    if (!scope) return null;
    try {
      const request = classifyAgentRequest(scope, method, url, body, location.origin, hooks.allowControl);
      if (request.kind === 'business') hooks.beforeBusiness(request, authorization);
      if (request.kind === 'planning') hooks.beforePlanning?.(authorization);
      return request;
    } catch (error) {
      hooks.blocked(error instanceof Error ? error.message : '请求已停止');
      throw error;
    }
  };
  const setHeader: XMLHttpRequest['setRequestHeader'] = function (this: XMLHttpRequest, name: string, value: string): void {
    const target = opened.get(this);
    if (target && name.toLowerCase() === 'authorization') target.authorization = value;
    originalSetHeader.call(this, name, value);
  };
  const open: XMLHttpRequest['open'] = function (this: XMLHttpRequest, method: string, url: string | URL, async: boolean = true, username?: string | null, password?: string | null): void {
    opened.set(this, { method, url: resolvedUrl(String(url), method) });
    originalOpen.call(this, method, url, async, username, password);
  };
  const send: XMLHttpRequest['send'] = function (this: XMLHttpRequest, body?: Document | XMLHttpRequestBodyInit | null): void {
    const target = opened.get(this);
    const request = target ? classify(target.method, target.url, body, target.authorization ?? null) : null;
    let finished = false;
    const isRead = request?.kind === 'read';
    if (isRead) hooks.readPending(1);
    const complete = () => {
      if (finished) return;
      finished = true;
      if (isRead) hooks.readPending(-1);
      if (request?.kind !== 'business') return;
      let response: unknown = null;
      try { response = this.responseType === 'json' ? this.response : parsedBody(this.responseText); } catch { /* binary responses do not prove business data */ }
      hooks.completeBusiness(wireResult(request, this.status, response));
    };
    if (request) this.addEventListener('loadend', complete, { once: true });
    try { originalSend.call(this, body); } catch (error) { complete(); throw error; }
  };
  const fetch: typeof window.fetch = async (input, init) => {
    if (!hooks.scope()) return originalFetch.call(window, input, init);
    const source = input instanceof Request ? input : null;
    const url = source?.url ?? String(input);
    const method = init?.method ?? source?.method ?? 'GET';
    let body: unknown = init?.body;
    if (body === undefined && source?.body && MUTATIONS.has(method.toUpperCase())) body = await source.clone().text();
    const headers = new Headers(init?.headers ?? source?.headers);
    const request = classify(method, resolvedUrl(url, method), body, headers.get('Authorization'));
    const isRead = request?.kind === 'read';
    if (isRead) hooks.readPending(1);
    try {
      const response = await originalFetch.call(window, input, init);
      if (request?.kind === 'business') {
        let result: unknown = null;
        try { result = await response.clone().json(); } catch { /* acknowledgement only */ }
        hooks.completeBusiness(wireResult(request, response.status, result));
      }
      return response;
    } catch (error) {
      if (request?.kind === 'business') hooks.completeBusiness(wireResult(request, 0, null));
      throw error;
    } finally { if (isRead) hooks.readPending(-1); }
  };
  XMLHttpRequest.prototype.open = open;
  XMLHttpRequest.prototype.setRequestHeader = setHeader;
  XMLHttpRequest.prototype.send = send;
  window.fetch = fetch;
  return () => {
    if (XMLHttpRequest.prototype.open === open) XMLHttpRequest.prototype.open = originalOpen;
    if (XMLHttpRequest.prototype.setRequestHeader === setHeader) XMLHttpRequest.prototype.setRequestHeader = originalSetHeader;
    if (XMLHttpRequest.prototype.send === send) XMLHttpRequest.prototype.send = originalSend;
    if (window.fetch === fetch) window.fetch = originalFetch;
  };
}
