import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

describe('approval workflow business UX contract', () => {
  const catalog = source('src/views/system/approval-chains/list.vue')
  const canvas = source('src/views/platform/canvas-editor/index.vue')
  const editor = source('src/views/platform/approval-workflow-editor/index.vue')
  const properties = source(
    'src/views/platform/approval-workflow-editor/components/PropertyPanel.vue',
  )
  const ai = source(
    'src/views/platform/approval-workflow-editor/components/ApprovalWorkflowAIComposer.vue',
  )

  it('opens a business overview before a scoped approval canvas', () => {
    expect(catalog).toContain('<h1>审批业务</h1>')
    expect(catalog).toContain('label="部门"')
    expect(catalog).toContain('label="审批业务"')
    expect(catalog).toContain('label="审批状态"')
    expect(catalog).toContain('label="版本状态"')
    expect(catalog).toContain('label="最后更新"')
    expect(catalog).toContain('无需审批')
    expect(canvas).toContain('v-else-if="approvalBusinessLocked"')
    expect(canvas).toContain(':lock-decision-type="true"')
  })

  it('uses text operations and implements the expected canvas interactions', () => {
    for (const label of [
      '拖动画布',
      '批量选择',
      '撤销',
      '重做',
      '缩小',
      '放大',
      '适应画布',
      '自动布局',
      '删除所选',
    ]) {
      expect(editor).toContain(label)
    }
    expect(editor).not.toContain(':icon=')
    expect(editor).not.toContain('<Controls')
    expect(editor).toContain('<button\n            v-for="schema in nodeSchemas"')
    expect(editor).toContain('@click="addPaletteNode(schema)"')
    expect(editor).toContain('@node-drag-start="captureHistory"')
    expect(editor).toContain(':is-valid-connection="isValidConnection"')
    expect(editor).toContain('edge.id !== connectionId')
    expect(editor).toContain('@selection-end="onSelectionEnd"')
  })

  it('keeps basic properties visible and advanced properties collapsed', () => {
    expect(properties).toContain('基础配置')
    expect(properties).toContain('展开高级配置')
    expect(properties).toContain('v-show="advancedOpen"')
    expect(properties).toContain('审批时限（分钟）')
    expect(properties).toContain('限定部门（可选）')
    expect(properties).toContain('超时转派（可选）')
  })

  // 2026-10-08 organizer 裁定 (965-3): AI 写配置一律「预览 → 用户确认 → 执行」, 不再直接套用。
  // 行为由 ApprovalWorkflowAIComposer.stdLayout.spec 真挂载守着; 这里只钉接线。
  it('places AI at the canvas bottom and applies its draft only after the user confirms the preview', () => {
    expect(editor).toContain('class="approval-ai-dock"')
    expect(editor).toContain(':preview-spec="previewAiSpec"')
    expect(editor).toContain(':apply-spec="applyAiSpec"')
    expect(ai).toContain("diff.type === 'APPROVAL_WORKFLOW_SPEC'")
    expect(ai).toContain('props.previewSpec(spec)')
    expect(ai).not.toContain('props.applySpec(spec)')
    expect(ai).toContain('await props.applySpec(proposedSpec)')
    expect(ai).toContain('应用配置')
    expect(ai).not.toContain('<el-icon')
    expect(ai).toContain('AI 对话')
    expect(ai).toContain('快捷问题')
  })
})
