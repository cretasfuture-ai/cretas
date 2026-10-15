import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  hydrateCanonicalRecoveryView,
  restoreRecoveredConversationView,
} from '../factoryCanonicalRecoveryView'

describe('factory canonical recovery view', () => {
  it('keeps the saved view closed until matching canonical identity is ready', async () => {
    const events: string[] = []
    const ready = await hydrateCanonicalRecoveryView({
      expectedBlueprintId: 'blueprint-1',
      expectedFactoryId: 'F001',
      expectedSessionId: 'canonical-session',
      onStart: () => events.push('start'),
      hydrate: async () => {
        events.push('hydrate')
        return {
          canonicalBlueprintId: 'blueprint-1',
          canonicalFactoryId: 'F001',
          canonicalSessionId: 'canonical-session',
        }
      },
      onReady: () => events.push('ready'),
      onError: () => events.push('error'),
    })

    expect(ready).toBe(true)
    expect(events).toEqual(['start', 'hydrate', 'ready'])
  })

  it.each([
    ['blueprint', { canonicalBlueprintId: 'other-blueprint', canonicalFactoryId: 'F001', canonicalSessionId: 'canonical-session' }],
    ['factory', { canonicalBlueprintId: 'blueprint-1', canonicalFactoryId: 'OTHER', canonicalSessionId: 'canonical-session' }],
    ['session', { canonicalBlueprintId: 'blueprint-1', canonicalFactoryId: 'F001', canonicalSessionId: 'recovered-other-session' }],
  ])('rejects a mismatched canonical %s identity without enabling editing', async (_identity, result) => {
    const onReady = vi.fn()
    const onError = vi.fn()
    const ready = await hydrateCanonicalRecoveryView({
      expectedBlueprintId: 'blueprint-1',
      expectedFactoryId: 'F001',
      expectedSessionId: 'canonical-session',
      onStart: vi.fn(),
      hydrate: async () => result,
      onReady,
      onError,
    })

    expect(ready).toBe(false)
    expect(onReady).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledOnce()
  })

  it('keeps editing closed when the strict canonical read fails', async () => {
    const onReady = vi.fn()
    const onError = vi.fn()
    const ready = await hydrateCanonicalRecoveryView({
      expectedBlueprintId: 'blueprint-1',
      expectedFactoryId: 'F001',
      expectedSessionId: 'canonical-session',
      onStart: vi.fn(),
      hydrate: async () => { throw new Error('canonical unavailable') },
      onReady,
      onError,
    })

    expect(ready).toBe(false)
    expect(onReady).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledOnce()
  })

  it('returns only to the exact recovered server conversation', async () => {
    const restored = vi.fn()
    const response = await restoreRecoveredConversationView({
      expectedFactoryId: 'F001',
      expectedSessionId: 'recovered-session',
      onStart: vi.fn(),
      load: async () => ({ factoryId: 'F001', sessionId: 'recovered-session', remaining: 2 }),
      onRestored: restored,
      onError: vi.fn(),
    })
    expect(response).toBe(true)
    expect(restored).toHaveBeenCalledWith({ factoryId: 'F001', sessionId: 'recovered-session', remaining: 2 })

    const rejected = vi.fn()
    const error = await restoreRecoveredConversationView({
      expectedFactoryId: 'F001',
      expectedSessionId: 'recovered-session',
      onStart: vi.fn(),
      load: async () => ({ factoryId: 'OTHER', sessionId: 'different-session' }),
      onRestored: vi.fn(),
      onError: rejected,
    })
    expect(error).toBe(false)
    expect(rejected).toHaveBeenCalledOnce()
  })

  it('is wired into the real onboarding page and exposes both return actions', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const page = readFileSync(resolve(here, '..', 'index.vue'), 'utf8')
    expect(page).toContain("from './factoryCanonicalRecoveryView'")
    expect(page).toContain('hydrateCanonicalRecoveryView(')
    expect(page).toContain('hydrateCanonicalBlueprint(expectedSessionId, expectedBlueprintId, expectedFactoryId)')
    expect(page).toContain('candidate.id !== strictExpectedBlueprintId')
    expect(page).toContain('candidate.factoryId !== strictExpectedFactoryId')
    expect(page).toContain('candidate.sessionId !== strictExpectedSessionId')
    expect(page).toContain('progress.value = null')
    expect(page).toContain('const authoritativeSessionId = canonicalViewMode.value')
    expect(page).toContain('restoreRecoveredConversationView(')
    expect(page).toContain('查看已保存的蓝图')
    expect(page).toContain('返回原访谈')
  })
})
