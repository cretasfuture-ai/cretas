import type { ProcessTaskItem } from '../../../services/api/processTaskApiClient';
import {
  buildReportableTaskIds,
  compareTasksByWorkOrder,
  extractProcessTaskList,
  getBatchKey,
  getTaskBatchId,
  getTaskWorkProcessTaskId,
  pickCurrentReportTask,
} from '../../../utils/processTaskFlow';

const baseTask = (overrides: Partial<ProcessTaskItem>): ProcessTaskItem => ({
  id: 'task-1',
  factoryId: 'F006',
  productTypeId: 'P001',
  workProcessId: 'WP-1',
  unit: 'kg',
  plannedQuantity: 100,
  completedQuantity: 0,
  pendingQuantity: 100,
  status: 'PENDING',
  createdAt: '2026-06-12T08:00:00Z',
  ...overrides,
});

describe('processTaskFlow', () => {
  it('extracts batch ids from explicit fields and BATCH productionRunId', () => {
    expect(getTaskBatchId(baseTask({ batchId: 12 }))).toBe(12);
    expect(getTaskBatchId(baseTask({ productionBatchId: 34 }))).toBe(34);
    expect(getTaskBatchId(baseTask({ productionRunId: 'BATCH-56' }))).toBe(56);
    expect(getTaskBatchId(baseTask({ productionRunId: 'RUN-56' }))).toBeNull();
  });

  it('builds stable batch keys and work process task ids', () => {
    expect(getBatchKey(baseTask({ batchId: 12 }))).toBe('batch:12');
    expect(getBatchKey(baseTask({ productionRunId: 'BATCH-56' }))).toBe('BATCH-56');
    expect(getBatchKey(baseTask({}))).toBeNull();
    expect(getTaskWorkProcessTaskId(baseTask({ workProcessTaskId: 88 }))).toBe(88);
  });

  it('sorts newer batches first, then process order, status and creation time', () => {
    const olderBatch = baseTask({ id: 'older', batchId: 10, processOrder: 1 });
    const newerBatch = baseTask({ id: 'newer', batchId: 20, processOrder: 1 });
    const secondProcess = baseTask({ id: 'second', batchId: 20, processOrder: 2 });
    const inProgress = baseTask({ id: 'active', batchId: 20, processOrder: 1, status: 'IN_PROGRESS' });

    const sorted = [olderBatch, secondProcess, newerBatch, inProgress].sort(compareTasksByWorkOrder);

    expect(sorted.map(task => task.id)).toEqual(['active', 'newer', 'second', 'older']);
  });

  it('marks only the next unfinished task in each batch as reportable', () => {
    const tasks = [
      baseTask({ id: 'b1-step1', batchId: 1, processOrder: 1, status: 'COMPLETED' }),
      baseTask({ id: 'b1-step2', batchId: 1, processOrder: 2, status: 'PENDING' }),
      baseTask({ id: 'b1-step3', batchId: 1, processOrder: 3, status: 'PENDING' }),
      baseTask({ id: 'b2-step1', batchId: 2, processOrder: 1, status: 'IN_PROGRESS' }),
      baseTask({ id: 'b2-step2', batchId: 2, processOrder: 2, status: 'PENDING' }),
    ].sort(compareTasksByWorkOrder);

    expect([...buildReportableTaskIds(tasks)].sort()).toEqual(['b1-step2', 'b2-step1']);
  });

  it('allows unbatched in-progress and supplementing tasks, but not pending tasks', () => {
    const ids = buildReportableTaskIds([
      baseTask({ id: 'active', status: 'IN_PROGRESS' }),
      baseTask({ id: 'supplement', status: 'SUPPLEMENTING' }),
      baseTask({ id: 'pending', status: 'PENDING' }),
    ]);

    expect(ids.has('active')).toBe(true);
    expect(ids.has('supplement')).toBe(true);
    expect(ids.has('pending')).toBe(false);
  });

  it('picks the current report task after applying ordering and reportability rules', () => {
    const current = pickCurrentReportTask([
      baseTask({ id: 'later', batchId: 5, processOrder: 3, status: 'PENDING' }),
      baseTask({ id: 'done', batchId: 5, processOrder: 1, status: 'COMPLETED' }),
      baseTask({ id: 'current', batchId: 5, processOrder: 2, status: 'PENDING' }),
    ]);

    expect(current?.id).toBe('current');
  });

  it('extracts task lists from paged, array and raw array responses', () => {
    const task = baseTask({ id: 'x' });

    expect(extractProcessTaskList({ data: { content: [task] } })).toEqual([task]);
    expect(extractProcessTaskList({ data: [task] })).toEqual([task]);
    expect(extractProcessTaskList([task])).toEqual([task]);
    expect(extractProcessTaskList({ data: { content: null } })).toEqual([]);
  });

  // 缺口 04: 后端按工艺图算「能不能报」(上游产出的料到了就解锁)
  it('follows the backend unlocked verdict: parallel branches are reportable together', () => {
    const tasks = [
      baseTask({ id: 'thaw', batchId: 9, processOrder: 1, unlocked: true }),
      baseTask({ id: 'spice', batchId: 9, processOrder: 2, unlocked: true }),
      baseTask({ id: 'braise', batchId: 9, processOrder: 3, unlocked: false, blockedReason: '上游工序「解冻」还没有产出' }),
    ];
    const ids = buildReportableTaskIds(tasks);
    // 原来只放 thaw; 现在两条并行分支都能报, 等料的那道不能报
    expect([...ids].sort()).toEqual(['spice', 'thaw']);
  });

  it('unlocks a downstream step while its upstream is still in progress', () => {
    const tasks = [
      baseTask({ id: 'thaw', batchId: 9, processOrder: 1, status: 'IN_PROGRESS', unlocked: true }),
      baseTask({ id: 'braise', batchId: 9, processOrder: 2, unlocked: true }),
    ];
    expect([...buildReportableTaskIds(tasks)].sort()).toEqual(['braise', 'thaw']);
  });

  it('falls back to first-step-only when the backend gave no verdict', () => {
    const tasks = [
      baseTask({ id: 'a', batchId: 9, processOrder: 1 }),
      baseTask({ id: 'b', batchId: 9, processOrder: 2 }),
    ];
    expect([...buildReportableTaskIds(tasks)]).toEqual(['a']);
  });
});
