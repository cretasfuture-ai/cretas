import { buildSemiOutputsPayload } from '../../../screens/processing/yieldStepOutputPayload';

describe('多产出提交载荷', () => {
  const options = [
    { taskId: 1, processName: '金检', semiCode: 'PTSEMI-LSM-0010', processOrder: 9, isRework: false },
    { taskId: 1, processName: '金检', semiCode: 'PTSEMI-LSM-0003', processOrder: 9, isRework: true },
  ];

  it('两个产出都填了 → 两条都带上, 并把回料那条的量作为 defectQuantity', () => {
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950', 'PTSEMI-LSM-0003': '50' });
    expect(r.semiOutputs).toHaveLength(2);
    expect(r.defectQuantity).toBe(50);
  });

  it('回料没填 → defectQuantity 是 undefined 而不是 0', () => {
    // 🔴「没清点」不许被写成「确认零不良」—— 这是 2026-08-26 事故的核心。
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950' });
    expect(r.semiOutputs).toHaveLength(1);
    expect(r.defectQuantity).toBeUndefined();
  });

  it('回料明确填 0 → defectQuantity 是 0 (确认零不良), 但不生成入账条目', () => {
    const r = buildSemiOutputsPayload(options, { 'PTSEMI-LSM-0010': '950', 'PTSEMI-LSM-0003': '0' });
    expect(r.semiOutputs).toHaveLength(1);
    expect(r.defectQuantity).toBe(0);
  });

  it('没有任何产出选项 → 全部 undefined, 走旧路径', () => {
    const r = buildSemiOutputsPayload([], {});
    expect(r.semiOutputs).toBeUndefined();
    expect(r.defectQuantity).toBeUndefined();
  });
});
