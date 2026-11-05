import { shallowRef } from 'vue'

export const SANDBOX_STORAGE_KEY = 'cretas:f006:sandbox:v1'
export const SANDBOX_FLOW_STORAGE_KEY = 'cretas:f006:sandbox-flow:v1'
export type SandboxStatus = 'PREPARING' | 'READY' | 'ENDING' | 'ENDED' | 'FAILED' | 'VERIFYING'
export interface SandboxSession { sessionId: string; actor?: number; status: SandboxStatus; generation?: string | number; message?: string }
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const statuses = new Set<SandboxStatus>(['PREPARING', 'READY', 'ENDING', 'ENDED', 'FAILED', 'VERIFYING'])
function restore(): SandboxSession | null {
  if (typeof localStorage === 'undefined') return null
  const raw = localStorage.getItem(SANDBOX_STORAGE_KEY)
  if (!raw) return null
  try {
    const saved = JSON.parse(raw) as SandboxSession
    if (!UUID.test(saved.sessionId) || saved.actor !== 1309 || !statuses.has(saved.status)) throw new Error('invalid')
    return { ...saved, status: 'VERIFYING' }
  } catch { return { sessionId: '', status: 'VERIFYING', message: '正在向服务端恢复演示会话' } }
}
export const sandboxSession = shallowRef<SandboxSession | null>(restore())
export function updateSandboxSession(session: SandboxSession, serverRecovery = false): void {
  if (!UUID.test(session.sessionId) || session.actor !== 1309 || !statuses.has(session.status)) throw new Error('沙盒返回了无效会话')
  if (!serverRecovery && sandboxSession.value?.sessionId && sandboxSession.value.sessionId !== session.sessionId) throw new Error('上一轮演示尚未结束')
  // A storage failure must never leave the browser in production mode after server creation.
  sandboxSession.value = session
  localStorage.setItem(SANDBOX_STORAGE_KEY, JSON.stringify(session))
}
export function selectSandboxActor(actorId: unknown, factoryId: unknown): void {
  if (Number(actorId) !== 1309 || factoryId !== 'F006') sandboxSession.value = null
  else if (!sandboxSession.value) sandboxSession.value = restore()
}
export function clearEndedSandbox(): void {
  if (sandboxSession.value?.status !== 'ENDED') throw new Error('服务端尚未确认清理完成')
  localStorage.removeItem(SANDBOX_STORAGE_KEY)
  sessionStorage.removeItem(SANDBOX_FLOW_STORAGE_KEY)
  sandboxSession.value = null
}

export function currentTokenOwnsSandbox(): boolean | undefined {
  // A newly signed-in account can issue bootstrap requests before AppLayout mounts.
  // This only selects a client context; the server verifies JWT signatures and session ownership.
  const token = typeof localStorage === 'undefined' ? null : localStorage.getItem('cretas_access_token')
  if (token) {
    try {
      const payload = token.split('.')[1]
      const claims = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as { userId?: number; factoryId?: string }
      if (claims.userId != null && claims.factoryId) return Number(claims.userId) === 1309 && claims.factoryId === 'F006'
    } catch { /* An unverifiable client context keeps the isolation closed. */ }
  }
  return undefined
}
/** Resolve every API request against the active run; an unavailable run never falls back. */
export function sandboxRequestUrl(raw: string, method = 'GET', session = sandboxSession.value): string {
  if (!session || currentTokenOwnsSandbox() === false) return raw
  const url = new URL(raw, typeof location === 'undefined' ? 'http://localhost' : location.origin)
  const expectedOrigin = typeof location === 'undefined' ? 'http://localhost' : location.origin
  if (url.origin !== expectedOrigin) throw new Error('隔离演示禁止向外部地址发送请求')
  const path = url.pathname
  if (/^\/api\/mobile\/F006\/demo-sandbox\/(?:sessions(?:\/|$)|active-session$)/.test(path)) return raw
  if (/^\/api\/mobile\/auth\/(?:me|refresh|logout|unified-login|login)$/.test(path)) return raw
  // These two exact reads describe the current actor's authorization, not ERP
  // records. The route guard needs them even while the data slot is preparing.
  if (method.toUpperCase() === 'GET' && (path === '/api/mobile/F006/permissions/current' || path === '/api/mobile/F006/canvas/user-module-access/1309')) return raw
  // This exact handler only returns suggested field values. It never dispatches ERP tools.
  // Keep model access on the authenticated main service; submitting the filled form stays isolated.
  if (path === '/api/mobile/F006/form-assistant/parse' && method.toUpperCase() === 'POST') {
    if (session.status !== 'READY') throw new Error('沙盒尚未就绪，请稍后再使用 AI 填写')
    return raw
  }
  // Tenant-neutral bootstrap reads are required to keep the navigation available.
  if (method.toUpperCase() === 'GET' && /^\/api\/mobile\/(?:auth|users\/me|system\/public-config)(?:\/|$)/.test(path)) return raw
  if (path.startsWith('/api/mobile/F006/')) {
    if (session.status !== 'READY') throw new Error(session.message || '演示沙盒尚未就绪，业务请求已暂停')
    if (!UUID.test(session.sessionId) || /%|\\|\/\.\.?\//.test(path)) throw new Error('无效的演示请求路径')
    url.pathname = `/api/mobile/F006/demo-sandbox/sessions/${session.sessionId}/proxy${path}`
    return url.origin === (typeof location === 'undefined' ? 'http://localhost' : location.origin)
      ? `${url.pathname}${url.search}` : url.href
  }
  throw new Error('隔离演示期间仅支持沙盒业务接口，请结束演示后使用其他功能')
}

export function requireSandboxReady(): SandboxSession {
  const current = sandboxSession.value
  if (!current || current.status !== 'READY') throw new Error('请先进入隔离演示沙盒')
  return current
}

interface SandboxClient {
  get(path: string): Promise<unknown>
  post(path: string): Promise<unknown>
  delete(path: string): Promise<unknown>
}
const endpoint = '/F006/demo-sandbox/sessions'
function sessionData(response: unknown): SandboxSession {
  if (!response || typeof response !== 'object') throw new Error('沙盒未返回有效状态')
  const envelope = response as { success?: boolean; data?: unknown; message?: string }
  if (envelope.success === false) throw new Error(envelope.message || '沙盒操作失败')
  const value = (envelope.data ?? response) as SandboxSession
  if (!UUID.test(value.sessionId) || value.actor !== 1309 || !statuses.has(value.status)) throw new Error('沙盒状态无效，已停止')
  return value
}
export async function refreshSandbox(client: SandboxClient): Promise<SandboxSession> {
  const current = sandboxSession.value
  if (!current || !UUID.test(current.sessionId)) throw new Error('演示会话无效')
  const session = sessionData(await client.get(`${endpoint}/${current.sessionId}`))
  updateSandboxSession(session)
  return session
}
export async function beginSandbox(client: SandboxClient): Promise<SandboxSession> {
  if (sandboxSession.value) return refreshSandbox(client)
  const session = sessionData(await client.post(endpoint))
  updateSandboxSession(session)
  return session
}
export async function recoverActiveSandbox(client: SandboxClient, stillCurrent: () => boolean = () => true): Promise<void> {
  const response = await client.get('/F006/demo-sandbox/active-session')
  if (!stillCurrent()) throw new Error('登录身份已变化，已忽略旧演示会话')
  const envelope = response as { success?: boolean; data?: unknown; message?: string }
  if (!envelope || envelope.success !== true || !('data' in envelope)) throw new Error(envelope?.message || '无法核对演示状态')
  if (envelope.data != null) updateSandboxSession(sessionData(response), true)
  else if (sandboxSession.value) {
    // Only the server's explicit no-active-session proof can release corrupt local state.
    localStorage.removeItem(SANDBOX_STORAGE_KEY)
    sandboxSession.value = null
  }
}
export async function endSandbox(client: SandboxClient): Promise<SandboxSession> {
  const current = sandboxSession.value
  if (!current || !UUID.test(current.sessionId)) throw new Error('演示会话无效')
  // Block new requests before sending DELETE; preserve the isolation on any error.
  updateSandboxSession({ ...current, status: 'ENDING', message: '正在清理本轮数据' })
  try {
    const session = sessionData(await client.delete(`${endpoint}/${current.sessionId}`))
    updateSandboxSession(session)
    return session
  } catch (error) {
    updateSandboxSession({ ...current, status: 'FAILED', message: '清理未确认完成，仍保持隔离；请重试结束演示' })
    throw error
  }
}
