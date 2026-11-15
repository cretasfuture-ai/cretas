import type { WorkProcessTaskItem } from '@/api/processProduction';

export const TERMINAL_TASK_STATUSES = ['COMPLETED', 'SKIPPED', 'CANCELLED'] as const;

export function taskAssignmentDisabledReason(status: string | null | undefined): string | null {
  const normalized = String(status || '').toUpperCase();
  if (normalized === 'COMPLETED') return '这道工序已经完工，不能再改负责人';
  if (normalized === 'SKIPPED') return '这道工序已经跳过，不能再改负责人';
  if (normalized === 'CANCELLED') return '这道工序已经取消，不能再改负责人';
  return null;
}

export interface AssignmentFailure {
  taskId: number;
  processOrder: number;
  processName: string;
  reason: string;
}

export interface AssignmentResult {
  succeeded: WorkProcessTaskItem[];
  failed: AssignmentFailure[];
  refreshedTasks: WorkProcessTaskItem[] | null;
}

interface ApiResult<T> {
  success: boolean;
  data?: T;
  message?: string;
}

interface AssignTasksOptions {
  tasks: WorkProcessTaskItem[];
  assignedTo: number | null;
  updateTask: (
    taskId: number,
    payload: { assignedTo?: number; clearAssignee?: true },
  ) => Promise<ApiResult<WorkProcessTaskItem>>;
  reloadTasks: () => Promise<ApiResult<WorkProcessTaskItem[]>>;
}

function taskName(task: WorkProcessTaskItem) {
  return task.processName || `第${task.processOrder}道工序`;
}

function failureFor(task: WorkProcessTaskItem, reason: string): AssignmentFailure {
  return {
    taskId: task.id,
    processOrder: task.processOrder,
    processName: taskName(task),
    reason,
  };
}

function errorMessage(error: unknown) {
  return error instanceof Error && error.message ? error.message : '请求失败';
}

/**
 * 逐道保存，再用批次任务列表做一次服务端回读。
 *
 * 后端目前没有批量事务端点，所以半成功是合法运行态。调用方必须把 failed 明细展示给用户，
 * 不能用一个“批量成功” toast 把已经写入和没有写入的工序混在一起。
 */
export async function assignTasksWithReadback(options: AssignTasksOptions): Promise<AssignmentResult> {
  const succeeded: WorkProcessTaskItem[] = [];
  const failed: AssignmentFailure[] = [];
  const expectedAssignee = options.assignedTo;

  for (const task of options.tasks) {
    const disabledReason = taskAssignmentDisabledReason(task.status);
    if (disabledReason) {
      failed.push(failureFor(task, disabledReason));
      continue;
    }

    try {
      const response = await options.updateTask(
        task.id,
        expectedAssignee == null ? { clearAssignee: true } : { assignedTo: expectedAssignee },
      );
      if (!response.success || !response.data) {
        failed.push(failureFor(task, response.message || '保存失败'));
        continue;
      }
      if ((response.data.assignedTo ?? null) !== expectedAssignee) {
        failed.push(failureFor(task, '保存响应中的负责人不一致'));
        continue;
      }
      succeeded.push(task);
    } catch (error) {
      failed.push(failureFor(task, errorMessage(error)));
    }
  }

  if (succeeded.length === 0) {
    return { succeeded, failed, refreshedTasks: null };
  }

  let refreshedTasks: WorkProcessTaskItem[] | null = null;
  try {
    const readback = await options.reloadTasks();
    if (!readback.success || !Array.isArray(readback.data)) {
      const reason = readback.message || '保存后回读失败，不能确认负责人已生效';
      failed.push(...succeeded.map((task) => failureFor(task, reason)));
      succeeded.splice(0, succeeded.length);
    } else {
      refreshedTasks = readback.data;
      const byId = new Map(refreshedTasks.map((task) => [task.id, task]));
      for (let index = succeeded.length - 1; index >= 0; index -= 1) {
        const task = succeeded[index];
        const actual = byId.get(task.id);
        if (!actual || (actual.assignedTo ?? null) !== expectedAssignee) {
          failed.push(failureFor(task, '保存后回读的负责人不一致'));
          succeeded.splice(index, 1);
        }
      }
    }
  } catch (error) {
    const reason = `保存后回读失败：${errorMessage(error)}`;
    failed.push(...succeeded.map((task) => failureFor(task, reason)));
    succeeded.splice(0, succeeded.length);
  }

  return { succeeded, failed, refreshedTasks };
}
