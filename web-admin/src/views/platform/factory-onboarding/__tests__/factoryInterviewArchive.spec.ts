import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import FactoryInterviewArchive from '../components/FactoryInterviewArchive.vue'
import type { FactoryBuildProgress } from '@/api/factoryOnboardingApi'

function progress(overrides: Partial<FactoryBuildProgress> = {}): FactoryBuildProgress {
  return {
    sessionId: 'demo-session', factoryId: 'DEMO', status: 'COMPLETED',
    currentRound: 14, maxRounds: 80, remaining: 0, pending: [], pendingLabels: [],
    answered: [], collected: {}, readyForGenerate: true, ...overrides,
  }
}

describe('completed and in-progress factory interview archive', () => {
  it('keeps all 46 completed answers and the final history message available without restarting', async () => {
    const collected = Object.fromEntries(Array.from({ length: 46 }, (_, i) => [`field${i}`, `答案${i}`]))
    const labels = Object.fromEntries(Object.keys(collected).map((field, i) => [field, `问题${i}`]))
    const wrapper = mount(FactoryInterviewArchive, { props: { progress: progress({
      collected, answeredLabels: labels,
      history: [{ role: 'user', content: '完整样例' }, { role: 'assistant', content: '最后一条记录' }],
    }) } })
    const details = wrapper.get('details').element as HTMLDetailsElement
    details.open = true
    await wrapper.get('details').trigger('toggle')
    expect(wrapper.findAll('dt')).toHaveLength(46)
    expect(wrapper.text()).toContain('问题45')
    expect(wrapper.text()).toContain('答案45')
    expect(wrapper.text()).toContain('最后一条记录')
    expect(wrapper.findAll('button, input, textarea')).toHaveLength(0)
    expect(wrapper.emitted()).toEqual({})
  })

  it('renders questions beyond the former six-row limit, including their hints and choices', () => {
    const pendingItems = Array.from({ length: 9 }, (_, i) => ({
      name: `pending${i}`, label: `待答问题${i}`, inputType: 'CHOICE', hint: `完整题目${i}`,
      options: ['选项甲', '选项乙'], suggestion: '选项甲',
    }))
    const wrapper = mount(FactoryInterviewArchive, { props: { progress: progress({
      status: 'ACTIVE', remaining: 9, pendingItems, collected: { old: '旧答案' },
      skippedItems: [{ name: 'skipped', label: '跳过项目', why: '尚未确定仓库用途' }],
    }) } })
    expect(wrapper.findAll('article')).toHaveLength(9)
    expect(wrapper.text()).toContain('待答问题8')
    expect(wrapper.text()).toContain('完整题目8')
    expect(wrapper.text()).toContain('选项甲 / 选项乙')
    expect(wrapper.text()).toContain('尚未确定仓库用途')
    expect(wrapper.text()).toContain('旧答案')
  })

  it('does not invent answers or history for an empty session', () => {
    const wrapper = mount(FactoryInterviewArchive, { props: { progress: progress() } })
    expect(wrapper.findAll('dt')).toHaveLength(0)
    expect(wrapper.text()).toContain('当前会话没有可回读的历史消息')
  })
})
