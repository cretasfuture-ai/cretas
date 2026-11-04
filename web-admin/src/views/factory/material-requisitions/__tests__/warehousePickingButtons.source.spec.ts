import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * D-S315-03: 备料 / 确认领料 / 调拨是仓管的活, 这三个按钮认 warehouse 写权限;
 * 其余按钮 (按计划生成 / 签收 / 关单 / 取消) 范围不变, 与后端只放这三个动作一致。
 */
const source = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

function buttonGate(label: string): string {
  const line = source.split('\n').find((text) => text.includes('<el-button') && text.includes(`>${label}</el-button>`));
  if (!line) throw new Error(`找不到按钮: ${label}`);
  return line;
}

describe('领料单按钮权限', () => {
  it('显式判断 production 或 warehouse 写权限', () => {
    expect(source).toContain("permissionStore.canWrite('production') || permissionStore.canWrite('warehouse')");
  });

  it.each(['备料', '确认领料', '调拨'])('%s 用 canPick', (label) => {
    expect(buttonGate(label)).toContain('canPick');
  });

  it.each(['按计划生成', '签收', '关单', '取消'])('%s 仍用 canWrite', (label) => {
    const gate = buttonGate(label);
    expect(gate).toContain('canWrite');
    expect(gate).not.toContain('canPick');
  });
});
