import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DETAIL = resolve(HERE, '../detail.vue');
const API = resolve(HERE, '../../../../api/processProduction.ts');

/**
 * 「分配工序给工人」的入口契约（2026-08-19 客户实报）。
 *
 * <pre>
 * 「我需要的是分配工序给工人的方式，现在是新建了一个工人」
 * 「因为工人改不了被分配的生产单和工序」
 * 「我没找到改的入口」
 * </pre>
 *
 * 后端一直有 `PUT /work-process-tasks/{id}`（Operation summary 逐字是
 * 「修改工序任务计划（**分配责任人** / 计划时间 / 备注）」），
 * **两个前端都没有入口** —— 教科书式的形态 B（机制在、没接上）。
 *
 * ⚠️ 局限说清楚：读的是 `detail.vue` 源码，不是挂载后的行为。该视图很大且依赖多个 API，
 * 挂载成本远高于本次改动。它挡得住「入口又没了」「逃生门被拿掉」「helper 只定义不调用」，
 * 挡不住渲染层被别的东西盖掉。
 */
describe('工序指派入口 —— 接线契约', () => {
  const view = readFileSync(DETAIL, 'utf8');
  const api = readFileSync(API, 'utf8');

  it('API 层有 updateWorkProcessTaskPlan, 且支持 clearAssignee', () => {
    expect(api).toContain('export function updateWorkProcessTaskPlan');
    expect(api).toContain('work-process-tasks/${taskId}');
    expect(api).toContain('clearAssignee');
  });

  it('批次详情把「负责人」变成了可指派的下拉', () => {
    expect(view).toContain('handleAssignTask(task');
    expect(view).toContain('updateWorkProcessTaskPlan(');
  });

  it('🔴 逃生门: 下拉必须 clearable, 且清空走 clearAssignee —— 否则派错就焊死', () => {
    // 指派是排他的(后端 403 + App 列表过滤), 没有「取消指派」就没有退路
    expect(view).toContain('clearAssignee: true');
    const selectBlock = view.slice(view.indexOf('class="task-assignee"'), view.indexOf('class="task-assignee"') + 1400);
    expect(selectBlock).toContain('clearable');
  });

  it('⛔ 整行有 openTaskDrawer, 指派区必须 @click.stop —— 否则一点就弹抽屉', () => {
    expect(view).toContain('class="task-assignee" @click.stop');
    expect(view).toContain('openTaskDrawer(task)');
  });

  it('🔴 候选人列表必须真的被加载 —— 只定义不调用的话下拉永远是空的', () => {
    // 定义 1 次 + onMounted 调用 1 次
    expect((view.match(/loadOperators/g) || []).length).toBe(2);
    expect(view).toContain("from '@/api/factory'");
  });

  it('已完工/已跳过的工序不给改派 —— 后端 updatePlan 对终态也是拒绝的', () => {
    expect(view).toContain('isTerminalTaskStatus(task.status)');
    expect(view).toContain("'COMPLETED', 'SKIPPED', 'CANCELLED'");
  });

  it('必须把「排他」这件事告诉配置的人 —— ⛔ 不能让他派完才发现别人报不了', () => {
    expect(view).toContain('只有该工人和主管能报这道工序');
    expect(view).toContain('谁都可以报工');
  });

  it('阳性对照: 锚点确实存在 —— 否则上面几条是恒真的', () => {
    expect(view).toContain('task-assignee-empty');
    expect(view).toContain('function handleAssignTask');
    expect(api).toContain('export interface WorkProcessTaskItem');
  });
});
