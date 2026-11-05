import { afterEach, describe, expect, it, vi } from 'vitest'
import { installSandboxNetworkBoundary } from '../f006SandboxNetwork'
import { sandboxSession, updateSandboxSession, type SandboxSession } from '../f006Sandbox'

const session: SandboxSession = {
  sessionId: '8ba01349-0595-4fa2-892a-18ce2f99986f',
  actor: 1309,
  status: 'READY',
  generation: 1,
}

describe('F006 sandbox network boundary', () => {
  const originalFetch = window.fetch
  const originalOpen = XMLHttpRequest.prototype.open

  afterEach(() => {
    window.fetch = originalFetch
    XMLHttpRequest.prototype.open = originalOpen
    sandboxSession.value = null
    localStorage.clear()
    sessionStorage.clear()
  })

  it('installs explicitly, rewrites active fetch/XHR, rejects unsafe targets, and is idempotent', async () => {
    expect(window.fetch).toBe(originalFetch)
    expect(XMLHttpRequest.prototype.open).toBe(originalOpen)

    const fetchMock = vi.fn<typeof window.fetch>().mockResolvedValue(
      new Response(null, { status: 200 }),
    )
    const openMock = vi.fn<(...args: Parameters<XMLHttpRequest['open']>) => void>()
    window.fetch = fetchMock
    XMLHttpRequest.prototype.open = openMock

    installSandboxNetworkBoundary()
    const installedFetch = window.fetch
    const installedOpen = XMLHttpRequest.prototype.open
    installSandboxNetworkBoundary()
    expect(window.fetch).toBe(installedFetch)
    expect(XMLHttpRequest.prototype.open).toBe(installedOpen)

    await window.fetch('/api/mobile/auth/me')
    expect(fetchMock).toHaveBeenCalledWith('/api/mobile/auth/me', undefined)

    updateSandboxSession(session)
    await window.fetch('/api/mobile/F006/sales/orders?page=1', { method: 'GET' })
    expect(fetchMock).toHaveBeenLastCalledWith(
      `/api/mobile/F006/demo-sandbox/sessions/${session.sessionId}/proxy/api/mobile/F006/sales/orders?page=1`,
      { method: 'GET' },
    )

    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/mobile/F006/purchase/orders')
    expect(openMock).toHaveBeenLastCalledWith(
      'POST',
      `/api/mobile/F006/demo-sandbox/sessions/${session.sessionId}/proxy/api/mobile/F006/purchase/orders`,
      true,
      undefined,
      undefined,
    )

    await expect(window.fetch('/api/mobile/F007/sales/orders')).rejects.toThrow()
    await expect(window.fetch('https://evil.invalid/api/mobile/F006/sales/orders')).rejects.toThrow()
    expect(() => new XMLHttpRequest().open('GET', '/api/mobile/F007/sales/orders')).toThrow()
    expect(() => new XMLHttpRequest().open('GET', 'https://evil.invalid/api/mobile/F006/sales/orders')).toThrow()
  })
})
