import { describe, expect, it } from 'vitest';
import { buildPayload, checkWeigh, fieldOfHintTarget, type WeighDraft } from '../weighForm';

/** 「现在」= 北京时间 2026-11-14 10:00（UTC 02:00）。 */
const NOW = new Date('2026-11-14T02:00:00Z');
const draft = (p: Partial<WeighDraft> = {}): WeighDraft => ({
  inputKg: '100', goodGrossKg: '82.5', tareKg: '2.5', defectKg: '3', date: '2026-11-14', start: '09:00', end: '09:40', ...p,
});

describe('称重表单：皮重与净重', () => {
  it('阳性：良品净重 = 毛重 − 皮重，出成率按净重算', () => {
    const c = checkWeigh(draft(), NOW);
    expect(c.ok).toBe(true);
    expect(c.netKg).toBe(80);
    expect(c.yieldRate).toBeCloseTo(0.8);
  });
  it('阴性：皮重大于毛重 ⇒ 皮重字段旁报错，不能保存', () => {
    const c = checkWeigh(draft({ goodGrossKg: '2', tareKg: '3' }), NOW);
    expect(c.ok).toBe(false);
    expect(c.errors.tareKg).toBe('皮重 3 kg 大于良品毛重 2 kg，请核对');
  });
  it('阴性：皮重负数 / 投入为 0 都拦', () => {
    expect(checkWeigh(draft({ tareKg: '-1' }), NOW).errors.tareKg).toBe('皮重不能是负数');
    expect(checkWeigh(draft({ inputKg: '0' }), NOW).errors.inputKg).toBe('投入重量要大于 0');
  });
  it('皮重不填按 0，提交时 tareWeight 为 null（不回填 0）', () => {
    const c = checkWeigh(draft({ tareKg: '' }), NOW);
    expect(c.netKg).toBe(82.5);
    expect(buildPayload(draft({ tareKg: '' }), 7, 'r1', c.netKg as number).tareWeight).toBeNull();
  });
});

describe('称重表单：R36 时间不晚于现在 5 分钟', () => {
  it('阴性：结束 10:06 ⇒ 结束字段旁「时间晚于现在」，不能保存', () => {
    const c = checkWeigh(draft({ end: '10:06' }), NOW);
    expect(c.ok).toBe(false);
    expect(c.errors.end).toContain('时间晚于现在');
    expect(c.errors.start).toBeUndefined();
  });
  it('阳性：结束 10:05（正好 5 分钟内）放行；昨天跨零点放行', () => {
    expect(checkWeigh(draft({ end: '10:05' }), NOW).ok).toBe(true);
    expect(checkWeigh(draft({ date: '2026-11-13', start: '22:00', end: '02:00' }), NOW).ok).toBe(true);
  });
  it('阴性：今天 22:00 → 次日 02:00 结束在未来；明天的日期也拦', () => {
    expect(checkWeigh(draft({ start: '09:00', end: '08:00' }), NOW).errors.end).toContain('时间晚于现在');
    expect(checkWeigh(draft({ date: '2026-11-15', start: '08:00', end: '08:30' }), NOW).ok).toBe(false);
  });
});

describe('称重表单：R34 数据疑点只提示不拦', () => {
  it('出成率 ≥100% ⇒ 毛重字段旁提示会标为数据疑点，但仍可保存', () => {
    const c = checkWeigh(draft({ goodGrossKg: '102.5', tareKg: '2.5' }), NOW);
    expect(c.ok).toBe(true);
    expect(c.warnings.goodGrossKg).toContain('数据疑点');
  });
  it('起止相同 ⇒ 结束字段旁提示，仍可保存', () => {
    const c = checkWeigh(draft({ start: '09:00', end: '09:00' }), NOW);
    expect(c.ok).toBe(true);
    expect(c.warnings.end).toContain('开始和结束时间相同');
  });
});

describe('提交载荷与服务端错误定位', () => {
  it('载荷：净重进 outputQuantity，皮重单列，单位 kg，不把任务置完工', () => {
    const p = buildPayload(draft(), 7, 'req-1', 80);
    expect(p).toMatchObject({
      workProcessTaskId: 7, clientRequestId: 'req-1', inputQuantity: 100, outputQuantity: 80, tareWeight: 2.5,
      defectQuantity: 3, inputUnit: 'kg', outputUnit: 'kg', businessDate: '2026-11-14', markComplete: false,
      laborSegments: [{ startTime: '09:00', endTime: '09:40' }],
    });
  });
  it('服务端 hintTarget 映射回字段', () => {
    expect(fieldOfHintTarget('laborSegments[0].endTime')).toBe('end');
    expect(fieldOfHintTarget('laborSegments[0].startTime')).toBe('start');
    expect(fieldOfHintTarget('tareWeight')).toBe('tareKg');
    expect(fieldOfHintTarget('whatever')).toBeNull();
  });
});
