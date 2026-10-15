import { beforeEach, describe, expect, it, vi } from 'vitest'

const request = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/api/request', () => ({ default: request }))

import type { User } from '@/types/auth'
import { getFactoryAssemblyEnabled, isFactoryAssemblyPlatformUser } from '../factoryAssemblyCapabilities'

describe('factory assembly capability contract', () => {
  beforeEach(() => request.get.mockReset())

  it.each([
    [{ success: true, data: { enabled: true } }, true],
    [{ success: true, data: { enabled: false } }, false],
    [{ success: true, data: { enabled: 'true' } }, false],
    [{ success: true }, false],
    [{ success: false, data: { enabled: true } }, false],
  ])('accepts only an explicit enabled boolean', async (response, expected) => {
    request.get.mockResolvedValue(response)
    await expect(getFactoryAssemblyEnabled()).resolves.toBe(expected)
    expect(request.get).toHaveBeenCalledWith(
      '/api/platform/factory-assembly/capabilities',
      { baseURL: '' },
    )
  })

  it.each([
    [{ userType: 'platform', platformUser: { role: 'super_admin' } }, true],
    [{ userType: 'factory', factoryUser: { role: 'super_admin', factoryId: 'F006' } }, false],
    [{ userType: 'platform', platformUser: { role: 'system_admin' } }, false],
    [{ userType: 'platform', platformUser: { role: 'platform_admin' } }, false],
  ])('allows only a platform namespace with super_admin', (user, expected) => {
    expect(isFactoryAssemblyPlatformUser(user as User)).toBe(expected)
  })

  it('fails closed on a rejected request', async () => {
    request.get.mockRejectedValueOnce(new Error('offline'))
    await expect(getFactoryAssemblyEnabled()).resolves.toBe(false)
  })
})
