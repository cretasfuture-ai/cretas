import { beforeEach, describe, expect, it, vi } from 'vitest'
import { beginSandbox, clearEndedSandbox, endSandbox, recoverActiveSandbox, refreshSandbox, sandboxRequestUrl, sandboxSession, selectSandboxActor, updateSandboxSession, type SandboxSession } from '../f006Sandbox'
const id = '8ba01349-0595-4fa2-892a-18ce2f99986f'
const ready: SandboxSession = { sessionId: id, actor: 1309, status: 'READY', generation: 1 }
describe('F006 sandbox request and cleanup boundary', () => {
  beforeEach(() => { sandboxSession.value = null; localStorage.clear(); sessionStorage.clear() })
  it('routes every F006 business verb through the active isolated session', () => {
    updateSandboxSession(ready)
    for (const verb of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']) {
      expect(sandboxRequestUrl('/api/mobile/F006/sales/orders?page=1', verb)).toBe(`/api/mobile/F006/demo-sandbox/sessions/${id}/proxy/api/mobile/F006/sales/orders?page=1`)
    }
    expect(sandboxRequestUrl('/api/mobile/auth/me')).toBe('/api/mobile/auth/me')
  })
  it('blocks other tenants, global AI dispatch, and external storage while isolated', () => {
    updateSandboxSession(ready)
    for (const url of ['/api/mobile/F007/sales/orders', '/api/admin/users', '/smartbi-api/api/upload', 'https://storage.invalid/file.png', 'https://evil.invalid/api/mobile/F006/sales/orders', '//evil.invalid/api/mobile/auth/me', `https://evil.invalid/api/mobile/F006/demo-sandbox/sessions/${id}`]) expect(() => sandboxRequestUrl(url, 'POST')).toThrow()
  })
  it('never routes business traffic to production during recovery, preparation or cleanup failure', () => {
    for (const status of ['VERIFYING', 'PREPARING', 'ENDING', 'FAILED', 'ENDED'] as const) {
      updateSandboxSession({ ...ready, status })
      expect(() => sandboxRequestUrl('/api/mobile/F006/purchase/orders', 'POST')).toThrow()
      expect(sandboxRequestUrl(`/api/mobile/F006/demo-sandbox/sessions/${id}`)).toContain(id)
    }
  })
  it('keeps only exact own authorization reads available while preparing', () => {
    updateSandboxSession({ ...ready, status: 'PREPARING' })
    for (const url of ['/api/mobile/F006/permissions/current', '/api/mobile/F006/canvas/user-module-access/1309']) {
      expect(sandboxRequestUrl(url, 'GET')).toBe(url)
      expect(() => sandboxRequestUrl(url, 'POST')).toThrow()
    }
    expect(() => sandboxRequestUrl('/api/mobile/F006/canvas/user-module-access/1310', 'GET')).toThrow()
  })
  it('cannot clear local isolation until server confirms ENDED', async () => {
    updateSandboxSession(ready)
    const client = { get: vi.fn(), post: vi.fn(), delete: vi.fn().mockRejectedValue(new Error('timeout')) }
    await expect(endSandbox(client)).rejects.toThrow('timeout')
    expect(sandboxSession.value?.status).toBe('FAILED')
    expect(() => clearEndedSandbox()).toThrow()
    client.get.mockResolvedValue({ success: true, data: { ...ready, status: 'ENDED' } })
    await refreshSandbox(client)
    clearEndedSandbox()
    expect(sandboxSession.value).toBeNull()
    expect(sandboxRequestUrl('/api/mobile/F006/sales/orders')).toBe('/api/mobile/F006/sales/orders')
  })
  it('resumes one active session rather than allocating another database slot', async () => {
    const client = { get: vi.fn().mockResolvedValue({ data: ready }), post: vi.fn().mockResolvedValue({ data: ready }), delete: vi.fn() }
    await beginSandbox(client); await beginSandbox(client)
    expect(client.post).toHaveBeenCalledTimes(1)
    expect(client.get).toHaveBeenCalledTimes(1)
    expect(() => updateSandboxSession({ ...ready, sessionId: '0fd69b80-3984-4d55-931c-c0183fda9ec1' })).toThrow('上一轮')
  })
  it('recovers lost or corrupt local state only from an explicit server answer', async () => {
    sandboxSession.value = { sessionId: '', status: 'VERIFYING' }
    const client = { get: vi.fn().mockRejectedValue(new Error('offline')), post: vi.fn(), delete: vi.fn() }
    await expect(recoverActiveSandbox(client)).rejects.toThrow('offline')
    expect(() => sandboxRequestUrl('/api/mobile/F006/sales/orders', 'POST')).toThrow()
    client.get.mockResolvedValue({ success: true, data: ready })
    await recoverActiveSandbox(client)
    expect(sandboxSession.value?.sessionId).toBe(id)
    sandboxSession.value = { sessionId: '', status: 'VERIFYING' }
    client.get.mockResolvedValue({ success: true, data: null })
    await recoverActiveSandbox(client)
    expect(sandboxSession.value).toBeNull()
  })
  it('preserves the demo owner session while another account uses its own context', () => {
    updateSandboxSession(ready)
    selectSandboxActor(999, 'F007')
    expect(sandboxSession.value).toBeNull()
    expect(sandboxRequestUrl('/api/mobile/F007/sales/orders', 'POST')).toBe('/api/mobile/F007/sales/orders')
    selectSandboxActor(1309, 'F006')
    expect(sandboxSession.value?.status).toBe('VERIFYING')
    expect(sandboxSession.value?.sessionId).toBe(id)
  })
  it('keeps isolation active if browser persistence fails after server creation', () => {
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('quota') })
    expect(() => updateSandboxSession(ready)).toThrow('quota')
    expect(sandboxSession.value?.sessionId).toBe(id)
    expect(sandboxRequestUrl('/api/mobile/F006/sales/orders', 'POST')).toContain('/proxy/')
    write.mockRestore()
  })
  it('clears a valid stale local session only after an explicit server no-active proof', async () => {
    updateSandboxSession(ready)
    const client = { get: vi.fn().mockResolvedValue({ success: true, data: null }), post: vi.fn(), delete: vi.fn() }
    await recoverActiveSandbox(client)
    expect(sandboxSession.value).toBeNull()
  })
})
