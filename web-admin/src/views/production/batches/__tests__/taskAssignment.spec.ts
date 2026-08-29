import { describe, expect, it, vi } from 'vitest';
import type { WorkProcessTaskItem } from '@/api/processProduction';
import { assignTasksWithReadback, taskAssignmentDisabledReason } from '../taskAssignment';

function task(id: number, processOrder: number, status = 'PENDING'): WorkProcessTaskItem {
  return {
    id,
    factoryId: 'LIUSHANMEN',
    productionBatchId: 10883,
    productWorkProcessId: id,
    workProcessId: `WP-${id}`,
    productTypeId: 'PRODUCT-1',
    processOrder,
    status,
    processName: `工序${processOrder}`,
    processCategory: null,
    plannedQuantity: 1,
    plannedUnit: 'kg',
    plannedStartAt: null,
    plannedEndAt: null,
    estimatedMinutes: null,
    actualQuantity: null,
    actualStartAt: null,
    actualEndAt: null,
    actualMinutes: null,
    assignedTo: null,
    completedBy: null,
    completedAt: null,
    standardYieldMin: null,
    standardYieldMax: null,
    outputUnit: 'kg',
    notes: null,
    createdAt: '2026-08-29T00:00:00Z',
    updatedAt: '2026-08-29T00:00:00Z',
  };
}

describe('批次工序负责人分配', () => {
  it('只更新明确选择的任务，并在批次回读 10/10 后才判成功', async () => {
    const selected = Array.from({ length: 10 }, (_, index) => task(2001 + index, index + 1));
    const untouched = task(2100, 11);
    const updateTask = vi.fn(async (taskId: number) => ({
      success: true,
      data: { ...selected.find((item) => item.id === taskId)!, assignedTo: 1654 },
    }));
    const reloadTasks = vi.fn(async () => ({
      success: true,
      data: [...selected.map((item) => ({ ...item, assignedTo: 1654 })), untouched],
    }));

    const result = await assignTasksWithReadback({
      tasks: selected,
      assignedTo: 1654,
      updateTask,
      reloadTasks,
    });

    expect(updateTask).toHaveBeenCalledTimes(10);
    expect(updateTask.mock.calls.map(([taskId]) => taskId)).toEqual(selected.map((item) => item.id));
    expect(result.succeeded).toHaveLength(10);
    expect(result.failed).toEqual([]);
    expect(result.refreshedTasks?.find((item) => item.id === untouched.id)?.assignedTo).toBeNull();
  });

  it('局部失败逐道列出，不能把 2 成功 + 1 失败报成整体成功', async () => {
    const selected = [task(3001, 1), task(3002, 2), task(3003, 3)];
    const updateTask = vi.fn(async (taskId: number) => taskId === 3002
      ? { success: false, message: '该工序已被锁定' }
      : { success: true, data: { ...selected.find((item) => item.id === taskId)!, assignedTo: 1654 } });

    const result = await assignTasksWithReadback({
      tasks: selected,
      assignedTo: 1654,
      updateTask,
      reloadTasks: async () => ({
        success: true,
        data: selected.map((item) => ({ ...item, assignedTo: item.id === 3002 ? null : 1654 })),
      }),
    });

    expect(result.succeeded.map((item) => item.id)).toEqual([3001, 3003]);
    expect(result.failed).toEqual([expect.objectContaining({ taskId: 3002, reason: '该工序已被锁定' })]);
  });

  it('接口 200 但回读负责人不一致时仍判失败', async () => {
    const selected = [task(4001, 1)];
    const result = await assignTasksWithReadback({
      tasks: selected,
      assignedTo: 1654,
      updateTask: async () => ({ success: true, data: { ...selected[0], assignedTo: 1654 } }),
      reloadTasks: async () => ({ success: true, data: selected }),
    });

    expect(result.succeeded).toEqual([]);
    expect(result.failed).toEqual([expect.objectContaining({
      taskId: 4001,
      reason: '保存后回读的负责人不一致',
    })]);
  });

  it('取消负责人走显式 clearAssignee，并回读 null', async () => {
    const selected = [{ ...task(5001, 1), assignedTo: 1654 }];
    const updateTask = vi.fn(async () => ({ success: true, data: { ...selected[0], assignedTo: null } }));

    const result = await assignTasksWithReadback({
      tasks: selected,
      assignedTo: null,
      updateTask,
      reloadTasks: async () => ({ success: true, data: [{ ...selected[0], assignedTo: null }] }),
    });

    expect(updateTask).toHaveBeenCalledWith(5001, { clearAssignee: true });
    expect(result.succeeded).toHaveLength(1);
  });

  it('终态给出白话原因，且发送前就拒绝', async () => {
    expect(taskAssignmentDisabledReason('COMPLETED')).toBe('这道工序已经完工，不能再改负责人');
    expect(taskAssignmentDisabledReason('IN_PROGRESS')).toBeNull();
    const updateTask = vi.fn();
    const result = await assignTasksWithReadback({
      tasks: [task(6001, 1, 'COMPLETED')],
      assignedTo: 1654,
      updateTask,
      reloadTasks: vi.fn(),
    });

    expect(updateTask).not.toHaveBeenCalled();
    expect(result.failed[0].reason).toContain('已经完工');
  });
});
