import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
const post = vi.fn();
vi.mock('../request', () => ({ get, post }));

describe('scheduling capabilities API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the factory scoped endpoint and preserves the response envelope', async () => {
    const response = { success: true, data: { contractVersion: 'scheduling-capabilities/v1' }, message: 'OK' };
    get.mockResolvedValue(response);
    const { getSchedulingCapabilities } = await import('../scheduling');
    await expect(getSchedulingCapabilities('F-P05')).resolves.toBe(response);
    expect(get).toHaveBeenCalledWith('/F-P05/scheduling/capabilities');
  });
});

describe('DAG auto-schedule API contract (aiGeneration mode DAG_DURATION)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts plan ids to the factory scoped generate endpoint and keeps warnings + task timeline', async () => {
    post.mockResolvedValue({ success: true, message: '生成成功', data: {
      id: 'SP-1', factoryId: 'F-P05', planDate: '2026-10-19', status: 'draft',
      warnings: ['计划「PLAN-B」有工序缺预计工时：「蒸煮」'],
      lineSchedules: [{
        id: 'S-1', planId: 'SP-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: null,
        batchNumber: null, productionPlanId: 'PP-A', planNumber: 'PLAN-A', sequenceOrder: 0,
        plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:25:00', plannedQuantity: 12,
        status: 'pending',
        taskTimeline: [{ taskName: '分切', processOrder: 1, workProcessId: 'WP-CUT', start: '2026-10-19T08:00', end: '2026-10-19T08:30', status: 'SCHEDULED' }],
      }],
    } });
    const { generateSchedule } = await import('../scheduling');
    const response = await generateSchedule('F-P05', { planDate: '2026-10-19', planIds: ['PP-A', 'PP-B'] });

    expect(post).toHaveBeenCalledWith('/F-P05/scheduling/generate', { planDate: '2026-10-19', planIds: ['PP-A', 'PP-B'] });
    expect(response.data.warnings).toEqual(['计划「PLAN-B」有工序缺预计工时：「蒸煮」']);
    const schedule = response.data.lineSchedules[0];
    expect(schedule.productionPlanId).toBe('PP-A');
    expect(schedule.planNumber).toBe('PLAN-A');
    expect(schedule.targetQuantity).toBe(12);
    expect(schedule.taskTimeline?.[0]).toMatchObject({ taskName: '分切', start: '2026-10-19T08:00', status: 'SCHEDULED' });
  });

  it('reads the pending production plans list (the pending-batches endpoint returns plans)', async () => {
    get.mockResolvedValue({ success: true, data: [] });
    const { getPendingProductionPlans } = await import('../scheduling');
    await getPendingProductionPlans('F-P05');
    expect(get).toHaveBeenCalledWith('/F-P05/scheduling/pending-batches');
  });
});
