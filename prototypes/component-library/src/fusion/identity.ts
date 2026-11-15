import { useAuthStore } from '../../legacy-src/store/modules/auth'
import { usePermissionStore } from '../../legacy-src/store/modules/permission'
import type { User } from '../../legacy-src/types/auth'
import type { PageId, Role } from '../types'
export const fusionPaths: Partial<Record<PageId,string>> = { plans:'/production/plans', canvas:'/system/product-processes', analytics:'/smart-bi/dashboard', ai:'/smart-bi/analysis' }
let activeRole = ''
export async function initializeFusionIdentity(role: Role) {
  if (activeRole === role) return
  const response = await fetch('/__v5/identity?role='+role)
  if (!response.ok) throw Error('账号工作区暂未就绪，请重新载入。')
  const user: User = await response.json(), auth = useAuthStore(), permissions = usePermissionStore()
  auth.setUser(user); permissions.setRole(role === 'factory' ? 'factory_super_admin' : 'platform_admin', role === 'factory' ? 'F006' : '', role === 'factory' ? 'FACTORY' : '', String(user.id || ''))
  activeRole = role
}
