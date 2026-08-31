import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf8',
);

describe('Workflow 辅料/包材详情零写门禁', () => {
  it('Cell 详情和行点击只打开只读抽屉，不直接进入 ensure-draft 编辑路径', () => {
    expect(source).toContain(`@open-detail="openBomDetail('AUXILIARY', slotProps.data.processNodeId)"`);
    expect(source).toContain(`@edit-row="(rowId) => openBomDetail('AUXILIARY', slotProps.data.processNodeId, rowId)"`);
    expect(source).toContain(`@edit-row="(rowId) => openBomDetail('PACKAGING', slotProps.data.outputNodeId, rowId)"`);
    expect(source).toContain('data-testid="workflow-bom-readonly-detail"');
  });

  it('只有用户明确点开始编辑后才调用既有写入口', () => {
    const beginEditBody = source.match(/function beginEditingBomDetail[\s\S]*?\n}\n/)?.[0] ?? '';
    expect(beginEditBody).toContain('openAuxiliaryEditor(ownerNodeId, rowId)');
    expect(beginEditBody).toContain('openPackagingEditor(ownerNodeId, rowId)');
    const openDetailBody = source.match(/function openBomDetail[\s\S]*?\n}\n/)?.[0] ?? '';
    expect(openDetailBody).not.toContain('openAuxiliaryEditor');
    expect(openDetailBody).not.toContain('openPackagingEditor');
    expect(openDetailBody).not.toContain('resolveWritableRecipeId');
  });
});
