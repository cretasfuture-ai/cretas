import { describe, expect, it } from 'vitest';
import { allocationSourceLabel, allocationSourceTagType } from '../processSheetInputs';

// 「本次实际扣料」的来源标签: 回料 (手工 / 按配比自动) 此前都掉进 else 显示成「原料」。
describe('allocationSourceLabel', () => {
  it('回料两种来源各有自己的标签, 不再冒充原料', () => {
    expect(allocationSourceLabel('REWORK')).toBe('回料');
    expect(allocationSourceLabel('REWORK_AUTO')).toBe('回料·按配比自动');
    expect(allocationSourceTagType('REWORK_AUTO')).toBe('info');
  });

  it('阴性对照: 包材/调料/原料的既有文案不变', () => {
    expect(allocationSourceLabel('PACKAGING')).toBe('包材');
    expect(allocationSourceLabel('SEASONING')).toBe('调料');
    expect(allocationSourceLabel('RAW_MATERIAL')).toBe('原料');
    expect(allocationSourceLabel(undefined)).toBe('原料');
    expect(allocationSourceTagType('PACKAGING')).toBe('success');
    expect(allocationSourceTagType('RAW_MATERIAL')).toBe('warning');
  });
});
