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

  // 2026-08-28 评审 Critical 返工: 金检/X光两道工序各自都有一个 isRework 产出项。
  // 调用方(屏幕层)有责任只传入"当前工序"的选项 —— 这个函数不做工序过滤, 也不知道
  // "当前工序"是谁。这条测试钉住"调用方违反契约、传入 2 个 isRework 项"时的行为:
  // 明确(last-write-wins), 不是静默丢数据的黑盒。
  it('传入两个 isRework 项(违反"至多一个"契约)且都填了数 → 两条都进 semiOutputs, defectQuantity 取最后一条(明确的 last-write-wins, 不是随机丢弃)', () => {
    const twoReworkOptions = [
      { taskId: 1, processName: '金检', semiCode: 'PTSEMI-JJ-REWORK', processOrder: 9, isRework: true },
      { taskId: 2, processName: 'X光', semiCode: 'PTSEMI-XG-REWORK', processOrder: 10, isRework: true },
    ];
    const r = buildSemiOutputsPayload(twoReworkOptions, {
      'PTSEMI-JJ-REWORK': '30',
      'PTSEMI-XG-REWORK': '20',
    });
    // 两条产出都保留 (不会被静默吞掉)
    expect(r.semiOutputs).toHaveLength(2);
    expect(r.semiOutputs).toEqual(
      expect.arrayContaining([
        { semiCode: 'PTSEMI-JJ-REWORK', quantity: 30, isRework: true },
        { semiCode: 'PTSEMI-XG-REWORK', quantity: 20, isRework: true },
      ]),
    );
    // defectQuantity 只能有一个值, 契约是"取最后一条" —— 明确, 不是未定义行为
    expect(r.defectQuantity).toBe(20);
  });
});
