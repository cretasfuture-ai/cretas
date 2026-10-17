import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import FactoryBasicsHandoff from '../components/FactoryBasicsHandoff.vue'

const page = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../index.vue'), 'utf8')
const fragments = page.match(/<FactoryBasicsHandoff\b[^>]*\/>/g) || []

function render(lifecycleState: string | null, success = false) {
  return mount(defineComponent({
    components: { FactoryBasicsHandoff },
    template: `<div>${fragments.join('')}</div>`,
    data: () => ({ canonicalBlueprint: lifecycleState ? { lifecycleState } : null, applied: { success } }),
  }))
}

describe('基础资料交付核对', () => {
  it.each(['DRAFT', 'VALIDATED', 'PUBLISHED'])('does not claim application or readiness in %s', state => {
    expect(render(state).find('[data-testid="factory-basics-handoff"]').exists()).toBe(false)
  })

  it('shows one handoff after canonical application, including a recovered applied blueprint', () => {
    for (const success of [false, true]) {
      const wrapper = render('APPLIED', success)
      expect(wrapper.findAll('[data-testid="factory-basics-handoff"]')).toHaveLength(1)
      expect(wrapper.text()).toContain('以下项目不会因蓝图应用成功而自动标记完成')
      wrapper.unmount()
    }
  })

  it('also shows the legacy successful result but never a failed result', () => {
    expect(render(null, true).findAll('[data-testid="factory-basics-handoff"]')).toHaveLength(1)
    expect(render(null).find('[data-testid="factory-basics-handoff"]').exists()).toBe(false)
  })

  it('gives usable configuration boundaries without inventing independent warehouse switches', () => {
    const wrapper = mount(FactoryBasicsHandoff)
    expect(wrapper.findAll('li')).toHaveLength(4)
    expect(wrapper.text()).toContain('包材不要求储存类型')
    expect(wrapper.text()).toContain('销售出货和生产领料目前共用物流默认仓')
    expect(wrapper.text()).toContain('多种箱规须在下单时明确选择')
    expect(wrapper.text()).toContain('BOM 和 Workflow 仍须独立确认发布')
    expect(wrapper.findAll('button, input, a')).toHaveLength(0)
  })
})
