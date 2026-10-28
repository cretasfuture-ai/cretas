import { describe, expect, it } from 'vitest';
import { isSecretHidden, summarizeBatch, toMaterialDetail } from '../bomAchievementSummary';

/**
 * 工艺保密 (独立审查 V2.1 M4): 保密产品的批次里, 自动带出的调料实际量由后端置 null。
 * 按 0 加进合计会把「看不到」显示成「少用了」—— 达成率和偏差都成了假数。
 */
describe('BOM 达成率汇总遇到工艺保密隐藏的实际量', () => {
  const beef = { materialTypeName: '牛肉', plannedQty: 115, actualQty: 115, unit: 'kg' };
  // 后端裁剪后的形状: 字段在、值为 null, 并带 processSecret 标记
  const cinnamon = {
    materialTypeName: '桂皮', plannedQty: 0.1725, actualQty: null, unit: 'kg',
    processSecret: { visibility: 'TOTALS_ONLY' },
  };

  it('批次合计与达成率不给, 并标出已保密 (而不是按 0 算出 99.9%)', () => {
    const summary = summarizeBatch([beef, cinnamon]);
    expect(summary.secretHidden).toBe(true);
    expect(summary.actualTotal).toBeNull();
    expect(summary.achievementRate).toBeUndefined();
    expect(summary.plannedTotal).toBe(115.17);
  });

  it('阴性对照: 没有隐藏量时照常算合计和达成率', () => {
    const summary = summarizeBatch([beef, { ...cinnamon, actualQty: 0.1725 }]);
    expect(summary.secretHidden).toBe(false);
    expect(summary.actualTotal).toBe(115.17);
    expect(summary.achievementRate).toBe(100);
  });

  it('展开明细: 隐藏的那一行实际量和偏差都是 null, 人工投料照常', () => {
    expect(toMaterialDetail(cinnamon)).toMatchObject({ materialName: '桂皮', actual: null, variance: null });
    expect(toMaterialDetail({ ...beef, actualQty: 110 })).toMatchObject({ actual: 110, variance: -5 });
  });

  it('缺字段的旧形状不算隐藏 (仍按 0)', () => {
    expect(isSecretHidden({ plannedQty: 3 })).toBe(false);
    expect(toMaterialDetail({ plannedQty: 3 })).toMatchObject({ actual: 0, variance: -3 });
  });
});
