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

  // 2026-08-28 整分支终审 I-4: 这条测试原来钉的是 last-write-wins ("defectQuantity 取最后一条")。
  //
  // ⚠️ 为什么改: 那个契约靠 Javadoc 声明「同一次调用里最多应有一个 isRework 项」, 而**没有任何一层
  //    保证这个前提** —— 画布的 addOutputToProcess(processId, { rework: true }) 可以对同一工序点两次,
  //    后端也不校验。于是「一道工序配两个回料去向 (金属异物 / 非金属异物分开回收)」这个完全合法的
  //    配置下, 操作员两栏各填 30 / 20, semiOutputs 两条都正确入库, 而 defectQuantity = 20 ——
  //    **质量账少记 60% 且无任何提示**。
  //
  //    last-write-wins 是「明确的」, 但明确不等于对。「本道不良品总数」= 本道所有回料项之和,
  //    这才是这个字段的口径。所以这条断言从钉「取最后一条」改成钉「求和」。
  it('一道工序挂两个回料 Cell 且都填了数 → defectQuantity 是两条之和(本道不良品总数), ⛔ 不是只记最后一条', () => {
    const twoReworkOptions = [
      { taskId: 1, processName: '金检', semiCode: 'PTSEMI-JJ-REWORK', processOrder: 9, isRework: true },
      { taskId: 1, processName: '金检', semiCode: 'PTSEMI-JJ-REWORK-2', processOrder: 9, isRework: true },
    ];
    const r = buildSemiOutputsPayload(twoReworkOptions, {
      'PTSEMI-JJ-REWORK': '30',
      'PTSEMI-JJ-REWORK-2': '20',
    });
    // 两条产出都保留 (不会被静默吞掉)
    expect(r.semiOutputs).toHaveLength(2);
    expect(r.semiOutputs).toEqual(
      expect.arrayContaining([
        { semiCode: 'PTSEMI-JJ-REWORK', quantity: 30, isRework: true },
        { semiCode: 'PTSEMI-JJ-REWORK-2', quantity: 20, isRework: true },
      ]),
    );
    // 🔴 30 + 20 = 50。改动前这里是 20, 漏掉 30 (少记 60%), 而且不报错。
    expect(r.defectQuantity).toBe(50);
  });

  it('两个回料项都明确填 0 → defectQuantity 是 0 (确认零不良), ⛔ 不是 undefined', () => {
    // 求和不许把「都填了 0」折成「没填」—— 那正好把 2026-08-26 事故的两件事又合并回去。
    const twoReworkOptions = [
      { taskId: 1, processName: '金检', semiCode: 'R1', processOrder: 9, isRework: true },
      { taskId: 1, processName: '金检', semiCode: 'R2', processOrder: 9, isRework: true },
    ];
    const r = buildSemiOutputsPayload(twoReworkOptions, { R1: '0', R2: '0' });
    expect(r.defectQuantity).toBe(0);
    expect(r.semiOutputs).toBeUndefined();   // 量为 0 不生成入账条目
  });

  it('两个回料项只填了一个 → 只把填了的那个计入 (没填的不当 0 参与求和)', () => {
    const twoReworkOptions = [
      { taskId: 1, processName: '金检', semiCode: 'R1', processOrder: 9, isRework: true },
      { taskId: 1, processName: '金检', semiCode: 'R2', processOrder: 9, isRework: true },
    ];
    const r = buildSemiOutputsPayload(twoReworkOptions, { R2: '20' });
    expect(r.defectQuantity).toBe(20);
  });
});
