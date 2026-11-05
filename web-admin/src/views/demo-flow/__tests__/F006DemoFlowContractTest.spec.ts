import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { DEMO_STEPS, assertF006Session, createDemoState, prepareStep } from '@/demo/f006Flow'

const root = path.resolve(__dirname, '../../../..')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('F006 flow demo wiring contract', () => {
  it('is protected by the F006 factory-admin route role', () => {
    const router = read('src/router/index.ts')
    expect(router).toContain("path: 'demo-flow'")
    expect(router).toContain("roles: ['factory_super_admin']")
  })

  it('only shows the header entry for an F006 factory admin', () => {
    const header = read('src/components/layout/AppHeader.vue')
    expect(header).toContain("authStore.factoryId === 'F006'")
    expect(header).toContain("authStore.currentRole === 'factory_super_admin'")
    expect(header).toContain("router.push('/demo-flow')")
  })

  it('renders honest execution boundaries and stops on failure', () => {
    const page = read('src/views/demo-flow/index.vue')
    expect(page).toContain("真实结果回读")
    expect(page).toContain("stopPlayback()")
    expect(page).toContain("只显示业务动作与结果")
    expect(page).toContain("factoryId: authStore.factoryId")
  })

  it('uses the real F006 engine contract and prepares fields from its step definitions', () => {
    expect(DEMO_STEPS.length).toBeGreaterThan(10)
    const state = createDemoState()
    expect(prepareStep(state, 'purchase-create').fields.map(field => field.name)).toEqual(
      expect.arrayContaining(['supplierId', 'materialTypeId', 'quantity', 'unit', 'unitPrice']),
    )
    expect(() => assertF006Session({ factoryId: 'F001' })).toThrow('F006')
    expect(() => assertF006Session({ factoryId: 'F006' })).not.toThrow()
  })

  it('offers human-readable bootstrap choices, safe result fields, and continuous playback controls', () => {
    const page = read('src/views/demo-flow/index.vue')
    expect(page).toContain("/suppliers/active")
    expect(page).toContain("/raw-material-types?keyword=DEMO-20261105&page=1&size=100")
    expect(page).toContain('<el-option')
    expect(page).toContain('const safeResult')
    expect(page).toContain('function pausePlayback')
    expect(page).toContain('scheduleAutoplay')
    expect(page).toContain('initializeDemoDefaults')
    expect(page).toContain('state.value = await initializeDemo(state.value)')
    expect(page).not.toContain('defaultFieldValue')
    expect(page).toContain("process-report-6")
    expect(page).toContain("material-requisition")
    expect(page).not.toContain("'DEMO-20261105-PB-022'))}`")
    expect(page).toContain('state.value.currentStep !== currentIndex.value + 1')
    expect(page).toContain('saved.schemaVersion === 2')
    expect(page).toContain('function enterSandbox')
    expect(page).toContain('saved.sandboxSessionId === sandboxSession.value?.sessionId')
    expect(page).not.toContain('function startNewRound')
    expect(page).toContain('restoreState(); currentIndex.value = Math.min(state.value.currentStep')
    expect(page).not.toContain('DEMO_SKU_CODE')
    expect(page).not.toContain('页面不会用假数据')
    expect(page).not.toContain('authorizeDemoApprover')
    expect(page).not.toContain('f006_finance_mgr')
    expect(page).toContain('执行演示审批')
  })
})
