import request from './request'
import type { User } from '@/types/auth'
import { isPlatformUser } from '@/types/auth'

export interface FactoryAssemblyCapabilities {
  enabled: boolean
}

type Envelope<T> = { success: boolean; data?: T; message?: string }

export function isFactoryAssemblyPlatformUser(user: User | null | undefined): boolean {
  return isPlatformUser(user) && String(user.platformUser.role) === 'super_admin'
}

/**
 * The backend is the source of truth for whether the platform assembly UI is
 * exposed. A missing, malformed, or failed response is deliberately treated
 * as disabled so a build flag cannot accidentally open these routes.
 */
export async function getFactoryAssemblyEnabled(): Promise<boolean> {
  try {
    const response = await request.get<Envelope<FactoryAssemblyCapabilities>>(
      '/api/platform/factory-assembly/capabilities',
      { baseURL: '' },
    )
    return response.success === true && response.data?.enabled === true
  } catch {
    return false
  }
}
