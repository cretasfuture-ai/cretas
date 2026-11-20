import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { displayName } from './returnDisplay';

describe('采购退货名称列', () => {
  it('有名称时显示名称', () => {
    expect(displayName('青岛供应商')).toBe('青岛供应商');
  });
  it('没名称时为空, 不是 UUID', () => {
    expect(displayName(null)).toBe('');
    expect(displayName(undefined)).toBe('');
    expect(displayName('  ')).toBe('');
  });
  it('列表模板的供应商/源单列只用名称字段, 不引用 id', () => {
    const src = readFileSync(resolve(__dirname, 'list.vue'), 'utf-8');
    const tpl = src.slice(src.indexOf('<template>\n  <div class="page-wrapper">'));
    expect(tpl).not.toContain('counterpartyId');
    expect(tpl).not.toContain('sourceOrderId');
    expect(tpl).toContain('counterpartyName');
    expect(tpl).toContain('sourceOrderNumber');
  });
});
