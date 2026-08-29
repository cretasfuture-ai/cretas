import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DETAIL = resolve(HERE, '../detail.vue');
const API = resolve(HERE, '../../../../api/processProduction.ts');

/**
 * 批次工序负责人分配入口的接线契约。
 *
 * 入口曾补成“每行下拉即刻写入”，但十道同人仍需点十次，也没有确认、部分失败明细或
 * 保存后回读。本契约钉住批量入口和权限边界；逐道行为、半成功和回读由
 * taskAssignment.spec.ts 验证。
 */
describe('工序指派入口 —— 接线契约', () => {
  const view = readFileSync(DETAIL, 'utf8');
  const api = readFileSync(API, 'utf8');

  it('API 层使用现有单任务端点，并保留 clearAssignee 逃生门', () => {
    expect(api).toContain('export function updateWorkProcessTaskPlan');
    expect(api).toContain('work-process-tasks/${taskId}');
    expect(api).toContain('clearAssignee');
  });

  it('批次详情提供勾选、全选与批量分配入口', () => {
    expect(view).toContain('v-model="selectedTaskIds"');
    expect(view).toContain('全选可分配工序');
    expect(view).toContain('分配操作员<span');
    expect(view).toContain('openAssignmentDialog');
  });

  it('🔴 保存前明确确认 N 道和姓名，不再一下拉就写', () => {
    expect(view).toContain('确认分配操作员');
    expect(view).toContain('将 ${selectedTasks.value.length} 道工序分给');
    expect(view).not.toContain('@change="(v: number | undefined) => handleAssignTask');
  });

  it('⛔ 整行有详情抽屉，勾选与指派区必须阻止冒泡', () => {
    expect(view).toContain('class="task-assignee" @click.stop');
    expect(view).toContain('class="task-select" @click.stop');
    expect(view).toContain('openTaskDrawer(task)');
  });

  it('候选人名单被真实加载，且加载失败会明确阻止保存', () => {
    expect((view.match(/loadOperators/g) || []).length).toBe(2);
    expect(view).toContain("from '@/api/factory'");
    expect(view).toContain('operatorsError');
    expect(view).toContain('操作员名单没有加载成功，不能进行新的指派');
  });

  it('终态不给改派，并展示后端规则对应的白话原因', () => {
    expect(view).toContain('isTerminalTaskStatus(task.status)');
    expect(view).toContain('taskAssignmentDisabledReason(task.status)');
  });

  it('候选人只来自当前工厂有效操作员/报工员，禁止自由输入编号', () => {
    expect(view).toContain("op.roleCode === 'operator'");
    expect(view).toContain("op.roleCode === 'yield_operator'");
    expect(view).toContain('op.isActive !== false');
    expect(view).toContain('名单仅来自当前工厂有效的操作员和报工员，不能手填用户编号');
    expect(view).not.toContain('allow-create');
  });

  it('没有 production 写权限时没有编辑入口', () => {
    expect(view).toContain('v-if="canWrite && workProcessTasks.length > 0"');
    expect(view).toContain('v-if="canWrite"');
    expect(view).toContain("permissionStore.canWrite('production')");
  });

  it('明确说明指派排他和半成功边界', () => {
    expect(view).toContain('只有该操作员本人和主管能报工');
    expect(view).toContain('已成功的不会回滚');
    expect(view).toContain('失败明细');
  });

  it('阳性对照：视图和 API 锚点确实存在', () => {
    expect(view).toContain('task-assignee-empty');
    expect(view).toContain('function saveAssignments');
    expect(api).toContain('export interface WorkProcessTaskItem');
  });
});
