import { beforeEach, describe, expect, it, vi } from 'vitest';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
vi.mock('../request', () => ({ get, post, put }));

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

describe('daily-capacity auto-schedule API contract (aiGeneration mode DAILY_CAPACITY)', () => {
  beforeEach(() => vi.clearAllMocks());

  it('posts plan ids + capacity overrides to the factory scoped generate endpoint and keeps warnings + daily allocations', async () => {
    post.mockResolvedValue({ success: true, message: '生成成功', data: {
      id: 'SP-1', factoryId: 'F-P05', planDate: '2026-10-19', status: 'draft', outcome: 'SCHEDULED',
      warnings: ['「卤猪蹄」没有填日产能，按历史估算 150 袋/天'],
      lineSchedules: [{
        id: 'S-1', planId: 'SP-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: null,
        batchNumber: null, productionPlanId: 'PP-A', planNumber: 'PLAN-A', sequenceOrder: 0,
        plannedStartTime: '2026-10-19T00:00:00', plannedEndTime: '2026-10-20T23:59:00', plannedQuantity: 300,
        status: 'pending',
        dailyAllocations: {
          unit: '袋', capacityPerDay: 150, capacitySource: 'EXPERIENCE', capacitySourceLabel: '按历史估算',
          plannedQuantity: 300, reportedQuantity: 0, remainingQuantity: 300,
          days: [{ date: '2026-10-19', quantity: 150, shareOfDay: 1 }, { date: '2026-10-20', quantity: 150, shareOfDay: 1 }],
        },
      }],
    } });
    const { generateSchedule } = await import('../scheduling');
    const body = {
      planDate: '2026-10-19', planIds: ['PP-A', 'PP-B'],
      dailyCapacityOverrides: [{ productTypeId: 'PT-B', quantityPerDay: 200, unit: '袋' }],
    };
    const response = await generateSchedule('F-P05', body);

    expect(post).toHaveBeenCalledWith('/F-P05/scheduling/generate', body);
    expect(response.data.outcome).toBe('SCHEDULED');
    expect(response.data.warnings).toEqual(['「卤猪蹄」没有填日产能，按历史估算 150 袋/天']);
    const schedule = response.data.lineSchedules[0];
    expect(schedule.productionPlanId).toBe('PP-A');
    expect(schedule.targetQuantity).toBe(300);
    expect(schedule.dailyAllocations?.capacitySourceLabel).toBe('按历史估算');
    expect(schedule.dailyAllocations?.days.map(d => `${d.date}=${d.quantity}`)).toEqual(['2026-10-19=150', '2026-10-20=150']);
  });

  it('passes the capacity-required list through untouched (nothing was saved, id is null)', async () => {
    post.mockResolvedValue({ success: true, data: {
      id: null, outcome: 'CAPACITY_REQUIRED', lineSchedules: [],
      capacityRequired: [{ productTypeId: 'PT-NEW', productName: '新品', unit: '袋', jointPlan: false, question: '「新品」一天能做多少 袋？', reason: 'MISSING', planNumbers: ['PLAN-NEW'] }],
    } });
    const { generateSchedule } = await import('../scheduling');
    const response = await generateSchedule('F-P05', { planDate: '2026-10-19', planIds: ['PP-NEW'] });

    expect(response.data.id).toBeNull();
    expect(response.data.outcome).toBe('CAPACITY_REQUIRED');
    expect(response.data.capacityRequired?.[0]).toMatchObject({ productTypeId: 'PT-NEW', unit: '袋', planNumbers: ['PLAN-NEW'] });
    expect(response.data.lineSchedules).toEqual([]);
  });

  it('writes and clears a product daily capacity through its own endpoint', async () => {
    put.mockResolvedValue({ success: true, data: {} });
    const { setProductDailyCapacity } = await import('../scheduling');
    await setProductDailyCapacity('F-P05', 'PT 1', 300, '袋');
    await setProductDailyCapacity('F-P05', 'PT 1', null, null);
    expect(put).toHaveBeenNthCalledWith(1, '/F-P05/product-types/PT%201/daily-capacity', { quantityPerDay: 300, unit: '袋' });
    expect(put).toHaveBeenNthCalledWith(2, '/F-P05/product-types/PT%201/daily-capacity', { quantityPerDay: null, unit: null });
  });

  it('reads the pending production plans list (the pending-batches endpoint returns plans)', async () => {
    get.mockResolvedValue({ success: true, data: [] });
    const { getPendingProductionPlans } = await import('../scheduling');
    await getPendingProductionPlans('F-P05');
    expect(get).toHaveBeenCalledWith('/F-P05/scheduling/pending-batches');
  });
});
