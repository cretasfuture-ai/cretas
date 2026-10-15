import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const src = (name: string) => readFileSync(resolve(__dirname, '..', name), 'utf8')
const router = src('index.ts')
const guards = src('guards.ts')
const menu = readFileSync(resolve(__dirname, '..', '..', 'components', 'layout', 'menuConfig.ts'), 'utf8')
const sidebar = readFileSync(resolve(__dirname, '..', '..', 'components', 'layout', 'AppSidebar.vue'), 'utf8')
const capabilities = readFileSync(resolve(__dirname, '..', '..', 'api', 'factoryAssemblyCapabilities.ts'), 'utf8')

describe('platform factory assembly access gate', () => {
  it('keeps both routes platform-only and backend gated', () => {
    expect(router).toContain("path: 'factory-onboarding'")
    expect(router).toContain("path: 'factory-review'")
    expect(router).toContain('assemblyFeature: true')
    expect(router).toContain("const factoryAssemblyRoles = ['platform_admin']")
    expect(router.match(/roles: factoryAssemblyRoles/g)).toHaveLength(2)
    expect(router).not.toContain("roles: ['factory_super_admin', 'platform_admin']")
    expect(guards).toContain("getFactoryAssemblyEnabled()")
    expect(guards).toContain("next('/404')")
  })

  it('hides menu entries until the capability endpoint explicitly enables them', () => {
    expect(menu).toContain('assemblyFeature: true')
    expect(menu).toContain("roles: FACTORY_ASSEMBLY_ROLES")
    expect(sidebar).toContain('item.assemblyFeature && !assemblyEnabled')
    expect(capabilities).toContain("'/api/platform/factory-assembly/capabilities'")
    expect(capabilities).toContain('response.data?.enabled === true')
    expect(capabilities).toContain('return false')
  })
})
