import { readFileSync } from 'node:fs';
import { sourceWithChinese } from '@/i18n-pilot/sourceForTests';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = sourceWithChinese(readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8'));

describe('sales order positive price guard', () => {
  it('rejects zero and negative prices in both create and edit before submission', () => {
    expect(source.match(/Number\(i\.unitPrice\) <= 0/g)).toHaveLength(2);
    expect(source.match(/销售单价必须大于 0/g)).toHaveLength(2);
    expect(source).not.toContain('Number(i.unitPrice) < 0');
  });
});
