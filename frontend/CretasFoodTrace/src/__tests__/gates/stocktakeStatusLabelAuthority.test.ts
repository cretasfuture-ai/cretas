import fs from 'node:fs';
import path from 'node:path';

const SCREEN = fs.readFileSync(
  path.resolve(__dirname, '../../screens/warehouse/inventory/WHStocktakeListScreen.tsx'),
  'utf8',
);

describe('盘点状态中文标签只有后端一份权威', () => {
  it('列表显示 DTO.statusLabel，颜色表不再复制中文标签', () => {
    const colorMap = SCREEN.match(/const STATUS_META:[\s\S]*?\n};/)?.[0];
    if (!colorMap) throw new Error('STATUS_META 没读到，下面的“不含 label”会变成空对空假绿');
    expect(colorMap).not.toContain('label:');
    expect(SCREEN).toContain('{item.statusLabel}');
  });
});
