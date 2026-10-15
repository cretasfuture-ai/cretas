import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { selectedMetricInterviewAnswer, type MetricInterviewChoice } from '../metricInterviewAnswer'

const metric = (overrides: Partial<MetricInterviewChoice> = {}): MetricInterviewChoice => ({
  label: '良品率', direction: '低于', unit: '%', checked: true,
  alreadyConfigured: false, threshold: 97, ...overrides,
})

describe('explicit catalog metric interview answers', () => {
  it('retains the actual selected threshold and server-owned direction', () => {
    expect(selectedMetricInterviewAnswer([metric(), metric({ label: '工时', direction: '高于', unit: '小时', threshold: 8 })]))
      .toBe('良品率低于97%；工时高于8小时')
  })

  it('does not confirm unchecked, existing or missing-threshold rows', () => {
    expect(selectedMetricInterviewAnswer([
      metric({ checked: false }), metric({ alreadyConfigured: true }), metric({ threshold: undefined }),
    ])).toBeUndefined()
  })

  it.each([NaN, Infinity, -1])('rejects invalid threshold %s', threshold => {
    expect(selectedMetricInterviewAnswer([metric({ threshold })])).toBeUndefined()
  })

  it('accepts explicitly entered zero rather than treating it as absent', () => {
    expect(selectedMetricInterviewAnswer([metric({ threshold: 0 })])).toBe('良品率低于0%')
  })

  it('cannot invent a label or direction when catalog metadata is absent', () => {
    expect(selectedMetricInterviewAnswer([metric({ label: '' }), metric({ direction: '' })])).toBeUndefined()
  })

  it('wires the answer into the live submission, enabled action and visible receipt', () => {
    const page = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '..', 'index.vue'), 'utf8')
      .replace(/<!--[\s\S]*?-->|\/\*[\s\S]*?\*\/|^\s*\/\/.*$/gm, '')
    expect(page).toContain('selectedMetricInterviewAnswer(metricRows.value)')
    expect(page).toContain("(progress.value?.pending ?? []).includes('metricWatches')")
    expect(page).toContain('answers.metricWatches = metricAnswer')
    expect(page).toContain('const answers = questionnaireSubmissionAnswers.value')
    expect(page).toContain('v-if="questionnaireCanSubmit"')
    expect(page).toContain('choiceReceiptEntries(progress.value?.pendingItems ?? [], answers)')
  })
})
