import axios, { type AxiosAdapter } from 'axios'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import request from '../request'
import { sandboxSession, updateSandboxSession, type SandboxSession } from '@/demo/f006Sandbox'

const readySession: SandboxSession = {
  sessionId: '8ba01349-0595-4fa2-892a-18ce2f99986f',
  actor: 1309,
  status: 'READY',
  generation: 1,
}

describe('F006 sandbox query serialization', () => {
  const originalAdapter = request.defaults.adapter
  const captured: Array<{ url: string; params: unknown }> = []
  const adapter: AxiosAdapter = async (config) => {
    captured.push({ url: axios.getUri(config), params: config.params })
    return {
      data: { success: true, data: { ok: true } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      request: {},
    }
  }

  beforeEach(() => {
    captured.length = 0
    request.defaults.adapter = adapter
    sandboxSession.value = null
    localStorage.clear()
  })

  afterEach(() => {
    request.defaults.adapter = originalAdapter
    sandboxSession.value = null
    localStorage.clear()
  })

  it('serializes active sandbox params once through the real Axios adapter path', async () => {
    updateSandboxSession(readySession)

    await request.get('/F006/sales/orders', {
      params: { keyword: 'SO-001', page: 2 },
    })

    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.pathname).toBe(`/api/mobile/F006/demo-sandbox/sessions/${readySession.sessionId}/proxy/api/mobile/F006/sales/orders`)
    expect(url.searchParams.getAll('keyword')).toEqual(['SO-001'])
    expect(url.searchParams.getAll('page')).toEqual(['2'])
    expect(captured[0].params).toBeUndefined()
  })

  it('preserves embedded query values while removing only serialized sandbox params', async () => {
    updateSandboxSession(readySession)

    await request.get('/F006/sales/orders?sort=desc', { params: { page: 2 } })

    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.searchParams.getAll('sort')).toEqual(['desc'])
    expect(url.searchParams.getAll('page')).toEqual(['2'])
    expect(captured[0].params).toBeUndefined()
  })

  it('keeps params for non-sandbox requests', async () => {
    updateSandboxSession(readySession)
    const params = { page: 2, keyword: 'me' }

    await request.get('/auth/me', { params })

    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.pathname).toBe('/api/mobile/auth/me')
    expect(url.searchParams.getAll('page')).toEqual(['2'])
    expect(url.searchParams.getAll('keyword')).toEqual(['me'])
    expect(captured[0].params).toEqual(params)
  })

  it('preserves array values produced by a custom serializer without duplicating them', async () => {
    updateSandboxSession(readySession)
    await request.get('/F006/sales/orders', {
      params: { tags: ['raw', 'packaging'] },
      paramsSerializer: { serialize: params => (params.tags as string[]).map(tag => `tag=${encodeURIComponent(tag)}`).join('&') },
    })
    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.searchParams.getAll('tag')).toEqual(['raw', 'packaging'])
  })

  it('preserves ordinary business params when no sandbox session is active', async () => {
    const params = { keyword: 'SO-001', page: 2 }
    await request.get('/F006/sales/orders', { params })
    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.pathname).toBe('/api/mobile/F006/sales/orders')
    expect(url.searchParams.getAll('keyword')).toEqual(['SO-001'])
    expect(captured[0].params).toEqual(params)
  })

  it('does not rewrite an existing sandbox proxy URL', async () => {
    updateSandboxSession(readySession)

    const params = { page: 2 }
    await request.get(`/F006/demo-sandbox/sessions/${readySession.sessionId}/proxy/api/mobile/F006/sales/orders?keyword=SO-001`, { params })

    const url = new URL(captured[0].url, 'http://localhost')
    expect(url.pathname).toBe(`/api/mobile/F006/demo-sandbox/sessions/${readySession.sessionId}/proxy/api/mobile/F006/sales/orders`)
    expect(url.searchParams.getAll('keyword')).toEqual(['SO-001'])
    expect(url.searchParams.getAll('page')).toEqual(['2'])
    expect(captured[0].params).toEqual(params)
  })
})
