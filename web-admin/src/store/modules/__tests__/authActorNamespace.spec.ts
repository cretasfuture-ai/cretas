import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const loginApi = vi.fn()
vi.mock('@/api/auth', () => ({ login: (...args: unknown[]) => loginApi(...args) }))
import { useAuthStore } from '../auth'
import { isPlatformUser, isFactoryUser } from '@/types/auth'
import { factoryViewContext } from '../platformFactoryContext'

describe('login actor namespace', () => {
beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); sessionStorage.clear(); factoryViewContext.value = null; loginApi.mockReset() })
  const data = { userId: 62, username: 'fixture', role: 'super_admin', permissions: [], token: 'fixture-token' }

  it('keeps explicit platform identity and the existing Web permission alias', async () => {
    loginApi.mockResolvedValue({ success: true, data: { ...data, actorType: 'PLATFORM_ADMIN' } })
    const store = useAuthStore()
    expect(await store.login('fixture', 'fixture')).toBe(true)
    expect(isPlatformUser(store.user)).toBe(true)
    expect(store.user?.platformUser?.role).toBe('super_admin')
    expect(store.user?.factoryUser).toBeUndefined()
    expect(store.currentRole).toBe('platform_admin')
  })

  it('never promotes a factory or legacy role-only response into the platform namespace', async () => {
    const store = useAuthStore()
    for (const response of [
      { ...data, role: 'factory_super_admin', factoryId: 'F1', actorType: 'FACTORY_USER' },
      { ...data, factoryId: undefined },
    ]) {
      loginApi.mockResolvedValue({ success: true, data: response })
      expect(await store.login('fixture', 'fixture')).toBe(true)
      expect(isFactoryUser(store.user)).toBe(true)
      expect(isPlatformUser(store.user)).toBe(false)
      expect(store.user?.platformUser).toBeUndefined()
      expect(store.user?.id).toBe(62)
    }
  })

  it('rejects a platform response carrying a factory before saving login state', async () => {
    loginApi.mockResolvedValue({ success: true, data: { ...data, actorType: 'PLATFORM_ADMIN', factoryId: 'F1' } })
    const store = useAuthStore()
    expect(await store.login('fixture', 'fixture')).toBe(false)
    expect(store.user).toBeNull()
    expect(localStorage.getItem('cretas_access_token')).toBeNull()
  })

  it('recognizes the legacy platform-shaped super_admin for workspace routing', () => {
    const store = useAuthStore()
    store.setUser({
      id: 62, username: 'legacy-platform', fullName: '', email: '', isActive: true,
      createdAt: '', updatedAt: '', userType: 'factory',
      factoryUser: { role: 'super_admin' as never, factoryId: '', permissions: [] },
    } as never)
    expect(store.isPlatform).toBe(true)
    expect(store.factoryId).toBe('')
  })

  it('clears the live read-only context on logout, not only session storage', () => {
    const store = useAuthStore()
    factoryViewContext.value = {
      factoryId: 'F006', factoryName: '六膳门', factoryType: 'FACTORY',
      accessToken: 'short-lived', expiresIn: 300, expiresAt: Date.now() + 300000, readOnly: true,
    }
    store.clearAuth()
    expect(factoryViewContext.value).toBeNull()
    expect(sessionStorage.getItem('cretas_platform_factory_view')).toBeNull()
  })

  it('projects the selected factory into business getters without changing platform identity', () => {
    const store = useAuthStore()
    store.setUser({
      id: 62, username: 'platform', fullName: '', email: '', isActive: true,
      createdAt: '', updatedAt: '', userType: 'platform',
      platformUser: { role: 'platform_admin', permissions: [] },
    } as never)
    factoryViewContext.value = {
      factoryId: 'F006', factoryName: '六膳门', factoryType: 'FACTORY',
      accessToken: 'short-lived', expiresIn: 300, expiresAt: Date.now() + 300000, readOnly: true,
    }
    expect(store.isPlatform).toBe(true)
    expect(store.isFactory).toBe(false)
    expect(store.factoryId).toBe('F006')
    expect(store.factoryName).toBe('六膳门')
    expect(store.factoryType).toBe('FACTORY')
  })
})
