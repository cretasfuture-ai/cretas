import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf8',
);
const apiSource = readFileSync(
  resolve(import.meta.dirname, '../workflowApi.ts'),
  'utf8',
);

describe('Workflow 自动分叉与历史预览编辑闸', () => {
  it('用户只管理当前配置，生产历史由真实报工自动形成', () => {
    expect(source).toContain("const saveActionLabel = computed(() => '保存当前配置')");
    expect(source).toContain("publishActionCompleted.value ? '当前配置已生效' : '更新并应用'");
    expect(source).toContain('不需要另存版本');
    expect(source).not.toContain('>另存独立版本</el-button>');
    expect(source).not.toContain('data-testid="snapshot-workflow"');
    expect(apiSource).not.toContain('snapshotProductProcessWorkflow');
    expect(apiSource).not.toContain('/snapshot');
    expect(source).toContain('已经报工的计划保留旧配置并进入生产版本记录');
  });

  it('历史预览才关闭编辑，草稿入口仍统一由 canEdit 控制', () => {
    expect(source).toContain('&& previewingVersion.value === null');
    expect(source).toContain(':nodes-draggable="canEdit"');
    expect(source).toContain(':nodes-connectable="canEdit"');
    expect(source.match(/:can-write="canEdit"/g)?.length).toBe(4);
    expect(source).toContain('function restorePreviewAsDraft');
  });

  it('恢复历史版本先读取当前 envelope，再只替换历史图内容', () => {
    expect(source).toContain('const current = await getProductProcessWorkflow(identity.factoryId, identity.productTypeId)');
    expect(source).toContain('...toPlainWorkflowValue(current.data)');
    expect(source).toContain('nodes: historicalGraph.nodes');
    expect(source).toContain('previewingVersion.value = null');
  });

  it('保存响应如实提示服务端是否自动分叉', () => {
    expect(source).toContain('response.data.autoForked');
    expect(source).toContain('正在报工的旧配置 v${response.data.forkedFromVersion ?? \'?\'} 已自动保留');
  });

  it('更新生效后展示计划改绑结果，版本记录展示绑定计划', () => {
    expect(source).toContain('response.data.planRebind');
    expect(source).toContain('个未报工计划已同步更新');
    expect(source).toContain('个已报工计划保留旧版');
    expect(source).toContain('v.productionPlans');
    expect(source).toContain('已开始报工 · 固定此版本');
  });

  it('停用失败必须把后端业务原因展示给操作者', () => {
    expect(source).toContain("ElMessage.error(workflowErrorMessage(error, 'Workflow 停用失败，请稍后重试'))");
    expect(source).toContain('responseData?.message');
    expect(source).toContain('responseData?.actionHint');
  });
});
