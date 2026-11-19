import { describe, expect, it } from 'vitest';
import { equipmentStatusTagType, equipmentStatusText } from '../equipmentStatus';

describe('设备状态标签', () => {
  it('prod 实际出现的小写状态都显示中文（LSMDEMO 复测：scrapped 曾显示英文原文）', () => {
    expect(equipmentStatusText('scrapped')).toBe('已报废');
    expect(equipmentStatusText('active')).toBe('运行中');
    expect(equipmentStatusText('idle')).toBe('空闲');
    expect(equipmentStatusText('maintenance')).toBe('维护中');
    expect(equipmentStatusText('inactive')).toBe('停用');
  });

  it('认不出的状态原样显示，不猜', () => {
    expect(equipmentStatusText('weird_state')).toBe('weird_state');
    expect(equipmentStatusTagType('weird_state')).toBe('info');
  });
});
