import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf8',
);

describe('Workflow 自动分叉与历史预览编辑闸', () => {
  it('已启用版本的保存动作明确走自动新版本，不要求先手工另存', () => {
    expect(source).toContain('保存草稿（自动新版本）');
    expect(source).toContain('保存会自动创建新的 Workflow 草稿版本');
    expect(source).toContain('无需先手工另存为版本');
    expect(source).toContain('>另存独立版本</el-button>');
  });

  it('历史预览才关闭编辑，草稿入口仍统一由 canEdit 控制', () => {
    expect(source).toContain('&& previewingVersion.value === null');
    expect(source).toContain(':nodes-draggable="canEdit"');
    expect(source).toContain(':nodes-connectable="canEdit"');
    expect(source.match(/:can-write="canEdit"/g)?.length).toBe(4);
    expect(source).toContain('function restorePreviewAsDraft');
  });

  it('停用失败必须把后端业务原因展示给操作者', () => {
    expect(source).toContain("ElMessage.error(workflowErrorMessage(error, 'Workflow 停用失败，请稍后重试'))");
    expect(source).toContain('responseData?.message');
    expect(source).toContain('responseData?.actionHint');
  });
});
