import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const DETAIL = resolve(HERE, '../detail.vue');
const API = resolve(HERE, '../../../../api/processProduction.ts');

/**
 * 缺陷 51: 生产计划列表新增「分配工序操作员」入口深链到本页 —— 本页负责滚动定位并
 * 高亮「工序明细」卡片。⛔ 不移动/删除既有的按批次分配入口(多批次生产仍要逐批分配),
 * 本契约同时钉住三条必须原样保留的既有行为(排他指派 403 / 取消负责人退出 / 终态禁改)。
 */
describe('批次详情深链定位 —— 接线契约 (缺陷51)', () => {
  const view = readFileSync(DETAIL, 'utf8');
  const api = readFileSync(API, 'utf8');

  it('深链判据复用 batches/assignmentDeepLink.ts, 与 plans 那侧同一个 query key', () => {
    expect(view).toContain("from './assignmentDeepLink'");
    expect(view).toContain('shouldFocusAssignmentSection(route.query)');
  });

  it('工序明细卡片有稳定选择器, 深链据此滚动 + 高亮', () => {
    // 🔴 不能只查 `data-testid="process-tasks-card"` 这个子串是否存在 —— 它同时会命中
    // 下面 querySelector 调用里的同名字符串, 卡片元素本身没写这个属性时这条断言仍会通过
    // (2026-09-02 实测: 单独删掉卡片上的 data-testid 变异不会让下面这条裸检查变红)。
    // 改成校验卡片**开标签这一整块**同时带 class 与 data-testid, 才是绑定到卡片本身。
    expect(view).toMatch(
      /<el-card[\s\S]{0,200}class="detail-card process-tasks-card"[\s\S]{0,200}data-testid="process-tasks-card"/,
    );
    expect(view).toContain(
      "document.querySelector<HTMLElement>('[data-testid=\"process-tasks-card\"]')",
    );
    expect(view).toContain('scrollIntoView(');
    expect(view).toContain("'is-deep-link-highlight': isAssignmentDeepLinkHighlighted");
    expect(view).toContain('&.is-deep-link-highlight');
  });

  it('高亮会自动褪去(不是永久改色), 且挂在 loadData 完成之后而不是并发触发', () => {
    expect(view).toContain('setTimeout(() => {\n    isAssignmentDeepLinkHighlighted.value = false;\n  }, 2600)');
    expect(view).toContain('void loadData().then(() => {');
    expect(view).toContain('void maybeFocusAssignmentFromQuery();');
  });

  // ⛔ 不移动/删除既有的批次级分配入口 (多批次生产仍要逐批次分配)。
  it('✅ 保留1: 指派仍是排他的 —— 后端 ReportAuthGuard 403 语义文案未被改写', () => {
    expect(view).toContain('只有该操作员本人和主管能报工');
    expect(api).toContain('export function updateWorkProcessTaskPlan');
    expect(api).toContain('work-process-tasks/${taskId}');
  });

  it('✅ 保留2: 「取消负责人（恢复未分配）」退出仍在', () => {
    expect(view).toContain('取消负责人（恢复未分配）');
    expect(view).toContain("value=\"__UNASSIGNED__\"");
  });

  it('✅ 保留3: 已完工工序的复选框仍禁用, 且原因文案未被改写', () => {
    expect(view).toContain('isTerminalTaskStatus(task.status) || assignmentSaving');
    expect(view).toContain('taskAssignmentDisabledReason(task.status)');
    const taskAssignment = readFileSync(resolve(HERE, '../taskAssignment.ts'), 'utf8');
    expect(taskAssignment).toContain('这道工序已经完工，不能再改负责人');
  });

  it('阳性对照: 批次级「分配操作员」按钮和批量勾选入口仍然存在(未被挪走)', () => {
    expect(view).toContain('分配操作员<span');
    expect(view).toContain('全选可分配工序');
    expect(view).toContain('function openAssignmentDialog');
  });
});
