import { describe, expect, it } from 'vitest'
import { INTERVIEW_MODULE_OPTIONS, interviewModuleLabel, isKnownInterviewModule } from '../interviewModuleCatalog'

describe('interview module dropdown catalog', () => {
  it('knows the catalog ids and rejects the one the tester typed', () => {
    expect(isKnownInterviewModule('quality')).toBe(true)
    expect(isKnownInterviewModule(' Quality ')).toBe(true)
    expect(isKnownInterviewModule('quality-check')).toBe(false)
    expect(isKnownInterviewModule('')).toBe(false)
    expect(isKnownInterviewModule('restaurant')).toBe(false)
    expect(isKnownInterviewModule(' Restaurant ')).toBe(false)
  })
  it('gives the display name for a chosen id and has unique ids', () => {
    expect(interviewModuleLabel('quality')).toBe('质检')
    expect(interviewModuleLabel('nope')).toBe('')
    expect(new Set(INTERVIEW_MODULE_OPTIONS.map((o) => o.moduleId)).size).toBe(INTERVIEW_MODULE_OPTIONS.length)
  })
})
