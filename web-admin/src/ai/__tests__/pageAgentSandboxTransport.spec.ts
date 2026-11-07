import { afterEach, expect, it, vi } from 'vitest';
import { installAgentTransport } from '../pageAgentTransport';
import { installSandboxNetworkBoundary } from '@/demo/f006SandboxNetwork';
import { sandboxRequestUrl, sandboxSession, updateSandboxSession } from '@/demo/f006Sandbox';

const originalFetch = window.fetch;
const originalOpen = XMLHttpRequest.prototype.open;
const originalSend = XMLHttpRequest.prototype.send;
const originalSetHeader = XMLHttpRequest.prototype.setRequestHeader;
let remove: (() => void) | undefined;
afterEach(() => {
  remove?.(); window.fetch = originalFetch; XMLHttpRequest.prototype.open = originalOpen;
  XMLHttpRequest.prototype.send = originalSend; XMLHttpRequest.prototype.setRequestHeader = originalSetHeader;
  sandboxSession.value = null; localStorage.clear(); vi.restoreAllMocks();
});

it('observes the final owned sandbox destination when the sandbox wrapper was installed first', async () => {
  const sid = '8ba01349-0595-4fa2-892a-18ce2f99986f';
  const fetchSend = vi.fn<typeof window.fetch>().mockResolvedValue(new Response(JSON.stringify({ success: true }), { status: 200 }));
  const xhrOpen = vi.fn<(...args: Parameters<XMLHttpRequest['open']>) => void>();
  const xhrSend = vi.fn();
  window.fetch = fetchSend; XMLHttpRequest.prototype.open = xhrOpen; XMLHttpRequest.prototype.send = xhrSend;
  installSandboxNetworkBoundary();
  updateSandboxSession({ sessionId: sid, actor: 1309, status: 'READY' });
  const beforeBusiness = vi.fn(); const blocked = vi.fn();
  remove = installAgentTransport({ scope: () => ({ factoryId: 'F006', actorId: '1309', sandboxId: sid }),
    allowControl: () => false, resolveUrl: sandboxRequestUrl, beforeBusiness,
    completeBusiness: vi.fn(), blocked, readPending: vi.fn() });
  const path = '/api/mobile/F006/purchase/orders';
  const finalPath = `/api/mobile/F006/demo-sandbox/sessions/${sid}/proxy${path}`;
  await window.fetch(path, { method: 'POST', body: '{}' });
  expect(fetchSend).toHaveBeenCalledWith(finalPath, { method: 'POST', body: '{}' });
  const xhr = new XMLHttpRequest(); xhr.open('POST', path); xhr.send('{}');
  expect(xhrOpen).toHaveBeenCalledWith('POST', finalPath, true, undefined, undefined);
  expect(xhrSend).toHaveBeenCalledOnce();
  expect(beforeBusiness).toHaveBeenCalledTimes(2);
  expect(beforeBusiness.mock.calls.every(([request]) => request.path === finalPath && request.canonicalPath === '/purchase/orders')).toBe(true);
  await window.fetch('/api/mobile/F006/activity-events', { method: 'POST', body: '{}' });
  expect(fetchSend).toHaveBeenLastCalledWith(`/api/mobile/F006/demo-sandbox/sessions/${sid}/proxy/api/mobile/F006/activity-events`, { method: 'POST', body: '{}' });
  expect(beforeBusiness).toHaveBeenCalledTimes(2);
  await expect(window.fetch('/api/mobile/F001/purchase/orders', { method: 'POST' })).rejects.toThrow();
  expect(fetchSend).toHaveBeenCalledTimes(2); expect(blocked).toHaveBeenCalledOnce();
});
