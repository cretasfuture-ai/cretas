import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
const loginApi = vi.fn()
vi.mock('@/api/auth', () => ({ login: (...args: unknown[]) => loginApi(...args) }))
import { useAuthStore } from '../auth'
import { isPlatformUser, isFactoryUser } from '@/types/auth'

describe('login actor namespace', () => {
  beforeEach(() => { setActivePinia(createPinia()); localStorage.clear(); loginApi.mockReset() })
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
})
