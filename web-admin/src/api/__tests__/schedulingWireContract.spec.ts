import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
const post = vi.fn();
vi.mock('../request', () => ({ get, post, put: vi.fn(), del: vi.fn() }));

describe('scheduling wire DTO mapping', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('maps LineScheduleDTO names to the UI model without inventing missing worker fields', async () => {
    get.mockResolvedValue({ success: true, data: {
      id: 'PLAN-1', lineSchedules: [{ id: 'S-1', planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: 9, batchNumber: 'B-1', sequenceOrder: 1, plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:00:00', status: 'pending', assignedWorkers: 3, plannedQuantity: 120, completedQuantity: 20, workerAssignments: [{ id: 'A-1', scheduleId: 'S-1', userId: 77, actualStartTime: '2026-10-19T08:00:00', status: 'assigned' }] }]
    }, message: 'OK' });
    const { getSchedulingPlan } = await import('../scheduling');
    const result = await getSchedulingPlan('F-REAL', 'PLAN-1');
    const schedule = result.data.lineSchedules[0];
    expect(schedule).toMatchObject({ targetQuantity: 120, assignedWorkerCount: 3, productionLineName: '一号线', batchNumber: 'B-1' });
    expect(schedule.workerAssignments[0]).toMatchObject({ userId: 77, checkedInAt: '2026-10-19T08:00:00' });
    expect(schedule.workerAssignments[0].userName).toBeUndefined();
    expect(schedule.workerAssignments[0].role).toBeUndefined();
  });

  it('sends AssignWorkerRequest.workerIds and maps the response', async () => {
    post.mockResolvedValue({ success: true, data: [{ id: 'A-2', scheduleId: 'S-1', userId: 88, status: 'assigned' }], message: 'OK' });
    const { assignWorkers } = await import('../scheduling');
    const result = await assignWorkers('F-REAL', { scheduleId: 'S-1', workerIds: [88] });
    expect(post).toHaveBeenCalledWith('/F-REAL/scheduling/workers/assign', { scheduleId: 'S-1', workerIds: [88] });
    expect(result.data[0]).toMatchObject({ userId: 88 });
  });
});
