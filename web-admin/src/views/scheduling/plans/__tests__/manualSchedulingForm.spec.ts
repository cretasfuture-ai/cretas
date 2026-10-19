import { describe, expect, it } from 'vitest';
import { buildManualSchedulePayload, validateManualSchedules } from '../manualSchedulingForm';

const valid = { batchId: 901, productionLineId: 'LINE-1', plannedQuantity: 10, sourceUnit: 'kg', plannedStartTime: '2026-10-03T08:00', plannedEndTime: '2026-10-03T09:00' };

describe('manual scheduling form guard', () => {
  it('requires explicit line, positive integer quantity, and increasing times', () => {
    expect(validateManualSchedules([valid])).toEqual([]);
    expect(validateManualSchedules([{ ...valid, productionLineId: '' }])[0]).toContain('未选择生产线');
    expect(validateManualSchedules([{ ...valid, plannedQuantity: 1.5 }])[0]).toContain('整数');
    expect(validateManualSchedules([{ ...valid, plannedEndTime: valid.plannedStartTime }])[0]).toContain('结束时间');
    expect(validateManualSchedules([{ ...valid, sourceUnit: '' }])[0]).toContain('基本单位');
  });

  it('builds the backend schedules shape without worker auto assignment', () => {
    expect(buildManualSchedulePayload('2026-10-03', [valid], '  note ')).toEqual({
      planDate: '2026-10-03', notes: 'note', schedules: [{ productionLineId: 'LINE-1', batchId: 901, sequenceOrder: 1, plannedStartTime: '2026-10-03T08:00:00', plannedEndTime: '2026-10-03T09:00:00', plannedQuantity: 10 }],
    });
  });

  it('rejects invalid local datetime instead of converting it or guessing a timezone', () => {
    expect(() => buildManualSchedulePayload('2026-10-03', [{ ...valid, plannedStartTime: '2026-10-03T08:00:00Z', plannedEndTime: '2026-10-03T09:00:00Z' }])).toThrow('有效的本地时间');
  });
});
