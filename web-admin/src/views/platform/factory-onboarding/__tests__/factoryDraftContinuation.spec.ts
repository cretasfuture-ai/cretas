import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// Mount the actual page fragment so a lifecycle condition cannot silently hide
// the independent BOM / Workflow continuation after configuration was applied.
const page = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../index.vue'), 'utf8')
const fragment = page.match(/<FactoryBlueprintDraftsPanel\b[\s\S]*?\/>/)?.[0]
if (!fragment) throw new Error('Onboarding draft continuation is missing')

describe('saved blueprint draft continuation', () => {
  it.each(['VALIDATED', 'PUBLISHED', 'APPLIED'])('keeps independent publication available in %s', (lifecycleState) => {
    const wrapper = mount(defineComponent({
      template: fragment,
      data: () => ({ canonicalBlueprint: { lifecycleState, factoryName: '测试工厂' }, factoryId: 'TEST', factoryName: '', onFormalState: () => {} }),
      components: { FactoryBlueprintDraftsPanel: { template: '<section data-testid="continuation" />' } },
    }))
    expect(wrapper.find('[data-testid="continuation"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it.each(['DRAFT', 'INVALID', null])('keeps unvalidated state %s closed', (lifecycleState) => {
    const wrapper = mount(defineComponent({
      template: fragment,
      data: () => ({ canonicalBlueprint: { lifecycleState }, factoryId: 'TEST', factoryName: '', onFormalState: () => {} }),
      components: { FactoryBlueprintDraftsPanel: { template: '<section data-testid="continuation" />' } },
    }))
    expect(wrapper.find('[data-testid="continuation"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
