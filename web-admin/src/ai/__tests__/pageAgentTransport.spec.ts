import { afterEach, describe, expect, it, vi } from 'vitest';
import { classifyAgentRequest, installAgentTransport, type AgentTransportHooks, type AgentWireScope } from '../pageAgentTransport';

const origin = 'http://localhost:3000';
const ordinary = { factoryId: 'F006', actorId: '1309', role: 'factory_super_admin' };
const sandbox = { ...ordinary, sandboxId: 'own-round' };
const ownPath = '/api/mobile/F006/demo-sandbox/sessions/own-round/proxy/api/mobile/F006/purchase/orders';
const classify = (scope: AgentWireScope, method: string, url: string, body: unknown = {}) => classifyAgentRequest(scope, method, url, body, origin, () => false);

describe('page-agent business scope fencing', () => {
  it('allows an ordinary same-factory business request and an owned sandbox request', () => {
    expect(classify(ordinary, 'POST', '/api/mobile/F006/purchase/orders').kind).toBe('business');
    expect(classify(sandbox, 'POST', ownPath).canonicalPath).toBe('/purchase/orders');
  });
  it.each([
    '/api/mobile/F001/purchase/orders',
    'https://outside.example/api/mobile/F006/purchase/orders',
    '/api/mobile/F006/demo-sandbox/sessions/another-round/proxy/api/mobile/F006/purchase/orders',
    '/api/mobile/F006/purchase/orders',
  ])('rejects cross-tenant, external and out-of-slot submission: %s', path => {
    expect(() => classify(sandbox, 'POST', path)).toThrow();
  });
  it('checks nested payload and URL tenant fields before any request is sent', () => {
    expect(() => classify(ordinary, 'POST', '/api/mobile/F006/purchase/orders', JSON.stringify({ items: [{ factoryId: 'F001' }] }))).toThrow('工厂');
    expect(() => classify(ordinary, 'PUT', '/api/mobile/F006/purchase/orders/1?factory_id=F001')).toThrow('工厂');
    const body = new FormData(); body.set('factoryId', 'F001');
    expect(() => classify(ordinary, 'POST', '/api/mobile/F006/purchase/orders', body)).toThrow('工厂');
  });
  it.each([
    ['DELETE', '/api/mobile/F006/purchase/orders/1'],
    ['POST', '/api/mobile/F006/purchase/orders/1/payments'],
    ['POST', '/api/mobile/F006/workflow/instances/1/demo-actions'],
    ['PUT', '/api/mobile/F006/permissions'],
    ['POST', '/api/mobile/F006/purchase/%2fadmin'],
  ])('does not allow destructive or unrelated actions: %s %s', (method, path) => {
    expect(() => classify(ordinary, method, path)).toThrow();
  });
  it('keeps authenticated pure planning on the main service even in an isolated slot', () => {
    expect(classify(sandbox, 'POST', '/api/mobile/F006/form-assistant/parse', { entityType: 'UI_PAGE_ACTION' }).kind).toBe('planning');
    expect(() => classify(sandbox, 'POST', '/api/mobile/F006/form-assistant/parse', { entityType: 'PURCHASE_ORDER' })).toThrow();
  });
  it('does not count known original-page query POSTs as business submissions', () => {
    const path = '/api/mobile/F006/demo-sandbox/sessions/own-round/proxy/api/mobile/F006/production-plans/plan1/process-sheet/input-availability';
    expect(classify(sandbox, 'POST', path).kind).toBe('read');
    expect(classify(ordinary, 'POST', '/api/mobile/F006/activity-events').kind).toBe('activity');
    const activity = ownPath.replace('/purchase/orders', '/activity-events');
    expect(classify(sandbox, 'POST', activity).kind).toBe('activity');
    expect(() => classify(sandbox, 'POST', activity.replace('own-round', 'another-round'))).toThrow('隔离');
  });
  it('permits only the explicitly owned lifecycle control grant', () => {
    const path = '/api/mobile/F006/demo-sandbox/sessions/own-round';
    const policy = (method: string, target: string) => method === 'DELETE' && target === path;
    expect(classifyAgentRequest(sandbox, 'DELETE', path, null, origin, policy).kind).toBe('control');
    expect(() => classifyAgentRequest(sandbox, 'DELETE', path.replace('own-round', 'other'), null, origin, policy)).toThrow();
  });
});

describe('actual browser transport interception', () => {
  let remove: (() => void) | undefined;
  afterEach(() => { remove?.(); remove = undefined; vi.restoreAllMocks(); });
  function hooks(): AgentTransportHooks {
    return { scope: () => ordinary, allowControl: () => false, beforeBusiness: vi.fn(), completeBusiness: vi.fn(), blocked: vi.fn(), readPending: vi.fn() };
  }
  it('observes a real fetch response, without issuing a second business request', async () => {
    const send = vi.fn(async () => new Response(JSON.stringify({ success: true, data: { id: 'po1' } }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.spyOn(window, 'fetch').mockImplementation(send);
    const observer = hooks(); remove = installAgentTransport(observer);
    await window.fetch('/api/mobile/F006/purchase/orders', { method: 'POST', body: '{}' });
    expect(send).toHaveBeenCalledOnce();
    expect(observer.beforeBusiness).toHaveBeenCalledOnce();
    expect(observer.completeBusiness).toHaveBeenCalledWith(expect.objectContaining({ status: 200, ok: true, body: { success: true, data: { id: 'po1' } } }));
  });
  it('aborts fetch before send when the controller has no active submit permission', async () => {
    const send = vi.fn(); vi.spyOn(window, 'fetch').mockImplementation(send);
    const observer = hooks(); observer.beforeBusiness = () => { throw new Error('not the current click'); };
    remove = installAgentTransport(observer);
    await expect(window.fetch('/api/mobile/F006/purchase/orders', { method: 'POST' })).rejects.toThrow('current click');
    expect(send).not.toHaveBeenCalled(); expect(observer.blocked).toHaveBeenCalledOnce();
  });
  it('records response loss as uncertain rather than successful or automatically retried', async () => {
    const send = vi.fn(async () => { throw new TypeError('connection lost'); });
    vi.spyOn(window, 'fetch').mockImplementation(send);
    const observer = hooks(); remove = installAgentTransport(observer);
    await expect(window.fetch('/api/mobile/F006/purchase/orders', { method: 'POST' })).rejects.toThrow('lost');
    expect(send).toHaveBeenCalledOnce();
    expect(observer.completeBusiness).toHaveBeenCalledWith(expect.objectContaining({ status: 0, ok: false }));
  });
  it('also fences Axios/XHR before its native send', () => {
    const send = vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => {});
    const observer = hooks(); remove = installAgentTransport(observer);
    const xhr = new XMLHttpRequest(); xhr.open('POST', '/api/mobile/F001/purchase/orders');
    expect(() => xhr.send('{}')).toThrow('工厂');
    expect(send).not.toHaveBeenCalled(); expect(observer.blocked).toHaveBeenCalledOnce();
  });
  it('provides the actual XHR Authorization header for the synchronous identity fence', () => {
    const send = vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(() => {});
    const observer = hooks();
    observer.beforeBusiness = vi.fn((_request, authorization) => {
      if (authorization !== 'Bearer verified-test-token') throw new Error('credential epoch changed');
    });
    remove = installAgentTransport(observer);
    const xhr = new XMLHttpRequest(); xhr.open('POST', '/api/mobile/F006/purchase/orders');
    xhr.setRequestHeader('Authorization', 'Bearer swapped-test-token');
    expect(() => xhr.send('{}')).toThrow('epoch changed');
    expect(send).not.toHaveBeenCalled();
  });
});
