import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PLAN_STALE_MESSAGE, isPlanStale } from '../planStaleness'

const page = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../index.vue'), 'utf8')

describe('plan staleness after an interview answer is rejected (D-S2-22)', () => {
  const generatedWithSalt = '第1道·辅料：盐'
  const afterKeepConfirmed = '第1道·辅料：没有'

  it('the plan generated with the rejected answer is stale once the server description changes', () => {
    expect(isPlanStale(generatedWithSalt, afterKeepConfirmed, true)).toBe(true)
    expect(isPlanStale(generatedWithSalt, '', true)).toBe(true)
    expect(isPlanStale(generatedWithSalt, undefined, true)).toBe(true)
  })

  it('an unchanged description, no plan, or a plan of unknown origin is never stale', () => {
    expect(isPlanStale(generatedWithSalt, generatedWithSalt, true)).toBe(false)
    expect(isPlanStale(generatedWithSalt, afterKeepConfirmed, false)).toBe(false)
    expect(isPlanStale('', afterKeepConfirmed, true)).toBe(false)
  })

  it('tells the user what to do and that the rejected answer is still visible in history', () => {
    expect(PLAN_STALE_MESSAGE).toContain('按最新答案重新生成')
    expect(PLAN_STALE_MESSAGE).toContain('历史')
  })

  it('the page records what the plan was generated from, re-reads answers on focus, and refuses to save a stale plan', () => {
    expect(page).toContain('generatedFromDescription.value = describedFrom')
    expect(page).toContain("document.addEventListener('visibilitychange', onPageVisible)")
    expect(page).toMatch(/await refreshProgressQuietly\(\)\s*\n\s*if \(planStale\.value\)/)
    expect(page).toContain('data-testid="plan-stale-alert"')
  })
})
