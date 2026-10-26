import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 缺口 11: 待检成品批次在成品库存列表里必须显示「待检」, 不能按数量显示「充足」——
 * 后端不让它分配/发货, 列表却说充足, 仓管员会以为系统坏了。状态判断必须排在数量判断之前。
 */
const source = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

function body(fn: string): string {
  const start = source.indexOf(`function ${fn}(`);
  expect(start, `找不到 ${fn}`).toBeGreaterThan(0);
  return source.slice(start, source.indexOf('\n}', start));
}

describe('成品库存列表: 待检状态优先于数量', () => {
  it('statusLabel / statusType 先判待检再算数量', () => {
    const label = body('statusLabel');
    expect(label).toContain("'待检(质检放行后可发货)'");
    expect(label.indexOf('isPendingInspection')).toBeLessThan(label.indexOf('availableQty'));
    const type = body('statusType');
    expect(type.indexOf('isPendingInspection')).toBeLessThan(type.indexOf('availableQty'));
    expect(source).toContain("=== 'PENDING_INSPECTION'");
  });
});
