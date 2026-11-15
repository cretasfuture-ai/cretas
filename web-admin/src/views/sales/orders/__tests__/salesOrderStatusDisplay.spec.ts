import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import {
  SALES_ORDER_STATUS_DISPLAY,
  salesOrderStatusText,
  salesOrderStatusTagType,
} from '../salesOrderStatusDisplay';

const listSource = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');

describe('销售订单状态显示', () => {
  it('与 list.vue 的 statusMap 逐项一致(文字与颜色), 且键集合相同', () => {
    const start = listSource.indexOf('const statusMap');
    expect(start, '找不到 statusMap —— 闸没量到东西').toBeGreaterThan(-1);
    const block = listSource.slice(start, listSource.indexOf('};', start));
    const re = /([A-Z_]+):\s*\{\s*text:\s*'([^']*)',\s*type:\s*'([^']*)'\s*\}/g;
    const fromList: Record<string, { text: string; type: string }> = {};
    for (const m of block.matchAll(re)) fromList[m[1]] = { text: m[2], type: m[3] };
    expect(Object.keys(fromList).length).toBeGreaterThan(5);
    expect(SALES_ORDER_STATUS_DISPLAY).toEqual(fromList);
  });

  it('每个已知状态显示中文且不含英文原码', () => {
    for (const [code, v] of Object.entries(SALES_ORDER_STATUS_DISPLAY)) {
      const t = salesOrderStatusText(code);
      expect(t).toBe(v.text);
      expect(t).not.toMatch(/[A-Z_]{4,}/);
    }
    expect(salesOrderStatusText('FINANCE_APPROVED')).toBe('审批已通过');
    expect(salesOrderStatusTagType('FINANCE_APPROVED')).toBe('success');
    expect(salesOrderStatusTagType('CANCELLED')).toBe('danger');
  });

  it('未知/空状态显示「未知状态」而不是原文', () => {
    for (const bad of ['SOME_NEW_STATUS', '', null, undefined]) {
      expect(salesOrderStatusText(bad)).toBe('未知状态');
    }
    expect(salesOrderStatusText('SOME_NEW_STATUS')).not.toContain('SOME_NEW_STATUS');
    expect(salesOrderStatusTagType('SOME_NEW_STATUS')).toBe('info');
  });
});
